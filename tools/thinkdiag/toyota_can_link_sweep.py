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
import re
import collections
import hashlib
import io
import json
import struct
import time
import traceback
import zipfile
from pathlib import Path, PurePosixPath

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG

from analyze_toyota import checked_infos
from convert_vehicle_packs import REGIONS, load_sources, system_lists
from native_replay import XREGS, NativeSandbox, ReplayError
from unicorn import UC_HOOK_CODE, UC_HOOK_MEM_FETCH_UNMAPPED
from unicorn.arm64_const import UC_ARM64_REG_NZCV, UC_ARM64_REG_PC, UC_ARM64_REG_SP
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
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


def build_probe(libraries, sources, can_success=1, hook_results=None):
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
        result = (hook_results or {}).get(hook, can_success if hook in CAN_CONFIG_HOOKS else 1)
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
    preceding = []
    for item in [entry for entry in instructions if branch.address - 32 <= entry.address < branch.address]:
        preceding.append(f"{hex(item.address - native.libraries[library].base) if library in native.libraries else hex(item.address)} {item.mnemonic} {item.op_str}".strip())
    context["preceding"] = preceding
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


def _segment_of(library, address):
    """The PT_LOAD segment of a library covering a link-time address, or None."""
    for segment in library.elf.iter_segments():
        if segment["p_type"] != "PT_LOAD":
            continue
        if segment["p_vaddr"] <= address < segment["p_vaddr"] + segment["p_memsz"]:
            return segment
    return None


def _provider(native, library_name, symbol_name, addend):
    """Which supplied object defines what a relocation would store, if anything."""
    if symbol_name:
        owners = [f"{name}:{symbol_name} ({library.symbols[symbol_name]['st_info']['type']}, "
                  f"size {library.symbols[symbol_name]['st_size']})"
                  for name, library in native.libraries.items() if symbol_name in library.symbols]
        return owners or [f"NOT ESTABLISHED: no supplied object defines {symbol_name}"]
    if addend is None:
        return ["NOT ESTABLISHED: relocation carries no addend"]
    owners = [name for name, library in native.libraries.items() if _segment_of(library, addend)]
    return [f"link-time address {addend:#x} inside {name}" for name in owners] or [
        f"NOT ESTABLISHED: link-time address {addend:#x} inside no supplied object"]


def elf_cell_report(native, address):
    """What a cell is, read from the supplied ELF: section, symbol relation, relocation.

    Reported literally: an exact symbol is not confused with a nearest one, and a
    relocation that does not cover the cell is stated as absent rather than inferred.
    """
    for name, library in native.libraries.items():
        if not library.base <= address < library.base + native.MAX_LIBRARY_SPAN:
            continue
        offset = address - library.base
        report = {"library": name, "offset": hex(offset), "address": hex(address), "sections": []}
        for section in library.elf.iter_sections():
            start = section["sh_addr"]
            if not start or not start <= offset < start + max(section["sh_size"], 1):
                continue
            item = {"name": section.name, "type": str(section["sh_type"]),
                    "offsetInSection": hex(offset - start), "size": section["sh_size"]}
            if section["sh_type"] != "SHT_NOBITS":
                item["fileBytes"] = section.data()[offset - start:][:8].hex()
            report["sections"].append(item)
        symbols = library.symbols
        report["exactSymbols"] = [symbol_name for symbol_name, symbol in symbols.items()
                                  if symbol["st_value"] == offset]
        covering = [(symbol_name, symbol) for symbol_name, symbol in symbols.items()
                    if symbol["st_value"] <= offset < symbol["st_value"] + max(symbol["st_size"], 1)]
        report["coveringSymbols"] = [{"name": symbol_name, "type": symbol["st_info"]["type"],
                                     "size": symbol["st_size"],
                                     "distance": hex(offset - symbol["st_value"])}
                                    for symbol_name, symbol in covering]
        preceding = [(symbol_name, symbol) for symbol_name, symbol in symbols.items()
                     if symbol["st_value"] <= offset]
        if preceding:
            symbol_name, symbol = max(preceding, key=lambda item: item[1]["st_value"])
            report["nearestSymbol"] = {"name": symbol_name, "type": symbol["st_info"]["type"],
                                       "size": symbol["st_size"], "stValue": hex(symbol["st_value"]),
                                       "distance": hex(offset - symbol["st_value"])}
            if symbol["st_value"] + max(symbol["st_size"], 1) <= offset:
                report["nearestSymbol"]["note"] = "nearest only: its size does not reach the cell"
        relocations = [{"type": reloc["r_info_type"], "offset": hex(reloc["r_offset"]),
                        "addend": (reloc["r_addend"]
                                   if "r_addend" in getattr(reloc, "entry", {"r_addend": 0}) else None),
                        "symbol": relocation_symbol.name if relocation_symbol else None}
                       for reloc, relocation_symbol in library.relocations
                       if reloc["r_offset"] == offset]
        report["relocations"] = relocations
        providers = []
        for relocation in relocations:
            providers += _provider(native, name, relocation["symbol"], relocation["addend"])
        if not relocations:
            report["provider"] = ["NOT ESTABLISHED: no supplied provider - no relocation covers the cell"]
        else:
            report["provider"] = providers
        return report
    return {"library": None, "offset": None, "address": hex(address),
            "sections": [], "relocations": [],
            "provider": ["NOT ESTABLISHED: the address lies inside no supplied object"]}


