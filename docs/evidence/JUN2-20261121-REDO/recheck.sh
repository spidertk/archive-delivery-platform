#!/usr/bin/env bash
set -euo pipefail
cd /opt/archive-delivery-platform
# 复核固定到本轮 ID 与来源；受控凭据由现有 .env 注入，FTP 使用测试内的只读模式。
set -a
source deploy/.env
set +a
docker run --rm --memory=1400m --network "${COMPOSE_PROJECT_NAME}_default" \
  -e JAVA_TOOL_OPTIONS=-Xmx512m \
  -e ARCHIVE_VERIFICATION_PROFILE=production \
  -e ARCHIVE_REAL_E2E=true \
  -e ARCHIVE_VERIFY_DELIVERY_ID=4 \
  -e ARCHIVE_VERIFY_EXPECTED_TASKS=89 \
  -e ARCHIVE_VERIFY_EXPECTED_ENTITIES=1 \
  -e ARCHIVE_VERIFY_ALLOW_CLEANED_WORK=true \
  -e ARCHIVE_VERIFY_SOURCE_URI=oss://sg-storage-20261013-6vmlk/internal/archive-delivery-migrations/DO_20261117_00001/remaining-343/products/ \
  -e 'ARCHIVE_DB_URL=jdbc:postgresql://postgres:5432/archive_delivery?currentSchema=archive_delivery' \
  -e ARCHIVE_DB_USER -e ARCHIVE_DB_PASSWORD \
  -v "$PWD":/workspace -v /root/.m2:/root/.m2 \
  -v "${COMPOSE_PROJECT_NAME}_archive_data":/data:ro -w /workspace \
  archive-delivery-maven:test mvn -q -pl delivery-worker -am \
  -Dsurefire.failIfNoSpecifiedTests=false '-Dtest=RealExternalVerificationTest#verifyRealDelivery' test
