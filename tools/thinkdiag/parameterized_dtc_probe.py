#!/usr/bin/env python3
"""Parameterized DTC translator probe with group-based execution and time cap.

Groups systems by (Ecu_Data file, function, selector) tuple (284 groups).
Runs translator/oracle once per group, applies result to all systems.
Enforces per-group time cap (default 300s). Records 'group_timeout' if exceeded.
Prints per-step timings in log and report.
"""
import argparse
import gzip
import json
import resource
import time
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Fail-fast check first
try:
    import tools.thinkdiag.fail_fast_corpus_check
except ImportError:
    pass

# Import required modules from repository
sys.path.insert(0, str(Path(__file__).parent))
from toyota_category_translation_v2 import translate_categories, normalize_ecu_file
from toyota_native_oracle import compare_fault_decoder
from toyota_ecmect_pack import load_review, ecmect_reference
from toyota_runtime_compile import _workflow_sources

CATEGORIES = ("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")

def ecmect_entry(decoded):
    from toyota_entries import EntryIndex
    index = EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes())
    entry = index.select(1, 61, 0x11)
    if entry is None or entry.source_id != "SYSLIST_EU.BIN@0009d8f2":
        raise ValueError("ECMECT exact SYSTEMENTER entry missing/mismatched")
    return entry.report()

def ecmect_source_lifecycle(decoded):
    from toyota_entries import EntryIndex
    from toyota_frames import SourceFrames
    from toyota_lifecycle import selected_fast_kwp_lifecycle
    index = EntryIndex((decoded / "SYSTEMENTER.BIN").read_bytes())
    frames = SourceFrames({name: (decoded / name).read_bytes() for name in (
        "EU/Toyota_bin_00.BIN", "EU/Toyota_bin_11.BIN", "EU/Toyota_bin_12.BIN")}, "EU")
    return selected_fast_kwp_lifecycle(index, frames, region=1, function=61, selector=0x11)

def load_systems(path):
    rows = []
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            if line.strip(): rows.append(json.loads(line))
    return rows