def _mapping(native, address):
    """Which emulated region an address belongs to; "unmapped" is reported as such."""
    for name, library in native.libraries.items():
        if library.base <= address < library.base + native.MAX_LIBRARY_SPAN:
            return name
    for region, low, size in (("stack", native.STACK, native.STACK_BYTES),
                              ("heap", native.HEAP, native.HEAP_BYTES),
                              ("tls", native.TLS, 4096), ("shims", native.SHIMS, native.SHIM_BYTES)):
        if low <= address < low + size:
            return region
    return "unmapped"


def _register_index(name, limit):
    """Index into the captured register tuple: x0-x9, then sp."""
    if name == "sp":
        return 10 if limit > 10 else None
    if name[:1] in ("x", "w") and name[1:].isdigit():
        index = int(name[1:])
        return index if index < min(limit, 10) else None
    return None


def annotate_steps(native, steps, length=60):
    """One line per executed instruction: offset, text, flow, operand values and sources."""
    disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    disassembler.detail = True
    rows = []
    for position, (address, registers, flags) in enumerate(steps):
        try:
            instruction = next(iter(disassembler.disasm(native.read(address, 4), address)))
            text = f"{instruction.mnemonic} {instruction.op_str}".strip()
        except Exception:
            instruction, text = None, "(undecodable)"
        library, offset = _library_of(native, address)
        flow = "-"
        if position + 1 < len(steps):
            flow = "falls through" if steps[position + 1][0] == address + 4 else "TAKEN"
            if instruction is None or not text.startswith(("b", "cb", "tb")):
                flow = "next=" + ("+4" if flow == "falls through" else hex(steps[position + 1][0]))
        values = []
        if instruction is not None:
            for operand in instruction.operands:
                if operand.type == ARM64_OP_REG:
                    index = _register_index(instruction.reg_name(operand.reg), len(registers))
                    if index is not None:
                        values.append(f"{instruction.reg_name(operand.reg)}={registers[index]:#x}")
                elif operand.type == ARM64_OP_MEM:
                    index = _register_index(instruction and
                                            (instruction.reg_name(operand.mem.base) if operand.mem.base else ""),
                                            len(registers))
                    if index is not None:
                        target = (registers[index] + operand.mem.disp) & 0xFFFFFFFFFFFFFFFF
                        values.append(f"[{target:#x} {_mapping(native, target)}]")
        if native.SHIMS <= address < native.SHIMS + native.SHIM_BYTES:
            hook = getattr(native, "hooks", {}).get(address)
            if hook is not None:
                values.append(f"(shim for {hook[0]})")
        rows.append({"offset": f"{library}+{hex(offset)}" if library else hex(address),
                     "text": text, "flow": flow, "values": " ".join(values),
                     "flags": f"nzcv={flags:#x}" if text.startswith(("cmp", "tst", "b.", "cb", "tb")) else ""})
    return rows


