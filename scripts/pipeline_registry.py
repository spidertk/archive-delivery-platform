#!/usr/bin/env python3
import argparse
import datetime
import json
import os
import re
import shlex
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BLOCK = re.compile(r"```json\s+(pipeline-definition|golden-case)\s*\n(.*?)```", re.DOTALL)
REQUIRED_CHECKS = {"SOURCE_GET", "TARGET_GET", "FTP_GET", "SIZE_SHA", "NO_PARTIAL"}


def load_case(path):
    text = path.read_text(encoding="utf-8")
    blocks = {kind: body for kind, body in BLOCK.findall(text)}
    if set(blocks) != {"pipeline-definition", "golden-case"}:
        raise ValueError(f"{path}: exactly one pipeline-definition and one golden-case JSON block are required")
    definition = json.loads(blocks["pipeline-definition"])
    golden = json.loads(blocks["golden-case"])
    case_id = golden.get("caseId", "")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,63}", case_id):
        raise ValueError(f"{path}: caseId must be a stable lowercase kebab-case identifier")
    if definition.get("pipelineCode") != golden.get("pipelineCode"):
        raise ValueError(f"{path}: pipelineCode differs between definition and golden case")
    if not definition["pipelineCode"] or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", definition["pipelineCode"]):
        raise ValueError(f"{path}: invalid pipelineCode")
    nodes = definition.get("nodes", [])
    if not nodes or len({node["id"] for node in nodes}) != len(nodes):
        raise ValueError(f"{path}: nodes must be non-empty with unique ids")
    if set(definition.get("nodeDefinitions", {})) != {node["type"] for node in nodes}:
        raise ValueError(f"{path}: nodeDefinitions must exactly match the node types")
    for node in nodes:
        script = node.get("config", {}).get("scriptPath")
        if script and (Path(script).is_absolute() or ".." in Path(script).parts or not script.endswith(".py")):
            raise ValueError(f"{path}: unsafe scriptPath {script}")
    assignment = definition.get("config", {}).get("assignment", {})
    minimum, maximum = assignment.get("minPackageBytes"), assignment.get("maxPackageBytes")
    if assignment.get("strategySource") != "SUBMIT_TARGETS" or not isinstance(minimum, int) or not isinstance(maximum, int):
        raise ValueError(f"{path}: invalid assignment configuration")
    if minimum <= 0 or minimum > maximum:
        raise ValueError(f"{path}: minPackageBytes must be positive and no greater than maxPackageBytes")
    targets = golden.get("targets", [])
    if not targets:
        raise ValueError(f"{path}: at least one target is required")
    business_keys = set()
    for target in targets:
        required = {"vendorCode", "entityCode", "accountName", "workerGroup", "host", "hostRoot", "targetCount"}
        if not required.issubset(target):
            raise ValueError(f"{path}: target fields {sorted(required)} are required")
        key = (target["vendorCode"], target["entityCode"])
        if key in business_keys or not isinstance(target["targetCount"], int) or target["targetCount"] <= 0:
            raise ValueError(f"{path}: targets require unique vendorCode/entityCode and positive targetCount")
        host_root = Path(target["hostRoot"])
        if not host_root.is_absolute() or ".." in host_root.parts:
            raise ValueError(f"{path}: hostRoot must be an absolute normalized directory without traversal")
        business_keys.add(key)
    expected = golden.get("expected", {})
    if expected.get("deliveryStatus") != "DELIVERED" or expected.get("taskCount") != sum(t["targetCount"] for t in targets):
        raise ValueError(f"{path}: expected taskCount/deliveryStatus does not match targets")
    if expected.get("nodeTypes") != [node["type"] for node in nodes]:
        raise ValueError(f"{path}: expected nodeTypes must match the DAG order")
    if not REQUIRED_CHECKS.issubset(set(expected.get("externalChecks", []))):
        raise ValueError(f"{path}: every golden case requires {sorted(REQUIRED_CHECKS)}")
    serialized = json.dumps({"definition": definition, "golden": golden}, ensure_ascii=False)
    if "********" in serialized or "BEGIN PRIVATE KEY" in serialized:
        raise ValueError(f"{path}: secrets are not allowed in Markdown pipelines")
    return {"path": path, "caseId": case_id, "definition": definition, "golden": golden}


def cases(root=ROOT):
    files = sorted((root / "deploy" / "pipelines").glob("*.md"))
    if not files:
        raise ValueError("No Markdown pipelines found below deploy/pipelines")
    rows = [load_case(path) for path in files]
    identifiers = [row["caseId"] for row in rows]
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("caseId values must be globally unique")
    return rows


def select(case_id, root=ROOT):
    matches = [row for row in cases(root) if row["caseId"] == case_id]
    if not matches:
        raise ValueError(f"Unknown pipeline case: {case_id}")
    return matches[0]


def emit_shell(case):
    golden = case["golden"]
    targets = golden["targets"]
    values = {
        "PROD_ENTITY_COUNT": len(targets),
        "PROD_TARGET_COUNT": sum(target["targetCount"] for target in targets),
        "REGISTRY_DELIVERY_BATCH": "PROD-" + case["caseId"].upper() + "-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"),
    }
    for index, target in enumerate(targets, 1):
        values[f"PROD_ENTITY_{index}_VENDOR_CODE"] = target["vendorCode"]
        values[f"PROD_ENTITY_{index}_ENTITY_CODE"] = target["entityCode"]
        values[f"PROD_ENTITY_{index}_ACCOUNT"] = target["accountName"]
        values[f"PROD_ENTITY_{index}_HOST"] = target["host"]
        values[f"ENTITY_{index}_WORKER_GROUP"] = target["workerGroup"]
        values[f"PROD_FTP_ENTITY_{index}_ROOT_PATH"] = target.get("rootPath", "/")
        values[f"PROD_FTP_ENTITY_{index}_PORT"] = target.get("port", 21)
    for key, value in values.items():
        print(f"export {key}={shlex.quote(str(value))}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("validate", "list", "show", "materialize", "env"))
    parser.add_argument("case_id", nargs="?")
    args = parser.parse_args()
    if args.command == "validate":
        rows = cases()
        print(f"Validated {len(rows)} Markdown pipelines and their local/golden verification contracts.")
        return
    if args.command == "list":
        for row in cases():
            print(row["caseId"])
        return
    if not args.case_id:
        parser.error(f"{args.command} requires case_id")
    case = select(args.case_id)
    if args.command == "show":
        print(json.dumps(case["golden"], ensure_ascii=False, indent=2))
    elif args.command == "materialize":
        (ROOT / "deploy" / "pipeline.production.json").write_text(
            json.dumps(case["definition"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (ROOT / "deploy" / "golden-case.json").write_text(
            json.dumps(case["golden"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.chmod(ROOT / "deploy" / "pipeline.production.json", 0o600)
        os.chmod(ROOT / "deploy" / "golden-case.json", 0o600)
        print(f"Materialized pipeline case {case['caseId']} from {case['path'].name}")
    elif args.command == "env":
        emit_shell(case)


if __name__ == "__main__":
    main()
