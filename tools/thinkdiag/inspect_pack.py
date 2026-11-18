#!/usr/bin/env python3
"""Inspect a vendor ZIP as data only; never load or execute any bundled program.

Raw vendor archives belong in .local/, not in git. The report distinguishes ZIP
password protection from opaque/encrypted *members* (they are different layers).
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

MAX_ENTRIES = 20_000
MAX_UNCOMPRESSED = 2 * 1024**3
PREFIX_BYTES = 64 * 1024


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    return round(-sum((n / len(data)) * math.log2(n / len(data)) for n in Counter(data).values()), 3)


def signatures(data: bytes) -> str:
    for magic, name in (
        (b"SQLite format 3\x00", "SQLite database"),
        (b"\x7fELF", "ELF executable/library"),
        (b"MZ", "PE/DOS executable"),
        (b"PK\x03\x04", "ZIP container"),
        (b"\x1f\x8b", "gzip stream"),
        (b"7z\xbc\xaf\x27\x1c", "7-Zip container"),
        (b"Rar!", "RAR container"),
        (b"\x89PNG\r\n\x1a\n", "PNG image"),
        (b"\xff\xd8\xff", "JPEG image"),
    ):
        if data.startswith(magic):
            return name
    if len(data) >= 2 and data[0] & 0x0F == 8 and int.from_bytes(data[:2], "big") % 31 == 0:
        return "possible zlib stream"
    try:
        text = data.decode("utf-8-sig")
        if text and sum(c.isprintable() or c in "\n\r\t" for c in text) / len(text) > 0.95:
            return "UTF-8 text"
    except UnicodeDecodeError:
        pass
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "UTF-16 text"
    return "unidentified binary"


def strings(data: bytes) -> list[str]:
    # A sample for identifying formats, not evidence of a decoded diagnostic definition.
    return [s.decode("ascii") for s in re.findall(rb"[\x20-\x7e]{6,}", data)[:12]]


def inspect_archive(path: Path) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as src:
        for chunk in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(chunk)
    report = {"archive": path.name, "bytes": path.stat().st_size, "sha256": digest.hexdigest(), "members": []}
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError(f"Too many ZIP entries: {len(infos)}")
        if sum(i.file_size for i in infos) > MAX_UNCOMPRESSED:
            raise ValueError("Uncompressed ZIP exceeds the 2 GiB inspection limit")
        report["entryCount"] = len(infos)
        report["uncompressedBytes"] = sum(i.file_size for i in infos)
        report["zipEncryptedEntries"] = sum(bool(i.flag_bits & 1) for i in infos)
        report["extensions"] = dict(Counter(PurePosixPath(i.filename).suffix.lower() for i in infos if not i.is_dir()))
        for info in infos:
            if info.is_dir():
                continue
            name = PurePosixPath(info.filename.replace("\\", "/"))
            if name.is_absolute() or ".." in name.parts or stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Unsafe ZIP member: {info.filename}")
            item = {"name": info.filename, "bytes": info.file_size, "zipEncrypted": bool(info.flag_bits & 1)}
            if not item["zipEncrypted"]:
                with archive.open(info) as src:
                    prefix = src.read(PREFIX_BYTES)
                item.update({
                    "type": signatures(prefix), "headHex": prefix[:64].hex(" "),
                    "entropy": entropy(prefix), "strings": strings(prefix),
                })
                if item["type"] in ("UTF-8 text", "UTF-16 text"):
                    encoding = "utf-16" if item["type"] == "UTF-16 text" else "utf-8-sig"
                    item["textPreview"] = prefix.decode(encoding, errors="replace")[:4000]
            report["members"].append(item)
    return report


def markdown(report: dict) -> str:
    out = io.StringIO()
    out.write(f"# {report['archive']} inspection\n\n")
    out.write(f"- SHA-256: `{report['sha256']}`\n")
    for key in ("bytes", "entryCount", "uncompressedBytes", "zipEncryptedEntries", "extensions"):
        out.write(f"- {key}: {report[key]}\n")
    out.write("\nOnly file contents were inspected. No vendor executable was run.\n\n")
    out.write("## File inventory\n\n| File | Bytes | Detected type | Prefix entropy |\n| --- | ---: | --- | ---: |\n")
    for item in report["members"][:350]:
        name = item["name"].replace("|", "&#124;").replace("`", "")
        out.write(f"| `{name}` | {item['bytes']} | {item.get('type', 'ZIP encrypted')} | {item.get('entropy', '')} |\n")
    out.write("\n## Representative file prefixes\n\n")
    counts: Counter = Counter()
    for item in report["members"]:
        suffix = PurePosixPath(item["name"]).suffix.lower()
        if counts[suffix] >= 3:
            continue
        counts[suffix] += 1
        out.write(f"### {item['name']}\n\n```json\n{json.dumps(item, indent=2, ensure_ascii=True)}\n```\n\n")
    return out.getvalue()[:60_000]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inspect_archive(args.archive)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "inspection.json").write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    (args.output / "summary.md").write_text(markdown(report), encoding="utf-8")
    print(f"Inspected {report['entryCount']} entries; {report['zipEncryptedEntries']} are ZIP encrypted.")


if __name__ == "__main__":
    main()
