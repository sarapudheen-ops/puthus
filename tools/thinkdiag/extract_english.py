#!/usr/bin/env python3
"""Recover indexed English text records; never infer vehicle commands from text."""
import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

from analyze_toyota import TOYOTA_SHA256, checked_infos
from decode_toyota import elf_bytes
from ggp import GagFile, unpack_ggp


def read_members(path: Path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != TOYOTA_SHA256:
        raise ValueError("Unexpected archive SHA-256; native format-table offsets are version-specific")
    with zipfile.ZipFile(path) as archive:
        files = {}
        for info in checked_infos(archive):
            if info.is_dir(): continue
            name = PurePosixPath(info.filename).name
            if name in files:
                raise ValueError("Ambiguous English-library or format-table member")
            files[name] = info
        native = archive.read(files["libSTD.so"])
        key = bytes.fromhex(elf_bytes(native, 0x423B3, 64).decode("ascii"))
        format_xor = elf_bytes(native, 0x4C81C, 15)
        return unpack_ggp(archive.read(files["TOYOTA_EN.GGP"]), key), format_xor


def read_text_libraries(path: Path, names=('TCODE_EN.GAG', 'TEXT_EN.GAG')):
    """Re-extract selected pairs directly from the pinned archive, with locations.

    Do not trust a mutable English JSON file just because a different decoded
    BIN manifest passed. There is no native execution in this data reader.
    """
    if not names or len(set(names)) != len(names):
        raise ValueError('Need distinct English text-library names')
    members, table = read_members(path)
    result = {}
    for member in members:
        if member.name not in names: continue
        library = GagFile(member.data, table)
        records, locations = {}, {}
        for row in library.indexed_records():
            key = row.identity.hex().upper()
            records[key] = row.data.decode('utf-8').rstrip('\0')
            locations[key] = {'indexOffset': row.index_offset, 'textOffset': row.text_offset,
                              'encodedBytes': row.encoded_bytes, 'ggpMemberOffset': member.offset}
        result[member.name] = (records, locations)
    if set(result) != set(names):
        raise ValueError('Missing exact English text library')
    return result


def extract(path: Path, output: Path, report_dir: Path):
    members, format_xor = read_members(path)
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    summaries = []
    for member in members:
        (output / member.name).write_bytes(member.data)
        item = {"name": member.name, "offset": member.offset, "bytes": len(member.data),
                "headHex": member.data[:48].hex(" "), "prefixBase64": base64.b64encode(member.data[:512]).decode("ascii")}
        if member.data.startswith((b"LAH", b"WCX")):
            library = GagFile(member.data, format_xor)
            text, locations = {}, {}
            invalid = []
            for row in library.indexed_records():
                identity, raw = row.identity, row.data
                try:
                    key = identity.hex().upper()
                    text[key] = raw.decode("utf-8").rstrip("\0")
                    locations[key] = {'indexOffset': row.index_offset, 'textOffset': row.text_offset,
                                      'encodedBytes': row.encoded_bytes, 'ggpMemberOffset': member.offset}
                except UnicodeDecodeError:
                    if len(invalid) < 3:
                        invalid.append({"id": identity.hex(), "raw": raw[:64].hex(" ")})
            (output / (member.name + ".json")).write_text(json.dumps(text, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            (output / (member.name + ".provenance.json")).write_text(json.dumps(locations, sort_keys=True) + "\n", encoding="utf-8")
            pairs = list(text.items())
            item.update({
                "records": library.count, "utf8Records": len(text), "invalidUtf8Examples": invalid,
                "examples": [(i, t[:150]) for i, t in pairs[:12]],
                "dtcExamples": [(i, t[:250]) for i, t in pairs if re.search(r"\b[PCBU][0-3][0-9A-F]{3}\b", t)][:10],
                "liveValueExamples": [(i, t[:150]) for i, t in pairs if re.search("coolant|engine speed|battery voltage", t, re.I)][:8],
                "modelExamples": [(i, t[:100]) for i, t in pairs if re.search("prius|camry|corolla", t, re.I)][:8],
            })
        summaries.append(item)
    (report_dir / "english-text.json").write_text(json.dumps(summaries, indent=2) + "\n")
    (report_dir / "english-text.md").write_text("# Recovered English text libraries\n\n```json\n" + json.dumps(summaries, indent=2) + "\n```\n")
    print(f"Recovered {len(members)} GGP member files; see English text report for validated record counts.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        extract(args.archive, args.output, args.report)
    except Exception as error:
        message = str(error).replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print(f"::error title=English data extraction::{message}", flush=True)
        raise
