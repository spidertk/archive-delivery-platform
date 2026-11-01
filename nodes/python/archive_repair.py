#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import shutil
import tarfile
import tempfile
from pathlib import Path

BATCH_PATTERN = re.compile(r"(batch_\d{5})\.tar\.gz$")


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


def repair_request(request_dir, new_request_id, archive_date, provider_code):
    metadata_path = request_dir / "metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError("Missing metadata.json in " + request_dir.name)
    if not (request_dir / "biz_request.json").is_file():
        raise FileNotFoundError("Missing biz_request.json in " + request_dir.name)
    with metadata_path.open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    original_request_id = metadata.get("requestId")
    original_time = metadata.get("requestTime", "")
    time_part = original_time.split(" ", 1)[1] if " " in original_time else "00:00:00"
    metadata["requestId"] = new_request_id
    metadata["providerCode"] = provider_code
    metadata["requestTime"] = archive_date + " " + time_part
    stream_response = request_dir / "biz_stream_response.jsonl"
    sync_response = request_dir / "biz_response.json"
    mode = metadata.get("requestMode", "")
    if mode == "STREAM" and sync_response.exists() and not stream_response.exists():
        sync_response.rename(stream_response)
    elif mode == "SYNC" and stream_response.exists() and not sync_response.exists():
        stream_response.rename(sync_response)
    if mode == "STREAM" and not stream_response.is_file():
        raise FileNotFoundError("Missing STREAM response in " + request_dir.name)
    if mode == "SYNC" and not sync_response.is_file():
        raise FileNotFoundError("Missing SYNC response in " + request_dir.name)
    with metadata_path.open("w", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False)
    return original_request_id


def repair(source, target, archive_date, provider_code, start_number, manifest_path):
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
        date_part = archive_date.replace("-", "")
        for offset, old_name in enumerate(names):
            new_request_id = "req_{}_{:09d}".format(date_part, start_number + offset)
            old_path = temp_dir / old_name
            new_path = temp_dir / new_request_id
            old_path.rename(new_path)
            original_request_id = repair_request(new_path, new_request_id, archive_date, provider_code)
            mappings.append({
                "memberKey": old_name,
                "originalPath": old_name,
                "originalRequestId": original_request_id,
                "newRequestId": new_request_id,
                "qualification": "QUALIFIED"
            })
        match = BATCH_PATTERN.search(source.name)
        batch_name = (match.group(1) if match else "batch_00001") + ".tar.gz"
        artifact = target / batch_name
        temporary_artifact = target / (batch_name + ".tmp")
        with tarfile.open(temporary_artifact, "w:gz") as archive:
            for mapping in mappings:
                archive.add(temp_dir / mapping["newRequestId"], arcname=mapping["newRequestId"])
        os.replace(temporary_artifact, artifact)
        manifest = {
            "protocolVersion": 1,
            "sourceFile": str(source),
            "requestCount": len(mappings),
            "mappings": mappings,
            "artifacts": [{
                "path": str(artifact),
                "name": batch_name,
                "bytes": artifact.stat().st_size,
                "sha256": sha256(artifact)
            }]
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
    parser.add_argument("--archive-date")
    parser.add_argument("--provider-code")
    parser.add_argument("--start-number", type=int)
    parser.add_argument("--manifest")
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        result = {"requestCount": len(request_directories(Path(args.source)))}
    else:
        missing = [name for name in ("target", "archive_date", "provider_code", "start_number", "manifest")
                   if getattr(args, name) is None]
        if missing:
            parser.error("Missing repair arguments: " + ", ".join(missing))
        result = repair(args.source, args.target, args.archive_date, args.provider_code,
                        args.start_number, args.manifest)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
