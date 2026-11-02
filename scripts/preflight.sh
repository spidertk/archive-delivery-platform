#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "FAIL deploy/.env is missing; copy deploy/env.example and provide local settings." >&2
  exit 1
fi
set -a
source "$ENV_FILE"
set +a

docker info --format '{{.ServerVersion}}' >/dev/null
echo "OK Docker daemon"
docker compose version >/dev/null
echo "OK Docker Compose"
ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}"
docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ENV_FILE" exec -T postgres \
  pg_isready -U "$ARCHIVE_DB_USER" -d archive_delivery >/dev/null
echo "OK PostgreSQL archive_delivery"
BOUND="$(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ENV_FILE" exec -T postgres \
  psql -U "$ARCHIVE_DB_USER" -d archive_delivery -Atc \
  "SELECT count(*) FROM source_pipeline_binding b JOIN pipeline_version pv ON pv.id=b.pipeline_version_id WHERE b.effective_to IS NULL AND pv.status='PUBLISHED'")"
if [[ "$BOUND" -eq 0 ]]; then
  echo "FAIL no published Pipeline binding; run scripts/configure-pipeline.sh first" >&2
  exit 1
fi

docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy" >/dev/null
docker run --rm --network host -e ARCHIVE_PREFLIGHT=true \
  -e ARCHIVE_DB_URL='jdbc:postgresql://127.0.0.1:55432/archive_delivery?currentSchema=archive_delivery' \
  -e ARCHIVE_DB_USER -e ARCHIVE_DB_PASSWORD \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
  -v "$HOME/.ssh/archive-delivery-mvp/line-1-id_ed25519:/run/secrets/line-1-id_ed25519:ro" \
  -v "$HOME/.ssh/archive-delivery-mvp/line-2-id_ed25519:/run/secrets/line-2-id_ed25519:ro" \
  -v "$HOME/.ssh/archive-delivery-mvp/line-3-id_ed25519:/run/secrets/line-3-id_ed25519:ro" \
  archive-delivery-maven:test mvn -q -pl delivery-core -Dtest=ProductionPreflightTest test
"$ROOT/scripts/ftp-inspect.sh" check

echo "Preflight passed. No delivery data was modified."
