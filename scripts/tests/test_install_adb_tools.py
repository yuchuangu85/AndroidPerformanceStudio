import hashlib
import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location("install_adb", Path(__file__).parents[1] / "install-adb-tools.py")
INSTALL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSTALL)


class InstallAdbToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="adb download with spaces ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def archive(self, entries):
        archive = self.root / "tools.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for path, content in entries.items():
                output.writestr(path, content)
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def test_verified_archive_preserves_directory_structure(self):
        archive, checksum = self.archive({"platform-tools/adb": b"binary", "platform-tools/lib64/library.so": b"library"})
        source = INSTALL.extract_archive(archive, checksum, self.root / "out")
        self.assertEqual(b"binary", (source / "adb").read_bytes())
        self.assertEqual(b"library", (source / "lib64/library.so").read_bytes())

    def test_wrong_checksum_does_not_extract_files(self):
        archive, _ = self.archive({"platform-tools/adb": b"binary"})
        output = self.root / "out"
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            INSTALL.extract_archive(archive, "0" * 64, output)
        self.assertFalse(output.exists())

    def test_parent_traversal_and_absolute_paths_are_rejected(self):
        for path in ("platform-tools/../../outside", "/platform-tools/adb", "platform-tools\\..\\outside"):
            with self.subTest(path=path):
                archive, checksum = self.archive({path: b"binary"})
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    INSTALL.extract_archive(archive, checksum, self.root / "out")
        self.assertFalse((self.root / "outside").exists())

    def test_zip_symlink_is_rejected(self):
        archive = self.root / "link.zip"
        with zipfile.ZipFile(archive, "w") as output:
            entry = zipfile.ZipInfo("platform-tools/adb")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            output.writestr(entry, "../../outside")
        with self.assertRaisesRegex(ValueError, "Symbolic links"):
            INSTALL.extract_archive(archive, INSTALL.sha256(archive), self.root / "out")

    def test_revision_is_read_as_structured_properties(self):
        (self.root / "source.properties").write_text("Pkg.Desc = Android tools\nPkg.Revision=37.0.1\n", encoding="utf-8")
        self.assertEqual("37.0.1", INSTALL.source_version(self.root))

    def test_duplicate_revision_is_rejected(self):
        (self.root / "source.properties").write_text("Pkg.Revision=37.0.1\nPkg.Revision=1\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            INSTALL.source_version(self.root)

    def test_manifest_covers_every_release_target_with_fixed_downloads(self):
        import json
        manifest = json.loads(INSTALL.MANIFEST.read_text(encoding="utf-8"))
        self.assertEqual(
            {"macos-arm64", "macos-x64", "windows-x64", "linux-x64", "linux-arm64"},
            set(manifest["artifacts"]),
        )
        for entry in manifest["artifacts"].values():
            if entry.get("provider") == "ubuntu":
                self.assertEqual("24.04", entry["distribution"])
            else:
                self.assertEqual(64, len(entry["sha256"]))
                self.assertIn(f"_r{entry['version']}-", entry["url"])
                self.assertNotIn("latest", entry["url"])


if __name__ == "__main__":
    unittest.main()
