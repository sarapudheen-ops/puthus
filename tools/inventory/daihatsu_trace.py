#!/usr/bin/env python3
"""DAIHATSU-1: trace the linked system-list reader in DAIHATSU.zip (read-only).

One archive, one objective. Inventory-8 recorded exactly one filename link for
this brand -- ``libDAIHATSU_FILE.so`` VA ``0x15a07`` holds the literal
``DAIHATSU_SYS_LIST.BIN`` -- and two own-role consumers whose code differs from
Toyota's. This tool reproduces that target and disassembles the brand's own
reader so a human can judge what the code actually does with the decoded bytes.

What it does:

* verifies the downloaded archive against the exact SHA-256 recorded for
  ``DAIHATSU.zip`` in the Inventory-8 report (never a guessed hash);
* verifies the exact ``DAIHATSU_SYS_LIST.BIN`` member and ``libDAIHATSU_FILE.so``
  by SHA-256, decodes the member with the archive's own ``libDEVICEID.so``
  format table, and confirms the literal at the reported VA;
* finds every code cross-reference to that literal (adrp+add, adrp+ldr, adr,
  and RELATIVE-relocated data pointers) and maps each to its function;
* disassembles the referenced functions, the cited consumers, their internal
  callees and their imported callees resolved inside this archive, plus
  cross-library callers of the cited exports -- all bounded;
* decodes every BIN member of the archive, runs the existing sentinel-index and
  system-list readers on the payloads, and reconciles accepted, rejected and
  duplicate rows against the raw bytes.

What it never does: it asserts no field meaning, invents no record layout,
writes no converter, pack or app change, and executes no vendor code. Field
meanings are established (or left opaque) by a human reading the published
disassembly, never by an export name or an integer value.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
THINKDIAG = HERE.parent / "thinkdiag"
for _path in (str(HERE), str(THINKDIAG)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

import brand_inventory as old  # noqa: E402
import family_evidence as fe  # noqa: E402
from analyze_toyota import native_info  # noqa: E402
from elftools.elf.elffile import ELFFile  # noqa: E402
from toyota_structure import sentinel_index, system_lists, system_fields  # noqa: E402
from yzjm import decode_yzjm  # noqa: E402

FORMAT = "bod3-daihatsu-trace"
FORMAT_VERSION = 1
LABEL = ("read-only trace: verification, cross-references and disassembly are observed; "
         "this tool asserts no field meaning and enables no operation")
BRAND = "DAIHATSU"
ARCHIVE_NAME = "DAIHATSU.zip"
FAMILY_KEY = "keyed-index-8"
CONSUMER_NAMES = ("GetSystemListData", "GetVehicleInfoData")
MAX_LIBRARY_BYTES = 24 * 1024 * 1024
MAX_LIBRARIES = 64
MAX_FUNCTIONS = 48
MAX_INSTRUCTIONS = 60000
MAX_CALLER_FUNCTIONS = 12
MAX_STRINGS = 600
MAX_BLOCK_PREVIEW = 96
ASCII_RUN = re.compile(rb"[\x20-\x7e]{4,200}")


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# --------------------------------------------------------------------- report

def report_target(state: dict) -> dict:
    """The exact DAIHATSU pair Inventory-8 measured, with its archive hash."""
    matches = [row for row in state.get("targets", [])
               if row.get("archive") == ARCHIVE_NAME and row.get("family") == FAMILY_KEY]
    if len(matches) != 1:
        raise ValueError(f"Inventory-8 must record exactly one {ARCHIVE_NAME} {FAMILY_KEY} pair; "
                         f"found {len(matches)}")
    return matches[0]


def verify_archive(path: Path, expected_sha256: str) -> dict:
    data = path.read_bytes()
    actual = sha256(data)
    if actual != expected_sha256:
        raise ValueError(f"{ARCHIVE_NAME} SHA-256 {actual} does not match the Inventory-8 report "
                         f"hash {expected_sha256}; refusing to trace a different archive")
    return {"archive": ARCHIVE_NAME, "bytes": len(data), "sha256": actual,
            "expectedSha256": expected_sha256, "match": True}


# ------------------------------------------------------------------------ ELF

class Elf:
    """One bounded, read-only AArch64 ELF view: symbols, PLT, relocations, memory."""

    def __init__(self, data: bytes, name: str):
        if len(data) > MAX_LIBRARY_BYTES:
            raise ValueError(f"{name} exceeds the {MAX_LIBRARY_BYTES}-byte library bound")
        self.data = data
        self.name = name
        self.sha256 = sha256(data)
        self.elf = ELFFile(io.BytesIO(data))
        if self.elf.get_machine_arch() != "AArch64" or not self.elf.little_endian:
            raise ValueError(f"{name} is not little-endian AArch64")
        self.loads = [s.header for s in self.elf.iter_segments() if s.header.p_type == "PT_LOAD"]
        self.exec_ranges = [(s.header.sh_addr, s.header.sh_size) for s in self.elf.iter_sections()
                            if s.header.sh_type == "SHT_PROGBITS" and s.header.sh_flags & 4
                            and s.header.sh_size]
        self.functions: dict[int, dict] = {}
        for section_name in (".symtab", ".dynsym"):
            section = self.elf.get_section_by_name(section_name)
            if section is None:
                continue
            for symbol in section.iter_symbols():
                if (symbol.name and symbol.entry.st_info.type == "STT_FUNC"
                        and symbol.entry.st_shndx != "SHN_UNDEF" and symbol.entry.st_value):
                    self.functions.setdefault(symbol.entry.st_value, {
                        "name": symbol.name, "size": symbol.entry.st_size, "source": section_name})
        self.exports = sorted(row["name"] for row in self.functions.values()
                              if row["source"] == ".dynsym")
        dynsym = self.elf.get_section_by_name(".dynsym")
        self.imports: list[str] = []
        if dynsym is not None:
            self.imports = sorted({s.name for s in dynsym.iter_symbols()
                                   if s.name and s.entry.st_shndx == "SHN_UNDEF"})
        self.got: dict[int, str] = {}
        self.relative: dict[int, int] = {}  # slot VA -> RELATIVE addend (a linked VA)
        for section in self.elf.iter_sections():
            if not section.name.startswith(".rela"):
                continue
            symtable = self.elf.get_section(section.header.sh_link)
            for reloc in section.iter_relocations():
                rtype = reloc.entry.r_info_type
                if rtype == 1027:  # R_AARCH64_RELATIVE: no symbol, addend is a linked VA
                    self.relative[reloc.entry.r_offset] = reloc.entry.get("r_addend", 0)
                elif symtable is not None and rtype in (1025, 1026, 257):  # GLOB_DAT/JUMP_SLOT/ABS64
                    symbol = symtable.get_symbol(reloc.entry.r_info_sym)
                    self.got[reloc.entry.r_offset] = symbol.name or hex(reloc.entry.get("r_addend", 0))
        self.plt: dict[int, str] = {}
        self._build_plt()

    def _build_plt(self) -> None:
        """Map each PLT stub to its import by decoding the stub's own GOT load.

        The stub layout (32-byte header, 16-byte entries) is the standard one
        static_readers.py already assumes, but the mapping is verified against
        the stub's adrp+ldr target instead of trusted from the index alone.
        """
        plt = self.elf.get_section_by_name(".plt")
        relplt = self.elf.get_section_by_name(".rela.plt")
        dynsym = self.elf.get_section_by_name(".dynsym")
        if plt is None or relplt is None or dynsym is None:
            return
        by_slot: dict[int, str] = {}
        for reloc in relplt.iter_relocations():
            symbol = dynsym.get_symbol(reloc.entry.r_info_sym)
            if symbol.name:
                by_slot[reloc.entry.r_offset] = symbol.name
        from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
        from capstone.arm64 import ARM64_OP_MEM
        md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        md.detail = True
        base, size = plt.header.sh_addr, plt.header.sh_size
        try:
            code = self.memory(base, size)
        except ValueError:
            return
        page: dict[int, int] = {}
        stub_start: int | None = None
        for ins in md.disasm(code, base):
            ops = ins.operands
            if ins.mnemonic == "adrp" and len(ops) == 2:
                page[ops[0].reg] = ops[1].imm
                stub_start = ins.address
            elif ins.mnemonic == "ldr" and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
                mem = ops[1].mem
                if mem.base in page and not mem.index:
                    slot = page[mem.base] + mem.disp
                    if slot in by_slot and stub_start is not None:
                        self.plt[stub_start] = by_slot[slot] + "@plt"
            elif ins.mnemonic in ("br", "blr"):
                page.clear()
                stub_start = None

    def memory(self, va: int, size: int) -> bytes:
        for header in self.loads:
            offset = va - header.p_vaddr
            if 0 <= offset and offset + size <= header.p_filesz:
                return self.data[header.p_offset + offset:header.p_offset + offset + size]
        raise ValueError(f"{self.name}: VA {va:#x}+{size} is outside every file-backed PT_LOAD")

    def available(self, va: int) -> int:
        """File-backed bytes readable from va inside its own PT_LOAD segment."""
        for header in self.loads:
            offset = va - header.p_vaddr
            if 0 <= offset < header.p_filesz:
                return header.p_filesz - offset
        return 0

    def preview(self, va: int, size: int = 48) -> str:
        if va in self.got:
            return f"reloc {self.got[va]}"
        if va in self.relative:
            return f"relative -> {self.relative[va]:#x}"
        try:
            block = self.memory(va, size)
        except ValueError:
            return ""
        if not block:
            return ""
        text = block.split(b"\0", 1)[0]
        if len(text) >= 3 and all(32 <= b < 127 for b in text):
            return repr(text.decode("ascii"))
        return block[:24].hex(" ")

    def target_name(self, va: int) -> str:
        if va in self.plt:
            return self.plt[va]
        row = self.functions.get(va)
        if row:
            return row["name"]
        for start, info in self.functions.items():
            if info["size"] and start <= va < start + info["size"]:
                return f"{info['name']}+{va - start:#x}"
        return ""

    def function_at(self, va: int) -> dict | None:
        best = None
        for start, info in self.functions.items():
            if info["size"] and start <= va < start + info["size"]:
                if best is None or start > best["start"]:
                    best = {"start": start, **info}
        return best

    def c_string(self, va: int, limit: int = 200) -> str | None:
        try:
            block = self.memory(va, limit)
        except ValueError:
            return None
        end = block.find(b"\0")
        if end < 0:
            return None
        text = block[:end]
        if not text or not all(32 <= b < 127 for b in text):
            return None
        return text.decode("ascii")


# --------------------------------------------------------------- disassembly

def disassemble(elf: Elf, start: int, size: int) -> list[dict]:
    """Instruction rows with the repo's standard adrp/add/ldr/call annotations."""
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_OP_MEM
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    code = elf.memory(start, size)
    known: dict[int, int] = {}
    rows: list[dict] = []
    for ins in md.disasm(code, start):
        comment = ""
        ops = ins.operands
        if ins.mnemonic in ("bl", "b") and ops and ops[0].type == ARM64_OP_IMM:
            comment = elf.target_name(ops[0].imm)
        elif ins.mnemonic.startswith("b.") and ops and ops[0].type == ARM64_OP_IMM:
            comment = elf.target_name(ops[0].imm)
        elif ins.mnemonic in ("adrp", "adr") and len(ops) == 2:
            known[ops[0].reg] = ops[1].imm
            comment = f"= {ops[1].imm:#x}"
        elif (ins.mnemonic == "add" and len(ops) == 3 and ops[2].type == ARM64_OP_IMM
              and ops[1].reg in known):
            address = known[ops[1].reg] + ops[2].imm
            known[ops[0].reg] = address
            comment = f"{address:#x}: {elf.preview(address)}"
        elif ins.mnemonic.startswith("ldr") and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
            mem = ops[1].mem
            if mem.base in known and not mem.index:
                address = known[mem.base] + mem.disp
                comment = f"[{address:#x}] {elf.preview(address)}"
            known.pop(ops[0].reg, None)
        elif ops and ops[0].type == ARM64_OP_REG and ins.mnemonic not in (
                "cmp", "tst", "str", "strb", "strh", "stp"):
            known.pop(ops[0].reg, None)
        rows.append({"va": ins.address, "mnemonic": ins.mnemonic, "ops": ins.op_str,
                     "comment": comment, "bytes": ins.bytes.hex()})
        if ins.mnemonic in ("bl", "blr"):
            known = {reg: value for reg, value in known.items()
                     if md.reg_name(reg) in {f"x{i}" for i in range(19, 29)}}
    return rows


