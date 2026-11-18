import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from convert_dtcs import convert, write_conversion


class ConvertDtcsTest(unittest.TestCase):
    def test_only_explicit_pairs_are_used(self):
        records = {
            "1": "P0301\0Cylinder 1 Misfire Detected",
            "2": "P042000\0Catalyst System Efficiency Below Threshold (Bank 1)",
            "3": "P0301\0Cylinder 1 Misfire Detected",
            "4": "Please clear P1234 first.",
            "5": "62\0Legacy blink code",
            "6": "P062F46\0P062F 46",
            "7": "PFFFF\0Invalid code",
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
        records = {"1": "P1234\0Module A fault", "2": "P1234\0Module A fault", "3": "P1234\0Module B fault"}
        pack, audit = convert(records)
        self.assertEqual({}, pack["dtcGroups"][0]["dtcs"])
        self.assertEqual(1, audit["statistics"]["conflictingCodesExcluded"])
        self.assertEqual(2, len(audit["conflicts"]["P1234"]))

    def test_normalizes_whitespace_but_not_code_semantics(self):
        pack, _ = convert({"1": " U0100\0Lost Communication\nWith ECM ", "2": "U010000\0U0100 $00"})
        self.assertEqual({"U0100": "Lost Communication With ECM"}, pack["dtcGroups"][0]["dtcs"])

    def test_log_renderings_extra_fields_and_placeholder_categories_are_not_data(self):
        pack, audit = convert({
            "1": "B0000\0Normal", "2": "P3216\0X Manufacturer Control P32 Fuel and Air metering",
            "3": "P0301\\^@Cylinder 1 Misfire Detected",  # Visible log rendering, not a NUL.
            "4": "P0301\0Cylinder 1 Misfire Detected\0Unexpected third field",
            "5": "P0301\0Reserved", "6": "P0301\0No description", "7": "P0301\0?",
        })
        self.assertEqual({}, pack["dtcGroups"][0]["dtcs"])
        self.assertEqual(2, audit["statistics"]["skippedRecords"]["not_a_code_description_pair"])
        self.assertEqual(5, audit["statistics"]["skippedRecords"]["placeholder_or_empty"])

    def test_empty_conversion_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "source.json").write_text(json.dumps({"1": "Not a DTC pair"}))
            with self.assertRaisesRegex(ValueError, "No explicit DTC pairs"):
                write_conversion(root / "source.json", root / "out", root / "report")

    def test_record_accounting_includes_duplicates_conflicts_and_rejections(self):
        _, audit = convert({
            "1": "P1234\0Module A fault", "2": "P1234\0Module B fault",
            "3": "P0301\0Cylinder 1 Misfire Detected", "4": "P0301\0Cylinder 1 Misfire Detected",
            "5": "Not a pair", "6": "62\0Blink code",
        })
        stats = audit["statistics"]
        self.assertEqual(stats["sourceRecords"], stats["acceptedRecords"] + stats["conflictingRecordsExcluded"] + sum(stats["skippedRecords"].values()))
        self.assertEqual(2, stats["acceptedRecords"])
        self.assertEqual(2, stats["conflictingRecordsExcluded"])

    def test_bundled_data_matches_its_provenance_and_has_no_requests(self):
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads((root / "tools/thinkdiag/toyota-dtcs.provenance.json").read_text())
        data = (root / manifest["output"]["file"]).read_bytes()
        self.assertEqual(manifest["output"]["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(manifest["output"]["bytes"], len(data))
        pack = json.loads(data)
        self.assertEqual(2, pack["formatVersion"])
        group = pack["dtcGroups"][0]
        self.assertEqual(manifest["output"]["makes"], group["makes"])
        self.assertEqual(manifest["conversion"]["statistics"]["acceptedCodes"], len(group["dtcs"]))
        self.assertTrue(group["source"])
        self.assertFalse(pack.get("pids") or pack.get("vehicles") or pack.get("recipes") or pack.get("dtcs"))
        self.assertNotIn("B0000", group["dtcs"])

    def test_writes_reproducible_pack_and_audit_outside_app(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            source.write_text(json.dumps({"1": "P0301\0Cylinder 1 Misfire Detected"}))
            write_conversion(source, root / "out", root / "report")
            pack = json.loads((root / "out/toyota-lexus-dtcs.json").read_text())
            shard = json.loads((root / "report/dtc-data-001.json").read_text())
            self.assertEqual(pack["dtcGroups"][0]["dtcs"], shard)
            self.assertTrue((root / "out/toyota-dtc-audit.json").exists())
            self.assertFalse(pack.get("pids") or pack.get("vehicles") or pack.get("recipes"))


if __name__ == "__main__":
    unittest.main()
