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

BATCH_PATTERN = re.compile(r"(batch_\d{5})\.tar\.gz$")
MAX_ARTIFACT_BYTES = 100 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def request_directories(source):
    with tarfile.open(source, "r:gz") as archive:
        names = set()
        for member in archive.getmembers():
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe archive path: " + member.name)
            if member.issym() or member.islnk():
                raise ValueError("Archive links are not allowed: " + member.name)
            if len(path.parts) > 1:
                names.add(path.parts[0])
        return sorted(names)


def safe_extract(source, target):
    with tarfile.open(source, "r:gz") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe archive path: " + member.name)
            if member.issym() or member.islnk():
                raise ValueError("Archive links are not allowed: " + member.name)
        archive.extractall(target)


def stable_member(member):
    member.mtime = 0
    member.uid = 0
    member.gid = 0
    member.uname = ""
    member.gname = ""
    return member


def repair(source, target, delivery_date, delivery_account, start_number, manifest_path):
    """修复仅消费 Java 提供的日期、账户和号段，输出确定性产物及严格七字段 artifact。"""
    source = Path(source).resolve()
    target = Path(target).resolve()
    target.mkdir(parents=True, exist_ok=True)
    names = request_directories(source)
    if not names:
        raise ValueError("Source archive contains no request directories")
    if start_number < 1 or start_number + len(names) - 1 > 999999999:
        raise ValueError("Request number range exceeds nine digits")
    temp_dir = Path(tempfile.mkdtemp(prefix="archive_repair_"))
    try:
        safe_extract(source, temp_dir)
        mappings = []
        date_part = delivery_date.replace("-", "")
        for offset, old_name in enumerate(names):
            new_request_id = "req_{}_{:09d}".format(date_part, start_number + offset)
            old_path = temp_dir / old_name
            new_path = temp_dir / new_request_id
            old_path.rename(new_path)
            original_request_id = fix_request(new_path, new_request_id, delivery_date, delivery_account)
            mappings.append({
                "memberKey": old_name,
                "originalPath": old_name,
                "originalRequestId": original_request_id,
                "newRequestId": new_request_id,
                "qualification": "QUALIFIED"
            })
        match = BATCH_PATTERN.search(source.name)
        batch_name = (match.group(1) if match else "batch_00001") + ".tar.gz"
        artifact_path = target / batch_name
        temporary_artifact = target / (batch_name + ".tmp")
        try:
            with temporary_artifact.open("wb") as raw:
                with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                    with tarfile.open(fileobj=compressed, mode="w") as archive:
                        for mapping in mappings:
                            archive.add(temp_dir / mapping["newRequestId"],
                                        arcname=mapping["newRequestId"], filter=stable_member)
            if temporary_artifact.stat().st_size > MAX_ARTIFACT_BYTES:
                raise ValueError("Output artifact exceeds 100 MiB")
            verify_output(temporary_artifact, delivery_date, delivery_account)
            os.replace(temporary_artifact, artifact_path)
        except Exception:
            temporary_artifact.unlink(missing_ok=True)
            raise
        size_bytes = artifact_path.stat().st_size
        artifact = {
            "artifactSeq": 1,
            "originalFileName": source.name,
            "newFileName": batch_name,
            "sizeBytes": size_bytes,
            "sha256": sha256(artifact_path),
            "token": size_bytes,
            "file_mapping": {mapping["memberKey"]: mapping["newRequestId"] for mapping in mappings}
        }
        manifest = {
            "protocolVersion": 1,
            "requestCount": len(mappings),
            "mappings": mappings,
            "artifactPath": str(artifact_path),
            "artifacts": [artifact]
        }
        manifest_path = Path(manifest_path).resolve()
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_manifest = manifest_path.with_suffix(".tmp")
        with temporary_manifest.open("w", encoding="utf-8") as stream:
            json.dump(manifest, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_manifest, manifest_path)
        return manifest
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--target")
    parser.add_argument("--delivery-date")
    parser.add_argument("--delivery-account")
    parser.add_argument("--start-number", type=int)
    parser.add_argument("--manifest")
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        result = {"requestCount": len(request_directories(Path(args.source)))}
    else:
        missing = [name for name in ("target", "delivery_date", "delivery_account", "start_number", "manifest")
                   if getattr(args, name) is None]
        if missing:
            parser.error("Missing repair arguments: " + ", ".join(missing))
        result = repair(args.source, args.target, args.delivery_date, args.delivery_account,
                        args.start_number, args.manifest)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
