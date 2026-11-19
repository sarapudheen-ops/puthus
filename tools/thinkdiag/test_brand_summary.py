"""Tests for the brand support-matrix summary (no vendor data required)."""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest

import brand_summary


def report(**overrides):
    base = {
        "archive": "FAKE.zip", "bytes": 12345, "sha256": "abc",
        "entryCount": 2, "uncompressedBytes": 999, "zipEncryptedEntries": 0,
        "extensions": {".so": 1},
        "members": [{"name": "V/DOB/libSTD.so", "headHex": "7f 45 4c 46"}],
    }
    base.update(overrides)
    return base


class SummaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, payload):
        page = self.root / name / "inspection.json"
        page.parent.mkdir(parents=True, exist_ok=True)
        page.write_text(json.dumps(payload), encoding="utf-8")
        return page

    def test_reports_missing_members_as_no(self):
        self.write("FAKE", report())
        rows = brand_summary.summarise(self.root)
        self.assertEqual(len(rows), 1)
        self.assertFalse(rows[0]["hasDeviceId"])
        self.assertTrue(rows[0]["hasStd"])
        self.assertFalse(rows[0]["hasNestedBinZip"])
        self.assertIn("| NO |", brand_summary.markdown(rows))

    def test_detects_yzjm_and_nested_zip(self):
        self.write("FAKE", report(members=[
            {"name": "V/PAYLOAD.BIN", "headHex": "59 5a 4a 4d"},
            {"name": "V/BIN.ZIP", "headHex": "50 4b 03 04"},
        ]))
        row = brand_summary.summarise(self.root)[0]
        self.assertEqual(row["yzjmHeads"], 1)
        self.assertTrue(row["hasNestedBinZip"])

    def test_tolerates_garbage_head_hex(self):
        self.write("FAKE", report(members=[{"name": "a.bin", "headHex": "zz not hex"}]))
        row = brand_summary.summarise(self.root)[0]
        self.assertEqual(row["yzjmHeads"], 0)

    def test_empty_directory_is_not_an_error(self):
        self.assertEqual(brand_summary.summarise(self.root), [])
        self.assertEqual(brand_summary.markdown([]), brand_summary.HEADER + "\n")

    def test_cli_writes_both_outputs(self):
        self.write("FAKE", report())
        self.assertEqual(brand_summary.main([str(self.root)]), 0)
        self.assertTrue((self.root / "summary.json").is_file())
        self.assertTrue((self.root / "summary.md").is_file())


if __name__ == "__main__":
    unittest.main()
