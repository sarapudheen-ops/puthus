import json
import struct
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

    def test_ecu_tables_are_censused_from_cached_inventory_when_decoded_tree_is_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            writer = BundleWriter(root)
            row_a = "0102030405060708090a0b0c"
            row_b = "1112131415161718191a1b1c"
            page = writer.add({"kind": "disabled-items", "items": [
                {"id": "EU/Ecu_Data_0001.BIN@00000020#slot0:row0", "tableTag": 0, "rawHex": row_a},
                {"id": "EU/Ecu_Data_0001.BIN@0000002c#slot0:row1", "tableTag": 0, "rawHex": row_b},
            ]})
            ecu = writer.add({"kind": "ecu-source", "sourceFile": "EU/Ecu_Data_0001.BIN",
                "sha256": "source-file-hash", "sections": [{"slot": 0, "tag": 0, "rows": 2, "stride": 12}],
                "pages": [page]})
            inventory = writer.add({"kind": "source-inventory", "systemPages": [], "ecuSources": [ecu],
                "functionSources": [], "entryPages": [], "unclaimedEntryPages": []})
            catalog = writer.add({"kind": "catalog-level", "level": "root", "id": "root", "children": []})
            writer.finish(catalog, {}, catalog, inventory)
            result = census(root, root / "decoded-cache-miss")
            source = result["sourceCensus"]
            self.assertEqual(1, len(source["ecuTables"]))
            schema = source["ecuTables"][0]
            self.assertEqual(("EU", 0, 0, 12, "1-4"),
                (schema["region"], schema["slot"], schema["tableTag"], schema["stride"], schema["rowBucket"]))
            self.assertEqual(2, schema["rowCount"])
            self.assertEqual(1, schema["ecuFiles"])
            self.assertEqual([row_a, row_b], schema["rawRows"])
            stats = source["ecuTableStats"]
            self.assertEqual(1, stats["distinctTagStridePairs"])
            self.assertEqual(2, stats["tag0Stride12"]["rowCount"])
            self.assertEqual({"01020304": 1, "11121314": 1}, stats["tag0Stride12"]["first4Bytes"])
            self.assertEqual({"090a0b0c": 1, "191a1b1c": 1}, stats["tag0Stride12"]["last4Bytes"])
            rendered = markdown(result)
            self.assertIn("## Ecu_Data typed tables", rendered)
            self.assertIn(row_a, rendered)

            decoded = root / "decoded" / "EU"
            decoded.mkdir(parents=True)
            body = bytes.fromhex(row_a + row_b)
            table = struct.pack("<HII", 0, 2, len(body)) + body
            (decoded / "Ecu_Data_0001.BIN").write_bytes(struct.pack("<IBI", 1, 0, 9) + table)
            decoded_result = census(root, decoded.parent)
            decoded_schema = decoded_result["sourceCensus"]["ecuTables"][0]
            self.assertEqual(1, decoded_schema["uniqueTables"])
            self.assertEqual(2, decoded_schema["rowCount"])
            self.assertEqual([row_a, row_b], decoded_schema["rawRows"])
            self.assertEqual(1, decoded_result["sourceCensus"]["ecuTableStats"]["tag0Stride12"]["uniqueTables"])

if __name__ == "__main__": unittest.main()
