"""Artifact I/O contracts on synthetic JSON; actual-source vectors run via test-artifacts in CI."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from toyota_runtime_compile import strict_json, verify_source_read_files, write_source_read_review
from vehicle_bundle import canonical


class CompiledArtifactJsonTest(unittest.TestCase):
    def test_duplicate_keys_nonfinite_numbers_bad_unicode_empty_and_size_limits(self):
        for raw in (b'', b'{"x":1,"x":2}', b'{"x":{"n":0,"n":1}}', b'{"x":NaN}',
                    b'{"x":Infinity}', b'{"x":-Infinity}', b'{"x":"\xff"}', b'{"x":1', b'{}{}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError): strict_json(raw)
        with self.assertRaises(ValueError): strict_json(b'{}', maximum_bytes=1)
        with self.assertRaises(ValueError): strict_json('{}')
        self.assertEqual({'x': [1, 'text', None, True]}, strict_json(b'{"x":[1,"text",null,true]}'))

    def test_reviewed_compiler_pin_is_small_distinct_from_source_or_executable_approval(self):
        path = Path(__file__).parent / 'schema' / 'mandatory-read-artifacts-v1.json'
        pin = strict_json(path.read_bytes(), maximum_bytes=4096)
        self.assertEqual('compiled-source-read-artifact-pin', pin['kind'])
        self.assertEqual({'compiledLiveReads': 30, 'faultLabels': 1414, 'sourceFaultQueries': 4,
                          'identificationReads': 1, 'productionExecutableDefinitions': 0}, pin['counts'])
        self.assertNotEqual(pin['sourceSha256'], pin['review']['sha256'])
        for name in ('review', 'diagnostics'):
            self.assertRegex(pin[name]['sha256'], '^[0-9a-f]{64}$')
            self.assertGreater(pin[name]['bytes'], 400000)


class CompiledArtifactIoTest(unittest.TestCase):
    """Only JSON/file delivery is isolated here. No mocked source validation is coverage."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.review = {'kind': 'synthetic-io-fixture', 'diagnostics': {'text': 'Synthetic test, not vehicle data'}}
        self.counts = {'syntheticCount': 1}
        patch = mock.patch('toyota_runtime_compile.verify_source_read_review', return_value=self.counts)
        self.validator = patch.start(); self.addCleanup(patch.stop)
        write_source_read_review(self.review, self.root)

    def test_canonical_split_roundtrip_is_byte_deterministic_and_calls_independent_validator(self):
        before = {p.name: p.read_bytes() for p in self.root.iterdir()}
        self.assertEqual(self.counts, verify_source_read_files(self.root))
        self.assertGreaterEqual(self.validator.call_count, 2)
        write_source_read_review(copy.deepcopy(self.review), self.root)
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.root.iterdir()})
        review = strict_json(before['mandatory-ecmect-source-reads.json'])
        raw = before['mandatory-ecmect-diagnostics.json']
        self.assertEqual({'file': 'mandatory-ecmect-diagnostics.json', 'bytes': len(raw),
            'sha256': hashlib.sha256(raw).hexdigest()}, review['diagnosticsFile'])

    def test_fixed_filenames_canonical_bytes_full_payload_and_pin_types_are_required(self):
        file = self.root / 'mandatory-ecmect-source-reads.json'
        original = strict_json(file.read_bytes())
        for change in (
                lambda p: p.update(file='../outside.json'), lambda p: p.update(file='/tmp/outside.json'),
                lambda p: p.update(bytes=True), lambda p: p.update(bytes=p['bytes'] + 1),
                lambda p: p.update(sha256='0' * 64), lambda p: p.update(ignored=True)):
            changed = copy.deepcopy(original); change(changed['diagnosticsFile'])
            file.write_bytes(canonical(changed) + b'\n')
            with self.assertRaises(ValueError): verify_source_read_files(self.root)
        file.write_bytes(canonical(original) + b'\n')
        diagnostic = self.root / 'mandatory-ecmect-diagnostics.json'
        raw = diagnostic.read_bytes()
        for changed in (raw[:-1], b' ' + raw, raw + b'{}', b'{"text":"Other synthetic value"}\n'):
            diagnostic.write_bytes(changed)
            with self.assertRaises(ValueError): verify_source_read_files(self.root)
        diagnostic.write_bytes(raw)
        file.write_bytes(json.dumps(original, indent=2).encode())
        with self.assertRaisesRegex(ValueError, 'canonical'): verify_source_read_files(self.root)

    def test_diagnostics_and_read_review_cannot_replace_one_another_or_disappear(self):
        review = self.root / 'mandatory-ecmect-source-reads.json'
        diagnostic = self.root / 'mandatory-ecmect-diagnostics.json'
        original = review.read_bytes()
        review.write_bytes(diagnostic.read_bytes())
        with self.assertRaises(KeyError): verify_source_read_files(self.root)
        review.write_bytes(original); diagnostic.unlink()
        with self.assertRaises(FileNotFoundError): verify_source_read_files(self.root)


if __name__ == '__main__': unittest.main()
