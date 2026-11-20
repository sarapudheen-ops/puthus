"""Timing-record boundaries and saved experiment integrity, NOT adapter proofs.

The independently runnable CLI executes the actual pinned ELF and Cortex-M
image. These unit tests need neither proprietary binaries nor a public download;
parsing a saved native result is deliberately not counted as guest execution.
"""
import copy
import hashlib
import json
from pathlib import Path
import unittest

from native_replay import ReplayError
from toyota_firmware_timing import (ABSTRACT_SHA256, AbstractCapture, FIRMWARE_BYTES,
                                   FIRMWARE_SHA256, IntervalRecord, MiniTimingSandbox,
                                   NativeFirmwareExchange, checked_bytes, float32, integral)
from vehicle_bundle import canonical

PIN = Path(__file__).parent / 'verification' / 'mini-firmware-timing.json'
RESULT = 'c8780bc9d1092560c6b4cb77cb0516c7bcaa80150b58fbf27f8b4f60dbcec646'
REPORT_SHA256 = '824e4cb8eb7cd56b5a900e0ef792e91f4256e79cac500acdbc0fa1eb22042e48'


def result_digest(report):
    return hashlib.sha256(canonical([report['intervalVectors'], report['normalUartTimer'],
        report['exchangeVectors'], report['keepaliveVectors'], report['source10416RecordHex'],
        report['nativeCombinedResults'], report['nativePrimaryResults'], report['nativeUnequalPulseComponent']])).hexdigest()


