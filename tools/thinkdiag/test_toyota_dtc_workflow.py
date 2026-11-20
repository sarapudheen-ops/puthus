"""Control-flow fixtures are synthetic; source differential runs use the pinned archive."""
import copy
import struct
import unittest

from toyota_dtc_workflow import (compile_dtc_workflow, default_detail_rows, default_add_info_rows,
                                member_enabled, membership_ids, setup_result, support_result, SupportDecision)
from toyota_frames import SourceFrame
from toyota_functions import FaultBlock, FaultCondition, FaultQuery
from test_toyota_live import typed


def workflow_sources():
    frames = [(5, b'\x10\x5f', b'\x00\x40', b'\x50\x5f'),
              (13, b'\x07', b'', b'\x47'), (10, b'\x13\xb0', b'', b'\x53'),
              (11, b'\x13\x80', b'\x00\xff', b'\x53'),
              (132, b'\x01\x01', b'\xff\xff\x80', b'\x41\x01\x80'),
              (165, b'\x0a', b'', b'')]
    block = FaultBlock(33, (5,), 1, 2, 0, 1, 2, 2,
        (FaultQuery(0, 3, 0x3903, 13, (FaultCondition(0, 132, 0, 0),)),
         FaultQuery(0, 1, 1, 10, ()), FaultQuery(0, 2, 2, 11, ()), FaultQuery(0, 5, 43, 165, ())), ())
    functions = bytes([28]) + struct.pack('<7I', 1, 33, 0, 0, 0, 0, 1) + b'\xff' * 4 + block.encode()
    pool = []; frame_rows = []; binding_rows = []
    for frame_id, request, mask, response in frames:
        indexes = []
        for value in (request, mask, response):
            if value:
                pool.append(value); indexes.append(len(pool))
            else: indexes.append(0)
        frame_rows.append(struct.pack('<4H', frame_id, *indexes))
        binding_rows.append(struct.pack('<8H', 1, frame_id, 0, frame_id, 0, 0, 0, 0))
    at = 0; descriptors = []
    for value in pool:
        descriptors.append(struct.pack('<IBB', at, len(value), 0)); at += len(value)
    raw = b''.join(descriptors + pool)
    return {'FUNCDATA_EU.BIN': functions, 'EU/Toyota_bin_00.BIN': struct.pack('<HII', 0, len(pool), len(raw)) + raw,
            'EU/Toyota_bin_11.BIN': typed(17, frame_rows), 'EU/Toyota_bin_12.BIN': typed(0x112, binding_rows)}


