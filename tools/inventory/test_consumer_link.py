"""Fast synthetic tests for Inventory-8. No vendor archive, library or table is used.

Consumer bodies are faked through ``native_info``/``elf_bytes``: a function body is
derived from its own symbol address, so byte-identity between a brand's consumer
and Toyota's is a lever the test controls by choosing addresses, never by copying
anything from a real library.
"""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import consumer_link as cl
import family_evidence as fe
from yzjm import ror32, word_keys

# Synthetic, unrelated to any vendor/device key or copyrighted diagnostic table.
TABLE = bytes(range(32))
FILE_LIBRARY = "libTOYOTA_FILE.so"
TOYOTA_SYMBOLS = {
    FILE_LIBRARY: [{"name": "GetSystemListData", "address": "0x11574", "size": 8, "defined": True},
                   {"name": "GetVehicleInfoData", "address": "0x112b4", "size": 12, "defined": True}],
}


def yzjm(payload: bytes, table: bytes = TABLE, make="SYNTH", version="V01.23", seed=0x12345678) -> bytes:
    header = b""
    for text in ("YZJM", make, version):
        raw = text.encode("ascii") + b"\0"
        header += struct.pack("<H", len(raw)) + raw
    header += struct.pack("<I", seed)
    keys = word_keys(make, version, seed, table)
    body = b""
    for index, (word,) in enumerate(struct.iter_unpack(">I", payload)):
        cipher = word if word in (0, 0xFFFFFFFF) else ror32(word ^ keys[index % 8], 10)
        body += struct.pack(">I", cipher)
    return header + body


def system_list_payload(key: int = 1) -> bytes:
    data = bytearray(56)
    struct.pack_into("<I", data, 0, key)
    struct.pack_into("<I", data, 4, 16)
    struct.pack_into("<I", data, 8, 0xFFFFFFFF)
    struct.pack_into("<H", data, 16, 1)
    data[18:55] = bytes(range(37))
    return bytes(data)


