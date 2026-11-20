#!/usr/bin/env python3
"""Independently verify compiled Toyota data, provenance, counts and APK scope.

This verifier executes neither vendor code nor source scripts. A source-owner
attestation is not a conversion result: the pinned objects and per-item ledger
are checked separately, and completeness is recomputed, never trusted as a flag.
"""
import argparse
from collections import Counter
from functools import lru_cache
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile

from vehicle_bundle import (FORMAT, VERSION, SOURCE_SHA256, MAGIC, MAX_CONTAINER_BYTES,
                            MAX_COMPRESSED_BYTES, MAX_OBJECT_BYTES, canonical, read_object)
from source_verification import load_verification
from vehicle_system_validation import KINDS, require, integer, validate_system

MAX_INDEX_BYTES = 256 * 1024
SUMMARY_COUNTS = ("systems", "enabledSystems", "unresolvedSourceRecords", "manualLeaves",
                  "manualLeavesWithSystemJoin", "normalizedVehicleChoices", "unresolvedHierarchyChoices")


def valid_range(ref, total):
    require(isinstance(ref, dict) and set(ref) == {"offset", "bytes", "jsonBytes", "sha256"}, "Invalid range fields")
    integer(ref["offset"], "range.offset", len(MAGIC), total)
    integer(ref["bytes"], "range.bytes", 1, MAX_COMPRESSED_BYTES)
    integer(ref["jsonBytes"], "range.jsonBytes", 1, MAX_OBJECT_BYTES)
    require(ref["offset"] + ref["bytes"] <= total and isinstance(ref["sha256"], str) and
            re.fullmatch("[0-9a-f]{64}", ref["sha256"]) is not None, "Invalid pinned object range")


def load_index(path):
    raw = path.read_bytes()
    require(len(raw) <= MAX_INDEX_BYTES, "Vehicle-pack index exceeds its APK budget")
    index = json.loads(raw)
    require(isinstance(index, dict) and index.get("format") == FORMAT and
            type(index.get("formatVersion")) is int and index["formatVersion"] == VERSION and
            type(index.get("converterVersion")) is int and index["converterVersion"] == 1 and
            index.get("sourceSha256") == SOURCE_SHA256 and index.get("manualSelectionOnly") is True,
            "Unknown or unpinned vehicle-pack index")
    summary = index.get("summary")
    require(isinstance(summary, dict) and summary.get("sourceVerification") == load_verification(),
            "Vehicle-pack pin is missing the exact whole-archive owner verification")
    require(type(summary.get("complete")) is bool, "Missing computed conversion completeness")
    integer(summary.get("executableDefinitions"), "summary.executableDefinitions", maximum=1_000_000_000)
    for field in SUMMARY_COUNTS:
        if field in summary: integer(summary[field], "summary." + field, maximum=1_000_000_000)
    if "converted" in summary:
        require(isinstance(summary["converted"], dict) and summary["converted"].keys() == KINDS,
                "Invalid summary converted categories")
        for field, value in summary["converted"].items():
            integer(value, "summary.converted." + field, maximum=1_000_000_000)
    ledger = summary.get("unresolvedLedger")
    if ledger is not None:
        require(isinstance(ledger, dict) and ledger.keys() == {"file", "bytes", "sha256"} and
                ledger["file"] == "unresolved-items.jsonl.gz" and isinstance(ledger["sha256"], str) and
                re.fullmatch("[0-9a-f]{64}", ledger["sha256"]) is not None, "Invalid pinned conversion ledger")
        integer(ledger["bytes"], "unresolved ledger bytes", 1, MAX_CONTAINER_BYTES)
    digest = index.get("containerSha256")
    require(isinstance(digest, str) and re.fullmatch("[0-9a-f]{64}", digest) is not None and
            index.get("container") == f"toyota-{digest}.bin", "Unsafe or unpinned container filename")
    integer(index.get("containerBytes"), "containerBytes", len(MAGIC), MAX_CONTAINER_BYTES)
    valid_range(index.get("root"), index["containerBytes"])
    valid_range(index.get("catalogRoot"), index["containerBytes"])
    return index


def references(value):
    if isinstance(value, dict):
        if set(value) == {"offset", "bytes", "jsonBytes", "sha256"}:
            yield value
        else:
            for item in value.values(): yield from references(item)
    elif isinstance(value, list):
        for item in value: yield from references(item)


