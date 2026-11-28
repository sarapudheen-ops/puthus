#!/usr/bin/env python3
"""Synthetic tests for the DAIHATSU-1 system-list reader trace.

No corpus access: every ELF, instruction and payload here is hand-built. The
AArch64 words are assembled from their documented encodings and decoded back
with capstone, so the xref scanner and disassembler are exercised on real
instruction semantics, not on mocks.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
if str(HERE.parent / "thinkdiag") not in sys.path:
    sys.path.insert(0, str(HERE.parent / "thinkdiag"))

import daihatsu_trace as dt  # noqa: E402
from yzjm import decode_yzjm, word_keys, MASK32  # noqa: E402


# ------------------------------------------------------------- AArch64 words

def adrp(rd: int, page: int, pc: int) -> int:
    imm = (page - (pc & ~0xFFF)) >> 12
    return 0x90000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7FFFF) << 5) | rd


def add_imm(rd: int, rn: int, imm12: int) -> int:
    return 0x91000000 | (imm12 << 10) | (rn << 5) | rd


def ldr_imm(rt: int, rn: int, off: int) -> int:
    assert off % 8 == 0
    return 0xF9400000 | ((off // 8) << 10) | (rn << 5) | rt


def adr(rd: int, target: int, pc: int) -> int:
    imm = target - pc
    return 0x10000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7FFFF) << 5) | rd


def bl(target: int, pc: int) -> int:
    imm = (target - pc) >> 2
    return 0x94000000 | (imm & 0x3FFFFFF)


RET = 0xD65F03C0
MOVZ_W0_1 = 0x52800020  # movz w0, #1


def build_elf(functions: list[tuple[str, list[int]]], rodata: bytes = b"",
              rela: list[tuple[int, int, int]] = (), text_va: int = 0x10000,
              rodata_va: int = 0x15000) -> bytes:
    """A minimal but valid little-endian AArch64 ELF with one PT_LOAD per region.

    functions: (name, words) laid out consecutively in .text; each becomes a
    defined STT_FUNC dynamic symbol sized to its own words. rela entries are
    (slot VA, type, addend) placed in .rela.dyn.
    """
    code = b"".join(struct.pack("<I", w) for _, words in functions for w in words)
    rodata = rodata or b"\0" * 0x1000
    dynstr = b"\0" + b"\0".join(name.encode() for name, _ in functions) + b"\0"
    dynsym = struct.pack("<IBBHQQ", 0, 0, 0, 0, 0, 0)
    cursor = text_va
    str_pos = 1
    for name, words in functions:
        size = len(words) * 4
        dynsym += struct.pack("<IBBHQQ", str_pos, 0x12, 0, 1, cursor, size)
        cursor += size
        str_pos += len(name) + 1
    rela_bytes = b"".join(struct.pack("<QQq", off, typ, add) for off, typ, add in rela)
    shstr = b"\0.text\0.rodata\0.dynsym\0.dynstr\0.rela.dyn\0.shstrtab\0"
    names = {n: shstr.index(b"\0" + n.encode() + b"\0") + 1
             for n in (".text", ".rodata", ".dynsym", ".dynstr", ".rela.dyn", ".shstrtab")}
    off = 64 + 56 * 2

    def place(data: bytes, align: int = 8) -> int:
        nonlocal off
        off = (off + align - 1) & ~(align - 1)
        start = off
        off += len(data)
        return start

    text_o = place(code, 4)
    rodata_o = place(rodata)
    dynstr_o = place(dynstr)
    dynsym_o = place(dynsym)
    rela_o = place(rela_bytes) if rela_bytes else 0
    shstr_o = place(shstr)
    shoff = (off + 7) & ~7
    sections = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (names[".text"], 1, 6, text_va, text_o, len(code), 0, 0, 4, 0),
        (names[".rodata"], 1, 2, rodata_va, rodata_o, len(rodata), 0, 0, 8, 0),
        (names[".dynsym"], 11, 2, 0, dynsym_o, len(dynsym), 4, 1, 8, 24),
        (names[".dynstr"], 3, 2, 0, dynstr_o, len(dynstr), 0, 0, 1, 0),
        (names[".rela.dyn"], 4, 2, 0, rela_o, len(rela_bytes), 3, 0, 8, 24),
        (names[".shstrtab"], 3, 0, 0, shstr_o, len(shstr), 0, 0, 1, 0),
    ]
    total = shoff + 64 * len(sections)
    hdr = struct.pack("<16sHHIQQQIHHHHHH",
                      b"\x7fELF\x02\x01\x01\x00" + b"\0" * 8, 2, 183, 1, text_va, 64, shoff,
                      0, 64, 56, 2, 64, len(sections), 6)
    ph1 = struct.pack("<IIQQQQQQ", 1, 5, text_o, text_va, text_va, len(code), len(code), 0x1000)
    ph2 = struct.pack("<IIQQQQQQ", 1, 6, rodata_o, rodata_va, rodata_va, len(rodata), len(rodata), 0x1000)
    blob = bytearray(total)
    blob[0:64] = hdr
    blob[64:120] = ph1
    blob[120:176] = ph2
    blob[text_o:text_o + len(code)] = code
    blob[rodata_o:rodata_o + len(rodata)] = rodata
    blob[dynstr_o:dynstr_o + len(dynstr)] = dynstr
    blob[dynsym_o:dynsym_o + len(dynsym)] = dynsym
    if rela_bytes:
        blob[rela_o:rela_o + len(rela_bytes)] = rela_bytes
    blob[shstr_o:shstr_o + len(shstr)] = shstr
    for i, section in enumerate(sections):
        blob[shoff + 64 * i:shoff + 64 * i + 64] = struct.pack("<IIQQQQIIQQ", *section)
    return bytes(blob)


def literal_rodata() -> bytes:
    rodata = bytearray(0x1000)
    lit = b"DAIHATSU_SYS_LIST.BIN\0"
    rodata[0xA07:0xA07 + len(lit)] = lit
    return bytes(rodata)


def reader_elf() -> bytes:
    """GetSystemListData references the literal three ways; a negative adrp+add
    targets a different page; a bl reaches a second symbolized function."""
    pc = 0x10000
    main = [
        adrp(0, 0x15000, pc),          # 0x10000
        add_imm(0, 0, 0xA07),          # 0x10004 -> 0x15a07
        bl(0x10030, pc + 8),           # 0x10008 -> helper
        RET,                           # 0x1000c
        adrp(1, 0x16000, pc + 16),     # 0x10010 negative control
        add_imm(1, 1, 0xA07),          # 0x10014 -> 0x16a07 (not the literal)
        RET,                           # 0x10018
        adrp(2, 0x15000, pc + 24),     # 0x1001c
        ldr_imm(2, 2, 0x200),          # 0x10020 slot 0x15200 -> relative 0x15a07
        RET,                           # 0x10024
        adr(3, 0x15A07, pc + 40),      # 0x10028
        RET,                           # 0x1002c
    ]
    helper = [MOVZ_W0_1, RET]          # 0x10030, 0x10034
    return build_elf([("GetSystemListData", main), ("helper", helper)],
                     rodata=literal_rodata(), rela=[(0x15200, 1027, 0x15A07)])


# ------------------------------------------------------------------- tests

class ReportTargetTest(unittest.TestCase):
    def test_extracts_exact_pair(self):
        state = {"targets": [
            {"archive": "TOYOTA.zip", "family": "keyed-index-8", "sha256": "a" * 64},
            {"archive": "DAIHATSU.zip", "family": "keyed-index-8", "sha256": "b" * 64},
            {"archive": "DAIHATSU.zip", "family": "system-list-37", "sha256": "c" * 64},
        ]}
        self.assertEqual(dt.report_target(state)["sha256"], "b" * 64)

    def test_missing_pair_is_error(self):
        with self.assertRaises(ValueError):
            dt.report_target({"targets": []})

    def test_ambiguous_pair_is_error(self):
        row = {"archive": "DAIHATSU.zip", "family": "keyed-index-8", "sha256": "b" * 64}
        with self.assertRaises(ValueError):
            dt.report_target({"targets": [row, dict(row)]})


class VerifyArchiveTest(unittest.TestCase):
    def test_match_and_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "DAIHATSU.zip"
            payload = b"synthetic archive bytes"
            path.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            result = dt.verify_archive(path, digest)
            self.assertTrue(result["match"])
            self.assertEqual(result["sha256"], digest)
            with self.assertRaises(ValueError):
                dt.verify_archive(path, "0" * 64)


class ElfTraceTest(unittest.TestCase):
    def setUp(self):
        self.elf = dt.Elf(reader_elf(), "libDAIHATSU_FILE.so")

    def test_exports_and_literal(self):
        self.assertIn("GetSystemListData", self.elf.exports)
        self.assertIn("helper", self.elf.exports)
        self.assertEqual(self.elf.c_string(0x15A07), "DAIHATSU_SYS_LIST.BIN")
        self.assertIsNone(self.elf.c_string(0x16A07))

    def test_xrefs_positive_and_negative(self):
        sites = dt.find_literal_xrefs(self.elf, 0x15A07)
        kinds = {site["va"]: site["kind"] for site in sites}
        self.assertEqual(kinds, {0x10004: "adrp+add",
                                 0x10020: "adrp+ldr->relative-pointer",
                                 0x10028: "adr"})
        # The negative adrp+add to 0x16a07 must not appear.
        self.assertNotIn(0x10014, kinds)
        for site in sites:
            self.assertEqual(site["function"], "GetSystemListData")
            self.assertEqual(site["functionStart"], 0x10000)

    def test_disassembly_annotations(self):
        rows = dt.disassemble(self.elf, 0x10000, 12)
        text = dt.render_disassembly(rows)
        self.assertIn("0x15a07: 'DAIHATSU_SYS_LIST.BIN'", text)
        self.assertIn("helper", text)  # bl target resolved by name
        self.assertEqual(rows[0]["mnemonic"], "adrp")
        self.assertEqual(rows[2]["mnemonic"], "bl")

    def test_trace_follows_internal_callee(self):
        budget = {"functions": 0, "instructions": 0, "callers": 0}
        seeds = [{"start": 0x10000, "name": "GetSystemListData", "size": 48,
                  "reason": "test seed"}]
        traced = dt.trace_functions(self.elf, seeds, budget)
        names = {entry["name"] for entry in traced}
        self.assertIn("GetSystemListData", names)
        self.assertIn("helper", names)
        helper = next(entry for entry in traced if entry["name"] == "helper")
        self.assertEqual(helper["depth"], 1)
        self.assertEqual(helper["start"], 0x10030)

    def test_function_at(self):
        row = self.elf.function_at(0x10004)
        self.assertEqual(row["name"], "GetSystemListData")
        self.assertIsNone(self.elf.function_at(0x99999))


class IndexReconciliationTest(unittest.TestCase):
    @staticmethod
    def payload(index_rows, blocks: dict[int, bytes], sentinel: bool = True) -> bytes:
        out = bytearray()
        for key, value in index_rows:
            out += struct.pack("<II", key, value)
        if sentinel:
            out += struct.pack("<I", 0xFFFFFFFF)
        for offset, block in sorted(blocks.items()):
            while len(out) < offset:
                out += b"\0"
            out += block
        return bytes(out)

    def test_accepted_index_and_blocks(self):
        block = struct.pack("<H", 2) + b"\x11" * 74
        data = self.payload([(0x100, 0x20)], {0x20: block})
        result = dt.reconcile_system_list(data)
        self.assertTrue(result["sentinelIndex"]["accepted"])
        self.assertEqual(result["sentinelIndex"]["rows"], 1)
        self.assertTrue(result["systemLists37"]["accepted"])
        self.assertEqual(result["systemLists37"]["packedRows"], 2)
        self.assertEqual(result["reconciliation"]["indexRows"], 1)
        self.assertEqual(result["reconciliation"]["rowsWithValid37ByteBlocks"], 1)
        self.assertEqual(result["perRowBasis"], "sentinel_index")

    def test_duplicate_offsets_are_reconciled(self):
        block = struct.pack("<H", 1) + b"\x22" * 37
        data = self.payload([(0x100, 0x20), (0x101, 0x20)], {0x20: block})
        result = dt.reconcile_system_list(data)
        self.assertTrue(result["sentinelIndex"]["accepted"])
        self.assertEqual(result["reconciliation"]["indexRows"], 2)
        self.assertEqual(result["reconciliation"]["distinctOffsets"], 1)
        self.assertEqual(result["reconciliation"]["duplicateOffsetRows"], 2)
        self.assertEqual(result["duplicateOffsets"], [0x20])
        # system_lists shares the block, so packed rows count it once.
        self.assertEqual(result["systemLists37"]["packedRows"], 1)

    def test_duplicate_keys_rejected_but_raw_scan_reconciles(self):
        block = struct.pack("<H", 1) + b"\x33" * 37
        data = self.payload([(0x100, 0x20), (0x100, 0x40)], {0x20: block, 0x40: block})
        result = dt.reconcile_system_list(data)
        self.assertFalse(result["sentinelIndex"]["accepted"])
        self.assertIn("Duplicate", result["sentinelIndex"]["error"])
        raw = result["rawIndexScan"]
        self.assertEqual(raw["rowCount"], 2)
        self.assertEqual(len(raw["duplicateKeys"]), 1)
        self.assertEqual(result["perRowBasis"], "rawIndexScan")
        self.assertEqual(result["reconciliation"]["indexRows"], 2)

    def test_missing_sentinel_is_truncated(self):
        data = struct.pack("<II", 0x100, 0x20) + b"\0" * 16
        result = dt.reconcile_system_list(data)
        self.assertFalse(result["sentinelIndex"]["accepted"])
        self.assertTrue(result["rawIndexScan"]["truncated"])
        self.assertFalse(result["rawIndexScan"]["sentinelFound"])

    def test_offset_past_end_is_rejected(self):
        data = self.payload([(0x100, 0xFFFF)], {})
        result = dt.reconcile_system_list(data)
        row = result["perRow"][0]
        self.assertIn("rejected", row["status"])
        self.assertEqual(result["reconciliation"]["rowsWithValid37ByteBlocks"], 0)

    def test_18_byte_blocks_tile_and_37_does_not(self):
        # Two blocks of 18-byte records: block0 @0x20 count2 -> end 0x46; block1 @0x46 count1.
        rec = bytes(range(18))
        block0 = struct.pack("<H", 2) + rec + rec
        block1 = struct.pack("<H", 1) + rec
        data = self.payload([(0x100, 0x20), (0x101, 0x46)], {0x20: block0, 0x46: block1})
        result = dt.reconcile_system_list(data)
        b18 = result["blocks18"]
        self.assertEqual(b18["accepted"], 2)
        self.assertEqual(b18["rejected"], 0)
        b37 = result["blocks37"]
        self.assertEqual(b37["accepted"], 0)
        self.assertEqual(b37["rejected"], 2)
        records = result["firstBlockRecords18"]
        self.assertEqual(len(records), 2)
        self.assertEqual(records[0]["le16_0"], int.from_bytes(rec[0:2], "little"))
        self.assertEqual(records[0]["le32_2"], int.from_bytes(rec[2:6], "little"))
        self.assertEqual(records[0]["u8_9"], rec[9])
        self.assertEqual(records[0]["le32_14"], int.from_bytes(rec[14:18], "little"))

    def test_reconcile_blocks_gap_and_truncation(self):
        rec = bytes(18)
        # block0 @0x20 count1 -> end 0x34; next block @0x40 leaves a gap.
        data = self.payload([(0x100, 0x20), (0x101, 0x40)],
                            {0x20: struct.pack("<H", 1) + rec, 0x40: struct.pack("<H", 1) + rec})
        result = dt.reconcile_blocks(data, [0x20, 0x40], 18)
        self.assertEqual(result["rows"][0]["status"], "rejected: block end does not meet next block (gap)")
        self.assertEqual(result["rows"][1]["status"], "accepted: tiles exactly")
        truncated = struct.pack("<H", 5) + bytes(18)  # claims 5 records, only 1 present
        result2 = dt.reconcile_blocks(truncated, [0], 18)
        self.assertIn("past end", result2["rows"][0]["status"])


def rol32(value: int, bits: int) -> int:
    return ((value << bits) | (value >> (32 - bits))) & MASK32


def encode_yzjm(payload: bytes, make: str, version: str, seed: int, table: bytes) -> bytes:
    """Inverse of decode_yzjm, for synthetic wrapped members only."""
    keys = word_keys(make, version, seed, table)

    def string(text: str) -> bytes:
        raw = text.encode("ascii") + b"\0"
        return struct.pack("<H", len(raw)) + raw

    out = bytearray(string("YZJM") + string(make) + string(version) + struct.pack("<I", seed))
    assert len(payload) % 4 == 0
    for index, (word,) in enumerate(struct.iter_unpack(">I", payload)):
        encoded = word if word in (0, MASK32) else rol32(word ^ keys[index % 8], 22)
        out += struct.pack(">I", encoded)
    return bytes(out)


class BuildStateIntegrationTest(unittest.TestCase):
    """The full pipeline on a hand-built archive: no corpus, real decode path."""

    def test_full_pipeline(self):
        table = bytes(range(32))
        # libDEVICEID.so carries the 32-byte format table at VA 0xF88C.
        device_rodata = bytearray(0x1000)
        device_rodata[0x88C:0x88C + 32] = table
        device = build_elf([("dummy", [RET])], rodata=bytes(device_rodata), rodata_va=0xF000)
        reader = reader_elf()
        # A system-list payload: one index row -> a block of two 37-byte rows.
        block = struct.pack("<H", 2) + bytes(range(74))
        payload = (struct.pack("<II", 0x100, 0x20) + struct.pack("<I", 0xFFFFFFFF)
                   + b"\0" * (0x20 - 12) + block)
        wrapped = encode_yzjm(payload, "DAIHATSU", "V10.22", 0x12345678, table)
        self.assertEqual(decode_yzjm(wrapped, table).payload, payload)
        member = "DIAGNOSTIC/VEHICLES/DAIHATSU/V10.22/DAIHATSU_SYS_LIST.BIN"
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "DAIHATSU.zip"
            with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.writestr(member, wrapped)
                zf.writestr("libDEVICEID.so", device)
                zf.writestr("libDAIHATSU_FILE.so", reader)
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            inventory8 = {"headSha": "synthetic-source", "targets": [{
                "archive": "DAIHATSU.zip", "brand": "DAIHATSU", "family": "keyed-index-8",
                "sha256": digest, "membersExamined": [member],
                "consumerIdentity": [{"library": "libDAIHATSU_FILE.so"}],
                "filenameLinks": [{"library": "libDAIHATSU_FILE.so", "va": 0x15A07}],
                "inventory7Verdict": "CANDIDATE", "parsed": 1, "attempted": 2,
                "wrapperConfidence": "established", "verdict": "FILENAME LINK, CODE DIFFERS"}]}
            state = dt.build_state(archive, inventory8, "synthetic-head")
        self.assertFalse(state["failed"], state["errors"])
        self.assertTrue(state["verification"]["match"])
        self.assertEqual(state["literal"]["text"], "DAIHATSU_SYS_LIST.BIN")
        self.assertTrue(state["literal"]["match"])
        self.assertTrue(state["xrefs"])
        traced = {entry["name"] for entry in state["trace"]}
        self.assertIn("GetSystemListData", traced)
        self.assertIn("helper", traced)
        self.assertEqual(len(state["members"]), 1)
        entry = state["members"][0]
        self.assertEqual(entry["status"], "decoded")
        self.assertEqual(entry["decodedSha256"], hashlib.sha256(payload).hexdigest())
        self.assertEqual(entry["make"], "DAIHATSU")
        self.assertEqual(entry["version"], "V10.22")
        analysis = entry["systemList"]
        self.assertTrue(analysis["sentinelIndex"]["accepted"])
        self.assertTrue(analysis["systemLists37"]["accepted"])
        self.assertEqual(analysis["systemLists37"]["packedRows"], 2)
        self.assertEqual(analysis["reconciliation"]["rowsWithValid37ByteBlocks"], 1)
        # The report renders and stays within the check-part bound.
        with tempfile.TemporaryDirectory() as tmp:
            result = dt.emit(state, Path(tmp))
            self.assertEqual(result["conclusion"], "success")
            for part in result["parts"]:
                self.assertLessEqual(part["bytes"], 60000)

    def test_hash_mismatch_stops(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "DAIHATSU.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("libDAIHATSU_FILE.so", reader_elf())
            inventory8 = {"targets": [{"archive": "DAIHATSU.zip", "family": "keyed-index-8",
                                       "sha256": "0" * 64}]}
            with self.assertRaises(ValueError):
                dt.build_state(archive, inventory8, "synthetic-head")


class EmitTest(unittest.TestCase):
    def test_parts_stay_within_check_limit(self):
        state = {
            "format": dt.FORMAT, "formatVersion": dt.FORMAT_VERSION,
            "generatedUtc": "2026-11-28T00:00:00+00:00", "headSha": "synthetic",
            "label": dt.LABEL, "inventory8HeadSha": "synthetic",
            "reportTarget": {"archive": "DAIHATSU.zip", "family": "keyed-index-8",
                             "inventory7Verdict": "CANDIDATE", "parsed": 1, "attempted": 2,
                             "verdict": "FILENAME LINK, CODE DIFFERS", "membersExamined": []},
            "verification": {"archive": "DAIHATSU.zip", "bytes": 1, "sha256": "0" * 64,
                             "expectedSha256": "0" * 64, "match": True},
            "budget": {"functions": 0, "instructions": 0, "callers": 0},
            "libraries": [], "members": [], "xrefs": [], "trace": [], "callers": [],
            "strings": [], "failed": False, "errors": [],
        }
        with tempfile.TemporaryDirectory() as tmp:
            result = dt.emit(state, Path(tmp))
            self.assertEqual(result["conclusion"], "success")
            for part in result["parts"]:
                self.assertLessEqual(part["bytes"], 60000)
            self.assertTrue((Path(tmp) / "daihatsu-trace.json").is_file())
            self.assertTrue((Path(tmp) / "parts.json").is_file())

    def test_failed_state_conclusion(self):
        state = {"failed": True, "verification": {"archive": "DAIHATSU.zip", "bytes": 0,
                                                  "sha256": "", "expectedSha256": "", "match": False},
                 "reportTarget": {"archive": "DAIHATSU.zip", "family": "keyed-index-8",
                                  "inventory7Verdict": "", "parsed": 0, "attempted": 0,
                                  "verdict": "", "membersExamined": []},
                 "headSha": "synthetic", "generatedUtc": "", "label": "",
                 "inventory8HeadSha": None, "budget": {}, "libraries": [], "members": [],
                 "xrefs": [], "trace": [], "callers": [], "strings": [], "errors": ["boom"]}
        with tempfile.TemporaryDirectory() as tmp:
            result = dt.emit(state, Path(tmp))
            self.assertEqual(result["conclusion"], "failure")


if __name__ == "__main__":
    unittest.main()
