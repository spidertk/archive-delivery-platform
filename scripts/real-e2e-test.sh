#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f "$ROOT/deploy/.env" ]]; then
  echo "Missing deploy/.env; copy deploy/env.example and configure all production settings." >&2
  exit 1
fi
set -a
source "$ROOT/deploy/.env"
set +a

./scripts/preflight.sh
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh

docker compose -f deploy/docker-compose.yml exec -T postgres psql -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery -Atc \
  "SELECT definition->'config' FROM archive_delivery.pipeline_version WHERE version_no=2" | \
  python3 -c 'import json,sys; c=json.load(sys.stdin); assert c=={"crrWaitSeconds":180,"autoRollover":False,"ftpCleanBeforeDownload":False},c'

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
for line in 1 2 3; do
  host_var="FTP_LINE_${line}_SSH_HOST"
  user_var="FTP_LINE_${line}_SSH_USER"
  key_var="FTP_LINE_${line}_SSH_KEY"
  jump_host_var="FTP_LINE_${line}_JUMP_HOST"
  jump_user_var="FTP_LINE_${line}_JUMP_USER"
  jump_key_var="FTP_LINE_${line}_JUMP_KEY"
  args=(-o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i "${!key_var}")
  if [[ -n "${!jump_host_var:-}" ]]; then
    args+=(-o "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i ${!jump_key_var} -W %h:%p ${!jump_user_var}@${!jump_host_var}")
  fi
  provider="june"
  if [[ "$line" -eq 2 ]]; then provider="june-1"; elif [[ "$line" -eq 3 ]]; then provider="june-2"; fi
  remote_root="/srv/maas-sftp/maasData/archive/$provider/2026-10-30"
  ssh "${args[@]}" "${!user_var}@${!host_var}" \
    "if test -d '$remote_root'; then find '$remote_root' -type f -printf '%P|%s|%T@\\n' | sort; fi" > "$WORK/before-$line"
done

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
for line in 1 2 3; do
  host_var="FTP_LINE_${line}_SSH_HOST"
  user_var="FTP_LINE_${line}_SSH_USER"
  key_var="FTP_LINE_${line}_SSH_KEY"
  jump_host_var="FTP_LINE_${line}_JUMP_HOST"
  jump_user_var="FTP_LINE_${line}_JUMP_USER"
  jump_key_var="FTP_LINE_${line}_JUMP_KEY"
  args=(-o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i "${!key_var}")
  if [[ -n "${!jump_host_var:-}" ]]; then
    args+=(-o "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i ${!jump_key_var} -W %h:%p ${!jump_user_var}@${!jump_host_var}")
  fi
  provider="june"
  if [[ "$line" -eq 2 ]]; then provider="june-1"; elif [[ "$line" -eq 3 ]]; then provider="june-2"; fi
  remote_root="/srv/maas-sftp/maasData/archive/$provider/2026-10-30"
  ssh "${args[@]}" "${!user_var}@${!host_var}" \
    "if test -d '$remote_root'; then find '$remote_root' -type f -printf '%P|%s|%T@\\n' | sort; fi" > "$WORK/after-$line"
  python3 -c 'import sys; before=dict(line.rstrip("\n").split("|",2) for line in open(sys.argv[1]) if line.strip()); after=dict(line.rstrip("\n").split("|",2) for line in open(sys.argv[2]) if line.strip()); changed={k:(v,after.get(k)) for k,v in before.items() if after.get(k)!=v}; assert not changed,changed' \
    "$WORK/before-$line" "$WORK/after-$line"
done

echo "Real E2E passed: runId=$RUN_ID, 10/10 DELIVERED, historical FTP files unchanged."
