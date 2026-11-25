#!/usr/bin/env python3
"""生产入口内部的全量续跑器：PG 只读，提交走 API，验收通过后回收当前批次的可重建副本。"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
API = 'http://127.0.0.1:8080/api/v1'
COMPOSE = ['docker', 'compose', '-f', 'deploy/docker-compose.production.yml', '--env-file', 'deploy/.env']


def save(path, value):
    """证据先写临时文件再替换；中断不能留下看似完整的验收收据。"""
    partial = path.with_suffix(path.suffix + '.tmp')
    partial.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    partial.replace(path)


def api(path, payload=None):
    """POST 超时不自动重发；重新启动时从 PG 查询已接受的独立提交，避免多消费一批包。"""
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(API + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def query(sql):
    """查询只接受本模块固定 SQL 和已验证整数；普通输出不包含受控配置或完整 Pipeline。"""
    result = subprocess.run(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1',
                            '-U', os.environ['ARCHIVE_DB_USER'], '-d', 'archive_delivery', '-Atq', '-c', sql],
                            check=True, text=True, capture_output=True, timeout=30)
    return json.loads(result.stdout)


def submissions(batch_id):
    """按事实源重建提交顺序；已有提交必须先完成和核验，不能通过新 POST 代替恢复。"""
    return query(f"""SELECT coalesce(json_agg(s ORDER BY delivery_id),'[]') FROM
      (SELECT delivery_id,status,(SELECT sum((t->>'targetCount')::int)
       FROM jsonb_array_elements(target_strategy->'targets') t) AS expected
       FROM archive_delivery.delivery_submit WHERE batch_delivery_id={batch_id}) s""")


def remaining(batch_id):
    """只统计尚未被消费的包；repair 追加的完整来源包也会在后续轮次被重新发现。"""
    return query(f"""SELECT coalesce(json_agg(s),'[]') FROM
      (SELECT entity_code,worker_group,count(DISTINCT new_file_name) AS packages
       FROM archive_delivery.delivery_batch WHERE batch_delivery_id={batch_id} AND delivery_id IS NULL
       GROUP BY entity_code,worker_group) s""")


def wait_delivery(delivery_id, expected, directory):
    """等待真实业务终态；BLOCKED、暂停或超时立即停止新增提交，由正常 retry/resume 修复。"""
    deadline = time.monotonic() + int(os.environ.get('PROD_PIPELINE_OBSERVATION_SECONDS', '7200'))
    while time.monotonic() < deadline:
        summary = api(f'/dashboard/summary?deliveryId={delivery_id}')
        save(directory / f'summary-{delivery_id}.json', summary)
        if summary['deliveryStatus'] == 'DELIVERED' and summary['succeeded'] == expected:
            return
        if summary['blocked'] or summary.get('paused') or summary['deliveryStatus'] in ('BLOCKED', 'CANCELLED'):
            raise RuntimeError(f'Delivery {delivery_id} requires repair; no further submissions created')
        print(f'FULL PROGRESS deliveryId={delivery_id} succeeded={summary["succeeded"]}/{expected}', flush=True)
        time.sleep(10)
    raise TimeoutError(f'Delivery {delivery_id} did not finish within the observation limit')


def verify_delivery(delivery_id, expected, directory):
    """退出码、真实通过标记和日志 SHA 同时约束收据；skipped 不能触发后续回收或提交。"""
    receipt = directory / f'verified-{delivery_id}.json'
    log = directory / f'external-{delivery_id}.log'
    if receipt.exists():
        fact = json.loads(receipt.read_text())
        if fact['deliveryId'] != delivery_id or fact['expected'] != expected or fact['logSha256'] != digest(log):
            raise RuntimeError('Existing verification receipt is inconsistent')
        return
    with log.open('w') as output:
        result = subprocess.run(['bash', '-c', 'run_external_test ARCHIVE_REAL_E2E "$1" verifyRealDelivery "$2"',
                                 'production-external', str(delivery_id), str(expected)], stdout=output,
                                stderr=subprocess.STDOUT, timeout=7200)
    passed = re.search(rf'REAL E2E PASSED deliveryId={delivery_id} tasks={expected}\b', log.read_text())
    if result.returncode or not passed:
        raise RuntimeError(f'Independent external verification failed for delivery {delivery_id}')
    save(receipt, {'deliveryId': delivery_id, 'expected': expected, 'logSha256': digest(log),
                   'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})


def digest(path):
    """大文件以有界缓冲计算真实 SHA，不按文件名或先前节点状态推定字节正确。"""
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            checksum.update(block)
    return checksum.hexdigest()


def local_file(volume, locator, kind, delivery_id):
    """只允许当前提交的 FTP 回验文件或 UUID 源缓存；拒绝符号链接、越界和业务成品路径。"""
    path = PurePosixPath(locator)
    prefix = PurePosixPath('/data/work/source-cache' if kind == 'source' else f'/data/ftp-verify/{delivery_id}')
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('Unsafe local copy locator')
    relative = path.relative_to(prefix)
    if kind == 'source':
        if len(relative.parts) != 2 or not re.fullmatch(r'[0-9a-f-]{36}', relative.parts[0]) or not relative.name.endswith('.tar.gz'):
            raise ValueError('Source cache locator does not match its fixed layout')
    elif len(relative.parts) != 3 or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', relative.parts[0]) or not re.fullmatch(r'entity-\d+', relative.parts[1]) or not re.fullmatch(r'batch_\d{7}\.tar\.gz', relative.name):
        raise ValueError('FTP verification locator does not match its fixed layout')
    candidate = volume.joinpath(*path.relative_to('/data').parts)
    for parent in (candidate, *candidate.parents):
        if parent == volume:
            break
        if parent.is_symlink():
            raise ValueError('Local copy path contains a symbolic link')
    if not candidate.resolve().is_relative_to(volume.resolve()):
        raise ValueError('Local copy path escapes the selected Docker volume')
    return candidate


def cleanup_candidates_sql(delivery_id, batch_id, evict_verified_sources=False):
    """授权可忽略其他批次的未消费规划引用；本批次未交付包及所有已建未完成任务始终保护。"""
    source_guard = "NOT EXISTS(SELECT 1 FROM archive_delivery.delivery_batch other WHERE other.data_id=sd.data_id AND other.status<>'DELIVERED')"
    if evict_verified_sources:
        source_guard = f"""NOT EXISTS(SELECT 1 FROM archive_delivery.delivery_batch other
          WHERE other.data_id=sd.data_id AND other.batch_delivery_id={batch_id} AND other.status<>'DELIVERED')
          AND NOT EXISTS(SELECT 1 FROM archive_delivery.delivery_batch other
            JOIN archive_delivery.delivery_task active ON active.delivery_id=other.delivery_id AND active.new_file_name=other.new_file_name
            WHERE other.data_id=sd.data_id AND active.execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED'))"""
    return f"""SELECT json_build_object(
      'status',(SELECT status FROM archive_delivery.delivery_submit WHERE delivery_id={delivery_id} AND batch_delivery_id={batch_id}),
      'taskCount',(SELECT count(*) FROM archive_delivery.delivery_task WHERE delivery_id={delivery_id} AND execution_status NOT IN('EXPIRED','CANCELLED')),
      'unfinished',(SELECT count(*) FROM archive_delivery.delivery_task WHERE delivery_id={delivery_id} AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')),
      'files',coalesce((SELECT json_agg(f) FROM (
        SELECT DISTINCT 'source' AS kind,sd.original_locator->>'cachePath' AS path,sd.size_bytes AS size,sd.sha256
        FROM archive_delivery.delivery_batch b JOIN archive_delivery.source_data sd ON sd.data_id=b.data_id
        WHERE b.batch_delivery_id={batch_id} AND b.delivery_id={delivery_id}
          AND {source_guard}
        UNION ALL
        SELECT 'ftp',n.output_locator->>'downloadPath',(n.output_locator->>'sizeBytes')::bigint,n.output_locator->>'sha256'
        FROM archive_delivery.delivery_node n JOIN archive_delivery.delivery_task t ON t.task_id=n.task_id
        WHERE t.delivery_id={delivery_id} AND t.execution_status='SUCCEEDED' AND n.node_type='ftp.download-verify' AND n.status='SUCCEEDED'
      ) f),'[]'))"""


def cleanup(delivery_id, batch_id, directory, volume):
    """先独立验收和逐文件核对，再重查引用；只删除白名单内可重建副本，保留最终交付及事实。"""
    evict_sources = os.environ.get('PROD_EVICT_VERIFIED_SOURCE_CACHE', 'false') == 'true'
    sql = cleanup_candidates_sql(delivery_id, batch_id, evict_sources)
    facts = query(sql)
    if facts['status'] != 'DELIVERED' or facts['unfinished']:
        raise RuntimeError('Local copy cleanup requires an independently verified completed submission')
    receipt = json.loads((directory / f'verified-{delivery_id}.json').read_text())
    if receipt['deliveryId'] != delivery_id or receipt['expected'] != facts['taskCount'] or receipt['logSha256'] != digest(directory / f'external-{delivery_id}.log'):
        raise RuntimeError('Local copy cleanup requires a matching independent verification receipt')
    records = []
    for fact in facts['files']:
        path = local_file(volume, fact['path'], fact['kind'], delivery_id)
        if not path.exists():
            continue
        if not path.is_file() or path.stat().st_size != fact['size'] or digest(path) != fact['sha256']:
            raise RuntimeError(f'Local copy differs from verified facts: {fact["path"]}')
        records.append(fact)
    # 先持久化完整白名单，再逐文件记录回收；中断后可按清单核对，而非盲目清空目录。
    audit = directory / f'local-copies-{delivery_id}-{time.time_ns()}.json'
    result = {'deliveryId': delivery_id, 'batchDeliveryId': batch_id, 'files': records, 'removed': [], 'skipped': [],
              'evictVerifiedSourceCache': evict_sources, 'status': 'PLANNED'}
    save(audit, result)
    # SHA 计算期间可能接受其他提交；删除前重查所有待删源的已建任务引用，避免影响在途作业。
    current = query(sql)
    if current['status'] != 'DELIVERED' or current['unfinished']:
        raise RuntimeError('Submission changed before local copy cleanup')
    eligible = {(fact['kind'], fact['path'], fact['size'], fact['sha256']) for fact in current['files']}
    for fact in records:
        if (fact['kind'], fact['path'], fact['size'], fact['sha256']) not in eligible:
            result['skipped'].append(fact['path'])
            save(audit, result)
            continue
        local_file(volume, fact['path'], fact['kind'], delivery_id).unlink()
        result['removed'].append(fact['path'])
        save(audit, result)
    result['status'] = 'COMPLETE'
    save(audit, result)
    print(f'FULL VERIFIED deliveryId={delivery_id} freedBytes={sum(f["size"] for f in records if f["path"] in result["removed"])}', flush=True)


def run(args):
    """单个生产入口串行创建提交，各提交内部仍由八个 group1 Worker 满并发运行。"""
    os.chdir(ROOT)
    args.evidence.mkdir(parents=True, exist_ok=True)
    batch = api(f'/delivery-batches/{args.batch_id}')
    for key, name in [('deliveryBatch', 'PROD_DELIVERY_BATCH'), ('sourceUri', 'PROD_OSS_URI')]:
        if batch[key] != os.environ[name]:
            raise ValueError('Existing batch differs from the authorized source and batch')
    targets = json.loads((ROOT / 'deploy/golden-case.json').read_text())['targets']
    if len(targets) != 1:
        raise ValueError('Full continuation requires one explicit entity/worker target')
    target = targets[0]
    mount = subprocess.run(['docker', 'volume', 'inspect', '--format', '{{.Mountpoint}}', os.environ['DATA_VOLUME']],
                           check=True, text=True, capture_output=True, timeout=20).stdout.strip()
    volume = Path(mount)
    handled = set()
    while True:
        for submit in submissions(args.batch_id):
            delivery_id = submit['delivery_id']
            if delivery_id in handled:
                continue
            wait_delivery(delivery_id, submit['expected'], args.evidence)
            verify_delivery(delivery_id, submit['expected'], args.evidence)
            save(args.evidence / f'delivery-{delivery_id}.json', api(f'/deliveries/{delivery_id}'))
            save(args.evidence / f'tasks-{delivery_id}.json', api(f'/deliveries/{delivery_id}/tasks'))
            # 实测产物可能远小于源包；空间充足时保留缓存，仅低水位时回收已独立核验的副本。
            if shutil.disk_usage(volume).free < 75 * 1024**3:
                cleanup(delivery_id, args.batch_id, args.evidence, volume)
            handled.add(delivery_id)
        available = remaining(args.batch_id)
        if any(row['entity_code'] != target['entityCode'] or row['worker_group'] != target['workerGroup'] for row in available):
            raise RuntimeError('Remaining packages include an unauthorized entity or worker group')
        count = sum(row['packages'] for row in available)
        if not count:
            final = api(f'/dashboard/summary?deliveryId={args.batch_id}')
            save(args.evidence / 'batch-final-summary.json', final)
            save(args.evidence / 'batch-final-packages.json', api(f'/delivery-batches/{args.batch_id}/packages'))
            if final['deliveryStatus'] != 'DELIVERED':
                raise RuntimeError('No available packages but the batch is not delivered')
            print(f'FULL BATCH PASSED batchDeliveryId={args.batch_id} submissions={len(handled)}', flush=True)
            return
        if shutil.disk_usage(volume).free < 30 * 1024**3:
            raise RuntimeError('Less than 30 GiB remains; no further packages consumed')
        payload = {'ossUri': os.environ['PROD_OSS_URI'], 'pipelineCode': 'archive-delivery',
                   'deliveryDate': os.environ['PROD_DELIVERY_DATE'], 'deliveryBatch': os.environ['PROD_DELIVERY_BATCH'],
                   'targets': [{'vendorCode': target['vendorCode'], 'entityCode': target['entityCode'],
                                'targetCount': min(args.wave_size, count)}]}
        save(args.evidence / f'next-submit-{len(handled) + 1}.json', payload)
        accepted = api('/deliveries', payload)
        save(args.evidence / f'accepted-{accepted["deliveryId"]}.json', accepted)
        print(f'FULL SUBMITTED deliveryId={accepted["deliveryId"]} targetCount={min(args.wave_size, count)} remaining={count}', flush=True)


def main():
    # 批次级本机排他锁覆盖整个续跑，避免重复启动造成并行提交与副本回收竞争。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch_id', type=int)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--wave-size', type=int, default=100)
    args = parser.parse_args()
    if args.batch_id <= 0 or not 1 <= args.wave_size <= 100:
        parser.error('batch ID must be positive and wave-size must be 1..100')
    args.evidence = args.evidence.resolve()
    lock_path = ROOT / '.tools' / f'production-batch-{args.batch_id}.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        run(args)


if __name__ == '__main__':
    main()
