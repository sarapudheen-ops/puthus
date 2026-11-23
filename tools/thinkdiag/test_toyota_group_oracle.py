"""Group-keyed DTC oracle tests (no corpus, no native library).

The native sandbox is replaced by a fake that implements the two compared DTCF
consumers with the same Python ports, so the comparison pipeline, its vector
shapes and its fail-closed behaviour are exercised without TOYOTA.zip.
"""
import struct
import unittest

from toyota_faults import NativeFaultLayout
from toyota_group_oracle import (DECODABLE_KINDS, build_payload, compare_display, compare_layout,
                                 compare_linkage, encode_row, layout_cases, layout_from_compiled,
                                 oracle_for_group)


class FakeNative:
    """Minimal stand-in for native_replay.NativeSandbox covering the two DTCF consumers."""

    def __init__(self, codes=None, texts=None, corrupt_decoder=False, corrupt_display=False):
        self.blocks = []
        self.next = 0x10000
        self.codes = codes or {}
        self.texts = texts or {}
        self.corrupt_decoder = corrupt_decoder
        self.corrupt_display = corrupt_display
        self.calls = []
        self.libraries = {'libTOYOTA_DTCF.so': type('Library', (), {'sha256': 'fake'})()}

    def allocate(self, size, data=b''):
        block = bytearray(size)
        if data:
            block[:len(data)] = data
        self.blocks.append((self.next, block))
        at = self.next
        self.next += (max(size, 1) + 15) & -16
        return at

    def _locate(self, address):
        for base, block in self.blocks:
            if base <= address < base + len(block):
                return block, address - base
        raise AssertionError(f'unmapped fake address {address:#x}')

    def write(self, address, data):
        block, offset = self._locate(address)
        block[offset:offset + len(data)] = bytes(data)

    def read(self, address, size):
        block, offset = self._locate(address)
        return bytes(block[offset:offset + size])

    def string(self, address, limit=16384):
        out = bytearray()
        for index in range(limit):
            value = self.read(address + index, 1)[0]
            if value == 0:
                return bytes(out)
            out.append(value)
        raise AssertionError('unterminated fake string')

    def bind(self, *args, **kwargs):
        return None

    def call(self, library, function, *args):
        self.calls.append((function, args))
        if function == 'GetDTCCodeFromAnsP3P4':
            return self._decode(*args)
        if function == 'GetShowDtcInfo':
            return self._display(*args)
        raise AssertionError(f'unexpected fake consumer {function}')

    def _decode(self, size, data, packed, unused, out, kind, mask, context):
        payload = self.read(data, size)
        fields = packed.to_bytes(4, 'little')
        source_block = struct.unpack('<Q', self.read(context + 0x1b0, 8))[0]
        presence = self.read(source_block + 0x1c, 1)[0] == 2
        mode = self.read(context + 0x18, 1)[0]
        layout = NativeFaultLayout(fields[0], fields[1], fields[2], fields[3], presence, mode)
        pairs = list(layout.decode(payload, kind, mask))
        if self.corrupt_decoder and pairs:
            pairs[0] = (pairs[0][0], pairs[0][1] ^ 1)
        answer = self.allocate(4 + 8 * len(pairs))
        self.write(answer, struct.pack('<I', len(pairs))
                   + b''.join(struct.pack('<II', *pair) for pair in pairs))
        return answer

    def _display(self, key, unused, output, description, state, context):
        pair = self.codes.get(f'{key:08X}')
        if pair is None:
            return 0
        code, text = pair.split('\0')
        if self.corrupt_display:
            text = text + ' (corrupted)'
        for address, value in ((output, code), (description, text), (state, self.texts['FFFF0050'])):
            raw = value.encode('utf-8') + b'\0'
            self.write(address, raw)
        return 0


def compiled_group(layout_fields, codes, kinds=(1,)):
    labels = [{'nativeCode': key, 'code': value.split('\0')[0], 'description': value.split('\0')[1],
               'sourceRecords': []} for key, value in sorted(codes.items())]
    return {'layout': {**layout_fields, 'availabilityMask': 0, 'queryKind': kinds[0], 'labels': labels},
            'workflow': {'queries': [{'queryKind': kind} for kind in kinds]},
            'read': {'request': '13B0'}, 'clear': {'request': '04'}}


CODES = {'00000123': 'P0123\0Synthetic exact wording', '00000420': 'P0420\0Other synthetic wording'}
TEXTS = {'FFFF0050': 'Current', 'FFFF0048': 'Unknown'}


class LayoutFromCompiledTest(unittest.TestCase):
    def test_required_fields_are_enforced(self):
        with self.assertRaises(ValueError):
            layout_from_compiled({'countIndex': 1})

    def test_compiled_fields_become_the_native_layout(self):
        layout = layout_from_compiled({'countIndex': 1, 'recordStart': 2, 'recordBytes': 4,
                                       'assemblyKind': 1, 'countIsPresence': False, 'modeLowBits': 2})
        self.assertEqual((1, 2, 4, 1, False, 2), (layout.count_index, layout.record_start,
                                                  layout.record_bytes, layout.assembly_kind,
                                                  layout.count_is_presence, layout.mode_lowbits))


