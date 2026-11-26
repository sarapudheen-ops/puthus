import struct
import unittest
from pathlib import Path

from toyota_can_link_sweep import (address_evidence, annotate_steps, can_tuples, classify,
                                   decode_windows, elf_cell_report, failure_group, fault_cause,
                                   fault_context, markdown, memory_matches, oracle_verdict,
                                   probe_selection, provider_symbol, reconciliation,
                                   caller_hits, static_caller_search, static_cell_search,
                                   store_hits, trace_selection, window_candidates)
from toyota_entries import EntryIndex


def entry_row(descriptor, transport, flags, baud, extra=b""):
    """A literal reader fixture for the low-nibble-2 CAN-class layout, not a definition."""
    row = bytes([descriptor, transport, flags]) + struct.pack("<I", baud)
    row += bytes.fromhex("1234" "5678" "01" "9abc")          # runtime107b0/d0/b4/c4
    row += bytes([4]) + bytes.fromhex("0a0b0c0e")            # pin pairs
    row += bytes.fromhex("3c")                               # runtime107f2
    row += (b"\x01" + extra) if extra else b"\x00"           # extraAddressFlag, optional fields
    for binding in (0x64, 0x66, 0x7f):                       # init, keepalive, exit
        row += bytes([1]) + struct.pack("<H", binding)
    return row


def entry_row_low1(descriptor, transport, flags, baud):
    """The low-nibble-1 layout (the native transport-1/5 class), for exclusion checks."""
    row = bytes([descriptor, transport, flags]) + struct.pack("<I", baud)
    row += bytes.fromhex("f013") + bytes([0x00])              # runtime107b0, runtime107e4
    row += bytes([4]) + bytes.fromhex("0a0b0c0e")             # pin pairs
    row += bytes.fromhex("053c001919")                        # runtime107f0
    for binding in (0x64, 0x66, 0x7f):
        row += bytes([1]) + struct.pack("<H", binding)
    return row


def document(lists):
    """lists: [(function, [row, ...])]; each list carries its own four-byte count header."""
    encoded = [(function, bytes([len(rows), 0, 0, 0]) + b"".join(rows)) for function, rows in lists]
    start = 6 + 2 + len(encoded) * 8
    directory = bytearray(struct.pack("<H", len(encoded)))
    for function, payload in encoded:
        directory += struct.pack("<II", function, start)
        start += len(payload)
    return bytes([1, 1]) + struct.pack("<I", 6) + directory + b"".join(payload for _, payload in encoded)


class TupleTest(unittest.TestCase):
    def setUp(self):
        self.index = EntryIndex(document([
            (61, [entry_row(0x12, 3, 0x21, 500000),
                  entry_row(0x22, 4, 0x22, 500000),
                  entry_row_low1(0x51, 5, 0x33, 10416)]),  # transport 5 stays out of the sweep
            (62, [entry_row(0x1a, 3, 0x21, 500000, extra=bytes.fromhex("07e0" "07e8" "9000"))]),
        ]))

    def test_exact_joins_are_grouped_and_non_can_transports_are_excluded(self):
        rows = [(1, 61, 0x12), (1, 61, 0x12), (1, 61, 0x22), (1, 61, 0x51), (1, 62, 0x1a),
                (1, 61, 0x99), (2, 61, 0x12)]
        systems, entries, unjoined = can_tuples(rows, self.index)
        self.assertEqual({(1, 61, 0x12): 2, (1, 61, 0x22): 1, (1, 62, 0x1a): 1}, dict(systems))
        self.assertEqual({(1, 61, 0x12), (1, 61, 0x22), (1, 62, 0x1a)}, set(entries))
        self.assertEqual(4, sum(systems.values()))
        self.assertEqual(2, sum(unjoined.values()))  # unmatched selector and wrong region
        self.assertEqual([3, 4], sorted({entries[key].transport for key in entries}))

    def test_the_pinned_oracle_class_rejects_can_tuples_literally(self):
        entry = self.index.select(1, 61, 0x12)
        verdict = oracle_verdict(entry)
        self.assertEqual("not_replayable", verdict["status"])
        self.assertIn("fast-KWP", verdict["reason"])   # literal class admission failure

    def test_verdicts_never_claim_a_match_without_a_source_expectation(self):
        entry = self.index.select(1, 61, 0x12)
        oracle = oracle_verdict(entry)
        traced = classify(entry, oracle, {"status": "traced", "nativeStatus": 0, "calls": []})
        blocked = classify(entry, oracle, {"status": "blocked", "reason": "External call has no experiment binding: X"})
        self.assertEqual("not_replayable", traced["verdict"])
        self.assertIn("no source-derived call expectation", traced["reason"])
        self.assertIn("External call has no experiment binding: X", blocked["reason"])

    def test_address_evidence_compares_recorded_arguments_with_source_fields(self):
        entry = self.index.select(1, 62, 0x1a)
        calls = [{"api": "SetCan15765", "arguments": [0x07E0, 0x07E8, 0, 0]}]  # both orders match
        evidence = address_evidence(entry, calls)
        self.assertEqual("07e0", evidence["sourceFields"]["runtime107b8"])
        self.assertIn({"api": "SetCan15765", "argument": 0x07E0, "sourceField": "runtime107b8",
                       "byteOrder": "bigEndian"}, evidence["callArgumentEqualsSourceField"])
        swapped = address_evidence(entry, [{"api": "CanSetParameter", "arguments": [0xE007, 0]}])
        self.assertIn({"api": "CanSetParameter", "argument": 0xE007, "sourceField": "runtime107b8",
                       "byteOrder": "littleEndian"}, swapped["callArgumentEqualsSourceField"])
        self.assertEqual([], address_evidence(entry, [])["callArgumentEqualsSourceField"])


