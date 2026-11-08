#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 只清理本地新模型运行事实；配置、实体、Pipeline 和外部 OSS/FTP 历史文件均保留。
if [[ "${ARCHIVE_ALLOW_TEST_DATA_RESET:-false}" != "true" || "${ARCHIVE_CONFIRM_LOCAL_TEST_RESET:-}" != "archive_delivery" ]]; then
  echo "Refused: dual confirmation is required for the local Compose database only." >&2
  exit 64
fi
set -a
source "$ROOT/deploy/.env"
set +a
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/deploy/.env")
"${COMPOSE[@]}" stop archive-server worker-1 worker-2 worker-3 >/dev/null
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery <<'SQL'
SET search_path TO archive_delivery;
DO $$ BEGIN
  IF current_database()<>'archive_delivery' OR to_regclass('archive_delivery.delivery_submit') IS NULL
     OR to_regclass('archive_delivery.delivery_group') IS NOT NULL THEN
    RAISE EXCEPTION 'V14 local test schema is required';
  END IF;
END $$;
TRUNCATE delivery_event,delivery_node,delivery_mapping,delivery_task,request_id_allocation,source_data,
  delivery_submit CASCADE;
DELETE FROM request_id_sequence;
SQL
printf 'Local delivery facts reset; Pipeline/entity configuration and external history were preserved.\n'
