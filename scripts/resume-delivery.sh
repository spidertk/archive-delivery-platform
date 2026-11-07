#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -lt 3 || "$#" -gt 4 ]]; then
  echo "Usage: $0 deliveryId fromNode reason [commaSeparatedTaskIds]" >&2
  exit 64
fi
export DELIVERY_ID="$1" FROM_NODE="$2" REASON="$3" TASK_IDS="${4:-}"
python3 - <<'PY' | curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary @- \
  "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/resume"
import json, os
ids = [int(value) for value in os.environ["TASK_IDS"].split(",") if value]
print(json.dumps({"fromNode": os.environ["FROM_NODE"], "taskIds": ids, "reason": os.environ["REASON"]}))
PY