class MemoryTest(unittest.TestCase):
    def test_parameter_block_words_are_reported_as_observations_only(self):
        entry = self.entry_fixture = EntryIndex(document([
            (61, [entry_row(0x1a, 3, 0x21, 500000, extra=bytes.fromhex("07e0" "07e8" "9000"))]),
        ])).select(1, 61, 0x1a)
        calls = [{"api": "CanSetParameterNormal", "arguments": [16, 0x6000000000, 0, 6],
                  "memoryWindow": {"1": "0000000000000000" + "07e0" + "07e8" + "00" * 22}}]
        matches = memory_matches(entry, calls)
        self.assertIn({"api": "CanSetParameterNormal", "argumentIndex": 1,
                       "sourceField": "runtime107b8", "byteOrder": "as stored"}, matches)
        self.assertIn("runtime107bc", [match["sourceField"] for match in matches])
        self.assertEqual([], memory_matches(entry, [{"api": "X", "arguments": [1]}]))
        self.assertFalse(any("sourceField" in str(match) for match in memory_matches(entry, [])
                             if isinstance(match, str)))


def window_record(function, selector, c4, transport=3, region="EU"):
    return {"region": region, "transport": transport, "selector": selector, "function": function,
            "baud": 500000, "flags": 0x21, "fields": {"runtime107b0": "1234", "runtime107c4": c4},
            "window": "00" * 8 + c4 + "00" * 54}


