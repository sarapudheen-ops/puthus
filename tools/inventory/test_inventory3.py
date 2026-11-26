import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

import inventory3 as inv


class Inventory3Test(unittest.TestCase):
    def test_magic_and_experiment_use_toyota_table(self):
        class Decoded:
            payload = b'parsed payload'
        own_table = b'o' * 32
        toyota_table = b't' * 32
        with patch.object(inv, 'decode_yzjm', return_value=Decoded()) as decode, \
             patch.object(inv.control, 'reader', return_value='menu_nodes'):
            row = inv.experiment(toyota_table, own_table, None)('MENU.BIN', inv.MAGIC + b'abc')
        self.assertEqual(row['headerClass'], 'YZJM')
        self.assertTrue(row['experiment']['parsed'])
        decode.assert_called_once_with(inv.MAGIC + b'abc', toyota_table)
        self.assertEqual(row['first8'], (inv.MAGIC + b'a').hex())

    def test_no_reader_or_magic_is_not_a_failed_trial(self):
        describe = inv.experiment(b't' * 32, None, 'not applicable: no libDEVICEID.so')
        row = describe('other.BIN', inv.MAGIC + b'abc')
        self.assertIsNone(row['experiment']['parsed'])
        self.assertIn('no converter', row['experiment']['reason'])
        self.assertEqual(describe('x.BIN', b'PK\x03\x04rest')['headerClass'], 'other recognized magic')
        self.assertEqual(describe('x.BIN', b'hello')['headerClass'], 'no recognized header')

    def test_library_comparison_and_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'a.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('libDEVICEID.so', b'xyztable')
            with patch.object(inv, 'elf_bytes', return_value=b'table'):
                _, details = inv.device(path, b'other', b'table')
            self.assertEqual(details['status'], 'different')
            self.assertTrue(details['sameVABytes'])
            self.assertTrue(details['containsToyotaTable'])
            self.assertEqual(details['size'], 8)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('unrelated', b'')
            self.assertEqual(inv.device(path, b'other', b'table')[1]['status'], 'absent')

    def test_metrics_exclude_unmeasured_and_inapplicable(self):
        rows = [{'headerClass': 'YZJM', 'experiment': {'parsed': True}},
                {'headerClass': 'YZJM', 'experiment': {'parsed': False, 'error': 'ValueError: bad'}},
                {'headerClass': 'no recognized header', 'experiment': {'parsed': None, 'reason': 'not YZJM'}},
                {}]
        result = inv.metrics({'report': {'bins': rows}})
        self.assertEqual((result['parsed'], result['applicable']), (1, 2))
        self.assertEqual(result['errors'], {'ValueError: bad': 1})
        self.assertEqual(result['unmeasured'], 1)


if __name__ == '__main__':
    unittest.main()
