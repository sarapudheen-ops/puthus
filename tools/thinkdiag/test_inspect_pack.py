"""Synthetic fixtures only: vendor binaries and keys are not stored in this repository."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from inspect_pack import entropy, inspect_archive, markdown, signatures


class InspectPackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "test.zip"

    def write_zip(self, entries):
        with zipfile.ZipFile(self.path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, content in entries:
                archive.writestr(name, content)

    def test_inspects_without_extracting_or_executing(self):
        self.write_zip([("TOYOTA/version.ini", "version=1\n"), ("TOYOTA/tool.sh", "exit 99\n")])
        result = inspect_archive(self.path)
        self.assertEqual(2, result["entryCount"])
        self.assertEqual(0, result["zipEncryptedEntries"])
        self.assertEqual("UTF-8 text", result["members"][0]["type"])
        self.assertEqual("version=1\n", result["members"][0]["textPreview"])
        self.assertEqual([self.path], list(Path(self.tmp.name).iterdir()))
        json.dumps(result)
        self.assertIn(result["sha256"], markdown(result))

    def test_rejects_traversal(self):
        for name in ("../escape", "/tmp/escape", "TOYOTA/../../escape", "..\\escape"):
            with self.subTest(name=name):
                self.write_zip([(name, b"x")])
                with self.assertRaisesRegex(ValueError, "Unsafe ZIP member"):
                    inspect_archive(self.path)

    def test_rejects_symlink(self):
        info = zipfile.ZipInfo("link")
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        self.write_zip([(info, b"../../etc/passwd")])
        with self.assertRaisesRegex(ValueError, "Unsafe ZIP member"):
            inspect_archive(self.path)

    def test_rejects_entry_and_size_limits(self):
        self.write_zip([("file", b"12345")])
        with patch("inspect_pack.MAX_ENTRIES", 0), self.assertRaisesRegex(ValueError, "Too many"):
            inspect_archive(self.path)
        with patch("inspect_pack.MAX_UNCOMPRESSED", 4), self.assertRaisesRegex(ValueError, "2 GiB"):
            inspect_archive(self.path)

    def test_distinguishes_zip_encryption_from_opaque_members(self):
        self.write_zip([("locked.bin", b"x")])
        raw = bytearray(self.path.read_bytes())
        # Mark both local and central directory as encrypted. The inspector must
        # report this without trying to read/decrypt the member.
        struct.pack_into("<H", raw, 6, 1)
        central = raw.index(b"PK\x01\x02")
        struct.pack_into("<H", raw, central + 8, 1)
        self.path.write_bytes(raw)
        result = inspect_archive(self.path)
        self.assertEqual(1, result["zipEncryptedEntries"])
        self.assertNotIn("headHex", result["members"][0])

    def test_magic_signatures(self):
        self.assertEqual("SQLite database", signatures(b"SQLite format 3\0"))
        self.assertEqual("ELF executable/library", signatures(b"\x7fELF"))
        self.assertEqual("UTF-16 text", signatures(b"\xff\xfeh\x00i\x00"))
        self.assertEqual("unidentified binary", signatures(b"\xff\x01\x02\x99"))

    def test_entropy(self):
        self.assertEqual(0, entropy(b""))
        self.assertEqual(0, entropy(b"a" * 100))
        self.assertEqual(8, entropy(bytes(range(256))))

    def test_only_reads_a_bounded_prefix(self):
        self.write_zip([("data.bin", b"hello" * 20000)])
        result = inspect_archive(self.path)
        self.assertEqual(100000, result["members"][0]["bytes"])
        self.assertLessEqual(len(result["members"][0]["textPreview"]), 4000)


if __name__ == "__main__":
    unittest.main()