def verify_ledger(directory, summary):
    """A streaming, bounded ledger pass stores only each exact item ID and digest."""
    pin = summary.get("unresolvedLedger")
    require(isinstance(pin, dict), "Full conversion verification requires the pinned per-item ledger")
    path = directory / pin["file"]
    require(not path.is_symlink() and path.is_file() and path.stat().st_size == pin["bytes"],
            "Conversion ledger length/path differs")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
    require(digest.hexdigest() == pin["sha256"], "Conversion ledger checksum differs")
    rows = {}; expanded = 0
    with gzip.open(path, "rb") as stream:
        while True:
            line = stream.readline(1024 * 1024 + 1)
            if not line: break
            expanded += len(line)
            require(len(line) <= 1024 * 1024 and expanded <= 4 * 1024**3, "Unbounded conversion ledger")
            item = json.loads(line)
            require(isinstance(item, dict) and item.get("enabled") is False and item.get("sourceVerified") is True and
                    isinstance(item.get("id"), str) and bool(item["id"]) and
                    isinstance(item.get("reasons"), list) and bool(item["reasons"]) and
                    all(isinstance(value, str) and value.strip() for value in item["reasons"]),
                    "Invalid unresolved source ledger item")
            require(item["id"] not in rows and len(rows) < 10_000_000, "Duplicate/excessive unresolved source ledger item")
            rows[item["id"]] = hashlib.sha256(canonical(item)).digest()
    require(summary.get("unresolvedSourceRecords") == len(rows),
            "Summary unresolved count differs from actual per-item ledger")
    return rows


def system_brief(value):
    return {field: value.get(field) for field in ("id", "name", "enabled", "counts", "reasons")}


