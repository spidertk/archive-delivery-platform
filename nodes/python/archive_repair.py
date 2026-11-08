#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import tarfile
import tempfile
from pathlib import Path

from legacy_fix_archive import fix_request, verify_output

BATCH_PATTERN = re.compile(r"^batch_\d{5}\.tar\.gz$")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def request_directories(source):
    source = Path(source)
    if not source.is_dir():
        raise ValueError("Repair source must be the merged request directory")
    names = set()
    for path in source.iterdir():
        if path.is_symlink() or path.name in ("", ".", ".."):
            raise ValueError("Unsafe merged workspace member: " + path.name)
        if path.is_dir():
            names.add(path.name)
    return sorted(names)


def safe_extract(source, target):
    with tarfile.open(source, "r:gz") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe archive path: " + member.name)
            if member.issym() or member.islnk():
                raise ValueError("Archive links are not allowed: " + member.name)
            if member.isdir() or member.isfile():
                archive.extract(member, target)


def stable_member(member):
    member.mtime = 0
    member.uid = 0
    member.gid = 0
    member.uname = ""
    member.gname = ""
    return member


def repair(source, target, delivery_date, entity_code, start_number, max_bytes, output_name, original_name, manifest_path,
           merge_manifest_path=None):
    """在合并目录的 staging 副本上修复，只有一次最终 tar/gzip 封存；失败不会污染 merge 工作目录。"""
    source = Path(source).resolve(strict=True)
    target = Path(target).resolve()
    target.mkdir(parents=True, exist_ok=True)
    if not BATCH_PATTERN.fullmatch(output_name):
        raise ValueError("Output name must match batch_NNNNN.tar.gz")
    if Path(original_name).name != original_name or ".." in original_name:
        raise ValueError("Original file name must be safe")
    merge_manifest_path = Path(merge_manifest_path) if merge_manifest_path else source.parent / "merge-result.json"
    with merge_manifest_path.open(encoding="utf-8") as stream:
        members = json.load(stream)["members"]
    original_paths = {member["memberKey"]: member["originalPath"] for member in members}
    names = request_directories(source)
    if not names or set(names) != set(original_paths):
        raise ValueError("Merged workspace and member lineage differ")
    if start_number < 1:
        raise ValueError("Request number must be positive")
    print(f"REPAIR_STARTED workspace={source} requests={len(names)} entityCode={entity_code} deliveryDate={delivery_date} maxBytes={max_bytes}", flush=True)
    for stale in target.glob(".archive_repair_*"):
        if stale.is_dir():
            shutil.rmtree(stale)
            print(f"REPAIR_STALE_CLEANED path={stale}", flush=True)
    temp_dir = Path(tempfile.mkdtemp(prefix=".archive_repair_", dir=target))
    artifact_path = target / output_name
    temporary_artifact = target / (output_name + ".tmp")
    temporary_artifact.unlink(missing_ok=True)
    try:
        shutil.copytree(source, temp_dir, dirs_exist_ok=True)
        mappings = []
        new_request_ids = set()
        for offset, old_name in enumerate(names):
            old_path = temp_dir / old_name
            original_request_id, new_request_id = fix_request(
                old_path, start_number + offset, delivery_date, entity_code)
            if new_request_id in new_request_ids:
                raise ValueError("Duplicate requestId after repair: " + new_request_id)
            new_request_ids.add(new_request_id)
            new_path = temp_dir / new_request_id
            old_path.rename(new_path)
            mappings.append({"memberKey": old_name, "originalPath": original_paths[old_name],
                             "originalRequestId": original_request_id, "newRequestId": new_request_id,
                             "qualification": "QUALIFIED"})
            print(f"REPAIR_REQUEST sourceMember={old_name} originalRequestId={original_request_id or ''} newRequestId={new_request_id}", flush=True)
        with temporary_artifact.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as archive:
                    for mapping in mappings:
                        archive.add(temp_dir / mapping["newRequestId"],
                                    arcname=mapping["newRequestId"], filter=stable_member)
        size_bytes = temporary_artifact.stat().st_size
        if size_bytes > max_bytes:
            raise ValueError(f"Repaired artifact exceeds maxPackageBytes: {size_bytes}>{max_bytes}")
        verify_output(temporary_artifact, delivery_date, entity_code)
        os.replace(temporary_artifact, artifact_path)
        artifact = {"artifactSeq": 1, "originalFileName": original_name, "newFileName": output_name,
                    "sizeBytes": size_bytes, "sha256": sha256(artifact_path), "token": size_bytes,
                    "file_mapping": {mapping["memberKey"]: mapping["newRequestId"] for mapping in mappings}}
        manifest = {"protocolVersion": 3, "requestCount": len(mappings), "mappings": mappings,
                    "artifactPath": str(artifact_path), "artifacts": [artifact]}
        manifest_path = Path(manifest_path).resolve()
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_manifest = manifest_path.with_name(manifest_path.name + ".tmp")
        with temporary_manifest.open("w", encoding="utf-8") as stream:
            json.dump(manifest, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_manifest, manifest_path)
        print(f"REPAIR_COMPLETED requests={len(mappings)} bytes={size_bytes} sha256={artifact['sha256']} artifact={artifact_path}", flush=True)
        return manifest
    except Exception:
        temporary_artifact.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--target")
    parser.add_argument("--delivery-date")
    parser.add_argument("--entity-code")
    parser.add_argument("--start-number", type=int)
    parser.add_argument("--max-bytes", type=int)
    parser.add_argument("--output-name")
    parser.add_argument("--original-name")
    parser.add_argument("--merge-manifest")
    parser.add_argument("--manifest")
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        result = {"requestCount": len(request_directories(Path(args.source)))}
        print("INSPECT_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)
    else:
        missing = [name for name in ("target", "delivery_date", "entity_code", "start_number", "max_bytes", "output_name", "original_name", "manifest")
                   if getattr(args, name) is None]
        if missing:
            parser.error("Missing repair arguments: " + ", ".join(missing))
        result = repair(args.source, args.target, args.delivery_date, args.entity_code,
                        args.start_number, args.max_bytes, args.output_name, args.original_name, args.manifest,
                        args.merge_manifest)
        print("REPAIR_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
