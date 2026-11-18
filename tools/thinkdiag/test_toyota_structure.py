import struct
import unittest

from toyota_structure import (function_index, section_directory, sentinel_index,
                              system_fields, system_lists, uint)


class ToyotaStructureTest(unittest.TestCase):
    def test_section_directory_and_exact_spans(self):
        payload = struct.pack("<I", 2) + struct.pack("<BI", 0, 14) + struct.pack("<BI", 9, 17) + b"abc12345"
        sections = section_directory(payload)
        self.assertEqual([(0, 14, 17), (9, 17, 22)], [(s.identity, s.offset, s.end) for s in sections])

    def test_directory_rejects_partial_duplicate_reversed_and_out_of_bounds(self):
        cases = [b"", struct.pack("<I", 0), struct.pack("<I", 257),
                 struct.pack("<I", 1) + b"\0\x09\0",
                 struct.pack("<I", 2) + struct.pack("<BI", 0, 14) + struct.pack("<BI", 0, 16) + b"abcd",
                 struct.pack("<I", 2) + struct.pack("<BI", 0, 14) + struct.pack("<BI", 1, 14) + b"abcd",
                 struct.pack("<I", 2) + struct.pack("<BI", 0, 16) + struct.pack("<BI", 1, 14) + b"abcd",
                 struct.pack("<I", 1) + struct.pack("<BI", 0, 8) + b"xx",
                 struct.pack("<I", 1) + struct.pack("<BI", 0, 99) + b"xx"]
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError):
                section_directory(data)

    def test_integer_reads_never_accept_truncation_or_negative_offsets(self):
        for offset, width in [(-1, 1), (0, 3), (2, 4), (4, 1)]:
            with self.subTest(offset=offset, width=width), self.assertRaises(ValueError):
                uint(b"1234", offset, width)

    def test_index_accepts_zero_id_and_rejects_missing_sentinel_and_duplicates(self):
        rows, end = sentinel_index(struct.pack("<IIIII", 0, 20, 2, 25, 0xFFFFFFFF))
        self.assertEqual([(0, 20), (2, 25)], rows)
        self.assertEqual(20, end)
        for data in [b"", struct.pack("<II", 1, 12), struct.pack("<IIII", 1, 20, 1, 24)]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                sentinel_index(data)
        for start, stride in [(-1, 8), (0, 7), (0, 132)]:
            with self.subTest(start=start, stride=stride), self.assertRaises(ValueError):
                sentinel_index(b"1234", start, stride)

    def test_system_lists_keep_shared_blocks_and_source_fields(self):
        record = bytes(range(37))
        data = struct.pack("<IIIIIH", 1, 20, 2, 20, 0xFFFFFFFF, 1) + record
        index, blocks = system_lists(data)
        self.assertEqual([(1, 20), (2, 20)], index)
        self.assertEqual({20: [record]}, blocks)
        fields = system_fields(record)
        self.assertEqual(0x03020100, fields["u32"]["0"])
        self.assertEqual(0x1A191817, fields["u32"]["23"])
        self.assertEqual(36, fields["u8"]["36"])
        with self.assertRaises(ValueError):
            system_fields(record[:-1])

    def test_system_lists_reject_bad_pointers_counts_and_partial_overlaps(self):
        cases = [struct.pack("<III", 1, 0, 0xFFFFFFFF),
                 struct.pack("<IIIH", 1, 12, 0xFFFFFFFF, 1) + b"x" * 36,
                 struct.pack("<III", 1, 100, 0xFFFFFFFF),
                 struct.pack("<IIIIIH", 1, 20, 2, 22, 0xFFFFFFFF, 1) + b"\0" * 37]
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError):
                system_lists(data)

    def test_function_index_validates_all_pointer_columns(self):
        header = b"\x1c" + struct.pack("<8I", 1, 33, 34, 0, 0xFFFFFFFF, 35, 1, 0xFFFFFFFF)
        rows, end = function_index(header + b"abc")
        self.assertEqual(33, end)
        self.assertEqual((1, 33, 34, 0, 0xFFFFFFFF, 35, 1), rows[0])
        for pointer in [1, 32, 36, 0xFFFFFFFE]:
            data = b"\x1c" + struct.pack("<8I", 1, 33, 34, 0, pointer, 35, 1, 0xFFFFFFFF) + b"abc"
            with self.subTest(pointer=pointer), self.assertRaises(ValueError):
                function_index(data)
        with self.assertRaises(ValueError):
            function_index(b"\x18" + b"\0" * 50)


if __name__ == "__main__":
    unittest.main()
