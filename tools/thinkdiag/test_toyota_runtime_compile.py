"""Synthetic runtime compilation and corruption tests; real-source acceptance runs separately."""
import copy
import struct
import unittest

from test_toyota_live import typed
from toyota_functions import FaultBlock, FaultCondition, FaultQuery, FaultMapping, ClearBlock, ClearQuery
from toyota_runtime_compile import compile_p3p4_diagnostics
from vehicle_workflow_validation import validate_workflow


def runtime_fixture():
    block = FaultBlock(33, (5,), 1, 2, 0, 1, 2, 2,
        (FaultQuery(0, 3, 0x3903, 13, (FaultCondition(0, 132, 0, 0),)),
         FaultQuery(0, 1, 1, 10, ()), FaultQuery(0, 2, 2, 11, ()), FaultQuery(0, 5, 43, 165, ())),
        (FaultMapping(0, 0x123, 1, 1),))
    clear = ClearBlock(0, (ClearQuery(0, 1, 1),))
    functions = bytes([28]) + struct.pack('<7I', 1, 33, 33 + len(block.encode()), 0, 0, 0, 1) + b'\xff' * 4 + block.encode() + clear.encode()
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
        frames.append(struct.pack('<4H', key, *indexes)); bindings.append(struct.pack('<8H', 1, key, 0, key, 0, 0, 0, 0))
    descriptors = []; at = 0
    for raw in pool:
        descriptors.append(struct.pack('<IBB', at, len(raw), 0)); at += len(raw)
    body = b''.join(descriptors + pool)
    details = []
    for identity, support, frame, expected in ((1, 0, 0, 0), (2, 0, 0, 0), (3, 1, 13, 71), (43, 1, 10404, 32), (57, 0, 0, 0)):
        raw = bytearray(24); struct.pack_into('<6H', raw, 4, 1, 1, identity, frame, 0, support); raw[20] = expected; details.append(bytes(raw))
    sources = {'FUNCDATA_EU.BIN': functions,
        'ECUPROGRAM.BIN': b'\x01' + struct.pack('<BIH', 1, 6, 1) + struct.pack('<IIB', 1, 2, 14),
        'EU/Toyota_bin_00.BIN': struct.pack('<HII', 0, len(pool), len(body)) + body,
        'EU/Toyota_bin_11.BIN': typed(17, frames), 'EU/Toyota_bin_12.BIN': typed(0x112, bindings),
        'EU/Toyota_bin_1B.BIN': typed(0x11b, details)}
    libraries = {'TCODE_EN.GAG': {'00000123': 'SYNTHETIC\0Not vehicle data'}, 'TEXT_EN.GAG': {'FFFF0048': 'Synthetic missing text'}}
    system = bytearray(37); struct.pack_into('<I', system, 0, 1); system[27] = 0x11; system[33] = 11
    return sources, libraries, bytes(system)


class RuntimeCompileTest(unittest.TestCase):
    def compile(self):
        sources, libraries, raw = runtime_fixture()
        return compile_p3p4_diagnostics(sources, libraries, region='EU', system_id='SYSLIST_EU.BIN@00000000', system_bytes=raw, native_transport=1)

    def test_complete_source_caller_does_not_collapse_to_primary_request(self):
        data = self.compile(); faults = data['faults']; workflow = faults['workflow']
        self.assertEqual((1, 11, 1), (faults['layout']['modeLowBits'], workflow['nativeMode'], workflow['nativeTransport']))
        self.assertEqual(('13B0', '53', '04'), (faults['read']['request'], faults['read']['responsePrefix'], faults['clear']['request']))
        self.assertEqual(['105F'], [r['request'] for r in workflow['initial']])
        self.assertEqual([3, 1, 2, 5], [q['queryKind'] for q in workflow['queries']])
        self.assertEqual([0x3903, 1, 2, 43], [q['membershipWord'] for q in workflow['queries']])
        self.assertEqual('0101', workflow['queries'][0]['setupAlternatives'][0]['frames'][0]['request'])
        self.assertEqual(0, workflow['queries'][0]['setupAlternatives'][0]['rule'])
        self.assertEqual('', workflow['queries'][-1]['frames'][0]['response'])
        self.assertEqual([1, 2, 3, 43, 57], [row['detailId'] for row in workflow['details']])
        self.assertEqual('00000123', faults['layout']['labels'][0]['nativeCode'])
        self.assertEqual(('0904', 'ASCII_RECORDS', 16), (data['identification'][0]['read']['request'], data['identification'][0]['encoding'], data['identification'][0]['recordBytes']))
        self.assertEqual(data, self.compile())

    def validate(self, data):
        _, _, raw = runtime_fixture(); faults = data['faults']
        def bound(row, path): self.assertTrue(row['sourceRecords'], path)
        return validate_workflow(faults['workflow'], function=1, raw_system=raw, dialect='KWP', primary=faults['read'], layout=faults['layout'], bound=bound)

    def test_independent_validator_rejects_mode_function_session_prefix_and_partial_workflows(self):
        data = self.compile(); self.validate(data)
        for key, value in [('nativeMode', 17), ('nativeMode', True), ('nativeTransport', 0), ('functionKey', 0x10001), ('queries', []), ('details', [])]:
            bad = copy.deepcopy(data); bad['faults']['workflow'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.validate(bad)
        for stage in ('initial', 'query', 'setup'):
            for key, value in [('request', '1002'), ('request', '3101'), ('response', '505E'), ('frameId', True), ('mask', 'F')]:
                bad = copy.deepcopy(data); w = bad['faults']['workflow']
                frame = w['initial'][0] if stage == 'initial' else w['queries'][0]['frames'][0] if stage == 'query' else w['queries'][0]['setupAlternatives'][0]['frames'][0]
                frame[key] = value
                with self.subTest(stage=stage, key=key), self.assertRaises(ValueError): self.validate(bad)
        bad = copy.deepcopy(data); bad['faults']['read']['request'] = '13FF'
        with self.assertRaises(ValueError): self.validate(bad)
        bad = copy.deepcopy(data); bad['faults']['workflow']['details'][0]['frame'] = data['faults']['workflow']['initial'][0]
        with self.assertRaises(ValueError): self.validate(bad)

    def test_missing_binding_short_file_wrong_region_full_function_and_native_override_fail_closed(self):
        sources, libraries, raw = runtime_fixture()
        for region, system in [('NA', raw), ('JP', raw), ('EU', raw[:20]), ('EU', b'\x01\0\x01\0' + raw[4:]), ('EU', raw[:33] + b'\x07' + raw[34:]), ('EU', raw[:33] + b'\x09' + raw[34:])]:
            with self.subTest(region=region, source=system.hex()), self.assertRaises((KeyError, ValueError)):
                compile_p3p4_diagnostics(sources, libraries, region=region, system_id='SYSLIST_EU.BIN@00000000', system_bytes=system, native_transport=1)
        for name in sources:
            bad = {**sources, name: sources[name][:-1]}
            with self.subTest(file=name), self.assertRaises(ValueError):
                compile_p3p4_diagnostics(bad, libraries, region='EU', system_id='SYSLIST_EU.BIN@00000000', system_bytes=raw, native_transport=1)


if __name__ == '__main__': unittest.main()
