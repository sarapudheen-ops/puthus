"""Tests for the cross-brand native library hash comparison (synthetic archives only)."""

from __future__ import annotations

import pathlib
import tempfile
import unittest
import zipfile

import brand_libs

SHARED = b"\x7fELF" + b"shared" * 40
UNIQUE = b"\x7fELF" + b"unique" * 40


def make(path: pathlib.Path, members: dict[str, bytes]) -> pathlib.Path:
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in members.items():
            zf.writestr(name, data)
    return path


class LibraryHashTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_library_reported_as_shared(self):
        make(self.root / "AAA.zip", {"V/libSTD.so": SHARED, "V/libDEVICEID.so": SHARED})
        make(self.root / "BBB.zip", {"V/libSTD.so": SHARED, "V/libDEVICEID.so": SHARED})
        result = brand_libs.compare(sorted(self.root.glob("*.zip")))
        by_name = {row["library"]: row for row in result["libraries"]}
        self.assertTrue(by_name["libSTD.so"]["identical"])
        self.assertEqual(by_name["libSTD.so"]["variantCount"], 1)

    def test_differing_library_reported_as_variants(self):
        make(self.root / "AAA.zip", {"V/libSTD.so": SHARED})
        make(self.root / "CCC.zip", {"V/libSTD.so": UNIQUE})
        result = brand_libs.compare(sorted(self.root.glob("*.zip")))
        row = {r["library"]: r for r in result["libraries"]}["libSTD.so"]
        self.assertFalse(row["identical"])
        self.assertEqual(row["variantCount"], 2)

    def test_non_so_members_are_ignored(self):
        make(self.root / "AAA.zip", {"V/libSTD.so": SHARED, "V/TCODE_EN.GAG": b"data"})
        libs = brand_libs.library_hashes(self.root / "AAA.zip")
        self.assertEqual(set(libs), {"libSTD.so"})

    def test_brand_specific_library_present_in_one_brand_only(self):
        make(self.root / "AAA.zip", {"V/libAAAFILE.so": SHARED})
        make(self.root / "BBB.zip", {"V/libBBBFILE.so": UNIQUE})
        result = brand_libs.compare(sorted(self.root.glob("*.zip")))
        by_name = {r["library"]: r for r in result["libraries"]}
        self.assertEqual(sorted(by_name["libAAAFILE.so"]["brands"].values())[0], ["AAA"])
        # "Identical" is trivially true for a library only one brand ships.
        self.assertTrue(by_name["libAAAFILE.so"]["identical"])

    def test_corrupt_archive_is_reported_not_raised(self):
        (self.root / "BAD.zip").write_bytes(b"not a zip")
        result = brand_libs.compare([self.root / "BAD.zip"])
        self.assertIn("#error", result["brands"]["BAD"])

    def test_markdown_mentions_both_brands(self):
        make(self.root / "AAA.zip", {"V/libSTD.so": SHARED})
        make(self.root / "BBB.zip", {"V/libSTD.so": UNIQUE})
        text = brand_libs.markdown(brand_libs.compare(sorted(self.root.glob("*.zip"))))
        self.assertIn("AAA", text)
        self.assertIn("BBB", text)
        self.assertIn("NO", text)

    def test_missing_archive_directory_exits_nonzero(self):
        self.assertEqual(brand_libs.main([str(self.root / "missing")]), 2)


if __name__ == "__main__":
    unittest.main()
