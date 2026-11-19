"""Tests for brand DTC conversion into the app's datapack format."""

from __future__ import annotations

import json
import pathlib
import tempfile
import unittest

import convert_brand_dtcs


def records(**pairs):
    return {f"id{n}": code + "\0" + text for n, (code, text) in enumerate(pairs.items())}


class ConvertTests(unittest.TestCase):
    def test_keeps_unique_pairs_and_normalizes_whitespace(self):
        accepted, audit = convert_brand_dtcs.convert(records(**{"P0301": "  Cylinder 1   misfire "}))
        self.assertEqual(accepted, {"P0301": "Cylinder 1 misfire"})
        self.assertEqual(audit["statistics"]["acceptedCodes"], 1)

    def test_excludes_conflicting_wording_rather_than_picking_one(self):
        raw = {"a": "P0301\0Misfire cylinder 1", "b": "P0301\0Cylinder 1 misfire detected"}
        accepted, audit = convert_brand_dtcs.convert(raw)
        self.assertEqual(accepted, {})
        self.assertEqual(audit["statistics"]["conflictingCodesExcluded"], 1)
        self.assertIn("P0301", audit["conflicts"])

    def test_keeps_extended_codes_extended(self):
        accepted, _ = convert_brand_dtcs.convert(records(**{"P030100": "Misfire detected"}))
        self.assertEqual(list(accepted), ["P0301-00"])

    def test_rejects_non_code_and_placeholder_records(self):
        raw = {
            "a": "VARIATION CODING\0Variation Coding",
            "b": "1234\0Something",
            "c": "P0301\0P0301",
            "d": "P0301\0normal",
            "e": "P0301\0ab",
            "f": "P0301",
        }
        accepted, audit = convert_brand_dtcs.convert(raw)
        self.assertEqual(accepted, {})
        self.assertEqual(sum(audit["statistics"]["skipped"].values()), 6)

    def test_strips_control_bytes_the_app_rejects(self):
        accepted, _ = convert_brand_dtcs.convert(records(**{"P0420": "Catalyst\x01efficiency below"}))
        self.assertEqual(accepted["P0420"], "Catalystefficiency below")
        self.assertTrue(all(ord(c) >= 32 for c in accepted["P0420"]))

    def test_pack_is_app_importable_shape(self):
        pack = convert_brand_dtcs.build_pack("VW", ["Volkswagen"], "TCODE_EN.GAG.json", {"P0301": "Misfire"})
        self.assertEqual(pack["format"], "bod3-datapack")
        self.assertEqual(pack["formatVersion"], 2)
        group = pack["dtcGroups"][0]
        self.assertEqual(group["makes"], ["Volkswagen"])
        self.assertEqual(list(group["dtcs"]), ["P0301"])


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_writes_pack_and_audit(self):
        source = self.root / "TCODE_EN.GAG.json"
        source.write_text(json.dumps(records(**{"P0301": "Cylinder 1 misfire"})), encoding="utf-8")
        out = self.root / "vw-dtcs.json"
        code = convert_brand_dtcs.main([str(source), "--brand", "vw", "--makes", "Volkswagen,Audi",
                                        "--output", str(out)])
        self.assertEqual(code, 0)
        pack = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(pack["id"], "vw-dtcs")
        self.assertEqual(pack["dtcGroups"][0]["makes"], ["Volkswagen", "Audi"])
        self.assertTrue((self.root / "vw-dtc-audit.json").is_file())

    def test_empty_conversion_fails_loudly(self):
        source = self.root / "TCODE_EN.GAG.json"
        source.write_text(json.dumps({"a": "not a pair"}), encoding="utf-8")
        code = convert_brand_dtcs.main([str(source), "--brand", "VW", "--makes", "Volkswagen",
                                        "--output", str(self.root / "x.json")])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
