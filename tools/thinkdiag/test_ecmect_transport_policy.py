"""Policy defaults must not be presented as source or physical equivalence."""
import unittest

from toyota_ecmect_pack import LINK, transport_for


class EcmectTransportPolicyTest(unittest.TestCase):
    def test_policy_defaults_and_blockers_are_explicit(self):
        source_plan = {'sourceRecords': ['SYSTEMENTER.BIN@00002f63'], 'keepalive': {'requestHex': '2100'}}
        policy = transport_for({'transportPlan': source_plan})
        fields = ('p1MaxMs', 'p2MaxMs', 'p2StarMs', 'p3MinMs', 'p3MaxMs', 'p4Ms', 'initAddress')
        self.assertEqual({'testerPresent': LINK['testerPresent'],
                          **{key: LINK['kLine'][key] for key in fields}}, policy['policyDefaults'])
        self.assertEqual('3E', policy['policyDefaults']['testerPresent'])
        self.assertEqual('2100', policy['sourcePlan']['keepalive']['requestHex'])
        self.assertIn('not established as equivalent', policy['blockers'][0])
        self.assertIn('not a decoded source address', policy['blockers'][1])
        self.assertEqual('PENDING', policy['physicalValidation'])
        source_plan['keepalive']['requestHex'] = 'FFFF'
        self.assertEqual('2100', policy['sourcePlan']['keepalive']['requestHex'])


if __name__ == '__main__':
    unittest.main()
