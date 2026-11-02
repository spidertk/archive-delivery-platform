#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -m unittest nodes/python/test_archive_repair.py -v
IMAGE="${MAVEN_IMAGE:-archive-delivery-maven:test}"
if [[ "${SKIP_IMAGE_BUILD:-false}" != "true" ]]; then
  docker build -t "${IMAGE}" -f deploy/Dockerfile.test deploy
fi
DOCKER_SOCK="${DOCKER_SOCK:-$HOME/.docker/run/docker.sock}"
# Docker Desktop for Mac 拒绝 docker-java 默认的 Docker API v1.32 请求（HTTP 400），
# 通过 -Dapi.version 指定受支持的 API 版本；旧版本 Docker daemon 可整体去掉该参数。
docker run --rm \
  -e ARCHIVE_TEST_SAMPLE=/samples/batch_00002.tar.gz \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v "$ROOT":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v "$DOCKER_SOCK":/var/run/docker.sock \
  -v "$ROOT/../old/sample":/samples:ro \
  -w /workspace \
  "${IMAGE}" mvn -DargLine=-Dapi.version=1.43 test