def render_disassembly(rows: list[dict]) -> str:
    out = []
    for row in rows:
        line = f"{row['va']:08x}: {row['mnemonic']:8} {row['ops']}"
        if row["comment"]:
            line += f"  ; {row['comment']}"
        out.append(line)
    return "\n".join(out)


def find_literal_xrefs(elf: Elf, target_va: int) -> list[dict]:
    """Every static code reference computing target_va, with its function.

    Covers adrp+add (the common string-address form), adrp+ldr (a literal loaded
    through a GOT/data slot), adr, and a RELATIVE-relocated pointer slot whose
    addend is target_va (a global char* initialised to the literal).
    """
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_OP_MEM
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    slots = {slot for slot, addend in elf.relative.items() if addend == target_va}
    sites: list[dict] = []

    def record(va: int, kind: str, detail: str) -> None:
        function = elf.function_at(va)
        sites.append({"va": va, "kind": kind, "detail": detail,
                      "function": function["name"] if function else None,
                      "functionStart": function["start"] if function else None,
                      "functionSize": function["size"] if function else None})

    for base, size in elf.exec_ranges:
        try:
            code = elf.memory(base, size)
        except ValueError:
            continue
        pending: dict[int, tuple[int, int]] = {}  # reg -> (page value, adrp va)
        for ins in md.disasm(code, base):
            ops = ins.operands
            mnemonic = ins.mnemonic
            if mnemonic == "adrp" and len(ops) == 2 and ops[1].type == ARM64_OP_IMM:
                pending[ops[0].reg] = (ops[1].imm, ins.address)
            elif mnemonic == "adr" and len(ops) == 2 and ops[1].type == ARM64_OP_IMM:
                if ops[1].imm == target_va:
                    record(ins.address, "adr", f"adr {md.reg_name(ops[0].reg)}, {target_va:#x}")
            elif (mnemonic == "add" and len(ops) == 3 and ops[2].type == ARM64_OP_IMM
                  and ops[1].reg in pending):
                page, adrp_va = pending[ops[1].reg]
                value = page + ops[2].imm
                if value == target_va:
                    record(ins.address, "adrp+add",
                           f"adrp@{adrp_va:#x} page {page:#x} + {ops[2].imm:#x} = {target_va:#x}")
                pending.pop(ops[0].reg, None)
            elif mnemonic.startswith("ldr") and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
                mem = ops[1].mem
                if mem.base in pending and not mem.index:
                    page, adrp_va = pending[mem.base]
                    address = page + mem.disp
                    if address == target_va:
                        record(ins.address, "adrp+ldr",
                               f"adrp@{adrp_va:#x} page {page:#x} + {mem.disp:#x} = {target_va:#x}")
                    elif address in slots:
                        record(ins.address, "adrp+ldr->relative-pointer",
                               f"slot {address:#x} holds RELATIVE addend {target_va:#x}")
                pending.pop(ops[0].reg, None)
            elif ops and ops[0].type == ARM64_OP_REG and mnemonic not in (
                    "cmp", "tst", "str", "strb", "strh", "stp", "cmn", "ccmp"):
                pending.pop(ops[0].reg, None)
    return sites


