#!/usr/bin/env python3
"""Summarise a directory of inspect_pack.py reports into a support matrix.

Read-only and brand-agnostic: it consumes only the JSON that inspect_pack.py already
produced, so no vendor archive is reopened or executed here. Reports an explicit
`NO` rather than assuming a member exists.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

HEADER = (
    "| Brand | Size | Entries | ZIP-enc | YZJM | .so | DEVICEID | STD | BIN.ZIP | .ggp | .gag |\n"
    "| --- | ---: | ---: | ---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: |"
)


def starts_with_yzjm(member: dict) -> bool:
    head = (member.get("headHex") or "").replace(" ", "")
    try:
        return bytes.fromhex(head)[:4] == b"YZJM"
    except ValueError:
        return False


def summarise(root: pathlib.Path) -> list[dict]:
    rows = []
    for page in sorted(root.glob("*/inspection.json")):
        report = json.loads(page.read_text(encoding="utf-8"))
        members = report.get("members") or []
        names = [m.get("name", "") for m in members]
        base = [n.split("/")[-1] for n in names]
        extensions = report.get("extensions") or {}
        rows.append({
            "brand": str(report.get("archive", page.parent.name)).removesuffix(".zip"),
            "bytes": int(report.get("bytes") or 0),
            "sha256": str(report.get("sha256") or ""),
            "entries": int(report.get("entryCount") or 0),
            "uncompressed": int(report.get("uncompressedBytes") or 0),
            "zipEncrypted": int(report.get("zipEncryptedEntries") or 0),
            "soCount": sum(1 for n in names if n.lower().endswith(".so")),
            "hasDeviceId": any(n.endswith("libDEVICEID.so") for n in base),
            "hasStd": any(n.endswith("libSTD.so") for n in base),
            "yzjmHeads": sum(1 for m in members if starts_with_yzjm(m)),
            "hasNestedBinZip": "BIN.ZIP" in base,
            "hasGgp": ".ggp" in extensions,
            "hasGag": ".gag" in extensions,
            "extensions": extensions,
        })
    return rows


def markdown(rows: list[dict]) -> str:
    lines = [HEADER]
    for r in rows:
        lines.append(
            f"| {r['brand']} | {r['bytes'] / 1048576:.1f} MB | {r['entries']} | {r['zipEncrypted']} | "
            f"{r['yzjmHeads']} | {r['soCount']} | {'yes' if r['hasDeviceId'] else 'NO'} | "
            f"{'yes' if r['hasStd'] else 'NO'} | {'yes' if r['hasNestedBinZip'] else 'no'} | "
            f"{'yes' if r['hasGgp'] else 'no'} | {'yes' if r['hasGag'] else 'no'} |")
    return "\n".join(lines) + "\n"


MEMBER_HEADER = ("| file | bytes | type | prefix entropy |\n"
                 "| --- | ---: | --- | ---: |")


def member_markdown(root: pathlib.Path, limit: int = 40) -> str:
    """Per-brand member listings, bounded so a report stays publishable."""
    chunks = []
    for page in sorted(root.glob("*/inspection.json")):
        report = json.loads(page.read_text(encoding="utf-8"))
        brand = str(report.get("archive", page.parent.name)).removesuffix(".zip")
        members = report.get("members") or []
        chunks.append(f"### {brand} \u2014 {report.get('bytes', 0)} bytes, "
                      f"{report.get('entryCount', 0)} entries\n")
        chunks.append(MEMBER_HEADER)
        for member in members[:limit]:
            name = str(member.get("name", "")).replace("|", "\\|")
            chunks.append(f"| `{name}` | {member.get('bytes', 0)} | {member.get('type', '')} | "
                          f"{member.get('entropy', '')} |")
        if len(members) > limit:
            chunks.append(f"| _…{len(members) - limit} more members omitted_ |  |  |  |")
        chunks.append("")
    return "\n".join(chunks) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    args = parser.parse_args(argv)
    if not args.directory.is_dir():
        print(f"Not a directory: {args.directory}", file=sys.stderr)
        return 2
    rows = summarise(args.directory)
    (args.directory / "summary.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    body = markdown(rows)
    (args.directory / "summary.md").write_text(body, encoding="utf-8")
    (args.directory / "members.md").write_text(member_markdown(args.directory), encoding="utf-8")
    print(body, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
