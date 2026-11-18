#!/usr/bin/env python3
"""Exhaustive, data-only Toyota diagnostic-table survey (never runs vendor code).

Reuses the SHA-pinned YZJM/GGP recovery, then inventories EVERY decoded table,
validates known directories, cross-checks source fields and surveys all English
libraries and native exports. Output belongs in ignored .local/, not the APK.
A recovered table or label is not automatically an executable app definition.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

from analyze_toyota import TOYOTA_SHA256, checked_infos, native_info
from static_readers import reader_report
from toyota_structure import function_index, section_directory, system_fields, system_lists, uint

FOCUSED_READERS = {
    "libTOYOTA_FILE.so": ["GetAllToyotaTblData", "GetToyotaTblData", "GetInfoFromAddrPart",
        "GetSystemEnterInfoData", "GetSysFuncInfoData", "GetRDtcDataFromRDTCPart",
        "GetDsSubMenuInfoData", "GetDsSubMenuItemList", "GetSFDataFromSFList", "GetCustomizeFuncData"],
    "libTOYOTA_FDATA.so": ["AnalyzeDsCmdDataP3", "GetDSCmdDataInfoP3", "GetDSCmdDataInfoP4",
        "GetExistDataIdList", "GetDsValue", "GetActCmdDataInfoP3", "GetActCmdDataInfoP4",
        "SetCmdToSndFrameFunc_1", "SetCmdToSndFrameFunc_2", "SetCmdToSndFrameFunc_3"],
    "libDIAG.so": ["InitMenuTree", "MenuTreeDispose", "GetCurrentMenuData", "GetMenuSubItemSaveData"],
}
SEARCHES = {
    "innova": r"\binnova\b|\ban40\b|\ban140\b|\bag10\b",
    "engines": r"\b(?:1|2)(?:kd|gd|tr)[- ]?(?:ftv|fe)?\b",
    "live_values": r"engine speed|coolant temperature|fuel rail pressure|injection volume|battery voltage",
    "actuators": r"active test|actuator|injector cut|fuel pump|solenoid",
    "coding_customization": r"customi[sz]|coding|configuration|learning|calibration",
    "protocols": r"\bkwp|\bcan\b|iso.?9141|iso.?14230|iso.?15765|baud",
}


def json_text(value) -> str:
    # JSON escapes embedded NUL/control bytes; do not publish check-rendered text
    # as a substitute for the recovered binary or full UTF-8 JSON source.
    return json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def publishable_report(directory: Path, name: str, title: str, text: str) -> list[str]:
    """Chunk at lines under Checks API's UTF-8 limit, never silently truncate."""
    directory.mkdir(parents=True, exist_ok=True)
    parts, lines, size = [], [], 0
    for line in text.splitlines(keepends=True):
        # ASCII-escaped JSON and disassembly have short lines; handle arbitrary
        # long Unicode lines too without losing a single character.
        for start in range(0, max(1, len(line)), 6000):
            piece = line[start:start + 6000]
            encoded_size = len(piece.encode("utf-8"))
            if lines and size + encoded_size > 52000:
                parts.append("".join(lines)); lines, size = [], 0
            lines.append(piece); size += encoded_size
    if lines or not parts:
        parts.append("".join(lines))
    paths = []
    for index, body in enumerate(parts, 1):
        suffix = f"-{index:03d}" if len(parts) > 1 else ""
        path = directory / f"sweep-{name}{suffix}.md"
        path.write_text(f"# {title}\n\nPart {index}/{len(parts)}; research only, not runnable definitions.\n\n{body}", encoding="utf-8")
        if path.stat().st_size > 60000:
            raise ValueError("Report exceeds Checks API budget")
        paths.append(path.name)
    return paths


def safe_source(root: Path, relative: str) -> Path:
    name = PurePosixPath(relative)
    if name.is_absolute() or not name.parts or ".." in name.parts or "\\" in relative:
        raise ValueError("Unsafe decoded manifest path")
    result = root.joinpath(*name.parts)
    if result.is_symlink() or root.resolve() not in result.resolve().parents:
        raise ValueError("Decoded manifest path escapes output root")
    return result


