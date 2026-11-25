import io
import tempfile
import unittest
import zipfile
from pathlib import Path

import brand_inventory as inventory


class InventoryTest(unittest.TestCase):
    def test_sample_is_deterministic_and_bounded(self):
        paths = [f'dir/{i:04d}.BIN' for i in range(600)] + ['one.BIN']
        first, rule = inventory.sample(paths, 100, 50)
        self.assertEqual(first, inventory.sample(list(reversed(paths)), 100, 50)[0])
        self.assertEqual(len(first), 100)
        self.assertEqual(len(set(first)), 100)
        self.assertIn('one.BIN', first)
        self.assertIn('uniform', rule)

    def test_bin_error_and_magic(self):
        result = inventory.describe_bin('SYSTEMENTER.BIN', b'\x00' * 30)
        self.assertFalse(result['parsed'])
        self.assertIn('Invalid SYSTEMENTER region count', result['readerError'])
        self.assertEqual(result['entropyFirst4KiB'], 0)
        self.assertEqual(result['entropyLabel'], 'looks structured')

    def test_non_elf_is_not_a_match(self):
        result = inventory.describe_so('not.so', b'plain text')
        self.assertFalse(result['elf'])
        self.assertEqual(result['exports'], [])

    def test_synthetic_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'example.zip'
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, 'w') as archive:
                archive.writestr('EU/Toyota_bin_11.BIN', b'\0' * 20)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('SYSTEMENTER.BIN', b'\0' * 30)
                archive.writestr('fake.so', b'not an ELF')
                archive.writestr('BIN.ZIP', inner.getvalue())
            budget = dict(bytesRead=0, binsInspected=0, librariesInspected=0)
            result = inventory.walk(path, budget)
            self.assertIsNone(result['error'])
            self.assertEqual(result['entries'], 3)
            self.assertEqual(result['binTotalOuter'], 1)
            self.assertEqual(result['binTotalNested'], 1)
            self.assertEqual(len(result['bins']), 2)
            self.assertEqual(len(result['sos']), 1)
            self.assertEqual(result['byExtension']['.so']['files'], 1)
            inventory.annotate_sos(result, {'names': ['SystemEnterPart']})
            summary = inventory.summarise('example', result, {'names': ['SystemEnterPart']})
            self.assertEqual(summary['verdict'], 'NOT ESTABLISHED')

    def test_chunks(self):
        text = 'a' * 150000
        chunks = inventory.chunk(text)
        self.assertEqual(''.join(chunks), text)
        self.assertTrue(all(len(part.encode()) <= inventory.CHECK_PART_BYTES for part in chunks))

    def test_known_oracle_names(self):
        names = inventory.hooked_names()
        for name in ('SystemEnterPart', 'GetDTCCodeFromAnsP3P4', 'CanSetParameterNormal'):
            self.assertIn(name, names)


if __name__ == '__main__':
    unittest.main()
