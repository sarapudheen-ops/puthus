"""Tests for the ODX-style calc-record parser (synthetic records, no vendor data)."""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest

import parse_calc

LINEAR = ("Linear, , , 0, 1, 10, ; A_UINT32, StandardLengthType, , , Undefined, , 0, 0, 16, ; "
          "A_FLOAT64, DEC, 1; , ; ; 0x55000003")
SCALE = ("ScaleLinear, 0 255, , 0, 20, 1, ; A_UINT32, StandardLengthType, , , Undefined, , 0, 0, 8, ; "
         "A_UINT32, DEC, 0; , ; ; 0x55000004")
IDENTICAL = ("Identical, A_ASCIISTRING, StandardLengthType, , , ISO-8859-2, , 0, 0, 64, ; "
             "A_UNICODE2STRING, DEC, 0; , ;")


class ParseTests(unittest.TestCase):
    def test_linear_factor_and_divisor(self):
        rule = parse_calc.parse(LINEAR)
        self.assertEqual(rule["kind"], "Linear")
        self.assertEqual(rule["sourceType"], "A_UINT32")
        self.assertEqual(rule["sourceBits"], 16)
        self.assertEqual(rule["outputType"], "A_FLOAT64")
        self.assertEqual(rule["decimals"], 1)
        self.assertEqual(rule["tableId"], "0x55000003")
        self.assertAlmostEqual(parse_calc.scale_factor(rule), 0.1)

    def test_scale_linear_with_limits(self):
        rule = parse_calc.parse(SCALE)
        self.assertEqual(rule["limits"], (0, 255))
        self.assertEqual(rule["sourceBits"], 8)
        self.assertEqual(rule["decimals"], 0)
        self.assertAlmostEqual(parse_calc.scale_factor(rule), 20.0)

    def test_identical_has_no_scale(self):
        rule = parse_calc.parse(IDENTICAL)
        self.assertEqual(rule["kind"], "Identical")
        self.assertIsNone(parse_calc.scale_factor(rule))

    def test_non_calc_text_is_ignored(self):
        for text in ("Synthetic coolant temperature", "", "NotLinear, 1, 2"):
            with self.subTest(text=text):
                self.assertIsNone(parse_calc.parse(text))

    def test_truncated_record_does_not_raise(self):
        self.assertIsNone(parse_calc.parse("Linear,"))
        rule = parse_calc.parse("Linear, , , 0, 1, 10,")
        self.assertIsNotNone(rule)
        self.assertIsNone(rule["outputType"])


class ScanTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_scans_only_calc_members(self):
        (self.root / "VW_DS_CALC.GAG.json").write_text(
            json.dumps({"a": LINEAR, "b": "not a rule", "c": SCALE}), encoding="utf-8")
        (self.root / "VW_TCODE_EN.GAG.json").write_text(json.dumps({"a": LINEAR}), encoding="utf-8")
        report = parse_calc.scan(self.root)
        self.assertEqual(len(report["members"]), 1)
        self.assertEqual(report["totals"]["parsed"], 2)
        self.assertEqual(report["totals"]["withScale"], 2)

    def test_corrupt_member_is_reported_not_raised(self):
        (self.root / "VW_DS_CALC.GAG.json").write_text("{not json", encoding="utf-8")
        report = parse_calc.scan(self.root)
        self.assertIn("error", report["members"][0])

    def test_main_returns_nonzero_when_nothing_parses(self):
        (self.root / "VW_DS_CALC.GAG.json").write_text(json.dumps({"a": "plain"}), encoding="utf-8")
        self.assertEqual(parse_calc.main([str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
