import copy
import gzip
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from convert_vehicle_packs import Converter, load_sources
from vehicle_bundle import BundleWriter, MAX_OBJECT_BYTES, canonical, read_object
from reviewed_hierarchy import normalize, PREFIX
from verify_vehicle_bundle import load_index, verify


def fixture():
    # Entirely synthetic; not Toyota addresses, PIDs or recipes.
    labels = {"00000001": "Show Menu", "00000002": "Manual Select", "00000003": "Region",
              "00000004": "Synthetic variant", "00000005": "Synthetic ECU", "00000006": "Ecu_Data_ffff.BIN",
              "00000007": "K-Line"}
    menu = bytearray()
    for i, task in enumerate((0xFEF0, 0xFEF1, 0xFEE9, 7)):
        children = 1 if i < 3 else 0
        node = bytearray(38 + children * 23)
        struct.pack_into("<I", node, 0, len(node))
        struct.pack_into("<I", node, 8, i + 1)
        struct.pack_into("<HH", node, 34, task, children)
        if children: struct.pack_into("<I", node, 57, len(menu) + len(node))
        menu += node
    system = bytearray(37)
    for at, value in ((0, 1), (4, 5), (23, 6), (28, 7)):
        struct.pack_into("<I", system, at, value)
    system[27] = 0x11
    syslist = struct.pack("<IIIH", 7, 12, 0xFFFFFFFF, 1) + system
    directory = struct.pack("<I", 1) + struct.pack("<BI", 10, 9)
    ecu = directory + struct.pack("<HII", 0x1234, 2, 8) + b"12345678"
    function = bytes([28]) + struct.pack("<7I", 1, 0, 0, 33, 0, 0, 1) + b"\xff" * 4
    function += struct.pack("<HH", 1, 1) + bytes(range(30)) + bytes.fromhex("022100")
    # One synthetic SYSTEMENTER directory and framed record. No addresses are
    # inferred from its opaque runtime fields.
    entry = bytes.fromhex("01 00 00 00 11 01 81 a0 28 00 00 12 34 00 02 07 07 05 3c 00 19 19 00 00 00")
    enter = bytes([1, 1]) + struct.pack("<I", 6) + struct.pack("<HII", 1, 1, 16) + entry
    sources = {"MENU.BIN": bytes(menu), "SYSLIST_EU.BIN": syslist, "FUNCDATA_EU.BIN": function,
               "EU/Ecu_Data_ffff.BIN": ecu, "SYSTEMENTER.BIN": enter}
    for region in ("NA", "JP"):
        sources[f"SYSLIST_{region}.BIN"] = b"\xff" * 4
        sources[f"FUNCDATA_{region}.BIN"] = bytes([28]) + b"\xff" * 4
    return sources, labels


