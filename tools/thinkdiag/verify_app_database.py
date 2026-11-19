#!/usr/bin/env python3
"""Fail packaging if the complete audited Toyota library is absent, stale or corrupt.

Uses only Python's standard library; never downloads or executes source content.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from app_library_format import (DB_NAME, EXPECTED_COUNTS, FORMAT_VERSION, MAX_DB_BYTES,
                                MAX_ZIP_BYTES, SOURCE_SHA256, fingerprint)


def digest_stream(stream, maximum):
    digest = hashlib.sha256()
    total = 0
    header = b""
    while True:
        chunk = stream.read(65536)
        if not chunk:
            break
        if not header:
            header = chunk[:16]
        total += len(chunk)
        if total > maximum:
            raise ValueError("App library exceeds declared byte bound")
        digest.update(chunk)
    return total, digest.hexdigest(), header


def validate_manifest(manifest, check_fingerprint=True):
    if manifest.get("format") != "bod3-toyota-source" or manifest.get("formatVersion") != FORMAT_VERSION:
        raise ValueError("Unexpected app library format")
    if manifest.get("sourceSha256") != SOURCE_SHA256:
        raise ValueError("Unpinned Toyota source archive")
    if manifest.get("executableDefinitions") != 0 or manifest.get("expressExecution") is not False or manifest.get("manualSelectionOnly") is not True:
        raise ValueError("App library must be passive and manually browsed")
    if manifest.get("counts") != EXPECTED_COUNTS:
        raise ValueError("Incomplete Toyota source census")
    if check_fingerprint and manifest.get("processorSha256") != fingerprint():
        raise ValueError("Stale source library: run preparation again after processor changes")
    for field in ("databaseSha256", "archiveSha256", "processorSha256"):
        if not re.fullmatch("[a-f0-9]{64}", str(manifest.get(field, ""))):
            raise ValueError(f"Invalid {field}")
    for field, minimum, maximum in (("databaseBytes", 4096, MAX_DB_BYTES), ("archiveBytes", 1, MAX_ZIP_BYTES)):
        value = manifest.get(field)
        if type(value) is not int or not minimum <= value <= maximum:
            raise ValueError(f"Invalid {field}")


def verify(directory, apk=None, check_fingerprint=True):
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("Complete Toyota library is missing. See docs/toyota-app-library.md for the preparation command; refusing to build a silently incomplete APK.")
    if manifest_path.stat().st_size > 16384:
        raise ValueError("Manifest is too large")
    manifest = json.loads(manifest_path.read_text())
    validate_manifest(manifest, check_fingerprint)
    archive_path = directory / "toyota-source.zip"
    with archive_path.open("rb") as stream:
        size, digest, _ = digest_stream(stream, MAX_ZIP_BYTES)
    if (size, digest) != (manifest["archiveBytes"], manifest["archiveSha256"]):
        raise ValueError("App library archive checksum mismatch")
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        if len(entries) != 1 or entries[0].filename != DB_NAME or entries[0].file_size != manifest["databaseBytes"]:
            raise ValueError("Unexpected app library archive entries")
        with archive.open(entries[0]) as stream:
            size, digest, header = digest_stream(stream, manifest["databaseBytes"])
        if (size, digest, header) != (manifest["databaseBytes"], manifest["databaseSha256"], b"SQLite format 3\0"):
            raise ValueError("App library database checksum/header mismatch")
    if apk:
        with zipfile.ZipFile(apk) as package:
            prefix = "assets/toyota-source/"
            if package.read(prefix + "manifest.json") != manifest_path.read_bytes():
                raise ValueError("APK contains a different source manifest")
            with package.open(prefix + "toyota-source.zip") as stream:
                size, digest, _ = digest_stream(stream, MAX_ZIP_BYTES)
            if (size, digest) != (manifest["archiveBytes"], manifest["archiveSha256"]):
                raise ValueError("APK is missing the complete source library")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--stamp", type=Path)
    args = parser.parse_args()
    result = verify(args.directory, args.apk)
    if args.stamp:
        args.stamp.parent.mkdir(parents=True, exist_ok=True)
        args.stamp.write_text(result["databaseSha256"] + "\n")
    print(f"Verified complete Toyota offline library: {result['counts']['source_file']} decoded files, "
          f"{result['counts']['text_record']} text records; {result['archiveBytes']} compressed bytes.")
