"""Source control-flow vectors with explicit fake adapter replies, never hardware approval."""
from dataclasses import replace
import unittest

from test_toyota_entries import document
from test_toyota_lifecycle import frame_sources
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_entry_runtime import (CombinedReply as R, fast_entry_trace, signed16,
                                  reentry_is_selected, read_reentry_trace)


class SourceEntryRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.lifecycle = selected_fast_kwp_lifecycle(EntryIndex(document()), SourceFrames(frame_sources(), 'EU'),
                                                    region=1, function=61, selector=17)

    def test_first_success_is_identical_to_reviewed_trace_without_retries_or_sibling_selection(self):
        result = fast_entry_trace(self.lifecycle, (R(9, 0xc1),))
        self.assertEqual((0, True, 1), (result.native_status, result.has_positive_reply, result.consumed_replies))
        self.assertEqual(self.lifecycle.fast_success_calls(connector=16, pin_pair_index=0), result.calls)
        self.assertFalse(result.wants_iso_fallback(1)); self.assertFalse(result.wants_iso_fallback(5))
        self.assertIn('8113f08105', str(result.calls))

    def test_only_negative_combined_return_selects_alternate_api(self):
        for replies, alternatives, attempts, status in (
                ((R(-1), R(9, 0xc1)), 1, 1, 0),
                ((R(0), R(9, 0xc1)), 0, 2, 0),
                ((R(9, 0x7f), R(9, 0xc1)), 0, 2, 0),
                ((R(9, 0x7f), R(9, 0x7f)), 0, 2, -2),
                ((R(-1), R(-1), R(-1), R(-1)), 2, 2, -1),
                ((R(-1), R(0), R(-1), R(9, 0xc1)), 2, 2, 0)):
            with self.subTest(replies=replies):
                result = fast_entry_trace(self.lifecycle, replies)
                self.assertEqual(status, result.native_status)
                self.assertEqual(alternatives, sum(c.api == 'ToyotaKwpSendDataToEcuGetMultiFrameAnswer' for c in result.calls))
                self.assertEqual(attempts, sum(c.api == 'CommSubFunc_00000003' for c in result.calls))
                self.assertEqual(attempts - 1, sum(c.api == 'usleep' for c in result.calls))
                self.assertEqual(status == -1, result.wants_iso_fallback(5))
                self.assertFalse(result.wants_iso_fallback(1))
                self.assertNotIn('105f', str(result.calls).lower())

    def test_native_zero_success_is_explicitly_not_positive_initialization_evidence(self):
        result = fast_entry_trace(self.lifecycle, (R(0), R(0)))
        self.assertEqual(0, result.native_status)
        self.assertFalse(result.has_positive_reply)
        self.assertFalse(result.wants_iso_fallback(5))

    def test_only_exhausted_adapter_failure_may_try_the_next_declared_pin_pair(self):
        life = replace(self.lifecycle, entry=replace(self.lifecycle.entry, pins=((7, 7), (15, 15))))
        result = fast_entry_trace(life, (R(-1), R(-1), R(-1), R(-1), R(9, 0xc1)))
        self.assertEqual(0, result.native_status)
        self.assertEqual(2, sum(c.api == 'SetEcuBaudRate' for c in result.calls))
        self.assertEqual([(16, 1, 0, 7, 7), (16, 1, 0, 15, 15)],
                         [c.arguments for c in result.calls if c.api == 'SelectPIN'])
        self.assertEqual(-2, fast_entry_trace(life, (R(9, 0x7f), R(9, 0x7f))).native_status)
        self.assertEqual(1, sum(c.api == 'SetEcuBaudRate' for c in fast_entry_trace(life, (R(9, 0x7f), R(9, 0x7f))).calls))

    def test_bounded_inputs_do_not_fabricate_missing_responses_or_drop_unused_responses(self):
        for replies in ((), (R(-1),), (R(9, 0xc1), R(9, 0xc1)), ('success',), tuple(R(-1) for _ in range(5))):
            with self.subTest(replies=replies), self.assertRaises(ValueError): fast_entry_trace(self.lifecycle, replies)
        for value in (True, None, -32769, 0x100000000):
            with self.assertRaises(ValueError): signed16(value)
        self.assertEqual(-1, signed16(0xffffffff)); self.assertEqual(-2, signed16(0xfffe))
        with self.assertRaises(ValueError): R(9)
        with self.assertRaises(ValueError): R(9, True)

    def test_read_reentry_uses_temporary_context_and_never_replays_fault_session_setup(self):
        for transport in range(12):
            for result in (-32768, -1, 0, 1, 9, 255, 0x7ffe, 0x7fff, 0xffff, 0x10000):
                eligible = transport in (1, 2, 5) and (signed16(result) < 0 or result == 0x7fff)
                self.assertEqual(eligible, reentry_is_selected(transport, result))
                trace = read_reentry_trace(transport, result, 11)
                self.assertEqual(5 if eligible else 0, len(trace))
                if trace:
                    self.assertEqual([('SetContext174d1', (255,)), ('SystemExitPart', ()), ('SystemEnterPart', ()),
                                      ('SetContext174d1', (11,)), ('RetrySameReadOnce', ())], [(c.api, c.arguments) for c in trace])
                    self.assertNotIn('105f', str(trace).lower())
        with self.assertRaises(ValueError): read_reentry_trace(1, -1, 256)




class SourceIsoEntryRuntimeTest(unittest.TestCase):
    setUp = SourceEntryRuntimeTest.setUp

    def test_low_word_zero_selects_exact_entry_initial_and_nonzero_retries_no_sleep(self):
        from toyota_entry_runtime import iso_entry_trace
        for handshake in (0, 0x10000):
            result = iso_entry_trace(self.lifecycle, (handshake,))
            self.assertEqual((0, 1), (result.native_status, result.consumed_handshakes))
            self.assertEqual(('SendCmdAndGetReceiveInitial', (255, '81', 129, 0)),
                             (result.calls[-1].api, result.calls[-1].arguments))
        for first in (-1, 1, 0x7fff, 0xffff):
            result = iso_entry_trace(self.lifecycle, (first, 0))
            self.assertEqual((0, 2), (result.native_status, result.consumed_handshakes))
            self.assertFalse(any(c.api == 'usleep' for c in result.calls))
        self.assertEqual(-1, iso_entry_trace(self.lifecycle, (-1, -1)).native_status)
        self.assertEqual(-1, iso_entry_trace(self.lifecycle, (0,), initial_result=-1).native_status)
        self.assertEqual(0, iso_entry_trace(self.lifecycle, (0,), initial_result=0).native_status)
        for words in ((), (0, 0), (-1,), (True,), (0x100000000,)):
            with self.assertRaises(ValueError): iso_entry_trace(self.lifecycle, words)


if __name__ == '__main__': unittest.main()
