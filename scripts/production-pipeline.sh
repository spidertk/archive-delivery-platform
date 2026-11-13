#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f deploy/.env ]]; then
  echo "Missing deploy/.env; use deploy/env.production.example and configure controlled credentials." >&2
  exit 1
fi
set -a
source deploy/.env
set +a
PIPELINE_CASE="${PIPELINE_CASE:-archive-delivery-jun}"
eval "$(python3 scripts/pipeline_registry.py env "$PIPELINE_CASE")"
python3 scripts/pipeline_registry.py materialize "$PIPELINE_CASE"
export PROD_DELIVERY_BATCH="${PROD_DELIVERY_BATCH:-$REGISTRY_DELIVERY_BATCH}"
COMPOSE=(docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env)
NETWORK="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}_default"
DATA_VOLUME="${COMPOSE_PROJECT_NAME}_archive_data"
API="http://127.0.0.1:8080/api/v1"

wait_api() {
  for attempt in $(seq 1 90); do
    if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then return 0; fi
    [[ "$attempt" -lt 90 ]] || { echo "Archive API readiness timed out" >&2; return 1; }
    sleep 3
  done
}

flyway_version() {
  "${COMPOSE[@]}" exec -T postgres psql -U "$ARCHIVE_DB_USER" -d archive_delivery -Atc \
    "SELECT COALESCE((SELECT max(version::int) FROM archive_delivery.flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),0);" \
    2>/dev/null || printf '0\n'
}

wait_flyway() {
  local expected="$1" version
  for attempt in $(seq 1 90); do
    version="$(flyway_version)"
    [[ "$version" == "$expected" ]] && return 0
    [[ "$attempt" -lt 90 ]] || { echo "Flyway did not reach V$expected; current V$version" >&2; return 1; }
    sleep 2
  done
}

start_stack() {
  "${COMPOSE[@]}" up -d --build --remove-orphans
}

run_external_test() {
  local mode="$1" delivery_id="${2:-}"
  docker build -t archive-delivery-maven:test -f deploy/Dockerfile.test deploy >/dev/null
  docker run --rm --network "$NETWORK" \
    -e ARCHIVE_VERIFICATION_PROFILE=production \
    -e ARCHIVE_VERIFY_EXPECTED_TASKS="${PROD_TARGET_COUNT:-1}" \
    -e ARCHIVE_VERIFY_EXPECTED_ENTITIES="${PROD_ENTITY_COUNT:-1}" \
    -e "$mode=true" \
    -e ARCHIVE_VERIFY_DELIVERY_ID="$delivery_id" \
    -e ARCHIVE_DB_URL='jdbc:postgresql://postgres:5432/archive_delivery?currentSchema=archive_delivery' \
    -e ARCHIVE_DB_USER="$ARCHIVE_DB_USER" -e ARCHIVE_DB_PASSWORD="$ARCHIVE_DB_PASSWORD" \
    -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -v "$DATA_VOLUME":/data:ro -w /workspace \
    archive-delivery-maven:test mvn -q -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dtest=RealExternalVerificationTest#$3" test
}

"${COMPOSE[@]}" up -d postgres
for attempt in $(seq 1 45); do
  if "${COMPOSE[@]}" exec -T postgres pg_isready -U "$ARCHIVE_DB_USER" -d archive_delivery >/dev/null 2>&1; then break; fi
  [[ "$attempt" -lt 45 ]] || { echo "PostgreSQL readiness timed out" >&2; exit 1; }
  sleep 2