def instruction_tail(libraries, sources, keys, entries, index, length=60):
    """The last `length` instructions before the guest returns, in two cheap passes.

    Pass one records addresses only, so the sandbox stays inside its instruction/time
    budget; pass two re-runs identically and snapshots registers at exactly those
    addresses. Determinism is what makes the pair valid, and both passes are replayed
    from the same inputs.
    """
    addresses = {}
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(libraries, sources)
    FaultTap(native)
    ring = collections.deque(maxlen=length)

    def on_address(uc, address, size, _):
        ring.append(address)

    hook = native.uc.hook_add(UC_HOOK_CODE, on_address)
    try:
        for key in keys:
            ring.clear()
            result = probe_tuple(native, files, handle, context, global_state, trace,
                                index, key[0], key[1], entries[key])
            addresses[key] = {"addresses": list(ring), "status": result["status"],
                              "nativeStatus": result.get("nativeStatus"),
                              "calls": setup_calls(result.get("calls", [])),
                              "reason": result.get("reason")}
    finally:
        native.uc.hook_del(hook)

    rows = []
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(libraries, sources)
    FaultTap(native)
    snapshots = collections.deque(maxlen=length)
    wanted = set()

    def on_snapshot(uc, address, size, _):
        if address not in wanted:
            return
        try:
            snapshots.append((address, tuple(uc.reg_read(register) for register in XREGS[:10])
                              + (uc.reg_read(UC_ARM64_REG_SP),), uc.reg_read(UC_ARM64_REG_NZCV)))
        except Exception:
            pass

    hook = native.uc.hook_add(UC_HOOK_CODE, on_snapshot)
    try:
        for key in keys:
            wanted = set(addresses[key]["addresses"])
            snapshots.clear()
            result = probe_tuple(native, files, handle, context, global_state, trace,
                                index, key[0], key[1], entries[key])
            rows.append({"region": REGION_NAMES[key[0]], "function": key[1], "selector": key[2],
                         "status": result["status"], "nativeStatus": result.get("nativeStatus"),
                         "calls": setup_calls(result.get("calls", [])),
                         "reason": result.get("reason"),
                         "reproduced": [address for address in addresses[key]["addresses"]] ==
                                       [step[0] for step in snapshots],
                         "steps": annotate_steps(native, list(snapshots))})
    finally:
        native.uc.hook_del(hook)
    return rows


def trace_selection(rows, count=3):
    """The heaviest replayed tuple in each of the heaviest census classes, for the tail."""
    best = {}
    for row in rows:
        if row["status"] != "traced" or row.get("nativeStatus") != -1:
            continue
        key = (row["region"], row["transport"], row["selector"])
        systems_of_row = row["systems"]
        if key not in best or systems_of_row > best[key]["systems"]:
            best[key] = row
    ordered = sorted(best.values(), key=lambda row: (-row["systems"], row["region"], row["function"]))
    return [(REGION_IDS[row["region"]], row["function"], row["selector"]) for row in ordered[:count]]


PROVIDER = re.compile(r"(?P<library>[A-Za-z0-9_.-]+\.so):(?P<symbol>\S+) \((?P<type>STT_[A-Z]+), size (?P<size>\d+)\)")


def provider_symbol(cell):
    """The supplied function a relocation names, when the ELF analysis found one."""
    for entry in (cell or {}).get("provider") or []:
        match = PROVIDER.fullmatch(entry)
        if match and match.group("type") == "STT_FUNC" and int(match.group("size")) >= 4:
            return match.group("library"), match.group("symbol")
    return None


def link_exploratory(libraries, sources, cell, keys, entries, index, limit=32):
    """Exploratory only: store a supplied function into the null cell and re-run.

    Runs only when the ELF names a supplied provider. It is a stand-in measurement of
    what the guest would do with the slot filled; it is not vehicle evidence.
    """
    provider = provider_symbol(cell)
    if provider is None:
        return {"label": "exploratory, not vehicle evidence", "skipped":
                "no supplied provider fills the cell: " + "; ".join((cell or {}).get("provider") or
                                                                    ["no ELF analysis available"])}
    library, symbol = provider
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(libraries, sources)
    FaultTap(native)
    slot = int(cell["address"], 16)
    target = native.symbol(library, symbol)
    native.write(slot, struct.pack("<Q", target))
    rows = []
    for key in keys[:limit]:
        rid, function, selector = key
        result = probe_tuple(native, files, handle, context, global_state, trace, index, rid, function, entries[key])
        rows.append({"region": REGION_NAMES[rid], "function": function, "selector": selector,
                     "status": result["status"], "nativeStatus": result.get("nativeStatus"),
                     "calls": setup_calls(result.get("calls", [])), "reason": result.get("reason")})
    return {"label": "exploratory, not vehicle evidence", "linked": f"{library}:{symbol}",
            "cell": cell["address"], "tuples": len(rows), "rows": rows}


SHIPPED_LIMIT = 64 * 1024 * 1024
SCAN_CAP = 4_000_000
SETTER_LIKE = re.compile(r"(?i)(register|set|init|callback)")
CELL_WINDOW = 0x40


def shipped_libraries(path):
    """Every shared object the archive ships, read for static search only."""
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        for info in checked_infos(archive):
            name = PurePosixPath(info.filename).name
            if name.endswith(".so") and info.file_size <= SHIPPED_LIMIT:
                libraries[name] = archive.read(info)
    return libraries


