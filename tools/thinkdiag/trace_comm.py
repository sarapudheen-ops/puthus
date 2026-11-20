#!/usr/bin/env python3
"""Find how the native COMM library turns a system-entry record into an ECU request.

Every module in the Toyota data needs a CAN/K-line request header. The system-entry record
holds the raw fields, but their roles were only known by native offsets. This tool reads
`libTOYOTA_COMM.so` *statically* and reports the callers that consume a system-entry record,
so the field role can be established from the code that uses it rather than guessed.

No vendor code is loaded or executed: this is Capstone disassembly plus string/symbol tables.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path
import re
import zipfile

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

# Entry points worth tracing: the COMM layer that packs a request for an ECU.
INTERESTING = re.compile(
    r"(?i)(GetSystemEnter|SendCmdAndGetReceive|GetCommFrame|Addr|Header|Pack|Init|"
    r"Enter|Session|Baud|Pins|KeepAlive|GetCmdDataInfo)")
MAXFUNCS = 400


def functions(data: bytes) -> dict[int, tuple[int, int, str]]:
    """address -> (offset, size, name) for defined function symbols."""
    elf = ELFFile(io.BytesIO(data))
    symbols = elf.get_section_by_name(".dynsym")
    found = {}
    if symbols is None:
        return found
    for symbol in symbols.iter_symbols():
        if symbol.entry.st_info.type != "STT_FUNC" or symbol.entry.st_shndx == "SHN_UNDEF":
            continue
        if symbol.entry.st_size:
            found[symbol.entry.st_value] = (symbol.entry.st_value, symbol.entry.st_size, symbol.name)
    return found


def disassemble(data: bytes, start: int, size: int, limit: int = 120):
    elf = ELFFile(io.BytesIO(data))
    for segment in elf.iter_segments():
        offset = start - segment.header.p_vaddr
        if segment.header.p_type != "PT_LOAD" or offset < 0 or offset + size > segment.header.p_filesz:
            continue
        code = segment.data()[offset:offset + size]
        engine = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        return list(engine.disasm(code, start))[:limit]
    return []


def describe(data: bytes, wanted: str) -> dict:
    table = functions(data)
    chosen = {addr: item for addr, item in table.items() if INTERESTING.search(item[2])}
    report = {"libraryBytes": len(data), "functionCount": len(table), "matched": [], "strings": []}
    for addr, (_, size, name) in sorted(chosen.items())[:MAXFUNCS]:
        instructions = disassemble(data, addr, size)
        # Record the immediate operands: field offsets show up here as small constants.
        immediates = sorted({ins.op_str for ins in instructions if ins.op_str.startswith("#") and len(ins.op_str) <= 6})
        report["matched"].append({
            "name": name, "address": f"0x{addr:x}", "size": size,
            "instructions": len(instructions),
            "immediates": immediates[:40],
            "sample": [f"{ins.mnemonic} {ins.op_str}" for ins in instructions[:24]],
        })
    text = data.decode("latin-1")
    report["strings"] = sorted({m.group(0) for m in re.finditer(r"[ -~]{6,40}", text)
                                if INTERESTING.search(m.group(0))})[:60]
    return report


def markdown(report: dict) -> str:
    lines = [f"## `libTOYOTA_COMM.so` — {report['functionCount']} functions, "
             f"{len(report['matched'])} matched", ""]
    lines.append("### Functions that touch system-entry / request packing")
    lines.append("")
    lines.append("| function | address | size | field immediates |")
    lines.append("| --- | --- | ---: | --- |")
    for item in report["matched"]:
        lines.append(f"| `{item['name']}` | {item['address']} | {item['size']} | "
                     f"{', '.join(item['immediates'][:14]) or '-'} |")
    lines.append("")
    lines.append("### Disassembly samples")
    lines.append("")
    for item in report["matched"][:12]:
        lines.append(f"**`{item['name']}`** @ {item['address']}")
        lines.append("```")
        lines.extend(item["sample"])
        lines.append("```")
    if report["strings"]:
        lines.append("### Matching strings")
        lines.append("")
        for value in report["strings"][:30]:
            lines.append(f"- `{value}`")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--library", default="libTOYOTA_COMM.so")
    args = parser.parse_args(argv)
    with zipfile.ZipFile(args.archive) as archive:
        names = {Path(i.filename).name: i for i in archive.infolist() if not i.is_dir()}
        if args.library not in names:
            raise SystemExit(f"{args.library} not in archive")
        data = archive.read(names[args.library])
    report = describe(data, args.library)
    text = markdown(report)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
