import tempfile
import unittest
from pathlib import Path

from pack_report import report
from vehicle_bundle import BundleWriter


class PackReportTest(unittest.TestCase):
    def test_tiny_bundle_report_uses_bundle_reader_and_keeps_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            writer = BundleWriter(root)
            system_ref = writer.add({"kind": "vehicle-system", "id": "sys-x", "enabled": False,
                "reasons": ["missing_function"], "counts": {"pids": {"converted": 0, "unresolved": None},
                "dtcs": {"converted": 0, "unresolved": 0}, "tests": {"converted": 0, "unresolved": 0},
                "coding": {"converted": 0, "unresolved": 0}, "specialFunctions": {"converted": 0, "unresolved": 0},
                "ecuInfo": {"converted": 0, "unresolved": 0}}})
            variant = writer.add({"kind": "converted-vehicle", "level": "variant", "id": "v", "systems": [{"object": system_ref}]})
            ref = writer.add({"kind": "catalog-level", "level": "make", "id": "m", "children": [{"id": "v", "level": "variant", "object": variant}]})
            writer.finish(ref, {"complete": False}, ref)
            text = report(root)
            self.assertIn("Unique referenced objects: 3", text)
            self.assertIn("missing_function", text)
            self.assertIn("unknown", text)
            self.assertIn("Systems: 1 total / 0 enabled / 1 disabled", text)

if __name__ == "__main__": unittest.main()