def _elf_tables(data):
    """(defined symbols by name, relocations) of one shared object."""
    elf = ELFFile(io.BytesIO(data))
    tables = [elf.get_section_by_name(".dynsym")]
    if elf.get_section_by_name(".symtab") is not None:
        tables.append(elf.get_section_by_name(".symtab"))
    symbols = {}
    for table in tables:
        if table is None:
            continue
        for symbol in table.iter_symbols():
            if symbol.name and symbol["st_shndx"] != "SHN_UNDEF":
                symbols.setdefault(symbol["name"], symbol)
    relocations = []
    sections = list(elf.iter_sections())
    for section in sections:
        if not isinstance(section, RelocationSection):
            continue
        try:
            table = elf.get_section(section["sh_link"])
        except Exception:
            continue
        if table is None:
            continue
        for reloc in section.iter_relocations():
            symbol = table.get_symbol(reloc["r_info_sym"]) if reloc["r_info_sym"] else None
            relocations.append((reloc, symbol))
    return elf, symbols, relocations


def _undefined_names(elf):
    names = set()
    for table_name in (".dynsym", ".symtab"):
        table = elf.get_section_by_name(table_name)
        if table is None:
            continue
        for symbol in table.iter_symbols():
            if symbol.name and symbol["st_shndx"] == "SHN_UNDEF":
                names.add(symbol.name)
    return names


def _executable_sections(elf):
    for section in elf.iter_sections():
        if (section["sh_type"] == "SHT_PROGBITS" and section["sh_flags"] & 0x4
                and section["sh_size"] and section["sh_addr"]):
            yield section


def _function_starts(symbols):
    starts = {}
    for name, symbol in symbols.items():
        if symbol["st_info"]["type"] == "STT_FUNC":
            starts.setdefault(symbol["st_value"], name)
    return starts


def _nearest_function(starts, offset):
    names = [start for start in starts if start <= offset]
    return starts[max(names)] if names else None


def _state_key(instruction, register):
    """wN and xN are one register; everything else is keyed by its own name."""
    name = instruction.reg_name(register)
    if name[:1] in ("w", "x") and name[1:].isdigit():
        return name[1:]
    return name


def store_hits(instructions, cell_target, holders, starts, window=CELL_WINDOW,
               section_name="", cap=None, same_object=True):
    """Stores to the cell, or to the window around it, provable from local constants.

    Two address spaces are kept apart: an `adrp`/`add` chain names an address inside the
    object being scanned (only the cell's own object can reach the cell that way), while
    a load from a relocation slot makes the cell's address reachable from any object.
    Anything else is left unreported rather than guessed, and the state resets at every
    function start.
    """
    hits = []
    state = {}
    scanned = 0
    for instruction in instructions:
        scanned += 1
        if cap is not None and scanned > cap:
            return hits, scanned, True
        if instruction.address in starts:
            state = {}
        operands = list(instruction.operands)
        mnemonic = instruction.mnemonic
        if mnemonic == "adrp" and len(operands) == 2:
            state[_state_key(instruction, operands[0].reg)] = {"addr": ("abs", operands[1].imm),
                                                               "value": None}
        elif mnemonic in ("add", "sub") and len(operands) == 3 and operands[2].type == ARM64_OP_IMM:
            source = state.get(_state_key(instruction, operands[1].reg), {})
            address = source.get("addr")
            if address is not None:
                delta = operands[2].imm if mnemonic == "add" else -operands[2].imm
                state[_state_key(instruction, operands[0].reg)] = {
                    "addr": (address[0], address[1] + delta), "value": None}
            else:
                state.pop(_state_key(instruction, operands[0].reg), None)
        elif mnemonic in ("mov", "movz", "movn") and len(operands) == 2:
            if operands[1].type == ARM64_OP_IMM:
                state[_state_key(instruction, operands[0].reg)] = {
                    "addr": None, "value": f"constant {operands[1].imm:#x}"}
            elif (operands[1].type == ARM64_OP_REG
                  and _state_key(instruction, operands[1].reg) in state):
                state[_state_key(instruction, operands[0].reg)] = dict(
                    state[_state_key(instruction, operands[1].reg)])
            else:
                state.pop(_state_key(instruction, operands[0].reg), None)
        elif mnemonic.startswith("ldr") or mnemonic.startswith("ldur"):
            memory = next((operand for operand in operands if operand.type == ARM64_OP_MEM), None)
            destination = next((operand for operand in operands if operand.type == ARM64_OP_REG), None)
            source = state.get(_state_key(instruction, memory.mem.base), {}) if memory is not None else {}
            address = source.get("addr")
            if memory is not None and destination is not None and address is not None:
                reachable = ((address[0], address[1] + memory.mem.disp) if address[0] == "abs" else None)
                state[_state_key(instruction, destination.reg)] = {
                    "addr": ("cell", 0) if reachable and reachable[1] in holders else None,
                    "value": f"load from {reachable[1]:#x}" if reachable else None}
            elif destination is not None:
                state.pop(_state_key(instruction, destination.reg), None)
        elif mnemonic.startswith("str") or mnemonic.startswith("stur"):
            memory = next((operand for operand in operands if operand.type == ARM64_OP_MEM), None)
            if memory is None:
                continue
            source = state.get(_state_key(instruction, memory.mem.base), {})
            address = source.get("addr")
            if address is None:
                continue
            kind = None
            if address[0] == "abs" and same_object:
                displaced = address[1] + memory.mem.disp - cell_target
                kind = ("store to the cell" if displaced == 0
                        else f"store at cell+{displaced:#x} (within ±{window:#x})")
                if not 0 <= displaced < window:
                    kind = None
            elif address[0] == "cell":
                displaced = address[1] + memory.mem.disp
                kind = ("store to the cell" if displaced == 0
                        else f"store at cell+{displaced:#x} (within ±{window:#x})")
                if kind and not 0 <= displaced < window:
                    kind = None
            if kind is None:
                continue
            stored = next((operand for operand in operands if operand.type == ARM64_OP_REG), None)
            hits.append({"section": section_name, "offset": hex(instruction.address),
                         "function": _nearest_function(starts, instruction.address),
                         "instruction": f"{instruction.mnemonic} {instruction.op_str}".strip(),
                         "kind": kind,
                         "storedValue": (state.get(_state_key(instruction, stored.reg), {}).get("value")
                                         if stored else None)
                         or "not determined by a constant or a known load"})
    return hits, scanned, False


