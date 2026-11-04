"""macOS signing payload transforms; all native operations are explicit caller callbacks."""

import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import stat
import tempfile
import zipfile

from qualification import GateError

ROOT = Path(__file__).resolve().parents[2]
PINNED_TP_MANIFEST = ROOT / "desktop-viewer/platform-perfetto/trace-processor-manifest.json"
SIGNED_TP_MANIFEST = "trace-processor-signature.json"


def sha256_file(path):
    # macOS's system Python is 3.9; hashlib.file_digest is only available from 3.11.
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        raise GateError("Missing or unreadable signing payload") from None
    return digest.hexdigest()


def safe_file(path, root):
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise GateError("Unsafe or missing signing payload file")
    current = path.parent
    while current != root:
        if current.is_symlink() or current == current.parent:
            raise GateError("Unsafe signing payload parent")
        current = current.parent
    return path


def native_header(header):
    magic = header[:4]
    if magic in (b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe"):
        return True
    if magic in (b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca", b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca"):
        # Java .class uses CAFEBABE too, but its version is not a fat-architecture count.
        order = "big" if magic[0] == 0xca else "little"
        return len(header) >= 8 and 1 <= int.from_bytes(header[4:8], order) <= 32
    return False


def signing_targets(app):
    """Discover metadata bundles, including jpackage's extensionless Contents/runtime BNDL."""
    files, bundles = [], set()
    for path in app.rglob("*"):
        if path.is_symlink():
            if not path.resolve(strict=True).is_relative_to(app.resolve()):
                raise GateError("External app symlink rejected")
            continue
        if path.is_file():
            with path.open("rb") as stream:
                if native_header(stream.read(8)):
                    files.append(path)
            if path.name == "Info.plist" and path.parent.name == "Contents":
                safe_file(path, app)
                try:
                    info = plistlib.loads(path.read_bytes())
                except (ValueError, TypeError, plistlib.InvalidFileException):
                    raise GateError("Invalid nested bundle metadata") from None
                if not isinstance(info, dict):
                    raise GateError("Invalid nested bundle metadata object")
                if info.get("CFBundlePackageType") in ("APPL", "BNDL", "FMWK", "XPC!"):
                    bundle = path.parent.parent
                    if bundle != app:
                        bundles.add(bundle)
        elif path.suffix in (".app", ".framework", ".xpc", ".appex", ".bundle"):
            bundles.add(path)
    runtime = app / "Contents/runtime"
    if runtime.exists() and runtime not in bundles:
        raise GateError("The runtime must have valid bundle metadata before signing")
    return (sorted(files, key=lambda item: (-len(item.parts), str(item))),
            sorted(bundles, key=lambda item: (-len(item.parts), str(item))))


def tool_inventories(app, arch):
    """Verify original tool bytes against their provenance before any signing operation."""
    records = []
    for resources in sorted(app.rglob("resources")):
        if resources.is_symlink():
            raise GateError("Symlinked application resources rejected")
        for kind, relative in (("hprof", "hprof-engine/aps-hprof-engine"),
                               ("trace-processor", "perfetto-tools/trace_processor_shell")):
            binary = resources / relative
            if not binary.exists() and not binary.is_symlink():
                if binary.parent.exists():
                    raise GateError("A bundled tool directory is missing its executable")
                continue
            safe_file(binary, app)
            with binary.open("rb") as stream:
                if not native_header(stream.read(8)):
                    raise GateError("Expected native macOS tool payload")
            if kind == "hprof":
                metadata = safe_file(binary.with_name(binary.name + ".sha256"), app)
                if metadata.stat().st_size > 1024:
                    raise GateError("Invalid original HPROF checksum size")
                original = metadata.read_bytes()
                try:
                    upstream = original.decode("ascii").strip()
                except UnicodeError:
                    raise GateError("Invalid original HPROF checksum") from None
                sidecar = binary.with_name(binary.name + ".upstream.sha256")
            else:
                metadata = PINNED_TP_MANIFEST
                if metadata.stat().st_size > 1024 * 1024:
                    raise GateError("Pinned Trace Processor provenance is too large")
                original = metadata.read_bytes()
                try:
                    pinned = json.loads(original)
                    version = pinned["version"]
                    upstream = pinned["artifacts"]["macos-" + arch]["sha256"]
                    if not re.fullmatch(r"v[0-9]+(?:\.[0-9]+)*", version):
                        raise ValueError()
                except (ValueError, KeyError, TypeError):
                    raise GateError("Invalid pinned Trace Processor provenance") from None
                sidecar = binary.parent / SIGNED_TP_MANIFEST
            if not isinstance(upstream, str) or not re.fullmatch(r"[0-9a-f]{64}", upstream) or sha256_file(binary) != upstream:
                raise GateError("Original bundled tool SHA-256 does not match verified provenance")
            if sidecar.exists() or sidecar.is_symlink():
                raise GateError("Refusing already-transformed tool provenance")
            records.append({"kind": kind, "binary": binary, "metadata": metadata, "original": original,
                            "upstream": upstream, "sidecar": sidecar,
                            "version": version if kind == "trace-processor" else None})
    return records


def refresh_tool_inventories(app, arch, records, signed_hashes, team):
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise GateError("Invalid signing team for transformed tool inventory")
    updates = []
    for record in records:
        binary = safe_file(record["binary"], app)
        metadata = record["metadata"]
        if metadata.read_bytes() != record["original"]:
            raise GateError("Tool provenance changed during native signing")
        actual = sha256_file(binary)
        if signed_hashes.get(binary.resolve()) != actual:
            raise GateError("Cannot refresh a tool without verified signed bytes")
        if record["sidecar"].exists() or record["sidecar"].is_symlink():
            raise GateError("Signed tool inventory was replaced during signing")
        if record["kind"] == "hprof":
            updates.append((record["sidecar"], (record["upstream"] + "\n").encode("ascii"), True))
            updates.append((metadata, (actual + "\n").encode("ascii"), False))
        else:
            data = {"schemaVersion": 1, "upstreamVersion": record["version"], "binary": "trace_processor_shell",
                    "upstreamSha256": record["upstream"], "signedSha256": actual, "teamId": team}
            updates.append((record["sidecar"], (json.dumps(data, sort_keys=True) + "\n").encode("ascii"), True))
    # Validate the entire set before changing any checksum or provenance file.
    for path, data, exclusive in updates:
        with path.open("xb" if exclusive else "wb") as stream:
            stream.write(data)
    return {record["binary"]: sha256_file(record["binary"]) for record in records}


def verify_tool_bytes(app, expected):
    for path, digest in expected.items():
        safe_file(path, app)
        if sha256_file(path) != digest:
            raise GateError("A sealed nested bundle changed an inventoried tool")


def jar_plans(app):
    """Inspect all JARs before signing; reject unsafe or already JAR-signed native archives."""
    plans = []
    try:
        for jar in sorted(app.rglob("*.jar")):
            safe_file(jar, app)
            with zipfile.ZipFile(jar) as archive:
                entries, natives = archive.infolist(), []
                seen = set()
                if (len(entries) > 100000 or sum(entry.file_size for entry in entries) > 512 * 1024 * 1024
                        or any(entry.file_size > 64 * 1024 * 1024 for entry in entries)):
                    raise GateError("Native JAR exceeds qualification limits")
                if archive.testzip() is not None:
                    raise GateError("Native JAR failed CRC verification")
                for entry in entries:
                    name = entry.filename
                    path = PurePosixPath(name)
                    if (name in seen or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name
                            or path.as_posix() != name.rstrip("/") or stat.S_ISLNK(entry.external_attr >> 16)
                            or entry.flag_bits & 1):
                        raise GateError("Unsafe or duplicate JAR member")
                    seen.add(name)
                    if not entry.is_dir():
                        with archive.open(entry) as stream:
                            native = native_header(stream.read(8))
                        if native:
                            if not name.endswith((".dylib", ".jnilib")) or entry.file_size > 64 * 1024 * 1024:
                                raise GateError("Unsupported native JAR member")
                            natives.append(name)
                if natives:
                    if any(re.fullmatch(r"META-INF/[^/]+\.(?:SF|RSA|DSA|EC)", name, re.IGNORECASE) for name in seen):
                        raise GateError("Refusing to invalidate a signed JAR; an explicit upstream transform is required")
                    plans.append((jar, sha256_file(jar), tuple(natives)))
    except (OSError, ValueError, zipfile.BadZipFile, NotImplementedError):
        raise GateError("Invalid or unreadable native JAR") from None
    return plans


def sign_jar_natives(plans, directory, signer, verifier):
    """Compose-style extract/sign/repack, preserving paths and every non-native member's bytes."""
    for jar, original_hash, natives in plans:
        if sha256_file(jar) != original_hash:
            raise GateError("JAR changed after pre-sign inspection")
        with tempfile.TemporaryDirectory(prefix="jar-native-", dir=directory) as temporary:
            temporary = Path(temporary)
            replacement = temporary / "signed.jar"
            with zipfile.ZipFile(jar) as source, zipfile.ZipFile(replacement, "w") as target:
                target.comment = source.comment
                for index, entry in enumerate(source.infolist()):
                    data = source.read(entry)
                    if entry.filename in natives:
                        native = temporary / (str(index) + "-" + PurePosixPath(entry.filename).name)
                        native.write_bytes(data)
                        native.chmod(0o700)
                        signer(native)
                        verifier(native)
                        data = native.read_bytes()
                    target.writestr(copy.copy(entry), data)
            with zipfile.ZipFile(jar) as before, zipfile.ZipFile(replacement) as after:
                if before.namelist() != after.namelist() or after.testzip() is not None:
                    raise GateError("Repacked native JAR failed structural verification")
                for entry in before.infolist():
                    if entry.filename not in natives and before.read(entry) != after.read(entry.filename):
                        raise GateError("Repacking changed non-native JAR bytes")
            mode = stat.S_IMODE(jar.stat().st_mode)
            replacement.chmod(mode)
            os.replace(replacement, jar)