class WindowTest(unittest.TestCase):
    def test_a_relation_is_reported_only_when_every_tuple_in_the_class_agrees(self):
        decoded = decode_windows([window_record(121, 0x12, "18d3"), window_record(124, 0x12, "35ae")])
        self.assertEqual(1, len(decoded))
        class_rows = {row["bytes"]: row for row in decoded[0]["fields"]}
        self.assertEqual(2, class_rows["0-7"]["tuples"])
        self.assertEqual(("constant", "00"), (class_rows["0-7"]["kind"], class_rows["0-7"]["detail"]))
        self.assertEqual(("equals", "row field runtime107c4"),
                         (class_rows["8-9"]["kind"], class_rows["8-9"]["detail"]))
        # Two tuples disagreeing on one byte and matching no field is NOT ESTABLISHED.
        broken = decode_windows([window_record(121, 0x12, "18d3"), window_record(124, 0x12, "18ff")])
        kinds = {row["bytes"]: row["kind"] for row in broken[0]["fields"]}
        self.assertIn("unestablished", kinds.values())

    def test_candidates_are_stored_field_bytes_and_plain_row_identity_words(self):
        candidates = window_candidates(window_record(121, 0x12, "18d3"))
        self.assertEqual(b"\x18\xd3", candidates["row field runtime107c4"])
        self.assertEqual(b"\x79", candidates["row field function (u8)"])
        self.assertEqual(b"\x79\x00", candidates["row field function (u16le)"])
        self.assertIn("row field baud (u32le)", candidates)

    def test_failure_grouping_key_names_caller_branch_and_slot(self):
        key = failure_group({"callerLibrary": "libTOYOTA_SYSP.so", "callerOffset": "0x109c8",
                             "branch": "blr x8", "slotSymbol": "libTOYOTA_SYSP.so:RunInterface",
                             "slotAddress": "0x1001234"})
        self.assertEqual("libTOYOTA_SYSP.so | 0x109c8 | blr x8 | libTOYOTA_SYSP.so:RunInterface | 0x1001234", key)
        self.assertEqual("context unavailable", failure_group(None))

    def test_cause_separates_named_and_unnamed_slots_and_never_claims_an_import(self):
        self.assertEqual("unlinked slot: named cell holding 0",
                         fault_cause({"branchRegisterValue": 0, "slotSymbol": "libTOYOTA_SYSP.so:X"}))
        self.assertEqual("unlinked slot: unnamed address holding 0",
                         fault_cause({"branchRegisterValue": 0, "slotSymbol": None}))
        self.assertEqual("jump target outside emulated memory", fault_cause({"branchRegisterValue": 0x40}))
        self.assertEqual("context unavailable", fault_cause(None))


class Section(dict):
    def __init__(self, name, kind, address, size, body=b""):
        super().__init__(sh_addr=address, sh_size=size, sh_type=kind)
        self.name, self.body = name, body

    def data(self):
        return self.body


class Elf:
    def __init__(self, sections):
        self.sections = sections

    def iter_sections(self):
        return iter(self.sections)


class CellTest(unittest.TestCase):
    """The cell report: section, exact versus nearest symbol, relocation, provider."""

    def native(self, symbols, relocations, sections):
        class Library:
            base = 0x10000000
            elf = Elf(sections)

        holder = type("Sandbox", (), {})()
        holder.MAX_LIBRARY_SPAN = 32 * 1024 * 1024
        holder.libraries = {"libTOYOTA_COMM.so": Library()}
        holder.libraries["libTOYOTA_COMM.so"].symbols = symbols
        holder.libraries["libTOYOTA_COMM.so"].relocations = relocations
        return holder

    def test_a_zero_filled_cell_reports_no_relocation_and_no_provider(self):
        symbols = {"CheckXOR": {"st_value": 0x6ec00, "st_size": 8, "st_info": {"type": "STT_OBJECT"}}}
        native = self.native(symbols, [], [Section(".bss", "SHT_NOBITS", 0x6e000, 0x1000)])
        report = elf_cell_report(native, 0x10000000 + 0x6ec88)
        self.assertEqual("libTOYOTA_COMM.so", report["library"])
        self.assertEqual("0x6ec88", report["offset"])
        self.assertEqual([".bss"], [section["name"] for section in report["sections"]])
        self.assertEqual([], report["exactSymbols"])
        self.assertEqual("CheckXOR", report["nearestSymbol"]["name"])
        self.assertEqual("0x88", report["nearestSymbol"]["distance"])
        self.assertIn("nearest only", report["nearestSymbol"]["note"])
        self.assertEqual([], report["relocations"])
        self.assertEqual(["NOT ESTABLISHED: no supplied provider - no relocation covers the cell"],
                         report["provider"])

    def test_a_relocation_names_the_object_that_would_fill_the_cell(self):
        class Entry(dict):
            pass

        class Reloc(dict):
            entry = Entry(r_addend=0)

        class Symbol:
            name = "SetCanComPara"

        native = self.native({}, [(Reloc(r_offset=0x6ec88, r_info_type=1025, r_addend=0), Symbol())],
                             [Section(".got", "SHT_PROGBITS", 0x6e000, 0x1000, b"\x00" * 8)])
        native.libraries["libTOYOTA_SYSP.so"] = type("Library", (), {
            "base": 0x20000000, "elf": Elf([]), "relocations": [],
            "symbols": {"SetCanComPara": {"st_size": 4, "st_info": {"type": "STT_FUNC"}}}})()
        report = elf_cell_report(native, 0x10000000 + 0x6ec88)
        self.assertEqual("SetCanComPara", report["relocations"][0]["symbol"])
        self.assertEqual(["libTOYOTA_SYSP.so:SetCanComPara (STT_FUNC, size 4)"], report["provider"])

    def test_a_function_provider_is_recognised_and_a_bare_cell_is_not(self):
        self.assertEqual(("libTOYOTA_SYSP.so", "SetCanComPara"),
                         provider_symbol({"provider": ["libTOYOTA_SYSP.so:SetCanComPara (STT_FUNC, size 4)"]}))
        self.assertIsNone(provider_symbol({"provider": [
            "NOT ESTABLISHED: no supplied provider - no relocation covers the cell"]}))
        self.assertIsNone(provider_symbol(None))

    def test_an_address_outside_every_object_is_reported_as_such(self):
        report = elf_cell_report(self.native({}, [], []), 0x1234)
        self.assertIsNone(report["library"])
        self.assertEqual(["NOT ESTABLISHED: the address lies inside no supplied object"], report["provider"])


