"""Regression checks for the publication audit's working-tree boundaries."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import publication_audit as audit


class PublicationAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="supermens-audit-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source"
        self.root.mkdir()
        self.report = Path(self.temp.name) / "report"
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        self.patch = patch.object(audit, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def write(self, name, content):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        return file

    def run_audit(self, *args):
        with patch.object(sys, "argv", ["publication_audit.py", "--report-dir", str(self.report), *args]):
            with contextlib.redirect_stdout(io.StringIO()):
                status = audit.main()
        return status, json.loads((self.report / "AUDIT.json").read_text())

    def test_deleted_tracked_service_does_not_prevent_a_complete_report(self):
        deleted = self.write("tools/old_service.py", "# Removed service\n")
        subprocess.run(["git", "-C", str(self.root), "add", "tools/old_service.py"], check=True)
        deleted.unlink()
        self.write("README.md", "Current app\n")
        status, report = self.run_audit()
        self.assertEqual(0, status)
        self.assertEqual(1, report["source_files"])
        self.assertEqual(len(b"Current app\n"), report["source_bytes"])
        self.assertEqual([], report["findings"])

    def test_untracked_sensitive_file_is_still_detected_after_a_deletion(self):
        deleted = self.write("tools/old.py", "# Removed\n")
        subprocess.run(["git", "-C", str(self.root), "add", "tools/old.py"], check=True)
        deleted.unlink()
        self.write("tools/private.key", "synthetic private fixture\n")
        status, report = self.run_audit()
        self.assertEqual(1, status)
        self.assertIn({"file": "tools/private.key", "category": "unexpected or private publication file"}, report["findings"])

    def test_symlink_is_rejected_without_scanning_its_target(self):
        target = Path(self.temp.name) / "outside.txt"
        target.write_text("outside@example.com")
        (self.root / "README.md").symlink_to(target)
        status, report = self.run_audit()
        self.assertEqual(1, status)
        self.assertEqual(0, report["source_bytes"])
        self.assertEqual([{"file": "README.md", "category": "unexpected or private publication file"}], report["findings"])

    def test_dangling_symlink_is_rejected_without_crashing(self):
        (self.root / "README.md").symlink_to(self.root / "missing")
        status, report = self.run_audit()
        self.assertEqual(1, status)
        self.assertEqual(1, len(report["findings"]))

    def test_email_exception_is_exact_and_other_source_addresses_still_fail(self):
        self.write("README.md", "\n".join(("public@example.com", "private@example.com", "")))
        status, report = self.run_audit("--allow-source-email", "public@example.com")
        self.assertEqual(1, status)
        self.assertEqual([{"file": "README.md", "line": 2, "category": "email address"}], report["findings"])
        self.assertEqual(["public@example.com"], report["allowed_source_emails"])

    def test_public_reporting_config_is_scanned_for_secrets_even_with_email_exception(self):
        credential_name = "pass" + "word"
        self.write("reporting.properties", "privacyEmail=public@example.com\n" +
                   f'{credential_name}="synthetic-private-value"\n')
        status, report = self.run_audit("--allow-source-email", "public@example.com")
        self.assertEqual(1, status)
        self.assertEqual([{"file": "reporting.properties", "line": 2, "category": "credential assignment"}], report["findings"])

    def test_public_reporting_config_with_explicitly_reviewed_email_passes(self):
        self.write("reporting.properties", "privacyEmail=public@example.com\n")
        status, report = self.run_audit("--allow-source-email", "public@example.com")
        self.assertEqual(0, status)
        self.assertEqual([], report["findings"])


if __name__ == "__main__":
    unittest.main()