def fake_elf_bytes(data: bytes, address: int, size: int) -> bytes:
    """A function body determined by its own symbol address."""
    return bytes([(address >> 8) & 0xFF, address & 0xFF]) * (size // 2 + 1)


def fake_native_info(symbols: dict):
    def inner(data: bytes, name: str) -> dict:
        if data[:4] == b"BADL":
            raise ValueError("not an ELF file")
        return {"name": name, "machine": "AArch64", "needed": [], "strings": [],
                "functions": list(symbols.get(name, []))}
    return inner


def build_archive(path: Path, members: dict[str, bytes], libraries: dict[str, bytes] | None = None) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("libDEVICEID.so", b"elf-device-id")
        for name, data in (libraries or {}).items():
            archive.writestr(name, data)
        for name, data in members.items():
            archive.writestr(name, data)
    return path


def library_with(*literals: str) -> bytes:
    body = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 24
    for literal in literals:
        body += literal.encode("ascii") + b"\x00"
    return body + b"\x00" * 32


def consumer(brand_library="libSYNTH_FILE.so", toyota_library=FILE_LIBRARY,
             name="GetSystemListData", kind="own-role-library") -> dict:
    return {"consumer": name, "toyotaLibrary": toyota_library, "role": "FILE",
            "exportedBy": [brand_library], "kind": kind}


def target(**over) -> dict:
    row = {"archive": "SYNTH.zip", "brand": "SYNTH", "sha256": "s" * 64, "family": "keyed-index-8",
           "inventory7Verdict": "CANDIDATE", "parsed": 1, "attempted": 2,
           "wrapperConfidence": "established: identical", "ownRoleConsumers": ["GetSystemListData"],
           "ownRoleLibraries": ["libSYNTH_FILE.so"], "consumers": [consumer()],
           "librariesParsed": ["libSYNTH_FILE.so"],
           "samples": [{"member": "SYNTH_SYS_LIST.BIN", "decodedBytes": 56,
                        "payloadSha256": "p" * 64, "first8": "01000000", "structure": {},
                        "asciiLeads": []}]}
    row.update(over)
    return row


def verdict_row(brand="SYNTH", family="keyed-index-8", parsed=1, verdict_="CANDIDATE", members=2,
                own=("GetSystemListData",)) -> dict:
    library = f"lib{brand}_FILE.so"
    return {"family": family, "verdict": verdict_, "parsed": parsed, "membersAttempted": members,
            "wrapperConfidence": "established: identical", "ownRoleConsumers": list(own),
            "sharedLibraryConsumers": [], "consumers": [consumer(brand_library=library)], "roles": ["FILE"],
            "ownRoleLibraries": [library], "topStems": [], "topFirstHeaders": [],
            "missingEvidence": []}


def brand_entry(brand="SYNTH", verdicts=None, samples=None) -> dict:
    library = f"lib{brand}_FILE.so"
    return {"brand": brand, "archive": f"{brand}.zip", "archiveBytes": 1000, "sha256": "b" * 64,
            "deviceLibrary": {"status": "present"}, "wrapperConfidence": "established: identical",
            "librariesParsed": [library], "ownRoleLibraries": {"FILE": [library]},
            "consumerExports": {}, "verdicts": verdicts if verdicts is not None else [verdict_row(brand)],
            "families": {family["key"]: {"members": 2, "parsed": 1, "stems": {}, "errors": {},
                                         "firstHeaders": {},
                                         "samples": samples if samples is not None else
                                         [{"member": f"{brand}_SYS_LIST.BIN", "decodedBytes": 56,
                                           "payloadSha256": "p" * 64, "first8": "01000000",
                                           "structure": {}, "asciiLeads": []}]}
                         for family in fe.FAMILIES},
            "payloadOwners": {}, "nameKeyedCrossCheck": []}


def inventory7_state(*entries: dict) -> dict:
    return {"format": fe.FORMAT, "formatVersion": fe.FORMAT_VERSION, "headSha": "abc123",
            "control": {"passed": True, "toyotaControl": "68/68 parsed", "classifierAgreements": 4,
                        "disagreementCount": 0},
            "brands": list(entries), "pending": [], "failed": False, "summary": {}}


class CompareFunctionTest(unittest.TestCase):
    def test_identical_bodies_are_identical(self):
        body = fake_elf_bytes(b"", 0x11574, 8)
        result = cl.compare_functions(body, {"status": "present", "bytes": 8},
                                      body, {"status": "present", "bytes": 8})
        self.assertTrue(result["identical"])
        self.assertIsNone(result["firstDifference"])
        self.assertEqual(result["brandSha256"], result["toyotaSha256"])

    def test_differing_bodies_locate_the_first_difference(self):
        brand = fake_elf_bytes(b"", 0x1000, 8)
        toyota = fake_elf_bytes(b"", 0x11574, 8)
        result = cl.compare_functions(brand, {"status": "present", "bytes": 8},
                                      toyota, {"status": "present", "bytes": 8})
        self.assertFalse(result["identical"])
        self.assertEqual(0, result["firstDifference"])
        self.assertEqual(0, result["sizeDelta"])

    def test_a_size_difference_is_reported_not_averaged(self):
        result = cl.compare_functions(b"\x01" * 8, {"status": "present", "bytes": 8},
                                      b"\x01" * 12, {"status": "present", "bytes": 12})
        self.assertFalse(result["identical"])
        self.assertEqual(8, result["firstDifference"])
        self.assertEqual(-4, result["sizeDelta"])

    def test_a_missing_body_is_a_reason_never_an_identity(self):
        result = cl.compare_functions(None, {"status": "not exported"}, b"\x01", {"status": "present"})
        self.assertFalse(result["identical"])
        self.assertIn("brand: not exported", result["reason"])
        self.assertIsNone(result["brandSha256"])


class FunctionBytesTest(unittest.TestCase):
    def test_an_absent_symbol_is_data(self):
        with patch.object(cl, "native_info", fake_native_info({})):
            body, info = cl.function_bytes(b"elf", "libSYNTH_FILE.so", "GetSystemListData")
        self.assertIsNone(body)
        self.assertEqual("not exported", info["status"])

    def test_an_imported_symbol_is_not_a_consumer_body(self):
        symbols = {"libSYNTH_FILE.so": [{"name": "GetSystemListData", "address": "0x0", "size": 8,
                                         "defined": False}]}
        with patch.object(cl, "native_info", fake_native_info(symbols)):
            body, info = cl.function_bytes(b"elf", "libSYNTH_FILE.so", "GetSystemListData")
        self.assertIsNone(body)
        self.assertEqual("imported only", info["status"])

    def test_a_symbol_without_a_size_cannot_be_compared(self):
        symbols = {"libSYNTH_FILE.so": [{"name": "GetSystemListData", "address": "0x1000", "size": 0,
                                         "defined": True}]}
        with patch.object(cl, "native_info", fake_native_info(symbols)):
            body, info = cl.function_bytes(b"elf", "libSYNTH_FILE.so", "GetSystemListData")
        self.assertIsNone(body)
        self.assertEqual("no symbol size", info["status"])

    def test_an_unparsable_library_is_data_not_a_crash(self):
        with patch.object(cl, "native_info", fake_native_info(TOYOTA_SYMBOLS)):
            body, info = cl.function_bytes(b"BADL", FILE_LIBRARY, "GetSystemListData")
        self.assertIsNone(body)
        self.assertEqual("unparsable", info["status"])
        self.assertIn("ValueError", info["error"])

    def test_an_out_of_bounds_address_is_reported(self):
        def boom(data, address, size):
            raise ValueError(f"ELF data address {address:#x} is out of bounds")
        with patch.object(cl, "native_info", fake_native_info(TOYOTA_SYMBOLS)), \
                patch.object(cl, "elf_bytes", boom):
            body, info = cl.function_bytes(b"elf", FILE_LIBRARY, "GetSystemListData")
        self.assertIsNone(body)
        self.assertEqual("out of bounds", info["status"])
        self.assertEqual("0x11574", info["address"])


class ReadLibraryTest(unittest.TestCase):
    def test_absent_ambiguous_and_unreadable_are_all_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build_archive(root / "one.zip", {}, {})
            with zipfile.ZipFile(root / "two.zip", "w") as archive:
                archive.writestr("a/libX.so", b"one")
                archive.writestr("b/libX.so", b"two")
            (root / "bad.zip").write_bytes(b"not a zip at all")
            self.assertEqual("absent", cl.read_library(root / "one.zip", "libX.so")[1]["status"])
            self.assertEqual("ambiguous", cl.read_library(root / "two.zip", "libX.so")[1]["status"])
            self.assertEqual("unreadable", cl.read_library(root / "bad.zip", "libX.so")[1]["status"])
            data, info = cl.read_library(root / "two.zip", "libDEVICEID.so")
            self.assertEqual("absent", info["status"])
            self.assertIsNone(data)

    def test_an_oversized_library_is_not_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with zipfile.ZipFile(root / "big.zip", "w", zipfile.ZIP_STORED) as archive:
                archive.writestr("libX.so", b"\0" * (cl.LIBRARY_MAX_BYTES + 1))
            data, info = cl.read_library(root / "big.zip", "libX.so")
            self.assertIsNone(data)
            self.assertEqual("too large", info["status"])


class FilenameLinkTest(unittest.TestCase):
    def test_a_literal_naming_the_member_is_found_with_its_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "SYNTH.zip", {"SYNTH_SYS_LIST.BIN": yzjm(system_list_payload())},
                                 {"libSYNTH_FILE.so": library_with("SYNTH_SYS_LIST.BIN", "unrelated")})
            with patch.object(cl, "va_of", return_value=0x2000):
                links = cl.filename_links(path, ["libSYNTH_FILE.so"],
                                          {"SYNTH_SYS_LIST.BIN": "member basename"})
        self.assertEqual(1, len(links))
        self.assertEqual("libSYNTH_FILE.so", links[0]["library"])
        self.assertEqual("SYNTH_SYS_LIST.BIN", links[0]["literal"])
        self.assertEqual("member basename", links[0]["why"])
        self.assertEqual(0x2000, links[0]["va"])

    def test_matching_is_case_insensitive_on_the_stem(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "SYNTH.zip", {}, {"libSYNTH_FILE.so": library_with("synth_sys_list")})
            links = cl.filename_links(path, ["libSYNTH_FILE.so"], {"SYNTH_SYS_LIST": "Inventory-4 stem"})
        self.assertEqual(1, len(links))
        self.assertEqual("synth_sys_list", links[0]["literal"])

    def test_another_brands_member_is_not_a_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "SYNTH.zip", {}, {"libSYNTH_FILE.so": library_with("SYNTH_SYS_LIST")})
            self.assertEqual([], cl.filename_links(path, ["libSYNTH_FILE.so"],
                                                   {"DAIHATSU_SYS_LIST.BIN": "another brand"}))

    def test_the_result_is_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            count = cl.MAX_LINKS + 2
            path = build_archive(Path(tmp) / "SYNTH.zip", {},
                                 {f"lib{i}.so": library_with("SYNTH_SYS_LIST.BIN") for i in range(count)})
            links = cl.filename_links(path, [f"lib{i}.so" for i in range(count)],
                                      {"SYNTH_SYS_LIST.BIN": "member basename"})
        self.assertEqual(cl.MAX_LINKS, len(links))


