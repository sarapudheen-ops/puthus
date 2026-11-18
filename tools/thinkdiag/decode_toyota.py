#!/usr/bin/env python3
"""Decode Toyota YZJM tables for offline research, without executing vendor code.

Outputs stay in .local/. Diagnostic requests are NOT inferred from random bytes
or enabled in the app just because a table was decoded.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

from elftools.elf.elffile import ELFFile
from analyze_toyota import TOYOTA_SHA256, checked_infos
from yzjm import decode_yzjm


def elf_bytes(data, address, size):
    elf = ELFFile(io.BytesIO(data))
    for segment in elf.iter_segments():
        offset = address - segment.header.p_vaddr
        if segment.header.p_type == "PT_LOAD" and 0 <= offset and offset + size <= segment.header.p_filesz:
            return segment.data()[offset:offset + size]
    raise ValueError(f"ELF data address {address:#x} is out of bounds")


def decode_archive(path: Path, output: Path, report_dir: Path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != TOYOTA_SHA256:
        raise ValueError("Unexpected archive SHA-256; native table offsets are version-specific")
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    manifest = []
    previews = []
    with zipfile.ZipFile(path) as archive:
        files = {PurePosixPath(i.filename).name: i for i in checked_infos(archive) if not i.is_dir()}
        table = elf_bytes(archive.read(files["libDEVICEID.so"]), 0xF88C, 32)

        def decode_one(name, data):
            if not data.startswith(b"\x05\x00YZJM\0"):
                return
            decoded = decode_yzjm(data, table)
            target = PurePosixPath(name)
            if target.is_absolute() or ".." in target.parts or "\\" in name:
                raise ValueError("Unsafe output name")
            destination = output / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(decoded.payload)
            item = {"file": name, "make": decoded.make, "version": decoded.version,
                    "bytes": len(decoded.payload), "sha256": hashlib.sha256(decoded.payload).hexdigest()}
            manifest.append(item)
            if "/" not in name or name.endswith("Ecu_Data_0001.BIN"):
                previews.append({**item, "headHex": decoded.payload[:256].hex(" "),
                                 "strings": [s.decode("ascii") for s in re.findall(rb"[\x20-\x7e]{6,}", decoded.payload)[:12]]})

        for name, info in files.items():
            if name.endswith(".BIN"):
                decode_one(name, archive.read(info))
        with zipfile.ZipFile(io.BytesIO(archive.read(files["BIN.ZIP"]))) as inner:
            for info in checked_infos(inner):
                if not info.is_dir() and info.filename.endswith(".BIN"):
                    decode_one(info.filename, inner.read(info))
    (report_dir / "decoded-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    summary = {"decodedFiles": len(manifest), "decodedBytes": sum(i["bytes"] for i in manifest),
               "versions": dict(Counter(i["version"] for i in manifest)), "previews": previews}
    (report_dir / "decoded-tables.md").write_text("# YZJM decoded tables (not executable recipes)\n\n```json\n" + json.dumps(summary, indent=2) + "\n```\n")
    print(f"Decoded {len(manifest)} YZJM files. Raw decoded tables remain outside git.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    decode_archive(args.archive, args.output, args.report)
