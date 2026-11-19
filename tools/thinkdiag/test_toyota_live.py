"""Synthetic row/layout regressions; recovered-source integration runs in Actions."""
import copy
import struct
import unittest

from toyota_live import Direct21Live, P4SupportFiles, UntranslatedLive, review_direct21, translate_arithmetic


def fixture(*, position=2, width=2):
    # Invented fixture identities, never a recovered vehicle/address mapping.
    source = bytearray(30)
    struct.pack_into("<IIHHHHI", source, 0, 1, 77, 0, 7, 123, 456, 789)
    source[20:26] = bytes((0, 3, 0, position, 255, 2))
    struct.pack_into("<I", source, 26, 2)
    functions = (bytes([28]) + struct.pack("<7I", 1, 0, 0, 33, 0, 0, 1) + b"\xff" * 4
                 + struct.pack("<HH", 1, 1) + source + bytes.fromhex("02 21 07"))
    monitor = bytearray(104)
    struct.pack_into("<dd", monitor, 0x18, 5000.0, -100.0)
    monitor[0x44:0x48] = source[4:8]
    monitor[0x63:0x65] = source[10:12]
    monitor[0x4c:0x4e] = source[12:14]
    monitor[0x58:0x5a] = source[14:16]
    monitor[0x48:0x4c] = source[16:20]
    monitor[0x61:0x63] = source[20:22]
    monitor[0x66] = source[22]
    monitor[0x60] = 2
    struct.pack_into("<HH", monitor, 0x52, (position - 1) * 8, (position - 1 + width) * 8 - 1)
    # +50 is NOT the EXPRESS key; it is deliberately unrelated.
    struct.pack_into("<H", monitor, 0x50, 999)
    lsb = bytearray(20)
    struct.pack_into("<H", lsb, 8, 888)

    def ecu(rows, overrides, *, groups=None, commands=None):
        groups = [bytes.fromhex("00 00 00 00 00 01 00 00")] if groups is None else groups
        commands = [bytes.fromhex("00 00 07 03 00 00 00 00")] if commands is None else commands
        sections = [(3, 3, groups), (6, 6, commands), (10, 10, rows), (38, 39, overrides)]
        at = 4 + len(sections) * 5
        directory, payload = b"", b""
        for slot, tag, body in sections:
            raw = struct.pack("<HII", tag, len(body), sum(map(len, body))) + b"".join(body)
            directory += struct.pack("<BI", slot, at)
            payload += raw
            at += len(raw)
        return struct.pack("<I", len(sections)) + directory + payload

    return functions, monitor, lsb, ecu


def typed(tag, rows):
    return struct.pack("<HII", tag, len(rows), sum(map(len, rows))) + b"".join(rows)


def support_sources():
    # All identities, frame IDs and values here are synthetic, not source-derived mappings.
    sequences = [b"\xa8\x01", b"\xe8\x01", b"\x21\x00", b"\x61"]
    descriptors, blob = b"", b""
    for sequence in sequences:
        descriptors += struct.pack("<IBB", len(blob), len(sequence), 0)
        blob += sequence
    return {
        "EU/Toyota_bin_00.BIN": struct.pack("<HII", 0, len(sequences), len(descriptors + blob)) + descriptors + blob,
        "EU/Toyota_bin_11.BIN": typed(17, [struct.pack("<4H", 21, 1, 0, 2), struct.pack("<4H", 22, 3, 0, 4)]),
        "EU/Toyota_bin_12.BIN": typed(274, [struct.pack("<8H", 1, 19, 1, 21, 0, 0, 1, 0),
                                            struct.pack("<8H", 1, 15, 1, 22, 0, 0, 1, 0)]),
        "EU/Viewer_bin_12.BIN": typed(18, []),
    }


def parser(functions, monitors, overrides, ecu, streams=None, expressions=None, *, support=None, with_support=True):
    support = P4SupportFiles(support_sources() if support is None else support, "EU", 1) if with_support else None
    return Direct21Live("FUNCDATA_FIXTURE.BIN", functions, 1, "EU/Ecu_Data_ffff.BIN", ecu(monitors, overrides),
                        streams or {"00000001": "Synthetic signal\x00unit"},
                        expressions or {"00000002": "y=(256*x1+x2)/4;"}, support_files=support)


