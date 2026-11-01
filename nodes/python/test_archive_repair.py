import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("archive_repair.py")
SAMPLE = Path(__file__).resolve().parents[3] / "old" / "sample" / "batch_00002.tar.gz"


class ArchiveRepairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, *args, expected=0):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def test_inspect_real_sample(self):
        result = self.run_script("--source", SAMPLE, "--inspect")
        self.assertEqual(json.loads(result.stdout)["requestCount"], 4)

    def test_repairs_real_sample_and_preserves_lineage(self):
        manifest = self.root / "manifest.json"
        result = self.run_script("--source", SAMPLE, "--target", self.root,
                                 "--archive-date", "2026-10-30", "--provider-code", "june",
                                 "--start-number", "1", "--manifest", manifest)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["requestCount"], 4)
        self.assertEqual([m["originalRequestId"] for m in payload["mappings"]],
                         ["req_20261027_0001", "req_20261027_0002",
                          "req_20261027_0003", "req_20261027_0004"])
        artifact = Path(payload["artifacts"][0]["path"])
        with tarfile.open(artifact, "r:gz") as archive:
            for index, mapping in enumerate(payload["mappings"], start=1):
                expected = "req_20261030_{:09d}".format(index)
                self.assertEqual(mapping["newRequestId"], expected)
                metadata = json.load(archive.extractfile(expected + "/metadata.json"))
                self.assertEqual(metadata["requestId"], expected)
                self.assertEqual(metadata["providerCode"], "june")
                self.assertTrue(metadata["requestTime"].startswith("2026-10-30 "))
                self.assertIsNotNone(archive.extractfile(
                    expected + "/biz_stream_response.jsonl"))

    def test_missing_metadata_does_not_publish_manifest(self):
        source = self.root / "batch_00003.tar.gz"
        with tarfile.open(source, "w:gz") as archive:
            request = self.root / "input"
            request.mkdir()
            (request / "biz_request.json").write_text("{}", encoding="utf-8")
            archive.add(request, arcname="req_old")
        manifest = self.root / "manifest.json"
        self.run_script("--source", source, "--target", self.root / "out",
                        "--archive-date", "2026-10-30", "--provider-code", "june",
                        "--start-number", "1", "--manifest", manifest, expected=1)
        self.assertFalse(manifest.exists())

    def test_missing_request_time_uses_midnight(self):
        source_dir = self.root / "req_old"
        source_dir.mkdir()
        (source_dir / "metadata.json").write_text(json.dumps({
            "requestId": "old", "requestMode": "SYNC"
        }), encoding="utf-8")
        (source_dir / "biz_request.json").write_text("{}", encoding="utf-8")
        (source_dir / "biz_stream_response.jsonl").write_text("{}\n", encoding="utf-8")
        source = self.root / "batch_00004.tar.gz"
        with tarfile.open(source, "w:gz") as archive:
            archive.add(source_dir, arcname="req_old")
        manifest = self.root / "manifest.json"
        result = self.run_script("--source", source, "--target", self.root / "out",
                                 "--archive-date", "2026-10-30", "--provider-code", "june-1",
                                 "--start-number", "9", "--manifest", manifest)
        new_id = json.loads(result.stdout)["mappings"][0]["newRequestId"]
        with tarfile.open(json.loads(result.stdout)["artifacts"][0]["path"], "r:gz") as archive:
            metadata = json.load(archive.extractfile(new_id + "/metadata.json"))
            self.assertEqual(metadata["requestTime"], "2026-10-30 00:00:00")
            self.assertIsNotNone(archive.extractfile(new_id + "/biz_response.json"))

    def test_golden_rules_and_deterministic_repair(self):
        source_dir = self.root / "input"
        source_dir.mkdir()
        for name, mode, response, original_time in (
                ("req_z", "STREAM", "biz_response.json", "2026-10-27 12:34:56"),
                ("req_a", "SYNC", "biz_stream_response.jsonl", "2026-10-27 01:02:03")):
            request = source_dir / name
            request.mkdir()
            (request / "metadata.json").write_text(json.dumps({
                "requestId": "old-" + name, "requestMode": mode, "requestTime": original_time
            }), encoding="utf-8")
            (request / "biz_request.json").write_text("{}", encoding="utf-8")
            (request / response).write_text("{}\n", encoding="utf-8")
        source = self.root / "batch_00007.tar.gz"
        with tarfile.open(source, "w:gz") as archive:
            archive.add(source_dir / "req_z", arcname="req_z")
            archive.add(source_dir / "req_a", arcname="req_a")

        outputs = []
        for run in ("first", "second"):
            target = self.root / run
            manifest = target / "manifest.json"
            result = self.run_script("--source", source, "--target", target,
                                     "--archive-date", "2026-11-01", "--provider-code", "june",
                                     "--start-number", "20", "--manifest", manifest)
            payload = json.loads(result.stdout)
            artifact = Path(payload["artifacts"][0]["path"])
            self.assertLessEqual(artifact.stat().st_size, 100 * 1024 * 1024)
            with tarfile.open(artifact, "r:gz") as archive:
                for index, (mode, original_time) in enumerate(
                        (("SYNC", "01:02:03"), ("STREAM", "12:34:56")), start=20):
                    new_id = "req_20261101_{:09d}".format(index)
                    metadata = json.load(archive.extractfile(new_id + "/metadata.json"))
                    self.assertEqual(metadata["requestId"], new_id)
                    self.assertEqual(metadata["providerCode"], "june")
                    self.assertEqual(metadata["requestTime"], "2026-11-01 " + original_time)
                    names = [Path(name).name for name in archive.getnames()
                             if name.startswith(new_id + "/") and "response" in name]
                    self.assertEqual(names, ["biz_response.json" if mode == "SYNC"
                                             else "biz_stream_response.jsonl"])
            outputs.append((payload["mappings"], payload["artifacts"][0]["sha256"]))
        self.assertEqual(outputs[0], outputs[1])

    def test_missing_protocol_files_do_not_publish_output(self):
        for missing in ("biz_request.json", "response"):
            with self.subTest(missing=missing):
                case = self.root / missing
                request = case / "req_old"
                request.mkdir(parents=True)
                (request / "metadata.json").write_text(json.dumps({
                    "requestId": "old", "requestMode": "STREAM"
                }), encoding="utf-8")
                if missing != "biz_request.json":
                    (request / "biz_request.json").write_text("{}", encoding="utf-8")
                if missing != "response":
                    (request / "biz_stream_response.jsonl").write_text("{}\n", encoding="utf-8")
                source = case / "batch_00008.tar.gz"
                with tarfile.open(source, "w:gz") as archive:
                    archive.add(request, arcname="req_old")
                target = case / "out"
                manifest = case / "manifest.json"
                self.run_script("--source", source, "--target", target,
                                "--archive-date", "2026-11-01", "--provider-code", "june",
                                "--start-number", "1", "--manifest", manifest, expected=1)
                self.assertFalse((target / "batch_00008.tar.gz").exists())
                self.assertFalse((target / "batch_00008.tar.gz.tmp").exists())
                self.assertFalse(manifest.exists())


if __name__ == "__main__":
    unittest.main()
