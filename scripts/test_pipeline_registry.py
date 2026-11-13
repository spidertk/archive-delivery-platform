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
        source = Path(pipeline_registry.ROOT, "deploy", "pipelines", "archive-delivery-jun.md")
        target_dir = self.root / "deploy" / "pipelines"
        target_dir.mkdir(parents=True)
        self.source_text = source.read_text(encoding="utf-8")
        (target_dir / "archive-delivery-jun.md").write_text(self.source_text, encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_current_markdown_pipeline_has_complete_local_and_golden_contract(self):
        rows = pipeline_registry.cases(self.root)
        self.assertEqual(len(rows), 1)
        case = rows[0]
        self.assertEqual(case["caseId"], "archive-delivery-jun")
        self.assertEqual(case["golden"]["targets"][0]["hostRoot"], "/srv/maas-sftp/maasData/archive/june")
        self.assertEqual(case["golden"]["expected"]["taskCount"], 1)

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
        case = pipeline_registry.select("archive-delivery-jun", self.root)
        with mock.patch("sys.stdout", new=io.StringIO()) as output:
            pipeline_registry.emit_shell(case)
        text = output.getvalue()
        self.assertIn("PROD_ENTITY_COUNT=1", text)
        self.assertIn("PROD_TARGET_COUNT=1", text)
        self.assertIn("alibaba", text)
        self.assertIn("REGISTRY_DELIVERY_BATCH=PROD-ARCHIVE-DELIVERY-JUN-", text)


if __name__ == "__main__":
    unittest.main()