class ArithmeticTest(unittest.TestCase):
    def test_exact_unsigned_byte_names_and_real_arithmetic_grouping(self):
        result = translate_arithmetic(" y = (0x100*x1+x2)/4; ")
        self.assertEqual("(((256*A)+B)/4)", result.formula)
        self.assertEqual(2, result.bytes_needed)
        self.assertEqual("(A-40)", translate_arithmetic("y=x-40;").formula)
        self.assertEqual("(((-A)+B)*0.125)", translate_arithmetic("y=(-x+x2)*0.125;").formula)
        self.assertEqual("(J+Z)", translate_arithmetic("y=x10+x26;").formula)
        self.assertEqual("(0.00000000000000000001*A)", translate_arithmetic("y=1e-20*x;").formula)

    def test_no_vendor_or_python_program_is_evaluated(self):
        for text in ("y=__import__('os').system('true');", "y=x[0];", "y=x.bit0;", "y=string(0,0,0,1);",
                     "switch(x)0:y=x;", "y=x;y=x2;", "y=x27;", "y=x0;", "y=x**2;", "y=x//2;", "y=x&1;",
                     "y=True+x;", "y=42;", "y=1e1000*x;", "y=x if x2 else 0;", "y=a+x;", "x=x+1;"):
            with self.subTest(text=text), self.assertRaises(UntranslatedLive): translate_arithmetic(text)
        with self.assertRaises(UntranslatedLive): translate_arithmetic("y=" + "+".join(["x"] * 200) + ";")


