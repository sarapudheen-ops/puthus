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


def inventory8_state(*rows: dict, control_passed: bool = True) -> dict:
    """An Inventory-8 report as tools/inventory/consumer_link.py publishes it."""
    return {"format": s2.INVENTORY8_FORMAT, "formatVersion": 1, "headSha": "abc123",
            "control": {"passed": control_passed, "checks": []},
            "summary": {"targets": len(rows), "archives": len({r["archive"] for r in rows}),
                        "functionsCompared": sum(r.get("comparedConsumers", 0) for r in rows),
                        "identicalConsumers": sum(len(r.get("identicalConsumers") or []) for r in rows),
                        "linkTargets": sum(1 for r in rows if r.get("filenameLinks")),
                        "links": sum(len(r.get("filenameLinks") or []) for r in rows),
                        "textTargets": sum(1 for r in rows if r.get("readerTextValues", 0) > 0),
                        "meaningsEstablished": sum(1 for r in rows if r.get("meaningsEstablished")),
                        "packs": 0, "appChanges": 0},
            "targets": list(rows), "pending": [], "failed": False}


def link_row(**over) -> dict:
    row = {"archive": "SYNTH.zip", "family": "system-list-37", "verdict": "CODE-IDENTICAL AND LINKED",
           "comparedConsumers": 1, "identicalConsumers": ["GetSystemListData"],
           "filenameLinks": [{"library": "libSYNTH_FILE.so", "literal": "SYNTH_SYS_LIST.BIN",
                              "needle": "SYNTH_SYS_LIST.BIN", "why": "member basename"}],
           "readerTextValues": 0, "meaningsEstablished": False,
           "meaningBlockers": ["the established reader for this family returns no text value at all, "
                               "so every field it yields is an opaque identifier",
                               "no vendor documentation, runtime observation on a vehicle or ECU"]}
    row.update(over)
    return row


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
    def _run(self, root: Path, state: dict, archives: dict[str, dict[str, bytes]] | None = None,
             inventory8: dict | None = None) -> dict:
        source = root / "zips"
        source.mkdir(exist_ok=True)
        for name, members in (archives or {}).items():
            build_archive(source / name, members)
        report = root / "inventory7.json"
        report.write_text(json.dumps(state))
        extra = []
        if inventory8 is not None:
            links = root / "inventory8.json"
            links.write_text(json.dumps(inventory8))
            extra = ["--inventory8-json", str(links)]
        out = root / "out"
        argv = sys.argv
        try:
            sys.argv = ["stage2_multibrand.py", "--inventory7-json", str(report),
                        "--local-dir", str(source), "--out-dir", str(out),
                        "--commit-sha", "deadbeef"] + extra
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

    def test_inventory8_measurements_never_produce_a_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = inventory7_state({"brand": "SYNTH", "archive": "SYNTH.zip", "sha256": "s" * 64,
                                      "verdicts": [verdict()]})
            summary = self._run(root, state, {"SYNTH.zip": {"A.BIN": yzjm(system_list_payload())}},
                                inventory8=inventory8_state(link_row()))
            self.assertTrue(summary["inventory8"]["available"])
            self.assertEqual(1, summary["measured"])
            self.assertEqual(0, summary["allowed"])
            self.assertEqual(0, summary["built"])
            self.assertEqual(0, summary["app_packs_built"])
            self.assertEqual([], summary["packs"])
            self.assertEqual(1, len(summary["inventory8_rows"]))
            text = (root / "out" / "stage2-multibrand-deadbeef.md").read_text()
            self.assertIn("## Inventory-8 consumer-link measurements", text)
            self.assertIn("CODE-IDENTICAL AND LINKED", text)
            self.assertIn("None. No (archive, family) pair satisfied the gate", text)


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

