#!/usr/bin/env python3
"""Verify bounded, data-only vehicle packs and that an APK contains ONLY their small pin.

No vendor code/scripts are run. Corrupt source, JSON, hashes or ranges are fatal;
missing mappings remain disabled, never replaced by generic diagnostic data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from vehicle_bundle import (FORMAT, VERSION, SOURCE_SHA256, MAGIC, MAX_CONTAINER_BYTES,
                            MAX_COMPRESSED_BYTES, MAX_OBJECT_BYTES, read_object)

MAX_INDEX_BYTES = 256 * 1024


def load_index(path):
    raw = path.read_bytes()
    if len(raw) > MAX_INDEX_BYTES:
        raise ValueError("Vehicle-pack index exceeds its APK budget")
    index = json.loads(raw)
    if (index.get("format") != FORMAT or index.get("formatVersion") != VERSION
            or index.get("converterVersion") != 1 or index.get("sourceSha256") != SOURCE_SHA256
            or index.get("manualSelectionOnly") is not True):
        raise ValueError("Unknown or unpinned vehicle-pack index")
    digest = index.get("containerSha256")
    if not isinstance(digest, str) or not re.fullmatch("[0-9a-f]{64}", digest) or index.get("container") != f"toyota-{digest}.bin":
        raise ValueError("Unsafe or unpinned container filename")
    if type(index.get("containerBytes")) is not int or not len(MAGIC) <= index["containerBytes"] <= MAX_CONTAINER_BYTES:
        raise ValueError("Container size outside its bound")
    for ref in (index["root"], index["catalogRoot"]):
        if (not isinstance(ref, dict) or set(ref) != {"offset", "bytes", "jsonBytes", "sha256"}
                or any(type(ref[k]) is not int for k in ("offset", "bytes", "jsonBytes"))
                or ref["offset"] < len(MAGIC) or not 1 <= ref["bytes"] <= MAX_COMPRESSED_BYTES
                or not 1 <= ref["jsonBytes"] <= MAX_OBJECT_BYTES
                or ref["offset"] + ref["bytes"] > index["containerBytes"]
                or not isinstance(ref["sha256"], str) or not re.fullmatch("[0-9a-f]{64}", ref["sha256"])):
            raise ValueError("Invalid index range")
    return index


def references(value):
    if isinstance(value, dict):
        if set(value) == {"offset", "bytes", "jsonBytes", "sha256"}:
            yield value
        else:
            for item in value.values(): yield from references(item)
    elif isinstance(value, list):
        for item in value: yield from references(item)


def verify(directory, *, index_only=False, apk=None):
    index = load_index(directory / "index.json")
    objects = 0
    if not index_only:
        container = directory / index["container"]
        if container.is_symlink() or container.stat().st_size != index["containerBytes"]:
            raise ValueError("Container length/symlink mismatch")
        digest = hashlib.sha256()
        with container.open("rb") as stream:
            if stream.read(len(MAGIC)) != MAGIC: raise ValueError("Wrong container magic")
            stream.seek(0)
            for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
        if digest.hexdigest() != index["containerSha256"]: raise ValueError("Container checksum mismatch")
        seen = {}
        pending = list(references(index))
        while pending:
            ref = pending.pop()
            key = ref["sha256"]
            if key in seen:
                if seen[key] != ref: raise ValueError("Conflicting references to one object")
                continue
            value = read_object(container, ref)
            if not isinstance(value, dict) or not isinstance(value.get("kind"), str):
                raise ValueError("Pack object is not a typed JSON document")
            if value["kind"] == "vehicle-system":
                if value.get("enabled") is not False or value.get("data") != {} or not value.get("reasons"):
                    # Remove this explicit guard ONLY when a real service/formula converter with
                    # tests has been implemented. A source verified flag is not proof of that work.
                    raise ValueError("This converter version has no resolved executable Toyota systems")
                if any(c["converted"] != 0 for c in value["counts"].values()):
                    raise ValueError("Disabled system overstates its conversion coverage")
            if value["kind"] == "bring-up-candidates":
                candidates = value.get("candidates")
                if not isinstance(candidates, list) or not 1 <= len(candidates) <= 128:
                    raise ValueError("Invalid bring-up candidate page")
                for candidate in candidates:
                    support = candidate.get("supportRecords", [])
                    if (candidate.get("runtime") is not None or not candidate.get("blockers")
                            or not candidate.get("sourceRecords") or not support
                            or len(set(support)) != len(support) or candidate.get("supportCount") != len(support)):
                        raise ValueError("Untranslated bring-up candidates cannot become executable or overstate source support")
            seen[key] = ref
            objects += 1
            if objects > 1_000_000: raise ValueError("Too many vehicle-pack objects")
            pending.extend(references(value))
        spans = sorted((r["offset"], r["offset"] + r["bytes"]) for r in seen.values())
        if any(b > c for (_, b), (c, _) in zip(spans, spans[1:])): raise ValueError("Overlapping vehicle-pack objects")
    if apk:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            asset = "assets/toyota-vehicle-packs/index.json"
            if names.count(asset) != 1 or archive.read(asset) != (directory / "index.json").read_bytes():
                raise ValueError("APK is missing the exact generated vehicle-pack pin")
            if any(n.startswith("assets/toyota-source/") or re.search(r"(?:^|/)toyota-[0-9a-f]{64}\.bin$", n)
                   or "libTOYOTA" in n for n in names):
                raise ValueError("APK contains the large corpus or vendor executables")
    return {"indexBytes": (directory / "index.json").stat().st_size, "containerBytes": index["containerBytes"],
            "verifiedObjects": objects, "executableDefinitions": index["summary"]["executableDefinitions"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--index-only", action="store_true")
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.directory, index_only=args.index_only, apk=args.apk), sort_keys=True))
