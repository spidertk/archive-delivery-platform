#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 本地入口沿用完整真实 case；其双确认重置和外部写边界由原有脚本统一控制。
exec "$ROOT/scripts/real-e2e-test.sh" "$@"
