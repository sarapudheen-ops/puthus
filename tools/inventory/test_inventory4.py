import struct
import tempfile
from pathlib import Path
import unittest
import zipfile

import inventory4 as inv


class Inventory4Test(unittest.TestCase):
    def test_stem_rule(self):
        self.assertEqual(inv.stem('EU/V10.62/FUNCDATA_EU.BIN'), 'FUNCDATA')
        self.assertEqual(inv.stem('x/NA-Ecu_Data_0001-V12.34.bin'), 'ECU_DATA_0001')
        self.assertEqual(inv.stem('x/Menu.Bin'), 'MENU')

    def test_generic_reader_and_entropy(self):
        payload = struct.pack('<HII', 1, 1, 4) + b'abcd'
        result = inv.measure(payload)
        self.assertTrue(result['generic']['parsed'])
        self.assertEqual(result['generic']['rows'], 1)
        self.assertEqual(result['label'], 'looks structured')
        self.assertFalse(inv.measure(b'x')['generic']['parsed'])

    def test_all_stems_not_only_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'x.zip'
            with tempfile.TemporaryFile() as nested:
                with zipfile.ZipFile(nested, 'w') as inner:
                    inner.writestr('EU/MENU.BIN', b'a')
                    inner.writestr('NA/MENU.BIN', b'b')
                nested.seek(0)
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('SYSLIST_EU.BIN', b'x')
                    archive.writestr('BIN.ZIP', nested.read())
            result = inv.stems(path)
            self.assertEqual(result['counts'], {'MENU': 2, 'SYSLIST': 1})
            self.assertEqual(result['skippedNested'], [])

    def test_toyota_match_counts_files_not_distinct_only(self):
        row = {'decodedMeasurement': {'first8': 'aa'*8, 'first8Complete': True,
               'label': 'looks structured', 'generic': {'parsed': True}}}
        result = inv.summary({'report': {'bins': [row, row]}}, {'aa'*8})
        self.assertEqual(result['matchingFiles'], 2)
        self.assertEqual(result['parsed'], 2)


if __name__ == '__main__':
    unittest.main()
