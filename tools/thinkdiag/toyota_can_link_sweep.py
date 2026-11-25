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
import traceback
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_MEM

from convert_vehicle_packs import REGIONS, load_sources, system_lists
from native_replay import XREGS, NativeSandbox, ReplayError
from unicorn import UC_HOOK_MEM_FETCH_UNMAPPED
from unicorn.arm64_const import UC_ARM64_REG_PC
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

# CAN configuration hooks whose stand-in return value the exploratory variant changes.
CAN_CONFIG_HOOKS = ("CanSetParameter", "CanSetParameterNormal", "CanSetEnterFrame", "SetCan15765",
                    "SetCanBitRate", "SetCanFilterId", "SetCanComPara", "SetCanComParaFilterId",
                    "InitCanBusMode", "InitExtendCanbusMode", "InitCanCommSet_MPT")

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


POINTER_WINDOW = 64


def _mapped_window(native, value):
    """Bytes behind an argument only when the argument addresses emulated memory.

    A read is attempted inside the sandbox's own mapped ranges and bounded; the
    result is recorded as observed bytes, never interpreted as an identifier.
    """
    if native is None:
        return None
    ranges = ((native.HEAP, native.HEAP + native.HEAP_BYTES),
              (native.STACK, native.STACK + native.STACK_BYTES))
    if not any(low <= value < high for low, high in ranges):
        return None
    try:
        return native.read(value, POINTER_WINDOW).hex()
    except Exception:
        return None


def _recorder(trace, name, count, native=None, result=1):
    def callback(*args):
        call = {"api": name, "arguments": [int(value) for value in args[:count]]}
        windows = {}
        for index, value in enumerate(call["arguments"]):
            window = _mapped_window(native, value)
            if window is not None:
                windows[str(index)] = window
        if windows:
            call["memoryWindow"] = windows
        trace.append(call)
        return result
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


def build_probe(libraries, sources, can_success=1):
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
        result = can_success if hook in CAN_CONFIG_HOOKS else 1
        owners = bind_hook(native, hook, _recorder(trace, hook, count, native, result), libraries)
        if owners:
            bound[hook] = owners
    for region_id, region in enumerate(("EU", "NA", "JP"), 1):
        for offset, filename in ((0, "Toyota_bin_00.BIN"), (0x38, "Toyota_bin_11.BIN"), (0x40, "Toyota_bin_12.BIN")):
            raw = sources[region + "/" + filename]
            native.write(global_state + 0xc0 + offset, struct.pack("<Q", native.allocate(len(raw), raw)))
    return native, files, handle, context, global_state, trace, bound, linked, unlinked


def _slot_index(native):
    """Address -> symbol name for every defined symbol in the loaded libraries."""
    cached = getattr(native, "_slot_index", None)
    if cached is not None:
        return cached
    index = {}
    for name, library in native.libraries.items():
        for symbol_name, symbol in library.symbols.items():
            index.setdefault(library.base + symbol["st_value"], f"{name}:{symbol_name}")
    native._slot_index = index
    return index


def _register_value(native, name):
    if not name or name[0] not in "xw":
        return None
    try:
        index = int(name[1:])
    except ValueError:
        return None
    if not 0 <= index <= 30:
        return None
    value = native.uc.reg_read(XREGS[index])
    return value & (0xFFFFFFFF if name[0] == "w" else 0xFFFFFFFFFFFFFFFF)


def _library_of(native, address):
    for name, library in native.libraries.items():
        if library.base <= address < library.base + native.MAX_LIBRARY_SPAN:
            return name, address - library.base
    return None, None


