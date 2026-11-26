import ftplib
import datetime
import hashlib
import fcntl
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import ftp_download_verify as node
from ftp_retention import retain_delivery_date
import ftp_retention as retention
from ftp_capacity import reserve_capacity


class FileFtp:
    """本地文件模拟 FTP 协议结果，验证删除边界和真实字节校验，不访问业务服务。"""
    def __init__(self, root):
        self.root = root
        self.retrievals = 0
        self.fail_retrieval = False

    def path(self, value):
        return self.root / value.lstrip("/")

    def connect(self, *args, **kwargs): pass
    def login(self, *args): pass
    def voidcmd(self, *args): pass
    def quit(self): pass
    def close(self): pass

    def mlsd(self, value):
        for path in self.path(value).iterdir():
            kind = "OS.unix=slink" if path.is_symlink() else "dir" if path.is_dir() else "file"
            yield path.name, {"type": kind}

    def size(self, value):
        path = self.path(value)
        if not path.is_file():
            raise ftplib.error_perm("550 not found")
        return path.stat().st_size

    def mkd(self, value):
        try:
            self.path(value).mkdir()
        except FileExistsError:
            raise ftplib.error_perm("550 directory exists")

    def storbinary(self, command, stream):
        self.path(command.removeprefix("STOR ")).write_bytes(stream.read())

    def retrbinary(self, command, callback, blocksize=8192):
        self.retrievals += 1
        if self.fail_retrieval:
            callback(b"incomplete")
            raise ConnectionError("interrupted")
        callback(self.path(command.removeprefix("RETR ")).read_bytes())

    def rename(self, source, target):
        self.path(source).rename(self.path(target))

    def delete(self, value):
        self.path(value).unlink()

    def rmd(self, value):
        self.path(value).rmdir()