class TailTest(unittest.TestCase):
    def native(self):
        class Sandbox:
            MAX_LIBRARY_SPAN = 32 * 1024 * 1024
            libraries = {}
            STACK, STACK_BYTES = 0x6000000000, 4096
            HEAP, HEAP_BYTES = 0x6100000000, 4096
            TLS, SHIMS, SHIM_BYTES = 0x6200000000, 0x6300000000, 4096
            hooks = {0x6300000008: ("CanSetParameterNormal", None)}

            @staticmethod
            def read(address, size):
                return struct.pack("<I", 0xD65F03C0)[:size]  # ret

        return Sandbox()

    def test_each_step_carries_offset_text_flow_and_register_values(self):
        steps = [(0x1000, tuple(range(10)), 0), (0x1008, tuple(range(10)), 0)]
        rows = annotate_steps(self.native(), steps)
        self.assertEqual("0x1000", rows[0]["offset"])
        self.assertEqual("ret", rows[0]["text"])
        self.assertEqual("next=0x1008", rows[0]["flow"])
        self.assertEqual("ret", rows[1]["text"])
        self.assertEqual("-", rows[1]["flow"])

    def test_a_shim_step_names_the_hook_that_answered(self):
        rows = annotate_steps(self.native(), [(0x6300000008, (1,) + (0,) * 10, 0)])
        self.assertIn("(shim for CanSetParameterNormal)", rows[0]["values"])

    def test_the_tail_selection_takes_the_heaviest_tuple_of_each_class(self):
        rows = [{"region": "EU", "transport": 3, "selector": 0x12, "function": 121, "systems": 4065,
                 "status": "traced", "nativeStatus": -1},
                {"region": "EU", "transport": 3, "selector": 0x12, "function": 105, "systems": 3372,
                 "status": "traced", "nativeStatus": -1},
                {"region": "EU", "transport": 4, "selector": 0x22, "function": 124, "systems": 3608,
                 "status": "traced", "nativeStatus": -1},
                {"region": "NA", "transport": 3, "selector": 0x12, "function": 9, "systems": 1,
                 "status": "blocked", "nativeStatus": None}]
        self.assertEqual([(1, 121, 0x12), (1, 124, 0x22)], trace_selection(rows, count=2))


def aarch64(words, base):
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
    disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    disassembler.detail = True
    return list(disassembler.disasm(b"".join(struct.pack("<I", word) for word in words), base))


