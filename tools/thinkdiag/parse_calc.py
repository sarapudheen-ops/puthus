#!/usr/bin/env python3
"""Parse the recovered ODX-style calculation records into structured scaling rules.

A recovered record looks like::

    Linear, , , 0, 1, 10, ; A_UINT32, StandardLengthType, , , Undefined, , 0, 0, 16, ; A_FLOAT64, DEC, 1; , ; ; 0x55000003
    ScaleLinear, 0 255, , 0, 20, 1, ; A_UINT32, ..., 8, ; A_UINT32, DEC, 0; , ; ; 0x55000004

The first segment carries the computation (kind, limits, offset, factor, divisor), the second the
source type and bit length, the third the output type and decimals. This module turns those into
numbers. It does **not** decide which ECU or request a rule belongs to: that join is not recovered,
so nothing here becomes a runnable PID.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

KINDS = ("ScaleLinear", "Linear", "Identical", "TextTable", "TabIntp", "Rational", "ScaleRational")
TYPE = re.compile(r"A_[A-Z0-9_]+")
INT = re.compile(r"-?\d+")


def segments(text: str) -> list[list[str]]:
    """Split on ';' then ',' — the record is a small, fixed grammar."""
    parts = [part.strip() for part in text.split(";")]
    return [[field.strip() for field in part.split(",")] for part in parts if part.strip()]


def numbers(fields: list[str], stop_at_type: bool = True) -> list[int]:
    values = []
    for field in fields:
        if stop_at_type and TYPE.search(field):
            break
        match = INT.fullmatch(field.strip())
        if match:
            values.append(int(match.group(0)))
    return values


def parse(text: str) -> dict | None:
    """Return a structured rule, or None when the record is not a calc definition."""
    if not any(text.startswith(kind) for kind in KINDS):
        return None
    parts = segments(text)
    if not parts:
        return None
    if len(parts) == 1 and len([f for f in parts[0] if f]) < 2:
        return None
    head = parts[0]
    kind = next((k for k in KINDS if head and head[0].startswith(k)), None)
    if kind is None:
        return None

    # Computation numbers: offsets/factors/divisors after the kind word.
    values = numbers(head[1:])
    limits = None
    if len(head) > 1 and re.fullmatch(r"-?\d+\s+-?\d+", head[1] or ""):
        low, high = (int(v) for v in head[1].split())
        limits = (low, high)
    offset = values[0] if len(values) > 0 else None
    factor = values[1] if len(values) > 1 else None
    divisor = values[2] if len(values) > 2 else None

    source = parts[1] if len(parts) > 1 else []
    source_type = next((t for t in TYPE.findall(" ".join(source))), None)
    source_ints = numbers(source, stop_at_type=False)
    bits = None
    for value in reversed(source_ints):
        if value in (8, 16, 32, 64):
            bits = value
            break

    out = parts[2] if len(parts) > 2 else []
    if len(parts) == 1:
        return {"kind": kind, "offset": offset, "factor": factor, "divisor": divisor,
                "limits": limits, "sourceType": None, "sourceBits": None,
                "outputType": None, "decimals": None, "tableId": None}
    output_type = next((t for t in TYPE.findall(" ".join(out))), None)
    decimals = None
    for field in out:
        match = re.fullmatch(r"\s*(\d+)\s*", field)
        if match:
            decimals = int(match.group(1))
    table_id = next((f for f in parts[-1] if f.lower().startswith("0x")), None)

    return {
        "kind": kind, "offset": offset, "factor": factor, "divisor": divisor,
        "limits": limits, "sourceType": source_type, "sourceBits": bits,
        "outputType": output_type, "decimals": decimals, "tableId": table_id,
    }


def scale_factor(rule: dict) -> float | None:
    """The multiplier a plain `y = raw * k + offset` reading would use."""
    factor = rule.get("factor")
    divisor = rule.get("divisor")
    if factor is None:
        return None
    if divisor in (None, 0):
        return float(factor)
    return float(factor) / float(divisor)


def scan(directory: pathlib.Path) -> dict:
    members = []
    totals = {"records": 0, "parsed": 0, "withScale": 0, "kinds": {}}
    for path in sorted(directory.glob("*_CALC.GAG.json")) + sorted(directory.glob("*VER_CALC.GAG.json")):
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            members.append({"member": path.name, "error": str(error)})
            continue
        if not isinstance(records, dict):
            continue
        parsed = 0
        scaled = 0
        kinds: dict[str, int] = {}
        examples = []
        for identity, text in records.items():
            if not isinstance(text, str):
                continue
            rule = parse(text)
            if rule is None:
                continue
            parsed += 1
            kinds[rule["kind"]] = kinds.get(rule["kind"], 0) + 1
            totals["kinds"][rule["kind"]] = totals["kinds"].get(rule["kind"], 0) + 1
            if scale_factor(rule) is not None:
                scaled += 1
            if len(examples) < 4:
                examples.append({"id": identity, "text": text[:200], "rule": rule,
                                 "scale": scale_factor(rule)})
        totals["records"] += len(records)
        totals["parsed"] += parsed
        totals["withScale"] += scaled
        members.append({"member": path.name, "records": len(records), "parsed": parsed,
                        "withScale": scaled, "kinds": kinds, "examples": examples})
    return {"members": members, "totals": totals}


def markdown(report: dict) -> str:
    totals = report["totals"]
    lines = ["## Calc records parsed", "",
             f"{totals['parsed']:,} of {totals['records']:,} records parsed; "
             f"{totals['withScale']:,} carry a usable scale factor.", "",
             "| member | records | parsed | with scale | kinds |",
             "| --- | ---: | ---: | ---: | --- |"]
    for member in report["members"]:
        if "error" in member:
            lines.append(f"| `{member['member']}` | error | | | |")
            continue
        kinds = ", ".join(f"{k} {v}" for k, v in sorted(member["kinds"].items()))
        lines.append(f"| `{member['member']}` | {member['records']} | {member['parsed']} | "
                     f"{member['withScale']} | {kinds or '-'} |")
    lines.append("")
    lines.append("### Parsed examples")
    lines.append("")
    for member in report["members"]:
        for example in member.get("examples", [])[:2]:
            rule = example["rule"]
            lines.append(
                f"- `{member['member']}` / `{example['id']}` → **{rule['kind']}** "
                f"offset={rule['offset']} factor={rule['factor']} divisor={rule['divisor']} "
                f"→ scale={example['scale']} · {rule['sourceType']} {rule['sourceBits']} bit "
                f"→ {rule['outputType']} ({rule['decimals']} dp)")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    report = scan(args.directory)
    text = markdown(report)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(text, end="")
    return 0 if report["totals"]["parsed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
