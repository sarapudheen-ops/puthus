#!/usr/bin/env python3
"""Find the code/description separator a brand's TCODE records actually use.

Brands do not agree on how a code and its description are separated inside one record.
Toyota/VW use a literal NUL. Others use a printable delimiter. This samples the recovered
records and reports, per candidate separator, how many records split into exactly a valid
code plus a plausible description. It only measures; conversion decides.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

CODE = re.compile(r"[PCBU][0-3][0-9A-F]{3}(?:[0-9A-F]{2})?")
CANDIDATES = [
    ("NUL", "\0"), ("pipe", "|"), ("tab", "\t"), ("semicolon", ";"), ("comma", ","),
    ("colon", ":"), ("equals", "="), ("hyphen-space", " - "), ("space", " "),
    ("newline", "\n"), ("double-pipe", "||"), ("tilde", "~"), ("caret", "^"),
]


def score(records: dict[str, str], separator: str) -> dict:
    good = 0
    examples = []
    for identity, text in records.items():
        if not isinstance(text, str) or separator not in text:
            continue
        head, _, rest = text.partition(separator)
        head = head.strip()
        rest = rest.strip()
        if CODE.fullmatch(head) and len(re.sub(r"[^A-Za-z]", "", rest)) >= 4:
            good += 1
            if len(examples) < 3:
                examples.append([identity, head, rest[:110]])
    return {"separator": separator, "pairs": good, "examples": examples}


def analyse(directory: pathlib.Path) -> dict:
    results = {}
    for path in sorted(directory.glob("*TCODE*.GAG.json")):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(records, dict) or not records:
            continue
        scored = [score(records, sep) for _, sep in CANDIDATES]
        scored = [row for row in scored if row["pairs"]]
        scored.sort(key=lambda row: -row["pairs"])
        results[path.name] = {"records": len(records), "best": scored[:4]}
    return results


def markdown(results: dict) -> str:
    lines = ["## Code/description separator per brand", ""]
    if not results:
        lines.append("_No TCODE members found._")
        return "\n".join(lines) + "\n"
    lines.append("| member | records | best separator | pairs |")
    lines.append("| --- | ---: | --- | ---: |")
    for name, row in results.items():
        best = row["best"][0] if row["best"] else None
        shown = (best["separator"].encode("unicode_escape").decode("ascii") if best else "-")
        lines.append(f"| `{name}` | {row['records']} | `{shown}` | {best['pairs'] if best else 0} |")
    lines.append("")
    lines.append("### Examples")
    lines.append("")
    for name, row in results.items():
        for candidate in row["best"][:2]:
            for identity, code, text in candidate["examples"][:2]:
                lines.append(f"- `{name}` sep=`{candidate['separator'].encode('unicode_escape').decode('ascii')}` "
                             f"`{identity}` → **{code}** {text}")
    return "\n".join(lines) + "\n"


def best_separator(directory: pathlib.Path) -> str | None:
    """The separator that splits the most records into a code plus a description."""
    results = analyse(directory)
    totals: dict[str, int] = {}
    for row in results.values():
        for candidate in row["best"]:
            totals[candidate["separator"]] = totals.get(candidate["separator"], 0) + candidate["pairs"]
    return max(totals, key=totals.get) if totals else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    results = analyse(args.directory)
    text = markdown(results)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if results else 1


if __name__ == "__main__":
    raise SystemExit(main())
