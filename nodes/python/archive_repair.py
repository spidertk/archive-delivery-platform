#!/usr/bin/env python3
import argparse
import fcntl
import gzip
import hashlib
import json
import os
import re
import shutil
import tarfile
import tempfile
import time
from pathlib import Path

from legacy_fix_archive import fix_request, verify_output

BATCH_PATTERN = re.compile(r"^batch_\d{7}\.tar\.gz$")


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
        if not path.is_dir():
            raise ValueError("Merged workspace contains a non-request member: " + path.name)
        names.add(path.name)
    return sorted(names)


class StrictArchiveMember(tarfile.TarInfo):
    """只允许跳过合法 TAR 结束零块；拼接段坏头或截断不能被 ignore_zeros 静默忽略。"""
    @classmethod
    def fromtarfile(cls, archive):
        try:
            return super().fromtarfile(archive)
        except (tarfile.InvalidHeaderError, tarfile.TruncatedHeaderError) as error:
            raise tarfile.ReadError("Invalid concatenated archive member") from error


def safe_extract(source, target):
    # gzip.open 读取全部 gzip 成员，ignore_zeros 跨越拼接 TAR 的结束块，避免静默丢弃后续请求。
    seen = set()
    with gzip.open(source, "rb") as stream, tarfile.open(
            fileobj=stream, mode="r|", ignore_zeros=True, tarinfo=StrictArchiveMember) as archive:
        for member in archive:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or "\\" in member.name:
                raise ValueError("Unsafe archive path: " + member.name)
            if member.issym() or member.islnk():
                raise ValueError("Archive links are not allowed: " + member.name)
            if path.as_posix() in seen and not member.isdir():
                raise ValueError("Duplicate archive file: " + member.name)
            seen.add(path.as_posix())
            if member.isdir() or member.isfile():
                archive.extract(member, target)
            else:
                raise ValueError("Unsupported archive member: " + member.name)


def stable_member(member):
    member.mtime = 0
    member.uid = 0
    member.gid = 0
    member.uname = ""
    member.gname = ""
    return member


def repair(source, target, delivery_date, entity_code, max_bytes, output_name, original_name, manifest_path,
           merge_manifest_path=None, gzip_level=9):
    """同一任务只允许一个 repair 操作其 staging 和最终包，等待旧进程退出后才能清理残留。"""
    target = Path(target).resolve()
    target.mkdir(parents=True, exist_ok=True)
    # 重试和被中断的旧进程共享任务私有锁；关闭文件或进程退出时内核自动释放，不删除锁文件。
    with (target / '.repair.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return repair_locked(source, target, delivery_date, entity_code, max_bytes, output_name, original_name,
                             manifest_path, merge_manifest_path, gzip_level)


