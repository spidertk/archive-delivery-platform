import json
import subprocess
import sys
import tarfile
import tempfile
import unittest
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

    def repair_args(self, target, date="2026-10-30", provider="june", start="1", manifest=None, output="batch_00001.tar.gz"):
        manifest = Path(manifest) if manifest is not None else Path(target) / "manifest.json"
        return ("--target", target, "--delivery-date", date, "--entity-code", provider,
                "--start-number", start, "--max-bytes", 10 * 1024 * 1024,
                "--output-name", output, "--original-name", "batch_00001.tar.gz", "--manifest", manifest)

    def make_source(self, name, requests):
        source_dir = self.root / (name + "-input")
        source_dir.mkdir()
        source = self.root / name
        for directory, metadata, response in requests:
            request = source_dir / directory
            request.mkdir()
            (request / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            (request / "biz_request.json").write_text("{}", encoding="utf-8")
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

    @staticmethod
    def request_id(index, date="20261027"):
        return date + "123456" + f"{index:025d}"

    def test_inspect_and_production_repair_preserve_source_request_ids(self):
        first = self.request_id(1)
        second = self.request_id(2)
        source = self.make_source("batch_00007.tar.gz", [
            ("old_z", {"requestId": first, "requestMode": "STREAM", "requestTime": "2026-10-27 12:34:56.12"}, "biz_response.json"),
            ("old_a", {"requestId": second, "requestMode": "SYNC", "requestTime": "2026-10-27T01:02:03.123456789Z"}, "biz_stream_response.jsonl")])
        merged = self.merge_sources([source])
        inspected = self.run_script("--source", merged["workspacePath"], "--inspect", marker="INSPECT_RESULT ")
        self.assertEqual(inspected.json["requestCount"], 2)
        result = self.run_script("--source", merged["workspacePath"], *self.repair_args(self.root / "out", date="2026-11-01"))
        payload = result.json
        self.assertEqual([mapping["newRequestId"] for mapping in payload["mappings"]], [second, first])
        self.assertEqual([mapping["originalRequestId"] for mapping in payload["mappings"]], [second, first])
        artifact = payload["artifacts"][0]
        self.assertEqual(set(artifact), ARTIFACT_FIELDS)
        with tarfile.open(payload["artifactPath"], "r:gz") as archive:
            second_metadata = json.load(archive.extractfile(second + "/metadata.json"))
            first_metadata = json.load(archive.extractfile(first + "/metadata.json"))
            self.assertEqual(second_metadata["requestTime"], "2026-11-01T01:02:03.123456789Z")
            self.assertEqual(first_metadata["requestTime"], "2026-11-01T12:34:56.120000000Z")
            self.assertEqual(first_metadata["providerCode"], "june")
            self.assertIsNotNone(archive.extractfile(second + "/biz_response.json"))
            self.assertIsNotNone(archive.extractfile(first + "/biz_stream_response.jsonl"))

    def test_missing_request_id_and_time_use_deterministic_fallback(self):
        source = self.make_source("batch_00004.tar.gz", [
            ("req_old", {"requestId": "", "requestMode": "SYNC"}, "biz_stream_response.jsonl")])
        merged = self.merge_sources([source])
        outputs = []
        for run in ("first", "second"):
            result = self.run_script("--source", merged["workspacePath"], *self.repair_args(
                self.root / run, provider="june-1", start="9", manifest=self.root / run / "manifest.json"))
            payload = result.json
            new_id = payload["mappings"][0]["newRequestId"]
            self.assertEqual(new_id, "20261030000000" + "0" * 24 + "9")
            with tarfile.open(payload["artifactPath"], "r:gz") as archive:
                metadata = json.load(archive.extractfile(new_id + "/metadata.json"))
                self.assertEqual(metadata["requestTime"], "2026-10-30T00:00:00.000000000Z")
                self.assertEqual(metadata["providerCode"], "june-1")
                self.assertIsNotNone(archive.extractfile(new_id + "/biz_response.json"))
            outputs.append(payload["artifacts"][0]["sha256"])
        self.assertEqual(outputs[0], outputs[1])

    def test_missing_protocol_files_do_not_publish_output_or_temp_files(self):
        for missing in ("metadata", "biz_request", "response"):
            with self.subTest(missing=missing):
                case = self.root / missing
                case.mkdir()
                request = case / "req_old"
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
                    archive.add(request, arcname="req_old")
                merged = self.merge_sources([source], "merged-" + missing)
                target = case / "out"
                manifest = case / "manifest.json"
                self.run_script("--source", merged["workspacePath"], *self.repair_args(target, manifest=manifest), expected=1)
                self.assertFalse((target / "batch_00001.tar.gz").exists())
                self.assertFalse((target / "batch_00001.tar.gz.tmp").exists())
                self.assertFalse(manifest.exists())
                self.assertFalse(list(target.glob(".archive_repair_*")))

    def test_three_sources_merge_into_one_repaired_artifact_without_intermediate_tar(self):
        sources = []
        for index in range(1, 4):
            request_id = self.request_id(index)
            sources.append(self.make_source(f"source_{index:02d}.tar.gz", [
                (f"member_{index:02d}", {"requestId": request_id, "requestMode": "SYNC",
                                          "requestTime": "2026-10-27 01:02:03"}, "biz_response.json")]))
        merged_dir = self.root / "merged"
        merged = self.merge_sources(sources)
        self.assertEqual(len(merged["sources"]), 3)
        self.assertEqual(merged["requestCount"], 3)
        self.assertTrue(Path(merged["workspacePath"]).is_dir())
        self.assertFalse((merged_dir / "merged-source.tar.gz").exists())
        repaired = self.run_script("--source", merged["workspacePath"], *self.repair_args(
            self.root / "delivery", provider="june-2", manifest=self.root / "delivery" / "manifest.json")).json
        self.assertEqual(repaired["requestCount"], 3)
        self.assertEqual(len(repaired["artifacts"][0]["file_mapping"]), 3)
        self.assertLessEqual(repaired["artifacts"][0]["sizeBytes"], 10 * 1024 * 1024)
        self.assertFalse(list(merged_dir.glob("*.tmp")))

    def test_merge_namespaces_duplicate_directories_and_legacy_ids_are_renumbered(self):
        first = self.make_source("first.tar.gz", [
            ("same", {"requestId": "req_20261027_0001", "requestMode": "SYNC"}, "biz_response.json")])
        second = self.make_source("second.tar.gz", [
            ("same", {"requestId": "req_20261027_0002", "requestMode": "SYNC"}, "biz_response.json")])
        merged = self.merge_sources([first, second], "merged-duplicates")
        self.assertEqual(merged["requestCount"], 2)
        self.assertNotEqual(merged["members"][0]["memberKey"], merged["members"][1]["memberKey"])
        repaired = self.run_script("--source", merged["workspacePath"], *self.repair_args(self.root / "duplicate-out")).json
        self.assertEqual([mapping["originalPath"] for mapping in repaired["mappings"]], ["same", "same"])
        self.assertEqual([mapping["originalRequestId"] for mapping in repaired["mappings"]],
                         ["req_20261027_0001", "req_20261027_0002"])
        self.assertNotEqual([mapping["memberKey"] for mapping in repaired["mappings"]][0],
                            [mapping["memberKey"] for mapping in repaired["mappings"]][1])
        for mapping in repaired["mappings"]:
            self.assertRegex(mapping["newRequestId"], r"^\d{14}[A-Za-z0-9]{25}$")
            self.assertNotEqual(mapping["newRequestId"], mapping["originalRequestId"])

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
