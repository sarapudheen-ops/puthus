#!/usr/bin/env python3
"""Read-only census and native-setup probe for source native transport codes 3 and 4.

Nothing is enabled: no converter, pack, APK or validator output is written and no
system/category is promoted. Two independent measurements are reported per exact
link tuple (region, function, selector), using the same tuple definition as the
Code 1/5 sweep in `toyota_code1_lifecycle_sweep.py`:

1. the pinned oracle path (`FastKwpEntry` / `selected_fast_kwp_lifecycle`), which is
   a closed transport-1/5 class, so a CAN tuple is not replayable there; and
2. the native dispatch itself: `GetInfoFromEnterList` then `SystemEnterPart` in the
   bounded AArch64 sandbox, with the adapter-facing COMM transport hooks bound to
   argument recorders. Calls and their exact integer/byte arguments are recorded.

No frame byte, timing or CAN identifier is derived, guessed or filled in. A tuple
whose native setup stops on an unbound external call reports that literal symbol.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import struct
import time
from pathlib import Path

from convert_vehicle_packs import REGIONS, load_sources, system_lists
from native_replay import NativeSandbox, ReplayError
from pack_census import census
from toyota_entries import EntryIndex
from toyota_entry_runtime import signed16
from toyota_native_oracle import MemoryFiles, REFERENCE, load_libraries
from toyota_transport import FastKwpEntry
from vehicle_bundle import canonical

REGION_IDS = {"EU": 1, "NA": 2, "JP": 3}
REGION_NAMES = {1: "EU", 2: "NA", 3: "JP"}
CAN_CODES = (3, 4)
PROBE_LIMIT = 1000
PROBE_SECONDS = 600

# Cells the reviewed symbol inventory (sweep_toyota.py SYMBOLS) places in both SYSP
# and COMM. A cell is joined only when SYSP holds an eight-byte object of that name
# and COMM defines the function of the same name: the shape the sandbox enforces.
CAN_CELLS = ("InitCanBusMode", "InitExtendCanbusMode", "InitCanCommSet_MPT", "SetCan15765",
             "SetCanComPara", "SetCanComParaFilterId", "MenuEnterCommunicationSet",
             "GetBaseCanBusRecData", "MyCanSendOneFrameReceiveDatas", "GetKwpRecData",
             "GetKwpRecDataWithFrame")

# The adapter-facing COMM/SYSP hooks the native setup is observed through. Bound
# callbacks are declared fakes: they record arguments and return a fixed value.
HOOKS = (
    ("BeginCombination", 0), ("EnableLLine", 1), ("SelectPIN", 5),
    ("SetCommunicationMaximumWaitTime", 1), ("SetEcuCommunicationVoltageLogic", 2),
    ("SetCommunicationParameter", 4), ("SetCommunicationLineVoltage", 3),
    ("SetEcuCommTimeInterval", 3), ("SetCanBitRate", 1), ("SetCanFilterId", 3),
    ("CanSetParameter", 4), ("CanSetParameterNormal", 4), ("CanSetEnterFrame", 4),
    ("SetCan15765", 4), ("SetCanComPara", 4), ("InitCanBusMode", 4),
    ("InitExtendCanbusMode", 4), ("InitCanCommSet_MPT", 4), ("CHK_CAN_BUS_iProtect", 2),
)


def source_rows(sources):
    """Every SYSLIST row as (region id, function, selector); duplicated header first."""
    rows = []
    for region in REGIONS.values():
        for raw in system_lists(sources[f"SYSLIST_{region}.BIN"])[1].values():
            for item in raw:
                rows.append((REGION_IDS[region], int.from_bytes(item[:4], "little"), item[27]))
    return rows


def can_tuples(rows, index):
    """Group exact joins for native transport 3/4. No nibble or sibling fallback."""
    systems = collections.Counter()
    entries = {}
    unjoined = collections.Counter()
    for rid, function, selector in rows:
        entry = index.select(rid, function, selector)
        if entry is None:
            unjoined[REGION_NAMES[rid]] += 1
            continue
        if entry.transport not in CAN_CODES:
            continue
        key = (rid, function, selector)
        systems[key] += 1
        entries[key] = entry
    return systems, entries, unjoined


def census_totals(pack_dir, decoded):
    """Census class rows for native codes 3/4, plus their totals."""
    data = census(Path(pack_dir), Path(decoded) if decoded else None)
    classes = [row for row in data["systemClasses"] if str(row["nativeTransportCode"]) in ("3", "4")]
    totals = {}
    for code in ("3", "4"):
        rows = [row for row in classes if str(row["nativeTransportCode"]) == code]
        totals[code] = {
            "systems": sum(row["systems"] for row in rows),
            "enabled": sum(row["enabled"] for row in rows),
            "classes": sorted(rows, key=lambda row: (-row["systems"], row["region"], row["transportLabel"])),
        }
    return totals, data["treeCounts"]


def reconciliation(tuple_systems, tuple_count, census_totals_row, published=None):
    """Tuple-side and census-side totals must agree; disagreement is reported literally."""
    census_systems = sum(census_totals_row[code]["systems"] for code in ("3", "4"))
    row = {
        "tupleSystems": tuple_systems,
        "censusSystems": census_systems,
        "agrees": tuple_systems == census_systems,
        "difference": tuple_systems - census_systems,
        "tupleCount": tuple_count,
    }
    if published is not None:
        pub = {code: sum(r["systems"] for r in published["systemClasses"]
                         if str(r["nativeTransportCode"]) == str(code)) for code in ("3", "4")}
        row["publishedCensusSystems"] = pub
        row["publishedCensusAgrees"] = sum(pub.values()) == census_systems
    return row


def probe_selection(tuples, limit=PROBE_LIMIT):
    """All tuples when the set is small, else a stated systems-descending prefix."""
    ordered = sorted(tuples, key=lambda key: (-tuples[key], key[0], key[1], key[2]))
    if len(ordered) <= limit:
        return ordered, {"policy": "all tuples; set at or below the limit",
                         "limit": limit, "probed": len(ordered), "totalTuples": len(ordered)}
    chosen = ordered[:limit]
    return chosen, {"policy": f"systems-descending prefix of {limit} tuples (set exceeds the limit)",
                    "limit": limit, "probed": len(chosen), "totalTuples": len(ordered),
                    "coveredSystems": sum(tuples[key] for key in chosen),
                    "totalSystems": sum(tuples.values())}


def oracle_verdict(entry):
    """The pinned oracle's own admission test for a tuple."""
    try:
        FastKwpEntry.from_entry(entry)
    except ValueError as error:
        return {"status": "not_replayable", "reason": str(error)}
    return {"status": "projected_transport_class"}


