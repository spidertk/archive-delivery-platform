#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f deploy/.env ]]; then
  echo "Missing deploy/.env; use deploy/env.production.example and configure controlled credentials." >&2
  exit 1
fi
CLI_OVERRIDES=(
  "ARCHIVE_MAX_RUNNING_TASKS=${ARCHIVE_MAX_RUNNING_TASKS:-}"
  "ARCHIVE_PREPARATION_DOWNLOAD_THREADS=${ARCHIVE_PREPARATION_DOWNLOAD_THREADS:-}"
  "ARCHIVE_TASK_LEASE_SECONDS=${ARCHIVE_TASK_LEASE_SECONDS:-}"
  "ARCHIVE_CLEANUP_WORK_AFTER_VERIFY=${ARCHIVE_CLEANUP_WORK_AFTER_VERIFY:-}"
  "PROD_TARGET_COUNT=${PROD_TARGET_COUNT:-}"
  "PROD_OSS_URI=${PROD_OSS_URI:-}"
  "PROD_DELIVERY_BATCH=${PROD_DELIVERY_BATCH:-}"
  "PROD_DELIVERY_DATE=${PROD_DELIVERY_DATE:-}"
  "PROD_SUBMISSION_COUNT=${PROD_SUBMISSION_COUNT:-}"
  "PROD_VERIFY_ALLOW_CLEANED_WORK=${PROD_VERIFY_ALLOW_CLEANED_WORK:-}"
  "PROD_PIPELINE_OBSERVATION_SECONDS=${PROD_PIPELINE_OBSERVATION_SECONDS:-}"
)
set -a
source deploy/.env
set +a
for override in "${CLI_OVERRIDES[@]}"; do
  name="${override%%=*}"
  value="${override#*=}"
  if [[ -n "$value" ]]; then export "$name=$value"; fi
done
# 旧受控配置未声明 profile 时保持 jun 内置 FTP；显式空值表示复用已有外部 FTP。
if [[ -z "${COMPOSE_PROFILES+x}" ]]; then export COMPOSE_PROFILES=embedded-ftp; fi
PIPELINE_CASE="${PIPELINE_CASE:-archive-batch-jun}"
eval "$(python3 scripts/pipeline_registry.py env "$PIPELINE_CASE")"
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

run_external_test() {
  local mode="$1" delivery_id="${2:-}" expected_tasks="${4:-$PROD_TARGET_COUNT}"
  docker build -t archive-delivery-maven:test -f deploy/Dockerfile.test deploy >/dev/null
  docker run --rm --network "$NETWORK" \
    -e ARCHIVE_VERIFICATION_PROFILE=production \
    -e ARCHIVE_VERIFY_EXPECTED_TASKS="$expected_tasks" \
    -e ARCHIVE_VERIFY_EXPECTED_ENTITIES="${PROD_ENTITY_COUNT:-1}" \
    -e ARCHIVE_VERIFY_SOURCE_URI="$PROD_OSS_URI" \
    -e ARCHIVE_VERIFY_ALLOW_CLEANED_WORK="${PROD_VERIFY_ALLOW_CLEANED_WORK:-true}" \
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
if [[ "${PROD_REUSE_SERVICES:-false}" == "true" ]]; then
  "${COMPOSE[@]}" up -d --no-build
else
  "${COMPOSE[@]}" up -d --build --remove-orphans
fi
wait_flyway 25
"${COMPOSE[@]}" up -d --no-build
wait_api
if [[ "${ARCHIVE_BOOTSTRAP_POWERJOB:-true}" == "true" ]]; then
  ./scripts/bootstrap-powerjob.sh
else
  curl -fsSI --connect-timeout 3 --max-time 5 http://127.0.0.1:7700 >/dev/null
fi

python3 scripts/pipeline_registry.py materialize archive-batch-jun
./scripts/configure-production-pipeline.sh
python3 scripts/pipeline_registry.py materialize archive-delivery-jun
./scripts/configure-production-pipeline.sh
run_external_test ARCHIVE_PREFLIGHT "" preflight "$PROD_TARGET_COUNT"

DELIVERY_DATE="${PROD_DELIVERY_DATE:-$(date +%F)}"
DELIVERY_BATCH="${PROD_DELIVERY_BATCH:-PROD-BATCH-$(date +%Y%m%d-%H%M%S)}"
TARGET_COUNT="${PROD_TARGET_COUNT:-100}"
SUBMISSION_COUNT="${PROD_SUBMISSION_COUNT:-2}"
EVIDENCE_DIR="${PROD_EVIDENCE_ROOT:-$ROOT/docs/evidence}/$DELIVERY_BATCH"
mkdir -p "$EVIDENCE_DIR"
python3 - "$EVIDENCE_DIR/create-batch.json" "$DELIVERY_DATE" "$DELIVERY_BATCH" <<'PY'
import json, os, sys
json.dump({'ossUri': os.environ['PROD_OSS_URI'], 'pipelineCode': 'archive-batch',
           'deliveryDate': sys.argv[2], 'deliveryBatch': sys.argv[3]},
          open(sys.argv[1], 'w'), ensure_ascii=False, indent=2)
PY
curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' \
  --data-binary "@$EVIDENCE_DIR/create-batch.json" "$API/delivery-batches" -o "$EVIDENCE_DIR/create-batch-response.json"
BATCH_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$EVIDENCE_DIR/create-batch-response.json")"
echo "Created OSS batch deliveryId=$BATCH_ID batch=$DELIVERY_BATCH"

BATCH_DEADLINE=$((SECONDS + ${PROD_PIPELINE_OBSERVATION_SECONDS:-7200}))
while ((SECONDS < BATCH_DEADLINE)); do
  curl -fsS --connect-timeout 5 --max-time 15 "$API/delivery-batches/$BATCH_ID" -o "$EVIDENCE_DIR/batch-api.json"
  read -r STATUS DISCOVERED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["status"],str(bool(s.get("discoveryCompletedAt"))).lower())' < "$EVIDENCE_DIR/batch-api.json")"
  if [[ "$STATUS" == "PROCESSING" && "$DISCOVERED" == "true" || "$STATUS" == "DELIVERED" ]]; then break; fi
  [[ "$STATUS" != "BLOCKED" ]] || { echo "OSS batch creation is BLOCKED; evidence=$EVIDENCE_DIR" >&2; exit 1; }
  sleep 5
