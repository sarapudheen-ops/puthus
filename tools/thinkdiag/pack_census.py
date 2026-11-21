#!/usr/bin/env python3
"""Bounded, observational census of generated Toyota packs and recovered source rows.

Reads every bundle document through vehicle_bundle.read_object; source tables use
existing toyota_structure/toyota_entries readers. Raw samples are capped and no
corpus payloads are copied into the repository.
"""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from vehicle_bundle import read_object
from toyota_structure import ECU_FILE, section_directory, typed_table
from toyota_entries import EntryIndex

CATEGORIES = ("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")


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
    # Source inventory pages are referenced from the inventory root and are traversed above.
    kinds = Counter(obj.get("kind", "<missing>") for obj in objects.values())
    entry_transport = {}
    if decoded is not None and (decoded / "SYSTEMENTER.BIN").is_file():
        for listing in EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes()).lists.values():
            for entry in listing.entries:
                entry_transport[entry.source_id] = entry.transport
    system_classes = Counter()
    class_counts = Counter()
    reason_counts = Counter()
    examples = defaultdict(list)
    for s in systems:
        src = next((x for x in s.get("sourceRecords", []) if x.startswith("SYSLIST_")), "")
        region = src.split("_", 1)[1].split(".", 1)[0] if src else "unknown"
        native_transport = next((entry_transport[x] for x in s.get("sourceRecords", []) if x in entry_transport), "unknown")
        entry = s.get("entrySource") or {}
        for page in entry.get("pages", []):
            pobj = objects.get(page.get("sha256"))
            if pobj is None:
                pobj = visit(page)
            for c in pobj.get("items", []):
                if c.get("id") == entry.get("sourceRecord"):
                    native_transport = c.get("transportCode", native_transport)
        signature = (region, s.get("transportLabel", "unknown"), str(native_transport), str(s.get("entrySelector", "unknown")))
        system_classes[signature] += 1
        class_counts[signature] += int(s.get("enabled") is True)
        for reason in s.get("reasons", []):
            reason_counts[reason] += 1
            if len(examples[reason]) < 3: examples[reason].append(s.get("id"))

    hierarchy = Counter(o.get("level") for o in objects.values() if o.get("kind") == "catalog-level")
    ordered_classes = system_classes.most_common()
    cumulative = 0
    class_rows = []
    for signature, count in ordered_classes:
        cumulative += count
        class_rows.append({"region": signature[0], "transportLabel": signature[1],
                           "nativeTransportCode": signature[2], "selector": signature[3],
                           "systems": count, "enabled": class_counts[signature],
                           "cumulativeSystems": cumulative,
                           "cumulativeCorpusPercent": round(100 * cumulative / max(1, len(systems)), 4)})
    function_sources = [o for o in objects.values() if o.get("kind") == "function-source"]
    fun = defaultdict(lambda: {"functionRecords": 0, "declaredLiveRows": 0, "nonemptyColumns": [0, 0, 0, 0, 0]})
    for obj in function_sources:
        src = obj.get("sourceRecord", "")
        region = src.split("_", 1)[1].split(".", 1)[0] if src.startswith("FUNCDATA_") else "unknown"
        row = fun[region]
        row["functionRecords"] += 1
        row["declaredLiveRows"] += int(obj.get("declaredLiveRows", 0))
        fields = obj.get("sourceFields", [])
        for i, pointer in enumerate(fields[1:6]):
            row["nonemptyColumns"][i] += int(pointer not in (0, 0xFFFFFFFF))
    report = {"index": {"keys": sorted(index), "container": index.get("container"),
                        "containerBytes": index.get("containerBytes"), "objects": len(objects)}, 
              "objectKinds": dict(sorted(kinds.items())),
              "treeCounts": {"makes": hierarchy.get("make", 0), "models": hierarchy.get("model", 0),
                             "generations": hierarchy.get("generation", 0),
                             "variants": kinds.get("converted-vehicle", 0), "systems": len(systems),
                             "enabled": sum(s.get("enabled") is True for s in systems),
                             "disabled": sum(s.get("enabled") is False for s in systems)},
              "systemClasses": class_rows,
              "funData": {k: dict(v) for k, v in sorted(fun.items())},
              "reasonCounts": dict(reason_counts.most_common()),
              "reasonExamples": dict(examples),
              "categorySystems": {cat: sum(bool(s.get("counts", {}).get(cat, {}).get("converted", 0)) for s in systems)
                                   for cat in CATEGORIES}}

    if decoded is not None:
        source_counts = {"systementer": {}, "ecuTables": {}, "funData": {}}
        entry_path = decoded / "SYSTEMENTER.BIN"
        if entry_path.exists():
            ei = EntryIndex(entry_path.read_bytes())
            by_transport = defaultdict(list)
            for listing in ei.lists.values():
                for e in listing.entries:
                    row = e.report()
                    key = str(e.transport)
                    by_transport[key].append(row)
            source_counts["systementer"] = {k: {"count": len(v), "examples": v[:3]} for k, v in by_transport.items()}
        tables = defaultdict(lambda: {"count": 0, "ecuFiles": set(), "rows": []})
        for path in decoded.rglob("Ecu_Data_*.BIN"):
            rel = path.relative_to(decoded).as_posix()
            if not __import__("re").fullmatch(ECU_FILE, rel): continue
            raw_ecu = path.read_bytes()
            for section in section_directory(raw_ecu):
                table_bytes = raw_ecu[section.offset:section.end]
                table = typed_table(table_bytes)
                bucket = "0" if table.count == 0 else "1-4" if table.count < 5 else "5-15" if table.count < 16 else "16-63" if table.count < 64 else "64+"
                key = f"slot={section.identity},tag={table.identity},stride={table.stride},rows={bucket}"
                item = tables[key]; item["count"] += 1; item["ecuFiles"].add(rel)
                item.setdefault("uniqueTables", set()).add(__import__("hashlib").sha256(table_bytes).hexdigest())
                if table.stride and len(item["rows"]) < 3:
                    item["rows"].extend(table.body[i:i + table.stride].hex() for i in
                                        range(0, min(len(table.body), 3 * table.stride), table.stride))
        source_counts["ecuTables"] = {k: {"count": v["count"], "ecuFiles": len(v["ecuFiles"]),
            "uniqueTables": len(v.get("uniqueTables", set())), "rows": v["rows"][:3]} for k, v in tables.items()}
        report["sourceCensus"] = source_counts
    return report