def _recorder(trace, name, count):
    def callback(*args):
        trace.append({"api": name, "arguments": [int(value) for value in args[:count]]})
        return 1
    return callback


def bind_hook(native, name, callback, libraries):
    """Bind by reviewed symbol type: a function is hooked, an eight-byte cell is overwritten.

    Guessing the indirection from the name is what the sandbox forbids, so the
    declared type decides. Absent symbols are skipped and stay unbound on purpose.
    """
    bound = []
    for library in libraries:
        symbol = native.libraries[library].symbols.get(name)
        if symbol is None:
            continue
        if symbol["st_info"]["type"] == "STT_FUNC":
            native.bind(name, callback, library=library)
        elif symbol["st_info"]["type"] == "STT_OBJECT" and symbol["st_size"] == 8:
            native.bind(name, callback, indirect=True, library=library)
        else:
            continue
        bound.append(library)
    return bound


def build_probe(libraries, sources):
    """One sandbox for the whole probe; the freed/declared fakes mirror the oracle."""
    file, comm, sysp = "libTOYOTA_FILE.so", "libTOYOTA_COMM.so", "libTOYOTA_SYSP.so"
    reference = json.loads(REFERENCE.read_text())
    for name, key in ((file, "entryComparison"), (comm, "frameComparison"), (sysp, "kwpLifecycleComparison")):
        if hashlib.sha256(libraries[name]).hexdigest() != reference[key]["librarySha256"]:
            raise ValueError("Probe needs the exact pinned library: " + name)
    native = NativeSandbox({name: libraries[name] for name in (file, comm, sysp)})
    files = MemoryFiles(native)
    files.bind(file)
    handle = files.register(sources["SYSTEMENTER.BIN"])
    context = native.allocate(0x20000)
    global_state = native.allocate(0x280e1d0)
    for name in (comm, sysp):
        native.write(native.symbol(name, "g_MToken"), struct.pack("<Q", global_state))
    for name, functions in {
            comm: ("OpenAbstractCommDll", "OpenDiagFuncDll", "OpenShowInterfaceDLL"),
            sysp: ("OpenDiagCommDll", "OpenAbstractCommDll", "OpenShowInterfaceDLL")}.items():
        for function in functions:
            native.bind(function, lambda *_: 0, library=name)
    for function in ("InitSetEnterFrame", "InitCommSet", "GetFuncCommFrameInfo",
                     "MultiPackFunc", "MultSetLinkKeepFunc", "CmdSendWithoutRec"):
        native.link_function_pointer(sysp, function, comm, function)
    linked, unlinked = {}, {}
    for cell in CAN_CELLS:
        try:
            native.link_function_pointer(sysp, cell, comm, cell)
        except ReplayError as error:
            unlinked[cell] = str(error)
            continue
        linked[cell] = "SYSP cell -> COMM function"
    trace = []
    bound = {}
    for hook, count in (*HOOKS, ("EndCombination", 0), ("SetLinkKeep", 4)):
        libraries = [name for name in (comm, sysp) if hook in native.libraries[name].symbols]
        owners = bind_hook(native, hook, _recorder(trace, hook, count), libraries)
        if owners:
            bound[hook] = owners
    for region_id, region in enumerate(("EU", "NA", "JP"), 1):
        for offset, filename in ((0, "Toyota_bin_00.BIN"), (0x38, "Toyota_bin_11.BIN"), (0x40, "Toyota_bin_12.BIN")):
            raw = sources[region + "/" + filename]
            native.write(global_state + 0xc0 + offset, struct.pack("<Q", native.allocate(len(raw), raw)))
    return native, files, handle, context, global_state, trace, bound, linked, unlinked


