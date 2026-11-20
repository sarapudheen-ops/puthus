"""Synthetic parser/identity fixtures, not replacement source programs or vehicle IDs."""
import struct
import unittest
from toyota_ecuprogram import EcuProgramIndex, calibration_ids_14


def program_fixture():
    one = struct.pack('<H', 3) + struct.pack('<IIB', 61, 2, 14) + struct.pack('<IIB', 0x1003d, 9, 4) + struct.pack('<IIB', 61, 5, 1)
    two = struct.pack('<H', 1) + struct.pack('<IIB', 61, 7, 16)
    return b'\x02' + struct.pack('<BI', 1, 11) + struct.pack('<BI', 2, 11 + len(one)) + one + two


class EcuProgramTest(unittest.TestCase):
    def test_exact_full_key_region_and_first_native_match_not_entry_selector(self):
        index = EcuProgramIndex(program_fixture())
        self.assertEqual((2, 14, 13), (index.select(1, 61).program, index.select(1, 61).dispatch, index.select(1, 61).offset))
        self.assertEqual(16, index.select(2, 61).dispatch)
        self.assertEqual(4, index.select(1, 0x1003d).dispatch)
        self.assertEqual(3, len(index.rows_by_region[1]))
        self.assertEqual(1, index.rows_by_region[1][-1].dispatch)  # retained, never preferred to the first exact match
        self.assertIsNone(index.select(3, 61))
        self.assertIsNone(index.select(1, 11))
        for r, f in [(0, 61), (True, 61), (1, True), (1, -1), (1, 0x100000000)]:
            with self.assertRaises(ValueError): index.select(r, f)

    def test_truncation_overlap_duplicate_region_and_unknown_unframed_bytes_rejected(self):
        raw = program_fixture()
        for n in range(len(raw)):
            with self.subTest(size=n), self.assertRaises(ValueError): EcuProgramIndex(raw[:n])
        for offset, replacement in [(0, b'\0'), (6, b'\x01'), (7, (12).to_bytes(4, 'little')), (1, b'\x04')]:
            bad = raw[:offset] + replacement + raw[offset + len(replacement):]
            with self.subTest(offset=offset), self.assertRaises(ValueError): EcuProgramIndex(bad)
        self.assertEqual([(len(raw), b'\xff' * 4)], EcuProgramIndex(raw + b'\xff' * 4).unclaimed)
        with self.assertRaises(ValueError): EcuProgramIndex(raw + b'\x00')

    def test_complete_fixed_width_text_records_and_native_count_header_semantics(self):
        a = b'CAL0123456789012'; b = b'SYNTHETIC'.ljust(16, b'\0')
        for header in (0, 1, 2, 255):
            self.assertEqual(('CAL0123456789012', 'SYNTHETIC'), calibration_ids_14(bytes([0x49, 4, header]) + a + b))
        self.assertEqual(15, len(calibration_ids_14(b'\x49\x04\x0f' + a * 15)))
        for data in [b'', b'\x49\x04\x00', b'\x49\x04\x01' + a[:-1], b'\x49\x04\x02' + a + b[:-1],
                     b'\x49\x05\x01' + a, b'\x49\x04\x01' + b'\0' * 16, b'\x49\x04\x01' + b'\xff' * 16,
                     b'\x49\x04\x01' + b'A\0B'.ljust(16, b'\0'), b'\x49\x04\x10' + a * 16]:
            with self.subTest(data=data[:20]), self.assertRaises(ValueError): calibration_ids_14(data)


if __name__ == '__main__': unittest.main()
