import json
from pathlib import Path
import tempfile
import unittest

from convert_dtcs import convert, write_conversion


class ConvertDtcsTest(unittest.TestCase):
    def test_only_explicit_pairs_are_used(self):
        records = {
            "1": "P0301\\^@Cylinder 1 Misfire Detected",
            "2": "P042000\\^@Catalyst System Efficiency Below Threshold (Bank 1)",
            "3": "P0301\\^@Cylinder 1 Misfire Detected",
            "4": "Please clear P1234 first.",
            "5": "62\\^@Legacy blink code",
            "6": "P062F46\\^@P062F 46",
            "7": "PFFFF\\^@Invalid code",
        }
        pack, audit = convert(records)
        dtcs = pack["dtcGroups"][0]["dtcs"]
        self.assertEqual({"P0301": "Cylinder 1 Misfire Detected", "P0420-00": "Catalyst System Efficiency Below Threshold (Bank 1)"}, dtcs)
        self.assertNotIn("P0420", dtcs)
        self.assertEqual(["1", "3"], audit["sources"]["P0301"])
        self.assertNotIn("dtcs", pack)  # No manufacturer data in the global namespace.
        self.assertEqual(["Toyota", "Lexus", "Toyota Thailand"], pack["dtcGroups"][0]["makes"])
        self.assertEqual(2, pack["formatVersion"])

    def test_conflicting_meanings_are_excluded_not_voted_on(self):
        records = {"1": "P1234\\^@Module A fault", "2": "P1234\\^@Module A fault", "3": "P1234\\^@Module B fault"}
        pack, audit = convert(records)
        self.assertEqual({}, pack["dtcGroups"][0]["dtcs"])
        self.assertEqual(1, audit["statistics"]["conflictingCodesExcluded"])
        self.assertEqual(2, len(audit["conflicts"]["P1234"]))

    def test_normalizes_whitespace_but_not_code_semantics(self):
        pack, _ = convert({"1": " U0100\\^@Lost Communication\nWith ECM ", "2": "U010000\\^@U0100 $00"})
        self.assertEqual({"U0100": "Lost Communication With ECM"}, pack["dtcGroups"][0]["dtcs"])

    def test_writes_reproducible_pack_and_audit_outside_app(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(json.dumps({"1": "P0301\\^@Cylinder 1 Misfire Detected"}))
            write_conversion(source, root / "out", root / "report")
            pack = json.loads((root / "out/toyota-lexus-dtcs.json").read_text())
            shard = json.loads((root / "report/dtc-data-001.json").read_text())
            self.assertEqual(pack["dtcGroups"][0]["dtcs"], shard)
            self.assertTrue((root / "out/toyota-dtc-audit.json").exists())
            self.assertFalse(pack.get("pids") or pack.get("vehicles") or pack.get("recipes"))


if __name__ == "__main__":
    unittest.main()
