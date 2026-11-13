#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# 本地门禁只执行代码、DAG 和状态逻辑，不读取真实 OSS/FTP，也不清理业务或外部数据。
python3 scripts/pipeline_registry.py validate
python3 -m unittest nodes/python/test_archive_repair.py scripts/test_pipeline_registry.py -v
bash -n scripts/*.sh
docker compose -f deploy/docker-compose.production.yml --env-file deploy/env.production.example config --quiet
./scripts/maven-test.sh test
cd web
npm ci
npm run test
npm run build
