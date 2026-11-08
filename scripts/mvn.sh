#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy"
DOCKER_SOCK="${DOCKER_SOCK:-$HOME/.docker/run/docker.sock}"
docker run --rm -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e JAVA_TOOL_OPTIONS="-Dapi.version=${DOCKER_API_VERSION:-1.44}" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e ARCHIVE_TEST_LOCAL_PG -e ARCHIVE_TEST_LOCAL_SCHEMA_RESET \
  -e ARCHIVE_DB_URL -e ARCHIVE_DB_USER -e ARCHIVE_DB_PASSWORD \
  -e ARCHIVE_TEST_SAMPLE=/samples/batch_00002.tar.gz \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 \
  -v "$ROOT/../old/sample":/samples:ro \
  -v "$DOCKER_SOCK":/var/run/docker.sock -w /workspace \
  archive-delivery-maven:test mvn "$@"
