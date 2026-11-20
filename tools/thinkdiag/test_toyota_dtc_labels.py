"""Synthetic exact-key/source-isolation/display tests, with no ECU or adapter."""
from dataclasses import replace
import unittest

from test_toyota_functions import fault_fixture
from toyota_functions import FaultMapping
from toyota_dtc_labels import (DiagnosticCodeMerge, FaultLabels, MergedCode, compile_p3p4_layout,
                               fallback_code, fallback_description)


class FaultLabelsTest(unittest.TestCase):
    def test_exact_whole_keys_and_selected_source_mappings_not_printed_suffixes(self):
        table = FaultLabels({'00000420': 'P0420\0First exact source wording',
                             '00010420': 'P0420\0Different exact source wording',
                             '0000C123': 'U0123\0Two-byte form',
                             '00C12300': 'U0123\0Three-byte form'})
        block = replace(fault_fixture(), mappings=(FaultMapping(30, 0x10420, 0, 1), FaultMapping(36, 0xc12300, 0, 1),
            FaultMapping(42, 0xdead0420, 0, 0), FaultMapping(48, 0x10420, 2, 1)))
        labels, missing = table.for_block(block, 'FUNCDATA_EU.BIN')
        self.assertEqual(['00010420', '00C12300'], [l['nativeCode'] for l in labels])
        self.assertEqual('Different exact source wording', labels[0]['description'])
        self.assertEqual(['TOYOTA_EN.GGP/TCODE_EN.GAG#id00010420',
            'FUNCDATA_EU.BIN@0000001e#dtc-map', 'FUNCDATA_EU.BIN@00000030#dtc-map'], labels[0]['sourceRecords'])
        self.assertEqual(['DEAD0420'], [m['nativeCode'] for m in missing])
        other = replace(block, mappings=(FaultMapping(77, 0x420, 0, 1),))
        other_labels, _ = table.for_block(other, 'FUNCDATA_JP.BIN')
        self.assertEqual('First exact source wording', other_labels[0]['description'])
        self.assertNotIn('FUNCDATA_EU.BIN', str(other_labels))

    def test_legacy_codes_empty_descriptions_and_exact_spelling_are_not_passive_reference_filters(self):
        table = FaultLabels({'00000000': '12\0', '00000001': 'P0001\0  Exact  spacing; °C\nsecond line'})
        self.assertEqual('12', table.labels[0].code)
        self.assertEqual('', table.labels[0].description)
        self.assertEqual('  Exact  spacing; °C\nsecond line', table.labels[1].description)
        for records in ({'01': 'P0001\0x'}, {'0000000a': 'P000A\0x'}, {'00000001': 'no separator'},
                {'00000001': '\0x'}, {'00000001': 'bad\ncode\0x'}, {'00000001': 'P0001\0a\0b'},
                {'00000001': 1}, {'00000001': 'P0001\0' + 'a' * 4097}):
            with self.subTest(records=str(records)[:100]), self.assertRaises(ValueError): FaultLabels(records)

    def test_indexed_gag_locations_are_explicit_and_complete_or_rejected(self):
        table = FaultLabels({'12345678': '17\0Source'}, {'12345678': {'indexOffset': 321, 'textOffset': 1000, 'encodedBytes': 12}})
        self.assertIn('TOYOTA_EN.GGP/TCODE_EN.GAG@00000141#id12345678', table.labels[0x12345678].source_records)
        for location in ({'other': {}}, {'12345678': {'indexOffset': True, 'textOffset': 2, 'encodedBytes': 4}},
                         {'12345678': {'indexOffset': 3, 'textOffset': -2, 'encodedBytes': 4}}):
            with self.assertRaises(ValueError): FaultLabels({'12345678': '17\0Source'}, location)
        with self.assertRaises(ValueError): table.for_block(fault_fixture(), 'other/FUNCDATA_EU.BIN')

    def test_app_layout_compilation_needs_exact_context_and_the_actual_decoding_caller(self):
        block = replace(fault_fixture(), mappings=(FaultMapping(30, 0x420, 0, 1),))
        table = FaultLabels({'00000420': 'P0420\0Synthetic selected-system wording'})
        args = (block, block.queries[1], 'FUNCDATA_EU.BIN', table, {'FFFF0048': 'Source manual'})
        context = {'mode_lowbits': 0, 'availability_mask': 0, 'context_source_records': ['synthetic:caller-mode-and-mask']}
        result = compile_p3p4_layout(*args, **context)
        self.assertEqual('TOYOTA_P3P4', result['kind'])
        self.assertEqual((0, 1, 2, 2, 1, 0), tuple(result[k] for k in ('countIndex', 'recordStart', 'recordBytes', 'assemblyKind', 'queryKind', 'availabilityMask')))
        self.assertEqual('00000420', result['labels'][0]['nativeCode'])
        self.assertEqual('Source manual', result['sourceFallback']['missingDescription'])
        self.assertIn('synthetic:caller-mode-and-mask', result['sourceRecords'])
        for changed in ({'mode_lowbits': 8}, {'mode_lowbits': True}, {'availability_mask': -1}, {'context_source_records': []}):
            with self.assertRaises(ValueError): compile_p3p4_layout(*args, **(context | changed))
        for changed_block, query in ((replace(block, byte1=3), block.queries[1]),
                (block, replace(block.queries[1], kind=5)), (block, replace(block.queries[1], template=999))):
            with self.assertRaises(ValueError):
                compile_p3p4_layout(changed_block, query, *args[2:], **context)
        with self.assertRaisesRegex(ValueError, 'missing exact'):
            compile_p3p4_layout(block, block.queries[1], args[2], FaultLabels({}), args[4], **context)

    def test_source_numeric_fallback_is_width_specific_not_generic_key_normalization(self):
        for assembly in (1, 2, 3, 5, 7):
            self.assertEqual('P0420', fallback_code(0xdead0420, assembly))
            self.assertEqual('U3FFF', fallback_code(0xffffffff, assembly))
        for assembly in (4, 6):
            self.assertEqual('B2FFFFF', fallback_code(0xffafffff, assembly))
            self.assertEqual('U3FFFFF', fallback_code(0xffffffff, assembly))
        self.assertEqual('[00, ad, be, ef]-Please refer to the source manual.',
            fallback_description(0xdeadbeef, 'Please refer to the source manual.'))
        for code in (-1, 0x100000000, True, 1.0):
            with self.assertRaises(ValueError): fallback_code(code, 2)
            with self.assertRaises(ValueError): fallback_description(code, 'Source')
        for kind in (0, 8, True):
            with self.assertRaises(ValueError): fallback_code(0x420, kind)


