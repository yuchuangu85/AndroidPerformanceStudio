import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("heap_compare", Path(__file__).with_name("compare.py"))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class ComparisonTest(unittest.TestCase):
    def test_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input"
            path.write_bytes(b"fixture")
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                compare.verify_hash(path, "0" * 64)

    def test_source_manifest_tracks_new_and_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tracked.rs").write_bytes(b"tracked")
            (root / "new.rs").write_bytes(b"new")
            paths = b"tracked.rs\0new.rs\0deleted.rs\0"
            with patch.object(compare.subprocess, "check_output", return_value=paths):
                before = compare.source_manifest(root)
                self.assertEqual(before["tracked.rs"], compare.digest(root / "tracked.rs"))
                self.assertEqual(before["new.rs"], compare.digest(root / "new.rs"))
                self.assertIsNone(before["deleted.rs"])
                (root / "tracked.rs").unlink()
                self.assertNotEqual(before, compare.source_manifest(root))

    def test_missing_row(self):
        self.assertEqual(compare.rows_diff([{"id": "7", "size": 0}], [], ("id",), ("size",))[0]["reason"], "missing row")

    def test_null_is_not_zero(self):
        self.assertEqual(len(compare.rows_diff([{"id": "7", "size": None}], [{"id": "7", "size": 0}], ("id",), ("size",))), 1)

    def test_difference(self):
        self.assertEqual(len(compare.rows_diff([{"id": "7", "size": 2}], [{"id": "7", "size": 3}], ("id",), ("size",))), 1)

    def test_missing_is_not_null(self):
        self.assertEqual(len(compare.rows_diff([{"id": "7"}], [{"id": "7", "size": None}], ("id",), ("size",))), 1)

    def test_duplicate_identity_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate row"):
            compare.rows_diff([{"id": "7"}, {"id": "7"}], [], ("id",), ())

    def test_matching_partial_data_never_equal(self):
        data = {"classes": [], "roots": [], "objects": []}
        self.assertEqual(compare.compare(data, data)["status"], "incomplete")


if __name__ == "__main__":
    unittest.main()
