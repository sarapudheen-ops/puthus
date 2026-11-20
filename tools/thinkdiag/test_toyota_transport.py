"""Synthetic entry boundaries plus literal reader-review bytes; no ECU operations."""
from dataclasses import replace
import unittest
from unittest import mock

from test_toyota_entries import document
from toyota_entries import EntryIndex
from toyota_transport import ConsumerCall, FastKwpEntry, fast_kwp_packet, selected_fast_kwp


class FastKwpTransportTest(unittest.TestCase):
    def setUp(self):
        self.index = EntryIndex(document())
        self.entry = self.index.select(1, 61, 0x11)
        self.profile = FastKwpEntry.from_entry(self.entry)

    def test_native_comparison_rejects_other_library_bytes_before_mapping_or_execution(self):
        from toyota_native_transport import compare_fast_kwp
        with mock.patch('toyota_native_transport.NativeSandbox') as native:
            for raw in (b'not the supplied library', b'', bytearray(b'fixture'), None):
                with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, 'exact supplied'):
                    compare_fast_kwp({'libTOYOTA_COMM.so': raw}, document())
            native.assert_not_called()

    def test_source_words_lists_baud_and_order_are_not_normalized_or_reinterpreted(self):
        p = self.profile
        self.assertEqual((17, 1, 129, 10416), (p.selector, p.transport, p.flags, p.baud))
        self.assertEqual(b'\xf0\x13', p.send_address)
        self.assertEqual(((7, 7),), p.pins)
        self.assertEqual(bytes.fromhex('053c001919'), p.timing)
        self.assertEqual(((104,), (102,), (127,)), (p.initial_templates, p.keepalive_templates, p.exit_templates))
        r = p.report()
        self.assertEqual('SYSTEMENTER.BIN@00000014', r['sourceRecord'])
        self.assertEqual('f013', r['sendAddressSourceHex'])
        self.assertEqual('053c001919', r['sourceTimingHex'])
        self.assertEqual(r['sourceRecord'], r['sourceRecords'][0])
        self.assertEqual(6, len(r['sourceRecords']))
        self.assertTrue(all(key not in r for key in ('enabled', 'verified', 'requestId', 'responseId', 'link', 'p1MaxMs')))
        self.assertEqual(ConsumerCall('x', (1, 2.0)).report(), {'api': 'x', 'arguments': [1, 2.0]})

    def test_literal_configuration_sequence_and_connector_branches(self):
        p = self.profile
        calls = p.configuration_calls(connector=16, pin_pair_index=0)
        self.assertEqual((
            ConsumerCall('BeginCombination', ()), ConsumerCall('EnableLLine', (0,)),
            ConsumerCall('SelectPIN', (16, 1, 0, 7, 7)),
            ConsumerCall('SetCommunicationMaximumWaitTime', (15000,)),
            ConsumerCall('SetEcuCommunicationVoltageLogic', (255, 255)),
            ConsumerCall('SetEcuBaudRate', (10416.0,)),
            ConsumerCall('SetCommunicationParameter', (1, 8, 1, 0)),
            ConsumerCall('SetEcuCommTimeInterval', (5.0, 60, 600)), ConsumerCall('EndCombination', ())), calls)
        other = p.configuration_calls(connector=32, pin_pair_index=0)
        self.assertEqual(ConsumerCall('SelectPIN', (32, 14, 0x1900, 0, 0)), other[1])
        self.assertEqual(ConsumerCall('SelectPIN', (32, 1, 0, 7, 7)), other[2])
        self.assertEqual(calls[3:], other[3:])

    def test_four_byte_source_load_retains_shifted_high_byte_on_the_alternate_connector(self):
        # A native comparison caught the tempting-but-wrong three-byte slice:
        # source 05 3c 00 23 passes 0x2300, not 0, to SelectPIN's third register.
        p = replace(self.profile, timing=bytes.fromhex('053c00230f'))
        self.assertEqual((32, 14, 0x2300, 0, 0), p.configuration_calls(connector=32, pin_pair_index=0)[1].arguments)
        self.assertEqual((0,), p.configuration_calls(connector=16, pin_pair_index=0)[1].arguments)
        p = replace(p, timing=bytes.fromhex('092fff230f'))
        self.assertEqual((32, 14, 0x23ff, 0, 0), p.configuration_calls(connector=32, pin_pair_index=0)[1].arguments)
        self.assertEqual((255,), p.configuration_calls(connector=16, pin_pair_index=0)[1].arguments)

    def test_baud_9600_exception_and_binary32_conversion_are_explicit_not_generic_defaults(self):
        for baud, third in ((9600, 500), (9599, 600), (10416, 600), (10400, 600)):
            calls = replace(self.profile, baud=baud).configuration_calls(connector=16, pin_pair_index=0)
            self.assertEqual((float(baud),), calls[5].arguments)
            self.assertEqual((5.0, 60, third), calls[7].arguments)
        calls = replace(self.profile, baud=16777217).configuration_calls(connector=16, pin_pair_index=0)
        self.assertEqual((16777216.0,), calls[5].arguments)  # AArch64 scvtf s0, not binary64.

    def test_no_source_region_function_full_selector_or_transport_fallback(self):
        selected = selected_fast_kwp(self.index, region=1, function=61, selector=0x11)
        alternative = selected_fast_kwp(self.index, region=1, function=61, selector=0x51)
        self.assertEqual(self.profile, selected)
        self.assertEqual((0x51, 5, 0x33, b'\xf0\x10'),
                         (alternative.selector, alternative.transport, alternative.flags, alternative.send_address))
        self.assertNotEqual(selected.source_record, alternative.source_record)
        for region, function, selector in ((2, 61, 0x11), (1, 62, 0x11), (1, 61, 1), (1, 61, 0x12),
                                             (True, 61, 0x11), (1, False, 0x11), (1, 61, True)):
            with self.subTest(region=region, function=function, selector=selector), self.assertRaises(ValueError):
                selected_fast_kwp(self.index, region=region, function=function, selector=selector)
        with self.assertRaises(ValueError): selected_fast_kwp(None, region=1, function=61, selector=17)

    def test_an_explicit_pin_pair_is_required_without_automatic_retry_or_pair_selection(self):
        p = replace(self.profile, pins=((7, 7), (3, 15)))
        calls = p.configuration_calls(connector=16, pin_pair_index=1)
        self.assertEqual((16, 1, 0, 3, 15), calls[2].arguments)
        self.assertEqual(9, len(calls))
        for connector, pair in ((-1, 0), (256, 0), (True, 0), (16, -1), (16, 2), (16, True), (16, 0.0)):
            with self.subTest(connector=connector, pair=pair), self.assertRaises(ValueError):
                p.configuration_calls(connector=connector, pin_pair_index=pair)

    def test_missing_odd_unknown_truncated_or_wrong_schema_entries_reject(self):
        for key, value in (('runtime107b0', ''), ('runtime107b0', 'f0'), ('runtime107b0', 'f01300'),
                           ('runtime107e4', 'gg'), ('runtime107f0', '053c0019'),
                           ('pinPairs', ''), ('pinPairs', '07'), ('pinPairs', '070707'), ('pinPairs', 'gggg'),
                           ('pinPairs', '0707' * 128)):
            fields = {**self.entry.fields, key: value}
            with self.subTest(key=key, value=value[:30]), self.assertRaises(ValueError):
                FastKwpEntry.from_entry(replace(self.entry, fields=fields))
        for key in self.entry.fields:
            fields = dict(self.entry.fields); del fields[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                FastKwpEntry.from_entry(replace(self.entry, fields=fields))
        for change in ({'fields': {**self.entry.fields, 'unknown': '00'}}, {'transport': 2}, {'flags': 0x80},
                       {'descriptor': 0x12}, {'baud': 0}, {'baud': True}, {'init': (65536,)}, {'exit': [127]}):
            with self.subTest(change=change), self.assertRaises(ValueError): FastKwpEntry.from_entry(replace(self.entry, **change))
        with self.assertRaises(ValueError): FastKwpEntry.from_entry(None)

    def test_profile_cannot_be_constructed_with_retyped_or_mutable_unchecked_operational_fields(self):
        for change in ({'source_record': 'SYSTEMENTER.BIN'}, {'source_record': 'other@00000014'},
                       {'selector': True}, {'transport': True}, {'flags': True}, {'baud': True},
                       {'baud': -1}, {'baud': 0x80000000}, {'runtime107e4': False},
                       {'send_address': b'\\xf0'}, {'send_address': bytearray([0xf0, 0x13])},
                       {'timing': bytes(4)}, {'pins': ()}, {'pins': [(7, 7)]},
                       {'pins': ((7, 256),)}, {'pins': ((True, 7),)}, {'initial_templates': [104]},
                       {'keepalive_templates': (True,)}, {'exit_templates': (65536,)}):
            with self.subTest(change=change), self.assertRaises(ValueError): replace(self.profile, **change)

    def test_literal_multi_pack_addresses_header_alias_and_checksum(self):
        self.assertEqual(bytes.fromhex('8113f08105'), self.profile.packet(b'\x81'))
        self.assertEqual(bytes.fromhex('8213f013b048'), self.profile.packet(bytes.fromhex('13b0')))
        alternative = selected_fast_kwp(self.index, region=1, function=61, selector=0x51)
        self.assertEqual(bytes.fromhex('8210f013b045'), alternative.packet(bytes.fromhex('13b0')))
        for flags in (0x81, 0x33):
            for size in range(1, 64):
                payload = bytes((size * 17 + i * 73) & 255 for i in range(size))
                frame = fast_kwp_packet(bytes.fromhex('a4b5'), flags, payload)
                self.assertEqual(bytes([0x80 | size, 0xb5, 0xa4]) + payload, frame[:-1])
                self.assertEqual(sum(frame[:-1]) & 255, frame[-1])
                self.assertEqual(size + 4, len(frame))

    def test_oversized_or_nonbyte_requests_never_wrap_change_addressing_or_choose_another_variant(self):
        for size in (0, 64, 127, 128, 255, 256, 4096):
            with self.subTest(size=size), self.assertRaises(ValueError): self.profile.packet(bytes(size))
        for payload in ('81', [0x81], bytearray([0x81]), None):
            with self.subTest(payload=payload), self.assertRaises(ValueError): self.profile.packet(payload)
        for flags in (True, 0x80, 0xc0, 0xc1, 0x20, 0x22, 0x181):
            with self.subTest(flags=flags), self.assertRaises(ValueError): fast_kwp_packet(b'\xf0\x13', flags, b'\x81')
        for address in (b'', b'\xf0', b'\xf0\x13\0', 'f013', [0xf0, 0x13], None):
            with self.subTest(address=address), self.assertRaises(ValueError): fast_kwp_packet(address, 0x81, b'\x81')


if __name__ == '__main__': unittest.main()
