import struct
import unittest

from toyota_entries import EntryIndex


# A literal reader-review fixture, not an operational address/session definition.
# SYSTEMENTER.BIN EU/function 61 starts at 12127 in source SHA 26c77a39...:
REVIEWED_LIST = bytes.fromhex(
    "03 04 00 00 "
    "11 01 81 b0 28 00 00 f0 13 00 02 07 07 05 3c 00 19 19 01 68 00 01 66 00 01 7f 00 "
    "12 03 31 f4 01 00 00 3b 2e be 42 00 04 00 02 06 0e 00 00 01 67 00 00 00 "
    "51 05 33 b0 28 00 00 f0 10 33 02 07 07 05 3c 00 19 19 01 68 00 01 66 00 01 7f 00")


def document(lists=(REVIEWED_LIST,)):
    start = 6 + 2 + len(lists) * 8
    directory = bytearray(struct.pack("<H", len(lists)))
    for i, row in enumerate(lists):
        directory += struct.pack("<II", 61 + i, start)
        start += len(row)
    return bytes([1, 1]) + struct.pack("<I", 6) + directory + b"".join(lists)


class EntryTest(unittest.TestCase):
    def test_reader_proven_fields_exact_selector_and_full_byte_accounting(self):
        data = document()
        index = EntryIndex(data)
        entry = index.select(1, 61, 0x11)
        self.assertEqual((1, 0x81, 10416), (entry.transport, entry.flags, entry.baud))
        self.assertEqual("f013", entry.fields["runtime107b0"])
        self.assertEqual("053c001919", entry.fields["runtime107f0"])
        self.assertEqual((104,), entry.init)
        self.assertEqual((102,), entry.keepalive)
        self.assertEqual((127,), entry.exit)
        self.assertEqual(20, entry.offset)
        self.assertEqual("SYSTEMENTER.BIN@00000014", entry.source_id)
        self.assertEqual(REVIEWED_LIST[4:31], entry.raw)
        self.assertEqual("3b2e", index.select(1, 61, 0x12).fields["runtime107b0"])
        self.assertEqual("be42", index.select(1, 61, 0x12).fields["runtime107d0"])
        self.assertEqual("0400", index.select(1, 61, 0x12).fields["runtime107c4"])
        self.assertEqual(5, index.select(1, 61, 0x51).transport)
        self.assertIsNone(index.select(1, 61, 1))  # No low-nibble or transport fallback.
        self.assertIsNone(index.select(2, 61, 0x11))
        self.assertIsNone(index.select(1, 62, 0x11))
        self.assertEqual([], index.unclaimed)
        item = index.lists[16]
        self.assertEqual(data[item.offset:item.end], item.header + b"".join(e.raw for e in item.entries))
        self.assertNotIn("requestId", entry.report())
        self.assertNotIn("enabled", entry.report())

    def test_every_truncation_is_rejected_not_padded(self):
        data = document()
        for n in range(len(data)):
            with self.subTest(n=n), self.assertRaises(ValueError):
                EntryIndex(data[:n])

    def test_duplicate_selectors_overlap_and_odd_pin_counts_rejected(self):
        for at, raw in ((47, b"\x11"), (30, b"\x03"), (12, b"\x06\x00\x00\x00")):
            data = bytearray(document())
            data[at:at + len(raw)] = raw
            with self.subTest(at=at), self.assertRaises(ValueError): EntryIndex(bytes(data))

    def test_default_switch_branch_and_explicit_interrecord_bytes(self):
        # Native low-nibble=4 switch takes no transport-specific fields, then
        # reads three binding lists. Such a record remains non-executable.
        data = document((bytes.fromhex("01 14 00 01 14 08 14 00 00 00 00 00 00 00"),)) + b"\xff\xff"
        index = EntryIndex(data)
        entry = index.select(1, 61, 0x14)
        self.assertEqual({}, entry.fields)
        self.assertEqual(((), (), ()), (entry.init, entry.keepalive, entry.exit))
        self.assertEqual([{"offset": len(data) - 2, "rawHex": "ffff"}], index.unclaimed)

    def test_variable_pin_count_and_optional_can_override(self):
        row = bytes.fromhex("01 00 00 00 12 03 31 f4 01 00 00 11 22 33 44 00 55 66 04 06 0e 03 0b 00 01 aa bb cc dd ee ff 00 00 00")
        entry = EntryIndex(document((row,))).select(1, 61, 0x12)
        self.assertEqual("060e030b", entry.fields["pinPairs"])
        self.assertEqual("aabb", entry.fields["runtime107b8"])
        self.assertEqual("ccdd", entry.fields["runtime107bc"])
        self.assertEqual("eeff", entry.fields["runtime107d4"])
        self.assertEqual(row[4:], entry.raw)


if __name__ == "__main__":
    unittest.main()