class StructureTextTest(unittest.TestCase):
    def test_a_reader_that_returns_only_integers_surfaces_no_text(self):
        result = cl.structure_text("keyed-index-8", system_list_payload())
        self.assertEqual("parsed", result["status"])
        self.assertEqual(0, result["textValues"])
        self.assertNotIn("str", result["valueKinds"])
        self.assertIn("int", result["valueKinds"])

    def test_a_text_value_inside_the_parsed_structure_is_counted(self):
        with patch.dict(fe.FAMILIES[1], {"structure": lambda data: {"name": "ENGINE", "id": 7}}, clear=False):
            result = cl.structure_text("keyed-index-8", system_list_payload())
        self.assertEqual(1, result["textValues"])
        self.assertIn("str", result["valueKinds"])

    def test_a_rejecting_reader_is_reported_not_raised(self):
        result = cl.structure_text("keyed-index-8", b"\x00\x00\x00\x00")
        self.assertTrue(result["status"].startswith("reader rejected"))
        self.assertEqual(0, result["textValues"])

    def test_an_unknown_family_is_data(self):
        self.assertEqual("unknown family", cl.structure_text("not-a-family", b"")["status"])


class MeasureTest(unittest.TestCase):
    def _measure(self, root: Path, brand_symbols: dict, literals=("SYNTH_SYS_LIST.BIN",),
                 row: dict | None = None) -> dict:
        path = build_archive(root / "SYNTH.zip", {"SYNTH_SYS_LIST.BIN": yzjm(system_list_payload())},
                             {"libSYNTH_FILE.so": library_with(*literals)})
        symbols = {**TOYOTA_SYMBOLS, **brand_symbols}
        budget = {"functionsCompared": 0, "payloadsMeasured": 0}
        with patch.object(cl, "native_info", fake_native_info(symbols)), \
                patch.object(cl, "elf_bytes", fake_elf_bytes), \
                patch.object(fe, "elf_bytes", return_value=TABLE):
            return cl.measure(path, row or target(), {FILE_LIBRARY: {
                "GetSystemListData": fake_elf_bytes(b"", 0x11574, 8),
                "GetVehicleInfoData": fake_elf_bytes(b"", 0x112B4, 12)}}, budget)

    def test_an_identical_consumer_body_at_the_same_address_is_identical(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {"libSYNTH_FILE.so": [
                {"name": "GetSystemListData", "address": "0x11574", "size": 8, "defined": True}]})
        self.assertEqual(["GetSystemListData"], row["identicalConsumers"])
        self.assertEqual("CODE-IDENTICAL AND LINKED", row["verdict"])
        self.assertTrue(row["filenameLinks"])
        self.assertFalse(row["meaningsEstablished"])

    def test_a_same_named_consumer_at_a_different_address_is_not_identical(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {"libSYNTH_FILE.so": [
                {"name": "GetSystemListData", "address": "0x1000", "size": 8, "defined": True}]})
        self.assertEqual([], row["identicalConsumers"])
        self.assertEqual("FILENAME LINK, CODE DIFFERS", row["verdict"])
        self.assertEqual(0, row["consumerIdentity"][0]["firstDifference"])

    def test_no_literal_naming_the_member_means_no_link(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {"libSYNTH_FILE.so": [
                {"name": "GetSystemListData", "address": "0x11574", "size": 8, "defined": True}]},
                literals=("MENU", "OBD2_SYS_DATA"))
        self.assertEqual("CODE-IDENTICAL, NO FILENAME LINK", row["verdict"])
        self.assertEqual([], row["filenameLinks"])

    def test_an_opaque_reader_always_leaves_meanings_unestablished(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {"libSYNTH_FILE.so": [
                {"name": "GetSystemListData", "address": "0x11574", "size": 8, "defined": True}]})
        self.assertEqual(0, row["readerTextValues"])
        self.assertFalse(row["meaningsEstablished"])
        joined = " ".join(row["meaningBlockers"])
        self.assertIn("opaque identifier", joined)
        self.assertIn("no vendor documentation", joined)
        self.assertNotIn("NOT byte-identical", joined)
        self.assertEqual(64, len(row["readerText"][0]["payloadSha256"]))
        self.assertEqual(56, row["readerText"][0]["decodedBytes"])

    def test_a_differing_consumer_adds_the_identity_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {"libSYNTH_FILE.so": [
                {"name": "GetSystemListData", "address": "0x1000", "size": 8, "defined": True}]})
        self.assertIn("NOT byte-identical", " ".join(row["meaningBlockers"]))

    def test_a_shared_library_consumer_is_not_compared_as_an_own_role_consumer(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = self._measure(Path(tmp), {}, row=target(consumers=[consumer(kind="shared-library-only")]))
        self.assertEqual([], row["consumerIdentity"])
        self.assertEqual(0, row["comparedConsumers"])
        self.assertIn("no own-role-library consumer could be compared", " ".join(row["meaningBlockers"]))


class ControlTest(unittest.TestCase):
    def measured_toyota(self, links=None) -> dict:
        """A Toyota row shaped like `measure` output, so every control has an input."""
        return {**target(), "archive": "TOYOTA.zip", "membersExamined": ["TOYOTA_SYS_LIST.BIN"],
                "filenameLinks": links if links is not None else
                [{"library": FILE_LIBRARY, "literal": "TOYOTA_SYS_LIST.BIN", "needle": "TOYOTA_SYS_LIST.BIN",
                  "why": "member basename", "offset": 8, "va": 0x2000}],
                "readerText": [{"member": "TOYOTA_SYS_LIST.BIN", "status": "parsed", "textValues": 0,
                                "valueKinds": ["int"], "valueCounts": {"int": 5}, "topKeys": []}],
                "readerTextValues": 0}

    def reference(self) -> dict:
        return {FILE_LIBRARY: {"GetSystemListData": fake_elf_bytes(b"", 0x11574, 8),
                               "GetVehicleInfoData": fake_elf_bytes(b"", 0x112B4, 12)}}

    def test_all_controls_pass_on_a_complete_reference(self):
        row = self.measured_toyota()
        result = cl.control_check(self.reference(), row, row["filenameLinks"], [])
        self.assertTrue(result["passed"], result["checks"])
        self.assertEqual(5, len(result["checks"]))

    def test_a_reference_with_one_body_fails_the_negative_identity_control(self):
        row = self.measured_toyota()
        result = cl.control_check({FILE_LIBRARY: {"GetSystemListData": b"\x01" * 8}}, row,
                                  row["filenameLinks"], [])
        self.assertFalse(result["passed"])
        failing = [row["name"] for row in result["checks"] if not row["passed"]]
        self.assertEqual(["identity negative: two different Toyota consumers are not byte-identical"], failing)

    def test_no_positive_link_makes_the_control_fail(self):
        result = cl.control_check(self.reference(), self.measured_toyota(links=[]), [], [])
        self.assertFalse(result["passed"])
        self.assertIn("uninformative", next(row["detail"] for row in result["checks"]
                                            if row["name"].startswith("link positive")))

    def test_a_cross_brand_literal_in_toyota_fails_the_negative_link_control(self):
        row = self.measured_toyota()
        result = cl.control_check(self.reference(), row, row["filenameLinks"],
                                  [{"library": FILE_LIBRARY, "literal": "SYNTH_SYS_LIST.BIN"}])
        self.assertFalse(result["passed"])

    def test_an_unmeasured_reader_census_fails_the_control(self):
        result = cl.control_check(self.reference(), None,
                                  [{"library": FILE_LIBRARY, "literal": "SYSLIST_EU.BIN"}], [])
        self.assertFalse(result["passed"])


class TargetsTest(unittest.TestCase):
    def test_only_pairs_that_parsed_something_are_targets(self):
        state = inventory7_state(brand_entry("ONE"), brand_entry("TWO", [verdict_row("TWO", parsed=0)]))
        rows = cl.targets(state)
        self.assertEqual(["ONE.zip"], [row["archive"] for row in rows])

    def test_toyota_is_the_reference_and_never_a_target(self):
        state = inventory7_state(brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)]), brand_entry("ONE"))
        self.assertEqual(["ONE.zip"], [row["archive"] for row in cl.targets(state)])
        self.assertEqual("TOYOTA.zip", cl.toyota_target(state)["archive"])

    def test_candidates_are_measured_first_and_the_list_is_bounded(self):
        entries = [brand_entry(f"B{i:03d}", [verdict_row(f"B{i:03d}", parsed=1, verdict_="NOT ESTABLISHED")])
                   for i in range(cl.MAX_TARGETS)]
        entries.append(brand_entry("LAST", [verdict_row("LAST", parsed=2, verdict_="CANDIDATE")]))
        rows = cl.targets(inventory7_state(*entries))
        self.assertEqual(cl.MAX_TARGETS, len(rows))
        self.assertEqual("LAST.zip", rows[0]["archive"])

    def test_the_toyota_control_row_carries_the_same_shape_as_a_target(self):
        row = cl.toyota_target(inventory7_state(brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)])))
        self.assertEqual(set(cl.targets(inventory7_state(brand_entry("ONE")))[0]), set(row))


