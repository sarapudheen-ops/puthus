"""Tiny invented ELF fixtures: static data review must never export code or BSS."""
import hashlib
import json
import struct
import unittest

from static_readers import data_report


def elf_fixture():
    names = ["", ".shstrtab", ".rodata", ".text", ".bss", ".dynstr", ".dynsym"]
    strings = b"\0operators\0code\0empty\0uninitialized\0undefined\0"
    symbol_names = ["operators", "code", "empty", "uninitialized", "undefined"]
    symbols = bytes(24)
    for name, kind, section, address, size in zip(symbol_names, [17, 18, 17, 17, 17],
                                                 [2, 3, 2, 4, 0], [0x1000, 0x2000, 0x1000, 0x3000, 0], [8, 4, 0, 16, 8]):
        symbols += struct.pack("<IBBHQQ", strings.index(name.encode()), kind, 0, section, address, size)
    shstrings = b"\0" + b"\0".join(n.encode() for n in names[1:]) + b"\0"
    contents = [b"", shstrings, bytes.fromhex("2b 00 00 00 06 00 00 00"), bytes.fromhex("c0 03 5f d6"), b"", strings, symbols]
    types = [0, 3, 1, 1, 8, 3, 11]
    flags = [0, 0, 2, 6, 3, 2, 2]
    addresses = [0, 0, 0x1000, 0x2000, 0x3000, 0x4000, 0x5000]
    payload = bytearray(64)
    headers = []
    for i, (name, raw) in enumerate(zip(names, contents)):
        at = len(payload)
        payload.extend(raw)
        headers.append(struct.pack("<IIQQQQIIQQ", shstrings.index(name.encode()) if name else 0,
                                   types[i], flags[i], addresses[i], at if i else 0,
                                   16 if i == 4 else len(raw), 5 if i == 6 else 0, 1 if i == 6 else 0,
                                   1, 24 if i == 6 else 0))
    shoff = len(payload)
    payload.extend(b"".join(headers))
    payload[:64] = b"\x7fELF\x02\x01\x01" + bytes(9) + struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 0, shoff, 0, 64, 56, 0, 64, len(headers), 1)
    return bytes(payload)


class StaticDataTest(unittest.TestCase):
    def test_original_bounded_data_and_provenance_are_not_relocated_or_executed(self):
        raw = elf_fixture()
        report = json.loads(data_report(raw, "synthetic.so", ["operators", "@0x1004:4"]))
        self.assertEqual(hashlib.sha256(raw).hexdigest(), report["librarySha256"])
        self.assertEqual(0, report["executableDefinitions"])
        first, second = report["objects"]
        self.assertEqual(("0x1000", 8, ".rodata", "2b 00 00 00 06 00 00 00"),
                         tuple(first[k] for k in ("address", "bytes", "section", "hex")))
        self.assertEqual(hashlib.sha256(bytes.fromhex(first["hex"])).hexdigest(), first["sha256"])
        self.assertEqual("06 00 00 00", second["hex"])

    def test_code_empty_missing_undefined_and_unbacked_objects_are_explicit_errors(self):
        selectors = ["code", "empty", "uninitialized", "undefined", "not-an-object", "@0x2000:4", "@0x3000:4"]
        report = json.loads(data_report(elf_fixture(), "synthetic.so", selectors))
        self.assertEqual(selectors, [i["selector"] for i in report["objects"]])
        for item in report["objects"]:
            self.assertIn("error", item)
            self.assertNotIn("hex", item)

    def test_bad_and_cross_section_ranges_never_leak_partial_file_data(self):
        selectors = ["@0x1000:0", "@0x1000:4097", "@-1:4", "@0x1007:2", "@0x1008:1", "@x:y", "@1:2:3"]
        report = json.loads(data_report(elf_fixture(), "synthetic.so", selectors))
        self.assertTrue(all("error" in item and "hex" not in item for item in report["objects"]))
        for invalid in ([], ["operators"] * 2, [str(i) for i in range(17)]):
            with self.assertRaises(ValueError): data_report(elf_fixture(), "synthetic.so", invalid)

    def test_wrong_machine_is_not_silently_treated_as_aarch64(self):
        raw = bytearray(elf_fixture())
        struct.pack_into("<H", raw, 18, 62)  # synthetic x86_64 instead
        with self.assertRaisesRegex(ValueError, "AArch64"): data_report(bytes(raw), "synthetic.so", ["operators"])


if __name__ == "__main__":
    unittest.main()
