#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${POWERJOB_ADMIN_URL:-http://127.0.0.1:7700}"
APP_NAME="${POWERJOB_APP_NAME:-archive-delivery-mvp}"
APP_PASSWORD="${POWERJOB_PASSWORD:-archive_local}"
ADMIN_USERNAME="${POWERJOB_ADMIN_USER:-ADMIN}"
ADMIN_PASSWORD="${POWERJOB_ADMIN_PASSWORD:-powerjob_admin}"

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

login_payload="$(python3 -c 'import json,sys; print(json.dumps({"loginType":"PWJB","originParams":json.dumps({"username":sys.argv[1],"password":sys.argv[2],"encryption":"none"})}))' "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
login_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' \
  -d "$login_payload" "$BASE_URL/auth/thirdPartyLoginDirect")"
JWT="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); assert payload.get("success",False),payload; print(payload["data"]["jwtToken"])' <<<"$login_response")"
AUTH_HEADER=(-H "PowerJwt: $JWT")

app_list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" \
  -d "{\"appNameLike\":\"$APP_NAME\",\"index\":0,\"pageSize\":20}" "$BASE_URL/appInfo/list")"
APP_ID="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); assert payload.get("success",False),payload; rows=payload.get("data",{}).get("data",[]); print(next((str(row["id"]) for row in rows if row.get("appName")==sys.argv[1]),""))' <<<"$app_list_response" "$APP_NAME")"
APP_HEADER=()
app_id_json="null"
if [[ -n "$APP_ID" ]]; then
  APP_HEADER=(-H "AppId: $APP_ID")
  app_id_json="$APP_ID"
fi
app_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" "${APP_HEADER[@]}" \
  -d "{\"id\":$app_id_json,\"appName\":\"$APP_NAME\",\"title\":\"$APP_NAME\",\"password\":\"$APP_PASSWORD\",\"namespaceCode\":\"default_namespace\"}" \
  "$BASE_URL/appInfo/save")"
APP_ID="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); assert payload.get("success",False),payload; print(payload["data"]["id"])' <<<"$app_response")"
echo "PowerJob app $APP_NAME id=$APP_ID"

save_job() {
  local name="$1"
  local execute_type="$2"
  local interval_ms="$3"
  local processor="$4"
  local list_response
  list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" \
    -H "AppId: $APP_ID" -d "{\"keyword\":\"$name\",\"index\":0,\"pageSize\":1}" "$BASE_URL/job/list")"
  local job_id
  job_id="$(python3 -c 'import json,sys; payload=json.load(sys.stdin); data=payload.get("data",{}).get("data",[]); print(data[0]["id"] if data else "")' <<<"$list_response")"
  local id_json="null"
  if [[ -n "$job_id" ]]; then id_json="$job_id"; fi
  curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -H "AppId: $APP_ID" \
    -d "{\"id\":$id_json,\"jobName\":\"$name\",\"jobDescription\":\"Local production MVP\",\"appId\":$APP_ID,\"timeExpressionType\":\"FIXED_RATE\",\"timeExpression\":\"$interval_ms\",\"executeType\":\"$execute_type\",\"processorType\":\"BUILT_IN\",\"processorInfo\":\"$processor\",\"maxInstanceNum\":0,\"concurrency\":1,\"instanceTimeLimit\":0,\"instanceRetryNum\":0,\"taskRetryNum\":0,\"maxWorkerCount\":0,\"enable\":true}" \
    "$BASE_URL/job/save" >/dev/null
  echo "Saved $name ($execute_type, ${interval_ms}ms)"
}

save_job archive-discovery STANDALONE 30000 com.powerjob.archive.worker.DiscoveryProcessor
save_job archive-package-tick BROADCAST 10000 com.powerjob.archive.worker.PackageTickProcessor
save_job archive-delivery-rollover STANDALONE 60000 com.powerjob.archive.worker.DeliveryRolloverProcessor
