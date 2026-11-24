"""Stage 1d group DTC read/clear enablement tests.

Synthetic sources only; these are compiler/converter contract tests and never
ECU tests, transport approval or physical validation.
"""
import copy
import json
import pathlib
import struct
import tempfile
import unittest
from unittest import mock

from test_toyota_live import typed
from toyota_functions import (ClearBlock, ClearQuery, FaultBlock, FaultCondition,
                              FaultMapping, FaultQuery)
from toyota_runtime_compile import compile_p3p4_diagnostics
import toyota_group_dtc_enable as group_dtc
from toyota_group_dtc_enable import (GroupQualifier, definition_hash,
                                     definition_sha256, enable_system,
                                     load_pin, match_pinned_properties,
                                     pin_entry_checks, qualification_report,
                                     request_pair, verify_group_dtc_system)
from vehicle_system_validation import validate_system

FUNCTION = 61
SELECTOR = 0x11
ECU_FILE = "EU/Ecu_Data_ffff.BIN"
FUNCTION_ID = "FUNCDATA_EU.BIN@00000001"
ENTRY_ID = "SYSTEMENTER.BIN@00000014"


def merged_fixture():
    """Two SYSLIST rows on one compiled group; rows differ only in caller mode.

    The FUNCDATA block, clear block, frames, details and library tables are the
    synthetic runtime-compilation fixture re-keyed to function 61 so the exact
    SYSTEMENTER document() lifecycle (function 61, selector 0x11) joins it.
    """
    block = FaultBlock(33, (5,), 1, 2, 0, 1, 2, 2,
        (FaultQuery(0, 3, 0x3903, 13, (FaultCondition(0, 132, 0, 0),)),
         FaultQuery(0, 1, 1, 10, ()), FaultQuery(0, 2, 2, 11, ()),
         FaultQuery(0, 5, 43, 165, ())),
        (FaultMapping(0, 0x123, 1, 1),))
    clear = ClearBlock(0, (ClearQuery(0, 1, 1),))
    functions = (bytes([28]) + struct.pack('<7I', FUNCTION, 33, 33 + len(block.encode()), 0, 0, 0, 1)
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
        bindings.append(struct.pack('<8H', FUNCTION, key, 0, key, 0, 0, 0, 0))
    descriptors = []; at = 0
    for raw in pool:
        descriptors.append(struct.pack('<IBB', at, len(raw), 0)); at += len(raw)
    body = b''.join(descriptors + pool)
    details = []
    for identity, support, frame, expected in ((1, 0, 0, 0), (2, 0, 0, 0), (3, 1, 13, 71),
                                               (43, 1, 10404, 32), (57, 0, 0, 0)):
        raw = bytearray(24)
        struct.pack_into('<6H', raw, 4, FUNCTION, 1, identity, frame, 0, support)
        raw[20] = expected; details.append(bytes(raw))
    sources = {'FUNCDATA_EU.BIN': functions,
        'ECUPROGRAM.BIN': b'\x01' + struct.pack('<BIH', 1, 6, 1) + struct.pack('<IIB', FUNCTION, 2, 14),
        'EU/Toyota_bin_00.BIN': struct.pack('<HII', 0, len(pool), len(body)) + body,
        'EU/Toyota_bin_11.BIN': typed(17, frames), 'EU/Toyota_bin_12.BIN': typed(0x112, bindings),
        'EU/Toyota_bin_1B.BIN': typed(0x11b, details)}
    libraries = {'TCODE_EN.GAG': {'00000123': 'SYNTHETIC' + chr(0) + 'Not vehicle data'},
                 'TEXT_EN.GAG': {'FFFF0048': 'Synthetic missing text'}}
    rep = bytearray(37); struct.pack_into('<I', rep, 0, FUNCTION); rep[27] = SELECTOR; rep[33] = 11
    member = bytearray(rep); member[33] = 3
    rows = [bytes(rep), bytes(member)]
    syslist = struct.pack('<IIIH', 7, 12, 0xFFFFFFFF, len(rows)) + b''.join(rows)
    sources['SYSLIST_EU.BIN'] = syslist
    rep_id = f'SYSLIST_EU.BIN@{14:08x}'
    member_id = f'SYSLIST_EU.BIN@{14 + 37:08x}'
    return sources, libraries, rows[0], rows[1], rep_id, member_id


def synthetic_target(rep_id):
    return {'region': 'EU', 'ecuFile': ECU_FILE, 'function': FUNCTION,
            'selector': SELECTOR, 'representative': rep_id, 'systems': 2,
            'dtcLabels': 1, 'readRequest': '13B0', 'clearRequest': '04',
            'layout': {'countIndex': 0, 'recordStart': 1, 'recordBytes': 2,
                       'assemblyKind': 2, 'countIsPresence': False,
                       'modeLowBits': 1},
            'groupKey': ('EU', ECU_FILE, FUNCTION, SELECTOR)}


def value_for(identity, raw):
    counts = {kind: {'converted': 0, 'unresolved': None} for kind in
              ('pids', 'dtcs', 'tests', 'coding', 'specialFunctions', 'ecuInfo')}
    counts['pids']['unresolved'] = 1
    return {'kind': 'vehicle-system', 'id': identity,
            'name': 'Synthetic group system', 'description': 'Synthetic',
            'sourceRecords': [identity, FUNCTION_ID, 'SYSTEMENTER.BIN@00000000', ENTRY_ID],
            'sourceVerified': True, 'enabled': False,
            'reasons': ['ecu_entry_untranslated'],
            'functionKey': FUNCTION, 'entrySelector': SELECTOR,
            'transportLabel': 'KWP2000', 'rawHex': raw.hex(),
            'ecuSource': {'sourceFile': ECU_FILE, 'sha256': '0' * 64},
            'functionSource': {'sourceRecord': FUNCTION_ID,
                               'functionKey': FUNCTION, 'declaredLiveRows': 1},
            'entrySource': {'sourceRecord': ENTRY_ID, 'selector': SELECTOR,
                            'pages': []},
            'bringUpPages': [], 'counts': counts, 'data': {}}


def synthetic_lifecycle():
    from test_toyota_entries import document
    from test_toyota_lifecycle import frame_sources
    from toyota_entries import EntryIndex
    from toyota_frames import SourceFrames
    from toyota_lifecycle import selected_fast_kwp_lifecycle
    return selected_fast_kwp_lifecycle(EntryIndex(document()),
                                       SourceFrames(frame_sources(), 'EU'),
                                       region=1, function=FUNCTION, selector=SELECTOR)


def pin_document(groups):
    return {'kind': 'compiled-dtc-group-enable-pin', 'formatVersion': 1,
            'sourceSha256': group_dtc.SOURCE_SHA256, 'note': 'synthetic test pin',
            'oracleProvenance': group_dtc.ORACLE_PROVENANCE, 'groups': groups}


def write_pin(directory, groups):
    (pathlib.Path(directory) / group_dtc.PIN_FILE).write_text(
        json.dumps(pin_document(groups)))


class DefinitionHashTest(unittest.TestCase):
    def test_hash_is_provenance_invariant_but_content_sensitive(self):
        sources, libraries, rep, member, rep_id, member_id = merged_fixture()
        first = compile_p3p4_diagnostics(sources, libraries, region='EU',
            system_id=rep_id, system_bytes=rep, native_transport=1)['faults']
        second = compile_p3p4_diagnostics(sources, libraries, region='EU',
            system_id=member_id, system_bytes=member, native_transport=1)['faults']
        self.assertEqual(definition_hash(first), definition_hash(second))
        self.assertNotEqual(definition_sha256(first), definition_sha256(second))
        tampered = copy.deepcopy(first)
        tampered['layout']['labels'][0]['description'] = 'moved'
        self.assertNotEqual(definition_hash(first), definition_hash(tampered))
        moved_mode = copy.deepcopy(first)
        moved_mode['workflow']['nativeMode'] = 3
        self.assertEqual(definition_hash(first), definition_hash(moved_mode))
        moved_transport = copy.deepcopy(first)
        moved_transport['workflow']['nativeTransport'] = 5
        self.assertEqual(definition_hash(first), definition_hash(moved_transport))
        # The hash covers exactly the shipped read surface: read+layout(+labels).
        shipped = {k: v for k, v in first.items() if k not in ('workflow', 'clear')}
        self.assertEqual(definition_hash(first), definition_hash(shipped))
        moved_read = copy.deepcopy(first)
        moved_read['read']['request'] = '1380'
        self.assertNotEqual(definition_hash(first), definition_hash(moved_read))

    def test_pinned_property_checks_are_literal(self):
        sources, libraries, rep, _member, rep_id, _member_id = merged_fixture()
        faults = compile_p3p4_diagnostics(sources, libraries, region='EU',
            system_id=rep_id, system_bytes=rep, native_transport=1)['faults']
        target = synthetic_target(rep_id)
        self.assertEqual([], match_pinned_properties(faults, target))
        problems = match_pinned_properties(faults, dict(target, dtcLabels=2))
        self.assertEqual(['dtcLabels:1!=2'], problems)
        problems = match_pinned_properties(faults, dict(target, readRequest='1380'))
        self.assertEqual(['readRequest:13B0!=1380'], problems)
        problems = match_pinned_properties(
            faults, dict(target, layout=dict(target['layout'], modeLowBits=0)))
        self.assertEqual(["layout.modeLowBits:1!=0"], problems)


class PinLoadTest(unittest.TestCase):
    def test_pin_load_is_fail_closed(self):
        sources, libraries, rep, _member, rep_id, _member_id = merged_fixture()
        faults = compile_p3p4_diagnostics(sources, libraries, region='EU',
            system_id=rep_id, system_bytes=rep, native_transport=1)['faults']
        entry = {'region': 'EU', 'ecuFile': ECU_FILE, 'function': FUNCTION,
                 'selector': SELECTOR, 'representative': rep_id, 'systems': 2,
                 'dtcLabels': 1, 'readRequest': '13B0', 'clearRequest': '04',
                 'layout': synthetic_target(rep_id)['layout'],
                 'definitionSha256': definition_sha256(faults),
                 'definitionHash': definition_hash(faults)}
        target = synthetic_target(rep_id)
        key = ('EU', ECU_FILE, FUNCTION, SELECTOR)
        with mock.patch.dict(group_dtc.TARGETS, {key: target}, clear=True):
            with tempfile.TemporaryDirectory() as d:
                write_pin(d, [])
                self.assertEqual({}, load_pin(d)['groups'])
                write_pin(d, [entry])
                loaded = load_pin(d)
                self.assertIn(key, loaded['groups'])
                for tamper, fragment in (
                        (dict(entry, definitionHash=123), 'definitionHash is not a sha256'),
                        (dict(entry, dtcLabels=2), 'dtcLabels'),
                        (dict(entry, selector=0x51), 'not a Stage 1c target group'),
                        (dict(entry, extra=1), 'extra')):
                    write_pin(d, [tamper])
                    with self.assertRaises(ValueError) as caught:
                        load_pin(d)
                    self.assertIn(fragment, str(caught.exception))
                broken = pin_document([])
                broken['sourceSha256'] = '0' * 64
                (pathlib.Path(d) / group_dtc.PIN_FILE).write_text(json.dumps(broken))
                with self.assertRaises(ValueError) as caught:
                    load_pin(d)
                self.assertIn('sourceSha256', str(caught.exception))

    def test_pin_entry_checks_reject_wrong_shapes(self):
        self.assertEqual(['pin entry is not an object'], pin_entry_checks('x'))


class GroupQualifierTest(unittest.TestCase):
    def setUp(self):
        (self.sources, self.libraries, self.rep, self.member,
         self.rep_id, self.member_id) = merged_fixture()
        self.target = synthetic_target(self.rep_id)
        self.key = ('EU', ECU_FILE, FUNCTION, SELECTOR)
        patcher = mock.patch.dict(group_dtc.TARGETS, {self.key: self.target},
                                  clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def qualifier(self, pin_groups):
        with tempfile.TemporaryDirectory() as d:
            write_pin(d, pin_groups)
            pin = load_pin(d)
        return GroupQualifier(self.sources, self.libraries, pin)

    def group_entry(self):
        faults = compile_p3p4_diagnostics(self.sources, self.libraries, region='EU',
            system_id=self.rep_id, system_bytes=self.rep, native_transport=1)['faults']
        return {'region': 'EU', 'ecuFile': ECU_FILE, 'function': FUNCTION,
                'selector': SELECTOR, 'representative': self.rep_id, 'systems': 2,
                'dtcLabels': 1, 'readRequest': '13B0', 'clearRequest': '04',
                'layout': self.target['layout'],
                'definitionSha256': definition_sha256(faults),
                'definitionHash': definition_hash(faults)}

    def run_qualify(self, qualifier):
        first = qualifier.qualify('EU', FUNCTION, SELECTOR, ECU_FILE,
                                  self.rep_id, self.rep, 1)
        second = qualifier.qualify('EU', FUNCTION, SELECTOR, ECU_FILE,
                                   self.member_id, self.member, 1)
        return first, second

    def test_without_pin_nothing_is_enabled_ready_but_hashes_are_reported(self):
        qualifier = self.qualifier([])
        first, second = self.run_qualify(qualifier)
        for record in (first, second):
            self.assertEqual('unresolved-pin', record['status'])
            self.assertIn('group_pin_absent_or_mismatched:no pin entry',
                          record['reason'])
            self.assertEqual(record['definitionHash'],
                             record['groupDefinitionHash'])
        markdown, payload = qualification_report(qualifier, qualifier.pin)
        self.assertIn('no pin entry', markdown)
        self.assertIn(self.member_id, markdown)
        self.assertEqual(0, json.loads(payload)['totals']['enabledReady'])

    def test_pin_match_makes_members_enabled_ready(self):
        qualifier = self.qualifier([self.group_entry()])
        first, second = self.run_qualify(qualifier)
        self.assertEqual('enabled-ready', first['status'])
        self.assertEqual('enabled-ready', second['status'])
        self.assertEqual(first['definitionHash'], second['definitionHash'])
        self.assertIsNotNone(first['_compiled'])

    def test_pinned_path_slices_are_counted_separately_and_never_enable(self):
        qualifier = self.qualifier([self.group_entry()])
        self.run_qualify(qualifier)
        record = qualifier.pinned_path('EU', FUNCTION, SELECTOR, ECU_FILE,
                                      'SYSLIST_EU.BIN@0009d8f2')
        self.assertEqual('pinned-path', record['status'])
        self.assertIsNone(qualifier.pinned_path('EU', FUNCTION, 0x99, ECU_FILE,
                                                'SYSLIST_EU.BIN@0009d8f2'))
        markdown, payload = qualification_report(qualifier, qualifier.pin)
        totals = json.loads(payload)['totals']
        self.assertEqual(3, totals['members'])
        self.assertEqual(2, totals['enabledReady'])
        self.assertEqual(1, totals['pinnedPath'])
        self.assertEqual(0, totals['unresolved'])
        self.assertIn('pinned-path: 1', markdown)
        self.assertIn('SYSLIST_EU.BIN@0009d8f2: pinned-path', markdown)

    def test_member_hash_drift_is_literal_and_blocks(self):
        qualifier = self.qualifier([self.group_entry()])
        real_compile = qualifier._compile

        def drifted(region, system_id, system_bytes, native_transport):
            compiled = real_compile(region, system_id, system_bytes, native_transport)
            if system_id == self.member_id:
                compiled = copy.deepcopy(compiled)
                compiled['faults']['read']['request'] = '1380'
            return compiled

        with mock.patch.object(qualifier, '_compile', side_effect=drifted):
            first, second = self.run_qualify(qualifier)
        self.assertEqual('enabled-ready', first['status'])
        self.assertEqual('unresolved', second['status'])
        self.assertTrue(second['reason'].startswith('definition_hash_mismatch:'),
                        second['reason'])
        self.assertIn(second['definitionHash'], second['reason'])

    def test_representative_compile_rejection_is_literal(self):
        qualifier = self.qualifier([])
        with mock.patch.object(qualifier, '_compile',
                               side_effect=ValueError('synthetic refusal')):
            first, _second = self.run_qualify(qualifier)
        self.assertEqual('unresolved', first['status'])
        self.assertIn('group_compile_rejected:ValueError: synthetic refusal',
                      first['reason'])


class EnableSystemTest(unittest.TestCase):
    def setUp(self):
        (self.sources, self.libraries, self.rep, self.member,
         self.rep_id, self.member_id) = merged_fixture()
        self.target = synthetic_target(self.rep_id)
        self.faults = compile_p3p4_diagnostics(self.sources, self.libraries,
            region='EU', system_id=self.rep_id, system_bytes=self.rep,
            native_transport=1)['faults']
        self.record = {'groupDefinitionSha256': definition_sha256(self.faults),
                       'definitionHash': definition_hash(self.faults),
                       'group': [self.target[k] for k in
                                 ('region', 'ecuFile', 'function', 'selector')]}
        self.lifecycle = synthetic_lifecycle()
        patcher = mock.patch.dict(
            group_dtc.TARGETS,
            {('EU', ECU_FILE, FUNCTION, SELECTOR): self.target}, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_request_pair_is_the_documented_address_swap_convention(self):
        request_id, response_id = request_pair(self.lifecycle)
        self.assertEqual(('8013F0', '80F013'), (request_id, response_id))

    def test_enable_ships_a_dtc_only_slice_that_validates(self):
        value = value_for(self.rep_id, self.rep)
        problem = enable_system(value, {'faults': self.faults}, self.target,
                                self.record, self.lifecycle)
        self.assertIsNone(problem)
        # The converter's own enable rule decides the flag; mirror it here.
        value['enabled'] = True
        self.assertEqual([], value['reasons'])
        self.assertIs(True, value['readOnly'])
        self.assertEqual({'pids': 0, 'dtcs': 1, 'tests': 0, 'coding': 0,
                          'specialFunctions': 0, 'ecuInfo': 0},
                         validate_system(value))
        self.assertEqual({'ecus', 'pids'}, set(value['data']))
        diagnostics = value['data']['ecus'][0]['diagnostics']
        self.assertEqual([], diagnostics['identification'])
        faults = diagnostics['faults']
        self.assertEqual({'read', 'layout'}, set(faults))
        self.assertNotIn('clear', faults)
        self.assertNotIn('workflow', faults)
        self.assertIs(False, value['groupDtcEnable']['clearShipped'])
        self.assertEqual(group_dtc.CLEAR_UNRESOLVED_REASON,
                         value['groupDtcEnable']['clearReason'])
        self.assertIn(group_dtc.CLEAR_UNRESOLVED_REASON,
                      value['data']['ecus'][0]['notes'][0])
        self.assertEqual(1, value['counts']['dtcs']['converted'])
        self.assertEqual(0, value['counts']['pids']['converted'])
        self.assertEqual(0, value['counts']['ecuInfo']['converted'])
        self.assertEqual('KWP', value['data']['ecus'][0]['protocol'])
        self.assertEqual('8013F0', value['data']['ecus'][0]['requestId'])
        self.assertEqual(self.record['definitionHash'],
                         value['groupDtcEnable']['definitionHash'])

    def test_enable_fails_closed_and_leaves_the_system_untouched(self):
        value = value_for(self.rep_id, self.rep)
        before = copy.deepcopy(value)
        problem = enable_system(value, {}, self.target, self.record,
                                self.lifecycle)
        self.assertEqual('group_data_rejected:missing faults projection', problem)
        self.assertEqual(before, value)
        empty_labels = copy.deepcopy(self.faults)
        empty_labels['layout']['labels'] = []
        problem = enable_system(value, {'faults': empty_labels}, self.target,
                                self.record, self.lifecycle)
        self.assertTrue(problem.startswith('group_data_rejected:'), problem)
        self.assertIn('nonempty source-key DTC', problem)
        self.assertEqual(before, value)

    def test_verify_group_dtc_system_accepts_exact_and_rejects_tampering(self):
        value = value_for(self.rep_id, self.rep)
        self.assertIsNone(enable_system(value, {'faults': self.faults},
                                        self.target, self.record, self.lifecycle))
        value['enabled'] = True
        value['categoryStatus'] = {'pids': 'unresolved', 'dtcs': 'executable',
                                   'ecuInfo': 'unresolved', 'actuators': 'unknown',
                                   'coding': 'unknown', 'specialFunctions': 'unknown'}
        pins = {self.target['groupKey']: dict(self.target,
                definitionSha256=self.record['groupDefinitionSha256'],
                definitionHash=self.record['definitionHash'])}
        self.assertEqual([], verify_group_dtc_system(value, pins))
        for mutate, fragment in (
                (lambda v: v['data']['ecus'][0]['diagnostics']['identification'].append({'x': 1}),
                 'identification must stay unresolved'),
                (lambda v: v['data']['ecus'][0]['diagnostics']['faults']['layout']
                 ['labels'][0].update(description='moved'), 'definitionHash mismatch'),
                (lambda v: v['data'].update(pids=[{'x': 1}]),
                 'pids must stay unresolved'),
                (lambda v: v.update(categoryStatus=dict(v['categoryStatus'], dtcs='unresolved')),
                 'executable dtcs'),
                (lambda v: v['data']['ecus'][0]['diagnostics']['faults']
                 .update(clear={'request': '04', 'responsePrefix': '44'}),
                 'must not ship a workflow or clear'),
                (lambda v: v.update(groupDtcEnable=dict(v['groupDtcEnable'], clearShipped=True)),
                 'unresolved clear reason'),
                (lambda v: v.update(categoryStatus=dict(v['categoryStatus'], actuators='executable')),
                 'write category actuators'),
                (lambda v: v.update(categoryStatus=dict(v['categoryStatus'], coding='executable')),
                 'write category coding')):
            tampered = copy.deepcopy(value)
            mutate(tampered)
            problems = verify_group_dtc_system(tampered, pins)
            self.assertTrue(any(fragment in p for p in problems),
                            (fragment, problems))
        problems = verify_group_dtc_system(value, {})
        self.assertTrue(any('no pin entry' in p for p in problems), problems)
        missing = copy.deepcopy(value)
        del missing['groupDtcEnable']
        self.assertEqual(['missing groupDtcEnable record'],
                         verify_group_dtc_system(missing, pins))


if __name__ == '__main__':
    unittest.main()
