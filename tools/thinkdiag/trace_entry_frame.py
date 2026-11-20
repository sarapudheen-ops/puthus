#!/usr/bin/env python3
"""Resolve `InitSetEnterFrame` completely: the transport jump table and every frame layout.

`InitSetEnterFrame` (libTOYOTA_COMM.so @0x1a130) is the function that turns one
system-entry record into the bytes actually sent to a control unit. It switches on the
transport byte at `record+0x828` (`sub w8, w8, #3; cmp w8, #8; b.hi`), through a
9-entry jump table of PC-relative offsets. Each branch packs a different frame.

This tool reads that table out of the .rodata it lives in, disassembles each branch
until the branches converge, and reports the exact byte layout each transport produces:
which stack offset each byte lands in, and which source field feeds it.

Static analysis only. No vendor code is loaded or executed.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path
import re
import struct
import zipfile

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

FUNCTION = "InitSetEnterFrame"
# Branch targets converge here, after the frame bytes have been laid down.
CONVERGE = 0x1a264
INDEX_BASE = 3          # sub w8, w8, #3
TABLE_ENTRIES = 9       # cmp w8, #8
STORE_RE = re.compile(r"^\[sp(?:, #(0x[0-9a-f]+|\d+))?\]$")


class Binary:
    def __init__(self, data: bytes):
        self.data = data
        self.elf = ELFFile(io.BytesIO(data))

    def read(self, address: int, size: int) -> bytes:
        for segment in self.elf.iter_segments():
            offset = address - segment.header.p_vaddr
            if (segment.header.p_type == "PT_LOAD" and 0 <= offset
                    and offset + size <= segment.header.p_filesz):
                return segment.data()[offset:offset + size]
        raise ValueError(f"address {address:#x} is not file-backed")

    def u32(self, address: int) -> int:
        return struct.unpack("<I", self.read(address, 4))[0]

    def s32(self, address: int) -> int:
        return struct.unpack("<i", self.read(address, 4))[0]

    def functions(self) -> dict[str, tuple[int, int]]:
        symbols = self.elf.get_section_by_name(".dynsym")
        found = {}
        if symbols is None:
            return found
        for symbol in symbols.iter_symbols():
            if (symbol.entry.st_info.type == "STT_FUNC" and symbol.entry.st_size
                    and symbol.entry.st_shndx != "SHN_UNDEF"):
                found.setdefault(symbol.name, (symbol.entry.st_value, symbol.entry.st_size))
        return found

    def code(self, start: int, size: int):
        return list(Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(self.read(start, size), start))


def split_ops(op_str: str) -> list[str]:
    """Split an operand string on commas that are not inside brackets."""
    parts, depth, current = [], 0, ""
    for char in op_str:
        if char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += char
    if current.strip():
        parts.append(current.strip())
    return parts


def find_table(binary: Binary, instructions) -> tuple[int, dict]:
    """Locate the PC-relative jump table used by this function."""
    detail = {}
    for index, ins in enumerate(instructions):
        if ins.mnemonic != "ldrsw" or ", lsl #2]" not in ins.op_str:
            continue
        table_reg = split_ops(ins.op_str)[1].strip("[]").split(",")[0].strip()
        base = None
        addend = None
        for back in range(index - 1, max(index - 6, -1), -1):
            before = instructions[back]
            if before.mnemonic == "add" and before.op_str.startswith(f"{table_reg}, {table_reg},"):
                addend = int(split_ops(before.op_str)[2].lstrip("#"), 0)
            elif before.mnemonic == "adrp" and before.op_str.startswith(f"{table_reg},"):
                base = int(split_ops(before.op_str)[1].lstrip("#"), 0)
                break
        if base is None or addend is None:
            continue
        table = base + addend
        detail["loaderAddress"] = f"0x{ins.address:x}"
        detail["tableAddress"] = f"0x{table:x}"
        entries = {}
        for slot in range(TABLE_ENTRIES):
            offset = binary.s32(table + slot * 4)
            entries[INDEX_BASE + slot] = table + offset
        return table, {"detail": detail, "entries": entries}
    raise ValueError("jump table not found")


def frame_layout(instructions) -> list[dict]:
    """Every `strb <src>, [sp, #N]` in a branch, in execution order."""
    stores = []
    for ins in instructions:
        if ins.mnemonic not in ("strb", "strh", "str", "stur", "sturb"):
            continue
        operands = split_ops(ins.op_str)
        if len(operands) != 2:
            continue
        target = STORE_RE.match(operands[1])
        if not target:
            continue
        offset = target.group(1)
        stores.append({
            "offset": int(offset, 0) if offset else 0,
            "source": operands[0],
            "width": ins.mnemonic,
            "at": f"0x{ins.address:x}",
        })
    return stores


def branch_body(binary: Binary, start: int, limit: int = 60) -> list:
    """Disassemble from a branch target until control reaches the convergence block."""
    body = []
    for ins in binary.code(start, 4 * limit):
        body.append(ins)
        if ins.mnemonic == "b":
            try:
                if int(ins.op_str.lstrip("#"), 0) == CONVERGE:
                    break
            except ValueError:
                pass
        if ins.address >= CONVERGE:
            break
    return body


def analyse(data: bytes) -> dict:
    binary = Binary(data)
    table = binary.functions()
    if FUNCTION not in table:
        raise SystemExit(f"{FUNCTION} not found")
    address, size = table[FUNCTION]
    instructions = binary.code(address, size)
    _, resolved = find_table(binary, instructions)

    # Frame template copied into the stack buffer before any branch runs.
    seed = {}
    for index, ins in enumerate(instructions):
        if ins.mnemonic == "ldr" and ins.op_str.startswith("q0,"):
            page = None
            offset = None
            for back in range(index - 1, max(index - 8, -1), -1):
                before = instructions[back]
                if before.mnemonic == "adrp" and before.op_str.startswith("x9,") and page is None:
                    page = int(split_ops(before.op_str)[1].lstrip("#"), 0)
                if before.mnemonic == "add" and before.op_str.startswith("x9, x9,") and offset is None:
                    offset = int(split_ops(before.op_str)[2].lstrip("#"), 0)
            if page is not None and offset is not None:
                seed = {"offset": offset, "page": page,
                        "address": f"0x{page + offset:x}",
                        "raw": binary.read(page + offset, 16).hex()}
            break

    branches = []
    for transport, target in sorted(resolved["entries"].items()):
        body = branch_body(binary, target)
        branches.append({
            "transport": transport,
            "target": f"0x{target:x}",
            "instructions": len(body),
            "stores": frame_layout(body),
            "lines": [f"{ins.address:x}: {ins.mnemonic:<7} {ins.op_str}" for ins in body],
        })
    return {"function": FUNCTION, "address": f"0x{address:x}", "size": size,
            "table": resolved["detail"], "seed": seed, "branches": branches,
            "callCount": len(instructions)}


def markdown(report: dict) -> str:
    lines = [f"## `{report['function']}` @ {report['address']} — "
             f"{report['size']} bytes, {report['callCount']} instructions", ""]
    lines.append(f"Jump table at **{report['table']['tableAddress']}**, "
                 f"loaded by `{report['table']['loaderAddress']}`. "
                 f"Index = transport byte `record+0x828` minus {INDEX_BASE}, out of range jumps away.")
    lines.append("")
    if report["seed"].get("raw"):
        lines.append(f"Frame seed copied from **{report['seed']['address']}**: "
                     f"`{report['seed']['raw']}`")
        lines.append("")
    lines.append("### Frame layout per transport")
    lines.append("")
    lines.append("| transport | branch | bytes placed | sources |")
    lines.append("| ---: | --- | --- | --- |")
    for item in report["branches"]:
        if not item["stores"]:
            lines.append(f"| {item['transport']} | {item['target']} | none | - |")
            continue
        placed = ", ".join(f"+{s['offset']}" for s in item["stores"])
        sources = ", ".join(f"{s['source']}(+{s['offset']})" for s in item["stores"])
        lines.append(f"| {item['transport']} | {item['target']} | {placed} | {sources} |")
    lines.append("")
    for item in report["branches"]:
        lines.append(f"**transport {item['transport']}** @ {item['target']} "
                     f"({item['instructions']} instructions)")
        lines.append("```")
        lines.extend(item["lines"][:44])
        lines.append("```")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--library", default="libTOYOTA_COMM.so")
    parser.add_argument("--limit", type=int, default=52000)
    args = parser.parse_args(argv)
    with zipfile.ZipFile(args.archive) as archive:
        names = {Path(i.filename).name: i for i in archive.infolist() if not i.is_dir()}
        data = archive.read(names[args.library])
    report = analyse(data)
    text = markdown(report)
    if args.output:
        args.output.write_text(text[:args.limit], encoding="utf-8")
    print(text[:args.limit], end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
