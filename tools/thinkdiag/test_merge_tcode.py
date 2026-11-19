"""Tests for merging a brand's several TCODE libraries."""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest

import merge_tcode


class MergeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, data):
        (self.root / name).write_text(json.dumps(data), encoding="utf-8")

    def run_merge(self):
        out = self.root / "out.json"
        code = merge_tcode.main([str(self.root), "--brand", "BENZ", "--output", str(out)])
        return code, json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}

    def test_merges_every_tcode_file_for_the_brand(self):
        self.write("BENZ_TCODE_EN.GAG.json", {"a": "P0301\0Misfire"})
        self.write("BENZ_TCODE_EX_EN.GAG.json", {"b": "U0146\0Gateway comms"})
        self.write("BENZ_TCODE_X_EN.GAG.json", {"c": "B15AB\0SIM blocked"})
        code, merged = self.run_merge()
        self.assertEqual(code, 0)
        self.assertEqual(len(merged), 3)

    def test_ignores_other_brands(self):
        self.write("BENZ_TCODE_EN.GAG.json", {"a": "P0301\0Misfire"})
        self.write("BMW_TCODE_EN.GAG.json", {"b": "0B\0Sensor"})
        _, merged = self.run_merge()
        self.assertEqual(list(merged), ["a"])

    def test_identical_duplicates_collapse(self):
        self.write("BENZ_TCODE_EN.GAG.json", {"a": "P0301\0Misfire"})
        self.write("BENZ_TCODE_EX_EN.GAG.json", {"a": "P0301\0Misfire"})
        _, merged = self.run_merge()
        self.assertEqual(len(merged), 1)

    def test_differing_text_is_kept_so_it_stays_a_conflict(self):
        self.write("BENZ_TCODE_EN.GAG.json", {"a": "P0301\0Misfire"})
        self.write("BENZ_TCODE_EX_EN.GAG.json", {"a": "P0301\0Different wording"})
        _, merged = self.run_merge()
        self.assertEqual(len(merged), 2)

    def test_no_records_fails_loudly(self):
        (self.root / "empty").mkdir()
        code = merge_tcode.main([str(self.root), "--brand", "BENZ",
                                 "--output", str(self.root / "o.json")])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
