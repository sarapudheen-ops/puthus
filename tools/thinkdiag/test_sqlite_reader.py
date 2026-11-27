import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

import sqlite_reader as sr


class SqliteReaderTest(unittest.TestCase):
    def test_extract_sqlite_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            db_path = root / "test.db"
            conn = sqlite3.connect(db_path)
            conn.execute("CREATE TABLE ecus (id INTEGER PRIMARY KEY, name TEXT)")
            conn.execute("INSERT INTO ecus (name) VALUES ('Engine')")
            conn.commit()
            conn.close()

            zp = root / "SQLITE_BRAND.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.write(db_path, "DATA/test.db")
                zf.writestr("README.txt", b"ignored")

            res = sr.extract_sqlite_archive(zp, "SQLITE_BRAND.zip", root / "out")
            self.assertEqual(res["accepted"], 1)
            self.assertEqual(res["rejected"], 0)
            self.assertTrue(res["under_480mib"])
            self.assertTrue(res["pack_file"].startswith("sqlite-"))
            self.assertTrue((root / "out" / res["pack_file"]).exists())


if __name__ == "__main__":
    unittest.main()
