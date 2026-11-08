#!/usr/bin/env python3
import ftplib
import hashlib
import json
import os
import sys
from pathlib import Path, PurePosixPath

REQUIRED = {"host", "port", "username", "password", "rootPath", "remotePath", "sourcePath",
            "downloadPath", "expectedSize", "expectedSha256"}


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


def transfer(request):
    """FTP 同名不同 SHA 永不覆盖；下载核验通过后才从 .partial 原子封存最终文件。"""
    if set(request) != REQUIRED:
        raise ValueError("FTP request fields do not match the fixed contract")
    source = Path(request["sourcePath"]).resolve(strict=True)
    if not source.is_file() or source.stat().st_size != request["expectedSize"] or sha256(source) != request["expectedSha256"]:
        raise ValueError("Verified OSS copy does not match expected size/SHA")
    remote = PurePosixPath(request["remotePath"])
    if remote.is_absolute() or ".." in remote.parts or "\\" in str(remote):
        raise ValueError("Unsafe FTP remote path")
    root = PurePosixPath(request["rootPath"])
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
        existing = remote_matches(ftp, remote_full, request["expectedSize"], request["expectedSha256"])
        if existing is False:
            raise ValueError("FTP remote exists with different size or SHA")
        if existing is None:
            make_remote_directories(ftp, str(root), remote)
            with source.open("rb") as stream:
                ftp.storbinary("STOR " + remote_full, stream)
            if remote_matches(ftp, remote_full, request["expectedSize"], request["expectedSha256"]) is not True:
                raise ValueError("FTP upload verification failed")
            print(f"FTP_UPLOAD_VERIFIED remotePath={remote_full} size={request['expectedSize']} sha256={request['expectedSha256']}", flush=True)
        try:
            with partial.open("wb") as stream:
                ftp.retrbinary("RETR " + remote_full, stream.write)
            if partial.stat().st_size != request["expectedSize"] or sha256(partial) != request["expectedSha256"]:
                raise ValueError("FTP download size/SHA mismatch")
            if download.exists():
                if download.stat().st_size != request["expectedSize"] or sha256(download) != request["expectedSha256"]:
                    raise ValueError("Local FTP verification target exists with different size or SHA")
                partial.unlink()
            else:
                os.replace(partial, download)
        except Exception:
            partial.unlink(missing_ok=True)
            raise
        return {
            "uri": "ftp://" + request["host"] + remote_full,
            "downloadPath": str(download),
            "sizeBytes": download.stat().st_size,
            "sha256": sha256(download)
        }
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()


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
            result = transfer(json.load(sys.stdin))
            print("FTP_RESULT " + json.dumps(result, ensure_ascii=False))
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
