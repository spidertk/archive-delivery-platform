#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${ARCHIVE_PIPELINE_PROFILE:-local}"
if [[ "$PROFILE" == "production" ]]; then
  DEFAULT_CONFIG="$ROOT/deploy/pipeline.production.json"
  EXAMPLE_CONFIG="deploy/pipeline.production.example.json"
else
  DEFAULT_CONFIG="$ROOT/deploy/pipeline.local.json"
  EXAMPLE_CONFIG="deploy/pipeline.example.json"
fi
CONFIG_FILE="${1:-${ARCHIVE_PIPELINE_CONFIG:-$DEFAULT_CONFIG}}"
API="${ARCHIVE_API_URL:-http://127.0.0.1:8080}/api/v1"
set -a
source "$ROOT/deploy/.env"
set +a
if [[ ! -s "$CONFIG_FILE" ]]; then
  echo "Pipeline config is missing; copy $EXAMPLE_CONFIG to ${CONFIG_FILE#$ROOT/} and configure it." >&2
  exit 1
fi
# 节点、合并上限、路径模板和凭据集中在同一版本；只从受控变量补齐掩码，日志不输出定义或 Secret。
python3 - "$CONFIG_FILE" "$PROFILE" <<'PY'
import json, os, sys
path, profile = sys.argv[1:]
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
        if config.get(field) in (None, "", "********", "replace-access-key", "replace-secret") and os.environ.get(name):
            config[field] = os.environ[name]
ftp = next(node for node in definition["nodes"] if node["type"] == "ftp.download-verify")
for index in range(1, 4):
    if profile == "production":
        account = os.environ.get(f"PROD_FTP_ENTITY_{index}_ACCOUNT", ("june", "june-1", "june-2")[index - 1])
        value = os.environ.get(f"PROD_FTP_ENTITY_{index}_PASSWORD", "")
    else:
        account = f"entity{index}"
        value = os.environ.get(f"LOCAL_FTP_ENTITY_{index}_PASSWORD", f"local_entity{index}_verify")
    if ftp["config"].setdefault("ftpCredential", {}).get(account) in (None, "", "********", "replace-ftp-password"):
        ftp["config"]["ftpCredential"][account] = value
with open(path, "w", encoding="utf-8") as stream:
    json.dump(definition, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
assert definition.get("pipelineCode") == "archive-delivery"
assert definition["config"]["selection"]["limit"] > 0
assert definition["config"]["assignment"]["strategySource"] == "SUBMIT_TARGETS"
assert [node["type"] for node in definition["nodes"]] == ["oss.download", "package.merge", "python.repair", "manifest.qualify", "oss.upload", "ftp.download-verify"]
max_package_bytes = definition["config"]["assignment"].get("maxPackageBytes")
assert isinstance(max_package_bytes, int) and max_package_bytes > 0
assert set(definition["nodeDefinitions"]) == {node["type"] for node in definition["nodes"]}
assert definition["ruleSets"]
serialized = json.dumps(definition)
if "replace-" in serialized or "********" in serialized:
    raise SystemExit("Pipeline config contains placeholder/masked values; provide the controlled OSS/FTP credentials")
if "ssh." in serialized or "jumpHost" in serialized or "ossutil" in serialized:
    raise SystemExit("SSH jump-host and ossutil configuration are forbidden")
print(f"Validated {profile} Pipeline: selectionLimit={definition['config']['selection']['limit']} maxPackageBytes={max_package_bytes} nodes={len(definition['nodes'])}")
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
printf 'Published PipelineVersion profile=%s versionId=%s.\n' "$PROFILE" "$VERSION_ID"
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
  python3 - "$PROFILE" "$ENTITY" "$TEMP/entity-$ENTITY.json" <<'PY'
import json, os, sys
profile, entity, output = sys.argv[1], int(sys.argv[2]), sys.argv[3]
if profile == "production":
    vendor = ("june", "june-1", "june-2")[entity - 1]
    host = ("8.219.218.145", "47.84.235.96", "8.222.228.188")[entity - 1]
    account = os.environ.get(f"PROD_FTP_ENTITY_{entity}_ACCOUNT", vendor)
    payload = {"vendorCode": vendor, "entityCode": f"PROD-{vendor.upper()}", "entityName": f"生产交付实体 {vendor}",
               "protocol": "FTP", "accountName": account, "workerGroup": os.environ.get(f"ENTITY_{entity}_WORKER_GROUP", "group1"),
               "endpointLocator": {"schemaVersion": 1, "host": host, "port": int(os.environ.get(f"PROD_FTP_ENTITY_{entity}_PORT", "21")),
                                   "rootPath": os.environ.get(f"PROD_FTP_ENTITY_{entity}_ROOT_PATH", "/")}}
else:
    payload = {"vendorCode": "local-other" if entity == 3 else "alibaba", "entityCode": ("jun", "jun2", "jun3")[entity - 1], "entityName": f"本地 FTP 验证实体 {entity}",
               "protocol": "FTP", "accountName": f"entity{entity}", "workerGroup": os.environ.get(f"ENTITY_{entity}_WORKER_GROUP", "group1"),
               "endpointLocator": {"schemaVersion": 1, "host": f"ftp-entity-{entity}", "port": 21, "rootPath": "/"}}
json.dump(payload, open(output, "w"), ensure_ascii=False)
PY
  if ! python3 - "$TEMP/entities.json" "$TEMP/entity-$ENTITY.json" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))
expected = json.load(open(sys.argv[2]))
raise SystemExit(0 if any(row["vendorCode"] == expected["vendorCode"] and row["entityCode"] == expected["entityCode"] for row in rows) else 1)
PY
  then
    curl -fsS --connect-timeout 5 --max-time 15 -H 'Content-Type: application/json' \
      --data-binary "@$TEMP/entity-$ENTITY.json" "$API/delivery-entities" -o /dev/null
  fi
  python3 - "$TEMP/entity-$ENTITY.json" <<'PY'
import json, sys
row = json.load(open(sys.argv[1]))
endpoint = row["endpointLocator"]
print(f"Entity ready vendorCode={row['vendorCode']} entityCode={row['entityCode']} account={row['accountName']} workerGroup={row['workerGroup']} host={endpoint['host']} rootPath={endpoint['rootPath']}")
PY
done
printf 'Pipeline initialization completed profile=%s versionId=%s sourceId=%s entities=3.\n' "$PROFILE" "$VERSION_ID" "$SOURCE_ID"
