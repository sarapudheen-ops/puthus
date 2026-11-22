#!/usr/bin/env python3
"""Bounded, fail-closed Stage 1 K-Line category probe.

This reports current converter outcomes, source table census, FUNCDATA live
record references, and EXPRESS usage. It never changes a pack or enables a row.
A converter status is not promoted to `executable` unless it already is in the
verified output; categories without per-record oracle coverage stay rejected.
"""
import argparse
import gzip
import json
import resource
import time
from collections import Counter, defaultdict
from pathlib import Path

from toyota_calc import REVIEWED
from convert_vehicle_packs import REASONS
from toyota_structure import section_directory, typed_table, uint

CATEGORIES = ("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")
KNOWN = {(3, 8), (6, 8), (10, 104), (39, 20)}


def translate_categories(region, function, selector, system_row):
    """No-write classification of exact current per-system compiler results."""
    if region not in ("EU", "NA", "JP") or type(function) is not int or type(selector) is not int:
        raise ValueError("exact_region_function_selector_required")
    if not isinstance(system_row, dict):
        raise TypeError("system_row_must_be_object")
    if (system_row.get("region"), system_row.get("functionKey"), system_row.get("entrySelector")) != (region, function, selector):
        raise ValueError("system_identity_mismatch")
    statuses = system_row.get("categoryStatus")
    if not isinstance(statuses, dict):
        raise ValueError("category_status_missing")
    reasons = system_row.get("categoryReasons", {})
    result = {}
    for category in CATEGORIES:
        status = statuses.get(category, "unknown")
        if status == "executable":
            result[category] = ("executable", None)
        elif status == "error":
            result[category] = ("error", str(reasons.get(category, "translator_error_unspecified")))
        else:
            reason = reasons.get(category)
            if not reason:
                reason = "translator_not_ported" if status in ("unknown", "unresolved", None) else f"invalid_status:{status}"
            result[category] = ("rejected", str(reason))
    return result


def row_bucket(n):
    if n == 0: return "0"
    if n <= 4: return "1-4"
    if n <= 16: return "5-16"
    if n <= 64: return "17-64"
    if n <= 256: return "65-256"
    return "257+"


def load_systems(path):
    rows = []
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            if line.strip(): rows.append(json.loads(line))
    return rows


def census_tables(decoded):
    groups = defaultdict(lambda: {"sections": 0, "rows": 0, "files": set(), "samples": []})
    for path in sorted(decoded.rglob("Ecu_Data_*.BIN")):
        rel = path.relative_to(decoded).as_posix()
        region = rel.split("/", 1)[0]
        raw = path.read_bytes()
        for sec in section_directory(raw):
            table = typed_table(raw[sec.offset:sec.end])
            bucket = (region, sec.identity, table.identity, table.stride, row_bucket(table.count))
            g = groups[bucket]
            g["sections"] += 1; g["rows"] += table.count; g["files"].add(rel)
            stride = table.stride
            if stride:
                for i in range(table.count):
                    if len(g["samples"]) >= 2: break
                    offset = sec.offset + 10 + i * stride
                    row = table.body[i * stride:(i + 1) * stride]
                    g["samples"].append((f"{rel}@{offset:08x}", row.hex()))
    result = []
    for (region, slot, tag, stride, bucket), g in groups.items():
        result.append({"region": region, "slot": slot, "tableTag": tag, "stride": stride,
                       "rowBucket": bucket, "sections": g["sections"], "rows": g["rows"],
                       "ecuFiles": sorted(g["files"]), "samples": g["samples"],
                       "recognizedP4Pair": (tag, stride) in KNOWN})
    return sorted(result, key=lambda r: (-r["rows"], r["region"], r["slot"], str(r["tableTag"]), str(r["stride"]), r["rowBucket"]))