def probe_tuple(native, files, handle, context, global_state, trace, index, rid, function, entry):
    """Run the native system-entry dispatch for one exact tuple; record, never infer."""
    row = index.regions.get(rid, {}).get(function)
    if row is None:
        return {"status": "blocked", "reason": "no exact SYSTEMENTER region/function directory row"}
    native.write(context, bytes(0x20000))
    if native.call("libTOYOTA_FILE.so", "GetInfoFromEnterList", handle, row["listOffset"],
                   entry.descriptor, context) != 0:
        return {"status": "blocked", "reason": "native FILE entry reader rejected the exact selector"}
    native.write(context, struct.pack("<I", function))
    native.write(global_state + 0x9e, b"\x10")
    trace.clear()
    try:
        status = signed16(native.call("libTOYOTA_SYSP.so", "SystemEnterPart", function, context))
    except ReplayError as error:
        return {"status": "blocked", "reason": str(error), "calls": list(trace)}
    return {"status": "traced", "nativeStatus": status, "calls": list(trace)}


def setup_calls(calls):
    """Only the transport-hook calls, i.e. the adapter-facing configuration surface."""
    return [call for call in calls if call["api"] in {hook for hook, _ in HOOKS} | {"EndCombination"}]


def address_evidence(entry, calls):
    """Where an address-like value appears: source fields and recorded call arguments.

    Both byte orders are recorded because the source stores little-endian words and
    the wire order is not asserted here. A match is reported as an observation only:
    it does not name the field a request or response identifier.
    """
    fields = dict(entry.fields)
    words = {}
    for field, value in fields.items():
        raw = bytes.fromhex(value)
        if len(raw) in (2, 4):
            words[field] = {"littleEndian": int.from_bytes(raw, "little"),
                            "bigEndian": int.from_bytes(raw, "big")}
    matches = []
    for call in calls:
        for argument in call["arguments"]:
            for field, value in words.items():
                for order, candidate in value.items():
                    if argument == candidate:
                        matches.append({"api": call["api"], "argument": argument,
                                        "sourceField": field, "byteOrder": order})
    return {"sourceFields": fields, "callArgumentEqualsSourceField": matches}


def classify(entry, oracle, native):
    """matched / mismatched need a source expectation; the transport-1/5 class is the only one."""
    if oracle["status"] == "projected_transport_class":
        return {"verdict": "mismatched", "reason": "a transport-3/4 entry unexpectedly passed the pinned 1/5 projection"}
    if native["status"] == "blocked":
        return {"verdict": "not_replayable", "reason": f"{oracle['reason']}; native setup stopped at: {native['reason']}"}
    return {"verdict": "not_replayable",
            "reason": f"{oracle['reason']}; native setup ran but no source-derived call expectation exists for this class"}