class StaticWriterTest(unittest.TestCase):
    """Hand-encoded instructions: the scanner reports only provable stores."""

    CELL = 0x6ec88

    def test_a_direct_store_through_adrp_add_is_reported_with_its_constant(self):
        words = [0xB0000368, 0x91322108, 0x52800020, 0xF9000100]  # adrp/add/movz/str
        hits, scanned, capped = store_hits(aarch64(words, 0x1000), self.CELL, set(), {0x1000: "f"})
        self.assertEqual(4, scanned)
        self.assertFalse(capped)
        self.assertEqual(1, len(hits))
        self.assertEqual("store to the cell", hits[0]["kind"])
        self.assertEqual("0x100c", hits[0]["offset"])
        self.assertEqual("f", hits[0]["function"])
        self.assertEqual("constant 0x1", hits[0]["storedValue"])

    def test_a_store_beside_the_cell_is_reported_as_the_window_not_the_cell(self):
        words = [0xB0000368, 0x91322108, 0x52800020, 0xF9000500]  # str x0, [x8, #8]
        hits, _, _ = store_hits(aarch64(words, 0x1000), self.CELL, set(), {})
        self.assertEqual("store at cell+0x8 (within ±0x40)", hits[0]["kind"])

    def test_a_store_through_an_address_taken_slot_is_reported(self):
        words = [0xD0000369, 0x91004129, 0xF9400128, 0x52800020, 0xF9000100]
        # adrp x9,#0x6f000 ; add x9,x9,#0x10 ; ldr x8,[x9] ; movz w0,#1 ; str x0,[x8]
        hits, _, _ = store_hits(aarch64(words, 0x1000), self.CELL, {0x6f010}, {})
        self.assertEqual("store to the cell", hits[0]["kind"])
        self.assertEqual("constant 0x1", hits[0]["storedValue"])

    def test_a_direct_store_in_another_object_is_not_reported(self):
        words = [0xB0000368, 0x91322108, 0x52800020, 0xF9000100]
        hits, _, _ = store_hits(aarch64(words, 0x1000), self.CELL, set(), {}, same_object=False)
        self.assertEqual([], hits)

    def test_an_unprovable_store_is_not_reported(self):
        hits, _, _ = store_hits(aarch64([0xF9000100], 0x1000), self.CELL, set(), {})
        self.assertEqual([], hits)


class StaticSearchTest(unittest.TestCase):
    def test_a_malformed_object_is_recorded_and_does_not_abort_the_search(self):
        import tempfile, zipfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "toyota.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("x/libTOYOTA_COMM.so", b"\x7fELF" + b"\x00" * 120)
                archive.writestr("x/notes.txt", b"not a library")
            report = static_cell_search(path)
        self.assertEqual(["libTOYOTA_COMM.so"], report["libraries"])
        self.assertEqual([], report["hits"])
        self.assertIn("libTOYOTA_COMM.so", report["errors"])
        self.assertEqual("libTOYOTA_COMM.so+0x6ec88", report["cell"])


class CallerSiteTest(unittest.TestCase):
    """Hand-encoded call sites: only a call that provably reaches the target is reported."""

    def test_a_direct_bl_reports_the_caller_and_an_argument_x0(self):
        hits, scanned, capped = caller_hits(aarch64([0x94000040], 0x1000), 0x1100, {}, {0x1000: "f"})
        self.assertEqual((1, False), (scanned, capped))
        self.assertEqual("direct call", hits[0]["kind"])
        self.assertEqual("f", hits[0]["function"])
        self.assertEqual("0x1000", hits[0]["offset"])
        self.assertEqual("argument of the caller", hits[0]["x0"]["kind"])
        self.assertEqual([], hits[0]["before"])

    def test_a_constant_x0_is_reported_with_its_value(self):
        hits, _, _ = caller_hits(aarch64([0x52800020, 0x94000040], 0x1000), 0x1104, {}, {0x1000: "f"})
        self.assertEqual("constant", hits[0]["x0"]["kind"])
        self.assertEqual("0x1", hits[0]["x0"]["detail"])

    def test_an_x0_holding_a_function_address_is_reported_as_a_supplied_function(self):
        words = [0xB0000000, 0x91010000, 0x94000040]  # adrp x0,#0x2000 ; add x0,x0,#0x40 ; bl
        hits, _, _ = caller_hits(aarch64(words, 0x1000), 0x1108, {}, {0x2040: "CheckXOR", 0x1000: "f"})
        self.assertEqual("address of a supplied function", hits[0]["x0"]["kind"])
        self.assertEqual("CheckXOR at 0x2040", hits[0]["x0"]["detail"])

    def test_an_indirect_call_through_the_targets_slot_is_reported(self):
        words = [0xD0000369, 0x91004129, 0xF9400130, 0xD63F0200]  # adrp/add/ldr x16/blr x16
        hits, _, _ = caller_hits(aarch64(words, 0x1000), None, {0x6F010: "OpenAbstractCommDll"}, {})
        self.assertEqual("indirect call via a relocation slot", hits[0]["kind"])
        self.assertEqual("0x100c", hits[0]["offset"])

    def test_a_slot_naming_another_symbol_is_not_reported(self):
        words = [0xD0000369, 0x91004129, 0xF9400130, 0xD63F0200]
        hits, _, _ = caller_hits(aarch64(words, 0x1000), None, {0x6F010: "OpenDiagCommDll"}, {})
        self.assertEqual([], hits)

    def test_a_call_to_another_address_is_not_reported(self):
        hits, _, _ = caller_hits(aarch64([0x94000041], 0x1000), 0x1100, {}, {})
        self.assertEqual([], hits)

    def test_the_twelve_instructions_before_the_call_are_kept(self):
        words = [0x52800020] * 13 + [0x94000040]
        hits, _, _ = caller_hits(aarch64(words, 0x1000), 0x1134, {}, {0x1000: "f"})
        self.assertEqual(12, len(hits[0]["before"]))
        self.assertTrue(hits[0]["before"][-1].startswith("0x1030 "))

    def test_a_malformed_object_is_recorded_in_the_caller_search(self):
        import tempfile, zipfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "toyota.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("x/libTOYOTA_COMM.so", b"\x7fELF" + b"\x00" * 120)
            report = static_caller_search(path)
        self.assertEqual([], report["hits"])
        self.assertIn("libTOYOTA_COMM.so", report["errors"])
        self.assertEqual({}, report["definitions"])


