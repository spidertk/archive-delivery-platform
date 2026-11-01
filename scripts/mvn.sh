#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy"
DOCKER_SOCK="${DOCKER_SOCK:-$HOME/.docker/run/docker.sock}"
docker run --rm -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 \
  -v "$DOCKER_SOCK":/var/run/docker.sock -w /workspace \
  archive-delivery-maven:test mvn "$@"