class Inventory8LinkTest(unittest.TestCase):
    """Inventory-8 is measurement only: it can add evidence, never open the gate."""

    def write(self, root: Path, state: dict) -> Path:
        path = root / "inventory8.json"
        path.write_text(json.dumps(state))
        return path

    def test_no_report_is_not_an_error(self):
        rows, summary = s2.load_inventory8(None)
        self.assertEqual({}, rows)
        self.assertFalse(summary["available"])
        with tempfile.TemporaryDirectory() as tmp:
            rows, summary = s2.load_inventory8(Path(tmp) / "absent.json")
        self.assertEqual(({}, False), (rows, summary["available"]))

    def test_a_report_of_the_wrong_format_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write(Path(tmp), {"format": "bod3-family-evidence", "targets": []})
            with self.assertRaises(SystemExit) as caught:
                s2.load_inventory8(path)
        self.assertIn("not an Inventory-8 report", str(caught.exception))

    def test_rows_are_keyed_by_archive_and_family(self):
        with tempfile.TemporaryDirectory() as tmp:
            rows, summary = s2.load_inventory8(
                self.write(Path(tmp), inventory8_state(link_row(), link_row(family="keyed-index-8"))))
        self.assertTrue(summary["available"])
        self.assertTrue(summary["controlPassed"])
        self.assertEqual({("SYNTH.zip", "system-list-37"), ("SYNTH.zip", "keyed-index-8")}, set(rows))
        self.assertEqual(0, summary["meaningsEstablished"])

    def test_identical_code_and_a_link_are_satisfied_but_do_not_open_the_gate(self):
        allowed, satisfied, missing = s2.established(candidate(), {("SYNTH.zip", "system-list-37"): link_row()})
        self.assertFalse(allowed)
        self.assertTrue(any(item.startswith("consumer code:") for item in satisfied), satisfied)
        self.assertTrue(any(item.startswith("filename link:") for item in satisfied), satisfied)
        self.assertEqual(5, len(satisfied))
        self.assertTrue(missing[0].startswith("meanings: Inventory-8 measured 'CODE-IDENTICAL AND LINKED'"),
                        missing[0])
        self.assertIn("opaque identifier", " ".join(missing))

    def test_a_failed_control_never_becomes_satisfied_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            rows, summary = s2.load_inventory8(
                self.write(Path(tmp), inventory8_state(link_row(), control_passed=False)))
        self.assertFalse(summary["controlPassed"])
        allowed, satisfied, _ = s2.established(candidate(), rows)
        self.assertFalse(allowed)
        self.assertTrue(any(item.startswith("consumer code:") for item in satisfied))

    def test_a_row_without_blockers_falls_back_to_the_registry_reason(self):
        row = link_row(meaningBlockers=[], identicalConsumers=[], filenameLinks=[],
                       verdict="NOT ESTABLISHED")
        allowed, satisfied, missing = s2.established(candidate(), {("SYNTH.zip", "system-list-37"): row})
        self.assertFalse(allowed)
        self.assertEqual([], satisfied[3:])
        self.assertIn("MEANING_EVIDENCE is empty", missing[0])

    def test_an_unmeasured_pair_says_so(self):
        _, _, missing = s2.established(candidate(), {("OTHER.zip", "system-list-37"): link_row()})
        self.assertIn("Inventory-8 did not measure this pair", missing[0])

    def test_the_report_shows_the_measurements_and_still_builds_nothing(self):
        summary = {"commit_sha": "a" * 7, "control": "108/108 parsed (PASS)", "measured": 1, "allowed": 0,
                   "built": 0, "app_packs_built": 0, "packs": [], "best_candidates": [],
                   "inventory8": {"available": True, "controlPassed": True, "targets": 1,
                                  "functionsCompared": 1, "identicalConsumers": 1, "linkTargets": 1,
                                  "textTargets": 0, "meaningsEstablished": 0},
                   "inventory8_rows": [{"archive": "SYNTH.zip", "family": "system-list-37",
                                        "verdict": "CODE-IDENTICAL AND LINKED", "comparedConsumers": 1,
                                        "identicalConsumers": ["GetSystemListData"], "filenameLinks": 1,
                                        "readerTextValues": 0, "meaningsEstablished": False}],
                   "evaluations": []}
        text = s2.render(summary)
        self.assertIn("## Inventory-8 consumer-link measurements", text)
        self.assertIn("| `SYNTH.zip` | `system-list-37` | CODE-IDENTICAL AND LINKED | 1 | 1 | 1 | 0 "
                      "| NOT established |", text)
        self.assertIn("neither satisfies `meanings`", text)
        self.assertLessEqual(len(text.encode("utf-8")), s2.MAX_SUMMARY_BYTES)

    def test_the_report_says_when_inventory8_published_nothing(self):
        text = s2.render({"commit_sha": "a" * 7, "control": "PASS", "measured": 0, "allowed": 0,
                          "built": 0, "app_packs_built": 0, "packs": [], "best_candidates": [],
                          "inventory8": {"available": False}, "inventory8_rows": [], "evaluations": []})
        self.assertIn("Not available for this run", text)
        self.assertIn("still rejects every pair", text)


