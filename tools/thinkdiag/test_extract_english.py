"""Pinned archive -> exact English records/provenance boundary (synthetic GAG)."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from extract_english import read_members, read_text_libraries
from ggp import GgpMember
from test_ggp import FORMAT_XOR, gag_fixture


class EnglishLibraryTest(unittest.TestCase):
    def test_missing_or_repeated_library_names_and_unpinned_archive_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'not-source.zip'; path.write_bytes(b'not the pinned source')
            with self.assertRaisesRegex(ValueError, 'SHA-256'): read_members(path)
        with mock.patch('extract_english.read_members', return_value=([], FORMAT_XOR)):
            with self.assertRaisesRegex(ValueError, 'Missing'): read_text_libraries(Path('unused'))
            with self.assertRaises(ValueError): read_text_libraries(Path('unused'), ('x', 'x'))

    def test_index_and_text_locations_refer_to_the_original_member_not_json(self):
        records = {b'\x12\x34\x56\x78': b'P0001\0Exact pair\0'}
        member = GgpMember('TCODE_EN.GAG', 1234, gag_fixture(records))
        with mock.patch('extract_english.read_members', return_value=([member], FORMAT_XOR)):
            result = read_text_libraries(Path('unused'), ('TCODE_EN.GAG',))
        text, locations = result['TCODE_EN.GAG']
        self.assertEqual({'12345678': 'P0001\0Exact pair'}, text)
        self.assertEqual(1234, locations['12345678']['ggpMemberOffset'])
        self.assertGreater(locations['12345678']['textOffset'], locations['12345678']['indexOffset'])
        self.assertEqual(len(next(iter(records.values()))) + 2, locations['12345678']['encodedBytes'])

    def test_invalid_utf8_or_truncated_source_is_not_silently_skipped(self):
        for data in (gag_fixture({b'\0\0\0\x01': b'bad\xff'}), gag_fixture()[:-3]):
            with mock.patch('extract_english.read_members', return_value=([GgpMember('TCODE_EN.GAG', 1, data)], FORMAT_XOR)):
                with self.assertRaises((ValueError, UnicodeDecodeError)): read_text_libraries(Path('unused'), ('TCODE_EN.GAG',))


if __name__ == '__main__': unittest.main()
