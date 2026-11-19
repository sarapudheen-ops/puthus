#!/usr/bin/env python3
"""Convert recovered brand TCODE text into app-importable `bod3-datapack` JSON.

Input is the JSON map written by `extract_brand_text.py` for a `TCODE*_EN.GAG` member.
Each record is a NUL-delimited pair: the explicit code, then its English description.
Only explicit, unambiguous code/description pairs are kept; conflicts, placeholders and
non-code records are excluded rather than guessed. Extended codes stay extended.

Nothing here creates a diagnostic request, address or formula.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys

# Same shape the app enforces for a DTC key: P/B/C/U + 4 hex, optional -NN.
CODE = re.compile(r"[PCBU][0-3][0-9A-F]{3}(?:[0-9A-F]{2})?")
DEFAULT_SEPARATOR = "\0"
PLACEHOLDERS = {
    "NORMAL", "NOFAULT", "NOFAULTS", "NODTC", "NODTCS", "NODESCRIPTION", "UNKNOWN",
    "RESERVED", "NOTAPPLICABLE", "VARIATIONCODING",
}
# The app's import limit is 16 MiB; keep well under it so a pack always imports.
MAX_PACK_BYTES = 12 * 1024 * 1024


def clean(text: str) -> str:
    """Collapse whitespace and drop control bytes that the app rejects."""
    return " ".join("".join(c for c in text if ord(c) >= 32 or c == " ").split())


def convert(records: dict[str, str], separator: str = DEFAULT_SEPARATOR) -> tuple[dict[str, str], dict]:
    candidates: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    prefixes: dict[str, set[str]] = defaultdict(set)
    skipped = Counter()
    for identity, entry in records.items():
        fields = entry.split(separator)
        if len(fields) != 2:
            skipped["not_a_code_description_pair"] += 1
            continue
        raw, description = clean(fields[0]), clean(fields[1])
        # A brand whose codes are raw hex (BMW: "0B\0Sensor…") cannot be mapped to the app's
        # SAE key shape, so those records are skipped and counted rather than guessed at.
        if not CODE.fullmatch(raw):
            skipped["unsupported_code"] += 1
            continue
        compact = re.sub(r"[^A-Z0-9]", "", description.upper())
        if not description or compact == re.sub(r"[^A-Z0-9]", "", raw) or compact in PLACEHOLDERS:
            skipped["placeholder_or_empty"] += 1
            continue
        if len(re.sub(r"[^A-Za-z]", "", description)) < 4:
            skipped["too_short"] += 1
            continue
        code = raw[:5] + "-" + raw[5:] if len(raw) == 7 else raw
        candidates[code][description].append(identity)

    accepted: dict[str, str] = {}
    sources: dict[str, list[str]] = {}
    conflicts: dict[str, dict[str, list[str]]] = {}
    for code, variants in sorted(candidates.items()):
        if len(variants) == 1:
            description, identities = next(iter(variants.items()))
            accepted[code] = description
            sources[code] = identities
        else:
            conflicts[code] = dict(variants)

    stats = {
        "sourceRecords": len(records),
        "codesWithSourcePrefix": sum(1 for code in accepted if prefixes.get(code)),
        "acceptedCodes": len(accepted),
        "acceptedRecords": sum(len(v) for v in sources.values()),
        "conflictingCodesExcluded": len(conflicts),
        "conflictingRecordsExcluded": sum(len(v) for variants in conflicts.values() for v in variants.values()),
        "baseCodes": sum("-" not in code for code in accepted),
        "extendedCodes": sum("-" in code for code in accepted),
        "skipped": dict(skipped),
    }
    return accepted, {"statistics": stats, "sources": sources, "conflicts": conflicts}


def build_pack(brand: str, makes: list[str], source_name: str, accepted: dict[str, str]) -> dict:
    return {
        "format": "bod3-datapack",
        "formatVersion": 2,
        "id": f"{brand.lower()}-dtcs",
        "name": f"{brand} DTC reference",
        "version": 1,
        "author": f"Source: ThinkDiag {brand} ({source_name}); conversion: bod3",
        "description": (
            "Recovered fault-code text from this repository's assets-v1 release. Only explicit "
            "code/English-description pairs with one unique wording are included; conflicting or "
            "placeholder records were excluded. Reference text only, not a confirmed diagnosis: "
            "confirm against the repair information for the actual vehicle and ECU. This pack "
            "contains no PIDs, ECU addresses, tests or coding recipes."
        ),
        "dtcGroups": [{
            "id": f"{brand.lower()}-english-dtcs",
            "makes": makes,
            "source": f"ThinkDiag {brand} - {source_name}",
            "dtcs": accepted,
        }],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="TCODE*_EN.GAG.json from extract_brand_text.py")
    parser.add_argument("--brand", required=True, help="Brand name, e.g. VW")
    parser.add_argument("--makes", required=True, help="Comma-separated app makes, e.g. Volkswagen,Audi")
    parser.add_argument("--separator", default="NUL",
                        help="NUL (default), or the literal separator text, e.g. '|'")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    records = json.loads(args.source.read_text(encoding="utf-8"))
    brand = args.brand.upper()
    makes = [m.strip() for m in args.makes.split(",") if m.strip()]
    separator = "\0" if args.separator == "NUL" else args.separator.encode().decode("unicode_escape")
    accepted, audit = convert(records, separator)
    pack = build_pack(brand, makes, args.source.name, accepted)
    encoded = (json.dumps(pack, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    stats = audit["statistics"]
    if len(encoded) > MAX_PACK_BYTES:
        print(f"{brand}: pack is {len(encoded)} bytes, over the {MAX_PACK_BYTES} byte budget", file=sys.stderr)
        return 1
    if not accepted:
        print(f"{brand}: no explicit code/description pairs recovered", file=sys.stderr)
        (args.output / f"{brand.lower()}-dtc-audit.json").write_text(
            json.dumps(audit, indent=2) + "\n", encoding="utf-8",
            errors="replace") if args.output.is_dir() else None
        return 1
    args.output.write_bytes(encoded)
    audit_output = args.output.with_name(f"{brand.lower()}-dtc-audit.json")
    audit_output.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8", errors="replace")
    print(f"{brand}: {stats['acceptedCodes']} codes ({stats['baseCodes']} base, "
          f"{stats['extendedCodes']} extended), {stats['conflictingCodesExcluded']} conflicts excluded, "
          f"{len(encoded)} bytes -> {args.output.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
