#!/usr/bin/env python3
"""Build the offline Toyota source library, NOT an executable diagnostic pack.

Every decoded file and English record is accounted for; known structures become
indexed tables, opaque schemas remain explicitly non-executable source data.
No vendor code or EXPRESS is evaluated. The Android adapter/session APIs never
receive this database as a command source.
"""
from collections import deque
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sqlite3
import zipfile

from analyze_toyota import TOYOTA_SHA256, checked_infos
from decode_toyota import elf_bytes
from ggp import GagFile
from sweep_toyota import safe_source
from toyota_structure import (ECU_FILE, audit_command_pool, function_index, menu_nodes,
                              section_directory, system_fields, system_lists, typed_table, uint)

from app_library_format import (DB_NAME, EXPECTED_COUNTS, FORMAT_VERSION, MAX_DB_BYTES, MAX_ZIP_BYTES, fingerprint)

DDL = (Path(__file__).parent / "schema" / "app_schema.sql").read_text()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def create_database(path):
    db = sqlite3.connect(path)
    db.executescript(DDL)
    return db


def insert_table(db, name, slot, offset, data):
    table = typed_table(data)
    identity = f"{name}#{slot}"
    db.execute("INSERT INTO data_table VALUES(?,?,?,?,?,?,?,?)", (
        identity, name, slot, table.identity, table.count, table.stride, offset, len(table.body)))
    return table


def insert_decoded(db, name, data, texts):
    """All paths handled. Unknown formats are retained, never silently skipped."""
    status, issue = "opaque", None
    try:
        if re.fullmatch(ECU_FILE, name):
            for section in section_directory(data):
                insert_table(db, name, section.identity, section.offset + 10, data[section.offset:section.end])
            status = "ecu-sections"
        elif re.fullmatch(r"(?:EU|JP|NA)/(?:Toyota|Viewer)_bin_[0-9a-fA-F]+\.BIN", name):
            if name.endswith("Toyota_bin_00.BIN"):
                audit = audit_command_pool(data)
                region = name[:2]
                db.executemany("INSERT INTO command VALUES(?,?,?,?,?,?)", (
                    (region, row["index"], row.get("bytesHex"), row.get("error"), row["bytesOffset"], row["length"])
                    for row in audit["entries"]))
                status = "command-audit"
                issue = f"{audit['bodyShortfallBytes']} missing declared bytes; {len(audit['invalidEntries'])} invalid descriptor(s)"
            else:
                table = insert_table(db, name, -1, 10, data)
                status = "shared-table"
                if name.endswith("Toyota_bin_11.BIN"):
                    if table.stride != 8:
                        raise ValueError("Unknown frame stride")
                    db.executemany("INSERT INTO frame VALUES(?,?,?,?,?,?)", (
                        (name[:2], *(uint(table.body, i * 8 + n, 2) for n in (0, 2, 4, 6)), 10 + i * 8)
                        for i in range(table.count)))
                elif name.endswith("Toyota_bin_12.BIN"):
                    if table.stride != 16:
                        raise ValueError("Unknown function/frame stride")
                    db.executemany("INSERT INTO function_frame VALUES(?,?,?,?,?,?,?,?)", (
                        (name[:2], i, *(uint(table.body, i * 16 + n, 2) for n in (0, 2, 4, 6, 8)),
                         table.body[i * 16:(i + 1) * 16].hex(" ")) for i in range(table.count)))
        elif re.fullmatch(r"FUNCDATA_(EU|JP|NA)\.BIN", name):
            rows, _ = function_index(data)
            region = name[9:11]
            db.executemany("INSERT INTO function_index VALUES(?,?,?)", (
                (region, row[0], json.dumps(row, separators=(",", ":"))) for row in rows))
            status = "function-directory"
        elif re.fullmatch(r"SYSLIST_(EU|JP|NA)\.BIN", name):
            index, blocks = system_lists(data)
            region = name[8:10]
            db.executemany("INSERT INTO vehicle VALUES(?,?,?)", ((region, key, off) for key, off in index))
            for off, records in blocks.items():
                for i, record in enumerate(records):
                    fields = system_fields(record)["u32"]
                    label = lambda at: texts.get(f"{fields[str(at)]:08X}", "")
                    db.execute("INSERT INTO system VALUES(?,?,?,?,?,?,?,?,?,?)", (
                        region, off, i, fields["0"], label(4), label(8), label(23), label(28),
                        off + 2 + i * 37, record.hex(" ")))
            status = "vehicle-system-index"
        elif name == "MENU.BIN":
            insert_menu(db, data, texts)
            status = "menu-graph"
        else:
            issue = "Row schema unresolved; retained for offline inspection, not an executable definition"
    except ValueError as error:
        # A known schema becoming invalid must fail the build, not leave a partial table in the APK.
        raise ValueError(f"{name}: {error}") from error
    # One copy of EVERY decoded byte, including unrecognised fields/padding.
    # Indexed rows use bounded substrings of this payload; they never duplicate it.
    db.execute("INSERT INTO source_file VALUES(?,?,?,?,?,?)", (name, len(data), sha(data), status, issue, data))