def sweep(archive_path: Path, decoded: Path, english: Path, manifest_path: Path,
          output: Path, report_dir: Path):
    source_hash = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    if source_hash != TOYOTA_SHA256:
        raise ValueError("Unexpected Toyota archive SHA-256")
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, list) or len(manifest) > 10000:
        raise ValueError("Invalid decoded manifest")
    seen = set()
    paths = {}
    for item in manifest:
        name = item["file"]
        if name in seen:
            raise ValueError("Duplicate decoded manifest path")
        seen.add(name)
        path = safe_source(decoded, name)
        data = path.read_bytes()
        if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"Decoded manifest hash mismatch: {name}")
        paths[name] = path
    libraries = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in sorted(english.glob("*.GAG.json"))}
    if not libraries or any(not isinstance(v, dict) for v in libraries.values()):
        raise ValueError("Missing or invalid recovered text libraries")
    text = libraries.get("TEXT_EN.GAG.json", {})
    findings = {"sourceSha256": source_hash, "decodedFiles": len(manifest),
                "decodedBytes": sum(i["bytes"] for i in manifest), "families": {},
                "limitations": ["Structure/label recovery is not operational diagnostic coverage.",
                    "No vendor library/EXPRESS content is executed; no requests are enabled in bod3.",
                    "Opaque fields stay named by source offset; pattern matches are not diagnostic definitions."]}
    all_files = []
    native_census = []
    with zipfile.ZipFile(archive_path) as archive:
        for info in checked_infos(archive):
            if info.is_dir():
                continue
            name = PurePosixPath(info.filename).name
            data = archive.read(info)
            all_files.append({"path": info.filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                              "type": "ELF" if data.startswith(b"\x7fELF") else name.rsplit(".", 1)[-1]})
            if name.endswith(".so") and data.startswith(b"\x7fELF"):
                native = native_info(data, name)
                exports = [f for f in native["functions"] if f["defined"]]
                native_census.append({"library": name, "bytes": len(data), "machine": native["machine"],
                                      "exports": exports, "needed": native["needed"]})
                for function in FOCUSED_READERS.get(name, []):
                    report = reader_report(data, name, [function]).split("## Matching symbol names", 1)[0]
                    publishable_report(report_dir, f"reader-{name}-{function}", f"{name}: {function}", report)
            if name == "BIN.ZIP":
                import io
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    for member in checked_infos(nested):
                        if not member.is_dir():
                            all_files.append({"path": info.filename + "!/" + member.filename, "bytes": member.file_size,
                                              "type": "nested BIN" if member.filename.endswith(".BIN") else "nested other"})
    (output / "archive-inventory.json").write_text(json_text(all_files))
    (output / "native-exports.json").write_text(json_text(native_census))
    findings["archiveEntriesIncludingNestedFiles"] = len(all_files)
    findings["nativeLibraries"] = len(native_census)
    for native in native_census:
        publishable_report(report_dir, "exports-" + native["library"], native["library"] + " full export census", json_text(native))

    section_counts, header_shapes, unparsed = Counter(), Counter(), []
    section_files = []
    samples = []
    section_records = output / "ecu-sections.jsonl"
    with section_records.open("w", encoding="utf-8") as rows:
        for name, path in sorted(paths.items()):
            if not re.fullmatch(r"(?:EU|JP|NA)/Ecu_Data_\d+\.BIN", name):
                continue
            data = path.read_bytes()
            try:
                sections = section_directory(data)
                section_files.append(name)
                for section in sections:
                    block = data[section.offset:section.end]
                    item = {"file": name, "section": section.identity, "offset": section.offset,
                            "spanBytes": len(block), "sha256": hashlib.sha256(block).hexdigest(),
                            "prefixHex": block[:48].hex(" ")}
                    if len(block) >= 10:
                        item["opaqueHeader"] = {"u16At0": uint(block, 0, 2), "u32At2": uint(block, 2, 4), "u32At6": uint(block, 6, 4)}
                        header_shapes[(section.identity, uint(block, 0, 2) == section.identity)] += 1
                    section_counts[section.identity] += 1
                    rows.write(json.dumps(item, sort_keys=True) + "\n")
                if name.endswith("Ecu_Data_0001.BIN"):
                    samples.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(),
                                    "bytes": len(data), "base64": base64.b64encode(data).decode("ascii")})
            except ValueError as error:
                unparsed.append({"file": name, "error": str(error)})
    findings["families"]["ecuTables"] = {"parsedDirectories": len(section_files), "unparsed": unparsed,
        "sectionsById": dict(sorted(section_counts.items())),
        "headerTagComparisons": [{"section": key[0], "matchesDirectoryTag": key[1], "count": value} for key, value in sorted(header_shapes.items())]}
    # These three small source excerpts let an offline reviewer reproduce the
    # structural work without committing vendor data or executable libraries.
    (output / "structural-excerpts.json").write_text(json_text(samples))
    publishable_report(report_dir, "structural-excerpts", "SHA-pinned ECU structural excerpts", json_text(samples))

    function_tables = {}
    for name, path in sorted(paths.items()):
        if re.fullmatch(r"FUNCDATA_(EU|JP|NA)\.BIN", name):
            try:
                parsed, end = function_index(path.read_bytes())
                function_tables[name] = {row[0]: row for row in parsed}
                findings["families"][name] = {"indexRows": len(parsed), "indexEnd": end, "examples": parsed[:5]}
                (output / (name + ".index.json")).write_text(json_text(parsed))
            except ValueError as error:
                findings["families"][name] = {"unparsed": str(error)}

    for name, path in sorted(paths.items()):
        match = re.fullmatch(r"SYSLIST_(EU|JP|NA)\.BIN", name)
        if not match:
            continue
        try:
            data = path.read_bytes()
            index, blocks = system_lists(data)
            stats, lookups, examples, field0s = Counter(), Counter(), [], set()
            functions = function_tables.get(f"FUNCDATA_{match[1]}.BIN", {})
            with (output / (name + ".records.jsonl")).open("w", encoding="utf-8") as dest:
                for offset, records in sorted(blocks.items()):
                    for row_number, record in enumerate(records):
                        fields = system_fields(record)
                        field0s.add(fields["u32"]["0"])
                        labels = {key: text[f"{value:08X}"] for key, value in fields["u32"].items() if f"{value:08X}" in text}
                        lookups.update(labels.keys())
                        stats["records"] += 1
                        stats["word0ExistsInFunctionIndex"] += fields["u32"]["0"] in functions
                        item = {"file": name, "offset": offset + 2 + 37 * row_number, "rawHex": record.hex(" "),
                                "fields": fields, "textMatchesByFieldOffset": labels}
                        dest.write(json.dumps(item, ensure_ascii=True, sort_keys=True) + "\n")
                        if len(examples) < 8:
                            examples.append(item)
            findings["families"][name] = {"vehicleIndexRows": len(index), "uniqueBlocks": len(blocks),
                "uniqueWord0Values": len(field0s), "counts": dict(stats), "textMatchesByWordOffset": dict(lookups),
                "examples": examples, "indexExamples": index[:5]}
            (output / (name + ".index.json")).write_text(json_text(index))
        except ValueError as error:
            findings["families"][name] = {"unparsed": str(error)}

    census, hits = [], []
    for name, records in libraries.items():
        totals = {}
        for category, regex in SEARCHES.items():
            matches = [(key, value) for key, value in records.items() if re.search(regex, value, re.I)]
            totals[category] = len(matches)
            hits.extend({"library": name, "category": category, "id": key, "text": value[:1200],
                         "fullTextCharacters": len(value)} for key, value in matches[:12])
        census.append({"library": name, "records": len(records), "keywordMatches": totals,
                       "examples": [{"id": key, "text": value[:500]} for key, value in list(records.items())[:4]]})
    findings["textLibraryCensus"] = census
    # Explicitly list every remaining top-level table, not just attractive
    # samples. Absence of a parser is reported as an open format, never absence
    # of diagnostics in the vendor folder.
    findings["notStructurallyInterpreted"] = [{"file": name, "bytes": p.stat().st_size} for name, p in sorted(paths.items())
        if name not in section_files and name not in function_tables and not re.fullmatch(r"SYSLIST_(EU|JP|NA)\.BIN", name)]
    (output / "text-keyword-evidence.json").write_text(json_text(hits))
    (output / "sweep-summary.json").write_text(json_text(findings))
    publishable_report(report_dir, "summary", "Toyota full diagnostic-data sweep", json_text(findings))
    publishable_report(report_dir, "text-evidence", "Toyota non-DTC text evidence (not request definitions)", json_text(hits))
    print(f"Swept {len(all_files)} archive/nested entries, {len(manifest)} decoded files, {len(native_census)} ELF libraries and {len(libraries)} text libraries.")
    print(f"Validated {len(section_files)} ECU section directories; {len(unparsed)} unparsed. Detailed findings: {output / 'sweep-summary.json'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--english", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    sweep(args.archive, args.decoded, args.english, args.manifest, args.output, args.report)