class PayloadTest(unittest.TestCase):
    def test_count_byte_precedes_records(self):
        layout = NativeFaultLayout(0, 1, 4, 1, False, 0)
        payload = build_payload(layout, 1, [b'\x00\x00\x01\x23'])
        self.assertEqual(5, len(payload))
        self.assertEqual(1, payload[0])
        self.assertEqual(b'\x00\x00\x01\x23', payload[1:])

    def test_kind_three_moves_records_to_the_origin_when_mode_is_not_two(self):
        layout = NativeFaultLayout(0, 1, 4, 1, False, 0)
        payload = build_payload(layout, 3, [b'\x00\x00\x01\x23'])
        self.assertEqual(b'\x00\x00\x01\x23', payload)

    def test_count_index_inside_the_record_body_is_refused(self):
        layout = NativeFaultLayout(2, 1, 4, 1, False, 0)
        with self.assertRaises(ValueError):
            build_payload(layout, 1, [b'\x00\x00\x01\x23'])

    def test_code_wider_than_the_record_is_refused(self):
        with self.assertRaises(ValueError):
            encode_row(NativeFaultLayout(255, 1, 3, 1, False, 0), 0x01020304)

    def test_encode_row_round_trips_through_the_port(self):
        layout = NativeFaultLayout(255, 0, 4, 1, False, 0)
        row = encode_row(layout, 0x123)
        decoded = layout.decode(row, 1)
        self.assertEqual([0x123], [code for _, code in decoded])
        self.assertEqual(1, len(decoded))


class LayoutCasesTest(unittest.TestCase):
    def test_only_decodable_kinds_and_expected_masks(self):
        layout = NativeFaultLayout(1, 2, 4, 1, False, 0)
        cases = layout_cases(layout, [1, 10])
        self.assertEqual({1, 10}, {case['kind'] for case in cases})
        self.assertEqual({0xff}, {case['mask'] for case in cases if case['kind'] == 1})
        self.assertIn(0x55, {case['mask'] for case in cases if case['kind'] == 10})
        self.assertNotIn(5, DECODABLE_KINDS)

    def test_presence_layout_adds_the_zero_count_vector(self):
        plain = layout_cases(NativeFaultLayout(1, 2, 4, 1, False, 0), [1])
        presence = layout_cases(NativeFaultLayout(1, 2, 4, 1, True, 0), [1])
        self.assertEqual(len(plain) + len({case['value'] for case in plain}), len(presence))


class CompareLayoutTest(unittest.TestCase):
    def test_matching_decoder_passes(self):
        layout = NativeFaultLayout(1, 2, 4, 1, False, 0)
        result = compare_layout(FakeNative(), layout, [1, 10])
        self.assertTrue(result['passed'])
        self.assertEqual(len(layout_cases(layout, [1, 10])), result['vectors'])
        self.assertEqual([], result['mismatches'])

    def test_corrupted_decoder_fails_with_a_bounded_mismatch_list(self):
        layout = NativeFaultLayout(1, 2, 4, 1, False, 0)
        result = compare_layout(FakeNative(corrupt_decoder=True), layout, [1])
        self.assertFalse(result['passed'])
        self.assertTrue(result['mismatches'])
        self.assertLessEqual(len(result['mismatches']), 5)


class CompareLinkageAndDisplayTest(unittest.TestCase):
    def labels(self):
        return [{'nativeCode': key, 'code': value.split('\0')[0], 'description': value.split('\0')[1]}
                for key, value in sorted(CODES.items())]

    def test_linkage_passes_for_encodable_codes(self):
        layout = NativeFaultLayout(1, 2, 4, 1, False, 0)
        result = compare_linkage(FakeNative(), layout, self.labels(), [1], sample=2)
        self.assertTrue(result['passed'])
        self.assertEqual(2, result['vectors'])
        self.assertEqual(2, result['of'])

    def test_linkage_reports_a_code_that_does_not_fit(self):
        layout = NativeFaultLayout(255, 0, 2, 6, False, 2)
        result = compare_linkage(FakeNative(), layout, self.labels(), [1], sample=2)
        self.assertFalse(result['passed'])
        self.assertTrue(all('does not fit' in failure.get('reason', '') for failure in result['failures']))

    def test_display_matches_compiled_labels(self):
        native = FakeNative(codes=CODES, texts=TEXTS)
        buffers = {'sourceBlock': native.allocate(0x100), 'context': native.allocate(0x400),
                   'output': native.allocate(4096), 'description': native.allocate(4096),
                   'state': native.allocate(4096)}
        result = compare_display(native, buffers, CODES, TEXTS, self.labels())
        self.assertTrue(result['passed'])
        self.assertEqual(2, result['vectors'])

    def test_display_detects_a_changed_description(self):
        native = FakeNative(codes=CODES, texts=TEXTS, corrupt_display=True)
        buffers = {'sourceBlock': native.allocate(0x100), 'context': native.allocate(0x400),
                   'output': native.allocate(4096), 'description': native.allocate(4096),
                   'state': native.allocate(4096)}
        result = compare_display(native, buffers, CODES, TEXTS, self.labels())
        self.assertFalse(result['passed'])
        self.assertTrue(result['failures'])