class FirmwareTimingRecordTest(unittest.TestCase):
    def test_exact_source_intervals_and_literal_unequal_fields(self):
        for args, raw, fields in [((5., 60, 600), '6003003206003c', (60, 5000, 600, 5)),
                                  ((5., 60, 500), '60030032060032', (60, 5000, 500, 5)),
                                  ((1.25, 65, 605), '6003000d07003d', (70, 1300, 610, 1)),
                                  ((19.95, 123, 4567), '600300c80c01c9', (120, 20000, 4570, 20))]:
            with self.subTest(args=args):
                actual, record = IntervalRecord.from_arguments(*args)
                self.assertEqual(bytes.fromhex(raw), actual)
                self.assertEqual(IntervalRecord(*fields), record)
                self.assertEqual(record, IntervalRecord.decode(actual))

    def test_every_record_byte_affects_its_correct_field_not_a_generic_p2star(self):
        base = bytearray.fromhex('6003003206003c')
        changes = [(2, 1, IntervalRecord(60, 30600, 600, 31)),
                   (3, 51, IntervalRecord(60, 5100, 600, 5)),
                   (4, 7, IntervalRecord(70, 5000, 600, 5)),
                   (5, 1, IntervalRecord(60, 5000, 3160, 5)),
                   (6, 61, IntervalRecord(60, 5000, 610, 5))]
        for offset, byte, expected in changes:
            data = base.copy(); data[offset] = byte
            with self.subTest(offset=offset): self.assertEqual(expected, IntervalRecord.decode(bytes(data)))

    def test_float32_multiply_then_double_half_and_truncation(self):
        # The supplied AArch64 function FMULs in S0, widens to D0, adds
        # double 0.5 and FCVTZS. Python double-only multiplication is not ABI equivalence.
        self.assertNotEqual(.1, float32(.1))
        for value, tx in [(0, 0), (0.049999997, 0), (.05, 100), (1.2499999, 1200), (1.25, 1300)]:
            with self.subTest(value=value):
                self.assertEqual(tx, IntervalRecord.from_arguments(value, 60, 600)[1].tx_gap_us)

    def test_source_timer_quantum_is_a_byte_not_a_clamped_full_integer(self):
        for value, tick in [(0, 1), (255, 255), (256, 1), (257, 1), (258, 2), (6553.4, 153)]:
            with self.subTest(value=value):
                self.assertEqual(tick, IntervalRecord.from_arguments(value, 60, 600)[1].keepalive_tick_ms)

    def test_no_truncation_ignored_tail_foreign_opcode_or_zero_wait(self):
        raw = bytes.fromhex('6003003206003c')
        bad = [None, '', raw.hex(), bytearray(raw), raw + b'\0', b'\0' + raw,
               b'\x60\x04' + raw[2:], bytes.fromhex('6003003200003c'), bytes.fromhex('60030032060000')]
        bad += [raw[:n] for n in range(len(raw))]
        for data in bad:
            with self.subTest(data=data), self.assertRaises(ValueError): IntervalRecord.decode(data)

    def test_no_nonfinite_boolean_fractional_integer_clamping_or_wrap(self):
        for tx in (None, True, '5', float('nan'), float('inf'), float('-inf'), -1, 1e39, 6554):
            with self.subTest(tx=tx), self.assertRaises(ValueError): IntervalRecord.from_arguments(tx, 60, 600)
        for index, values in [(1, (True, None, 60., '60', -1, 0, 4, 2546)),
                              (2, (True, None, 600., '600', -1, 0, 4, 655346))]:
            for value in values:
                args = [5., 60, 600]; args[index] = value
                with self.subTest(index=index, value=value), self.assertRaises(ValueError): IntervalRecord.from_arguments(*args)
        for n in (True, 1., None, -1, 4):
            with self.assertRaises(ValueError): integral(n, 5, 10)

    def test_unpinned_binaries_fail_before_any_guest_execution(self):
        for data in (b'', bytes(128), bytes(FIRMWARE_BYTES - 1), bytes(FIRMWARE_BYTES), bytes(FIRMWARE_BYTES + 1)):
            with self.subTest(size=len(data)), self.assertRaises(ReplayError): MiniTimingSandbox(data)
        for data in (None, b'', bytes(100), bytes.fromhex('7f454c46')):
            with self.subTest(data=data), self.assertRaises(ReplayError): AbstractCapture(data)
            with self.subTest(data=data), self.assertRaises(ReplayError): NativeFirmwareExchange(data, b'')
        for data, size, digest in [(b'x', 1, '0' * 64), (b'x', 2, hashlib.sha256(b'x').hexdigest()),
                                   (bytearray(b'x'), 1, hashlib.sha256(b'x').hexdigest())]:
            with self.assertRaises(ReplayError): checked_bytes(data, size, digest, 'test image')
        self.assertEqual(b'x', checked_bytes(b'x', 1, hashlib.sha256(b'x').hexdigest(), 'unit identity'))

    def test_saved_comparison_is_pinned_bounded_and_not_hardware_or_production_approval(self):
        raw = PIN.read_bytes(); report = json.loads(raw)
        self.assertEqual(REPORT_SHA256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(canonical(report) + b'\n', raw)
        self.assertEqual((ABSTRACT_SHA256, FIRMWARE_SHA256, FIRMWARE_BYTES), tuple(report[k] for k in
            ('nativeLibrarySha256', 'publicFirmwareSha256', 'publicFirmwareBytes')))
        self.assertEqual('offline-native-firmware-timing-comparison', report['kind'])
        self.assertEqual(RESULT, report['resultSha256']); self.assertEqual(RESULT, result_digest(report))
        self.assertIs(False, report['physicalValidation']); self.assertIs(False, report['provesMic3313Equivalence'])
        self.assertEqual(0, report['productionExecutableDefinitions']); self.assertEqual(3, len(report['fakeBoundaries']))
        self.assertEqual(8, len(report['intervalVectors'])); self.assertEqual(3, len(report['exchangeVectors']))
        self.assertEqual('6002000028b000', report['source10416RecordHex'])
        self.assertEqual(1000, report['normalUartTimer']['periodUsAtDeclaredClock'])
        actual = {row['case']: row for row in report['nativeCombinedResults']}
        self.assertEqual({'completeC1', 'noData', 'corruptChecksum', 'partialReply', 'wrongEcu', 'negativeService'}, set(actual))
        self.assertEqual(9, actual['completeC1']['endSignedLowWord'])
        self.assertEqual(-70, actual['noData']['endSignedLowWord'])
        self.assertEqual('ff02', actual['noData']['rpcCalls'][-1]['firmwareResultHex'])
        # Actual native returns remain positive for unsafe bytes. Only the
        # separate app frame/address/checksum validation may accept ECU proof.
        for name in ('corruptChecksum', 'partialReply', 'wrongEcu', 'negativeService'):
            self.assertGreater(actual[name]['endSignedLowWord'], 0)
        for row in actual.values():
            self.assertEqual(0, row['queuedWord']); self.assertEqual(2, len(row['rpcCalls']))
            self.assertEqual('ff00', row['rpcCalls'][0]['firmwareResultHex'])
            self.assertEqual('6109ff01058113f08105', row['rpcCalls'][1]['commandHex'])
        self.assertEqual({'3e', '3e01', '2100', '0100', '2102', '21e1'},
                         {r['packetHex'][6:-2] for r in report['keepaliveVectors']})
        for row in report['keepaliveVectors']:
            self.assertEqual((800, 5, 161, 805, 3), tuple(row[k] for k in
                ('intervalMs', 'callbackPeriodMsAtDeclaredClock', 'firstTransmitTick', 'firstTransmitMsAtDeclaredClock', 'receiveDrainBytes')))
            self.assertEqual([1000] * 3, [event['ticks'] for event in row['events'] if event['boundary'] == 'UART_RX'])
        for mutate in [lambda r: r['intervalVectors'][0].update(txGapUs=5),
                       lambda r: r['keepaliveVectors'][0].update(firstTransmitMsAtDeclaredClock=800),
                       lambda r: r['nativePrimaryResults'][0]['rpcCalls'][-1].update(commandHex='6109ff01058113f08105'),
                       lambda r: r.update(source10416RecordHex='6002ffc800')]:
            altered = copy.deepcopy(report); mutate(altered)
            self.assertNotEqual(RESULT, result_digest(altered))

    def test_actual_primary_alias_queued_pulses_and_literal_baud_are_separate_from_hardware_approval(self):
        report = json.loads(PIN.read_bytes())
        self.assertEqual(('CommSubFunc_00000003', 'CommSubFunc0003', '6402', '6109'),
                         tuple(report['primaryLinkage'][k] for k in ('pointerCell', 'abstractExport', 'serializedOpcodeHex', 'firmwareAliasTargetHex')))
        self.assertEqual(6, len(report['nativePrimaryResults']))
        for index, row in enumerate(report['nativePrimaryResults']):
            self.assertEqual(row['outputHex'], report['nativeCombinedResults'][index]['outputHex'])
            self.assertEqual(row['endSignedLowWord'], report['nativeCombinedResults'][index]['endSignedLowWord'])
        for row, pulses in [(r, [25000, 25000]) for r in report['nativePrimaryResults']] + [(report['nativeUnequalPulseComponent'], [35000, 15000])]:
            calls = row['rpcCalls']
            self.assertEqual(3, len(calls)); self.assertEqual(0, row['queuedWord'])
            self.assertEqual('6002000028b000', calls[0]['commandHex'])
            self.assertEqual('ff00', calls[0]['firmwareResultHex'])
            self.assertEqual('6003003206003c', calls[1]['commandHex'])
            expected = '600700' + (pulses[0] // 1000).to_bytes(2, 'big').hex()
            expected += '6007ff' + (pulses[1] // 1000).to_bytes(2, 'big').hex() + '6402ff01058113f08105'
            self.assertEqual(expected, calls[2]['commandHex'])
            events = calls[2]['firmwareTrace']
            self.assertEqual(pulses, [e['value'] for e in events if e['boundary'] == 'SysTick_DelayArmUs'])
            self.assertEqual([1, 0, 1, 1], [e['bit'] for e in events if e['boundary'] == 'GPIO_BitWrite'])
            self.assertEqual([[10416, 0, 0, 0]], [e['settingsWords'] for e in events if e['boundary'] == 'UART_Apply'])
            self.assertEqual([5000] * 4, [e['value'] for e in events if e['boundary'] == 'delayUs'])
            self.assertEqual([5000] * 3, [c['nativeHostBudgetMs'] for c in calls])
        # The unequal pulse vector holds the other settings fixed to isolate
        # this component; it is not asserted to be a real 9600-baud entry.
        self.assertIs(False, report['provesMic3313Equivalence'])


if __name__ == '__main__': unittest.main()
