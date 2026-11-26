#!/usr/bin/env python3
"""Read-only inventory of every brand archive published in this repo's releases.

Nothing is enabled, decoded into app packs or executed. One release asset is
downloaded, described from its own ZIP central directory and its ``.bin``/``.so``
members, then deleted before the next asset is fetched. Partial results are
rewritten atomically after every brand, so a run cut short by the job timeout
still publishes exactly what it measured plus an explicit list of what it did
not reach.

The comparison basis is the *existing* Toyota work in ``tools/thinkdiag``: the
same ZIP-in-ZIP walk, the same entropy/magic helper, the same strict
``toyota_structure``/``EntryIndex``/``FunctionBlocks`` readers and the same
exported-symbol reader. ``TOYOTA.zip`` is processed first through that identical
code path, so its own exported names — not a hand-written expectation — form the
reference set, intersected with the names the Toyota oracle hooks. No vendor key,
table, payload or library is written to Git.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import datetime
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import struct
import sys
import tempfile
import time
import zipfile

HERE = Path(__file__).resolve().parent
THINKDIAG = HERE.parent / "thinkdiag"
REPO_ROOT = HERE.parent.parent
if str(THINKDIAG) not in sys.path:
    sys.path.insert(0, str(THINKDIAG))

from analyze_toyota import native_info  # noqa: E402  (path set above)
from inspect_pack import entropy, signatures  # noqa: E402
from toyota_ecuprogram import EcuProgramIndex  # noqa: E402
from toyota_entries import EntryIndex  # noqa: E402
from toyota_functions import FunctionBlocks  # noqa: E402
from toyota_structure import (ECU_FILE, command_pool, function_index, menu_nodes,  # noqa: E402
                              section_directory, system_lists, typed_table)

FORMAT = "bod3-brand-inventory"
FORMAT_VERSION = 1
BASELINE = "TOYOTA.zip"
# This repo's own published artefacts are never brand archives. The pattern is
# deliberately narrow; every asset it does not match is treated as a brand zip and
# appears in the report, so a misclassification can never hide a brand silently.
OWN_ASSET = re.compile(r"^(?:sweep-|stage-|pack-census|code1-|reader-|native-|binary-|decoded-|english-|"
                       r"converted-|inspection|structural-|command-pool|shared-tables|open-formats|ecu-sections)"
                       r"|^bod3-debug-|^toyota-[0-9a-f]{16,}\.bin$|^[a-z]+-dtcs\.json$")
YZJM_HEAD = b"\x05\x00YZJM\0"
BIN_SAMPLE = 100
BIN_SHALLOW_QUOTA = 50
NESTED_BIN_SAMPLE = 60
NESTED_SHALLOW_QUOTA = 30
SO_SAMPLE = 1000000
SO_ALL_LIMIT = 1000000
SO_MAX_BYTES = 64 * 1024 * 1024
MEMBER_MAX_BYTES = 32 * 1024 * 1024
NESTED_ZIP_MAX_BYTES = 256 * 1024 * 1024
NESTED_ZIP_COUNT = 4
ENTROPY_STRUCTURED = 7.2
CHECK_PART_BYTES = 52000
CHECK_PARTS_MAX = 200
VERDICTS = ("same family as Toyota", "different", "NOT ESTABLISHED")
# Modules whose source names the native functions the Toyota oracle calls, binds
# or compares. The reference set is derived from them at run time, so it cannot
# silently drift from the oracle; only names Toyota really exports are kept.
ORACLE_MODULES = (
    "toyota_native_oracle", "toyota_native_workflow", "toyota_native_transport",
    "toyota_native_identity", "toyota_group_oracle", "toyota_can_link_sweep",
    "toyota_firmware_timing", "toyota_entry_runtime", "toyota_dtc_workflow",
    "stage1c_group_oracle", "toyota_ecmect_pack",
)
IDENTIFIER = re.compile(r"[A-Za-z][A-Za-z0-9_]*")


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


# ------------------------------------------------------------------- oracle names

def hooked_names(directory: Path | None = None) -> list[str]:
    """Every identifier the Toyota oracle calls, binds or compares, from its source."""
    directory = directory or THINKDIAG
    found: set[str] = set()

    def add(value: str) -> None:
        # Reject prose, filenames and bare lowercase p-code words; a real export
        # is either CamelCase or underscore-separated.
        if IDENTIFIER.fullmatch(value) and ("_" in value or re.search(r"[A-Z]", value[1:])):
            found.add(value)

    for module in ORACLE_MODULES:
        path = directory / f"{module}.py"
        if not path.is_file():
            continue
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                    and node.func.attr in ("call", "bind") and node.args:
                first = node.args[0]
                if isinstance(first, ast.Constant) and isinstance(first.value, str):
                    add(first.value)
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) \
                    and node.func.id == "ConsumerCall" and node.args:
                first = node.args[0]
                if isinstance(first, ast.Constant) and isinstance(first.value, str):
                    add(first.value)
            elif isinstance(node, ast.Tuple) and len(node.elts) >= 2:
                first, second = node.elts[0], node.elts[1]
                if isinstance(first, ast.Constant) and isinstance(first.value, str) \
                        and isinstance(second, (ast.Tuple, ast.Constant, ast.Call, ast.List)):
                    add(first.value)
    tree = ast.parse((directory / "sweep_toyota.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "FOCUSED_READERS" for t in node.targets):
            for values in ast.literal_eval(node.value).values():
                found.update(value for value in values if IDENTIFIER.fullmatch(value))
    return sorted(found)


# ------------------------------------------------------------------- release data

def gh_lines(args: list[str]) -> list[str]:
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True)
    return [line for line in out.stdout.splitlines() if line.strip()]


def release_assets(repo: str) -> list[dict]:
    """Every asset of every release, without assuming a release name."""
    releases = gh_lines(["api", "--paginate", f"repos/{repo}/releases", "--jq", '.[] | [.id, .tag_name] | @tsv'])
    assets = []
    for release in releases:
        release_id, tag = release.split("\t", 1)
        rows = gh_lines(["api", "--paginate", f"repos/{repo}/releases/{release_id}/assets", "--jq", '.[] | [.name, (.size | tostring)] | @tsv'])
        for row in rows:
            name, size = row.split("\t", 1)
            assets.append({"tag": tag, "name": name, "bytes": int(size)})
    return assets


def classify(assets: list[dict]) -> tuple[dict | None, list[dict]]:
    baseline, brands = None, []
    for asset in assets:
        if asset["name"] == BASELINE:
            baseline = asset
        elif asset["name"].lower().endswith(".zip") and not OWN_ASSET.match(asset["name"]):
            brands.append(asset)
    brands.sort(key=lambda asset: asset["name"].casefold())
    return baseline, brands


def download(asset: dict, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / asset["name"]
    if target.exists():
        target.unlink()
    subprocess.run(["gh", "release", "download", asset["tag"], "--pattern", asset["name"],
                    "--dir", str(directory), "--clobber"], check=True)
    if not target.is_file():
        raise ValueError(f"gh release download did not produce {asset['name']}")
    if target.stat().st_size != asset["bytes"]:
        raise ValueError(f"Downloaded {asset['name']} is {target.stat().st_size} bytes, "
                         f"the release lists {asset['bytes']}")
    return target


# ------------------------------------------------------------------- sampling

def sample(items: list[str], limit: int, quota: int) -> tuple[list[str], str]:
    """Deterministic sample: every shallowest entry (up to quota), then a stride."""
    ordered = sorted(items)
    if len(ordered) <= limit:
        return ordered, f"all {len(ordered)} (limit {limit})"
    shallow_depth = min(item.count("/") for item in ordered)
    shallow = [item for item in ordered if item.count("/") == shallow_depth][:quota]
    chosen = set(shallow)
    rest = [item for item in ordered if item not in chosen]
    need = max(0, limit - len(shallow))
    picked: list[str] = []
    seen: set[str] = set()
    for index in range(need):
        candidate = rest[min(len(rest) - 1, int(index * len(rest) / need))]
        if candidate not in seen:
            seen.add(candidate)
            picked.append(candidate)
    rule = (f"{len(ordered)} > {limit}: all {len(shallow)} shallowest (depth {shallow_depth}) path(s), then "
            f"{len(picked)} at a uniform index stride over the remaining {len(rest)} lexicographic path(s)")
    return shallow + picked, rule


# ------------------------------------------------------------------- .bin readers

def strict_ecu_sections(data: bytes) -> dict:
    sections = section_directory(data)
    for section in sections:
        block = data[section.offset:section.end]
        if len(block) < 10:
            raise ValueError(f"section {section.identity} is shorter than a typed-table header")
        table = typed_table(block)
        if table.count == 0:
            raise ValueError(f"section {section.identity} declares an empty typed table")
    return {"sections": len(sections), "ids": [section.identity for section in sections][:24]}


def strict_command_pool(data: bytes) -> dict:
    rows, _ = command_pool(data)
    if not rows:
        raise ValueError("command pool is empty")
    return {"rows": len(rows)}


def strict_typed_table(data: bytes) -> dict:
    table = typed_table(data)
    if table.count == 0:
        raise ValueError("typed table declares zero rows")
    return {"rows": table.count, "stride": table.stride}


def strict_entries(data: bytes) -> dict:
    index = EntryIndex(data)
    functions = sum(len(rows) for rows in index.regions.values())
    if functions == 0:
        raise ValueError("SYSTEMENTER directory lists no functions")
    unclaimed = sum(len(item["rawHex"]) // 2 for item in index.unclaimed)
    if unclaimed > 64:
        raise ValueError(f"SYSTEMENTER has {unclaimed} bytes outside framed records")
    return {"functions": functions, "lists": len(index.lists), "unclaimedBytes": unclaimed}


def strict_system_lists(data: bytes) -> dict:
    index, blocks = system_lists(data)
    if not index:
        raise ValueError("SYSLIST directory is empty")
    end = max((block_offset + 2 + 37 * len(rows) for block_offset, rows in blocks.items()), default=0)
    if len(data) - end > 4:
        raise ValueError(f"SYSLIST has {len(data) - end} byte(s) past the last declared block")
    return {"blocks": len(blocks), "rows": sum(len(rows) for rows in blocks.values())}


def strict_functions(data: bytes) -> dict:
    rows, end = function_index(data)
    if not rows:
        raise ValueError("FUNCDATA index is empty")
    blocks = FunctionBlocks(data)
    return {"indexRows": len(rows), "rows": len(blocks.rows), "indexEnd": end}


def strict_menu(data: bytes) -> dict:
    nodes = menu_nodes(data)
    return {"nodes": len(nodes), "edges": sum(len(node["edges"]) for node in nodes.values())}


def strict_ecuprogram(data: bytes) -> dict:
    index = EcuProgramIndex(data)
    return {"regions": len(index.regions),
            "rows": sum(len(rows) for rows in index.rows_by_region.values())}


READERS = (
    (re.compile(r"(?:EU|JP|NA)/Ecu_Data_[0-9a-fA-F]+\.BIN"),
     "Ecu_Data_XX.BIN: section_directory + typed tables", strict_ecu_sections),
    (re.compile(r"SYSTEMENTER\.BIN"), "SYSTEMENTER.BIN: EntryIndex", strict_entries),
    (re.compile(r"SYSLIST_(?:EU|JP|NA)\.BIN"), "SYSLIST_XX.BIN: system_lists", strict_system_lists),
    (re.compile(r"FUNCDATA_(?:EU|JP|NA)\.BIN"), "FUNCDATA_XX.BIN: function_index + FunctionBlocks",
     strict_functions),
    (re.compile(r"MENU\.BIN"), "MENU.BIN: menu_nodes", strict_menu),
    (re.compile(r"ECUPROGRAM\.BIN"), "ECUPROGRAM.BIN: EcuProgramIndex", strict_ecuprogram),
    (re.compile(r"(?:EU|JP|NA)/(?:Toyota|Viewer)_bin_00\.BIN"), "command_pool", strict_command_pool),
    (re.compile(r"(?:EU|JP|NA)/(?:Toyota|Viewer)_bin_[0-9a-fA-F]+\.BIN"),
     "Toyota_bin_XX.BIN: typed_table", strict_typed_table),
)


def reader_for(name: str):
    normalised = name.replace("\\", "/").split("!/", 1)[-1]
    base = normalised.rsplit("/", 1)[-1]
    for pattern, label, reader in READERS:
        if pattern.fullmatch(normalised) or pattern.fullmatch(base):
            return label, reader
    return "generic Toyota typed_table", strict_typed_table


def describe_bin(name: str, data: bytes) -> dict:
    first = data[:4096]
    value = entropy(first)
    record = {"path": name, "bytes": len(data),
              "magic": signatures(data[:512]) if data else "empty file", "headerHex": data[:16].hex(),
              "entropyFirst4KiB": value,
              "entropyLabel": "looks encrypted/compressed" if value >= ENTROPY_STRUCTURED else "looks structured",
              "yzjmWrapped": data.startswith(YZJM_HEAD)}
    label, reader = reader_for(name)
    if reader is None:
        record["reader"] = "no Toyota container reader is keyed to this file name"
        record["parsed"] = None
        return record
    record["reader"] = label
    try:
        record["readerResult"] = reader(data)
        record["parsed"] = True
    except ValueError as error:
        record["readerError"] = str(error)
        record["parsed"] = False
    except (IndexError, KeyError, OverflowError, TypeError, struct.error) as error:
        record["readerError"] = f"{type(error).__name__}: {error}"
        record["parsed"] = False
    return record


# ------------------------------------------------------------------- ELF readers

def elf_ident(data: bytes) -> dict:
    if not data.startswith(b"\x7fELF"):
        return {"elf": False}
    width = data[4] if len(data) > 4 else 0
    endian = data[5] if len(data) > 5 else 0
    kind = int.from_bytes(data[16:18], "little" if endian != 2 else "big") if len(data) >= 18 else 0
    return {"elf": True, "class": {1: "ELF32", 2: "ELF64"}.get(width, f"class {width}"),
            "endianness": {1: "little", 2: "big"}.get(endian, f"endian {endian}"), "eType": kind}


def describe_so(name: str, data: bytes) -> dict:
    record = {"path": name, "bytes": len(data), "magic": signatures(data[:512])}
    record.update(elf_ident(data))
    if not record["elf"]:
        record.update({"exports": [], "needed": [], "exportCount": 0, "architecture": "NOT ESTABLISHED",
                       "error": "not an ELF object, so it has no dynamic symbol table"})
        return record
    try:
        info = native_info(data, name)
    except Exception as error:  # pyelftools raises several unrelated types on damage
        record.update({"exports": [], "needed": [], "exportCount": 0, "architecture": "NOT ESTABLISHED",
                       "error": f"{type(error).__name__}: {error}"})
        return record
    exports = sorted({item["name"] for item in info["functions"] if item["defined"]})
    record.update({"architecture": info["machine"], "needed": info["needed"],
                   "exports": exports, "exportCount": len(exports)})
    return record


# ------------------------------------------------------------------- one archive

def walk(path: Path, budget: dict, so_limit: int = SO_SAMPLE) -> dict:
    """Describe one archive from its central directory; never extract to disk."""
    report = {"archive": path.name, "archiveBytes": path.stat().st_size, "error": None, "entries": 0,
              "encrypted": 0, "byExtension": {}, "nestedByExtension": {}, "nested": [],
              "bins": [], "sos": [], "binTotalOuter": 0, "binTotalNested": 0, "binBytesOuter": 0,
              "binBytesNested": 0, "soOuter": 0, "soNested": 0, "soNames": []}
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            for info in infos:
                member = PurePosixPath(info.filename.replace("\\", "/"))
                if member.is_absolute() or ".." in member.parts:
                    raise ValueError(f"Unsafe ZIP member: {info.filename}")
                if info.external_attr >> 16 & 0o170000 == 0o120000:
                    raise ValueError(f"Symbolic-link ZIP member: {info.filename}")
            files = [info for info in infos if not info.is_dir()]
            report["entries"] = len(files)
            report["encrypted"] = sum(bool(info.flag_bits & 1) for info in files)
            counts, sizes = Counter(), Counter()
            for info in files:
                suffix = PurePosixPath(info.filename.replace("\\", "/")).suffix.lower() or "<none>"
                counts[suffix] += 1
                sizes[suffix] += info.file_size
            report["byExtension"] = {suffix: {"files": counts[suffix], "bytes": sizes[suffix]}
                                     for suffix in sorted(counts, key=lambda key: (-counts[key], key))}
            outer = {info.filename: info for info in files}
            outer_bins = sorted(name for name in outer if name.lower().endswith(".bin"))
            outer_sos = sorted(name for name in outer if name.lower().endswith(".so"))
            report["binTotalOuter"] = len(outer_bins)
            report["binBytesOuter"] = sum(outer[name].file_size for name in outer_bins)
            report["soOuter"] = len(outer_sos)
            report["soNames"] = list(outer_sos)

            # Nested containers (Toyota keeps most tables inside BIN.ZIP). Bounded
            # count and size; each is copied to an ignored scratch file only while
            # its sampled members are read, then removed.
            nested_zips = [info for info in files
                           if info.filename.lower().endswith(".zip") and not info.flag_bits & 1
                           and info.file_size <= NESTED_ZIP_MAX_BYTES][:NESTED_ZIP_COUNT]
            for info in files:
                if info.filename.lower().endswith(".zip"):
                    report["nested"].append({
                        "path": info.filename, "bytes": info.file_size,
                        "enumerated": info in nested_zips,
                        "reason": None if info in nested_zips else (
                            "ZIP-encrypted" if info.flag_bits & 1 else
                            f"beyond the first {NESTED_ZIP_COUNT} nested archives" if info.file_size <= NESTED_ZIP_MAX_BYTES
                            else f"larger than {NESTED_ZIP_MAX_BYTES} bytes")})
            nested_counts, nested_sizes = Counter(), Counter()
            for order, info in enumerate(nested_zips):
                scratch = Path(tempfile.mkdtemp(prefix="nested-")) / f"nested-{order}.zip"
                try:
                    with archive.open(info) as source, scratch.open("wb") as target:
                        shutil.copyfileobj(source, target)
                    with zipfile.ZipFile(scratch) as inner:
                        members = [member for member in inner.infolist() if not member.is_dir()]
                        inner_by_name = {member.filename: member for member in members}
                        for member in members:
                            suffix = PurePosixPath(member.filename.replace("\\", "/")).suffix.lower() or "<none>"
                            nested_counts[suffix] += 1
                            nested_sizes[suffix] += member.file_size
                        nested_bins = sorted(name for name in inner_by_name if name.lower().endswith(".bin"))
                        nested_sos = sorted(name for name in inner_by_name if name.lower().endswith(".so"))
                        report["binTotalNested"] += len(nested_bins)
                        report["binBytesNested"] += sum(inner_by_name[name].file_size for name in nested_bins)
                        report["soNested"] += len(nested_sos)
                        report["soNames"].extend(f"{info.filename}!/{name}" for name in nested_sos)
                        for name in sample(nested_sos, SO_SAMPLE, SO_SAMPLE)[0]:
                            member = inner_by_name[name]
                            if member.file_size > SO_MAX_BYTES:
                                continue
                            budget["bytesRead"] += member.file_size
                            budget["librariesInspected"] += 1
                            report["sos"].append(describe_so(
                                f"{info.filename}!/{name}", inner.read(member)))
                        chosen, rule = sample(nested_bins, NESTED_BIN_SAMPLE, NESTED_SHALLOW_QUOTA)
                        report["nested"].append({"path": info.filename, "entries": len(members),
                                                 "binMembers": len(nested_bins),
                                                 "binSample": {"size": len(chosen), "rule": rule}})
                        for name in chosen:
                            member = inner_by_name[name]
                            if member.file_size > MEMBER_MAX_BYTES:
                                report["bins"].append({"path": f"{info.filename}!/{name}",
                                                       "bytes": member.file_size, "parsed": None,
                                                       "reader": f"not attempted: member exceeds "
                                                                 f"{MEMBER_MAX_BYTES} bytes"})
                                continue
                            budget["bytesRead"] += member.file_size
                            budget["binsInspected"] += 1
                            report["bins"].append(describe_bin(f"{info.filename}!/{name}",
                                                               inner.read(member)))
                finally:
                    scratch.unlink(missing_ok=True)
                    scratch.parent.rmdir()
            report["nestedByExtension"] = {suffix: {"files": nested_counts[suffix], "bytes": nested_sizes[suffix]}
                                           for suffix in sorted(nested_counts, key=lambda key: (-nested_counts[key], key))}

            chosen, rule = sample(outer_bins, BIN_SAMPLE, BIN_SHALLOW_QUOTA)
            report["binSample"] = {"size": len(chosen), "rule": rule, "paths": chosen}
            for name in chosen:
                info = outer[name]
                if info.flag_bits & 1:
                    report["bins"].append({"path": name, "bytes": info.file_size, "parsed": None,
                                           "reader": "ZIP-encrypted member, contents unavailable"})
                    continue
                if info.file_size > MEMBER_MAX_BYTES:
                    report["bins"].append({"path": name, "bytes": info.file_size, "parsed": None,
                                           "reader": f"not attempted: member exceeds {MEMBER_MAX_BYTES} bytes"})
                    continue
                budget["bytesRead"] += info.file_size
                budget["binsInspected"] += 1
                with archive.open(info) as handle:
                    report["bins"].append(describe_bin(name, handle.read(MEMBER_MAX_BYTES)))
            chosen, rule = sample(outer_sos, so_limit, so_limit)
            report["soSample"] = {"size": len(chosen), "rule": rule, "paths": chosen}
            for name in chosen:
                info = outer[name]
                if info.flag_bits & 1:
                    report["sos"].append({"path": name, "bytes": info.file_size,
                                          "architecture": "NOT ESTABLISHED", "exports": [], "needed": [],
                                          "exportCount": 0, "error": "ZIP-encrypted member"})
                    continue
                if info.file_size > SO_MAX_BYTES:
                    report["sos"].append({"path": name, "bytes": info.file_size, "architecture": "NOT ESTABLISHED",
                                          "exports": [], "needed": [], "exportCount": 0,
                                          "error": f"not attempted: member exceeds {SO_MAX_BYTES} bytes"})
                    continue
                budget["bytesRead"] += info.file_size
                budget["librariesInspected"] += 1
                with archive.open(info) as handle:
                    report["sos"].append(describe_so(name, handle.read(SO_MAX_BYTES)))
    except (zipfile.BadZipFile, ValueError, OSError) as error:
        report["error"] = f"{type(error).__name__}: {error}"
    return report


# ------------------------------------------------------------------- judgements

def reference_from(report: dict, hooked: list[str]) -> dict:
    exports: set[str] = set()
    libraries: set[str] = set()
    per_library: dict[str, list[str]] = {}
    for row in report["sos"]:
        libraries.add(PurePosixPath(row["path"].replace("!/", "/")).name)
        if row.get("elf"):
            exports.update(row["exports"])
            per_library[row["path"]] = row["exports"]
    names = sorted(set(hooked) & exports)
    return {"names": names, "libraryNames": sorted(libraries),
            "hookedButNotExported": sorted(set(hooked) - exports),
            "exportedNames": sorted(exports), "perLibrary": per_library,
            "architectures": sorted({row["architecture"] for row in report["sos"] if row.get("elf") and not row.get("error")})}


def annotate_sos(report: dict, reference: dict | None) -> None:
    """Attach the per-library oracle match/miss lists the report tables render."""
    names = list((reference or {}).get("names", []))
    for row in report["sos"]:
        if row.get("elf"):
            exports = set(row.get("exports", []))
            row["matchedOracleNames"] = [name for name in names if name in exports]
            row["missedOracleNames"] = [name for name in names if name not in exports]
        else:
            row["matchedOracleNames"] = []
            row["missedOracleNames"] = list(names)


def summarise(brand: str, report: dict, reference: dict | None, *, baseline_exports: int = 0,
              hooked: int = 0) -> dict:
    applicable = [row for row in report["bins"] if row.get("parsed") is not None]
    parsed = [row for row in applicable if row["parsed"]]
    elfs = [row for row in report["sos"] if row.get("elf") and not row.get("error")]
    names = list((reference or {}).get("names", []))
    matched = sorted({name for row in report["sos"] for name in row.get("matchedOracleNames", [])})
    library_names = {PurePosixPath(name.replace("!/", "/")).name for name in report.get("soNames", [])}
    shared_libraries = sorted(library_names & set((reference or {}).get("libraryNames", [])))
    if brand == PurePosixPath(BASELINE).stem:
        verdict = "same family as Toyota"
        reason = (f"the reference archive itself: it exports {baseline_exports} of the {hooked} names the "
                  f"Toyota oracle hooks, so {len(names)} form the comparison set")
    elif report.get("error"):
        verdict, reason = "NOT ESTABLISHED", f"archive could not be read: {report['error']}"
    elif matched:
        verdict = "NOT ESTABLISHED"
        reason = "symbol overlap alone does not establish decoded BIN compatibility; use inventory2.py"
    elif not report["sos"]:
        verdict, reason = "NOT ESTABLISHED", "no .so member was found, so there is no exported-name evidence"
    elif not elfs:
        verdict, reason = "NOT ESTABLISHED", (
            f"{len(report['sos'])} .so member(s) inspected but none is an ELF object with a readable "
            f"dynamic symbol table")
    else:
        verdict, reason = "NOT ESTABLISHED", (
            f"{len(elfs)} ELF librar{'y' if len(elfs) == 1 else 'ies'} parsed but exported no defined "
            f"function name")
    return {"brand": brand, "entries": report["entries"], "archiveBytes": report["archiveBytes"],
            "binTotal": report["binTotalOuter"] + report["binTotalNested"],
            "binBytes": report["binBytesOuter"] + report["binBytesNested"],
            "binSampleSize": len(report["bins"]), "binParseRate": (
                f"{len(parsed)}/{len(applicable)} applicable sampled" if applicable
                else "no .bin applicable to a Toyota reader"),
            "entropy": dict(Counter(row.get("entropyLabel", "not measured") for row in report["bins"])),
            "libraries": len(report["sos"]), "elfLibraries": len(elfs),
            "oracleMatches": matched, "oracleMisses": len(names) - len(matched),
            "sharedLibraryNames": shared_libraries, "verdict": verdict, "reason": reason}


# ------------------------------------------------------------------- reports

def extension_table(rows: dict, limit: int = 12) -> tuple[list[str], int]:
    lines, total = [], len(rows)
    for suffix, value in rows.items():
        lines.append(f"| `{suffix}` | {value['files']} | {value['bytes']} |")
    return lines, 0


def escape(text: str) -> str:
    return str(text).replace("|", "&#124;").replace("\n", " ")


def shorten(name: str, limit: int = 76) -> str:
    return name if len(name) <= limit else name[:limit - 3] + "..."


def brand_markdown(entry: dict, hooked_count: int) -> str:
    summary, report = entry["summary"], entry["report"]
    out = io.StringIO()
    out.write(f"\n## {summary['brand']}\n\n")
    out.write(f"- Archive: `{report['archive']}` — {report['archiveBytes']} bytes, {report['entries']} file(s), "
              f"{report['encrypted']} ZIP-encrypted member(s).\n")
    out.write(f"- `.bin` members: {report['binTotalOuter']} at this level, {report['binTotalNested']} inside nested "
              f"archives ({report['binBytesOuter']} + {report['binBytesNested']} bytes); {len(report['bins'])} "
              f"inspected.\n")
    if "binSample" in report:
        out.write(f"- `.bin` sample: {report['binSample']['rule']}.\n")
    out.write(f"- `.so` members: {report['soOuter']} at this level, {report['soNested']} nested; "
              f"{len(report['sos'])} inspected"
              + (f" — {report['soSample']['rule']}" if "soSample" in report else "") + ".\n")
    if report["nested"]:
        enumerated = [item for item in report["nested"] if item.get("enumerated")]
        out.write(f"- Nested archives: {len(report['nested'])} found, {len(enumerated)} enumerated "
                  f"(limit {NESTED_ZIP_COUNT} × {NESTED_ZIP_MAX_BYTES} bytes).\n")
    if report.get("error"):
        out.write(f"- **Archive error:** {report['error']}\n")
    out.write(f"- Verdict: **{summary['verdict']}** — {summary['reason']}.\n\n")
    for title, table in (("Extensions at this level", report["byExtension"]),
                         ("Extensions inside nested archives", report["nestedByExtension"])):
        if not table:
            continue
        lines, rest = extension_table(table)
        out.write(f"{title}:\n\n| extension | files | bytes |\n| --- | ---: | ---: |\n" + "\n".join(lines) + "\n")
        if rest:
            out.write(f"| _(and {rest} further extension(s), all in the JSON)_ | | |\n")
        out.write("\n")
    if report["bins"]:
        out.write("| .bin (sampled) | bytes | magic | entropy, first 4 KiB | Toyota reader | result |\n"
                  "| --- | ---: | --- | --- | --- | --- |\n")
        for row in report["bins"]:
            if row.get("parsed") is True:
                result = "parsed: " + json.dumps(row.get("readerResult"), sort_keys=True, separators=(",", ":"))
            elif row.get("parsed") is False:
                result = "error: " + row.get("readerError", "")
            else:
                result = row.get("reader", "")
            if row.get("yzjmWrapped"):
                result += "; YZJM-wrapped, needs this brand's own libDEVICEID table (not attempted)"
            out.write(f"| `{escape(shorten(row['path']))}` | {row['bytes']} | {escape(row.get('magic', ''))} | "
                      f"{row.get('entropyFirst4KiB', '')} ({row.get('entropyLabel', 'not measured')}) | "
                      f"{escape(row.get('reader', ''))} | {escape(result)} |\n")
    if report["sos"]:
        out.write("\n| .so | bytes | ELF arch | exports | DT_NEEDED | Toyota reference names exported |\n"
                  "| --- | ---: | --- | ---: | --- | --- |\n")
        for row in report["sos"]:
            arch = row.get("architecture", "NOT ESTABLISHED")
            if row.get("elf"):
                arch = f"{arch} {row.get('class', '')} {row.get('endianness', '')}".strip()
            names = ", ".join(f"`{name}`" for name in row.get("matchedOracleNames", [])) or "none"
            needed = ", ".join(f"`{library}`" for library in row.get("needed", [])) or "none"
            if row.get("error"):
                names = f"error: {escape(row['error'])}"
            out.write(f"| `{escape(shorten(row['path']))}` | {row['bytes']} | {escape(arch)} | "
                      f"{row.get('exportCount', 0)} | {escape(needed)} | {escape(names)} |\n")
        missing = sorted(set(report["sos"][0].get("missedOracleNames", [])) - set(summary["oracleMatches"]))
        out.write(f"\nReference names not exported by any inspected library of this brand: {len(missing)} of "
                  f"{hooked_count}")
        if missing and len(missing) != hooked_count:
            out.write(":\n\n```text\n" + "\n".join(", ".join(missing[index:index + 6])
                                                   for index in range(0, len(missing), 6)) + "\n```\n")
        else:
            out.write(" (the complete list is in the JSON).\n")
    return out.getvalue()


def markdown(state: dict) -> str:
    out = io.StringIO()
    hooked = state["hookedNames"]
    out.write("# Brand inventory\n\n")
    out.write(f"Head: `{state['headSha']}`  \nGenerated (UTC): {state['generatedUtc']}  \n")
    out.write("Tool: `tools/inventory/brand_inventory.py` — read-only; nothing decoded, loaded or enabled.\n\n")
    budget = state["budget"]
    out.write(f"- Release assets listed: {state['assets']['total']} across {state['assets']['releases']} release(s).\n")
    out.write(f"- Brand archives: {len(state['brandOrder'])}; processed: {len(state['brands'])}; "
              f"not reached: {len(state['pending'])} (processed count includes Toyota).\n")
    out.write(f"- Elapsed: {budget['elapsedSeconds']}s of a {budget['maxSeconds']}s tool budget; "
              f"member bytes read: {budget['bytesRead']}.\n")
    out.write(f"- `.bin` members inspected: {budget['binsInspected']}; ELF members inspected: "
              f"{budget['librariesInspected']}.\n")
    out.write("- Symbol overlap alone cannot establish compatibility; use the decoded control and readers in inventory2.py.\n")
    out.write(f"- Reference: the {len(hooked)} source-derived candidate names, restricted to "
              f"the {len(state['reference']['names'])} that `{BASELINE}` itself exports. `{BASELINE}` is processed "
              f"through this same code path first.\n\n")
    out.write("## Summary\n\n| brand | files | size (bytes) | .bin parse rate | .so name overlap with Toyota | verdict | reason |\n"
              "| --- | ---: | ---: | --- | --- | --- | --- |\n")
    for row in state["summary"]:
        overlap = (f"{len(row['oracleMatches'])}/{len(state['reference']['names'])} names"
                   + (f"; {len(row['sharedLibraryNames'])} library filename(s)"
                      if row["sharedLibraryNames"] else ""))
        out.write(f"| {row['brand']} | {row['entries']} | {row['archiveBytes']} | {escape(row['binParseRate'])} | "
                  f"{escape(overlap)} | **{row['verdict']}** | {escape(row['reason'])} |\n")
    for brand in state["pending"]:
        out.write(f"| {PurePosixPath(brand).stem} | — | — | — | — | **NOT ESTABLISHED** | "
                  f"not reached inside the run budget |\n")
    out.write("\n## Release asset listing\n\nEvery asset of every release (`gh api .../releases`, then each release's "
              "assets); no release name is assumed.\n\n| release | asset | bytes | kind |\n| --- | --- | ---: | --- |\n")
    for asset in state["assets"]["listing"]:
        out.write(f"| {asset['tag']} | `{asset['name']}` | {asset['bytes']} | {asset['kind']} |\n")
    out.write(f"\n## Reference names\n\n`{BASELINE}` exports {len(state['reference']['names'])} of the "
              f"{len(hooked)} identifiers the Toyota oracle in `tools/thinkdiag` calls, binds or compares. "
              f"These are the names each brand is matched against:\n\n```text\n")
    names = state["reference"]["names"]
    out.write("\n".join(", ".join(names[index:index + 6]) for index in range(0, len(names), 6)))
    out.write("\n```\n")
    for entry in state["brands"]:
        out.write(brand_markdown(entry, len(names)))
    return out.getvalue()


def write_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    scratch = path.with_suffix(path.suffix + ".tmp")
    scratch.write_text(text, encoding="utf-8")
    scratch.replace(path)


def chunk(text: str) -> list[str]:
    parts, lines, size = [], [], 0
    for line in text.splitlines(keepends=True):
        for start in range(0, max(1, len(line)), 6000):
            piece = line[start:start + 6000]
            width = len(piece.encode("utf-8"))
            if lines and size + width > CHECK_PART_BYTES:
                parts.append("".join(lines))
                lines, size = [], 0
            lines.append(piece)
            size += width
    if lines or not parts:
        parts.append("".join(lines))
    if len(parts) > CHECK_PARTS_MAX:
        raise ValueError(f"Inventory report needs {len(parts)} check parts (limit {CHECK_PARTS_MAX})")
    return parts


def emit(state: dict, output: Path) -> dict:
    text = markdown(state)
    write_atomic(output / "brand-inventory.md", text)
    write_atomic(output / "brand-inventory.json",
                 json.dumps(state, indent=1, sort_keys=True, ensure_ascii=True) + "\n")
    parts = chunk(text)
    for stale in sorted(output.glob("brand-inventory-[0-9][0-9][0-9].md")):
        stale.unlink()
    manifest = []
    for index, body in enumerate(parts, 1):
        name = "Brand inventory" if index == 1 else f"Brand inventory (part {index}/{len(parts)})"
        path = output / f"brand-inventory-{index:03d}.md"
        write_atomic(path, body)
        manifest.append({"name": name, "file": str(path), "bytes": len(body.encode("utf-8"))})
    if manifest and max(item["bytes"] for item in manifest) > 60000:
        raise ValueError("A check part exceeds the 60,000-byte Checks API field limit")
    conclusion = "success"
    if state["failed"] or not state["brands"]:
        conclusion = "failure"
    elif state["pending"] or any(entry["report"].get("error") for entry in state["brands"]):
        conclusion = "neutral"
    write_atomic(output / "parts.json", json.dumps(manifest, indent=1) + "\n")
    write_atomic(output / "conclusion.txt", conclusion + "\n")
    return {"parts": manifest, "bytes": len(text.encode("utf-8")), "conclusion": conclusion}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path, default=Path(".local/inventory/downloads"))
    parser.add_argument("--max-seconds", type=int, default=1800)
    parser.add_argument("--brands", default="", help="optional comma-separated asset subset for a manual run")
    args = parser.parse_args()
    if not args.repo:
        raise SystemExit("STOP: GH_REPO (or --repo) is required to list the release assets")
    started = time.monotonic()
    args.output.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    assets = release_assets(args.repo)
    baseline, brands = classify(assets)
    if baseline is None:
        raise SystemExit(f"STOP: no {BASELINE} asset is published, so no Toyota reference can be established")
    if not brands:
        raise SystemExit("STOP: no non-Toyota .zip asset is published; only the listing can be reported")
    wanted = {name.strip() for name in args.brands.split(",") if name.strip()}
    if wanted:
        brands = [asset for asset in brands if asset["name"] in wanted]
        if not brands:
            raise SystemExit("STOP: the requested brand subset matches no published .zip asset")
    listing = []
    for asset in assets:
        if asset is baseline:
            kind = "baseline"
        elif any(asset is item for item in brands):
            kind = "brand zip"
        elif OWN_ASSET.match(asset["name"]):
            kind = "this repo's own asset"
        else:
            kind = "other (not a .zip brand archive)"
        listing.append({"tag": asset["tag"], "name": asset["name"], "bytes": asset["bytes"], "kind": kind})
    hooked = hooked_names()
    state = {"format": FORMAT, "formatVersion": FORMAT_VERSION, "generatedUtc": utc_now(),
             "headSha": os.environ.get("GITHUB_SHA", "NOT ESTABLISHED"),
             "assets": {"total": len(assets), "releases": len({asset["tag"] for asset in assets}),
                        "listing": listing},
             "brandOrder": [asset["name"] for asset in brands],
             "brands": [], "summary": [], "pending": [asset["name"] for asset in brands], "failed": False,
             "hookedNames": hooked,
             "reference": {"names": [], "libraryNames": [], "hookedButNotExported": []},
             "budget": {"maxSeconds": args.max_seconds, "elapsedSeconds": 0, "bytesRead": 0,
                        "binsInspected": 0, "librariesInspected": 0},
             "verdictValues": list(VERDICTS)}
    reference = None
    emit(state, args.output)
    try:
        for asset in [baseline] + brands:
            if asset is not baseline and time.monotonic() - started > args.max_seconds:
                break
            path = download(asset, args.work)
            report = walk(path, state["budget"], SO_ALL_LIMIT if asset is baseline else SO_SAMPLE)
            brand = PurePosixPath(asset["name"]).stem
            if asset is baseline:
                if report.get("error"):
                    raise ValueError(f"Toyota reference could not be read: {report['error']}")
                reference = reference_from(report, hooked)
                if not reference["names"]:
                    raise ValueError("Toyota reference exports none of the oracle names; comparison unavailable")
                state["reference"] = {"names": reference["names"],
                                      "libraryNames": reference["libraryNames"],
                                      "hookedButNotExported": reference["hookedButNotExported"]}
                annotate_sos(report, reference)
                summary = summarise(brand, report, reference, baseline_exports=len(reference["names"]),
                                    hooked=len(hooked))
            else:
                annotate_sos(report, reference)
                summary = summarise(brand, report, reference)
            state["brands"].append({"summary": summary, "report": report})
            state["summary"] = [entry["summary"] for entry in state["brands"]]
            state["pending"] = [name for name in state["pending"] if name != asset["name"]]
            state["budget"]["elapsedSeconds"] = round(time.monotonic() - started)
            emit(state, args.output)
            path.unlink()
            if path.exists():
                raise SystemExit(f"STOP: {path} still exists after processing")
            print(f"[{state['budget']['elapsedSeconds']}s] {brand}: {summary['verdict']} "
                  f"({summary['entries']} files, {len(summary['oracleMatches'])} reference names)", flush=True)
    except (subprocess.CalledProcessError, ValueError, OSError) as error:
        state["failed"] = True
        state["error"] = f"{type(error).__name__}: {error}"
        state["budget"]["elapsedSeconds"] = round(time.monotonic() - started)
        print(f"inventory stopped: {state['error']}", file=sys.stderr, flush=True)
        try:
            emit(state, args.output)
        except ValueError as emit_error:
            print(f"partial report could not be emitted: {emit_error}", file=sys.stderr, flush=True)
        return 1
    state["budget"]["elapsedSeconds"] = round(time.monotonic() - started)
    result = emit(state, args.output)
    print(f"Inventory complete: {len(state['brands'])} archive(s), {result['bytes']} report bytes, "
          f"{len(result['parts'])} check part(s), conclusion {result['conclusion']}", flush=True)
    if state["pending"]:
        print(f"Not reached inside the budget: {', '.join(state['pending'])}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