# ------------------------------------------------------------------- tracing

def read_library(path: Path, basename: str) -> bytes | None:
    with zipfile.ZipFile(path) as archive:
        matches = [i for i in archive.infolist()
                   if not i.is_dir() and PurePosixPath(i.filename).name == basename]
        if len(matches) != 1:
            return None
        if matches[0].file_size > MAX_LIBRARY_BYTES:
            return None
        return archive.read(matches[0])


def archive_libraries(path: Path) -> list[str]:
    with zipfile.ZipFile(path) as archive:
        names = sorted({PurePosixPath(i.filename).name for i in archive.infolist()
                        if not i.is_dir() and i.filename.lower().endswith(".so")
                        and i.file_size <= MAX_LIBRARY_BYTES})
    return names[:MAX_LIBRARIES]


def trace_functions(elf: Elf, seeds: list[dict], budget: dict) -> list[dict]:
    """Disassemble seeds, then their internal callees, bounded by the budget."""
    traced: list[dict] = []
    seen: set[int] = set()
    queue = [(seed, 0) for seed in seeds]
    while queue:
        seed, depth = queue.pop(0)
        start = seed["start"]
        if start in seen:
            continue
        size = seed.get("size") or 0
        if not size:
            row = elf.functions.get(start)
            size = row["size"] if row else 0
        if not size or size > 64 * 1024:
            continue
        size = min(size, elf.available(start))
        if not size:
            continue
        if (budget["functions"] >= MAX_FUNCTIONS or budget["instructions"] >= MAX_INSTRUCTIONS):
            break
        seen.add(start)
        rows = disassemble(elf, start, size)
        budget["functions"] += 1
        budget["instructions"] += len(rows)
        entry = {"library": elf.name, "name": seed.get("name") or elf.target_name(start) or f"sub_{start:x}",
                 "start": start, "size": size, "depth": depth, "reason": seed.get("reason", ""),
                 "rows": rows, "decodedBytes": sum(4 for _ in rows),
                 "calls": [], "indirectCalls": []}
        for row in rows:
            if row["mnemonic"] in ("bl", "b") and row["ops"]:
                target = row["ops"].split(",")[0].strip()
                if target.startswith("#"):
                    try:
                        value = int(target[1:], 0)
                    except ValueError:
                        continue
                    name = elf.target_name(value)
                    if name.endswith("@plt"):
                        entry["calls"].append({"va": row["va"], "target": value, "import": name[:-4]})
                    else:
                        entry["calls"].append({"va": row["va"], "target": value, "internal": name})
                        # Follow bl (a call) and b landing exactly on another
                        # function's start (a tail call). A loop branch lands
                        # mid-function and is never followed.
                        if depth < 2 and value != start and value not in seen:
                            if value in elf.functions and elf.functions[value]["size"]:
                                queue.append(({"start": value, "name": elf.functions[value]["name"],
                                               "size": elf.functions[value]["size"],
                                               "reason": f"callee of {entry['name']} at {row['va']:#x}"},
                                              depth + 1))
                            elif row["mnemonic"] == "bl" and any(
                                    base <= value < base + size for base, size in elf.exec_ranges):
                                queue.append(({"start": value, "name": f"sub_{value:x}", "size": 1536,
                                               "window": True,
                                               "reason": f"unsymbolized callee of {entry['name']} at "
                                                         f"{row['va']:#x}; 1536-byte window, may span "
                                                         f"function boundaries"}, depth + 1))
            elif row["mnemonic"] == "blr":
                entry["indirectCalls"].append({"va": row["va"], "ops": row["ops"]})
        traced.append(entry)
    return traced


