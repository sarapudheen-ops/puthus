#!/usr/bin/env python3
"""Convert only explicit, unambiguous TCODE_EN records into a passive bod3 pack.

No numeric text IDs are treated as vehicle requests. Conflicting descriptions,
legacy blink codes, placeholder descriptions and unsupported code forms are
excluded, with an audit report. Extended codes are kept as Pxxxx-yy, never
silently promoted to a base OBD code.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
import hashlib
import json
from pathlib import Path
import re

from analyze_toyota import TOYOTA_SHA256

CODE = re.compile(r"[PCBU][0-3][0-9A-F]{3}(?:[0-9A-F]{2})?")
# The raw record contains byte 00. Some check/log renderers display it as \\^@;
# that is not the file format. Do not split rendered examples or infer codes.
SEPARATOR = "\0"
PLACEHOLDERS = {"NORMAL", "NOFAULT", "NOFAULTS", "NODTC", "NODTCS", "NODESCRIPTION", "UNKNOWN", "RESERVED", "NOTAPPLICABLE"}


def convert(records: dict[str, str]):
    candidates = defaultdict(lambda: defaultdict(list))
    skipped = Counter()
    for identity, entry in records.items():
        fields = entry.split(SEPARATOR)
        if len(fields) != 2:
            skipped["not_a_code_description_pair"] += 1
            continue
        raw, description = (" ".join(field.split()) for field in fields)
        if not CODE.fullmatch(raw):
            skipped["unsupported_code_or_blink_code"] += 1
            continue
        compact = re.sub(r"[^A-Z0-9]", "", description.upper())
        if (compact == raw or compact in PLACEHOLDERS or "MANUFACTURER CONTROL" in description.upper()
                or len(re.sub(r"[^A-Za-z]", "", description)) < 4):
            skipped["placeholder_or_empty"] += 1
            continue
        if any(ord(c) < 32 for c in description):
            skipped["control_characters"] += 1
            continue
        code = raw[:5] + "-" + raw[5:] if len(raw) == 7 else raw
        candidates[code][description].append(identity)
    accepted, sources, conflicts = {}, {}, {}
    for code, variants in sorted(candidates.items()):
        # Preserve exact wording. Even plausible alternative meanings need an ECU
        # context we do not yet have, so do not select one by frequency or ID.
        if len(variants) == 1:
            description, identities = next(iter(variants.items()))
            accepted[code] = description
            sources[code] = identities
        else:
            conflicts[code] = dict(variants)
    pack = {
        "format": "bod3-datapack", "formatVersion": 2,
        "id": "toyota-lexus-dtcs", "name": "Toyota / Lexus DTC reference (V10.62)", "version": 1,
        "author": "Source: ThinkDiag Toyota/Lexus V10.62; conversion: bod3",
        "description": (
            "Recovered from TOYOTA.zip in this repository's assets-v1 release. "
            "Only explicit code/English-description pairs with one unique wording are included. "
            "Conflicts, placeholders and blink codes were excluded. Reference text only: confirm against the repair manual for your ECU/model. "
            "No PIDs, ECU addresses, actuator tests or coding recipes are enabled by this pack."
        ),
        "dtcGroups": [{"id": "toyota-lexus-english-dtcs", "makes": ["Toyota", "Lexus", "Toyota Thailand"],
                       "source": "ThinkDiag Toyota/Lexus V10.62 - TCODE_EN.GAG", "dtcs": accepted}],
    }
    stats = {"sourceRecords": len(records), "acceptedCodes": len(accepted), "conflictingCodesExcluded": len(conflicts),
             "acceptedRecords": sum(map(len, sources.values())),
             "conflictingRecordsExcluded": sum(len(ids) for variants in conflicts.values() for ids in variants.values()),
             "skippedRecords": dict(skipped), "baseCodes": sum("-" not in k for k in accepted),
             "extendedCodes": sum("-" in k for k in accepted)}
    return pack, {"sourceArchiveSha256": TOYOTA_SHA256, "sourceLibrary": "TOYOTA_EN.GGP/TCODE_EN.GAG",
                  "statistics": stats, "sources": sources, "conflicts": conflicts}


def write_conversion(source: Path, output: Path, report_dir: Path):
    records = json.loads(source.read_text(encoding="utf-8"))
    pack, audit = convert(records)
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(pack, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(encoded) > 2 * 1024 * 1024:
        raise ValueError("Converted pack exceeds the app's JSON import limit")
    (output / "toyota-lexus-dtcs.json").write_bytes(encoded)
    audit["sourceTextJsonSha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
    (output / "toyota-dtc-audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    manifest = []
    dtcs = pack["dtcGroups"][0]["dtcs"]
    # Bounded, human-readable data reports also make the conversion inspectable
    # through the GitHub checks API where binary artifact downloads are blocked.
    chunk = {}
    def flush():
        if not chunk:
            return
        name = f"dtc-data-{len(manifest) + 1:03}.json"
        raw = (json.dumps(chunk, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        (report_dir / name).write_bytes(raw)
        manifest.append({"name": name, "count": len(chunk), "sha256": hashlib.sha256(raw).hexdigest()})
        chunk.clear()
    for code, text in dtcs.items():
        if len(json.dumps({**chunk, code: text}, ensure_ascii=False).encode("utf-8")) > 45000:
            flush()
        chunk[code] = text
    flush()
    metadata = copy.deepcopy(pack)
    metadata["dtcGroups"][0]["dtcs"] = {}
    report = {"sourceArchiveSha256": TOYOTA_SHA256, "statistics": audit["statistics"], "packBytes": len(encoded),
              "sourceByteExamples": [{"id": i, "hex": t[:120].encode("utf-8").hex(" ")} for i, t in list(records.items())[:3]],
              "packSha256": hashlib.sha256(encoded).hexdigest(), "packMetadata": metadata, "shards": manifest,
              "acceptedExamples": dict(list(dtcs.items())[:10]),
              "conflictExamples": dict(list(audit["conflicts"].items())[:5])}
    (report_dir / "converted-dtcs.md").write_text("# Toyota/Lexus passive DTC conversion\n\n```json\n" + json.dumps(report, indent=2) + "\n```\n")
    print(json.dumps(audit["statistics"]))
    if not dtcs:
        raise ValueError("No explicit DTC pairs were recovered; inspect sourceByteExamples in the conversion report")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    write_conversion(args.source, args.output, args.report)
