import unittest
from unittest.mock import patch

import inspect_innova_2007 as inspector
from test_vehicle_conversion import fixture


class _Pilot:
    class entry:
        source_record = 'SYSTEMENTER.BIN@00002f63'

    def report(self):
        return {'keepalive': {'requestHex': '2100'}}


class InspectInnovaTest(unittest.TestCase):
    def test_missing_full_selector_is_reported_without_fallback(self):
        sources, texts = fixture()
        raw = bytearray(sources['SYSLIST_EU.BIN'])
        raw[14 + 27] = 0x51
        sources['SYSLIST_EU.BIN'] = bytes(raw)
        with patch.object(inspector, 'OFFSET', 14), patch.object(inspector, 'SourceFrames'), \
             patch.object(inspector, 'selected_fast_kwp_lifecycle', return_value=_Pilot()):
            result = inspector.report(sources, texts)
        self.assertEqual(74, len(result['rawHex']))
        self.assertEqual(0x51, result['selector'])
        self.assertEqual([0x11], result['directory']['descriptors'])
        self.assertIsNone(result['selectedEntry'])
        self.assertIsNone(result['sourceLifecycle'])
        self.assertEqual(1, result['functionIndex']['declaredLiveRows'])
        self.assertEqual('Ecu_Data_ffff.BIN', result['ecuFilename']['text'])

    def test_unknown_row_does_not_return_a_nearby_record(self):
        sources, texts = fixture()
        with self.assertRaisesRegex(ValueError, 'complete source row'):
            inspector.report(sources, texts)


if __name__ == '__main__':
    unittest.main()
