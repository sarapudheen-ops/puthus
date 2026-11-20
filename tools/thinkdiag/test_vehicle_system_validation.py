"""Synthetic schema/accounting fixtures. They are NOT Toyota vehicle mappings.

The native differential suite separately checks owner-source semantics. These
fixtures ensure that neither a sourceVerified flag nor a forged summary can
turn empty, cross-ECU, wrongly counted or disabled data into executable coverage.
"""
import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from source_verification import load_verification
from vehicle_bundle import BundleWriter, canonical
from vehicle_formula_validation import formula_bytes
from vehicle_system_validation import KINDS, feature_counts, validate_system
from verify_vehicle_bundle import verify


def enabled_fixture():
    system_id = 'SYSLIST_EU.BIN@0000000e'
    function = 'FUNCDATA_EU.BIN@00000001'
    entry = 'SYSTEMENTER.BIN@00000014'
    ecu_row = 'EU/Ecu_Data_ffff.BIN@00000009'
    sources = [function, ecu_row]
    link = {'protocol': 'ISO_15765_4_CAN_11_500', 'dialect': 'KWP2000', 'testerPresent': '3E00'}
    ident = {'read': {'id': 'synthetic-id', 'name': 'Synthetic type', 'request': '21C1', 'responsePrefix': '61C1',
        'dataOffset': 1, 'minBytes': 1, 'maxBytes': 8, 'dataBytes': 1, 'sourceRecords': sources},
        'encoding': 'BYTE_ENUM', 'byteValues': {'01': 'Synthetic one', 'FF': 'Synthetic other'}, 'defaultText': 'Unknown synthetic type'}
    layout = {'kind': 'TOYOTA_P3P4', 'countIndex': 0, 'recordStart': 1, 'recordBytes': 2, 'assemblyKind': 2,
        'countIsPresence': False, 'modeLowBits': 0, 'queryKind': 1, 'availabilityMask': 0,
        'sourceRecords': [function], 'labels': [{'nativeCode': '00001234', 'code': 'P1234',
            'description': 'Synthetic fault, not vehicle data', 'sourceRecords': [function, 'TCODE_EN.GAG#id00001234']} ]}
    faults = {'read': {'id': 'synthetic-dtc', 'name': 'Synthetic current faults', 'request': '13B0',
        'responsePrefix': '53', 'minBytes': 1, 'maxBytes': 511, 'sourceRecords': [function]}, 'layout': layout,
        'clear': {'request': '04', 'responsePrefix': '44', 'sourceRecords': [function]}}
    ecu = {'name': 'Synthetic ECU', 'requestId': '7E0', 'responseId': '7E8', 'protocol': 'KWP',
        'link': link, 'sourceRecords': [function, entry], 'diagnostics': {'identification': [ident], 'faults': faults}}
    pid = {'id': 'synthetic-live', 'name': 'Synthetic signal', 'request': '2105', 'formula': '(A-40)',
        'unit': 'synthetic', 'min': -40, 'max': 215, 'decimals': 0,
        'header': '7E0', 'responseHeader': '7E8', 'link': copy.deepcopy(link), 'sourceRecords': sources,
        'supportChecks': [{'request': 'A801', 'recordId': 5, 'dataOffset': 0, 'formula': 'A', 'sourceRecords': [ecu_row]},
                          {'request': '2100', 'formula': 'bit(A,3)', 'sourceRecords': [ecu_row]}]}
    raw = bytearray(37); raw[:4] = (1).to_bytes(4, 'little'); raw[27] = 17
    data = {'ecus': [ecu], 'pids': [pid], 'dtcs': {}, 'actuators': [], 'coding': []}
    return {'kind': 'vehicle-system', 'id': system_id, 'name': 'Synthetic ECU', 'sourceVerified': True,
        'enabled': True, 'reasons': [], 'sourceRecords': [system_id, function, entry], 'functionKey': 1, 'entrySelector': 17,
        'rawHex': raw.hex(), 'ecuSource': {'sourceFile': 'EU/Ecu_Data_ffff.BIN'},
        'functionSource': {'sourceRecord': function, 'functionKey': 1}, 'entrySource': {'sourceRecord': entry, 'selector': 17},
        'counts': {kind: {'converted': count, 'unresolved': 0} for kind, count in feature_counts(data).items()}, 'data': data}