class FaultTap:
    """Snapshot the machine at an invalid fetch; the tap itself never raises.

    Reading registers after the error returns is not relied on: Unicorn reports the
    invalid fetch to this hook first, with the null target already in PC.
    """

    def __init__(self, native):
        self.native = native
        self.record = None
        native.uc.hook_add(UC_HOOK_MEM_FETCH_UNMAPPED, self._on_fetch)

    def _on_fetch(self, uc, access, address, size, value, _):
        try:
            registers = [uc.reg_read(register) for register in XREGS]
            self.record = {"fetchAddress": address, "pc": uc.reg_read(UC_ARM64_REG_PC),
                           "linkRegister": registers[30], "registers": registers}
        except Exception as error:
            self.record = {"error": f"{type(error).__name__}: {error}"}
        return False

    def take(self):
        record, self.record = self.record, None
        return record


def _code_at(native, address, after=4):
    """Instructions ending at the caller, in the longest run the mapping allows.

    A caller near the start of its page makes a long read fail, so shorter windows are
    tried; four bytes are always enough to decode the branch itself.
    """
    disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    disassembler.detail = True
    for before in (48, 36, 24, 16, 8, 4):
        start = address - before
        if start < 0:
            continue
        try:
            code = native.read(start, before + after)
        except Exception:
            continue
        return list(disassembler.disasm(code, start))
    return []


def fault_context(native, record):
    """Decode the snapshot; a decoding failure is reported as a field, never raised."""
    try:
        return _fault_context(native, record)
    except Exception as error:
        return {"decodeError": f"{type(error).__name__}: {error}",
                "linkRegister": (record or {}).get("linkRegister")}


def _fault_context(native, record):
    """Caller, branch and jump slot of a null fetch, decoded from the fault snapshot.

    Returns a dict even when only part of the picture is available; a missing field is
    reported as a missing field, never filled in.
    """
    if not record:
        return None
    context = {"pc": record.get("pc"), "fetchAddress": record.get("fetchAddress"),
               "linkRegister": record.get("linkRegister")}
    if record.get("error"):
        context["error"] = record["error"]
        return context
    registers = record.get("registers") or []
    if len(registers) < 31:
        return context
    caller = record.get("linkRegister")
    library, offset = _library_of(native, caller)
    context.update({"caller": caller, "callerLibrary": library,
                    "callerOffset": None if offset is None else hex(offset)})
    instructions = _code_at(native, caller)
    branch = next((item for item in reversed(instructions) if item.mnemonic in ("blr", "br")), None)
    if branch is None:
        return context
    operands = list(branch.operands)
    register_name = branch.reg_name(operands[0].reg) if operands else ""
    register_index = int(register_name[1:]) if register_name[:1] == "x" and register_name[1:].isdigit() else None
    context.update({"branch": f"{branch.mnemonic} {branch.op_str}", "branchAddress": hex(branch.address),
                    "branchOffsetInLibrary": (hex(branch.address - native.libraries[library].base)
                                              if library in native.libraries else None),
                    "branchRegister": register_name, "branchRegisterValue":
                        registers[register_index] if register_index is not None and register_index < 31 else None})
    for item in reversed([entry for entry in instructions if entry.address < branch.address]):
        if not item.mnemonic.startswith("ldr") or register_name not in item.op_str.split("[")[0]:
            continue
        memory = next((op for op in item.operands if op.type == ARM64_OP_MEM), None)
        if memory is None:
            continue
        base_name = item.reg_name(memory.mem.base) if memory.mem.base else ""
        base_index = int(base_name[1:]) if base_name[:1] == "x" and base_name[1:].isdigit() else None
        context["slotLoad"] = f"{item.mnemonic} {item.op_str}"
        if base_index is None or base_index >= 31:
            break
        slot = (registers[base_index] + memory.mem.disp) & 0xFFFFFFFFFFFFFFFF
        context.update({"slotAddress": hex(slot), "slotSymbol": _slot_index(native).get(slot)})
        break
    return context


def fault_cause(context):
    """Classify one stop without assuming what the register held."""
    if not context:
        return "context unavailable"
    value = context.get("branchRegisterValue")
    if value == 0:
        return ("unlinked slot: named cell holding 0" if context.get("slotSymbol")
                else "unlinked slot: unnamed address holding 0")
    if value is None:
        return "branch register unresolved"
    return "jump target outside emulated memory"


