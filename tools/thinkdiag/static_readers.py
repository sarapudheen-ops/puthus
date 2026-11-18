#!/usr/bin/env python3
"""Disassemble selected file readers without loading/executing vendor libraries.

This is offline research output, NOT an Android dependency or a recipe generator.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path, PurePosixPath
import zipfile

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG, ARM64_OP_MEM
from elftools.elf.elffile import ELFFile

READERS = {
    "libDEVICEID.so": ["@0x8dc4:0x254"],
    "libSTD.so": ["OpenFileIssueSearchID", "EncryptNumber", "ggp_Open", "IdSearch"],
    "libTOYOTA_FILE.so": ["GetVehicleInfoData", "GetSystemListData"],
    "libDIAG.so": ["FOPEN", "FOPEN_Ex", "OpenDiagReadFileDll", "OpenSearchIDDLL"],
}


def reader_report(data: bytes, name: str, wanted: list[str]) -> str:
    elf = ELFFile(io.BytesIO(data))
    table = elf.get_section_by_name(".dynsym")
    symbols = {s.name: s for s in table.iter_symbols() if s.name}
    targets = {s.entry.st_value: s.name for s in symbols.values() if s.entry.st_value}
    plt = elf.get_section_by_name(".plt")
    relplt = elf.get_section_by_name(".rela.plt")
    if plt is not None and relplt is not None:
        for index, reloc in enumerate(relplt.iter_relocations()):
            targets[plt.header.sh_addr + 32 + index * 16] = table.get_symbol(reloc.entry.r_info_sym).name + "@plt"
    relocs = {}
    for section in elf.iter_sections():
        if section.name.startswith(".rela"):
            symtable = elf.get_section(section.header.sh_link)
            for reloc in section.iter_relocations():
                symbol = symtable.get_symbol(reloc.entry.r_info_sym)
                relocs[reloc.entry.r_offset] = symbol.name or hex(reloc.entry.get("r_addend", 0))

    def memory(address, size=48):
        for segment in elf.iter_segments():
            if segment.header.p_type == "PT_LOAD":
                offset = address - segment.header.p_vaddr
                if 0 <= offset < segment.header.p_filesz:
                    return segment.data()[offset:offset + size]
        return b""

    def preview(address):
        if address in relocs:
            return f"reloc {relocs[address]}"
        block = memory(address)
        if not block:
            return ""
        text = block.split(b"\0", 1)[0]
        if len(text) >= 3 and all(32 <= b < 127 for b in text):
            return repr(text.decode("ascii"))
        return block[:24].hex(" ")

    out = [f"# {name}: selected readers (static disassembly only)", ""]
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    for function in wanted:
        if function.startswith("@"):
            start, size = (int(s, 0) for s in function[1:].split(":"))
        else:
            symbol = symbols.get(function)
            if symbol is None:
                out.append(f"Missing symbol: {function}\n")
                continue
            start, size = symbol.entry.st_value, symbol.entry.st_size
        out.extend([f"## {function} @ {start:#x}, {size} bytes", "", "```text"])
        known = {}
        for ins in md.disasm(memory(start, min(size, 12000)), start):
            comment = ""
            ops = ins.operands
            if ins.mnemonic in ("bl", "b") and ops[0].type == ARM64_OP_IMM:
                comment = targets.get(ops[0].imm, "")
            elif ins.mnemonic in ("adrp", "adr"):
                known[ops[0].reg] = ops[1].imm
            elif ins.mnemonic == "add" and len(ops) == 3 and ops[2].type == ARM64_OP_IMM and ops[1].reg in known:
                address = known[ops[1].reg] + ops[2].imm
                known[ops[0].reg] = address
                comment = f"{address:#x}: {preview(address)}"
            elif ins.mnemonic.startswith("ldr") and len(ops) == 2 and ops[1].type == ARM64_OP_MEM:
                mem = ops[1].mem
                if mem.base in known and not mem.index:
                    address = known[mem.base] + mem.disp
                    comment = f"[{address:#x}] {preview(address)}"
                known.pop(ops[0].reg, None)
            elif ops and ops[0].type == ARM64_OP_REG and ins.mnemonic not in ("cmp", "tst", "str", "strb", "strh", "stp"):
                known.pop(ops[0].reg, None)
            out.append(f"{ins.address:08x}: {ins.mnemonic:8} {ins.op_str:45}" + (f" ; {comment}" if comment else ""))
            if ins.mnemonic in ("bl", "blr"):
                # Do not treat caller-saved registers as constants after a call.
                known = {reg: value for reg, value in known.items() if md.reg_name(reg) in {f"x{i}" for i in range(19, 29)}}
        out.extend(["```", ""])
    out.extend(["## Matching symbol names", "", "```text"])
    for symbol in symbols.values():
        if any(s in symbol.name.lower() for s in ("crypt", "file", "fopen", "fread", "bin", "ggp", "gag", "text", "tbl")):
            out.append(f"{symbol.entry.st_value:#x} {symbol.entry.st_size:5} {symbol.name}")
    out.extend(["```", ""])
    return "\n".join(out)[:60000]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.archive) as archive:
        for info in archive.infolist():
            name = PurePosixPath(info.filename).name
            if name in READERS:
                report = reader_report(archive.read(info), name, READERS[name])
                (args.output / f"reader-{name}.md").write_text(report)
