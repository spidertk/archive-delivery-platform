#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 1 || ! "$1" =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 deliveryId" >&2
  exit 64
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a
source "$ROOT/deploy/.env"
set +a
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy" >/dev/null
# 验证容器只从 PG 读取当前 Pipeline 凭据，不打印 definition；archive_data 以只读方式核对 Worker 最终下载。
docker run --rm --network deploy_default \
  -e ARCHIVE_REAL_E2E=true -e ARCHIVE_VERIFY_DELIVERY_ID="$1" \
  -e ARCHIVE_DB_URL='jdbc:postgresql://postgres:5432/archive_delivery?currentSchema=archive_delivery' \
  -e ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}" -e ARCHIVE_DB_PASSWORD="${ARCHIVE_DB_PASSWORD:-archive_local}" \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -v deploy_archive_data:/data:ro -w /workspace \
  archive-delivery-maven:test mvn -q -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=RealExternalVerificationTest#verifyRealDelivery' test