def find_callers(elves: dict[str, Elf], export_names: tuple[str, ...], budget: dict) -> list[dict]:
    """Cross-library callers of the cited exports, from each library's own PLT.

    The call-site scan reads raw 4-byte words and decodes only the BL encoding
    (0x94000000 | imm26), so a large library is scanned without disassembling
    every instruction; only the containing functions of real hits are
    disassembled, bounded by the budget.
    """
    callers: list[dict] = []
    for basename, elf in sorted(elves.items()):
        if basename == "libDAIHATSU_FILE.so":
            continue
        if budget["callers"] >= MAX_CALLER_FUNCTIONS:
            break
        wanted = {name for name in export_names if name in elf.imports}
        if not wanted:
            continue
        stubs = {va: name[:-4] for va, name in elf.plt.items() if name[:-4] in wanted}
        if not stubs:
            continue
        for base, size in elf.exec_ranges:
            try:
                code = elf.memory(base, size)
            except ValueError:
                continue
            for offset in range(0, len(code) - 3, 4):
                word = struct.unpack_from("<I", code, offset)[0]
                if word & 0xFC000000 != 0x94000000:
                    continue
                imm = word & 0x3FFFFFF
                if imm & 0x2000000:
                    imm -= 0x4000000
                address = base + offset
                target = address + imm * 4
                if target not in stubs:
                    continue
                function = elf.function_at(address)
                if function is None:
                    continue
                if budget["callers"] >= MAX_CALLER_FUNCTIONS:
                    break
                if any(c["library"] == basename and c["start"] == function["start"]
                       for c in callers):
                    continue
                rows = disassemble(elf, function["start"], min(function["size"], elf.available(function["start"])))
                budget["callers"] += 1
                budget["instructions"] += len(rows)
                callers.append({"library": basename, "name": function["name"],
                                "start": function["start"], "size": function["size"],
                                "callsExport": stubs[target], "callVa": address, "rows": rows})
    return callers


# ---------------------------------------------------------------------- data

def decode_members(path: Path) -> tuple[list[dict], dict]:
    """Decode every enumerated BIN member with the archive's own format table."""
    table, device, _ = fe.own_table(path)
    members: list[dict] = []
    with fe.ArchiveScanner(path) as scanner:
        names = sorted(scanner.names)
        payloads = scanner.read(set(names))
        skipped = list(scanner.skipped)
    for name in names:
        raw = payloads.get(name)
        entry: dict = {"member": name, "rawBytes": len(raw) if raw is not None else None,
                       "rawSha256": sha256(raw) if raw is not None else None}
        if raw is None:
            entry["status"] = "not readable"
        elif not raw.startswith(fe.YZJM_HEAD):
            entry["status"] = "not YZJM-wrapped"
        elif table is None:
            entry["status"] = f"no format table: {device.get('status')}"
        else:
            try:
                decoded = decode_yzjm(raw, table)
                entry.update(status="decoded", make=decoded.make, version=decoded.version,
                             seed=decoded.seed, decodedBytes=len(decoded.payload),
                             decodedSha256=sha256(decoded.payload))
                entry["_payload"] = decoded.payload
            except (ValueError, IndexError, KeyError, TypeError, OverflowError) as error:
                entry["status"] = f"decode failed: {type(error).__name__}: {error}"
        members.append(entry)
    return members, {"deviceLibrary": device, "skipped": skipped,
                     "formatTableSha256": sha256(table) if table else None}


def raw_index_scan(payload: bytes) -> dict:
    """Walk 8-byte LE32 pairs from 0 to the FFFFFFFF sentinel, without validating.

    This is the reconciliation fallback: it reports every raw row, duplicate
    keys included, even when the strict sentinel_index reader rejects the index,
    so accepted/rejected/duplicate counts can always be reconciled.
    """
    rows = []
    pos = 0
    seen: dict[int, int] = {}
    duplicates = []
    terminated = False
    while pos + 8 <= len(payload) and len(rows) < 4096:
        key, value = struct.unpack_from("<II", payload, pos)
        if key == 0xFFFFFFFF:
            terminated = True
            pos += 4
            break
        if key in seen:
            duplicates.append({"key": key, "firstRowAt": seen[key], "duplicateRowAt": pos})
        seen.setdefault(key, pos)
        rows.append({"at": pos, "key": key, "value": value})
        pos += 8
    return {"rows": rows, "rowCount": len(rows), "sentinelFound": terminated,
            "end": pos if terminated else None, "duplicateKeys": duplicates,
            "capped": len(rows) >= 4096 and not terminated,
            "truncated": not terminated and pos + 8 > len(payload)}


def reconcile_blocks(payload: bytes, offsets: list[int], stride: int) -> dict:
    """Reconcile LE16-count blocks of a fixed record stride against the index.

    For each block offset (sorted), read the LE16 count and check whether
    ``offset + 2 + count*stride`` lands exactly on the next block offset (or,
    for the last block, within the payload). This is the reconciliation that
    matches a reader's actual record stride; a stride that tiles the index
    exactly is strong layout evidence, while overlap/gap/truncation is reported
    per block rather than hidden.
    """
    ordered = sorted(set(offsets))
    rows = []
    accepted = rejected = 0
    for index, offset in enumerate(ordered):
        item: dict = {"offset": offset}
        if offset + 2 > len(payload):
            item["status"] = "rejected: no room for LE16 count"
            rejected += 1
            rows.append(item)
            continue
        count = int.from_bytes(payload[offset:offset + 2], "little")
        end = offset + 2 + count * stride
        item["count"] = count
        item["impliedEnd"] = end
        next_offset = ordered[index + 1] if index + 1 < len(ordered) else None
        item["nextBlockOffset"] = next_offset
        if end > len(payload):
            item["status"] = "rejected: block runs past end of payload"
            rejected += 1
        elif next_offset is not None and end != next_offset:
            item["status"] = ("rejected: block end does not meet next block "
                              f"({'overlap' if end > next_offset else 'gap'})")
            rejected += 1
        else:
            item["status"] = "accepted: tiles exactly"
            accepted += 1
        rows.append(item)
    return {"stride": stride, "blocks": len(ordered), "accepted": accepted,
            "rejected": rejected, "rows": rows[:2048], "capped": len(ordered) > 2048}


