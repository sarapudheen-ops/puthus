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

    def test_nonmanual_source_systems_remain_auditable_without_becoming_vehicle_fallbacks(self):
        sources, labels = fixture()
        block = sources['SYSLIST_EU.BIN'][12:]
        sources['SYSLIST_EU.BIN'] = struct.pack('<IIIII', 7, 20, 8, 20 + len(block), 0xFFFFFFFF) + block + block
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory); index = Converter(sources, labels, path).finish()
            self.assertEqual(2, index['summary']['systems'])
            verify(path)
            container = path / index['container']
            inventory = read_object(container, index['sourceInventory'])
            rows = [row for ref in inventory['systemPages'] for row in read_object(container, ref)['systems']]
            self.assertEqual(2, len(rows))
            page = read_object(container, index['root'])
            for title in ('Manual Select', 'Region', 'Synthetic variant'):
                page = read_object(container, next(c for c in page['children'] if c['name'] == title)['object'])
            self.assertEqual(1, len(page['systems']))
            self.assertTrue(all(not read_object(container, r['object'])['enabled'] for r in rows))
            # Losing the separate inventory must fail full-source accounting,
            # not quietly lower the denominator to the reachable menu subset.
            del index['sourceInventory']
            (path / 'index.json').write_bytes(canonical(index) + b'\n')
            with self.assertRaisesRegex(ValueError, 'summary.*2.*actual.*1'):
                verify(path)

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
            staged = p / 'staged-index.json'
            staged.write_bytes((p / 'index.json').read_bytes())
            verify(p, index_copy=staged)
            staged.write_bytes(b'{}')
            with self.assertRaises(ValueError): verify(p, index_copy=staged)
            for include_corpus in (False, True):
                apk = p / "test.apk"
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("assets/toyota-vehicle-packs/index.json", (p / "index.json").read_bytes())
                    if include_corpus: archive.writestr("assets/toyota-source/toyota-source.zip", b"large corpus is forbidden")
                if include_corpus:
                    with self.assertRaises(ValueError): verify(p, apk=apk)
                else: verify(p, apk=apk)

    def test_index_never_accepts_unpinned_or_out_of_bounds_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d); sources, labels = fixture(); index = Converter(sources, labels, p).finish()
            for changes in ({"sourceSha256": "0" * 64}, {"container": "../escape"}, {"containerBytes": 1},
                            {"manualSelectionOnly": False}, {"catalogRoot": {**index["catalogRoot"], "bytes": True}}):
                with self.subTest(changes=changes):
                    (p / "index.json").write_bytes(canonical({**index, **changes}))
                    with self.assertRaises(ValueError): load_index(p / "index.json")


