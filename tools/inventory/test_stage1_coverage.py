import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import stage1_coverage as s1


class Stage1CoverageTest(unittest.TestCase):
    def test_nested_zip_and_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            inner_buf = io.BytesIO()
            with zipfile.ZipFile(inner_buf, "w") as izf:
                izf.writestr("INNER/A.BIN", b"hello")
            zp = root / "BRANDX.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("TOP/A.BIN", b"hello")
                zf.writestr("NESTED.zip", inner_buf.getvalue())

            res = s1.scan_archive(zp, "BRANDX.zip", zp.stat().st_size, run_parsers=True)
            self.assertEqual(res["status"], "complete")
            self.assertEqual(res["member_count"], 3)
            self.assertEqual(res["duplicate_member_hashes"], 1)
            self.assertEqual(res["skipped_count"], 0)
            depths = {m["path"]: m["depth"] for m in res["members"]}
            self.assertEqual(depths["TOP/A.BIN"], 0)
            self.assertEqual(depths["NESTED.zip"], 0)
            self.assertEqual(depths["NESTED.zip!/INNER/A.BIN"], 1)

    def test_unsafe_path_marks_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            zp = Path(tmp) / "UNSAFE.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("../escape.bin", b"bad")
                zf.writestr("ok.bin", b"good")
            res = s1.scan_archive(zp, "UNSAFE.zip", None, run_parsers=False)
            self.assertEqual(res["status"], "partial")
            self.assertEqual(res["skipped_count"], 1)
            self.assertEqual(res["member_count"], 1)

    def test_corrupt_zip_marks_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            zp = Path(tmp) / "CORRUPT.zip"
            zp.write_bytes(b"not a zip at all")
            res = s1.scan_archive(zp, "CORRUPT.zip", None, run_parsers=False)
            self.assertEqual(res["status"], "unreadable")
            self.assertIsNotNone(res["error"])

    def test_cli_local_dir_generates_reports(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            zdir = root / "zips"
            zdir.mkdir()
            with zipfile.ZipFile(zdir / "ALPHA.zip", "w") as zf:
                zf.writestr("DATA.BIN", b"\x00\x01\x02\x03")
            out_json = root / "out" / "report.json"
            out_md = root / "out" / "report.md"
            import sys
            old_argv = sys.argv
            try:
                sys.argv = [
                    "stage1_coverage.py",
                    "--local-dir", str(zdir),
                    "--output-json", str(out_json),
                    "--output-md", str(out_md),
                    "--commit-sha", "abc1234",
                ]
                rc = s1.main()
            finally:
                sys.argv = old_argv
            self.assertEqual(rc, 0)
            data = json.loads(out_json.read_text())
            self.assertEqual(data["archive_count"], 1)
            self.assertEqual(data["brands"][0]["archive"], "ALPHA.zip")
            self.assertIn("ALPHA.zip", out_md.read_text())


if __name__ == "__main__":
    unittest.main()
