"""Synthetic lifecycle joins plus source-literal entry bytes, never an ECU test."""
from dataclasses import FrozenInstanceError, replace
import struct
import unittest
from unittest import mock

from test_toyota_entries import document
from test_toyota_live import typed
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import FastKwpLifecycle, selected_fast_kwp_lifecycle
from toyota_transport import ConsumerCall


def frame_sources(*, keepalive=b'\x3e', exit=b'\x3e', exit_reply=b'\x7e'):
    commands = [b'\x81', keepalive, exit, exit_reply]
    descriptors, blob = b'', b''
    for command in commands:
        descriptors += struct.pack('<IBB', len(blob), len(command), 0)
        blob += command
    return {'EU/Toyota_bin_00.BIN': struct.pack('<HII', 0, len(commands), len(descriptors + blob)) + descriptors + blob,
            'EU/Toyota_bin_11.BIN': typed(17, [struct.pack('<4H', 1, 1, 0, 0),
                struct.pack('<4H', 2, 2, 0, 0), struct.pack('<4H', 3, 3, 0, 4)]),
            'EU/Toyota_bin_12.BIN': typed(274, [struct.pack('<8H', 61, t, 1, f, 0, 0, 1, 0)
                for t, f in ((104, 1), (102, 2), (127, 3))])}


class FastKwpLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.index = EntryIndex(document())
        self.frames = SourceFrames(frame_sources(), 'EU')
        self.lifecycle = self.selected()

    def selected(self, *, frames=None, region=1, function=61, selector=0x11):
        return selected_fast_kwp_lifecycle(self.index, self.frames if frames is None else frames,
                                          region=region, function=function, selector=selector)

    def test_exact_selected_function_frame_bindings_and_original_provenance(self):
        r = self.lifecycle.report()
        self.assertEqual('source-fast-kwp-lifecycle', r['kind'])
        self.assertEqual((1, 61), (r['region'], r['function']))
        self.assertEqual((104, '81', ''), (r['initial']['template'], r['initial']['requestHex'], r['initial']['responseHex']))
        self.assertEqual((102, '3e', ''), (r['keepalive']['template'], r['keepalive']['requestHex'], r['keepalive']['responseHex']))
        self.assertEqual((127, '3e', '7e'), (r['exit']['template'], r['exit']['requestHex'], r['exit']['responseHex']))
        self.assertFalse(r['requiresIsoFallbackPort'])
        self.assertTrue(self.selected(selector=0x51).report()['requiresIsoFallbackPort'])
        self.assertTrue(any(x.startswith('EU/Toyota_bin_00.BIN@') for x in r['sourceRecords']))
        self.assertIn('libTOYOTA_SYSP.so@000109b8#fast-entry', r['sourceRecords'])
        self.assertTrue(all(key not in r for key in ('enabled', 'verified', 'link', 'ecuProof', 'p3MaxMs')))
        with self.assertRaises(FrozenInstanceError): self.lifecycle.function = 62

    def test_startup_trace_retains_source_pulse_arguments_address_and_one_explicit_pin(self):
        actual = self.lifecycle.fast_success_calls(connector=16, pin_pair_index=0)
        self.assertEqual(self.lifecycle.entry.configuration_calls(connector=16, pin_pair_index=0), actual[:9])
        self.assertEqual((ConsumerCall('BeginCombination', ()),
            ConsumerCall('SetCommunicationLineVoltage', (2, 25, 25)),
            ConsumerCall('CommSubFunc_00000003', (255, 255, 1, '8113f08105')),
            ConsumerCall('EndCombination', ())), actual[9:])
        other = replace(self.lifecycle, entry=replace(self.lifecycle.entry, timing=bytes.fromhex('053c00230f')))
        self.assertEqual((2, 35, 15), other.fast_success_calls(connector=16, pin_pair_index=0)[10].arguments)
        self.assertEqual('8110f08102', self.selected(selector=0x51).fast_success_calls(connector=16, pin_pair_index=0)[11].arguments[-1])
        with self.assertRaises(ValueError): self.lifecycle.fast_success_calls(connector=16, pin_pair_index=1)

    def test_keepalive_uses_exact_binding_not_universal_tester_present_or_app_default_period(self):
        self.assertEqual((ConsumerCall('SetLinkKeep', ('8113f03ec2', 5, 3, 800)),),
                         self.lifecycle.keepalive_calls(enabled=True))
        for request in (b'\x21\x00', b'\x01\x00', b'\x3e\x01', b'\x21\xe1', b'\x21\x02'):
            item = self.selected(frames=SourceFrames(frame_sources(keepalive=request), 'EU'))
            wire = item.entry.packet(request)
            self.assertEqual((ConsumerCall('SetLinkKeep', (wire.hex(), len(wire), 3, 800)),), item.keepalive_calls(enabled=True))
            self.assertEqual(request.hex(), item.report()['keepalive']['requestHex'])
        self.assertEqual((ConsumerCall('SetLinkKeep', ('', 0, 0, 0)),), self.lifecycle.keepalive_calls(enabled=False))
        for value in (None, 0, 1, 'true'):
            with self.subTest(value=value), self.assertRaises(ValueError): self.lifecycle.keepalive_calls(enabled=value)

    def test_exit_retains_reply_metadata_but_does_not_invent_a_reply_enforcement_step(self):
        item = self.selected(frames=SourceFrames(frame_sources(exit=b'\x82', exit_reply=b'\xc2'), 'EU'))
        wire = item.entry.packet(b'\x82')
        self.assertEqual((ConsumerCall('KwpSendDataToEcuGetAnswer', (255, 255, 1, wire.hex())),
                          ConsumerCall('usleep', (500,))), item.exit_calls())
        self.assertEqual('c2', item.report()['exit']['responseHex'])
        self.assertEqual((ConsumerCall('KwpSendDataToEcuGetAnswer', (255, 255, 1, '8113f03ec2')),
                          ConsumerCall('usleep', (500,))), self.lifecycle.exit_calls())

    def test_missing_conditional_or_ambiguous_templates_are_not_borrowed_or_shortened(self):
        for template in (104, 102, 127):
            for condition, duplicate in ((1, False), (0, True)):
                sources = frame_sources(); rows = []
                for t, f in ((104, 1), (102, 2), (127, 3)):
                    row = struct.pack('<8H', 61, t, 1, f, condition if t == template else 0, 0, 1, 0)
                    rows.extend([row] * (2 if duplicate and t == template else 1))
                sources['EU/Toyota_bin_12.BIN'] = typed(274, rows)
                with self.subTest(template=template, condition=condition, duplicate=duplicate), self.assertRaisesRegex(ValueError, 'unique exact'):
                    self.selected(frames=SourceFrames(sources, 'EU'))
        for change in ({'function': 62}, {'region': 2}, {'selector': 1}, {'selector': 0x12}, {'selector': True}):
            with self.subTest(change=change), self.assertRaises(ValueError): self.selected(**change)
        for frames in (None, object()):
            with self.assertRaises(ValueError):
                selected_fast_kwp_lifecycle(self.index, frames, region=1, function=61, selector=17)

    def test_lower_word_frame_key_is_not_a_lower_word_fallback_in_entry_selection(self):
        data = bytearray(document()); struct.pack_into('<I', data, 8, 0x1003d)
        self.index = EntryIndex(bytes(data))
        item = self.selected(function=0x1003d)
        self.assertEqual(0x1003d, item.report()['function'])
        self.assertEqual(self.lifecycle.initial, item.initial)
        with self.assertRaises(ValueError): self.selected(function=61)

    def test_immutable_fields_capacity_region_and_single_template_shape_are_required(self):
        item = self.lifecycle
        for field in ('initial', 'keepalive', 'exit'):
            frame = getattr(item, field)
            for bad in (None, replace(frame, request=bytearray(b'\x81')),
                        replace(frame, source_records=['EU/file@00000000']),
                        replace(frame, source_records=('NA/file@00000000',)),
                        replace(frame, frame_id=True)):
                with self.subTest(field=field, bad=bad), self.assertRaises(ValueError): replace(item, **{field: bad})
            capacity = 20 if field == 'keepalive' else 50
            with self.assertRaisesRegex(ValueError, 'capacity'):
                replace(item, **{field: replace(frame, request=bytes(capacity - 3))})
        for change in ({'region': True}, {'region': 4}, {'function': False}, {'function': -1}, {'entry': None}):
            with self.subTest(change=change), self.assertRaises(ValueError): replace(item, **change)
        for field in ('initial_templates', 'keepalive_templates', 'exit_templates'):
            for templates in ((), (1, 2)):
                with self.subTest(field=field, templates=templates), self.assertRaises(ValueError):
                    replace(item, entry=replace(item.entry, **{field: templates}))
        with self.assertRaises(ValueError): replace(item, entry=replace(item.entry, timing=bytes.fromhex('053c000019')))

    def test_native_oracle_checks_all_library_identities_before_mapping_any_library(self):
        from toyota_native_oracle import compare_fast_kwp_lifecycle
        with mock.patch('toyota_native_oracle.NativeSandbox') as sandbox:
            for libraries in ({}, {'libTOYOTA_FILE.so': b'not the source'}, {'libTOYOTA_FILE.so': None}):
                with self.assertRaisesRegex(ValueError, 'exact supplied library'): compare_fast_kwp_lifecycle(libraries, {})
            sandbox.assert_not_called()


if __name__ == '__main__': unittest.main()