def scan_stores(data, target, holders, window=CELL_WINDOW, cap=SCAN_CAP, same_object=True):
    """Every executable section of one object, scanned for provable stores."""
    elf, symbols, relocations = _elf_tables(data)
    starts = _function_starts(symbols)
    disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    disassembler.detail = True
    hits, scanned = [], 0
    for section in _executable_sections(elf):
        found, count, capped = store_hits(disassembler.disasm(section.data(), section["sh_addr"]),
                                          target, holders, starts, window, section.name, cap - scanned,
                                          same_object)
        hits += found
        scanned += count
        if capped:
            return {"hits": hits, "scanned": scanned, "capped": True}
    return {"hits": hits, "scanned": scanned, "capped": False}


def static_cell_search(path, cell_library="libTOYOTA_COMM.so", cell_offset=0x6ec88):
    """Who writes the cell: direct stores, address-taken slots, and setter-like exports."""
    libraries = shipped_libraries(path)
    if cell_library not in libraries:
        return {"libraries": sorted(libraries), "error": f"{cell_library} is not shipped"}
    target = cell_offset
    # Slot vaddrs that hold the cell's address, per object; plus per-object errors.
    errors, jni, exports, holders = {}, {}, {}, {}
    for name, data in libraries.items():
        try:
            elf, symbols, relocations = _elf_tables(data)
            slots = set()
            for reloc, symbol in relocations:
                if symbol is not None and symbol.name == "CheckXOR":
                    slots.add(reloc["r_offset"])
                elif (name == cell_library and "r_addend" in getattr(reloc, "entry", {})
                      and reloc["r_addend"] == cell_offset):
                    slots.add(reloc["r_offset"])
            holders[name] = slots
            undefined = _undefined_names(elf)
            jni[name] = {"definesJNI_OnLoad": "JNI_OnLoad" in symbols,
                         "importsRegisterNatives": "RegisterNatives" in undefined}
            names = sorted(symbol_name for symbol_name, symbol in symbols.items()
                           if symbol["st_info"]["type"] == "STT_FUNC" and SETTER_LIKE.search(symbol_name))
            exports[name] = {"count": len(names), "sample": names[:20],
                             "xorLike": [name for name in names if "xor" in name.lower()]}
        except Exception as error:
            errors[name] = f"{type(error).__name__}: {error}"
    hits, scanned, capped = [], 0, False
    for name, data in libraries.items():
        holders_of_this_library = holders.get(name, set())
        if name != cell_library and not holders_of_this_library:
            continue
        try:
            result = scan_stores(data, target, holders_of_this_library,
                                 same_object=name == cell_library)
        except Exception as error:
            errors[name] = f"{type(error).__name__}: {error}"
            continue
        scanned += result["scanned"]
        capped = capped or result["capped"]
        for hit in result["hits"]:
            hits.append(dict(hit, library=name))
    return {"libraries": sorted(libraries), "cell": f"{cell_library}+{cell_offset:#x}",
            "holders": {name: sorted(slots) and [hex(slot) for slot in sorted(slots)]
                        for name, slots in holders.items() if slots},
            "hits": hits, "instructionsScanned": scanned, "scanCapped": capped,
            "errors": errors,
            "cap": SCAN_CAP, "jni": jni, "setterLike": exports,
            "window": f"±{CELL_WINDOW:#x}", "regex": SETTER_LIKE.pattern}