def records_18(payload: bytes, offset: int, count: int) -> list[dict]:
    """The code-established 18-byte record fields, named by offset only.

    GetSystemListData reads, per record: LE16@0, LE32@2, u8@6, u8@7, u8@8,
    u8@9, LE32@10, LE32@14 (see the trace). This extracts those typed values
    verbatim; it asserts no meaning for any of them.
    """
    out = []
    for index in range(count):
        base = offset + 2 + index * 18
        if base + 18 > len(payload):
            out.append({"index": index, "offset": base, "status": "truncated"})
            break
        record = payload[base:base + 18]
        out.append({
            "index": index, "offset": base,
            "le16_0": int.from_bytes(record[0:2], "little"),
            "le32_2": int.from_bytes(record[2:6], "little"),
            "u8_6": record[6], "u8_7": record[7], "u8_8": record[8], "u8_9": record[9],
            "le32_10": int.from_bytes(record[10:14], "little"),
            "le32_14": int.from_bytes(record[14:18], "little"),
            "hex": record.hex(" "),
        })
    return out


def reconcile_system_list(payload: bytes) -> dict:
    """Run the existing readers and reconcile every index row against the bytes."""
    out: dict = {"payloadBytes": len(payload), "payloadSha256": sha256(payload),
                 "headHex": payload[:64].hex(" "),
                 "rawIndexScan": raw_index_scan(payload)}
    try:
        rows, end = sentinel_index(payload, 0, 8)
        out["sentinelIndex"] = {"accepted": True, "rows": len(rows), "indexEnd": end,
                                "entries": [{"key": r[0], "value": r[1]} for r in rows]}
    except (ValueError, IndexError, struct.error) as error:
        out["sentinelIndex"] = {"accepted": False, "error": f"{type(error).__name__}: {error}"}
        rows, end = [], None
    try:
        index, blocks = system_lists(payload)
        packed = sum(len(block) for block in blocks.values())
        out["systemLists37"] = {"accepted": True, "indexRows": len(index), "blocks": len(blocks),
                                "packedRows": packed,
                                "blockOffsets": sorted(blocks),
                                "blockCounts": {str(off): len(block) for off, block in sorted(blocks.items())}}
    except (ValueError, IndexError, struct.error) as error:
        out["systemLists37"] = {"accepted": False, "error": f"{type(error).__name__}: {error}"}
    # Per-row reconciliation against the raw bytes, independent of either reader.
    # Basis: the strict reader's rows when it accepted; otherwise the raw scan,
    # so a rejected index is still fully reconciled rather than silently dropped.
    basis = ([(r[0], r[1]) for r in rows] if rows
             else [(r["key"], r["value"]) for r in out["rawIndexScan"]["rows"]])
    out["perRowBasis"] = "sentinel_index" if rows else "rawIndexScan"
    per_row = []
    if basis:
        offsets = [offset for _, offset in basis]
        duplicates = sorted({off for off in offsets if offsets.count(off) > 1})
        out["duplicateOffsets"] = duplicates
        for key, offset in basis[:2048]:
            item: dict = {"key": key, "offset": offset}
            if offset >= len(payload):
                item["status"] = "rejected: offset past end of payload"
            else:
                item["headHex"] = payload[offset:offset + MAX_BLOCK_PREVIEW].hex(" ")
                if offset + 2 <= len(payload):
                    count = int.from_bytes(payload[offset:offset + 2], "little")
                    item["le16AtOffset"] = count
                    end37 = offset + 2 + count * 37
                    item["fits37ByteRows"] = end37 <= len(payload)
                    item["impliedEnd"] = end37
                else:
                    item["status"] = "rejected: no room for a count field"
            per_row.append(item)
        out["perRow"] = per_row
        out["perRowCapped"] = len(basis) > 2048
        out["reconciliation"] = {
            "indexRows": len(basis),
            "distinctOffsets": len(set(offsets)),
            "duplicateOffsetRows": sum(offsets.count(off) for off in duplicates),
            "rowsWithValid37ByteBlocks": sum(
                1 for _, offset in basis
                if offset + 2 <= len(payload)
                and offset + 2 + int.from_bytes(payload[offset:offset + 2], "little") * 37 <= len(payload)),
        }
        # Code-aligned block tiling: GetSystemListData advances the file offset
        # by 0x12 (18) per record, so 18 is the reader's actual stride. The
        # 37-byte tiling is shown only to demonstrate it does NOT fit.
        out["blocks18"] = reconcile_blocks(payload, offsets, 18)
        out["blocks37"] = reconcile_blocks(payload, offsets, 37)
        if rows:
            first_offset = rows[0][1]
            if first_offset + 2 <= len(payload):
                count = int.from_bytes(payload[first_offset:first_offset + 2], "little")
                out["firstBlockRecords18"] = records_18(payload, first_offset, min(count, 32))
                out["firstBlockOffset"] = first_offset
                out["firstBlockCount"] = count
    return out


def library_strings(elf: Elf) -> list[dict]:
    out = []
    for match in ASCII_RUN.finditer(elf.data):
        offset = match.start()
        va = None
        for header in elf.loads:
            if header.p_offset <= offset < header.p_offset + header.p_filesz:
                va = header.p_vaddr + (offset - header.p_offset)
                break
        out.append({"va": va, "offset": offset, "text": match.group().decode("ascii")})
        if len(out) >= MAX_STRINGS:
            break
    return out


# --------------------------------------------------------------------- state