class Session:
    def __init__(self, native):
        self.codes, self.texts = CODES, TEXTS
        self.native = native

    def ensure(self):
        native = self.native
        buffers = {'sourceBlock': native.allocate(0x100), 'context': native.allocate(0x400),
                   'output': native.allocate(4096), 'description': native.allocate(4096),
                   'state': native.allocate(4096)}
        return native, buffers


class OracleForGroupTest(unittest.TestCase):
    def compiled(self):
        return compiled_group({'countIndex': 1, 'recordStart': 2, 'recordBytes': 4, 'assemblyKind': 1,
                               'countIsPresence': False, 'modeLowBits': 0}, CODES)

    def test_all_three_legs_are_required_for_a_pass(self):
        native = FakeNative(codes=CODES, texts=TEXTS)
        result = oracle_for_group(Session(native), self.compiled(), linkage_sample=2)
        self.assertTrue(result['ran'], result.get('failure'))
        self.assertTrue(result['passed'], result['legs'])
        self.assertEqual({'layout', 'linkage', 'display'}, set(result['legs']))
        self.assertIn('synthesised payload vectors, not ECU replies', result['notCovered'])

    def test_a_failed_leg_fails_the_group(self):
        native = FakeNative(codes=CODES, texts=TEXTS, corrupt_decoder=True)
        result = oracle_for_group(Session(native), self.compiled(), linkage_sample=2)
        self.assertTrue(result['ran'])
        self.assertFalse(result['passed'])

    def test_an_unusable_layout_is_a_failure_not_a_pass(self):
        broken = compiled_group({'countIndex': 1, 'recordStart': 2}, CODES)
        result = oracle_for_group(Session(FakeNative()), broken, linkage_sample=2)
        self.assertFalse(result['ran'])
        self.assertFalse(result['passed'])
        self.assertIn('ValueError:', result['failure'])


if __name__ == '__main__':
    unittest.main()


class CompileLibrariesTest(unittest.TestCase):
    def test_compile_libraries_are_the_english_tables_not_the_native_bytes(self):
        from stage1c_group_oracle import compile_libraries
        tables = compile_libraries(CODES, TEXTS)
        self.assertEqual({'TCODE_EN.GAG', 'TEXT_EN.GAG'}, set(tables))
        self.assertIs(CODES, tables['TCODE_EN.GAG'])
        with self.assertRaises(ValueError):
            compile_libraries(b'not a table', TEXTS)

    def test_wrong_compile_libraries_stay_a_literal_reject(self):
        from toyota_category_translation_v2 import parameterized_dtc_group
        from test_toyota_runtime_compile import runtime_fixture
        sources, libraries, raw = runtime_fixture()
        native_style = {'libTOYOTA_DTCF.so': b'\x7fELF'}
        result = parameterized_dtc_group(sources, native_style, region='EU',
                                        system_id='SYSLIST_EU.BIN@00000000',
                                        system_bytes=raw, native_transport=1)
        self.assertFalse(result['executable'])
        self.assertIn("KeyError:'TCODE_EN.GAG'", result['rejectReason'])


class PublishHelperTest(unittest.TestCase):
    def test_chunks_cover_the_whole_report(self):
        from publish_ci_report import build_comments, chunk_text
        text = 'x' * 40
        self.assertEqual(['x' * 20, 'x' * 20], chunk_text(text, 20))
        comments = build_comments('Stage 1c report', text, 'sha', 'stamp', 'success',
                                 {'elapsedSeconds': 12.5}, limit=20)
        self.assertEqual(2, len(comments))
        self.assertIn('part 1/2', comments[0])
        self.assertIn('Measured duration: 12.5s', comments[0])
        self.assertIn('part 2/2', comments[1])
        self.assertNotIn('Measured duration', comments[1])
        chunks = chunk_text(text, 20)
        self.assertEqual(text, ''.join(chunks))
        for comment, chunk in zip(comments, chunks):
            self.assertTrue(comment.endswith('\n\n' + chunk))

    def test_failure_comment_bounds_the_log(self):
        import tempfile
        from pathlib import Path
        from publish_ci_report import failure_comment
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'step.log'
            log.write_text('\n'.join(f'line {index}' for index in range(500)))
            body = failure_comment('Stage 1c report', str(log), 'sha', 'failure')
            self.assertIn('FAILURE log', body)
            self.assertIn('line 499', body)
            self.assertNotIn('line 0\n', body)
