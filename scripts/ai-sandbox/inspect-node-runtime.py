#!/usr/bin/env python3
"""Read only known distro runtime files and package metadata, never host/user configuration."""
import json
from pathlib import Path
import stat
import subprocess


paths = (
    "/usr/share/nodejs/cjs-module-lexer/lexer.js",
    "/usr/share/nodejs/cjs-module-lexer/dist/lexer.js",
    "/usr/share/nodejs/undici/undici-fetch.js",
    "/usr/share/nodejs/acorn/dist/acorn.js",
    "/usr/share/nodejs/acorn-walk/dist/walk.js",
)
records = []
for name in paths:
    path = Path(name)
    owner = subprocess.run(["/usr/bin/dpkg-query", "-S", name], env={}, capture_output=True, timeout=5)
    record = {"path": name, "package": owner.stdout.decode().strip(), "exists": path.exists()}
    if path.exists():
        metadata = path.lstat()
        record.update({"canonical": str(path.resolve()), "symlink": path.is_symlink(),
                       "uid": metadata.st_uid, "mode": stat.filemode(metadata.st_mode), "size": metadata.st_size})
    records.append(record)
print(json.dumps(records, indent=2))
