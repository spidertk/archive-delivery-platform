#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_FILE="${1:-$ROOT/deploy/pipeline.local.json}"
API="${ARCHIVE_API_URL:-http://127.0.0.1:8080}/api/v1"
set -a
source "$ROOT/deploy/.env"
set +a
if [[ ! -s "$CONFIG_FILE" ]]; then
  echo "Pipeline config is missing; copy deploy/pipeline.example.json to deploy/pipeline.local.json and configure it." >&2
  exit 1
fi
# 节点定义、规则、DAG 和凭据集中在同一版本；只从显式受控变量补齐掩码且不打印定义。
python3 - "$CONFIG_FILE" <<'PY'
import json, os, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as stream:
    definition = json.load(stream)
for node in definition["nodes"]:
    if node["type"] == "oss.download":
        prefix = "SOURCE_OSS"
    elif node["type"] == "oss.upload":
        prefix = "TARGET_OSS"
    else:
        continue
    config = node["config"]
    for field, suffix in (("accessKeyId", "ACCESS_KEY_ID"), ("accessKeySecret", "ACCESS_KEY_SECRET")):
        name = f"{prefix}_{suffix}"
        if config.get(field) in (None, "", "********") and os.environ.get(name):
            config[field] = os.environ[name]
ftp = next(node for node in definition["nodes"] if node["type"] == "ftp.download-verify")
for index in range(1, 4):
    account = f"entity{index}"
    value = os.environ.get(f"LOCAL_FTP_ENTITY_{index}_PASSWORD", f"local_entity{index}_verify")
    if ftp["config"].get("ftpCredential", {}).get(account) in (None, "", "********", "replace-ftp-password"):
        ftp["config"]["ftpCredential"][account] = value
with open(path, "w", encoding="utf-8") as stream:
    json.dump(definition, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
assert definition.get("pipelineCode") == "archive-delivery"
assert definition["config"]["selection"]["limit"] == 3
assert definition["config"]["assignment"]["strategySource"] == "SUBMIT_TARGETS"
assert [node["type"] for node in definition["nodes"]] == ["oss.download", "python.repair", "manifest.qualify", "oss.upload", "ftp.download-verify"]
assert set(definition["nodeDefinitions"]) == {node["type"] for node in definition["nodes"]}
assert definition["ruleSets"]
serialized = json.dumps(definition)
if "replace-" in serialized or "********" in serialized:
    raise SystemExit("Pipeline config contains placeholder/masked values; provide the controlled OSS/FTP credentials")
if "ssh." in serialized or "jumpHost" in serialized:
    raise SystemExit("SSH jump-host configuration is forbidden")
PY
TEMP="$(mktemp -d)"
trap 'rm -f "$TEMP"/*.json; rmdir "$TEMP"' EXIT
curl -fsS --connect-timeout 5 --max-time 15 "$API/pipelines/archive-delivery/versions" -o "$TEMP/versions.json"
python3 - "$TEMP" <<'PY'
import json, pathlib, sys
rows = json.loads((pathlib.Path(sys.argv[1]) / "versions.json").read_text())
assert rows
row = max(rows, key=lambda item: item["id"])
(pathlib.Path(sys.argv[1]) / "current.json").write_text(json.dumps(row))
PY
BASE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' < "$TEMP/current.json")"
STATUS="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' < "$TEMP/current.json")"
if [[ "$STATUS" == "PUBLISHED" ]]; then
  curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
    -d "{\"baseVersionId\":$BASE_ID}" "$API/pipelines/archive-delivery/versions" -o "$TEMP/draft.json"
elif [[ "$STATUS" == "DRAFT" ]]; then
  cp "$TEMP/current.json" "$TEMP/draft.json"
else
  echo "Unsupported Pipeline version status: $STATUS" >&2
  exit 1
fi
VERSION_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' < "$TEMP/draft.json")"
curl -fsS --connect-timeout 5 --max-time 30 -X PUT -H 'Content-Type: application/json' \
  --data-binary "@$CONFIG_FILE" "$API/pipeline-versions/$VERSION_ID/definition" -o /dev/null
curl -fsS --connect-timeout 5 --max-time 30 -X POST "$API/pipeline-versions/$VERSION_ID/publish" -o /dev/null
python3 - "$CONFIG_FILE" "$TEMP/source.json" <<'PY'
import json, sys
definition = json.load(open(sys.argv[1]))
config = next(node["config"] for node in definition["nodes"] if node["type"] == "oss.download")
uri = "oss://" + config["bucket"] + "/" + config["prefix"]
json.dump({"ossUri": uri, "channelCode": "ARCHIVE_SOURCE", "ossType": "ALIBABA_CLOUD"}, open(sys.argv[2], "w"))
PY
curl -fsS --connect-timeout 5 --max-time 15 "$API/ingest-sources" -o "$TEMP/sources.json"
SOURCE_ID="$(python3 - "$TEMP" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
rows = json.loads((p / "sources.json").read_text())
expected = json.loads((p / "source.json").read_text())["ossUri"]
print(next((row["sourceId"] for row in rows if row["ossUri"] == expected), ""))
PY
)"
if [[ -z "$SOURCE_ID" ]]; then
  curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
    --data-binary "@$TEMP/source.json" "$API/ingest-sources" -o "$TEMP/source-created.json"
  SOURCE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sourceId"])' < "$TEMP/source-created.json")"
fi
curl -fsS --connect-timeout 5 --max-time 15 "$API/delivery-entities" -o "$TEMP/entities.json"
for ENTITY in 1 2 3; do
  if ! python3 - "$TEMP/entities.json" "$ENTITY" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))
raise SystemExit(0 if any(row["entityCode"] == "ENTITY-" + sys.argv[2] for row in rows) else 1)
PY
  then
    python3 - "$ENTITY" "$TEMP/entity-$ENTITY.json" <<'PY'
import json, os, sys
entity = int(sys.argv[1])
json.dump({"vendorCode": "LOCAL-FTP", "entityCode": f"ENTITY-{entity}", "entityName": f"本地 FTP 验证实体 {entity}",
           "protocol": "FTP", "accountName": f"entity{entity}", "workerGroup": os.environ.get(f"ENTITY_{entity}_WORKER_GROUP", "group1"),
           "endpointLocator": {"schemaVersion": 1, "host": f"ftp-entity-{entity}", "port": 21, "rootPath": "/"}}, open(sys.argv[2], "w"))
PY
    curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
      --data-binary "@$TEMP/entity-$ENTITY.json" "$API/delivery-entities" -o /dev/null
  fi
done
printf 'Published PipelineVersion %s with source %s and three FTP delivery entities.\n' "$VERSION_ID" "$SOURCE_ID"