def repair_locked(source, target, delivery_date, entity_code, max_bytes, output_name, original_name, manifest_path,
                  merge_manifest_path=None, gzip_level=9):
    """在合并目录的 staging 副本上修复，只有一次最终 tar/gzip 封存；失败不会污染 merge 工作目录。"""
    source = Path(source).resolve(strict=True)
    target = Path(target).resolve()
    target.mkdir(parents=True, exist_ok=True)
    if not BATCH_PATTERN.fullmatch(output_name):
        raise ValueError("Output name must match batch_NNNNNNN.tar.gz")
    if Path(original_name).name != original_name or ".." in original_name:
        raise ValueError("Original file name must be safe")
    merge_manifest_path = Path(merge_manifest_path) if merge_manifest_path else source.parent / "merge-result.json"
    with merge_manifest_path.open(encoding="utf-8") as stream:
        members = json.load(stream)["members"]
    original_paths = {member["memberKey"]: member["originalPath"] for member in members}
    names = request_directories(source)
    if not names or set(names) != set(original_paths):
        raise ValueError("Merged workspace and member lineage differ")
    if not isinstance(gzip_level, int) or isinstance(gzip_level, bool) or not 1 <= gzip_level <= 9:
        raise ValueError("gzip_level must be an integer from 1 to 9")
    print(f"REPAIR_STARTED workspace={source} requests={len(names)} entityCode={entity_code} deliveryDate={delivery_date} maxBytes={max_bytes} gzipLevel={gzip_level}", flush=True)
    for stale in target.glob(".archive_repair_*"):
        if stale.is_dir():
            shutil.rmtree(stale)
            print(f"REPAIR_STALE_CLEANED path={stale}", flush=True)
    temp_dir = Path(tempfile.mkdtemp(prefix=".archive_repair_", dir=target))
    artifact_path = target / output_name
    temporary_artifact = target / (output_name + ".tmp")
    temporary_artifact.unlink(missing_ok=True)
    timings = {}
    repair_started = time.perf_counter()
    try:
        # 分段计时用于定位真实 IO/CPU 瓶颈，不改变修复和原子发布语义。
        stage_started = time.perf_counter()
        shutil.copytree(source, temp_dir, dirs_exist_ok=True)
        timings["copyWorkspaceSeconds"] = time.perf_counter() - stage_started
        mappings = []
        request_ids = set()
        stage_started = time.perf_counter()
        for old_name in names:
            old_path = temp_dir / old_name
            # 原始目录与 ID 都是来源事实；出现不一致应失败，不能通过改名或补号掩盖。
            if old_name != original_paths[old_name]:
                raise ValueError("Merged directory differs from its original source name")
            request_id = fix_request(old_path, entity_code)
            if request_id in request_ids:
                raise ValueError("Duplicate original requestId: " + request_id)
            request_ids.add(request_id)
            mappings.append({"memberKey": old_name, "originalPath": original_paths[old_name],
                             "requestId": request_id,
                             "qualification": "QUALIFIED"})
            print(f"REPAIR_REQUEST sourceMember={old_name} requestId={request_id}", flush=True)
        timings["repairRequestsSeconds"] = time.perf_counter() - stage_started
        stage_started = time.perf_counter()
        with temporary_artifact.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=gzip_level, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as archive:
                    for mapping in mappings:
                        archive.add(temp_dir / mapping["originalPath"],
                                    arcname=mapping["originalPath"], filter=stable_member)
        timings["gzipPackSeconds"] = time.perf_counter() - stage_started
        size_bytes = temporary_artifact.stat().st_size
        if size_bytes > max_bytes:
            raise ValueError(f"Repaired artifact exceeds maxPackageBytes: {size_bytes}>{max_bytes}")
        stage_started = time.perf_counter()
        verify_output(temporary_artifact, entity_code)
        timings["verifyArtifactSeconds"] = time.perf_counter() - stage_started
        os.replace(temporary_artifact, artifact_path)
        stage_started = time.perf_counter()
        artifact_sha256 = sha256(artifact_path)
        timings["sha256Seconds"] = time.perf_counter() - stage_started
        artifact = {"artifactSeq": 1, "originalFileName": original_name, "newFileName": output_name,
                    "sizeBytes": size_bytes, "sha256": artifact_sha256, "token": size_bytes,
                    "file_mapping": {mapping["memberKey"]: mapping["originalPath"] for mapping in mappings}}
        manifest = {"protocolVersion": 4, "requestCount": len(mappings), "mappings": mappings,
                    "artifactPath": str(artifact_path), "artifacts": [artifact], "timings": timings}
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
        cleanup_started = time.perf_counter()
        shutil.rmtree(temp_dir, ignore_errors=True)
        timings["cleanupSeconds"] = time.perf_counter() - cleanup_started
        timings["totalSeconds"] = time.perf_counter() - repair_started
        print("REPAIR_TIMINGS " + json.dumps(timings, sort_keys=True), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--target")
    parser.add_argument("--delivery-date")
    parser.add_argument("--entity-code")
    parser.add_argument("--max-bytes", type=int)
    parser.add_argument("--output-name")
    parser.add_argument("--original-name")
    parser.add_argument("--merge-manifest")
    parser.add_argument("--manifest")
    parser.add_argument("--gzip-level", type=int, choices=range(1, 10), default=9)
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    if args.inspect:
        result = {"requestCount": len(request_directories(Path(args.source)))}
        print("INSPECT_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)
    else:
        missing = [name for name in ("target", "delivery_date", "entity_code", "max_bytes", "output_name", "original_name", "manifest")
                   if getattr(args, name) is None]
        if missing:
            parser.error("Missing repair arguments: " + ", ".join(missing))
        result = repair(args.source, args.target, args.delivery_date, args.entity_code,
                        args.max_bytes, args.output_name, args.original_name, args.manifest,
                        args.merge_manifest, args.gzip_level)
        print("REPAIR_RESULT " + json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
