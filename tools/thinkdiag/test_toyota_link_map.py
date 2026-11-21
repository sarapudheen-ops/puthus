import unittest

from test_toyota_entries import document
from test_toyota_lifecycle import frame_sources
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_link_map import map_plan, map_contracts


class ToyotaLinkMapTest(unittest.TestCase):
    def test_exact_selector_and_frames_and_no_app_link_inference(self):
        index = EntryIndex(document())
        frames = SourceFrames(frame_sources(keepalive=b'\x21\x00'), 'EU')
        first = map_plan(selected_fast_kwp_lifecycle(index, frames, region=1, function=61, selector=0x11).report())
        other = map_plan(selected_fast_kwp_lifecycle(index, frames, region=1, function=61, selector=0x51).report())
        self.assertEqual('8113F08105', first['initialWireHex'])
        self.assertEqual('8110F08102', other['initialWireHex'])
        self.assertEqual('2100', other['keepalive']['requestHex'])
        self.assertIn('transport5_iso_fallback_not_approved', other['blockers'])
        self.assertNotIn('transport5_iso_fallback_not_approved', first['blockers'])
        self.assertIn('mic3313_timing_equivalence_unproven', first['blockers'])
        self.assertFalse(first['executable'])
        self.assertNotIn('responseHeader', first)
        self.assertNotIn('initAddress', first)
        self.assertNotIn('p1MaxMs', first)

    def test_incomplete_contract_fails_closed(self):
        with self.assertRaises(ValueError):
            map_contracts({'sourceEntries': 1, 'entries': []})


if __name__ == '__main__':
    unittest.main()
