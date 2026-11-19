#!/usr/bin/env python3
"""Check whether the recovered table namespaces join.

A runnable live value needs three things: a label, a scaling rule, and the ECU + request that
produces the bytes. The archive separates those into different members. This tool measures how
far the join actually goes by comparing record-ID namespaces between members. It reports overlap
only; a shared ID is evidence of a candidate join, not proof of one.
"""
from __future__ import annotations

import argparse
import json
import pathlib

# Members that could plausibly hold one side of a join, and what they would contribute.
GROUPS = {
    "calc": ("*_CALC.GAG.json", "scaling rules"),
    "dsid": ("*_DSID.GAG.json", "data-stream identifiers"),
    "dsin": ("*_DSIN.GAG.json", "identifiers / part numbers"),
    "dst": ("*_DSTREAM_EN.GAG.json", "live-data labels"),
    "uds": ("*_UDS_INDEX.GAG.json", "named values"),
    "text": ("*_TEXT_EN.GAG.json", "general text"),
    "cmd": ("*_COMMAND.GAG.json", "command pool"),
    "can": ("*_CAN_ADD.GAG.json", "CAN addressing"),
}


def load(directory: pathlib.Path, pattern: str) -> dict[str, str]:
    """Merge every member matching a pattern into one ID -> text map."""
    merged: dict[str, str] = {}
    for path in sorted(directory.glob(pattern)):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(records, dict):
            for identity, text in records.items():
                if isinstance(text, str):
                    merged.setdefault(identity, text)
    return merged


def prefix(identity: str, size: int = 2) -> str:
    return identity[:size].upper()


def analyse(directory: pathlib.Path) -> dict:
    groups = {name: load(directory, pattern) for name, (pattern, _) in GROUPS.items()}
    report = {"sizes": {name: len(rows) for name, rows in groups.items()}, "pairs": []}
    names = [n for n in groups if groups[n]]
    for i, left in enumerate(names):
        for right in names[i + 1:]:
            shared = set(groups[left]) & set(groups[right])
            if not shared:
                continue
            report["pairs"].append({
                "left": left, "right": right, "shared": len(shared),
                "examples": sorted(shared)[:5],
                "leftExample": groups[left][sorted(shared)[0]][:120],
                "rightExample": groups[right][sorted(shared)[0]][:120],
            })
    # Prefix distribution: do the two sides even live in the same ID space?
    report["prefixes"] = {
        name: {p: sum(1 for k in rows if prefix(k) == p)
               for p in sorted({prefix(k) for k in rows})}
        for name, rows in groups.items() if rows
    }
    report["pairs"].sort(key=lambda pair: -pair["shared"])
    return report


def markdown(report: dict) -> str:
    lines = ["## Namespace join probe", "", "Record counts:", ""]
    lines.append("| group | records | ID prefixes |")
    lines.append("| --- | ---: | --- |")
    for name, rows in report["sizes"].items():
        prefixes = report["prefixes"].get(name, {})
        top = ", ".join(f"{p}:{c}" for p, c in sorted(prefixes.items(), key=lambda x: -x[1])[:6])
        lines.append(f"| {name} | {rows} | {top or '-'} |")
    lines.append("")
    lines.append("### Shared record IDs between members")
    lines.append("")
    if not report["pairs"]:
        lines.append("_No shared record IDs between any two members._")
    else:
        lines.append("| left | right | shared IDs | example |")
        lines.append("| --- | --- | ---: | --- |")
        for pair in report["pairs"][:20]:
            example = pair["examples"][0] if pair["examples"] else ""
            lines.append(f"| {pair['left']} | {pair['right']} | {pair['shared']} | `{example}` |")
        lines.append("")
        lines.append("### Examples")
        lines.append("")
        for pair in report["pairs"][:6]:
            lines.append(f"- **{pair['left']} ↔ {pair['right']}** `{pair['examples'][0]}`: "
                         f"{pair['leftExample'][:90]} ⟷ {pair['rightExample'][:90]}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    report = analyse(args.directory)
    text = markdown(report)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
