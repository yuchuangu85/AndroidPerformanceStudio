#!/usr/bin/env python3
"""Explicit release signing only. Default tests must never invoke platform signing tools."""

import argparse
import base64
import configparser
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import plistlib
import re
import secrets
import shlex
import signal
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import uuid

from qualification import GateError, require_environment
from macos_payload import (jar_plans, native_header, refresh_tool_inventories, sha256_file,
                           sign_jar_natives, signing_targets, tool_inventories, verify_tool_bytes)

HERE = Path(__file__).resolve().parent
MAC_REFERENCES = (
    "APS_MACOS_CERTIFICATE_BASE64", "APS_MACOS_CERTIFICATE_PASSWORD",
    "APS_MACOS_SIGNING_IDENTITY", "APS_MACOS_INSTALLER_IDENTITY",
    "APPLE_ID", "APPLE_TEAM_ID", "APPLE_APP_SPECIFIC_PASSWORD",
)
WINDOWS_REFERENCES = (
    "APS_WINDOWS_CERTIFICATE_BASE64", "APS_WINDOWS_CERTIFICATE_PASSWORD",
    "APS_WINDOWS_CERTIFICATE_THUMBPRINT", "APS_WINDOWS_TIMESTAMP_URL",
)


def preflight(target, environment=None):
    environment = os.environ if environment is None else environment
    if environment.get("APS_ENABLE_RELEASE_SIGNING") != "true":
        raise GateError("Release signing requires APS_ENABLE_RELEASE_SIGNING=true; unsigned development artifacts are not qualified")
    require_environment(MAC_REFERENCES if target == "macos" else WINDOWS_REFERENCES, environment)
    if target == "macos":
        team = environment["APPLE_TEAM_ID"]
        if not re.fullmatch(r"[A-Z0-9]{10}", team):
            raise GateError("Invalid APPLE_TEAM_ID reference")
        for name, prefix in (("APS_MACOS_SIGNING_IDENTITY", "Developer ID Application: "),
                             ("APS_MACOS_INSTALLER_IDENTITY", "Developer ID Installer: ")):
            value = environment[name]
            if not value.startswith(prefix) or not value.endswith("(" + team + ")") or "\n" in value:
                raise GateError("Expected team-bound Developer ID identity: " + name)
    else:
        if not re.fullmatch(r"[A-Fa-f0-9]{40}", environment["APS_WINDOWS_CERTIFICATE_THUMBPRINT"]):
            raise GateError("Invalid APS_WINDOWS_CERTIFICATE_THUMBPRINT reference")
        try:
            url = urlsplit(environment["APS_WINDOWS_TIMESTAMP_URL"])
        except ValueError:
            raise GateError("Invalid timestamp environment reference") from None
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise GateError("Timestamp service must use HTTPS without inline credentials")


def command(args):
    # Never echo argv, native stdout/stderr or CalledProcessError (all can contain credentials).
    try:
        result = subprocess.run([str(arg) for arg in args], capture_output=True, timeout=2400, check=False)
    except (OSError, subprocess.SubprocessError):
        raise GateError("Release tool could not complete; sensitive output withheld") from None
    if result.returncode:
        raise GateError("Release tool failed; sensitive output withheld")
    return result.stdout.decode("utf-8", errors="replace")


def decode_certificate(name, destination):
    try:
        data = base64.b64decode(os.environ[name], validate=True)
    except (ValueError, KeyError):
        raise GateError("Invalid certificate environment reference: " + name) from None
    if not data or len(data) > 1024 * 1024:
        raise GateError("Invalid certificate size")
    with destination.open("xb") as stream:
        stream.write(data)
    destination.chmod(0o600)


