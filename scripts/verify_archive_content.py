#!/usr/bin/env python3
"""独立只读比对全部来源与最终 TAR 内容，不复用节点解包或修复实现。"""
import argparse
import datetime
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile


def checksum(path):
    """原始压缩文件完整读取计算 SHA，证据不依赖节点已存的摘要。"""
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def scan(path):
    """显式跨越 gzip 成员与 TAR 结束块；只保留摘要和必要协议字段，正文不进入证据。"""
    facts = {'files': {}, 'directories': set(), 'metadata': {}, 'stream': {}, 'contentBytes': 0}
    with gzip.open(path, 'rb') as stream, tarfile.open(fileobj=stream, mode='r|', ignore_zeros=True) as archive:
        for member in archive:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or '..' in relative.parts or '\\' in member.name or not relative.parts:
                raise ValueError('Unsafe archive member')
            if member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                raise ValueError('Unsupported archive member')
            facts['directories'].add(relative.parts[0])
            if member.isdir():
                continue
            key = relative.as_posix()
            if len(relative.parts) < 2 or key in facts['files']:
                raise ValueError('Duplicate or unscoped archive file')
            digest = hashlib.sha256()
            payload = bytearray() if len(relative.parts) == 2 and relative.name in ('metadata.json', 'biz_request.json') else None
            with archive.extractfile(member) as content:
                for block in iter(lambda: content.read(1024 * 1024), b''):
                    digest.update(block)
                    if payload is not None:
                        payload.extend(block)
            facts['files'][key] = {'size': member.size, 'sha256': digest.hexdigest()}
            facts['contentBytes'] += member.size
            if payload is not None:
                try:
                    value = json.loads(payload)
                except (ValueError, UnicodeError) as error:
                    raise ValueError('Required protocol JSON is invalid') from error
                if not isinstance(value, dict):
                    raise ValueError('Required protocol JSON must be an object')
                if relative.name == 'metadata.json':
                    facts['metadata'][relative.parts[0]] = value
                else:
                    facts['stream'][relative.parts[0]] = value.get('stream')
    if not facts['files'] or facts['directories'] != set(facts['metadata']):
        raise ValueError('Every request directory must contain metadata')
    return facts


def source_id(metadata):
    """UUID 等 ID 按原值比对，缺失、空值及两种原始字段相互矛盾都失败。"""
    values = [metadata[key] for key in ('requestId', 'request_id') if key in metadata]
    if not values or any(not isinstance(value, str) or not value.strip() for value in values) or len(set(values)) != 1:
        raise ValueError('Source request ID is missing or conflicting')
    return values[0]


def formatted_time(value):
    """保留来源墙上日期和时间，验证日历合法后仅去掉小数秒和时区格式。"""
    if not isinstance(value, str):
        raise ValueError('Source request time is invalid')
    match = re.fullmatch(r'(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.\d+)?(?:Z|([+-])(\d{2}):?(\d{2}))?', value.strip())
    if not match or match.group(3) and (int(match.group(4)) > 23 or int(match.group(5)) > 59):
        raise ValueError('Source request time is invalid')
    result = match.group(1) + ' ' + match.group(2)
    datetime.datetime.strptime(result, '%Y-%m-%d %H:%M:%S')
    return result


def expected_request(directory, metadata, facts, entity):
    """允许的变化仅为实体、秒级时间及确定性响应模式；ID 和其他 metadata 全部原样保留。"""
    expected = dict(metadata)
    source_id(metadata)
    expected['providerCode'] = entity
    expected['requestTime'] = formatted_time(metadata.get('requestTime'))
    stream_file = directory + '/biz_stream_response.jsonl'
    sync_file = directory + '/biz_response.json'
    mode = expected.get('requestMode')
    if mode is None or mode == '':
        stream = facts['stream'].get(directory)
        if isinstance(stream, bool):
            mode = 'STREAM' if stream else 'SYNC'
        elif (stream_file in facts['files']) != (sync_file in facts['files']):
            mode = 'STREAM' if stream_file in facts['files'] else 'SYNC'
        expected['requestMode'] = mode
    if mode not in ('STREAM', 'SYNC') or directory + '/biz_request.json' not in facts['files']:
        raise ValueError('Source request protocol is incomplete')
    required = stream_file if mode == 'STREAM' else sync_file
    alternate = sync_file if mode == 'STREAM' else stream_file
    if required not in facts['files'] and alternate not in facts['files']:
        raise ValueError('Source request response is missing')
    rename = {alternate: required} if required not in facts['files'] and alternate in facts['files'] else {}
    return expected, rename


