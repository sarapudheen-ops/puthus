#!/usr/bin/env python3
"""Inventory-7: decoded data-family evidence for non-Toyota archives.

Read-only research. Nothing here enables a brand, writes an app pack, infers a
diagnostic operation, a frame byte or a field meaning.

Why this tool exists
--------------------
Stage 1 (``tools/inventory/stage1_coverage.py``) and Stage 2
(``tools/thinkdiag/stage2_multibrand.py``) called ``yzjm.decode_yzjm`` with one
argument while the decoder requires the caller's 32-byte format table, so every
decode raised ``TypeError``. Both stages therefore published zero parser signal
(Stage 2: "Qualifying brands: 0"; Stage 1: "Parser Output: NOT ESTABLISHED" for
all 73 archives *including Toyota*). That is a false negative produced by a call
bug, not evidence that no family exists. This probe decodes each archive with the
table read from *that archive's own* ``libDEVICEID.so`` and measures what the
bytes actually are.

What is measured, and what is deliberately not claimed
------------------------------------------------------
For every data family this reports:

* record structure -- whether an existing reader in ``tools/thinkdiag`` accepts
  the decoded bytes, and what it recovered;
* wrapper confidence -- whether the archive's own ``libDEVICEID.so`` is
  byte-identical to Toyota's, so the format table *and the virtual address it is
  read from* are the same ones the Toyota native reader uses;
* native consumer -- which of the archive's own shipped libraries export the
  functions this repo already cites as the Toyota consumers of that reader
  (``tools/thinkdiag/sweep_toyota.py:FOCUSED_READERS``), and whether the
  exporting library is the brand's own role library or a shared generic one;
* field meanings -- never asserted for any brand.

Consumer names cannot be invented here: ``_consumers`` raises unless every name
is already cited for that exact library in ``sweep_toyota.FOCUSED_READERS``.

A shared magic string (for example the ASCII ``TCODE`` run seen in decoded
ASTONMARTIN bytes), a shared first-8-byte header (for example
``1801000000810100``, seen in Toyota and 15 other brands) or a Toyota-shaped
filename is deliberately never sufficient for a verdict. The strongest verdict
this tool can emit is ``CANDIDATE``: record structure parses *and* one of the
archive's own role libraries exports a cited consumer. Even then field meanings
stay NOT ESTABLISHED and no converter, pack or app change follows.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
import time
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import brand_inventory as old  # noqa: E402  (also puts tools/thinkdiag on sys.path)

import toyota_control as control  # noqa: E402
from analyze_toyota import TOYOTA_SHA256, native_info  # noqa: E402
from decode_toyota import elf_bytes  # noqa: E402
from toyota_entries import EntryIndex  # noqa: E402
from toyota_structure import (  # noqa: E402
    function_index, menu_nodes, section_directory, sentinel_index,
    system_fields, system_lists, typed_table,
)
from yzjm import decode_yzjm  # noqa: E402

FORMAT = "bod3-family-evidence"
FORMAT_VERSION = 1
LABEL = ("exploratory: record structure is self-verifying only insofar as an existing reader accepts the "
         "decoded bytes; a filename, a magic string or a shared header proves nothing")

# decode_toyota.py:39 reads the format table from libDEVICEID.so at this VA.
TABLE_VA = 0xF88C
TABLE_SIZE = 32
YZJM_HEAD = b"\x05\x00YZJM\0"
SQLITE_MAGIC = b"SQLite format 3\x00"
MAX_MEMBER_BYTES = 32 * 1024 * 1024
MAX_CLASSIFIED = 300
SAMPLE_QUOTA = 100
MAX_LIBRARIES = 64
ASCII_RUN = re.compile(rb"[\x20-\x7e]{4,}")
# Libraries cited by FOCUSED_READERS that are not per-brand role libraries, so
# their exports are shared-platform evidence rather than brand-specific evidence.
SHARED_LIBRARIES = ("libDIAG.so", "libCOMM_ABSTRACT_LAYER.so")
ROLE_LIBRARY = re.compile(r"^libTOYOTA_([A-Z0-9]+)\.so$", re.IGNORECASE)

READER_TO_FAMILY = {
    "section_directory + typed_table": "section-directory",
    "EntryIndex": "entry-index",
    "function_index": "function-index-28",
    "system_lists": "system-list-37",
    "menu_nodes": "menu-nodes",
}


SWEEP_TOYOTA = HERE.parent / "thinkdiag" / "sweep_toyota.py"


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


# ------------------------------------------------------------------- families

def cited_consumers(path: Path | None = None) -> dict[str, list[str]]:
    """``sweep_toyota.FOCUSED_READERS``, read from source with ``ast``.

    Importing ``sweep_toyota`` would pull in ``static_readers`` and therefore
    ``capstone``, which this workflow does not install. Reading the literal from
    source keeps the citation authoritative -- the names still come from the
    repo, not from this file -- without the disassembler dependency. This is the
    same technique ``brand_inventory.hooked_names`` already uses.
    """
    source = (path or SWEEP_TOYOTA).read_text(encoding="utf-8")
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Assign) and any(getattr(target, "id", None) == "FOCUSED_READERS"
                                                for target in node.targets):
            return {library: list(names) for library, names in ast.literal_eval(node.value).items()}
    raise ValueError("sweep_toyota.FOCUSED_READERS could not be read from source")


CITED_CONSUMERS = cited_consumers()


def _consumers(*pairs: tuple[str, tuple[str, ...]]) -> dict[str, list[str]]:
    """Consumer names must already be cited for that library in this repo."""
    out: dict[str, list[str]] = {}
    for library, names in pairs:
        cited = CITED_CONSUMERS.get(library, [])
        missing = [name for name in names if name not in cited]
        if missing:
            raise ValueError(
                f"sweep_toyota.FOCUSED_READERS[{library!r}] does not cite {missing}; "
                "this tool may not invent native consumers")
        out[library] = list(names)
    return out


def _structure_system_lists(data: bytes) -> dict:
    index, blocks = system_lists(data)
    rows = [row for block in blocks.values() for row in block]
    return {"indexRows": len(index), "blocks": len(blocks), "packedRows": len(rows), "rowBytes": 37,
            "firstRowFieldsByOffset": system_fields(rows[0]) if rows else None}


def _structure_keyed_index_8(data: bytes) -> dict:
    rows, end = sentinel_index(data, 0, 8)
    return {"indexRows": len(rows), "indexEnd": end, "stride": 8,
            "firstKey": rows[0][0] if rows else None, "firstValue": rows[0][1] if rows else None}


def _structure_function_index(data: bytes) -> dict:
    rows, end = function_index(data)
    return {"indexRows": len(rows), "indexEnd": end, "strideByte": 28}


def _structure_typed_table(data: bytes) -> dict:
    table = typed_table(data)
    return {"identity": table.identity, "count": table.count, "bodyBytes": len(table.body),
            "stride": table.stride, "paddingHex": table.padding.hex()}


def _structure_section_directory(data: bytes) -> dict:
    sections = section_directory(data)
    parsed = 0
    for section in sections:
        typed_table(data[section.offset:section.end])
        parsed += 1
    return {"sections": len(sections), "typedTablesParsed": parsed,
            "identities": [section.identity for section in sections][:16]}


def _structure_entry_index(data: bytes) -> dict:
    index = EntryIndex(data)
    return {"regions": sorted(index.regions), "lists": len(index.lists),
            "entries": sum(len(item.entries) for item in index.lists.values()),
            "unclaimedSpans": len(index.unclaimed)}


def _structure_menu_nodes(data: bytes) -> dict:
    nodes = menu_nodes(data)
    return {"nodes": len(nodes), "edges": sum(len(node["edges"]) for node in nodes.values())}


FAMILIES: tuple[dict, ...] = (
    {
        "key": "system-list-37",
        "label": "LE32-keyed sentinel index plus 37-byte packed rows (Toyota SYSLIST shape)",
        "structure": _structure_system_lists,
        "consumers": _consumers(
            ("libTOYOTA_FILE.so", ("GetVehicleInfoData", "GetSystemListData")),
            ("libTOYOTA_SCAN.so", ("GetSysListNo", "InitTopSystemList", "TransferVehicleInfo")),
        ),
        "citation": ("tools/thinkdiag/toyota_structure.py:242-247 (libTOYOTA_FILE.so "
                     "GetVehicleInfoData@0x112B4 / GetSystemListData@0x11574); "
                     "tools/thinkdiag/toyota_runtime_compile.py:17; tools/thinkdiag/sweep_toyota.py:28,62"),
    },
    {
        "key": "keyed-index-8",
        "label": "LE32-keyed sentinel index, 8-byte rows, values opaque",
        "structure": _structure_keyed_index_8,
        "consumers": _consumers(
            ("libTOYOTA_FILE.so", ("GetVehicleInfoData", "GetSystemListData")),
            ("libTOYOTA_SCAN.so", ("GetSysListNo", "InitTopSystemList")),
        ),
        "citation": ("tools/thinkdiag/toyota_structure.py:sentinel_index,system_lists; "
                     "tools/thinkdiag/sweep_toyota.py:28,62"),
    },
    {
        "key": "function-index-28",
        "label": "stride-tagged 28-byte directory with bounded section pointers (Toyota FUNCDATA shape)",
        "structure": _structure_function_index,
        "consumers": _consumers(
            ("libTOYOTA_COMM.so", ("GetFuncCommFrameInfo", "GetFuncCommFrameInfo_MultiCmd", "GetCommFrameInfo")),
        ),
        "citation": ("tools/thinkdiag/toyota_frames.py:3; tools/thinkdiag/toyota_structure.py:function_index; "
                     "tools/thinkdiag/sweep_toyota.py:53"),
    },
    {
        "key": "typed-table",
        "label": "LE16 tag, LE32 row count, LE32 payload size (Toyota typed table)",
        "structure": _structure_typed_table,
        "consumers": _consumers(
            ("libTOYOTA_FILE.so", ("GetToyotaTblData", "GetEcuTblData", "GetAllToyotaTblData")),
        ),
        "citation": ("tools/thinkdiag/toyota_structure.py:27-42 (GetToyotaTblData 0x103F8, "
                     "GetEcuTblData 0x105C0); tools/thinkdiag/sweep_toyota.py:28"),
    },
    {
        "key": "section-directory",
        "label": "LE32 count then (u8 kind, LE32 offset) sections, each a typed table (Toyota Ecu_Data shape)",
        "structure": _structure_section_directory,
        "consumers": _consumers(
            ("libTOYOTA_FILE.so", ("GetEcuTblData",)),
            ("libTOYOTA_FDATA.so", ("GetEcuDataP4",)),
        ),
        "citation": ("tools/thinkdiag/toyota_structure.py:section_directory; "
                     "tools/inventory/toyota_control.py:26-30; tools/thinkdiag/sweep_toyota.py:28,31"),
    },
    {
        "key": "entry-index",
        "label": "region directory plus selector-keyed entry lists (Toyota SYSTEMENTER shape)",
        "structure": _structure_entry_index,
        "consumers": _consumers(
            ("libTOYOTA_FILE.so", ("GetSystemEnterInfoData", "GetInfoFromEnterList")),
            ("libTOYOTA_FDATA.so", ("GetSystemEnterIndex", "CheckSystemEnter")),
        ),
        "citation": ("tools/thinkdiag/toyota_entries.py:1-8 (libTOYOTA_FILE.so "
                     "GetSystemEnterInfoData@11f70 / GetInfoFromEnterList@125e8); "
                     "tools/thinkdiag/sweep_toyota.py:28,31"),
    },
    {
        "key": "menu-nodes",
        "label": "38-byte node header plus 23-byte edge records (Toyota MENU shape)",
        "structure": _structure_menu_nodes,
        # libDIAG.so is a shared platform library, not a per-brand role library,
        # so a match here can never be brand-discriminating evidence.
        "consumers": _consumers(
            ("libDIAG.so", ("InitMenuTree", "GetCurrentMenuData", "GetMenuID_Ex", "MenuPro_Ex")),
        ),
        "citation": "tools/thinkdiag/toyota_structure.py:menu_nodes; tools/thinkdiag/sweep_toyota.py:58-60",
    },
)
FAMILY_KEYS = tuple(family["key"] for family in FAMILIES)
CONSUMER_NAMES = frozenset(name for family in FAMILIES
                           for names in family["consumers"].values() for name in names)


def family_roles(family: dict) -> set[str]:
    """Toyota role suffixes whose libraries evidence this family."""
    roles = set()
    for library in family["consumers"]:
        match = ROLE_LIBRARY.fullmatch(library)
        if match:
            roles.add(match.group(1).upper())
    return roles


# ------------------------------------------------------------------- archives

def own_table(path: Path) -> tuple[bytes | None, dict, bytes]:
    """Read the 32-byte format table from this archive's own libDEVICEID.so."""
    try:
        with zipfile.ZipFile(path) as archive:
            candidates = [i for i in archive.infolist() if not i.is_dir()
                          and PurePosixPath(i.filename).name == "libDEVICEID.so"]
            if not candidates:
                return None, {"status": "absent", "size": None, "sha256": None}, b""
            if len(candidates) != 1:
                return None, {"status": "ambiguous", "candidates": len(candidates)}, b""
            data = archive.read(candidates[0])
    except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError) as error:
        return None, {"status": "unreadable", "error": f"{type(error).__name__}: {error}"}, b""
    info: dict = {"status": "present", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    try:
        return elf_bytes(data, TABLE_VA, TABLE_SIZE), info, data
    except Exception as error:  # pyelftools raises several unrelated types here.
        return None, {**info, "status": "table-unavailable",
                      "tableError": f"{type(error).__name__}: {error}"}, data


class ArchiveScanner:
    """One bounded pass over an archive.

    Listing records every BIN member name and every member whose stored bytes
    begin with ``SQLite format 3\\0``, reading only a short prefix of each.
    Nested ZIPs stay open, so the selected members are read without extracting
    those archives a second time.

    Outer members and up to ``NESTED_ZIP_COUNT`` unencrypted nested ZIPs of at
    most ``NESTED_ZIP_MAX_BYTES`` are enumerated; everything else is recorded as
    skipped, so a bounded census is never reported as complete. The SQLite count
    is a measurement: PR #48 added a SQLite reader without any evidence that a
    brand archive contains one.
    """

    def __init__(self, path: Path):
        self.path = path
        self.names: list[str] = []
        self.sqlite: list[str] = []
        self.skipped: list[str] = []
        self._temps: list = []
        self._zips: list[zipfile.ZipFile] = []
        self._index: dict[str, tuple[zipfile.ZipFile, zipfile.ZipInfo]] = {}
        try:
            self._zips.append(zipfile.ZipFile(path))
            self._scan()
        except BaseException:
            self.close()
            raise

    def __enter__(self) -> "ArchiveScanner":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def close(self) -> None:
        for archive in self._zips:
            try:
                archive.close()
            except Exception:  # A partially read archive must not mask the result.
                pass
        self._zips.clear()
        for temp in self._temps:
            try:
                temp.close()
            except Exception:
                pass
        self._temps.clear()

    def _prefix(self, container: zipfile.ZipFile, info: zipfile.ZipInfo) -> bytes:
        try:
            with container.open(info, "r") as stream:
                return stream.read(len(SQLITE_MAGIC))
        except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError):
            return b""

    def _visit(self, container: zipfile.ZipFile, infos: list[zipfile.ZipInfo], nest: str) -> None:
        for info in sorted(infos, key=lambda item: item.filename):
            name = f"{nest}{info.filename}"
            if self._prefix(container, info).startswith(SQLITE_MAGIC):
                self.sqlite.append(name)
            if not info.filename.lower().endswith(".bin"):
                continue
            if info.flag_bits & 1:
                self.skipped.append(f"{name} (encrypted)")
            elif info.file_size > MAX_MEMBER_BYTES:
                self.skipped.append(f"{name} (larger than {MAX_MEMBER_BYTES} bytes)")
            else:
                self.names.append(name)
                self._index[name] = (container, info)

    def _scan(self) -> None:
        outer = self._zips[0]
        files = [i for i in outer.infolist() if not i.is_dir()]
        self._visit(outer, files, "")
        zips = [i for i in files if i.filename.lower().endswith(".zip")]
        eligible = [i for i in zips if not i.flag_bits & 1
                    and i.file_size <= old.NESTED_ZIP_MAX_BYTES][:old.NESTED_ZIP_COUNT]
        self.skipped += [i.filename for i in zips if i not in eligible]
        for info in eligible:
            temp = tempfile.TemporaryFile()
            self._temps.append(temp)
            try:
                with outer.open(info) as source:
                    shutil.copyfileobj(source, temp)
                temp.seek(0)
                inner = zipfile.ZipFile(temp)
            except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError) as error:
                self.skipped.append(f"{info.filename} ({type(error).__name__}: {error})")
                continue
            self._zips.append(inner)
            self._visit(inner, [i for i in inner.infolist() if not i.is_dir()], f"{info.filename}!/")

    def read(self, wanted: set[str]) -> dict[str, bytes]:
        payloads: dict[str, bytes] = {}
        for name in wanted:
            entry = self._index.get(name)
            if entry is None:
                self.skipped.append(f"{name} (not listed)")
                continue
            container, info = entry
            try:
                payloads[name] = container.read(info)
            except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError) as error:
                self.skipped.append(f"{name} ({type(error).__name__})")
        return payloads


