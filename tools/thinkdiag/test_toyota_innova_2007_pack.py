import copy
import unittest

from toyota_ecmect_pack import FUNCTION, MANDATORY_SYSTEM
from toyota_innova_2007_pack import ECU, ENTRY, SYSTEM, TX, RX, data_for, enable
from vehicle_system_validation import KINDS


def fixtures():
    plan = {'entry': {'sourceRecord': ENTRY, 'selector': 0x51, 'nativeTransport': 5, 'baud': 10416},
            'initialWireHex': '8110F08102', 'keepalive': {'requestHex': '3e'}, 'sourceRecords': [ENTRY]}
    review = {'systemId': SYSTEM, 'entryId': ENTRY, 'functionKey': 61, 'selector': 0x51, 'ecuFile': ECU,
              'rawSystemHex': 'ab', 'transportPlan': plan, 'uncompiledLiveRows': 985,
              'diagnostics': {'faults': {'layout': {'labels': [None] * 1414}, 'clear': {}}, 'identification': [{}]},
              'liveReads': [{'id': str(i), 'sourceRecords': [SYSTEM, ENTRY, FUNCTION]} for i in range(30)]}
    system = {'id': SYSTEM, 'kind': 'vehicle-system', 'sourceVerified': True, 'functionKey': 61,
              'entrySelector': 0x51, 'rawHex': 'ab', 'sourceRecords': [SYSTEM, FUNCTION, 'SYSTEMENTER.BIN@000001c6', ENTRY],
              'ecuSource': {'sourceFile': ECU, 'sha256': 'abc'},
              'functionSource': {'sourceRecord': FUNCTION, 'functionKey': 61, 'declaredLiveRows': 1015},
              'entrySource': {'sourceRecord': ENTRY, 'selector': 0x51},
              'counts': {k: {} for k in KINDS}}
    return system, review, {'inputs': [{'file': ECU, 'sha256': 'abc'}]}


class Innova2007PackTest(unittest.TestCase):
    def test_exact_link_and_read_only_scope(self):
        system, review, pilot = fixtures()
        result = enable(system, review, pilot)
        ecu = result['data']['ecus'][0]
        self.assertEqual((TX, RX, 16, 10416, '3E'),
                         (ecu['requestId'], ecu['responseId'], ecu['link']['kLine']['initAddress'],
                          ecu['link']['kLine']['baud'], ecu['link']['testerPresent']))
        self.assertTrue(result['readOnly'])
        self.assertNotIn('clear', ecu['diagnostics']['faults'])
        self.assertNotIn(MANDATORY_SYSTEM, str(result))
        self.assertTrue(all((p['header'], p['responseHeader']) == (TX, RX) for p in result['data']['pids']))

    def test_wrong_system_entry_selector_and_hash_fail(self):
        mutations = [lambda s: s.update(id=MANDATORY_SYSTEM), lambda s: s.update(entrySelector=0x11),
                     lambda s: s['entrySource'].update(sourceRecord='SYSTEMENTER.BIN@00002f63'),
                     lambda s: s['ecuSource'].update(sha256='bad')]
        for mutate in mutations:
            system, review, pilot = fixtures()
            mutate(system)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                enable(system, review, pilot)

    def test_pilot_identity_cannot_enter_2007_definitions(self):
        _, review, _ = fixtures()
        altered = copy.deepcopy(review)
        altered['liveReads'][0]['sourceRecords'].append(MANDATORY_SYSTEM)
        with self.assertRaises(ValueError):
            data_for(altered)


if __name__ == '__main__':
    unittest.main()
