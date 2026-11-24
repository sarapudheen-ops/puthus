"""The generated one-system pack, not a synthetic enabled/empty stand-in."""
import copy
import json
import os
from pathlib import Path
import unittest

from toyota_ecmect_pack import (ENTRY, MANDATORY_SYSTEM, link_for, load_review, verify_enabled)
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_innova_2007_pack import (ENTRY as ENTRY_2007, SYSTEM as SYSTEM_2007,
                                    load_candidate, verify_enabled as verify_2007)
from vehicle_bundle import read_object
from vehicle_system_validation import validate_system


class EcmectPackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = Path(os.environ.get('TOYOTA_PACK_DIRECTORY', '.local/toyota-vehicle-packs'))
        if not (cls.directory / 'mandatory-ecmect-source-reads.json').is_file():
            raise unittest.SkipTest('Generate/download the actual Toyota pack and recovered read artifacts first')
        cls.review = load_review(cls.directory)
        cls.index = json.loads((cls.directory / 'index.json').read_bytes())
        cls.container = cls.directory / cls.index['container']
        page = read_object(cls.container, cls.index['catalogRoot'])
        for name in ('Toyota', 'Kijang Innova', 'TGN · 2009.07-2017.07',
                     'MINI VAN · w/ VSC (Vehicle Stability Control)'):
            row = next(c for c in page['children'] if c['name'] == name)
            page = read_object(cls.container, row['object'])
        cls.vehicle = page
        manual = read_object(cls.container, page['systemsSource'])
        cls.system = read_object(cls.container, next(s for s in manual['systems'] if s['id'] == MANDATORY_SYSTEM)['object'])
        cls.review_2007 = load_candidate(cls.directory)
        page = read_object(cls.container, cls.index['catalogRoot'])
        for name in ('Toyota', 'Kijang Innova', 'TGN · -2009.07', 'MINI VAN · w/o VSC (Vehicle Stability Control)'):
            page = read_object(cls.container, next(c for c in page['children'] if c['name'] == name)['object'])
        manual_2007 = read_object(cls.container, page['systemsSource'])
        cls.system_2007 = read_object(cls.container, next(s for s in manual_2007['systems'] if s['id'] == SYSTEM_2007)['object'])

    def group_systems(self):
        """Enabled systems beyond the two pinned slices: Stage 1d group DTC path.

        Every one must carry a `groupDtcEnable` record that independently
        verifies against the repo's `schema/dtc-group-enable-v1.json`; with no
        pinned groups (or none enabled) this returns {} and the pinned
        expectations reduce to the two read-only slices.
        """
        from toyota_group_dtc_enable import load_pin, verify_group_dtc_system
        pin = load_pin(Path(__file__).resolve().parent / 'schema')['groups']
        inventory = read_object(self.container, self.index['sourceInventory'])
        groups = {}
        for ref in inventory['systemPages']:
            for row in read_object(self.container, ref)['systems']:
                value = read_object(self.container, row['object'])
                if value.get('groupDtcEnable'):
                    self.assertEqual([], verify_group_dtc_system(value, pin))
                    groups[value['id']] = value
        return groups

    def test_stage1_category_translator_serializes_exact_ecmect_control(self):
        from toyota_category_translation import translate_categories
        from vehicle_bundle import canonical
        row = {"id": MANDATORY_SYSTEM, "region": "EU", "functionKey": 61,
               "entrySelector": 0x11, "ecuFile": "EU/Ecu_Data_0081.BIN"}
        outcome = translate_categories("EU", 61, 0x11, row, ecmect_review=self.review,
                                       reference_data=self.system["data"])
        self.assertEqual({"pids": 30, "dtcs": 1414, "ecuInfo": 1},
                         {k: outcome["counts"][k] for k in ("pids", "dtcs", "ecuInfo")})
        self.assertTrue(outcome["ecmectControlByteIdentical"])
        self.assertEqual("executable", outcome["categories"]["pids"])
        self.assertEqual("executable", outcome["categories"]["dtcs"])
        self.assertEqual("executable", outcome["categories"]["ecuInfo"])
        self.assertEqual(canonical(outcome["definitions"]["pids"]),
                         canonical(self.system["data"]["pids"]))

    def test_real_selected_system_has_nonempty_exact_read_definitions(self):
        s = self.system
        self.assertTrue(s['enabled']); self.assertTrue(s['readOnly']); self.assertEqual([], s['reasons'])
        self.assertEqual((61, 17, ENTRY), (s['functionKey'], s['entrySelector'], s['entrySource']['sourceRecord']))
        self.assertEqual({'pids': 30, 'dtcs': 1414, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}, validate_system(s))
        self.assertTrue(verify_enabled(s, self.review))
        expected_keepalive = self.review['transportPlan']['keepalive']['requestHex'].upper()
        self.assertEqual(expected_keepalive, s['data']['ecus'][0]['link']['testerPresent'])
        changed = copy.deepcopy(s)
        changed['data']['ecus'][0]['link']['testerPresent'] = '2100' if expected_keepalive != '2100' else '3E'
        with self.assertRaises(ValueError):
            verify_enabled(changed, self.review)
        ecu, = s['data']['ecus']
        self.assertEqual(('0081', '8013F0', '80F013', 10416),
                         (ecu['address'], ecu['requestId'], ecu['responseId'], ecu['link']['kLine']['baud']))
        faults = ecu['diagnostics']['faults']
        self.assertEqual([13, 10, 11, 165], [q['template'] for q in faults['workflow']['queries']])
        self.assertEqual('0904', ecu['diagnostics']['identification'][0]['read']['request'])
        self.assertNotIn('clear', faults)
        self.assertEqual('8113F08105', s['transportPolicy']['initialFrameHex'])
        self.assertEqual('PENDING', s['transportPolicy']['physicalValidation'])
        self.assertFalse(self.index['summary']['complete'])
        group = self.group_systems()
        self.assertEqual(
            (2 + len(group), 2890 + sum(v['counts']['dtcs']['converted'] for v in group.values())),
            (self.index['summary']['enabledSystems'], self.index['summary']['executableDefinitions']))

    def test_2007_is_independently_joined_and_read_only(self):
        value = self.system_2007
        self.assertTrue(value['enabled'] and value['readOnly'])
        self.assertEqual((61, 0x51, ENTRY_2007), (value['functionKey'], value['entrySelector'], value['entrySource']['sourceRecord']))
        self.assertEqual({'pids': 30, 'dtcs': 1414, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}, validate_system(value))
        self.assertTrue(verify_2007(value, self.review_2007, self.review))
        self.assertEqual(1, len(value['data']['ecus']))
        self.assertIn(SYSTEM_2007, value['sourceRecords'])
        self.assertIn(ENTRY_2007, value['sourceRecords'])
        self.assertNotIn(MANDATORY_SYSTEM, value['sourceRecords'])
        self.assertNotIn(ENTRY, value['sourceRecords'])
        def check_records(item):
            if isinstance(item, dict):
                for key, child in item.items():
                    if key == 'sourceRecords':
                        self.assertIn(SYSTEM_2007, child)
                        self.assertIn(ENTRY_2007, child)
                        self.assertNotIn(MANDATORY_SYSTEM, child)
                        self.assertNotIn(ENTRY, child)
                    else: check_records(child)
            elif isinstance(item, list):
                for child in item: check_records(child)
        check_records(value['data'])
        ecu = value['data']['ecus'][0]
        self.assertEqual(('8110F0', '81F010', 16, 10416, '3E'),
                         (ecu['requestId'], ecu['responseId'], ecu['link']['kLine']['initAddress'], ecu['link']['kLine']['baud'], ecu['link']['testerPresent']))
        self.assertNotIn(MANDATORY_SYSTEM, json.dumps(value))
        self.assertNotIn(SYSTEM_2007, json.dumps(self.system))
        for mutate in (lambda s: s.update(entrySelector=0x11),
                       lambda s: s['entrySource'].update(sourceRecord='SYSTEMENTER.BIN@00002f63'),
                       lambda s: s.update(id=MANDATORY_SYSTEM)):
            wrong = copy.deepcopy(value); mutate(wrong)
            with self.assertRaises(ValueError): verify_2007(wrong, self.review_2007, self.review)

    def test_nonempty_but_unpinned_synthetic_enabled_bundle_still_fails(self):
        import tempfile
        from test_vehicle_system_validation import make_bundle
        from verify_vehicle_bundle import verify
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            make_bundle(path)
            # Even with independently valid read artifacts beside it, a fake
            # enabled ECU cannot use the one-system exception.
            for name in ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json'):
                (path / name).write_bytes((self.directory / name).read_bytes())
            with self.assertRaisesRegex(ValueError, 'ECMECT'):
                verify(path, require_executable=True)

    def test_policy_rejects_missing_corrupt_selector_and_third_system(self):
        import tempfile
        from toyota_ecmect_pack import candidate_for
        from toyota_innova_2007_pack import candidate_for as candidate_2007
        from verify_vehicle_bundle import verify_enabled_policy
        objects = {MANDATORY_SYSTEM: self.system, SYSTEM_2007: self.system_2007}
        candidates = [candidate_for(self.system, self.review), candidate_2007(self.system_2007, self.review_2007)]
        summary = self.index['summary']
        names = ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json',
                 'innova-2007-source-read-candidate.json', 'mandatory-ecmect-live-omissions.json')
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            for name in names:
                (path / name).write_bytes((self.directory / name).read_bytes())
            verify_enabled_policy(path, objects, candidates, summary)
            for name in ('mandatory-ecmect-source-reads.json', 'innova-2007-source-read-candidate.json'):
                file = path / name; original = file.read_bytes()
                file.unlink()
                with self.subTest(missing=name), self.assertRaises(ValueError):
                    verify_enabled_policy(path, objects, candidates, summary)
                file.write_bytes(original + b' ')
                with self.subTest(corrupt=name), self.assertRaises(ValueError):
                    verify_enabled_policy(path, objects, candidates, summary)
                file.write_bytes(original)
            wrong = copy.deepcopy(objects)
            wrong[SYSTEM_2007]['entrySelector'] = 0x11
            with self.assertRaises(ValueError): verify_enabled_policy(path, wrong, candidates, summary)
            wrong = dict(objects, **{'SYSLIST_EU.BIN@00000000': {}})
            with self.assertRaises(ValueError): verify_enabled_policy(path, wrong, candidates, summary)

    def test_pilot_artifact_cannot_be_swapped_into_2007_pin(self):
        from toyota_innova_2007_reads import verify_candidate_bytes
        pilot_raw = (self.directory / 'mandatory-ecmect-source-reads.json').read_bytes()
        with self.assertRaises(ValueError):
            verify_candidate_bytes(pilot_raw, 'innova-2007-source-read-candidate.json')

    def test_shared_link_builder_matches_ecmect_and_reports_innova_membership(self):
        decoded = Path('.local/thinkdiag/decoded')
        required = [decoded / 'SYSTEMENTER.BIN', *(decoded / 'EU' / f'Toyota_bin_{name}.BIN' for name in ('00', '11', '12'))]
        if not all(path.is_file() for path in required):
            self.skipTest('Decoded source inputs are unavailable in this test environment')
        sources = {
            'SYSTEMENTER.BIN': required[0].read_bytes(),
            **{f'EU/Toyota_bin_{name}.BIN': path.read_bytes() for name, path in zip(('00', '11', '12'), required[1:])},
        }
        index = EntryIndex(sources['SYSTEMENTER.BIN'])
        lifecycle = selected_fast_kwp_lifecycle(index, SourceFrames(sources, 'EU'), region=1, function=61, selector=0x11)
        self.assertEqual(self.system['data']['ecus'][0]['link'], link_for(lifecycle)['link'])
        innova = index.select(1, self.system_2007['functionKey'], self.system_2007['entrySelector'])
        if innova is None or innova.transport not in (1, 5):
            self.assertIsNone(innova)
        elif innova.transport == 5:
            with self.assertRaisesRegex(ValueError, 'link_representation_unsupported:transport-5'):
                link_for(selected_fast_kwp_lifecycle(index, SourceFrames(sources, 'EU'), region=1,
                    function=self.system_2007['functionKey'], selector=self.system_2007['entrySelector']))
        else:
            innova_lifecycle = selected_fast_kwp_lifecycle(index, SourceFrames(sources, 'EU'), region=1,
                function=self.system_2007['functionKey'], selector=self.system_2007['entrySelector'])
            innova_ecu = self.system_2007['data']['ecus'][0]
            self.assertEqual(innova_ecu['link'], link_for(innova_lifecycle)['link'])

    def test_all_985_omitted_live_rows_have_their_exact_first_implementation_blocker(self):
        from collections import Counter
        omissions = json.loads((self.directory / 'mandatory-ecmect-live-omissions.json').read_bytes())
        self.assertEqual(MANDATORY_SYSTEM, omissions['systemId'])
        items = {r['id']: r for r in omissions['items']}
        self.assertEqual(985, len(items))
        self.assertEqual({'live_applicability_untranslated': 630, 'calc_semantics_unreviewed': 323,
                          'formula_untranslated': 21, 'dynamic_scaling_untranslated': 11},
                         dict(Counter(r['reasons'][0] for r in items.values())))
        self.assertTrue(all(r['detail'] and r['sourceRecords'] for r in items.values()))
        self.assertTrue(set(items).isdisjoint(r['id'] for r in self.system['data']['pids']))
        function = read_object(self.container, self.system['functionSource']['object'])
        disabled = [r for ref in function['livePages'] for r in read_object(self.container, ref)['items']]
        self.assertEqual(set(items), {r['id'] for r in disabled})
        self.assertTrue(all((r['reasons'], r['detail']) == (items[r['id']]['reasons'], items[r['id']]['detail']) for r in disabled))

    def test_only_this_source_system_is_enabled(self):
        inventory = read_object(self.container, self.index['sourceInventory'])
        enabled = []
        for ref in inventory['systemPages']:
            for row in read_object(self.container, ref)['systems']:
                value = read_object(self.container, row['object'])
                if value['enabled']: enabled.append(value['id'])
                else:
                    self.assertEqual({}, value['data'])
                    self.assertTrue(value['reasons'])
                    self.assertTrue(all(c['converted'] == 0 for c in value['counts'].values()))
        self.assertEqual(sorted([MANDATORY_SYSTEM, SYSTEM_2007, *self.group_systems()]),
                         sorted(enabled))

    def test_enabled_empty_fake_cross_scope_changed_scaling_and_writes_are_rejected(self):
        mutations = [
            lambda s: s.update(data={}),
            lambda s: s.update(bringUpPages=[]),
            lambda s: s['data'].update(pids=[]),
            lambda s: s.update(id='SYSLIST_EU.BIN@0009d8f3'),
            lambda s: s.update(entrySelector=0x51),
            lambda s: s['sourceRecords'].append('SYSLIST_EU.BIN@00000000'),
            lambda s: s['ecuSource'].update(sha256='0' * 64),
            lambda s: s['data']['ecus'][0]['link']['kLine'].update(baud=10400),
            lambda s: s['data']['ecus'][0]['link'].update(testerPresent='FF'),
            lambda s: s['data']['pids'][0].update(formula='A+40'),
            lambda s: s['data']['pids'][0].update(header='8014F0'),
            lambda s: s['data']['ecus'][0]['diagnostics']['faults']['layout']['labels'][0].update(description='Fake label'),
            lambda s: s['data']['ecus'][0]['diagnostics']['faults']['workflow']['queries'].pop(),
            lambda s: s['data']['ecus'][0]['diagnostics']['faults'].update(clear=self.review['diagnostics']['faults']['clear']),
            lambda s: s['data']['ecus'][0]['diagnostics']['identification'][0]['read'].update(request='21C1'),
            lambda s: s['transportPolicy'].update(initialFrameHex='8110F18103'),
        ]
        for change in mutations:
            altered = copy.deepcopy(self.system); change(altered)
            with self.subTest(change=mutations.index(change)), self.assertRaises(ValueError):
                validate_system(altered)
                verify_enabled(altered, self.review)


if __name__ == '__main__': unittest.main()
