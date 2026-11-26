#!/usr/bin/env python3
import ftplib
import hashlib
import json
import os
import sys
from pathlib import Path, PurePosixPath
from ftp_retention import retain_delivery_date
from ftp_capacity import reserve_capacity

REQUIRED = {"host", "port", "username", "password", "rootPath", "remotePath", "sourcePath",
            "downloadPath", "expectedSize", "expectedSha256", "cleanupOtherDeliveryDates", "deliveryDate", "retentionRoot"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def make_remote_directories(ftp, root, relative):
    current = root.rstrip("/")
    for part in relative.parts[:-1]:
        current = (current + "/" + part).replace("//", "/")
        try:
            ftp.mkd(current)
        except ftplib.error_perm as error:
            if not str(error).startswith("550"):
                raise


def remote_matches(ftp, remote, expected_size, expected_sha):
    try:
        if ftp.size(remote) != expected_size:
            return False
        digest = hashlib.sha256()
        ftp.retrbinary("RETR " + remote, digest.update)
        return digest.hexdigest() == expected_sha
    except ftplib.error_perm as error:
        if str(error).startswith("550"):
            return None
        raise


def transfer(request, verify_only=False):
    """FTP 同名不同 SHA 永不覆盖；下载核验通过后才从 .partial 原子封存最终文件。"""
    if set(request) != REQUIRED:
        raise ValueError("FTP request fields do not match the fixed contract")
    if verify_only and request["cleanupOtherDeliveryDates"]:
        raise ValueError("Read-only FTP verification cannot enable retention cleanup")
    source = Path(request["sourcePath"]).resolve(strict=True)
    if not source.is_file() or source.stat().st_size != request["expectedSize"] or sha256(source) != request["expectedSha256"]:
        raise ValueError("Verified OSS copy does not match expected size/SHA")
    remote = PurePosixPath(request["remotePath"])
    if remote.is_absolute() or ".." in remote.parts or any(c in str(remote) for c in "\\\r\n") or len(remote.parts) < 2:
        raise ValueError("Unsafe FTP remote path")
    root = PurePosixPath(request["rootPath"])
    if not root.is_absolute() or ".." in root.parts or any(c in str(root) for c in "\\\r\n"):
        raise ValueError("Unsafe FTP root path")
    if not isinstance(request["cleanupOtherDeliveryDates"], bool):
        raise ValueError("FTP cleanup policy must be boolean")
    remote_full = str(root / remote).replace("//", "/")
    download = Path(request["downloadPath"]).resolve()
    if download.name != remote.name:
        raise ValueError("FTP download file name differs from artifact")
    download.parent.mkdir(parents=True, exist_ok=True)
    partial = download.with_name(download.name + ".partial")
    ftp = ftplib.FTP()
    try:
        ftp.connect(request["host"], int(request["port"]), timeout=30)
        ftp.login(request["username"], request["password"])
        ftp.voidcmd("TYPE I")
        print(f"FTP_CONNECTED host={request['host']} rootPath={root} remotePath={remote_full}", flush=True)
        with retain_delivery_date(ftp, request) as removed:
            if removed:
                print("FTP_DATE_CLEANUP " + json.dumps({"deliveryDate": request["deliveryDate"], "removedDates": removed}), flush=True)
            if verify_only:
                return verify_existing(ftp, request, remote_full)
            # 容量不足是等待事实，不能删除历史文件腾位置或消耗普通错误重试次数。
            with reserve_capacity(ftp, request) as granted:
                if not granted:
                    return {"status": "CAPACITY_WAIT", "retryAfterSeconds": 60}
                return transfer_verified(ftp, request, source, remote, root, remote_full, download, partial, removed)
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def verify_existing(ftp, request, remote_full):
    """独立回验完整读取远端字节，仅累计 size/SHA；不写本地副本或修复任何远端文件。"""
    try:
        size = ftp.size(remote_full)
    except ftplib.error_perm as error:
        raise ValueError("FTP artifact is missing during read-only verification") from error
    if size != request["expectedSize"]:
        raise ValueError("FTP remote exists with different size or SHA")
    digest = hashlib.sha256()
    received = 0

    # RETR 回调只处理真实收到的块，截断、错误 SHA 和断连都不能形成成功证据。
    def consume(block):
        nonlocal received
        digest.update(block)
        received += len(block)

    ftp.retrbinary("RETR " + remote_full, consume, blocksize=1024 * 1024)
    if received != size or digest.hexdigest() != request["expectedSha256"]:
        raise ValueError("FTP download size/SHA mismatch")
    return {"uri": "ftp://" + request["host"] + remote_full,
            "sizeBytes": received, "sha256": digest.hexdigest(), "removedDates": []}


def transfer_verified(ftp, request, source, remote, root, remote_full, download, partial, removed):
    """一次 RETR 同时完成远端 SHA 与本地回验，只有完整文件才原子发布。"""
    try:
        existing_size = ftp.size(remote_full)
    except ftplib.error_perm as error:
        if not str(error).startswith("550"):
            raise
        existing_size = None
    if existing_size is not None and existing_size != request["expectedSize"]:
        raise ValueError("FTP remote exists with different size or SHA")
    remote_partial = remote_full + ".partial"
    retrieval_path = remote_full
    try:
        if existing_size is None:
            make_remote_directories(ftp, str(root), remote)
            with source.open("rb") as stream:
                ftp.storbinary("STOR " + remote_partial, stream)
            retrieval_path = remote_partial
        with partial.open("wb") as stream:
            ftp.retrbinary("RETR " + retrieval_path, stream.write)
        if partial.stat().st_size != request["expectedSize"] or sha256(partial) != request["expectedSha256"]:
            raise ValueError("FTP download size/SHA mismatch")
        if existing_size is None:
            # 重试竞争时也不覆盖已存在的不同内容；正常路径仅一次 RETR。
            concurrent = remote_matches(ftp, remote_full, request["expectedSize"], request["expectedSha256"])
            if concurrent is False:
                raise ValueError("FTP remote exists with different size or SHA")
            if concurrent is None:
                ftp.rename(remote_partial, remote_full)
            else:
                ftp.delete(remote_partial)
            print(f"FTP_UPLOAD_VERIFIED remotePath={remote_full} size={request['expectedSize']} sha256={request['expectedSha256']}", flush=True)
        if download.exists():
            if download.stat().st_size != request["expectedSize"] or sha256(download) != request["expectedSha256"]:
                raise ValueError("Local FTP verification target exists with different size or SHA")
            partial.unlink()
        else:
            os.replace(partial, download)
    except Exception:
        partial.unlink(missing_ok=True)
        if existing_size is None:
            try:
                ftp.delete(remote_partial)
            except ftplib.all_errors:
                pass
        raise
    return {
        "uri": "ftp://" + request["host"] + remote_full,
        "downloadPath": str(download),
        "sizeBytes": download.stat().st_size,
        "sha256": sha256(download),
        "removedDates": removed
    }


def preflight(request):
    """预检只验证当前组账户和授权根目录，不创建、删除或修改任何 FTP 文件。"""
    required = {"host", "port", "username", "password", "rootPath"}
    if set(request) != required:
        raise ValueError("FTP preflight fields do not match the fixed contract")
    ftp = ftplib.FTP()
    try:
        ftp.connect(request["host"], int(request["port"]), timeout=15)
        ftp.login(request["username"], request["password"])
        ftp.cwd(request["rootPath"])
        return {"status": "OK"}
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


def main():
    try:
        if sys.argv[1:] == ["--preflight"]:
            result = preflight(json.load(sys.stdin))
            print(json.dumps(result, ensure_ascii=False))
        else:
            if sys.argv[1:] not in ([], ["--verify-only"]):
                raise ValueError("Unsupported FTP node mode")
            result = transfer(json.load(sys.stdin), verify_only=sys.argv[1:] == ["--verify-only"])
            print("FTP_RESULT " + json.dumps(result, ensure_ascii=False))
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