def library_members(path: Path, brand: str, roles: set[str]) -> list[str]:
    """Actual basenames worth parsing for consumer exports, bounded and targeted."""
    wanted = {f"lib{brand}_{role}.so".upper() for role in roles}
    wanted |= {name.upper() for name in SHARED_LIBRARIES}
    with zipfile.ZipFile(path) as archive:
        found = {PurePosixPath(i.filename).name for i in archive.infolist()
                 if not i.is_dir() and i.filename.lower().endswith(".so")
                 and i.file_size <= old.SO_MAX_BYTES}
    return sorted((name for name in found if name.upper() in wanted),
                  key=lambda name: (name.upper(), name))[:MAX_LIBRARIES]


def own_role_libraries(libraries: list[str], brand: str, roles: set[str]) -> dict[str, list[str]]:
    """role suffix -> this archive's own library basenames for that role."""
    out: dict[str, list[str]] = {role: [] for role in roles}
    prefix = f"lib{brand}_".upper()
    for name in libraries:
        upper = name.upper()
        if not upper.startswith(prefix) or not upper.endswith(".SO"):
            continue
        role = upper[len(prefix):-3]
        if role in out:
            out[role].append(name)
    return out


def read_exports(path: Path, names: list[str]) -> tuple[dict[str, list[str]], dict[str, str]]:
    """symbol -> exporting library basenames, restricted to cited consumer names."""
    exports: dict[str, list[str]] = {}
    errors: dict[str, str] = {}
    if not names:
        return exports, errors
    wanted = {name.upper(): name for name in names}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            base = PurePosixPath(info.filename).name
            actual = wanted.get(base.upper())
            if actual is None:
                continue
            try:
                found = {row["name"] for row in native_info(archive.read(info), actual)["functions"]
                         if row["defined"]}
            except Exception as error:  # ELF parse failures are data, not crashes.
                errors[actual] = f"{type(error).__name__}: {error}"
                continue
            for symbol in sorted(found & CONSUMER_NAMES):
                exports.setdefault(symbol, []).append(actual)
    return {symbol: sorted(set(libs)) for symbol, libs in exports.items()}, errors


