import unittest

from test_toyota_entries import document
from test_toyota_lifecycle import frame_sources
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_link_map import map_plan, map_contracts
from toyota_ecmect_pack import LINK, link_for


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

    def test_shared_link_for_preserves_ecmect_policy_and_exact_baud(self):
        lifecycle = selected_fast_kwp_lifecycle(EntryIndex(document()), SourceFrames(frame_sources(), 'EU'),
                                               region=1, function=61, selector=0x11)
        actual = link_for(lifecycle)
        self.assertEqual({**LINK, 'testerPresent': '3E'}, actual['link'])
        self.assertEqual(10416, actual['source']['baud'])
        self.assertEqual('8113f08105', actual['source']['initPacketHex'])
        self.assertEqual('8113f03ec2', actual['source']['exitPacketHex'])
        self.assertEqual('source', actual['provenance']['kLine.baud'])
        self.assertEqual('policy', actual['provenance']['kLine.p1MaxMs'])

    def test_transport5_link_is_not_approximated(self):
        lifecycle = selected_fast_kwp_lifecycle(EntryIndex(document()), SourceFrames(frame_sources(), 'EU'),
                                               region=1, function=61, selector=0x51)
        with self.assertRaisesRegex(ValueError, 'link_representation_unsupported:transport-5'):
            link_for(lifecycle)

    def test_incomplete_contract_fails_closed(self):
        with self.assertRaises(ValueError):
            map_contracts({'sourceEntries': 1, 'entries': []})


if __name__ == '__main__':
    unittest.main()
