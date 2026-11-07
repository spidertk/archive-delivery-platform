#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${POWERJOB_ADMIN_URL:-http://127.0.0.1:7700}"
APP_NAME="${POWERJOB_APP_NAME:-archive-delivery}"
APP_PASSWORD="${POWERJOB_PASSWORD:-archive_local}"
ADMIN_USERNAME="${POWERJOB_ADMIN_USER:-ADMIN}"
ADMIN_PASSWORD="${POWERJOB_ADMIN_PASSWORD:-powerjob_admin}"
for attempt in $(seq 1 40); do
  if curl -fsS --connect-timeout 2 --max-time 3 "$BASE_URL/" >/dev/null 2>&1; then break; fi
  if [[ "$attempt" -eq 40 ]]; then echo "PowerJob Server did not become ready" >&2; exit 1; fi
  sleep 3
done
login_payload="$(python3 -c 'import json,sys; print(json.dumps({"loginType":"PWJB","originParams":json.dumps({"username":sys.argv[1],"password":sys.argv[2],"encryption":"none"})}))' "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
login_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' -d "$login_payload" "$BASE_URL/auth/thirdPartyLoginDirect")"
JWT="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); print(p["data"]["jwtToken"])' <<<"$login_response")"
AUTH_HEADER=(-H "PowerJwt: $JWT")
app_list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -d "{\"appNameLike\":\"$APP_NAME\",\"index\":0,\"pageSize\":20}" "$BASE_URL/appInfo/list")"
APP_ID="$(python3 -c 'import json,sys; p=json.load(sys.stdin); rows=p.get("data",{}).get("data",[]); print(next((str(r["id"]) for r in rows if r.get("appName")==sys.argv[1]),""))' <<<"$app_list_response" "$APP_NAME")"
APP_HEADER=(); app_id_json="null"
if [[ -n "$APP_ID" ]]; then APP_HEADER=(-H "AppId: $APP_ID"); app_id_json="$APP_ID"; fi
app_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" ${APP_HEADER[@]+"${APP_HEADER[@]}"} \
  -d "{\"id\":$app_id_json,\"appName\":\"$APP_NAME\",\"title\":\"$APP_NAME\",\"password\":\"$APP_PASSWORD\",\"namespaceCode\":\"default_namespace\"}" "$BASE_URL/appInfo/save")"
APP_ID="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); print(p["data"]["id"])' <<<"$app_response")"
echo "PowerJob app $APP_NAME id=$APP_ID"
save_job() {
  local name="$1" execute_type="$2" interval_ms="$3" processor="$4" list_response job_id id_json
  list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -H "AppId: $APP_ID" -d "{\"keyword\":\"$name\",\"index\":0,\"pageSize\":1}" "$BASE_URL/job/list")"
  job_id="$(python3 -c 'import json,sys; d=json.load(sys.stdin).get("data",{}).get("data",[]); print(d[0]["id"] if d else "")' <<<"$list_response")"
  id_json="null"; if [[ -n "$job_id" ]]; then id_json="$job_id"; fi
  curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -H "AppId: $APP_ID" \
    -d "{\"id\":$id_json,\"jobName\":\"$name\",\"jobDescription\":\"Pipeline generic trigger\",\"appId\":$APP_ID,\"timeExpressionType\":\"FIXED_RATE\",\"timeExpression\":\"$interval_ms\",\"executeType\":\"$execute_type\",\"processorType\":\"BUILT_IN\",\"processorInfo\":\"$processor\",\"maxInstanceNum\":0,\"concurrency\":1,\"instanceTimeLimit\":0,\"instanceRetryNum\":0,\"taskRetryNum\":0,\"maxWorkerCount\":0,\"enable\":true}" \
    "$BASE_URL/job/save" >/dev/null
  echo "Saved $name ($execute_type, ${interval_ms}ms)"
}
save_job archive-delivery-preparation STANDALONE 15000 com.powerjob.archive.worker.DeliveryPreparationProcessor
save_job archive-task-tick BROADCAST 5000 com.powerjob.archive.worker.TaskTickProcessor
save_job archive-delivery-rollover STANDALONE 30000 com.powerjob.archive.worker.DeliveryRolloverProcessor

# 删除会调用已移除 Processor 的旧调度，保留 PowerJob 本身及三个新通用触发器。
delete_legacy_job() {
  local name="$1" response legacy_id
  response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" \
    -H "AppId: $APP_ID" -d "{\"keyword\":\"$name\",\"index\":0,\"pageSize\":1}" "$BASE_URL/job/list")"
  legacy_id="$(python3 -c 'import json,sys; d=json.load(sys.stdin).get("data",{}).get("data",[]); print(d[0]["id"] if d else "")' <<<"$response")"
  if [[ -n "$legacy_id" ]]; then
    curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" \
      -H "AppId: $APP_ID" -d "{\"jobId\":$legacy_id}" "$BASE_URL/job/delete" >/dev/null
    echo "Deleted legacy PowerJob schedule $name"
  fi
}
delete_legacy_job archive-discovery
delete_legacy_job archive-package-tick
