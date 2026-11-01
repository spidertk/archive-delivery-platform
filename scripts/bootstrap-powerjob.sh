#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${POWERJOB_ADMIN_URL:-http://127.0.0.1:7700}"
APP_NAME="${POWERJOB_APP_NAME:-archive-delivery-mvp}"
APP_PASSWORD="${POWERJOB_PASSWORD:-archive_local}"

for attempt in $(seq 1 30); do
  if curl -fsS --connect-timeout 2 --max-time 3 "$BASE_URL/" >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" -eq 30 ]]; then
    echo "PowerJob Server did not become ready within 90 seconds" >&2
    exit 1
  fi
  sleep 3
done

app_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' \
  -d "{\"appName\":\"$APP_NAME\",\"title\":\"$APP_NAME\",\"password\":\"$APP_PASSWORD\",\"namespaceCode\":\"default_namespace\"}" \
  "$BASE_URL/appInfo/save")"
APP_ID="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); assert payload.get("success",False),payload; print(payload["data"]["id"])' <<<"$app_response")"
echo "PowerJob app $APP_NAME id=$APP_ID"

save_job() {
  local name="$1"
  local execute_type="$2"
  local interval_ms="$3"
  local processor="$4"
  local list_response
  list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' \
    -H "AppId: $APP_ID" -d "{\"keyword\":\"$name\",\"index\":0,\"pageSize\":1}" "$BASE_URL/job/list")"
  local job_id
  job_id="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); data=payload.get("data",{}).get("data",[]); print(data[0]["id"] if data else "")' <<<"$list_response")"
  local id_json="null"
  if [[ -n "$job_id" ]]; then id_json="$job_id"; fi
  curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' -H "AppId: $APP_ID" \
    -d "{\"id\":$id_json,\"jobName\":\"$name\",\"jobDescription\":\"Local production MVP\",\"appId\":$APP_ID,\"timeExpressionType\":\"FIXED_RATE\",\"timeExpression\":\"$interval_ms\",\"executeType\":\"$execute_type\",\"processorType\":\"BUILT_IN\",\"processorInfo\":\"$processor\",\"maxInstanceNum\":0,\"concurrency\":1,\"instanceTimeLimit\":0,\"instanceRetryNum\":0,\"taskRetryNum\":0,\"maxWorkerCount\":0,\"enable\":true}" \
    "$BASE_URL/job/save" >/dev/null
  echo "Saved $name ($execute_type, ${interval_ms}ms)"
}

save_job archive-discovery STANDALONE 30000 com.powerjob.archive.worker.DiscoveryProcessor
save_job archive-package-tick BROADCAST 10000 com.powerjob.archive.worker.PackageTickProcessor
save_job archive-delivery-rollover STANDALONE 60000 com.powerjob.archive.worker.DeliveryRolloverProcessor
