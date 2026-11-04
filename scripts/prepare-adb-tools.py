#!/usr/bin/env python3
"""Prepare a bounded, host-checked ADB bundle without executing SDK tools.

Only platform-tools payloads (or an already patched Linux staging directory)
are inputs. Downloads, dependency discovery and RPATH patching belong upstream.
Existing valid bundles may be updated; unknown or damaged directories are never
removed. --verify-only is read-only and needs no source directory.
"""

import argparse
import configparser
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import sys
import tempfile


HOSTS = ("macos-arm64", "macos-x64", "windows-x64", "linux-arm64", "linux-x64")
MANIFEST = "manifest.properties"
MAX_FILES = 64  # Matches ManagedAdbBundle's runtime limit.
MAX_METADATA = 128 * 1024
CPU_MACH = {0x01000007: "x64", 0x0100000C: "arm64"}
CPU_ELF = {62: "x64", 183: "arm64"}
CPU_PE = {0x014C: "x86", 0x8664: "x64"}


class BundleError(ValueError):
    """Invalid input or an unsafe output destination."""


def require(condition, message):
    if not condition:
        raise BundleError(message)


def contained(root, relative, allow_links=False):
    candidate = root / relative
    if not allow_links:
        current = root
        for part in PurePosixPath(relative).parts:
            current = current / part
            require(not current.is_symlink(), "Symlink in bundle: " + relative)
    resolved = candidate.resolve(strict=True)
    require(resolved.is_relative_to(root), "Path escapes root: " + relative)
    return resolved


def regular_file(root, relative, allow_links=False):
    path = contained(root, relative, allow_links)
    require(stat.S_ISREG(path.stat().st_mode), "Not a regular file: " + relative)
    return path


def properties(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_METADATA + 1)
    require(len(data) <= MAX_METADATA, "Properties file is too large: " + str(path))
    parser = configparser.ConfigParser(
        interpolation=None, delimiters=("=", ":"), comment_prefixes=("#", "!"),
        empty_lines_in_values=False,
    )
    parser.optionxform = str
    try:
        parser.read_string("[properties]\n" + data.decode("ascii"))
    except (configparser.Error, UnicodeError) as error:
        raise BundleError("Invalid properties file: " + str(path)) from error
    require(parser.sections() == ["properties"] and not parser.defaults(),
            "Unexpected properties section: " + str(path))
    return dict(parser["properties"])


def version_from_source(source, explicit):
    metadata = source / "source.properties"
    recorded = None
    if metadata.exists() or metadata.is_symlink():
        recorded = properties(regular_file(source, "source.properties", True)).get("Pkg.Revision")
    if explicit is not None and recorded is not None:
        require(explicit == recorded, "--version disagrees with source.properties Pkg.Revision")
    version = explicit if explicit is not None else recorded
    require(version is not None, "Missing Pkg.Revision in source.properties; supply --version")
    validate_version(version)
    return version


def validate_version(version):
    require(isinstance(version, str) and len(version) <= 128 and
            re.fullmatch(r"[0-9]+(?::[0-9]+)?(?:\.[0-9]+)*(?:[-+~ ][A-Za-z0-9][A-Za-z0-9._+:~ -]*)?", version),
            "Invalid ADB version")


def required_paths(host):
    if host == "windows-x64":
        return {"adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt"}
    return {"adb", "NOTICE.txt"}


def library_name(name, host):
    if host.startswith("linux-"):
        selected = name.endswith(".so") or ".so." in name
    else:
        selected = name.startswith("libc++.")
    if selected:
        require(re.fullmatch(r"[A-Za-z0-9_+.-]+", name) is not None,
                "Unsafe companion filename: " + name)
    return selected


def allowed_path(relative, host):
    path = PurePosixPath(relative)
    require(relative and "\\" not in relative and ":" not in relative and
            not path.is_absolute() and ".." not in path.parts and
            path.as_posix() == relative, "Invalid bundle path: " + relative)
    return relative in required_paths(host) or (
        len(path.parts) == 2 and path.parts[0] == "lib64" and library_name(path.name, host)
    )


