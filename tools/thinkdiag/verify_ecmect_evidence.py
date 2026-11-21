#!/usr/bin/env python3
"""Run real-pack acceptance checks and publish bounded, computed evidence."""
import json
import unittest
from test_ecmect_pack import EcmectPackTest

names = ('test_real_selected_system_has_nonempty_exact_read_definitions',
         'test_2007_is_independently_joined_and_read_only',
         'test_policy_rejects_missing_corrupt_selector_and_third_system',
         'test_only_this_source_system_is_enabled')
suite = unittest.TestSuite(EcmectPackTest(name) for name in names)
result = unittest.TextTestRunner(verbosity=2).run(suite)
if not result.wasSuccessful() or result.skipped or result.testsRun != len(names):
    raise SystemExit('Real-pack acceptance checks failed or were skipped')
# setUpClass populated values from the same actual pack just checked.
cls = EcmectPackTest
systems = []
for obj in (cls.system, cls.system_2007):
    systems.append({'id': obj['id'], 'entry': obj['entrySource']['sourceRecord'],
                    'selector': obj['entrySelector'], 'sourceRecords': obj['sourceRecords'],
                    'ecuCount': len(obj['data']['ecus']),
                    'converted': {k: v['converted'] for k, v in obj['counts'].items()}})
print(json.dumps({'enabledSystems': cls.index['summary']['enabledSystems'],
                  'executableDefinitions': cls.index['summary']['executableDefinitions'],
                  'systems': systems, 'physicalValidation': 'PENDING'}, indent=2))
