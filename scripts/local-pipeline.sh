#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# 本地门禁只执行代码、DAG 和状态逻辑，不读取真实 OSS/FTP，也不清理业务或外部数据。
python3 scripts/pipeline_registry.py validate
python3 -m unittest discover -s nodes/python -p 'test_*.py' -v
python3 -m unittest scripts/test_pipeline_registry.py -v
bash -n scripts/*.sh
docker compose -f deploy/docker-compose.production.yml --env-file deploy/env.production.example config --quiet
# 删除旧迁移或测试后，target 中的残留文件不能参与当前基线的建库和测试发现。
./scripts/maven-test.sh clean test
cd web
npm ci
npm run test
npm run build