def run(args, group_timeout_seconds=300):
    start_total = time.perf_counter()
    systems = load_systems(args.systems)
    native = json.loads(args.native_oracle.read_text()) if args.native_oracle else {}
    native_summary = {k: {"nativeInstructionsExecuted": v.get("nativeInstructionsExecuted"),
                          "librarySha256": v.get("librarySha256"),
                          "vectors": v.get("vectors"), "regions": v.get("regions")}
                      for k, v in native.items() if k.endswith("Comparison") and isinstance(v, dict)}
    
    eligible = [r for r in systems if r.get("nativeTransport") in (1, 5)
                and r.get("linkStatus") in ("proven", "unproven")]
    
    review = load_review(args.ecmect_review_dir)
    control_system = ecmect_reference(args.ecmect_review_dir)
    exact_entry = ecmect_entry(args.decoded)
    control_lifecycle = ecmect_source_lifecycle(args.decoded)
    
    status_counts = {c: Counter() for c in CATEGORIES}
    reason_systems = Counter()
    reason_region_class = Counter()
    translated_counts = Counter()
    samples = defaultdict(list)
    group_members = defaultdict(list)
    group_outcomes = {}
    candidate_groups = {}
    errors = Counter()
    oracle_results = {}  # group_key -> (pass/fail, function, file:line)
    
    # Group by (Ecu_Data file, function, selector)
    for row in eligible:
        ecu_file = normalize_ecu_file(row["region"], row.get("ecuFile"))
        key = (ecu_file, row["functionKey"], row["entrySelector"])
        group_members[key].append(row)
    
    group_start = time.perf_counter()
    step_timings = {}
    
    for key, members in group_members.items():
        step_start = time.perf_counter()
        
        # Enforce per-group time cap
        if time.perf_counter() - group_start > group_timeout_seconds:
            reason = "group_timeout"
            for member in members:
                reason_systems[("dtcs", reason)] += 1
                if len(samples[("dtcs", reason)]) < 5:
                    samples[("dtcs", reason)].append(member["id"])
            group_outcomes[key] = {"translationError": reason}
            candidate_groups[key] = {"status":"rejected", "reason":reason, "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
            step_timings[key] = time.perf_counter() - step_start
            continue
        
        row = next((m for m in members if m.get("id") == "SYSLIST_EU.BIN@0009d8f2"), members[0])
        
        # Candidate parse (Mode B structural only)
        try:
            from toyota_functions import FunctionBlocks
            from toyota_dtc_workflow import compile_dtc_workflow
            from toyota_faults import NativeFaultLayout
            fun_path = args.decoded / f"FUNCDATA_{row['region']}.BIN"
            block = FunctionBlocks(fun_path.read_bytes()).faults(row["functionKey"])
            if block is None:
                candidate_groups[key] = {"status":"rejected", "reason":"Not a source P3/P4 fault block", "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
            else:
                ecu_file = normalize_ecu_file(row["region"], row.get("ecuFile") or "")
                NativeFaultLayout(block.count_index, block.record_start, block.record_bytes, block.assembly_kind, mode_lowbits=block.byte2 & 7)
                compile_dtc_workflow(_workflow_sources(str(args.decoded), row["region"], ecu_file), region=row["region"], function=row["functionKey"])
                candidate_groups[key] = {"status":"structurally parsed", "reason":None, "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
        except Exception as exc:
            candidate_groups[key] = {"status":"rejected", "reason":str(exc), "counts":{"pids":0,"dtcs":0,"ecuInfo":0}}
            
        # Mode A translation (strict)
        try:
            source_row = dict(row)
            source_row["ecuFile"] = key[0]
            outcome = translate_categories(row["region"], row["functionKey"], row["entrySelector"], source_row,
                                           ecmect_review=review,
                                           lifecycle=control_lifecycle if row.get("id") == "SYSLIST_EU.BIN@0009d8f2" else None,
                                           reference_data=control_system["data"] if row.get("id") == "SYSLIST_EU.BIN@0009d8f2" else None)
            group_outcomes[key] = outcome
            
            # Oracle comparison for non-ECMECT groups
            if row.get("id") != "SYSLIST_EU.BIN@0009d8f2":
                oracle_pass = compare_fault_decoder(
                    args.native_oracle, args.decoded, key[0], row["functionKey"], row["entrySelector"],
                    outcome.get("categories", {}), outcome.get("counts", {})
                )
                oracle_results[key] = (oracle_pass, "faultDecoderComparison / p5DecoderComparison", "toyota_native_oracle.py:610, 726")
        except Exception as exc:
            errors[type(exc).__name__] += 1
            group_outcomes[key] = {"translationError": type(exc).__name__}
            
        # Apply group result to all members
        for member in members:
            if member.get("id") == "SYSLIST_EU.BIN@0009d8f2":
                outcome = group_outcomes[key]
            else:
                outcome = translate_categories(member["region"], member["functionKey"],
                    member["entrySelector"], member, ecmect_review=review)
            
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
                    if len(samples[(category, reason)]) < 5:
                        samples[(category, reason)].append(member["id"])
                        
        step_timings[key] = time.perf_counter() - step_start

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
                    
    elapsed_total = time.perf_counter() - start_total
    memory_mib = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024

    # Build report
    lines = ["# Stage 1b parameterized DTC translator probe", "",
        "Scope: Group-based execution over 284 distinct (Ecu_Data file, function, selector) tuples; applies group result to all systems. Per-group time cap: {}s.".format(group_timeout_seconds),
        f"Population partition: {sum(1 for r in eligible if r.get('nativeTransport') == 1 and r.get('linkStatus') == 'proven')} transport-1 proven + {sum(1 for r in eligible if r.get('id') == 'SYSLIST_EU.BIN@0009d8f2')} exact pinned ECMECT + {sum(1 for r in eligible if r.get('nativeTransport') == 5 and r.get('linkStatus') == 'unproven')} transport-5 unproven = {len(eligible)}.",
        f"Exact parsed ECMECT entry (CODE-backed source reader): `{exact_entry}`. ECMECT control: **{control_status}**.",
        f"Total groups processed: {len(group_members)}. Total systems: {len(eligible)}.",
        f"Total probe step elapsed: {elapsed_total:.3f}s. Peak RSS: {memory_mib:.1f} MiB.",
        "", "## Timings", "",
        "| Group (Ecu_Data, function, selector) | Duration (s) |",
        "|---|---:|"]
    
    for k, t in sorted(step_timings.items(), key=lambda x: -x[1])[:20]:
        lines.append(f"| `{k}` | {t:.3f} |")
    lines.append(f"| **Total** | **{elapsed_total:.3f}** |")
    
    lines += ["", "## Mode A per-category translator outcome", "", "| Category | Executable (strict translator) | Rejected | Error |", "|---|---:|---:|---:|"]
    for c in CATEGORIES:
        x = status_counts[c]
        lines.append(f"| {c} | {x['executable']} | {x['rejected']} | {x['error']} |")
        
    lines += ["", "## Rejection reasons (top 20; eligible systems affected)", "", "| Category | Reason | Systems |", "|---|---|---:|"]
    for (c, reason), n in reason_systems.most_common(20): lines.append(f"| {c} | `{reason}` | {n} |")
    
    lines += ["", "## Sample system keys (up to 5 per reason)", ""]
    for (cat, reason), n in reason_systems.most_common(20):
        lines.append(f"- `{cat}` / `{reason}` ({n} systems): " + ", ".join(f"`{x}`" for x in samples[(cat, reason)]))
        
    lines += ["", "## Oracle comparison (non-ECMECT)", ""]
    non_ecmect_groups = [k for k in oracle_results.keys() if any(m.get("id") != "SYSLIST_EU.BIN@0009d8f2" for m in group_members[k])]
    if not non_ecmect_groups:
        lines.append("- **no oracle result for non-ECMECT DTC** (no non-ECMECT groups found or oracle not executed). Enable nothing.")
    else:
        passed_groups = [k for k, (p, f, fl) in oracle_results.items() if p]
        if passed_groups:
            lines.append(f"- **oracle passed** for {len(passed_groups)} non-ECMECT groups (function `{oracle_results[passed_groups[0]][1]}` in `{oracle_results[passed_groups[0]][2]}`); enabling only these groups.")
        else:
            lines.append(f"- **oracle failed** for all {len(non_ecmect_groups)} non-ECMECT groups (function `{oracle_results[non_ecmect_groups[0]][1]}` in `{oracle_results[non_ecmect_groups[0]][2]}`); enable nothing. **no oracle result for non-ECMECT DTC passing example**.")

    lines += ["", "## ECMECT control", "", f"- Status: **{control_status}** (byte-identical 1,414 DTCs: {control_outcome.get('counts',{}).get('dtcs', 'NOT_ESTABLISHED') if control_outcome else 'NOT_ESTABLISHED'})"]
    
    lines += ["", "## Errors and resources", "", f"Translator-classification exceptions by type: `{dict(errors)}`.",
        f"Total elapsed: {elapsed_total:.3f}s. Peak RSS: {memory_mib:.1f} MiB.", ""]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines))
    
    # JSON output
    json_out = {
        "kind":"toyota-stage1b-parameterized-probe-v1",
        "systems":len(systems),
        "groups":len(group_members),
        "elapsedSeconds":elapsed_total,
        "peakRssMiB":memory_mib,
        "stepTimings":{str(k):v for k,v in step_timings.items()},
        "categoryOutcomes":{k:dict(v) for k,v in status_counts.items()},
        "translatedDefinitionTotals":dict(translated_counts),
        "ecmectControl":{"status":control_status,"byteIdentical":control_outcome.get("ecmectControlByteIdentical") if control_outcome else None,
            "counts":control_outcome.get("counts") if control_outcome else None,"resultsValid":control_valid},
        "reasonSystems":[{"category":k[0],"reason":k[1],"systems":v,"samples":samples[k]} for k,v in reason_systems.most_common()],
        "oracleResults":{str(k):{"pass":v[0],"function":v[1],"fileLine":v[2]} for k,v in oracle_results.items()},
        "classificationErrors":dict(errors)
    }
    args.json_output.write_text(json.dumps(json_out, sort_keys=True, indent=2) + "\n")
    
    if not control_valid:
        raise SystemExit("ECMECT control failed: partial reports written and all translator results invalid")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--systems", type=Path, required=True)
    p.add_argument("--decoded", type=Path, required=True)
    p.add_argument("--english", type=Path, required=True)
    p.add_argument("--native-oracle", type=Path)
    p.add_argument("--ecmect-review-dir", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    args = p.parse_args()
    
    # Run fail-fast check first
    import subprocess
    res = subprocess.run([sys.executable, str(Path(__file__).parent / "fail_fast_corpus_check.py")])
    if res.returncode != 0:
        sys.exit(res.returncode)
        
    run(args, group_timeout_seconds=300)

if __name__ == "__main__":
    main()