class DiagnosticCodeMergeTest(unittest.TestCase):
    def test_zero_arguments_do_not_append_but_existing_keys_or_bytes_in_place(self):
        merge = DiagnosticCodeMerge()
        self.assertFalse(merge.merge(0x12345678, 0, 1))
        self.assertFalse(merge.merge(0x12345678, 1, 0))
        self.assertTrue(merge.merge(0x12345678, 1, 2))
        self.assertTrue(merge.merge(0x12345678, 4, 0))
        self.assertTrue(merge.merge(0x12345678, 0, 8))
        self.assertTrue(merge.merge(0x00345678, 128, 64))
        self.assertEqual([MergedCode(0x12345678, 10, 5), MergedCode(0x00345678, 64, 128)], merge.rows)
        for args in ((-1, 1, 1), (0, 256, 1), (0, 1, True)):
            with self.assertRaises(ValueError): merge.merge(*args)

    def test_distinct_code_limit_fails_atomically_instead_of_native_count_wrap(self):
        merge = DiagnosticCodeMerge()
        for i in range(255): self.assertTrue(merge.merge(i, 1, 1))
        before = list(merge.rows)
        with self.assertRaisesRegex(ValueError, 'wrap'): merge.merge(256, 1, 1)
        self.assertEqual(before, merge.rows)
        self.assertTrue(merge.merge(254, 4, 8))
        self.assertEqual(MergedCode(254, 9, 5), merge.rows[-1])


if __name__ == '__main__': unittest.main()
