import struct
import unittest

from yzjm import decode_yzjm, ror32, word_keys

# Synthetic, unrelated to any vendor/device key or copyrighted diagnostic table.
TABLE = bytes(range(32))


def fixture(payload=b"\x12\x34\x56\x78" * 16, make="SYNTH", version="V01.23", seed=0x12345678):
    header = b""
    for text in ("YZJM", make, version):
        raw = text.encode("ascii") + b"\0"
        header += struct.pack("<H", len(raw)) + raw
    header += struct.pack("<I", seed)
    keys = word_keys(make, version, seed, TABLE)
    body = b""
    for i, (word,) in enumerate(struct.iter_unpack(">I", payload)):
        cipher = word if word in (0, 0xFFFFFFFF) else ror32(word ^ keys[i % 8], 10)
        body += struct.pack(">I", cipher)
    return header + body


class YzjmTest(unittest.TestCase):
    def test_rotation(self):
        self.assertEqual(0x78123456, ror32(0x12345678, 8))
        self.assertEqual(0x48D159E0, ror32(0x12345678, 30))

    def test_key_derivation_known_synthetic_vector(self):
        # Independently calculated byte rotations: S Y N T H S Y N T H V 0 1 . 2 3.
        rotated = bytes.fromhex("6a ac 93 8a 09 a9 56 c9 8a 24 95 06 26 17 8c 66")
        expected = tuple(
            int.from_bytes(TABLE[p:p+4], "big") ^ ror32(0x12345678, 10) ^ int.from_bytes(rotated[p%16:p%16+4], "big")
            for p in range(0, 32, 4)
        )
        self.assertEqual(expected, word_keys("SYNTH", "V01.23", 0x12345678, TABLE))

    def test_round_trip_multiple_key_cycles(self):
        plain = bytes(range(128))
        decoded = decode_yzjm(fixture(plain), TABLE)
        self.assertEqual(("SYNTH", "V01.23", 0x12345678), (decoded.make, decoded.version, decoded.seed))
        self.assertEqual(plain, decoded.payload)

    def test_preserves_sentinel_words(self):
        plain = bytes(4) + b"\xff" * 4 + b"test" + bytes(4) + b"\xff" * 4
        self.assertEqual(plain, decode_yzjm(fixture(plain), TABLE).payload)

    def test_short_make_is_repeated(self):
        self.assertEqual(b"test", decode_yzjm(fixture(b"test", make="A"), TABLE).payload)

    def test_bad_header_and_wrong_table_fail(self):
        for raw in (b"", b"xx", fixture()[:10], fixture()[:-1], b"\x05\0NOPE\0"):
            with self.subTest(data=raw[:15]), self.assertRaises(ValueError):
                decode_yzjm(raw, TABLE)
        with self.assertRaisesRegex(ValueError, "32 bytes"):
            decode_yzjm(fixture(), b"short")
        with self.assertRaises(ValueError):
            word_keys("", "V01.23", 0, TABLE)
        with self.assertRaises(ValueError):
            word_keys("X", "V12345", 0, TABLE)

    def test_rejects_embedded_nul_and_nonascii(self):
        for marker in (b"Y\0JM\0", b"\xffZJM\0", b"YZJMX"):
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                decode_yzjm(b"\x05\0" + marker, TABLE)


if __name__ == "__main__":
    unittest.main()
