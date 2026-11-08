#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 5 ]]; then
  echo "Usage: $0 ossUri pipelineCode deliveryDate cutoffAt deliveryBatch" >&2
  exit 64
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp)"
trap 'rm -f "$TEMP"' EXIT
curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/delivery-entities -o "$TEMP"
export TEMP
# 普通三实体提交显式传复合业务键和截止时间，服务端选择最新发布版本并校验目标。
python3 - "$@" <<'PY' | curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:8080/api/v1/deliveries
import json, os, sys
oss_uri, pipeline_code, date, cutoff_at, batch = sys.argv[1:]
entities = sorted(json.load(open(os.environ["TEMP"])), key=lambda row: (row["vendorCode"], row["entityCode"]))
assert len(entities) >= 3, "Three delivery entities are required"
targets = [{"targetCount": 1, "vendorCode": row["vendorCode"], "entityCode": row["entityCode"]} for row in entities[:3]]
print(json.dumps({"ossUri": oss_uri, "pipelineCode": pipeline_code, "deliveryDate": date,
                  "deliveryBatch": batch, "cutoffAt": cutoff_at, "targets": targets}))
PY
