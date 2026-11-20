"""Synthetic framing/round-trip tests. Source comparisons run in the pinned CI oracle."""
import struct
import unittest

from toyota_functions import (ClearBlock, ClearQuery, FaultBlock, FaultCondition,
                              FaultMapping, FaultQuery, FunctionBlocks)
from toyota_frames import SourceFrames
from test_toyota_live import support_sources, typed


def fault_fixture(offset=0):
    return FaultBlock(offset, (5, 104), 1, 2, 0, 1, 2, 2,
        (FaultQuery(offset + 12, 3, 0x3903, 13, (FaultCondition(offset + 18, 132, 7, 9),)),
         FaultQuery(offset + 22, 1, 1, 10, ())),
        (FaultMapping(offset + 30, 0x10203, 8, 1), FaultMapping(offset + 36, 0, 0, 0)))


class FunctionBlocksTest(unittest.TestCase):
    def test_exact_fault_and_clear_round_trip_including_all_offsets(self):
        original = fault_fixture(7); raw = b'prefix!'; body = original.encode()
        actual = FaultBlock.parse(raw + body + b'tail', 7, 7 + len(body))
        self.assertEqual(original, actual)
        self.assertEqual(body, actual.encode())
        clear = ClearBlock(3, (ClearQuery(4, 1, 258), ClearQuery(7, 2, 1)))
        self.assertEqual(clear, ClearBlock.parse(b'pad' + clear.encode(), 3, 3 + len(clear.encode())))

    def test_every_truncation_and_trailing_byte_is_rejected(self):
        for obj, parser in ((fault_fixture(), FaultBlock),
                (ClearBlock(0, (ClearQuery(1, 1, 1),)), ClearBlock)):
            data = obj.encode()
            for n in range(len(data)):
                with self.subTest(kind=parser.__name__, n=n), self.assertRaises(ValueError):
                    parser.parse(data[:n], 0, n)
            with self.assertRaisesRegex(ValueError, 'Unconsumed'):
                parser.parse(data + b'\0', 0, len(data) + 1)
            for start, stop in ((-1, len(data)), (0, len(data) + 1), (1, 0), (True, 3)):
                with self.assertRaises(ValueError): parser.parse(data, start, stop)

    def test_indexed_bounds_shared_payload_and_absence_are_distinct(self):
        faults, clear = fault_fixture().encode(), b'\x01\x01\x02\x01'
        start = 1 + 2 * 28 + 4; second = start + len(faults)
        header = (bytes([28]) + struct.pack('<7I', 1, start, second, 0, 0, 0, 1)
            + struct.pack('<7I', 2, start, 0xffffffff, 0, 0, 0, 1) + b'\xff' * 4)
        functions = FunctionBlocks(header + faults + clear)
        self.assertEqual((start, second), functions.span(1, 1))
        self.assertEqual(functions.faults(1), functions.faults(2))
        self.assertIsNone(functions.clear(2))
        self.assertEqual(258, functions.clear(1).queries[0].template)
        self.assertEqual({1: 1, 2: 29}, functions.index_offsets)
        with self.assertRaises(ValueError): functions.span(1, 6)
        with self.assertRaises(KeyError): functions.faults(3)
        for n in (len(header) - 1, len(header) + len(faults) - 1):
            with self.assertRaises(ValueError): FunctionBlocks((header + faults + clear)[:n])


