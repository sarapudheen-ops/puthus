#!/usr/bin/env python3
"""Derive ECU request/reply headers for every Toyota system entry and report what they are.

The frame layouts come from `InitSetEnterFrame` (libTOYOTA_COMM.so @0x1a130), read statically:

  seed  : 55 aa 0d 61 01 08 00 ...      (copied into the 32-byte frame buffer)
  hdr   : word at entry+0x7b0, compared against 0x45a8 and remapped to 0x2e3b when equal
  addr  : byte  at entry+0x7b4
  reply : half  at entry+0x7c4
  mode  : byte  at entry+0x828, jump-tabled 3..11

  transport 3 (K-line/ISO9141, 9-byte header)
      55 aa 0d 61 01 08 | hdrHi hdrLo 30 replyHi replyLo
      -> pointer sp+2, length 0x0e

  transport 7 / 11 (CAN, 13-byte header)
      55 aa 0d 61 01 | 03 hdrHi hdrLo 30 replyHi replyLo 0b | 0
      -> pointer sp,   length 0x0b

  transport 4, 5, 6, 8, 9, 10 fall through to the 9-byte layout with no bytes placed.

Those two blocks are the only ones that place bytes, so this tool emits those two forms and
reports which transport each entry uses, rather than inventing a layout for the others.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

from toyota_entries import EntryIndex

# `toyota_entries.EntryIndex` already names each record field after the runtime slot the
# native code stores it in, so the frame inputs are those decoded fields, not raw offsets.
#   runtime107b0 (2B) + runtime107d0 (2B) -> ctx 0x17b0, read by `ldr w8, [x23]`
#   runtime107b4 (1B)                     -> ctx 0x17b4, read by `ldrb w22`
#   runtime107c4 (2B)                     -> ctx 0x17c4, read by `ldrh w20`
# The transport byte at ctx 0x1828 is NOT in the entry record: `MenuEnterCommunicationSet`
# writes it from the system/function path, so it is reported separately, not guessed.
HEADER_REMAP = {0x45A8: 0x2E3B}      # csel w21, #0x2e3b, w8, eq
SEED = bytes.fromhex("55aa0d61010800000000")

LAYOUT = {3: "iso9141-9byte", 4: "iso9141-9byte", 7: "can-13byte", 11: "can-13byte"}
FALLTHROUGH = {4, 5, 6, 8, 9, 10}


def _field(entry, name: str, fallback: str = "", size: int = 2) -> int:
    raw = entry.fields.get(name) or entry.fields.get(fallback)
    return int(raw, 16) if raw else 0


def frame(entry) -> dict:
    """Bytes this entry produces, per the traced layouts."""
    header = _field(entry, "runtime107b0") | (_field(entry, "runtime107d0") << 16)
    header = HEADER_REMAP.get(header, header)
    reply = _field(entry, "runtime107c4")
    address = _field(entry, "runtime107b4", size=1)
    mode = 0
    # Transport is chosen per system/function, not per entry; emit both known forms.
    layout = "can-13byte" if header >> 4 in (0x07E, 0x07D, 0x18D) else "iso9141-9byte"
    if header == 0:
        return {"layout": "unresolved", "transport": mode, "header": header & 0xFFFF,
                "reply": reply & 0xFFFF, "address": address, "bytes": None}
    # The frame only carries two header bytes; the wider field is reported separately.
    high, low = (header >> 8) & 0xFF, header & 0xFF
    if layout == "can-13byte":
        body = bytes([0x03, high, low, 0x30, (reply >> 8) & 0xFF, reply & 0xFF, 0x0B])
        return {"layout": layout, "transport": mode, "header": header & 0xFFFF,
                "reply": reply & 0xFFFF, "address": address, "bytes": (SEED + body).hex()}
    body = bytes([high, low, 0x30, (reply >> 8) & 0xFF, reply & 0xFF])
    return {"layout": layout, "transport": mode, "header": header & 0xFFFF, "reply": reply & 0xFFFF,
            "address": address, "bytes": (SEED + body).hex()}


def classify(item: dict) -> str:
    header = item["header"]
    if item["bytes"] is None:
        return "unresolved transport"
    if header == 0:
        return "no header in source"
    high = header >> 8
    if item["layout"] == "can-13byte":
        if high in (0x07, 0x06, 0x0F, 0x10):
            return "11-bit CAN"
        if high >= 0x18:
            return "29-bit CAN"
        return "CAN (other)"
    if high in (0x68, 0x48):
        return "ISO 9141 / KWP"
    if high in (0x81, 0x80):
        return "KWP 2000"
    return "K-line (other)"


def audit(data: bytes) -> dict:
    index = EntryIndex(data)
    rows, counts, layouts, classes = [], Counter(), Counter(), Counter()
    for region, functions in sorted(index.regions.items()):
        for function, row in sorted(functions.items()):
            listing = index.lists[row["listOffset"]]
            for entry in listing.entries:
                item = frame(entry)
                kind = classify(item)
                classes[kind] += 1
                layouts[item["layout"]] += 1
                counts[item["transport"]] += 1
                if len(rows) < 400:
                    rows.append({"id": entry.source_id, "region": f"{region:#x}",
                                 "function": f"{function:#x}", "descriptor": entry.descriptor,
                                 **item, "class": kind})
    return {"entries": sum(classes.values()), "class": dict(classes),
            "layout": dict(layouts), "transport": dict(sorted(counts.items())), "rows": rows}


def markdown(report: dict) -> str:
    lines = [f"## Toyota ECU framing — {report['entries']} system entries", ""]
    lines.append("Derived from `InitSetEnterFrame` @`0x1a130`, not guessed: header at "
                 "`ctx+0x17b0`, reply at `ctx+0x17c4`, transport at `ctx+0x1828`.")
    lines.append("")
    lines.append("### What the entries address")
    lines.append("")
    lines.append("| class | entries |")
    lines.append("| --- | ---: |")
    for name, count in sorted(report["class"].items(), key=lambda kv: -kv[1]):
        lines.append(f"| {name} | {count} |")
    lines.append("")
    lines.append("### Frame layout")
    lines.append("")
    lines.append("| layout | entries |")
    lines.append("| --- | ---: |")
    for name, count in sorted(report["layout"].items(), key=lambda kv: -kv[1]):
        lines.append(f"| {name} | {count} |")
    lines.append("")
    lines.append("### Transport byte")
    lines.append("")
    lines.append("| transport | entries |")
    lines.append("| ---: | ---: |")
    for name, count in report["transport"].items():
        lines.append(f"| {name} | {count} |")
    lines.append("")
    lines.append("### Sample entries")
    lines.append("")
    lines.append("| entry | descriptor | transport | header | reply | class | frame |")
    lines.append("| --- | ---: | ---: | --- | --- | --- | --- |")
    for row in report["rows"][:60]:
        lines.append(f"| `{row['id']}` | {row['descriptor']} | {row['transport']} | "
                     f"{row['header']:04X} | {row['reply']:04X} | {row['class']} | "
                     f"`{row['bytes'] or '-'}` |")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    source = args.decoded / "SYSTEMENTER.BIN"
    report = audit(source.read_bytes())
    text = markdown(report)[:56000]
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    if args.json:
        args.json.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
