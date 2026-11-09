#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f deploy/.env ]]; then echo "Missing deploy/.env" >&2; exit 1; fi
set -a
source deploy/.env
set +a
COMPOSE=(docker compose -f deploy/docker-compose.yml --env-file deploy/.env)
# 本入口显式授权真实 OSS/FTP 写与本地旧业务数据放弃；仅 V14 以下数据库需要第一阶段授权。
VERSION="$("${COMPOSE[@]}" exec -T postgres psql -U archive -d archive_delivery -Atc \
  "SELECT COALESCE((SELECT version FROM archive_delivery.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1),'0')" 2>/dev/null || true)"
if ((10#${VERSION:-0} < 14)); then
  ARCHIVE_UPGRADE_AUTHORIZED=2026-11-06 ./scripts/mvp-up.sh
  for attempt in $(seq 1 90); do
    VERSION="$("${COMPOSE[@]}" exec -T postgres psql -U archive -d archive_delivery -Atc \
      "SELECT COALESCE((SELECT version FROM archive_delivery.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1),'0')" 2>/dev/null || true)"
    [[ "$VERSION" == "13" ]] && break
    [[ "$attempt" -lt 90 ]] || { echo "V13 migration did not complete" >&2; exit 1; }
    sleep 2
  done
fi
ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07 ./scripts/mvp-up.sh
for attempt in $(seq 1 60); do
  if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then break; fi
  [[ "$attempt" -lt 60 ]] || { echo "Archive API readiness timed out" >&2; exit 1; }
  sleep 3
done
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh
ARCHIVE_ALLOW_TEST_DATA_RESET=true ARCHIVE_CONFIRM_LOCAL_TEST_RESET=archive_delivery ./scripts/reset-test-data.sh
"${COMPOSE[@]}" up -d archive-server worker-1 worker-2 worker-3
./scripts/preflight.sh

TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/ingest-sources -o "$TEMP/sources.json"
curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/delivery-entities -o "$TEMP/entities.json"
DELIVERY_DATE="$(python3 -c 'from datetime import datetime,timedelta; print((datetime.now().astimezone()+timedelta(days=1)).date().isoformat())')"
NEXT_DELIVERY_DATE="$(python3 -c 'from datetime import datetime,timedelta; print((datetime.now().astimezone()+timedelta(days=2)).date().isoformat())')"
BATCH="real-e2e-$(date +%s)"
python3 - "$TEMP" "$DELIVERY_DATE" "$BATCH" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
definition = json.loads(pathlib.Path("deploy/pipeline.local.json").read_text())
source_config = next(node["config"] for node in definition["nodes"] if node["type"] == "oss.download")
expected_uri = "oss://" + source_config["bucket"] + "/" + source_config["prefix"]
sources = [row for row in json.loads((root / "sources.json").read_text()) if row["ossUri"] == expected_uri]
entities = sorted(json.loads((root / "entities.json").read_text()), key=lambda row: row["entityCode"])
assert len(sources) == 1 and len(entities) >= 2 and entities[0]["vendorCode"] == entities[1]["vendorCode"]
request = {"ossUri": sources[0]["ossUri"], "pipelineCode": "archive-delivery", "deliveryDate": sys.argv[2],
           "deliveryBatch": sys.argv[3],
           "targets": [{"targetCount": 1, "vendorCode": entities[0]["vendorCode"], "entityCode": entities[0]["entityCode"]},
                       {"targetCount": 1, "vendorCode": entities[1]["vendorCode"], "entityCode": entities[1]["entityCode"]}]}
(root / "submit.json").write_text(json.dumps(request))
print(",".join(target["entityCode"] for target in request["targets"]))
PY
ENTITIES="$(python3 -c 'import json,sys; print(",".join(row["entityCode"] for row in json.load(sys.stdin)["targets"]))' < "$TEMP/submit.json")"
"${COMPOSE[@]}" stop ftp-entity-1 ftp-entity-2 >/dev/null
curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary "@$TEMP/submit.json" \
  http://127.0.0.1:8080/api/v1/deliveries -o "$TEMP/response.json"
DELIVERY_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$TEMP/response.json")"
echo "Submitted real deliveryId=$DELIVERY_ID entities=$ENTITIES packageBounds=8388608-10485760 twoGroups=1+1"

TASKS_URL="http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/tasks"
wait_blocked() {
  local output="$1"
  for attempt in $(seq 1 120); do
    curl -fsS --connect-timeout 5 --max-time 15 "$TASKS_URL" -o "$output"
    if python3 - "$output" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))["items"]
ready = len(rows) == 2 and all(row["executionStatus"] == "BLOCKED" and row["currentNodeId"] == "ftpDownloadVerify" for row in rows)
raise SystemExit(0 if ready else 1)
PY
    then return 0; fi
    [[ "$attempt" -lt 120 ]] || { cat "$output"; return 1; }
    sleep 5
  done
}
wait_blocked "$TEMP/blocked-old.json"
OLD_TASK_IDS="$(python3 -c 'import json,sys; print(",".join(str(row["taskId"]) for row in json.load(sys.stdin)["items"]))' < "$TEMP/blocked-old.json")"
curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d '{"reason":"real acceptance node-boundary pause"}' "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/pause" >/dev/null
PAUSED_STATUS="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
[[ "$PAUSED_STATUS" == "PAUSED" ]] || { echo "Expected PAUSED, got $PAUSED_STATUS" >&2; exit 1; }
curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary "@$TEMP/submit.json" \
  http://127.0.0.1:8080/api/v1/deliveries -o "$TEMP/resumed.json"
