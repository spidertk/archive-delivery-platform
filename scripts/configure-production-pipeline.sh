#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 生产发布只切换受控配置和实体初始化分支，仍通过同一个 Pipeline API，不绕过版本不可变约束。
export ARCHIVE_PIPELINE_PROFILE=production
export ARCHIVE_PIPELINE_CONFIG="${ARCHIVE_PIPELINE_CONFIG:-$ROOT/deploy/pipeline.production.json}"
exec "$ROOT/scripts/configure-pipeline.sh" "$ARCHIVE_PIPELINE_CONFIG"