def stage3e_payload(callers, variant):
    """The smallest payload that reaches the Stage 3e renderer."""
    writers = {"libraries": ["a.so"], "cell": "a.so+0x1", "window": "±0x40", "instructionsScanned": 1,
               "cap": 1, "scanCapped": False, "holders": {}, "hits": [], "errors": {}, "jni": {},
               "setterLike": {}, "regex": "x"}
    sweep = {"label": "l", "hookResults": {}, "tuples": 0, "statuses": {}, "nativeStatus": {},
             "stops": {}, "stopTotal": 0, "afterSignatures": {}}
    return {"tuples": {"count": 0, "systems": 0, "byCode": {}, "topBySystems": []},
            "census": {"totals": {}, "tree": {}, "reconciliation": {"agrees": None, "reason": "x"}},
            "probe": {"selection": {"policy": "p", "probed": 0, "totalTuples": 0}, "verdicts": {},
                      "stopReasons": {}, "isoTpConfigurationCalls": {}, "faultGroups": [],
                      "stage3d": {"writers": writers, "sweep": sweep, "writerCall": {"skipped": "none"}},
                      "stage3c": {"cell": None, "link": {"linked": None, "skipped": "no test"},
                                  "tails": [], "stage3e": {"callers": callers, "call": variant}}},
            "tuplesProbed": []}


class CallerReportTest(unittest.TestCase):
    def test_a_search_without_a_site_says_not_established(self):
        callers = {"target": "OpenAbstractCommDll", "libraries": ["a.so"], "definitions": {},
                   "slotObjects": {}, "strings": [], "addressTakenCount": 0, "xorFunctions": [],
                   "hitCount": 0, "x0Kinds": {}, "hits": [], "instructionsScanned": 12,
                   "scanCapped": False, "cap": 10, "line": 1010, "errors": {}}
        text = markdown(stage3e_payload(callers, {"skipped": "no supplied object calls this function"}))
        self.assertIn("## Stage 3e", text)
        self.assertIn("**NOT ESTABLISHED: no supplied caller**", text)
        self.assertIn("Step 2 skipped on literal evidence", text)

    def test_sites_and_their_x0_origin_are_tabulated(self):
        callers = {"target": "OpenAbstractCommDll", "libraries": ["a.so"], "definitions": {"a.so": "0x1dbd8"},
                   "slotObjects": {"a.so": ["0x59ee8"]},
                   "strings": [{"library": "a.so", "section": ".rodata", "offset": "0x40"}],
                   "addressTakenCount": 3,
                   "xorFunctions": [{"name": "CheckXOR", "offset": "0x1e800", "size": 8}],
                   "hitCount": 1, "x0Kinds": {"constant": 1},
                   "hits": [{"library": "a.so", "offset": "0x1dc10", "function": "f",
                             "instruction": "bl #0x1e800", "kind": "direct call",
                             "before": ["0x1dc0c  mov w0, #1"],
                             "x0": {"kind": "constant", "detail": "0x1"}}],
                   "instructionsScanned": 12, "scanCapped": False, "cap": 10, "line": 1010, "errors": {}}
        text = markdown(stage3e_payload(callers, {"skipped": "x0 is not a supplied function address"}))
        for expected in ("| a.so | 0x1dc10 | f |", "constant: 0x1", "CheckXOR", "0x59ee8"):
            self.assertIn(expected, text)
        self.assertNotIn("NOT ESTABLISHED: no supplied caller", text)


