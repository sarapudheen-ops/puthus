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
from collections import Counter, deque
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile
import zlib

from analyze_toyota import TOYOTA_SHA256, checked_infos, native_info
from static_readers import data_report, reader_report
from toyota_live import review_direct21
from toyota_structure import (ECU_FILE, audit_command_pool, frame_pool_references, function_index, menu_nodes, section_directory,
                              system_fields, system_lists, typed_table, uint)

FOCUSED_READERS = {
    "libTOYOTA_FILE.so": ["GetVehicleInfoData", "GetSystemListData", "GetAllToyotaTblData", "GetToyotaTblData", "GetInfoFromAddrPart",
        "GetSystemEnterInfoData", "GetInfoFromEnterList", "GetSysFuncInfoData", "GetRDtcDataFromRDTCPart",
        "GetDsSubMenuInfoData", "GetDsSubMenuItemList", "GetSFDataFromSFList", "GetCustomizeFuncData",
        "GetEcuTblData", "GetDsDataFromDSPart", "GetActDataFromACTPart", "GetCDtcDataFromCDTCPart",
        "GetSFDataFromSFPart", "GetRoBDataFromRoBPart", "GetCustItemInfoData"],
    "libTOYOTA_FDATA.so": ["AnalyzeDsCmdDataP3", "GetDSCmdDataInfoP3", "GetDSCmdDataInfoP4",
        "GetExistDataIdList", "GetDsValue", "GetActCmdDataInfoP3", "GetActCmdDataInfoP4",
        "SetCmdToSndFrameFunc_1", "SetCmdToSndFrameFunc_2", "SetCmdToSndFrameFunc_3",
        "GetShowDsItem", "GetShowDsItem_P4", "GetShowDsItem_P5", "GetCmdDataIndex", "GetSystemEnterIndex",
        "GetEcuDataP4", "CalDataFromSignalLSB", "GetDIDReceiveData", "AnalyzeDataFunc_1", "AnalyzeDataFunc_2",
        "GetDataMonitorListP3", "GetDataMonitorListP4", "GetDataMonitorListP5", "GetEnablePidListP3",
        "GetDSCmdDataInfoP3_21", "SendCmdAndGetPid", "SendCmdGetSupid", "GetDtcListInfo_P3P4", "GetDtcListInfo_P5", "ChangeSignalLSB", "CheckSystemEnter",
        "PidCheckSupportP3", "PidCheckSupportP4", "PidCheckSupportBitP3", "PidCheckSupportBitP4",
        "GetEnablePidListP4", "DidCheckSupportBitP3", "DidCheckSupportBitP4", "AnalyzeDsCmdDataP3_21",
        "GetJudgeByteDataPosByA8", "GetDataBySevice", "AnalyzeSingleDataId", "AnalyzeRecData", "CheckCurrentDataSupport",
        "CheckCurrentDataBitSupport", "GetCurrentDataSupportRid", "GetEnableDidList", "GetEcuAddIDInformation"],
    "libTOYOTA_CDSF.so": ["ReadDStreamFunc", "InitDStreamAndShowSelectItem", "InitDStreamAndShowSelectItemEx", "ShowSelectedStreamAttribute_NoSubItem",
        "PackToTrueSendCmd", "DecodeRecvDataFromPackData", "SendSingleCmdShowDsData_NoSbuItem", "SendAllCmdShowDsData_NoSubItem"],
    "libTOYOTA_SYSP.so": ["SystemEnterPart", "SystemEnterAndExit", "FastKWPEnterMode", "ISOEnterMode", "CANBUSEnterMode", "SystemSetLinkKeepFunc"],
    "libTOYOTA_DTCF.so": ["ReadDtcModeP3P4", "SendCmdAndGetDtcP3P4", "SendCmdInitialDtc", "GetDTCCodeFromAnsP3P4", "GetDTCAskCmdResult"],
    "libCOMM_ABSTRACT_LAYER.so": ["SetEcuCommTimeInterval", "SetCommunicationMaximumWaitTime", "SetCommunicationParameter", "SetCommunicationLineVoltage", "ToyotaKwpSendDataToEcuGetMultiFrameAnswer",
        "CanSetParameterNormal", "CanSetParameter", "CanSetEnterFrame", "SetCanBitRate", "SetCanFilterId"],
    "libTOYOTA_ACTF.so": ["InitCmdStartAct", "ActTestProcess_P4", "ActTestProcess_P5", "GetRoutinActInfo", "P5_GetActInfoFromLineData"],
    "libTOYOTA_SCUST.so": ["CustomizeFuncProcess", "SetCustItemNormal", "SendCustItemCmdNormal", "SetCustItemProcess"],
    "libTOYOTA_ECUF.so": ["GetEcuCalitrationID", "GetEcuCalitrationID_P321", "GetEcuCalitrationID_P39C", "GetEcuCalitrationID_CAN"],
    "libTOYOTA_COMM.so": ["GetCmdDataInfo", "GetCommFrameInfo", "GetFuncCommFrameInfo", "GetFuncCommFrameInfo_MultiCmd", "InitCommSet_Ex", "InitSetEnterFrame", "SendCmdAndGetReceive", "SendCmdAndGetReceive_EX", "SendCmdAndGetReceive_GN", "InitKWPISOMode", "InitCanBusMode", "MenuEnterCommunicationSet", "GetKwpRecDataWithFrame", "GetKwpRecData", "GetBaseCanBusRecData",
        "MultiPackFunc", "SetCanComPara", "SetCanComParaFilterId", "InitExtendCanbusMode", "ConvertUint4ToBuffer",
        "My_KwpSendDataToEcuGetAnswer", "My_IsoSendDataToEcuGetAnswer", "MyCanSendOneFrameReceiveDatas",
        "CHK_ADDR_CODE", "CHK_CAN_BUS_iProtect", "InitCanCommSet_MPT", "SetCan15765", "InitAddressMode", "InitCommSet", "MultSetLinkKeepFunc", "MultSetMoreLinkKeepFunc"],
    "libTOYOTA_CALC.so": ["ResultCalcFromCalcID", "ResultCalcFromScript", "Calc_C", "CaculateBit", "@0x1032c:0x1ab8", "@0x11de4:0x830"],
    "libDIAG.so": ["InitMenuTree", "MenuTreeDispose_Ex", "GetMenuSubItemTextID_Ex",
        "GetMenuSubItemSaveData_Ex", "GetMenuSubItemFirstAddr_Ex", "GetCurrentMenuTitleID",
        "GetMenuID_Ex", "GetCurrentMenuSaveData", "MenuPro_Ex", "GetVehicleInfoManual", "DoDownTask", "GTokenMoveToSToken", "ManualSelectSystem", "DiagnoseSystem", "VehicleSystemScan", "VehicleSystemScanEx", "GetVehicleSystemBaseInfo", "GetCurrentMenuData", "MakeDataToBuffer_Ex"],
    "libTOYOTA_INFO.so": ["InitVehicleInfo"],
    "libTOYOTA_SCAN.so": ["GetSysListNo", "TransferVehicleInfo", "InitTopSystemList"],
}
# These bounded non-code tables resolve CALC operator IDs/precedence. File bytes
# only, never a loaded library or a general vendor-language execution engine.
FOCUSED_DATA = {
    "libTOYOTA_CALC.so": ["opchTbl", "@0x20cac:0x5c", "@0x20d08:0x5c", "@0x20980:0x50"],
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
    # Static address-range selectors contain ':' and '@'. Preserve them verbatim in
    # the report title/body, but use portable manifest/artifact names (also on NTFS).
    # The suffix prevents two distinct selectors with the same sanitized stem from
    # overwriting one another. Ordinary, already-safe report names stay stable.
    safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
    if safe_name != name:
        safe_name += "-" + hashlib.sha256(name.encode("utf-8")).hexdigest()[:16]
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
        path = directory / f"sweep-{safe_name}{suffix}.md"
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
    generated_reports = []

    def emit_report(name, title, text):
        generated_reports.extend(publishable_report(report_dir, name, title, text))

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
    calc_evidence = None
    opaque_natives, top_level_data = [], []
    with zipfile.ZipFile(archive_path) as archive:
        for info in checked_infos(archive):
            if info.is_dir():
                continue
            name = PurePosixPath(info.filename).name
            data = archive.read(info)
            all_files.append({"path": info.filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                              "type": "ELF" if data.startswith(b"\x7fELF") else name.rsplit(".", 1)[-1]})
            if name.endswith((".BIN", ".GGP", ".GAG", ".DEV")):
                top_level_data.append({"file": info.filename, "bytes": len(data), "prefixHex": data[:32].hex(" ")})
            if name.endswith(".so") and not data.startswith(b"\x7fELF"):
                opaque_natives.append({"file": info.filename, "bytes": len(data), "prefixHex": data[:32].hex(" ")})
            if name.endswith(".so") and data.startswith(b"\x7fELF"):
                native = native_info(data, name)
                exports = [f for f in native["functions"] if f["defined"]]
                native_census.append({"library": name, "bytes": len(data), "machine": native["machine"],
                                      "exports": exports, "needed": native["needed"]})
                for function in FOCUSED_READERS.get(name, []):
                    report = reader_report(data, name, [function], include_symbols=False, max_output_chars=None)
                    emit_report(f"reader-{name}-{function}", f"{name}: {function}", report)
                if name in FOCUSED_DATA:
                    tables = data_report(data, name, FOCUSED_DATA[name])
                    emit_report(f"data-{name}", f"{name}: bounded static data tables", tables)
                    if name == "libTOYOTA_CALC.so":
                        calc_evidence = json.loads(tables)
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
    findings["archiveTypes"] = dict(Counter(f["type"] for f in all_files))
    findings["nativeLibraries"] = len(native_census)
    findings["nonElfNativeFiles"] = opaque_natives
    findings["topLevelDataFiles"] = top_level_data
    recovery = json.loads((report_dir / "english-text.json").read_text())
    findings["englishRecovery"] = {"indexedRecords": sum(i.get("records", 0) for i in recovery),
        "utf8Records": sum(i.get("utf8Records", 0) for i in recovery),
        "nonUtf8RecordsRetainedInRawMembers": sum(i.get("records", 0) - i.get("utf8Records", 0) for i in recovery)}
    for native in native_census:
        emit_report("exports-" + native["library"], native["library"] + " full export census", json_text(native))

    section_counts, header_shapes, unparsed = Counter(), Counter(), []
    section_strides, section_row_totals, typed_errors = Counter(), Counter(), []
    section_files = []
    samples, p5_sampled = [], set()
    section_records = output / "ecu-sections.jsonl"
    with section_records.open("w", encoding="utf-8") as rows:
        for name, path in sorted(paths.items()):
            if not re.fullmatch(ECU_FILE, name):
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
                        header_shapes[(section.identity, uint(block, 0, 2))] += 1
                    try:
                        # A directory slot and a table's embedded schema tag
                        # are different namespaces (notably P5 sections >= 15).
                        table = typed_table(block)
                        item["table"] = {"rows": table.count, "payloadBytes": len(table.body),
                                         "stride": table.stride, "paddingBytes": len(table.padding)}
                        section_strides[(section.identity, table.stride or 0)] += 1
                        section_row_totals[section.identity] += table.count
                    except ValueError as error:
                        typed_errors.append({"file": name, "section": section.identity, "error": str(error)})
                    section_counts[section.identity] += 1
                    rows.write(json.dumps(item, sort_keys=True) + "\n")
                region = name.split("/")[0]
                p5_sample = region not in p5_sampled and any(s.identity == 23 for s in sections) and len(data) < 128000
                if name.endswith("Ecu_Data_0001.BIN") or p5_sample:
                    samples.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(),
                                    "bytes": len(data), "base64": base64.b64encode(data).decode("ascii")})
                    if p5_sample:
                        p5_sampled.add(region)
            except ValueError as error:
                unparsed.append({"file": name, "error": str(error)})
    findings["families"]["ecuTables"] = {"candidates": sum(bool(re.fullmatch(ECU_FILE, name)) for name in paths),
        "parsedDirectories": len(section_files), "unparsed": unparsed,
        "sectionsById": dict(sorted(section_counts.items())), "rowsBySection": dict(sorted(section_row_totals.items())),
        "typedTableErrors": typed_errors, "strides": [{"section": k[0], "stride": k[1], "tables": v} for k, v in sorted(section_strides.items())],
        "embeddedSchemaTags": [{"directorySection": key[0], "embeddedTag": key[1], "count": value}
                               for key, value in sorted(header_shapes.items())]}
    if "SYSTEMENTER.BIN" in paths and paths["SYSTEMENTER.BIN"].stat().st_size < 128000:
        data = paths["SYSTEMENTER.BIN"].read_bytes()
        samples.append({"file": "SYSTEMENTER.BIN", "sha256": hashlib.sha256(data).hexdigest(),
                        "bytes": len(data), "base64": base64.b64encode(data).decode("ascii")})
    # A few bounded legacy/P5/entry-table excerpts let an offline reviewer reproduce
    # the structural work without committing vendor data or executable libraries.
    (output / "structural-excerpts.json").write_text(json_text(samples))
    emit_report("structural-excerpts", "SHA-pinned ECU structural excerpts", json_text(samples))

    parsed_other = set()
    shared_tables, pools, pool_samples = [], [], []
    for name, path in sorted(paths.items()):
        match = re.fullmatch(r"(?:EU|JP|NA)/(?:Toyota|Viewer)_bin_([0-9a-fA-F]+)\.BIN", name)
        if not match:
            continue
        data = path.read_bytes()
        item = {"file": name, "prefixHex": data[:64].hex(" "),
                "headerValues": {"tag": uint(data, 0, 2), "count": uint(data, 2, 4), "payloadBytes": uint(data, 6, 4)}}
        try:
            table = typed_table(data)
            item.update({"tag": table.identity, "rows": table.count, "payloadBytes": len(table.body),
                         "stride": table.stride, "paddingBytes": len(table.padding),
                         "firstRowsHex": [table.body[i * table.stride:(i + 1) * table.stride].hex(" ")
                                          for i in range(min(table.count, 3))] if table.stride and table.stride <= 256 else []})
            parsed_other.add(name)
        except ValueError as error:
            item["unparsed"] = str(error)
        shared_tables.append(item)
        if name.endswith("Toyota_bin_00.BIN"):
            pool_samples.append({"file": name, "sha256": hashlib.sha256(data).hexdigest(),
                                 "bytes": len(data), "base64": base64.b64encode(data).decode("ascii")})
            try:
                audit = audit_command_pool(data)
                commands = audit["entries"]
                frames_path = paths.get(name.replace("Toyota_bin_00", "Toyota_bin_11"))
                if frames_path:
                    frames = frame_pool_references(frames_path.read_bytes(), audit)
                    refs = [ref for frame in frames for ref in frame["referencesByFieldOffset"].values()]
                    frame_ids = {frame["frameId"] for frame in frames}
                    bindings = typed_table(paths[name.replace("Toyota_bin_00", "Toyota_bin_12")].read_bytes())
                    if bindings.stride != 16:
                        raise ValueError("Unknown function/frame-binding stride")
                    eligible = [uint(bindings.body, i * 16 + 6, 2) for i in range(bindings.count)
                                if uint(bindings.body, i * 16 + 8, 2) == 0]
                    audit["frameLinks"] = {"frames": len(frames), "uniqueFrameIds": len(frame_ids),
                        "dictionaryReferences": len(refs), "unresolvedReferences": sum("error" in ref for ref in refs),
                        "functionBindingRows": bindings.count, "eligibleFunctionBindings": len(eligible),
                        "eligibleBindingsWithFrame": sum(identity in frame_ids for identity in eligible),
                        "readShapedExamples": [frame for frame in frames
                            if frame["referencesByFieldOffset"]["2"].get("bytesHex", "").startswith(("21 ", "22 "))][:10]}
                    (output / (name.replace("/", "-") + ".frames.json")).write_text(json_text(frames))
                audit.pop("entries")
                counts = Counter(c["bytesHex"].split(" ")[0] for c in commands if c["length"] and "bytesHex" in c)
                pools.append({"file": name, **audit, "firstByteCounts": dict(counts), "examples": commands[:12],
                              "readShapedExamples": [c for c in commands if c.get("bytesHex", "").startswith(("21 ", "22 "))][:12]})
                (output / (name.replace("/", "-") + ".commands.json")).write_text(json_text(commands))
            except ValueError as error:
                pools.append({"file": name, "unparsed": str(error)})
    findings["families"]["sharedTypedTables"] = shared_tables
    findings["families"]["opaqueCommandPools"] = pools
    (output / "shared-tables.json").write_text(json_text(shared_tables))
    open_prefixes = [{"file": name, "bytes": path.stat().st_size, "prefixHex": path.read_bytes()[:256].hex(" ")}
                     for name, path in paths.items() if "/" not in name and name not in ("MENU.BIN",)
                     and not name.startswith(("SYSLIST_", "FUNCDATA_"))]
    emit_report("open-formats", "Uninterpreted top-level format prefixes", json_text(open_prefixes))
    (output / "command-pool-excerpts.json").write_text(json_text(pool_samples))
    emit_report("command-pool-excerpts", "Source command-pool excerpts (not executable recipes)", json_text(pool_samples))

    if "MENU.BIN" in paths:
        try:
            nodes = menu_nodes(paths["MENU.BIN"].read_bytes())
            title_matches = sum(f"{n['headerWords'][1]:08X}" in text for n in nodes.values())
            with (output / "menu-nodes.jsonl").open("w", encoding="utf-8") as dest:
                for node in nodes.values():
                    dest.write(json.dumps(node, sort_keys=True) + "\n")
            evidence, leaf_examples, manual_leaf_examples, leaf_count, visited, queue = [], [], [], 0, set(), deque([(0, [])])
            while queue:
                offset, trail = queue.popleft()
                if offset in visited:
                    continue
                visited.add(offset)
                node = nodes[offset]
                title = text.get(f"{node['headerWords'][1]:08X}", f"@{offset:x}")
                trail = trail + [title]
                if re.search(SEARCHES["innova"], title, re.I):
                    evidence.append({"node": offset, "path": trail, "headerWords": node["headerWords"],
                                     "edges": node["edges"]})
                if not node["edges"] and any(re.search(SEARCHES["innova"], part, re.I) for part in trail):
                    leaf_count += 1
                    example = {"node": offset, "path": trail, "headerWords": node["headerWords"]}
                    if len(leaf_examples) < 30:
                        leaf_examples.append(example)
                    if "Manual Select" in trail and len(manual_leaf_examples) < 30:
                        manual_leaf_examples.append(example)
                if len(trail) < 32:
                    queue.extend((edge["target"], trail) for edge in node["edges"] if edge["target"] in nodes)
            findings["families"]["MENU.BIN"] = {"nodes": len(nodes), "edges": sum(len(n["edges"]) for n in nodes.values()),
                "titleIdsResolved": title_matches, "reachableNodesWithinDepth32": len(visited), "innovaPaths": evidence,
                "innovaDescendantLeaves": leaf_count, "innovaLeafPathExamples": leaf_examples,
                "innovaManualLeafPathExamples": manual_leaf_examples}
            parsed_other.add("MENU.BIN")
        except ValueError as error:
            findings["families"]["MENU.BIN"] = {"unparsed": str(error)}

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
            protocols, missing_functions, missing_files = Counter(), Counter(), Counter()
            lower_paths = {p.casefold() for p in paths}
            functions = function_tables.get(f"FUNCDATA_{match[1]}.BIN", {})
            with (output / (name + ".records.jsonl")).open("w", encoding="utf-8") as dest:
                for offset, records in sorted(blocks.items()):
                    for row_number, record in enumerate(records):
                        fields = system_fields(record)
                        field0s.add(fields["u32"]["0"])
                        # Field 0 is a numeric function key, not a text key.
                        # Accidental matches in the same integer namespace are
                        # not evidence (small integers also name UI prompts).
                        labels = {key: text[f"{value:08X}"] for key, value in fields["u32"].items()
                                  if key in ("4", "8", "16", "23", "28") and f"{value:08X}" in text}
                        lookups.update(labels.keys())
                        stats["records"] += 1
                        function_found = fields["u32"]["0"] in functions
                        stats["word0ExistsInFunctionIndex"] += function_found
                        if not function_found:
                            missing_functions[fields["u32"]["0"]] += 1
                        filename = labels.get("23", "")
                        file_found = f"{match[1]}/{filename}".casefold() in lower_paths
                        stats["resolvedEcuFileExists"] += file_found
                        if not file_found:
                            missing_files[filename] += 1
                            stats["unresolvedFileWithFlashLabel" if labels.get("28") == "FLASH" else "unresolvedFileWithNonFlashLabel"] += 1
                        protocols[labels.get("28", "<missing>")] += 1
                        item = {"file": name, "offset": offset + 2 + 37 * row_number, "rawHex": record.hex(" "),
                                "fields": fields, "textMatchesByFieldOffset": labels}
                        dest.write(json.dumps(item, ensure_ascii=True, sort_keys=True) + "\n")
                        if len(examples) < 8:
                            examples.append(item)
            findings["families"][name] = {"vehicleIndexRows": len(index), "uniqueBlocks": len(blocks),
                "uniqueWord0Values": len(field0s), "counts": dict(stats), "textMatchesByWordOffset": dict(lookups),
                "transportLabels": dict(protocols), "missingFunctionIds": dict(missing_functions),
                "missingEcuFiles": dict(missing_files), "examples": examples, "indexExamples": index[:5]}
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
        if name not in section_files and name not in function_tables and name not in parsed_other
        and not re.fullmatch(r"SYSLIST_(EU|JP|NA)\.BIN", name)]
    findings["structuralCoverage"] = {"parsedDecodedFiles": len(section_files) + len(function_tables) + len(parsed_other)
        + sum(1 for key, value in findings["families"].items() if key.startswith("SYSLIST_") and "unparsed" not in value),
        "uninterpretedDecodedFiles": len(findings["notStructurallyInterpreted"])}
    # Complete small, SHA-pinned conversion inputs for reviewing the native readers offline.
    # Keep raw binary and formula text in the ignored evidence/artifact, never in an APK or Git.
    conversion_inputs = []
    for name, source in sorted(paths.items()):
        if name == "SYSTEMENTER.BIN" or re.fullmatch(r"EU/Toyota_bin_(?:11|12)\.BIN", name):
            raw = source.read_bytes()
            conversion_inputs.append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                      "base64": base64.b64encode(raw).decode("ascii")})
        if name == "FUNCDATA_EU.BIN":
            raw = source.read_bytes()[:42225]
            conversion_inputs.append({"file": "FUNCDATA_EU.BIN.prefix", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                      "base64": base64.b64encode(raw).decode("ascii")})
    emit_report("conversion-inputs", "SHA-pinned conversion input excerpts (not executable)", json_text(conversion_inputs))
    emit_report("conversion-expressions", "Complete recovered EXPRESS text for bounded translation (not execution)",
                json_text(libraries.get("EXPRESS.GAG.json", {})))
    # Complete applicability inputs, compressed for review through the same checksum-checked
    # evidence path when release-asset downloads are inaccessible. Never native library bytes.
    applicability = []
    for name, source in sorted(paths.items()):
        if name == "MENU.BIN" or re.fullmatch(r"(?:SYSLIST|FUNCDATA)_(?:EU|JP|NA)\.BIN", name):
            raw = source.read_bytes()
            applicability.append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                  "compression": "zlib", "base64": base64.b64encode(zlib.compress(raw, 9)).decode("ascii")})
    # Bounded representative ECU inputs for reproducing exact byte/bit/formula joins locally.
    # The complete corpus is still converted in Actions, not shipped in Git or the APK.
    ecu_samples = {"0006", "000b", "0024", "0028", "002d", "006e", "0070", "0081", "0085", "00c1", "00c2", "00d4"}
    for name, source in sorted(paths.items()):
        if name == "SYSTEMENTER.BIN" or (name.startswith("EU/") and (
                re.fullmatch(r"EU/(?:Toyota|Viewer)_bin_[0-9a-fA-F]+\.BIN", name)
                or any(name.casefold() == f"eu/ecu_data_{identity}.bin" for identity in ecu_samples))):
            raw = source.read_bytes()
            applicability.append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                  "compression": "zlib", "base64": base64.b64encode(zlib.compress(raw, 9)).decode("ascii")})
    for name in ("TEXT_EN.GAG.json", "DSTREAM_EN.GAG.json", "TCODE_EN.GAG.json", "RCODE_EN.GAG.json"):
        if name in libraries:
            raw = json_text(libraries[name]).encode("utf-8")
            applicability.append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                  "compression": "zlib", "base64": base64.b64encode(zlib.compress(raw, 9)).decode("ascii")})
    emit_report("conversion-applicability", "Complete applicability tables and English labels for conversion review (data only)", json_text(applicability))
    # Exercise the real, exact representative join in Actions as well as synthetic
    # fixtures. This is deliberately NOT wired into convert_vehicle_packs or an
    # enabled system. Every row is retained with either disabled review metadata
    # or its first blocking reason. It is not coverage of all Toyota functions.
    review_names = ["FUNCDATA_EU.BIN", "EU/Ecu_Data_0081.BIN"] + ["EU/" + n for n in
                    ("Toyota_bin_00.BIN", "Toyota_bin_11.BIN", "Toyota_bin_12.BIN", "Viewer_bin_12.BIN")]
    if calc_evidence is None:
        raise ValueError("The SHA-pinned archive did not supply native CALC data evidence")
    review = review_direct21({n: paths[n].read_bytes() for n in review_names}, libraries["DSTREAM_EN.GAG.json"],
                             libraries["EXPRESS.GAG.json"], region="EU", function_key=61, ecu_file="EU/Ecu_Data_0081.BIN",
                             calc_evidence=calc_evidence)
    review["sourceSha256"] = source_hash
    (output / "direct21-review.json").write_text(json_text(review))
    emit_report("direct21-review", "Exact EU function 61 / ECU 0081 numeric review (ALL DISABLED)", json_text(review))
    findings["direct21Review"] = {k: v for k, v in review.items() if k not in ("items", "inputs")}
    (output / "text-keyword-evidence.json").write_text(json_text(hits))
    (output / "sweep-summary.json").write_text(json_text(findings))
    emit_report("summary", "Toyota full diagnostic-data sweep", json_text(findings))
    emit_report("text-evidence", "Toyota non-DTC text evidence (not request definitions)", json_text(hits))
    menu = findings["families"].get("MENU.BIN", {})
    overview = ["This archive contains diagnostic building blocks beyond passive DTC text.", "",
        f"- Source SHA-256: `{source_hash}`.",
        f"- Inventoried {len(all_files):,} archive/nested files and {len(native_census)} ELF libraries.",
        f"- Verified {len(manifest):,} decoded payload hashes ({findings['decodedBytes']:,} bytes).",
        f"- Parsed {len(section_files):,} ECU directories; {len(unparsed)} directory errors.",
        f"- Framed {sum(section_counts.values()):,} ECU typed sections; {len(typed_errors):,} section framing errors (semantics remain table-specific).",
        f"- Whole-file structural parsers accept {findings['structuralCoverage']['parsedDecodedFiles']:,}/{len(manifest):,}; "
        f"{findings['structuralCoverage']['uninterpretedDecodedFiles']} whole-file exceptions remain explicit.",
        f"- Parsed {menu.get('nodes', 0):,} menu nodes; {menu.get('titleIdsResolved', 0):,} title IDs resolved.",
        f"- Found {len(menu.get('innovaPaths', []))} Innova-labelled menu nodes.", "",
        "| Region | Vehicle index rows | System rows | ECU-file links resolved |",
        "|---|---:|---:|---:|"]
    for region in ("EU", "JP", "NA"):
        stats = findings["families"].get(f"SYSLIST_{region}.BIN", {})
        counts = stats.get("counts", {})
        overview.append(f"| {region} | {stats.get('vehicleIndexRows', 0):,} | {counts.get('records', 0):,} | {counts.get('resolvedEcuFileExists', 0):,} |")
    overview.extend(["", "Command-pool audit (byte sequences are not approved requests):", ""])
    for pool in pools:
        overview.append(f"- {pool['file']}: {pool.get('boundedEntries', 0)}/{pool.get('declaredEntries', 0)} bounded entries; "
                        f"{len(pool.get('invalidEntries', []))} invalid, {pool.get('bodyShortfallBytes', 0)} missing declared bytes. "
                        + pool.get("unparsed", ""))
        links = pool.get("frameLinks", {})
        if links:
            overview.append(f"  - {links['frames']:,} frame rows; {links['dictionaryReferences']:,} dictionary references, "
                            f"{links['unresolvedReferences']} unresolved; {links['eligibleBindingsWithFrame']:,}/"
                            f"{links['eligibleFunctionBindings']:,} eligible function/frame references resolve.")
    overview.extend(["", "**No new diagnostic operations were enabled in the app.** Vendor code and EXPRESS were not executed. "
        "Source anomalies and uninterpreted formats remain explicit in the detailed reports.", "",
        "Complete, checksum-verified text reports are available in the `Toyota sweep evidence NNN` checks "
        "(compressed JSON/base64; use `sweep_reports.py unpack`) and in `toyota-data-inspection`. "
        "Large source/decoded data stays outside Git and the APK.", ""])
    emit_report("overview", "Toyota diagnostic-data re-sweep", "\n".join(overview))
    report_index = {"sourceSha256": source_hash, "reports": [
        {"file": name, "sha256": hashlib.sha256((report_dir / name).read_bytes()).hexdigest()}
        for name in generated_reports]}
    (report_dir / "sweep-reports.json").write_text(json_text(report_index))
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