done
VERSION="$(flyway_version)"
if ((10#$VERSION > 0 && 10#$VERSION < 14)); then
  echo "Existing pre-V14 production database requires an independent backup and explicit upgrade authorization; no tables were changed." >&2
  exit 1
fi
# 空库只执行 V24 基线；已有 V23 数据库幂等升级，不重复建表或修改业务事实。
start_stack
wait_flyway 24
# 初始化通用调度和不可变 Pipeline。
"${COMPOSE[@]}" up -d --no-build
wait_api
./scripts/bootstrap-powerjob.sh
./scripts/configure-production-pipeline.sh

# 预检真实读取源/目标 OSS 并登录本批 FTP；失败时 set -e 直接阻断提交。
run_external_test ARCHIVE_PREFLIGHT "" preflight

DELIVERY_DATE="${PROD_DELIVERY_DATE:-$(python3 -c 'from datetime import datetime,timedelta; print((datetime.now().astimezone()+timedelta(days=1)).date().isoformat())')}"
DELIVERY_BATCH="${PROD_DELIVERY_BATCH:-PROD-JUN-$(date +%Y%m%d-%H%M%S)}"
TARGET_COUNT="${PROD_TARGET_COUNT:-1}"
EVIDENCE_DIR="${PROD_EVIDENCE_ROOT:-$ROOT/docs/evidence}/$DELIVERY_BATCH"
mkdir -p "$EVIDENCE_DIR"
SUBMIT_JSON="$EVIDENCE_DIR/submit.json"
python3 - "$SUBMIT_JSON" "$DELIVERY_DATE" "$DELIVERY_BATCH" "$TARGET_COUNT" <<'PY'
import json, os, sys
path, date, batch, count = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
definition = json.load(open('deploy/pipeline.production.json'))
golden = json.load(open('deploy/golden-case.json'))
source = next(node['config'] for node in definition['nodes'] if node['type'] == 'oss.download')
request = {
    'ossUri': 'oss://' + source['bucket'] + '/' + source['prefix'],
    'pipelineCode': golden['pipelineCode'],
    'deliveryDate': date,
    'deliveryBatch': batch,
    'targets': [{'targetCount': target['targetCount'], 'vendorCode': target['vendorCode'],
                 'entityCode': target['entityCode']} for target in golden['targets']]
}
json.dump(request, open(path, 'w'), ensure_ascii=False, indent=2)
PY
curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' \
  --data-binary "@$SUBMIT_JSON" "$API/deliveries" -o "$EVIDENCE_DIR/submit-response.json"
DELIVERY_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$EVIDENCE_DIR/submit-response.json")"
echo "Submitted production deliveryId=$DELIVERY_ID batch=$DELIVERY_BATCH target=${PROD_ENTITY_1_VENDOR_CODE}/${PROD_ENTITY_1_ENTITY_CODE} count=$TARGET_COUNT"

SUMMARY_URL="$API/dashboard/summary?deliveryId=$DELIVERY_ID"
DEADLINE=$((SECONDS + ${PROD_PIPELINE_OBSERVATION_SECONDS:-1800}))
while ((SECONDS < DEADLINE)); do
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$SUMMARY_URL")"
  read -r STATUS SUCCEEDED BLOCKED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["deliveryStatus"],s["succeeded"],s["blocked"])' <<<"$SUMMARY")"
  if [[ "$STATUS" == "DELIVERED" && "$SUCCEEDED" -eq "$TARGET_COUNT" ]]; then break; fi
  [[ "$BLOCKED" -eq 0 ]] || { printf '%s\n' "$SUMMARY" > "$EVIDENCE_DIR/failure-summary.json"; echo "Production delivery is BLOCKED; evidence=$EVIDENCE_DIR" >&2; exit 1; }
  sleep 5
done
SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$SUMMARY_URL")"
printf '%s\n' "$SUMMARY" > "$EVIDENCE_DIR/delivery-summary.json"
python3 - "$SUMMARY" "$TARGET_COUNT" <<'PY'
import json, sys
summary = json.loads(sys.argv[1])
assert summary['deliveryStatus'] == 'DELIVERED' and summary['succeeded'] == int(sys.argv[2]), summary
PY

run_external_test ARCHIVE_REAL_E2E "$DELIVERY_ID" verifyRealDelivery | tee "$EVIDENCE_DIR/external-verification.log"
curl -fsS --connect-timeout 5 --max-time 15 "$API/deliveries/$DELIVERY_ID" -o "$EVIDENCE_DIR/delivery-api.json"
curl -fsS --connect-timeout 5 --max-time 15 "$API/deliveries/$DELIVERY_ID/tasks" -o "$EVIDENCE_DIR/tasks-api.json"
"${COMPOSE[@]}" ps > "$EVIDENCE_DIR/compose-services.txt"
"${COMPOSE[@]}" logs --no-color --since 2h archive-server worker-1 > "$EVIDENCE_DIR/application.log"
python3 - "$DELIVERY_DATE" "$DELIVERY_BATCH" "$EVIDENCE_DIR" <<'PY'
import json, sys
from hashlib import sha256
from pathlib import Path
root = Path(sys.argv[3])
golden = json.load(open('deploy/golden-case.json'))
sha_lines, partial_lines = [], []
for target in golden['targets']:
    directory = Path(target['hostRoot']) / sys.argv[1] / sys.argv[2]
    files = sorted(directory.glob('batch_*.tar.gz'))
    assert len(files) == target['targetCount'], f"FTP artifact count differs for {target['entityCode']}: {len(files)}"
    for path in files:
        digest = sha256(path.read_bytes()).hexdigest()
        sha_lines.append(f"{digest}  {path}")
    partial_lines.extend(str(path) for path in directory.glob('*.partial'))
(root / 'ftp-sha256.txt').write_text('\n'.join(sha_lines) + ('\n' if sha_lines else ''))
(root / 'ftp-partial.txt').write_text('\n'.join(partial_lines) + ('\n' if partial_lines else ''))
assert not partial_lines, f"FTP .partial files remain: {partial_lines}"
PY
"${COMPOSE[@]}" exec -T postgres psql -U "$ARCHIVE_DB_USER" -d archive_delivery -P pager=off \
  -c "SELECT version,success,installed_on FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;" \
  -c "SELECT delivery_id,delivery_batch,delivery_date,cutoff_at,status,pipeline_version_id FROM archive_delivery.delivery_submit WHERE delivery_id=$DELIVERY_ID;" \
  -c "SELECT task_id,entity_code,worker_group,execution_status,new_file_name,size_bytes FROM archive_delivery.delivery_task WHERE delivery_id=$DELIVERY_ID ORDER BY task_id;" \
  -c "SELECT data_id,original_file_name,size_bytes,sha256,artifact,created_at,updated_at FROM archive_delivery.source_data WHERE delivery_id=$DELIVERY_ID ORDER BY data_id;" \
  -c "SELECT task_id,data_id,source_seq FROM archive_delivery.delivery_mapping WHERE delivery_id=$DELIVERY_ID ORDER BY task_id,source_seq;" \
  -c "SELECT process_id,task_id,node_id,status,worker_instance_id,output_locator FROM archive_delivery.delivery_node WHERE delivery_id=$DELIVERY_ID ORDER BY process_id;" \
  > "$EVIDENCE_DIR/database-facts.txt"
printf 'Production verification passed deliveryId=%s batch=%s evidence=%s\n' "$DELIVERY_ID" "$DELIVERY_BATCH" "$EVIDENCE_DIR"
