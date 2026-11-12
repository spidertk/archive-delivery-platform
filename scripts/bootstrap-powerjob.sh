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
login() {
  local password="$1" payload
  payload="$(python3 -c 'import json,sys; print(json.dumps({"loginType":"PWJB","originParams":json.dumps({"username":sys.argv[1],"password":sys.argv[2],"encryption":"none"})}))' "$ADMIN_USERNAME" "$password")"
  curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' -d "$payload" "$BASE_URL/auth/thirdPartyLoginDirect"
}
login_response="$(login "$ADMIN_PASSWORD")"
# 首次生产启动先使用受控初始密码登录，再立即轮换为随机密码；后续运行直接使用新密码。
if ! python3 -c 'import json,sys; raise SystemExit(0 if json.load(sys.stdin).get("success",False) else 1)' <<<"$login_response"; then
  INITIAL_PASSWORD="${POWERJOB_ADMIN_INITIAL_PASSWORD:-}"
  [[ -n "$INITIAL_PASSWORD" && "$INITIAL_PASSWORD" != "$ADMIN_PASSWORD" ]] \
    || { echo "PowerJob administrator login failed" >&2; exit 1; }
  login_response="$(login "$INITIAL_PASSWORD")"
  JWT="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); print(p["data"]["jwtToken"])' <<<"$login_response")"
  change_payload="$(python3 -c 'import json,sys; print(json.dumps({"username":sys.argv[1],"oldPassword":sys.argv[2],"newPassword":sys.argv[3],"newPassword2":sys.argv[3]}))' \
    "$ADMIN_USERNAME" "$INITIAL_PASSWORD" "$ADMIN_PASSWORD")"
  change_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' -H "PowerJwt: $JWT" \
    -d "$change_payload" "$BASE_URL/pwjbUser/changePassword")"
  python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False), p.get("message") or "password rotation failed"' <<<"$change_response"
  echo "PowerJob administrator password rotated from the controlled initial value"
fi
JWT="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); print(p["data"]["jwtToken"])' <<<"$login_response")"
AUTH_HEADER=(-H "PowerJwt: $JWT")
# 用户体系刚完成密码轮换时列表接口可能短暂返回空 data；有界等待，不把瞬时空值当成权限或空分页。
for attempt in $(seq 1 12); do
  app_list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -d "{\"appNameLike\":\"$APP_NAME\",\"index\":0,\"pageSize\":20}" "$BASE_URL/appInfo/list")"
  if python3 -c 'import json,sys; p=json.load(sys.stdin); raise SystemExit(0 if p.get("success",False) and isinstance(p.get("data"),dict) else 1)' <<<"$app_list_response"; then break; fi
  [[ "$attempt" -lt 12 ]] || { echo "PowerJob App list did not become ready" >&2; exit 1; }
  sleep 5
done
APP_ID="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); rows=(p.get("data") or {}).get("data") or []; print(next((str(r["id"]) for r in rows if r.get("appName")==sys.argv[1]),""))' <<<"$app_list_response" "$APP_NAME")"
APP_HEADER=(); app_id_json="null"
if [[ -n "$APP_ID" ]]; then APP_HEADER=(-H "AppId: $APP_ID"); app_id_json="$APP_ID"; fi
app_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" ${APP_HEADER[@]+"${APP_HEADER[@]}"} \
  -d "{\"id\":$app_id_json,\"appName\":\"$APP_NAME\",\"title\":\"$APP_NAME\",\"password\":\"$APP_PASSWORD\",\"namespaceCode\":\"default_namespace\"}" "$BASE_URL/appInfo/save")"
APP_ID="$(python3 -c 'import json,sys; p=json.load(sys.stdin); assert p.get("success",False); print(p["data"]["id"])' <<<"$app_response")"
echo "PowerJob app $APP_NAME id=$APP_ID"
save_job() {
  local name="$1" execute_type="$2" interval_ms="$3" processor="$4" list_response job_id id_json
  list_response="$(curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" -H "AppId: $APP_ID" -d "{\"keyword\":\"$name\",\"index\":0,\"pageSize\":1}" "$BASE_URL/job/list")"
  job_id="$(python3 -c 'import json,sys; d=(json.load(sys.stdin).get("data") or {}).get("data") or []; print(d[0]["id"] if d else "")' <<<"$list_response")"
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
  legacy_id="$(python3 -c 'import json,sys; d=(json.load(sys.stdin).get("data") or {}).get("data") or []; print(d[0]["id"] if d else "")' <<<"$response")"
  if [[ -n "$legacy_id" ]]; then
    curl -fsS --connect-timeout 5 --max-time 10 -H 'Content-Type: application/json' "${AUTH_HEADER[@]}" \
      -H "AppId: $APP_ID" -d "{\"jobId\":$legacy_id}" "$BASE_URL/job/delete" >/dev/null
    echo "Deleted legacy PowerJob schedule $name"
  fi
}
delete_legacy_job archive-discovery
delete_legacy_job archive-package-tick