def build(args):
    started = time.monotonic()
    sources = load_sources(Path(args.archive), args.decoded, args.manifest)
    index = EntryIndex(sources["SYSTEMENTER.BIN"])
    systems, entries, unjoined = can_tuples(source_rows(sources), index)
    totals, tree = census_totals(args.packs, args.decoded) if args.packs else ({}, {})
    published = json.loads(Path(args.published_census).read_text()) if args.published_census else None
    chosen, selection = probe_selection(systems, args.limit if args.limit else PROBE_LIMIT)
    libraries = load_libraries(Path(args.archive))
    probe_error = None
    try:
        native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(libraries, sources)
    except (ReplayError, ValueError, KeyError, MemoryError) as error:
        # A sandbox limitation is reported as the literal blocker, never as a silent gap.
        probe_error = f"{type(error).__name__}: {error}"
        native, bound, linked, unlinked = None, {}, {}, {}
    rows, verdicts, stopped = [], collections.Counter(), collections.Counter()
    status_hist, call_hist = collections.Counter(), collections.Counter()
    for key in chosen:
        rid, function, selector = key
        entry = entries[key]
        item = {"region": REGION_NAMES[rid], "function": function, "selector": selector,
                "systems": systems[key], "sourceRecord": entry.source_id, "rawHex": entry.raw.hex(),
                "transport": entry.transport, "baud": entry.baud}
        oracle = oracle_verdict(entry)
        if native is None:
            native_result = {"status": "blocked", "reason": "probe unavailable: " + probe_error}
        else:
            try:
                native_result = probe_tuple(native, files, handle, context, global_state, trace,
                                            index, rid, function, entry)
            except (ReplayError, AssertionError, MemoryError) as error:
                native_result = {"status": "blocked", "reason": f"{type(error).__name__}: {error}"}
        item["oraclePath"] = oracle
        item["nativeSetup"] = native_result
        item.update(classify(entry, oracle, native_result))
        verdicts[item["verdict"]] += 1
        if native_result["status"] == "blocked":
            stopped[native_result["reason"]] += 1
        else:
            status_hist[str(native_result.get("nativeStatus"))] += 1
            calls = len(native_result.get("calls", []))
            call_hist["0" if calls == 0 else ("1-3" if calls <= 3 else "4+")] += 1
        item["transportHookCalls"] = setup_calls(native_result.get("calls", []))
        item["addressEvidence"] = address_evidence(entry, item["transportHookCalls"])
        rows.append(item)
        if time.monotonic() - started > PROBE_SECONDS:
            stopped["probe time budget exhausted"] += 1
            break
    iso = collections.Counter()
    for row in rows:
        for call in row["transportHookCalls"]:
            if call["api"] in ("SetCan15765", "CanSetEnterFrame", "InitCanBusMode", "InitExtendCanbusMode"):
                iso[call["api"]] += 1
    return {"format": "can-link-sweep-v1",
            "scope": "read-only; no system, category, pack or validator change",
            "tuples": {"count": len(entries), "systems": sum(systems.values()),
                       "byCode": {str(code): sum(systems[key] for key in entries
                                                 if entries[key].transport == code) for code in CAN_CODES},
                       "topBySystems": [{"region": REGION_NAMES[key[0]], "function": key[1], "selector": key[2],
                                         "systems": systems[key], "transport": entries[key].transport}
                                        for key in sorted(systems, key=lambda k: (-systems[k], k))[:20]],
                       "unjoinedSystemListRows": dict(unjoined)},
            "census": {"totals": totals, "tree": tree,
                       "reconciliation": reconciliation(sum(systems.values()), len(entries), totals, published)
                       if totals else {"agrees": None, "reason": "census inputs not supplied"}},
            "probe": {"selection": selection, "verdicts": dict(verdicts), "stopReasons": dict(stopped),
                      "isoTpConfigurationCalls": dict(iso), "boundHooks": bound,
                      "unavailableReason": probe_error,
                      "linkedCells": linked, "unlinkedCells": unlinked,
                      "nativeStatus": dict(status_hist), "callCounts": dict(call_hist),
                      "fakeBoundaries": ["adapter-facing COMM/SYSP hooks record arguments and return 1",
                                         "no CAN frame, timing or identifier is synthesized or inferred",
                                         "unbound calls stop the probe with the literal symbol name"]},
            "tuplesProbed": rows,
            "wallSeconds": round(time.monotonic() - started, 3)}