@contextmanager
def temporary_keychain(directory):
    security = "/usr/bin/security"
    previous_search = shlex.split(command([security, "list-keychains", "-d", "user"]))
    previous_default = shlex.split(command([security, "default-keychain", "-d", "user"]))
    if len(previous_default) != 1 or not previous_search:
        raise GateError("Cannot snapshot user keychain state")
    keychain = directory / "aps-release.keychain-db"
    password = secrets.token_urlsafe(48)
    created = False
    try:
        # A tool can create the file and still exit nonzero; cleanup must cover that case too.
        created = True
        command([security, "create-keychain", "-p", password, keychain])
        command([security, "set-keychain-settings", "-lut", "21600", keychain])
        command([security, "unlock-keychain", "-p", password, keychain])
        command([security, "list-keychains", "-d", "user", "-s", keychain, *previous_search])
        command([security, "default-keychain", "-d", "user", "-s", keychain])
        certificate = directory / "identity.p12"
        decode_certificate("APS_MACOS_CERTIFICATE_BASE64", certificate)
        try:
            command([security, "import", certificate, "-P", os.environ["APS_MACOS_CERTIFICATE_PASSWORD"],
                     "-k", keychain, "-T", "/usr/bin/codesign", "-T", "/usr/bin/pkgbuild"])
            command([security, "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", password, keychain])
        finally:
            certificate.unlink(missing_ok=True)
        yield keychain
    finally:
        if created:
            failures = []
            for args in ([security, "default-keychain", "-d", "user", "-s", *previous_default],
                         [security, "list-keychains", "-d", "user", "-s", *previous_search],
                         [security, "delete-keychain", keychain]):
                try:
                    command(args)
                except GateError:
                    failures.append(True)
            if failures:
                raise GateError("Keychain restoration/cleanup failed; release qualification rejected")


def notarize(archive, staple_target, keychain):
    raw = command(["/usr/bin/xcrun", "notarytool", "submit", archive, "--keychain-profile", "aps-release",
                   "--keychain", keychain, "--wait", "--timeout", "30m", "--output-format", "json"])
    try:
        result = json.loads(raw)
        uuid.UUID(result["id"])
        if result["status"] != "Accepted":
            raise ValueError()
    except (ValueError, KeyError, TypeError, AttributeError):
        raise GateError("Notarization did not return an Accepted submission") from None
    command(["/usr/bin/xcrun", "stapler", "staple", staple_target])
    command(["/usr/bin/xcrun", "stapler", "validate", staple_target])


