import copy
import json
import os
from pathlib import Path
import unittest

from sweep_reports import make_parts, unpack_parts


class SweepReportBundleTest(unittest.TestCase):
    def test_lossless_utf8_roundtrip_with_small_check_count_and_api_bound(self):
        reports = [{"file": "sweep-test.md", "text": "\U0001f697\0\n" * 10000 + os.urandom(50000).hex()}]
        parts = make_parts(reports)
        self.assertGreater(len(parts), 1)
        for part in parts:
            self.assertLess(len(json.dumps(part).encode()), 60000)
        self.assertEqual(reports, unpack_parts(list(reversed(parts))))

    def test_rejects_missing_duplicate_or_corrupt_parts(self):
        parts = make_parts([{"file": "sweep-test.md", "text": os.urandom(4000).hex()}], chunk_chars=1000)
        self.assertGreater(len(parts), 2)
        for invalid in [[], parts[:-1], parts + [parts[0]]]:
            with self.assertRaises(ValueError):
                unpack_parts(invalid)
        invalid = copy.deepcopy(parts)
        invalid[0]["dataBase64"] = "A" + invalid[0]["dataBase64"][1:]
        with self.assertRaises(ValueError):
            unpack_parts(invalid)
        invalid = copy.deepcopy(parts)
        invalid[0]["uncompressedSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            unpack_parts(invalid)

    def test_rejects_unsafe_names_and_duplicate_files_before_use(self):
        for name in ["../escape.md", "/tmp/escape.md", "sweep-../escape.md", "sweep-a\\b.md", "unrelated.md"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                make_parts([{"file": name, "text": "no"}])
        record = {"file": "sweep-test.md", "text": "ok"}
        with self.assertRaises(ValueError):
            make_parts([record, record])


if __name__ == "__main__":
    unittest.main()
