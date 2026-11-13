#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="production"
DEFAULT_CONFIG="$ROOT/deploy/pipeline.production.json"
EXAMPLE_CONFIG="deploy/pipelines/archive-delivery-jun.md"
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
python3 - "$CONFIG_FILE" "$PROFILE" "$ROOT" <<'PY'
import json, os, pathlib, sys
path, profile, root = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    definition = json.load(stream)
for node in definition["nodes"]:
    if profile == "production" and node["type"] == "python.repair":
        node["config"].setdefault("gzipCompressLevel", 5)
    if node["type"] == "oss.download":
        prefix = "OSS_SOURCE"
    elif node["type"] == "oss.upload":
        prefix = "TARGET_OSS"
    else:
        continue
    config = node["config"]
    for field, suffix in (("accessKeyId", "ACCESS_KEY_ID"), ("accessKeySecret", "ACCESS_KEY_SECRET")):
        name = f"{prefix}_{suffix}"
        value = config.get(field)
        if (profile == "production" or value is None or value == "" or "********" in value or value.startswith("replace-")) and os.environ.get(name):
            config[field] = os.environ[name]
ftp = next(node for node in definition["nodes"] if node["type"] == "ftp.download-verify")
entity_count = int(os.environ.get("PROD_ENTITY_COUNT", "1"))
for index in range(1, entity_count + 1):
    account = os.environ.get(f"PROD_ENTITY_{index}_ACCOUNT", "")
    value = os.environ.get(f"PROD_FTP_ENTITY_{index}_PASSWORD", "")
    if not account or not value:
        raise SystemExit(f"Production entity {index} account/password is required")
    ftp["config"].setdefault("ftpCredential", {})[account] = value
if profile == "production":
    accounts = [os.environ[f"PROD_ENTITY_{index}_ACCOUNT"] for index in range(1, entity_count + 1)]
    ftp["config"]["ftpCredential"] = {account: ftp["config"]["ftpCredential"][account] for account in accounts}
with open(path, "w", encoding="utf-8") as stream:
    json.dump(definition, stream, ensure_ascii=False, indent=2)
    stream.write("\n")
assert definition.get("pipelineCode") and definition["pipelineCode"][0].isalnum()
assert "ruleSets" not in definition and "selection" not in definition["config"]
assert definition["config"]["assignment"]["strategySource"] == "SUBMIT_TARGETS"
assert all(node.get("type") for node in definition["nodes"])
assert all("ruleSet" not in node["config"] for node in definition["nodes"])
python_nodes = {"package.merge", "python.repair", "ftp.download-verify"}
script_root = pathlib.Path(root, "nodes", "python").resolve()
for node in definition["nodes"]:
    if node["type"] not in python_nodes:
        continue
    value = node["config"].get("scriptPath", "")
    relative = pathlib.PurePosixPath(value)
    if not value or relative.is_absolute() or relative.as_posix() != value or ".." in relative.parts or relative.suffix != ".py":
        raise SystemExit(f"{node['type']} scriptPath must be a normalized relative .py path")
    script_file = script_root.joinpath(*relative.parts).resolve()
    if not script_file.is_relative_to(script_root) or not script_file.is_file():
        raise SystemExit(f"{node['type']} script does not exist below nodes/python: {value}")
max_package_bytes = definition["config"]["assignment"].get("maxPackageBytes")
assert isinstance(max_package_bytes, int) and max_package_bytes > 0
assert set(definition["nodeDefinitions"]) == {node["type"] for node in definition["nodes"]}
serialized = json.dumps(definition)
if "replace-" in serialized or "********" in serialized:
    raise SystemExit("Pipeline config contains placeholder/masked values; provide the controlled OSS/FTP credentials")
if "ssh." in serialized or "jumpHost" in serialized or "ossutil" in serialized:
    raise SystemExit("SSH jump-host and ossutil configuration are forbidden")
print(f"Validated {profile} Pipeline: target-driven selection maxPackageBytes={max_package_bytes} nodes={len(definition['nodes'])}")
PY
PIPELINE_CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["pipelineCode"])' "$CONFIG_FILE")"
TEMP="$(mktemp -d)"
trap 'rm -f "$TEMP"/*.json; rmdir "$TEMP"' EXIT
curl -fsS --connect-timeout 5 --max-time 15 "$API/pipelines/$PIPELINE_CODE/versions" -o "$TEMP/versions.json"
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
    -d "{\"baseVersionId\":$BASE_ID}" "$API/pipelines/$PIPELINE_CODE/versions" -o "$TEMP/draft.json"
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
ENTITY_COUNT=3
if [[ "$PROFILE" == "production" ]]; then ENTITY_COUNT="${PROD_ENTITY_COUNT:-1}"; fi
for ((ENTITY=1; ENTITY<=ENTITY_COUNT; ENTITY++)); do
  python3 - "$PROFILE" "$ENTITY" "$TEMP/entity-$ENTITY.json" <<'PY'
import json, os, sys
profile, entity, output = sys.argv[1], int(sys.argv[2]), sys.argv[3]
def required(name):
    value = os.environ.get(name, "")
    if not value:
        raise SystemExit(f"{name} is required")
    return value
vendor = required(f"PROD_ENTITY_{entity}_VENDOR_CODE")
entity_code = required(f"PROD_ENTITY_{entity}_ENTITY_CODE")
account = required(f"PROD_ENTITY_{entity}_ACCOUNT")
host = required(f"PROD_ENTITY_{entity}_HOST")
payload = {"vendorCode": vendor, "entityCode": entity_code,
           "entityName": os.environ.get(f"PROD_ENTITY_{entity}_NAME", f"生产交付实体 {entity_code}"),
           "protocol": "FTP", "accountName": account, "workerGroup": os.environ.get(f"ENTITY_{entity}_WORKER_GROUP", "group1"),
           "endpointLocator": {"schemaVersion": 1, "host": host, "port": int(os.environ.get(f"PROD_FTP_ENTITY_{entity}_PORT", "21")),
                               "rootPath": os.environ.get(f"PROD_FTP_ENTITY_{entity}_ROOT_PATH", "/")}}
json.dump(payload, open(output, "w"), ensure_ascii=False)
PY
  if ! python3 - "$TEMP/entities.json" "$TEMP/entity-$ENTITY.json" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))
expected = json.load(open(sys.argv[2]))
matched = [row for row in rows if row["vendorCode"] == expected["vendorCode"] and row["entityCode"] == expected["entityCode"]]
if matched:
    actual = matched[0]
    for field in ("accountName", "workerGroup", "endpointLocator"):
        if actual[field] != expected[field]:
            raise SystemExit(f"Existing entity {expected['vendorCode']}/{expected['entityCode']} has different {field}; refusing implicit reconfiguration")
raise SystemExit(0 if matched else 1)
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
printf 'Pipeline initialization completed profile=%s versionId=%s sourceId=%s entities=%s.\n' "$PROFILE" "$VERSION_ID" "$SOURCE_ID" "$ENTITY_COUNT"
