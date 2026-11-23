"""Stage 1b parameterized DTC probe unit tests (no corpus, no vehicle data).

These tests exercise the fail-closed, read-only contracts only: a missing corpus
path is a hard corpus error (never a per-system reject), the parameterized
compile reuses the existing source compiler, and no oracle claim is made.
"""
import gzip
import json
import zipfile
import tempfile
import unittest
from pathlib import Path

from parameterized_dtc_probe import (
    CorpusError, VerifiedSources, fail_fast, oracle_status, stream_eligible)
from test_toyota_runtime_compile import runtime_fixture
from toyota_category_translation_v2 import NO_ORACLE, parameterized_dtc_group


def corpus_arguments(root, *, systems_rows=2):
    """Build the argument surface with placeholder files, then let a test break one path."""
    decoded = root / 'decoded'
    english = root / 'english'
    review = root / 'review'
    report = root / 'report'
    for directory in (decoded, english, review, report):
        directory.mkdir()
    for name in ('SYSTEMENTER.BIN', 'FUNCDATA_EU.BIN', 'SYSLIST_EU.BIN', 'ECUPROGRAM.BIN',
                 'EU/Toyota_bin_00.BIN', 'EU/Toyota_bin_11.BIN', 'EU/Toyota_bin_12.BIN',
                 'EU/Toyota_bin_1B.BIN'):
        (decoded / name).parent.mkdir(parents=True, exist_ok=True)
        (decoded / name).write_bytes(b'\x01')
    for name in ('TCODE_EN.GAG', 'TEXT_EN.GAG'):
        (english / (name + '.json')).write_text(json.dumps({'00000123': 'SYNTHETIC'}))
    for name in ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json'):
        (review / name).write_text('{}')
    manifest = report / 'decoded-manifest.json'
    manifest.write_text(json.dumps([{'file': 'FUNCDATA_EU.BIN', 'bytes': 1, 'sha256': '00'}]))
    archive = root / 'TOYOTA.zip'
    with zipfile.ZipFile(archive, 'w') as bundle:
        bundle.writestr('SYSTEMENTER.BIN', b'\x01')
    systems = root / 'systems.jsonl.gz'
    with gzip.open(systems, 'wb') as stream:
        for index in range(systems_rows):
            stream.write(json.dumps({'id': f'SYSLIST_EU.BIN@{index:08x}', 'region': 'EU',
                                     'functionKey': 61, 'entrySelector': 0x11,
                                     'ecuFile': 'Ecu_Data_0081.BIN', 'nativeTransport': 1,
                                     'linkStatus': 'proven'}).encode() + b'\n')

    class Arguments:
        pass

    arguments = Arguments()
    arguments.archive, arguments.decoded, arguments.manifest = archive, decoded, manifest
    arguments.english, arguments.systems, arguments.review = english, systems, review
    arguments.ecmect_review_dir = review
    arguments.native_oracle = report / 'native-oracle.json'
    arguments.output = report / 'probe.md'
    arguments.json_output = report / 'probe.json'
    arguments.head_sha = None
    arguments.max_seconds = 600.0
    return arguments


class FailFastTest(unittest.TestCase):
    def test_missing_corpus_archive_is_a_corpus_error_not_a_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = corpus_arguments(Path(temporary))
            arguments.archive.unlink()
            with self.assertRaises(CorpusError) as raised:
                fail_fast(arguments)
            self.assertIn('corpus_archive:missing', str(raised.exception))
            self.assertFalse(arguments.output.exists())

    def test_unopenable_system_ledger_is_a_corpus_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = corpus_arguments(Path(temporary))
            arguments.systems.write_bytes(b'not gzip')
            with self.assertRaises(CorpusError) as raised:
                fail_fast(arguments)
            self.assertIn('systems:unopenable', str(raised.exception))

    def test_missing_english_table_is_a_corpus_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = corpus_arguments(Path(temporary))
            (arguments.english / 'TCODE_EN.GAG.json').write_text('[]')
            with self.assertRaises(CorpusError) as raised:
                fail_fast(arguments)
            self.assertIn('english_table:TCODE_EN.GAG:not_a_nonempty_object', str(raised.exception))

    def test_complete_paths_pass_and_record_checks(self):
        with tempfile.TemporaryDirectory() as temporary:
            arguments = corpus_arguments(Path(temporary))
            checks = fail_fast(arguments)
            self.assertTrue(any(check.startswith('corpus_archive=') for check in checks))
            self.assertTrue(any(check.startswith('systems=') for check in checks))


