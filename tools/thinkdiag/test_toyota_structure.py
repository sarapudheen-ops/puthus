import struct
import unittest

from toyota_structure import (ECU_FILE, command_pool, function_index, leading_typed_table, menu_nodes,
                              section_directory, sentinel_index, system_fields, system_lists, typed_table, uint)


class ToyotaStructureTest(unittest.TestCase):
    def test_ecu_filenames_include_hexadecimal_letters_not_just_decimal_digits(self):
        import re
        for name in ["EU/Ecu_Data_0001.BIN", "JP/Ecu_Data_006a.BIN", "NA/Ecu_Data_02FF.BIN"]:
            self.assertIsNotNone(re.fullmatch(ECU_FILE, name), name)
        for name in ["EU/Ecu_Data_zzz.BIN", "OTHER/Ecu_Data_0001.BIN", "EU/Toyota_bin_04.BIN"]:
            self.assertIsNone(re.fullmatch(ECU_FILE, name), name)

    def test_typed_tables_keep_padding_and_validate_all_extents(self):
        data = struct.pack("<HII", 4, 2, 8) + b"abcdefgh" + b"\0\0"
        table = typed_table(data, 4)
        self.assertEqual((4, 2, b"abcdefgh", b"\0\0", 4),
                         (table.identity, table.count, table.body, table.padding, table.stride))
        for data, tag in [(data, 5), (data[:16], 4), (data + b"\0\0", 4),
                          (struct.pack("<HII", 4, 0, 1) + b"a", 4),
                          (struct.pack("<HII", 4, 1, 0), 4)]:
            with self.subTest(data=data, tag=tag), self.assertRaises(ValueError):
                typed_table(data, tag)
        self.assertIsNone(typed_table(struct.pack("<HII", 4, 2, 3) + b"abc").stride)
        self.assertEqual(b"", typed_table(struct.pack("<HII", 4, 0, 0)).body)

    def test_command_pool_recovers_only_bounded_byte_sequences_and_retains_trailing_data(self):
        body = struct.pack("<IBBIBB", 0, 2, 0, 2, 2, 1) + b"\x21\x01\x3e\x00"
        source = struct.pack("<HII", 0, 2, len(body)) + body
        rows, trailing = command_pool(source + b"uninterpreted")
        self.assertEqual(b"uninterpreted", trailing)
        self.assertEqual(["21 01", "3e 00"], [r["bytesHex"] for r in rows])
        self.assertEqual([22, 24], [r["bytesOffset"] for r in rows])
        self.assertEqual([1, 2], [r["index"] for r in rows])
        table, trailing = leading_typed_table(source + b"uninterpreted")
        self.assertEqual(body, table.body)
        self.assertEqual(b"uninterpreted", trailing)
        for offset in [0xFFFFFFFF, 3, 5]:
            bad_body = struct.pack("<IBBIBB", offset, 2, 0, 2, 2, 1) + b"\x21\x01\x3e\x00"
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                command_pool(struct.pack("<HII", 0, 2, len(bad_body)) + bad_body)
        with self.assertRaises(ValueError):
            command_pool(struct.pack("<HII", 0, 3, len(body)) + body)
        with self.assertRaises(ValueError):
            leading_typed_table(source[:-1])

    def test_menu_boundaries_targets_and_saved_parameters(self):
        root = bytearray(61)
        struct.pack_into("<I", root, 0, 61)
        struct.pack_into("<H", root, 36, 1)
        struct.pack_into("<H", root, 38, 15)
        root[40] = 1
        root[41:57] = bytes(range(16))
        struct.pack_into("<I", root, 57, 61)
        leaf = struct.pack("<I", 38) + bytes(34)
        nodes = menu_nodes(bytes(root) + leaf + b"\0\0\0")
        self.assertEqual({0, 61}, set(nodes))
        self.assertEqual(61, nodes[0]["edges"][0]["target"])
        self.assertEqual(bytes(range(16)).hex(), nodes[0]["edges"][0]["parametersHex"])
        for data in [b"", bytes(root) + leaf[:-1], bytes(root) + leaf + b"\0" * 4,
                     bytes(root[:57]) + struct.pack("<I", 62) + leaf,
                     struct.pack("<I", 60) + bytes(root[4:]) + leaf]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                menu_nodes(data)

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