def classify(payload: bytes) -> dict[str, dict]:
    """Every family whose existing reader accepts these decoded bytes."""
    matches: dict[str, dict] = {}
    for family in FAMILIES:
        try:
            matches[family["key"]] = {"parsed": True, "structure": family["structure"](payload)}
        except (ValueError, IndexError, KeyError, TypeError, OverflowError, StopIteration) as error:
            matches[family["key"]] = {"parsed": False, "error": f"{type(error).__name__}: {error}"}
    return matches


def ascii_leads(payload: bytes, limit: int = 6, width: int = 40) -> list[str]:
    """Bounded printable runs from the first 4 KiB. Leads only: a string is not a
    meaning, and the ``TCODE`` run in decoded ASTONMARTIN bytes is not a DTC."""
    runs = (run.decode("ascii")[:width] for run in ASCII_RUN.findall(payload[:4096]))
    return list(dict.fromkeys(runs))[:limit]


def canonical(name: str) -> str:
    return name.replace("\\", "/").split("!/", 1)[-1]


def stem(name: str) -> str:
    """Inventory-4's stem rule, restated so this tool does not depend on import order."""
    text = PurePosixPath(canonical(name)).name
    text = re.sub(r"(?i)\.bin$", "", text)
    text = re.sub(r"(?i)(?:^|[_\-.])v\d{2}\.\d{2}(?=$|[_\-.])", "_", text)
    text = re.sub(r"(?i)(?:^|[_\-.])(?:EU|JP|NA)(?=$|[_\-.])", "_", text)
    return re.sub(r"[_\-.]+", "_", text).strip("_").upper()


