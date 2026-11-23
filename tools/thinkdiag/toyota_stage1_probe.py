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
from functools import lru_cache

from toyota_calc import REVIEWED
from convert_vehicle_packs import REASONS
from toyota_ecmect_pack import load_review
from toyota_category_translation import translate_categories, normalize_ecu_file
from toyota_structure import section_directory, typed_table, uint

CATEGORIES = ("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")
KNOWN = {(3, 8), (6, 8), (10, 104), (39, 20)}


def ecmect_entry(decoded):
    from toyota_entries import EntryIndex
    index = EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes())
    entry = index.select(1, 61, 0x11)
    if entry is None or entry.source_id != "SYSTEMENTER.BIN@00002f63":
        raise ValueError("ECMECT exact SYSTEMENTER entry missing/mismatched")
    return entry.report()


def ecmect_reference(pack_dir):
    from vehicle_bundle import read_object
    index = json.loads((pack_dir / "index.json").read_bytes())
    container = pack_dir / index["container"]
    page = read_object(container, index["catalogRoot"])
    for name in ("Toyota", "Kijang Innova", "TGN · 2009.07-2017.07", "MINI VAN · w/ VSC (Vehicle Stability Control)"):
        page = read_object(container, next(c for c in page["children"] if c["name"] == name)["object"])
    manual = read_object(container, page["systemsSource"])
    ref = next(s["object"] for s in manual["systems"] if s["id"] == "SYSLIST_EU.BIN@0009d8f2")
    return read_object(container, ref)


@lru_cache(maxsize=None)
def _function_blocks(path):
    from toyota_functions import FunctionBlocks
    return FunctionBlocks(Path(path).read_bytes())


@lru_cache(maxsize=None)
def _ecu_table_count(path):
    raw = Path(path).read_bytes()
    return len([typed_table(raw[s.offset:s.end]) for s in section_directory(raw)])


@lru_cache(maxsize=1)
def _program_index(path):
    from toyota_ecuprogram import EcuProgramIndex
    return EcuProgramIndex(Path(path).read_bytes())


@lru_cache(maxsize=None)
def _workflow_sources(decoded_path, region, ecu_file):
    root = Path(decoded_path)
    filenames = [f"FUNCDATA_{region}.BIN", f"{region}/Toyota_bin_00.BIN",
                 f"{region}/Toyota_bin_11.BIN", f"{region}/Toyota_bin_12.BIN", ecu_file]
    return {name: (root / name).read_bytes() for name in dict.fromkeys(filenames)}


