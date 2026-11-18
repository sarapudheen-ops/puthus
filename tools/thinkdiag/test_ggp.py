import struct
import unittest

from py3rijndael import Rijndael
from ggp import GagFile, unpack_ggp

KEY = bytes(range(32))
FORMAT_XOR = bytes(range(15))


def ggp_fixture(name="SYNTH_EN.GAG", data=b"data"):
    size = 13 + 72
    directory = bytearray(b"GGP" + bytes(4) + struct.pack("<I", size) + b"\x01")
    directory += Rijndael(KEY, block_size=32).encrypt(name.encode().ljust(32, b"\0"))
    directory += bytes(32) + struct.pack("<II", size, len(data))
    directory += bytes([sum(directory) & 255])
    return bytes(directory) + data


def gag_fixture(records=None, key=b"abc"):
    records = records or {b"\x01\x02\x03\x04": b"Synthetic coolant temperature", b"\x00\x00\x00\x0d": "\u00b0C".encode()}
    perm = bytes((i * 13 + 7) % 256 for i in range(256))
    inv = bytearray(256)
    for i, byte in enumerate(perm):
        inv[byte] = i
    def meta(raw):
        last = raw[-1]
        return bytes(byte ^ inv[121 + ((i + 1 + last) % 134)] for i, byte in enumerate(raw[:-1])) + bytes([last ^ inv[121]])
    header = b"LAH\x00\x05\x03\x00" + bytes(byte ^ FORMAT_XOR[i % 15] for i, byte in enumerate(perm))
    header += bytes(4) + struct.pack(">H", len(key)) + key
    index = meta(struct.pack(">I", len(records)) + b"\x04")
    strings = b""
    offset = len(header) + len(index) + len(records) * 8
    for identity, text in records.items():
        index += bytes(byte ^ inv[119 + j] for j, byte in enumerate(identity))
        index += bytes(byte ^ inv[99 + ((i + 18) % 156)] for i, byte in enumerate(struct.pack(">I", offset + len(strings))))
        strings += meta(struct.pack(">H", len(text) + 2))
        strings += bytes(perm[byte] ^ (key[i % len(key)] if key else 0) for i, byte in enumerate(text))
    return header + index + strings


class GgpTest(unittest.TestCase):
    def test_reads_encrypted_directory_and_payload(self):
        member, = unpack_ggp(ggp_fixture(), KEY)
        self.assertEqual(("SYNTH_EN.GAG", 85, b"data"), (member.name, member.offset, member.data))

    def test_wrong_key_corruption_and_truncation_fail(self):
        for raw in (b"", b"GGP", ggp_fixture()[:20], b"BAD" + ggp_fixture()[3:]):
            with self.subTest(data=raw[:10]), self.assertRaises(ValueError):
                unpack_ggp(raw, KEY)
        raw = bytearray(ggp_fixture()); raw[20] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            unpack_ggp(raw, KEY)
        with self.assertRaises(ValueError):
            unpack_ggp(ggp_fixture(), bytes(32))

    def test_no_path_traversal(self):
        for name in ("../oops", "/etc/x", "..\\oops"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                unpack_ggp(ggp_fixture(name), KEY)

    def test_payload_bounds_are_checked(self):
        raw = bytearray(ggp_fixture()); struct.pack_into("<I", raw, 76, 1000); raw[84] = sum(raw[:84]) & 255
        with self.assertRaisesRegex(ValueError, "out of bounds"):
            unpack_ggp(raw, KEY)

    def test_recovers_indexed_unicode_text_with_and_without_secondary_table(self):
        expected = {b"\x01\x02\x03\x04": b"Synthetic coolant temperature", b"\x00\x00\x00\x0d": "\u00b0C".encode()}
        for key in (b"", b"abc"):
            with self.subTest(key=key):
                library = GagFile(gag_fixture(expected, key), FORMAT_XOR)
                self.assertEqual(2, library.count)
                self.assertEqual(expected, dict(library.records()))

    def test_wrong_revision_invalid_substitution_and_truncation_fail(self):
        for raw in (b"", gag_fixture()[:30], gag_fixture()[:269], b"BAD" + gag_fixture()[3:]):
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                GagFile(raw, FORMAT_XOR)
        raw = bytearray(gag_fixture()); raw[5] = 4
        with self.assertRaisesRegex(ValueError, "revision"):
            GagFile(raw, FORMAT_XOR)
        raw = bytearray(gag_fixture()); raw[8] = raw[7] ^ FORMAT_XOR[0] ^ FORMAT_XOR[1]
        with self.assertRaisesRegex(ValueError, "permutation"):
            GagFile(raw, FORMAT_XOR)

    def test_invalid_record_offsets_do_not_read_arbitrary_data(self):
        raw = gag_fixture()[:-20]
        with self.assertRaisesRegex(ValueError, "out of bounds"):
            list(GagFile(raw, FORMAT_XOR).records())


if __name__ == "__main__":
    unittest.main()