def failure_group(context):
    """A stable grouping key for the 232 fetch-at-0x0 stops."""
    if not context:
        return "context unavailable"
    return " | ".join(str(context.get(key)) for key in
                      ("callerLibrary", "callerOffset", "branch", "slotSymbol", "slotAddress"))


def window_candidates(record):
    """Byte forms of every data-row field, plus the row identity words.

    Only stored byte order and plain little/big-endian integer forms are tried; no
    transform is invented. A candidate is a label plus the bytes it would contribute.
    """
    candidates = {}
    for name, value in record["fields"].items():
        raw = bytes.fromhex(value)
        if raw:
            candidates[f"row field {name}"] = raw
    for name in ("function", "selector", "transport", "baud", "flags"):
        value = record[name]
        candidates[f"row field {name} (u8)"] = bytes([value & 0xFF])
        if value <= 0xFFFF:
            candidates[f"row field {name} (u16le)"] = struct.pack("<H", value)
            candidates[f"row field {name} (u16be)"] = struct.pack(">H", value)
        if value <= 0xFFFFFFFF:
            candidates[f"row field {name} (u32le)"] = struct.pack("<I", value)
    return candidates


def decode_windows(records):
    """Per census class: does each window byte range equal a row field, stay constant, or neither.

    A relation is reported only when it holds for every tuple of the class, and a
    candidate is used only when every tuple of the class carries that field.
    """
    classes = collections.defaultdict(list)
    for record in records:
        if record.get("window"):
            classes[(record["region"], record["transport"], record["selector"])].append(record)
    decoded = []
    for key in sorted(classes, key=lambda item: -len(classes[item])):
        group = classes[key]
        prepared = [(record, window_candidates(record)) for record in group]
        size = min(len(bytes.fromhex(record["window"])) for record in group)
        rows, offset = [], 0
        while offset < size:
            values = {record["window"][offset * 2:offset * 2 + 2] for record in group}
            if len(values) == 1:
                value = sorted(values)[0]
                end = offset
                while end + 1 < size and {record["window"][(end + 1) * 2:(end + 1) * 2 + 2]
                                          for record in group} == {value}:
                    end += 1
                rows.append({"bytes": f"{offset}-{end}", "kind": "constant", "detail": value,
                             "tuples": len(group)})
                offset = end + 1
                continue
            chosen = None
            for name, contribution in prepared[0][1].items():
                length = len(contribution)
                if offset + length > size:
                    continue
                if all(name in candidates and
                       record["window"][offset * 2:(offset + length) * 2] == candidates[name].hex()
                       for record, candidates in prepared):
                    chosen = (name, length)
                    break
            if chosen:
                rows.append({"bytes": f"{offset}-{offset + chosen[1] - 1}", "kind": "equals",
                             "detail": chosen[0], "tuples": len(group)})
                offset += chosen[1]
            else:
                rows.append({"bytes": f"{offset}", "kind": "unestablished", "detail": None,
                             "tuples": len(group)})
                offset += 1
        varying = {}
        for name in prepared[0][1]:
            if not all(name in candidates for _, candidates in prepared):
                continue
            distinct = {candidates[name] for _, candidates in prepared}
            if len(distinct) > 1 and "(" not in name:
                varying[name] = len(distinct)
        decoded.append({"region": key[0], "transport": key[1], "selector": key[2],
                        "tuples": len(group), "fields": rows, "varying": varying})
    return decoded


def exploratory_run(libraries, sources, chosen, entries, index, limit=32):
    """Re-run the native setup with the CAN hook returning success (explicit stand-in)."""
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(
        libraries, sources, can_success=0)
    FaultTap(native)
    rows = []
    for key in chosen:
        if len(rows) >= limit:
            break
        rid, function, selector = key
        entry = entries[key]
        result = probe_tuple(native, files, handle, context, global_state, trace, index, rid, function, entry)
        rows.append({"region": REGION_NAMES[rid], "function": function, "selector": selector,
                     "status": result["status"], "nativeStatus": result.get("nativeStatus"),
                     "calls": setup_calls(result.get("calls", [])),
                     "reason": result.get("reason"),
                     "faultCause": fault_cause(result.get("fault"))})
    return rows