class ReportTest(unittest.TestCase):
    def _state(self, control_passed=True, pending=()) -> dict:
        state = {"format": cl.FORMAT, "formatVersion": cl.FORMAT_VERSION, "generatedUtc": cl.utc_now(),
                 "headSha": "abc123", "label": cl.LABEL, "inventory7": "inventory7.json",
                 "budget": {"functionsCompared": 1, "payloadsMeasured": 1},
                 "targets": [{**target(), "membersExamined": ["SYNTH_SYS_LIST.BIN"],
                              "libraryStatus": "present",
                              "consumerIdentity": [{"consumer": "GetSystemListData",
                                                    "library": "libSYNTH_FILE.so",
                                                    "toyotaLibrary": FILE_LIBRARY,
                                                    "brand": {"status": "present", "bytes": 8},
                                                    "toyota": {"status": "present", "bytes": 8},
                                                    "identical": False, "firstDifference": 0,
                                                    "sizeDelta": 0}],
                              "identicalConsumers": [], "comparedConsumers": 1, "filenameLinks": [],
                              "readerText": [{"member": "SYNTH_SYS_LIST.BIN", "status": "parsed",
                                              "textValues": 0, "valueKinds": ["int"],
                                              "valueCounts": {"int": 5}, "topKeys": []}],
                              "readerTextValues": 0, "verdict": "NOT ESTABLISHED",
                              "meaningsEstablished": False,
                              "meaningBlockers": ["no vendor documentation"]}],
                 "toyota": None, "pending": list(pending), "failed": False,
                 "control": {"passed": control_passed,
                             "checks": [{"name": "identity positive", "passed": control_passed,
                                         "detail": "ok"}]},
                 "referenceErrors": {}}
        cl.summarise(state)
        return state

    def test_markdown_states_the_measurements_and_never_a_meaning(self):
        text = cl.markdown(self._state())
        self.assertIn("# Inventory-8: consumer code identity, filename link and reader text", text)
        self.assertIn("| `SYNTH.zip` | `keyed-index-8` | CANDIDATE | 1 | 1 | 0 | 0 | 0 | NOT ESTABLISHED |", text)
        self.assertIn("Field meanings established: **no**", text)
        self.assertIn("## Documentation summary", text)
        self.assertIn("Still missing before any field could be named", text)
        self.assertIn("no vendor documentation", text)

    def test_emit_writes_bounded_parts_and_a_conclusion(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            result = cl.emit(self._state(), out)
            self.assertEqual("success", result["conclusion"])
            self.assertTrue((out / "inventory8.md").is_file())
            json.loads((out / "inventory8.json").read_text())
            manifest = json.loads((out / "parts.json").read_text())
            self.assertEqual("Inventory-8 consumer link", manifest[0]["name"])
            self.assertLessEqual(max(item["bytes"] for item in manifest), 60000)

    def test_a_failed_control_or_an_unreached_archive_is_not_a_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual("failure", cl.emit(self._state(control_passed=False), Path(tmp))["conclusion"])
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual("failure", cl.emit(self._state(pending=["OTHER.zip"]), Path(tmp))["conclusion"])

    def test_the_summary_counts_verdicts_and_never_counts_a_pack(self):
        summary = self._state()["summary"]
        self.assertEqual(1, summary["targets"])
        self.assertEqual(0, summary["identicalConsumers"])
        self.assertEqual(0, summary["meaningsEstablished"])
        self.assertEqual(1, summary["verdicts"]["NOT ESTABLISHED"])
        self.assertEqual(0, summary["packs"] + summary["appChanges"])


class MainTest(unittest.TestCase):
    def _archives(self, root: Path, brand_symbols: dict) -> Path:
        source = root / "zips"
        source.mkdir(exist_ok=True)
        build_archive(source / "TOYOTA.zip", {"TOYOTA_SYS_LIST.BIN": yzjm(system_list_payload())},
                      {FILE_LIBRARY: library_with("TOYOTA_SYS_LIST.BIN"),
                       "libTOYOTA_SCAN.so": library_with("GetSysListNo")})
        build_archive(source / "SYNTH.zip", {"SYNTH_SYS_LIST.BIN": yzjm(system_list_payload())},
                      {"libSYNTH_FILE.so": library_with("SYNTH_SYS_LIST.BIN")})
        return source

    def _run(self, root: Path, state: dict, extra: list[str] | None = None,
             symbols: dict | None = None) -> tuple[int, dict]:
        source = self._archives(root, symbols or {})
        report = root / "inventory7.json"
        report.write_text(json.dumps(state))
        out = root / "out"
        digest = hashlib.sha256((source / "TOYOTA.zip").read_bytes()).hexdigest()
        argv = sys.argv
        try:
            sys.argv = ["consumer_link.py", "--inventory7-json", str(report), "--local-dir", str(source),
                        "--output", str(out), "--max-seconds", "60"] + (extra or [])
            with patch.object(cl, "native_info", fake_native_info({**TOYOTA_SYMBOLS, **(symbols or {})})), \
                    patch.object(cl, "elf_bytes", fake_elf_bytes), \
                    patch.object(fe, "elf_bytes", return_value=TABLE), \
                    patch.object(cl.fe, "TOYOTA_SHA256", digest):
                code = cl.main()
        finally:
            sys.argv = argv
        return code, json.loads((out / "inventory8.json").read_text())

    def test_a_full_run_measures_the_candidate_and_establishes_no_meaning(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state(brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)]),
                                     brand_entry("SYNTH"))
            code, result = self._run(root, state)
            self.assertEqual(0, code, result["control"]["checks"])
            self.assertEqual(1, result["summary"]["targets"])
            self.assertEqual(0, result["summary"]["meaningsEstablished"])
            self.assertEqual(0, result["summary"]["packs"])
            self.assertTrue(result["control"]["passed"], result["control"]["checks"])
            row = result["targets"][0]
            self.assertEqual("SYNTH.zip", row["archive"])
            self.assertEqual(1, row["comparedConsumers"])
            self.assertFalse(row["meaningsEstablished"])
            self.assertTrue((root / "out" / "inventory8.md").is_file())

    def test_an_identical_and_linked_consumer_still_establishes_no_field_meaning(self):
        symbols = {"libSYNTH_FILE.so": [{"name": "GetSystemListData", "address": "0x11574", "size": 8,
                                         "defined": True}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state(brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)]),
                                     brand_entry("SYNTH"))
            code, result = self._run(root, state, symbols=symbols)
            self.assertEqual(0, code, result["control"]["checks"])
            row = result["targets"][0]
            self.assertEqual("CODE-IDENTICAL AND LINKED", row["verdict"])
            self.assertEqual(["GetSystemListData"], row["identicalConsumers"])
            self.assertTrue(row["filenameLinks"])
            # The strongest static result this tool can produce still leaves the
            # identifier-to-system mapping unestablished, and the tool says so.
            self.assertFalse(row["meaningsEstablished"])
            self.assertEqual(0, result["summary"]["meaningsEstablished"])
            self.assertEqual(0, result["summary"]["packs"] + result["summary"]["appChanges"])
            self.assertIn("opaque identifier", " ".join(row["meaningBlockers"]))
            self.assertIn("no vendor documentation", " ".join(row["meaningBlockers"]))

    def test_a_reference_that_does_not_match_the_pinned_hash_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self._archives(root, {})
            report = root / "inventory7.json"
            report.write_text(json.dumps(inventory7_state(
                brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)]), brand_entry("SYNTH"))))
            argv = sys.argv
            try:
                sys.argv = ["consumer_link.py", "--inventory7-json", str(report), "--local-dir", str(source),
                            "--output", str(root / "out")]
                with self.assertRaises(SystemExit) as caught:
                    cl.main()
            finally:
                sys.argv = argv
            self.assertIn("pinned SHA-256", str(caught.exception))

    def test_no_targets_is_a_stop_not_an_empty_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state(brand_entry("SYNTH", [verdict_row("SYNTH", parsed=0)]))
            argv = sys.argv
            source = self._archives(root, {})
            report = root / "inventory7.json"
            report.write_text(json.dumps(state))
            try:
                sys.argv = ["consumer_link.py", "--inventory7-json", str(report), "--local-dir", str(source),
                            "--output", str(root / "out")]
                with self.assertRaises(SystemExit) as caught:
                    cl.main()
            finally:
                sys.argv = argv
            self.assertIn("nothing to measure", str(caught.exception))

    def test_an_exhausted_budget_leaves_the_pair_pending_and_fails_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state(brand_entry("TOYOTA", [verdict_row("TOYOTA", parsed=9)]),
                                     brand_entry("SYNTH"))
            code, result = self._run(root, state, ["--max-seconds", "0"])
            self.assertEqual(1, code)
            self.assertEqual(["SYNTH.zip"], result["pending"])
            self.assertEqual("failure", (root / "out" / "conclusion.txt").read_text().strip())

    def test_the_script_is_importable_with_pyelftools_alone(self):
        script = """
import sys, importlib.abc
BLOCK = {"capstone", "unicorn", "py3rijndael", "rijndael"}
class Blocker(importlib.abc.MetaPathFinder):
    def find_spec(self, name, path=None, target=None):
        if name.split(".")[0] in BLOCK:
            raise ImportError("blocked: " + name)
sys.meta_path.insert(0, Blocker())
sys.path.insert(0, "tools/inventory")
import consumer_link
print("ok", consumer_link.FORMAT)
"""
        result = subprocess.run([sys.executable, "-c", script], cwd=cl.HERE.parents[1],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("ok bod3-consumer-link", result.stdout)


if __name__ == "__main__":
    unittest.main()