def verify(directory, *, index_only=False, apk=None, require_executable=False):
    index = load_index(directory / "index.json")
    summary = index["summary"]
    objects = 0
    if require_executable:
        require(not index_only, "Executable acceptance requires checking the actual data objects")
        require(summary["executableDefinitions"] > 0,
                "Acceptance requires real nonempty executable Toyota definitions, not an index-only checkpoint")
    if not index_only:
        ledger = verify_ledger(directory, summary)
        container = directory / index["container"]
        require(not container.is_symlink() and container.is_file() and container.stat().st_size == index["containerBytes"],
                "Container length/symlink mismatch")
        digest = hashlib.sha256()
        with container.open("rb") as stream:
            require(stream.read(len(MAGIC)) == MAGIC, "Wrong container magic")
            stream.seek(0)
            for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
        require(digest.hexdigest() == index["containerSha256"], "Container checksum mismatch")
        seen = {}; systems = {}; converted = Counter(dict.fromkeys(KINDS, 0)); incomplete = False
        manual_leaves = set(); joined_leaves = set(); normalized_leaves = set(); unresolved_choices = 0
        descriptors = {}; definitions = {}; enabled_objects = {}; runnable_candidates = []

        @lru_cache(maxsize=2048)
        def source_document(offset, size, json_size, digest):
            return read_object(container, {"offset": offset, "bytes": size, "jsonBytes": json_size, "sha256": digest})

        def source(ref):
            valid_range(ref, index["containerBytes"])
            return source_document(ref["offset"], ref["bytes"], ref["jsonBytes"], ref["sha256"])

        def source_systems(rows):
            require(isinstance(rows, list) and len(rows) <= 1024, "Invalid manual system descriptors")
            identities = set()
            for row in rows:
                require(isinstance(row, dict) and isinstance(row.get("id"), str) and row["id"] not in identities,
                        "Duplicate/invalid manual system descriptor")
                identities.add(row["id"])
                valid_range(row.get("object"), index["containerBytes"])
                key = row["object"]["sha256"]; brief = system_brief(row)
                require(key not in descriptors or descriptors[key] == brief, "Conflicting descriptors for one exact system")
                descriptors[key] = brief

        pending = list(references(index))
        while pending:
            ref = pending.pop()
            valid_range(ref, index["containerBytes"])
            object_key = ref["sha256"]
            if object_key in seen:
                require(seen[object_key] == ref, "Conflicting references to one object")
                continue
            value = read_object(container, ref)
            require(isinstance(value, dict) and isinstance(value.get("kind"), str), "Pack object is not a typed JSON document")
            kind = value["kind"]
            if kind == "vehicle-system":
                actual = validate_system(value)
                require(isinstance(value.get("id"), str) and value["id"] not in systems, "Duplicate source system identity")
                systems[value["id"]] = value["enabled"]
                if value["enabled"]:
                    enabled_objects[value["id"]] = value
                definitions[object_key] = system_brief(value)
                converted.update(actual)
                incomplete = incomplete or not value["enabled"] or any(count["unresolved"] != 0 for count in value["counts"].values())
                for field, expected_kind, fields in (("ecuSource", "ecu-source", ("sourceFile", "sha256")),
                        ("functionSource", "function-source", ("sourceRecord", "functionKey", "declaredLiveRows"))):
                    stub = value.get(field)
                    if isinstance(stub, dict) and "object" in stub:
                        metadata = source(stub["object"])
                        require(metadata.get("kind") == expected_kind and all(metadata.get(f) == stub.get(f) for f in fields) and
                                "pages" not in stub and "livePages" not in stub,
                                "Shared metadata crossed an exact ECU/function boundary")
            if kind == "manual-vehicle":
                identity = value.get("id")
                require(isinstance(identity, str) and identity not in manual_leaves and
                        type(value.get("hierarchyResolved")) is bool and type(value.get("systemJoinResolved")) is bool and
                        value.get("enabled") is False, "Invalid source manual vehicle")
                manual_leaves.add(identity)
                if value["systemJoinResolved"]: joined_leaves.add(identity)
                unresolved_choices += not value["hierarchyResolved"]
                source_systems(value.get("systems"))
            if kind == "converted-vehicle":
                identity = value.get("id")
                require(isinstance(identity, str) and identity not in normalized_leaves and value.get("hierarchyResolved") is True,
                        "Invalid/duplicate normalized manual vehicle")
                normalized_leaves.add(identity)
                if value.get("systemsSource") is not None:
                    manual_source = source(value["systemsSource"])
                    require("systems" not in value and manual_source.get("kind") == "manual-vehicle" and
                            manual_source.get("hierarchyResolved") is True and manual_source.get("id") == value.get("systemsSourceId") and
                            (value.get("sourceRecords") or [None])[-1] == manual_source.get("id") and
                            value.get("sourceSelection") == [s.get("name") for s in manual_source.get("selection", [])],
                            "Shared system descriptors crossed an exact manual vehicle boundary")
                else:
                    source_systems(value.get("systems"))
            if kind == "disabled-items":
                items = value.get("items")
                require(isinstance(items, list) and 1 <= len(items) <= 128, "Invalid unresolved-item page")
                for item in items:
                    require(isinstance(item, dict) and isinstance(item.get("id"), str) and
                            ledger.get(item["id"]) == hashlib.sha256(canonical(item)).digest(),
                            "Unresolved page differs from its exact pinned per-item ledger")
            if kind == "bring-up-candidates":
                candidates = value.get("candidates")
                require(isinstance(candidates, list) and 1 <= len(candidates) <= 128, "Invalid bring-up candidate page")
                for candidate in candidates:
                    support = candidate.get("supportRecords", [])
                    require(bool(candidate.get("sourceRecords")) and bool(support) and len(set(support)) == len(support) and
                            candidate.get("supportCount") == len(support),
                            "Bring-up candidates cannot overstate source support")
                    if candidate.get("runtime") is None:
                        require(bool(candidate.get("blockers")),
                                "Untranslated bring-up candidates cannot become executable")
                    else:
                        # Only the pinned ECMECT slice may carry a runnable candidate;
                        # exact equality with candidate_for() is enforced after traversal.
                        runnable_candidates.append(candidate)
            seen[object_key] = ref
            objects += 1
            require(objects <= 1_000_000, "Too many vehicle-pack objects")
            pending.extend(references(value))
        spans = sorted((ref["offset"], ref["offset"] + ref["bytes"]) for ref in seen.values())
        require(not any(end > start for (_, end), (start, _) in zip(spans, spans[1:])), "Overlapping vehicle-pack objects")
        require(all(definitions.get(key) == brief for key, brief in descriptors.items()),
                "A manual system descriptor differs from the exact executable/disabled object")
        executable = sum(converted.values())
        require(summary["executableDefinitions"] == executable, "Summary executableDefinitions differs from actual compiled objects")
        require(summary.get("converted") == dict(converted), "Summary category counts differ from actual compiled objects")
        computed_counts = {"systems": len(systems), "enabledSystems": sum(systems.values()), "manualLeaves": len(manual_leaves),
            "manualLeavesWithSystemJoin": len(joined_leaves), "normalizedVehicleChoices": len(normalized_leaves),
            "unresolvedHierarchyChoices": unresolved_choices}
        mismatches = {field: {"summary": summary.get(field), "actual": count}
                      for field, count in computed_counts.items() if summary.get(field) != count}
        require(not mismatches, "Summary system/manual hierarchy counts differ from the actual object graph: " + str(mismatches))
        complete = (executable > 0 and bool(systems) and not incomplete and not ledger and
                    unresolved_choices == 0 and len(joined_leaves) == len(manual_leaves) == len(normalized_leaves) and bool(manual_leaves))
        require(summary["complete"] is complete, "Summary complete differs from computed source/feature coverage")
        # A pack carrying the staged mandatory-ECMECT compiler artifacts is the production
        # single-slice pack: the enabled set must be exactly that one pinned, read-only
        # system, re-verified against the artifact allowlist. Packs without the staged
        # artifacts (synthetic test fixtures) keep the generic per-system validation.
        artifact = directory / "mandatory-ecmect-source-reads.json"
        if artifact.is_file():
            from toyota_ecmect_pack import ARTIFACTS, candidate_for, load_review, verify_enabled
            require(all((directory / name).is_file() for name in ARTIFACTS) and
                    (directory / "ecmect-live-omissions.json").is_file(),
                    "Enabled ECMECT pack is missing staged compiler artifacts or the omissions ledger")
            review = load_review(directory)
            require(sorted(enabled_objects) == [review["systemId"]],
                    "Enabled systems differ from the single pinned read-only ECMECT slice")
            verify_enabled(enabled_objects[review["systemId"]], review)
            require(runnable_candidates == [candidate_for(enabled_objects[review["systemId"]], review)],
                    "Runnable bring-up candidate differs from the pinned ECMECT slice")
            omissions = json.loads((directory / "ecmect-live-omissions.json").read_bytes())
            require(isinstance(omissions, dict) and omissions.get("kind") == "ecmect-live-omissions" and
                    omissions.get("systemId") == review["systemId"] and
                    omissions.get("omittedLiveRows") == review["uncompiledLiveRows"] ==
                        summary.get("ecmectOmittedLiveRows") and
                    isinstance(omissions.get("items"), list) and
                    len(omissions["items"]) == omissions["omittedLiveRows"],
                    "Omissions ledger differs from the pinned review/summary")
        else:
            require(not runnable_candidates, "Runnable bring-up candidate without the pinned ECMECT artifacts")
    if apk:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            asset = "assets/toyota-vehicle-packs/index.json"
            require(names.count(asset) == 1 and archive.read(asset) == (directory / "index.json").read_bytes(),
                    "APK is missing the exact generated vehicle-pack pin")
            require(not any(name.startswith("assets/toyota-source/") or re.search(r"(?:^|/)toyota-[0-9a-f]{64}\.bin$", name)
                            or "libTOYOTA" in name for name in names), "APK contains the large corpus or vendor executables")
    return {"indexBytes": (directory / "index.json").stat().st_size, "containerBytes": index["containerBytes"],
            "verifiedObjects": objects, "executableDefinitions": summary["executableDefinitions"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--index-only", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--require-executable", action="store_true", help="Reject index-only/zero-definition acceptance")
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.directory, index_only=args.index_only, apk=args.apk,
                                require_executable=args.require_executable), sort_keys=True))
    except ValueError as error:
        # GitHub job-log storage redirects are not always reachable by an agent.
        # Surface the exact bounded validation failure in the ordinary annotations
        # API as well; preserve the exception/nonzero exit and never mask a failure.
        message = str(error)[:4096].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::error title=Toyota compiled bundle validation::" + message, flush=True)
        raise
