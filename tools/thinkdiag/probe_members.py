#!/usr/bin/env python3
"""Inspect recovered brand records to find the label -> request/formula join.

Samples every recovered member and reports, per member, how many records look like a
request, a formula, a label or an identity, with examples. Read-only over already
extracted JSON; nothing is executed or promoted to a definition.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

REQUEST = re.compile(r"^(?:[0-9A-F]{2}[ ,]*){2,}$", re.I)
DID = re.compile(r"^(?:22|2E|2F|31|21|1A)[ ]?[0-9A-F]{2,6}$", re.I)
FORMULA = re.compile(r"\b(?:if\s*\(|x\d\s*[*/+\-]|y\s*=|string\()", re.I)
ODX = re.compile(r"A_(?:U?INT|ASCIISTRING|UNICODE2STRING|BYTES|FLOAT)", re.I)
PARTNO = re.compile(r"\b[0-9A-Z]{2,3}[- ]?[0-9A-Z]{3}[- ]?[0-9A-Z]{3}[- ]?[0-9A-Z]{3}\b")
UNIT = re.compile(r"^(.*?)\s*\|\s*(km|V|mbar|bar|%|rpm|°C|C|A|Ah|L|s|h|Nm|l/h|mg/stroke|Pa|kWh|W)$", re.I)


def classify(value: str) -> str:
    if FORMULA.search(value):
        return "formula"
    if ODX.search(value):
        return "odx_typedef"
    if DID.match(value.strip()):
        return "request_like"
    if REQUEST.match(value.strip()):
        return "hex_bytes"
    if UNIT.match(value):
        return "label_unit"
    if PARTNO.search(value):
        return "part_number"
    return "text"


def inspect(directory: pathlib.Path, per_member: int, limit_members: int) -> dict:
    report = {"members": [], "summary": {}}
    totals: dict[str, int] = {}
    for path in sorted(directory.glob("*_*.GAG.json")):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            report["members"].append({"member": path.name, "error": str(error)})
            continue
        if not isinstance(records, dict):
            continue
        counts: dict[str, int] = {}
        samples: dict[str, list] = {}
        for identity, text in records.items():
            if not isinstance(text, str):
                continue
            kind = classify(text)
            counts[kind] = counts.get(kind, 0) + 1
            totals[kind] = totals.get(kind, 0) + 1
            bucket = samples.setdefault(kind, [])
            if len(bucket) < per_member:
                bucket.append([identity, text[:220]])
        report["members"].append({
            "member": path.name, "records": len(records), "kinds": counts, "samples": samples,
        })
    report["summary"] = totals
    return report


MAX_SUMMARY = 55_000  # GitHub rejects a check summary over ~65 KB


def markdown(report: dict, budget: int = MAX_SUMMARY) -> str:
    lines = ["## Record shape by member", ""]
    lines.append("| member | records | formula | odx | request-like | hex | label+unit | part no | text |")
    lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for member in report["members"]:
        if "error" in member:
            lines.append(f"| `{member['member']}` | error | | | | | | | |")
            continue
        k = member["kinds"]
        lines.append(
            f"| `{member['member']}` | {member['records']} | {k.get('formula',0)} | "
            f"{k.get('odx_typedef',0)} | {k.get('request_like',0)} | {k.get('hex_bytes',0)} | "
            f"{k.get('label_unit',0)} | {k.get('part_number',0)} | {k.get('text',0)} |")
    lines.append("")
    for member in report["members"]:
        if "error" in member:
            continue
        interesting = {kind: rows for kind, rows in member["samples"].items()
                       if kind in ("formula", "odx_typedef", "request_like", "label_unit")}
        if not interesting:
            continue
        lines.append(f"### `{member['member']}`")
        lines.append("")
        for kind, rows in interesting.items():
            for identity, text in rows:
                lines.append(f"- **{kind}** `{identity}` → {text[:150]}")
        lines.append("")
        if sum(len(line) + 1 for line in lines) > budget:
            lines.append("_Output truncated to fit the check-summary limit._")
            break
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--per-member", type=int, default=3)
    args = parser.parse_args(argv)
    report = inspect(args.directory, args.per_member, 0)
    text = markdown(report)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        (args.output.with_suffix(".json")).write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(text, end="")
    return 0 if report["members"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
