#!/usr/bin/env python3
"""Brand-specific reader for SQLite-based non-Toyota diagnostic archives."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile
import zlib

SQLITE_MAGIC = b"SQLite format 3\x00"
MAX_PACK_BYTES = 480 * 1024 * 1024


def is_sqlite_bytes(head: bytes) -> bool:
    return head.startswith(SQLITE_MAGIC)


def read_sqlite_file(db_path: Path) -> dict:
    head = db_path.read_bytes()[:16]
    if not is_sqlite_bytes(head):
        raise ValueError("Not a SQLite format 3 database")
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        cur = conn.cursor()
        tables = [
            row[0]
            for row in cur.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).fetchall()
        ]
        table_info = {}
        for tbl in tables:
            cols = [r[1] for r in cur.execute(f'PRAGMA table_info("{tbl}")').fetchall()]
            count = cur.execute(f'SELECT COUNT(*) FROM "{tbl}"').fetchone()[0]
            table_info[tbl] = {"columns": cols, "row_count": count}
        return {"tables": table_info, "table_count": len(tables)}
    finally:
        conn.close()


def extract_sqlite_archive(archive_path: Path, archive_name: str, out_dir: Path) -> dict:
    accepted = 0
    rejected = 0
    databases = []

    with zipfile.ZipFile(archive_path, "r") as zf:
        for info in sorted(zf.infolist(), key=lambda i: i.filename):
            if info.is_dir():
                continue
            raw = zf.read(info)
            if not is_sqlite_bytes(raw[:16]):
                continue
            with tempfile.NamedTemporaryFile(suffix=".db") as tmp:
                tmp.write(raw)
                tmp.flush()
                try:
                    meta = read_sqlite_file(Path(tmp.name))
                    accepted += 1
                    databases.append({
                        "member": info.filename,
                        "sha256": hashlib.sha256(raw).hexdigest(),
                        "bytes": len(raw),
                        **meta,
                    })
                except Exception:
                    rejected += 1

    out_dir.mkdir(parents=True, exist_ok=True)
    pack_obj = {
        "group": "sqlite",
        "archive": archive_name,
        "manualSelectionOnly": True,
        "vehicleTested": False,
        "databases": databases,
    }
    raw_json = json.dumps(pack_obj, sort_keys=True, separators=(",", ":")).encode("utf-8")
    compressed = zlib.compress(raw_json, level=6)
    pack_sha256 = hashlib.sha256(compressed).hexdigest()
    pack_path = out_dir / f"sqlite-{pack_sha256}.bin"
    pack_path.write_bytes(compressed)

    return {
        "archive": archive_name,
        "accepted": accepted,
        "rejected": rejected,
        "pack_file": pack_path.name,
        "pack_bytes": len(compressed),
        "pack_sha256": pack_sha256,
        "under_480mib": len(compressed) < MAX_PACK_BYTES,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    res = extract_sqlite_archive(args.archive, args.archive.name, args.out_dir)
    print(json.dumps(res, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
