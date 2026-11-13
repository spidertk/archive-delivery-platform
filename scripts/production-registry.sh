#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SELECTION="${1:-all}"
# 每个 case 都独立发布、预检、提交和核验；全部通过才表示注册级生产验证完成。
if [[ "$SELECTION" == "all" ]]; then
  mapfile -t CASES < <(python3 scripts/pipeline_registry.py list)
else
  CASES=("$SELECTION")
fi
python3 scripts/pipeline_registry.py validate
for CASE_ID in "${CASES[@]}"; do
  echo "Starting golden production case=$CASE_ID"
  PIPELINE_CASE="$CASE_ID" ./scripts/production-pipeline.sh
done
printf 'Golden production registry passed cases=%s\n' "${CASES[*]}"
