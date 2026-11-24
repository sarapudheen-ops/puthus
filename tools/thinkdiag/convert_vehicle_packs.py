#!/usr/bin/env python3
"""Strict, fail-closed Toyota vehicle-pack conversion, performed in Actions.

Implemented joins: native manual MENU leaf task -> regional SYSLIST key ->
system/function/ECU file, ECU section framing, and FUNCDATA live-row boundaries.
Untranslated records remain disabled with stable source IDs and per-item reasons.
This is deliberately NOT a claim that incomplete conversion is runnable support.
"""
from collections import Counter
import argparse
import gzip
import hashlib
import json
import sys
from pathlib import Path
import re

from toyota_structure import (ECU_FILE, function_index, menu_nodes, section_directory,
                              system_lists, typed_table, uint)
from vehicle_bundle import BundleWriter, SOURCE_SHA256, canonical, read_object
from toyota_hierarchy import normalize_source_vehicle
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from source_verification import load_verification, verify_archive

REGIONS = {0xFEE9: "EU", 0xFEEA: "NA", 0xFEEB: "JP"}
# Filled only by a separately audited native write-oracle review. Empty means
# every write capability remains disabled; do not accept corpus-supplied pins.
WRITE_ORACLE_REVIEW_HASHES = {}


def verified_write(definition):
    marker, digest = definition.get("provenMarker"), definition.get("reviewHash")
    return isinstance(marker, str) and bool(marker) and WRITE_ORACLE_REVIEW_HASHES.get(marker) == digest


def category_definitions(data, category, field):
    definitions = data.get(field, []) if isinstance(data, dict) else []
    if category in ("dtcs", "ecuInfo") and isinstance(data, dict):
        definitions = []
        for ecu_definition in data.get("ecus", []):
            diag = ecu_definition.get("diagnostics", {}) if isinstance(ecu_definition, dict) else {}
            if category == "dtcs":
                faults = diag.get("faults", {}) if isinstance(diag, dict) else {}
                layout = faults.get("layout", {}) if isinstance(faults, dict) else {}
                definitions.extend(layout.get("labels", []) if isinstance(layout, dict) else [])
            else:
                definitions.extend(diag.get("identification", []) if isinstance(diag, dict) else [])
    return definitions


