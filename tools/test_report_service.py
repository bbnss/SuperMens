import io
import json
from pathlib import Path
import tempfile
import unittest
from report_service import ReportService


class ReportServiceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.app = ReportService(Path(self.directory.name) / "reports.sqlite3")

    def tearDown(self):
        self.directory.cleanup()

    def request(self, value, **extra):
        raw = json.dumps(value).encode()
        status = []
        environ = {"REQUEST_METHOD": "POST", "PATH_INFO": "/reports", "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw), "REMOTE_ADDR": "127.0.0.1"}
        environ.update(extra)
        result = self.app(environ, lambda code, headers: status.append(code))
        return status[0], json.loads(b"".join(result))

    def valid(self):
        return {"version": 1, "reason": "harmful", "response": "Selected response", "app_version": "0.3.0-beta1"}

    def test_received_only_after_persistent_insert_and_private_file(self):
        status, result = self.request(self.valid())
        self.assertEqual("201 Created", status)
        with self.app.connect() as db:
            value = db.execute("SELECT payload FROM reports WHERE id=?", (result["id"],)).fetchone()[0]
        self.assertEqual(self.valid(), json.loads(value))
        self.assertEqual(0o600, self.app.database.stat().st_mode & 0o777)

    def test_rejects_archive_prompt_serial_and_unknown_diagnostics(self):
        for field in ("archive", "prompt", "serial", "url"):
            value = self.valid() | {field: "private"}
            self.assertTrue(self.request(value)[0].startswith("400"))
        value = self.valid() | {"diagnostics": {"serial": "private"}}
        self.assertTrue(self.request(value)[0].startswith("400"))
        with self.app.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM reports").fetchone()[0])

    def test_utf8_and_body_limits(self):
        self.assertTrue(self.request(self.valid() | {"response": "🌍" * 4001})[0].startswith("400"))
        self.assertTrue(self.request(self.valid(), CONTENT_LENGTH="40000")[0].startswith("400"))
        self.assertTrue(self.request(self.valid(), CONTENT_LENGTH="-1")[0].startswith("400"))

    def test_rate_limit_and_no_client_controlled_peer(self):
        for _ in range(60):
            self.assertEqual("201 Created", self.request(self.valid())[0])
        self.assertTrue(self.request(self.valid(), HTTP_X_FORWARDED_FOR="another-ip")[0].startswith("429"))

    def test_retention_and_optional_diagnostics(self):
        self.request(self.valid() | {"diagnostics": {"backend": "GPU", "device_certified": False}})
        with self.app.connect() as db:
            db.execute("UPDATE reports SET created=0")
        self.request(self.valid())
        with self.app.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM reports").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