def select_files(source, host):
    selected = {name: regular_file(source, name, True) for name in sorted(required_paths(host))}
    # Pinned macOS ADB links only system libraries; the SDK's libc++ is for other tools.
    if not host.startswith("linux-"):
        return selected
    directory = source / "lib64"
    if directory.exists() or directory.is_symlink():
        library_dir = contained(source, "lib64", True)
        require(library_dir.is_dir(), "lib64 is not a directory")
        for candidate in library_dir.iterdir():
            if library_name(candidate.name, host):
                relative = "lib64/" + candidate.name
                selected[relative] = regular_file(source, relative, True)
                require(len(selected) <= MAX_FILES, "Too many bundle files (maximum 64)")
    return selected


def binary_architectures(path):
    """Read only bounded headers; universal declarations must match their slices."""
    with path.open("rb") as stream:
        size = os.fstat(stream.fileno()).st_size

        def read(offset, count):
            require(0 <= offset <= size and 0 <= count <= size - offset,
                    "Truncated binary header: " + str(path))
            stream.seek(offset)
            data = stream.read(count)
            require(len(data) == count, "Truncated binary header: " + str(path))
            return data

        def thin_mach(offset, length):
            require(length >= 32, "Truncated Mach-O slice: " + str(path))
            magic = read(offset, 4)
            endian = {b"\xcf\xfa\xed\xfe": "<", b"\xfe\xed\xfa\xcf": ">"}.get(magic)
            require(endian is not None, "Unsupported Mach-O slice: " + str(path))
            cpu, subtype = struct.unpack(endian + "II", read(offset + 4, 8))
            read(offset, 32)
            require(cpu in CPU_MACH, "Unsupported Mach-O CPU: " + str(path))
            return cpu, subtype

        magic = read(0, 4)
        fat_formats = {
            b"\xca\xfe\xba\xbe": (">", False), b"\xbe\xba\xfe\xca": ("<", False),
            b"\xca\xfe\xba\xbf": (">", True), b"\xbf\xba\xfe\xca": ("<", True),
        }
        if magic in fat_formats:
            endian, wide = fat_formats[magic]
            count = struct.unpack(endian + "I", read(4, 4))[0]
            require(1 <= count <= 64, "Invalid universal Mach-O slice count")
            entry_size = 32 if wide else 20
            table_end = 8 + count * entry_size
            read(0, table_end)
            ranges, architectures = [], set()
            for index in range(count):
                entry = read(8 + index * entry_size, entry_size)
                if wide:
                    cpu, subtype, offset, length, alignment, reserved = struct.unpack(endian + "IIQQII", entry)
                    require(reserved == 0, "Invalid universal Mach-O reserved field")
                else:
                    cpu, subtype, offset, length, alignment = struct.unpack(endian + "IIIII", entry)
                require(offset >= table_end and length >= 32 and length <= size - offset and
                        alignment <= 63 and offset % (1 << alignment) == 0,
                        "Invalid universal Mach-O slice bounds")
                require(all(offset + length <= start or offset >= end for start, end in ranges),
                        "Overlapping universal Mach-O slices")
                require(thin_mach(offset, length) == (cpu, subtype), "Mach-O slice CPU disagrees with table")
                ranges.append((offset, offset + length))
                architectures.add(CPU_MACH[cpu])
            return "macos", architectures
        if magic in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf"):
            cpu, _ = thin_mach(0, size)
            return "macos", {CPU_MACH[cpu]}
        if magic == b"\x7fELF":
            header = read(0, 64)
            require(header[4:7] == b"\x02\x01\x01" and header[7] in (0, 3),
                    "Expected Linux little-endian ELF64: " + str(path))
            kind, cpu, version = struct.unpack_from("<HHI", header, 16)
            require(kind in (2, 3) and cpu in CPU_ELF and version == 1 and
                    struct.unpack_from("<H", header, 52)[0] == 64,
                    "Unsupported ELF64 CPU or header: " + str(path))
            return "linux", {CPU_ELF[cpu]}
        if magic[:2] == b"MZ":
            offset = struct.unpack_from("<I", read(0, 64), 60)[0]
            require(offset >= 64, "Invalid PE header offset")
            header = read(offset, 24)
            require(header[:4] == b"PE\0\0", "Invalid PE signature")
            cpu = struct.unpack_from("<H", header, 4)[0]
            require(cpu in CPU_PE, "Unsupported PE CPU (only x86/x64 are allowed)")
            optional_size = struct.unpack_from("<H", header, 20)[0]
            minimum, expected_magic = (96, 0x10B) if cpu == 0x014C else (112, 0x20B)
            require(optional_size >= minimum, "Truncated PE optional header")
            optional = read(offset + 24, optional_size)
            require(struct.unpack_from("<H", optional)[0] == expected_magic,
                    "PE machine and optional header disagree")
            return "windows", {CPU_PE[cpu]}
        raise BundleError("Unrecognized binary header: " + str(path))


