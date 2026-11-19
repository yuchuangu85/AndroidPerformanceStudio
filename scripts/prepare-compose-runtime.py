#!/usr/bin/env python3
"""Stage a locally built Compose helper, verified agent bundle and Java runtime."""

import argparse
import configparser
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


MAIN_CLASS = "com/androidperformancestudio/compose/inspection/host/ComposeHeadlessMainKt.class"
TARGETS = ("compose-inspection-host", "compose-agent-bundle", "java-runtime")
ARTIFACTS = {
    "service.sha256": "lib_ui_inspector_service.jar",
    "payload.sha256": "lib_ui_inspector_payload.jar",
    "view.sha256": "view-inspector.jar",
    **{f"agent.{abi}.sha256": f"agent/{abi}/lib_ui_inspector_agent.so"
       for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")},
}


def require_file(path):
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"Missing or empty runtime file: {path}")
    return path


def validate_helper(source):
    launcher = source / "bin" / ("aps-compose-inspection.bat" if os.name == "nt" else "aps-compose-inspection")
    require_file(launcher)
    if os.name != "nt" and not os.access(launcher, os.X_OK):
        raise ValueError(f"Helper launcher is not executable: {launcher}")
    jars = sorted((source / "lib").glob("*.jar"))
    if not jars:
        raise ValueError(f"Helper distribution has no lib/*.jar: {source}")
    has_main = False
    for jar in jars:
        with zipfile.ZipFile(require_file(jar)) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Damaged helper JAR: {jar}")
            has_main |= MAIN_CLASS in archive.namelist() and archive.getinfo(MAIN_CLASS).file_size > 0
    if not has_main:
        raise ValueError(f"Helper distribution is missing {MAIN_CLASS}")


def validate_bundle(source):
    parser = configparser.ConfigParser(
        interpolation=None, delimiters=("=", ":"), comment_prefixes=("#", "!"),
    )
    parser.optionxform = str
    parser.read_string("[bundle]\n" + require_file(source / "manifest.properties").read_text(encoding="ascii"))
    if parser.sections() != ["bundle"] or parser.defaults():
        raise ValueError("Invalid agent bundle manifest.properties")
    for key, relative in ARTIFACTS.items():
        expected = parser["bundle"].get(key, "")
        if re.fullmatch(r"[0-9a-fA-F]{64}", expected) is None:
            raise ValueError(f"Missing or invalid agent bundle digest: {key}")
        digest = hashlib.sha256()
        with require_file(source / relative).open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected.lower():
            raise ValueError(f"Agent bundle digest mismatch: {relative}")


def java_executable(home):
    return home / "bin" / ("java.exe" if os.name == "nt" else "java")


def normalize_java_home(source):
    for candidate in (source, source / "Contents" / "Home", source / "Home"):
        if java_executable(candidate).is_file():
            return candidate.resolve()
    raise ValueError(f"Java home has no bin/java executable: {source}")


def validate_java(home):
    executable = require_file(java_executable(home))
    require_file(home / "release")
    require_file(home / "lib" / "modules")
    libraries = (home / "lib/server/libjvm.so", home / "lib/server/libjvm.dylib", home / "bin/server/jvm.dll")
    if not any(path.is_file() and path.stat().st_size > 0 for path in libraries):
        raise ValueError(f"Java runtime is missing its JVM library: {home}")
    result = subprocess.run([str(executable), "-version"], capture_output=True, text=True, timeout=15, check=True)
    version = re.search(r'\b(?:openjdk|java)(?: version)?\s+"?(\d+)(?:[.\s"+_-]|$)', result.stdout + result.stderr)
    if version is None or int(version[1]) < 17:
        raise ValueError(f"Java 17+ is required: {home}")


def prepare(helper_dist, agent_bundle, java_home, resources):
    sources = (helper_dist.resolve(), agent_bundle.resolve(), normalize_java_home(java_home.resolve()))
    resources = resources.resolve()
    for source in sources:
        if source.is_relative_to(resources) or resources.is_relative_to(source):
            raise ValueError("Source distributions and resources must not overlap")
    validators = (validate_helper, validate_bundle, validate_java)
    for source, validate in zip(sources, validators):
        validate(source)
    resources.mkdir(parents=True, exist_ok=True)
    for name in TARGETS:
        target = resources / name
        if target.is_symlink() or target.exists() and not target.is_dir():
            raise ValueError(f"Resource target must be a real directory: {target}")
    with tempfile.TemporaryDirectory(prefix=".compose-runtime-", dir=resources) as temporary:
        scratch = Path(temporary)
        for name, source, validate in zip(TARGETS, sources, validators):
            staged = scratch / name
            shutil.copytree(source, staged)
            validate(staged)
        # Keep existing resources until every copy, checksum and relocated Java probe succeeds.
        backup = scratch / "previous"
        backup.mkdir()
        previous, installed = [], []
        try:
            for name in TARGETS:
                target = resources / name
                if target.exists():
                    target.replace(backup / name)
                    previous.append(name)
                (scratch / name).replace(target)
                installed.append(name)
        except OSError:
            for name in reversed(installed):
                shutil.rmtree(resources / name)
            for name in previous:
                (backup / name).replace(resources / name)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--helper-dist", type=Path, required=True)
    parser.add_argument("--agent-bundle", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--resources", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.helper_dist, args.agent_bundle, args.java_home, args.resources)
    except (OSError, ValueError, configparser.Error, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"Compose runtime preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Compose runtime prepared: {args.resources.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