class SourceFramesTest(unittest.TestCase):
    def test_exact_function_template_frame_roles_and_provenance(self):
        source = SourceFrames(support_sources(), 'EU')
        frame, = source.function(1, 19)
        self.assertEqual((b'\xa8\x01', b'', b'\xe8\x01'), (frame.request, frame.mask, frame.response))
        self.assertEqual('EU/Toyota_bin_12.BIN@0000000a', frame.source_records[0])
        self.assertEqual('EU/Toyota_bin_11.BIN@0000000a', frame.source_records[1])
        self.assertIn('EU/Toyota_bin_00.BIN@0000000a#pool1', frame.source_records)
        self.assertEqual([], source.function(2, 19))
        self.assertEqual([], source.function(1, 18))
        for region in ('JP', 'NA'):
            with self.assertRaises(KeyError): SourceFrames(support_sources(), region)
        with self.assertRaises(ValueError): SourceFrames(support_sources(), 'Other')

    def test_regional_table_tags_and_bytes_are_not_replaced_by_eu_defaults(self):
        for region in ('JP', 'NA'):
            sources = support_sources()
            for file, data in list(sources.items()):
                if file.startswith('EU/'):
                    sources[region + file[2:]] = data
            name = region + '/Toyota_bin_12.BIN'
            with self.assertRaisesRegex(ValueError, 'tag disagrees'):
                SourceFrames(sources, region)
            sources[name] = b'\x12\x00' + sources[name][2:]
            frame, = SourceFrames(sources, region).function(1, 19)
            self.assertEqual(region + '/Toyota_bin_12.BIN@0000000a', frame.source_records[0])
            sources[region + '/Toyota_bin_00.BIN'] = sources[region + '/Toyota_bin_00.BIN'].replace(b'\xa8\x01', b'\x13\xb0')
            self.assertEqual(b'\x13\xb0', SourceFrames(sources, region).function(1, 19)[0].request)
        for args in ((True, 1), (1, 65536), (-1, 1)):
            with self.assertRaises(ValueError): SourceFrames(support_sources(), 'EU').function(*args)

    def test_conditional_binding_is_never_an_unconditional_fallback(self):
        sources = support_sources()
        sources['EU/Toyota_bin_12.BIN'] = typed(274, [struct.pack('<8H', 1, 19, 1, 21, 1, 0, 1, 0)])
        self.assertEqual([], SourceFrames(sources, 'EU').function(1, 19))

    def test_source_order_multiple_commands_and_native_limit(self):
        sources = support_sources()
        row = struct.pack('<8H', 1, 19, 1, 21, 0, 0, 1, 0)
        other = struct.pack('<8H', 1, 19, 1, 22, 0, 0, 1, 0)
        sources['EU/Toyota_bin_12.BIN'] = typed(274, [other, row])
        self.assertEqual([22, 21], [f.frame_id for f in SourceFrames(sources, 'EU').function(1, 19)])
        sources['EU/Toyota_bin_12.BIN'] = typed(274, [row] * 21)
        with self.assertRaisesRegex(ValueError, '20-command'): SourceFrames(sources, 'EU').function(1, 19)

    def test_unbounded_pool_bad_frame_and_truncated_tables_are_not_padded(self):
        for file in support_sources():
            if 'Viewer' in file: continue
            sources = support_sources(); sources[file] = sources[file][:-1]
            with self.subTest(file=file), self.assertRaises(ValueError):
                SourceFrames(sources, 'EU').frame(22)
        sources = support_sources()
        sources['EU/Toyota_bin_11.BIN'] = typed(17, [struct.pack('<4H', 21, 99, 0, 2)])
        with self.assertRaisesRegex(ValueError, 'Unbounded'):
            SourceFrames(sources, 'EU').function(1, 19)
        sources['EU/Toyota_bin_11.BIN'] = typed(17, [struct.pack('<4H', 21, 1, 0, 2)] * 2)
        with self.assertRaisesRegex(ValueError, 'unique'): SourceFrames(sources, 'EU').frame(21)

    def test_bias_is_strictly_above_10000_not_above_or_equal(self):
        sources = support_sources()
        sources['EU/Toyota_bin_11.BIN'] = typed(17, [struct.pack('<4H', 21, 10001, 0, 10002)])
        self.assertEqual(b'\xa8\x01', SourceFrames(sources, 'EU').frame(21).request)
        sources['EU/Toyota_bin_11.BIN'] = typed(17, [struct.pack('<4H', 21, 10000, 0, 2)])
        with self.assertRaisesRegex(ValueError, 'Unbounded'): SourceFrames(sources, 'EU').frame(21)


if __name__ == '__main__': unittest.main()
