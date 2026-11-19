#!/usr/bin/env python3
"""Render sample recovered records so a reviewer can see the real data shape.

Reads the summary produced by `extract_brand_text.py` and prints a few decoded records per
brand and member. Bounded output: samples only, never a full library dump.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys


def render(summary: list[dict], per_member: int = 3) -> str:
    lines = ["## Sample recovered records", ""]
    for brand in summary:
        if "error" in brand:
            lines.append(f"### {brand['brand']} — failed: {brand['error']}")
            lines.append("")
            continue
        lines.append(f"### {brand['brand']} — {brand['totalRecords']} records "
                     f"from `{brand['textLibrary']}`")
        lines.append("")
        for member in brand.get("members", []):
            pairs = member.get("pairExamples") or []
            examples = member.get("examples") or []
            if not pairs and not examples:
                continue
            lines.append(f"**`{member['name']}`** — {member.get('records', '?')} records, "
                         f"{member.get('utf8Records', '?')} UTF-8")
            lines.append("")
            for identity, text in (pairs or examples)[:per_member]:
                lines.append(f"- `{identity}` → {text}")
            lines.append("")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("summary", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--per-member", type=int, default=3)
    args = parser.parse_args(argv)
    try:
        summary = json.loads(args.summary.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"Cannot read {args.summary}: {error}", file=sys.stderr)
        return 2
    text = render(summary, args.per_member)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
