#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 6 ]]; then
  echo "Usage: $0 sourceId pipelineVersionId deliveryDate cutoffAt deliveryBatch idempotencyKey" >&2
  exit 64
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp)"
trap 'rm -f "$TEMP"' EXIT
curl -fsS --connect-timeout 5 --max-time 15 http://127.0.0.1:8080/api/v1/delivery-entities -o "$TEMP"
export TEMP
# 核心验收按实体 ID 顺序生成三个独立目标，每个目标只交付一个产物。
python3 - "$@" <<'PY' | curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:8080/api/v1/deliveries
import json, os, sys
source_id, version_id, date, cutoff, batch, key = sys.argv[1:]
entities = sorted(json.load(open(os.environ["TEMP"])), key=lambda row: row["deliveryEntityId"])
assert len(entities) >= 3, "Three delivery entities are required"
targets = [{"targetCount": 1, "deliveryEntityId": entities[i]["deliveryEntityId"]} for i in range(3)]
print(json.dumps({"sourceId": int(source_id), "pipelineVersionId": int(version_id), "deliveryDate": date,
                  "cutoffAt": cutoff, "deliveryBatch": batch, "idempotencyKey": key, "targets": targets}))
PY
