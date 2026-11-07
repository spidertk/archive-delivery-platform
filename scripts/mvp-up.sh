#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
EXPLICIT_UPGRADE_AUTHORIZED="${ARCHIVE_UPGRADE_AUTHORIZED:-}"
set -a
source deploy/.env
set +a
if [[ -n "$EXPLICIT_UPGRADE_AUTHORIZED" ]]; then export ARCHIVE_UPGRADE_AUTHORIZED="$EXPLICIT_UPGRADE_AUTHORIZED"; fi
# 启动不清理业务数据；V14 仅在外部显式传入升级授权时允许清空旧模型。
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build --remove-orphans
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
