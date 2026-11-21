import unittest
from convert_vehicle_packs import WRITE_ORACLE_REVIEW_HASHES, category_definitions, verified_write


class WriteProofTest(unittest.TestCase):
    def test_missing_or_corpus_supplied_marker_and_hash_never_verify(self):
        self.assertFalse(verified_write({}))
        self.assertFalse(verified_write({"provenMarker": "unreviewed", "reviewHash": "0" * 64}))
        self.assertFalse(WRITE_ORACLE_REVIEW_HASHES)

    def test_diagnostic_categories_count_nested_ecu_definitions(self):
        data = {"ecus": [{"diagnostics": {
            "faults": {"layout": {"labels": [{"code": "P1"}, {"code": "P2"}]}},
            "identification": [{"id": "VIN"}]}}]}
        self.assertEqual(len(category_definitions(data, "dtcs", "dtcs")), 2)
        self.assertEqual(len(category_definitions(data, "ecuInfo", "ecus")), 1)
        self.assertEqual(category_definitions(data, "pids", "pids"), [])


if __name__ == "__main__":
    unittest.main()
