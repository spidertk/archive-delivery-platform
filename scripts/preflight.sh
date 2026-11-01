#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "FAIL deploy/.env is missing; copy deploy/env.example and provide real settings." >&2
  exit 1
fi
set -a
source "$ENV_FILE"
set +a

required() {
  local value="${!1:-}"
  if [[ -z "$value" || "$value" == replace-* ]]; then
    echo "FAIL $1 is not configured" >&2
    exit 1
  fi
}

docker info --format '{{.ServerVersion}}' >/dev/null
echo "OK Docker daemon"
docker compose version >/dev/null
echo "OK Docker Compose"

ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}"
docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ENV_FILE" exec -T postgres \
  pg_isready -U "$ARCHIVE_DB_USER" -d archive_delivery >/dev/null
echo "OK PostgreSQL archive_delivery"

missing=0
for name in OSS_SOURCE_ENDPOINT OSS_SOURCE_ACCESS_KEY_ID OSS_SOURCE_ACCESS_KEY_SECRET OSS_SOURCE_BUCKET OSS_SOURCE_PREFIX \
  OSS_HZ_ENDPOINT OSS_HZ_ACCESS_KEY_ID OSS_HZ_ACCESS_KEY_SECRET OSS_HZ_BUCKET HZ_OSS_TEST_PREFIX \
  OSS_SG_ENDPOINT OSS_SG_ACCESS_KEY_ID OSS_SG_ACCESS_KEY_SECRET OSS_SG_BUCKET OSS_SG_HEAD_KEY; do
  value="${!name:-}"
  if [[ -z "$value" || "$value" == replace-* ]]; then
    echo "FAIL $name is not configured" >&2
    missing=1
  fi
done
if [[ "$missing" -ne 0 ]]; then
  echo "Preflight stopped before OSS or SSH requests. Complete deploy/.env from deploy/env.example." >&2
  exit 1
fi

docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy" >/dev/null
docker run --rm --env-file "$ENV_FILE" -e ARCHIVE_PREFLIGHT=true \
  -e OSS_SOURCE_BUCKET -e OSS_SOURCE_PREFIX -e HZ_OSS_TEST_PREFIX -e OSS_SG_HEAD_KEY \
  -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
  archive-delivery-maven:test mvn -q -pl delivery-core -Dtest=ProductionPreflightTest test

for line in 1 2 3; do
  for suffix in SSH_HOST SSH_USER SSH_KEY; do
    required "FTP_LINE_${line}_${suffix}"
  done
  host_var="FTP_LINE_${line}_SSH_HOST"
  user_var="FTP_LINE_${line}_SSH_USER"
  key_var="FTP_LINE_${line}_SSH_KEY"
  jump_host_var="FTP_LINE_${line}_JUMP_HOST"
  jump_user_var="FTP_LINE_${line}_JUMP_USER"
  jump_key_var="FTP_LINE_${line}_JUMP_KEY"
  args=(-o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i "${!key_var}")
  if [[ -n "${!jump_host_var:-}" && "${!jump_host_var}" != replace-* ]]; then
    required "$jump_user_var"
    required "$jump_key_var"
    args+=(-o "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i ${!jump_key_var} -W %h:%p ${!jump_user_var}@${!jump_host_var}")
  fi
  ssh "${args[@]}" "${!user_var}@${!host_var}" 'command -v ossutil64 >/dev/null'
  echo "OK LINE-$line SSH and remote ossutil64 (${!host_var})"
done

echo "Preflight passed. No delivery data was modified."
