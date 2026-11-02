"""Synthetic cross-platform fixtures; no installed SDK or native tools required."""

import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "prepare-adb-tools.py"
SPEC = importlib.util.spec_from_file_location("prepare_adb_tools", SCRIPT)
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)


def macho(architecture, endian="<"):
    cpu = {"x64": 0x01000007, "arm64": 0x0100000C}[architecture]
    return struct.pack(endian + "8I", 0xFEEDFACF, cpu, 0, 2, 0, 0, 0, 0)


def universal(architectures=("x64", "arm64"), wide=False, endian=">"):
    entry_size = 32 if wide else 20
    offset = 8 + entry_size * len(architectures)
    table, slices = [], []
    for architecture in architectures:
        image = macho(architecture)
        cpu, subtype = struct.unpack_from("<II", image, 4)
        table.append(struct.pack(endian + ("IIQQII" if wide else "IIIII"),
                                 cpu, subtype, offset, len(image), 0, *([0] if wide else [])))
        slices.append(image)
        offset += len(image)
    return struct.pack(endian + "II", 0xCAFEBABF if wide else 0xCAFEBABE, len(architectures)) + b"".join(table + slices)


def elf(architecture="x64"):
    cpu = {"x64": 62, "arm64": 183}[architecture]
    identity = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8
    return identity + struct.pack("<HHIQQQIHHHHHH", 3, cpu, 1, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0)


def pe(architecture="x64"):
    cpu = {"x86": 0x014C, "x64": 0x8664, "arm64": 0xAA64}[architecture]
    optional_size, magic = (96, 0x10B) if architecture == "x86" else (112, 0x20B)
    dos = bytearray(64)
    dos[:2] = b"MZ"
    struct.pack_into("<I", dos, 60, 64)
    coff = b"PE\0\0" + struct.pack("<HHIIIHH", cpu, 0, 0, 0, 0, optional_size, 2)
    return bytes(dos) + coff + struct.pack("<H", magic) + bytes(optional_size - 2)


class PrepareAdbToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="adb tools tests ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "SDK With Spaces" / "platform-tools"
        self.output = self.root / "Application With Spaces" / "adb-tools"

    def write(self, relative, data, root=None):
        path = (root or self.source) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, str):
            path.write_text(data, encoding="ascii")
        else:
            path.write_bytes(data)
        return path

    def fixture(self, host="linux-x64", image=None):
        os_name, architecture = host.split("-", 1)
        if image is None:
            image = {"macos": macho, "linux": elf, "windows": pe}[os_name](architecture)
        self.write("adb.exe" if os_name == "windows" else "adb", image)
        if os_name == "windows":
            self.write("AdbWinApi.dll", image)
            self.write("AdbWinUsbApi.dll", image)
        self.write("NOTICE.txt", "Synthetic license notices\n")
        self.write("source.properties", "# fixture\nPkg.Desc = Android SDK Platform-Tools\nPkg.Revision = 37.0.1\n")

    def prepare(self, host="linux-x64", **kwargs):
        return TOOLS.prepare(self.source, host, self.output, **kwargs)

    def snapshot(self, root):
        return {str(path.relative_to(root)): (path.read_bytes(), path.stat().st_mode)
                for path in root.rglob("*") if path.is_file()}

    def manifest_paths(self, manifest):
        return {manifest["file.%d.path" % index] for index in range(int(manifest["file.count"]))}

    def replace_manifest(self, change):
        path = self.output / TOOLS.MANIFEST
        path.write_text(change(path.read_text(encoding="ascii")), encoding="ascii")

    def assert_rejected(self, host="linux-x64", message=None, **kwargs):
        with self.assertRaises((TOOLS.BundleError, OSError)) as caught:
            self.prepare(host, **kwargs)
        if message:
            self.assertIn(message, str(caught.exception))
        return caught.exception

    def test_all_hosts_and_minimal_deterministic_manifest(self):
        for host in TOOLS.HOSTS:
            with self.subTest(host=host):
                self.fixture(host)
                self.write("fastboot", "not selected")
                self.write("unrelated/user-data.txt", "not selected")
                source_before = self.snapshot(self.source)
                manifest = self.prepare(host)
                paths = self.manifest_paths(manifest)
                self.assertEqual(TOOLS.required_paths(host), paths)
                self.assertEqual("1", manifest["schemaVersion"])
                self.assertEqual("37.0.1", manifest["version"])
                self.assertEqual(host, manifest["host"])
                self.assertEqual(host.split("-")[1], manifest["binaryArchitectures"])
                self.assertNotIn("source", manifest)
                self.assertEqual(source_before, self.snapshot(self.source))
                self.assertEqual(paths | {TOOLS.MANIFEST}, set(self.snapshot(self.output)))
                for index in range(int(manifest["file.count"])):
                    path = manifest["file.%d.path" % index]
                    self.assertEqual(hashlib.sha256((self.output / path).read_bytes()).hexdigest(),
                                     manifest["file.%d.sha256" % index])
                    self.assertNotIn("\\", path)
                self.assertEqual(manifest, TOOLS.verify_bundle(self.output, host))
                shutil.rmtree(self.output)
                shutil.rmtree(self.source)

    def test_macos_universal_variants_cover_both_hosts(self):
        for wide in (False, True):
            for endian in ("<", ">"):
                for host in ("macos-x64", "macos-arm64"):
                    with self.subTest(wide=wide, endian=endian, host=host):
                        self.fixture(host, universal(wide=wide, endian=endian))
                        self.write("lib64/libc++.dylib", universal(wide=wide, endian=endian))
                        manifest = self.prepare(host)
                        self.assertEqual("arm64,x64", manifest["binaryArchitectures"])
                        self.assertNotIn("lib64/libc++.dylib", self.manifest_paths(manifest))
                        shutil.rmtree(self.output)

    def test_big_endian_thin_macho(self):
        self.fixture("macos-arm64", macho("arm64", ">"))
        self.assertEqual("arm64", self.prepare("macos-arm64")["binaryArchitectures"])

    def test_windows_wow64_x86_is_accepted_and_recorded(self):
        self.fixture("windows-x64", pe("x86"))
        self.assertEqual("x86", self.prepare("windows-x64")["binaryArchitectures"])
        self.assertEqual("x86", TOOLS.verify_bundle(self.output, "windows-x64")["binaryArchitectures"])

    def test_windows_dll_must_match_executable(self):
        for architecture in ("x86", "arm64"):
            with self.subTest(architecture=architecture):
                self.fixture("windows-x64")
                self.write("AdbWinUsbApi.dll", pe(architecture))
                self.assert_rejected("windows-x64")
                self.assertFalse(self.output.exists())

    def test_windows_arm_executable_is_rejected(self):
        self.fixture("windows-x64", pe("arm64"))
        self.assert_rejected("windows-x64", "Unsupported PE CPU")

    def test_linux_collects_all_shared_libraries(self):
        self.fixture("linux-arm64")
        names = ("libc++.so", "libc++.so.1", "libusb-1.0.so.0", "libcrypto.so.3", "libzstd.so.1")
        for name in names:
            self.write("lib64/" + name, elf("arm64"))
        self.write("lib64/ignored.a", "not selected")
        self.write("lib64/ignored.txt", "not selected")
        manifest = self.prepare("linux-arm64")
        self.assertEqual(TOOLS.required_paths("linux-arm64") | {"lib64/" + name for name in names},
                         self.manifest_paths(manifest))

    def test_macos_does_not_bundle_unneeded_sdk_libraries(self):
        self.fixture("macos-x64")
        self.write("lib64/libc++.dylib", macho("x64"))
        self.write("lib64/libother.so.1", elf())
        self.assertEqual({"adb", "NOTICE.txt"},
                         self.manifest_paths(self.prepare("macos-x64")))

    def test_all_cross_host_mismatches_are_rejected(self):
        cases = (("macos-x64", macho("arm64")), ("macos-arm64", macho("x64")),
                 ("linux-x64", elf("arm64")), ("linux-arm64", elf("x64")),
                 ("linux-x64", macho("x64")), ("macos-x64", elf("x64")),
                 ("windows-x64", elf("x64")))
        for host, image in cases:
            with self.subTest(host=host, image=image[:8]):
                self.fixture(host, image)
                self.assert_rejected(host, "host mismatch")
                self.assertFalse(self.output.exists())

    def test_companion_architecture_is_checked(self):
        self.fixture()
        self.write("lib64/libusb.so.1", elf("arm64"))
        self.assert_rejected(message="lib64/libusb.so.1")

    def test_malformed_or_spoofed_headers_are_rejected(self):
        wrong_class = bytearray(elf())
        wrong_class[4] = 1
        wrong_endian = bytearray(elf())
        wrong_endian[5] = 2
        wrong_machine = bytearray(pe())
        struct.pack_into("<H", wrong_machine, 64 + 24, 0x10B)
        spoofed_fat = bytearray(universal())
        struct.pack_into(">I", spoofed_fat, 8, 0x0100000C)
        outside_fat = bytearray(universal())
        struct.pack_into(">I", outside_fat, 8 + 8, 0xFFFFFF00)
        overlapping_fat = bytearray(universal())
        struct.pack_into(">I", overlapping_fat, 8 + 20 + 8, 48)
        cases = (("linux-x64", b"adb is only a filename"), ("linux-x64", bytes(wrong_class)),
                 ("linux-x64", bytes(wrong_endian)), ("linux-x64", elf()[:30]),
                 ("macos-x64", bytes(spoofed_fat)), ("macos-x64", bytes(outside_fat)),
                 ("macos-x64", bytes(overlapping_fat)), ("macos-x64", universal()[:-1]),
                 ("windows-x64", bytes(wrong_machine)), ("windows-x64", pe()[:100]))
        for host, image in cases:
            with self.subTest(host=host, image=image[:8]):
                self.fixture(host, image)
                self.assert_rejected(host)

    def test_every_required_file_is_required(self):
        for host in TOOLS.HOSTS:
            for relative in TOOLS.required_paths(host):
                with self.subTest(host=host, missing=relative):
                    self.fixture(host)
                    (self.source / relative).unlink()
                    self.assert_rejected(host)
                    self.assertFalse(self.output.exists())

    def test_version_metadata_or_explicit_version_is_required(self):
        self.fixture()
        (self.source / "source.properties").unlink()
        self.assert_rejected(message="--version")
        for version in ("1.2.3-ubuntu24.04", "1:34.0.4-1ubuntu2", "1:34.0.4-1ubuntu2~24.04"):
            with self.subTest(version=version):
                self.assertEqual(version, self.prepare(version=version)["version"])

    def test_version_conflicts_and_manifest_injection_are_rejected(self):
        self.fixture()
        self.assert_rejected(version="99.0", message="disagrees")
        (self.source / "source.properties").unlink()
        for version in ("", "unknown", "1.0\nhost=macos-x64", "1.0\\evil"):
            with self.subTest(version=version):
                self.assert_rejected(version=version, message="Invalid ADB version")

    def test_source_properties_do_not_override_host_or_manifest_fields(self):
        self.fixture()
        self.write("source.properties", "Pkg.Revision=37.0.1\nhost=macos-x64\nfile.count=99\n")
        manifest = self.prepare()
        self.assertEqual("linux-x64", manifest["host"])
        self.assertEqual("2", manifest["file.count"])

    def test_invalid_and_duplicate_source_revisions_are_rejected(self):
        self.fixture()
        for text in ("Pkg.Revision=not-a-version\n", "Pkg.Revision=1.0\nPkg.Revision=2.0\n",
                     "Pkg.Revision=1.0\\evil\n", "Pkg.Desc=no revision\n"):
            with self.subTest(text=text):
                self.write("source.properties", text)
                self.assert_rejected()

    def test_source_provenance_is_optional_and_java_escaped(self):
        self.fixture()
        url = "https://example.invalid/ubuntu/package.deb?arch=arm64&version=1:2#source"
        manifest = self.prepare(source_url=url)
        self.assertEqual(TOOLS.property_escape(url), manifest["source"])
        self.assertIn("source=" + url, (self.output / TOOLS.MANIFEST).read_text())
        self.assertEqual(manifest, TOOLS.verify_bundle(self.output, "linux-x64"))

    def test_source_provenance_cannot_inject_properties(self):
        self.fixture()
        for url in ("", "https://example.invalid\nhost=windows-x64", "bad\x00url"):
            with self.subTest(url=url):
                self.assert_rejected(source_url=url)
                self.assertFalse(self.output.exists())

    def test_reentry_is_noop_and_valid_owned_bundle_can_update(self):
        self.fixture()
        manifest = self.prepare()
        before = self.snapshot(self.output)
        modified = (self.output / TOOLS.MANIFEST).stat().st_mtime_ns
        self.assertEqual(manifest, self.prepare())
        self.assertEqual(modified, (self.output / TOOLS.MANIFEST).stat().st_mtime_ns)
        self.assertEqual(before, self.snapshot(self.output))
        self.write("source.properties", "Pkg.Revision=37.0.2\n")
        self.write("adb", elf() + b"new build")
        self.assertEqual("37.0.2", self.prepare()["version"])
        self.assertEqual(elf() + b"new build", (self.output / "adb").read_bytes())
        self.assertFalse(list(self.output.parent.glob(".adb-tools.prepare-*")))

    def test_existing_empty_directory_is_accepted(self):
        self.fixture()
        self.output.mkdir(parents=True)
        self.prepare()
        TOOLS.verify_bundle(self.output, "linux-x64")

    def test_unknown_and_corrupt_outputs_are_preserved(self):
        self.fixture()
        self.write("user-file.txt", "do not delete", self.output)
        before = self.snapshot(self.output)
        self.assert_rejected(message="manifest.properties")
        self.assertEqual(before, self.snapshot(self.output))
        (self.output / "user-file.txt").unlink()
        self.prepare()
        self.write("adb", elf() + b"tampered", self.output)
        before = self.snapshot(self.output)
        self.assert_rejected(message="SHA-256 mismatch")
        self.assertEqual(before, self.snapshot(self.output))

    def test_unknown_file_inside_valid_bundle_is_not_deleted(self):
        self.fixture()
        self.prepare()
        self.write("user-file.txt", "do not delete", self.output)
        before = self.snapshot(self.output)
        self.assert_rejected(message="unlisted")
        self.assertEqual(before, self.snapshot(self.output))

    def test_copy_failure_does_not_publish_partial_bundle(self):
        self.fixture()
        self.output.parent.mkdir(parents=True)
        with mock.patch.object(TOOLS.shutil, "copyfileobj", side_effect=OSError("injected copy failure")):
            self.assert_rejected(message="injected copy failure")
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.iterdir()))

    def test_failed_staging_verification_preserves_previous_bundle(self):
        self.fixture()
        self.prepare()
        before = self.snapshot(self.output)
        self.write("source.properties", "Pkg.Revision=37.0.2\n")
        real_copy = TOOLS.shutil.copyfileobj

        def corrupt_copy(incoming, outgoing):
            real_copy(incoming, outgoing)
            if Path(outgoing.name).name == "adb":
                outgoing.seek(0)
                outgoing.write(b"BAD!")

        with mock.patch.object(TOOLS.shutil, "copyfileobj", side_effect=corrupt_copy):
            self.assert_rejected(message="Unrecognized binary")
        self.assertEqual(before, self.snapshot(self.output))
        self.assertFalse(list(self.output.parent.glob(".adb-tools.prepare-*")))

    def test_publication_failure_rolls_back_owned_bundle(self):
        self.fixture()
        self.prepare()
        before = self.snapshot(self.output)
        self.write("source.properties", "Pkg.Revision=37.0.2\n")
        rename = Path.rename

        def fail_staging(path, target):
            if path.name == "bundle":
                raise OSError("injected publication failure")
            return rename(path, target)

        with mock.patch.object(Path, "rename", fail_staging):
            self.assert_rejected(message="injected publication failure")
        self.assertEqual(before, self.snapshot(self.output))
        self.assertFalse(list(self.output.parent.glob(".adb-tools.prepare-*")))

    def test_verify_only_needs_no_source_and_does_not_write(self):
        self.fixture()
        self.prepare()
        before = self.snapshot(self.output)
        shutil.rmtree(self.source)
        with mock.patch.object(TOOLS.tempfile, "mkdtemp", side_effect=AssertionError("must not write")):
            manifest = TOOLS.prepare(None, "linux-x64", self.output, verify_only=True)
        self.assertEqual("linux-x64", manifest["host"])
        self.assertEqual(before, self.snapshot(self.output))

    def test_verify_checks_host_version_and_architectures(self):
        self.fixture()
        self.prepare()
        with self.assertRaisesRegex(TOOLS.BundleError, "host mismatch"):
            TOOLS.verify_bundle(self.output, "linux-arm64")
        with self.assertRaisesRegex(TOOLS.BundleError, "version mismatch"):
            TOOLS.verify_bundle(self.output, "linux-x64", "1.0")
        self.replace_manifest(lambda text: text.replace("binaryArchitectures=x64", "binaryArchitectures=arm64"))
        with self.assertRaisesRegex(TOOLS.BundleError, "binaryArchitectures mismatch"):
            TOOLS.verify_bundle(self.output, "linux-x64")

    def test_verify_detects_tampering_and_missing_files(self):
        self.fixture()
        self.write("lib64/libusb.so.1", elf())
        self.prepare()
        for relative in ("adb", "NOTICE.txt", "lib64/libusb.so.1"):
            with self.subTest(relative=relative):
                path = self.output / relative
                original = path.read_bytes()
                path.write_bytes(original + b"tampered")
                with self.assertRaisesRegex(TOOLS.BundleError, "SHA-256 mismatch"):
                    TOOLS.verify_bundle(self.output, "linux-x64")
                path.unlink()
                with self.assertRaises((TOOLS.BundleError, OSError)):
                    TOOLS.verify_bundle(self.output, "linux-x64")
                path.write_bytes(original)
                path.chmod(0o755 if relative == "adb" else 0o644)

    def test_manifest_path_traversal_and_reserved_paths_are_rejected(self):
        self.fixture()
        self.prepare()
        original = (self.output / TOOLS.MANIFEST).read_text()
        for path in ("../outside", "/tmp/outside", "lib64/../../outside", "C:/outside", "..\\outside",
                     "./NOTICE.txt", "lib64//libc++.so", "manifest.properties"):
            with self.subTest(path=path):
                (self.output / TOOLS.MANIFEST).write_text(original.replace("file.0.path=NOTICE.txt", "file.0.path=" + path))
                with self.assertRaises(TOOLS.BundleError):
                    TOOLS.verify_bundle(self.output, "linux-x64")

    def test_invalid_manifest_fields_and_duplicate_paths_are_rejected(self):
        self.fixture()
        self.prepare()
        original = (self.output / TOOLS.MANIFEST).read_text()
        for text in (original.replace("schemaVersion=1", "schemaVersion=2"),
                     original.replace("file.count=2", "file.count=65"),
                     original.replace("file.count=2", "file.count=0"),
                     original.replace("file.1.path=adb", "file.1.path=NOTICE.txt"),
                     original + "host=linux-x64\n",
                     original + "file.99.path=unknown\n"):
            with self.subTest(text=text):
                (self.output / TOOLS.MANIFEST).write_text(text)
                with self.assertRaises(TOOLS.BundleError):
                    TOOLS.verify_bundle(self.output, "linux-x64")

    def symlink(self, target, link):
        link.parent.mkdir(parents=True, exist_ok=True)
        try:
            link.symlink_to(target, target_is_directory=target.is_dir())
        except (OSError, NotImplementedError):
            self.skipTest("Symlinks are not available on this host")

    def test_internal_source_library_symlinks_are_materialized(self):
        self.fixture()
        library = self.write("lib64/libusb.so.1", elf())
        self.symlink(library, self.source / "lib64/libusb.so")
        self.prepare()
        self.assertFalse((self.output / "lib64/libusb.so").is_symlink())
        self.assertEqual(elf(), (self.output / "lib64/libusb.so").read_bytes())

    def test_source_file_symlinks_cannot_escape_root(self):
        for relative in ("adb", "NOTICE.txt", "source.properties", "lib64/libusb.so.1"):
            with self.subTest(relative=relative):
                self.fixture()
                target = self.write("outside", elf(), self.root)
                path = self.source / relative
                if path.exists():
                    path.unlink()
                self.symlink(target, path)
                self.assert_rejected(message="escapes root")
                self.assertEqual(elf(), target.read_bytes())
                path.unlink()

    def test_source_library_directory_symlink_cannot_escape_root(self):
        self.fixture()
        outside = self.root / "outside-libraries"
        self.write("libusb.so.1", elf(), outside)
        self.symlink(outside, self.source / "lib64")
        self.assert_rejected(message="escapes root")

    def test_dangling_source_symlink_is_rejected(self):
        self.fixture()
        self.symlink(self.root / "missing", self.source / "lib64/libusb.so.1")
        self.assert_rejected()

    def test_output_root_and_nested_symlinks_are_rejected(self):
        self.fixture()
        outside = self.root / "outside"
        outside.mkdir()
        self.symlink(outside, self.output)
        self.assert_rejected(message="symlink")
        self.assertEqual([], list(outside.iterdir()))
        self.output.unlink()
        self.prepare()
        (self.output / "NOTICE.txt").unlink()
        notice = self.write("NOTICE.txt", "outside notices", outside)
        self.symlink(notice, self.output / "NOTICE.txt")
        with self.assertRaisesRegex(TOOLS.BundleError, "Symlink"):
            TOOLS.verify_bundle(self.output, "linux-x64")
        self.assert_rejected()
        self.assertEqual("outside notices", notice.read_text())

    def test_output_library_directory_symlink_is_rejected(self):
        self.fixture()
        self.prepare()
        outside = self.root / "outside"
        self.write("libusb.so.1", elf(), outside)
        self.symlink(outside, self.output / "lib64")
        self.assert_rejected(message="Symlink")
        self.assertTrue((outside / "libusb.so.1").is_file())

    def test_source_output_overlap_and_resolved_aliases_are_rejected(self):
        self.fixture()
        for output in (self.source, self.source / "bundle", self.source.parent):
            with self.subTest(output=output):
                with self.assertRaisesRegex(TOOLS.BundleError, "overlap"):
                    TOOLS.prepare(self.source, "linux-x64", output)
        alias = self.root / "source-alias"
        self.symlink(self.source, alias)
        with self.assertRaisesRegex(TOOLS.BundleError, "overlap"):
            TOOLS.prepare(alias, "linux-x64", self.source / "bundle")

    def test_manifest_runtime_file_limit_is_enforced(self):
        self.fixture()
        for index in range(63):
            self.write("lib64/libfixture%d.so" % index, elf())
        self.assert_rejected(message="Too many bundle files")

    def test_cli_build_and_verify_without_source_with_spaces(self):
        self.fixture()
        environment = dict(os.environ, PATH="")
        build = subprocess.run(
            [sys.executable, str(SCRIPT), "--source-dir", str(self.source), "--host", "linux-x64",
             "--output-dir", str(self.output), "--source-url", "https://example.invalid/adb.deb"],
            capture_output=True, text=True, env=environment, timeout=15,
        )
        self.assertEqual(0, build.returncode, build.stderr)
        shutil.rmtree(self.source)
        verify = subprocess.run(
            [sys.executable, str(SCRIPT), "--verify-only", "--host", "linux-x64", "--output-dir", str(self.output)],
            capture_output=True, text=True, env=environment, timeout=15,
        )
        self.assertEqual(0, verify.returncode, verify.stderr)
        self.assertIn("Verified ADB bundle", verify.stdout)

    def test_cli_requires_source_for_build_and_diagnoses_invalid_bundle(self):
        for args, code, diagnostic in (
            (["--host", "linux-x64", "--output-dir", str(self.output)], 2, "--source-dir is required"),
            (["--verify-only", "--host", "linux-x64", "--output-dir", str(self.output)], 1, "error:"),
        ):
            with self.subTest(args=args):
                error = io.StringIO()
                with contextlib.redirect_stderr(error):
                    try:
                        result = TOOLS.main(args)
                    except SystemExit as exit_error:
                        result = exit_error.code
                self.assertEqual(code, result)
                self.assertIn(diagnostic, error.getvalue())
                self.assertNotIn("Traceback", error.getvalue())


if __name__ == "__main__":
    unittest.main()