REASONS = {
    "ecu_entry_untranslated": "Per-ECU SYSTEMENTER address/header, baud, init/session and selector are not fully translated.",
    "service_reply_untranslated": "Application service/identifier and reply-mask joins are not fully translated.",
    "formula_untranslated": "Signal byte/bit positions, signedness, scaling, unit and runtime applicability are not fully translated.",
    "record_schema_untranslated": "This source-row schema has not been translated into an executable definition.",
    "hierarchy_untranslated": "The exact source path is retained; normalization into model/generation/variant fields is not yet validated for this path.",
    "flash_unsupported": "Source system uses FLASH; flashing/download services are intentionally outside the app's write whitelist.",
    "missing_ecu_file": "Source ECU file could not be joined. No generic ECU or address is substituted.",
    "missing_function": "No regional FUNCDATA row joins this source function key.",
    "missing_system_entry": "No SYSTEMENTER entry exactly matches this region, function and complete selector byte; another selector or ECU is never substituted.",
    "missing_system_key": "The ordinary manual MENU leaf has no matching SYSLIST key in its source region.",
    "nonordinary_menu_task": "This manual leaf uses a special task, not the proven ordinary VehicleSystemScan key path.",
    "live_applicability_untranslated": "The source row's runtime applicability conditions are not fully translated.",
    "calc_semantics_unreviewed": "The exact EXPRESS/CALC expression is not in the runtime-compiled subset.",
    "dynamic_scaling_untranslated": "The source row's dynamic scaling behavior is not fully translated.",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_id(name, offset, suffix=""):
    return f"{name}@{offset:08x}{suffix}"


def load_sources(archive, decoded, manifest):
    verify_archive(archive)
    entries = json.loads(manifest.read_text())
    if not isinstance(entries, list) or len(entries) != 1410:
        raise ValueError("Incomplete decoded source manifest")
    result = {}
    for item in entries:
        name = item["file"]
        if (not isinstance(name, str) or name in result or "\\" in name
                or Path(name).is_absolute() or ".." in Path(name).parts):
            raise ValueError("Unsafe or duplicate source path")
        path = decoded / name
        if path.is_symlink() or decoded.resolve() not in path.resolve().parents:
            raise ValueError("Source escapes decoded directory")
        raw = path.read_bytes()
        if len(raw) != item["bytes"] or sha(raw) != item["sha256"]:
            raise ValueError(f"Decoded source checksum mismatch: {name}")
        result[name] = raw
    return result


class Converter:
    def __init__(self, sources, texts, output, english=None, read_artifacts=None,
                 oracle_evidence=None, group_enable_artifacts=None):
        self.sources = sources
        self.texts = texts
        self.english = english
        self.output = output
        self.writer = BundleWriter(output)
        # None: fully disabled conversion. Otherwise (review_artifact_counts, directory)
        # from toyota_runtime_compile.verify_source_read_files for the ONE reviewed
        # mandatory ECMECT system. All other systems stay disabled.
        self.read_artifacts = read_artifacts
        oracle_evidence = oracle_evidence or []
        self.oracle_evidence_rows = len(oracle_evidence)
        self.oracle_evidence = {}
        self.oracle_evidence_valid = True
        for row in oracle_evidence:
            key = (row['region'], row['function'], row['selector'])
            if key in self.oracle_evidence:
                self.oracle_evidence_valid = False
            self.oracle_evidence[key] = row
        self.oracle_evidence_keys = set(self.oracle_evidence)
        self.oracle_evidence_complete = False
        self.lifecycle_frames = {}
        self.response_header_checks = {}
        self.ecus = {}
        self.functions = {}
        self.systems = {}
        self.vehicle_systems = {}
        self.census = Counter()
        self.ledger_path = output / "unresolved-items.jsonl.gz"
        self._ledger_stream = self.ledger_path.open("wb")
        self.ledger = gzip.GzipFile(filename="", mode="wb", fileobj=self._ledger_stream, mtime=0)
        self.system_report = []
        self.vehicle_report = []
        self.hierarchy_issues = []
        self.normalized = []
        self.entries = None
        self.entry_pages = {}
        self.bring_up_pages = {}
        self.entry_support = {}
        self.unclaimed_entry_pages = []
        self.group_qualifier = None
        if group_enable_artifacts is not None:
            if english is None:
                raise ValueError("group enable artifacts need the --english library directory")
            from toyota_group_dtc_enable import GroupQualifier, load_pin
            libraries = {
                "TCODE_EN.GAG": json.loads((english / "TCODE_EN.GAG.json").read_text()),
                "TEXT_EN.GAG": json.loads((english / "TEXT_EN.GAG.json").read_text()),
            }
            self.group_qualifier = GroupQualifier(sources, libraries,
                                                  load_pin(group_enable_artifacts))

    def record(self, item):
        self.ledger.write(canonical(item) + b"\n")
        self.census["unresolvedSourceRecords"] += 1

    def text(self, identity):
        return self.texts.get(f"{identity:08X}")

    def pages(self, rows):
        return [self.writer.add({"kind": "disabled-items", "items": rows[i:i + 128]}) for i in range(0, len(rows), 128)]

    def disabled(self, identity, kind, raw, reasons, **fields):
        item = {"id": identity, "kind": kind, "sourceRecords": [identity], "sourceVerified": True,
                "enabled": False, "reasons": reasons, "rawHex": raw.hex(), **fields}
        self.record(item)
        return item

    def convert_entries(self):
        self.entries = EntryIndex(self.sources["SYSTEMENTER.BIN"])
        expected_oracle_keys = {(region_id, function, entry.descriptor)
            for region_id, region_rows in self.entries.regions.items()
            for function, row in region_rows.items()
            for entry in self.entries.lists[row['listOffset']].entries
            if entry.transport in (1, 5)}
        self.oracle_evidence_complete = (self.oracle_evidence_valid and bool(expected_oracle_keys)
                                         and self.oracle_evidence_rows == len(expected_oracle_keys)
                                         and self.oracle_evidence_keys == expected_oracle_keys)
        # Count DISTINCT recovered source records, not repeated menu/vehicle references. This
        # ranks byte-identical source setups only; it NEVER broadens an ECU's applicability.
        for item in self.entries.lists.values():
            for entry in item.entries:
                self.entry_support.setdefault(entry.raw, []).append(entry.source_id)
        for item in self.entries.lists.values():
            for entry in item.entries:
                report = entry.report()
                identity = report.pop("id")
                raw = report.pop("rawHex")
                disabled = self.disabled(identity, "ecu-entry-candidate", bytes.fromhex(raw),
                                         ["ecu_entry_untranslated"], **report)
                self.entry_pages[entry.offset] = self.pages([disabled])
                self.census["systemEntryRecords"] += 1
        # Markers/padding stay explicit; they are not silently decoded as zero
        # features, guessed requests or a second ECU's configuration.
        for span in self.entries.unclaimed:
            item = self.disabled(source_id("SYSTEMENTER.BIN", span["offset"], "#unclaimed"), "unclassified-entry-span",
                                 bytes.fromhex(span["rawHex"]), ["record_schema_untranslated"])
            self.unclaimed_entry_pages.extend(self.pages([item]))
        (self.output / "entry-index.json").write_bytes(canonical(self.entries.report()) + b"\n")

    def lifecycle_for(self, region_id, function, selector):
        region = {1: 'EU', 2: 'NA', 3: 'JP'}[region_id]
        if region not in self.lifecycle_frames:
            self.lifecycle_frames[region] = SourceFrames(self.sources, region)
        frames = self.lifecycle_frames[region]
        return selected_fast_kwp_lifecycle(self.entries, frames, region=region_id,
                                           function=function, selector=selector)

    def bring_up_candidates(self, region, function, selector, flash=False):
        row = self.entries.regions.get(region, {}).get(function)
        if row is None:
            return []
        key = (row["listOffset"], selector, flash)
        if key not in self.bring_up_pages:
            candidates = []
            for entry in self.entries.lists[row["listOffset"]].entries:
                support = sorted(set(self.entry_support[entry.raw]))
                blockers = ["Native transport/address/init/timing fields are not fully translated; no adapter settings are guessed",
                            "Identification and DTC read/record definitions are not translated; a valid reply cannot approve unknown formulas"]
                if entry.descriptor != selector:
                    blockers.append("Selector differs from the exact selected vehicle/system; this is evidence, not a fallback ECU")
                if flash:
                    blockers.append("FLASH is outside read-only bring-up and the write whitelist")
                candidates.append({"id": entry.source_id, "name": f"Recovered entry {entry.descriptor:02X}",
                    "sourceRecords": [entry.source_id], "supportRecords": support, "supportCount": len(support),
                    "metadata": {"protocol": f"Unmapped native transport code {entry.transport}",
                        "baudRaw": str(entry.baud), "selector": f"{entry.descriptor:02X}", "flags": f"{entry.flags:02X}",
                        "init": "Untranslated; bindings " + json.dumps(entry.init),
                        "addresses": "Raw runtime107b0=" + entry.fields.get("runtime107b0", "absent"),
                        "timing": "Raw runtime107f0=" + entry.fields.get("runtime107f0", "absent"),
                        "session": "Untranslated; no session-control request permitted",
                        "sourceFields": json.dumps(entry.fields, sort_keys=True, separators=(",", ":")),
                        "rawHex": entry.raw.hex(),
                        "supportBasis": "Distinct byte-identical SYSTEMENTER records; not cross-vehicle applicability"},
                    "blockers": blockers, "runtime": None})
            candidates.sort(key=lambda c: (-c["supportCount"], c["id"]))
            self.bring_up_pages[key] = [self.writer.add({"kind": "bring-up-candidates", "candidates": candidates[i:i + 128]})
                                       for i in range(0, len(candidates), 128)]
        return self.bring_up_pages[key]

    def convert_ecus(self):
        for name, raw in sorted(self.sources.items()):
            if not re.fullmatch(ECU_FILE, name):
                continue
            rows = []
            sections = []
            for section in section_directory(raw):
                table = typed_table(raw[section.offset:section.end])
                sections.append({"slot": section.identity, "tag": table.identity, "rows": table.count,
                                 "sourceRecord": source_id(name, section.offset), "stride": table.stride})
                if table.count == 0:
                    continue
                if table.stride is None:
                    rows.append(self.disabled(source_id(name, section.offset + 10), "unclassified", table.body,
                                              ["record_schema_untranslated"], declaredRows=table.count))
                else:
                    for i in range(table.count):
                        at = section.offset + 10 + i * table.stride
                        rows.append(self.disabled(source_id(name, at, f"#slot{section.identity}:row{i}"), "unclassified",
                                                  table.body[i * table.stride:(i + 1) * table.stride],
                                                  ["record_schema_untranslated"], tableTag=table.identity))
            metadata = {"kind": "ecu-source", "sourceFile": name, "sha256": sha(raw), "sections": sections,
                        "unclassifiedSourceRecords": len(rows), "pages": self.pages(rows)}
            self.ecus[name.casefold()] = {"sourceFile": name, "sha256": metadata["sha256"],
                                          "object": self.writer.add(metadata)}
            self.census["ecuFiles"] += 1

    def convert_functions(self):
        omissions = {}
        compiled = set()
        if self.ecmect_review is not None:
            # The enabled function's live rows are partitioned exactly: 30 compiled
            # into the slice, the other 985 keep their first implementation blocker.
            omissions = {row["id"]: row for row in self.omissions["items"]}
            compiled = {row["id"] for row in self.ecmect_review["liveReads"]}
        for region in REGIONS.values():
            name = f"FUNCDATA_{region}.BIN"
            raw = self.sources[name]
            index, _ = function_index(raw)
            pointers = sorted({p for row in index for p in row[1:6] if p not in (0, 0xFFFFFFFF)} | {len(raw)})
            stops = {p: pointers[i + 1] for i, p in enumerate(pointers[:-1])}
            for row_index, row in enumerate(index):
                # Match toyota_runtime_compile.FunctionBlocks.index_offsets so the
                # function source record joins the pinned compiler artifact exactly
                # (last key position wins, as in the dict comprehension there).
                identity = source_id(name, 1 + 28 * row_index)
                live_rows = []
                # CDSF ReadDStreamFunc @0xFBB0 passes runtime+0x107a0 to
                # FILE GetDsDataFromDSPart; FILE GetInfoFromAddrPart @0x11e30
                # loaded that value from FUNCDATA column 3. This is NOT an address.
                p = row[3]
                live_count = 0
                if p not in (0, 0xFFFFFFFF):
                    live_count, commands = uint(raw, p, 2), uint(raw, p + 2, 2)
                    end = p + 4 + live_count * 30
                    if live_count > 65535 or end > stops[p]:
                        raise ValueError(f"{name} function {row[0]}: live-row span exceeds its indexed block")
                    command_end = end
                    for _ in range(commands):
                        size = uint(raw, command_end, 1)
                        command_end += 1 + size
                        if command_end > stops[p]:
                            raise ValueError("Live command sequence exceeds source block")
                    for i in range(live_count):
                        at = p + 4 + i * 30
                        row_id = source_id(name, at, f"#function{row[0]}:live{i}")
                        omitted = omissions.get(row_id)
                        if self.ecmect_review is not None and (region, row[0]) == ("EU", 61):
                            if omitted is None:
                                if row_id not in compiled:
                                    raise ValueError("ECMECT live row is neither compiled nor accounted as omitted")
                                continue  # Compiled into the enabled read-only slice, not unresolved.
                            live_rows.append(self.disabled(row_id, "pid-candidate", raw[at:at + 30],
                                omitted["reasons"], detail=omitted["detail"]))
                        else:
                            live_rows.append(self.disabled(row_id, "pid-candidate",
                                raw[at:at + 30], ["ecu_entry_untranslated", "service_reply_untranslated", "formula_untranslated"]))
                metadata = {"kind": "function-source", "sourceRecord": identity, "functionKey": row[0],
                            "sourceFields": list(row), "declaredLiveRows": live_count, "livePages": self.pages(live_rows)}
                self.functions[region, row[0]] = {"sourceRecord": identity, "functionKey": row[0],
                    "declaredLiveRows": live_count, "object": self.writer.add(metadata)}

    def convert_systems(self):
        for region in REGIONS.values():
            name = f"SYSLIST_{region}.BIN"
            index, blocks = system_lists(self.sources[name])
            for offset, rows in sorted(blocks.items()):
                refs = []
                for i, raw in enumerate(rows):
                    at = offset + 2 + 37 * i
                    identity = source_id(name, at)
                    key = uint(raw, 0, 4)
                    ecu_file = self.text(uint(raw, 23, 4))
                    transport = self.text(uint(raw, 28, 4))
                    ecu = self.ecus.get(f"{region}/{ecu_file}".casefold())
                    function = self.functions.get((region, key))
                    region_id = {"EU": 1, "NA": 2, "JP": 3}[region]
                    entry = self.entries.select(region_id, key, raw[27])
                    entry_source = None
                    sources = [identity]
                    if function is not None: sources.append(function["sourceRecord"])
                    if entry is not None:
                        function_row = self.entries.regions[region_id][key]
                        sources += [source_id("SYSTEMENTER.BIN", function_row["offset"]), entry.source_id]
                        entry_source = {"sourceRecord": entry.source_id, "selector": entry.descriptor,
                                        "pages": self.entry_pages[entry.offset]}
                        self.census["systemsWithExactEntryJoin"] += 1
                    reasons = ["ecu_entry_untranslated"]
                    if transport == "FLASH": reasons = ["flash_unsupported"]
                    if ecu is None: reasons.append("missing_ecu_file")
                    if function is None: reasons.append("missing_function")
                    if entry is None: reasons.append("missing_system_entry")
                    # Operational category denominators are UNKNOWN until their row schemas/joins
                    # are translated. Zero is only used for the number actually made executable.
                    counts = {kind: {"converted": 0, "unresolved": None} for kind in
                              ("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")}
                    counts["pids"]["unresolved"] = function["declaredLiveRows"] if function else None
                    value = {"kind": "vehicle-system", "id": identity,
                        "name": self.text(uint(raw, 4, 4)) or f"System {key}",
                        "description": self.text(uint(raw, 8, 4)), "sourceRecords": sources,
                        "sourceVerified": True, "enabled": False, "reasons": reasons,
                        "functionKey": key, "entrySelector": raw[27], "transportLabel": transport,
                        "rawHex": raw.hex(), "ecuSource": ecu, "functionSource": function, "entrySource": entry_source,
                        "bringUpPages": self.bring_up_candidates(region_id, key, raw[27], transport == "FLASH"),
                        "counts": counts, "data": {}}
                    lifecycle = None
                    evidence_status = None
                    evidence_reason = None
                    oracle_key = (region_id, key, raw[27])
                    is_ecmect = self.ecmect_review is not None and identity == self.ecmect_review["systemId"]
                    if entry is not None and (is_ecmect or self.oracle_evidence):
                        try:
                            lifecycle = self.lifecycle_for(region_id, key, raw[27])
                        except Exception as error:
                            evidence_status = 'unproven'
                            evidence_reason = 'lifecycle_projection_rejected:' + type(error).__name__
                        if lifecycle is not None:
                            from toyota_ecmect_pack import response_header_check
                            self.response_header_checks[oracle_key] = response_header_check(lifecycle)
                        if not is_ecmect:
                            evidence = self.oracle_evidence.get(oracle_key)
                            if evidence is None or not self.oracle_evidence_complete:
                                evidence_status, evidence_reason = 'unproven', 'oracle_evidence_missing'
                            elif lifecycle is None:
                                evidence_status = 'unproven'
                            elif evidence.get('projectionDigest') != lifecycle.projection_digest():
                                evidence_status, evidence_reason = 'unproven', 'oracle_digest_mismatch'
                            else:
                                try:
                                    from toyota_ecmect_pack import link_for
                                    projection = link_for(lifecycle)
                                    value['link'] = projection['link']
                                    value['linkSource'] = projection['source']
                                    value['linkProvenance'] = projection['provenance']
                                    evidence_status, evidence_reason = 'proven', None
                                except ValueError as error:
                                    evidence_status, evidence_reason = 'unproven', str(error)
                    if is_ecmect:
                        # Enablement is allowed only via the pinned, independently
                        # verified compiler artifact; enable() itself asserts the exact
                        # source join (function 61, full selector 0x11, raw bytes, hashes).
                        from toyota_ecmect_pack import candidate_for, enable
                        enable(value, self.ecmect_review, lifecycle)
                        # Replace the untranslated evidence page with the ONE runnable
                        # read-only candidate carrying the exact translated runtime.
                        candidate = candidate_for(value, self.ecmect_review)
                        value["bringUpPages"] = [self.writer.add({"kind": "bring-up-candidates", "candidates": [candidate]})]
                    elif self.innova_2007_review is not None and identity == self.innova_2007_review["systemId"]:
                        from toyota_innova_2007_pack import candidate_for, enable
                        enable(value, self.innova_2007_review, self.ecmect_review)
                        candidate = candidate_for(value, self.innova_2007_review)
                        value["bringUpPages"] = [self.writer.add({"kind": "bring-up-candidates", "candidates": [candidate]})]
                    # Stage 1d group DTC path: same enable mechanics as ECMECT's
                    # pinned DTC category, but for whole compiled groups. Every
                    # gate (pin entry, per-system definition hash, per-system link
                    # projection, validate_system) is fail-closed; the pinned two
                    # systems above never take this path and stay byte-identical.
                    group_record = None
                    if self.group_qualifier is not None and entry is not None:
                        from toyota_group_dtc_enable import TARGETS, enable_system
                        ecu_path = ecu_file if "/" in ecu_file else f"{region}/{ecu_file}"
                        if is_ecmect or (self.innova_2007_review is not None
                                         and identity == self.innova_2007_review["systemId"]):
                            # The two pinned read-only slices keep their
                            # byte-identical packs; record their literal
                            # per-system outcome instead of a group gate.
                            self.group_qualifier.pinned_path(
                                region, key, raw[27], ecu_path, identity)
                        else:
                            group_record = self.group_qualifier.qualify(
                                region, key, raw[27], ecu_path, identity,
                                raw, entry.transport)
                        if group_record is not None:
                            if group_record["status"] == "enabled-ready":
                                if evidence_status != "proven":
                                    group_record["status"] = "unresolved"
                                    group_record["reason"] = "link_unproven:" + (
                                        evidence_reason or "oracle_evidence_missing")
                                else:
                                    problem = enable_system(
                                        value, group_record["_compiled"],
                                        TARGETS[tuple(group_record["group"])],
                                        group_record, lifecycle)
                                    if problem is not None:
                                        group_record["status"] = "unresolved"
                                        group_record["reason"] = problem
                                    else:
                                        group_record["status"] = "enabled"
                            value["groupDtcStatus"] = {
                                k: v for k, v in group_record.items()
                                if not k.startswith("_") and v is not None}
                    # Link and category status are explicit; legacy count fields are not
                    # treated as proof. Separately reviewed paths retain their pinned
                    # authorization; other links require complete native evidence below.
                    value["linkStatus"] = "proven" if value.get("enabled") is True and not value.get("reasons") else (evidence_status or "unproven")
                    if value["linkStatus"] == 'proven':
                        value['linkReasons'] = []
                    elif evidence_reason:
                        value['linkReasons'] = [evidence_reason]
                    else:
                        value["linkReasons"] = [] if value["linkStatus"] == "proven" else list(value.get("reasons") or ["ecu_entry_untranslated"])
                    data = value.get("data", {})
                    if isinstance(data, dict):
                        # No actuator/coding/security oracle hash is currently
                        # audited for production. Preserve proof slots, but fail closed.
                        for write_kind in ("actuators", "coding", "security", "specialFunctions"):
                            if isinstance(data.get(write_kind), list):
                                for definition in data[write_kind]:
                                    if isinstance(definition, dict):
                                        definition["provenMarker"] = definition.get("provenMarker")
                                        definition["reviewHash"] = definition.get("reviewHash")
                                        definition["verified"] = verified_write(definition)
                    category_keys = {"pids": "pids", "dtcs": "dtcs", "ecuInfo": "ecus",
                                     "actuators": "actuators", "coding": "coding", "specialFunctions": "specialFunctions"}
                    category_status = {}
                    if evidence_status == 'proven' and value.get('enabled') is not True \
                            and not value.get('groupDtcEnable'):
                        category_status = {category: 'unresolved' for category in category_keys}
                        value['categoryReasons'] = {category: f'translator_not_ported:{category}' for category in category_keys}
                        if group_record is not None and group_record.get('reason'):
                            value['categoryReasons']['dtcs'] = group_record['reason']
                    else:
                        for category, field in category_keys.items():
                            definitions = category_definitions(data, category, field)
                            count_key = "tests" if category == "actuators" else category
                            converted = value.get("counts", {}).get(count_key, {}).get("converted", 0)
                            if definitions:
                                category_status[category] = "executable"
                            elif converted:
                                category_status[category] = "unresolved"
                            elif category in ("pids", "dtcs", "ecuInfo") and value["linkStatus"] == "proven":
                                category_status[category] = "unresolved"
                            else:
                                category_status[category] = "unknown"
                            if category_status[category] != "executable" and isinstance(data, dict) and field in data:
                                if field == "ecus":
                                    # ecus carry BOTH the DTC and identification
                                    # definitions; drop only the non-executable
                                    # category's section, never the whole mapping.
                                    for ecu_definition in data.get("ecus", []):
                                        diag = ecu_definition.get("diagnostics", {}) if isinstance(ecu_definition, dict) else {}
                                        if isinstance(diag, dict):
                                            diag["identification"] = []
                                else:
                                    data[field] = []
                    value["categoryStatus"] = category_status
                    value["enabled"] = value["linkStatus"] == "proven" and "executable" in category_status.values()
                    ref = self.writer.add(value)
                    brief = {"id": identity, "name": value["name"], "counts": value["counts"],
                             "enabled": value["enabled"], "reasons": value["reasons"],
                             "linkStatus": value["linkStatus"], "linkReasons": value["linkReasons"],
                             "categoryStatus": category_status, "object": ref,
                             "readOnly": bool(value.get("readOnly"))}
                    refs.append(brief)
                    self.systems[identity] = ref
                    self.system_report.append({"id": identity, "region": region, "system": value["name"],
                        "functionKey": key, "entrySelector": raw[27], "ecuFile": ecu_file,
                        "nativeTransport": entry.transport if entry else None, "baud": entry.baud if entry else None,
                        "sourceRecords": sources, "entrySource": entry.source_id if entry else None,
                        "counts": value["counts"], "enabled": value["enabled"], "reasons": value["reasons"],
                        "linkStatus": value["linkStatus"], "linkReasons": value["linkReasons"],
                        "link": value.get("link"), "linkSource": value.get("linkSource"),
                        "linkProvenance": value.get("linkProvenance"),
                        "categoryStatus": value.get("categoryStatus"), "categoryReasons": value.get("categoryReasons", {}),
                        "groupDtcStatus": value.get("groupDtcStatus")})
                for key, block in index:
                    if block == offset:
                        self.vehicle_systems[region, key] = refs
        self.census["systems"] = len(self.systems)

    def convert_menu(self):
        raw = self.sources["MENU.BIN"]
        nodes = menu_nodes(raw)
        visited = set()

        def visit(offset, path, region, manual):
            if offset in visited or len(path) > 32:
                raise ValueError("Cyclic, shared-parent or excessive source menu")
            visited.add(offset)
            node = nodes[offset]
            title = self.text(node["headerWords"][1])
            if title is None:
                raise ValueError("Unresolved menu title")
            task = uint(raw, offset + 34, 2)
            region = REGIONS.get(task, region)
            manual = manual or (len(path) == 1 and title == "Manual Select")
            step = {"id": source_id("MENU.BIN", offset), "name": title, "task": task}
            path = path + [step]
            children = []
            for edge in node["edges"]:
                child = visit(edge["target"], path, region, manual)
                if child is not None:
                    children.append(child)
            if not manual and not children:
                return None
            value = {"kind": "manual-menu", "id": step["id"], "name": title, "children": children}
            normalized = None
            if manual and not children:
                systems = self.vehicle_systems.get((region, task)) if task < 0x8000 else None
                reasons = ["hierarchy_untranslated"]
                if systems is None:
                    reasons.append("missing_system_key" if task < 0x8000 else "nonordinary_menu_task")
                value.update({"kind": "manual-vehicle", "region": region, "sourceKey": task,
                              "make": "Toyota", "selection": path[2:], "systems": systems or [],
                              "hierarchyResolved": False, "systemJoinResolved": systems is not None, "enabled": False, "reasons": reasons})
                record = {"id": step["id"], "path": [s["name"] for s in path[2:]], "region": region,
                          "sourceKey": task, "systems": [s["id"] for s in systems or []], "reasons": reasons}
                catalog = normalize_source_vehicle(record)
                if catalog is not None:
                    reasons.remove("hierarchy_untranslated")
                    value.update(hierarchyResolved=True)
                    record.update(hierarchyResolved=True, normalizedVariantId=catalog["nodes"][-1]["id"])
                    normalized = {"kind": "converted-vehicle", "level": "variant",
                        "id": catalog["nodes"][-1]["id"], "name": catalog["nodes"][-1]["name"],
                        "catalog": catalog, "sourceSelection": record["path"], "sourceRecords": [s["id"] for s in path],
                        "hierarchyResolved": True, "reasons": [], "sourceIssues": list(reasons), "systems": systems or []}
                    self.normalized.append(normalized)
                else:
                    self.hierarchy_issues.append(record)
                self.vehicle_report.append(record)
                self.census["manualLeaves"] += 1
                self.census["manualLeavesWithSystemJoin"] += systems is not None
            ref = self.writer.add(value)
            if normalized is not None:
                # Reuse the exact source leaf's system descriptors, rather than embedding
                # their counts/reasons/ranges a second time for all 24k vehicle variants.
                normalized["systemsSource"] = ref
                normalized["systemsSourceId"] = step["id"]
            return {"id": value["id"], "name": title, "leaf": value["kind"] == "manual-vehicle", "object": ref}

        root = visit(0, [], None, False)
        if len(visited) != len(nodes):
            raise ValueError("Unreachable source menu records")
        return root

    def convert_catalog(self):
        tree = {"id": "recovered-toyota-root", "name": "Toyota", "level": "root", "children": {}}
        for vehicle in self.normalized:
            current = tree
            for node in vehicle["catalog"]["nodes"]:
                if node["level"] == "variant":
                    if node["id"] in current["children"]:
                        raise ValueError("Two source paths normalize to the same variant; review required")
                    current["children"][node["id"]] = vehicle
                else:
                    current = current["children"].setdefault(node["id"], {
                        "id": node["id"], "name": node["name"], "level": node["level"], "children": {}})
        def write(node):
            if node["level"] == "variant":
                value = {key: value for key, value in node.items() if key != "systems" or "systemsSource" not in node}
            else:
                value = {**node, "kind": "catalog-level", "children": [
                    write(child) for child in sorted(node["children"].values(), key=lambda n: (n["name"], n["id"]))]}
            return {"id": node["id"], "name": node["name"], "level": node["level"], "object": self.writer.add(value)}
        self.census["normalizedVehicleChoices"] = len(self.normalized)
        self.census["unresolvedHierarchyChoices"] = len(self.hierarchy_issues)
        return write(tree)["object"]

    def convert_inventory(self):
        if self.ecmect_review is not None:
            from toyota_ecmect_pack import stage_review, verify_enabled
            from toyota_runtime_compile import MANDATORY_SYSTEM
            # Re-verify the enabled object against the exact artifact allowlist and
            # stage the omissions ledger plus pinned compiler artifacts next to the
            # pack (the omissions page stays in the container; artifacts are not
            # APK content).
            enabled_ref = self.systems.get(MANDATORY_SYSTEM)
            if enabled_ref is None:
                raise ValueError("Mandatory ECMECT system record is missing")
            self.writer.stream.flush()  # read_object reopens the container path.
            verify_enabled(read_object(self.writer.path, enabled_ref), self.ecmect_review)
            self.omissions_ref = self.writer.add(self.omissions)
            (self.output / "mandatory-ecmect-live-omissions.json").write_bytes(canonical(self.omissions) + b"\n")
            stage_review(self.ecmect_review, self.output)
            from toyota_innova_2007_pack import FILENAME, SYSTEM, verify_enabled as verify_2007
            from toyota_innova_2007_reads import verify_candidate_bytes
            second_ref = self.systems.get(SYSTEM)
            if second_ref is None:
                raise ValueError("2007 ECMECT system record is missing")
            self.writer.stream.flush()
            verify_2007(read_object(self.writer.path, second_ref), self.innova_2007_review, self.ecmect_review)
            raw_review = canonical(self.innova_2007_review) + b'\n'
            verify_candidate_bytes(raw_review, FILENAME)
            (self.output / FILENAME).write_bytes(raw_review)
            self.census["ecmectOmittedLiveRows"] = self.omissions["omittedLiveRows"]
        # The source has SYSLIST blocks not reached by ordinary manual MENU paths.
        # Keep ALL source systems/ECUs/functions in a separate audit root. They are
        # never attached to another vehicle, selected as fallback, or hidden from
        # computed coverage merely because navigation cannot currently reach them.
        systems = sorted(self.systems.items())
        pages = [self.writer.add({"kind": "source-inventory-page", "systems": [
            {"id": identity, "object": ref} for identity, ref in systems[i:i + 256]]})
            for i in range(0, len(systems), 256)]
        return self.writer.add({"kind": "source-inventory", "systemPages": pages,
            "ecuSources": [value["object"] for _, value in sorted(self.ecus.items())],
            "functionSources": [value["object"] for _, value in sorted(self.functions.items())],
            "entryPages": [ref for _, values in sorted(self.entry_pages.items()) for ref in values],
            "unclaimedEntryPages": self.unclaimed_entry_pages})

    def finish(self):
        self.ecmect_review = None
        self.innova_2007_review = None
        self.omissions = None
        if self.read_artifacts is not None:
            _, artifact_dir = self.read_artifacts
            from toyota_ecmect_pack import live_omissions, load_review
            from toyota_innova_2007_pack import load_candidate
            self.ecmect_review = load_review(artifact_dir)
            self.innova_2007_review = load_candidate(artifact_dir)
            self.omissions = live_omissions(self.sources, self.english, self.ecmect_review)
        self.convert_entries()
        self.convert_ecus()
        self.convert_functions()
        self.convert_systems()
        root = self.convert_menu()
        catalog_root = self.convert_catalog()
        source_inventory = self.convert_inventory()
        self.ledger.close(); self._ledger_stream.close()
        categories = ("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")
        converted = {kind: sum(s["counts"][kind]["converted"] for s in self.system_report) for kind in categories}
        executable = sum(converted.values())
        enabled = sum(s["enabled"] for s in self.system_report)
        complete = (executable > 0 and enabled == self.census["systems"] and self.census["unresolvedSourceRecords"] == 0 and
                    self.census["unresolvedHierarchyChoices"] == 0 and self.census["manualLeavesWithSystemJoin"] == self.census["manualLeaves"] and
                    self.census["normalizedVehicleChoices"] == self.census["manualLeaves"] and
                    all(s["counts"][kind]["unresolved"] == 0 for s in self.system_report for kind in categories))
        ledger = self.ledger_path.read_bytes()
        summary = {**dict(self.census), "complete": complete, "executableDefinitions": executable,
                   "enabledSystems": enabled, "converted": converted,
                   "unresolvedLedger": {"file": self.ledger_path.name, "bytes": len(ledger), "sha256": sha(ledger)},
                   "sourceVerification": load_verification(), "physicalValidation": False, "reasons": REASONS}
        index = self.writer.finish(root["object"], summary, catalog_root, source_inventory)
        for name, records in (("vehicles", self.vehicle_report), ("systems", self.system_report)):
            with (self.output / f"{name}.jsonl.gz").open("wb") as file:
                with gzip.GzipFile(filename="", mode="wb", fileobj=file, mtime=0) as dest:
                    for row in records: dest.write(canonical(row) + b"\n")
        report = ["# Toyota conversion report", "", ("**COMPLETE source conversion; physical validation is separate.**" if complete else
            f"**PARTIAL — {enabled:,} executable system{'s' if enabled != 1 else ''} · {executable:,} executable definitions.**"), "",
            f"Source archive SHA-256: `{SOURCE_SHA256}`.", "",
            "| Measure | Count |", "|---|---:|"]
        report += [f"| {key} | {value} |" for key, value in sorted(self.census.items())]
        report += [f"| Executable definitions | {executable} |", f"| Enabled source systems | {enabled} |", "", "## Exact per-item accounting", "",
            "- `unresolved-items.jsonl.gz`: every framed ECU row, declared live row, SYSTEMENTER entry and unclaimed entry-file span, stable file/offset/slot/row ID, exact source bytes and all disabling reasons.",
            "- `entry-index.json`: complete reader-backed regional function/selector directory, framed entry fields, binding IDs and every unclaimed byte span. Opaque header/timing values are not approved transport settings.",
            "- `vehicles.jsonl.gz`: every manual source leaf, full exact path, region/key, all system IDs and hierarchy reasons.",
            "- `systems.jsonl.gz`: every regional system record and converted/unresolved category counts. `null` means **unknown**, not zero or no source support.",
            "- The range bundle retains the same per-system pages. Selecting a vehicle does not fetch the full corpus.",
            "- Unclassified rows are not counted as working PIDs, DTCs, tests or coding. Source label/byte recovery is not an executable mapping.",
            "- Opaque whole-file formats and non-live functional row schemas are not yet enumerated as individual features. Full original source IDs remain in the separate research library; this report is not yet an exhaustive feature census.",
            "", "## Disabling reasons", ""]
        report += [f"- `{key}`: {value}" for key, value in REASONS.items()]
        report += ["", "## Full manual hierarchy and starting choices", "",
                   "All regional vehicle paths are exposed as Make / exact source Model / first source detail or group / "
                   "remaining details plus region and communication menu. Missing detail levels are labelled as absent; "
                   "a source group is not asserted to be a chassis generation. The four previously reviewed Innova paths retain their IDs and month boundaries. "
                   "Non-vehicle manual tasks remain in the lossless source tree. Full per-path/per-system accounting is in the compressed ledgers, "
                   "not an unbounded CI summary. The following table shows only the four starting reviewed choices; it is not a claim of executable coverage.", "",
                   "| Manual vehicle choice | Source system ID / name | PIDs converted / unresolved candidates | DTCs | Tests | Coding | Reasons |",
                   "|---|---|---:|---|---|---|---|"]
        for vehicle in self.normalized:
            if not vehicle["catalog"]["id"].startswith("recovered-toyota-"):
                continue
            title = vehicle["catalog"]["name"].replace("|", "\\|")
            for system in vehicle["systems"]:
                def count(kind):
                    c = system["counts"][kind]
                    return f'{c["converted"]} / {c["unresolved"] if c["unresolved"] is not None else "unknown"}'
                report.append(f'| {title} | `{system["id"]}` {system["name"].replace("|", " ")} | {count("pids")} | '
                              f'{count("dtcs")} | {count("tests")} | {count("coding")} | '
                              f'{", ".join(system["reasons"]) or ("enabled read-only; physical validation PENDING" if system["enabled"] else "")} |')
        report += ["", f"{enabled} source systems are enabled: the pinned ECMECT and 2007 Innova slices are read-only, "
                   "where clearing, coding, actuators, security access and flashing are excluded.",
                   (f"{sum(1 for row in self.system_report if (row.get('groupDtcStatus') or {}).get('status') == 'enabled')} "
                    "compiled-group systems are additionally enabled as DTC read/clear slices: the documented fault-memory "
                    "clear stays behind its separate confirmation, and coding, actuators, security access and flashing "
                    "stay excluded. Their pids and ecuInfo definitions stay unresolved.")
                   if any((row.get('groupDtcStatus') or {}).get('status') == 'enabled' for row in self.system_report) else
                   "No compiled-group DTC slices are enabled (fail-closed: absent or unmatched group pin).",
                   "No guessed CAN IDs, local identifiers, formulas, security algorithms or source applicability were enabled.",
                   "Source verification is distinct from completed conversion and from physical vehicle testing.", ""]
        (self.output / "conversion-report.md").write_text("\n".join(report))
        container = self.output / index["container"]
        if read_object(container, index["root"])["kind"] != "manual-menu":
            raise ValueError("Invalid bundle root")
        promoted = [r for r in self.system_report if r.get('nativeTransport') in (1, 5)]
        groups = Counter((r['linkStatus'], r['region'], r['nativeTransport'], str(r['baud'])) for r in promoted)
        reasons = Counter(reason for r in promoted for reason in r.get('linkReasons', []))
        deviations = Counter((reason, r['region'], r['nativeTransport'], str(r['baud']), r['functionKey'], r['entrySelector'])
                             for r in promoted if r['linkStatus'] == 'unproven'
                             for reason in (r.get('linkReasons') or ['unspecified']))
        links = Counter(canonical(r['link']) for r in promoted if r.get('link') is not None)
        init_packets = Counter(r['linkSource']['initPacketHex'] for r in promoted if r.get('linkSource'))
        keepalive_packets = Counter(r['linkSource']['keepalivePacketHex'] for r in promoted if r.get('linkSource'))
        exit_packets = Counter(r['linkSource']['exitPacketHex'] for r in promoted if r.get('linkSource'))
        keepalive_first = Counter(packet[:2] for packet in keepalive_packets.elements())
        exit_first = Counter(packet[:2] for packet in exit_packets.elements())
        rx = Counter('agree' if item['agrees'] else 'disagree'
                     for item in self.response_header_checks.values())
        promotion = {
            'kind': 'code1-link-promotion-v1',
            'eligibleSystemRecords': len(promoted),
            'systemsByLinkStatusRegionTransportBaud': [
                {'linkStatus': k[0], 'region': k[1], 'transport': k[2], 'baud': k[3], 'systems': v}
                for k, v in sorted(groups.items())],
            'reasonHistogram': dict(sorted(reasons.items())),
            'top10Reasons': reasons.most_common(10),
            'deviations': [
                {'reason': k[0], 'region': k[1], 'transport': k[2], 'baud': k[3],
                 'function': k[4], 'selector': k[5], 'systems': v}
                for k, v in sorted(deviations.items())],
            'distinctLinkObjects': len(links),
            'topLinksBySystems': [{'link': json.loads(k), 'systems': v} for k, v in links.most_common(10)],
            'distinctInitPackets': len(init_packets), 'topInitPackets': init_packets.most_common(20),
            'distinctKeepalivePackets': len(keepalive_packets), 'topKeepalivePackets': keepalive_packets.most_common(20),
            'keepaliveFirstByteDistribution': dict(sorted(keepalive_first.items())),
            'distinctExitPackets': len(exit_packets), 'topExitPackets': exit_packets.most_common(20),
            'exitFirstByteDistribution': dict(sorted(exit_first.items())),
            'responseHeaderConventionChecks': {'checkedTuples': len(self.response_header_checks), **dict(sorted(rx.items()))},
            'provenanceFields': next((r['linkProvenance'] for r in promoted if r.get('linkProvenance')), {}),
            'featureCounts': converted, 'enabledSystems': enabled,
            'containerBytesAfter': index['containerBytes'],
            'containerBytesBefore': None,
            'containerBytesAdded': None,
            '9600Systems': {status: sum(1 for r in promoted if r['baud'] == 9600 and r['linkStatus'] == status)
                            for status in ('proven', 'unproven')},
        }
        (self.output / 'code1-link-promotion.json').write_bytes(canonical(promotion) + b'\n')
        if self.group_qualifier is not None:
            import shutil
            from toyota_group_dtc_enable import qualification_report
            markdown, payload = qualification_report(self.group_qualifier,
                                                     self.group_qualifier.pin)
            (self.output / 'group-dtc-enable.md').write_text(markdown)
            (self.output / 'group-dtc-enable.json').write_text(payload)
            shutil.copyfile(self.group_qualifier.pin['path'],
                            self.output / 'dtc-group-enable-v1.json')
        return index


def main():
    import traceback
    sys.excepthook = lambda *exc: (traceback.print_exception(*exc), sys.stdout.flush(), sys.stderr.flush())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--decoded", type=Path, required=True)
    parser.add_argument("--english", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--oracle-evidence", type=Path,
                        help="Complete verified native lifecycle evidence; absent means no new links are promoted.")
    parser.add_argument("--read-artifacts", type=Path,
                        help="Directory with both pinned ECMECT source-read artifacts. "
                             "When given, exactly the two reviewed systems are enabled read-only; "
                             "everything else stays disabled.")
    parser.add_argument("--group-enable-artifacts", type=Path,
                        help="Directory with schema/dtc-group-enable-v1.json. When given, the "
                             "Stage 1c-passing compiled groups are qualified per system and "
                             "enabled as DTC read/clear slices only where the pin entry and the "
                             "per-system definition hash match and the link is proven.")
    args = parser.parse_args()
    sources = load_sources(args.archive, args.decoded, args.manifest)
    texts = json.loads((args.english / "TEXT_EN.GAG.json").read_text())
    read_artifacts = None
    if args.read_artifacts is not None:
        from toyota_runtime_compile import verify_source_read_files
        counts = verify_source_read_files(args.read_artifacts)
        read_artifacts = (counts, args.read_artifacts)
    oracle_evidence = None
    if args.oracle_evidence is not None:
        oracle_evidence = json.loads(args.oracle_evidence.read_text())
        if not isinstance(oracle_evidence, list) or any(not isinstance(row, dict) for row in oracle_evidence):
            raise ValueError('Oracle evidence must be a list of records')
    index = Converter(sources, texts, args.output, english=args.english, read_artifacts=read_artifacts,
                      oracle_evidence=oracle_evidence,
                      group_enable_artifacts=args.group_enable_artifacts).finish()
    print(json.dumps(index, indent=2))


if __name__ == "__main__":
    main()
