#!/usr/bin/env python3
"""Merge every TCODE library a brand ships into one ID→text map.

Brands split fault-code text across files (BENZ: TCODE_EN, TCODE_EX_EN, TCODE_X_EN). Converting
only one loses most of the codes. Identical duplicate records collapse; differing text for the
same record ID is kept as separate records so the converter's conflict rule still applies.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--brand", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)

    merged: dict[str, str] = {}
    sources = []
    total = 0
    for path in sorted(args.directory.glob(f"{args.brand.upper()}_TCODE*.GAG.json")):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            print(f"skipping {path.name}: {error}", file=sys.stderr)
            continue
        if not isinstance(records, dict):
            continue
        sources.append(path.name)
        for index, (identity, text) in enumerate(records.items()):
            total += 1
            if not isinstance(text, str):
                continue
            key = identity
            suffix = 0
            # Keep differing text for the same record ID as its own record, so a real
            # disagreement stays a conflict instead of being silently overwritten.
            while key in merged and merged[key] != text:
                suffix += 1
                key = f"{identity}#{suffix}"
            merged.setdefault(key, text)

    if not merged:
        print(f"{args.brand}: no TCODE records found", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(merged, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{args.brand}: merged {total} records from {', '.join(sources)} -> {len(merged)} unique")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