def check_binaries(files, host):
    os_name, architecture = host.split("-", 1)
    executable = "adb.exe" if os_name == "windows" else "adb"
    detected = {}
    for relative, path in files.items():
        if relative == "NOTICE.txt":
            continue
        binary_os, architectures = binary_architectures(path)
        compatible = architectures <= {"x86", "x64"} if os_name == "windows" else architecture in architectures
        require(binary_os == os_name and compatible,
                "Binary host mismatch for %s: expected %s, found %s/%s" %
                (relative, host, binary_os, ",".join(sorted(architectures))))
        detected[relative] = architectures
    if os_name == "windows":
        require(all(architectures == detected[executable] for architectures in detected.values()),
                "Windows DLL architecture must match adb.exe")
    return ",".join(sorted(detected[executable]))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def property_escape(value):
    # java.util.Properties.load(InputStream) is Latin-1 with Java escapes.
    require(not any(ord(character) < 32 or ord(character) == 127 for character in value),
            "Control character in manifest metadata")
    result = []
    for index, character in enumerate(value):
        if character == "\\" or (index == 0 and character == " "):
            result.append("\\" + character)
        elif ord(character) > 126:
            for offset in range(0, len(character.encode("utf-16-be")), 2):
                unit = character.encode("utf-16-be")[offset:offset + 2]
                result.append("\\u%04x" % int.from_bytes(unit, "big"))
        else:
            result.append(character)
    return "".join(result)


def inventory(root):
    require(not root.is_symlink() and root.is_dir(), "Output must be a real directory")
    found = set()
    for child in root.iterdir():
        require(not child.is_symlink(), "Symlink in output: " + child.name)
        if child.name == "lib64" and child.is_dir():
            has_libraries = False
            for library in child.iterdir():
                has_libraries = True
                require(not library.is_symlink() and library.is_file(), "Unsafe output library: " + library.name)
                found.add("lib64/" + library.name)
                require(len(found) <= MAX_FILES + 1, "Too many output files")
            require(has_libraries, "Unexpected empty lib64 directory")
        else:
            require(child.is_file(), "Unknown output directory or special file: " + child.name)
            found.add(child.name)
        require(len(found) <= MAX_FILES + 1, "Too many output files")
    return found


def verify_bundle(root, host, version=None):
    present = inventory(root)
    require(MANIFEST in present, "Missing " + MANIFEST)
    manifest = properties(regular_file(root, MANIFEST))
    require(manifest.get("schemaVersion") == "1", "Unsupported manifest schemaVersion")
    require(manifest.get("host") == host, "Manifest host mismatch: expected " + host)
    validate_version(manifest.get("version"))
    if version is not None:
        require(manifest["version"] == version, "Manifest version mismatch")
    count_text = manifest.get("file.count", "")
    require(re.fullmatch(r"[1-9][0-9]?", count_text) is not None and int(count_text) <= MAX_FILES,
            "Invalid manifest file.count")
    keys = {"schemaVersion", "version", "host", "binaryArchitectures", "file.count"}
    if "source" in manifest:
        keys.add("source")
    files = {}
    for index in range(int(count_text)):
        path_key, hash_key = "file.%d.path" % index, "file.%d.sha256" % index
        keys.update((path_key, hash_key))
        relative = manifest.get(path_key, "")
        require(allowed_path(relative, host) and relative not in files, "Unexpected or duplicate bundle path: " + relative)
        expected = manifest.get(hash_key, "")
        require(re.fullmatch(r"[0-9a-f]{64}", expected) is not None, "Invalid checksum for " + relative)
        path = regular_file(root, relative)
        require(sha256(path) == expected, "SHA-256 mismatch: " + relative)
        files[relative] = path
    require(set(manifest) == keys, "Missing or unexpected manifest fields")
    require(required_paths(host) <= set(files), "Required executable, companion or NOTICE.txt missing from manifest")
    require(present == set(files) | {MANIFEST}, "Unknown or unlisted files in output")
    require(manifest["binaryArchitectures"] == check_binaries(files, host), "binaryArchitectures mismatch")
    if os.name != "nt" and not host.startswith("windows-"):
        require(files["adb"].stat().st_mode & 0o111, "Bundled adb is not executable")
    return manifest