RESUMED_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$TEMP/resumed.json")"
[[ "$RESUMED_ID" == "$DELIVERY_ID" ]] || { echo "Batch continuation created a different delivery" >&2; exit 1; }
wait_blocked "$TEMP/blocked-resumed.json"
echo "Paused and resumed deliveryId=$DELIVERY_ID by identical deliveryBatch"

curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d "{\"expectedDeliveryDate\":\"$DELIVERY_DATE\",\"deliveryDate\":\"$NEXT_DELIVERY_DATE\",\"reason\":\"real acceptance next-day task regeneration\"}" \
  "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/rollover" >/dev/null
PARTIAL_STATUS="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
[[ "$PARTIAL_STATUS" == "PARTIALLY_DELIVERED" ]] || { echo "Expected PARTIALLY_DELIVERED after rollover, got $PARTIAL_STATUS" >&2; exit 1; }
wait_blocked "$TEMP/blocked-new.json"
NEW_TASK_IDS="$(python3 -c 'import json,sys; print(",".join(str(row["taskId"]) for row in json.load(sys.stdin)["items"]))' < "$TEMP/blocked-new.json")"
"${COMPOSE[@]}" start ftp-entity-1 ftp-entity-2 >/dev/null
python3 - "$TEMP/blocked-new.json" <<'PY' > "$TEMP/retry.txt"
import json, sys
for row in json.load(open(sys.argv[1]))["items"]:
    print(row["taskId"], row["fence"])
PY
while read -r TASK_ID FENCE; do
  curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
    -d "{\"expectedFence\":$FENCE,\"reason\":\"real acceptance single-task FTP retry\"}" \
    "http://127.0.0.1:8080/api/v1/tasks/$TASK_ID/retry" >/dev/null
done < "$TEMP/retry.txt"
echo "Rollover=$OLD_TASK_IDS->$NEW_TASK_IDS; retried each blocked task after restoring FTP"

SUMMARY_URL="http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=$DELIVERY_ID"
for attempt in $(seq 1 120); do
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$SUMMARY_URL")"
  read -r STATUS SUCCEEDED BLOCKED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["deliveryStatus"],s["succeeded"],s["blocked"])' <<<"$SUMMARY")"
  [[ "$STATUS" == "DELIVERED" && "$SUCCEEDED" -eq 2 ]] && break
  [[ "$BLOCKED" -eq 0 ]] || { echo "$SUMMARY"; echo "Unexpected blocked task after retry" >&2; exit 1; }
  [[ "$attempt" -lt 120 ]] || { echo "$SUMMARY"; echo "Real delivery observation window expired" >&2; exit 1; }
  sleep 5
done
./scripts/verify-delivery.sh "$DELIVERY_ID"

