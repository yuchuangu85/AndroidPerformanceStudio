#!/usr/bin/env python3
"""Builds the pinned upstream Winscope viewer and refreshes its asset closure."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "third_party" / "aosp-winscope"
MANIFEST = PACKAGE / "manifest.json"


def run(*command: str, cwd: Path | None = None, stdout=None) -> None:
    subprocess.run(command, cwd=cwd, check=True, stdout=stdout)


def sha256(path: Path) -> str:
    digest = hashlib.sha256(path.read_bytes())
    return digest.hexdigest()


def tree_sha256(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(path for path in directory.rglob("*") if path.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode())
        digest.update(b"\0")
        digest.update(sha256(path).encode())
        digest.update(b"\n")
    return digest.hexdigest()


def main() -> int:
    source = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("AOSP_WINSCOPE_ROOT", ROOT.parent / "AOSP-WinScope")).resolve()
    manifest = json.loads(MANIFEST.read_text())
    commit = manifest["sourceCommit"]
    run("git", "-C", str(source), "cat-file", "-e", f"{commit}^{{commit}}")
    node_version = subprocess.check_output(["node", "--version"], text=True).strip()
    if node_version != manifest["nodeVersion"]:
        raise SystemExit(f"Expected Node {manifest['nodeVersion']}, found {node_version}")
    generated_protos = source / "winscope" / "deps_build" / "protos"
    if tree_sha256(generated_protos) != manifest["generatedProtosSha256"]:
        raise SystemExit("Generated protobuf inputs do not match manifest.json")

    with tempfile.TemporaryDirectory(prefix="aps-aosp-winscope-") as temp:
        checkout = Path(temp) / "source"
        run("git", "-C", str(source), "worktree", "add", "--detach", str(checkout), commit)
        try:
            run("git", "apply", str(PACKAGE / "patches" / "0001-add-offline-session-viewer.patch"), cwd=checkout)
            winscope = checkout / "winscope"
            fonts = winscope / "src" / "fonts"
            fonts.mkdir(parents=True, exist_ok=True)
            vendor = PACKAGE / "vendor" / "material-design-icons"
            shutil.copy2(vendor / "MaterialIcons-Regular.ttf", fonts)
            shutil.copy2(vendor / "MaterialSymbolsOutlined.ttf", fonts)
            shutil.copytree(generated_protos, winscope / "deps_build" / "protos", dirs_exist_ok=True)
            tools = winscope / "deps_build" / "trace_processor" / "to_be_served"
            tools.mkdir(parents=True, exist_ok=True)
            for name in ("engine_bundle.js", "trace_processor.wasm"):
                with (tools / name).open("wb") as output:
                    run("git", "-C", str(source), "show", f"{commit}:winscope/dist/prod/{name}", stdout=output)
            run("npm", "ci", cwd=winscope)
            run("npm", "run", "build:app", cwd=winscope)

            built = winscope / "dist" / "prod"
            staged = Path(temp) / "dist"
            (staged / "js").mkdir(parents=True)
            index = (built / "index.html").read_text()
            for relative in re.findall(r'src="\./(js/[^"]+\.js)"', index):
                shutil.copy2(built / relative, staged / relative)
            for path in built.iterdir():
                if path.is_file() and path.name != "winscope_proxy.py":
                    shutil.copy2(path, staged / path.name)
            shutil.copy2(ROOT / "third_party" / "aosp-ui-inspector" / "LICENSE", staged / "LICENSE-AOSP.txt")
            shutil.copy2(vendor / "LICENSE", staged / "LICENSE-MATERIAL-DESIGN-ICONS.txt")

            destination = PACKAGE / "dist"
            shutil.rmtree(destination)
            shutil.copytree(staged, destination)
            manifest["patchSha256"] = sha256(PACKAGE / "patches" / "0001-add-offline-session-viewer.patch")
            manifest["assets"] = {
                path.relative_to(destination).as_posix(): {"bytes": path.stat().st_size, "sha256": sha256(path)}
                for path in sorted(path for path in destination.rglob("*") if path.is_file())
            }
            MANIFEST.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        finally:
            run("git", "-C", str(source), "worktree", "remove", "--force", str(checkout))
    run(sys.executable, str(ROOT / "scripts" / "verify-aosp-winscope.py"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
