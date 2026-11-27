import unittest
from unittest.mock import patch

from stage4a_schema_probe import census, live_skeleton, markdown, selected, shape


class FakeBlocks:
    def __init__(self):
        self.raw = b'\x01\x00\x02\x00' + bytes(30) + b'\x02\x21\x01\x01\x05'
        self.rows = {7: (7,)}
    def span(self, key, slot):
        return (0, len(self.raw)) if slot == 3 else ((0, 5) if slot == 4 else None)


class SchemaProbeTests(unittest.TestCase):
    def test_live_skeleton_and_literal_bytes(self):
        b = FakeBlocks()
        skeleton, commands = live_skeleton(b.raw, 0, len(b.raw))
        self.assertEqual({'bytes': 39, 'rows': 1, 'rowBytes': 30, 'commandLengths': [2, 1]}, skeleton)
        self.assertEqual(['2101', '05'], commands)
        self.assertEqual(skeleton, shape(b, 7, 3))

    def test_live_truncated_or_untranslated_tail_fails_closed(self):
        b = FakeBlocks()
        with self.assertRaises(ValueError): live_skeleton(b.raw, 0, len(b.raw)-1)
        with self.assertRaises(ValueError): live_skeleton(b.raw + b'\x00', 0, len(b.raw)+1)

    def test_weighted_counts_and_unknown_are_not_claimed_layout(self):
        b = FakeBlocks()
        rows = [{'region': 'EU', 'functionKey': 7}] * 3
        counts = census(rows, {'EU': b})
        self.assertEqual(3, sum(counts[3].values()))
        self.assertIn('NOT ESTABLISHED', next(iter(counts[4])))
        self.assertEqual(0, sum(counts[1].values()))

    def test_proven_partition_control(self):
        with self.assertRaisesRegex(ValueError, 'expected 39363'): selected([])
        rows = [{'nativeTransport': 1, 'linkStatus': 'proven'}] * 39363
        self.assertEqual(39363, len(selected(rows)))
        self.assertEqual(39363, len(selected(rows + [{'nativeTransport': 5, 'linkStatus': 'proven'}])))

    def test_report_never_calls_unknown_shapes_established(self):
        data = {'headSha': 'synthetic', 'systems': 1, 'slots': [{'slot': 4, 'category': 'category NOT ESTABLISHED',
            'evidence': 'toyota_structure.py:275-287', 'systemsWithBlock': 1, 'distinctObservedSkeletons': None,
            'top10': [{'shape': {'spanBytes': 5, 'recordSkeleton': 'NOT ESTABLISHED'}, 'systems': 1}],
            'otherSystems': 0}], 'ecmect': []}
        report = markdown(data)
        self.assertIn('NOT ESTABLISHED', report)
        self.assertIn('span-length buckets only', report)

if __name__ == '__main__': unittest.main()