def full_sweep(libraries, sources, chosen, entries, index, hook_results, label):
    """Re-run every tuple with one hook's return changed; group what happened instead.

    Exploratory by construction: the return value is a stand-in, so nothing here is
    vehicle evidence. Every tuple's own row is kept in the JSON payload.
    """
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(
        libraries, sources, hook_results=hook_results)
    FaultTap(native)
    rows = []
    for key in chosen:
        rid, function, selector = key
        result = probe_tuple(native, files, handle, context, global_state, trace,
                            index, rid, function, entries[key])
        calls = [{"api": call["api"], "arguments": call["arguments"]}
                 for call in setup_calls(result.get("calls", []))]
        positions = [position for position, call in enumerate(calls)
                     if call["api"] == "CanSetParameterNormal"]
        after = calls[positions[-1] + 1:] if positions else []
        stop = result.get("reason") or f"returned {result.get('nativeStatus')}"
        rows.append({"region": REGION_NAMES[rid], "function": function, "selector": selector,
                     "status": result["status"], "nativeStatus": result.get("nativeStatus"),
                     "stop": stop, "afterCanSetParameterNormal": after})
    stops = collections.Counter(row["stop"] for row in rows)
    after_signatures = collections.Counter(
        "; ".join(f"{call['api']}({', '.join(str(value) for value in call['arguments'])})"
                  for call in row["afterCanSetParameterNormal"])
        for row in rows)
    return {"label": label, "tuples": len(rows), "hookResults": dict(hook_results),
            "statuses": dict(collections.Counter(row["status"] for row in rows)),
            "nativeStatus": dict(collections.Counter(str(row["nativeStatus"]) for row in rows)),
            "stops": dict(stops.most_common()), "stopTotal": sum(stops.values()),
            "afterSignatures": dict(after_signatures.most_common()),
            "rows": rows}


