#!/usr/bin/env bash
# 真实环境端到端测试：真实 OSS 输入 + 本地 Docker PostgreSQL。
# 运行 MvpOssEndToEndTest（真实 OSS 全链路）与 RetryEndToEndTest（hzUpload 注入失败重试）。
# 需要 deploy/.env 提供真实只读 OSS 凭据；两个测试每次运行前都会重置（Flyway clean+migrate）
# 本地开发库 archive_delivery schema，只适用于 compose 启动的可丢弃 PostgreSQL。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f "$ROOT/deploy/.env" ]]; then
  echo "Missing deploy/.env; copy deploy/env.example and set the real source OSS credentials."
  exit 1
fi

IMAGE="${MAVEN_IMAGE:-archive-delivery-maven:test}"
if [[ "${SKIP_IMAGE_BUILD:-false}" != "true" ]]; then
  echo "Building test image ${IMAGE} (maven + python3)..."
  docker build -t "${IMAGE}" -f deploy/Dockerfile.test deploy
fi

echo "Starting local PostgreSQL..."
docker compose -f deploy/docker-compose.yml up -d postgres
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose -f deploy/docker-compose.yml ps -q postgres)" 2>/dev/null || echo starting)"
  [[ "${status}" == "healthy" ]] && break
  sleep 2
done
if [[ "${status}" != "healthy" ]]; then
  echo "PostgreSQL did not become healthy" >&2
  exit 1
fi

docker run --rm \
  --env-file "$ROOT/deploy/.env" \
  -e ARCHIVE_TEST_LOCAL_PG=true \
  -e ARCHIVE_TEST_SAMPLE=/samples/batch_00002.tar.gz \
  -e ARCHIVE_DB_URL="${ARCHIVE_DB_URL:-jdbc:postgresql://host.docker.internal:55432/archive_delivery}" \
  -e ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}" \
  -e ARCHIVE_DB_PASSWORD="${ARCHIVE_DB_PASSWORD:-archive_local}" \
  -v "$ROOT":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -v "$ROOT/../old/sample":/samples:ro \
  -w /workspace \
  "${IMAGE}" mvn -pl delivery-worker -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MvpOssEndToEndTest,RetryEndToEndTest test
