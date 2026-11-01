#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy"
DOCKER_SOCK="${DOCKER_SOCK:-$HOME/.docker/run/docker.sock}"
docker run --rm -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e ARCHIVE_TEST_LOCAL_PG -e ARCHIVE_DB_URL -e ARCHIVE_DB_USER -e ARCHIVE_DB_PASSWORD \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 \
  -v "$DOCKER_SOCK":/var/run/docker.sock -w /workspace \
  archive-delivery-maven:test mvn "$@"
