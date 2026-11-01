#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -lt 3 || "$#" -gt 4 ]]; then
  echo "Usage: $0 runId fromNode reason [commaSeparatedPackageIds]" >&2
  exit 64
fi
RUN_ID="$1"
FROM_NODE="$2"
REASON="$3"
PACKAGE_IDS="${4:-}"
export RUN_ID FROM_NODE REASON PACKAGE_IDS
BODY="$(python3 -c 'import json,os; ids=[int(x) for x in os.environ["PACKAGE_IDS"].split(",") if x]; print(json.dumps({"fromNode":os.environ["FROM_NODE"],"packageIds":ids,"reason":os.environ["REASON"]}))')"
curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
  -d "$BODY" "http://127.0.0.1:8080/api/v1/runs/$RUN_ID/resume"