# ------------------------------------------------------------------- probe

def probe(path: Path, brand: str, archive: str, toyota: dict, budget: dict,
          cross_check: bool = False) -> dict:
    """Decode one archive with its own table and measure every family."""
    table, device, device_bytes = own_table(path)
    identical = bool(table) and table == toyota["table"]
    if table is None:
        confidence = f"not applicable: {device.get('status')} — no format table could be read from this archive"
    elif identical:
        confidence = "established: this archive's own libDEVICEID.so is byte-identical to Toyota's"
    else:
        confidence = ("experimental: this archive's own libDEVICEID.so differs from Toyota's, so the table read "
                      "at the same virtual address is not proven to be the one this archive's reader uses")
    device["tableMatchesToyota"] = identical
    device["containsToyotaTable"] = bool(device_bytes) and toyota["table"] in device_bytes

    with ArchiveScanner(path) as scanner:
        listed = list(scanner.names)
        sqlite_members = list(scanner.sqlite)
        chosen, rule = old.sample(listed, MAX_CLASSIFIED, SAMPLE_QUOTA)
        payloads = scanner.read(set(chosen))
        member_skipped = list(scanner.skipped)

    libraries = library_members(path, brand, toyota["roles"])
    exports, export_errors = read_exports(path, libraries)
    own_libs = own_role_libraries(libraries, brand, toyota["roles"])

    families = {key: {"members": 0, "parsed": 0, "stems": Counter(), "errors": Counter(),
                      "samples": [], "firstHeaders": Counter()} for key in FAMILY_KEYS}
    decoded = 0
    decode_errors: Counter[str] = Counter()
    not_yzjm = 0
    sqlite_decoded = 0
    payload_owners: dict[str, list[str]] = {}
    cross: list[dict] = []

    for name in chosen:
        budget["binsInspected"] += 1
        raw = payloads.get(name)
        if raw is None:
            decode_errors["member could not be read after selection"] += 1
            continue
        if not raw.startswith(YZJM_HEAD):
            not_yzjm += 1
            continue
        if table is None:
            decode_errors["not applicable: no format table"] += 1
            continue
        try:
            payload = decode_yzjm(raw, table).payload
        except (ValueError, IndexError, KeyError, TypeError, OverflowError) as error:
            decode_errors[f"{type(error).__name__}: {error}"] += 1
            continue
        decoded += 1
        if payload.startswith(SQLITE_MAGIC):
            sqlite_decoded += 1
        digest = hashlib.sha256(payload).hexdigest()
        payload_owners.setdefault(digest, []).append(f"{brand}:{name}")
        header = payload[:8].hex()
        matches = classify(payload)
        if cross_check:
            try:
                reader: str | None = control.reader(canonical(name), payload)
            except (ValueError, IndexError, KeyError, TypeError) as error:
                reader = f"ERROR {type(error).__name__}: {error}"
            cross.append({"member": name, "nameKeyedReader": reader,
                          "families": sorted(key for key, value in matches.items() if value["parsed"])})
        for key, result in matches.items():
            row = families[key]
            row["members"] += 1
            row["firstHeaders"][header] += 1
            if result["parsed"]:
                row["parsed"] += 1
                row["stems"][stem(name)] += 1
                if len(row["samples"]) < 6:
                    row["samples"].append({"member": name, "decodedBytes": len(payload),
                                           "payloadSha256": digest, "first8": header,
                                           "structure": result["structure"],
                                           "asciiLeads": ascii_leads(payload)})
            else:
                row["errors"][result["error"]] += 1

    verdicts = []
    for family in FAMILIES:
        key = family["key"]
        row = families[key]
        roles = family_roles(family)
        consumers = []
        for library, symbols in family["consumers"].items():
            match = ROLE_LIBRARY.fullmatch(library)
            role = match.group(1).upper() if match else None
            for symbol in symbols:
                exported_by = exports.get(symbol, [])
                if role and any(lib in exported_by for lib in own_libs.get(role, [])):
                    kind = "own-role-library"
                elif exported_by:
                    kind = "shared-library-only"
                else:
                    kind = "absent"
                consumers.append({"consumer": symbol, "toyotaLibrary": library, "role": role,
                                  "exportedBy": exported_by, "kind": kind})
        own_consumers = sorted({c["consumer"] for c in consumers if c["kind"] == "own-role-library"})
        shared = sorted({c["consumer"] for c in consumers if c["kind"] == "shared-library-only"})
        role_names = sorted(name for role in sorted(roles) for name in own_libs.get(role, []))
        missing = []
        if row["parsed"] == 0:
            missing.append("no sampled decoded member of this archive parses with this family's existing reader")
        if table is None:
            missing.append(f"no format table could be read from this archive's own libDEVICEID.so ({device.get('status')})")
        elif not identical:
            missing.append("this archive's own libDEVICEID.so differs from Toyota's, so the decode is experimental")
        if not own_consumers:
            missing.append("none of this archive's own role libraries for this family "
                           f"({', '.join(role_names) or 'none present'}) export a cited consumer of its Toyota reader")
        missing.append("field meanings for this brand are NOT ESTABLISHED: no disassembly of this brand's own "
                       "reader ties any decoded field to a diagnostic address, service, PID or timing")
        verdict = "CANDIDATE" if (row["parsed"] and identical and own_consumers) else "NOT ESTABLISHED"
        verdicts.append({"family": key, "label": family["label"], "citation": family["citation"],
                         "verdict": verdict, "membersAttempted": row["members"], "parsed": row["parsed"],
                         "wrapperConfidence": confidence, "ownRoleConsumers": own_consumers,
                         "sharedLibraryConsumers": shared, "consumers": consumers, "roles": sorted(roles),
                         "ownRoleLibraries": role_names, "topStems": row["stems"].most_common(8),
                         "topFirstHeaders": row["firstHeaders"].most_common(5), "missingEvidence": missing})

    return {
        "brand": brand, "archive": archive, "archiveBytes": path.stat().st_size,
        "sha256": _file_sha256(path), "deviceLibrary": device, "wrapperConfidence": confidence,
        "binsListed": len(listed), "samplingRule": rule, "binsRead": len(payloads),
        "binsSkipped": member_skipped[:40],
        "binsSkippedCount": len(member_skipped),
        "yzjmDecoded": decoded, "notYzjm": not_yzjm, "classified": decoded,
        "decodeErrors": dict(sorted(decode_errors.items())),
        "sqliteMagicMembers": len(sqlite_members),
        "sqliteMagicMembersSample": sorted(sqlite_members)[:12],
        "sqliteMagicDecodedSamples": sqlite_decoded,
        "librariesParsed": libraries, "ownRoleLibraries": own_libs, "exportErrors": export_errors,
        "consumerExports": {symbol: libs for symbol, libs in sorted(exports.items())},
        "families": {key: {"members": row["members"], "parsed": row["parsed"],
                           "stems": dict(sorted(row["stems"].items())),
                           "errors": dict(sorted(row["errors"].items())),
                           "firstHeaders": dict(row["firstHeaders"].most_common(8)),
                           "samples": row["samples"]} for key, row in families.items()},
        "payloadOwners": payload_owners, "verdicts": verdicts, "nameKeyedCrossCheck": cross,
    }


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