class DirectLiveTest(unittest.TestCase):
    def test_exact_monitor_request_formula_window_units_and_provenance(self):
        f, m, l, ecu = fixture()
        result = parser(f, [m], [l], ecu).translate(0)
        self.assertEqual(("2107", "(((256*A)+B)/4)", 1),
                         (result["request"], result["formula"], result["dataOffset"]))
        self.assertNotIn("supportFormula", result)
        snapshot, discovery = result["supportChecks"]
        self.assertEqual(("A801", 7, 1, "A+B"),
                         (snapshot["request"], snapshot["recordId"], snapshot["dataOffset"], snapshot["formula"]))
        self.assertEqual(("2100", "bit(A,1)"), (discovery["request"], discovery["formula"]))
        self.assertTrue(all(c["sourceRecords"] for c in result["supportChecks"]))
        self.assertFalse(result["enabled"])
        self.assertEqual(["ecu_entry_untranslated", "calc_semantics_unreviewed"], result["reasons"])
        self.assertEqual("numeric-live-review", result["kind"])
        self.assertEqual(("Synthetic signal", "unit", -100.0, 5000.0),
                         (result["name"], result["unit"], result["min"], result["max"]))
        self.assertIn("EXPRESS.GAG#00000002", result["sourceRecords"])
        self.assertNotIn("EXPRESS.GAG#000003E7", result["sourceRecords"])
        self.assertTrue(result["id"].startswith("FUNCDATA_FIXTURE.BIN@00000025"))
        self.assertEqual(7, len(result["sourceRecords"]))

    def test_complete_join_and_full_window_are_required_not_a_similar_label(self):
        f, m, l, ecu = fixture()
        for offset in (0x44, 0x48, 0x4c, 0x58, 0x61, 0x62, 0x63, 0x66):
            bad = bytearray(m); bad[offset] ^= 1
            with self.subTest(offset=offset), self.assertRaises(UntranslatedLive): parser(f, [bad], [l], ecu).translate(0)
        with self.assertRaises(UntranslatedLive): parser(f, [m, m], [l], ecu).translate(0)
        bad = bytearray(m); struct.pack_into("<H", bad, 0x52, 9)
        with self.assertRaises(UntranslatedLive): parser(f, [bad], [l], ecu).translate(0)
        with self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, expressions={"00000002": "y=x;"}).translate(0)

    def test_dynamic_scaling_is_never_mislabelled_as_plain_express(self):
        f, m, l, ecu = fixture()
        override = bytearray(l); struct.pack_into("<H", override, 8, 123)
        with self.assertRaises(UntranslatedLive) as error: parser(f, [m], [override], ecu).translate(0)
        self.assertEqual("dynamic_scaling_untranslated", error.exception.reason)
        # A different monitor/target ID is not an override of this signal.
        self.assertEqual("2107", parser(f, [m], [l], ecu).translate(0)["request"])

    def test_missing_support_inputs_never_create_an_unconditional_or_sensor_nonzero_pid(self):
        f, m, l, ecu = fixture()
        with self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, with_support=False).translate(0)
        for name in support_sources():
            sources = support_sources(); del sources[name]
            with self.subTest(name=name), self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, support=sources).translate(0)
        for groups, commands in (([], None), (None, []), (None, [bytes.fromhex("00 00 08 02 00 00 00 00")]),
                                 ([bytes.fromhex("00 00 00 00 01 01 00 00")], None)):
            def modified(rows, overrides): return ecu(rows, overrides, groups=groups, commands=commands)
            with self.subTest(groups=groups, commands=commands), self.assertRaises(UntranslatedLive): parser(f, [m], [l], modified).translate(0)

    def test_support_join_needs_exact_function_and_read_reply_not_a_similar_binding(self):
        f, m, l, ecu = fixture()
        for filename, offset in (("Toyota_bin_12.BIN", 10), ("Toyota_bin_12.BIN", 12),
                                 ("Toyota_bin_12.BIN", 16), ("Toyota_bin_12.BIN", 18),
                                 ("Toyota_bin_11.BIN", 12), ("Toyota_bin_11.BIN", 14), ("Toyota_bin_11.BIN", 16)):
            sources = support_sources(); key = "EU/" + filename
            bad = bytearray(sources[key]); bad[offset] ^= 1; sources[key] = bytes(bad)
            with self.subTest(file=filename, offset=offset), self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, support=sources).translate(0)
        sources = support_sources()
        sources["EU/Toyota_bin_12.BIN"] = typed(274, [struct.pack("<8H", 1, 19, 1, 21, 0, 0, 1, 0)] * 2)
        with self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, support=sources).translate(0)

    def test_viewer_exclusions_are_not_inferred_from_a_missing_file_or_other_function(self):
        f, m, l, ecu = fixture()
        sources = support_sources(); sources["EU/Viewer_bin_12.BIN"] = typed(18, [struct.pack("<HH", 1, 123)])
        with self.assertRaises(UntranslatedLive) as error: parser(f, [m], [l], ecu, support=sources).translate(0)
        self.assertEqual("native_applicability_excluded", error.exception.reason)
        sources["EU/Viewer_bin_12.BIN"] = typed(18, [struct.pack("<HH", 2, 123)])
        self.assertFalse(parser(f, [m], [l], ecu, support=sources).translate(0)["enabled"])
        context = P4SupportFiles(sources, "EU", 1)
        for signal in (8000, 9999, 40000, 41999):
            with self.assertRaises(UntranslatedLive): context.not_excluded(signal)
        for signal in (7999, 10000, 39999, 42000): self.assertTrue(context.not_excluded(signal))

    def test_discovery_group_base_is_admitted_only_by_its_own_reply(self):
        f, m, l, ecu = fixture()
        # Synthetic group base changes to 0x20, and the sensor itself to that local ID.
        f = bytearray(f); f[-1] = 0x20; struct.pack_into("<H", f, 37 + 10, 0x20)
        m = bytearray(m); struct.pack_into("<H", m, 0x63, 0x20)
        def modified(rows, overrides):
            return ecu(rows, overrides, groups=[bytes.fromhex("00 00 00 00 20 01 00 00")],
                       commands=[bytes.fromhex("00 00 20 03 00 00 00 00")])
        result = parser(f, [m], [l], modified).translate(0)
        self.assertEqual("2120", result["supportChecks"][1]["request"])
        self.assertEqual("A*0+1", result["supportChecks"][1]["formula"])
        # A numeric 5-byte window is outside native CaculateBit's reviewed 32-bit class.
        f, m, l, ecu = fixture(width=5)
        with self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, expressions={"00000002": "y=x1+x2+x3+x4+x5;"}).translate(0)

    def test_command_lengths_prune_instead_of_reading_beyond_the_native_poll_window(self):
        f, m, l, ecu = fixture()
        for length in (0, 1, 2, 3, 4, 255):
            def modified(rows, overrides):
                return ecu(rows, overrides, commands=[bytes((0, 0, 7, length, 0, 0, 0, 0))])
            with self.subTest(length=length):
                if length in (1, 2):
                    with self.assertRaisesRegex(UntranslatedLive, "polling length"):
                        parser(f, [m], [l], modified).translate(0)
                else:
                    result = parser(f, [m], [l], modified).translate(0)
                    # Zero is a runtime A801-cache length, not an unconditional row.
                    self.assertEqual((7, 1, "A+B"), tuple(result["supportChecks"][0][k]
                                     for k in ("recordId", "dataOffset", "formula")))
                    self.assertFalse(result["enabled"])

    def test_native_numeric_review_never_removes_the_ecu_entry_blocker(self):
        from test_toyota_calc import calc_evidence
        f, m, l, ecu = fixture()
        sources = {**support_sources(), "FUNCDATA_EU.BIN": f, "EU/Ecu_Data_ffff.BIN": ecu([m], [l])}
        args = dict(region="EU", function_key=1, ecu_file="EU/Ecu_Data_ffff.BIN")
        streams, expressions = {"00000001": "Synthetic\x00unit"}, {"00000002": "y=(0x100*x1+x2)/4;"}
        result = review_direct21(sources, streams, expressions, calc_evidence=calc_evidence(), **args)
        self.assertEqual(1, result["calcReviewedCandidates"])
        self.assertEqual(0, result["executableDefinitions"])
        item = result["items"][0]
        self.assertEqual(["ecu_entry_untranslated"], item["reasons"])
        self.assertFalse(item["enabled"])
        self.assertEqual("u16be-quarter-v1", item["calcReview"]["id"])
        self.assertTrue(set(item["calcReview"]["sourceRecords"]) <= set(item["sourceRecords"]))
        expressions["00000002"] = "y=(256*x1+x2)/8;"  # valid arithmetic, not one of the two reviewed programs
        result = review_direct21(sources, streams, expressions, calc_evidence=calc_evidence(), **args)
        self.assertEqual(0, result["calcReviewedCandidates"])
        self.assertEqual(["ecu_entry_untranslated", "calc_semantics_unreviewed"], result["items"][0]["reasons"])
        bad = calc_evidence(); bad["librarySha256"] = "0" * 64
        with self.assertRaises(ValueError): review_direct21(sources, streams, expressions, calc_evidence=bad, **args)

    def test_inspection_accounts_for_all_rows_without_producing_an_executable_pack(self):
        f, m, l, ecu = fixture()
        sources = {**support_sources(), "FUNCDATA_EU.BIN": f, "EU/Ecu_Data_ffff.BIN": ecu([m], [l])}
        streams, expressions = {"00000001": "Synthetic\x00unit"}, {"00000002": "y=(256*x1+x2)/4;"}
        def review():
            return review_direct21(sources, streams, expressions, region="EU", function_key=1, ecu_file="EU/Ecu_Data_ffff.BIN")
        result = review()
        self.assertEqual((1, 1, 0, 0), tuple(result[k] for k in
                         ("sourceRows", "disabledReviewCandidates", "rejectedRows", "executableDefinitions")))
        self.assertEqual(6, len(result["inputs"]))
        self.assertEqual(0, result["calcReviewedCandidates"])
        self.assertNotIn("data", result)
        self.assertEqual("disabled-live-review", result["kind"])
        self.assertFalse(result["items"][0]["enabled"])
        expressions["00000002"] = "y=untranslated(x);"
        result = review()
        self.assertEqual((1, 0, 1), tuple(result[k] for k in ("sourceRows", "disabledReviewCandidates", "rejectedRows")))
        self.assertEqual({"formula_untranslated": 1}, result["rejectionsByFirstReason"])
        item = result["items"][0]
        self.assertEqual("FUNCDATA_EU.BIN@00000025#function1:live0", item["id"])
        self.assertEqual([item["id"]], item["sourceRecords"])
        self.assertFalse(item["enabled"])
        del sources["EU/Viewer_bin_12.BIN"]
        with self.assertRaises(UntranslatedLive): review()  # missing exclusion evidence is not an empty report

    def test_bad_boundaries_tail_command_and_label_do_not_become_partial_features(self):
        f, m, l, ecu = fixture()
        for bad in (f[:-1], f + b"\0", f[:-2] + b"\x22\x07", f[:-1] + b"\x08"):
            with self.subTest(length=len(bad)), self.assertRaises(ValueError): parser(bad, [m], [l], ecu).translate(0)
        with self.assertRaises(UntranslatedLive): parser(f, [m], [l], ecu, streams={"00000001": "x\0y\0z"}).translate(0)
        bad = bytearray(m); struct.pack_into("<H", bad, 0x5c, 1)
        with self.assertRaises(UntranslatedLive): parser(f, [bad], [l], ecu).translate(0)
        bad = bytearray(m); struct.pack_into("<d", bad, 0x18, float("nan"))
        with self.assertRaises(UntranslatedLive): parser(f, [bad], [l], ecu).translate(0)


if __name__ == "__main__":
    unittest.main()