done
curl -fsS --connect-timeout 5 --max-time 15 "$API/dashboard/summary?deliveryId=$BATCH_ID" -o "$EVIDENCE_DIR/batch-planning-summary.json"
PACKAGES="$(python3 -c 'import json; print(json.load(open("'"$EVIDENCE_DIR"'/batch-planning-summary.json"))["batchPackageTotal"])')"
EXPECTED_TOTAL=$((TARGET_COUNT * SUBMISSION_COUNT))
if ((PACKAGES < EXPECTED_TOTAL)); then
  echo "Batch has $PACKAGES packages, less than requested total $EXPECTED_TOTAL" >&2
  exit 1
fi
curl -fsS --connect-timeout 5 --max-time 20 "$API/delivery-batches/$BATCH_ID/packages" -o "$EVIDENCE_DIR/batch-packages-planned.json"

for ((SUBMISSION=1; SUBMISSION<=SUBMISSION_COUNT; SUBMISSION++)); do
  SUBMIT_JSON="$EVIDENCE_DIR/submit-${SUBMISSION}.json"
  python3 - "$SUBMIT_JSON" "$DELIVERY_DATE" "$DELIVERY_BATCH" "$TARGET_COUNT" <<'PY'
import json, os, sys
golden = json.load(open('deploy/golden-case.json'))
target = golden['targets'][0]
request = {'ossUri': os.environ['PROD_OSS_URI'], 'pipelineCode': 'archive-delivery',
           'deliveryDate': sys.argv[2], 'deliveryBatch': sys.argv[3],
           'targets': [{'targetCount': int(sys.argv[4]), 'vendorCode': target['vendorCode'], 'entityCode': target['entityCode']}]}
json.dump(request, open(sys.argv[1], 'w'), ensure_ascii=False, indent=2)
PY
  DELIVERY_ID="$("${COMPOSE[@]}" exec -T postgres psql -U "$ARCHIVE_DB_USER" -d archive_delivery -Atc \
    "SELECT delivery_id FROM archive_delivery.delivery_submit WHERE batch_delivery_id=$BATCH_ID AND submission_type='BATCH_SUBMIT' ORDER BY delivery_id OFFSET $((SUBMISSION - 1)) LIMIT 1")"
  if [[ -z "$DELIVERY_ID" ]]; then
    curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' \
      --data-binary "@$SUBMIT_JSON" "$API/deliveries" -o "$EVIDENCE_DIR/submit-response-${SUBMISSION}.json"
    DELIVERY_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveryId"])' < "$EVIDENCE_DIR/submit-response-${SUBMISSION}.json")"
    echo "Submitted production deliveryId=$DELIVERY_ID batch=$DELIVERY_BATCH submission=$SUBMISSION count=$TARGET_COUNT"
  else
    echo "Resuming existing production deliveryId=$DELIVERY_ID batch=$DELIVERY_BATCH submission=$SUBMISSION"
  fi
  DEADLINE=$((SECONDS + ${PROD_PIPELINE_OBSERVATION_SECONDS:-7200}))
  while ((SECONDS < DEADLINE)); do
    SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$API/dashboard/summary?deliveryId=$DELIVERY_ID")"
    read -r STATUS SUCCEEDED BLOCKED <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["deliveryStatus"],s["succeeded"],s["blocked"])' <<<"$SUMMARY")"
    if [[ "$STATUS" == "DELIVERED" && "$SUCCEEDED" -eq "$TARGET_COUNT" ]]; then break; fi
    [[ "$BLOCKED" -eq 0 ]] || { printf '%s\n' "$SUMMARY" > "$EVIDENCE_DIR/failure-summary-${SUBMISSION}.json"; echo "Production delivery is BLOCKED" >&2; exit 1; }
    sleep 5
  done
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "$API/dashboard/summary?deliveryId=$DELIVERY_ID")"
  printf '%s\n' "$SUMMARY" > "$EVIDENCE_DIR/delivery-summary-${SUBMISSION}.json"
  python3 - "$SUMMARY" "$TARGET_COUNT" <<'PY'
