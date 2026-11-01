#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
  echo "Usage: $0 ossUri idempotencyKey [jobDate]" >&2
  exit 64
fi
OSS_URI="$1"
IDEMPOTENCY_KEY="$2"
JOB_DATE="${3:-}"
export OSS_URI IDEMPOTENCY_KEY JOB_DATE
BODY="$(python3 -c 'import json,os; print(json.dumps({k:v for k,v in {"ossUri":os.environ["OSS_URI"],"idempotencyKey":os.environ["IDEMPOTENCY_KEY"],"jobDate":os.environ["JOB_DATE"]}.items() if v}))')"
curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d "$BODY" http://127.0.0.1:8080/api/v1/runs
