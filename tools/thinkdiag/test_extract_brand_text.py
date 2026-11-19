"""Tests for address-free brand text recovery, using synthetic archives only."""

from __future__ import annotations

import json
import pathlib
import struct
import tempfile
import unittest
import zipfile

from py3rijndael import Rijndael

import extract_brand_text

KEY = bytes(range(32))
FORMAT_XOR = bytes(range(15))
# An unrelated 64-hex-looking run that must not win over the real key.
DECOY = "f" * 64


def ggp_fixture(name="SYNTH_EN.GAG", data=b"data"):
    size = 13 + 72
    directory = bytearray(b"GGP" + bytes(4) + struct.pack("<I", size) + b"\x01")
    directory += Rijndael(KEY, block_size=32).encrypt(name.encode().ljust(32, b"\0"))
    directory += bytes(32) + struct.pack("<II", size, len(data))
    directory += bytes([sum(directory) & 255])
    return bytes(directory) + data


def gag_fixture(records=None, key=b"abc"):
    records = records or {b"\x01\x02\x03\x04": b"Synthetic coolant temperature"}
    perm = bytes((i * 13 + 7) % 256 for i in range(256))
    inv = bytearray(256)
    for i, byte in enumerate(perm):
        inv[byte] = i

    def meta(raw):
        last = raw[-1]
        return (bytes(byte ^ inv[121 + ((i + 1 + last) % 134)] for i, byte in enumerate(raw[:-1]))
                + bytes([last ^ inv[121]]))

    header = b"LAH\x00\x05\x03\x00" + bytes(byte ^ FORMAT_XOR[i % 15] for i, byte in enumerate(perm))
    header += bytes(4) + struct.pack(">H", len(key)) + key
    index = meta(struct.pack(">I", len(records)) + b"\x04")
    strings = b""
    offset = len(header) + len(index) + len(records) * 8
    for identity, text in records.items():
        index += bytes(byte ^ inv[119 + j] for j, byte in enumerate(identity))
        index += bytes(byte ^ inv[99 + ((i + 18) % 156)]
                       for i, byte in enumerate(struct.pack(">I", offset + len(strings))))
        strings += meta(struct.pack(">H", len(text) + 2))
        strings += bytes(perm[byte] ^ (key[i % len(key)] if key else 0) for i, byte in enumerate(text))
    return header + index + strings


def library_with_constants(gag: bytes, extra: bytes = b"") -> bytes:
    """A stand-in libSTD.so: real key + format table, plus padding and a decoy."""
    return (
        b"\x7fELF" + bytes(64) + extra + (DECOY.encode() + b"\0")
        + KEY.hex().encode() + b"\0" + bytes(32) + FORMAT_XOR + bytes(200)
    )


def archive(path: pathlib.Path, native: bytes, ggp: bytes, ggp_name="SYNTH_EN.GGP"):
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr(f"DIAGNOSTIC/VEHICLES/SYNTH/V1/libSTD.so", native)
        zf.writestr(f"DIAGNOSTIC/VEHICLES/SYNTH/V1/{ggp_name}", ggp)
    return path


class KeyDiscoveryTests(unittest.TestCase):
    def test_finds_the_real_key_and_ignores_decoys(self):
        keys = extract_brand_text.find_keys(library_with_constants(b""))
        self.assertIn(KEY, keys)
        self.assertGreaterEqual(len(keys), 2)  # the decoy is found too

    def test_open_ggp_picks_the_key_that_actually_decrypts(self):
        keys = [bytes(32), KEY]  # a wrong key first
        key, members = extract_brand_text.open_ggp(ggp_fixture(), keys)
        self.assertEqual(key, KEY)
        self.assertEqual(members[0].name, "SYNTH_EN.GAG")

    def test_open_ggp_fails_closed_when_no_key_works(self):
        with self.assertRaisesRegex(ValueError, "No candidate key"):
            extract_brand_text.open_ggp(ggp_fixture(), [bytes(32)])


class FormatTableTests(unittest.TestCase):
    def test_finds_the_table_without_knowing_its_address(self):
        tables = extract_brand_text.find_format_tables(library_with_constants(b""), gag_fixture())
        self.assertIn(FORMAT_XOR, tables)

    def test_open_gag_rejects_a_table_that_only_passes_the_header(self):
        bad = bytes(15)  # produces a non-permutation, so never offered as a candidate
        tables = extract_brand_text.find_format_tables(library_with_constants(b""), gag_fixture())
        self.assertNotIn(bad, tables)
        table, library = extract_brand_text.open_gag(gag_fixture(), tables)
        self.assertEqual(table, FORMAT_XOR)
        self.assertEqual(library.count, 1)


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_extracts_records_from_a_synthetic_brand(self):
        gag = gag_fixture({b"\x01\x02\x03\x04": b"Cylinder 1 misfire"})
        native = library_with_constants(gag)
        path = archive(self.root / "SYNTH.zip", native, ggp_fixture(data=gag))
        summary = extract_brand_text.extract(path, self.root / "out", self.root / "report")
        self.assertEqual(summary["brand"], "SYNTH")
        self.assertEqual(summary["keyHex"], KEY.hex())
        text = [m for m in summary["members"] if m["kind"] == "text"][0]
        self.assertEqual(text["records"], 1)
        recovered = json.loads((self.root / "out" / "SYNTH_SYNTH_EN.GAG.json").read_text())
        self.assertEqual(list(recovered.values()), ["Cylinder 1 misfire"])

    def test_missing_libstd_is_reported_not_guessed(self):
        path = self.root / "EMPTY.zip"
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("a.txt", b"x")
        with self.assertRaisesRegex(ValueError, "libSTD"):
            extract_brand_text.extract(path, self.root / "out", self.root / "report")

    def test_missing_text_library_is_reported(self):
        path = self.root / "NOTEXT.zip"
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr("V/libSTD.so", library_with_constants(b""))
        with self.assertRaisesRegex(ValueError, "text library"):
            extract_brand_text.extract(path, self.root / "out", self.root / "report")

    def test_one_bad_archive_does_not_hide_a_good_one(self):
        gag = gag_fixture()
        good = archive(self.root / "GOOD.zip", library_with_constants(gag), ggp_fixture(data=gag))
        bad = self.root / "BAD.zip"
        bad.write_bytes(b"not a zip")
        code = extract_brand_text.main([str(bad), str(good), "--output", str(self.root / "o"),
                                       "--report", str(self.root / "r")])
        self.assertEqual(code, 1)  # failures are signalled
        summary = json.loads((self.root / "r" / "brand-text-summary.json").read_text())
        self.assertIn("error", summary[0])
        self.assertEqual(summary[1]["totalRecords"], 1)


if __name__ == "__main__":
    unittest.main()
