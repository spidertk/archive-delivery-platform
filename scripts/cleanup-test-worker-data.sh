#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ ! -f deploy/.env ]]; then
  echo "deploy/.env is required" >&2
  exit 1
fi
set -a
source deploy/.env
set +a
COMPOSE=(docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env)
VOLUME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}_archive_data"
ACTIVE="$("${COMPOSE[@]}" exec -T postgres psql -U "$ARCHIVE_DB_USER" -d archive_delivery -Atc \
  "SELECT count(*) FROM archive_delivery.delivery_task WHERE execution_status IN('READY','RUNNING','WAITING','RETRY_WAIT','PAUSED')")"
if [[ "$ACTIVE" != "0" ]]; then
  echo "Refusing cleanup: $ACTIVE active delivery tasks" >&2
  exit 1
fi
echo "Active tasks=0; inspecting Worker intermediate data only"
docker run --rm -v "$VOLUME":/data:ro alpine:3.22 \
  sh -c 'du -sh /data/work/source-cache /data/work/delivery-* /data/ftp-verify 2>/dev/null || true'
df -h / /srv /opt
if [[ "${ARCHIVE_TEST_CLEANUP_AUTHORIZED:-}" != "2026-11-15" ]]; then
  echo "Dry run only. Set ARCHIVE_TEST_CLEANUP_AUTHORIZED=2026-11-15 on the test machine to delete these intermediate files."
  exit 0
fi
docker run --rm -v "$VOLUME":/data alpine:3.22 \
  sh -c 'find /data/work/source-cache -mindepth 1 -delete; find /data/ftp-verify -mindepth 1 -delete; for path in /data/work/delivery-*; do if [ -e "$path" ]; then rm -rf -- "$path"; fi; done'
echo "Cleanup completed: source-cache, delivery work directories and FTP verification copies removed"
df -h / /srv /opt