class RenderTest(unittest.TestCase):
    """The markdown is posted as a GitHub check-run summary, which is capped at 65535 bytes."""

    def big_summary(self, archives: int = 72, families: int = 7) -> dict:
        evaluations = []
        for index in range(archives):
            name = f"BRAND{index:03d}.zip"
            for family in fe.FAMILY_KEYS[:families]:
                row = candidate(archive=name, brand=name[:-4], family=family, verdict="NOT ESTABLISHED",
                                parsed=0, attempted=11, wrapper="experimental",
                                own_role_consumers=[], own_role_libraries=[])
                allowed, satisfied, missing = s2.established(row)
                evaluations.append({**row, "allowed": allowed, "satisfied": satisfied, "missing": missing})
        return {"commit_sha": "a" * 7, "control": "108/108 parsed (PASS)", "measured": len(evaluations),
                "allowed": 0, "built": 0, "app_packs_built": 0, "evaluations": evaluations,
                "not_attempted": 0, "table": evaluations[:s2.MAX_TABLE_ROWS],
                "table_rows": min(len(evaluations), s2.MAX_TABLE_ROWS), "packs": [],
                "best_candidates": evaluations[:12]}

    def test_a_full_corpus_report_fits_the_check_run_summary_limit(self):
        summary = self.big_summary()
        self.assertEqual(504, summary["measured"])
        text = s2.render(summary)
        self.assertLessEqual(len(text.encode("utf-8")), s2.MAX_SUMMARY_BYTES)
        self.assertLessEqual(len(text.encode("utf-8")), 65535)

    def test_every_measured_pair_is_accounted_for_in_the_markdown(self):
        import re
        summary = self.big_summary(archives=30)
        text = s2.render(summary)
        match = re.search(r"Accounted for: (\d+) pair\(s\) shown individually \+ (\d+) grouped = (\d+)", text)
        self.assertIsNotNone(match, text[:400])
        detail, grouped, total = (int(value) for value in match.groups())
        self.assertEqual(summary["measured"], total)
        self.assertEqual(summary["measured"], detail + grouped)
        self.assertIn("= every pair measured", text)
        self.assertEqual(summary["measured"], sum(int(row["pairs"]) for row in self.group_rows(text)))

    @staticmethod
    def group_rows(text: str) -> list[dict]:
        rows = []
        section = text.split("### Every other pair, grouped by its first missing requirement", 1)[1]
        for line in section.splitlines():
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if len(cells) == 4 and cells[2].isdigit():
                rows.append({"verdict": cells[0], "label": cells[1], "pairs": cells[2],
                             "archives": cells[3]})
        return rows

    def test_rejected_pairs_are_grouped_by_their_first_missing_requirement(self):
        summary = self.big_summary(archives=2, families=3)
        text = s2.render(summary)
        rows = self.group_rows(text)
        self.assertEqual(1, len(rows))
        self.assertEqual("wrapper", rows[0]["label"])
        self.assertEqual("6", rows[0]["pairs"])
        self.assertEqual("`BRAND000.zip`, `BRAND001.zip`", rows[0]["archives"])

    def test_a_parsed_candidate_is_shown_individually_not_grouped(self):
        summary = self.big_summary(archives=2, families=2)
        summary["evaluations"][0].update(verdict="CANDIDATE", parsed=1, wrapper="established",
                                         own_role_consumers=["GetSystemListData"])
        text = s2.render(summary)
        self.assertIn("| `BRAND000.zip` | `system-list-37` | CANDIDATE | 1 | established "
                      "| GetSystemListData | REJECTED |", text)
        self.assertIn("Accounted for: 1 pair(s) shown individually + 3 grouped = 4", text)

    def test_bound_keeps_short_text_untouched_and_marks_truncated_text(self):
        self.assertEqual("short\n", s2.bound("short\n", 1000))
        long = "x" * 5000
        cut = s2.bound(long, 800)
        self.assertLessEqual(len(cut.encode("utf-8")), 800)
        self.assertIn("Report truncated to fit the GitHub check-run summary limit", cut)
        self.assertIn("stage2-multibrand-<sha>.json", cut)

    def test_missing_label_shortens_runtime_reasons(self):
        row = candidate()
        row["missing"] = ["archive is not a published release asset that this run could reach: X.zip"]
        label = s2.missing_label(row)
        self.assertLessEqual(len(label), 48)
        self.assertTrue(label.endswith("..."))
        self.assertEqual("meanings", s2.missing_label({**row, "missing": ["meanings: no record"]}))
        self.assertEqual("none", s2.missing_label(candidate()))



if __name__ == "__main__":
    unittest.main()
