import tempfile
from pathlib import Path
import unittest
import zipfile

import inventory6 as i6


def entry(name, stems, size=1):
    return {'brand': name, 'report': {'stems': {'counts': dict.fromkeys(stems, 1), 'skippedNested': []},
                                     'archiveBytes': size, 'soNames': [], 'bins': []}}


class ClusteringTest(unittest.TestCase):
    def test_connected_components_transitive_and_smallest_representative(self):
        brands = [entry('A', ['x', 'y'], 10), entry('B', ['x', 'y', 'z'], 2), entry('C', ['y', 'z'], 3)]
        result = i6.clusters(brands, 0.6)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['members'], ['A', 'B', 'C'])
        self.assertEqual(result[0]['commonStems'], ['y'])
        self.assertEqual(result[0]['representative'], 'B')

    def test_empty_sets_are_singletons(self):
        self.assertEqual(len(i6.clusters([entry('A', []), entry('B', [])], 0.2)), 2)

    def test_library_and_stem_normalization(self):
        self.assertEqual(i6.library_stem('EU/libFoo_EU.so'), 'LIBFOO')

    def test_archive_profile_hashes_and_sizes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'test.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('EU/X.BIN', b'abc')
                archive.writestr('JP/X.BIN', b'defgh')
                archive.writestr('libFOO.so', b'data')
            result = i6.describe_archive(path)
            self.assertEqual(result['stems']['X'], {'count': 2, 'bytes': 8})
            self.assertEqual(result['libraries'][0]['stem'], 'LIBFOO')
            self.assertEqual(len(result['libraries'][0]['sha256']), 64)


if __name__ == '__main__':
    unittest.main()