def make_bundle(path, system=None, *, complete=True, summary_changes=None, descriptor_changes=None, ledger_items=()):
    system = enabled_fixture() if system is None else system
    writer = BundleWriter(path)
    system_ref = writer.add(system)
    descriptor = {field: system[field] for field in ('id', 'name', 'enabled', 'counts', 'reasons')}
    descriptor.update(descriptor_changes or {})
    descriptor['object'] = system_ref
    manual = writer.add({'kind': 'manual-vehicle', 'id': 'MENU.BIN@00000000', 'hierarchyResolved': True,
        'systemJoinResolved': True, 'enabled': False, 'selection': [{'name': 'Synthetic source'}], 'systems': [descriptor]})
    variant = writer.add({'kind': 'converted-vehicle', 'id': 'variant', 'hierarchyResolved': True,
        'systemsSource': manual, 'systemsSourceId': 'MENU.BIN@00000000', 'sourceRecords': ['MENU.BIN@00000000'],
        'sourceSelection': ['Synthetic source']})
    root = writer.add({'kind': 'manual-menu', 'children': [{'object': manual}]})
    ledger = gzip.compress(b''.join(canonical(item) + b'\n' for item in ledger_items), mtime=0)
    (path / 'unresolved-items.jsonl.gz').write_bytes(ledger)
    converted = feature_counts(system['data']) if system['enabled'] else dict.fromkeys(KINDS, 0)
    summary = {'sourceVerification': load_verification(), 'complete': complete, 'executableDefinitions': sum(converted.values()),
        'systems': 1, 'enabledSystems': int(system['enabled']), 'converted': converted, 'unresolvedSourceRecords': len(ledger_items),
        'manualLeaves': 1, 'manualLeavesWithSystemJoin': 1, 'normalizedVehicleChoices': 1, 'unresolvedHierarchyChoices': 0,
        'unresolvedLedger': {'file': 'unresolved-items.jsonl.gz', 'bytes': len(ledger), 'sha256': hashlib.sha256(ledger).hexdigest()}}
    summary.update(summary_changes or {})
    return writer.finish(root, summary, variant)


class FormulaValidationTest(unittest.TestCase):
    def test_same_bounded_app_grammar_and_byte_width_not_native_equivalence(self):
        for expression, width in [('42', 0), ('A-40', 1), ('(256*A+B)/4', 2), ('signed16(A*256+B)', 2),
                                  ('MAX(min(A,3),1)', 1), ('Z << 4 | 0x1f', 26), (' -.25 + A ', 1), ('A*0+1', 1)]:
            with self.subTest(expression=expression): self.assertEqual(width, formula_bytes(expression))

    def test_bad_names_arity_control_flow_bounds_and_literals_fail_closed(self):
        bad = ['', ' ', 'a', 'AA', 'x1', 'y=A;', '__import__(A)', 'A[0]', 'A**2', 'A//2', 'A=2', 'A?B:C',
               'min(A)', 'min(A,B,C)', 'abs()', 'round(A,B)', 'A B', 'A+', '0x', '0x8000000000000000',
               '9' * 400, '(' * 34 + 'A' + ')' * 34, 'A+' * 260 + 'A', True, None, 1]
        for expression in bad:
            with self.subTest(expression=str(expression)[:50]), self.assertRaises(ValueError): formula_bytes(expression)