def call_writer(libraries, sources, hit, keys, entries, index, limit=32):
    """Exploratory: call the supplied function that writes the cell, then re-run stops."""
    native, files, handle, context, global_state, trace, bound, linked, unlinked = build_probe(libraries, sources)
    FaultTap(native)
    called, error = False, None
    try:
        native.call(hit["library"], hit["function"], *([0] * 8))
        called = True
    except ReplayError as failure:
        error = str(failure)
    rows = []
    for key in keys[:limit]:
        rid, function, selector = key
        result = probe_tuple(native, files, handle, context, global_state, trace,
                            index, rid, function, entries[key])
        rows.append({"region": REGION_NAMES[rid], "function": function, "selector": selector,
                     "status": result["status"], "nativeStatus": result.get("nativeStatus"),
                     "calls": setup_calls(result.get("calls", [])), "reason": result.get("reason")})
    return {"label": "exploratory, not vehicle evidence",
            "called": f"{hit['library']}:{hit['function']} with eight zero words",
            "callSucceeded": called, "callError": error, "tuples": len(rows), "rows": rows}


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
    slot = next((context["slotAddress"] for context in fault_contexts.values()
                 if context.get("slotAddress")), None)
    cell = elf_cell_report(native, int(slot, 16)) if native is not None and slot else None
    link = {"label": "exploratory, not vehicle evidence",
            "skipped": ("no supplied provider fills the cell: " + "; ".join(cell["provider"])
                        if cell else "no ELF analysis of the null slot was available")}
    if native is not None and cell and provider_symbol(cell):
        stopped_keys = [key for key, row in zip(chosen, rows)
                        if (row["nativeSetup"].get("fault") or {}).get("slotAddress") == cell["address"]]
        try:
            link = link_exploratory(libraries, sources, cell, stopped_keys, entries, index)
        except Exception as error:
            link = {"label": "exploratory, not vehicle evidence",
                    "skipped": f"{type(error).__name__}: {error}"}
    tails = []
    if native is not None:
        candidates = [{"region": REGION_NAMES[key[0]], "transport": entries[key].transport,
                       "selector": key[2], "function": key[1], "systems": systems[key],
                       "status": row["nativeSetup"]["status"],
                       "nativeStatus": row["nativeSetup"].get("nativeStatus")}
                      for key, row in zip(chosen, rows)]
        try:
            tails = instruction_tail(libraries, sources, trace_selection(candidates), entries, index)
        except Exception as error:
            tails = [{"status": "blocked", "reason": f"{type(error).__name__}: {error}"}]
    writers, stage3d_sweep, writer_call = None, None, None
    if native is not None:
        try:
            writers = static_cell_search(Path(args.archive))
        except Exception as error:
            writers = {"error": f"{type(error).__name__}: {error}", "hits": []}
        try:
            stage3d_sweep = full_sweep(libraries, sources, chosen, entries, index,
                                       {"CanSetParameterNormal": 0},
                                       "exploratory, hook behavior is a stand-in, not vehicle evidence")
        except Exception as error:
            stage3d_sweep = {"label": "exploratory, hook behavior is a stand-in, not vehicle evidence",
                             "error": f"{type(error).__name__}: {error}", "rows": []}
        writable = [hit for hit in (writers or {}).get("hits", [])
                    if (hit.get("storedValue") or "").startswith("constant") and hit.get("function")]
        if writable:
            try:
                stopped_keys = [key for key, row in zip(chosen, rows)
                                if row["nativeSetup"].get("fault") or row["nativeSetup"].get("status") == "traced"]
                writer_call = call_writer(libraries, sources, writable[0], stopped_keys, entries, index)
            except Exception as error:
                writer_call = {"label": "exploratory, not vehicle evidence",
                               "callError": f"{type(error).__name__}: {error}"}
        else:
            writer_call = {"label": "exploratory, not vehicle evidence",
                           "skipped": ("no supplied function stores a constant or a known load into "
                                       "the cell, so there is nothing to call first")}
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
                      "stage3c": {"cell": cell, "tails": tails, "link": link},
                      "stage3d": {"writers": writers, "sweep": stage3d_sweep,
                                  "writerCall": writer_call},
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
    stage3c = probe.get("stage3c")
    stage3d = (stage3c or {}).get("stage3d") or probe.get("stage3d") or {}
    cell = (stage3c or {}).get("cell")
    if stage3c:
        lines += ["", "## Stage 3c — the cell behind the null slot, and the -1 decision", ""]
        if cell:
            lines += ["### The cell, from the supplied ELF", "",
                      f"Address `{cell['address']}` = `{cell['library']}+{cell['offset']}`.", ""]
            for section in cell["sections"]:
                lines.append(f"- section: `{section['name']}` ({section['type']}), offset in section "
                             f"`{section['offsetInSection']}`, size {section['size']}, "
                             f"file bytes: `{section.get('fileBytes', 'none (SHT_NOBITS)')}`")
            if not cell["sections"]:
                lines.append("- section: no section covers the cell")
            lines.append(f"- exact symbol at the cell: {cell['exactSymbols'] or 'none'}")
            lines.append(f"- covering symbols: {cell['coveringSymbols'] or 'none'}")
            nearest = cell.get("nearestSymbol")
            if nearest:
                lines.append(f"- nearest preceding symbol: `{nearest['name']}` ({nearest['type']}, "
                             f"size {nearest['size']}) at `{nearest['stValue']}`, {nearest['distance']} "
                             f"before the cell" + (f"; {nearest['note']}" if nearest.get("note") else ""))
            else:
                lines.append("- nearest preceding symbol: none")
            lines.append(f"- relocations with `r_offset` at the cell: {cell['relocations'] or 'none'}")
            lines.append(f"- who fills it: {'; '.join(cell['provider'])}")
            faulted = (probe.get("faultGroups") or [{}])[0].get("context") or {}
            if faulted.get("preceding"):
                lines += ["", "Instructions before the `blr` (offsets inside the caller's object):", "",
                          "```text"]
                lines += [f"  {text}" for text in
                          faulted["preceding"] + [f"{faulted.get('callerOffset')} {faulted.get('branch')}"]]
                lines += ["```"]
        link = stage3c.get("link") or {}
        lines += ["", "### Exploratory slot link — **exploratory, not vehicle evidence**", ""]
        if not link.get("linked"):
            lines += [f"Step 3 skipped on literal evidence: "
                      f"{link.get('skipped') or 'no link run recorded in this report'}"]
        else:
            calls = collections.Counter(call["api"] for row in link.get("rows", []) for call in row["calls"])
            lines += [f"Linked the cell `{link['cell']}` to `{link['linked']}` in a separate sandbox; "
                      f"{link['tuples']} stop tuples re-run.", "",
                      f"- calls seen: {dict(calls) or 'none'}",
                      f"- statuses: {dict(collections.Counter(str(row['status']) for row in link.get('rows', [])))}"]
            for row in link.get("rows", []):
                if len(row["calls"]) > 3:
                    lines.append(f"- {row['region']}/{row['function']}/{row['selector']:#04x}: "
                                 + "; ".join(f"{call['api']}({', '.join(str(value) for value in call['arguments'])})"
                                             for call in row["calls"]))
        writers = stage3d.get("writers") or {}
        lines += ["", "## Stage 3d — who fills the cell, and what the CAN path does on a zero return", ""]
        lines += ["### Static search for a writer (all shipped objects)", ""]
        if writers.get("error"):
            lines += [f"Static search unavailable: {writers['error']}"]
        else:
            lines += [f"Objects scanned: {', '.join(writers['libraries'])}; cell `{writers['cell']}`; "
                      f"window {writers['window']}; {writers['instructionsScanned']} instructions scanned "
                      f"(cap {writers['cap']}, capped: {writers['scanCapped']}).", "",
                      f"Address-taken slots holding the cell address: {writers['holders'] or 'none'}.", ""]
            if writers["hits"]:
                lines += ["| object | offset | function | instruction | kind | stored value |",
                          "|---|---:|---|---|---|---|"]
                for hit in writers["hits"][:40]:
                    lines.append(f"| {hit['library']} | {hit['offset']} | {hit['function']} | "
                                 f"`{hit['instruction']}` | {hit['kind']} | {hit['storedValue']} |")
            elif writers.get("errors"):
                lines += [f"Search incomplete for {sorted(writers['errors'])}: {writers['errors']}"]
            if not writers["hits"]:
                lines += ["**NOT ESTABLISHED: no static writer** — no instruction in any shipped object "
                          "provably stores to the cell or its window, and no relocation makes another "
                          "object reference it."]
        if writers.get("jni"):
            lines += ["", "| object | defines JNI_OnLoad | imports RegisterNatives | setter-like exports | of those, xor-named |",
                      "|---|---|---|---:|---|"]
            for name, flags in writers["jni"].items():
                exports = writers["setterLike"][name]
                lines.append(f"| {name} | {flags['definesJNI_OnLoad']} | {flags['importsRegisterNatives']} | "
                             f"{exports['count']} | {exports['xorLike'] or 'none'} |")
            lines += ["", f"Setter-like check: exported functions matching `{writers['regex']}`; "
                      "samples per object are in the JSON payload."]
        sweep = stage3d.get("sweep") or {}
        lines += ["", "### Full re-sweep with `CanSetParameterNormal` returning 0", "",
                  f"**{sweep.get('label')}**. Hook returns used: `{sweep.get('hookResults')}`; "
                  f"tuples re-run: {sweep.get('tuples')}.", ""]
        if sweep.get("error"):
            lines += [f"Re-sweep unavailable: {sweep['error']}"]
        else:
            lines += [f"- statuses: {sweep['statuses']}",
                      f"- native returns: {sweep['nativeStatus']}",
                      f"- stop causes ({sweep['stopTotal']} of {sweep['tuples']} tuples):", "",
                      "| stop cause | tuples |", "|---|---:|"]
            lines += [f"| {cause} | {count} |" for cause, count in sweep["stops"].items()]
            lines += ["", "| transport calls after `CanSetParameterNormal` | tuples |", "|---|---:|"]
            lines += [f"| {signature or 'none'} | {count} |"
                      for signature, count in list(sweep["afterSignatures"].items())[:10]]
        call = stage3d.get("writerCall") or {}
        lines += ["", "### Exploratory call of the writer — **exploratory, not vehicle evidence**", ""]
        if call.get("skipped"):
            lines += [f"Step 3 skipped on literal evidence: {call['skipped']}"]
        elif call.get("callError") and not call.get("called"):
            lines += [f"Step 3 could not run: {call['callError']}"]
        else:
            lines += [f"Called `{call.get('called')}`; returned without error: `{call.get('callSucceeded')}`; "
                      f"{call.get('tuples')} stop tuples re-run.",
                      f"- calls seen: {dict(collections.Counter(call_item['api'] for row in call.get('rows', []) for call_item in row['calls']))}"]
        tails = stage3c.get("tails") or []
        lines += ["", "### The last instructions before `SystemEnterPart` returns", ""]
        for tail in tails:
            if "steps" not in tail:
                lines += [f"- {tail.get('reason', 'unavailable')}", ""]
                continue
            lines += [f"**{tail['region']}/{tail['function']}/{tail['selector']:#04x}** — status "
                      f"`{tail['status']}`, native return `{tail['nativeStatus']}`, "
                      f"hook calls: {[call['api'] for call in tail['calls']] or 'none'}, "
                      f"second pass reproduced the address sequence: `{tail.get('reproduced')}`",
                      "", "```text",
                      "idx offset                  instruction                    flow          values / source"]
            for position, step in enumerate(tail["steps"]):
                lines.append(f"{position:3d} {step['offset']:>22s}  {step['text']:<30s} {step['flow']:<13s} "
                             f"{step['values']} {step['flags']}".rstrip())
            lines += ["```", ""]
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
