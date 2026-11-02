#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f "$ROOT/deploy/.env" ]]; then
  echo "Missing deploy/.env; copy deploy/env.example and configure local settings." >&2
  exit 1
fi
set -a
source "$ROOT/deploy/.env"
set +a

./scripts/preflight.sh
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
for attempt in $(seq 1 30); do
  if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" -eq 30 ]]; then
    echo "Archive API did not become ready within 90 seconds" >&2
    exit 1
  fi
  sleep 3
done

docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery -Atc \
  "SELECT pv.definition->'config' FROM archive_delivery.source_pipeline_binding b JOIN archive_delivery.pipeline_version pv ON pv.id=b.pipeline_version_id WHERE b.effective_to IS NULL AND pv.status='PUBLISHED' ORDER BY b.effective_from DESC LIMIT 1" | \
  python3 -c 'import json,sys; c=json.load(sys.stdin); assert c=={"crrWaitSeconds":180,"autoRollover":False,"ftpCleanBeforeDownload":False},c'

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
./scripts/ftp-inspect.sh before "$WORK"

KEY="real-e2e-$(date +%s)"
RESPONSE="$(./scripts/submit-run.sh \
  'oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/' \
  "$KEY" '20261030')"
RUN_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])' <<<"$RESPONSE")"
echo "Submitted real runId=$RUN_ID"

for attempt in $(seq 1 240); do
  SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/dashboard/summary?runId=$RUN_ID")"
  read -r delivered blocked <<<"$(python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["delivered"],s["blocked"])' <<<"$SUMMARY")"
  if [[ "$delivered" -eq 10 ]]; then
    break
  fi
  if [[ "$blocked" -gt 0 ]]; then
    echo "$SUMMARY"
    echo "Real run $RUN_ID is BLOCKED; stop new claims and inspect before retry/resume." >&2
    exit 1
  fi
  if [[ "$attempt" -eq 240 ]]; then
    echo "$SUMMARY"
    echo "Real run $RUN_ID exceeded the two-hour bounded observation window" >&2
    exit 1
  fi
  sleep 30
done

./scripts/verify-delivery.sh "$RUN_ID"
./scripts/ftp-inspect.sh after "$WORK"
for line in 1 2 3; do
  python3 -c 'import sys; before=dict((p[0],p[1:]) for p in (line.rstrip("\n").split("|",2) for line in open(sys.argv[1]) if line.strip())); after=dict((p[0],p[1:]) for p in (line.rstrip("\n").split("|",2) for line in open(sys.argv[2]) if line.strip())); changed={k:(v,after.get(k)) for k,v in before.items() if after.get(k)!=v}; assert not changed,changed' \
    "$WORK/before-$line" "$WORK/after-$line"
done

echo "Real E2E passed: runId=$RUN_ID, 10/10 DELIVERED, historical FTP files unchanged."
