#!/usr/bin/env python3
"""Recover indexed text libraries from any brand archive, as data only.

The Toyota pipeline hard-codes two ELF addresses in `libSTD.so`. Other brands move them.
This tool finds them by *self-validation* instead of by address:

  * the GGP filename key is a 64-character ASCII hex string; a candidate is accepted only
    when `unpack_ggp` returns well-formed ASCII member names;
  * the LAH/WCX format table is 15 bytes; a candidate is accepted only when it turns the
    256-byte substitution block into a true permutation of 0..255.

Both checks come from `ggp.py`, which already fails closed. No vendor code is loaded or
executed, and nothing here derives a vehicle command from recovered text.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

from ggp import GagFile, unpack_ggp

HEX64 = re.compile(rb"[0-9A-Fa-f]{64}")
TEXT_MEMBER = re.compile(r"(?i)(?:^|/)([A-Z0-9_]+\.GGP)$")
MAX_INSPECT = 512 * 1024 * 1024


def find_keys(data: bytes) -> list[bytes]:
    """Every 64-character ASCII hex run in the library, as raw 32-byte keys."""
    keys = []
    for match in HEX64.finditer(data):
        raw = match.group(0)
        # A real key decodes cleanly; keep order but drop duplicates.
        try:
            key = bytes.fromhex(raw.decode("ascii"))
        except ValueError:
            continue
        if key not in keys:
            keys.append(key)
    return keys


def find_format_tables(data: bytes, lahd: bytes, limit: int = 64) -> list[bytes]:
    """15-byte windows that turn the LAH substitution block into a permutation.

    Early-exits on the first repeated byte, so most windows cost only a few operations.
    """
    if len(lahd) < 263:
        return []
    block = lahd[7:263]
    found = []
    for start in range(0, len(data) - 15 + 1):
        window = data[start:start + 15]
        if window in found:
            continue
        seen = bytearray(256)
        ok = True
        for index, byte in enumerate(block):
            value = byte ^ window[index % 15]
            if seen[value]:
                ok = False
                break
            seen[value] = 1
        if ok:
            found.append(window)
            if len(found) >= limit:
                break
    return found


def open_ggp(data: bytes, keys: list[bytes]):
    """First (key, members) pair whose decrypted filenames are all valid ASCII."""
    errors = []
    for key in keys:
        try:
            return key, unpack_ggp(data, key)
        except Exception as error:  # py3rijndael and struct errors vary by failure mode
            errors.append(f"{key.hex()[:16]}: {error}")
    raise ValueError("No candidate key opened the GGP container: " + "; ".join(errors[:4]))


def open_gag(data: bytes, tables: list[bytes]):
    """First format table that parses as a complete, bounds-checked text library."""
    errors = []
    for table in tables:
        try:
            library = GagFile(data, table)
            # Force the index to be walked so a bad table cannot pass the header check.
            for _ in library.records():
                pass
            return table, library
        except Exception as error:
            errors.append(f"{table.hex()}: {error}")
    raise ValueError("No candidate format table parsed the LAH/WCX library: " + "; ".join(errors[:4]))


def extract(archive_path: Path, output: Path, report_dir: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    brand = archive_path.stem.upper()

    with zipfile.ZipFile(archive_path) as archive:
        infos = archive.infolist()
        if len(infos) > 20_000 or sum(i.file_size for i in infos) > MAX_INSPECT:
            raise ValueError("Archive exceeds inspection limits")
        members_by_base = {
            PurePosixPath(i.filename).name: i for i in infos if not i.is_dir()
        }
        if "libSTD.so" not in members_by_base:
            raise ValueError("Archive has no libSTD.so; the format constants live there")
        native = archive.read(members_by_base["libSTD.so"])

        text_ggps = [n for n in members_by_base if TEXT_MEMBER.match(n)]
        if not text_ggps:
            raise ValueError("Archive has no top-level *_EN.GGP text library")
        # Prefer the member naming the brand, then the largest.
        text_ggps.sort(key=lambda n: (brand not in n, -members_by_base[n].file_size))
        ggp_name = text_ggps[0]
        ggp_data = archive.read(members_by_base[ggp_name])

    keys = find_keys(native)
    if not keys:
        raise ValueError("No 64-character hex key found in libSTD.so")
    key, members = open_ggp(ggp_data, keys)

    summary = {
        "brand": brand,
        "archive": archive_path.name,
        "archiveSha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
        "textLibrary": ggp_name,
        "keyHex": key.hex(),
        "keyCandidates": len(keys),
        "members": [],
    }

    for member in members:
        (output / f"{brand}_{member.name}").write_bytes(member.data)
        item = {"name": member.name, "bytes": len(member.data), "kind": "raw"}
        if member.data.startswith((b"LAH", b"WCX")):
            tables = find_format_tables(native, member.data)
            if not tables:
                item["kind"] = "no_format_table"
                item["error"] = "No 15-byte window produced a valid permutation"
                summary["members"].append(item)
                continue
            table, library = open_gag(member.data, tables)
            records: dict[str, str] = {}
            invalid = 0
            for identity, raw in library.records():
                try:
                    records[identity.hex().upper()] = raw.decode("utf-8").rstrip("\0")
                except UnicodeDecodeError:
                    invalid += 1
            (output / f"{brand}_{member.name}.json").write_text(
                json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            item.update({
                "kind": "text",
                "records": library.count,
                "utf8Records": len(records),
                "invalidUtf8": invalid,
                "formatTableHex": table.hex(),
                "formatTableCandidates": len(tables),
                "codePairs": sum(1 for v in records.values() if "\0" in v),
                "examples": [(k, v[:120]) for k, v in list(records.items())[:5]],
                # First few NUL-delimited pairs, so the real code/description shape is
                # inspectable without publishing the whole extracted library.
                "pairExamples": [(k, v.replace("\0", " | ")[:160])
                                 for k, v in list(records.items()) if "\0" in v][:6],
            })
        summary["members"].append(item)

    texts = [m for m in summary["members"] if m["kind"] == "text"]
    summary["totalRecords"] = sum(m["records"] for m in texts)
    (report_dir / f"{brand}-text.md").write_text(
        "# " + brand + " text recovery\n\n```json\n" + json.dumps(summary, indent=2) + "\n```\n",
        encoding="utf-8")
    return summary


def detail_markdown(summary: dict) -> str:
    """Per-member breakdown for one brand, publishable as a check summary."""
    lines = [f"## {summary['brand']} text recovery", "",
             f"key `{summary['keyHex'][:32]}…` · library `{summary['textLibrary']}`", "",
             "| member | bytes | kind | records | utf8 | code pairs |",
             "| --- | ---: | --- | ---: | ---: | ---: |"]
    for member in summary["members"]:
        lines.append(
            f"| `{member['name']}` | {member['bytes']} | {member['kind']} | "
            f"{member.get('records', '-')} | {member.get('utf8Records', '-')} | "
            f"{member.get('codePairs', '-')} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)

    results = []
    failures = 0
    for archive in args.archives:
        try:
            summary = extract(archive, args.output, args.report)
            results.append(summary)
            print(f"{summary['brand']}: {summary['totalRecords']} records "
                  f"from {summary['textLibrary']} ({len(summary['members'])} members)")
        except Exception as error:  # keep going; one brand must not hide the others
            failures += 1
            print(f"{archive.stem.upper()}: FAILED - {error}", file=sys.stderr)
            results.append({"brand": archive.stem.upper(), "error": str(error)})

    (args.report / "brand-text-summary.json").write_text(
        json.dumps(results, indent=2) + "\n", encoding="utf-8")
    rows = ["| Brand | Text library | Members | Records | Key candidates | Status |",
            "| --- | --- | ---: | ---: | ---: | --- |"]
    for summary in results:
        if "error" in summary:
            rows.append(f"| {summary['brand']} | - | - | - | - | FAILED: {summary['error'][:60]} |")
        else:
            rows.append(f"| {summary['brand']} | {summary['textLibrary']} | "
                        f"{len(summary['members'])} | {summary['totalRecords']} | "
                        f"{summary['keyCandidates']} | ok |")
    (args.report / "brand-text-summary.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
    details = "\n".join(detail_markdown(s) for s in results if "error" not in s)
    (args.report / "brand-text-detail.md").write_text(details, encoding="utf-8")
    print("\n".join(rows))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
