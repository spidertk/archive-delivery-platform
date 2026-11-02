#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-}"
OUTPUT_DIR="${2:-}"
if [[ "$MODE" != "check" && "$MODE" != "before" && "$MODE" != "after" ]]; then
  echo "Usage: $0 check|before|after [output-dir]" >&2
  exit 1
fi
if [[ "$MODE" != "check" && -z "$OUTPUT_DIR" ]]; then
  echo "Output directory is required for $MODE" >&2
  exit 1
fi
mkdir -p "$OUTPUT_DIR"
LINES="$(docker compose -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/deploy/.env" exec -T postgres \
  psql -U "${ARCHIVE_DB_USER:-archive}" -d archive_delivery -Atc \
  "SELECT jsonb_path_query_first(pv.definition,'\$.nodes[*] ? (@.id==\"publish\").config.lines') FROM source_pipeline_binding b JOIN pipeline_version pv ON pv.id=b.pipeline_version_id WHERE b.effective_to IS NULL AND pv.status='PUBLISHED' ORDER BY b.effective_from DESC LIMIT 1")"
while IFS='|' read -r line host user key jump_host jump_user jump_key; do
  [[ -n "$line" ]] || continue
  key="$HOME/.ssh/archive-delivery-mvp/$(basename "$key")"
  if [[ -n "$jump_key" ]]; then jump_key="$HOME/.ssh/archive-delivery-mvp/$(basename "$jump_key")"; fi
  args=(-o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i "$key")
  if [[ -n "$jump_host" ]]; then
    args+=(-o "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 -o IdentitiesOnly=yes -i $jump_key -W %h:%p $jump_user@$jump_host")
  fi
  if [[ "$MODE" == "check" ]]; then
    ssh "${args[@]}" "$user@$host" 'command -v ossutil64 >/dev/null'
    echo "OK LINE-$line SSH and remote ossutil64"
    continue
  fi
  provider="june"
  if [[ "$line" -eq 2 ]]; then provider="june-1"; elif [[ "$line" -eq 3 ]]; then provider="june-2"; fi
  remote_root="/srv/maas-sftp/maasData/archive/$provider/2026-10-30"
  ssh "${args[@]}" "$user@$host" \
    "if test -d '$remote_root'; then find '$remote_root' -type f -printf '%P|%s|%T@\\n' | sort; fi" > "$OUTPUT_DIR/$MODE-$line"
done < <(python3 -c 'import json,sys; lines=json.load(sys.stdin); [print("|".join([str(i)]+[str(lines[str(i)].get(k,"")) for k in ("host","user","key","jumpHost","jumpUser","jumpKey")])) for i in range(1,4)]' <<<"$LINES")
