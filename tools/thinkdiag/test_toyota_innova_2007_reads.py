import copy
import unittest

from toyota_innova_2007_reads import ENTRY, SYSTEM, rebind_live
from toyota_runtime_compile import MANDATORY_SYSTEM


class InnovaReadScopeTest(unittest.TestCase):
    def test_rebinding_replaces_only_pilot_identity_and_keeps_source_rows(self):
        source = [{'id': 'FUNCDATA_EU.BIN@00051245',
                   'sourceRecords': [MANDATORY_SYSTEM, 'FUNCDATA_EU.BIN@000005e9'],
                   'supportChecks': [{'sourceRecords': [MANDATORY_SYSTEM, 'EU/Ecu_Data_0081.BIN@00000001']}]}]
        original = copy.deepcopy(source)
        result = rebind_live(source)
        self.assertEqual(original, source)
        self.assertEqual([SYSTEM, ENTRY, 'FUNCDATA_EU.BIN@000005e9'], result[0]['sourceRecords'])
        self.assertEqual([SYSTEM, 'EU/Ecu_Data_0081.BIN@00000001'], result[0]['supportChecks'][0]['sourceRecords'])
        self.assertNotIn(MANDATORY_SYSTEM, str(result))

    def test_missing_or_malformed_provenance_is_rejected(self):
        for row in ({}, {'sourceRecords': []}, {'sourceRecords': 'bad'}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                rebind_live([row])


if __name__ == '__main__':
    unittest.main()
