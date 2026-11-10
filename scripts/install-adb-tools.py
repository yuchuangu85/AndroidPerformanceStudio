#!/usr/bin/env python3
"""Prepare the application-owned ADB bundle on a development or packaging host."""

import argparse
import configparser
import hashlib
import json
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "desktop-viewer/platform-core/adb-core/adb-tools-manifest.json"
PREPARE = ROOT / "scripts/prepare-adb-tools.py"
MAX_ARCHIVE_BYTES = 128 * 1024 * 1024


def host_key():
    systems = {"Darwin": "macos", "Windows": "windows", "Linux": "linux"}
    architectures = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64"}
    try:
        return f"{systems[platform.system()]}-{architectures[platform.machine().lower()]}"
    except KeyError as error:
        raise ValueError("Unsupported ADB build host") from error


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_archive(url, archive):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as incoming, archive.open("wb") as destination:
                while True:
                    block = incoming.read(1024 * 1024)
                    if not block:
                        break
                    destination.write(block)
                    if destination.tell() > MAX_ARCHIVE_BYTES:
                        raise ValueError("ADB download exceeds archive limit")
            return
        except urllib.error.HTTPError as error:
            if error.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 2:
                raise
        print(f"ADB download failed; retrying ({attempt + 2}/3)", file=sys.stderr)
        time.sleep(2 ** attempt)


def extract_archive(archive, expected_checksum, output):
    if sha256(archive) != expected_checksum:
        raise ValueError("ADB archive checksum mismatch")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries) > 256 or sum(entry.file_size for entry in entries) > MAX_ARCHIVE_BYTES:
            raise ValueError("ADB archive exceeds extraction limits")
        for entry in entries:
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in entry.filename:
                raise ValueError("Unsafe ADB archive path")
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Symbolic links are not allowed in ADB archives")
            if not relative.parts or relative.parts[0] != "platform-tools":
                raise ValueError("Unexpected ADB archive root")
            if entry.is_dir():
                continue
            target = output.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(entry) as incoming, target.open("wb") as destination:
                shutil.copyfileobj(incoming, destination)
            if relative.name == "adb":
                target.chmod(0o755)
    return output / "platform-tools"


def source_version(source):
    properties = source / "source.properties"
    parser = configparser.ConfigParser(interpolation=None)
    try:
        parser.read_string("[properties]\n" + properties.read_text(encoding="utf-8"))
        return parser.get("properties", "Pkg.Revision").strip()
    except configparser.Error as error:
        raise ValueError("ADB source.properties has an invalid or missing Pkg.Revision") from error


def prepare(source, output, host, artifact):
    expected_version = artifact.get("version")
    if expected_version and source_version(source) != expected_version:
        raise ValueError(f"ADB source must be version {expected_version}")
    executable = source / ("adb.exe" if host.startswith("windows-") else "adb")
    if artifact.get("adbSha256") and sha256(executable) != artifact["adbSha256"]:
        raise ValueError("ADB executable does not match the pinned binary")
    subprocess.run(
        [sys.executable, str(PREPARE), "--source-dir", str(source), "--host", host,
         "--output-dir", str(output), "--source-url", artifact["url"]],
        check=True,
    )


def install(args):
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported ADB download manifest")
    host = args.host or host_key()
    artifact = manifest["artifacts"].get(host)
    if artifact is None:
        raise ValueError(f"No application-managed ADB source for {host}")
    output = args.output_dir.absolute()
    with tempfile.TemporaryDirectory(prefix="aps-adb-download-") as temporary:
        scratch = Path(temporary)
        if args.source_dir:
            source = args.source_dir.resolve()
        elif artifact.get("provider") == "ubuntu":
            if host != host_key() or platform.system() != "Linux":
                raise ValueError("The Linux arm64 ADB bundle must be prepared on its native build host")
            executable = shutil.which("adb")
            if executable is None:
                raise ValueError("Build host requires the Ubuntu adb and patchelf packages")
            source = scratch / "ubuntu-adb"
            subprocess.run(
                [sys.executable, str(ROOT / "scripts/collect-linux-adb.py"),
                 "--adb", executable, "--output-dir", str(source)], check=True,
            )
        else:
            archive = args.archive.resolve() if args.archive else scratch / "platform-tools.zip"
            if not args.archive:
                download_archive(artifact["url"], archive)
            source = extract_archive(archive, artifact["sha256"], scratch / "extracted")
        prepare(source, output, host, artifact)
    # Probe only host-compatible tools; macOS universal ADB also supports cross-arch packaging.
    if host == host_key() or host.startswith("macos-") and host_key().startswith("macos-"):
        executable = output / ("adb.exe" if host.startswith("windows-") else "adb")
        result = subprocess.run([str(executable), "version"], capture_output=True, text=True, timeout=15, check=True)
        if "Android Debug Bridge version" not in result.stdout:
            raise ValueError("Packaged ADB failed its version probe")
        print(result.stdout.strip())
    print(f"Application-managed ADB prepared for {host}: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", help="Target OS and architecture, for example macos-arm64")
    parser.add_argument("--source-dir", type=Path, help="Use an existing trusted platform-tools directory")
    parser.add_argument("--archive", type=Path, help="Use an already downloaded, checksum-verified ZIP")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/adb-tools")
    args = parser.parse_args()
    if args.source_dir and args.archive:
        parser.error("--source-dir and --archive are mutually exclusive")
    try:
        install(args)
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"ADB preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
