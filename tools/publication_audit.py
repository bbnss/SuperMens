#!/usr/bin/env python3
"""Review publishable files and an optional APK without exposing matched values."""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "credential format": re.compile(
        rb"(?:AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[0-9A-Za-z]{30,}|"
        rb"github_pat_[0-9A-Za-z_]{30,}|sk-(?:proj-)?[0-9A-Za-z_-]{24,}|"
        rb"AKIA[0-9A-Z]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)"
    ),
    "credential assignment": re.compile(
        rb"(?:api[_-]?key|access[_-]?token|client[_-]?secret|password|passwd)"
        rb"\s*[=:]\s*[\"'][^\"'\r\n]{8,}", re.I
    ),
    "private machine path": re.compile(rb"(?:/(?:Users|home)/[^/\s]+/|kDrive[0-9]*[/\\])"),
    "email address": re.compile(rb"[a-zA-Z0-9_.+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"),
}
FORBIDDEN_SUFFIXES = {".apk", ".aab", ".jks", ".keystore", ".pem", ".key", ".p12", ".pfx",
                      ".db", ".sqlite", ".sqlite3", ".zip", ".litertlm", ".tflite", ".log"}
FORBIDDEN_PARTS = {"build", ".gradle", ".kotlin", ".idea", ".private-signing", "profiles"}
TOP_LEVEL_FILES = {".gitignore", "README.md", "LICENSE", "LICENSE_EXCEPTION.md", "THIRD_PARTY_NOTICES.md", "build.gradle.kts",
                   "settings.gradle.kts", "gradle.properties", "gradlew"}
TOP_LEVEL_DIRS = {"app", "gradle", "tools", "docs"}


def candidates():
    """Use Git's ignore implementation even before the first repository exists."""
    if (ROOT / ".git").exists():
        command = ["git", "-C", str(ROOT), "ls-files", "--cached", "--others", "--exclude-standard", "-z"]
        raw = subprocess.check_output(command)
    else:
        with tempfile.TemporaryDirectory(prefix="supermens-audit-") as temp:
            repository = Path(temp) / "review.git"
            subprocess.run(["git", "init", "--bare", "--quiet", str(repository)], check=True)
            raw = subprocess.check_output(["git", "--git-dir", str(repository), "--work-tree", str(ROOT),
                                           "ls-files", "--others", "--exclude-standard", "-z"])
    return sorted(set(name.decode("utf-8") for name in raw.split(b"\0") if name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--report-dir", type=Path,
                        default=Path(tempfile.gettempdir()) / "supermens-publication-review")
    args = parser.parse_args()
    report_dir = args.report_dir.resolve()
    if report_dir == ROOT or ROOT in report_dir.parents:
        parser.error("Write review reports outside the source tree to avoid auditing generated reports.")
    findings = []
    files = candidates()

    def scan(data, label, binary=False):
        for category, pattern in PATTERNS.items():
            if binary and category != "credential format":
                continue  # Library binaries contain diagnostic templates and test domains.
            for match in pattern.finditer(data):
                line = data[:match.start()].count(b"\n") + 1 if not binary else None
                findings.append({"file": label, "line": line, "category": category})

    for name in files:
        relative = Path(name)
        file = ROOT / relative
        if file.is_symlink() or (len(relative.parts) == 1 and name not in TOP_LEVEL_FILES) or (
            len(relative.parts) > 1 and relative.parts[0] not in TOP_LEVEL_DIRS
        ) or file.suffix.lower() in FORBIDDEN_SUFFIXES or set(relative.parts) & FORBIDDEN_PARTS or (
            file.name == "local.properties" or file.name.startswith(".env") or ".credentials." in file.name
        ):
            findings.append({"file": name, "category": "unexpected or private publication file"})
        data = file.read_bytes()
        binary = b"\0" in data or file.suffix.lower() in {".jpg", ".jpeg", ".mp4", ".jar", ".png"}
        scan(data, name, binary)
    apk = None
    if args.apk:
        apk_path = args.apk.resolve()
        with apk_path.open("rb") as stream:
            sha256 = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                sha256.update(chunk)
            digest = sha256.hexdigest()
        apk = {"name": apk_path.name, "bytes": apk_path.stat().st_size, "sha256": digest}
        with zipfile.ZipFile(apk_path) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                if any(suffix in entry.filename.lower() for suffix in (".keystore", ".jks", ".credentials", ".litertlm")):
                    findings.append({"file": "APK/" + entry.filename, "category": "private packaged file"})
                data = archive.read(entry)
                scan(data, "APK/" + entry.filename, binary=True)
                if str(ROOT).encode() in data or (str(Path.home()) + "/").encode() in data:
                    findings.append({"file": "APK/" + entry.filename, "category": "local developer path"})
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "PUBLICATION_FILES.txt").write_text("\n".join(files) + "\n")
    result = {"source_files": len(files), "source_bytes": sum((ROOT / name).stat().st_size for name in files),
              "apk": apk, "findings": findings,
              "limitations": "Pattern scan plus an explicit file allowlist; not a guarantee that all private data is detectable. APK scanning checks known credential formats and private file names, not every library string."}
    (report_dir / "AUDIT.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"Publication candidates: {len(files)} files")
    print(f"Findings: {len(findings)} (matched values are never printed)")
    for finding in findings:
        print(f"  {finding['category']}: {finding['file']}" +
              (f":{finding['line']}" if finding.get("line") else ""))
    print(f"Review reports: {report_dir}")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