def build_state(path: Path, inventory8: dict, head_sha: str) -> dict:
    target = report_target(inventory8)
    expected = target["sha256"]
    verification = verify_archive(path, expected)
    state: dict = {
        "format": FORMAT, "formatVersion": FORMAT_VERSION, "generatedUtc": utc_now(),
        "headSha": head_sha, "label": LABEL,
        "inventory8HeadSha": inventory8.get("headSha"),
        "reportTarget": {k: target.get(k) for k in (
            "archive", "brand", "family", "sha256", "inventory7Verdict", "parsed", "attempted",
            "membersExamined", "wrapperConfidence", "verdict")},
        "verification": verification,
        "budget": {"functions": 0, "instructions": 0, "callers": 0},
        "libraries": [], "members": [], "xrefs": [], "trace": [], "callers": [],
        "strings": [], "failed": False, "errors": [],
    }

    # The exact member and library the report names.
    member_names = list(target.get("membersExamined") or [])
    library_name = None
    for consumer in target.get("consumerIdentity") or []:
        if consumer.get("library"):
            library_name = consumer["library"]
            break
    if library_name is None:
        library_name = "libDAIHATSU_FILE.so"
    link_va = None
    for link in target.get("filenameLinks") or []:
        if link.get("library") == library_name and link.get("va") is not None:
            link_va = link["va"]
            break

    library_data = read_library(path, library_name)
    if library_data is None:
        state["failed"] = True
        state["errors"].append(f"{library_name} is absent, ambiguous or oversized in {ARCHIVE_NAME}")
        return state
    try:
        elf = Elf(library_data, library_name)
    except Exception as error:
        state["failed"] = True
        state["errors"].append(f"{library_name} could not be parsed as AArch64 ELF: "
                               f"{type(error).__name__}: {error}")
        return state

    # Parse every archive library once; reused for callee resolution and callers.
    elves: dict[str, Elf] = {library_name: elf}
    for basename in archive_libraries(path):
        if basename in elves:
            continue
        data = read_library(path, basename)
        if data is None:
            continue
        try:
            elves[basename] = Elf(data, basename)
        except Exception:  # an unparsable library is data, not a crash
            continue
    for basename, parsed in sorted(elves.items()):
        state["libraries"].append(
            {"name": basename, "sha256": parsed.sha256, "bytes": len(parsed.data),
             "exports": parsed.exports, "imports": parsed.imports,
             "hasSymtab": parsed.elf.get_section_by_name(".symtab") is not None,
             "execRanges": [{"va": va, "size": size} for va, size in parsed.exec_ranges]})

    # Confirm the literal at the reported VA.
    literal = None
    if link_va is not None:
        literal = elf.c_string(link_va)
        state["literal"] = {"va": link_va, "text": literal,
                            "expected": "DAIHATSU_SYS_LIST.BIN",
                            "match": literal == "DAIHATSU_SYS_LIST.BIN"}
        if literal != "DAIHATSU_SYS_LIST.BIN":
            state["failed"] = True
            state["errors"].append(f"literal at {link_va:#x} is {literal!r}, not DAIHATSU_SYS_LIST.BIN")

    # Cross-references to the literal.
    if link_va is not None:
        state["xrefs"] = find_literal_xrefs(elf, link_va)

    # Seeds: the cited consumers plus every function referencing the literal.
    seeds: list[dict] = []
    for name in CONSUMER_NAMES:
        for start, info in elf.functions.items():
            if info["name"] == name and info["size"]:
                seeds.append({"start": start, "name": name, "size": info["size"],
                              "reason": "cited own-role consumer (Inventory-7/8)"})
    for site in state["xrefs"]:
        if site.get("functionStart") is not None:
            seeds.append({"start": site["functionStart"], "name": site["function"],
                          "size": site["functionSize"],
                          "reason": f"references the literal at {site['va']:#x} ({site['kind']})"})
        else:
            # Stripped library: no containing symbol. Disassemble a bounded,
            # 4-byte-aligned window around the site, explicitly labelled.
            start = max(0, (site["va"] - 256) & ~3)
            seeds.append({"start": start, "name": f"window_{site['va']:x}", "size": 768,
                          "window": True,
                          "reason": f"no containing symbol for xref at {site['va']:#x}; "
                                    f"768-byte window, may span function boundaries"})
    state["trace"] = trace_functions(elf, seeds, state["budget"])

    # Imported callees resolved inside this archive's own libraries.
    imported: dict[str, list[str]] = {}
    for entry in state["trace"]:
        for call in entry["calls"]:
            if "import" in call:
                imported.setdefault(call["import"], []).append(f"{entry['name']}@{call['va']:#x}")
    state["importedCallees"] = []
    for name in sorted(imported):
        resolved = {"import": name, "calledFrom": imported[name], "exportedBy": []}
        for basename, other in sorted(elves.items()):
            if basename == library_name:
                continue
            if name in other.exports and state["budget"]["functions"] < MAX_FUNCTIONS:
                for start, info in other.functions.items():
                    if info["name"] == name and info["size"] and info["source"] == ".dynsym":
                        size = min(info["size"], other.available(start))
                        if not size:
                            continue
                        rows = disassemble(other, start, size)
                        state["budget"]["functions"] += 1
                        state["budget"]["instructions"] += len(rows)
                        resolved["exportedBy"].append(
                            {"library": basename, "start": start, "size": info["size"], "rows": rows})
                        break
        state["importedCallees"].append(resolved)

    # Cross-library callers of the cited exports.
    state["callers"] = find_callers(elves, CONSUMER_NAMES, state["budget"])

    # Every BIN member, decoded and reconciled.
    members, device = decode_members(path)
    state["deviceLibrary"] = device
    for entry in members:
        payload = entry.pop("_payload", None)
        if payload is not None:
            base = PurePosixPath(fe.canonical(entry["member"])).name
            if base.upper() == "DAIHATSU_SYS_LIST.BIN":
                entry["systemList"] = reconcile_system_list(payload)
                entry["payloadHex"] = payload.hex()
                entry["payloadHeadHex"] = payload[:512].hex(" ")
            else:
                entry["payloadHeadHex"] = payload[:256].hex(" ")
                try:
                    rows, end = sentinel_index(payload, 0, 8)
                    entry["sentinelIndex"] = {"accepted": True, "rows": len(rows), "indexEnd": end}
                except (ValueError, IndexError, struct.error) as error:
                    entry["sentinelIndex"] = {"accepted": False,
                                              "error": f"{type(error).__name__}: {error}"}
        state["members"].append(entry)

    state["strings"] = library_strings(elf)
    return state


# -------------------------------------------------------------------- output

