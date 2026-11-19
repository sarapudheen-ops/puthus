#!/usr/bin/env python3
"""Print raw, unmodified TCODE records per brand so the real format is visible."""
from __future__ import annotations

import argparse
import json
import pathlib

MAX = 40_000


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    lines = ["## Raw TCODE records (unmodified)", ""]
    for path in sorted(args.directory.glob("*TCODE*.GAG.json")):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(records, dict) or not records:
            continue
        lines.append(f"### `{path.name}` — {len(records)} records")
        lines.append("")
        for identity, text in list(records.items())[:6]:
            snippet = text[:130].encode("unicode_escape").decode("ascii")
            lines.append(f"- `{identity}` → `{snippet}`")
        lines.append("")
        if sum(len(x) + 1 for x in lines) > MAX:
            break
    text = "\n".join(lines) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