class FaultTapTest(unittest.TestCase):
    """A real Unicorn null fetch: the tap snapshots it and the slot resolves by name."""

    def test_a_emulated_null_fetch_yields_caller_branch_and_named_slot(self):
        from unicorn import UC_ARCH_ARM64, UC_MODE_ARM, Uc
        from unicorn.arm64_const import UC_ARM64_REG_X9
        from toyota_can_link_sweep import FaultTap

        class Library:
            def __init__(self, base, symbols):
                self.base, self.symbols = base, symbols

        class Sandbox:
            MAX_LIBRARY_SPAN = 32 * 1024 * 1024
            libraries = {"libTOYOTA_COMM.so": Library(0x200000, {"GetBaseCanBusRecData": {"st_value": 0x18}})}

            def __init__(self, machine):
                self.uc = machine
                self.read = lambda address, size: bytes(machine.mem_read(address, size))

        base = 0x100000
        machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        machine.mem_map(base, 0x1000)
        machine.mem_map(0x200000, 0x1000)
        machine.mem_write(base, FaultContextTest.CODE)
        machine.reg_write(UC_ARM64_REG_X9, 0x200000)
        sandbox = Sandbox(machine)
        tap = FaultTap(sandbox)
        with self.assertRaises(Exception):
            machine.emu_start(base, base + 8)
        context = fault_context(sandbox, tap.take())
        self.assertEqual(0, context["pc"])
        self.assertEqual(base + 8, context["caller"])
        self.assertEqual("blr x8", context["branch"])
        self.assertEqual(0, context["branchRegisterValue"])
        self.assertEqual("0x200018", context["slotAddress"])
        self.assertEqual("libTOYOTA_COMM.so:GetBaseCanBusRecData", context["slotSymbol"])
        self.assertEqual("unlinked slot: named cell holding 0", fault_cause(context))


class FaultContextTest(unittest.TestCase):
    """The fault snapshot is decoded from bytes, with a stub standing in for the sandbox."""

    CODE = struct.pack("<II", 0xF9400D28, 0xD63F0100)  # ldr x8, [x9, #0x18]; blr x8

    def native(self):
        memory = {0x1000: self.CODE}

        class Library:
            def __init__(self, base, symbols):
                self.base, self.symbols = base, symbols

        class Stub:
            MAX_LIBRARY_SPAN = 32 * 1024 * 1024
            libraries = {"libTOYOTA_SYSP.so": Library(0x1000, {"Caller": {"st_value": 0x10}}),
                         "libTOYOTA_COMM.so": Library(0x2000, {"GetBaseCanBusRecData": {"st_value": 0x18}})}

            @staticmethod
            def read(address, size):
                return b"".join(bytes(memory.get(page, b"\x1f\x20\x03\xd5"))
                                for page in range(address, address + size))

        return Stub()

    def record(self, registers):
        values = [0] * 31
        for index, value in registers.items():
            values[index] = value
        return {"pc": 0, "fetchAddress": 0, "linkRegister": values[30], "registers": values}

    def test_caller_branch_register_and_named_slot_are_decoded(self):
        context = fault_context(self.native(), self.record({9: 0x2000, 30: 0x1008}))
        self.assertEqual("libTOYOTA_SYSP.so", context["callerLibrary"])
        self.assertEqual("0x8", context["callerOffset"])
        self.assertEqual("blr x8", context["branch"])
        self.assertEqual("x8", context["branchRegister"])
        self.assertEqual(0, context["branchRegisterValue"])
        self.assertEqual("0x2018", context["slotAddress"])
        self.assertEqual("libTOYOTA_COMM.so:GetBaseCanBusRecData", context["slotSymbol"])
        self.assertEqual("unlinked slot: named cell holding 0", fault_cause(context))

    def test_a_non_zero_branch_register_is_not_called_an_unlinked_slot(self):
        context = fault_context(self.native(), self.record({8: 0x4000, 9: 0x2000, 30: 0x1008}))
        self.assertEqual(0x4000, context["branchRegisterValue"])
        self.assertEqual("jump target outside emulated memory", fault_cause(context))

    def test_a_tap_error_is_reported_not_hidden(self):
        context = fault_context(self.native(), {"error": "RuntimeError: no state"})
        self.assertEqual("RuntimeError: no state", context["error"])
        self.assertIsNone(context["pc"])
        self.assertIsNone(fault_context(self.native(), None))