def markdown(data):
    lines = ["# Toyota pack census", "", f"Container: {data['index'].get('containerBytes')} bytes; "
             f"{data['index'].get('objects')} unique reachable objects.", "",
             "## Tree", "", "| Makes | Models | Generations | Variants | Systems | Enabled | Disabled |",
             "|---:|---:|---:|---:|---:|---:|---:|"]
    t = data["treeCounts"]
    lines.append("| " + " | ".join(str(t[k]) for k in ("makes", "models", "generations", "variants", "systems", "enabled", "disabled")) + " |")
    lines += ["", "## System classes (largest first)", "", "| Region | transportLabel | Native transport | Selector | Systems | Enabled |",
              "|---|---|---:|---:|---:|---:|"]
    for c in data["systemClasses"]:
        lines.append(f"| {c['region']} | {c['transportLabel']} | {c['nativeTransportCode']} | {c['selector']} | {c['systems']} | {c['enabled']} |")
    lines += ["", "## Disabled reasons", ""]
    for reason, count in data["reasonCounts"].items(): lines.append(f"- `{reason}`: {count:,}; examples `{', '.join(data['reasonExamples'].get(reason, []))}`")
    if data.get("funData"):
        lines += ["", "## FUNCDATA function blocks by region", ""]
        for region, value in data["funData"].items():
            lines.append(f"- {region}: {value['functionRecords']} indexed function records; {value['declaredLiveRows']} declared live rows; nonempty columns 1-5: {value['nonemptyColumns']}")
    if "sourceCensus" in data:
        lines += ["", "## SYSTEMENTER transports", ""]
        for key, value in data["sourceCensus"]["systementer"].items(): lines.append(f"- Native code {key}: {value['count']} entries; examples `{', '.join(x['id'] for x in value['examples'])}`")
        lines += ["", "## ECU table schemas", ""]
        for key, value in data["sourceCensus"]["ecuTables"].items(): lines.append(f"- `{key}`: {value['count']} sections / {value['uniqueTables']} unique tables across {value['ecuFiles']} ECU files; raw rows `{'; '.join(value['rows'])}`")
    rendered = "\n".join(lines) + "\n"
    encoded = rendered.encode("utf-8")
    if len(encoded) > 300_000:
        rendered = encoded[:299_900].decode("utf-8", errors="ignore") + "\n\n[Markdown truncated at 300 KB; full JSON artifact is retained.]\n"
    return rendered


def main():
    p = argparse.ArgumentParser()
    p.add_argument("packs", type=Path)
    p.add_argument("--decoded", type=Path)
    p.add_argument("--json", type=Path, required=True)
    p.add_argument("--markdown", type=Path, required=True)
    a = p.parse_args()
    result = census(a.packs, a.decoded)
    a.json.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    a.markdown.write_text(markdown(result))
    print(markdown(result), end="")

if __name__ == "__main__": main()