def adb_manifests(app, arch, signed_files=frozenset()):
    """Read and validate only embedded ADB inventories, without changing bytes."""
    result = []
    try:
        app = app.resolve(strict=True)
        for resources in sorted(app.rglob("resources")):
            bundle = resources / "adb-tools"
            if not bundle.exists() and not bundle.is_symlink():
                continue
            if not bundle.is_dir() or bundle.is_symlink() or not bundle.resolve().is_relative_to(app):
                raise GateError("Unsafe embedded ADB directory")
            bundle = bundle.resolve(strict=True)
            manifest = bundle / "manifest.properties"
            if not manifest.is_file() or manifest.is_symlink():
                raise GateError("Missing or unsafe ADB manifest.properties")
            with manifest.open("rb") as stream:
                data = stream.read(128 * 1024 + 1)
            if len(data) > 128 * 1024:
                raise GateError("ADB manifest is too large")
            parser = configparser.ConfigParser(
                interpolation=None, delimiters=("=", ":"), comment_prefixes=("#", "!"),
                empty_lines_in_values=False,
            )
            parser.optionxform = str
            parser.read_string("[properties]\n" + data.decode("ascii"))
            if parser.sections() != ["properties"] or parser.defaults():
                raise GateError("Invalid ADB manifest properties")
            properties = dict(parser["properties"])
            version = properties.get("version", "")
            if (properties.get("schemaVersion") != "1" or properties.get("host") != "macos-" + arch
                    or len(version) > 128
                    or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*(?:[-+ ][A-Za-z0-9][A-Za-z0-9._+ -]*)?", version)):
                raise GateError("Invalid ADB manifest schema, version or host")
            count = properties.get("file.count", "")
            if not re.fullmatch(r"[1-9][0-9]?", count) or int(count) > 64:
                raise GateError("Invalid ADB manifest file.count")
            keys = {"schemaVersion", "version", "host", "file.count"}
            keys.update({"binaryArchitectures", "source"} & properties.keys())
            files, resolved_files, digests = set(), set(), {}
            for index in range(int(count)):
                path_key, hash_key = "file.%d.path" % index, "file.%d.sha256" % index
                keys.update((path_key, hash_key))
                relative = properties.get(path_key, "")
                path = PurePosixPath(relative)
                if (not relative or "\\" in relative or ":" in relative or path.is_absolute()
                        or ".." in path.parts or path.as_posix() != relative or relative in files
                        or relative == "manifest.properties"):
                    raise GateError("Invalid or duplicate ADB manifest path")
                target = (bundle / relative).resolve(strict=True)
                if not target.is_relative_to(bundle) or not target.is_file() or target in resolved_files:
                    raise GateError("Unsafe or missing ADB manifest file")
                expected = properties.get(hash_key, "")
                if not re.fullmatch(r"[0-9a-f]{64}", expected):
                    raise GateError("Invalid ADB manifest checksum")
                actual = sha256_file(target)
                if target not in signed_files and actual != expected:
                    raise GateError("ADB manifest SHA-256 mismatch")
                files.add(relative)
                resolved_files.add(target)
                digests[hash_key] = (target, actual)
            if set(properties) != keys or not {"adb", "NOTICE.txt"} <= files:
                raise GateError("Missing or unexpected ADB manifest fields or files")
            present = set()
            for child in bundle.rglob("*"):
                if not child.resolve(strict=True).is_relative_to(bundle):
                    raise GateError("External ADB symlink rejected")
                if child.is_file():
                    present.add(child.relative_to(bundle).as_posix())
                elif not child.is_dir():
                    raise GateError("Unsafe ADB bundle entry")
            if present != files | {"manifest.properties"}:
                raise GateError("Missing or unlisted ADB bundle files")
            result.append((manifest, data, digests))
    except (OSError, RuntimeError, ValueError, configparser.Error):
        raise GateError("Invalid or unreadable ADB manifest bundle") from None
    return result


def refresh_adb_manifests(app, arch, before, signed_files):
    after = adb_manifests(app, arch, signed_files)
    if [(path, data) for path, data, _ in before] != [(path, data) for path, data, _ in after]:
        raise GateError("ADB manifests changed during nested signing")
    updates = []
    for (manifest, original, old), (_, _, current) in zip(before, after):
        updated = original
        for key, (path, digest) in current.items():
            if old[key][0] != path:
                raise GateError("ADB manifest file changed during nested signing")
            if path in signed_files and digest != old[key][1]:
                pattern = (rb"(?m)^([ \t]*" + re.escape(key.encode("ascii")) + rb"[ \t]*[=:][ \t]*)"
                           + old[key][1].encode("ascii") + rb"([ \t]*\r?)$")
                updated, count = re.subn(pattern, lambda match: match[1] + digest.encode("ascii") + match[2], updated)
                if count != 1:
                    raise GateError("Cannot update ADB manifest checksum")
        if updated != original:
            updates.append((manifest, updated))
    for manifest, data in updates:
        manifest.write_bytes(data)


def sign_macos(app, output, version, arch, directory):
    if platform.system() != "Darwin":
        raise GateError("Developer ID signing requires native macOS")
    if not app.is_dir() or app.is_symlink() or app.suffix != ".app":
        raise GateError("Expected an unsigned development .app directory")
    try:
        with (app / "Contents/Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        parts = version.split(".")
        expected_version = next((".".join(parts[index:]) for index, part in enumerate(parts) if int(part) > 0), "1")
        executable = info["CFBundleExecutable"]
        if (info["CFBundleShortVersionString"] != expected_version or not isinstance(executable, str)
                or not executable or Path(executable).name != executable or "\\" in executable):
            raise ValueError()
    except (OSError, ValueError, KeyError, TypeError, plistlib.InvalidFileException):
        raise GateError("App bundle version/executable does not match this release") from None
    command(["/usr/bin/lipo", "-verify_arch", "arm64" if arch == "arm64" else "x86_64", app / "Contents/MacOS" / executable])
    stage = directory / "stage"
    stage.mkdir()
    copied = stage / app.name
    command(["/usr/bin/ditto", app, copied])
    adb_before = adb_manifests(copied, arch)
    tools_before = tool_inventories(copied, arch)
    jars = jar_plans(copied)
    files, bundles = signing_targets(copied)
    if not files:
        raise GateError("No native app payload to sign")
    with temporary_keychain(directory) as keychain:
        identity = os.environ["APS_MACOS_SIGNING_IDENTITY"]
        available = command(["/usr/bin/security", "find-identity", "-v", "-p", "codesigning", keychain])
        if ('"' + identity + '"') not in available:
            raise GateError("Expected Developer ID Application identity is unavailable")
        def signer(path, entitlements=True):
            args = ["/usr/bin/codesign", "--force", "--sign", identity, "--keychain", keychain, "--timestamp", "--options", "runtime"]
            if entitlements:
                args += ["--entitlements", HERE / "macos-entitlements.plist"]
            command([*args, path])

        def verifier(path):
            command(["/usr/bin/codesign", "--verify", "--strict", path])

        sign_jar_natives(jars, directory, lambda path: signer(path, entitlements=False), verifier)
        expected_jars = {jar: sha256_file(jar) for jar, _, _ in jars}
        signed_hashes = {}
        for path in files:
            signer(path)
            verifier(path)
            signed_hashes[path.resolve()] = sha256_file(path)
        for path, digest in signed_hashes.items():
            if sha256_file(path) != digest:
                raise GateError("Native payload no longer matches verified signed bytes")
        signed_files = set(signed_hashes)
        expected_tools = refresh_tool_inventories(copied, arch, tools_before, signed_hashes, os.environ["APPLE_TEAM_ID"])
        refresh_adb_manifests(copied, arch, adb_before, signed_files)
        # Resource inventories and transformed JARs must be final before any enclosing bundle seal.
        for bundle in bundles:
            signer(bundle)
            command(["/usr/bin/codesign", "--verify", "--deep", "--strict", bundle])
        verify_tool_bytes(copied, {**expected_tools, **expected_jars})
        adb_manifests(copied, arch)
        signer(copied)
        command(["/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", copied])
        verify_tool_bytes(copied, {**expected_tools, **expected_jars})
        adb_manifests(copied, arch)
        command(["/usr/bin/xcrun", "notarytool", "store-credentials", "aps-release", "--keychain", keychain,
                 "--apple-id", os.environ["APPLE_ID"], "--team-id", os.environ["APPLE_TEAM_ID"],
                 "--password", os.environ["APPLE_APP_SPECIFIC_PASSWORD"], "--validate"])
        archive = directory / "app.zip"
        command(["/usr/bin/ditto", "-c", "-k", "--keepParent", copied, archive])
        notarize(archive, copied, keychain)
        command(["/usr/sbin/spctl", "--assess", "--type", "execute", copied])
        prefix = "AndroidPerformanceStudio-" + version + "-macos-" + arch
        dmg, pkg = output / (prefix + ".dmg"), output / (prefix + ".pkg")
        command(["/usr/bin/hdiutil", "create", "-volname", "AndroidPerformanceStudio", "-srcfolder", stage,
                 "-format", "UDZO", dmg])
        command(["/usr/bin/codesign", "--force", "--sign", identity, "--keychain", keychain, "--timestamp", dmg])
        command(["/usr/bin/codesign", "--verify", "--strict", dmg])
        command(["/usr/bin/hdiutil", "verify", dmg])
        command(["/usr/bin/pkgbuild", "--component", copied, "--install-location", "/Applications", "--sign",
                 os.environ["APS_MACOS_INSTALLER_IDENTITY"], "--keychain", keychain, "--timestamp", pkg])
        signature = command(["/usr/sbin/pkgutil", "--check-signature", pkg])
        if os.environ["APS_MACOS_INSTALLER_IDENTITY"] not in signature:
            raise GateError("Expected Developer ID Installer signature is unavailable")
        for artifact, kind in ((dmg, "open"), (pkg, "install")):
            notarize(artifact, artifact, keychain)
            assessment = ["/usr/sbin/spctl", "--assess", "--type", kind]
            if kind == "open":
                assessment += ["--context", "context:primary-signature"]
            command([*assessment, artifact])


def is_macho(path):
    with path.open("rb") as stream:
        return native_header(stream.read(8))


def names(target, version, arch):
    extensions = ("dmg", "pkg") if target == "macos" else ("msi", "exe")
    return {"AndroidPerformanceStudio-" + version + "-" + target + "-" + arch + "." + ext for ext in extensions}


def hashes(output, target, version, arch):
    files = list(output.iterdir())
    if {path.name for path in files} != names(target, version, arch) or any(not path.is_file() or path.is_symlink() for path in files):
        raise GateError("Unexpected or missing release assets")
    result = {}
    for path in files:
        if not path.stat().st_size:
            raise GateError("Empty release asset")
        result[path.name] = sha256_file(path)
    return result


def verify_receipt(receipt, output, target, version, arch, source_sha):
    try:
        data = json.loads(receipt.read_text())
    except (OSError, ValueError):
        raise GateError("Missing or invalid signing receipt") from None
    expected = {"schema": 1, "status": "signed-notarized" if target == "macos" else "authenticode-verified",
                "platform": target, "version": version, "arch": arch, "source_sha": source_sha,
                "assets": hashes(output, target, version, arch)}
    if data != expected:
        raise GateError("Release receipt does not match this source and artifact bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("preflight", "sign", "verify"))
    parser.add_argument("target", choices=("macos", "windows"))
    parser.add_argument("--app", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--arch", choices=("arm64", "x64"))
    parser.add_argument("--source-sha")
    args = parser.parse_args()
    try:
        if args.action == "preflight":
            preflight(args.target)
            print("Signing credential references available; not a signing qualification")
            return 0
        if (not args.output or not args.receipt or not args.arch or not args.version or not args.source_sha
                or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version)
                or not re.fullmatch(r"[0-9a-f]{40}", args.source_sha)):
            raise GateError("Explicit artifact, receipt, version, architecture and full source SHA are required")
        if args.action == "verify":
            verify_receipt(args.receipt, args.output, args.target, args.version, args.arch, args.source_sha)
        else:
            preflight(args.target)
            if command(["git", "rev-parse", "HEAD"]).strip() != args.source_sha:
                raise GateError("Signing source SHA does not match the checkout")
            if args.receipt.exists():
                raise GateError("Refusing to reuse a signing receipt")
            args.output.mkdir(parents=True, exist_ok=True)
            if args.target == "macos" and (not args.app or any(args.output.iterdir())):
                raise GateError("macOS signing requires an app and a fresh empty output directory")
            if args.target == "windows":
                if platform.system() != "Windows" or args.arch != "x64":
                    raise GateError("Windows signing requires a native x64 Windows runner")
                hashes(args.output, args.target, args.version, args.arch)
            with tempfile.TemporaryDirectory(prefix="aps-release-") as temporary:
                directory = Path(temporary)
                directory.chmod(0o700)
                if args.target == "macos":
                    sign_macos(args.app, args.output, args.version, args.arch, directory)
                else:
                    pfx = directory / "identity.pfx"
                    decode_certificate("APS_WINDOWS_CERTIFICATE_BASE64", pfx)
                    command(["pwsh", "-NoProfile", "-NonInteractive", "-File", HERE / "sign-windows.ps1",
                             "-PfxPath", pfx, "-AssetDirectory", args.output.resolve()])
            data = {"schema": 1, "status": "signed-notarized" if args.target == "macos" else "authenticode-verified",
                    "platform": args.target, "version": args.version, "arch": args.arch, "source_sha": args.source_sha,
                    "assets": hashes(args.output, args.target, args.version, args.arch)}
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            with args.receipt.open("x") as stream:
                json.dump(data, stream, sort_keys=True)
            verify_receipt(args.receipt, args.output, args.target, args.version, args.arch, args.source_sha)
    except (GateError, OSError, ValueError):
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, GateError) else "Release gate failed; sensitive details withheld", file=sys.stderr)
        return 1
    print("Release artifact gate passed: " + args.target)
    return 0


def interrupted(_signal, _frame):
    raise GateError("Release signing interrupted; qualification rejected")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
