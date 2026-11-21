import ftplib
import hashlib
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import ftp_download_verify as node
from ftp_retention import retain_delivery_date


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

    def retrbinary(self, command, callback):
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
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        remote = self.root / "remote"
        remote.mkdir()
        self.ftp = FileFtp(remote)
        source = self.root / "source.tar.gz"
        source.write_bytes(b"verified archive bytes")
        self.request = {
            "host": "ftp-test", "port": 21, "username": "test", "password": "test-secret",
            "rootPath": "/", "remotePath": "2026-11-21/batch_00001.tar.gz",
            "sourcePath": str(source), "downloadPath": str(self.root / "downloads" / "batch_00001.tar.gz"),
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

    def test_same_date_retry_cleans_late_other_date_directory(self):
        self.transfer()
        self.write_remote("2026-11-19/late-file.tar.gz")
        result = self.transfer()
        self.assertEqual(result["removedDates"], ["2026-11-19"])
        self.assertFalse(self.ftp.path("2026-11-19").exists())
        self.assertTrue(self.ftp.path(self.request["remotePath"]).exists())

    def test_new_date_waits_for_transfer_and_old_retry_cannot_delete_it(self):
        self.write_remote("2026-11-21/keep.tar.gz")
        next_request = dict(self.request, deliveryDate="2026-11-22", remotePath="2026-11-22/batch_00001.tar.gz")
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


if __name__ == "__main__":
    unittest.main()
