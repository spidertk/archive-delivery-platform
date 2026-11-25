import io
import json
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from scripts import pipeline_registry


class PipelineRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        target_dir = self.root / "deploy" / "pipelines"
        target_dir.mkdir(parents=True)
        for name in ("archive-delivery-jun.md", "archive-batch-jun.md"):
            source = Path(pipeline_registry.ROOT, "deploy", "pipelines", name)
            (target_dir / name).write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
        self.source_text = (target_dir / "archive-delivery-jun.md").read_text(encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_current_markdown_pipelines_have_complete_contracts(self):
        rows = pipeline_registry.cases(self.root)
        self.assertEqual([row["caseId"] for row in rows], ["archive-batch-jun", "archive-delivery-jun"])
        delivery = pipeline_registry.select("archive-delivery-jun", self.root)
        self.assertEqual(delivery["golden"]["expected"]["taskCount"], 100)
        self.assertNotIn("maxPackageBytes", delivery["definition"]["config"]["assignment"])
        batch = pipeline_registry.select("archive-batch-jun", self.root)
        self.assertEqual(batch["definition"]["config"]["assignment"]["maxPackageBytes"], 104857600)
        self.assertEqual(batch["definition"]["config"]["assignment"]["entityWorkers"][0]["workers"][0]["maxPackagesPerRound"], 6000)
        extension = next(item for item in batch["definition"]["config"]["assignment"]["entityWorkers"]
                         if item["entityCode"] == "jun")
        self.assertEqual(extension["vendorCode"], "alibaba")
        self.assertEqual(extension["workers"], [{"workerGroup": "group2", "maxPackagesPerRound": 100}])

    def test_missing_external_evidence_is_rejected(self):
        path = self.root / "deploy" / "pipelines" / "archive-delivery-jun.md"
        path.write_text(self.source_text.replace('"NO_PARTIAL"', '"OPTIONAL_CHECK"'), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "every golden case requires"):
            pipeline_registry.cases(self.root)

    def test_duplicate_case_id_is_rejected(self):
        duplicate = self.root / "deploy" / "pipelines" / "duplicate.md"
        duplicate.write_text(self.source_text, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "caseId values must be globally unique"):
            pipeline_registry.cases(self.root)

    def test_case_environment_contains_target_and_unique_batch(self):
        case = pipeline_registry.select("archive-batch-jun", self.root)
        with mock.patch("sys.stdout", new=io.StringIO()) as output:
            pipeline_registry.emit_shell(case)
        text = output.getvalue()
        self.assertIn("PROD_ENTITY_COUNT=1", text)
        self.assertIn("PROD_TARGET_COUNT=100", text)
        self.assertIn("PROD_OSS_URI=oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/", text)
        self.assertIn("alibaba", text)
        self.assertIn("REGISTRY_DELIVERY_BATCH=PROD-ARCHIVE-BATCH-JUN-", text)


if __name__ == "__main__":
    unittest.main()