class ParameterizedCompileTest(unittest.TestCase):
    def compile(self, mutate=None):
        sources, libraries, raw = runtime_fixture()
        system = bytearray(raw)
        if mutate is not None:
            mutate(system)
        return parameterized_dtc_group(sources, libraries, region='EU',
                                       system_id='SYSLIST_EU.BIN@00000000',
                                       system_bytes=bytes(system), native_transport=1)

    def test_synthetic_row_compiles_the_existing_source_compiler_output(self):
        result = self.compile()
        self.assertTrue(result['executable'])
        self.assertIsNone(result['rejectReason'])
        self.assertEqual(1, result['dtcCount'])
        self.assertEqual('13B0', result['readRequest'])
        self.assertEqual('04', result['clearRequest'])
        self.assertEqual(1, result['identificationCount'])
        self.assertEqual(NO_ORACLE, result['oracleStatus'])

    def test_caller_mode_seven_is_a_literal_reject_with_no_definitions(self):
        def mutate(system):
            system[33] = 7
        result = self.compile(mutate)
        self.assertFalse(result['executable'])
        self.assertTrue(result['rejectReason'].startswith('ValueError:Native caller mode'))
        self.assertIsNone(result['definition'])
        self.assertEqual(0, result['dtcCount'])

    def test_short_system_row_is_a_literal_reject(self):
        result = parameterized_dtc_group({}, {}, region='EU', system_id='SYSLIST_EU.BIN@00000000',
                                         system_bytes=b'\x00' * 8, native_transport=1)
        self.assertFalse(result['executable'])
        self.assertTrue(result['rejectReason'].startswith('ValueError:'))
        self.assertEqual(NO_ORACLE, result['oracleStatus'])


class LedgerAndSourceTest(unittest.TestCase):
    def test_stream_eligible_keeps_only_the_promotion_partition(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'systems.jsonl.gz'
            rows = [{'id': 'a', 'nativeTransport': 1, 'linkStatus': 'proven'},
                    {'id': 'b', 'nativeTransport': 5, 'linkStatus': 'unproven'},
                    {'id': 'c', 'nativeTransport': 2, 'linkStatus': 'proven'},
                    {'id': 'd', 'nativeTransport': 1, 'linkStatus': 'absent'}]
            with gzip.open(path, 'wb') as stream:
                for row in rows:
                    stream.write(json.dumps(row).encode() + b'\n')
            total, eligible = stream_eligible(path)
            self.assertEqual(4, total)
            self.assertEqual(['a', 'b'], [row['id'] for row in eligible])

    def test_verified_sources_reject_a_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            decoded = Path(temporary)
            (decoded / 'FUNCDATA_EU.BIN').write_bytes(b'\x01')
            sources = VerifiedSources(decoded, [{'file': 'FUNCDATA_EU.BIN', 'bytes': 1,
                                                 'sha256': '00'}])
            with self.assertRaises(ValueError):
                sources['FUNCDATA_EU.BIN']
            with self.assertRaises(KeyError):
                sources['unlisted.BIN']


class OracleStatusTest(unittest.TestCase):
    def test_absent_oracle_file_reports_not_established(self):
        with tempfile.TemporaryDirectory() as temporary:
            status = oracle_status(Path(temporary) / 'missing.json')
            self.assertFalse(status['available'])
            self.assertFalse(status['groupKeyed'])
            self.assertEqual('not_established:native_oracle_absent', status['reason'])

    def test_decoder_comparisons_are_reported_but_never_group_keyed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'native-oracle.json'
            path.write_text(json.dumps({'faultDecoderComparison': {
                'nativeInstructionsExecuted': True, 'stateVectors': 5, 'recordVectors': 7},
                'p5DecoderComparison': {'recordVectors': 9, 'sourceBlocks': 3}}))
            status = oracle_status(path)
            self.assertTrue(status['available'])
            self.assertFalse(status['groupKeyed'])
            self.assertEqual(5, status['decoderComparisons']['faultDecoderComparison']['stateVectors'])
            self.assertEqual(NO_ORACLE, status['reason'])


if __name__ == '__main__':
    unittest.main()