import json, sys
summary = json.loads(sys.argv[1])
assert summary['deliveryStatus'] == 'DELIVERED' and summary['succeeded'] == int(sys.argv[2]), summary
PY
  run_external_test ARCHIVE_REAL_E2E "$DELIVERY_ID" verifyRealDelivery "$TARGET_COUNT" | tee "$EVIDENCE_DIR/external-verification-${SUBMISSION}.log"
  curl -fsS --connect-timeout 5 --max-time 15 "$API/deliveries/$DELIVERY_ID" -o "$EVIDENCE_DIR/delivery-api-${SUBMISSION}.json"
  curl -fsS --connect-timeout 5 --max-time 15 "$API/deliveries/$DELIVERY_ID/tasks" -o "$EVIDENCE_DIR/tasks-api-${SUBMISSION}.json"
done

curl -fsS --connect-timeout 5 --max-time 15 "$API/dashboard/summary?deliveryId=$BATCH_ID" -o "$EVIDENCE_DIR/batch-summary.json"
curl -fsS --connect-timeout 5 --max-time 20 "$API/delivery-batches/$BATCH_ID/packages" -o "$EVIDENCE_DIR/batch-packages-final.json"
python3 - "$DELIVERY_DATE" "$DELIVERY_BATCH" "$EVIDENCE_DIR" "$EXPECTED_TOTAL" <<'PY'
import json, sys
from hashlib import sha256
from pathlib import Path
root = Path(sys.argv[3])
expected = int(sys.argv[4])
golden = json.load(open('deploy/golden-case.json'))
summary = json.loads((root / 'batch-summary.json').read_text())
assert summary['deliveredPackageCount'] == expected, summary
assert summary['taskTotal'] == 0 and summary['originalFileCount'] >= expected, summary
sha_lines, partial_lines = [], []
for target in golden['targets']:
    directory = Path(target['hostRoot']) / sys.argv[1]
    files = sorted(directory.glob('batch_*.tar.gz'))
    assert len(files) == expected, f"FTP artifact count differs: {len(files)} != {expected}"
    for path in files:
        with path.open('rb') as stream:
            digest = sha256()
            for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
                digest.update(block)
        sha_lines.append(f"{digest.hexdigest()}  {path}")
    partial_lines.extend(str(path) for path in directory.glob('*.partial'))
(root / 'ftp-sha256.txt').write_text('\n'.join(sha_lines) + ('\n' if sha_lines else ''))
(root / 'ftp-partial.txt').write_text('\n'.join(partial_lines) + ('\n' if partial_lines else ''))
assert not partial_lines, partial_lines
PY
"${COMPOSE[@]}" ps > "$EVIDENCE_DIR/compose-services.txt"
"${COMPOSE[@]}" logs --no-color --since 24h archive-server worker-1 worker-2 worker-3 worker-4 > "$EVIDENCE_DIR/application.log"
"${COMPOSE[@]}" exec -T postgres psql -U "$ARCHIVE_DB_USER" -d archive_delivery -P pager=off \
  -c "SELECT version,success,installed_on FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;" \
  -c "SELECT delivery_id,delivery_batch,submission_type,batch_delivery_id,status,batch_extension FROM archive_delivery.delivery_submit WHERE delivery_id=$BATCH_ID OR batch_delivery_id=$BATCH_ID ORDER BY delivery_id;" \
  -c "SELECT delivery_id,count(*) AS tasks,count(*) FILTER(WHERE execution_status='SUCCEEDED') AS succeeded,min(new_file_name),max(new_file_name) FROM archive_delivery.delivery_task WHERE delivery_id IN(SELECT delivery_id FROM archive_delivery.delivery_submit WHERE batch_delivery_id=$BATCH_ID) GROUP BY delivery_id ORDER BY delivery_id;" \
  -c "SELECT status,count(DISTINCT new_file_name) AS packages,count(*) AS original_files FROM archive_delivery.delivery_batch WHERE batch_delivery_id=$BATCH_ID GROUP BY status ORDER BY status;" \
  -c "SELECT new_file_name,entity_code,worker_group,status,delivery_id,count(*) AS original_files FROM archive_delivery.delivery_batch WHERE batch_delivery_id=$BATCH_ID GROUP BY new_file_name,entity_code,worker_group,status,delivery_id ORDER BY new_file_name;" \
  > "$EVIDENCE_DIR/database-facts.txt"
printf 'Production batch verification passed batchDeliveryId=%s batch=%s submissions=%s packagesPerSubmission=%s evidence=%s\n' \
  "$BATCH_ID" "$DELIVERY_BATCH" "$SUBMISSION_COUNT" "$TARGET_COUNT" "$EVIDENCE_DIR"
