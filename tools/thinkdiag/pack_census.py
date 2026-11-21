#!/usr/bin/env python3
"""Bounded, observational census of generated Toyota packs and recovered source rows.

Every container document is read by vehicle_bundle.read_object; source bytes are
parsed only by the checked-in Toyota readers. Reports contain bounded raw-row
samples, not recovered corpus files.
"""
import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from vehicle_bundle import read_object
from toyota_structure import ECU_FILE, section_directory, typed_table
from toyota_entries import EntryIndex

CATEGORIES = ("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")


def _entry_layout_census(entries):
    by_code = defaultdict(list)
    for entry in entries:
        code = str(entry.get("transportCode", "unknown"))
        by_code[code].append(entry)
    result = {}
    for code, rows in sorted(by_code.items(), key=lambda pair: pair[0]):
        field_counts = defaultdict(Counter)
        layouts = defaultdict(list)
        for row in rows:
            values = {"descriptor": row.get("descriptor"), "flags": row.get("flags"), "baudRaw": row.get("baud"),
                      "addresses": "opaque runtime107xx source fields; no address interpretation asserted",
                      "session": "not decoded by SYSTEMENTER reader; native session semantics remain unmapped",
                      "timing": "raw timing source fields only; timing semantics remain unmapped",
                      **{f"sourceFields.{k}": v for k, v in row.get("sourceFields", {}).items()},
                      "initBindings": row.get("initBindings", []), "keepaliveBindings": row.get("keepaliveBindings", []),
                      "exitBindings": row.get("exitBindings", [])}
            for field, value in values.items():
                field_counts[field][json.dumps(value, sort_keys=True, separators=(",", ":"))] += 1
            signature = json.dumps(values, sort_keys=True, separators=(",", ":"))
            layouts[signature].append(row)
        result[code] = {
            "entryCount": len(rows),
            "fields": {field: dict(sorted(values.items())) for field, values in sorted(field_counts.items())},
            "distinctLayouts": len(layouts),
            "layouts": [{"count": len(group), "fields": json.loads(signature),
                         "rawExamples": [r.get("rawHex", "") for r in group[:3]],
                         "entryExamples": [r.get("id", "") for r in group[:3]]}
                        for signature, group in sorted(layouts.items())],
        }
    return result