def existing_bundle(output, host):
    require(not output.is_symlink(), "Output directory cannot be a symlink")
    if not output.exists():
        return None
    if not inventory(output):
        return {}
    return verify_bundle(output, host)


def prepare(source, host, output, version=None, verify_only=False, source_url=None):
    require(host in HOSTS, "Unsupported host: " + host)
    output = Path(output).absolute()
    require(not output.is_symlink(), "Output directory cannot be a symlink")
    output = output.resolve()
    if source is not None:
        source = Path(source).resolve(strict=not verify_only)
        require(not source.is_relative_to(output) and not output.is_relative_to(source),
                "Source and output directories must not overlap")
    if verify_only:
        return verify_bundle(output, host, version)
    require(source is not None and source.is_dir(), "--source-dir must be a directory")
    version = version_from_source(source, version)
    selected = select_files(source, host)
    architectures = check_binaries(selected, host)
    metadata = {"schemaVersion": "1", "version": version, "host": host, "binaryArchitectures": architectures}
    if source_url is not None:
        require(0 < len(source_url) <= 4096, "Invalid --source-url length")
        metadata["source"] = property_escape(source_url)
    before = existing_bundle(output, host)
    output.parent.mkdir(parents=True, exist_ok=True)
    workspace = Path(tempfile.mkdtemp(prefix="." + output.name + ".prepare-", dir=output.parent))
    staging, previous = workspace / "bundle", workspace / "previous"
    try:
        staging.mkdir()
        for relative, path in sorted(selected.items()):
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            with path.open("rb") as incoming, destination.open("xb") as outgoing:
                shutil.copyfileobj(incoming, outgoing)
            destination.chmod(0o755 if relative == "adb" else 0o644)
        metadata["file.count"] = str(len(selected))
        for index, relative in enumerate(sorted(selected)):
            metadata["file.%d.path" % index] = relative
            metadata["file.%d.sha256" % index] = sha256(staging / relative)
        (staging / MANIFEST).write_text(
            "".join(key + "=" + value + "\n" for key, value in metadata.items()), encoding="ascii",
        )
        verified = verify_bundle(staging, host, version)
        require(existing_bundle(output, host) == before, "Output changed during preparation")
        if verified == before:
            return verified
        if before is not None:
            output.rename(previous)
        try:
            staging.rename(output)
        except OSError:
            if previous.exists():
                require(not output.exists() and not output.is_symlink(),
                        "Publication failed; previous bundle preserved at " + str(previous))
                previous.rename(output)
            raise
        if previous.exists():
            require(existing_bundle(previous, host) == before,
                    "Previous output changed; preserved at " + str(previous))
            shutil.rmtree(previous)
        return verified
    finally:
        # If rollback cannot complete, retain the original instead of deleting it.
        if not previous.exists():
            shutil.rmtree(workspace)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, help="Prepared platform-tools directory (required unless --verify-only)")
    parser.add_argument("--host", required=True, choices=HOSTS)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--version", help="Explicit version; must agree with source.properties when present")
    parser.add_argument("--source-url", help="Optional provenance string; no network requests are made")
    parser.add_argument("--verify-only", action="store_true", help="Validate existing output without modifying it")
    args = parser.parse_args(argv)
    if not args.verify_only and args.source_dir is None:
        parser.error("--source-dir is required unless --verify-only")
    try:
        manifest = prepare(args.source_dir, args.host, args.output_dir, args.version, args.verify_only, args.source_url)
    except (BundleError, OSError, RuntimeError) as error:
        print("error: " + str(error), file=sys.stderr)
        return 1
    print("%s ADB bundle: %s (version %s, %s, %s files)" %
          ("Verified" if args.verify_only else "Prepared", args.output_dir,
           manifest["version"], args.host, manifest["file.count"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
