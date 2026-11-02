#!/usr/bin/env python3
"""Prepare only a private temporary qualification directory; never installs packages."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request


def main():
    if os.uname().sysname != "Linux":
        raise SystemExit("Linux host required")
    root = Path(tempfile.mkdtemp(prefix="aps-native-linux-")).resolve()
    root.chmod(0o700)
    print("RAW_PROBE_ROOT=" + str(root), flush=True)
    records = []

    def run(name, argv):
        result = subprocess.run(argv, cwd=root, env={"PATH": "/usr/bin:/bin"},
                                capture_output=True, timeout=30)
        (root / (name + ".stdout")).write_bytes(result.stdout)
        (root / (name + ".stderr")).write_bytes(result.stderr)
        records.append({"name": name, "argv": argv, "exit": result.returncode})
        (root / "prepare.json").write_text(json.dumps(records, indent=2) + "\n")
        print(json.dumps(records[-1]), flush=True)
        return result

    installed = Path("/usr/bin/bwrap")
    if installed.is_file():
        bwrap = installed
    else:
        # Use the existing dependency's distribution package, not apt install/auth/proxy config.
        metadata = run("package-metadata", ["/usr/bin/apt-cache", "show", "bubblewrap"])
        fields = dict(line.split(": ", 1) for line in metadata.stdout.decode().split("\n\n")[0].splitlines()
                      if ": " in line and not line.startswith(" "))
        relative = fields["Filename"]
        if not relative.startswith("pool/main/b/bubblewrap/") or ".." in relative:
            raise SystemExit("Unexpected package path")
        url = "https://archive.ubuntu.com/ubuntu/" + relative
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=30) as response:
            package = response.read(1_048_577)
        if len(package) > 1_048_576 or hashlib.sha256(package).hexdigest() != fields["SHA256"]:
            raise SystemExit("Package checksum/size mismatch")
        archive = root / "bubblewrap.deb"
        archive.write_bytes(package)
        (root / "package.json").write_text(json.dumps({"url": url, "sha256": fields["SHA256"]}) + "\n")
        extracted = run("extract", ["/usr/bin/dpkg-deb", "-x", str(archive), str(root / "package")])
        if extracted.returncode:
            raise SystemExit(1)
        bwrap = root / "package/usr/bin/bwrap"
    source = Path(__file__).resolve().parents[2] / (
        "desktop-viewer/ai-platform/ai-platform-script-runtime/src/main/native/linux/aps-script-sandbox.c")
    helper = root / "aps-script-sandbox"
    compiled = run("compile", ["/usr/bin/cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                               "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", str(source), "-o", str(helper)])
    if compiled.returncode:
        raise SystemExit(1)
    run("bwrap-version", [str(bwrap), "--version"])
    # No untrusted code, no host-root/home binds, no host mounts added to the VM.
    smoke = run("namespace-smoke", [str(bwrap), "--die-with-parent", "--new-session", "--unshare-all",
                                     "--cap-drop", "ALL", "--clearenv", "--ro-bind", "/usr", "/usr",
                                     "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
                                     "--", "/usr/bin/true"])
    (root / "tools.json").write_text(json.dumps({"bwrap": str(bwrap), "helper": str(helper),
                                                "namespaceSmokeExit": smoke.returncode}) + "\n")
    if smoke.returncode:
        raise SystemExit("Namespace startup unavailable; no hostile code was launched")


if __name__ == "__main__":
    main()