def probe_tuple(native, files, handle, context, global_state, trace, index, rid, function, entry, tap=None):
    """Run the native system-entry dispatch for one exact tuple; record, never infer."""
    row = index.regions.get(rid, {}).get(function)
    if row is None:
        return {"status": "blocked", "reason": "no exact SYSTEMENTER region/function directory row"}
    if tap is not None:
        tap.take()
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
        record = tap.take() if tap is not None else None
        fault = fault_context(native, record) if record else None
        return {"status": "blocked", "reason": str(error), "calls": list(trace), "fault": fault,
                "faultRecord": record}
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


def memory_matches(entry, calls):
    """Source words that also appear inside a recorded parameter block, as an observation."""
    observed = []
    for call in calls:
        for index, window in (call.get("memoryWindow") or {}).items():
            raw = bytes.fromhex(window)
            for field, value in entry.fields.items():
                word = bytes.fromhex(value)
                if len(word) in (2, 4) and word in raw:
                    observed.append({"api": call["api"], "argumentIndex": int(index),
                                     "sourceField": field, "byteOrder": "as stored"})
    return observed


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
        tap = FaultTap(native)
    except (ReplayError, ValueError, KeyError, MemoryError) as error:
        # A sandbox limitation is reported as the literal blocker, never as a silent gap.
        probe_error = f"{type(error).__name__}: {error}"
        native, bound, linked, unlinked, tap = None, {}, {}, {}, None
    rows, verdicts, stopped = [], collections.Counter(), collections.Counter()
    status_hist, call_hist = collections.Counter(), collections.Counter()
    api_hist = collections.Counter(); examples = []
    memory_hist = collections.Counter()
    fault_groups = collections.Counter(); fault_contexts = {}; window_records = []; fault_errors = collections.Counter()
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
                                            index, rid, function, entry, tap)
            except Exception as error:  # one tuple can never abort the sweep
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
        item["memoryMatches"] = memory_matches(entry, item["transportHookCalls"])
        for call in item["transportHookCalls"]:
            api_hist[call["api"]] += 1
        for match in item["memoryMatches"]:
            memory_hist[f"{match['api']}[{match['argumentIndex']}]={match['sourceField']}"] += 1
        if native_result.get("faultRecord") and not native_result.get("fault"):
            fault_errors["no fault context"] += 1
        elif native_result.get("fault", {}).get("error"):
            fault_errors[native_result["fault"]["error"]] += 1
        if native_result.get("fault"):
            item["fault"] = native_result["fault"]
            key = failure_group(native_result["fault"])
            fault_contexts.setdefault(key, native_result["fault"])
            fault_groups[key] += 1
        window = next((value for call in item["transportHookCalls"]
                       if call["api"] == "CanSetParameterNormal"
                       for value in (call.get("memoryWindow") or {}).values()), None)
        if window:
            window_records.append({"region": item["region"], "transport": entry.transport,
                                   "selector": selector, "function": function, "baud": entry.baud,
                                   "flags": entry.flags, "fields": entry.fields, "window": window})
        if item["transportHookCalls"] and len(examples) < 5:
            examples.append({"region": item["region"], "function": item["function"],
                             "selector": item["selector"], "transport": item["transport"],
                             "rawHex": item["rawHex"], "sourceFields": entry.fields,
                             "calls": item["transportHookCalls"]})
        rows.append(item)
        if time.monotonic() - started > PROBE_SECONDS:
            stopped["probe time budget exhausted"] += 1
            break
    exploratory = []
    if native is not None:
        try:
            exploratory = exploratory_run(libraries, sources, chosen, entries, index, 32)
        except Exception as error:
            exploratory = [{"status": "blocked", "reason": f"{type(error).__name__}: {error}"}]
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
                      "transportHookCallHistogram": dict(api_hist.most_common()),
                      "callExamples": examples, "memoryMatches": memory_hist,
                      "faultGroups": [{"key": key, "tuples": count, "context": fault_contexts[key],
                                       "cause": fault_cause(fault_contexts[key])}
                                      for key, count in fault_groups.most_common()],
                      "faultTuples": sum(fault_groups.values()),
                      "faultWithContext": sum(fault_groups.values()),
                      "faultAnalysisErrors": dict(fault_errors),
                      "windowDecode": decode_windows(window_records),
                      "exploratory": {
                          "label": "exploratory, hook behavior is a stand-in, not vehicle evidence",
                          "returnValue": 0, "tuples": len(exploratory), "rows": exploratory},
                      "fakeBoundaries": ["adapter-facing COMM/SYSP hooks record arguments and return 1",
                                         "no CAN frame, timing or identifier is synthesized or inferred",
                                         "unbound calls stop the probe with the literal symbol name"]},
            "tuplesProbed": rows,
            "wallSeconds": round(time.monotonic() - started, 3)}


