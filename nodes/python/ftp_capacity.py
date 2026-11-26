"""同机各 Worker 共用 FTP 实物容量预留，包含全部目录文件及在途上传。"""
import fcntl
import hashlib
import json
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from ftp_retention import directory_entries

MAX_FILES = 6000


def remote_files(ftp, root):
    """完整枚举账户根；链接/未知类型直接拒绝，旧日期和 .partial 同样占用文件数。"""
    result = set()
    pending = [root]
    while pending:
        current = pending.pop()
        for name, kind in directory_entries(ftp, current):
            path = str(PurePosixPath(current) / name)
            if kind == 'dir':
                pending.append(path)
            else:
                result.add(path)
    return result


@contextmanager
def reserve_capacity(ftp, request, maximum=MAX_FILES):
    """全局短锁只用于容量计算，包锁贯穿传输；活动预留可防并发超额，无需删除锁或租约。"""
    identity = '\0'.join(str(request[key]) for key in ('host', 'port', 'username', 'rootPath'))
    directory = Path(request['retentionRoot']) / (hashlib.sha256(identity.encode()).hexdigest()+'.capacity')
    directory.mkdir(parents=True, exist_ok=True)
    remote = str(PurePosixPath(request['rootPath']) / request['remotePath'])
    claim_path = directory / (hashlib.sha256(remote.encode()).hexdigest()+'.claim')
    with claim_path.open('a+') as claim:
        fcntl.flock(claim, fcntl.LOCK_EX)
        with (directory / 'capacity.lock').open('a') as capacity:
            fcntl.flock(capacity, fcntl.LOCK_EX)
            files = remote_files(ftp, request['rootPath'])
            pending = set()
            for path in directory.glob('*.claim'):
                if path == claim_path:
                    continue
                with path.open() as other:
                    try:
                        fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        # 只有仍被进程持有的预留计入容量；进程退出留下的文件不会永久占位。
                        try:
                            value = json.loads(other.read())
                        except json.JSONDecodeError:
                            # 包锁可先于容量锁取得；空/中断记录尚未获准，不能当活动预留或令其他上传失败。
                            # 已获准记录只在容量锁内写入，故持此锁的扫描不会读到其半写状态。
                            continue
                        target = value['remotePath']
                        if target not in files and target+'.partial' not in files:
                            pending.add(target)
            represented = remote in files or remote+'.partial' in files
            granted = represented or len(files)+len(pending) < maximum
            if granted:
                claim.seek(0)
                claim.truncate()
                json.dump({'remotePath': remote}, claim)
                claim.flush()
        yield granted