def live_and_expression_census(decoded, english):
    region_result, expressions = {}, Counter()
    expression_text = json.loads((english / "EXPRESS.GAG.json").read_text())
    if not isinstance(expression_text, dict): raise ValueError("EXPRESS text index is not an object")
    for region in ("EU", "NA", "JP"):
        raw = (decoded / f"FUNCDATA_{region}.BIN").read_bytes()
        rows = []
        # Native FILE index pointer ordering is checked by FunctionBlocks; use it
        # to avoid counting unindexed bytes as functions.
        from toyota_functions import FunctionBlocks
        functions = FunctionBlocks(raw)
        calc_refs = Counter()
        for key in sorted(functions.rows):
            span = functions.span(key, 3)
            if span is None: continue
            start, stop = span
            if start + 4 > stop: raise ValueError(f"Truncated live block {region}/{key}")
            count, commands = uint(raw, start, 2), uint(raw, start + 2, 2)
            end = start + 4 + count * 30
            if end > stop: raise ValueError(f"Live block rows exceed indexed span {region}/{key}")
            rows.append((key, count, commands))
            for i in range(count):
                at = start + 4 + 30 * i
                calc_id = uint(raw, at + 26, 4)
                calc_refs[f"{calc_id:08X}"] += 1
        for calc_id, count in calc_refs.items():
            text = expression_text.get(calc_id)
            if isinstance(text, str): expressions[text] += count
        region_result[region] = {"functionsWithLiveBlock": len(rows),
            "declaredLiveRows": sum(c for _, c, _ in rows),
            "declaredCommandRows": sum(c for _, _, c in rows),
            "calcIdReferences": sum(calc_refs.values()), "distinctCalcIds": len(calc_refs)}
    return region_result, expressions