def census(pack_dir: Path, decoded: Path | None = None) -> dict:
    index = json.loads((pack_dir / "index.json").read_text())
    container = pack_dir / index["container"]
    objects = {}
    systems = []

    def visit(ref):
        key = ref["sha256"]
        if key in objects:
            return objects[key]
        obj = read_object(container, ref)
        objects[key] = obj
        if obj.get("kind") == "vehicle-system":
            systems.append(obj)
        def walk(value):
            if isinstance(value, dict):
                if set(value) == {"offset", "bytes", "jsonBytes", "sha256"}:
                    visit(value)
                else:
                    for child in value.values(): walk(child)
            elif isinstance(value, list):
                for child in value: walk(child)
        walk(obj)
        return obj

    for key in ("root", "catalogRoot", "sourceInventory"):
        if index.get(key): visit(index[key])
    kinds = Counter(obj.get("kind", "<missing>") for obj in objects.values())
    entry_transport = {}
    if decoded is not None and (decoded / "SYSTEMENTER.BIN").is_file():
        for listing in EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes()).lists.values():
            for entry in listing.entries: entry_transport[entry.source_id] = entry.transport
    elif (pack_dir / "entry-index.json").is_file():
        raw_index = json.loads((pack_dir / "entry-index.json").read_text())
        for listing in raw_index.get("lists", []):
            for entry in listing.get("entries", []): entry_transport[entry["id"]] = entry.get("transportCode", "unknown")
    system_classes, class_enabled, reason_counts = Counter(), Counter(), Counter()
    examples = defaultdict(list)
    for system in systems:
        source = next((x for x in system.get("sourceRecords", []) if x.startswith("SYSLIST_")), "")
        region = source.split("_", 1)[1].split(".", 1)[0] if source else "unknown"
        native = next((entry_transport[x] for x in system.get("sourceRecords", []) if x in entry_transport), "unknown")
        signature = (region, system.get("transportLabel", "unknown"), str(native), str(system.get("entrySelector", "unknown")))
        system_classes[signature] += 1
        class_enabled[signature] += int(system.get("enabled") is True)
        for reason in system.get("reasons", []):
            reason_counts[reason] += 1
            if len(examples[reason]) < 3: examples[reason].append(system.get("id"))
    hierarchy = Counter(obj.get("level") for obj in objects.values() if obj.get("kind") == "catalog-level")
    cumulative = 0
    class_rows = []
    for signature, count in system_classes.most_common():
        cumulative += count
        class_rows.append({"region": signature[0], "transportLabel": signature[1], "nativeTransportCode": signature[2],
            "selector": signature[3], "systems": count, "enabled": class_enabled[signature],
            "cumulativeSystems": cumulative, "cumulativeCorpusPercent": round(100 * cumulative / max(1, len(systems)), 4)})
    fun = defaultdict(lambda: {"functionRecords": 0, "declaredLiveRows": 0, "nonemptyColumns": [0, 0, 0, 0, 0]})
    for obj in objects.values():
        if obj.get("kind") != "function-source": continue
        source = obj.get("sourceRecord", "")
        region = source.split("_", 1)[1].split(".", 1)[0] if source.startswith("FUNCDATA_") else "unknown"
        rec = fun[region]; rec["functionRecords"] += 1; rec["declaredLiveRows"] += int(obj.get("declaredLiveRows", 0))
        for i, pointer in enumerate(obj.get("sourceFields", [])[1:6]): rec["nonemptyColumns"][i] += int(pointer not in (0, 0xFFFFFFFF))
    report = {"index": {"keys": sorted(index), "container": index.get("container"), "containerBytes": index.get("containerBytes"), "objects": len(objects)},
        "objectKinds": dict(sorted(kinds.items())),
        "treeCounts": {"makes": hierarchy.get("make", 0), "models": hierarchy.get("model", 0), "generations": hierarchy.get("generation", 0),
                       "variants": kinds.get("converted-vehicle", 0), "systems": len(systems),
                       "enabled": sum(s.get("enabled") is True for s in systems), "disabled": sum(s.get("enabled") is False for s in systems)},
        "systemClasses": class_rows, "funData": {k: dict(v) for k, v in sorted(fun.items())},
        "reasonCounts": dict(reason_counts.most_common()), "reasonExamples": dict(examples),
        "categorySystems": {cat: sum(bool(s.get("counts", {}).get(cat, {}).get("converted", 0)) for s in systems) for cat in CATEGORIES}}

    entries = []
    if decoded is not None and (decoded / "SYSTEMENTER.BIN").is_file():
        ei = EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes())
        entries = [e.report() for listing in ei.lists.values() for e in listing.entries]
    elif (pack_dir / "entry-index.json").is_file():
        raw_index = json.loads((pack_dir / "entry-index.json").read_text())
        entries = [e for listing in raw_index.get("lists", []) for e in listing.get("entries", [])]

    tables = defaultdict(lambda: {"sections": 0, "files": set(), "hashes": set(), "rows": [], "rowCount": 0})
    pairs = defaultdict(lambda: {"tableInstances": 0, "rowCount": 0, "hashes": set(), "rawRows": []})
    tag0_first, tag0_last = Counter(), Counter()
    tag0_tables = set()
    decoded_files = []
    if decoded is not None and decoded.is_dir():
        decoded_files = [p for p in decoded.rglob("Ecu_Data_*.BIN")
                         if re.fullmatch(ECU_FILE, p.relative_to(decoded).as_posix())]
    if decoded_files:
        for path in decoded_files:
            rel = path.relative_to(decoded).as_posix()
            raw_file = path.read_bytes(); region = rel.split("/", 1)[0]
            for section in section_directory(raw_file):
                raw_table = raw_file[section.offset:section.end]; table = typed_table(raw_table)
                bucket = _row_bucket(table.count); stride = table.stride
                key = (region, section.identity, table.identity, stride, bucket)
                row = tables[key]; row["sections"] += 1; row["files"].add(rel); row["hashes"].add(hashlib.sha256(raw_table).hexdigest())
                row["rowCount"] += table.count
                pair = pairs[(table.identity, stride)]
                pair["tableInstances"] += 1; pair["rowCount"] += table.count
                pair["hashes"].add(hashlib.sha256(raw_table).hexdigest())
                if stride:
                    for i in range(table.count):
                        raw_hex = table.body[i * stride:(i + 1) * stride].hex()
                        if raw_hex not in row["rows"] and len(row["rows"]) < 2: row["rows"].append(raw_hex)
                        if raw_hex not in pair["rawRows"] and len(pair["rawRows"]) < 2: pair["rawRows"].append(raw_hex)
                        if table.identity == 0 and stride == 12:
                            tag0_tables.add(hashlib.sha256(raw_table).hexdigest())
                            tag0_first[raw_hex[:8]] += 1; tag0_last[raw_hex[-8:]] += 1
    else:
        # Cache-hit packs retain exact ECU row bytes in their source-inventory objects.
        for obj in objects.values():
            if obj.get("kind") != "ecu-source": continue
            source_file = str(obj.get("sourceFile", "unknown")); region = source_file.split("/", 1)[0]
            for sec in obj.get("sections", []):
                slot, tag, stride = sec.get("slot"), sec.get("tag"), sec.get("stride")
                bucket = _row_bucket(sec.get("rows", 0)); key = (region, slot, tag, stride, bucket)
                row = tables[key]; row["sections"] += 1; row["files"].add(source_file); row["rowCount"] += int(sec.get("rows", 0))
                pair = pairs[(tag, stride)]; pair["tableInstances"] += 1; pair["rowCount"] += int(sec.get("rows", 0))
            for ref in obj.get("pages", []):
                page = objects.get(ref.get("sha256")) or visit(ref)
                for item in page.get("items", []):
                    raw_hex, tag = item.get("rawHex", ""), item.get("tableTag")
                    ident = item.get("id", "")
                    match = re.search(r"#slot(\d+):row\d+$", ident)
                    if tag is None or not match: continue
                    slot, stride = int(match.group(1)), len(raw_hex) // 2
                    for key, row in tables.items():
                        if key[0] == region and key[1] == slot and key[2] == tag and key[3] == stride:
                            if raw_hex not in row["rows"] and len(row["rows"]) < 2: row["rows"].append(raw_hex)
                    pair = pairs[(tag, stride)]
                    if raw_hex not in pair["rawRows"] and len(pair["rawRows"]) < 2: pair["rawRows"].append(raw_hex)
                    if tag == 0 and stride == 12:
                        tag0_first[raw_hex[:8]] += 1; tag0_last[raw_hex[-8:]] += 1
        for key, row in tables.items():
            pair = pairs[(key[2], key[3])]
            # Exact byte-string uniqueness is not recoverable from cached row metadata.
            row["hashes"] = set()
    schemas = [{"region": k[0], "slot": k[1], "tableTag": k[2], "stride": k[3], "rowBucket": k[4],
                "sections": v["sections"], "tableInstances": v["sections"], "ecuFiles": len(v["files"]),
                "uniqueTables": len(v["hashes"]) if v["hashes"] else None, "rowCount": v["rowCount"],
                "rawRows": v["rows"][:2]} for k, v in tables.items()]
    pair_rows = [{"tableTag": k[0], "stride": k[1], "tableInstances": v["tableInstances"], "rowCount": v["rowCount"],
                  "uniqueTables": len(v["hashes"]) if v["hashes"] else None, "rawRows": v["rawRows"][:2]}
                 for k, v in pairs.items()]
    total_rows = sum(p["rowCount"] for p in pair_rows)
    top_pairs = sorted(pair_rows, key=lambda p: (-p["rowCount"], p["tableTag"] if p["tableTag"] is not None else -1,
                                                p["stride"] if p["stride"] is not None else -1))
    top10_rows = sum(p["rowCount"] for p in top_pairs[:10])
    ecu_stats = {"distinctTagStridePairs": len(pair_rows), "totalRows": total_rows,
                 "top10Rows": top10_rows, "top10RowShare": top10_rows / total_rows if total_rows else None,
                 "topPairs": top_pairs[:15], "tag0Stride12": {"rowCount": sum(tag0_first.values()),
                 "tableInstances": sum(p["tableInstances"] for p in pair_rows if p["tableTag"] == 0 and p["stride"] == 12),
                 "uniqueTables": len(tag0_tables) if tag0_tables else None,
                 "first4Bytes": dict(tag0_first.most_common()), "last4Bytes": dict(tag0_last.most_common())}}
    report["sourceCensus"] = {
        "systementer": _entry_layout_census(entries),
        "ecuTables": sorted(schemas, key=lambda s: (-s["sections"], s["region"], s["slot"], s["tableTag"], str(s["stride"]), s["rowBucket"])),
        "ecuTableStats": ecu_stats,
        "tableTagSymbolEvidence": {"status": "partial static evidence only; generic typed-table readers do not identify a unique semantic consumer per table tag",
            "knownGenericReaders": ["GetToyotaTblData@0x103F8", "GetEcuTblData@0x105C0"],
            "perTagConsumers": "NOT ESTABLISHED by current symbol inspection"}}
    return report


