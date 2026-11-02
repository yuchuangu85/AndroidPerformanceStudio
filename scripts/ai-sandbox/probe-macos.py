#!/usr/bin/env python3
"""Bounded, synthetic deny-default Seatbelt investigation, not an enabled backend."""
import json
import os
from pathlib import Path
import resource
import socket
import subprocess
import tempfile


def no_core():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def run(command, root, name):
    result = subprocess.run(command, cwd=root, env={}, capture_output=True,
                            timeout=10, preexec_fn=no_core)
    (root / (name + ".stdout")).write_bytes(result.stdout)
    (root / (name + ".stderr")).write_bytes(result.stderr)
    record = {"name": name, "argv": command, "exit": result.returncode}
    print(json.dumps(record), flush=True)
    return record


def main():
    if os.uname().sysname != "Darwin":
        raise SystemExit("Darwin host required")
    root = Path(tempfile.mkdtemp(prefix="aps-seatbelt-")).resolve()
    root.chmod(0o700)
    print("RAW_PROBE_ROOT=" + str(root), flush=True)
    fixture = root / "fixture"
    run(["/usr/bin/clang", "-std=c11", "-Wall", "-Wextra", "-Werror",
         str(Path(__file__).with_name("macos-probe.c").resolve()), "-o", str(fixture)],
        root, "compile")
    if not fixture.exists():
        raise SystemExit(1)
    sentinel = root / "host-sentinel"
    sentinel.write_text("synthetic APS sentinel\n")
    sentinel.chmod(0o600)
    scratch = root / "scratch"
    scratch.mkdir(mode=0o700)
    results = [run([str(fixture), "hello"], root, "baseline"),
               run([str(fixture), "memory"], root, "memory-limit")]
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = str(listener.getsockname()[1])
        base = f'''(version 1)
(deny default)
(allow process-exec (literal {json.dumps(str(fixture))}))
(allow file-read* (literal {json.dumps(str(fixture))})
    (subpath "/usr/lib") (subpath "/System/Library"))
(allow file-write* (subpath {json.dumps(str(scratch))}))
'''
        # Each addition is explicit; no allow-default, home, network or fork grant.
        for name, additions in [
            ("strict", ""),
            ("sysctl", "(allow sysctl-read)\n"),
            ("metadata", '(allow sysctl-read)\n(allow file-read-metadata (literal "/") (literal "/private") (literal "/private/var") (literal "/dev/null"))\n'),
        ]:
            profile = root / (name + ".sb")
            profile.write_text(base + additions)
            results.append(run(["/usr/bin/sandbox-exec", "-f", str(profile),
                                str(fixture), "boundary", str(sentinel),
                                str(scratch / "output"), port], root, name))
    assert sentinel.read_text() == "synthetic APS sentinel\n"
    (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("Qualification is fail-closed: results are investigation evidence only.")


if __name__ == "__main__":
    main()
