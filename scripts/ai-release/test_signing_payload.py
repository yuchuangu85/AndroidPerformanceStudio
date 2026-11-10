"""Signing transform fixtures. All native tools/keychains/notary operations are mocked."""

from contextlib import nullcontext
import json
import os
from pathlib import Path
import plistlib
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import macos_payload as payload
from qualification import GateError
import sign

MACHO = b"\xcf\xfa\xed\xfe\x00\x00\x00\x00fixture-native"
TEAM = "ABC1234567"
ENVIRONMENT = {"APS_MACOS_SIGNING_IDENTITY": "Developer ID Application: Fixture (" + TEAM + ")",
               "APS_MACOS_INSTALLER_IDENTITY": "Developer ID Installer: Fixture (" + TEAM + ")",
               "APPLE_ID": "fixture@example.invalid", "APPLE_TEAM_ID": TEAM,
               "APPLE_APP_SPECIFIC_PASSWORD": "fixture-not-a-real-password"}


class PayloadFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aps-payload-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.app = self.root / "Fixture.app"
        self.write(self.app / "Contents/Info.plist", plistlib.dumps({"CFBundleShortVersionString": "1.2.3",
                   "CFBundleExecutable": "Fixture", "CFBundlePackageType": "APPL"}))
        self.write(self.app / "Contents/MacOS/Fixture", MACHO)
        self.runtime = self.app / "Contents/runtime"
        self.write(self.runtime / "Contents/Info.plist", plistlib.dumps({"CFBundlePackageType": "BNDL",
                   "CFBundleExecutable": "libjli.dylib"}))
        self.write(self.runtime / "Contents/MacOS/libjli.dylib", MACHO + b"runtime")
        self.resources = self.app / "Contents/app/resources"
        self.tp = self.write(self.resources / "perfetto-tools/trace_processor_shell", MACHO + b"trace-processor")
        self.pinned = self.write(self.root / "pinned.json", json.dumps({"version": "v59.1", "artifacts": {
            "macos-arm64": {"sha256": payload.sha256_file(self.tp)}, "macos-x64": {"sha256": "f" * 64}}}).encode())
        self.calls = []

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def staged(self, path):
        return self.copied / path.relative_to(self.app)

    def signing(self, on_file=None, on_bundle=None):
        directory = self.root / "signing"
        directory.mkdir()
        self.copied = directory / "stage" / self.app.name
        self.calls = []

        def command(args):
            args = [str(arg) for arg in args]
            self.calls.append(args)
            if args[0] == "/usr/bin/ditto" and len(args) == 3:
                shutil.copytree(args[1], args[2], symlinks=True)
            elif args[0] == "/usr/bin/security":
                return '"' + ENVIRONMENT["APS_MACOS_SIGNING_IDENTITY"] + '"'
            elif args[0] == "/usr/sbin/pkgutil":
                return ENVIRONMENT["APS_MACOS_INSTALLER_IDENTITY"]
            elif args[0] == "/usr/bin/codesign" and "--sign" in args:
                path = Path(args[-1])
                if path.is_file():
                    path.write_bytes(path.read_bytes() + b"|signed")
                    if on_file:
                        on_file(path)
                elif path.is_dir():
                    if on_bundle:
                        on_bundle(path)
                    if path == self.copied:
                        runtime = self.staged(self.runtime)
                        sealed = json.loads((runtime / "Contents/_CodeSignature/CodeResources").read_text())
                        self.assertEqual(payload.sha256_file(runtime / "Contents/MacOS/libjli.dylib"),
                                         sealed["Contents/MacOS/libjli.dylib"], "Outer signing must not see an invalid runtime seal")
                    sealed = {item.relative_to(path).as_posix(): payload.sha256_file(item) for item in path.rglob("*")
                              if item.is_file() and "_CodeSignature" not in item.relative_to(path).parts}
                    self.write(path / "Contents/_CodeSignature/CodeResources", json.dumps(sealed, sort_keys=True).encode())
            return ""

        with patch.object(sign.platform, "system", return_value="Darwin"), patch.dict(os.environ, ENVIRONMENT), \
                patch.object(payload, "PINNED_TP_MANIFEST", self.pinned), \
                patch.object(sign, "temporary_keychain", return_value=nullcontext(directory / "fixture.keychain")) as keychain, \
                patch.object(sign, "command", side_effect=command), patch.object(sign, "notarize"), \
                patch.object(sign.subprocess, "run", side_effect=AssertionError("Native invocation is forbidden")):
            sign.sign_macos(self.app, self.root / "output", "1.2.3", "arm64", directory)
        return keychain

    def signatures(self):
        return [Path(args[-1]) for args in self.calls if args[0] == "/usr/bin/codesign" and "--sign" in args]

    def test_extensionless_runtime_bundle_is_signed_after_its_machos_and_before_app(self):
        self.signing()
        signatures = self.signatures()
        runtime = self.staged(self.runtime)
        self.assertLess(signatures.index(runtime / "Contents/MacOS/libjli.dylib"), signatures.index(runtime))
        self.assertLess(signatures.index(runtime), signatures.index(self.copied))
        self.assertTrue((runtime / "Contents/_CodeSignature/CodeResources").is_file())
        self.assertTrue(any(args[1:5] == ["--verify", "--deep", "--strict", str(runtime)] for args in self.calls))

    def test_exact_tp_inventory_are_refreshed_before_any_enclosing_seal(self):
        before_tp = payload.sha256_file(self.tp)
        pinned_bytes = self.pinned.read_bytes()
        seen = []

        def on_bundle(path):
            signed_tp = self.staged(self.tp)
            data = json.loads((signed_tp.parent / payload.SIGNED_TP_MANIFEST).read_text())
            self.assertEqual({"schemaVersion": 1, "upstreamVersion": "v59.1", "binary": "trace_processor_shell",
                              "upstreamSha256": before_tp, "signedSha256": payload.sha256_file(signed_tp), "teamId": TEAM}, data)
            self.assertNotEqual(before_tp, data["signedSha256"])
            seen.append(path)

        self.signing(on_bundle=on_bundle)
        self.assertIn(self.copied, seen)
        self.assertEqual(pinned_bytes, self.pinned.read_bytes())
        self.assertFalse((self.tp.parent / payload.SIGNED_TP_MANIFEST).exists(), "Unsigned development app is untouched")

    def test_bad_upstream_tp_cannot_be_blessed_by_signing(self):
        for binary in (self.tp,):
            with self.subTest(binary=binary.name):
                original = binary.read_bytes()
                binary.write_bytes(original + b"tampered")
                with patch.object(payload, "PINNED_TP_MANIFEST", self.pinned):
                    with self.assertRaisesRegex(GateError, "provenance"):
                        payload.tool_inventories(self.app, "arm64")
                self.assertEqual(original + b"tampered", binary.read_bytes())
                binary.write_bytes(original)

    def test_changed_metadata_during_signing_never_replaces_provenance_or_seals_app(self):
        def tamper(path):
            if path == self.staged(self.tp):
                self.pinned.write_text("{}")

        with self.assertRaisesRegex(GateError, "provenance changed"):
            self.signing(on_file=tamper)
        self.assertNotIn(self.copied, self.signatures())
        self.assertFalse((self.staged(self.tp).parent / payload.SIGNED_TP_MANIFEST).exists())

    def test_changed_signed_binary_cannot_be_recorded_as_verified_bytes(self):
        def tamper(path):
            if path == self.copied / "Contents/MacOS/Fixture":
                self.staged(self.tp).write_bytes(b"unverified replacement")

        with self.assertRaisesRegex(GateError, "verified signed bytes"):
            self.signing(on_file=tamper)
        self.assertNotIn(self.copied, self.signatures())
        self.assertFalse((self.staged(self.tp).parent / payload.SIGNED_TP_MANIFEST).exists())

    def test_inventory_symlinks_and_already_transformed_inputs_fail_closed(self):
        sidecar = self.tp.parent / payload.SIGNED_TP_MANIFEST
        sidecar.write_text("untrusted")
        with patch.object(payload, "PINNED_TP_MANIFEST", self.pinned):
            with self.assertRaises(GateError):
                payload.tool_inventories(self.app, "arm64")
        sidecar.unlink()
        is_symlink = Path.is_symlink
        # This is a contract fixture, not a request for Windows symlink-creation privilege.
        with patch.object(Path, "is_symlink", lambda path: path == self.tp or is_symlink(path)), \
                patch.object(payload, "PINNED_TP_MANIFEST", self.pinned):
            with self.assertRaises(GateError):
                payload.tool_inventories(self.app, "arm64")

    def test_missing_runtime_metadata_is_not_silently_skipped(self):
        (self.runtime / "Contents/Info.plist").unlink()
        with self.assertRaisesRegex(GateError, "runtime"):
            payload.signing_targets(self.app)

    def test_nonobject_runtime_plist_is_rejected_before_any_signature(self):
        (self.runtime / "Contents/Info.plist").write_bytes(plistlib.dumps(["BNDL"]))
        with self.assertRaisesRegex(GateError, "metadata object"):
            payload.signing_targets(self.app)

    def native_jar(self):
        jar = self.app / "Contents/app/sqlite-jdbc.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        entries = {"org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib": MACHO + b"arm64",
                   "org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib": MACHO + b"x64",
                   "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so": b"\x7fELF-preserved",
                   "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
                   "org/sqlite/JDBC.class": b"\xca\xfe\xba\xbe\x00\x00\x00\x41java-class"}
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.comment = b"fixture jar comment"
            for name, data in entries.items():
                archive.writestr(name, data)
        return jar, entries

    def test_jar_native_members_are_signed_and_repacked_before_root_seal(self):
        jar, entries = self.native_jar()
        original = jar.read_bytes()

        def on_bundle(path):
            with zipfile.ZipFile(self.staged(jar)) as archive:
                self.assertEqual(list(entries), archive.namelist())
                self.assertEqual(b"fixture jar comment", archive.comment)
                self.assertIsNone(archive.testzip())
                for name, data in entries.items():
                    self.assertEqual(data + (b"|signed" if name.endswith(".dylib") else b""), archive.read(name))

        self.signing(on_bundle=on_bundle)
        extracted = [path for path in self.signatures() if "jar-native-" in str(path)]
        self.assertEqual(2, len(extracted))
        self.assertNotEqual(extracted[0], extracted[1], "Same basename members must use distinct temporary files")
        self.assertTrue(all(not path.exists() for path in extracted))
        jar_signatures = [args for args in self.calls if args[0] == "/usr/bin/codesign" and "--sign" in args and Path(args[-1]) in extracted]
        self.assertTrue(all("--entitlements" not in args for args in jar_signatures), "Compose-style library signing does not add executable JIT entitlements")
        self.assertEqual(original, jar.read_bytes())
        entitlements = plistlib.loads((sign.HERE / "macos-entitlements.plist").read_bytes())
        self.assertNotIn("com.apple.security.cs.disable-library-validation", entitlements)

    def test_failed_jar_native_signature_does_not_replace_archive(self):
        jar, _ = self.native_jar()
        original = jar.read_bytes()
        plans = payload.jar_plans(self.app)

        def fail(_path):
            raise GateError("fixture signature failure")

        with self.assertRaises(GateError):
            payload.sign_jar_natives(plans, self.root, fail, lambda _: None)
        self.assertEqual(original, jar.read_bytes())
        self.assertFalse(list(self.root.glob("jar-native-*")))

    def test_unsafe_duplicate_symlink_and_signed_native_jars_are_rejected(self):
        jar, _ = self.native_jar()
        for extra in ("../../escape.dylib", "/escape.dylib", "META-INF/fixture.SF"):
            self.native_jar()
            with zipfile.ZipFile(jar, "a") as archive:
                archive.writestr(extra, MACHO)
            with self.assertRaises(GateError):
                payload.jar_plans(self.app)
        self.native_jar()
        with zipfile.ZipFile(jar, "a") as archive:
            entry = zipfile.ZipInfo("link.dylib")
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, b"outside")
        with self.assertRaises(GateError):
            payload.jar_plans(self.app)

    def test_hashing_supports_system_python_without_file_digest(self):
        with patch.object(sign.hashlib, "file_digest", None, create=True):
            self.assertEqual(payload.sha256_file(self.tp), json.loads(self.pinned.read_text())["artifacts"]["macos-arm64"]["sha256"])