def fingerprint(files):
    """排序摘要用于留证，输出不包含请求或响应正文以及完整 metadata。"""
    encoded = json.dumps(files, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def audit(sources, artifact, entity):
    """独立构建来源内容全集后对比最终包，任何丢请求、正文变化或 ID 改写都不通过。"""
    combined = {'files': {}, 'directories': set(), 'metadata': {}, 'stream': {}, 'contentBytes': 0}
    evidence = []
    for source in sources:
        facts = scan(source)
        if combined['directories'] & facts['directories']:
            raise ValueError('Duplicate source request directory')
        combined['directories'].update(facts['directories'])
        for key in ('files', 'metadata', 'stream'):
            combined[key].update(facts[key])
        combined['contentBytes'] += facts['contentBytes']
        evidence.append({'fileName': source.name, 'compressedBytes': source.stat().st_size,
                         'sha256': checksum(source), 'requestCount': len(facts['metadata']),
                         'fileCount': len(facts['files']), 'contentBytes': facts['contentBytes']})
    final = scan(artifact)
    if combined['directories'] != final['directories']:
        raise ValueError('Request directory set differs from complete source archives')
    expected_files = {}
    ids = set()
    grouped_files = {}
    for name, fact in combined['files'].items():
        grouped_files.setdefault(name.split('/')[0], {})[name] = fact
    for directory, metadata in combined['metadata'].items():
        expected, rename = expected_request(directory, metadata, combined, entity)
        request_id = source_id(metadata)
        if request_id in ids:
            raise ValueError('Duplicate source request ID')
        ids.add(request_id)
        if json.dumps(expected, sort_keys=True) != json.dumps(final['metadata'][directory], sort_keys=True):
            raise ValueError('Metadata differs from authorized source transformations')
        for name, fact in grouped_files[directory].items():
            if name != directory + '/metadata.json':
                expected_files[rename.get(name, name)] = fact
    final_files = {name: fact for name, fact in final['files'].items() if name != name.split('/')[0] + '/metadata.json'}
    if expected_files != final_files:
        raise ValueError('Non-metadata files differ from complete source archives')
    return {'status': 'PASSED', 'schemaVersion': 1, 'entityCode': entity, 'sources': evidence,
            'sourceArchiveCount': len(sources), 'requestCount': len(ids), 'sourceFileCount': len(combined['files']),
            'artifactFileCount': len(final['files']), 'sourceContentBytes': combined['contentBytes'],
            'artifactContentBytes': final['contentBytes'], 'artifact': {'fileName': artifact.name,
            'compressedBytes': artifact.stat().st_size, 'sha256': checksum(artifact)},
            'preservedContentSha256': fingerprint(expected_files), 'directoryNamesPreserved': True,
            'requestIdsPreserved': True, 'requestTimesNormalized': True, 'nonMetadataContentPreserved': True}


def main():
    """CLI 只读来源与产物；收据路径不得覆盖输入，失败不得遗留以前的通过收据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, action='append', required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--entity-code', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in {path.resolve() for path in [*args.source, args.artifact]}:
        parser.error('Evidence output must not overwrite an input archive')
    try:
        report = audit(args.source, args.artifact, args.entity_code)
    except Exception as error:
        # 不输出底层异常的成员或 JSON 内容，防止业务输入混入 Secret 或正文。
        report = {'status': 'FAILED', 'schemaVersion': 1, 'errorType': type(error).__name__}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        raise SystemExit('Independent archive content audit failed') from None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'CONTENT VERIFIED requests={report["requestCount"]} files={report["artifactFileCount"]}')


if __name__ == '__main__':
    main()
