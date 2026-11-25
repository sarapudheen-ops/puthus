import struct
import unittest

from toyota_can_link_sweep import (address_evidence, can_tuples, classify, markdown,
                                   memory_matches, oracle_verdict, probe_selection, reconciliation)
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
