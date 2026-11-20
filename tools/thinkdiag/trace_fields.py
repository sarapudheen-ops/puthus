#!/usr/bin/env python3
"""Find every writer of the runtime offsets that `InitSetEnterFrame` reads.

`InitSetEnterFrame` reads its frame fields from a base register +0x7b0, +0x7b4, +0x7c4,
+0x828. Those offsets are far outside any system-entry record, so the values arrive in a
large runtime context that something else populates. This tool scans every native library
in the archive for instructions that store to those exact offsets, and reports the enclosing
function, the stored source register, and whether a nearby load shows where it came from.

Static analysis only. Nothing is loaded or executed.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path
import re
import zipfile

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM, CS_ARCH_ARM, CS_MODE_THUMB
from elftools.elf.elffile import ELFFile

OFFSETS = (0x7B0, 0x7B4, 0x7C4, 0x7C8, 0x828, 0x829, 0x7F0, 0x7F2)
STORE_RE = re.compile(r"\[(x\d+|w\d+)(?:,\s*#?(0x[0-9a-fA-F]+|\d+))?\]")
LIB_PATTERN = re.compile(r"^lib.*\.so$")
MAX_ROWS = 300


def load(data: bytes):
    elf = ELFFile(io.BytesIO(data))
    machine = elf.header.e_machine
    if machine == "EM_AARCH64":
        return elf, Cs(CS_ARCH_ARM64, CS_MODE_ARM), 4
    if machine == "EM_ARM":
        return elf, Cs(CS_ARCH_ARM, CS_MODE_THUMB), 2
    return elf, None, 0


def symbols(elf) -> list[tuple[int, int, str]]:
    table = elf.get_section_by_name(".dynsym") or elf.get_section_by_name(".symtab")
    found = []
    if table is None:
        return found
    for symbol in table.iter_symbols():
        if (symbol.entry.st_info.type == "STT_FUNC" and symbol.entry.st_size
                and symbol.entry.st_shndx != "SHN_UNDEF"):
            found.append((symbol.entry.st_value, symbol.entry.st_size, symbol.name))
    return sorted(found)


def owner(address: int, table: list[tuple[int, int, str]]) -> str:
    best = "?"
    for start, size, name in table:
        if start <= address < start + size:
            if size <= 0:
                continue
            best = name
    return best


def scan(data: bytes, name: str) -> list[dict]:
    elf, engine, step = load(data)
    if engine is None:
        return []
    table = symbols(elf)
    rows = []
    seen = set()
    for segment in elf.iter_segments():
        if segment.header.p_type != "PT_LOAD" or not segment.header.p_filesz:
            continue
        base = segment.header.p_vaddr
        blob = segment.data()
        for ins in engine.disasm(blob, base):
            if not (ins.mnemonic.startswith("st")
                    or (ins.mnemonic in ("add", "sub") and ins.op_str.count(",") == 2)):
                continue
            operands = [part.strip() for part in ins.op_str.split(",")]
            if ins.mnemonic in ("add", "sub"):
                if len(operands) != 3 or not operands[2].startswith("#"):
                    continue
                value = int(operands[2].lstrip("#"), 0)
                if value not in OFFSETS:
                    continue
            else:
                match = STORE_RE.search(ins.op_str)
                if not match or not match.group(2):
                    continue
                value = int(match.group(2), 0)
                if value not in OFFSETS:
                    continue
            key = (name, ins.address)
            if key in seen:
                continue
            seen.add(key)
            rows.append({"library": name, "at": f"0x{ins.address:x}",
                         "offset": f"0x{value:x}", "function": owner(ins.address, table),
                         "instruction": f"{ins.mnemonic} {ins.op_str}"})
            if len(rows) >= MAX_ROWS:
                return rows
    return rows


def context(data: bytes, address: int, before: int = 8, after: int = 4) -> list[str]:
    elf, engine, _ = load(data)
    for segment in elf.iter_segments():
        if segment.header.p_type != "PT_LOAD":
            continue
        delta = address - segment.header.p_vaddr
        if 0 <= delta < segment.header.p_filesz:
            start = max(delta - before * 4, 0)
            blob = segment.data()[start:delta + after * 4]
            return [f"{ins.address:x}: {ins.mnemonic:<7} {ins.op_str}"
                    for ins in engine.disasm(blob, segment.header.p_vaddr + start)]
    return []


def markdown(rows: list[dict], contexts: dict) -> str:
    lines = ["## Who fills the frame fields", ""]
    if not rows:
        lines.append("No store to those offsets was found in any native library.")
        return "\n".join(lines) + "\n"
    lines.append("| library | function | offset | instruction |")
    lines.append("| --- | --- | --- | --- |")
    for row in rows:
        lines.append(f"| `{row['library']}` | `{row['function']}` | {row['offset']} | "
                     f"`{row['instruction']}` |")
    lines.append("")
    for key, value in list(contexts.items())[:10]:
        lines.append(f"**{key}**")
        lines.append("```")
        lines.extend(value)
        lines.append("```")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--limit", type=int, default=52000)
    args = parser.parse_args(argv)
    rows, contexts = [], {}
    with zipfile.ZipFile(args.archive) as archive:
        for info in archive.infolist():
            name = Path(info.filename).name
            if info.is_dir() or not LIB_PATTERN.match(name):
                continue
            data = archive.read(info)
            try:
                found = scan(data, name)
            except Exception as error:                      # noqa: BLE001 - report, never abort
                contexts[f"{name} failed"] = [str(error)[:200]]
                continue
            for row in found:
                rows.append(row)
                if len(contexts) < 10:
                    key = f"{name} @ {row['at']} ({row['function']})"
                    contexts[key] = context(data, int(row["at"], 16))
    text = markdown(rows, contexts)[:args.limit]
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
