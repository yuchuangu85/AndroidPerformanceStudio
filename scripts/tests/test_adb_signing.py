"""ADB manifest signing fixtures; never invoke native signing or keychain tools."""

from contextlib import nullcontext
import hashlib
import importlib.util
import os
from pathlib import Path
import plistlib
import shutil
import sys
import tempfile
import unittest
from unittest import mock


RELEASE = Path(__file__).resolve().parents[1] / "ai-release"
with mock.patch.object(sys, "path", [str(RELEASE), *sys.path]):
    SPEC = importlib.util.spec_from_file_location("adb_signing", RELEASE / "sign.py")
    SIGN = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(SIGN)

MACHO = b"\xcf\xfa\xed\xfe" + b"synthetic native payload"
ENVIRONMENT = {
    "APS_MACOS_SIGNING_IDENTITY": "Developer ID Application: Fixture (ABC1234567)",
    "APS_MACOS_INSTALLER_IDENTITY": "Developer ID Installer: Fixture (ABC1234567)",
    "APPLE_ID": "fixture@example.invalid",
    "APPLE_TEAM_ID": "ABC1234567",
    "APPLE_APP_SPECIFIC_PASSWORD": "fixture-only",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class AdbSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="adb signing fixtures ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.app = self.root / "Application With Spaces.app"
        self.write(self.app / "Contents/MacOS/launcher", MACHO)
        self.write(self.app / "Contents/Info.plist", plistlib.dumps({
            "CFBundleShortVersionString": "1.2.3", "CFBundleExecutable": "launcher",
        }))
        self.bundle = self.app / "Contents/app/resources/adb-tools"
        self.manifest = self.bundle / "manifest.properties"
        self.calls = []
        self.signed = []
        self.runs = 0

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def fixture(self, bundle=None, native=True):
        bundle = bundle or self.bundle
        files = ("NOTICE.txt", "adb", "lib64/libc++.dylib")
        for relative in files:
            data = MACHO + relative.encode("ascii") if native and relative != "NOTICE.txt" else b"unchanged text\n"
            self.write(bundle / relative, data)
        text = ("# Keep metadata and formatting\r\n"
                "schemaVersion=1\r\nversion=37.0.1\r\nhost=macos-arm64\r\n"
                "binaryArchitectures=arm64,x64\r\nsource=https://example.invalid/a?x=1&y=2\r\n"
                "file.count=3\r\n")
        for index, relative in enumerate(files):
            text += "file.%d.path=%s\r\nfile.%d.sha256 : %s\r\n" % (
                index, relative, index, digest(bundle / relative))
        return self.write(bundle / "manifest.properties", text.encode("ascii")).read_bytes()

    def run_signing(self, on_nested=None, on_root=None):
        self.runs += 1
        directory = self.root / ("signing-%d" % self.runs)
        directory.mkdir()
        self.copied = directory / "stage" / self.app.name
        self.calls = []
        self.signed = []

        def command(args):
            self.calls.append(args)
            tool = str(args[0])
            if tool == "/usr/bin/ditto" and len(args) == 3:
                shutil.copytree(args[1], args[2], symlinks=True)
            elif tool == "/usr/bin/security":
                return '"' + ENVIRONMENT["APS_MACOS_SIGNING_IDENTITY"] + '"'
            elif tool == "/usr/sbin/pkgutil":
                return ENVIRONMENT["APS_MACOS_INSTALLER_IDENTITY"]
            elif tool == "/usr/bin/codesign" and "--sign" in args:
                path = Path(args[-1])
                if path == self.copied:
                    if on_root:
                        on_root(path)
                elif path.is_file():
                    path.write_bytes(path.read_bytes() + b"fake signature")
                    if on_nested:
                        on_nested(path)
                self.signed.append(path)
            return ""

        with mock.patch.object(SIGN.platform, "system", return_value="Darwin"), \
                mock.patch.dict(os.environ, ENVIRONMENT), \
                mock.patch.object(SIGN, "temporary_keychain", return_value=nullcontext(directory / "fake.keychain")), \
                mock.patch.object(SIGN, "notarize"), \
                mock.patch.object(SIGN, "command", side_effect=command), \
                mock.patch.object(SIGN.subprocess, "run", side_effect=AssertionError("Native tool invocation")):
            SIGN.sign_macos(self.app, self.root / "output", "1.2.3", "arm64", directory)

    def staged(self, path):
        return self.copied / path.relative_to(self.app)

    def assert_rejected_before_signing(self):
        with self.assertRaises(SIGN.GateError):
            self.run_signing()
        self.assertEqual([], self.signed)

    def test_refreshes_only_signed_adb_hashes_before_root_signature(self):
        before = self.fixture()
        other_native = self.write(self.app / "Contents/app/resources/other/native", MACHO)
        other_metadata = b"file.0.sha256=" + digest(other_native).encode("ascii") + b"\n"
        other_manifest = self.write(self.app / "Contents/app/resources/other/manifest.properties", other_metadata)
        expected = before
        root_snapshots = []

        def on_nested(path):
            self.assertEqual(before, self.staged(self.manifest).read_bytes())

        def on_root(path):
            nonlocal expected
            for relative in ("adb", "lib64/libc++.dylib"):
                old = digest(self.bundle / relative).encode("ascii")
                new = digest(self.staged(self.bundle / relative)).encode("ascii")
                expected = expected.replace(old, new)
            self.assertEqual(expected, self.staged(self.manifest).read_bytes())
            self.assertEqual(other_metadata, self.staged(other_manifest).read_bytes())
            self.assertIn(self.staged(other_native), self.signed)
            root_snapshots.append(path)

        self.run_signing(on_nested, on_root)
        self.assertEqual([self.copied], root_snapshots)
        self.assertNotEqual(before, expected)
        self.assertEqual(before, self.manifest.read_bytes(), "Never mutate the input app")
        self.assertEqual(expected, self.staged(self.manifest).read_bytes())
        self.assertEqual(digest(self.bundle / "NOTICE.txt"), digest(self.staged(self.bundle / "NOTICE.txt")))

    def test_legacy_app_without_adb_bundle_keeps_existing_behavior(self):
        self.run_signing()
        self.assertIn(self.copied, self.signed)
        self.assertIn(self.copied / "Contents/MacOS/launcher", self.signed)

    def test_adb_directory_without_manifest_is_rejected(self):
        self.bundle.mkdir(parents=True)
        self.assert_rejected_before_signing()

    def test_original_hashes_are_checked_for_native_and_unsigned_files(self):
        self.fixture()
        for relative in ("adb", "NOTICE.txt", "lib64/libc++.dylib"):
            with self.subTest(relative=relative):
                path = self.bundle / relative
                before = path.read_bytes()
                path.write_bytes(before + b"tampered")
                self.assert_rejected_before_signing()
                path.write_bytes(before)

    def test_all_listed_files_must_exist(self):
        self.fixture()
        for relative in ("adb", "NOTICE.txt", "lib64/libc++.dylib"):
            with self.subTest(relative=relative):
                path = self.bundle / relative
                before = path.read_bytes()
                path.unlink()
                self.assert_rejected_before_signing()
                path.write_bytes(before)

    def test_malformed_manifest_is_rejected(self):
        before = self.fixture()
        for old, new in (
            (b"schemaVersion=1", b"schemaVersion=2"),
            (b"version=37.0.1", b"version="),
            (b"host=macos-arm64", b"host=macos-x64"),
            (b"file.count=3", b"file.count=0"),
            (b"file.count=3", b"file.count=65"),
            (b"file.count=3", b"file.count=2"),
            (b"file.count=3", b"file.count=4"),
            (b"file.1.path=adb", b"file.1.path=NOTICE.txt"),
            (b"file.1.path=adb", b"file.1.wrong=adb"),
            (b"file.1.sha256 : ", b"file.1.sha256 : invalid"),
            (b"schemaVersion=1", b"schemaVersion=1\nschemaVersion=1"),
            (b"schemaVersion=1", b"[other]\nschemaVersion=1"),
        ):
            with self.subTest(replacement=new):
                self.manifest.write_bytes(before.replace(old, new))
                self.assert_rejected_before_signing()

    def test_manifest_paths_cannot_escape_adb_bundle(self):
        before = self.fixture()
        payload = (self.bundle / "adb").read_bytes()
        outside = self.write(self.root / "outside", payload)
        for relative in (str(outside), "../outside", "lib64/../../outside", "../other/native", "C:/outside", "lib64\\outside", "."):
            with self.subTest(relative=relative):
                self.manifest.write_bytes(before.replace(b"file.1.path=adb", ("file.1.path=" + relative).encode("ascii")))
                self.assert_rejected_before_signing()
                self.assertEqual(payload, outside.read_bytes())

    def test_external_file_and_directory_symlinks_are_rejected(self):
        self.fixture()
        outside = self.root / "outside"
        outside.mkdir()
        payload = (self.bundle / "adb").read_bytes()
        self.write(outside / "adb", payload)
        self.write(outside / "libc++.dylib", (self.bundle / "lib64/libc++.dylib").read_bytes())
        for relative, target in (("adb", outside / "adb"), ("lib64", outside)):
            with self.subTest(relative=relative):
                path = self.bundle / relative
                backup = self.root / (relative + ".backup")
                path.rename(backup)
                path.symlink_to(target, target_is_directory=target.is_dir())
                self.assert_rejected_before_signing()
                path.unlink()
                backup.rename(path)
        self.assertEqual(payload, (outside / "adb").read_bytes())

    def test_external_bundle_and_resources_symlinks_are_rejected(self):
        self.fixture()
        for path in (self.bundle, self.bundle.parent):
            with self.subTest(path=path.name):
                outside = self.root / (path.name + ".outside")
                path.rename(outside)
                path.symlink_to(outside, target_is_directory=True)
                self.assert_rejected_before_signing()
                path.unlink()
                outside.rename(path)

    def test_manifest_symlink_cannot_write_outside_bundle(self):
        before = self.fixture()
        outside = self.write(self.root / "outside.properties", before)
        self.manifest.unlink()
        self.manifest.symlink_to(outside)
        self.assert_rejected_before_signing()
        self.assertEqual(before, outside.read_bytes())

    def test_unsigned_file_tampering_after_nested_signing_is_not_blessed(self):
        before = self.fixture()

        def tamper(path):
            self.staged(self.bundle / "NOTICE.txt").write_bytes(b"tampered during signing")

        with self.assertRaises(SIGN.GateError):
            self.run_signing(on_nested=tamper)
        self.assertNotIn(self.copied, self.signed)
        self.assertEqual(before, self.staged(self.manifest).read_bytes())

    def test_manifest_tampering_after_nested_signing_is_rejected(self):
        self.fixture()

        def tamper(path):
            self.staged(self.manifest).write_bytes(b"replacement manifest")

        with self.assertRaises(SIGN.GateError):
            self.run_signing(on_nested=tamper)
        self.assertNotIn(self.copied, self.signed)

    def test_signed_and_unsigned_files_must_still_exist_after_nested_signing(self):
        before = self.fixture()
        for relative in ("adb", "NOTICE.txt"):
            with self.subTest(relative=relative):
                def remove(path):
                    if path == self.staged(self.bundle / "adb"):
                        self.staged(self.bundle / relative).unlink()

                with self.assertRaises(SIGN.GateError):
                    self.run_signing(on_nested=remove)
                self.assertNotIn(self.copied, self.signed)
                self.assertEqual(before, self.staged(self.manifest).read_bytes())

    def test_new_external_symlink_after_nested_signing_is_rejected(self):
        before = self.fixture()
        outside = self.write(self.root / "external-adb", (self.bundle / "adb").read_bytes())
        external_before = outside.read_bytes()

        def replace(path):
            if path == self.staged(self.bundle / "adb"):
                path.unlink()
                path.symlink_to(outside)

        with self.assertRaises(SIGN.GateError):
            self.run_signing(on_nested=replace)
        self.assertNotIn(self.copied, self.signed)
        self.assertEqual(before, self.staged(self.manifest).read_bytes())
        self.assertEqual(external_before, outside.read_bytes())

    def test_unlisted_files_are_rejected(self):
        self.fixture()
        self.write(self.bundle / "unlisted", MACHO)
        self.assert_rejected_before_signing()

    def test_simple_manifest_without_optional_metadata_and_final_newline(self):
        before = self.fixture()
        before = b"\n".join(line.replace(b".sha256 : ", b".sha256=") for line in before.splitlines()
                            if not line.startswith((b"binaryArchitectures=", b"source=")))
        self.manifest.write_bytes(before)
        self.run_signing()
        expected = before
        for relative in ("adb", "lib64/libc++.dylib"):
            expected = expected.replace(digest(self.bundle / relative).encode("ascii"),
                                        digest(self.staged(self.bundle / relative)).encode("ascii"))
        self.assertEqual(expected, self.staged(self.manifest).read_bytes())

    def test_failed_nested_signature_does_not_refresh_manifest_or_sign_root(self):
        before = self.fixture()

        def fail(path):
            raise SIGN.GateError("Synthetic signing failure")

        with self.assertRaises(SIGN.GateError):
            self.run_signing(on_nested=fail)
        self.assertNotIn(self.copied, self.signed)
        self.assertEqual(before, self.staged(self.manifest).read_bytes())

    def test_unsigned_adb_files_do_not_trigger_manifest_rewrite(self):
        before = self.fixture(native=False)
        self.run_signing()
        self.assertEqual(before, self.staged(self.manifest).read_bytes())

    def test_every_embedded_adb_bundle_is_validated_before_any_signature(self):
        self.fixture()
        second = self.app / "Contents/PlugIns/Helper.app/Contents/app/resources/adb-tools"
        self.fixture(second)
        (second / "NOTICE.txt").write_bytes(b"tampered")
        self.assert_rejected_before_signing()


if __name__ == "__main__":
    unittest.main(verbosity=2)