def markdown(state: dict) -> str:
    out = ["# DAIHATSU-1: system-list reader trace", "",
           f"- CI head SHA: `{state['headSha']}`",
           f"- Generated: `{state['generatedUtc']}`",
           f"- Label: {LABEL}",
           f"- Inventory-8 source head: `{state.get('inventory8HeadSha')}`", ""]
    out += ["## 1. Verification", ""]
    verification = state["verification"]
    out += [f"- Archive `{verification['archive']}`: {verification['bytes']} bytes, SHA-256 "
            f"`{verification['sha256']}` — matches the Inventory-8 report hash: "
            f"**{verification['match']}**.", ""]
    target = state["reportTarget"]
    out += [f"- Report pair: `{target['archive']}` · `{target['family']}`, {target['inventory7Verdict']}, "
            f"{target['parsed']} parsed of {target['attempted']} sampled; verdict `{target['verdict']}`.",
            f"- Members examined by Inventory-8: "
            f"{', '.join('`' + old.escape(m) + '`' for m in target.get('membersExamined') or [])}.", ""]
    for lib in state["libraries"]:
        out += [f"- Library `{lib['name']}`: {lib['bytes']} bytes, SHA-256 `{lib['sha256']}`, "
                f"symtab present: {lib['hasSymtab']}, {len(lib['exports'])} exported function(s), "
                f"{len(lib['imports'])} import(s).", ""]
    literal = state.get("literal")
    if literal:
        out += [f"- Literal at VA `{literal['va']:#x}`: `{literal['text']}` — exact match with "
                f"`{literal['expected']}`: **{literal['match']}**.", ""]
    if state.get("errors"):
        out += ["Errors:", ""] + [f"- {old.escape(e)}" for e in state["errors"]] + [""]

    out += ["## 2. Cross-references to the literal", ""]
    if not state["xrefs"]:
        out += ["No code cross-reference to the literal VA was found in this library.", ""]
    for site in state["xrefs"]:
        function = (f"`{site['function']}` @ {site['functionStart']:#x} ({site['functionSize']} bytes)"
                    if site.get("function") else "no containing symbol")
        out += [f"- {site['va']:#x} ({site['kind']}) in {function}: {old.escape(site['detail'])}", ""]

    out += ["## 3. Disassembly (static, annotated)", "",
            "Annotations resolve adrp/add and adrp/ldr addresses against this library's own "
            "file bytes and relocations; `name@plt` marks an imported callee. No vendor code "
            "is executed.", ""]
    for entry in state["trace"]:
        out += [f"### `{entry['library']}` · `{entry['name']}` @ {entry['start']:#x} "
                f"({entry['size']} bytes, depth {entry['depth']})", "",
                f"Reason traced: {old.escape(entry['reason'])}.", "", "```text",
                render_disassembly(entry["rows"]), "```", "",
                f"Decoded {entry['decodedBytes']}/{entry['size']} bytes.", ""]
        if entry["calls"]:
            calls = ", ".join(f"{c['va']:#x}→{c.get('import') or c.get('internal') or hex(c['target'])}"
                              for c in entry["calls"])
            out += [f"Calls: {old.escape(calls)}.", ""]
        if entry["indirectCalls"]:
            out += [f"Indirect calls (blr, target not statically known): "
                    f"{', '.join(hex(c['va']) for c in entry['indirectCalls'])}.", ""]

    out += ["## 4. Imported callees resolved in this archive", ""]
    for resolved in state.get("importedCallees", []):
        out += [f"### `{resolved['import']}`", "",
                f"Called from: {', '.join('`' + c + '`' for c in resolved['calledFrom'])}.", ""]
        if not resolved["exportedBy"]:
            out += ["Not exported by any other library in this archive (or budget exhausted).", ""]
        for body in resolved["exportedBy"]:
            out += [f"#### `{body['library']}` @ {body['start']:#x} ({body['size']} bytes)", "",
                    "```text", render_disassembly(body["rows"]), "```", ""]

    out += ["## 5. Cross-library callers of the cited exports", ""]
    if not state["callers"]:
        out += ["No other library in this archive imports GetSystemListData or GetVehicleInfoData "
                "(within the bounded scan).", ""]
    for caller in state["callers"]:
        out += [f"### `{caller['library']}` · `{caller['name']}` @ {caller['start']:#x} "
                f"({caller['size']} bytes)", "",
                f"Calls `{caller['callsExport']}` at {caller['callVa']:#x}.", "", "```text",
                render_disassembly(caller["rows"]), "```", ""]

    out += ["## 6. Decoded members and row reconciliation", ""]
    device = state.get("deviceLibrary", {})
    out += [f"- Format table from the archive's own `libDEVICEID.so`: "
            f"{device.get('formatTableSha256')}; library status "
            f"`{device.get('deviceLibrary', {}).get('status')}`.", ""]
    for entry in state["members"]:
        out += [f"### `{old.escape(entry['member'])}`", "",
                f"- Raw: {entry['rawBytes']} bytes, SHA-256 `{entry['rawSha256']}`; status "
                f"`{entry['status']}`."]
        if entry.get("decodedSha256"):
            out += [f"- Decoded: {entry['decodedBytes']} bytes, SHA-256 `{entry['decodedSha256']}`, "
                    f"make `{entry.get('make')}`, version `{entry.get('version')}`, "
                    f"seed `{entry.get('seed')}`."]
        analysis = entry.get("systemList")
        if analysis:
            raw = analysis["rawIndexScan"]
            out += [f"- Raw 8-byte index scan: {raw['rowCount']} row(s), sentinel found="
                    f"{raw['sentinelFound']}, duplicate keys={len(raw['duplicateKeys'])}"
                    + (f", capped at 4096 rows" if raw.get("capped") else "")
                    + (f", truncated (no sentinel)" if raw.get("truncated") else "") + "."]
            si = analysis["sentinelIndex"]
            out += [f"- sentinel_index(0, 8): accepted={si['accepted']}"
                    + (f", {si['rows']} rows, index ends at {si['indexEnd']:#x}" if si["accepted"]
                       else f", error: {old.escape(si['error'])}")]
            sl = analysis["systemLists37"]
            out += [f"- system_lists (LE16 count + 37-byte rows): accepted={sl['accepted']}"
                    + (f", {sl['indexRows']} index rows, {sl['blocks']} block(s), "
                       f"{sl['packedRows']} packed row(s)" if sl["accepted"]
                       else f", error: {old.escape(sl['error'])}")]
            if "reconciliation" in analysis:
                rec = analysis["reconciliation"]
                out += [f"- Reconciliation (basis: {analysis['perRowBasis']}): {rec['indexRows']} index "
                        f"row(s), {rec['distinctOffsets']} distinct offset(s), "
                        f"{rec['duplicateOffsetRows']} row(s) sharing a duplicate offset, "
                        f"{rec['rowsWithValid37ByteBlocks']} row(s) whose offset holds a valid "
                        f"LE16-count + 37-byte block."
                        + (" Per-row detail capped at 2048 rows." if analysis.get("perRowCapped") else "")]
            if analysis.get("duplicateOffsets"):
                out += [f"- Duplicate offsets: {', '.join(hex(o) for o in analysis['duplicateOffsets'])}."]
            for key, label in (("blocks18", "18-byte (code-established stride 0x12)"),
                               ("blocks37", "37-byte (Toyota shape, for contrast)")):
                blocks = analysis.get(key)
                if blocks:
                    out += [f"- Block tiling, {label}: {blocks['accepted']} accepted (tiles exactly), "
                            f"{blocks['rejected']} rejected, of {blocks['blocks']} distinct block(s)."]
                    shown = 0
                    for row in blocks["rows"]:
                        if row.get("status", "").startswith("rejected") and shown < 8:
                            out += [f"  - offset {row['offset']:#x}: {row['status']} "
                                    f"(count={row.get('count')}, implied end {row.get('impliedEnd', 0):#x}, "
                                    f"next block {row.get('nextBlockOffset')})"]
                            shown += 1
            if analysis.get("firstBlockRecords18"):
                out += [f"- First block @ {analysis['firstBlockOffset']:#x}, LE16 count="
                        f"{analysis['firstBlockCount']}; its 18-byte records (fields named by offset, "
                        f"no meaning asserted):"]
                for record in analysis["firstBlockRecords18"]:
                    if record.get("status") == "truncated":
                        out += [f"  - record {record['index']} @ {record['offset']:#x}: truncated"]
                        continue
                    out += [f"  - record {record['index']} @ {record['offset']:#x}: "
                            f"le16_0={record['le16_0']:#06x}, le32_2={record['le32_2']:#010x}, "
                            f"u8_6={record['u8_6']:#04x}, u8_7={record['u8_7']:#04x}, "
                            f"u8_8={record['u8_8']:#04x}, u8_9={record['u8_9']:#04x}, "
                            f"le32_10={record['le32_10']:#010x}, le32_14={record['le32_14']:#010x}"]
            for item in analysis.get("perRow", [])[:64]:
                line = f"  - key {item['key']:#010x} → offset {item['offset']:#x}"
                if "le16AtOffset" in item:
                    line += (f": LE16={item['le16AtOffset']}, 37-byte rows fit="
                             f"{item['fits37ByteRows']} (implied end {item['impliedEnd']:#x})")
                if item.get("status"):
                    line += f" — {item['status']}"
                out += [line]
                if item.get("headHex"):
                    out += [f"    bytes: `{item['headHex']}`"]
        elif entry.get("sentinelIndex"):
            si = entry["sentinelIndex"]
            out += [f"- sentinel_index(0, 8): accepted={si['accepted']}"
                    + (f", {si['rows']} rows" if si["accepted"] else f", error: {old.escape(si['error'])}")]
        if entry.get("payloadHeadHex"):
            out += ["", "Payload head:", "", "```text", entry["payloadHeadHex"], "```"]
        out += [""]

    out += ["## 7. Strings in the reader library (bounded)", "", "```text"]
    for item in state["strings"]:
        va = f"{item['va']:#x}" if item["va"] is not None else f"file+{item['offset']:#x}"
        out += [f"{va}: {item['text']}"]
    out += ["```", ""]

    out += ["## 8. What this trace does not establish", "",
            "- This tool asserts no field meaning. Every integer the readers yield stays an "
            "opaque identifier unless the disassembly above proves what the code does with it.",
            "- No converter, pack or app change is produced here. No DTC or diagnostic operation "
            "is claimed or enabled.",
            "- Budget: " + json.dumps(state["budget"]) + ".", ""]
    return "\n".join(out) + "\n"