def insert_menu(db, data, texts):
    nodes = menu_nodes(data)
    queue = deque([(0, None, 0, [], False)])
    visited = set()
    while queue:
        off, parent, ordinal, trail, manual = queue.popleft()
        if off in visited:
            raise ValueError("Menu is not a tree; refusing to invent a single parent")
        visited.add(off)
        node = nodes[off]
        title_id = f"{node['headerWords'][1]:08X}"
        title = texts.get(title_id)
        if title is None:
            raise ValueError("Unresolved menu title")
        # Only the actual root's manual-selection branch is marked selectable.
        manual = manual or (parent == 0 and title == "Manual Select")
        trail = trail + [title]
        if len(trail) > 32:
            raise ValueError("Menu depth exceeds validated bound")
        db.execute("INSERT INTO menu VALUES(?,?,?,?,?,?,?,?,?,?)", (
            off, parent, ordinal, title_id, title, " / ".join(trail), int(manual), len(node["edges"]),
            uint(data, off + 34, 2), data[off + 4:off + 38].hex(" ")))
        for i, edge in enumerate(node["edges"]):
            if edge["target"] not in nodes:
                raise ValueError("Menu edge has no node")
            db.execute("INSERT INTO menu_edge VALUES(?,?,?,?,?,?)", (
                off, i, edge["target"], edge["flags"], edge["tag"], edge["parametersHex"]))
            queue.append((edge["target"], off, i, trail, manual))
    if len(visited) != len(nodes):
        raise ValueError("Unreachable menu node")


def insert_english(db, name, data, format_xor):
    library = GagFile(data, format_xor)
    texts = {}
    non_utf8 = 0
    for identity, raw in library.records():
        key = identity.hex().upper()
        try:
            value = raw.decode("utf-8")
        except UnicodeDecodeError:
            value = None
            non_utf8 += 1
        # UTF-8 TEXT includes embedded/trailing NULs. Re-encoding it is lossless.
        # Non-UTF-8 remains raw, not guessed or removed.
        # Android 24 SQLite LOWER() stops at NUL. Only the search derivative
        # replaces NULs; original value/raw remain byte-for-byte recoverable.
        db.execute("INSERT INTO text_record VALUES(?,?,?,?,?)", (name, key, value, raw if value is None else b"",
                   value.replace("\0", " ") if value is not None else None))
        if name == "TEXT_EN.GAG" and value is not None:
            texts[key] = value.rstrip("\0")
    db.execute("INSERT INTO text_library VALUES(?,?,?,?)", (name, library.count, non_utf8, sha(data)))
    return texts


def insert_archive_inventory(db, archive):
    for info in checked_infos(archive):
        if info.is_dir():
            continue
        data = archive.read(info)
        name = PurePosixPath(info.filename).name
        kind = "ELF (excluded)" if data.startswith(b"\x7fELF") else name.rsplit(".", 1)[-1]
        db.execute("INSERT INTO archive_entry VALUES(?,?,?,?)", (info.filename, len(data), kind, sha(data)))
        if name == "BIN.ZIP":
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                for item in checked_infos(nested):
                    if not item.is_dir():
                        db.execute("INSERT INTO archive_entry VALUES(?,?,?,?)", (
                            info.filename + "!/" + item.filename, item.file_size, "nested BIN", sha(nested.read(item))))


def validate_database(db):
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise ValueError("Database integrity failure")
    if db.execute("SELECT count(*) FROM source_file WHERE bytes!=length(payload)").fetchone()[0]:
        raise ValueError("Source payload byte count mismatch")
    if db.execute("SELECT count(*) FROM data_table t LEFT JOIN source_file f ON f.name=t.file WHERE f.name IS NULL OR t.source_offset<10 OR t.source_offset+t.body_bytes>f.bytes OR (t.stride IS NOT NULL AND t.row_count*t.stride!=t.body_bytes)").fetchone()[0]:
        raise ValueError("Typed-table index is out of source bounds")
    for name, expected, payload in db.execute("SELECT name,sha256,payload FROM source_file"):
        if sha(payload) != expected:
            raise ValueError(f"Stored source SHA-256 mismatch: {name}")
    if db.execute("SELECT count(*) FROM text_record WHERE (value IS NULL)!=(search_text IS NULL) OR (value IS NOT NULL AND (length(raw)!=0 OR length(CAST(value AS BLOB))!=length(CAST(search_text AS BLOB)) OR instr(search_text,char(0))>0))").fetchone()[0]:
        raise ValueError("Invalid text search derivative or source-byte preservation")
    counts = {name: db.execute(f"SELECT count(*) FROM {name}").fetchone()[0] for name in
              ("archive_entry", "source_file", "data_table", "text_library", "text_record", "menu", "menu_edge", "vehicle", "system", "function_index", "command", "frame", "function_frame")}
    bad_commands = db.execute("SELECT count(*) FROM command WHERE error IS NOT NULL").fetchone()[0]
    if db.execute("SELECT count(*) FROM frame f WHERE EXISTS (SELECT 1 FROM command c WHERE c.region=f.region AND c.error IS NOT NULL AND c.id IN (CASE WHEN ref2>10000 THEN ref2-10000 ELSE ref2 END,CASE WHEN ref4>10000 THEN ref4-10000 ELSE ref4 END,CASE WHEN ref6>10000 THEN ref6-10000 ELSE ref6 END))").fetchone()[0]:
        raise ValueError("A frame references an invalid command")
    for field in ("ref2", "ref4", "ref6"):
        missing = db.execute(f"SELECT count(*) FROM frame f LEFT JOIN command c ON c.region=f.region AND c.id=CASE WHEN f.{field}>10000 THEN f.{field}-10000 ELSE f.{field} END WHERE f.{field}!=0 AND c.id IS NULL").fetchone()[0]
        if missing:
            raise ValueError("A frame dictionary reference does not resolve")
    if db.execute("SELECT count(*) FROM function_frame b LEFT JOIN frame f ON f.region=b.region AND f.id=b.frame WHERE b.selector8=0 AND f.id IS NULL").fetchone()[0]:
        raise ValueError("A function/frame reference does not resolve")
    counts["invalidCommands"] = bad_commands
    counts["opaqueFiles"] = db.execute("SELECT count(*) FROM source_file WHERE status='opaque'").fetchone()[0]
    return counts


