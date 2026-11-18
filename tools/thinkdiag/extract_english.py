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


def extract(path: Path, output: Path, report_dir: Path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != TOYOTA_SHA256:
        raise ValueError("Unexpected archive SHA-256; native format-table offsets are version-specific")
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as archive:
        files = {PurePosixPath(i.filename).name: i for i in checked_infos(archive) if not i.is_dir()}
        native = archive.read(files["libSTD.so"])
        key = bytes.fromhex(elf_bytes(native, 0x423B3, 64).decode("ascii"))
        format_xor = elf_bytes(native, 0x4C81C, 15)
        members = unpack_ggp(archive.read(files["TOYOTA_EN.GGP"]), key)
    summaries = []
    for member in members:
        (output / member.name).write_bytes(member.data)
        item = {"name": member.name, "offset": member.offset, "bytes": len(member.data),
                "headHex": member.data[:48].hex(" "), "prefixBase64": base64.b64encode(member.data[:512]).decode("ascii")}
        if member.data.startswith((b"LAH", b"WCX")):
            library = GagFile(member.data, format_xor)
            text = {}
            invalid = []
            for identity, raw in library.records():
                try:
                    text[identity.hex().upper()] = raw.decode("utf-8").rstrip("\0")
                except UnicodeDecodeError:
                    if len(invalid) < 3:
                        invalid.append({"id": identity.hex(), "raw": raw[:64].hex(" ")})
            (output / (member.name + ".json")).write_text(json.dumps(text, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
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