class GroupDtcEnableConversionTest(unittest.TestCase):
    """Stage 1d converter wiring: group DTC read/clear enablement (synthetic).

    Two SYSLIST rows join one compiled group (function 61, selector 0x11) and
    differ only in the caller-mode byte. Enablement requires the group pin, the
    per-system definition hash and the per-system native link projection; every
    gate is fail-closed and the outcome is recorded with literal reasons.
    """

    def corpus(self):
        from test_toyota_entries import document
        from toyota_functions import (ClearBlock, ClearQuery, FaultBlock,
                                      FaultCondition, FaultMapping, FaultQuery)
        from test_toyota_live import typed
        block = FaultBlock(33, (5,), 1, 2, 0, 1, 2, 2,
            (FaultQuery(0, 3, 0x3903, 13, (FaultCondition(0, 132, 0, 0),)),
             FaultQuery(0, 1, 1, 10, ()), FaultQuery(0, 2, 2, 11, ()),
             FaultQuery(0, 5, 43, 165, ())),
            (FaultMapping(0, 0x123, 1, 1),))
        clear = ClearBlock(0, (ClearQuery(0, 1, 1),))
        functions = (bytes([28]) + struct.pack('<7I', 61, 33, 33 + len(block.encode()), 0, 0, 0, 1)
                     + b'\xff' * 4 + block.encode() + clear.encode())
        items = [(5, b'\x10\x5f', b'\x00\x40', b'\x50\x5f'), (13, b'\x07', b'', b'\x47'),
            (10, b'\x13\xb0', b'', b'\x53'), (11, b'\x13\x80', b'\x00\xff', b'\x53'),
            (132, b'\x01\x01', b'\xff\xff\x80', b'\x41\x01\x80'), (165, b'\x0a', b'', b''),
            (136, b'\x09\x04', b'\xff\xff', b'\x49\x04'), (1, b'\x04', b'\xff', b'\x44'),
            (10404, b'\x21\xe2', b'\0\0\0\0\0\0\x20', b'\x61\xe2')]
        pool = []; frames = []; bindings = []
        for key, req, mask, rep in items:
            indexes = []
            for raw in (req, mask, rep):
                if raw: pool.append(raw); indexes.append(len(pool))
                else: indexes.append(0)
            frames.append(struct.pack('<4H', key, *indexes))
            bindings.append(struct.pack('<8H', 61, key, 0, key, 0, 0, 0, 0))
        # The exact SYSTEMENTER document() lifecycle binds templates 104/102/127.
        for key, request, reply in ((200, b'\x81', None), (201, b'\x3e', None),
                                    (202, b'\x3e', b'\x7e')):
            pool.append(request); request_at = len(pool)
            reply_at = 0
            if reply is not None:
                pool.append(reply); reply_at = len(pool)
            frames.append(struct.pack('<4H', key, request_at, 0, reply_at))
        for template, key in ((104, 200), (102, 201), (127, 202)):
            bindings.append(struct.pack('<8H', 61, template, 1, key, 0, 0, 1, 0))
        descriptors = []; at = 0
        for raw in pool:
            descriptors.append(struct.pack('<IBB', at, len(raw), 0)); at += len(raw)
        body = b''.join(descriptors + pool)
        details = []
        for identity, support, frame, expected in ((1, 0, 0, 0), (2, 0, 0, 0), (3, 1, 13, 71),
                                                   (43, 1, 10404, 32), (57, 0, 0, 0)):
            raw = bytearray(24)
            struct.pack_into('<6H', raw, 4, 61, 1, identity, frame, 0, support)
            raw[20] = expected; details.append(bytes(raw))
        labels = {"00000001": "Show Menu", "00000002": "Manual Select",
                  "00000003": "Region", "00000004": "Synthetic variant",
                  "00000005": "Synthetic ECU", "00000006": "Ecu_Data_ffff.BIN",
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
        rows = []
        for mode in (11, 3):
            system = bytearray(37)
            struct.pack_into("<I", system, 0, 61)
            struct.pack_into("<I", system, 4, 5)
            struct.pack_into("<I", system, 23, 6)
            struct.pack_into("<I", system, 28, 7)
            system[27] = 0x11; system[33] = mode
            rows.append(bytes(system))
        syslist = struct.pack("<IIIH", 7, 12, 0xFFFFFFFF, len(rows)) + b"".join(rows)
        directory = struct.pack("<I", 1) + struct.pack("<BI", 10, 9)
        ecu = directory + struct.pack("<HII", 0x1234, 2, 8) + b"12345678"
        sources = {"MENU.BIN": bytes(menu), "SYSLIST_EU.BIN": syslist,
            "FUNCDATA_EU.BIN": functions, "EU/Ecu_Data_ffff.BIN": ecu,
            "SYSTEMENTER.BIN": document(),
            "ECUPROGRAM.BIN": b'\x01' + struct.pack('<BIH', 1, 6, 1) + struct.pack('<IIB', 61, 2, 14),
            "EU/Toyota_bin_00.BIN": struct.pack('<HII', 0, len(pool), len(body)) + body,
            "EU/Toyota_bin_11.BIN": typed(17, frames),
            "EU/Toyota_bin_12.BIN": typed(0x112, bindings),
            "EU/Toyota_bin_1B.BIN": typed(0x11b, details)}
        for region in ("NA", "JP"):
            sources[f"SYSLIST_{region}.BIN"] = b"\xff" * 4
            sources[f"FUNCDATA_{region}.BIN"] = bytes([28]) + b"\xff" * 4
        return sources, labels, rows

    def evidence(self, sources):
        from toyota_entries import EntryIndex
        from toyota_frames import SourceFrames
        from toyota_lifecycle import selected_fast_kwp_lifecycle
        rows = []
        index = EntryIndex(sources["SYSTEMENTER.BIN"])
        names = {1: "EU", 2: "NA", 3: "JP"}
        for region_id, region_rows in index.regions.items():
            for function, row in region_rows.items():
                for entry in index.lists[row["listOffset"]].entries:
                    if entry.transport not in (1, 5):
                        continue
                    lifecycle = selected_fast_kwp_lifecycle(
                        index, SourceFrames(sources, names[region_id]),
                        region=region_id, function=function,
                        selector=entry.descriptor)
                    rows.append({"region": region_id, "function": function,
                                 "selector": entry.descriptor,
                                 "projectionDigest": lifecycle.projection_digest()})
        return rows

    def target_and_pin(self, sources, rows):
        import toyota_group_dtc_enable as group_dtc
        from toyota_group_dtc_enable import (GroupQualifier, definition_hash,
                                             definition_sha256)
        rep_id = f"SYSLIST_EU.BIN@{12 + 2:08x}"
        target = {'region': 'EU', 'ecuFile': 'EU/Ecu_Data_ffff.BIN',
                  'function': 61, 'selector': 0x11, 'representative': rep_id,
                  'systems': 2, 'dtcLabels': 1, 'readRequest': '13B0',
                  'clearRequest': '04',
                  'layout': {'countIndex': 0, 'recordStart': 1,
                             'recordBytes': 2, 'assemblyKind': 2,
                             'countIsPresence': False, 'modeLowBits': 1},
                  'groupKey': ('EU', 'EU/Ecu_Data_ffff.BIN', 61, 0x11)}
        libraries = {'TCODE_EN.GAG': {'00000123': 'SYNTHETIC' + chr(0) + 'Not vehicle data'},
                     'TEXT_EN.GAG': {'FFFF0048': 'Synthetic missing text'}}
        compiled = GroupQualifier(sources, libraries,
                                  {'path': '', 'groups': {}})._compile(
            'EU', rep_id, rows[0], 1)
        entry = {'region': 'EU', 'ecuFile': 'EU/Ecu_Data_ffff.BIN',
                 'function': 61, 'selector': 0x11, 'representative': rep_id,
                 'systems': 2, 'dtcLabels': 1, 'readRequest': '13B0',
                 'clearRequest': '04', 'layout': target['layout'],
                 'definitionSha256': definition_sha256(compiled['faults']),
                 'definitionHash': definition_hash(compiled['faults'])}
        return target, entry

    def run_conversion(self, *, pin_entry, evidence_rows, with_flag=True):
        from unittest import mock
        import toyota_group_dtc_enable as group_dtc
        sources, labels, rows = self.corpus()
        target, entry = self.target_and_pin(sources, rows)
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            english = p / "english"; english.mkdir()
            libraries = {'TCODE_EN.GAG': {'00000123': 'SYNTHETIC' + chr(0) + 'Not vehicle data'},
                         'TEXT_EN.GAG': {'FFFF0048': 'Synthetic missing text'}}
            for name in ('TCODE_EN.GAG', 'TEXT_EN.GAG'):
                (english / f"{name}.json").write_text(json.dumps(libraries[name]))
            pin_dir = p / "pins"; pin_dir.mkdir()
            (pin_dir / group_dtc.PIN_FILE).write_text(json.dumps(
                {'kind': 'compiled-dtc-group-enable-pin', 'formatVersion': 1,
                 'sourceSha256': group_dtc.SOURCE_SHA256, 'note': 'test',
                 'oracleProvenance': group_dtc.ORACLE_PROVENANCE,
                 'groups': [pin_entry] if pin_entry else []}))
            out = p / "out"
            with mock.patch.dict(group_dtc.TARGETS,
                                 {target['groupKey']: target}, clear=True):
                converter = Converter(sources, labels, out, english=english,
                    oracle_evidence=evidence_rows,
                    group_enable_artifacts=pin_dir if with_flag else None)
                index = converter.finish()
                records = {row['id']: row for row in converter.system_report}
                systems = {}
                for identity, ref in converter.systems.items():
                    systems[identity] = read_object(out / index['container'], ref)
                report = (out / 'group-dtc-enable.md').read_text() if with_flag else ''
        return index, records, systems, report, rows

    def test_group_systems_enable_only_with_pin_hash_and_link_proof(self):
        import toyota_group_dtc_enable as group_dtc
        sources, _labels, rows = self.corpus()
        _target, entry = self.target_and_pin(sources, rows)
        index, records, systems, report, _rows = self.run_conversion(
            pin_entry=entry, evidence_rows=self.evidence(sources))
        self.assertEqual(2, index['summary']['enabledSystems'])
        self.assertIn('enabled: 2; unresolved: 0', report)
        for identity in records:
            record = records[identity]
            self.assertEqual('enabled', record['groupDtcStatus']['status'])
            self.assertTrue(record['enabled'])
            self.assertEqual([], record['reasons'])
            self.assertEqual('proven', record['linkStatus'])
            self.assertEqual('executable', record['categoryStatus']['dtcs'])
            self.assertEqual('unresolved', record['categoryStatus']['pids'])
            self.assertEqual('unresolved', record['categoryStatus']['ecuInfo'])
            system = systems[identity]
            self.assertEqual({'pids', 'ecus'}, set(system['data']))
            diagnostics = system['data']['ecus'][0]['diagnostics']
            self.assertEqual([], diagnostics['identification'])
            faults = diagnostics['faults']
            self.assertEqual({'read', 'layout'}, set(faults))
            self.assertNotIn('clear', faults)
            self.assertNotIn('workflow', faults)
            self.assertIs(True, system['readOnly'])
            self.assertIs(False, system['groupDtcEnable']['clearShipped'])
            self.assertEqual(group_dtc.CLEAR_UNRESOLVED_REASON,
                             system['groupDtcEnable']['clearReason'])
            self.assertIn(group_dtc.CLEAR_UNRESOLVED_REASON,
                          system['data']['ecus'][0]['notes'][0])
            self.assertEqual(1, system['counts']['dtcs']['converted'])
            self.assertEqual(0, system['counts']['pids']['converted'])

    def test_missing_pin_fails_closed_with_literal_reasons(self):
        sources, _labels, rows = self.corpus()
        _target, _entry = self.target_and_pin(sources, rows)
        index, records, _systems, report, _rows = self.run_conversion(
            pin_entry=None, evidence_rows=self.evidence(sources))
        self.assertEqual(0, index['summary']['enabledSystems'])
        self.assertIn('unresolved-pin', report)
        self.assertIn('group_pin_absent_or_mismatched:no pin entry', report)
        for record in records.values():
            self.assertEqual('unresolved-pin', record['groupDtcStatus']['status'])
            self.assertFalse(record['enabled'])

    def test_unproven_link_fails_closed_even_with_matching_pin(self):
        sources, _labels, rows = self.corpus()
        _target, entry = self.target_and_pin(sources, rows)
        index, records, _systems, _report, _rows = self.run_conversion(
            pin_entry=entry, evidence_rows=[])
        self.assertEqual(0, index['summary']['enabledSystems'])
        for record in records.values():
            self.assertEqual('unresolved', record['groupDtcStatus']['status'])
            self.assertTrue(record['groupDtcStatus']['reason'].startswith(
                'link_unproven:'), record['groupDtcStatus'])
            self.assertFalse(record['enabled'])

    def test_definition_hash_drift_stays_disabled_with_literal_reason(self):
        sources, _labels, rows = self.corpus()
        _target, entry = self.target_and_pin(sources, rows)
        drifted = dict(entry, definitionHash='0' * 64)
        index, records, _systems, report, _rows = self.run_conversion(
            pin_entry=drifted, evidence_rows=self.evidence(sources))
        self.assertEqual(0, index['summary']['enabledSystems'])
        self.assertIn('unresolved-pin', report)
        for record in records.values():
            self.assertEqual('unresolved-pin', record['groupDtcStatus']['status'])
            self.assertIn('pin definitionHash',
                          record['groupDtcStatus']['reason'])
            self.assertFalse(record['enabled'])


if __name__ == "__main__":
    unittest.main()