def run(args):
    started = time.perf_counter()
    systems = load_systems(args.systems)
    native = json.loads(args.native_oracle.read_text()) if args.native_oracle else {}
    native_summary = {k: {"nativeInstructionsExecuted": v.get("nativeInstructionsExecuted"),
                          "librarySha256": v.get("librarySha256"),
                          "vectors": v.get("vectors"), "regions": v.get("regions")}
                      for k, v in native.items() if k.endswith("Comparison") and isinstance(v, dict)}
    eligible = [r for r in systems if r.get("nativeTransport") == 1 and r.get("linkStatus") == "proven"]
    status_counts = {c: Counter() for c in CATEGORIES}
    reason_systems = Counter()
    errors = Counter()
    for row in eligible:
        try:
            outcome = translate_categories(row["region"], row["functionKey"], row["entrySelector"], row)
            for category, (status, reason) in outcome.items():
                status_counts[category][status] += 1
                if reason: reason_systems[(category, reason)] += 1
        except Exception as exc:
            errors[type(exc).__name__] += 1
            for category in CATEGORIES: status_counts[category]["error"] += 1
    tables = census_tables(args.decoded)
    live, expressions = live_and_expression_census(args.decoded, args.english)
    elapsed = time.perf_counter() - started
    memory_mib = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024

    lines = ["# Stage 1 K-Line category dry-run probe", "",
        "Scope: current converter outputs for transport-1 systems with complete native lifecycle evidence; no pack changes or enablement.",
        "", "## Source-read findings (CODE)", "",
        "- `convert_vehicle_packs.py:321-327`: system row reads function key from SYSLIST `raw[0:4]`, ECU filename from the text index at `raw[23:27]`, selector from `raw[27]`, then joins region+function to FUNCDATA, region+ECU name to Ecu_Data, and exact region/function/full selector via `EntryIndex.select` to SYSTEMENTER. Source IDs are retained at lines 329-335.",
        "- `toyota_runtime_compile.py:28-43`: existing DTC compiler requires exact 37-byte SYSLIST row and transport and rejects native caller modes 7/9 with `Native caller mode 7/9 has additional unported overrides`.",
        "- `toyota_live.py:1-17`: direct-21 reader describes +26 as the EXPRESS key (not monitor +50); only two bounded numeric equivalences have separate pinned CALC review, and the results remain disabled candidates.",
        "- `toyota_live.py:207-229`: inspected P4 Ecu_Data pairs are (3,8), (6,8), (10,104), and (39,20); table identity and nonzero stride are checked before rows are consumed. This code contains native-consumer stop conditions for support, command, monitor and LSB rows.",
        "- Pinned source slices: `toyota_ecmect_pack.py:13-14` identifies `SYSTEMENTER.BIN@00002f63` and `FUNCDATA_EU.BIN@000005e9`; `toyota_runtime_compile.py:110-111` pins the mandatory system/ECU; `toyota_innova_2007_reads.py:20-22` pins the 2007 SYSLIST/entry/ECU source records.",
        "- All current converter `REASONS` entries:", "", "| Reason | Source description |", "|---|---|"]
    for reason, description in sorted(REASONS.items()):
        lines.append(f"| `{reason}` | {description.replace('|', chr(92)+'|')} |")
    lines += ["",
        f"Systems examined in source conversion report: {len(systems)}; eligible rows: {len(eligible)}.",
        "The status is the existing converted-system category status. This probe does not claim an unrun per-record native differential.", "",
        "## Per-category outcome", "", "| Category | Executable (existing) | Rejected | Error |", "|---|---:|---:|---:|"]
    for c in CATEGORIES:
        x = status_counts[c]
        lines.append(f"| {c} | {x['executable']} | {x['rejected']} | {x['error']} |")
    lines += ["", "## Rejection reasons (top 20; eligible systems affected)", "", "| Category | Reason | Systems |", "|---|---|---:|"]
    for (c, reason), n in reason_systems.most_common(20): lines.append(f"| {c} | `{reason}` | {n} |")
    lines += ["", "## Unrecognized Ecu_Data table layouts", "",
        "Recognized-pair flag means only the four checked-in P4 tableTag/stride shapes; semantic coverage is not implied.",
        "Top 30 layouts by summed source rows. Each row includes its bounded file list and up to two raw source rows with byte offsets.", "",
        "| Region | slot | tableTag | stride | row bucket | sections | rows | ECU files | recognized P4 pair | raw samples |",
        "|---|---:|---:|---:|---|---:|---:|---:|---|---|"]
    for r in [x for x in tables if not x["recognizedP4Pair"]][:30]:
        samples = "<br>".join(f"`{ident}` `{raw}`" for ident, raw in r["samples"])
        files = ", ".join(f"`{f}`" for f in r["ecuFiles"])
        lines.append(f"| {r['region']} | {r['slot']} | {r['tableTag']} | {r['stride']} | {r['rowBucket']} | {r['sections']} | {r['rows']} | {files} | no | {samples} |")
    lines += ["", "## FUNCDATA live-row census", "", "| Region | Functions with live block | Declared live rows | Declared command rows | CALC id references | Distinct CALC ids |", "|---|---:|---:|---:|---:|---:|"]
    for region, d in live.items(): lines.append(f"| {region} | {d['functionsWithLiveBlock']} | {d['declaredLiveRows']} | {d['declaredCommandRows']} | {d['calcIdReferences']} | {d['distinctCalcIds']} |")
    lines += ["", "## EXPRESS expression references (top 20)", "", "Support is exact-string membership in `toyota_calc.REVIEWED`; not a claim of runtime/native comparison for this row.", "", "| References | Existing `toyota_calc.py` support | Expression |", "|---:|---|---|"]
    reviewed = {v[0]: (k, v[1]) for k, v in REVIEWED.items()}
    for text, count in expressions.most_common(20):
        supported = next((f"yes: `{key}`" for key, (script, _) in reviewed.items() if script == text), "no")
        lines.append(f"| {count} | {supported} | `{text.replace('|', chr(92)+'|')}` |")
    lines += ["", "## Errors and resources", "", f"Translator-classification exceptions by type: `{dict(errors)}`.",
        f"Elapsed: {elapsed:.3f}s. Peak RSS: {memory_mib:.1f} MiB.", "",
        "## Gate interpretation", "",
        "This is a dry-run of current system category statuses, not new translation or per-record native verification. Existing global native comparisons are summarized from the same run below; they do not imply new per-record comparison coverage. No additional system/category is enabled. Any candidate requiring unrecognized table semantics or a record-level differential remains rejected/unresolved.", "",
        "## Existing same-run native comparisons (global scope)", "",
        "```json", json.dumps(native_summary, sort_keys=True, indent=2), "```", ""]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines))
    args.json_output.write_text(json.dumps({"kind":"toyota-stage1-category-probe-v1", "systems":len(systems),
        "eligibleTransport1ProvenSystems":len(eligible), "categoryOutcomes":{k:dict(v) for k,v in status_counts.items()},
        "reasonSystems":[{"category":k[0],"reason":k[1],"systems":v} for k,v in reason_systems.most_common()],
        "classificationErrors":dict(errors), "nativeComparisons":native_summary,
        "unrecognizedEcuLayouts":[r for r in tables if not r["recognizedP4Pair"]],
        "liveRowsByRegion":live, "topExpressions":[{"expression":e,"references":c,"existingReviewed":e in {v[1] for v in REVIEWED.values()}} for e,c in expressions.most_common(20)],
        "elapsedSeconds":elapsed,"peakRssMiB":memory_mib}, sort_keys=True, indent=2)+"\n")


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--systems", type=Path, required=True)
    p.add_argument("--decoded", type=Path, required=True)
    p.add_argument("--english", type=Path, required=True)
    p.add_argument("--native-oracle", type=Path)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    run(p.parse_args())

if __name__ == "__main__": main()
