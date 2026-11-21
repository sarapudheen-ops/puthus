import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from toyota_innova_2007_reads import ENTRY, SYSTEM, compile_review, rebind_live, verify_candidate_bytes
from toyota_runtime_compile import MANDATORY_SYSTEM
from vehicle_bundle import SOURCE_SHA256, canonical


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

    def test_candidate_pin_rejects_wrong_bytes_and_name(self):
        raw = canonical({'systemId': SYSTEM, 'entryId': ENTRY}) + b'\n'
        pin = {'file': 'candidate.json', 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
               'sourceSha256': SOURCE_SHA256}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'pin.json'
            path.write_text(json.dumps(pin))
            with patch('toyota_innova_2007_reads.PIN', path):
                self.assertEqual(SYSTEM, verify_candidate_bytes(raw, 'candidate.json')['systemId'])
                for data, name in ((raw + b' ', 'candidate.json'), (raw, 'wrong.json')):
                    with self.assertRaises(ValueError):
                        verify_candidate_bytes(data, name)

    def test_wrong_selector_or_entry_fails_before_compilation(self):
        joined = {'systemId': SYSTEM, 'functionKey': 61, 'selector': 0x51, 'callerMode': 11,
                  'ecuFilename': {'text': 'Ecu_Data_0081.BIN'},
                  'selectedEntry': {'id': ENTRY, 'transportCode': 5}, 'sourceLifecycle': {}}
        for field in ('selector', 'entry'):
            bad = copy.deepcopy(joined)
            if field == 'selector':
                bad['selector'] = 0x11
            else:
                bad['selectedEntry']['id'] = 'SYSTEMENTER.BIN@00002f63'
            with self.subTest(field=field), patch('toyota_innova_2007_reads.report', return_value=bad), \
                    self.assertRaisesRegex(ValueError, 'source join'):
                compile_review({}, {'TEXT_EN.GAG': {}}, {})

    def test_missing_or_malformed_provenance_is_rejected(self):
        for row in ({}, {'sourceRecords': []}, {'sourceRecords': 'bad'}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                rebind_live([row])


if __name__ == '__main__':
    unittest.main()