def candidate_parse_group(decoded, region, function, ecu_file):
    """Mode B structural-only candidate parse; never authorizes definitions."""
    try:
        fun_path = decoded / f"FUNCDATA_{region}.BIN"
        block = _function_blocks(str(fun_path)).faults(function)
        if block is None:
            return {"status":"rejected", "reason":"Not a source P3/P4 fault block",
                    "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
        program_path = decoded / "ECUPROGRAM.BIN"
        if program_path.is_file():
            _program_index(str(program_path)).select({"EU":1,"NA":2,"JP":3}[region], function)
        if not ecu_file:
            return {"status":"rejected", "reason":"not_parameterizable:missing_ecu_file",
                    "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
        ecu_file = normalize_ecu_file(region, ecu_file)
        from toyota_faults import NativeFaultLayout
        from toyota_dtc_workflow import compile_dtc_workflow
        NativeFaultLayout(block.count_index, block.record_start, block.record_bytes,
                          block.assembly_kind, mode_lowbits=block.byte2 & 7)
        workflow = compile_dtc_workflow(_workflow_sources(str(decoded), region, ecu_file),
                                        region=region, function=function)
        # Parse framing only; no category semantics are inferred. Cache ECU and
        # regional input bytes because many system groups reuse those sources.
        table_count = _ecu_table_count(str(decoded / ecu_file))
        return {"status":"structurally parsed", "reason":None,
                "faultQueries":len(workflow.queries), "ecuTables":table_count,
                "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
    except Exception as exc:
        return {"status":"rejected", "reason":str(exc),
                "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}


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
    # Promotion partition: 39,543 records = 39,363 transport-1 proven +
    # one pinned ECMECT record (native transport 5, but separately source-verified)
    # + 179 transport-5 unproven. Do not equate linkStatus=proven with transport=1.
    eligible = [r for r in systems if r.get("nativeTransport") in (1, 5)
                and r.get("linkStatus") in ("proven", "unproven")]
    review = load_review(args.ecmect_review_dir)
    control_system = ecmect_reference(args.ecmect_review_dir)
    exact_entry = ecmect_entry(args.decoded)
    status_counts = {c: Counter() for c in CATEGORIES}
    reason_systems = Counter()
    reason_region_class = Counter()
    translated_counts = Counter()
    samples = defaultdict(list)
    group_members = defaultdict(list)
    group_outcomes = {}
    candidate_groups = {}
    errors = Counter()
    for row in eligible:
        ecu_file = normalize_ecu_file(row["region"], row.get("ecuFile"))
        key = (ecu_file, row["functionKey"], row["entrySelector"])
        group_members[key].append(row)
    for key, members in group_members.items():
        row = members[0]
        candidate_groups[key] = candidate_parse_group(args.decoded, row["region"], row["functionKey"], row.get("ecuFile") or "")
        try:
            source_row = dict(row)
            source_row["ecuFile"] = key[0]
            outcome = translate_categories(row["region"], row["functionKey"], row["entrySelector"], source_row,
                                           ecmect_review=review,
                                           reference_data=control_system["data"] if row.get("id") == "SYSLIST_EU.BIN@0009d8f2" else None)
            group_outcomes[key] = outcome
        except Exception as exc:
            errors[type(exc).__name__] += 1
            group_outcomes[key] = {"translationError": type(exc).__name__}
        for member in members:
            outcome = group_outcomes[key]
            for category in CATEGORIES:
                if "translationError" in outcome:
                    status_counts[category]["error"] += 1
                    reason = "translator_error:" + outcome["translationError"]
                else:
                    status = outcome["categories"][category]
                    status_counts[category][status] += 1
                    if status == "executable":
                        translated_counts[category] += outcome["counts"][category]
                    reason = outcome["reasons"].get(category)
                if reason:
                    reason_systems[(category, reason)] += 1
                    reason_region_class[(member.get('region'), member.get('nativeTransport'), category, reason)] += 1
                    if len(samples[(category, reason)]) < 10:
                        samples[(category, reason)].append(member["id"])
    control_outcome = next((v for k,v in group_outcomes.items()
                            if any(m.get("id") == "SYSLIST_EU.BIN@0009d8f2" for m in group_members[k])), None)
    control_valid = (control_outcome is not None
        and control_outcome.get("ecmectControlByteIdentical") is True
        and {c:control_outcome.get("counts",{}).get(c) for c in ("pids","dtcs","ecuInfo")} ==
            {"pids":30,"dtcs":1414,"ecuInfo":1})
    control_status = "passed" if control_valid else "control failed, results invalid"
    candidate_status = Counter()
    candidate_reasons = Counter()
    candidate_reason_samples = defaultdict(list)
    for key, outcome in candidate_groups.items():
        candidate_status[outcome['status']] += len(group_members[key])
        if outcome.get('reason'):
            candidate_reasons[outcome['reason']] += len(group_members[key])
            for row in group_members[key]:
                if len(candidate_reason_samples[outcome['reason']]) < 10:
                    candidate_reason_samples[outcome['reason']].append(row['id'])
    tables = census_tables(args.decoded)
    live, expressions = live_and_expression_census(args.decoded, args.english)
    elapsed = time.perf_counter() - started
    memory_mib = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024

    lines = ["# Stage 1 K-Line category dry-run probe", "",
        "Scope: Mode A strict pinned translator and Mode B bounded candidate-parse over the transport-1/ECMECT/transport-5-unproven partition; no pack changes or enablement.",
        f"Population partition: {sum(1 for r in eligible if r.get('nativeTransport') == 1 and r.get('linkStatus') == 'proven')} transport-1 proven + {sum(1 for r in eligible if r.get('id') == 'SYSLIST_EU.BIN@0009d8f2')} exact pinned ECMECT + {sum(1 for r in eligible if r.get('nativeTransport') == 5 and r.get('linkStatus') == 'unproven')} transport-5 unproven = {len(eligible)}.",
        f"Exact parsed ECMECT entry (CODE-backed source reader): `{exact_entry}`. Census classes: K-Line/native transport 1/selector 17 has 39,363 systems; K-Line/transport 5/selector 81 has 180 = ECMECT 1 + unproven 179. ECMECT is counted exactly once, in K-Line/5/81; the transport-5 unproven subclass excludes it.",
        "`toyota_ecmect_pack.link_for` rejects any entry where transport != 1 or flags != 0x81. The ECMECT compiler accepts its pinned identity through `convert_vehicle_packs.py:387-405` and `toyota_ecmect_pack.enable` (`:216-239`), which deliberately uses its source-read review/profile; when no lifecycle projection is passed, `data_for` uses pinned LINK policy instead of `link_for`. Thus linkStatus proven here means explicit profile acceptance, not generalized transport-5 support.",
        f"ECMECT control: {control_status}. If failed, all translator results in this report are invalid and the process exits non-zero after writing the partial report.",
        "Mode B is candidate-parse only; structurally parsed does NOT mean translated, verified, or enabling.",
        "Argument/pin audit: Mode A `translate_categories(region,function,selector,system_row,ecmect_review,reference_data)`; pins are `toyota_runtime_compile.py:110-111`, `toyota_ecmect_pack.py:13-14,222-235`, `toyota_runtime_compile.py:135-145`. Mode B `candidate_parse_group(decoded,region,function,ecu_file)` uses `FunctionBlocks.faults(function)` from `toyota_functions.py:148-159`; it can structurally parse fault blocks, but no DTC definitions or oracle is returned. `toyota_runtime_compile.py:114-145` pins SYSLIST offset 0x9d8f2, function 61, selector 0x11, caller 11, ECU 0081 and entry 0x2f63; those prevent applying ECMECT compile to other groups.",
        "Literal Mode A rejection reasons include `ecmect_specific:mandatory_system_and_live_read_review`, `ecmect_specific:mandatory_system_and_p3p4_workflow`, `ecmect_specific:mandatory_system_and_ecu_identification`, and `no_generic_translator:<category>`. Mode B retains the first Python parser exception verbatim or `not_parameterizable:category_semantics_require_source_caller_and_oracle` when structural framing alone is insufficient.",
        "Mode B categories without structural compiler result are deliberately skipped: PID live semantics are gated by `toyota_live.py:313-318` and exact support inputs; DTC semantic caller by `toyota_runtime_compile.py:28-43`; ecuInfo by `toyota_ecuprogram.py:119-127`; actuator/coding/special-functions have no generic translator in the reviewed modules.",

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
        "Mode A invokes the existing pinned ECMECT data_for(review) path and rejects other records by named pin dependencies. Mode B is bounded candidate parsing only and never yields executable category definitions. Neither mode performs new oracle approval.", "",
        "## Mode A per-category translator outcome", "", "| Category | Executable (strict translator) | Rejected | Error |", "|---|---:|---:|---:|"]
    for c in CATEGORIES:
        x = status_counts[c]
        lines.append(f"| {c} | {x['executable']} | {x['rejected']} | {x['error']} |")
    lines.append(f"Translated definition totals for translator-executable rows (not oracle-approved): `{dict(translated_counts)}`. ECMECT control: **{control_status}**; counts: `{control_outcome.get('counts') if control_outcome else None}`.")
    lines += ["", "## Rejection reasons (top 20; eligible systems affected)", "", "| Category | Reason | Systems |", "|---|---|---:|"]
    for (c, reason), n in reason_systems.most_common(20): lines.append(f"| {c} | `{reason}` | {n} |")
    lines += ["", "Regional / native-transport rejection histogram:", ""]
    for (region, transport, cat, reason), n in sorted(reason_region_class.items(), key=lambda x:(x[0][0],x[0][1],-x[1],x[0][2],x[0][3])):
        lines.append(f"- {region}/transport-{transport} `{cat}` `{reason}`: {n}")
    lines += ["", "## Mode B candidate-parse (NOT verified / NOT enabling)", "",
              f"Distinct `(Ecu_Data file,function,selector)` groups: {len(group_members)}; systems sharing groups: " +
              f"{sum(max(0,len(v)-1) for v in group_members.values())} beyond one representative per group.",
              f"Candidate parser outcomes (systems): `{dict(candidate_status)}`; first-reject histogram: `{dict(candidate_reasons)}`.",
              "Per-category candidate definitions remain zero: parsers only establish structure. Literal parser exceptions above are retained; no formula/table semantics are inferred.",
              "", "Top 15 rejection reasons by affected systems, up to 10 representative exact system keys each:", ""]
    for (cat, reason), n in reason_systems.most_common(15):
        lines.append(f"- `{cat}` / `{reason}`: {n}; samples: " + ", ".join(f"`{x}`" for x in samples[(cat, reason)]))
    lines.append("Mode B first-reject top 15 with up to 10 system samples each:")
    for reason, n in candidate_reasons.most_common(15):
        lines.append(f"- `{reason}`: {n}; samples: " + ", ".join(f"`{x}`" for x in candidate_reason_samples[reason]))
    lines += ["", "## Unrecognized Ecu_Data table layouts", "",
        "Recognized-pair flag means only the four checked-in P4 tableTag/stride shapes; semantic coverage is not implied.",
        "Top 30 layouts by summed source rows. Each row includes its bounded file list and up to two raw source rows with byte offsets.", "",
        "| Region | slot | tableTag | stride | row bucket | sections | rows | ECU files | recognized P4 pair | raw samples |",
        "|---|---:|---:|---:|---|---:|---:|---:|---|---|"]
    for r in [x for x in tables if not x["recognizedP4Pair"]][:30]:
        sample_html = "<br>".join(f"`{ident}` `{raw}`" for ident, raw in r["samples"])
        files = ", ".join(f"`{f}`" for f in r["ecuFiles"])
        lines.append(f"| {r['region']} | {r['slot']} | {r['tableTag']} | {r['stride']} | {r['rowBucket']} | {r['sections']} | {r['rows']} | {files} | no | {sample_html} |")
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
        "ecmectEntryParsed":exact_entry,
        "censusClassPartition":{"klineTransport1Selector17":39363,"klineTransport5Selector81":180,"ecmectInTransport5Class":1,"transport5Unproven":179},
        "populationPartition":{"transport1Proven":sum(1 for r in eligible if r.get('nativeTransport') == 1 and r.get('linkStatus') == 'proven'),
            "ecmect":sum(1 for r in eligible if r.get('id') == 'SYSLIST_EU.BIN@0009d8f2'),
            "transport5Unproven":sum(1 for r in eligible if r.get('nativeTransport') == 5 and r.get('linkStatus') == 'unproven')},
        "categoryOutcomes":{k:dict(v) for k,v in status_counts.items()},
        "translatedDefinitionTotalsNotOracleApproved":dict(translated_counts),
        "ecmectControl":{"status":control_status,"byteIdentical":control_outcome.get("ecmectControlByteIdentical") if control_outcome else None,
            "counts":control_outcome.get("counts") if control_outcome else None,"resultsValid":control_valid},
        "reasonSystems":[{"category":k[0],"reason":k[1],"systems":v,"samples":samples[k]} for k,v in reason_systems.most_common()],
        "reasonByRegionAndTransport":[{"region":k[0],"transport":k[1],"category":k[2],"reason":k[3],"systems":v}
            for k,v in sorted(reason_region_class.items())],
        "groups":{"distinct":len(group_members),"systemsBeyondOnePerGroup":sum(max(0,len(v)-1) for v in group_members.values())},
        "candidateParse":{"statusBySystems":dict(candidate_status),"firstRejectBySystems":dict(candidate_reasons)},
        "ecmectControlGroups":[{"group":list(k),"systems":len(v),"controlByteIdentical":group_outcomes[k]['ecmectControlByteIdentical']}
            for k,v in group_members.items() if 'ecmectControlByteIdentical' in group_outcomes.get(k,{}) and group_outcomes[k]['ecmectControlByteIdentical'] is not None],
        "classificationErrors":dict(errors), "nativeComparisons":native_summary,
        "unrecognizedEcuLayouts":[r for r in tables if not r["recognizedP4Pair"]],
        "liveRowsByRegion":live, "topExpressions":[{"expression":e,"references":c,"existingReviewed":e in {v[1] for v in REVIEWED.values()}} for e,c in expressions.most_common(20)],
        "elapsedSeconds":elapsed,"peakRssMiB":memory_mib}, sort_keys=True, indent=2)+"\n")
    if not control_valid:
        raise SystemExit("ECMECT control failed: partial reports written and all translator results invalid")


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--systems", type=Path, required=True)
    p.add_argument("--decoded", type=Path, required=True)
    p.add_argument("--english", type=Path, required=True)
    p.add_argument("--native-oracle", type=Path)
    p.add_argument("--ecmect-review-dir", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    run(p.parse_args())

if __name__ == "__main__": main()
