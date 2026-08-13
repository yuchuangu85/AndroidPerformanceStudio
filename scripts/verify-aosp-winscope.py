#!/usr/bin/env python3
"""Verifies the pinned upstream Winscope browser distribution."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "third_party" / "aosp-winscope"
DIST = PACKAGE / "dist"
MANIFEST = PACKAGE / "manifest.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text())
    expected = manifest["assets"]
    actual = {
        path.relative_to(DIST).as_posix(): path
        for path in DIST.rglob("*")
        if path.is_file()
    }
    if set(actual) != set(expected):
        print(f"Asset closure differs: missing={sorted(set(expected) - set(actual))}, extra={sorted(set(actual) - set(expected))}")
        return 1
    for name, path in actual.items():
        entry = expected[name]
        if path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
            print(f"Checksum mismatch: {name}")
            return 1

    patch = PACKAGE / "patches" / "0001-add-offline-session-viewer.patch"
    if sha256(patch) != manifest["patchSha256"]:
        print("Upstream patch checksum differs from manifest")
        return 1

    index = (DIST / "index.html").read_text()
    referenced_js = set(re.findall(r'src="\./(js/[^"]+\.js)"', index))
    packaged_js = {name for name in actual if name.startswith("js/")}
    if referenced_js != packaged_js:
        print("Packaged JavaScript is not the exact index.html closure")
        return 1

    prohibited = (b"googletagmanager", b"fonts.googleapis", b"fonts.gstatic")
    for name, path in actual.items():
        if path.suffix in {".html", ".js", ".css"}:
            content = path.read_bytes().lower()
            if any(value in content for value in prohibited):
                print(f"Remote runtime dependency remains in {name}")
                return 1
    if "winscope_proxy.py" in actual:
        print("winscope_proxy.py must not be packaged")
        return 1
    print(f"Verified {len(actual)} upstream Winscope assets ({sum(path.stat().st_size for path in actual.values())} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
