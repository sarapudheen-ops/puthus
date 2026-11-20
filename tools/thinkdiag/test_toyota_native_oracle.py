"""Native oracle evidence gates and declared boundaries, using synthetic bytes only."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from native_replay import NativeSandbox, ReplayError
from test_native_replay import elf_fixture
from toyota_native_oracle import (MemoryFiles, REFERENCE, load_libraries, verify_reference, write_checked_report)
from vehicle_bundle import SOURCE_SHA256, canonical


class NativeOracleTest(unittest.TestCase):
    def test_reviewed_reference_pins_all_executed_libraries_and_domains(self):
        reference = json.loads(REFERENCE.read_text())
        self.assertEqual(SOURCE_SHA256, reference['sourceSha256'])
        self.assertIs(reference['adapterAccess'], False)
        self.assertIs(reference['physicalValidation'], False)
        self.assertIs(reference['networkAccess'], False)
        self.assertEqual(1308, reference['entryComparison']['comparedRecords'])
        kwp = reference['kwpTransportComparison']
        self.assertEqual(284, kwp['sourceEntries'])
        self.assertEqual(1308, sum(kwp['allEntryCensusByNativeTransport'].values()))
        self.assertEqual((568, 17892, 8, 2016), tuple(kwp[key] for key in (
            'configurationVectors', 'sourcePacketVectors', 'syntheticConfigurationVectors', 'wireContractVectors')))
        self.assertTrue(any('trace-only fake' in boundary for boundary in kwp['fakeBoundaries']))
        wire_kotlin = (Path(__file__).resolve().parents[2] / 'app/src/test/java/com/bod3/app/obd/kwp/KwpScenarios.kt').read_text()
        self.assertIn(kwp['wireContractResultsSha256'], wire_kotlin)
        lifecycle = reference['kwpLifecycleComparison']
        self.assertEqual(284, lifecycle['sourceEntries'])
        self.assertEqual({'fastPositiveEntry': 284, 'keepaliveOn': 284, 'keepaliveOff': 284, 'exit': 284}, lifecycle['vectors'])
        self.assertEqual((284, 284), (sum(lifecycle['keepaliveRequests'].values()), sum(lifecycle['exitRequests'].values())))
        self.assertEqual((174, 41), (lifecycle['keepaliveRequests']['2100'], lifecycle['keepaliveRequests']['3e']))
        self.assertEqual({'1919': 78, '230f': 206}, lifecycle['sourcePulseArguments'])
        self.assertEqual(reference['entryComparison']['librarySha256'], lifecycle['linkedLibrariesSha256']['libTOYOTA_FILE.so'])
        self.assertEqual(reference['frameComparison']['librarySha256'], lifecycle['linkedLibrariesSha256']['libTOYOTA_COMM.so'])
        self.assertEqual(lifecycle['librarySha256'], lifecycle['linkedLibrariesSha256']['libTOYOTA_SYSP.so'])
        self.assertEqual(180316, sum(r['faultCodeMappings'] for r in reference['functionReaderComparison']['regions'].values()))
        self.assertEqual(70144, reference['faultDecoderComparison']['stateVectors'])
        self.assertEqual(2112, reference['faultDecoderComparison']['recordVectors'])
        self.assertEqual(49152, reference['p5DecoderComparison']['recordVectors'])
        self.assertEqual(395, reference['p5DecoderComparison']['sourceBlocks'])
        self.assertEqual(65792, sum(v['vectors'] for v in reference['calcComparison']['vectors']))
        self.assertEqual(8775, sum(r['standaloneFrames'] for r in reference['regionalTemplateComparison']['regions'].values()))
        self.assertEqual(34671, sum(r['templateLookups'] for r in reference['regionalTemplateComparison']['regions'].values()))
        self.assertEqual(27035, reference['faultLabelComparison']['exactLabelVectors'])
        self.assertEqual(3584, reference['faultLabelComparison']['fallbackVectors'])
        self.assertEqual(196608, reference['faultLabelComparison']['mergeVectors'])
        self.assertEqual(0, sum(r['missingExactLabels'] for r in reference['faultLabelComparison']['scopedLabels'].values()))
        for key in ('entryComparison', 'kwpTransportComparison', 'kwpLifecycleComparison', 'frameComparison', 'regionalTemplateComparison', 'faultDecoderComparison', 'p5DecoderComparison', 'faultLabelComparison', 'calcComparison'):
            self.assertRegex(reference[key]['librarySha256'], r'^[0-9a-f]{64}$')
            self.assertIs(reference[key]['nativeInstructionsExecuted'], True)
        self.assertEqual((678, 14, 60, 27648), (reference['ecuProgramComparison']['vectors'], reference['ecuProgramComparison']['ecmect']['dispatch'],
            reference['calibration14Comparison']['vectors'], reference['supportPredicateComparison']['vectors']))
        kotlin = (Path(__file__).resolve().parents[2] / 'app/src/test/java/com/bod3/app/diagnostics/ToyotaFaultScenarios.kt').read_text()
        for key in ('stateResultsSha256', 'recordResultsSha256'):
            self.assertIn(reference['faultDecoderComparison'][key], kotlin)
        for key in ('fallbackResultsSha256', 'mergeResultsSha256'):
            self.assertIn(reference['faultLabelComparison'][key], kotlin)
        self.assertIn(reference['p5DecoderComparison']['recordResultsSha256'], kotlin)

    def test_missing_partial_retyped_and_changed_reports_fail_closed(self):
        expected = {'complete': True, 'adapter': False, 'count': 3, 'hash': 'abc', 'cases': [{'n': 2}]}
        verify_reference({**expected, 'extra_detail': []}, expected)
        for key, value in (('complete', 1), ('adapter', 0), ('adapter', True), ('count', 3.0),
                ('count', 2), ('hash', 'def'), ('cases', []), ('cases', [{'n': 2.0}]), ('cases', [{'n': True}])):
            actual = copy.deepcopy(expected); actual[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): verify_reference(actual, expected)
        for key in expected:
            actual = dict(expected); del actual[key]
            with self.assertRaises(ValueError): verify_reference(actual, expected)
        with self.assertRaises(ValueError): verify_reference({})

    def test_failed_evidence_is_retained_but_cannot_be_reported_or_reused_as_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'native-oracle.json'
            out.with_suffix('.md').write_text('Stale PASS must be replaced')
            invalid = {'kind': 'synthetic-incomplete-comparison'}
            with self.assertRaises(ValueError): write_checked_report(invalid, out)
            self.assertEqual(invalid, json.loads(out.read_text()))
            text = out.with_suffix('.md').read_text()
            self.assertIn('FAIL', text)
            self.assertNotIn('PASS', text)
            self.assertIn('reference', text)
            with self.assertRaises(ValueError): verify_reference(json.loads(out.read_text()))

    def test_frame_detail_hash_and_count_cannot_disagree_with_claimed_evidence(self):
        frames = [{'synthetic': 'frame'}]
        ref = {'frameComparison': {'frameResultsSha256': hashlib.sha256(canonical(frames)).hexdigest(), 'comparedTemplates': 1}}
        actual = copy.deepcopy(ref); actual['frameComparison']['templates'] = frames
        verify_reference(actual, ref)
        actual['frameComparison']['templates'] = []
        with self.assertRaisesRegex(ValueError, 'details'): verify_reference(actual, ref)
        del actual['frameComparison']['templates']
        with self.assertRaisesRegex(ValueError, 'details'): verify_reference(actual, ref)

    def test_native_extraction_is_archive_pinned_complete_unique_and_individually_hashed(self):
        names = ('libTOYOTA_FILE.so', 'libTOYOTA_COMM.so', 'libTOYOTA_DTCF.so', 'libTOYOTA_CALC.so', 'libTOYOTA_SYSP.so', 'libTOYOTA_FDATA.so', 'libTOYOTA_ECUF.so')
        fields = ('entryComparison', 'frameComparison', 'faultDecoderComparison', 'calcComparison', 'kwpLifecycleComparison', 'detailLoadingComparison', 'calibration14Comparison')
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); archive = root / 'synthetic.zip'; ref = root / 'reference.json'
            reference = {field: {'librarySha256': hashlib.sha256(name.encode()).hexdigest()} for name, field in zip(names, fields)}
            ref.write_text(json.dumps(reference))
            def create(items):
                with zipfile.ZipFile(archive, 'w') as target:
                    for name, data in items: target.writestr(name, data)
            create([(name, name.encode()) for name in names])
            with mock.patch('toyota_native_oracle.verify_archive', side_effect=ValueError('source pin')):
                with self.assertRaisesRegex(ValueError, 'source pin'): load_libraries(archive)
            with mock.patch('toyota_native_oracle.verify_archive'), mock.patch('toyota_native_oracle.REFERENCE', ref):
                self.assertEqual({name: name.encode() for name in names}, load_libraries(archive))
                create([(name, name.encode()) for name in names[1:]])
                with self.assertRaisesRegex(ValueError, 'Missing'): load_libraries(archive)
                create([(name, name.encode()) for name in names] + [('other/' + names[0], b'duplicate')])
                with self.assertRaisesRegex(ValueError, 'Ambiguous'): load_libraries(archive)
                create([(name, name.encode() + b'changed') for name in names])
                with self.assertRaisesRegex(ValueError, 'identity'): load_libraries(archive)

    def test_memory_file_boundary_never_sees_a_host_path_or_pads_a_short_read(self):
        native = NativeSandbox({'synthetic.so': elf_fixture()})
        files = MemoryFiles(native); handle = files.register(b'fixture bytes')
        out = native.allocate(64)
        self.assertEqual(3, files.read(out, 2, 3, handle))
        self.assertEqual(b'fixtur', native.read(out, 6))
        self.assertEqual(0, files.seek(handle, 0xffffffff, 1))
        self.assertEqual(2, files.read(out, 1, 2, handle))
        self.assertEqual(b're', native.read(out, 2))
        for offset, whence in ((-20, 0), (1, 2)):
            with self.assertRaises(ReplayError): files.seek(handle, offset, whence)
        self.assertEqual(0, files.seek(handle, 0, 2))
        with self.assertRaises(ReplayError): files.read(out, 1, 1, handle)
        self.assertEqual(2, files.reads)
        with self.assertRaises(KeyError): files.read(out, 1, 1, 0)


if __name__ == '__main__': unittest.main()
