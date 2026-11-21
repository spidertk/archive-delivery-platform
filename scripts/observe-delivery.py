#!/usr/bin/env python3
"""在交付机上有界采集非 Secret 资源和 PG 状态，供稳定性验收与扩容评估。"""
import argparse
import datetime
import json
import pathlib
import shutil
import subprocess
import time


def command(argv):
    """只执行固定 argv 的只读命令；错误不回显受控配置或容器环境。"""
    result = subprocess.run(argv, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError("Observation command failed: " + argv[0])
    return result.stdout


def memory():
    """记录 available 和 swap 使用量，避免把可回收文件缓存误判为内存不足。"""
    values = {}
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        values[key] = int(value.split()[0]) * 1024
    return {key: values[key] for key in ['MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree']}


def cpu():
    """保留累计 tick，由相邻采样计算 CPU 和 IO wait，首条不当作区间速率。"""
    line = pathlib.Path('/proc/stat').read_text().splitlines()[0]
    ticks = list(map(int, line.split()[1:9]))
    return {'total': sum(ticks), 'idle': ticks[3], 'iowait': ticks[4]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--db-user', default='archive')
    parser.add_argument('--duration', type=int, default=21600)
    parser.add_argument('--interval', type=int, default=15)
    args = parser.parse_args()
    if not 5 <= args.interval <= 300 or not 1 <= args.duration <= 86400:
        parser.error('interval must be 5..300 seconds and duration 1..86400 seconds')
    compose = ['docker', 'compose', '-f', 'deploy/docker-compose.production.yml', '--env-file', 'deploy/.env']
    query = """SET search_path TO archive_delivery;
SELECT json_build_object(
 'deliveries',(SELECT coalesce(json_agg(s),'[]') FROM
  (SELECT delivery_id,submission_type,status,delivery_date FROM delivery_submit ORDER BY delivery_id) s),
 'tasks',(SELECT coalesce(json_agg(t),'[]') FROM
  (SELECT execution_status,current_node_id,count(*) FROM delivery_task GROUP BY 1,2) t),
 'failedNodes',(SELECT count(*) FROM delivery_node WHERE status='FAILED'),
 'expiredLeases',(SELECT count(*) FROM delivery_task WHERE execution_status='RUNNING' AND lease_until<now()),
 'pgDeadlocks',(SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()));"""
    path = pathlib.Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.duration
    with path.open('a', buffering=1) as output:
        # 结束标记由完成外部验收的调用方写入，采样器本身不改变任何交付状态。
        while time.monotonic() < deadline and not path.with_suffix('.stop').exists():
            sample = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'memory': memory(), 'cpu': cpu(),
                      'disk': shutil.disk_usage('/')._asdict(), 'load': pathlib.Path('/proc/loadavg').read_text().strip()}
            try:
                facts = command(compose + ['exec', '-T', 'postgres', 'psql', '-U', args.db_user, '-d',
                                          'archive_delivery', '-Atq', '-c', query])
                sample['database'] = json.loads(facts)
                sample['containers'] = [json.loads(line) for line in command(
                    ['docker', 'stats', '--no-stream', '--format', '{{json .}}']).splitlines()]
                sample['lifecycle'] = json.loads(command(['docker', 'inspect', '--format',
                    '{{json .State}}', command(compose + ['ps', '-q', 'archive-server']).strip()]))
            except Exception as error:
                sample['observationError'] = type(error).__name__
            output.write(json.dumps(sample, ensure_ascii=False) + '\n')
            time.sleep(min(args.interval, max(0, deadline - time.monotonic())))


if __name__ == '__main__':
    main()
