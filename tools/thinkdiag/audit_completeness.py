#!/usr/bin/env python3
"""Report every recovered member per brand with what it is and whether it is usable yet.

The point is completeness, not a headline number: for each brand, list every recovered
member, classify it, and say whether its content has been turned into something the app
can import. Nothing here converts anything.
"""
from __future__ import annotations

import argparse
import json
import pathlib

# Member name -> (what it holds, can the app import it today?)
KINDS = [
    ("TCODE_EN", "fault codes + descriptions", "yes: dtc pack"),
    ("TCODE_EX_EN", "extended fault codes", "yes: dtc pack (merged)"),
    ("TCODE_X_EN", "extra fault codes", "yes: dtc pack (merged)"),
    ("TCODEH_EN", "full workshop DTC write-ups", "no: app has no long-text field"),
    ("TCODES_EN", "fault status words", "yes: handled in conversion"),
    ("DSTREAM_EN", "live-data labels + units", "no: needs ECU/request join"),
    ("DSTREAMU_EN", "live-data units", "no: needs ECU/request join"),
    ("DSID", "data-stream identifiers", "no: needs join"),
    ("DSIN", "identifier names / part numbers", "no: needs join"),
    ("EXPRESS", "scaling expressions", "no: needs ECU/request join"),
    ("_CALC", "scaling rules (parsed)", "no: needs ECU/request join"),
    ("UDS_INDEX", "named UDS values", "no: needs DTC/request join"),
    ("DTC_INDEX", "DTC index", "no: needs join"),
    ("SYSTEM_PN", "ECU part numbers", "partial: identification display"),
    ("SYSTEM_PNID", "part-number IDs", "no: needs join"),
    ("TEXT_EN", "general UI/label text", "no UI hook"),
    ("FUNCTEXT", "function descriptions", "no: needs join"),
    ("DTCFUNCTEXT", "per-DTC function text", "no: app has no long-text field"),
    ("ACTTEXT", "actuator test text", "no: needs recipe join"),
    ("VARCODING", "variant coding text", "no: needs coding join"),
    ("COMMAND", "command pool", "no: not executable by policy"),
    ("CAN_ADD", "CAN addressing", "no: needs join"),
    ("CFG", "configuration", "no"),
    ("TEXT_JOBS", "job text", "no"),
    ("TEXT_ABLNAME", "workflow names", "no"),
    ("TEXT_SVGITEMS", "UI items", "no"),
    ("TEXT_DIAGNAMES", "diagnostic names", "no"),
    ("TEXT_INFOIDMAPS", "info ID maps", "no"),
    ("TEXT_UNITS", "units", "no"),
    ("EPCALC", "EP calculation", "no"),
    ("TYPE", "type table", "no"),
    ("PICTURE", "picture refs", "no"),
    ("RECODING", "recoding text", "no"),
    ("SA_EN", "security access text", "no: deliberately blocked"),
]


def classify(name: str) -> tuple[str, str]:
    upper = name.upper()
    for needle, what, usable in KINDS:
        if needle in upper:
            return what, usable
    return "other", "no"


def audit(directory: pathlib.Path) -> dict:
    brands: dict[str, list] = {}
    for path in sorted(directory.glob("*_*.GAG.json")):
        stem = path.name
        brand = stem.split("_")[0]
        member = stem[len(brand) + 1:]
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(records, dict):
            continue
        what, usable = classify(member)
        brands.setdefault(brand, []).append({
            "member": member, "records": len(records), "what": what, "usable": usable,
            "bytes": path.stat().st_size,
        })
    return brands


def markdown(brands: dict) -> str:
    lines = ["## Completeness audit", ""]
    for brand, members in sorted(brands.items()):
        total = sum(m["records"] for m in members)
        usable = sum(m["records"] for m in members if m["usable"].startswith("yes"))
        lines.append(f"### {brand} — {len(members)} members, {total:,} records, "
                     f"{usable:,} in an app-importable form")
        lines.append("")
        lines.append("| member | records | what it holds | in the app? |")
        lines.append("| --- | ---: | --- | --- |")
        for member in sorted(members, key=lambda m: -m["records"]):
            lines.append(f"| `{member['member']}` | {member['records']:,} | "
                         f"{member['what']} | {member['usable']} |")
        lines.append("")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    brands = audit(args.directory)
    text = markdown(brands)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
        args.output.with_suffix(".json").write_text(
            json.dumps(brands, indent=2) + "\n", encoding="utf-8")
    print(text, end="")
    return 0 if brands else 1


if __name__ == "__main__":
    raise SystemExit(main())
