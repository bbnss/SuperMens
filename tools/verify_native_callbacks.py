#!/usr/bin/env python3
"""Check LiteRT-LM's JNI callback signatures in the actual, optimized APK.

Run after assembling a release, before signing. This also works on signed APKs.
Requires Android SDK build-tools' dexdump; no device or model is needed.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from xml.etree import ElementTree
import zipfile

PREFIX = "com.google.ai.edge.litertlm.LiteRtLmJni$"
CONTRACTS = {
    PREFIX + "JniMessageCallback": {
        ("onMessage", ("java.lang.String",)),
        ("onDone", ()),
        ("onError", ("int", "java.lang.String")),
    },
    PREFIX + "JniInferenceCallback": {
        ("onNext", ("java.lang.String",)),
        ("onDone", ()),
        ("onError", ("int", "java.lang.String")),
    },
}


def verify(apk, dexdump):
    classes = {}
    with tempfile.TemporaryDirectory(prefix="supermens-jni-") as directory:
        with zipfile.ZipFile(apk) as archive:
            dex_names = sorted(name for name in archive.namelist()
                               if re.fullmatch(r"classes(?:\d+)?\.dex", name))
            if not dex_names:
                raise ValueError("APK contains no DEX files")
            for name in dex_names:
                dex = Path(directory) / name
                dex.write_bytes(archive.read(name))
                result = subprocess.run([str(dexdump), "-l", "xml", str(dex)],
                                        check=True, capture_output=True)
                root = ElementTree.fromstring(result.stdout)
                for package in root.findall("package"):
                    for entry in package.findall("class"):
                        classes[package.get("name") + "." + entry.get("name")] = entry

    failures = []
    checked = []
    for contract, expected in CONTRACTS.items():
        if contract not in classes:
            if contract.endswith("JniMessageCallback"):
                failures.append("Missing streaming callback interface: " + contract)
            continue  # The app need not retain the unused legacy Session API.
        implementations = [(name, entry) for name, entry in classes.items()
                           if any(implemented.get("name") == contract
                                  for implemented in entry.findall("implements"))]
        if not implementations:
            failures.append("No implementation for " + contract)
        for name, entry in [(contract, classes[contract]), *implementations]:
            methods = {(method.get("name"), tuple(parameter.get("type")
                        for parameter in method.findall("parameter")))
                       for method in entry.findall("method")
                       if method.get("return") == "void" and method.get("static") == "false"
                       and method.get("visibility") == "public"}
            for method, parameters in sorted(expected - methods):
                failures.append(f"{name}: missing public void {method}({', '.join(parameters)})")
            checked.append(name)
    return checked, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexdump", type=Path, required=True)
    args = parser.parse_args()
    try:
        checked, failures = verify(args.apk, args.dexdump)
    except (OSError, ValueError, subprocess.CalledProcessError,
            zipfile.BadZipFile, ElementTree.ParseError) as error:
        parser.exit(1, f"JNI callback check could not complete: {type(error).__name__}\n")
    for failure in failures:
        print("FAIL:", failure)
    if failures:
        return 1
    print(f"PASS: JNI callback contracts preserved in {args.apk.name}")
    for name in checked:
        print("  " + name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
