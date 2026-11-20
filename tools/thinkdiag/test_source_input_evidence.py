"""Small synthetic research-stream fixtures; never ship or execute source arrays."""
import base64
import copy
import unittest
import zlib
from source_input_evidence import encode_inputs, decode_inputs
from sweep_reports import make_parts, parts_from_checks, unpack_parts


class SourceInputEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.sources = {'SYSTEMENTER.BIN': b'\x01\xff\0', 'EU/Ecu_Data_ffff.BIN': b'fake data, not an ECU definition' * 30}
        self.record = encode_inputs(self.sources)

    def test_deterministic_complete_hash_checked_round_trip_and_independent_evidence_namespace(self):
        self.assertEqual(self.record, encode_inputs(dict(reversed(list(self.sources.items())))))
        self.assertEqual(self.sources, decode_inputs(self.record))
        parts = make_parts([{'file': 'sweep-fixture.md', 'text': 'test'}])
        checks = [{'name': f'Toyota decoded source evidence {p["part"]:03d}', 'conclusion': 'success', 'output': {'summary': __import__('json').dumps(p)}} for p in parts]
        with self.assertRaises(ValueError): parts_from_checks(checks)
        self.assertEqual(parts, parts_from_checks(checks, 'Toyota decoded source evidence '))
        self.assertEqual('test', unpack_parts(parts)[0]['text'])

    def test_corrupt_truncated_concatenated_oversized_and_wrong_scope_inputs_rejected(self):
        stream = base64.b64decode(self.record['dataBase64'])
        for compressed in (stream[:-1], stream + zlib.compress(b'extra'), zlib.compress(b'x' * 5000)):
            record = copy.deepcopy(self.record); record['dataBase64'] = base64.b64encode(compressed).decode()
            with self.assertRaises(ValueError): decode_inputs(record)
        for key, value in [('sourceSha256', '0' * 64), ('decodedBytes', True), ('decodedBytes', 128 * 1024**2 + 1), ('formatVersion', True)]:
            record = copy.deepcopy(self.record); record[key] = value
            with self.assertRaises(ValueError): decode_inputs(record)
        record = copy.deepcopy(self.record); record['manifest'][0]['sha256'] = '0' * 64
        with self.assertRaises(ValueError): decode_inputs(record)

    def test_unsafe_alias_and_duplicate_paths_rejected_before_materialization(self):
        for path in ('../escape', '/absolute', 'EU/../escape', 'EU\\escape', 'EU//escape', './escape', 'C:/escape'):
            record = copy.deepcopy(self.record); record['manifest'][0]['file'] = path
            with self.subTest(path=path), self.assertRaises(ValueError): decode_inputs(record)
        record = copy.deepcopy(self.record); record['manifest'][0]['file'] = record['manifest'][1]['file']
        with self.assertRaises(ValueError): decode_inputs(record)


if __name__ == '__main__': unittest.main()