class FtpNodeTest(unittest.TestCase):
    def setUp(self):
        # 普通协议测试固定在可清理时窗；时间门禁由独立边界用例验证，避免测试依赖执行时间。
        window = patch("ftp_retention.cleanup_window_open", return_value=True)
        window.start()
        self.addCleanup(window.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        remote = self.root / "remote"
        remote.mkdir()
        self.ftp = FileFtp(remote)
        source = self.root / "source.tar.gz"
        source.write_bytes(b"verified archive bytes")
        self.request = {
            "host": "ftp-test", "port": 21, "username": "test", "password": "test-secret",
            "rootPath": "/", "remotePath": "2026-11-21/batch_0000001.tar.gz",
            "sourcePath": str(source), "downloadPath": str(self.root / "downloads" / "batch_0000001.tar.gz"),
            "expectedSize": source.stat().st_size, "expectedSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "cleanupOtherDeliveryDates": True, "deliveryDate": "2026-11-21", "retentionRoot": str(self.root / "locks")}

    def tearDown(self):
        self.temp.cleanup()

    def write_remote(self, path, content=b"old"):
        target = self.ftp.path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    def transfer(self):
        with patch.object(node.ftplib, "FTP", return_value=self.ftp):
            return node.transfer(self.request)

    def test_capacity_counts_active_reservations_and_allows_retry_at_limit(self):
        # 未开始 STOR 的活动预留也占一位，完成文件及 partial 不再重复计数。
        first = dict(self.request)
        second = dict(first, remotePath="2026-11-21/batch_0000002.tar.gz")
        third = dict(first, remotePath="2026-11-21/batch_0000003.tar.gz")
        with reserve_capacity(self.ftp, first, maximum=2) as a:
            with reserve_capacity(self.ftp, second, maximum=2) as b:
                with reserve_capacity(self.ftp, third, maximum=2) as c:
                    self.assertTrue(a and b)
                    self.assertFalse(c)
        self.write_remote(first["remotePath"])
        self.write_remote(second["remotePath"]+".partial")
        with reserve_capacity(self.ftp, first, maximum=2) as retry:
            self.assertTrue(retry)
        with reserve_capacity(self.ftp, third, maximum=2) as full:
            self.assertFalse(full)

    def test_capacity_ignores_locked_claim_that_has_not_reached_admission(self):
        # 复现另一 Worker 已取得包锁但仍在等待容量锁的窗口，未写完的记录不能导致 JSON 错误。
        import ftp_capacity as capacity
        identity='\0'.join(str(self.request[k]) for k in ('host','port','username','rootPath'))
        directory=Path(self.request['retentionRoot'])/(hashlib.sha256(identity.encode()).hexdigest()+'.capacity')
        directory.mkdir(parents=True)
        for incomplete in ('','{"remotePath":'):
            with (directory/'waiting.claim').open('w+') as pending:
                pending.write(incomplete);pending.flush();fcntl.flock(pending,fcntl.LOCK_EX)
                with capacity.reserve_capacity(self.ftp,self.request,maximum=1) as granted:
                    self.assertTrue(granted)

    def test_capacity_ignores_abandoned_claims_but_preserves_old_date_files(self):
        with reserve_capacity(self.ftp, self.request, maximum=1) as first:
            self.assertTrue(first)
        other=dict(self.request, remotePath="2026-11-21/batch_0000002.tar.gz")
        with reserve_capacity(self.ftp, other, maximum=1) as released:
            self.assertTrue(released)
        self.write_remote("2026-11-20/download-in-progress.tar.gz")
        with reserve_capacity(self.ftp, other, maximum=1) as full:
            self.assertFalse(full)
        self.assertTrue(self.ftp.path("2026-11-20/download-in-progress.tar.gz").exists())

    def test_cleanup_preserves_current_date_and_non_date_directories(self):
        self.write_remote("2026-11-19/nested/a.tar.gz")
        self.write_remote("2026-11-22/b.tar.gz")
        self.write_remote("2026-11-21/keep.tar.gz")
        self.write_remote("operator-evidence/keep.txt")
        self.write_remote("2026-02-30/invalid-date.txt")
        result = self.transfer()
        self.assertEqual(sorted(result["removedDates"]), ["2026-11-19", "2026-11-22"])
        self.assertTrue(self.ftp.path("2026-11-21/keep.tar.gz").exists())
        self.assertTrue(self.ftp.path("operator-evidence/keep.txt").exists())
        self.assertTrue(self.ftp.path("2026-02-30/invalid-date.txt").exists())
        self.assertEqual(self.ftp.retrievals, 1)
        self.assertFalse(list(self.root.rglob("*.partial")))

    def test_before_seven_preserves_old_dates_then_same_day_after_seven_cleans(self):
        # 传输与清理分开：凌晨成品正常交付，旧目录必须继续供用户下载。
        self.write_remote("2026-11-20/keep.tar.gz")
        with patch("ftp_retention.cleanup_window_open", return_value=False):
            early = self.transfer()
        self.assertEqual([], early["removedDates"])
        self.assertTrue(self.ftp.path("2026-11-20/keep.tar.gz").exists())
        self.assertTrue(self.ftp.path(self.request["remotePath"]).exists())
        late = self.transfer()
        self.assertEqual(["2026-11-20"], late["removedDates"])
        self.assertFalse(self.ftp.path("2026-11-20").exists())

    def test_before_seven_advances_watermark_without_deleting_old_dates(self):
        original = dict(self.request)
        with patch("ftp_retention.cleanup_window_open", return_value=False):
            self.transfer()
            self.request.update(deliveryDate="2026-11-22", remotePath="2026-11-22/batch_0000001.tar.gz")
            self.transfer()
        # 新日期已传输后，旧日期重试即使到了清理时窗也不能删除较新成品。
        self.assertTrue(self.ftp.path("2026-11-21/batch_0000001.tar.gz").exists())
        self.assertTrue(self.ftp.path("2026-11-22/batch_0000001.tar.gz").exists())
        self.request = original
        with self.assertRaisesRegex(ValueError, "older delivery date"):
            self.transfer()
        self.assertTrue(self.ftp.path("2026-11-22/batch_0000001.tar.gz").exists())

    def test_exclusive_lock_rechecks_window_if_wait_crosses_midnight(self):
        self.write_remote("2026-11-20/keep.tar.gz")
        with patch("ftp_retention.cleanup_window_open", side_effect=[True, False]):
            result = self.transfer()
        self.assertEqual([], result["removedDates"])
        self.assertTrue(self.ftp.path("2026-11-20/keep.tar.gz").exists())

    def test_same_name_different_sha_is_never_overwritten(self):
        content = b"x" * self.request["expectedSize"]
        self.write_remote(self.request["remotePath"], content)
        with self.assertRaisesRegex(ValueError, "SHA mismatch"):
            self.transfer()
        self.assertEqual(self.ftp.path(self.request["remotePath"]).read_bytes(), content)
        self.assertFalse(list(self.root.rglob("*.partial")))

    def test_interrupted_upload_does_not_publish_final_or_leave_partial(self):
        self.ftp.fail_retrieval = True
        with self.assertRaises(ConnectionError):
            self.transfer()
        self.assertFalse(self.ftp.path(self.request["remotePath"]).exists())
        self.assertFalse(list(self.root.rglob("*.partial")))

    def test_symlink_aborts_cleanup_without_following_target(self):
        self.write_remote("2026-11-19/regular.txt")
        self.write_remote("evidence/keep.txt")
        self.ftp.path("2026-11-19/link").symlink_to(self.ftp.path("evidence"))
        with self.assertRaisesRegex(ValueError, "unsupported"):
            self.transfer()
        self.assertTrue(self.ftp.path("evidence/keep.txt").exists())
        self.assertTrue(self.ftp.path("2026-11-19/regular.txt").exists())

    def test_disabled_retention_and_read_only_preflight_never_delete(self):
        self.write_remote("2026-11-19/keep.tar.gz")
        self.request["cleanupOtherDeliveryDates"] = False
        self.transfer()
        self.assertTrue(self.ftp.path("2026-11-19/keep.tar.gz").exists())

    def test_unsafe_date_or_path_cannot_trigger_cleanup(self):
        self.write_remote("2026-11-19/keep.tar.gz")
        for value in ["2026-11-20/a.tar.gz", "../2026-11-21/a.tar.gz", "2026-11-21/a\nDELE x"]:
            self.request["remotePath"] = value
            with self.assertRaises(ValueError):
                self.transfer()
            self.assertTrue(self.ftp.path("2026-11-19/keep.tar.gz").exists())

    def test_independent_verification_cannot_repair_a_missing_remote_file(self):
        self.request["cleanupOtherDeliveryDates"] = False
        with patch.object(node.ftplib, "FTP", return_value=self.ftp):
            with self.assertRaisesRegex(ValueError, "missing during read-only"):
                node.transfer(self.request, verify_only=True)
        self.assertFalse(list(self.ftp.root.iterdir()))

    def test_independent_verification_streams_bytes_without_local_archive(self):
        self.request["cleanupOtherDeliveryDates"] = False
        self.write_remote(self.request["remotePath"], Path(self.request["sourcePath"]).read_bytes())
        with patch.object(node.ftplib, "FTP", return_value=self.ftp):
            result = node.transfer(self.request, verify_only=True)
        self.assertEqual(self.request["expectedSha256"], result["sha256"])
        self.assertFalse(Path(self.request["downloadPath"]).exists())
        self.assertEqual(1, self.ftp.retrievals)

    def test_independent_verification_rejects_same_size_corruption(self):
        self.request["cleanupOtherDeliveryDates"] = False
        self.write_remote(self.request["remotePath"], b"x" * self.request["expectedSize"])
        with patch.object(node.ftplib, "FTP", return_value=self.ftp):
            with self.assertRaisesRegex(ValueError, "SHA mismatch"):
                node.transfer(self.request, verify_only=True)
        self.assertFalse(Path(self.request["downloadPath"]).exists())

    def test_same_date_retry_cleans_late_other_date_directory(self):
        self.transfer()
        self.write_remote("2026-11-19/late-file.tar.gz")
        result = self.transfer()
        self.assertEqual(result["removedDates"], ["2026-11-19"])
        self.assertFalse(self.ftp.path("2026-11-19").exists())
        self.assertTrue(self.ftp.path(self.request["remotePath"]).exists())

    def test_new_date_waits_for_transfer_and_old_retry_cannot_delete_it(self):
        self.write_remote("2026-11-21/keep.tar.gz")
        next_request = dict(self.request, deliveryDate="2026-11-22", remotePath="2026-11-22/batch_0000001.tar.gz")
        started, finished = threading.Event(), threading.Event()
        errors = []
        def next_date():
            # 独占清理必须等待前一个日期的共享传输锁释放。
            started.set()
            try:
                with retain_delivery_date(self.ftp, next_request):
                    self.write_remote("2026-11-22/keep.tar.gz")
            except Exception as error:
                errors.append(error)
            finally:
                finished.set()
        with retain_delivery_date(self.ftp, self.request):
            worker = threading.Thread(target=next_date)
            worker.start()
            self.assertTrue(started.wait(2))
            self.assertFalse(finished.wait(0.1))
            self.assertTrue(self.ftp.path("2026-11-21/keep.tar.gz").exists())
        worker.join(3)
        self.assertTrue(finished.is_set())
        self.assertFalse(errors)
        with self.assertRaisesRegex(ValueError, "older delivery date"):
            self.transfer()
        self.assertTrue(self.ftp.path("2026-11-22/keep.tar.gz").exists())


class RetentionWindowTest(unittest.TestCase):
    def test_beijing_seven_boundary_uses_aware_clock(self):
        # 主机即使使用 UTC，业务边界仍必须精确落在北京时间 07:00。
        before = datetime.datetime(2026, 11, 25, 22, 59, 59, tzinfo=datetime.timezone.utc)
        at = datetime.datetime(2026, 11, 25, 23, 0, tzinfo=datetime.timezone.utc)
        self.assertFalse(retention.cleanup_window_open(before))
        self.assertTrue(retention.cleanup_window_open(at))
        with self.assertRaisesRegex(ValueError, "timezone-aware"):
            retention.cleanup_window_open(datetime.datetime(2026, 11, 26, 7))


if __name__ == "__main__":
    unittest.main()
