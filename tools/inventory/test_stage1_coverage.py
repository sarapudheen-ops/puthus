import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import stage1_coverage as s1
from yzjm import ror32, word_keys

# Synthetic, unrelated to any vendor/device key or copyrighted diagnostic table.
TABLE = bytes(range(32))


def yzjm(payload: bytes, table: bytes = TABLE, make="SYNTH", version="V01.23", seed=0x12345678) -> bytes:
    header = b""
    for text in ("YZJM", make, version):
        raw = text.encode("ascii") + b"\0"
        header += struct.pack("<H", len(raw)) + raw
    header += struct.pack("<I", seed)
    keys = word_keys(make, version, seed, table)
    body = b""
    for index, (word,) in enumerate(struct.iter_unpack(">I", payload)):
        cipher = word if word in (0, 0xFFFFFFFF) else ror32(word ^ keys[index % 8], 10)
        body += struct.pack(">I", cipher)
    return header + body


class Stage1CoverageTest(unittest.TestCase):
    def test_nested_zip_and_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            inner_buf = io.BytesIO()
            with zipfile.ZipFile(inner_buf, "w") as izf:
                izf.writestr("INNER/A.BIN", b"hello")
            zp = root / "BRANDX.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("TOP/A.BIN", b"hello")
                zf.writestr("NESTED.zip", inner_buf.getvalue())

            res = s1.scan_archive(zp, "BRANDX.zip", zp.stat().st_size, run_parsers=True)
            self.assertEqual(res["status"], "complete")
            self.assertEqual(res["member_count"], 3)
            self.assertEqual(res["duplicate_member_hashes"], 1)
            self.assertEqual(res["skipped_count"], 0)
            depths = {m["path"]: m["depth"] for m in res["members"]}
            self.assertEqual(depths["TOP/A.BIN"], 0)
            self.assertEqual(depths["NESTED.zip"], 0)
            self.assertEqual(depths["NESTED.zip!/INNER/A.BIN"], 1)

    def test_unsafe_path_marks_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            zp = Path(tmp) / "UNSAFE.zip"
            with zipfile.ZipFile(zp, "w") as zf:
                zf.writestr("../escape.bin", b"bad")
                zf.writestr("ok.bin", b"good")
            res = s1.scan_archive(zp, "UNSAFE.zip", None, run_parsers=False)
            self.assertEqual(res["status"], "partial")
            self.assertEqual(res["skipped_count"], 1)
            self.assertEqual(res["member_count"], 1)

    def test_corrupt_zip_marks_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            zp = Path(tmp) / "CORRUPT.zip"
            zp.write_bytes(b"not a zip at all")
            res = s1.scan_archive(zp, "CORRUPT.zip", None, run_parsers=False)
            self.assertEqual(res["status"], "unreadable")
            self.assertIsNotNone(res["error"])

    def test_cli_local_dir_generates_reports(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            zdir = root / "zips"
            zdir.mkdir()
            with zipfile.ZipFile(zdir / "ALPHA.zip", "w") as zf:
                zf.writestr("DATA.BIN", b"\x00\x01\x02\x03")
            out_json = root / "out" / "report.json"
            out_md = root / "out" / "report.md"
            import sys
            old_argv = sys.argv
            try:
                sys.argv = [
                    "stage1_coverage.py",
                    "--local-dir", str(zdir),
                    "--output-json", str(out_json),
                    "--output-md", str(out_md),
                    "--commit-sha", "abc1234",
                ]
                rc = s1.main()
            finally:
                sys.argv = old_argv
            self.assertEqual(rc, 0)
            data = json.loads(out_json.read_text())
            self.assertEqual(data["archive_count"], 1)
            self.assertEqual(data["brands"][0]["archive"], "ALPHA.zip")
            self.assertIn("ALPHA.zip", out_md.read_text())


class FormatTableTest(unittest.TestCase):
    """decode_yzjm has no default table; Stage 1 must supply the archive's own."""

    def test_decoder_requires_a_table(self):
        # Regression: Stage 1 called decode_yzjm(raw) with one argument, so every
        # decode raised TypeError and the whole matrix reported NOT ESTABLISHED.
        with self.assertRaises(TypeError):
            s1.decode_yzjm(yzjm(b"\x00" * 8))

    def test_missing_and_ambiguous_libraries_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            none = Path(tmp) / "A.zip"
            with zipfile.ZipFile(none, "w") as zf:
                zf.writestr("X.BIN", b"abc")
            table, why = s1.own_format_table(none)
            self.assertIsNone(table)
            self.assertIn("0 libDEVICEID.so candidate(s)", why)

            two = Path(tmp) / "B.zip"
            with zipfile.ZipFile(two, "w") as zf:
                zf.writestr("a/libDEVICEID.so", b"1")
                zf.writestr("b/libDEVICEID.so", b"2")
            table, why = s1.own_format_table(two)
            self.assertIsNone(table)
            self.assertIn("2 libDEVICEID.so candidate(s)", why)

    def test_table_is_read_from_the_archives_own_library_at_the_toyota_va(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "C.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("libDEVICEID.so", b"elf")
            with patch.object(s1, "elf_bytes", return_value=TABLE) as mocked:
                table, why = s1.own_format_table(path)
            self.assertEqual(TABLE, table)
            self.assertEqual("own libDEVICEID.so", why)
            self.assertEqual((0xF88C, 32), (mocked.call_args.args[1], mocked.call_args.args[2]))

    def test_unparsable_library_is_data_not_a_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "D.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("libDEVICEID.so", b"not-an-elf")
            table, why = s1.own_format_table(path)
            self.assertIsNone(table)
            self.assertIn("format table unavailable", why)


class ParserSignalTest(unittest.TestCase):
    def test_yzjm_members_decode_with_the_own_table_and_are_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "SYNTH.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("libDEVICEID.so", b"elf")
                zf.writestr("SOME_DATA.BIN", yzjm(b"\x01\x02\x03\x04" * 4))
            with patch.object(s1, "elf_bytes", return_value=TABLE):
                result = s1.scan_archive(path, "SYNTH.zip", None, run_parsers=True)
            stats = result["parser_stats"]
            self.assertEqual(1, stats.get("yzjm_inputs", 0))
            self.assertEqual(1, stats.get("yzjm_accepted", 0))
            self.assertEqual(0, stats.get("yzjm_rejected", 0))
            self.assertEqual(1, stats.get("format_table", 0))
            self.assertEqual("DATA", result["statuses"]["parser_output_produced"])
            # A borrowed Toyota name is never treated as brand compatibility.
            self.assertEqual("NOT ESTABLISHED", result["statuses"]["brand_family_established"])
            self.assertEqual("NOT ESTABLISHED", result["statuses"]["app_pack_compatible"])
            self.assertEqual("own libDEVICEID.so", result["format_table_provenance"])

    def test_a_toyota_shaped_name_is_counted_as_name_keyed_not_compatible(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "SYNTH.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("libDEVICEID.so", b"elf")
                zf.writestr("MENU.BIN", yzjm(b"not a menu at all\x00\x00\x00"))
            with patch.object(s1, "elf_bytes", return_value=TABLE):
                result = s1.scan_archive(path, "SYNTH.zip", None, run_parsers=True)
            stats = result["parser_stats"]
            self.assertEqual(1, stats.get("toyota_name_keyed_inputs", 0))
            self.assertEqual(0, stats.get("toyota_name_keyed_accepted", 0))
            self.assertEqual(1, stats.get("toyota_name_keyed_rejected", 0))
            self.assertEqual("NOT ESTABLISHED", result["statuses"]["rows_structurally_validated"])

    def test_without_a_table_nothing_is_decoded_and_the_reason_is_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "NOTABLE.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("SOME_DATA.BIN", yzjm(b"\x01\x02\x03\x04" * 4))
            result = s1.scan_archive(path, "NOTABLE.zip", None, run_parsers=True)
            stats = result["parser_stats"]
            self.assertEqual(0, stats.get("format_table", 0))
            self.assertEqual(1, stats.get("yzjm_inputs", 0))
            self.assertEqual(0, stats.get("yzjm_accepted", 0))
            self.assertEqual(1, stats.get("yzjm_skipped_no_table", 0))
            self.assertEqual("NOT ESTABLISHED", result["statuses"]["parser_output_produced"])

    def test_elf_members_are_parsed_not_blanket_rejected(self):
        # Regression: native_info was handed a Path instead of (bytes, name).
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ELF.zip"
            with zipfile.ZipFile(path, "w") as zf:
                zf.writestr("libDEVICEID.so", b"elf")
                zf.writestr("libOTHER.so", b"\x7fELF" + b"\x00" * 60)
            with patch.object(s1, "elf_bytes", return_value=TABLE), \
                    patch.object(s1, "native_info", return_value={"functions": []}) as mocked:
                result = s1.scan_archive(path, "ELF.zip", None, run_parsers=True)
            stats = result["parser_stats"]
            self.assertEqual(2, stats.get("elf_inputs", 0))
            self.assertEqual(2, stats.get("elf_accepted", 0))
            self.assertEqual(0, stats.get("elf_rejected", 0))
            self.assertIsInstance(mocked.call_args.args[0], bytes)

    def test_markdown_exposes_the_new_columns(self):
        report = {"generated_at": "now", "commit_sha": "abc", "archive_count": 1,
                  "expected_archive_count": 73, "controls": {"yzjm_control": "PASS"},
                  "app_integration_points": {}, "consumer_map": [],
                  "brands": [{"archive": "SYNTH.zip", "status": "complete", "actual_bytes": 1,
                              "sha256": "0" * 64, "member_count": 1, "duplicate_member_hashes": 0,
                              "skipped_count": 0, "parser_stats": {"format_table": 1, "yzjm_inputs": 4,
                                                                   "yzjm_accepted": 3,
                                                                   "toyota_name_keyed_inputs": 2,
                                                                   "toyota_name_keyed_accepted": 0},
                              "statuses": {"source_enumerated": "DATA", "parser_output_produced": "DATA",
                                           "rows_structurally_validated": "NOT ESTABLISHED",
                                           "brand_family_established": "NOT ESTABLISHED",
                                           "app_pack_compatible": "NOT ESTABLISHED",
                                           "app_consumes": "NOT ESTABLISHED",
                                           "vehicle_tested": "NOT ESTABLISHED"}}]}
        text = s1.render_markdown(report)
        self.assertIn("Format table", text)
        self.assertIn("1 | 4 / 3 | 2 / 0 |", text)
        self.assertIn("never brand compatibility", text)


if __name__ == "__main__":
    unittest.main()
