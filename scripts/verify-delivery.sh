#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 1 || ! "$1" =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 runId" >&2
  exit 64
fi
RUN_ID="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUMMARY="$(curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:8080/api/v1/dashboard/summary?runId=$RUN_ID")"
python3 -c 'import json,sys; s=json.load(sys.stdin); assert s["outputTotal"]==10,s; assert s["delivered"]==10,s; print("API verification: 10/10 DELIVERED")' <<<"$SUMMARY"

if [[ ! -f "$ROOT/deploy/.env" ]]; then
  echo "deploy/.env is required for HZ/SG/FTP verification" >&2
  exit 1
fi
set -a
source "$ROOT/deploy/.env"
set +a
export ARCHIVE_DB_URL="${ARCHIVE_DB_URL:-jdbc:postgresql://host.docker.internal:55432/archive_delivery}"
export ARCHIVE_VERIFY_RUN_ID="$RUN_ID"
export ARCHIVE_REAL_E2E=true
DOCKER_SOCK="${DOCKER_SOCK:-$HOME/.docker/run/docker.sock}"
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy" >/dev/null
docker run --rm --env-file "$ROOT/deploy/.env" \
  -e ARCHIVE_REAL_E2E -e ARCHIVE_VERIFY_RUN_ID -e ARCHIVE_DB_URL \
  -e ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}" -e ARCHIVE_DB_PASSWORD="${ARCHIVE_DB_PASSWORD:-archive_local}" \
  -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -v "$DOCKER_SOCK":/var/run/docker.sock \
  -v "$ROOT/../old/sample":/samples:ro -w /workspace \
  archive-delivery-maven:test mvn -q -pl delivery-worker -Dtest=RealProductionEndToEndTest test
