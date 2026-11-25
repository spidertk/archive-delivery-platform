import fcntl
import gzip
import io
import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
import time
from pathlib import Path

SCRIPT = Path(__file__).with_name("archive_repair.py")
MERGE_SCRIPT = Path(__file__).with_name("archive_merge.py")
ARTIFACT_FIELDS = {"artifactSeq", "originalFileName", "newFileName", "sizeBytes", "sha256", "token", "file_mapping"}


class ArchiveRepairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, *args, expected=0, script=SCRIPT, marker="REPAIR_RESULT "):
        result = subprocess.run([sys.executable, str(script), *map(str, args)], capture_output=True, text=True)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        if expected != 0:
            return result
        line = next(line for line in result.stdout.splitlines() if line.startswith(marker))
        result.json = json.loads(line[len(marker):])
        return result

    def repair_args(self, target, date="2026-10-30", provider="june", manifest=None, output="batch_0000001.tar.gz"):
        manifest = Path(manifest) if manifest is not None else Path(target) / "manifest.json"
        return ("--target", target, "--delivery-date", date, "--entity-code", provider,
                "--max-bytes", 10 * 1024 * 1024,
                "--output-name", output, "--original-name", "batch_0000001.tar.gz", "--manifest", manifest)

    def make_source(self, name, requests):
        source_dir = self.root / (name + "-input")
        source_dir.mkdir()
        source = self.root / name
        for directory, metadata, response, *stream_option in requests:
            request = source_dir / directory
            request.mkdir()
            (request / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            stream = stream_option[0] if stream_option else None
            (request / "biz_request.json").write_text(json.dumps({} if stream is None else {"stream": stream}), encoding="utf-8")
            (request / response).write_text("{}\n", encoding="utf-8")
        with tarfile.open(source, "w:gz") as archive:
            for request in sorted(source_dir.iterdir()):
                archive.add(request, arcname=request.name)
        return source

    def merge_sources(self, sources, name="merged"):
        merged_dir = self.root / name
        merge_args = ["--target", merged_dir / "requests", "--manifest", merged_dir / "merge-result.json"]
        for source in sources:
            merge_args.extend(["--source", source])
        return self.run_script(*merge_args, script=MERGE_SCRIPT, marker="MERGE_RESULT ").json

    def tar_segment(self, directory, metadata, response):
        """构造独立带结束零块的 TAR；拼接时第二段必须作为真实请求读取，不能靠重复实现断言。"""
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w") as archive:
            for name, content in (("metadata.json", json.dumps(metadata).encode()),
                                  ("biz_request.json", b'{"original":true}'),
                                  ("biz_response.json", response)):
                member = tarfile.TarInfo(directory + "/" + name)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        return output.getvalue()

    def test_all_concatenated_tar_and_gzip_segments_keep_content_ids_and_directories(self):
        # 同时复现多 gzip 成员和单 gzip 内拼接 TAR，覆盖生产中第一段之后请求被静默丢弃的故障。
        request_ids = ["b3cbcd69-611f-4789-9282-0013597cc31c", "ee030afb-a95c-4e1a-957c-9d01480cf818"]
        directories = ["original-folder-a", "original-folder-b"]
        responses = [b'first response\n' * 1700, b'second response\n' * 2100]
        segments = [self.tar_segment(directory, {"requestId": request_id, "requestMode": "SYNC",
                    "requestTime": "2026-11-19T11:18:34.60359085+08:00"}, response)
                    for directory, request_id, response in zip(directories, request_ids, responses)]
        for layout in ("gzip-members", "tar-members"):
            with self.subTest(layout=layout):
                source = self.root / (layout + ".tar.gz")
                source.write_bytes(b"".join(gzip.compress(segment) for segment in segments) if layout == "gzip-members"
                                   else gzip.compress(b"".join(segments)))
                merged = self.merge_sources([source], "merged-" + layout)
                self.assertEqual(2, merged["requestCount"])
                self.assertEqual(set(directories), {member["originalPath"] for member in merged["members"]})
                result = self.run_script("--source", merged["workspacePath"], *self.repair_args(
                    self.root / ("out-" + layout), date="2026-11-25", provider="jun-2")).json
                self.assertEqual(2, result["requestCount"])
                self.assertEqual(dict(zip(directories, directories)), result["artifacts"][0]["file_mapping"])
                with tarfile.open(result["artifactPath"], "r:gz") as archive:
                    self.assertEqual(set(directories), {member.name.split("/")[0] for member in archive.getmembers()})
                    for directory, request_id, response in zip(directories, request_ids, responses):
                        metadata = json.load(archive.extractfile(directory + "/metadata.json"))
                        self.assertEqual(request_id, metadata["requestId"])
                        self.assertEqual("2026-11-19 11:18:34", metadata["requestTime"])
                        self.assertEqual("jun-2", metadata["providerCode"])
                        self.assertEqual(response, archive.extractfile(directory + "/biz_response.json").read())
                        self.assertEqual(b'{"original":true}', archive.extractfile(directory + "/biz_request.json").read())

    def test_corrupt_later_concatenated_segment_cannot_publish_partial_workspace(self):
        # 第一段有效也不能掩盖后续 gzip 截断或坏 TAR 头；失败不发布 merge manifest 或工作目录。
        segment = self.tar_segment("original", {"requestId": "original", "requestMode": "SYNC",
                                               "requestTime": "2026-11-19 11:18:34"}, b"response")
        broken = {"truncated-gzip": gzip.compress(segment) + gzip.compress(segment)[:-8],
                  "invalid-tar": gzip.compress(segment + b"invalid header".ljust(512, b"x"))}
        for layout, content in broken.items():
            with self.subTest(layout=layout):
                source = self.root / (layout + ".tar.gz")
                source.write_bytes(content)
                directory = self.root / ("merged-" + layout)
                self.run_script("--source", source, "--target", directory / "requests", "--manifest",
                                directory / "merge-result.json", script=MERGE_SCRIPT, expected=1)
                self.assertFalse((directory / "requests").exists())
                self.assertFalse((directory / "merge-result.json").exists())
                self.assertFalse(list(directory.glob(".archive_merge_*")))

    def test_original_snake_case_id_is_preserved_without_adding_an_alias(self):
        source = self.make_source("snake-id.tar.gz", [("original-folder", {
            "request_id": "b3cbcd69-611f-4789-9282-0013597cc31c", "requestMode": "SYNC",
            "requestTime": "2026-11-19T11:18:34.60359085+08:00"}, "biz_response.json")])
        merged = self.merge_sources([source])
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(self.root / "out")).json
        with tarfile.open(result["artifactPath"], "r:gz") as archive:
            metadata = json.load(archive.extractfile("original-folder/metadata.json"))
            self.assertEqual("b3cbcd69-611f-4789-9282-0013597cc31c", metadata["request_id"])
            self.assertNotIn("requestId", metadata)
            self.assertEqual("2026-11-19 11:18:34", metadata["requestTime"])

    def test_root_file_cannot_be_silently_discarded_during_merge(self):
        source = self.root / "root-file.tar.gz"
        segment = self.tar_segment("original", {"requestId": "original", "requestMode": "SYNC",
                                               "requestTime": "2026-11-19 11:18:34"}, b"response")
        extra = io.BytesIO()
        with tarfile.open(fileobj=extra, mode="w") as archive:
            member = tarfile.TarInfo("unowned.txt")
            member.size = 7
            archive.addfile(member, io.BytesIO(b"keep me"))
        source.write_bytes(gzip.compress(segment + extra.getvalue()))
        result = self.run_script("--source", source, "--target", self.root / "requests", "--manifest",
                                 self.root / "merge.json", script=MERGE_SCRIPT, expected=1)
        self.assertIn("non-request top-level member", result.stderr)
        self.assertFalse((self.root / "merge.json").exists())

    def test_invalid_source_calendar_and_missing_time_are_rejected(self):
        for index, value in enumerate((None, "2026-02-30 12:34:56", "2026-11-19 25:00:00")):
            with self.subTest(value=value):
                source = self.make_source(f"bad-time-{index}.tar.gz", [("original", {
                    "requestId": "original", "requestMode": "SYNC", "requestTime": value}, "biz_response.json")])
                merged = self.merge_sources([source], f"merged-{index}")
                target = self.root / f"out-{index}"
                self.run_script("--source", merged["workspacePath"], *self.repair_args(target), expected=1)
                self.assertFalse((target / "batch_0000001.tar.gz").exists())

    @staticmethod
    def request_id(index, date="20261027"):
        return date + "123456" + f"{index:025d}"

    def test_inspect_and_production_repair_preserve_source_request_ids(self):
        first = self.request_id(1)
        second = self.request_id(2)
        source = self.make_source("batch_0000007.tar.gz", [
            (first, {"requestId": first, "requestMode": "STREAM", "requestTime": "2026-10-27 12:34:56.12"}, "biz_response.json"),
            (second, {"requestId": second, "requestMode": "SYNC", "requestTime": "2026-10-27T01:02:03.123456789Z"}, "biz_stream_response.jsonl")])
        merged = self.merge_sources([source])
        inspected = self.run_script("--source", merged["workspacePath"], "--inspect", marker="INSPECT_RESULT ")
        self.assertEqual(inspected.json["requestCount"], 2)
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(self.root / "out", date="2026-11-01"))
        payload = result.json
        self.assertEqual([mapping["requestId"] for mapping in payload["mappings"]], [first, second])
        artifact = payload["artifacts"][0]
        self.assertEqual(set(artifact), ARTIFACT_FIELDS)
        with tarfile.open(payload["artifactPath"], "r:gz") as archive:
            second_metadata = json.load(archive.extractfile(second + "/metadata.json"))
            first_metadata = json.load(archive.extractfile(first + "/metadata.json"))
            self.assertEqual(second_metadata["requestTime"], "2026-10-27 01:02:03")
            self.assertEqual(first_metadata["requestTime"], "2026-10-27 12:34:56")
            self.assertEqual(first_metadata["providerCode"], "june")
            self.assertIsNotNone(archive.extractfile(second + "/biz_response.json"))
            self.assertIsNotNone(archive.extractfile(first + "/biz_stream_response.jsonl"))

    def test_concurrent_retries_wait_before_cleaning_shared_staging(self):
        # 父进程模拟仍在运行的旧尝试；两个新尝试必须等待同一任务锁，随后顺序产出相同 SHA。
        source = self.make_source('retry.tar.gz', [('original-folder', {
            'requestId': 'original-request', 'requestMode': 'SYNC',
            'requestTime': '2026-11-19 12:34:56'}, 'biz_response.json')])
        merged = self.merge_sources([source])
        target = self.root / 'retry-out'
        target.mkdir()
        stale = target / '.archive_repair_stale'
        stale.mkdir()
        (stale / 'keep-until-previous-finishes').write_text('active previous attempt')
        command = [sys.executable, str(SCRIPT), '--source', merged['workspacePath'],
                   *map(str, self.repair_args(target))]
        children = []
        try:
            with (target / '.repair.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                children = [subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                            for _ in range(2)]
                time.sleep(.3)
                self.assertTrue(all(child.poll() is None for child in children))
                self.assertTrue((stale / 'keep-until-previous-finishes').exists())
                self.assertFalse((target / 'batch_0000001.tar.gz').exists())
            results = []
            for child in children:
                stdout, stderr = child.communicate(timeout=15)
                self.assertEqual(0, child.returncode, stdout + stderr)
                results.append(json.loads(next(line[len('REPAIR_RESULT '):] for line in stdout.splitlines()
                                               if line.startswith('REPAIR_RESULT '))))
            self.assertEqual(results[0]['artifacts'][0]['sha256'], results[1]['artifacts'][0]['sha256'])
            self.assertFalse(stale.exists())
            self.assertFalse(list(target.glob('.archive_repair_*')))
        finally:
            for child in children:
                if child.poll() is None: child.kill()
                child.communicate(timeout=5)

    def test_duplicate_valid_request_ids_are_rejected_without_renumbering(self):
        duplicate = self.request_id(7)
        source = self.make_source("duplicate-valid.tar.gz", [
            ("old_a", {"requestId": duplicate, "requestMode": "SYNC", "requestTime": "2026-10-27 01:02:03"}, "biz_response.json"),
            ("old_z", {"requestId": duplicate, "requestMode": "SYNC", "requestTime": "2026-10-27 12:34:56"}, "biz_response.json")])
        merged = self.merge_sources([source], "merged-valid-duplicates")
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "duplicate-valid-out", date="2026-11-01"), expected=1)
        self.assertIn("Duplicate original requestId", result.stderr + result.stdout)

    def test_missing_request_id_or_time_is_rejected_without_fabrication(self):
        source = self.make_source("batch_00004.tar.gz", [
            ("req_old", {"requestId": "", "requestMode": "SYNC"}, "biz_stream_response.jsonl")])
        merged = self.merge_sources([source])
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "first", provider="june-1", manifest=self.root / "first" / "manifest.json"), expected=1)
        self.assertIn("Source requestId is missing", result.stderr + result.stdout)

    def test_null_request_mode_uses_request_stream_flag_or_response_file(self):
        source = self.make_source("batch_00005.tar.gz", [
            (self.request_id(11), {"requestId": self.request_id(11), "requestMode": None, "requestTime": "2026-10-27 01:02:03"}, "biz_stream_response.jsonl", True),
            (self.request_id(12), {"requestId": self.request_id(12), "requestMode": None, "requestTime": "2026-10-27 01:02:03"}, "biz_response.json", False),
            (self.request_id(13), {"requestId": self.request_id(13), "requestMode": None, "requestTime": "2026-10-27 01:02:03"}, "biz_response.json")])
        merged = self.merge_sources([source])
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(self.root / "out"))
        with tarfile.open(result.json["artifactPath"], "r:gz") as archive:
            for index, mode in ((11, "STREAM"), (12, "SYNC"), (13, "SYNC")):
                metadata = json.load(archive.extractfile(self.request_id(index) + "/metadata.json"))
                self.assertEqual(metadata["requestMode"], mode)

    def test_missing_protocol_files_do_not_publish_output_or_temp_files(self):
        for missing in ("metadata", "biz_request", "response"):
            with self.subTest(missing=missing):
                case = self.root / missing
                case.mkdir()
                request_id = self.request_id(8)
                request = case / request_id
                request.mkdir()
                metadata = {"requestId": self.request_id(8), "requestMode": "STREAM"}
                if missing != "metadata":
                    (request / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
                if missing != "biz_request":
                    (request / "biz_request.json").write_text("{}", encoding="utf-8")
                if missing != "response":
                    (request / "biz_stream_response.jsonl").write_text("{}\n", encoding="utf-8")
                source = case / "batch_00008.tar.gz"
                with tarfile.open(source, "w:gz") as archive:
                    archive.add(request, arcname=request_id)
                merged = self.merge_sources([source], "merged-" + missing)
                target = case / "out"
                manifest = case / "manifest.json"
                self.run_script("--source", merged["workspacePath"], *self.repair_args(target, manifest=manifest), expected=1)
                self.assertFalse((target / "batch_0000001.tar.gz").exists())
                self.assertFalse((target / "batch_0000001.tar.gz.tmp").exists())
                self.assertFalse(manifest.exists())
                self.assertFalse(list(target.glob(".archive_repair_*")))

    def test_three_sources_merge_into_one_repaired_artifact_without_intermediate_tar(self):
        sources = []
        for index in range(1, 4):
            request_id = self.request_id(index)
            sources.append(self.make_source(f"source_{index:02d}.tar.gz", [
                (request_id, {"requestId": request_id, "requestMode": "SYNC",
                                          "requestTime": "2026-10-27 01:02:03"}, "biz_response.json")]))
        merged_dir = self.root / "merged"
        merged = self.merge_sources(sources)
        self.assertEqual(len(merged["sources"]), 3)
        self.assertEqual(merged["requestCount"], 3)
        self.assertTrue(Path(merged["workspacePath"]).is_dir())
        self.assertFalse((merged_dir / "merged-source.tar.gz").exists())
        repair_result = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "delivery", provider="june-2", manifest=self.root / "delivery" / "manifest.json"))
        repaired = repair_result.json
        self.assertEqual(repaired["requestCount"], 3)
        self.assertEqual(len(repaired["artifacts"][0]["file_mapping"]), 3)
        self.assertLessEqual(repaired["artifacts"][0]["sizeBytes"], 10 * 1024 * 1024)
        self.assertEqual(set(repaired["timings"]), {
            "copyWorkspaceSeconds", "repairRequestsSeconds", "gzipPackSeconds", "verifyArtifactSeconds", "sha256Seconds",
            "cleanupSeconds", "totalSeconds"})
        self.assertTrue(all(value >= 0 for value in repaired["timings"].values()))
        self.assertIn("REPAIR_TIMINGS ", repair_result.stdout)
        self.assertFalse(list(merged_dir.glob("*.tmp")))

    def test_merge_rejects_duplicate_original_directories_and_legacy_ids(self):
        first = self.make_source("first.tar.gz", [
            ("same", {"requestId": "req_20261027_0001", "requestMode": "SYNC"}, "biz_response.json")])
        second = self.make_source("second.tar.gz", [
            ("same", {"requestId": "req_20261027_0002", "requestMode": "SYNC"}, "biz_response.json")])
        result = self.run_script("--source", first, "--source", second, "--target", self.root / "merged-duplicates" / "requests",
                                 "--manifest", self.root / "merged-duplicates" / "merge-result.json", script=MERGE_SCRIPT,
                                 expected=1, marker="MERGE_RESULT ")
        self.assertIn("Duplicate original request directory", result.stderr + result.stdout)

    def test_gzip_level_is_bounded_and_changes_only_compression(self):
        source = self.make_source("compression.tar.gz", [
            (self.request_id(4), {"requestId": self.request_id(4), "requestMode": "SYNC", "requestTime": "2026-10-27 01:02:03",
                              "payload": "x" * 20000}, "biz_response.json")])
        merged = self.merge_sources([source], "merged-compression")
        default = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "compression-default")).json
        level_five = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "compression-level-five") + ("--gzip-level", "5")).json
        self.assertGreater(level_five["artifacts"][0]["sizeBytes"], default["artifacts"][0]["sizeBytes"])
        self.assertEqual(level_five["requestCount"], default["requestCount"])
        self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "compression-invalid") + ("--gzip-level", "0"), expected=2)

    def test_unsafe_archive_member_is_rejected(self):
        source = self.root / "unsafe.tar.gz"
        with tarfile.open(source, "w:gz") as archive:
            member = tarfile.TarInfo("../escape")
            member.size = 1
            import io
            archive.addfile(member, io.BytesIO(b"x"))
        self.run_script("--source", source, "--target", self.root / "unsafe-requests",
                        "--manifest", self.root / "unsafe.json", script=MERGE_SCRIPT, expected=1)


if __name__ == "__main__":
    unittest.main()
