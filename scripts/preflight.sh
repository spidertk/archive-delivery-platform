#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a
source "$ROOT/deploy/.env"
set +a
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/deploy/.env")
"${COMPOSE[@]}" exec -T postgres pg_isready -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery >/dev/null
PUBLISHED="$("${COMPOSE[@]}" exec -T postgres psql -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery -Atc \
  "SELECT count(*) FROM archive_delivery.pipeline_version WHERE status='PUBLISHED'")"
[[ "$PUBLISHED" -gt 0 ]] || { echo "FAIL no published PipelineVersion; run configure-pipeline.sh" >&2; exit 1; }
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy" >/dev/null
run_preflight() {
  docker run --rm --network deploy_default \
    -e ARCHIVE_PREFLIGHT=true \
    -e ARCHIVE_DB_URL='jdbc:postgresql://postgres:5432/archive_delivery?currentSchema=archive_delivery' \
    -e ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}" -e ARCHIVE_DB_PASSWORD="${ARCHIVE_DB_PASSWORD:-archive_local}" \
    -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
    archive-delivery-maven:test mvn -q -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false \
    '-Dtest=RealExternalVerificationTest#preflight' test
}
# FTP 容器首次初始化账户需要短暂时间；其他预检失败也在有界次数后停止，不进入真实交付。
for attempt in $(seq 1 12); do
  if run_preflight; then
    echo "Preflight passed: direct source/target OSS and three local FTP accounts verified."
    exit 0
  fi
  [[ "$attempt" -lt 12 ]] || break
  sleep 5
done
echo "Preflight failed; real delivery was not submitted." >&2
exit 1
