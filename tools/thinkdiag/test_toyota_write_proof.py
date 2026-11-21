import unittest
from convert_vehicle_packs import WRITE_ORACLE_REVIEW_HASHES, verified_write


class WriteProofTest(unittest.TestCase):
    def test_missing_or_corpus_supplied_marker_and_hash_never_verify(self):
        self.assertFalse(verified_write({}))
        self.assertFalse(verified_write({"provenMarker": "unreviewed", "reviewHash": "0" * 64}))
        self.assertFalse(WRITE_ORACLE_REVIEW_HASHES)


if __name__ == "__main__":
    unittest.main()
