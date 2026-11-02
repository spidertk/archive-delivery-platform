#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_FILE="${1:-$ROOT/deploy/pipeline-v3.local.json}"
API="${ARCHIVE_API_URL:-http://127.0.0.1:8080}/api/v1"
if [[ ! -s "$CONFIG_FILE" ]]; then
  echo "Pipeline config is missing or empty: $CONFIG_FILE" >&2
  exit 1
fi
python3 - "$CONFIG_FILE" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as stream:
    definition = json.load(stream)
if definition.get("pipelineCode") != "archive-delivery":
    raise SystemExit("Invalid Pipeline code")
serialized = json.dumps(definition)
if "replace-" in serialized:
    raise SystemExit("Pipeline config contains placeholder values")
PY
versions_json="$(curl -fsS --connect-timeout 5 --max-time 15 "$API/pipelines/archive-delivery/versions")"
version_json="$(python3 -c 'import json,sys; rows=json.load(sys.stdin); assert rows; print(json.dumps(max(rows,key=lambda row:row["id"])))' <<<"$versions_json")"
base_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$version_json")"
status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$version_json")"
if [[ "$status" == "PUBLISHED" ]]; then
  version_json="$(curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
    -d "{\"baseVersionId\":$base_id}" "$API/pipelines/archive-delivery/versions")"
elif [[ "$status" != "DRAFT" ]]; then
  echo "Unsupported Pipeline version status: $status" >&2
  exit 1
fi
version_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$version_json")"
curl -fsS --connect-timeout 5 --max-time 30 -X PUT -H 'Content-Type: application/json' \
  --data-binary "@$CONFIG_FILE" "$API/pipeline-versions/$version_id/definition" >/dev/null
curl -fsS --connect-timeout 5 --max-time 30 -X POST "$API/pipeline-versions/$version_id/publish" >/dev/null
printf 'Published Pipeline version %s\n' "$version_id"
