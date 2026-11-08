#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

from archive_repair import safe_extract


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def merge(sources, target, manifest_path):
    """每个源包只解包一次；同名目录以来源序号隔离成员键，同时保留原始路径和来源血缘。"""
    sources = [Path(source).resolve(strict=True) for source in sources]
    target = Path(target).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    for stale in target.parent.glob(".archive_merge_*"):
        if stale.is_dir():
            shutil.rmtree(stale)
            print(f"MERGE_STALE_CLEANED path={stale}", flush=True)
    temp_dir = Path(tempfile.mkdtemp(prefix=".archive_merge_", dir=target.parent))
    workspace = temp_dir / "requests"
    workspace.mkdir()
    source_facts = []
    members = []
    member_keys = set()
    try:
        for index, source in enumerate(sources, start=1):
            extract_dir = temp_dir / f"source-{index:05d}"
            extract_dir.mkdir()
            safe_extract(source, extract_dir)
            names = sorted(path.name for path in extract_dir.iterdir() if path.is_dir())
            if not names:
                raise ValueError(f"Source archive contains no request directories: {source.name}")
            for name in names:
                member_key = name
                if member_key in member_keys:
                    member_key = f"source-{index:05d}__{name}"
                    suffix = 2
                    while member_key in member_keys:
                        member_key = f"source-{index:05d}-{suffix}__{name}"
                        suffix += 1
                member_keys.add(member_key)
                os.replace(extract_dir / name, workspace / member_key)
                members.append({"memberKey": member_key, "originalPath": name,
                                "sourceSeq": index, "sourceFileName": source.name})
            source_facts.append({"sourceSeq": index, "fileName": source.name,
                                 "sizeBytes": source.stat().st_size, "sha256": sha256(source),
                                 "requestCount": len(names)})
            print(f"MERGE_SOURCE seq={index} file={source.name} requests={len(names)} bytes={source.stat().st_size}", flush=True)
        if target.exists():
            shutil.rmtree(target)
        os.replace(workspace, target)
        result = {"protocolVersion": 3, "workspacePath": str(target), "requestCount": len(members),
                  "sourceCount": len(sources), "sourceBytes": sum(fact["sizeBytes"] for fact in source_facts),
                  "sources": source_facts, "members": members}
        manifest_path = Path(manifest_path).resolve()
        temporary_manifest = manifest_path.with_name(manifest_path.name + ".tmp")
        with temporary_manifest.open("w", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_manifest, manifest_path)
        print(f"MERGE_COMPLETED sources={len(sources)} requests={len(members)} workspace={target}", flush=True)
        return result
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", action="append", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()
    result = merge(args.source, args.target, args.manifest)
    print("MERGE_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