# ------------------------------------------------------------------- control

def toyota_reference(path: Path) -> dict:
    """Pin the baseline archive and derive the table and role set from it."""
    if _file_sha256(path) != TOYOTA_SHA256:
        raise ValueError("Unexpected TOYOTA.zip SHA-256; the native table VA is version-specific")
    table, device, _ = own_table(path)
    if table is None:
        raise ValueError(f"Toyota format table unavailable: {device}")
    with zipfile.ZipFile(path) as archive:
        roles = {match.group(1).upper() for info in archive.infolist() if not info.is_dir()
                 for match in [ROLE_LIBRARY.fullmatch(PurePosixPath(info.filename).name)] if match}
    if not roles:
        raise ValueError("TOYOTA.zip ships no libTOYOTA_<ROLE>.so libraries")
    return {"table": table, "device": device, "roles": sorted(roles)}


def control_check(reference: dict, toyota_result: dict, control_result: dict) -> dict:
    """The name-agnostic classifier must reproduce the name-keyed converter readers."""
    disagreements = []
    agreed = 0
    for row in toyota_result["nameKeyedCrossCheck"]:
        reader = row["nameKeyedReader"]
        if not reader or str(reader).startswith("ERROR"):
            continue
        expected = READER_TO_FAMILY.get(reader)
        if expected is None:
            disagreements.append({**row, "problem": f"no family mapped from reader {reader!r}"})
        elif expected in row["families"]:
            agreed += 1
        else:
            disagreements.append({**row, "problem": f"reader {reader!r} expects family {expected!r}, "
                                                    f"classifier found {row['families']}"})
    successes = sum(1 for row in toyota_result["nameKeyedCrossCheck"]
                    if row["nameKeyedReader"] and not str(row["nameKeyedReader"]).startswith("ERROR"))
    passed = bool(control_result.get("passed")) and successes > 0 and not disagreements \
        and toyota_result["yzjmDecoded"] > 0
    reason = None
    if not passed:
        if not control_result.get("passed"):
            reason = "the existing Toyota decoded control did not pass"
        elif toyota_result["yzjmDecoded"] == 0:
            reason = "no Toyota YZJM member decoded with the archive's own table"
        elif successes == 0:
            reason = "no sampled Toyota member reached a name-keyed converter reader"
        else:
            reason = "the name-agnostic classifier disagrees with the name-keyed converter reader"
    return {"passed": passed, "reason": reason,
            "toyotaControl": f"{control_result.get('parsed')}/{control_result.get('sample')} parsed",
            "toyotaControlErrors": control_result.get("errors") or {},
            "classifierAgreements": agreed, "nameKeyedReaderSuccesses": successes,
            "disagreements": disagreements[:20], "disagreementCount": len(disagreements),
            "referenceRoles": reference["roles"], "deviceLibrary": reference["device"]}


