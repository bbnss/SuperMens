#!/usr/bin/env python3
"""Private report receiver. Run behind an HTTPS reverse proxy; no analytics or emails."""
import argparse
from contextlib import contextmanager, closing
import json
import os
from pathlib import Path
import sqlite3
import threading
import time
import uuid
from wsgiref.simple_server import make_server, WSGIRequestHandler

REASONS = {"child_safety", "harmful", "deceptive", "other"}
DIAGNOSTICS = {
    "app_version", "version_code", "manufacturer", "model", "soc", "android_sdk",
    "ram_total_bytes", "ram_available_bytes", "model_label", "model_ready", "backend",
    "vision", "load_ms", "generation_ms", "first_token_ms", "output_chars",
    "thermal_status", "foreground_failed", "device_certified",
}


def validate_report(value):
    if not isinstance(value, dict) or set(value) - {"version", "reason", "response", "app_version", "diagnostics"}:
        raise ValueError("Unexpected report fields")
    if type(value.get("version")) is not int or value["version"] != 1:
        raise ValueError("Unsupported format")
    if value.get("reason") not in REASONS:
        raise ValueError("Invalid reason")
    response = value.get("response")
    if not isinstance(response, str) or not response.strip() or len(response.encode("utf-8")) > 16000:
        raise ValueError("Invalid response")
    version = value.get("app_version")
    if not isinstance(version, str) or not 1 <= len(version) <= 80:
        raise ValueError("Invalid app version")
    diagnostics = value.get("diagnostics", {})
    if not isinstance(diagnostics, dict) or set(diagnostics) - DIAGNOSTICS:
        raise ValueError("Unexpected diagnostic fields")
    for item in diagnostics.values():
        if type(item) not in (str, int, bool) or (isinstance(item, str) and len(item) > 200):
            raise ValueError("Invalid diagnostic value")
    return value


class ReportService:
    def __init__(self, database):
        self.database = Path(database)
        self.database.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Refuse symlinks and make new files private before SQLite can write content.
        descriptor = os.open(self.database, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        os.fchmod(descriptor, 0o600)
        os.close(descriptor)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, created INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, disposition TEXT NOT NULL DEFAULT 'open')")
            db.execute("DELETE FROM reports WHERE created < ?", (int(time.time()) - 30 * 86400,))
        self.requests = {}
        self.lock = threading.Lock()

    @contextmanager
    def connect(self):
        with closing(sqlite3.connect(self.database, timeout=10)) as db:
            with db:
                yield db

    def __call__(self, environ, start_response):
        def reply(status, value):
            data = json.dumps(value).encode()
            start_response(status, [("Content-Type", "application/json"), ("Content-Length", str(len(data))), ("Cache-Control", "no-store")])
            return [data]
        if environ.get("PATH_INFO") != "/reports" or environ.get("REQUEST_METHOD") != "POST":
            return reply("404 Not Found", {"error": "not_found"})
        now = int(time.time())
        with self.lock:
            # IPs are used only in volatile memory, never persisted or taken from client headers.
            self.requests = {key: entry for key, entry in self.requests.items() if now - entry[0] < 3600}
            peer = environ.get("REMOTE_ADDR", "unknown")
            started, count = self.requests.get(peer, (now, 0))
            if count >= 60 or (peer not in self.requests and len(self.requests) >= 4096):
                return reply("429 Too Many Requests", {"error": "rate_limited"})
            self.requests[peer] = (started, count + 1)
        try:
            length = int(environ.get("CONTENT_LENGTH", "0"))
            if not 0 < length <= 32768 or not environ.get("CONTENT_TYPE", "").startswith("application/json"):
                raise ValueError("Invalid body")
            raw = environ["wsgi.input"].read(length)
            if len(raw) != length:
                raise ValueError("Incomplete body")
            value = validate_report(json.loads(raw))
        except (ValueError, TypeError, UnicodeError, RecursionError):
            return reply("400 Bad Request", {"error": "invalid_report"})
        identifier = str(uuid.uuid4())
        try:
            with self.connect() as db:
                db.execute("DELETE FROM reports WHERE created < ?", (now - 30 * 86400,))
                db.execute("INSERT INTO reports(id,created,reason,payload) VALUES(?,?,?,?)", (identifier, now, value["reason"], json.dumps(value)))
        except sqlite3.Error:
            return reply("503 Service Unavailable", {"error": "unavailable"})
        return reply("201 Created", {"id": identifier})


class PrivateHandler(WSGIRequestHandler):
    def log_message(self, *args):
        pass  # No request URLs, addresses or report content in application logs.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    os.umask(0o077)
    app = ReportService(args.database)
    with make_server("127.0.0.1", args.port, app, handler_class=PrivateHandler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
