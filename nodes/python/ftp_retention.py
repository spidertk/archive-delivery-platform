"""按明确授权的交付日期清理直属 FTP 根目录；同机 Worker 共享锁保护传输。"""
import datetime
import fcntl
import hashlib
import os
import re
from contextlib import contextmanager
from pathlib import Path, PurePosixPath


def date_name(value):
    """只识别规范日期目录，其他名称永远不进入自动删除范围。"""
    return bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", value)) and str(datetime.date.fromisoformat(value)) == value


def directory_entries(ftp, directory):
    """MLSD 必须明确区分目录与普通文件；不跟随链接或猜测 LIST 文本。"""
    entries = []
    for name, facts in ftp.mlsd(directory):
        kind = facts.get("type", "")
        if kind in {"cdir", "pdir"}:
            continue
        if not name or name in {".", ".."} or any(c in name for c in "/\\\r\n") or kind not in {"dir", "file"}:
            raise ValueError("Unsafe or unsupported FTP retention entry")
        entries.append((name, kind))
    # MLSD 使用 ASCII 数据连接，后续 SIZE/RETR 必须恢复二进制模式。
    ftp.voidcmd("TYPE I")
    return entries


def other_date_directories(ftp, root, delivery_date):
    """每次传输前重新查看直属根，其他日期目录即使在同日后来出现也必须清理。"""
    result = []
    for name, kind in directory_entries(ftp, root):
        try:
            dated = date_name(name)
        except ValueError:
            dated = False
        if kind == "dir" and dated and name != delivery_date:
            result.append(name)
    return result


def remove_directory(ftp, directory):
    """删除前完整验证子树类型，异常或符号链接阻止该目录清理。"""
    files, directories = [], []
    pending = [directory]
    while pending:
        current = pending.pop()
        directories.append(current)
        for name, kind in directory_entries(ftp, current):
            path = str(PurePosixPath(current) / name)
            if kind == "dir":
                pending.append(path)
            else:
                files.append(path)
    for path in files:
        ftp.delete(path)
    for path in reversed(directories):
        ftp.rmd(path)


@contextmanager
def retain_delivery_date(ftp, request):
    """每次传输前检查日期目录；独占清理、共享传输，旧日期不得清理新日期。"""
    if not request["cleanupOtherDeliveryDates"]:
        yield []
        return
    delivery_date = request["deliveryDate"]
    if not date_name(delivery_date) or PurePosixPath(request["remotePath"]).parts[0] != delivery_date:
        raise ValueError("FTP retention requires deliveryDate/file under the dedicated entity root")
    identity = "\0".join(str(request[key]) for key in ("host", "port", "username", "rootPath"))
    lock_root = Path(request["retentionRoot"])
    lock_root.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(identity.encode()).hexdigest()
    state = lock_root / (key + ".date")
    # 先取共享锁让同日期传输并行；发现旧目录或日期切换时才等待在途 FTP 完成。
    with (lock_root / (key + ".lock")).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_SH)
        previous = state.read_text() if state.exists() else ""
        if previous and (not date_name(previous) or previous > delivery_date):
            raise ValueError("FTP retention refuses an older delivery date after a newer date")
        obsolete = other_date_directories(ftp, request["rootPath"], delivery_date)
        if previous != delivery_date or obsolete:
            fcntl.flock(lock, fcntl.LOCK_UN)
            fcntl.flock(lock, fcntl.LOCK_EX)
            previous = state.read_text() if state.exists() else ""
            if previous and (not date_name(previous) or previous > delivery_date):
                raise ValueError("FTP retention refuses an older delivery date after a newer date")
            removed = []
            for name in other_date_directories(ftp, request["rootPath"], delivery_date):
                remove_directory(ftp, str(PurePosixPath(request["rootPath"]) / name))
                removed.append(name)
            if previous != delivery_date:
                temporary = state.with_suffix(".tmp")
                temporary.write_text(delivery_date)
                os.replace(temporary, state)
            fcntl.flock(lock, fcntl.LOCK_SH)
        else:
            removed = []
        # 同包重试也必须互斥，避免过期 Worker 与当前 fence 的 .partial 改名竞争。
        artifact_key = hashlib.sha256(request["remotePath"].encode()).hexdigest()
        with (lock_root / (key + ".package-" + artifact_key + ".lock")).open("a") as artifact_lock:
            fcntl.flock(artifact_lock, fcntl.LOCK_EX)
            yield removed
