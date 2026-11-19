"""Static-reader regression tests use a synthetic ELF, never vendor binaries."""
from pathlib import Path
import struct
import tempfile
import unittest

from static_readers import reader_report
from sweep_toyota import publishable_report


def synthetic_aarch64_elf(instructions=4000):
    code = b"\x1f\x20\x03\xd5" * (instructions - 1) + b"\xc0\x03\x5f\xd6"  # NOPs; RET
    text_at = 0x200
    strings = b"\0reader\0"
    strings_at = text_at + len(code)
    symbols_at = (strings_at + len(strings) + 7) & ~7
    symbols = bytes(24) + struct.pack("<IBBHQQ", 1, 0x12, 0, 1, text_at, len(code))
    names = b"\0.text\0.dynstr\0.dynsym\0.shstrtab\0"
    names_at = symbols_at + len(symbols)
    sections_at = (names_at + len(names) + 7) & ~7
    length = sections_at + 5 * 64
    data = bytearray(length)
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, ident, 3, 183, 1, text_at,
                     64, sections_at, 0, 64, 56, 1, 64, 5, 4)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, length, length, 0x1000)
    data[text_at:text_at + len(code)] = code
    data[strings_at:strings_at + len(strings)] = strings
    data[symbols_at:symbols_at + len(symbols)] = symbols
    data[names_at:names_at + len(names)] = names
    sections = [(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                (1, 1, 6, text_at, text_at, len(code), 0, 0, 4, 0),
                (7, 3, 0, strings_at, strings_at, len(strings), 0, 0, 1, 0),
                (15, 11, 0, symbols_at, symbols_at, len(symbols), 2, 1, 8, 24),
                (23, 3, 0, names_at, names_at, len(names), 0, 0, 1, 0)]
    for index, section in enumerate(sections):
        struct.pack_into("<IIQQQQIIQQ", data, sections_at + index * 64, *section)
    return bytes(data)


class StaticReadersTest(unittest.TestCase):
    def test_full_reader_disassembly_is_not_silently_capped_at_12000_bytes(self):
        report = reader_report(synthetic_aarch64_elf(), "synthetic.so", ["reader"],
                               include_symbols=False, max_output_chars=None)
        self.assertIn("Decoded 16000/16000 function bytes.", report)
        self.assertIn("0000407c: ret", report)
        self.assertNotIn("Report truncated", report)
        self.assertGreater(len(report), 60000)
        with tempfile.TemporaryDirectory() as temp:
            names = publishable_report(Path(temp), "native", "Synthetic reader", report)
            self.assertGreater(len(names), 1)
            actual = "".join((Path(temp) / name).read_text().split("\n\n", 2)[2] for name in names)
            self.assertEqual(report, actual)

    def test_legacy_display_budget_has_an_explicit_truncation_notice(self):
        report = reader_report(synthetic_aarch64_elf(), "synthetic.so", ["reader"])
        self.assertLessEqual(len(report), 60000)
        self.assertIn("Report truncated for display", report)

    def test_missing_function_is_reported_instead_of_invented(self):
        report = reader_report(synthetic_aarch64_elf(2), "synthetic.so", ["absent"], include_symbols=False)
        self.assertIn("Missing symbol: absent", report)
        self.assertNotIn("Decoded", report)


if __name__ == "__main__":
    unittest.main()