def _source_line(token):
    """The literal line number of a marker in this file, quoted in the report."""
    source = Path(__file__).read_text().splitlines()
    return next((index + 1 for index, line in enumerate(source) if token in line), None)


def _window_text(row):
    if row["kind"] == "constant":
        return f"constant 0x{row['detail']} ({row['tuples']}/{row['tuples']})"
    if row["kind"] == "equals":
        return f"equals {row['detail']} ({row['tuples']}/{row['tuples']})"
    return "NOT ESTABLISHED"


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
    probe = payload["probe"]
    fault_groups = probe.get("faultGroups") or []
    lines += ["", "## Stage 3b — failure modes and window decode", ""]
    lines += ["### Fetch-at-0x0 stops (groups)", "",
              f"Grouped tuples: {probe.get('faultTuples')} in {len(fault_groups)} distinct contexts; "
              f"counts sum to {sum(group['tuples'] for group in fault_groups)}.", ""]
    errors = probe.get("faultAnalysisErrors") or {}
    missing = sum(errors.values())
    if missing:
        lines += [f"{missing} fetch stop(s) had no decodable context: {errors}.", ""]
    if fault_groups:
        lines += ["| caller library | caller offset | branch | register value | slot symbol | slot address | cause | tuples |",
                  "|---|---:|---|---:|---|---|---:|---:|"]
        for group in fault_groups:
            context = group["context"] or {}
            lines.append("| " + " | ".join(str(value) for value in (
                context.get("callerLibrary"), context.get("callerOffset"), context.get("branch"),
                context.get("branchRegisterValue"), context.get("slotSymbol"), context.get("slotAddress"),
                group["cause"], group["tuples"])) + " |")
        unlinked = sum(group["tuples"] for group in fault_groups
                       if group["cause"].startswith("unlinked slot"))
        lines += ["", f"An unhooked import cannot appear in this table: the sandbox raises "
                      f"`External call has no experiment binding: <name>` instead, which is reported as its own "
                      f"stop reason. {unlinked} of {probe.get('faultTuples')} stop tuples jump through a register "
                      f"holding 0 via a fixed slot (unlinked slot); no group needs a data-dependent branch or a "
                      f"jump target outside emulated memory to explain it." if unlinked == probe.get("faultTuples")
                 else f"Stops split as: {[(group['cause'], group['tuples']) for group in fault_groups]}."]
    hook_line = _source_line("result = can_success if hook in CAN_CONFIG_HOOKS")
    call_line = _source_line('native.call("libTOYOTA_SYSP.so", "SystemEnterPart"')
    lines += ["", "### Where the -1 comes from", "",
              f"Hooks are bound at `toyota_can_link_sweep.py:{hook_line}` and `_recorder` returns its `result` "
              f"argument, which the baseline probe sets to 1 for every CAN hook — a hook can therefore never "
              f"produce -1. The value is the guest's own X0 return from `SystemEnterPart`, read at "
              f"`toyota_can_link_sweep.py:{call_line}` via `signed16(...)`."]
    exploratory = probe.get("exploratory") or {}
    rows = exploratory.get("rows") or []
    changed = [row for row in rows if row.get("status") == "traced" and
               [(call["api"], tuple(call["arguments"])) for call in row["calls"]] !=
               [("SelectPIN", (16, 33, 0, 6, 14)), ("SetCommunicationMaximumWaitTime", (26000,)),
                ("CanSetParameterNormal", (16, 412321049560, 0, 6))]]
    calls_after = collections.Counter(call["api"] for row in rows for call in row.get("calls", []))
    lines += ["", "### Exploratory variant", "",
              f"**{exploratory.get('label', 'exploratory')}**. CAN configuration hooks return "
              f"{exploratory.get('returnValue')} instead of 1; {exploratory.get('tuples')} tuples re-run.", "",
              f"- calls seen: {dict(calls_after) or 'none'}",
              f"- native entry return values seen: "
              f"{dict(collections.Counter(str(row.get('nativeStatus')) for row in rows))}",
              f"- tuples whose call sequence differs from the baseline three: {len(changed)}",
              f"- stop reasons in the re-run: "
              f"{dict(collections.Counter(row.get('reason') or row.get('faultCause') or row['status'] for row in rows))}"]
    for row in changed[:3]:
        lines.append(f"- {row['region']}/{row['function']}/{row['selector']:#04x}: "
                     + "; ".join(f"{call['api']}({', '.join(str(value) for value in call['arguments'])})"
                                 for call in row["calls"]))
    if rows and not changed:
        lines.append("- no further transport call appeared; the stand-in return alone does not advance the setup")
    for decoded in (probe.get("windowDecode") or []):
        lines += ["", f"### Window decode — {decoded['region']} transport {decoded['transport']} "
                      f"selector {decoded['selector']:#04x} ({decoded['tuples']} tuples)", "",
                  "| window bytes | verdict |", "|---|---|"]
        lines += [f"| {field['bytes']} | {_window_text(field)} |" for field in decoded["fields"]]
        varying = decoded.get("varying") or {}
        lines += ["", f"Row fields that differ between the {decoded['tuples']} tuples of this class: "
                      f"{varying if varying else 'none'}."]
    if payload["probe"].get("transportHookCallHistogram"):
        lines += ["", "### Recorded transport-hook calls (all probed tuples)", ""]
        lines += [f"- `{api}` x{count}" for api, count in payload["probe"]["transportHookCallHistogram"].items()]
    for example in payload["probe"].get("callExamples", [])[:2]:
        lines += ["", f"Example tuple {example['region']}/{example['function']}/{example['selector']:#04x} "
                      f"(transport {example['transport']}), exact calls:", "", "```text"]
        for call in example["calls"]:
            lines.append(f"{call['api']}({', '.join(str(value) for value in call['arguments'])})")
            for index, window in (call.get("memoryWindow") or {}).items():
                lines.append(f"  arg[{index}] -> {POINTER_WINDOW} bytes: {window}")
        lines += ["```"]
    if payload["probe"].get("memoryMatches"):
        lines += ["", "### Source words found inside a recorded parameter block", ""]
        lines += [f"- `{key}` x{count}" for key, count in payload["probe"]["memoryMatches"].most_common(10)]
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
    try:
        payload = build(args)
        text = markdown(payload)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "can-link-sweep.json").write_bytes(canonical(payload) + b"\n")
        (args.output / "can-link-sweep.md").write_text(text)
        print(text)
    except Exception:
        # The check-run publisher reads this file, so a failure is reported with its
        # literal traceback instead of only living in a job log.
        args.output.mkdir(parents=True, exist_ok=True)
        detail = traceback.format_exc()
        (args.output / "can-link-sweep.md").write_text(
            "# CAN link sweep failed\n\n```text\n" + detail[-4000:] + "\n```\n")
        raise


if __name__ == "__main__":
    main()
