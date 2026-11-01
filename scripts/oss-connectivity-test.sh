#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -f "$ROOT/deploy/.env" ]]; then
  echo "Missing deploy/.env; copy deploy/env.example and set the real source OSS credentials."
  exit 1
fi
IMAGE="${MAVEN_IMAGE:-docker.1ms.run/library/maven:3.9-eclipse-temurin-17}"
docker run --rm \
  --env-file "$ROOT/deploy/.env" \
  -v "$ROOT":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -w /workspace \
  "$IMAGE" mvn -pl delivery-core -Dtest=OssDiscoveryIntegrationTest test
