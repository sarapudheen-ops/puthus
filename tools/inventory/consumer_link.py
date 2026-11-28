#!/usr/bin/env python3
"""Inventory-8: does a brand's own native reader actually link to its decoded data?

Inventory-7 established, for exactly one (archive, family) pair, that a decoded
member parses with an existing ``tools/thinkdiag`` reader *and* that the
archive's own role library exports a consumer this repo already cites for that
reader. It also recorded what was still missing before any decoded field could be
called a diagnostic address, service, PID or timing. This stage measures that
missing evidence. It measures three things, each with a positive and a negative
Toyota control so that a negative brand result is interpretable rather than
merely absent:

1. **Consumer code identity.** Is the brand's own exported consumer the same
   *code* as Toyota's same-named function? Measured by byte-identity of the
   function body at its own symbol address and size, never by its name. A shared
   export name is not evidence; identical bytes are the same standard Inventory-7
   already accepts for ``libDEVICEID.so``.
2. **Filename link.** Does any library in the brand's own archive contain an
   ASCII literal naming the decoded member? This is the only static link between
   that consumer and that data file, and it is the kind of independent evidence
   required before anything is enabled.
3. **Reader text.** Does the established reader surface any text for this family?
   A reader that returns only integers cannot make an opaque id mean something,
   so this bounds what any amount of further static work could establish.

Nothing here labels a value, infers an operation, writes a pack or changes the
app. ``meaningsEstablished`` is ``False`` for every row by construction: even
identical code plus a filename link establishes the record layout and the link,
not which real vehicle system an opaque identifier denotes.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import io
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

import family_evidence as fe  # noqa: E402  (also puts tools/thinkdiag on sys.path)

from analyze_toyota import native_info  # noqa: E402
from decode_toyota import elf_bytes  # noqa: E402
from elftools.elf.elffile import ELFFile  # noqa: E402
from yzjm import decode_yzjm  # noqa: E402

old = fe.old
control = fe.control

FORMAT = "bod3-consumer-link"
FORMAT_VERSION = 1
LABEL = ("measurement only: consumer code identity, filename link and reader text are observed, "
         "never interpreted; no field is labelled and no operation is enabled")

# Pairs Inventory-7 actually had something to judge. Bounded so the job downloads
# the candidate archives only, not the whole 5 GB corpus.
MAX_TARGETS = 32
MAX_SAMPLES = 4
MAX_LINKS = 8
MAX_LITERALS = 40
LINK_MIN_LENGTH = 4
LIBRARY_MAX_BYTES = 24 * 1024 * 1024
ASCII_RUN = re.compile(rb"[\x20-\x7e]{%d,180}" % LINK_MIN_LENGTH)


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


# ------------------------------------------------------------------ ELF reads

def va_of(data: bytes, offset: int) -> int | None:
    """File offset -> virtual address, or None when no PT_LOAD segment covers it."""
    try:
        elf = ELFFile(io.BytesIO(data))
        for segment in elf.iter_segments():
            header = segment.header
            if (header.p_type == "PT_LOAD" and header.p_offset <= offset
                    and offset < header.p_offset + header.p_filesz):
                return header.p_vaddr + (offset - header.p_offset)
    except Exception:  # pyelftools raises several unrelated types on odd inputs.
        return None
    return None


def read_library(path: Path, member: str) -> tuple[bytes | None, dict]:
    """One library's stored bytes from an archive, bounded."""
    try:
        with zipfile.ZipFile(path) as archive:
            wanted = [i for i in archive.infolist()
                      if not i.is_dir() and PurePosixPath(i.filename).name == member]
            if not wanted:
                return None, {"status": "absent"}
            if len(wanted) != 1:
                return None, {"status": "ambiguous", "candidates": len(wanted)}
            if wanted[0].file_size > LIBRARY_MAX_BYTES:
                return None, {"status": "too large", "bytes": wanted[0].file_size}
            data = archive.read(wanted[0])
    except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError) as error:
        return None, {"status": "unreadable", "error": f"{type(error).__name__}: {error}"}
    return data, {"status": "present", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def function_bytes(data: bytes, library: str, symbol: str) -> tuple[bytes | None, dict]:
    """The exported function's own bytes, at its own symbol address and size."""
    try:
        functions = native_info(data, library)["functions"]
    except Exception as error:  # an unparsable library is data, not a crash.
        return None, {"status": "unparsable", "error": f"{type(error).__name__}: {error}"}
    matches = [row for row in functions if row["name"] == symbol]
    if not matches:
        return None, {"status": "not exported"}
    defined = [row for row in matches if row["defined"]]
    if not defined:
        return None, {"status": "imported only", "occurrences": len(matches)}
    row = defined[0]
    address, size = int(row["address"], 16), int(row["size"])
    if size <= 0:
        return None, {"status": "no symbol size", "address": row["address"], "occurrences": len(defined)}
    try:
        return elf_bytes(data, address, size), {"status": "present", "address": row["address"], "bytes": size,
                                                "occurrences": len(defined)}
    except Exception as error:
        return None, {"status": "out of bounds", "address": row["address"], "bytes": size,
                      "error": f"{type(error).__name__}: {error}"}


def compare_functions(brand: bytes | None, brand_info: dict,
                      toyota: bytes | None, toyota_info: dict) -> dict:
    """Byte-identity of two function bodies, with the first difference located."""
    out = {"brand": brand_info, "toyota": toyota_info, "identical": False, "firstDifference": None,
           "brandSha256": hashlib.sha256(brand).hexdigest() if brand else None,
           "toyotaSha256": hashlib.sha256(toyota).hexdigest() if toyota else None}
    if brand is None or toyota is None:
        out["reason"] = f"brand: {brand_info.get('status')}; toyota: {toyota_info.get('status')}"
        return out
    out["identical"] = brand == toyota
    if not out["identical"]:
        shared = min(len(brand), len(toyota))
        offset = next((i for i in range(shared) if brand[i] != toyota[i]), shared)
        out["firstDifference"] = offset
        out["sizeDelta"] = len(brand) - len(toyota)
    return out


# --------------------------------------------------------------- measurements

def filename_links(path: Path, libraries: list[str], needles: dict[str, str]) -> list[dict]:
    """ASCII literals inside this archive's own libraries that name a member.

    ``needles`` maps the literal being searched for to why it was chosen. Matching
    is case-insensitive on the member stem, which is the same normalisation
    Inventory-4 uses, so a library naming ``sys_list`` counts as naming
    ``SYS_LIST.BIN``. The literal is recorded verbatim and truncated; it is never
    interpreted.
    """
    found: list[dict] = []
    for library in libraries:
        data, info = read_library(path, library)
        if data is None:
            continue
        for run in ASCII_RUN.findall(data)[:4000]:
            text = run.decode("ascii")
            upper = text.upper()
            for needle, why in needles.items():
                if needle.upper() not in upper:
                    continue
                offset = data.find(run)
                found.append({"library": library, "needle": needle, "why": why, "literal": text[:MAX_LITERALS],
                              "offset": offset, "va": va_of(data, offset) if offset >= 0 else None})
                break
            if len(found) >= MAX_LINKS:
                return found
    return found


def structure_text(family_key: str, payload: bytes) -> dict:
    """Does the established reader for this family surface any text at all?"""
    family = next((item for item in fe.FAMILIES if item["key"] == family_key), None)
    if family is None:
        return {"status": "unknown family", "textValues": 0, "valueKinds": []}
    try:
        structure = family["structure"](payload)
    except (ValueError, IndexError, KeyError, TypeError, OverflowError, StopIteration) as error:
        return {"status": f"reader rejected: {type(error).__name__}", "textValues": 0, "valueKinds": []}
    kinds: dict[str, int] = {}
    text_values = 0

    def walk(value: object) -> None:
        nonlocal text_values
        if isinstance(value, dict):
            for item in value.values():
                walk(item)
        elif isinstance(value, (list, tuple)):
            for item in value:
                walk(item)
        elif isinstance(value, bytes):
            kinds["bytes"] = kinds.get("bytes", 0) + 1
            if re.fullmatch(rb"[\x20-\x7e]+", value or b""):
                text_values += 1
        elif isinstance(value, str):
            kinds["str"] = kinds.get("str", 0) + 1
            if value.strip():
                text_values += 1
        elif isinstance(value, bool):
            kinds["bool"] = kinds.get("bool", 0) + 1
        elif isinstance(value, int):
            kinds["int"] = kinds.get("int", 0) + 1
        elif value is None:
            kinds["none"] = kinds.get("none", 0) + 1
        else:
            kinds[type(value).__name__] = kinds.get(type(value).__name__, 0) + 1

    walk(structure)
    return {"status": "parsed", "textValues": text_values, "valueKinds": sorted(kinds),
            "valueCounts": dict(sorted(kinds.items())),
            "topKeys": sorted(list(structure)[:12]) if isinstance(structure, dict) else []}


def measure(path: Path, target: dict, toyota_functions: dict[str, dict[str, bytes]],
            budget: dict) -> dict:
    """All three measurements for one (archive, family) pair."""
    brand = target["brand"]
    family = next((item for item in fe.FAMILIES if item["key"] == target["family"]), None)
    samples = target["samples"][:MAX_SAMPLES]
    member_names = [sample["member"] for sample in samples]

    identities = []
    for consumer in target["consumers"]:
        if consumer["kind"] != "own-role-library":
            continue
        for library in consumer["exportedBy"]:
            data, info = read_library(path, library)
            if data is None:
                identities.append({"consumer": consumer["consumer"], "library": library,
                                   "brand": info, "toyota": {"status": "not read"}, "identical": False,
                                   "reason": info.get("status")})
                continue
            body, body_info = function_bytes(data, library, consumer["consumer"])
            reference = toyota_functions.get(consumer["toyotaLibrary"], {})
            toyota_body = reference.get(consumer["consumer"])
            toyota_info = {"status": "present" if toyota_body is not None else "not in reference",
                           "bytes": len(toyota_body) if toyota_body is not None else None}
            identities.append({"consumer": consumer["consumer"], "library": library,
                               "toyotaLibrary": consumer["toyotaLibrary"],
                               **compare_functions(body, body_info, toyota_body, toyota_info)})
            budget["functionsCompared"] += 1

    needles: dict[str, str] = {}
    for name in member_names:
        base = PurePosixPath(fe.canonical(name)).name
        needles[base] = "member basename"
        stem = re.sub(r"(?i)\.bin$", "", base)
        if stem and stem.upper() != base.upper():
            needles[stem] = "member name without extension"
        canonical_stem = fe.stem(name)
        if canonical_stem and canonical_stem not in needles:
            needles[canonical_stem] = "Inventory-4 stem normalisation"
    libraries = sorted(set(target["ownRoleLibraries"]) | set(target["librariesParsed"]))
    links = filename_links(path, libraries, needles)

    texts = []
    table, device, _ = fe.own_table(path)
    if table is not None:
        with fe.ArchiveScanner(path) as scanner:
            payloads = scanner.read(set(member_names))
        for sample in samples:
            raw = payloads.get(sample["member"])
            if raw is None or not raw.startswith(fe.YZJM_HEAD):
                texts.append({"member": sample["member"], "status": "not re-readable as YZJM"})
                continue
            try:
                payload = decode_yzjm(raw, table).payload
            except (ValueError, IndexError, KeyError, TypeError, OverflowError) as error:
                texts.append({"member": sample["member"], "status": f"decode failed: {type(error).__name__}"})
                continue
            texts.append({"member": sample["member"], "payloadSha256": hashlib.sha256(payload).hexdigest(),
                          "decodedBytes": len(payload), **structure_text(target["family"], payload)})
            budget["payloadsMeasured"] += 1
    else:
        texts.append({"member": None, "status": f"no format table: {device.get('status')}"})

    identical = [row for row in identities if row["identical"]]
    text_values = sum(row.get("textValues", 0) for row in texts)
    return {
        **target, "wrapperConfidence": target["wrapperConfidence"],
        "membersExamined": member_names, "libraryStatus": device.get("status"),
        "consumerIdentity": identities,
        "identicalConsumers": sorted({row["consumer"] for row in identical}),
        "comparedConsumers": len(identities),
        "filenameLinks": links,
        "readerText": texts,
        "readerTextValues": text_values,
        "verdict": verdict(identical, links),
        "meaningsEstablished": False,
        "meaningBlockers": meaning_blockers(identities, identical, links, texts, text_values),
    }


def verdict(identical: list[dict], links: list[dict]) -> str:
    if identical and links:
        return "CODE-IDENTICAL AND LINKED"
    if identical:
        return "CODE-IDENTICAL, NO FILENAME LINK"
    if links:
        return "FILENAME LINK, CODE DIFFERS"
    return "NOT ESTABLISHED"


def meaning_blockers(identities: list[dict], identical: list[dict], links: list[dict],
                     texts: list[dict], text_values: int) -> list[str]:
    """Exactly what is still missing before a decoded field could be named."""
    missing = []
    if not identities:
        missing.append("no own-role-library consumer could be compared, so code identity is unmeasured")
    elif not identical:
        missing.append("this brand's own consumer is NOT byte-identical to Toyota's same-named function, "
                       "so the code that reads these bytes is not the code whose behaviour this repo has "
                       "established for Toyota")
    if not links:
        missing.append("no library in this archive contains an ASCII literal naming the decoded member, "
                       "so no static link ties that consumer to that data file")
    if text_values == 0:
        missing.append("the established reader for this family returns no text value at all "
                       f"({', '.join(texts[0].get('valueKinds') or ['nothing parsed']) if texts else 'unmeasured'}), "
                       "so every field it yields is an opaque identifier; no static reading of this brand's "
                       "data can say which real system, service or PID an identifier denotes")
    missing.append("no vendor documentation, runtime observation on a vehicle or ECU, and no disassembly of "
                   "this brand's own reader in this repo ties any decoded field to a diagnostic address, "
                   "service, PID or timing")
    return missing


# -------------------------------------------------------------------- control

def toyota_reference_functions(path: Path, families: list[str]) -> tuple[dict[str, dict[str, bytes]], dict]:
    """Toyota's own cited consumer bodies, keyed library -> symbol."""
    wanted: dict[str, set[str]] = {}
    for key in families:
        family = next((item for item in fe.FAMILIES if item["key"] == key), None)
        if family is None:
            continue
        for library, symbols in family["consumers"].items():
            wanted.setdefault(library, set()).update(symbols)
    out: dict[str, dict[str, bytes]] = {}
    errors: dict[str, str] = {}
    for library, symbols in sorted(wanted.items()):
        data, info = read_library(path, library)
        if data is None:
            errors[library] = str(info.get("status"))
            continue
        bodies: dict[str, bytes] = {}
        for symbol in sorted(symbols):
            body, body_info = function_bytes(data, library, symbol)
            if body is not None:
                bodies[symbol] = body
            else:
                errors[f"{library}:{symbol}"] = str(body_info.get("status"))
        out[library] = bodies
    return out, errors


def control_check(reference: dict[str, dict[str, bytes]], toyota_target: dict | None,
                  toyota_links: list[dict], negative_links: list[dict]) -> dict:
    """Positive and negative controls for all three measurements.

    A negative result for a brand is only interpretable when the same measurement
    is shown to be able to return a positive one. This is the discipline the
    ``decode_yzjm`` arity bug broke: a harness that cannot succeed reports
    failure for everything, and that looks like a finding.
    """
    checks: list[dict] = []
    bodies = reference.get("libTOYOTA_FILE.so", {})
    symbols = sorted(bodies)
    first = symbols[0] if symbols else None
    second = next((symbol for symbol in symbols if symbol != first), None)

    def info(symbol: str | None) -> dict:
        return {"status": "present", "bytes": len(bodies[symbol])} if symbol else {"status": "absent"}

    positive = (compare_functions(bodies[first], info(first), bodies[first], info(first))
                if first else None)
    negative = (compare_functions(bodies[second], info(second), bodies[first], info(first))
                if first and second else None)
    checks.append({
        "name": "identity positive: a Toyota consumer body compared with itself is byte-identical",
        "passed": bool(positive and positive["identical"]),
        "detail": (f"{first} ({positive['toyota']['bytes']} bytes, sha256 {str(positive['toyotaSha256'])[:12]})"
                   if positive else "no cited libTOYOTA_FILE.so consumer body could be read")})
    checks.append({
        "name": "identity negative: two different Toyota consumers are not byte-identical",
        "passed": bool(negative and not negative["identical"]),
        "detail": (f"{second} vs {first}: first difference at byte {negative['firstDifference']}, "
                   f"size delta {negative.get('sizeDelta', 0):+d}"
                   if negative else "libTOYOTA_FILE.so yielded fewer than two cited consumer bodies")})
    checks.append({
        "name": "link positive: Toyota's own libraries contain a literal naming a member Toyota's reader accepts",
        "passed": bool(toyota_links),
        "detail": (", ".join(f"{row['library']}:{row['literal']}" for row in toyota_links[:3])
                   if toyota_links else
                   "no literal naming a Toyota member was found, so an empty brand result is uninformative")})
    checks.append({
        "name": "link negative: Toyota's libraries do not name another brand's member",
        "passed": not negative_links,
        "detail": (", ".join(f"{row['library']}:{row['literal']}" for row in negative_links[:3])
                   if negative_links else "no cross-brand literal was found")})
    measured = (toyota_target or {}).get("readerText", [])
    checks.append({
        "name": "reader text positive: the value-kind census is reported for a Toyota member",
        "passed": bool(any(row.get("valueKinds") for row in measured)),
        "detail": (f"{toyota_target['membersExamined'][0]}: kinds "
                   f"{next(row['valueKinds'] for row in measured if row.get('valueKinds'))}"
                   if any(row.get("valueKinds") for row in measured) else "no Toyota member was measured")})
    return {"passed": all(row["passed"] for row in checks), "checks": checks}


# ----------------------------------------------------------------------- state

def targets(state: dict) -> list[dict]:
    """Every (archive, family) pair Inventory-7 actually parsed something for."""
    out = []
    for entry in state["brands"]:
        if entry["brand"] == "TOYOTA":
            continue
        for row in entry["verdicts"]:
            if row["parsed"] <= 0:
                continue
            samples = entry["families"][row["family"]]["samples"]
            out.append({"archive": entry["archive"], "brand": entry["brand"], "sha256": entry["sha256"],
                        "family": row["family"], "inventory7Verdict": row["verdict"], "parsed": row["parsed"],
                        "attempted": row["membersAttempted"], "wrapperConfidence": row["wrapperConfidence"],
                        "ownRoleConsumers": list(row["ownRoleConsumers"]),
                        "ownRoleLibraries": list(row["ownRoleLibraries"]),
                        "consumers": [c for c in row["consumers"] if c["kind"] == "own-role-library"],
                        "librariesParsed": list(entry["librariesParsed"]), "samples": samples})
    order = {"CANDIDATE": 0, "NOT ESTABLISHED": 1}
    out.sort(key=lambda row: (order.get(row["inventory7Verdict"], 2), -row["parsed"], row["archive"]))
    return out[:MAX_TARGETS]


def toyota_target(state: dict) -> dict | None:
    """Toyota's own best pair, used as the positive control for every measurement."""
    entry = next((item for item in state["brands"] if item["brand"] == "TOYOTA"), None)
    if entry is None:
        return None
    best = max((row for row in entry["verdicts"] if row["parsed"] > 0),
               key=lambda row: row["parsed"], default=None)
    if best is None:
        return None
    return {"archive": entry["archive"], "brand": "TOYOTA", "sha256": entry["sha256"],
            "family": best["family"], "inventory7Verdict": best["verdict"], "parsed": best["parsed"],
            "attempted": best["membersAttempted"], "wrapperConfidence": best["wrapperConfidence"],
            "ownRoleConsumers": list(best["ownRoleConsumers"]),
            "ownRoleLibraries": list(best["ownRoleLibraries"]),
            "consumers": [c for c in best["consumers"] if c["kind"] == "own-role-library"],
            "librariesParsed": list(entry["librariesParsed"]),
            "samples": entry["families"][best["family"]]["samples"]}


def summarise(state: dict) -> None:
    rows = state["targets"]
    state["summary"] = {
        "targets": len(rows),
        "archives": len({row["archive"] for row in rows}),
        "functionsCompared": state["budget"].get("functionsCompared", 0),
        "identicalConsumers": sum(len(row["identicalConsumers"]) for row in rows),
        "linkTargets": sum(1 for row in rows if row["filenameLinks"]),
        "links": sum(len(row["filenameLinks"]) for row in rows),
        "textTargets": sum(1 for row in rows if row["readerTextValues"] > 0),
        "meaningsEstablished": sum(1 for row in rows if row["meaningsEstablished"]),
        "verdicts": {name: sum(1 for row in rows if row["verdict"] == name) for name in (
            "CODE-IDENTICAL AND LINKED", "CODE-IDENTICAL, NO FILENAME LINK",
            "FILENAME LINK, CODE DIFFERS", "NOT ESTABLISHED")},
        "packs": 0, "appChanges": 0,
    }


def markdown(state: dict) -> str:
    summary, control_state = state["summary"], state["control"]
    out = ["# Inventory-8: consumer code identity, filename link and reader text", "",
           f"- CI head SHA: `{state['headSha']}`",
           f"- Generated: `{state['generatedUtc']}`",
           f"- Label: {LABEL}",
           f"- Source: Inventory-7 pairs with `parsed > 0` from `{state['inventory7']}`", "",
           "## Controls", "",
           f"Conclusion: **{'PASS' if control_state['passed'] else 'FAIL'}**. A negative result for a brand is "
           "only interpretable when these show each measurement can return both answers.", "",
           "| control | result | detail |", "| --- | --- | --- |"]
    for row in control_state["checks"]:
        out.append(f"| {old.escape(row['name'])} | {'PASS' if row['passed'] else 'FAIL'} "
                   f"| {old.escape(str(row['detail']))} |")
    out += ["", "## Measurements", "",
            "`identity` compares the brand's own exported consumer body, at its own symbol address and size, "
            "with Toyota's same-named function body. `link` counts ASCII literals in this archive's own "
            "libraries that name a decoded member. `reader text` counts text values the established reader "
            "returns for that family; zero means every field is an opaque identifier.", "",
            "| archive | family | Inventory-7 | parsed | consumers compared | identical | links | reader text values | verdict |",
            "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |"]
    for row in state["targets"]:
        out.append(f"| `{old.escape(row['archive'])}` | `{row['family']}` | {row['inventory7Verdict']} "
                   f"| {row['parsed']} | {row['comparedConsumers']} | {len(row['identicalConsumers'])} "
                   f"| {len(row['filenameLinks'])} | {row['readerTextValues']} | {row['verdict']} |")
    toyota = state.get("toyota")
    if toyota:
        out += ["", f"Toyota positive control row: `{toyota['archive']}` · `{toyota['family']}` — "
                    f"{toyota['comparedConsumers']} consumer(s) compared, "
                    f"{len(toyota['identicalConsumers'])} identical, {len(toyota['filenameLinks'])} link(s), "
                    f"{toyota['readerTextValues']} reader text value(s), verdict `{toyota['verdict']}`."]
    out += ["", "## Detail", ""]
    for row in state["targets"]:
        out += [f"### `{old.escape(row['archive'])}` · `{row['family']}`", "",
                f"- Inventory-7: {row['inventory7Verdict']}, {row['parsed']} parsed of {row['attempted']} "
                f"sampled; wrapper {row['wrapperConfidence'].split(':', 1)[0]}.",
                f"- Members examined: {', '.join(f'`{old.escape(name)}`' for name in row['membersExamined']) or 'none'}.",
                "- Consumer code identity:", ""]
        if not row["consumerIdentity"]:
            out.append("  - not measured: no own-role-library consumer for this pair")
        for item in row["consumerIdentity"]:
            status = "**IDENTICAL**" if item["identical"] else "differs"
            detail = (f"first difference at byte {item['firstDifference']}, size delta "
                      f"{item.get('sizeDelta', 0):+d}" if not item["identical"] and
                      item.get("firstDifference") is not None else str(item.get("reason", "")))
            out.append(f"  - `{item['consumer']}` in `{old.escape(item['library'])}` vs "
                       f"`{old.escape(item.get('toyotaLibrary', '?'))}`: {status}"
                       f" ({item['brand'].get('bytes')} vs {item['toyota'].get('bytes')} bytes)"
                       f"{'; ' + old.escape(detail) if detail else ''}")
        out += ["", "- Filename links:", ""]
        if not row["filenameLinks"]:
            out.append("  - none: no library in this archive names any examined member")
        for link in row["filenameLinks"][:MAX_LINKS]:
            va = f" VA {link['va']:#x}" if link.get("va") is not None else ""
            out.append(f"  - `{old.escape(link['library'])}`{va} literal `{old.escape(link['literal'])}` "
                       f"(needle `{old.escape(link['needle'])}`, {old.escape(link['why'])})")
        out += ["", "- Reader text:", ""]
        for item in row["readerText"]:
            if item.get("valueKinds"):
                out.append(f"  - `{old.escape(str(item.get('member')))}`: {item['status']}, "
                           f"{item['textValues']} text value(s), kinds {item['valueKinds']}, "
                           f"counts {item.get('valueCounts')}")
            else:
                out.append(f"  - `{old.escape(str(item.get('member')))}`: {item['status']}")
        out += ["", f"- Verdict: **{row['verdict']}**. Field meanings established: "
                    f"**{'yes' if row['meaningsEstablished'] else 'no'}**.", "",
                "- Still missing before any field could be named:", ""]
        out += [f"  - {old.escape(item)}" for item in row["meaningBlockers"]]
        out.append("")
    out += ["## Documentation summary", "",
            f"- Pairs measured: {summary['targets']} across {summary['archives']} archive(s); "
            f"not reached: {len(state['pending'])}.",
            f"- Controls: {'PASS' if control_state['passed'] else 'FAIL'} "
            f"({sum(1 for row in control_state['checks'] if row['passed'])}/"
            f"{len(control_state['checks'])}).",
            f"- Consumer function bodies compared: {summary['functionsCompared']}; byte-identical to Toyota's: "
            f"{summary['identicalConsumers']}.",
            f"- Pairs with a filename link: {summary['linkTargets']} ({summary['links']} literal(s)).",
            f"- Pairs whose established reader returns any text value: {summary['textTargets']}.",
            f"- Pairs with field meanings established: {summary['meaningsEstablished']}.",
            f"- Verdicts: {summary['verdicts']['CODE-IDENTICAL AND LINKED']} code-identical and linked, "
            f"{summary['verdicts']['CODE-IDENTICAL, NO FILENAME LINK']} code-identical without a link, "
            f"{summary['verdicts']['FILENAME LINK, CODE DIFFERS']} linked with differing code, "
            f"{summary['verdicts']['NOT ESTABLISHED']} not established.",
            f"- Converters, packs or app changes produced by this tool: {summary['packs'] + summary['appChanges']}.",
            ""]
    if state["pending"]:
        out += [f"Not reached inside the budget: {', '.join(state['pending'])}.", ""]
    return "\n".join(out) + "\n"


def emit(state: dict, output: Path) -> dict:
    text = markdown(state)
    old.write_atomic(output / "inventory8.md", text)
    old.write_atomic(output / "inventory8.json",
                     json.dumps(state, indent=1, sort_keys=True, ensure_ascii=True, default=str) + "\n")
    for stale in sorted(output.glob("inventory8-[0-9][0-9][0-9].md")):
        stale.unlink()
    manifest = []
    for index, body in enumerate(old.chunk(text), 1):
        name = ("Inventory-8 consumer link" if index == 1
                else f"Inventory-8 consumer link (part {index})")
        path = output / f"inventory8-{index:03d}.md"
        old.write_atomic(path, body)
        manifest.append({"name": name, "file": str(path), "bytes": len(body.encode("utf-8"))})
    if manifest and max(item["bytes"] for item in manifest) > 60000:
        raise ValueError("A check part exceeds the 60,000-byte Checks API field limit")
    conclusion = ("success" if state["control"]["passed"] and not state["pending"] and not state.get("failed")
                  else "failure")
    old.write_atomic(output / "parts.json", json.dumps(manifest, indent=1) + "\n")
    old.write_atomic(output / "conclusion.txt", conclusion + "\n")
    return {"parts": manifest, "bytes": len(text.encode("utf-8")), "conclusion": conclusion}


# ------------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory7-json", type=Path, required=True)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", ""))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--work", type=Path)
    parser.add_argument("--local-dir", type=Path,
                        help="directory holding the archives; skips release downloads")
    parser.add_argument("--max-seconds", type=int, default=1500)
    args = parser.parse_args()

    source = json.loads(args.inventory7_json.read_text())
    wanted = targets(source)
    reference_target = toyota_target(source)
    if not wanted:
        raise SystemExit("STOP: Inventory-7 recorded no pair with parsed > 0, so there is nothing to measure")
    names = sorted({row["archive"] for row in wanted} | {"TOYOTA.zip"})

    assets: dict[str, dict] = {}
    if not args.local_dir:
        if not args.repo:
            raise SystemExit("STOP: --repo or GH_REPO is required when --local-dir is not set")
        assets = {asset["name"]: asset for asset in old.release_assets(args.repo)}
        missing = [name for name in names if name not in assets]
        if missing:
            raise SystemExit(f"STOP: not published as release assets: {', '.join(missing)}")

    deadline = time.monotonic() + args.max_seconds
    work = args.work or Path(tempfile.mkdtemp(prefix="inventory8-"))
    work.mkdir(parents=True, exist_ok=True)
    state: dict = {"format": FORMAT, "formatVersion": FORMAT_VERSION, "generatedUtc": utc_now(),
                   "headSha": os.environ.get("GITHUB_SHA", "local"), "label": LABEL,
                   "inventory7": str(args.inventory7_json), "budget": {"functionsCompared": 0,
                                                                       "payloadsMeasured": 0},
                   "targets": [], "toyota": None, "pending": [], "failed": False, "control": {}}
    toyota_functions: dict[str, dict[str, bytes]] = {}
    toyota_path = args.local_dir / "TOYOTA.zip" if args.local_dir else None
    try:
        if toyota_path is None:
            toyota_path = old.download(assets["TOYOTA.zip"], work)
        if hashlib.sha256(toyota_path.read_bytes()).hexdigest() != fe.TOYOTA_SHA256:
            raise SystemExit("STOP: TOYOTA.zip does not match the pinned SHA-256; refusing to use it as a reference")
        families = {row["family"] for row in wanted}
        if reference_target is not None:
            families.add(reference_target["family"])
        families = sorted(families)
        toyota_functions, reference_errors = toyota_reference_functions(toyota_path, families)
        state["referenceErrors"] = reference_errors
        if reference_target is not None:
            state["toyota"] = measure(toyota_path, reference_target, toyota_functions, state["budget"])
        for target in wanted:
            if time.monotonic() > deadline:
                state["pending"].append(target["archive"])
                continue
            path = args.local_dir / target["archive"] if args.local_dir else None
            downloaded = False
            try:
                if path is None:
                    path = old.download(assets[target["archive"]], work)
                    downloaded = True
                state["targets"].append(measure(path, target, toyota_functions, state["budget"]))
            except (ValueError, OSError, zipfile.BadZipFile, zlib.error) as error:
                state["failed"] = True
                state["targets"].append({**target, "membersExamined": [], "consumerIdentity": [],
                                         "identicalConsumers": [], "comparedConsumers": 0, "filenameLinks": [],
                                         "readerText": [], "readerTextValues": 0, "verdict": "NOT ESTABLISHED",
                                         "meaningsEstablished": False,
                                         "meaningBlockers": [f"measurement failed: {type(error).__name__}: {error}"]})
            finally:
                if downloaded and path is not None:
                    Path(path).unlink(missing_ok=True)
        # Negative control: Toyota's libraries must not name another brand's member.
        needle = next((PurePosixPath(fe.canonical(row["samples"][0]["member"])).name
                       for row in wanted if row["samples"]), None)
        negative = (filename_links(toyota_path, sorted(set(
            reference_target["ownRoleLibraries"]) | set(reference_target["librariesParsed"]))
            if reference_target else [], {needle: "another brand's member"}) if needle else [])
        state["control"] = control_check(toyota_functions, state["toyota"],
                                         (state["toyota"] or {}).get("filenameLinks", []), negative)
        summarise(state)
        args.output.mkdir(parents=True, exist_ok=True)
        result = emit(state, args.output)
        print(f"inventory8: {state['summary']['targets']} pair(s) measured, "
              f"{state['summary']['identicalConsumers']} identical consumer body/bodies, "
              f"{state['summary']['linkTargets']} pair(s) with a filename link, "
              f"{state['summary']['meaningsEstablished']} with meanings established, "
              f"control {'PASS' if state['control']['passed'] else 'FAIL'}, "
              f"{result['bytes']} report byte(s)", flush=True)
        return 0 if result["conclusion"] == "success" else 1
    finally:
        if not args.work and work.exists():
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
