"""Policy defaults must not be presented as source or physical equivalence."""
import copy
import unittest
from unittest.mock import patch

from toyota_ecmect_pack import ENTRY, LINK, source_keepalive, transport_for


def fixture():
    return {'transportPlan': {'region': 1, 'function': 61,
            'entry': {'sourceRecord': ENTRY, 'selector': 0x11},
            'keepalive': {'requestHex': '2100', 'sourceRecords': ['EU/Toyota_bin_11.BIN@00000001']}}}


class EcmectTransportPolicyTest(unittest.TestCase):
    def test_policy_defaults_and_blockers_are_explicit(self):
        review = fixture()
        policy = transport_for(review)
        fields = ('p1MaxMs', 'p2MaxMs', 'p2StarMs', 'p3MinMs', 'p3MaxMs', 'p4Ms', 'initAddress')
        self.assertEqual({key: LINK['kLine'][key] for key in fields}, policy['policyDefaults'])
        self.assertNotIn('testerPresent', policy['policyDefaults'])
        self.assertEqual('2100', policy['sourceKeepalive']['requestHex'])
        self.assertIn('not established as equivalent', policy['blockers'][0])
        self.assertIn('not a decoded source address', policy['blockers'][1])
        self.assertEqual('PENDING', policy['physicalValidation'])
        review['transportPlan']['keepalive']['requestHex'] = 'FFFF'
        self.assertEqual('2100', policy['sourcePlan']['keepalive']['requestHex'])

    @patch('toyota_ecmect_pack.verify_source_read_review')
    def test_source_keepalive_requires_exact_entry_and_compiler_pin(self, verify):
        review = fixture()
        self.assertEqual('2100', source_keepalive(review))
        verify.assert_called_once_with(review)
        for key, value in [('sourceRecord', 'SYSTEMENTER.BIN@00002f7e'), ('selector', 0x51)]:
            altered = copy.deepcopy(review)
            altered['transportPlan']['entry'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                source_keepalive(altered)


if __name__ == '__main__':
    unittest.main()
