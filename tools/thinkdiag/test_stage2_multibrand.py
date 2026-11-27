import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import stage2_multibrand as s2


class Stage2MultibrandTest(unittest.TestCase):
    def test_rank_candidates_excludes_toyota_and_incomplete(self):
        report = {
            "brands": [
                {"archive": "TOYOTA.zip", "sha256": "a", "status": "complete", "parser_stats": {"yzjm_accepted": 108, "toyota_struct_accepted": 100}},
                {"archive": "PARTIAL.zip", "sha256": "b", "status": "partial", "parser_stats": {"yzjm_accepted": 10, "toyota_struct_accepted": 5}},
                {"archive": "LEXUS.zip", "sha256": "c", "status": "complete", "parser_stats": {"yzjm_accepted": 50, "toyota_struct_accepted": 20}},
                {"archive": "NOSTRUCT.zip", "sha256": "d", "status": "complete", "parser_stats": {"yzjm_accepted": 10, "toyota_struct_accepted": 0}},
            ]
        }
        ranked = s2.rank_candidates(report)
        self.assertEqual([c["archive"] for c in ranked], ["LEXUS.zip"])

    def test_extract_brand_pack_reconciles_duplicates_and_rejections(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            zp = root / "LEXUS.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("MENU.BIN", b"not-a-valid-menu")
                zf.writestr("DUP/MENU.BIN", b"not-a-valid-menu")
                zf.writestr("OTHER.TXT", b"ignored")

            res = s2.extract_brand_pack(zp, "LEXUS.zip", root / "out")
            self.assertEqual(res["brand"], "lexus")
            self.assertEqual(res["accepted"], 0)
            self.assertEqual(res["rejected"], 1)
            self.assertEqual(res["duplicates"], 1)
            self.assertTrue(res["under_480mib"])
            self.assertTrue((root / "out" / "lexus-pack.bin").exists())


if __name__ == "__main__":
    unittest.main()