# ------------------------------------------------------------------- report

def shared_payloads(state: dict, minimum_brands: int = 2) -> list[dict]:
    """Decoded payloads that are byte-identical across different archives."""
    owners: dict[str, list[str]] = {}
    for entry in state["brands"]:
        for digest, paths in entry["payloadOwners"].items():
            owners.setdefault(digest, []).extend(paths)
    rows = []
    for digest, paths in owners.items():
        brands = sorted({path.split(":", 1)[0] for path in paths})
        if len(brands) >= minimum_brands:
            rows.append({"sha256": digest, "brands": brands, "members": sorted(paths)[:12],
                         "memberCount": len(paths)})
    return sorted(rows, key=lambda row: (-len(row["brands"]), row["sha256"]))


def _wrapper(entry: dict) -> str:
    confidence = entry["wrapperConfidence"]
    for label in ("established", "experimental"):
        if confidence.startswith(label):
            return label
    return "not applicable"


def markdown(state: dict) -> str:
    control = state["control"]
    summary = state["summary"]
    out = ["# Inventory-7: decoded data-family evidence", "",
           f"- CI head SHA: `{state['headSha']}`",
           f"- Generated: `{state['generatedUtc']}`",
           f"- Label: {LABEL}.", "",
           "## Toyota control", "",
           f"- Existing decoded control (`tools/inventory/toyota_control.py`): {control['toyotaControl']}.",
           f"- Name-agnostic classifier vs name-keyed converter readers: {control['classifierAgreements']} "
           f"agreement(s), {control['disagreementCount']} disagreement(s).",
           f"- Toyota role libraries derived at run time: {', '.join(control['referenceRoles'])}.",
           f"- Conclusion: **{'PASS' if control['passed'] else 'FAIL'}**"
           + (f" — {control['reason']}" if control.get("reason") else "") + ".", "",
           "Control literal failure histogram:", "", "| error | count |", "| --- | ---: |"]
    for error, count in (control.get("toyotaControlErrors") or {"no failures": 0}).items():
        out.append(f"| {old.escape(str(error))} | {count} |")
    if control["disagreements"]:
        out += ["", "Classifier disagreements (control failures):", "",
                "| member | name-keyed reader | problem |", "| --- | --- | --- |"]
        for row in control["disagreements"]:
            out.append(f"| `{old.escape(row['member'])}` | `{old.escape(str(row['nameKeyedReader']))}` "
                       f"| {old.escape(row['problem'])} |")
    out += ["", "## Family definitions and their cited native consumers", "",
            "Consumer names are read from `sweep_toyota.FOCUSED_READERS` with `ast` (so `capstone` is never "
            "imported); `_consumers` refuses to run if a name is not already cited for that exact library, so "
            "none can be invented here.", "",
            "| family | record structure | cited Toyota consumers | citation |", "| --- | --- | --- | --- |"]
    for family in FAMILIES:
        consumers = "; ".join(f"`{lib}`: " + ", ".join(f"`{name}`" for name in names)
                              for lib, names in family["consumers"].items())
        out.append(f"| `{family['key']}` | {old.escape(family['label'])} | {consumers} "
                   f"| `{old.escape(family['citation'])}` |")
    out += ["", "## Per-archive measurement", "",
            "`wrapper` is **established** only when the archive's own `libDEVICEID.so` is byte-identical to "
            "Toyota's, so the table and the virtual address it is read from are the ones the Toyota native "
            "reader uses. Otherwise the decode is **experimental** and proves nothing either way. "
            "`SQLite` counts members of any extension whose stored bytes begin with `SQLite format 3\\0`; "
            "it is measured here over the whole enumerated set, never assumed.", "",
            "| archive | bytes | wrapper | BINs listed / read | decoded YZJM | SQLite | own role libraries |",
            "| --- | ---: | --- | --- | ---: | ---: | --- |"]
    for entry in state["brands"]:
        roles = [name for role in sorted(entry["ownRoleLibraries"]) for name in entry["ownRoleLibraries"][role]]
        out.append(f"| `{entry['archive']}` | {entry['archiveBytes']} | {_wrapper(entry)} "
                   f"| {entry['binsListed']} / {entry['binsRead']} | {entry['yzjmDecoded']} "
                   f"| {entry['sqliteMagicMembers']} | {old.escape(', '.join(roles) or 'none')} |")
    out += ["", "## Family verdicts", "",
            "The strongest verdict available is `CANDIDATE`: record structure parses with an existing reader "
            "*and* one of this archive's own role libraries exports a cited consumer of that reader. "
            "`CANDIDATE` never means field meanings are known, and no converter, pack or app change follows "
            "from it. Rows where nothing was attempted for that family are omitted; the JSON keeps all of them.",
            "", "| archive | family | parsed / attempted | verdict | own-role consumers | top stems |",
            "| --- | --- | ---: | --- | --- | --- |"]
    for entry in state["brands"]:
        for verdict in entry["verdicts"]:
            if verdict["membersAttempted"] == 0:
                continue
            stems = ", ".join(f"`{old.escape(name)}` ({count})" for name, count in verdict["topStems"][:5]) or "none"
            out.append(f"| `{entry['archive']}` | `{verdict['family']}` "
                       f"| {verdict['parsed']} / {verdict['membersAttempted']} | {verdict['verdict']} "
                       f"| {old.escape(', '.join(verdict['ownRoleConsumers']) or 'none')} | {stems} |")
    candidates = [(entry["archive"], verdict) for entry in state["brands"] if entry["brand"] != "TOYOTA"
                  for verdict in entry["verdicts"] if verdict["verdict"] == "CANDIDATE"]
    out += ["", "## Candidates and their missing evidence", "",
            f"- Candidate (archive, family) pairs: {len(candidates)} "
            "(TOYOTA.zip is the reference and is never counted as a candidate).",
            "- Families with established field meanings outside Toyota: **0**.",
            "- Packs, converters or app changes produced by this tool: **0**.", ""]
    for archive, verdict in candidates:
        out.append(f"### `{archive}` · `{verdict['family']}`")
        out.append("")
        out.append(f"- Parsed {verdict['parsed']} of {verdict['membersAttempted']} sampled decoded member(s).")
        out.append(f"- Own-role consumers: {', '.join(f'`{name}`' for name in verdict['ownRoleConsumers'])}.")
        out.append(f"- Citation: `{verdict['citation']}`")
        out.append("- Missing evidence:")
        for item in verdict["missingEvidence"]:
            out.append(f"  - {item}")
        out.append("")
    shared = shared_payloads(state)
    out += ["## Byte-identical decoded payloads across archives", "",
            "Same decoded bytes in different archives. This is duplication evidence, not format evidence.", "",
            "| decoded SHA-256 (prefix) | archives | members |", "| --- | --- | --- |"]
    for row in shared[:40]:
        out.append(f"| `{row['sha256'][:16]}` | {old.escape(', '.join(row['brands']))} "
                   f"| {old.escape(', '.join(row['members'][:4]))} |")
    if not shared:
        out.append("| none | none | none |")
    out += ["", "## Documentation summary", "",
            f"- Archives probed: {len(state['brands'])}; not reached: {len(state['pending'])}.",
            f"- Toyota control: {'PASS' if control['passed'] else 'FAIL'} "
            f"({control['toyotaControl']}; {control['classifierAgreements']} classifier agreement(s), "
            f"{control['disagreementCount']} disagreement(s)).",
            f"- Archives with an established wrapper: {summary['establishedWrappers']} of {summary['archives']}.",
            f"- Decoded YZJM members in sampled non-Toyota archives: {summary['decodedMembers']}.",
            f"- SQLite-magic members observed in brand archives: {summary['sqliteMembers']} "
            f"(plus {summary['sqliteDecodedSamples']} decoded sample(s)); measured, not assumed.",
            f"- Candidate (archive, family) pairs: {summary['candidates']}.",
            "- Families with established field meanings outside Toyota: 0.",
            "- Converters, packs or app changes produced by this tool: 0.", ""]
    if state["pending"]:
        out += [f"Not reached inside the budget: {', '.join(state['pending'])}.", ""]
    return "\n".join(out) + "\n"


