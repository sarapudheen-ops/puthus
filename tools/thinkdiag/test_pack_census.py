import json
import tempfile
import unittest
from pathlib import Path

from pack_census import census, markdown
from vehicle_bundle import BundleWriter


class PackCensusTest(unittest.TestCase):
    def test_census_walks_bundle_reader_and_counts_enabled_system(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            writer = BundleWriter(root)
            sysref = writer.add({"kind": "vehicle-system", "id": "sys-1", "enabled": True,
                "transportLabel": "Kwp", "entrySelector": 17, "sourceRecords": ["SYSLIST_EU.BIN@00000001"],
                "counts": {"pids": {"converted": 1, "unresolved": 0}}})
            page = writer.add({"kind": "source-inventory-page", "systems": [{"id": "sys-1", "object": sysref}]})
            inv = writer.add({"kind": "source-inventory", "systemPages": [page]})
            rootref = writer.add({"kind": "catalog-level", "level": "root", "id": "root", "children": []})
            writer.finish(rootref, {}, rootref, inv)
            result = census(root)
            self.assertEqual(1, result["treeCounts"]["systems"])
            self.assertEqual(1, result["treeCounts"]["enabled"])
            self.assertEqual("Kwp", result["systemClasses"][0]["transportLabel"])
            self.assertIn("Toyota pack census", markdown(result))

if __name__ == "__main__": unittest.main()
