"""The generated one-system pack, not a synthetic enabled/empty stand-in."""
import copy
import json
import os
from pathlib import Path
import unittest

from toyota_ecmect_pack import (ENTRY, MANDATORY_SYSTEM, load_review, verify_enabled)
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

    def test_real_selected_system_has_nonempty_exact_read_definitions(self):
        s = self.system
        self.assertTrue(s['enabled']); self.assertTrue(s['readOnly']); self.assertEqual([], s['reasons'])
        self.assertEqual((61, 17, ENTRY), (s['functionKey'], s['entrySelector'], s['entrySource']['sourceRecord']))
        self.assertEqual({'pids': 30, 'dtcs': 1414, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}, validate_system(s))
        self.assertTrue(verify_enabled(s, self.review))
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
        self.assertEqual((1, 1445), (self.index['summary']['enabledSystems'], self.index['summary']['executableDefinitions']))

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
        self.assertEqual([MANDATORY_SYSTEM], enabled)

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