class RangeBundleTest(unittest.TestCase):
    def test_byte_exact_range_bounds_and_reproducibility(self):
        with tempfile.TemporaryDirectory() as d:
            indexes = []
            for name in ("first", "second"):
                writer = BundleWriter(Path(d) / name)
                child = writer.add({"kind": "test", "text": "\0\u2603"})
                self.assertEqual(child, writer.add({"kind": "test", "text": "\0\u2603"}))
                root = writer.add({"kind": "root", "child": child})
                index = writer.finish(root, {"complete": False})
                path = Path(d) / name / index["container"]
                self.assertEqual({"kind": "test", "text": "\0\u2603"}, read_object(path, child))
                for field, value in (("offset", -1), ("bytes", 0), ("jsonBytes", 1), ("sha256", "0" * 64)):
                    with self.subTest(field=field), self.assertRaises(ValueError):
                        read_object(path, {**child, field: value})
                indexes.append(index)
            self.assertEqual(indexes[0], indexes[1])
            self.assertEqual((Path(d) / "first" / index["container"]).read_bytes(),
                             (Path(d) / "second" / index["container"]).read_bytes())

    def test_rejects_concatenated_member_even_if_hash_and_size_match(self):
        with tempfile.TemporaryDirectory() as d:
            writer = BundleWriter(Path(d))
            ref = writer.add({"a": 1}); index = writer.finish(ref, {})
            path = Path(d) / index["container"]
            extra = gzip.compress(b"{}", mtime=0)
            with path.open("ab") as f: f.write(extra)
            raw = path.read_bytes()[ref["offset"]:]
            bad = {**ref, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
            with self.assertRaises(ValueError): read_object(path, bad)

    def test_never_builds_oversized_or_nonfinite_objects(self):
        with tempfile.TemporaryDirectory() as d:
            writer = BundleWriter(Path(d))
            try:
                with self.assertRaises(ValueError): writer.add({"value": "x" * MAX_OBJECT_BYTES})
                with self.assertRaises(ValueError): writer.add({"value": float("inf")})
            finally: writer.stream.close()


class ConversionTest(unittest.TestCase):
    def test_complete_synthetic_chain_preserves_every_row_and_disables_unresolved(self):
        with tempfile.TemporaryDirectory() as d:
            sources, labels = fixture()
            converter = Converter(sources, labels, Path(d))
            index = converter.finish()
            self.assertFalse(index["summary"]["complete"])
            self.assertEqual(0, index["summary"]["executableDefinitions"])
            self.assertEqual(1, index["summary"]["manualLeavesWithSystemJoin"])
            items = [json.loads(line) for line in gzip.decompress((Path(d) / "unresolved-items.jsonl.gz").read_bytes()).splitlines()]
            self.assertEqual(4, len(items))  # exact ECU entry, two ECU rows, one declared live row
            self.assertEqual(4, len({r["id"] for r in items}))
            self.assertEqual(1, index["summary"]["systemEntryRecords"])
            self.assertEqual(1, index["summary"]["systemsWithExactEntryJoin"])
            self.assertTrue(all(r["sourceVerified"] and not r["enabled"] and r["reasons"] for r in items))
            container = Path(d) / index["container"]
            page = read_object(container, index["root"])
            for name in ("Manual Select", "Region", "Synthetic variant"):
                child = next(c for c in page["children"] if c["name"] == name)
                page = read_object(container, child["object"])
            self.assertEqual("manual-vehicle", page["kind"])
            self.assertFalse(page["hierarchyResolved"])
            self.assertEqual(7, page["sourceKey"])
            system = read_object(container, page["systems"][0]["object"])
            self.assertEqual("Synthetic ECU", system["name"])
            self.assertEqual({"converted": 0, "unresolved": 1}, system["counts"]["pids"])
            self.assertEqual({"converted": 0, "unresolved": None}, system["counts"]["coding"])
            self.assertEqual({}, system["data"])
            self.assertEqual(0x11, system["entrySource"]["selector"])
            entry = read_object(container, system["entrySource"]["pages"][0])["items"][0]
            self.assertEqual("1234", entry["sourceFields"]["runtime107b0"])
            self.assertIn(entry["id"], system["sourceRecords"])
            self.assertTrue((Path(d) / "entry-index.json").exists())
            self.assertTrue((Path(d) / "conversion-report.md").read_text().startswith("# Toyota conversion report"))

    def test_general_manual_vehicle_uses_exact_shared_systems_and_metadata_deterministically(self):
        sources, labels = fixture()
        titles = ["Show Menu", "Manual Select", "16PIN DLC (Europe and Other)", "Others", "Synthetic model", "Group X", "Variant Y"]
        tasks = [0xFEF0, 0xFEF1, 0xFEE9, 0xFEBA, 0xFEBA, 0xFEBA, 7]
        menu = bytearray()
        for i, (title, task) in enumerate(zip(titles, tasks)):
            children = int(i + 1 < len(titles)); node = bytearray(38 + children * 23)
            struct.pack_into("<I", node, 0, len(node)); struct.pack_into("<I", node, 8, 100 + i)
            struct.pack_into("<HH", node, 34, task, children)
            if children: struct.pack_into("<I", node, 57, len(menu) + len(node))
            labels[f"{100 + i:08X}"] = title; menu += node
        sources["MENU.BIN"] = bytes(menu)
        with tempfile.TemporaryDirectory() as d:
            indexes = []
            for name in ("first", "second"):
                p = Path(d) / name; p.mkdir()
                index = Converter(sources, labels, p).finish(); indexes.append(index)
                self.assertEqual(1, index["summary"]["normalizedVehicleChoices"])
                self.assertEqual(0, index["summary"]["unresolvedHierarchyChoices"])
                container = p / index["container"]; page = read_object(container, index["catalogRoot"])
                for level in ("make", "model", "generation", "variant"):
                    child, = page["children"]; self.assertEqual(level, child["level"])
                    page = read_object(container, child["object"])
                self.assertNotIn("systems", page)
                source = read_object(container, page["systemsSource"])
                self.assertEqual(page["sourceRecords"][-1], source["id"])
                self.assertEqual(page["systemsSourceId"], source["id"])
                self.assertEqual(page["sourceSelection"], [s["name"] for s in source["selection"]])
                system = read_object(container, source["systems"][0]["object"])
                ecu = read_object(container, system["ecuSource"]["object"])
                function = read_object(container, system["functionSource"]["object"])
                self.assertEqual("EU/Ecu_Data_ffff.BIN", ecu["sourceFile"])
                self.assertEqual("ecu-source", ecu["kind"])
                self.assertEqual("function-source", function["kind"])
                self.assertEqual(1, function["declaredLiveRows"])
                self.assertTrue(ecu["pages"] and function["livePages"])
                self.assertNotIn("pages", system["ecuSource"])
                self.assertNotIn("livePages", system["functionSource"])
                verify(p)
                from check_pack_delivery import check
                result = check(p, lambda _, __, ref: read_object(container, ref))
                self.assertEqual(7, result["requests"])
                self.assertEqual(system["id"], result["systemId"])
            self.assertEqual(indexes[0], indexes[1])

    def test_malformed_known_layout_is_not_forgiven_or_padded(self):
        with tempfile.TemporaryDirectory() as d:
            sources, labels = fixture()
            sources["FUNCDATA_EU.BIN"] = sources["FUNCDATA_EU.BIN"][:-1]
            converter = Converter(sources, labels, Path(d))
            try:
                with self.assertRaises(ValueError): converter.finish()
                self.assertFalse((Path(d) / "index.json").exists())
            finally:
                converter.ledger.close(); converter._ledger_stream.close(); converter.writer.stream.close()

    def test_missing_ecu_never_uses_generic_fallback(self):
        with tempfile.TemporaryDirectory() as d:
            sources, labels = fixture()
            del sources["EU/Ecu_Data_ffff.BIN"]
            converter = Converter(sources, labels, Path(d))
            converter.finish()
            records = [json.loads(line) for line in gzip.decompress((Path(d) / "systems.jsonl.gz").read_bytes()).splitlines()]
            self.assertIn("missing_ecu_file", records[0]["reasons"])

    def test_missing_exact_selector_never_uses_same_low_nibble_or_another_ecu(self):
        with tempfile.TemporaryDirectory() as d:
            sources, labels = fixture()
            bad = bytearray(sources["SYSLIST_EU.BIN"])
            bad[14 + 27] = 0x51
            sources["SYSLIST_EU.BIN"] = bytes(bad)
            index = Converter(sources, labels, Path(d)).finish()
            self.assertEqual(0, index["summary"].get("systemsWithExactEntryJoin", 0))
            records = [json.loads(line) for line in gzip.decompress((Path(d) / "systems.jsonl.gz").read_bytes()).splitlines()]
            self.assertIn("missing_system_entry", records[0]["reasons"])
            self.assertEqual(0, index["summary"]["executableDefinitions"])

    def test_original_archive_is_pinned_before_any_output(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / "archive").write_bytes(b"unverified source")
            with self.assertRaises(ValueError): load_sources(p / "archive", p / "decoded", p / "manifest")


class BringUpCandidateConversionTest(unittest.TestCase):
    def candidates(self, sources):
        with tempfile.TemporaryDirectory() as d:
            _, labels = fixture()
            converter = Converter(sources, labels, Path(d))
            index = converter.finish()
            records = [json.loads(line) for line in gzip.decompress((Path(d) / "systems.jsonl.gz").read_bytes()).splitlines()]
            container = Path(d) / index["container"]
            system = read_object(container, converter.systems[records[0]["id"]])
            candidates = [c for ref in system["bringUpPages"] for c in read_object(container, ref)["candidates"]]
            verify(Path(d))
            return system, candidates

    def alternatives(self):
        sources, _ = fixture()
        record = sources["SYSTEMENTER.BIN"][20:]
        alt = bytearray(record); alt[0] = 0x51; alt[1] = 5
        first = bytes([2, 0, 0, 0]) + record + alt
        second = bytes([1, 0, 0, 0]) + record
        sources["SYSTEMENTER.BIN"] = (bytes([1, 1]) + struct.pack("<I", 6) + struct.pack("<HIIII", 2, 1, 24, 2, 24 + len(first))
                                         + first + second)
        return sources

    def test_every_source_alternative_ranked_by_distinct_record_support(self):
        system, candidates = self.candidates(self.alternatives())
        self.assertEqual(2, len(candidates))
        self.assertEqual([2, 1], [c["supportCount"] for c in candidates])
        self.assertTrue(all(c["supportCount"] == len(set(c["supportRecords"])) for c in candidates))
        self.assertEqual(["11", "51"], [c["metadata"]["selector"] for c in candidates])
        self.assertTrue(any("Selector differs" in why for why in candidates[1]["blockers"]))
        self.assertFalse(any("Selector differs" in why for why in candidates[0]["blockers"]))
        self.assertEqual({}, system["data"])
        self.assertFalse(system["enabled"])

    def test_opaque_fields_are_preserved_not_invented_as_runtime_protocol_or_timings(self):
        _, candidates = self.candidates(fixture()[0])
        c = candidates[0]
        self.assertIsNone(c["runtime"])
        self.assertTrue(c["blockers"])
        self.assertEqual("10400", c["metadata"]["baudRaw"])
        self.assertIn("1234", c["metadata"]["addresses"])
        self.assertIn("053c001919", c["metadata"]["timing"])
        self.assertIn("Unmapped native transport", c["metadata"]["protocol"])
        self.assertIn("not cross-vehicle applicability", c["metadata"]["supportBasis"])

    def test_missing_exact_selector_still_lists_evidence_but_blocks_all_alternatives(self):
        sources = self.alternatives()
        raw = bytearray(sources["SYSLIST_EU.BIN"]); raw[14 + 27] = 0x31; sources["SYSLIST_EU.BIN"] = bytes(raw)
        system, candidates = self.candidates(sources)
        self.assertIsNone(system["entrySource"])
        self.assertEqual(2, len(candidates))
        self.assertTrue(all(any("Selector differs" in why for why in c["blockers"]) for c in candidates))
        self.assertTrue(all(c["runtime"] is None for c in candidates))

    def test_no_function_directory_never_substitutes_another_ecu(self):
        sources, _ = fixture()
        raw = bytearray(sources["SYSLIST_EU.BIN"]); struct.pack_into("<I", raw, 14, 99); sources["SYSLIST_EU.BIN"] = bytes(raw)
        system, candidates = self.candidates(sources)
        self.assertEqual([], candidates)
        self.assertEqual([], system["bringUpPages"])


class HierarchyReviewTest(unittest.TestCase):
    def test_exact_reviewed_path_not_numeric_task_coincidence(self):
        record = {"id": "MENU.BIN@00871ee2", "region": "EU", "sourceKey": 433,
                  "path": list(PREFIX) + ["-2009.07", "w/o VSC (Vehicle Stability Control)"]}
        pack = normalize(record)
        self.assertEqual(["make", "model", "generation", "variant"], [n["level"] for n in pack["nodes"]])
        self.assertEqual("make-67336a6fdee431883a1a", pack["nodes"][0]["id"])
        self.assertFalse(any(k in pack["nodes"][2] for k in ("yearFrom", "yearTo")))
        self.assertFalse(any(k in pack["nodes"][3] for k in ("engine", "fuel", "transmission")))
        self.assertEqual(pack, normalize(copy.deepcopy(record)))
        self.assertIsNone(normalize({**record, "region": "JP"}))
        self.assertIsNone(normalize({**record, "path": ["Unreviewed"] + record["path"][1:]}))
        with self.assertRaises(ValueError): normalize({**record, "sourceKey": 938})

    def test_month_window_is_preserved_not_relabelled_as_an40(self):
        record = {"id": "MENU.BIN@00871e1c", "region": "EU", "sourceKey": 1538,
                  "path": list(PREFIX) + ["2009.07-2017.07", "w/ VSC (Vehicle Stability Control)"]}
        pack = normalize(record)
        generation = pack["nodes"][2]
        self.assertEqual((2009, 2017), (generation["yearFrom"], generation["yearTo"]))
        self.assertIn("2009.07-2017.07", generation["name"])
        self.assertIn("not infer AN40", generation["notes"])


class BundleVerificationTest(unittest.TestCase):
    def test_full_synthetic_bundle_and_tiny_apk_pin_only(self):
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            p = Path(d); sources, labels = fixture()
            index = Converter(sources, labels, p).finish()
            self.assertEqual(0, verify(p)["executableDefinitions"])
            self.assertLess((p / "index.json").stat().st_size, 4096)
            self.assertEqual(index, load_index(p / "index.json"))
            for include_corpus in (False, True):
                apk = p / "test.apk"
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("assets/toyota-vehicle-packs/index.json", (p / "index.json").read_bytes())
                    if include_corpus: archive.writestr("assets/toyota-source/toyota-source.zip", b"large corpus is forbidden")
                if include_corpus:
                    with self.assertRaises(ValueError): verify(p, index_only=True, apk=apk)
                else: verify(p, index_only=True, apk=apk)

    def test_index_never_accepts_unpinned_or_out_of_bounds_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d); sources, labels = fixture(); index = Converter(sources, labels, p).finish()
            for changes in ({"sourceSha256": "0" * 64}, {"container": "../escape"}, {"containerBytes": 1},
                            {"manualSelectionOnly": False}, {"catalogRoot": {**index["catalogRoot"], "bytes": True}}):
                with self.subTest(changes=changes):
                    (p / "index.json").write_bytes(canonical({**index, **changes}))
                    with self.assertRaises(ValueError): load_index(p / "index.json")


if __name__ == "__main__":
    unittest.main()