def emit(state: dict, output: Path) -> dict:
    text = markdown(state)
    old.write_atomic(output / "inventory7.md", text)
    old.write_atomic(output / "inventory7.json",
                     json.dumps(state, indent=1, sort_keys=True, ensure_ascii=True, default=str) + "\n")
    for stale in sorted(output.glob("inventory7-[0-9][0-9][0-9].md")):
        stale.unlink()
    manifest = []
    for index, body in enumerate(old.chunk(text), 1):
        name = ("Inventory-7 family evidence" if index == 1
                else f"Inventory-7 family evidence (part {index})")
        path = output / f"inventory7-{index:03d}.md"
        old.write_atomic(path, body)
        manifest.append({"name": name, "file": str(path), "bytes": len(body.encode("utf-8"))})
    if manifest and max(item["bytes"] for item in manifest) > 60000:
        raise ValueError("A check part exceeds the 60,000-byte Checks API field limit")
    conclusion = ("success" if state["control"]["passed"] and not state["pending"] and not state.get("failed")
                  else "failure")
    old.write_atomic(output / "parts.json", json.dumps(manifest, indent=1) + "\n")
    old.write_atomic(output / "conclusion.txt", conclusion + "\n")
    return {"parts": manifest, "bytes": len(text.encode("utf-8")), "conclusion": conclusion}


