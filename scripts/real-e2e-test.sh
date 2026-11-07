#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f deploy/.env ]]; then echo "Missing deploy/.env" >&2; exit 1; fi
set -a
source deploy/.env
set +a
COMPOSE=(docker compose -f deploy/docker-compose.yml --env-file deploy/.env)
# 本入口本身就是显式真实 OSS 写授权与旧本地数据放弃确认；普通启动不会携带这些变量。
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

# 两个实体暂时停止 FTP，分别制造真实单任务 retry 与未完成任务 rollover；第三实体保持正常对照。
"${COMPOSE[@]}" stop ftp-entity-1 ftp-entity-2 >/dev/null
VERSION_ID="$(curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/pipelines/archive-delivery/versions | python3 -c 'import json,sys; rows=json.load(sys.stdin); print(max(row["id"] for row in rows if row["status"]=="PUBLISHED"))')"
SOURCE_ID="$(curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/ingest-sources | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert len(rows)==1; print(rows[0]["sourceId"])')"
DELIVERY_DATE="$(date +%F)"
NEXT_DELIVERY_DATE="$(python3 -c 'from datetime import datetime,timedelta; print((datetime.now().astimezone()+timedelta(days=1)).date().isoformat())')"
CUTOFF="$(python3 -c 'from datetime import datetime,timedelta; print((datetime.now().astimezone()+timedelta(hours=6)).isoformat(timespec="seconds"))')"
KEY="real-e2e-$(date +%s)"
RESPONSE="$(./scripts/submit-delivery.sh "$SOURCE_ID" "$VERSION_ID" "$DELIVERY_DATE" "$CUTOFF" "BATCH-$KEY" "$KEY")"
DELIVERY_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' <<<"$RESPONSE")"
echo "Submitted real deliveryId=$DELIVERY_ID"

TASKS_URL="http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/tasks"
for attempt in $(seq 1 90); do
  TASKS="$(curl -fsS --connect-timeout 5 --max-time 15 "$TASKS_URL")"
  read -r RETRY_ID RETRY_FENCE ROLLOVER_ID <<<"$(python3 -c 'import json,sys; rows=sorted(json.load(sys.stdin)["items"],key=lambda r:r["deliveryEntityId"]); a=rows[0] if len(rows)>0 else {}; b=rows[1] if len(rows)>1 else {}; ready=a.get("executionStatus")=="BLOCKED" and a.get("currentNodeId")=="ftpDownloadVerify" and b.get("executionStatus")=="BLOCKED" and b.get("currentNodeId")=="ftpDownloadVerify"; print(a.get("taskId",0) if ready else 0,a.get("fence",0) if ready else 0,b.get("taskId",0) if ready else 0)' <<<"$TASKS")"
  if [[ "$RETRY_ID" -gt 0 && "$ROLLOVER_ID" -gt 0 ]]; then break; fi
  if [[ "$attempt" -eq 90 ]]; then echo "$TASKS"; echo "Timed out waiting for the two controlled FTP failures" >&2; exit 1; fi
  sleep 5
done
"${COMPOSE[@]}" start ftp-entity-1 >/dev/null
curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d "{\"expectedFence\":$RETRY_FENCE,\"reason\":\"real acceptance single-task FTP retry\"}" \
  "http://127.0.0.1:8080/api/v1/tasks/$RETRY_ID/retry" >/dev/null
echo "Retried only taskId=$RETRY_ID after restoring its FTP account"
for attempt in $(seq 1 60); do
  STATUS="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/tasks/$RETRY_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["executionStatus"])')"
  [[ "$STATUS" == "SUCCEEDED" ]] && break
  [[ "$STATUS" != "BLOCKED" ]] || { echo "Retried task became BLOCKED" >&2; exit 1; }
  [[ "$attempt" -lt 60 ]] || { echo "Retried task did not succeed" >&2; exit 1; }
  sleep 5
done
OLD_ROLLOVER_ID="$ROLLOVER_ID"
curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d "{\"expectedDeliveryDate\":\"$DELIVERY_DATE\",\"deliveryDate\":\"$NEXT_DELIVERY_DATE\",\"reason\":\"real acceptance next-day task regeneration\"}" \
  "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/rollover" >/dev/null
PARTIAL_STATUS="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
[[ "$PARTIAL_STATUS" == "PARTIALLY_DELIVERED" ]] || { echo "Expected PARTIALLY_DELIVERED after rollover, got $PARTIAL_STATUS" >&2; exit 1; }
NEW_ROLLOVER_ID="$(curl -fsS --connect-timeout 5 --max-time 15 "$TASKS_URL" | python3 -c 'import json,sys; rows=sorted(json.load(sys.stdin)["items"],key=lambda r:r["deliveryEntityId"]); print(rows[1]["taskId"])')"
[[ "$NEW_ROLLOVER_ID" != "$OLD_ROLLOVER_ID" ]] || { echo "Rollover did not create a new taskId" >&2; exit 1; }
"${COMPOSE[@]}" start ftp-entity-2 >/dev/null
echo "Rollover created next-day taskId=$NEW_ROLLOVER_ID from EXPIRED taskId=$OLD_ROLLOVER_ID"

SUMMARY_URL="http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=$DELIVERY_ID"
for attempt in $(seq 1 120); do
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$SUMMARY_URL")"
  read -r STATUS SUCCEEDED BLOCKED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["deliveryStatus"],s["succeeded"],s["blocked"])' <<<"$SUMMARY")"
  [[ "$STATUS" == "DELIVERED" && "$SUCCEEDED" -eq 3 ]] && break
  [[ "$BLOCKED" -eq 0 ]] || { echo "$SUMMARY"; echo "Unexpected blocked task after retry/rollover" >&2; exit 1; }
  [[ "$attempt" -lt 120 ]] || { echo "$SUMMARY"; echo "Real delivery observation window expired" >&2; exit 1; }
  sleep 5
done
./scripts/verify-delivery.sh "$DELIVERY_ID"
echo "Real E2E passed: deliveryId=$DELIVERY_ID, retryTaskId=$RETRY_ID, rollover=$OLD_ROLLOVER_ID->$NEW_ROLLOVER_ID, 3/3 DELIVERED."
