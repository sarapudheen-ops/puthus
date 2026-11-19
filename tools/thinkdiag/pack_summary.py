#!/usr/bin/env python3
"""Summarise built DTC packs as a markdown table (codes, makes, size)."""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

HEADER = "| Pack | Makes | Codes | Base | Extended | Bytes |\n| --- | --- | ---: | ---: | ---: | ---: |"


def summarise(directory: pathlib.Path) -> list[dict]:
    rows = []
    for path in sorted(directory.glob("*-dtcs.json")):
        try:
            pack = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        groups = pack.get("dtcGroups") or []
        if not groups:
            continue
        dtcs = groups[0].get("dtcs") or {}
        rows.append({
            "pack": pack.get("id", path.stem),
            "makes": ", ".join(groups[0].get("makes") or []),
            "codes": len(dtcs),
            "base": sum("-" not in code for code in dtcs),
            "extended": sum("-" in code for code in dtcs),
            "bytes": path.stat().st_size,
        })
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    rows = summarise(args.directory)
    lines = [HEADER]
    for row in rows:
        lines.append(f"| {row['pack']} | {row['makes']} | {row['codes']} | "
                     f"{row['base']} | {row['extended']} | {row['bytes']} |")
    text = "\n".join(lines) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    if not rows:
        print("No DTC packs found", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