class DtcWorkflowTest(unittest.TestCase):
    def test_membership_word_is_not_a_setup_template_or_mask(self):
        self.assertEqual((3, 57), membership_ids(0x3903))
        self.assertTrue(member_enabled(0x3903, {3}))
        self.assertTrue(member_enabled(0x3903, {57}))
        self.assertFalse(member_enabled(0x3903, {132}))
        self.assertFalse(member_enabled(0, {0, 132}))
        self.assertTrue(member_enabled(0xFF00, set()))
        self.assertTrue(member_enabled(0x00FF, set()))
        for bad in (True, -1, 65536, '3'):
            with self.assertRaises(ValueError): membership_ids(bad)
        for bad in ([3], {True}, {-1}):
            with self.assertRaises(ValueError): member_enabled(3, bad)

    def test_retains_initial_session_and_per_query_setup_and_nondecoded_commands(self):
        plan = compile_dtc_workflow(workflow_sources(), region='EU', function=1)
        self.assertEqual(b'\x10\x5f', plan.initial[0].request)
        report = plan.report()
        self.assertTrue(report['requiresNonReadSetup'])
        self.assertEqual([1, 2, 5], report['nativeReadFailurePolicy']['reenterOnceOnTransports'])
        query = report['queries'][0]
        self.assertEqual([3, 57], query['detailAny'])
        self.assertEqual(132, query['setupAlternatives'][0]['template'])
        self.assertEqual('0101', query['setupAlternatives'][0]['frames'][0]['request'])
        self.assertEqual([13, 10, 11], [q.template for q in plan.selected({1, 2, 57}, native_mode=0)])
        self.assertEqual([13, 10, 11, 165], [q.template for q in plan.selected({1, 2, 57, 43}, native_mode=0)])
        for mode in (7, 9, True, -1):
            with self.assertRaises(ValueError): plan.selected({1, 2, 57}, native_mode=mode)
        self.assertFalse(report['queries'][-1]['decodeFaultRecords'])
        self.assertTrue(report['queries'][0]['decodeFaultRecords'])
        self.assertTrue(all(q.source_record.startswith('FUNCDATA_EU.BIN@') for q in plan.queries))
        self.assertEqual(plan.report(), compile_dtc_workflow(workflow_sources(), region='EU', function=1).report())

    def test_no_function_or_regional_template_fallback(self):
        source = workflow_sources()
        for region, function in (('NA', 1), ('JP', 1), ('EU', 2)):
            with self.assertRaises(KeyError): compile_dtc_workflow(source, region=region, function=function)
        for region, function in (('ANY', 1), ('EU', True), ('EU', -1)):
            with self.assertRaises(ValueError): compile_dtc_workflow(source, region=region, function=function)
        for name, raw in source.items():
            for size in (0, len(raw) - 1):
                bad = copy.deepcopy(source); bad[name] = raw[:size]
                with self.subTest(name=name, size=size), self.assertRaises(ValueError):
                    compile_dtc_workflow(bad, region='EU', function=1)
        # A binding in another function is not a replacement for a missing setup.
        raw = source['EU/Toyota_bin_12.BIN']; at = 10 + 4 * 16
        bad = copy.deepcopy(source); bad['EU/Toyota_bin_12.BIN'] = raw[:at] + b'\x02\0' + raw[at + 2:]
        with self.assertRaisesRegex(ValueError, '132'): compile_dtc_workflow(bad, region='EU', function=1)

    def test_predicate_return_values_and_byte_origins_are_exact(self):
        frame = SourceFrame(1, b'\x13\x80', b'\0\xff\x0f', b'\x53\x80\x05', ('synthetic',))
        self.assertEqual(1, setup_result(b'', 0, 255, frame))
        self.assertEqual(128, setup_result(b'\x80\xf5', 1, 0, frame))
        for value in (0, 255, 1, 128):
            self.assertEqual(int(value not in (0, 255)), setup_result(bytes([value]), 2, 0, frame))
            self.assertEqual(-int(value not in (0, 255)), setup_result(bytes([value]), 3, 0, frame))
        self.assertEqual(1, setup_result(b'\x80\xf5', 4, 255, frame))
        self.assertEqual(-1, setup_result(b'\x81\xf5', 4, 0, frame))
        for rule in (1, 2, 3, 4):
            with self.assertRaisesRegex(ValueError, 'Short|Unbounded'): setup_result(b'', rule, 0, frame)
        bad = SourceFrame(1, b'\x13\x80', b'\0\xff\xff', b'\x53', ('synthetic',))
        with self.assertRaises(ValueError): setup_result(b'\x80\x01', 4, 0, bad)
        with self.assertRaises(ValueError): setup_result(b'\x80\x01', True, 0, frame)

    def test_support_any_mask_and_service_inclusive_minimum_are_not_setup_all_mask(self):
        def evaluate(data, mask=b'\x00\xf0\x0f', minimum=0, expected=0x80, mode=1, frame=1, succeeded=True):
            return support_result(data, mode=mode, frame_id=frame, mask=mask, expected_byte=expected, minimum_length=minimum, succeeded=succeeded)
        self.assertEqual(SupportDecision(True, 1), evaluate(b'\x80\x01'))  # first mask matches, last does not
        self.assertEqual(SupportDecision(False, 1), evaluate(b'\x70\x01'))
        self.assertEqual(SupportDecision(True), evaluate(b'\x70\x01', minimum=3))
        self.assertEqual(SupportDecision(False), evaluate(b'\x70\x01', minimum=4))
        self.assertEqual(SupportDecision(True), evaluate(b'', mask=b'\xff', minimum=255))
        self.assertEqual(SupportDecision(False), evaluate(b'\xff\xff', mask=b'\0\0\0', expected=0))
        self.assertEqual(SupportDecision(True), evaluate(None, mode=0))
        self.assertEqual(SupportDecision(True), evaluate(None, frame=0))
        self.assertEqual(SupportDecision(False), evaluate(None, succeeded=False))
        with self.assertRaises(ValueError): evaluate(b'\x80')
        with self.assertRaises(ValueError): evaluate(b'', minimum=True)

    def test_function_detail_and_add_info_default_prefix_boundaries(self):
        def row(function, main, detail, conditional=0):
            return struct.pack('<12H', 0, 0, function, main, detail, 0, 0, 0, conditional, 0, 0, 0)
        detail = typed(0x11b, [row(1, 1, 57), row(2, 1, 3), row(1, 2, 43), row(1, 1, 132, 1), row(1, 1, 3)])
        extra = typed(0x1c, [struct.pack('<9H', 1, 57, 3, 1, 0, 0, 0, 0, 0),
                             struct.pack('<9H', 2, 57, 4, 0, 0, 0, 0, 0, 0)])
        source = {'EU/Toyota_bin_1B.BIN': detail, 'EU/Toyota_bin_1C.BIN': extra}
        self.assertEqual([57], [r['detailId'] for r in default_detail_rows(source, 'EU', 1, 1)])
        self.assertEqual([43], [r['detailId'] for r in default_detail_rows(source, 'EU', 1, 2)])
        self.assertEqual([], default_detail_rows(source, 'EU', 3, 1))
        self.assertEqual(1, default_add_info_rows(source, 'EU', 1, 57)[0]['literalValue'])
        self.assertEqual([], default_add_info_rows(source, 'EU', 1, 3))
        source['EU/Toyota_bin_1B.BIN'] = detail[:-1]
        with self.assertRaises(ValueError): default_detail_rows(source, 'EU', 1, 1)


if __name__ == '__main__': unittest.main()