REUSE_BATCH="$BATCH-reuse"
python3 - "$TEMP" "$DELIVERY_DATE" "$REUSE_BATCH" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
original = json.loads((root / "submit.json").read_text())
entities = sorted(json.loads((root / "entities.json").read_text()), key=lambda row: row["entityCode"])
target = entities[2]
assert target["vendorCode"] != original["targets"][0]["vendorCode"]
request = {"ossUri": original["ossUri"], "pipelineCode": original["pipelineCode"], "deliveryDate": sys.argv[2],
           "deliveryBatch": sys.argv[3],
           "targets": [{"targetCount": 1, "vendorCode": target["vendorCode"], "entityCode": target["entityCode"]}]}
(root / "reuse-submit.json").write_text(json.dumps(request))
PY
curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary "@$TEMP/reuse-submit.json" \
  http://127.0.0.1:8080/api/v1/deliveries -o "$TEMP/reuse-response.json"
REUSE_DELIVERY_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$TEMP/reuse-response.json")"
REUSE_SUMMARY_URL="http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=$REUSE_DELIVERY_ID"
for attempt in $(seq 1 120); do
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$REUSE_SUMMARY_URL")"
  read -r STATUS SUCCEEDED BLOCKED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["deliveryStatus"],s["succeeded"],s["blocked"])' <<<"$SUMMARY")"
  [[ "$STATUS" == "DELIVERED" && "$SUCCEEDED" -eq 1 ]] && break
  [[ "$BLOCKED" -eq 0 ]] || { echo "$SUMMARY"; echo "Unexpected blocked cache-reuse task" >&2; exit 1; }
  [[ "$attempt" -lt 120 ]] || { echo "$SUMMARY"; echo "Cache-reuse observation window expired" >&2; exit 1; }
  sleep 5
done
curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/deliveries/$REUSE_DELIVERY_ID/tasks" -o "$TEMP/reuse-tasks.json"
while read -r TASK_ID; do
  curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/tasks/$TASK_ID/timeline" -o "$TEMP/reuse-timeline-$TASK_ID.json"
done < <(python3 -c 'import json,sys; print("\n".join(str(row["taskId"]) for row in json.load(sys.stdin)["items"]))' < "$TEMP/reuse-tasks.json")
python3 - "$TEMP" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
tasks = json.loads((root / "reuse-tasks.json").read_text())["items"]
assert len(tasks) == 1 and tasks[0]["entityCode"] == "jun3"
for task in tasks:
    assert 8388608 <= task["sizeBytes"] <= 10485760, f"Reuse package is outside 8-10 MiB: {task['sizeBytes']}"
    assert len(task["artifacts"]) == len(task["sourceDataIds"])
    assert task["metadataIds"] == task["sourceDataIds"]
    for pair, data_id in zip(task["artifacts"], task["metadataIds"]):
        assert set(pair) == {"dataId", "sourceArtifact"}
        assert pair["dataId"] == data_id
        assert isinstance(data_id, int) and data_id > 0
    timeline = json.loads((root / f"reuse-timeline-{task['taskId']}.json").read_text())
    repair = next(node for node in timeline["nodes"] if node["nodeType"] == "python.repair" and node["status"] == "SUCCEEDED")
    assert repair["outputLocator"]["metadataIds"] == task["metadataIds"]
    assert repair["outputLocator"]["requestCount"] > 0
    assert repair["outputLocator"]["sizeBytes"] == task["sizeBytes"]
    assert repair["outputLocator"]["sha256"] == task["sha256"]
    download = next(node for node in timeline["nodes"] if node["nodeType"] == "oss.download" and node["status"] == "SUCCEEDED")
    sources = download["outputLocator"]["sources"]
    assert sources and all(source["cacheHit"] is True for source in sources), "Reassigned sources must reuse the verified local cache"
    assert 8388608 <= sum(source["sizeBytes"] for source in sources) <= 10485760
print(f"CACHE REUSE PASSED deliveryId={task['deliveryId']} taskId={task['taskId']} sourceCount={len(sources)} metadataCount={len(task['metadataIds'])} requestCount={repair['outputLocator']['requestCount']} cacheHit=true size={task['sizeBytes']}")
PY
echo "Real E2E passed: deliveryId=$DELIVERY_ID, mergedTasks=$NEW_TASK_IDS, packageBounds=8-10MiB, sameVendor=alibaba/jun+jun2, pause/resume/retry/rollover DELIVERED; reuseDeliveryId=$REUSE_DELIVERY_ID cacheHit=true."
