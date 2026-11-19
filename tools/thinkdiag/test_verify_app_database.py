import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from app_library_format import DB_NAME, EXPECTED_COUNTS, SOURCE_SHA256, fingerprint
from verify_app_database import validate_manifest, verify


class VerifyAppDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.payload = b"SQLite format 3\0" + bytes(4080)
        self.pack = self.root / "toyota-source.zip"
        with zipfile.ZipFile(self.pack, "w", zipfile.ZIP_DEFLATED) as target:
            target.writestr(DB_NAME, self.payload)
        self.manifest = {"format": "bod3-toyota-source", "formatVersion": 1, "sourceSha256": SOURCE_SHA256,
                         "counts": EXPECTED_COUNTS.copy(), "executableDefinitions": 0, "expressExecution": False,
                         "manualSelectionOnly": True, "databaseBytes": len(self.payload),
                         "databaseSha256": hashlib.sha256(self.payload).hexdigest(), "archiveBytes": self.pack.stat().st_size,
                         "archiveSha256": hashlib.sha256(self.pack.read_bytes()).hexdigest(), "processorSha256": fingerprint()}
        self.save_manifest()

    def tearDown(self):
        self.temporary.cleanup()

    def save_manifest(self):
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))

    def test_accepts_exact_archive_and_checks_actual_apk_assets(self):
        self.assertEqual(self.manifest, verify(self.root))
        apk = self.root / "app-debug.apk"
        with zipfile.ZipFile(apk, "w") as app:
            app.write(self.root / "manifest.json", "assets/toyota-source/manifest.json")
            app.write(self.pack, "assets/toyota-source/toyota-source.zip")
        self.assertEqual(self.manifest, verify(self.root, apk))
        with zipfile.ZipFile(apk, "w") as app:
            app.writestr("assets/toyota-source/manifest.json", "{}")
        with self.assertRaisesRegex(ValueError, "different source manifest"):
            verify(self.root, apk)

    def test_rejects_missing_assets_and_a_partial_census(self):
        with self.assertRaisesRegex(ValueError, "missing"):
            verify(self.root / "not-built")
        self.manifest["counts"]["source_file"] = 1
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "census"):
            verify(self.root)

    def test_detects_modified_archives_and_database_checksums(self):
        self.manifest["databaseSha256"] = "a" * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "database checksum"):
            verify(self.root)
        self.manifest["archiveSha256"] = "b" * 64
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "archive checksum"):
            verify(self.root)

    def test_refuses_stale_processors_or_unpinned_sources(self):
        for key in ("processorSha256", "sourceSha256"):
            bad = {**self.manifest, key: "0" * 64}
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_manifest(bad)

    def test_refuses_executable_or_nonmanual_or_oversized_manifests(self):
        for key, value in (("executableDefinitions", 1), ("expressExecution", True), ("manualSelectionOnly", False),
                           ("databaseBytes", 500 * 1024 * 1024), ("archiveBytes", -1), ("databaseBytes", 4096.5)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_manifest({**self.manifest, key: value})

    def test_rejects_extra_archive_entries_even_with_recomputed_archive_hash(self):
        with zipfile.ZipFile(self.pack, "a") as target:
            target.writestr("../unwanted", b"data")
        self.manifest.update(archiveBytes=self.pack.stat().st_size,
                             archiveSha256=hashlib.sha256(self.pack.read_bytes()).hexdigest())
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "archive entries"):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
