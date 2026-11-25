#!/usr/bin/env python3
"""只读检查 FTP 挂载目录的当日成品快照；不解包落盘，不读取凭据，不修改交付状态。"""
import concurrent.futures
import datetime
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile


def inspect(path):
    """跨 gzip/TAR 结束块完整读取成品；重复成员与缺 metadata 的目录不能作为唯一请求。"""
    before = path.stat()
    directories, members, ids = set(), set(), {}
    duplicate_members = []
    with gzip.open(path, 'rb') as stream, tarfile.open(fileobj=stream, mode='r|', ignore_zeros=True) as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            if name.is_absolute() or '..' in name.parts or not name.parts or '\\' in member.name:
                raise ValueError('Unsafe archive member')
            if member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                raise ValueError('Unsupported archive member')
            directories.add(name.parts[0])
            if member.isdir():
                continue
            if len(name.parts) < 2:
                raise ValueError('Unscoped file')
            if name.as_posix() in members:
                duplicate_members.append(name.as_posix())
            members.add(name.as_posix())
            # 正文仅流式读取以验证完整 gzip/TAR；证据不保存请求或响应内容。
            with archive.extractfile(member) as content:
                if len(name.parts) == 2 and name.name == 'metadata.json':
                    metadata = json.load(content)
                    values = [metadata[k] for k in ('requestId', 'request_id') if k in metadata]
                    if not values or any(not isinstance(v, str) or not v.strip() for v in values) or len(set(values)) != 1:
                        raise ValueError('Missing or conflicting request ID')
                    ids[name.parts[0]] = values[0]
                else:
                    for _ in iter(lambda: content.read(1024 * 1024), b''):
                        pass
        for _ in iter(lambda: stream.read(1024 * 1024), b''):
            pass
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise ValueError('Artifact changed during inspection')
    if not directories or directories != set(ids):
        raise ValueError('Every directory must have metadata')
    checksum = hashlib.sha256()
    with path.open('rb') as raw:
        for block in iter(lambda: raw.read(8 * 1024 * 1024), b''):
            checksum.update(block)
    return {'fileName': path.name, 'sizeBytes': after.st_size, 'sha256': checksum.hexdigest(),
            'directoryCount': len(directories), 'memberCount': len(members), 'directories': sorted(directories),
            'ids': ids, 'duplicateMembers': duplicate_members}


def main():
    """文件范围在开始时固化；运行中新增成品单独计数，不能混称整天的未来文件已通过。"""
    root, output = Path(sys.argv[1]), Path(sys.argv[2])
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    files = sorted(root.glob('*.tar.gz'))
    if not files or root.is_symlink() or any(p.is_symlink() or not p.is_file() for p in files):
        raise ValueError('Nonempty regular FTP artifact snapshot required')
    owners, id_owners, duplicates, duplicate_ids, artifacts, failures = {}, {}, [], [], [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(inspect, path): path for path in files}
        for future in concurrent.futures.as_completed(futures):
            path = futures[future]
            try:
                artifact = future.result()
            except Exception as error:
                failures.append({'fileName': path.name, 'errorType': type(error).__name__})
                continue
            for directory in artifact.pop('directories'):
                if directory in owners:
                    duplicates.append({'directory': directory, 'files': [owners[directory], path.name]})
                else:
                    owners[directory] = path.name
            for directory, request_id in artifact.pop('ids').items():
                location = {'fileName': path.name, 'directory': directory}
                if request_id in id_owners:
                    duplicate_ids.append({'requestId': request_id, 'locations': [id_owners[request_id], location]})
                else:
                    id_owners[request_id] = location
            artifacts.append(artifact)
            if len(artifacts) % 20 == 0:
                print(f'INSPECTED {len(artifacts)}/{len(files)} directories={len(owners)}', flush=True)
    added = sorted(p.name for p in root.glob('*.tar.gz') if p not in files)
    passed = not failures and not duplicates and not duplicate_ids and not any(a['duplicateMembers'] for a in artifacts)
    report = {'schemaVersion': 1, 'status': 'PASSED' if passed else 'FAILED', 'deliveryDate': root.name,
              'rootPath': str(root), 'startedAt': started, 'finishedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'snapshotArtifactCount': len(files), 'inspectedArtifactCount': len(artifacts),
              'directoryOccurrenceCount': sum(a['directoryCount'] for a in artifacts),
              'uniqueDirectoryCount': len(owners), 'uniqueRequestIdCount': len(id_owners),
              'duplicateDirectories': duplicates, 'duplicateRequestIds': duplicate_ids, 'failures': failures,
              'newArtifactsAfterSnapshot': added, 'partialFilesAtFinish': sorted(p.name for p in root.glob('*.partial')),
              'artifacts': sorted(artifacts, key=lambda a: a['fileName'])}
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'artifacts'}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
