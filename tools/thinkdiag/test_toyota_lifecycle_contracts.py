"""Regression binding and artifact-I/O negatives; no synthetic source/adapter approval."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import toyota_lifecycle_contracts as c
from vehicle_bundle import canonical


class LifecycleContractsTest(unittest.TestCase):
    def test_reviewed_full_output_pin_cannot_drift_from_native_binding_domains(self):
        pin = json.loads(c.PIN.read_text())
        oracle = json.loads(c.ORACLE.read_text())
        self.assertEqual((284, 2272, 2272, 0), tuple(pin[k] for k in
            ('sourceEntries', 'fastVectors', 'isoVectors', 'productionExecutableDefinitions')))
        self.assertEqual(8, len(c.FAST_CASES)); self.assertEqual(8, len(c.ISO_CASES))
        for domain, digest in (('kwpRetryComparison', 'fastConsumerCallsSha256'), ('isoEntryComparison', 'isoConsumerCallsSha256')):
            self.assertEqual(pin['sourceBindingsSha256'], oracle[domain]['sourceBindingsSha256'])
            self.assertEqual(pin[digest], oracle[domain]['consumerCallsSha256'])
        self.assertEqual(set(c.FAST_CASES), set(oracle['kwpRetryComparison']['vectors']))
        self.assertEqual(set(c.ISO_CASES), set(oracle['isoEntryComparison']['vectors']))

    def test_contract_case_statuses_do_not_turn_zero_or_negative_service_into_fallback_proof(self):
        self.assertTrue(all(r.result == 0 for r in c.FAST_CASES['nativeZeroIsNotPositiveProof']))
        self.assertTrue(all(r.service == 0x7f for r in c.FAST_CASES['negativeServiceTwice']))
        self.assertTrue(all(r.result == -1 for r in c.FAST_CASES['adapterFailuresExhausted']))
        self.assertEqual(((0,), 0), c.ISO_CASES['initialZeroNotReplyProof'])

    def test_artifact_io_requires_whole_canonical_pin_not_claimed_counts_or_partial_json(self):
        # Intentionally tiny I/O-only pin: not a source-plan, wire, native or
        # production-positive fixture. Actual 284-entry artifacts run in CI.
        fixture = {'sourceEntries': 1, 'fastVectors': 8, 'isoVectors': 8, 'productionExecutableDefinitions': 0,
            'sourceBindingsSha256': 'a' * 64, 'fastConsumerCallsSha256': 'b' * 64, 'isoConsumerCallsSha256': 'c' * 64}
        raw = canonical(fixture) + b'\n'
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); pin = root / 'pin.json'; file = root / c.FILENAME
            pin.write_bytes(canonical(c.artifact_pin(raw, fixture)) + b'\n')
            with patch.object(c, 'PIN', pin):
                file.write_bytes(raw)
                self.assertEqual(1, c.verify_contract_file(root)['sourceEntries'])
                damaged = [b'', raw[:-1], raw[:len(raw)//2], b' ' + raw, raw + b'{}', raw + b'\n',
                    b'{"x":1,"x":2}', b'{"x":NaN}', raw[:1] + b'"sourceEntries":1,' + raw[1:]]
                for key, value in (('sourceEntries', True), ('productionExecutableDefinitions', 1), ('isoVectors', 0), ('foreignScope', 'NA')):
                    changed = copy.deepcopy(fixture); changed[key] = value; damaged.append(canonical(changed) + b'\n')
                for data in damaged:
                    file.write_bytes(data)
                    with self.subTest(data=data[:80]), self.assertRaises((ValueError, KeyError)):
                        c.verify_contract_file(root)
                file.unlink()
                with self.assertRaises(FileNotFoundError): c.verify_contract_file(root)


if __name__ == '__main__': unittest.main()