def markdown(payload):
    lines = ["# Native codes 3/4 (CAN) link sweep", "",
             f"Tuple definition `(region, function, selector)` — the same exact join as the Code 1/5 sweep. "
             f"Tuples: **{payload['tuples']['count']}**; systems: **{payload['tuples']['systems']}** "
             f"({payload['tuples']['byCode']}).", ""]
    lines += ["## Systems per tuple (top 20)", "", "| region | function | selector | transport | systems |", "|---|---:|---:|---:|---:|"]
    lines += [f"| {row['region']} | {row['function']} | {row['selector']:#04x} | {row['transport']} | {row['systems']} |"
              for row in payload["tuples"]["topBySystems"]]
    census_row = payload["census"]
    lines += ["", "## Census reconciliation", "",
              f"- tuple-side systems: {census_row['reconciliation'].get('tupleSystems')}",
              f"- census systems (codes 3/4): {census_row['reconciliation'].get('censusSystems')}",
              f"- agrees: {census_row['reconciliation'].get('agrees')}"]
    if "publishedCensusSystems" in census_row["reconciliation"]:
        lines.append(f"- published pack-census.json for codes 3/4: "
                     f"{census_row['reconciliation']['publishedCensusSystems']}, "
                     f"agrees: {census_row['reconciliation']['publishedCensusAgrees']}")
    for code, total in sorted(census_row.get("totals", {}).items()):
        lines.append(f"- native code {code}: {total['systems']} systems in {len(total['classes'])} census classes")
    class_rows = [(code, row) for code, total in sorted(census_row.get("totals", {}).items())
                  for row in total["classes"]]
    if class_rows:
        lines += ["", "| census class region | transportLabel | native code | selector | systems | enabled |",
                  "|---|---|---:|---:|---:|---:|"]
        lines += [f"| {row['region']} | {row['transportLabel']} | {code} | {row['selector']} | "
                  f"{row['systems']} | {row['enabled']} |" for code, row in class_rows]
    lines += ["", "## Native setup probe", "",
              f"- selection: {payload['probe']['selection']['policy']} "
              f"({payload['probe']['selection']['probed']}/{payload['probe']['selection']['totalTuples']} tuples)",
              f"- verdicts: {payload['probe']['verdicts']}",
              f"- ISO-TP configuration calls seen: {payload['probe']['isoTpConfigurationCalls'] or 'none'}",
              f"- SYSP cells joined to their COMM function: {len(payload['probe'].get('linkedCells') or {})}; "
              f"left unlinked: {len(payload['probe'].get('unlinkedCells') or {})}",
              f"- native entry return values: {payload['probe'].get('nativeStatus') or 'none'}",
              f"- recorded call counts per tuple: {payload['probe'].get('callCounts') or 'none'}", ""]
    lines += ["| region | function | selector | systems | verdict | stop reason |", "|---|---:|---:|---:|---|---|"]
    for row in payload["tuplesProbed"][:20]:
        native = row["nativeSetup"]
        reason = native.get("reason") or f"status={native.get('nativeStatus')}, calls={len(native.get('calls', []))}"
        reason = reason.replace("|", "/")[:110]
        lines.append(f"| {row['region']} | {row['function']} | {row['selector']:#04x} | {row['systems']} | {row['verdict']} | {reason} |")
    if payload["probe"].get("unlinkedCells"):
        lines += ["", "### Cells left unlinked (literal)", ""]
        lines += [f"- `{name}`: {reason}" for name, reason in sorted(payload["probe"]["unlinkedCells"].items())]
    if payload["probe"]["stopReasons"]:
        lines += ["", "### Stop reasons (literal)", ""]
        lines += [f"- `{reason}`: {count} tuple(s)" for reason, count in
                  collections.Counter(payload["probe"]["stopReasons"]).most_common(20)]
    lines += ["", "Nothing is enabled: no system, category, pack, validator or APK change is produced by this sweep.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive")
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--packs", type=Path)
    parser.add_argument("--published-census", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=PROBE_LIMIT)
    args = parser.parse_args()
    payload = build(args)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "can-link-sweep.json").write_bytes(canonical(payload) + b"\n")
    (args.output / "can-link-sweep.md").write_text(markdown(payload))
    print(markdown(payload))


if __name__ == "__main__":
    main()