def emit(state: dict, output: Path) -> dict:
    text = markdown(state)
    old.write_atomic(output / "daihatsu-trace.md", text)
    old.write_atomic(output / "daihatsu-trace.json",
                     json.dumps(state, indent=1, sort_keys=True, ensure_ascii=True, default=str) + "\n")
    for stale in sorted(output.glob("daihatsu-trace-[0-9][0-9][0-9].md")):
        stale.unlink()
    manifest = []
    for index, body in enumerate(old.chunk(text), 1):
        name = ("DAIHATSU-1 system-list trace" if index == 1
                else f"DAIHATSU-1 system-list trace (part {index})")
        path = output / f"daihatsu-trace-{index:03d}.md"
        old.write_atomic(path, body)
        manifest.append({"name": name, "file": str(path), "bytes": len(body.encode("utf-8"))})
    if manifest and max(item["bytes"] for item in manifest) > 60000:
        raise ValueError("A check part exceeds the 60,000-byte Checks API field limit")
    conclusion = "success" if not state["failed"] else "failure"
    old.write_atomic(output / "parts.json", json.dumps(manifest, indent=1) + "\n")
    old.write_atomic(output / "conclusion.txt", conclusion + "\n")
    return {"parts": manifest, "bytes": len(text.encode("utf-8")), "conclusion": conclusion}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--inventory8-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    inventory8 = json.loads(args.inventory8_json.read_text())
    state = build_state(args.archive, inventory8, os.environ.get("GITHUB_SHA", "local"))
    args.output.mkdir(parents=True, exist_ok=True)
    result = emit(state, args.output)
    print(f"daihatsu-trace: {len(state['trace'])} function(s) traced, "
          f"{len(state['xrefs'])} literal xref(s), {len(state['callers'])} cross-library caller(s), "
          f"{len(state['members'])} member(s) decoded, "
          f"{result['bytes']} report byte(s), conclusion {result['conclusion']}", flush=True)
    return 0 if result["conclusion"] == "success" else 1


if __name__ == "__main__":
    raise SystemExit(main())
