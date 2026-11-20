"""No ECU or native library is used by these bounded reply-decoder unit tests."""
from dataclasses import replace
import unittest

from toyota_faults import (NativeFaultLayout, state_ids, STATE_CURRENT, STATE_HISTORY,
    STATE_PENDING, STATE_READNESS, STATE_PERMANENT, STATE_FAILED, STATE_CONFIRMED)


class ToyotaFaultDecoderTest(unittest.TestCase):
    def test_states_are_native_text_ids_not_assumed_uds_statuses(self):
        expected = (STATE_CURRENT, STATE_HISTORY, STATE_PENDING, STATE_READNESS,
                    STATE_PERMANENT, STATE_FAILED, STATE_CONFIRMED)
        for kind, result in enumerate(expected, 1):
            for status in (0, 1, 0xff): self.assertEqual((result,), state_ids(kind, status))
        self.assertEqual((STATE_CURRENT, STATE_HISTORY), state_ids(8, 40))
        self.assertEqual((STATE_HISTORY,), state_ids(8, 41))
        self.assertEqual((STATE_CURRENT,), state_ids(9, 0x10))
        self.assertEqual((STATE_HISTORY,), state_ids(9, 0x08))
        self.assertEqual((STATE_FAILED, STATE_PENDING, STATE_CONFIRMED), state_ids(10, 0xff, 0xff))
        self.assertEqual((STATE_FAILED,), state_ids(10, 2, 2))
        self.assertEqual((), state_ids(10, 0xff, 0xf0))
        self.assertEqual((), state_ids(11, 255))
        for args in ((True, 0, 255), (-1, 0, 0), (1, 256, 255), (1, 0, -1)):
            with self.assertRaises(ValueError): state_ids(*args)

    def test_complete_counted_codes_skip_zero_and_deduplicate_only_equal_state_code_pairs(self):
        layout = NativeFaultLayout(0, 1, 2, 2)
        data = bytes.fromhex('03 04 20 00 00 01 23 04 20')
        self.assertEqual(((STATE_CURRENT, 0x0420), (STATE_CURRENT, 0x0123)), layout.decode(data, 1))
        self.assertEqual(((STATE_HISTORY, 0x0420), (STATE_HISTORY, 0x0123)), layout.decode(data, 2))
        # Native uses count as a bound/presence guard, not an exact number of
        # nonzero records. Padding rows do not manufacture faults.
        self.assertEqual((), layout.decode(bytes.fromhex('00 00 00'), 1))
        with self.assertRaisesRegex(ValueError, 'count exceeds'): layout.decode(bytes.fromhex('02 04 20'), 1)
        presence = replace(layout, count_is_presence=True)
        self.assertEqual(((STATE_CURRENT, 0x0420),), presence.decode(bytes.fromhex('ff 04 20'), 1))

    def test_each_assembly_rule_and_mask_keep_record_origin(self):
        for kind in (1, 2, 4):
            self.assertEqual(((STATE_CURRENT, 0x123456),), NativeFaultLayout(255, 1, 3, kind).decode(bytes.fromhex('aa 12 34 56'), 1))
        # Kind 3 keeps the entire code, and derives a state nibble from it.
        self.assertEqual(((STATE_CURRENT, 0x1234),), NativeFaultLayout(255, 0, 2, 3).decode(bytes.fromhex('12 34'), 9))
        self.assertEqual(((STATE_HISTORY, 0x0234),), NativeFaultLayout(255, 0, 2, 3).decode(bytes.fromhex('02 34'), 9))
        for kind in (5, 6):
            layout = NativeFaultLayout(255, 2, 4, kind)
            self.assertEqual(((STATE_FAILED, 0x042013), (STATE_PENDING, 0x042013)),
                             layout.decode(bytes.fromhex('02 ff 04 20 13 0f'), 10, 0x05))
        self.assertEqual(((STATE_CURRENT, 0x123456),),
            NativeFaultLayout(255, 0, 6, 7).decode(bytes.fromhex('12 34 56 aa bb cc'), 1))

    def test_kind_three_override_is_native_mode_specific_not_a_global_offset(self):
        layout = NativeFaultLayout(0, 1, 2, 2)
        self.assertEqual(((STATE_PENDING, 0x0420),), layout.decode(bytes.fromhex('04 20'), 3))
        mode2 = replace(layout, mode_lowbits=2)
        self.assertEqual(((STATE_PENDING, 0x0420),), mode2.decode(bytes.fromhex('01 04 20'), 3))
        with self.assertRaises(ValueError): mode2.decode(bytes.fromhex('04 20'), 3)

    def test_short_counts_partial_tail_invalid_fields_and_oversize_fail_atomically(self):
        layout = NativeFaultLayout(0, 1, 2, 2)
        for data in (b'', bytes.fromhex('01 04'), bytes.fromhex('01 04 20 ff')):
            with self.assertRaises(ValueError): layout.decode(data, 1)
        with self.assertRaises(ValueError): layout.decode([1, 4, 32], 1)
        with self.assertRaises(ValueError): layout.decode(bytes(4097), 1)
        for fields in ({'record_bytes': 0}, {'record_bytes': 255}, {'assembly_kind': 0},
                {'assembly_kind': 8}, {'assembly_kind': 6, 'record_bytes': 1}, {'count_index': -1},
                {'count_is_presence': 1}, {'mode_lowbits': 8}, {'record_start': True}):
            with self.subTest(fields=fields), self.assertRaises(ValueError): replace(layout, **fields)


if __name__ == '__main__': unittest.main()