def summarise(state: dict) -> None:
    brands = state["brands"]
    state["summary"] = {
        "archives": len(brands),
        "establishedWrappers": sum(1 for entry in brands if _wrapper(entry) == "established"),
        "experimentalWrappers": sum(1 for entry in brands if _wrapper(entry) == "experimental"),
        "decodedMembers": sum(entry["yzjmDecoded"] for entry in brands if entry["brand"] != "TOYOTA"),
        "sqliteMembers": sum(entry["sqliteMagicMembers"] for entry in brands if entry["brand"] != "TOYOTA"),
        "sqliteDecodedSamples": sum(entry["sqliteMagicDecodedSamples"] for entry in brands
                                    if entry["brand"] != "TOYOTA"),
        # Toyota is the reference, not a candidate: its own readers are the ones
        # every family citation comes from, so counting it would inflate the total.
        "candidates": sum(1 for entry in brands if entry["brand"] != "TOYOTA"
                          for verdict in entry["verdicts"] if verdict["verdict"] == "CANDIDATE"),
        "families": list(FAMILY_KEYS),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path, default=Path(".local/inventory7/downloads"))
    parser.add_argument("--max-seconds", type=int, default=1800)
    parser.add_argument("--brands", default="", help="optional comma-separated asset subset")
    parser.add_argument("--local-dir", type=Path, help="use local archives instead of downloading")
    args = parser.parse_args()

    started = time.monotonic()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.work if not args.local_dir else args.output).mkdir(parents=True, exist_ok=True)
    budget = {"maxSeconds": args.max_seconds, "elapsedSeconds": 0, "binsInspected": 0}

    if args.local_dir:
        assets = [{"tag": "local", "name": path.name, "bytes": path.stat().st_size}
                  for path in sorted(args.local_dir.glob("*.zip"),
                                     key=lambda item: (item.name != old.BASELINE, item.name.casefold()))]
        baseline = next((asset for asset in assets if asset["name"] == old.BASELINE), None)
        brands = [asset for asset in assets if asset is not baseline]
    else:
        if not args.repo:
            raise SystemExit("STOP: GH_REPO (or --repo) is required to list the release assets")
        baseline, brands = old.classify(old.release_assets(args.repo))
    if baseline is None:
        raise SystemExit(f"STOP: no {old.BASELINE} asset is available, so no Toyota reference can be established")
    wanted = {name.strip() for name in args.brands.split(",") if name.strip()}
    if wanted:
        brands = [asset for asset in brands if asset["name"] in wanted]
        if not brands:
            raise SystemExit("STOP: the requested brand subset matches no available archive")

    state = {"format": FORMAT, "formatVersion": FORMAT_VERSION, "generatedUtc": utc_now(),
             "headSha": os.environ.get("GITHUB_SHA", "NOT ESTABLISHED"), "label": LABEL,
             "brands": [], "pending": [asset["name"] for asset in brands], "failed": False,
             "budget": budget, "control": {"passed": False, "reason": "not run", "toyotaControl": "not run",
                                           "toyotaControlErrors": {}, "classifierAgreements": 0,
                                           "nameKeyedReaderSuccesses": 0, "disagreements": [],
                                           "disagreementCount": 0, "referenceRoles": [], "deviceLibrary": {}},
             "summary": {}, "familyKeys": list(FAMILY_KEYS)}

    def source(asset: dict) -> Path:
        return (args.local_dir / asset["name"]) if args.local_dir else old.download(asset, args.work)

    toyota_path = source(baseline)
    try:
        reference = toyota_reference(toyota_path)
    except ValueError as error:
        raise SystemExit(f"STOP: {error}")

    toyota_result = probe(toyota_path, "TOYOTA", baseline["name"], reference, budget, cross_check=True)
    state["brands"].append(toyota_result)
    state["control"] = control_check(reference, toyota_result, control.control(toyota_path))
    summarise(state)
    emit(state, args.output)
    print(f"control: {'PASS' if state['control']['passed'] else 'FAIL'} — {state['control']['toyotaControl']}, "
          f"{state['control']['classifierAgreements']} agreement(s), "
          f"{state['control']['disagreementCount']} disagreement(s)", flush=True)
    if not state["control"]["passed"]:
        print(f"STOP: Toyota control failed: {state['control']['reason']}", file=sys.stderr, flush=True)
        return 1
    if not args.local_dir:
        toyota_path.unlink(missing_ok=True)

    for asset in brands:
        if time.monotonic() - started > args.max_seconds:
            break
        path = source(asset)
        brand = PurePosixPath(asset["name"]).stem
        try:
            result = probe(path, brand, asset["name"], reference, budget)
        except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError, ValueError) as error:
            state["failed"] = True
            state["error"] = f"{asset['name']}: {type(error).__name__}: {error}"
            print(f"inventory-7 stopped: {state['error']}", file=sys.stderr, flush=True)
            break
        state["brands"].append(result)
        state["pending"] = [name for name in state["pending"] if name != asset["name"]]
        budget["elapsedSeconds"] = round(time.monotonic() - started)
        summarise(state)
        emit(state, args.output)
        if not args.local_dir:
            path.unlink(missing_ok=True)
            if path.exists():
                raise SystemExit(f"STOP: {path} still exists after processing")
        candidates = [verdict["family"] for verdict in result["verdicts"] if verdict["verdict"] == "CANDIDATE"]
        print(f"[{budget['elapsedSeconds']}s] {brand}: decoded {result['yzjmDecoded']}, "
              f"wrapper {_wrapper(result)}, candidates {', '.join(candidates) or 'none'}", flush=True)

    budget["elapsedSeconds"] = round(time.monotonic() - started)
    summarise(state)
    result = emit(state, args.output)
    print(f"Inventory-7 complete: {len(state['brands'])} archive(s), {result['bytes']} report bytes, "
          f"{len(result['parts'])} check part(s), conclusion {result['conclusion']}", flush=True)
    if state["pending"]:
        print(f"Not reached inside the budget: {', '.join(state['pending'])}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
