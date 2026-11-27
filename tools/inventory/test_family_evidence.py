"""Fast synthetic tests for Inventory-7. No vendor archive, table or library is used."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import family_evidence as fe
from yzjm import ror32, word_keys

# Synthetic, unrelated to any vendor/device key or copyrighted diagnostic table.
TABLE = bytes(range(32))
OTHER_TABLE = bytes(range(1, 33))
REFERENCE = {"table": TABLE, "device": {"status": "present"}, "roles": ["FILE", "SCAN"]}


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


def system_list_payload() -> bytes:
    """One keyed index row (1 -> 16), the FFFFFFFF sentinel, then one 37-byte row."""
    data = bytearray(56)
    struct.pack_into("<I", data, 0, 1)
    struct.pack_into("<I", data, 4, 16)
    struct.pack_into("<I", data, 8, 0xFFFFFFFF)
    struct.pack_into("<H", data, 16, 1)
    data[18:55] = bytes(range(37))
    return bytes(data)


def _nested(members: dict[str, bytes]) -> bytes:
    import io
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in members.items():
            archive.writestr(name, data)
    return buffer.getvalue()


def build_archive(path: Path, brand: str, members: dict[str, bytes], libraries: dict[str, bytes] | None = None) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in (libraries or {}).items():
            archive.writestr(name, data)
        for name, data in members.items():
            archive.writestr(name, data)
    return path


class ConsumerCitationTest(unittest.TestCase):
    def test_uncited_consumer_is_refused(self):
        with self.assertRaisesRegex(ValueError, "may not invent native consumers"):
            fe._consumers(("libTOYOTA_FILE.so", ("GetSomethingNeverCited",)))

    def test_unknown_library_is_refused(self):
        with self.assertRaisesRegex(ValueError, "does not cite"):
            fe._consumers(("libTOYOTA_NOSUCH.so", ("GetVehicleInfoData",)))

    def test_cited_consumer_is_accepted(self):
        self.assertEqual({"libTOYOTA_FILE.so": ["GetSystemListData"]},
                         fe._consumers(("libTOYOTA_FILE.so", ("GetSystemListData",))))

    def test_every_declared_family_is_cited_and_mapped(self):
        for family in fe.FAMILIES:
            self.assertIn(family["key"], fe.FAMILY_KEYS)
            self.assertTrue(family["consumers"], family["key"])
            self.assertTrue(family["citation"], family["key"])
        for reader, key in fe.READER_TO_FAMILY.items():
            self.assertIn(key, fe.FAMILY_KEYS, reader)


class StructureTest(unittest.TestCase):
    def test_system_list_shape_matches_two_families(self):
        matches = fe.classify(system_list_payload())
        self.assertTrue(matches["system-list-37"]["parsed"], matches["system-list-37"])
        self.assertTrue(matches["keyed-index-8"]["parsed"], matches["keyed-index-8"])
        self.assertEqual(1, matches["system-list-37"]["structure"]["packedRows"])
        self.assertEqual(37, matches["system-list-37"]["structure"]["rowBytes"])
        self.assertEqual(1, matches["keyed-index-8"]["structure"]["firstKey"])
        self.assertEqual(16, matches["keyed-index-8"]["structure"]["firstValue"])

    def test_unstructured_bytes_match_nothing(self):
        matches = fe.classify(b"\x00\x01\x02\x03")
        self.assertEqual([], [key for key, value in matches.items() if value["parsed"]])

    def test_ascii_leads_are_bounded_and_deduplicated(self):
        payload = b"TCODE" + b"\x00" * 8 + b"TCODE" + b"\xff" * 4 + b"ABCDEFGH"
        self.assertEqual(["TCODE", "ABCDEFGH"], fe.ascii_leads(payload))

    def test_stem_follows_the_inventory4_rule(self):
        self.assertEqual("SYS_DATA", fe.stem("A/B/SYS_DATA_V10.62.BIN"))
        self.assertEqual("MENU", fe.stem("X.ZIP!/EU/MENU.BIN"))


class OwnTableTest(unittest.TestCase):
    def test_absent_and_ambiguous_libraries_are_reported_not_guessed(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = build_archive(Path(tmp) / "A.zip", "A", {}, {})
            table, info, raw = fe.own_table(missing)
            self.assertIsNone(table)
            self.assertEqual("absent", info["status"])
            self.assertEqual(b"", raw)

            ambiguous = build_archive(Path(tmp) / "B.zip", "B", {},
                                      {"X/libDEVICEID.so": b"a", "Y/libDEVICEID.so": b"b"})
            table, info, _ = fe.own_table(ambiguous)
            self.assertIsNone(table)
            self.assertEqual("ambiguous", info["status"])
            self.assertEqual(2, info["candidates"])

    def test_table_is_read_from_the_archives_own_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "C.zip", "C", {}, {"libDEVICEID.so": b"elfbytes"})
            with patch.object(fe, "elf_bytes", return_value=TABLE) as mocked:
                table, info, raw = fe.own_table(path)
            self.assertEqual(TABLE, table)
            self.assertEqual("present", info["status"])
            self.assertEqual(b"elfbytes", raw)
            self.assertEqual((0xF88C, 32), (mocked.call_args.args[1], mocked.call_args.args[2]))

    def test_unreadable_table_is_data_not_a_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "D.zip", "D", {}, {"libDEVICEID.so": b"not-an-elf"})
            table, info, _ = fe.own_table(path)
            self.assertIsNone(table)
            self.assertEqual("table-unavailable", info["status"])
            self.assertIn("tableError", info)


class ProbeTest(unittest.TestCase):
    def _probe(self, tmp: str, name: str, table: bytes, libraries: dict[str, bytes],
               members: dict[str, bytes], brand: str, exports: dict | None = None):
        path = build_archive(Path(tmp) / name, brand, members, {"libDEVICEID.so": b"elf", **libraries})
        with patch.object(fe, "elf_bytes", return_value=table), \
                patch.object(fe, "native_info",
                             return_value={"functions": [{"name": symbol, "defined": True}
                                                         for symbol in (exports or {})]}):
            return fe.probe(path, brand, name, REFERENCE, {"binsInspected": 0})

    def test_own_role_consumer_and_identical_wrapper_yield_a_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._probe(tmp, "SYNTH.zip", TABLE, {"libSYNTH_FILE.so": b"elf"},
                                 {"SYS_DATA.BIN": yzjm(system_list_payload())}, "SYNTH",
                                 exports={"GetSystemListData"})
            self.assertTrue(result["wrapperConfidence"].startswith("established"), result["wrapperConfidence"])
            self.assertEqual(1, result["yzjmDecoded"])
            self.assertEqual(0, result["sqliteMagicMembers"])
            verdict = next(v for v in result["verdicts"] if v["family"] == "system-list-37")
            self.assertEqual("CANDIDATE", verdict["verdict"])
            self.assertEqual(["GetSystemListData"], verdict["ownRoleConsumers"])
            self.assertEqual(["libSYNTH_FILE.so"], verdict["ownRoleLibraries"])
            # A candidate still has no field meanings, and says so.
            self.assertTrue(any("field meanings" in item for item in verdict["missingEvidence"]))

    def test_shared_library_export_is_not_brand_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._probe(tmp, "SYNTH.zip", TABLE, {"libDIAG.so": b"elf"},
                                 {"MENU.BIN": yzjm(system_list_payload())}, "SYNTH",
                                 exports={"GetSystemListData"})
            verdict = next(v for v in result["verdicts"] if v["family"] == "system-list-37")
            self.assertEqual("NOT ESTABLISHED", verdict["verdict"])
            self.assertEqual([], verdict["ownRoleConsumers"])
            self.assertEqual(["GetSystemListData"], verdict["sharedLibraryConsumers"])

    def test_differing_wrapper_downgrades_to_experimental(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._probe(tmp, "SYNTH.zip", OTHER_TABLE, {"libSYNTH_FILE.so": b"elf"},
                                 {"SYS_DATA.BIN": yzjm(system_list_payload(), OTHER_TABLE)}, "SYNTH",
                                 exports={"GetSystemListData"})
            self.assertTrue(result["wrapperConfidence"].startswith("experimental"))
            self.assertFalse(result["deviceLibrary"]["tableMatchesToyota"])
            verdict = next(v for v in result["verdicts"] if v["family"] == "system-list-37")
            self.assertEqual("NOT ESTABLISHED", verdict["verdict"])
            self.assertTrue(any("experimental" in item for item in verdict["missingEvidence"]))

    def test_missing_table_decodes_nothing_and_says_why(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "NOTABLE.zip", "NOTABLE",
                                 {"SYS_DATA.BIN": yzjm(system_list_payload())}, {})
            with patch.object(fe, "elf_bytes", side_effect=ValueError("out of bounds")):
                result = fe.probe(path, "NOTABLE", "NOTABLE.zip", REFERENCE, {"binsInspected": 0})
            self.assertEqual(0, result["yzjmDecoded"])
            self.assertTrue(result["wrapperConfidence"].startswith("not applicable"))
            self.assertIn("not applicable: no format table", result["decodeErrors"])

    def test_sqlite_magic_is_measured_over_every_extension(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "SYNTH.zip", "SYNTH",
                                 {"A.DB": fe.SQLITE_MAGIC + b"\x00" * 92,
                                  "NEST.ZIP": _nested({"inner/B.DAT": fe.SQLITE_MAGIC + b"\x00" * 40}),
                                  "SYS_DATA.BIN": yzjm(system_list_payload())},
                                 {"libDEVICEID.so": b"elf"})
            with patch.object(fe, "elf_bytes", return_value=TABLE), \
                    patch.object(fe, "native_info", return_value={"functions": []}):
                result = fe.probe(path, "SYNTH", "SYNTH.zip", REFERENCE, {"binsInspected": 0})
            # Both the outer .DB and the nested .DAT are counted; extension is irrelevant.
            self.assertEqual(2, result["sqliteMagicMembers"])
            self.assertEqual(["A.DB", "NEST.ZIP!/inner/B.DAT"], result["sqliteMagicMembersSample"])
            self.assertEqual(0, result["sqliteMagicDecodedSamples"])
            self.assertEqual(1, result["yzjmDecoded"])

    def test_a_decoded_sqlite_payload_is_counted_separately(self):
        with tempfile.TemporaryDirectory() as tmp:
            blob = fe.SQLITE_MAGIC + b"\x00" * 44
            self.assertEqual(0, len(blob) % 4)
            result = self._probe(tmp, "SYNTH.zip", TABLE, {}, {"DB.BIN": yzjm(blob)}, "SYNTH")
            self.assertEqual(1, result["sqliteMagicDecodedSamples"])

    def test_duplicate_payloads_are_grouped_for_cross_archive_comparison(self):
        with tempfile.TemporaryDirectory() as tmp:
            payload = system_list_payload()
            first = self._probe(tmp, "ONE.zip", TABLE, {}, {"A.BIN": yzjm(payload)}, "ONE")
            second = self._probe(tmp, "TWO.zip", TABLE, {}, {"B.BIN": yzjm(payload)}, "TWO")
            state = {"brands": [first, second]}
            shared = fe.shared_payloads(state)
            self.assertEqual(1, len(shared))
            self.assertEqual(["ONE", "TWO"], shared[0]["brands"])

    def test_name_keyed_cross_check_records_both_verdicts(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._probe(tmp, "TOYOTA.zip", TABLE, {},
                                 {"SYSLIST_EU.BIN": yzjm(system_list_payload())}, "TOYOTA")
            with patch.object(fe.control, "reader", return_value="system_lists"):
                with patch.object(fe, "elf_bytes", return_value=TABLE), \
                        patch.object(fe, "native_info", return_value={"functions": []}):
                    path = build_archive(Path(tmp) / "T2.zip", "TOYOTA",
                                         {"SYSLIST_EU.BIN": yzjm(system_list_payload())},
                                         {"libDEVICEID.so": b"elf"})
                    crossed = fe.probe(path, "TOYOTA", "T2.zip", REFERENCE, {"binsInspected": 0},
                                       cross_check=True)
            self.assertEqual(1, len(crossed["nameKeyedCrossCheck"]))
            self.assertEqual("system_lists", crossed["nameKeyedCrossCheck"][0]["nameKeyedReader"])
            self.assertIn("system-list-37", crossed["nameKeyedCrossCheck"][0]["families"])
            self.assertEqual(0, len(result["nameKeyedCrossCheck"]))


class ControlTest(unittest.TestCase):
    def _toyota(self, reader: str, families: list[str], decoded: int = 1) -> dict:
        return {"yzjmDecoded": decoded,
                "nameKeyedCrossCheck": [{"member": "SYSLIST_EU.BIN", "nameKeyedReader": reader,
                                         "families": families}]}

    def test_agreement_passes(self):
        result = fe.control_check(REFERENCE, self._toyota("system_lists", ["keyed-index-8", "system-list-37"]),
                                  {"passed": True, "parsed": 68, "sample": 68, "errors": {}})
        self.assertTrue(result["passed"], result["reason"])
        self.assertEqual(1, result["classifierAgreements"])
        self.assertEqual(0, result["disagreementCount"])
        self.assertEqual("68/68 parsed", result["toyotaControl"])

    def test_classifier_disagreement_fails_the_control(self):
        result = fe.control_check(REFERENCE, self._toyota("system_lists", ["menu-nodes"]),
                                  {"passed": True, "parsed": 68, "sample": 68, "errors": {}})
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["disagreementCount"])
        self.assertIn("disagrees", result["reason"])

    def test_toyota_control_failure_fails_the_control(self):
        result = fe.control_check(REFERENCE, self._toyota("system_lists", ["system-list-37"]),
                                  {"passed": False, "parsed": 1, "sample": 68, "errors": {"ValueError: x": 67}})
        self.assertFalse(result["passed"])
        self.assertIn("existing Toyota decoded control", result["reason"])

    def test_no_decoded_member_fails_the_control(self):
        result = fe.control_check(REFERENCE, self._toyota("system_lists", ["system-list-37"], decoded=0),
                                  {"passed": True, "parsed": 68, "sample": 68, "errors": {}})
        self.assertFalse(result["passed"])
        self.assertIn("no Toyota YZJM member decoded", result["reason"])

    def test_name_keyed_errors_are_not_counted_as_agreement(self):
        result = fe.control_check(REFERENCE,
                                  self._toyota("ERROR ValueError: not applicable", []),
                                  {"passed": True, "parsed": 68, "sample": 68, "errors": {}})
        self.assertFalse(result["passed"])
        self.assertEqual(0, result["nameKeyedReaderSuccesses"])
        self.assertIn("no sampled Toyota member reached", result["reason"])


class ReportTest(unittest.TestCase):
    def _state(self) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            path = build_archive(Path(tmp) / "SYNTH.zip", "SYNTH",
                                 {"SYS_DATA.BIN": yzjm(system_list_payload())},
                                 {"libDEVICEID.so": b"elf", "libSYNTH_FILE.so": b"elf"})
            with patch.object(fe, "elf_bytes", return_value=TABLE), \
                    patch.object(fe, "native_info",
                                 return_value={"functions": [{"name": "GetSystemListData", "defined": True}]}):
                entry = fe.probe(path, "SYNTH", "SYNTH.zip", REFERENCE, {"binsInspected": 0})
        state = {"format": fe.FORMAT, "formatVersion": fe.FORMAT_VERSION, "generatedUtc": fe.utc_now(),
                 "headSha": "abc123", "label": fe.LABEL, "brands": [entry], "pending": [],
                 "failed": False, "budget": {}, "summary": {},
                 "control": fe.control_check(
                     REFERENCE,
                     {"yzjmDecoded": 1, "nameKeyedCrossCheck": [
                         {"member": "SYSLIST_EU.BIN", "nameKeyedReader": "system_lists",
                          "families": ["keyed-index-8", "system-list-37"]}]},
                     {"passed": True, "parsed": 68, "sample": 68, "errors": {}})}
        fe.summarise(state)
        return state

    def test_markdown_and_emit_are_serialisable_and_bounded(self):
        state = self._state()
        text = fe.markdown(state)
        self.assertIn("# Inventory-7: decoded data-family evidence", text)
        self.assertIn("SYNTH.zip", text)
        self.assertIn("## Documentation summary", text)
        self.assertIn("Families with established field meanings outside Toyota: 0.", text)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            result = fe.emit(state, out)
            self.assertEqual("success", result["conclusion"])
            self.assertTrue((out / "inventory7.md").is_file())
            manifest = json.loads((out / "parts.json").read_text())
            self.assertEqual(1, len(manifest))
            self.assertEqual("Inventory-7 family evidence", manifest[0]["name"])
            self.assertLessEqual(manifest[0]["bytes"], 60000)
            json.loads((out / "inventory7.json").read_text())

    def test_pending_or_failed_run_is_not_a_success(self):
        state = self._state()
        state["pending"] = ["OTHER.zip"]
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual("failure", fe.emit(state, Path(tmp))["conclusion"])

    def test_summary_never_counts_toyota_as_a_candidate(self):
        state = self._state()
        self.assertEqual(2, state["summary"]["candidates"])  # system-list-37 and keyed-index-8
        state["brands"].append({**state["brands"][0], "brand": "TOYOTA"})
        fe.summarise(state)
        self.assertEqual(1, state["summary"]["decodedMembers"])
        self.assertEqual(2, state["summary"]["establishedWrappers"])
        self.assertEqual(2, state["summary"]["candidates"])
        self.assertIn("TOYOTA.zip is the reference", fe.markdown(state))


if __name__ == "__main__":
    unittest.main()
