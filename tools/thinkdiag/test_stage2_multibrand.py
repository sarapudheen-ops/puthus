"""Fast synthetic tests for the Stage 2 evidence gate. No vendor archive or table is used."""
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
if str(HERE.parent / "inventory") not in sys.path:
    sys.path.insert(0, str(HERE.parent / "inventory"))

import stage2_multibrand as s2  # noqa: E402
import family_evidence as fe  # noqa: E402
from yzjm import ror32, word_keys  # noqa: E402

# Synthetic, unrelated to any vendor/device key or copyrighted diagnostic table.
TABLE = bytes(range(32))


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


def build_archive(path: Path, members: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("libDEVICEID.so", b"elf")
        for name, data in members.items():
            archive.writestr(name, data)
    return path


def candidate(**over) -> dict:
    row = {"archive": "SYNTH.zip", "brand": "SYNTH", "sha256": "a" * 64, "family": "system-list-37",
           "verdict": "CANDIDATE", "parsed": 3, "attempted": 10, "wrapper": "established",
           "own_role_consumers": ["GetSystemListData"], "own_role_libraries": ["libSYNTH_FILE.so"],
           "citation": "tools/thinkdiag/toyota_structure.py:242-247", "inventory7_missing": []}
    row.update(over)
    return row


def inventory7_state(*entries: dict) -> dict:
    return {"format": fe.FORMAT, "formatVersion": fe.FORMAT_VERSION,
            "control": {"passed": True, "toyotaControl": "68/68 parsed"},
            "brands": list(entries)}


def verdict(family: str = "system-list-37", parsed: int = 3, attempted: int = 10,
            verdict_: str = "CANDIDATE", consumers=("GetSystemListData",),
            libraries=("libSYNTH_FILE.so",), wrapper: str = "established") -> dict:
    confidence = {"established": "established: identical", "experimental": "experimental: differs",
                  "none": "not applicable: absent"}[wrapper]
    return {"family": family, "verdict": verdict_, "parsed": parsed, "membersAttempted": attempted,
            "wrapperConfidence": confidence, "ownRoleConsumers": list(consumers),
            "ownRoleLibraries": list(libraries), "citation": "cite", "missingEvidence": ["meanings missing"]}


class GateTest(unittest.TestCase):
    def test_field_meanings_are_required_even_for_a_full_candidate(self):
        allowed, satisfied, missing = s2.established(candidate())
        self.assertFalse(allowed)
        self.assertEqual(3, len(satisfied))
        self.assertEqual(1, len(missing))
        self.assertIn("MEANING_EVIDENCE is empty", missing[0])

    def test_meaning_evidence_registry_is_empty_by_default(self):
        self.assertEqual({}, s2.MEANING_EVIDENCE)
        self.assertIsNone(s2.meaning_evidence("SYNTH.zip", "system-list-37"))

    def test_a_recorded_field_meaning_opens_the_gate(self):
        record = {("SYNTH.zip", "system-list-37"): {"citation": "libSYNTH_FILE.so@0x1234 GetSystemListData"}}
        with patch.dict(s2.MEANING_EVIDENCE, record, clear=True):
            allowed, satisfied, missing = s2.established(candidate())
        self.assertTrue(allowed, missing)
        self.assertEqual([], missing)
        self.assertTrue(any(item.startswith("meanings:") for item in satisfied))

    def test_each_requirement_is_independently_enforced(self):
        cases = {
            "wrapper": candidate(wrapper="experimental"),
            "structure": candidate(parsed=0, verdict="NOT ESTABLISHED"),
            "consumer": candidate(own_role_consumers=[], own_role_libraries=[]),
        }
        for requirement, row in cases.items():
            with self.subTest(requirement=requirement):
                allowed, _, missing = s2.established(row)
                self.assertFalse(allowed)
                self.assertTrue(any(item.startswith(f"{requirement}:") for item in missing), missing)

    def test_a_shared_library_consumer_does_not_open_the_gate(self):
        allowed, _, missing = s2.established(candidate(own_role_consumers=[], own_role_libraries=[]))
        self.assertFalse(allowed)
        self.assertTrue(any("own role libraries" in item for item in missing))

    def test_a_toyota_shaped_filename_is_not_a_gate_input(self):
        # The gate reads Inventory-7 verdicts only; there is no filename path in.
        self.assertNotIn("name", candidate())
        allowed, _, _ = s2.established(candidate(archive="MENU.zip", family="menu-nodes"))
        self.assertFalse(allowed)


class CandidateOrderTest(unittest.TestCase):
    def test_candidates_sort_by_verdict_then_parsed_then_name(self):
        state = inventory7_state(
            {"brand": "TOYOTA", "archive": "TOYOTA.zip", "sha256": "t", "verdicts": []},
            {"brand": "ZED", "archive": "ZED.zip", "sha256": "z",
             "verdicts": [verdict(parsed=1, verdict_="NOT ESTABLISHED")]},
            {"brand": "BETA", "archive": "BETA.zip", "sha256": "b",
             "verdicts": [verdict(parsed=5), verdict(family="keyed-index-8", parsed=9)]},
            {"brand": "ALPHA", "archive": "ALPHA.zip", "sha256": "a", "verdicts": [verdict(parsed=5)]},
        )
        rows = s2.candidates(state)
        self.assertEqual([("BETA.zip", "keyed-index-8"), ("ALPHA.zip", "system-list-37"),
                          ("BETA.zip", "system-list-37"), ("ZED.zip", "system-list-37")],
                         [(row["archive"], row["family"]) for row in rows])
        self.assertNotIn("TOYOTA.zip", [row["archive"] for row in rows])

    def test_wrapper_confidence_is_read_from_the_verdict_text(self):
        state = inventory7_state({"brand": "X", "archive": "X.zip", "sha256": "x",
                                  "verdicts": [verdict(wrapper="experimental"),
                                              verdict(family="menu-nodes", wrapper="none")]})
        rows = {row["family"]: row["wrapper"] for row in s2.candidates(state)}
        self.assertEqual({"system-list-37": "experimental", "menu-nodes": "not applicable"}, rows)


class LoadTest(unittest.TestCase):
    def test_a_report_without_a_passing_control_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "inventory7.json"
            path.write_text(json.dumps({"format": fe.FORMAT, "control": {"passed": False}}))
            with self.assertRaisesRegex(SystemExit, "Toyota control did not pass"):
                s2.load_inventory7(path)

    def test_a_report_from_another_tool_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "inventory7.json"
            path.write_text(json.dumps({"format": "bod3-brand-inventory", "control": {"passed": True}}))
            with self.assertRaisesRegex(SystemExit, "not an Inventory-7 report"):
                s2.load_inventory7(path)


class ExtractTest(unittest.TestCase):
    def test_accepted_rejected_duplicate_and_not_applicable_are_reconciled(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            payload = system_list_payload()
            archive = build_archive(root / "SYNTH.zip", {
                "GOOD.BIN": yzjm(payload),
                "DUP.BIN": yzjm(payload),                      # same decoded payload
                "OTHER.BIN": yzjm(system_list_payload(key=2)),  # parses, distinct
                "BAD.BIN": yzjm(b"\x01\x02\x03\x04"),           # decodes, structure rejects
                "PLAIN.BIN": b"not wrapped at all",             # not YZJM
            })
            with patch.object(fe, "elf_bytes", return_value=TABLE):
                result = s2.extract_family_pack(archive, "SYNTH.zip", "system-list-37", root / "out")
            self.assertEqual(2, result["accepted"])
            self.assertEqual(1, result["duplicates"])
            self.assertEqual(1, result["rejected"])
            self.assertEqual(1, result["notApplicable"])
            self.assertEqual(5, result["sampled"])
            self.assertTrue(result["underBound"])
            self.assertFalse(result["appPackCompatible"])
            self.assertIn("structure rejected", "".join(result["reasons"]))
            self.assertIn("not applicable: member is not YZJM-wrapped", result["reasons"])
            pack = root / "out" / "synth-system-list-37-research.bin"
            self.assertTrue(pack.is_file())
            self.assertEqual(result["packBytes"], pack.stat().st_size)
            document = json.loads(__import__("zlib").decompress(pack.read_bytes()))
            self.assertEqual("bod3-family-research", document["format"])
            self.assertFalse(document["appPackCompatible"])
            self.assertFalse(document["fieldMeaningsEstablished"])
            self.assertEqual(2, len(document["records"]))
            self.assertEqual(37, document["records"][0]["structure"]["rowBytes"])

    def test_an_unknown_family_or_missing_table_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = build_archive(root / "SYNTH.zip", {"A.BIN": yzjm(system_list_payload())})
            with patch.object(fe, "elf_bytes", return_value=TABLE):
                with self.assertRaisesRegex(ValueError, "unknown family"):
                    s2.extract_family_pack(archive, "SYNTH.zip", "not-a-family", root / "out")
            with patch.object(fe, "elf_bytes", side_effect=ValueError("out of bounds")):
                with self.assertRaisesRegex(ValueError, "no format table"):
                    s2.extract_family_pack(archive, "SYNTH.zip", "system-list-37", root / "out")


class MainTest(unittest.TestCase):
    def _run(self, root: Path, state: dict, archives: dict[str, dict[str, bytes]] | None = None) -> dict:
        source = root / "zips"
        source.mkdir(exist_ok=True)
        for name, members in (archives or {}).items():
            build_archive(source / name, members)
        report = root / "inventory7.json"
        report.write_text(json.dumps(state))
        out = root / "out"
        argv = sys.argv
        try:
            sys.argv = ["stage2_multibrand.py", "--inventory7-json", str(report),
                        "--local-dir", str(source), "--out-dir", str(out), "--commit-sha", "deadbeef"]
            self.assertEqual(0, s2.main())
        finally:
            sys.argv = argv
        return json.loads((out / "stage2-multibrand-deadbeef.json").read_text())

    def test_no_candidate_produces_a_pack_and_the_report_states_the_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state({"brand": "SYNTH", "archive": "SYNTH.zip", "sha256": "s" * 64,
                                      "verdicts": [verdict()]})
            summary = self._run(root, state)
            self.assertEqual(1, summary["measured"])
            self.assertEqual(0, summary["allowed"])
            self.assertEqual(0, summary["built"])
            self.assertEqual(0, summary["app_packs_built"])
            text = (root / "out" / "stage2-multibrand-deadbeef.md").read_text()
            self.assertIn("## Built research packs", text)
            self.assertIn("None. No (archive, family) pair satisfied the gate", text)
            self.assertIn("MEANING_EVIDENCE is empty", text)
            self.assertIn("## Best candidates and their exact missing evidence", text)
            self.assertIn("SYNTH.zip", text)
            self.assertEqual([], summary["packs"])

    def test_a_recorded_field_meaning_builds_a_research_pack_not_an_app_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state({"brand": "SYNTH", "archive": "SYNTH.zip", "sha256": "s" * 64,
                                      "verdicts": [verdict()]})
            record = {("SYNTH.zip", "system-list-37"): {"citation": "libSYNTH_FILE.so@0x1234"}}
            with patch.dict(s2.MEANING_EVIDENCE, record, clear=True), \
                    patch.object(fe, "elf_bytes", return_value=TABLE):
                summary = self._run(root, state, {"SYNTH.zip": {"A.BIN": yzjm(system_list_payload())}})
            self.assertEqual(1, summary["allowed"])
            self.assertEqual(1, summary["built"])
            self.assertEqual(0, summary["app_packs_built"])
            pack = summary["packs"][0]
            self.assertEqual(1, pack["accepted"])
            self.assertFalse(pack["appPackCompatible"])
            self.assertEqual(64, len(pack["packSha256"]))
            text = (root / "out" / "stage2-multibrand-deadbeef.md").read_text()
            self.assertIn("## Built research packs", text)
            self.assertIn(pack["packSha256"], text)

    def test_a_missing_archive_is_reported_not_fatal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state({"brand": "GONE", "archive": "GONE.zip", "sha256": "g" * 64,
                                      "verdicts": [verdict()]})
            record = {("GONE.zip", "system-list-37"): {"citation": "x"}}
            with patch.dict(s2.MEANING_EVIDENCE, record, clear=True):
                summary = self._run(root, state)
            self.assertEqual(0, summary["built"])
            self.assertTrue(any("archive unavailable" in item for item in summary["evaluations"][0]["missing"]))


if __name__ == "__main__":
    unittest.main()