class SystemValidationTest(unittest.TestCase):
    def test_nonempty_selected_byte_enum_pid_and_source_key_fault_positive(self):
        value = enabled_fixture()
        self.assertEqual({'pids': 1, 'dtcs': 1, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}, validate_system(value))
        # Counts are exact native keys, not one count per printed-code spelling.
        label = copy.deepcopy(value['data']['ecus'][0]['diagnostics']['faults']['layout']['labels'][0])
        label['nativeCode'] = '00011234'
        value['data']['ecus'][0]['diagnostics']['faults']['layout']['labels'].append(label)
        value['counts']['dtcs']['converted'] = 2
        self.assertEqual(2, validate_system(value)['dtcs'])

    def test_enabled_flag_never_substitutes_for_actual_nonempty_features(self):
        mutations = [lambda v: v.update(data={}), lambda v: v['data'].update(ecus=[]),
            lambda v: v['data'].update(pids=[]), lambda v: v['data']['ecus'][0].update(diagnostics={}),
            lambda v: v['data']['ecus'][0]['diagnostics'].update(identification=[]),
            lambda v: v['data']['ecus'][0]['diagnostics']['faults']['layout'].update(labels=[])]
        for change in mutations:
            value = enabled_fixture(); change(value)
            with self.subTest(change=change), self.assertRaises(ValueError): validate_system(value)

    def test_disabled_means_no_executable_data_or_converted_counts(self):
        value = enabled_fixture(); value.update(enabled=False, reasons=['untranslated'])
        with self.assertRaises(ValueError): validate_system(value)
        value['data'] = {}
        with self.assertRaises(ValueError): validate_system(value)
        for count in value['counts'].values(): count['converted'] = 0
        self.assertEqual(dict.fromkeys(KINDS, 0), validate_system(value))
        value['counts']['pids']['unresolved'] = True
        with self.assertRaises(ValueError): validate_system(value)

    def test_exact_function_selector_region_ecu_and_routing_boundaries(self):
        mutations = [lambda v: v['data']['pids'][0].update(header='7E1'),
            lambda v: v['data']['pids'][0]['link'].update(testerPresent='2100'),
            lambda v: v['data']['pids'][0].update(sourceRecords=['FUNCDATA_NA.BIN@00000001', 'EU/Ecu_Data_ffff.BIN@00000009']),
            lambda v: v['data']['pids'][0]['supportChecks'][0].update(sourceRecords=['EU/Ecu_Data_0001.BIN@00000009']),
            lambda v: v['entrySource'].update(selector=81), lambda v: v['functionSource'].update(functionKey=2),
            lambda v: v['data']['ecus'][0].update(responseId='18DAF110'),
            lambda v: v['data'].update(ecus=v['data']['ecus'] * 2),
            lambda v: v['data'].update(dtcs={'P1234': 'Unscoped replacement'}),
            lambda v: v['data'].update(specialFunctions=[])]
        for change in mutations:
            value = enabled_fixture(); change(value)
            with self.subTest(change=change), self.assertRaises(ValueError): validate_system(value)

    def test_enum_prerequisite_unknown_field_and_actual_count_rejections(self):
        mutations = [lambda v: v['counts']['dtcs'].update(converted=2),
            lambda v: v['data']['pids'][0].update(formula='42'),
            lambda v: v['data']['pids'][0]['supportChecks'][0].update(formula='1'),
            lambda v: v['data']['pids'][0]['supportChecks'][1].update(recordId=1),
            lambda v: v['data']['ecus'][0]['diagnostics']['identification'][0].update(defaultText=''),
            lambda v: v['data']['ecus'][0]['diagnostics']['identification'][0]['read'].pop('dataBytes'),
            lambda v: v['data']['ecus'][0]['diagnostics']['identification'][0]['read'].update(dataBytes=2),
            lambda v: v['data']['ecus'][0]['diagnostics']['faults']['read'].update(request='105F', responsePrefix='505F'),
            lambda v: v['data']['ecus'][0]['diagnostics']['faults']['layout'].update(modeLowbits=0),
            lambda v: v['data']['pids'][0].update(ignoredField=True)]
        for change in mutations:
            value = enabled_fixture(); change(value)
            with self.subTest(change=change), self.assertRaises(ValueError): validate_system(value)


class IndependentBundleAccountingTest(unittest.TestCase):
    def test_positive_nonempty_graph_and_recomputed_completeness(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); make_bundle(path)
            self.assertEqual(3, verify(path, require_executable=True)['executableDefinitions'])
            # Full acceptance cannot be claimed by inspecting only the APK index.
            with self.assertRaises(ValueError): verify(path, index_only=True, require_executable=True)

    def test_rejects_forged_executable_category_system_and_manual_summary_counts(self):
        for changes in [{'executableDefinitions': 0}, {'executableDefinitions': 999}, {'complete': False},
                        {'systems': 2}, {'enabledSystems': 0}, {'manualLeaves': 0}, {'manualLeavesWithSystemJoin': 0},
                        {'normalizedVehicleChoices': 2}, {'unresolvedHierarchyChoices': 1}, {'unresolvedSourceRecords': 1},
                        {'converted': dict.fromkeys(KINDS, 0)}, {'enabledSystems': True}]:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp); make_bundle(path, summary_changes=changes)
                with self.assertRaises(ValueError): verify(path)

    def test_partial_is_computed_from_actual_unresolved_categories_not_a_flag(self):
        value = enabled_fixture(); value['counts']['pids']['unresolved'] = 2
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); make_bundle(path, value, complete=False)
            self.assertEqual(3, verify(path, require_executable=True)['executableDefinitions'])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); make_bundle(path, value, complete=True)
            with self.assertRaises(ValueError): verify(path)

    def test_descriptor_cannot_rename_recount_enable_or_retarget_the_object(self):
        for changes in [{'enabled': False}, {'id': 'another-system'}, {'name': 'another-ECU'}, {'counts': {}}, {'reasons': ['fake']}]:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp); make_bundle(path, descriptor_changes=changes)
                with self.assertRaises(ValueError): verify(path)

    def test_pinned_ledger_is_not_an_unverified_external_summary(self):
        item = {'id': 'ECU.BIN@00000000', 'enabled': False, 'sourceVerified': True, 'reasons': ['untranslated']}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); make_bundle(path, complete=False, ledger_items=[item])
            verify(path)
            file = path / 'unresolved-items.jsonl.gz'
            file.write_bytes(file.read_bytes()[:-1])
            with self.assertRaises(ValueError): verify(path)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); make_bundle(path, complete=False, ledger_items=[item, item])
            with self.assertRaises(ValueError): verify(path)


if __name__ == '__main__': unittest.main()
