"""全量续跑的破坏性边界只在临时目录和固定事实替身中验证，不连接 OSS/FTP/PG。"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('production_remaining', Path(__file__).with_name('production-remaining.py'))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class ProductionRemainingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.volume = self.root / 'volume'
        self.volume.mkdir()
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.locator = '/data/work/source-cache/00000000-0000-0000-0000-000000000001/source.tar.gz'
        self.path = self.volume / self.locator.removeprefix('/data/')
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(b'original source bytes')
        self.fact = {'kind': 'source', 'path': self.locator, 'size': self.path.stat().st_size,
                     'sha256': hashlib.sha256(self.path.read_bytes()).hexdigest()}

    def tearDown(self):
        self.temporary.cleanup()

    def receipt(self):
        log = self.evidence / 'external-6.log'
        log.write_text('REAL E2E PASSED deliveryId=6 tasks=2 sources=3\n')
        runner.save(self.evidence / 'verified-6.json', {'deliveryId': 6, 'expected': 2, 'logSha256': runner.digest(log)})

    def test_cleanup_removes_only_listed_verified_local_files(self):
        final = self.volume / 'final-ftp.tar.gz'
        final.write_bytes(b'preserve business artifact')
        self.receipt()
        facts = {'status': 'DELIVERED', 'unfinished': 0, 'taskCount': 2, 'files': [self.fact]}
        with patch.object(runner, 'query', return_value=facts):
            runner.cleanup(6, 5, self.evidence, self.volume)
        self.assertFalse(self.path.exists())
        self.assertTrue(final.exists())
        receipts = list(self.evidence.glob('local-copies-6-*.json'))
        self.assertEqual([self.fact], json.loads(receipts[0].read_text())['files'])

    def test_failed_or_running_submission_cannot_remove_cache(self):
        for status, unfinished in [('BLOCKED', 0), ('DELIVERED', 1)]:
            facts = {'status': status, 'unfinished': unfinished, 'files': [self.fact]}
            with patch.object(runner, 'query', return_value=facts), self.assertRaises(RuntimeError):
                runner.cleanup(6, 5, self.evidence, self.volume)
            self.assertTrue(self.path.exists())

    def test_source_newly_referenced_by_active_task_is_preserved(self):
        # 文件核对期间出现新引用时，二次事实查询必须将其移出白名单，不能按旧快照删除。
        self.receipt()
        before = {'status': 'DELIVERED', 'unfinished': 0, 'taskCount': 2, 'files': [self.fact]}
        after = dict(before, files=[])
        with patch.object(runner, 'query', side_effect=[before, after]):
            runner.cleanup(6, 5, self.evidence, self.volume)
        self.assertTrue(self.path.exists())
        audit = json.loads(next(self.evidence.glob('local-copies-6-*.json')).read_text())
        self.assertEqual([], audit['removed'])
        self.assertEqual([self.locator], audit['skipped'])

    def test_explicit_eviction_permission_is_recorded_with_exact_file_audit(self):
        self.receipt()
        facts = {'status': 'DELIVERED', 'unfinished': 0, 'taskCount': 2, 'files': [self.fact]}
        with patch.dict(os.environ, {'PROD_EVICT_VERIFIED_SOURCE_CACHE': 'true'}), \
             patch.object(runner, 'query', return_value=facts):
            runner.cleanup(6, 5, self.evidence, self.volume)
        audit = json.loads(next(self.evidence.glob('local-copies-6-*.json')).read_text())
        self.assertTrue(audit['evictVerifiedSourceCache'])
        self.assertEqual([self.locator], audit['removed'])

    def test_same_size_wrong_sha_is_never_deleted(self):
        self.receipt()
        self.path.write_bytes(b'x' * self.fact['size'])
        with patch.object(runner, 'query', return_value={'status': 'DELIVERED', 'unfinished': 0, 'taskCount': 2, 'files': [self.fact]}):
            with self.assertRaises(RuntimeError):
                runner.cleanup(6, 5, self.evidence, self.volume)
        self.assertTrue(self.path.exists())

    def test_delivered_without_external_receipt_cannot_delete_local_copy(self):
        with patch.object(runner, 'query', return_value={'status': 'DELIVERED', 'unfinished': 0, 'taskCount': 2, 'files': [self.fact]}):
            with self.assertRaises(FileNotFoundError):
                runner.cleanup(6, 5, self.evidence, self.volume)
        self.assertTrue(self.path.exists())

    def test_paths_cannot_escape_cache_or_cross_delivery(self):
        for locator, kind in [('/data/work/source-cache/../final.tar.gz', 'source'),
                              ('/data/ftp-verify/7/2026-11-24/entity-1/batch_0000001.tar.gz', 'ftp'),
                              ('/srv/ftp/batch_0000001.tar.gz', 'source')]:
            with self.assertRaises(ValueError):
                runner.local_file(self.volume, locator, kind, 6)

    def test_symlink_cannot_redirect_cache_cleanup(self):
        self.path.unlink()
        outside = self.root / 'outside.tar.gz'
        outside.write_bytes(b'preserve')
        self.path.symlink_to(outside)
        with self.assertRaises(ValueError):
            runner.local_file(self.volume, self.locator, 'source', 6)
        self.assertTrue(outside.exists())

    def test_skipped_external_test_does_not_create_receipt(self):
        def skipped(*args, **kwargs):
            kwargs['stdout'].write('Tests run: 1, Failures: 0, Errors: 0, Skipped: 1\n')
            return types.SimpleNamespace(returncode=0)
        with patch.object(runner.subprocess, 'run', side_effect=skipped), self.assertRaises(RuntimeError):
            runner.verify_delivery(6, 2, self.evidence)
        self.assertFalse((self.evidence / 'verified-6.json').exists())

    def test_blocked_summary_stops_without_creating_another_submission(self):
        summary = {'deliveryStatus': 'BLOCKED', 'succeeded': 1, 'blocked': 1}
        with patch.object(runner, 'api', return_value=summary) as api, self.assertRaises(RuntimeError):
            runner.wait_delivery(6, 2, self.evidence)
        api.assert_called_once_with('/dashboard/summary?deliveryId=6')

    def test_full_run_resumes_trial_and_submits_the_small_tail_once(self):
        # 已存在的两包试跑必须先核验；仅剩三个包时只提交三个，不能按波次大小多领或重发。
        deploy = self.root / 'deploy'
        deploy.mkdir()
        (deploy / 'golden-case.json').write_text(json.dumps({'targets': [
            {'vendorCode': 'alibaba', 'entityCode': 'jun-2', 'workerGroup': 'group1'}]}))
        requests = []

        def api(path, payload=None):
            if payload is not None:
                requests.append(payload)
                return {'deliveryId': 7}
            if path == '/delivery-batches/5':
                return {'deliveryBatch': 'test-batch', 'sourceUri': 'oss://bucket/products/'}
            if path.startswith('/dashboard/'):
                return {'deliveryStatus': 'DELIVERED'}
            return {}

        first = [{'delivery_id': 6, 'expected': 2}]
        second = first + [{'delivery_id': 7, 'expected': 3}]
        environment = {'PROD_DELIVERY_BATCH': 'test-batch', 'PROD_OSS_URI': 'oss://bucket/products/',
                       'PROD_DELIVERY_DATE': '2026-11-24', 'DATA_VOLUME': 'test-volume'}
        args = types.SimpleNamespace(batch_id=5, evidence=self.evidence, wave_size=100)
        with patch.dict(os.environ, environment), patch.object(runner, 'ROOT', self.root), \
             patch.object(runner.os, 'chdir'), patch.object(runner, 'api', side_effect=api), \
             patch.object(runner, 'submissions', side_effect=[first, second]), \
             patch.object(runner, 'remaining', side_effect=[[{'entity_code': 'jun-2', 'worker_group': 'group1', 'packages': 3}], []]), \
             patch.object(runner, 'wait_delivery'), patch.object(runner, 'verify_delivery') as verify, \
             patch.object(runner, 'cleanup'), patch.object(runner.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=50 * 1024**3)), \
             patch.object(runner.subprocess, 'run', return_value=types.SimpleNamespace(stdout=str(self.volume))):
            runner.run(args)
        self.assertEqual([3], [request['targets'][0]['targetCount'] for request in requests])
        self.assertEqual([6, 7], [call.args[0] for call in verify.call_args_list])


if __name__ == '__main__':
    unittest.main()
