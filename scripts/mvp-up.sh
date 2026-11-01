#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
docker compose -f deploy/docker-compose.yml up -d --build worker
docker compose -f deploy/docker-compose.yml logs -f worker