def _row_bucket(count):
    return "0" if not count else "1-4" if count < 5 else "5-15" if count < 16 else "16-63" if count < 64 else "64+"


def markdown(data):
    lines = ["# Toyota pack census", "", f"Container: {data['index'].get('containerBytes')} bytes; {data['index'].get('objects')} unique reachable objects.", "",
        "## Tree", "", "| Makes | Models | Generations | Variants | Systems | Enabled | Disabled |", "|---:|---:|---:|---:|---:|---:|---:|"]
    t = data["treeCounts"]
    lines.append("| " + " | ".join(str(t[k]) for k in ("makes", "models", "generations", "variants", "systems", "enabled", "disabled")) + " |")
    lines += ["", "## System classes (largest first)", "", "| Region | transportLabel | Native transport | Selector | Systems | Enabled | Cumulative systems | Cumulative corpus % |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for c in data["systemClasses"]:
        lines.append(f"| {c['region']} | {c['transportLabel']} | {c['nativeTransportCode']} | {c['selector']} | {c['systems']} | {c['enabled']} | {c['cumulativeSystems']} | {c['cumulativeCorpusPercent']}% |")
    lines += ["", "## Disabled reasons", ""]
    for reason, count in data["reasonCounts"].items(): lines.append(f"- `{reason}`: {count:,}; examples `{', '.join(data['reasonExamples'].get(reason, []))}`")
    lines += ["", "## FUNCDATA function blocks by region", ""]
    for region, value in data["funData"].items(): lines.append(f"- {region}: {value['functionRecords']} indexed function records; {value['declaredLiveRows']} declared live rows; nonempty columns 1-5: {value['nonemptyColumns']}")
    src = data["sourceCensus"]
    lines += ["", "## SYSTEMENTER layouts by native transport code", ""]
    for code, census_row in src["systementer"].items():
        lines += [f"### Native transport code {code}", "", f"Entries: {census_row['entryCount']}; distinct exact field-value layouts: {census_row['distinctLayouts']}", "", "#### Distinct field values and counts", ""]
        for field, values in census_row["fields"].items():
            lines.append(f"- `{field}` distinct values/counts: " + ", ".join(f"`{v}` × {n}" for v, n in values.items()))
        for number, layout in enumerate(census_row["layouts"], 1):
            lines += ["", f"#### Layout {number} ({layout['count']} entries)", "", "Fields:", "", "```json", json.dumps(layout["fields"], indent=2, sort_keys=True), "```", "Raw hex examples:", ""]
            lines += [f"- `{value}` (`{layout['entryExamples'][i]}`)" for i, value in enumerate(layout["rawExamples"])]
    stats = src["ecuTableStats"]
    lines += ["", "## Ecu_Data typed tables", "",
        f"Distinct (tableTag, stride) pairs: {stats['distinctTagStridePairs']}; total source rows: {stats['totalRows']}; "
        f"top ten pairs: {stats['top10Rows']} rows ({100 * stats['top10RowShare']:.4f}% of all rows)" if stats["top10RowShare"] is not None else "No typed-table rows were present in the inspected source.", "",
        "Top 60 region/slot/tag/stride/row-bucket groups by table instances. A table instance is one section in one ECU file; exact distinct table byte strings are reported separately when source bytes are available.", "",
        "| Region | Slot | tableTag | Stride | Row-count bucket | ECU files | Table instances | Distinct table byte strings | Rows | Two raw rows |",
        "|---|---:|---:|---:|---|---:|---:|---:|---:|---|"]
    for schema in src["ecuTables"][:60]:
        raw_rows = "<br>".join(f"`{row}`" for row in schema["rawRows"][:2])
        unique = schema["uniqueTables"] if schema["uniqueTables"] is not None else "NOT AVAILABLE from cached row metadata"
        lines.append(f"| {schema['region']} | {schema['slot']} | {schema['tableTag']} | {schema['stride']} | {schema['rowBucket']} | {schema['ecuFiles']} | {schema['tableInstances']} | {unique} | {schema['rowCount']} | {raw_rows} |")
    lines += ["", "### Top (tableTag, stride) pairs and two raw rows", "",
        "| tableTag | Stride | Table instances | Rows | Distinct table byte strings | Two raw rows |", "|---:|---:|---:|---:|---:|---|"]
    for pair in stats["topPairs"]:
        unique = pair["uniqueTables"] if pair["uniqueTables"] is not None else "NOT AVAILABLE from cached row metadata"
        raw_rows = "<br>".join(f"`{row}`" for row in pair["rawRows"][:2])
        lines.append(f"| {pair['tableTag']} | {pair['stride']} | {pair['tableInstances']} | {pair['rowCount']} | {unique} | {raw_rows} |")
    tag0 = stats["tag0Stride12"]
    lines += ["", "### 12-byte tableTag 0 row byte distributions", "",
        f"Rows: {tag0['rowCount']}; table instances: {tag0['tableInstances']}; distinct full tables: {tag0['uniqueTables'] if tag0['uniqueTables'] is not None else 'NOT AVAILABLE from cached row metadata'}", "",
        "First four bytes (hex) and counts: " + (", ".join(f"`{value}` × {count}" for value, count in tag0["first4Bytes"].items()) or "none"), "",
        "Last four bytes (hex) and counts: " + (", ".join(f"`{value}` × {count}" for value, count in tag0["last4Bytes"].items()) or "none"), ""]
    lines += ["## Native symbol-to-table-tag evidence", "", src["tableTagSymbolEvidence"]["status"], "",
        "Known generic readers: " + ", ".join(src["tableTagSymbolEvidence"]["knownGenericReaders"]), "",
        "Per-tag consumers: " + src["tableTagSymbolEvidence"]["perTagConsumers"] + ". Static symbol names do not identify a per-tag semantic consumer.", ""]
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("packs", type=Path)
    p.add_argument("--decoded", type=Path)
    p.add_argument("--json", type=Path, required=True)
    p.add_argument("--markdown", type=Path, required=True)
    args = p.parse_args()
    data = census(args.packs, args.decoded)
    args.json.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
    text = markdown(data)
    args.markdown.write_text(text + "\n")

if __name__ == "__main__": main()