class SelectionTest(unittest.TestCase):
    def test_small_sets_are_probed_whole_and_large_sets_state_their_prefix(self):
        small = {(1, 61, 0x12): 4, (1, 62, 0x14): 1}
        chosen, policy = probe_selection(small, 300)
        self.assertEqual(2, len(chosen))
        self.assertEqual("all tuples; set at or below the limit", policy["policy"])
        many = {(1, 61 + i, 0x12): i + 1 for i in range(400)}
        chosen, policy = probe_selection(many, 300)
        self.assertEqual(300, len(chosen))
        self.assertEqual(400, policy["totalTuples"])
        self.assertEqual(sum(many[key] for key in chosen), policy["coveredSystems"])
        self.assertIn("systems-descending prefix of 300", policy["policy"])
        self.assertEqual(many[chosen[0]], max(many.values()))  # heaviest tuple first


class ReconciliationTest(unittest.TestCase):
    def test_totals_must_agree_and_disagreement_is_reported_not_hidden(self):
        totals = {"3": {"systems": 7, "enabled": 0, "classes": []},
                  "4": {"systems": 3, "enabled": 0, "classes": []}}
        agree = reconciliation(10, 4, totals)
        self.assertTrue(agree["agrees"])
        self.assertEqual(0, agree["difference"])
        disagree = reconciliation(11, 4, totals)
        self.assertFalse(disagree["agrees"])
        self.assertEqual(1, disagree["difference"])
        published = {"systemClasses": [
            {"region": "EU", "transportLabel": "CAN", "nativeTransportCode": "3", "selector": "18", "systems": 7},
            {"region": "EU", "transportLabel": "CAN", "nativeTransportCode": "4", "selector": "34", "systems": 3}]}
        reconciled = reconciliation(10, 4, totals, published)
        self.assertEqual({"3": 7, "4": 3}, reconciled["publishedCensusSystems"])
        self.assertTrue(reconciled["publishedCensusAgrees"])

    def test_markdown_states_the_totals_policy_and_verdicts(self):
        payload = {"tuples": {"count": 2, "systems": 5, "byCode": {"3": 4, "4": 1},
                              "topBySystems": [{"region": "EU", "function": 61, "selector": 0x12,
                                                "transport": 3, "systems": 4}]},
                   "census": {"totals": {"3": {"systems": 4, "enabled": 0, "classes": []}},
                              "tree": {}, "reconciliation": {"tupleSystems": 5, "censusSystems": 5, "agrees": True}},
                   "probe": {"selection": {"policy": "all tuples; set at or below the limit", "probed": 2, "totalTuples": 2},
                             "verdicts": {"not_replayable": 2}, "stopReasons": {}, "isoTpConfigurationCalls": {}},
                   "tuplesProbed": [{"region": "EU", "function": 61, "selector": 0x12, "systems": 4,
                                     "verdict": "not_replayable", "nativeSetup": {"reason": "External call has no experiment binding: X"}}]}
        text = markdown(payload)
        for expected in ("Tuples: **2**", "systems: **5**", "agrees: True",
                         "verdicts: {'not_replayable': 2}", "ISO-TP configuration calls seen: none",
                         "Nothing is enabled"):
            self.assertIn(expected, text)
        self.assertLess(len(text.splitlines()), 60)


if __name__ == "__main__":
    unittest.main()
