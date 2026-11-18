#!/usr/bin/env python3
"""Static format research for the Toyota asset. Does not import/load vendor code."""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

from elftools.elf.elffile import ELFFile
from inspect_pack import MAX_ENTRIES, MAX_UNCOMPRESSED, entropy, signatures

TOYOTA_SHA256 = "26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a"
INTEREST = re.compile(r"crypt|decode|encode|file|bin|aes|des|xor|zip|ggp|ggb|text|menu|read|open|seek", re.I)
SELECTED = {"libSTD.so", "libTOYOTA_FILE.so", "libTOYOTA_FZIP.so", "libTOYOTA_FDATA.so"}


def native_info(data: bytes, name: str) -> dict:
    elf = ELFFile(io.BytesIO(data))
    result = {"name": name, "machine": elf.get_machine_arch(), "functions": [], "needed": []}
    dynamic = elf.get_section_by_name(".dynamic")
    if dynamic is not None:
        result["needed"] = [tag.needed for tag in dynamic.iter_tags() if tag.entry.d_tag == "DT_NEEDED"]
    symbols = elf.get_section_by_name(".dynsym")
    if symbols is not None:
        for symbol in symbols.iter_symbols():
            if symbol.name and symbol.entry.st_info.type == "STT_FUNC":
                result["functions"].append({
                    "name": symbol.name, "address": hex(symbol.entry.st_value), "size": symbol.entry.st_size,
                    "defined": symbol.entry.st_shndx != "SHN_UNDEF",
                })
    matches = []
    for s in re.findall(rb"[\x20-\x7e]{4,180}", data):
        text = s.decode("ascii")
        if INTEREST.search(text):
            matches.append(text)
    result["strings"] = list(dict.fromkeys(matches))
    return result


def checked_infos(archive):
    infos = archive.infolist()
    if len(infos) > MAX_ENTRIES or sum(i.file_size for i in infos) > MAX_UNCOMPRESSED:
        raise ValueError("Nested archive exceeds inspection limits")
    return infos


def analyze(path: Path, output: Path) -> None:
    if hashlib.sha256(path.read_bytes()).hexdigest() != TOYOTA_SHA256:
        raise ValueError("This analysis is pinned to the inspected TOYOTA.zip SHA-256")
    output.mkdir(parents=True, exist_ok=True)
    natives = []
    binary = []
    nested = {}
    with zipfile.ZipFile(path) as archive:
        for info in checked_infos(archive):
            name = PurePosixPath(info.filename).name
            if name.endswith(".so"):
                data = archive.read(info)
                if data.startswith(b"\x7fELF"):
                    natives.append(native_info(data, name))
            elif name.endswith((".BIN", ".GGP", ".GAG")):
                data = archive.read(info)
                binary.append({
                    "name": name, "size": len(data), "headHex": data[:256].hex(" "),
                    "prefixBase64": base64.b64encode(data[:1024]).decode("ascii"),
                    "tailHex": data[-128:].hex(" "), "entropy": entropy(data[:65536]),
                })
            elif name == "BIN.ZIP":
                with zipfile.ZipFile(io.BytesIO(archive.read(info))) as inner:
                    infos = checked_infos(inner)
                    nested.update({
                        "entries": len(infos), "size": sum(i.file_size for i in infos),
                        "zipEncrypted": sum(bool(i.flag_bits & 1) for i in infos),
                        "regions": dict(Counter(PurePosixPath(i.filename).parts[0] for i in infos if i.filename)),
                        "sampleNames": [i.filename for i in infos[:30]], "samples": [],
                    })
                    counts = Counter()
                    for member in infos:
                        if member.is_dir() or member.flag_bits & 1:
                            continue
                        region = PurePosixPath(member.filename).parts[0]
                        if counts[region] >= 2:
                            continue
                        counts[region] += 1
                        with inner.open(member) as src:
                            data = src.read(4096)
                        nested["samples"].append({
                            "name": member.filename, "size": member.file_size, "type": signatures(data),
                            "headHex": data[:256].hex(" "), "prefixBase64": base64.b64encode(data[:1024]).decode("ascii"),
                        })
    (output / "native-analysis.json").write_text(json.dumps(natives, indent=2))
    (output / "binary-analysis.json").write_text(json.dumps({"binary": binary, "nested": nested}, indent=2))
    out = io.StringIO()
    out.write("# Native symbols (static analysis only)\n\n")
    for lib in natives:
        out.write(f"## {lib['name']} ({lib['machine']})\n\nNeeded: {lib['needed']}\n\n```text\n")
        funcs = lib["functions"]
        if lib["name"] not in SELECTED:
            funcs = [f for f in funcs if INTEREST.search(f["name"])]
        for f in funcs[:180]:
            out.write(f"{'EXPORT' if f['defined'] else 'IMPORT'} {f['address']} {f['size']:5} {f['name']}\n")
        out.write("```\n\n")
        if lib["name"] in SELECTED:
            out.write("Matching strings:\n\n```text\n" + "\n".join(lib["strings"][:80]) + "\n```\n\n")
    (output / "native-symbols.md").write_text(out.getvalue()[:60000])
    (output / "binary-containers.md").write_text("# Binary headers and nested ZIP\n\n```json\n" + json.dumps({"binary": binary, "nested": nested}, indent=2)[:59000] + "\n```\n")
    print(f"Static analysis of {len(natives)} ELF files, {len(binary)} data files and BIN.ZIP completed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    analyze(args.archive, args.output)