def build(archive, decoded, english, manifest, output):
    if sha(archive.read_bytes()) != TOYOTA_SHA256:
        raise ValueError("Unexpected original archive hash")
    output.mkdir(parents=True, exist_ok=True)
    db_path = output / DB_NAME
    if db_path.exists():
        db_path.unlink()
    db = create_database(db_path)
    try:
        with zipfile.ZipFile(archive) as container:
            native = next(i for i in checked_infos(container) if i.filename.endswith("/libSTD.so"))
            format_xor = elf_bytes(container.read(native), 0x4C81C, 15)
            insert_archive_inventory(db, container)
        texts = {}
        expected_libraries = {"DSTREAM_EN.GAG": 44800, "EXPRESS.GAG": 12594, "RCODE_EN.GAG": 1885,
                              "TCODE_EN.GAG": 27035, "TEXT_EN.GAG": 35882, "VEHICLEADAS.GAG": 61656,
                              "VEHICLEDTCS.GAG": 701, "VEHICLEMANUAL.GAG": 13538}
        if {p.name for p in english.glob("*.GAG")} != expected_libraries.keys():
            raise ValueError("Missing or unexpected English member")
        for path in sorted(english.glob("*.GAG")):
            texts.update(insert_english(db, path.name, path.read_bytes(), format_xor))
        if dict(db.execute("SELECT name,records FROM text_library")) != expected_libraries:
            raise ValueError("Incomplete English library")
        entries = json.loads(manifest.read_text())
        seen = set()
        if len(entries) != 1410:
            raise ValueError("Incomplete decoded manifest")
        for item in sorted(entries, key=lambda x: x["file"]):
            name = item["file"]
            if name in seen:
                raise ValueError("Duplicate source file")
            seen.add(name)
            data = safe_source(decoded, name).read_bytes()
            if len(data) != item["bytes"] or sha(data) != item["sha256"]:
                raise ValueError(f"Decoded hash mismatch: {name}")
            insert_decoded(db, name, data, texts)
        counts = validate_database(db)
        for key, value in EXPECTED_COUNTS.items():
            if counts[key] != value:
                raise ValueError(f"Incomplete source library: {key}={counts[key]} not {value}")
        if db.execute("SELECT count(*) FROM text_record WHERE value IS NULL").fetchone()[0] != 4502:
            raise ValueError("Non-UTF-8 record census changed")
        if db.execute("SELECT count(*) FROM menu WHERE manual=1").fetchone()[0] == 0:
            raise ValueError("Manual branch not identified")
        metadata = {"format": "bod3-toyota-source", "formatVersion": FORMAT_VERSION, "sourceSha256": TOYOTA_SHA256,
                    "sourceVersion": "Toyota V10.62", "processorSha256": fingerprint(), "counts": counts,
                    "executableDefinitions": 0, "expressExecution": False,
                    "sqliteVersion": sqlite3.sqlite_version, "manualSelectionOnly": True}
        for key, value in metadata.items():
            db.execute("INSERT INTO metadata VALUES(?,?)", (key, json.dumps(value, separators=(",", ":"))))
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()
    if db_path.stat().st_size > MAX_DB_BYTES:
        raise ValueError("Source database exceeds the app's installation bound")
    payload = db_path.read_bytes()
    zipped = output / "toyota-source.zip"
    with zipfile.ZipFile(zipped, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        info = zipfile.ZipInfo(DB_NAME, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        target.writestr(info, payload)
    if zipped.stat().st_size > MAX_ZIP_BYTES:
        raise ValueError("Compressed library exceeds the app asset bound")
    metadata.update({"databaseBytes": len(payload), "databaseSha256": sha(payload),
                     "archiveBytes": zipped.stat().st_size, "archiveSha256": sha(zipped.read_bytes())})
    (output / "manifest.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    db_path.unlink()  # Only the compressed derivative and manifest may be bundled.
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--english", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.archive, args.decoded, args.english, args.manifest, args.output)
