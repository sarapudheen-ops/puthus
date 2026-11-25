import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import inventory2
import toyota_control


class DecodedInventoryTests(unittest.TestCase):
    def test_missing_brand_table_is_not_applicable(self):
        row = inventory2.decoded_describer(None, 'not applicable: no libDEVICEID.so')('MENU.BIN', b'abc')
        self.assertIsNone(row['parsed'])
        self.assertEqual(row['readerError'], 'not applicable: no libDEVICEID.so')

    def test_decode_then_reader(self):
        class Decoded:
            payload = b'decoded'
        with patch.object(inventory2, 'decode_yzjm', return_value=Decoded()) as decode, \
             patch.object(toyota_control, 'reader', return_value='menu_nodes') as reader:
            row = inventory2.decoded_describer(bytes(32), None)('MENU.BIN', b'\x05\x00YZJM\0xxxx')
        self.assertTrue(row['parsed'])
        decode.assert_called_once()
        reader.assert_called_once_with('MENU.BIN', b'decoded')

    def test_failed_control_report_has_no_verdicts(self):
        state = {'headSha': 'synthetic', 'control': {'parsed': 0, 'sample': 2, 'passed': False,
                  'sampling': {'outer': 'all 2', 'nested': 'all 0'}, 'errors': {'ValueError: example': 2}},
                 'brands': [], 'pending': ['X.zip'], 'assets': []}
        with tempfile.TemporaryDirectory() as tmp:
            inventory2.write_report(state, Path(tmp))
            text = (Path(tmp) / 'brand-inventory.md').read_text()
            self.assertIn('ValueError: example', text)
            self.assertIn('STOP', text)
            self.assertNotIn('same family as Toyota', text)
            self.assertNotIn('## Summary', text)


if __name__ == '__main__':
    unittest.main()
