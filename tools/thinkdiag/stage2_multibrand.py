#!/usr/bin/env python3
"""Multi-brand Stage 2: evidence-gated family extraction.

Stage 2 builds a pack ONLY for an (archive, family) pair that passes
``established()``. The gate is explicit and cannot be satisfied by a filename, a
magic string, a shared header or a borrowed Toyota reader:

1. **wrapper** -- the archive's own ``libDEVICEID.so`` is byte-identical to
   Toyota's, so the format table *and the virtual address it is read from* are
   the ones the Toyota native reader uses
   (``tools/inventory/family_evidence.py:own_table``, ``decode_toyota.py:39``);
2. **structure** -- Inventory-7 recorded at least one sampled decoded member that
   an existing ``tools/thinkdiag`` reader parses;
3. **consumer** -- one of the archive's OWN role libraries exports a consumer this
   repo already cites for that reader (``sweep_toyota.FOCUSED_READERS``);
4. **meanings** -- a field-meaning record tying the decoded fields of *that brand*
   to a disassembly of *that brand's own* reader.

Requirements 1-3 are exactly Inventory-7's ``CANDIDATE`` verdict. Requirement 4
is what separates a candidate from something that may be converted, packed and
shipped, and ``MEANING_EVIDENCE`` below is empty: no brand in the corpus has it.
Stage 2 therefore rejects every candidate with its reasons instead of guessing.

Two earlier defects made this stage report a false negative rather than a gate:
``decode_yzjm`` was called with one argument although the decoder requires the
caller's 32-byte table, and acceptance was decided by ``toyota_control.eligible``,
which is keyed to Toyota *filenames* -- so any archive shipping a file called
``MENU.BIN`` would have been called compatible if its bytes happened not to raise.
Neither a borrowed name nor a non-raising parse is compatibility.
"""
from __future__ import annotations

import argparse
from collections import Counter
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tempfile
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
INVENTORY = HERE.parent / "inventory"
for path in (HERE, INVENTORY):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

import family_evidence as fe  # noqa: E402
from yzjm import decode_yzjm  # noqa: E402

FORMAT = "bod3-family-research"
FORMAT_VERSION = 1
MAX_PACK_BYTES = 480 * 1024 * 1024
MAX_TABLE_ROWS = 120
# GitHub caps a check-run output summary at 65535 bytes; the full detail stays in the JSON.
MAX_SUMMARY_BYTES = 60000

# Field-meaning evidence, keyed by (archive, family). A record must name the
# brand's own library, the function and offset that reads the field, and what the
# field is -- a diagnostic address, service, PID, timing or text id. It may only
# be added from a disassembly citation, never from a decoded value that "looks
# like" something. Empty means: no brand's field meanings are established.
MEANING_EVIDENCE: dict[tuple[str, str], dict] = {}


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def meaning_evidence(archive: str, family: str) -> dict | None:
    return MEANING_EVIDENCE.get((archive, family))


def load_inventory7(path: Path) -> dict:
    state = json.loads(path.read_text())
    if state.get("format") != fe.FORMAT:
        raise SystemExit(f"STOP: {path} is not an Inventory-7 report (format {state.get('format')!r})")
    if not state.get("control", {}).get("passed"):
        raise SystemExit("STOP: the Inventory-7 Toyota control did not pass; its verdicts are not usable")
    return state


def candidates(state: dict) -> list[dict]:
    """Every (archive, family) Inventory-7 measured, best evidence first."""
    rows = []
    for entry in state["brands"]:
        if entry["brand"] == "TOYOTA":
            continue
        for verdict in entry["verdicts"]:
            rows.append({
                "archive": entry["archive"],
                "brand": entry["brand"],
                "sha256": entry["sha256"],
                "family": verdict["family"],
                "verdict": verdict["verdict"],
                "parsed": verdict["parsed"],
                "attempted": verdict["membersAttempted"],
                "wrapper": "established" if verdict["wrapperConfidence"].startswith("established")
                else ("experimental" if verdict["wrapperConfidence"].startswith("experimental")
                      else "not applicable"),
                "own_role_consumers": list(verdict["ownRoleConsumers"]),
                "own_role_libraries": list(verdict["ownRoleLibraries"]),
                "citation": verdict["citation"],
                "inventory7_missing": list(verdict["missingEvidence"]),
            })
    order = {"CANDIDATE": 0, "NOT ESTABLISHED": 1}
    return sorted(rows, key=lambda row: (order.get(row["verdict"], 2), -row["parsed"],
                                         row["archive"], row["family"]))


def established(candidate: dict) -> tuple[bool, list[str], list[str]]:
    """Return (allowed, satisfied, missing) against the four gate requirements."""
    satisfied, missing = [], []
    if candidate["wrapper"] == "established":
        satisfied.append("wrapper: own libDEVICEID.so is byte-identical to Toyota's")
    else:
        missing.append(f"wrapper: confidence is '{candidate['wrapper']}', not established")
    if candidate["parsed"] > 0:
        satisfied.append(f"structure: {candidate['parsed']} sampled decoded member(s) parsed "
                         f"with the existing reader for family '{candidate['family']}'")
    else:
        missing.append(f"structure: no sampled decoded member parsed with family '{candidate['family']}'")
    if candidate["own_role_consumers"]:
        satisfied.append("consumer: own role librar"
                         f"{'y' if len(candidate['own_role_libraries']) == 1 else 'ies'} "
                         f"{', '.join(candidate['own_role_libraries']) or '(unlisted)'} export "
                         f"{', '.join(candidate['own_role_consumers'])}")
    else:
        missing.append("consumer: none of this archive's own role libraries export a cited consumer "
                       f"of family '{candidate['family']}'")
    record = meaning_evidence(candidate["archive"], candidate["family"])
    if record:
        satisfied.append(f"meanings: {record.get('citation', 'recorded')}")
    else:
        missing.append("meanings: no field-meaning record ties this brand's decoded fields to a "
                       "disassembly of this brand's own reader; MEANING_EVIDENCE is empty")
    return (not missing), satisfied, missing


def extract_family_pack(archive_path: Path, archive_name: str, family: str, out_dir: Path) -> dict:
    """Decode with the archive's own table and reconcile every sampled member.

    Acceptance is the family's structural reader, never the member's filename.
    """
    source_sha256 = fe._file_sha256(archive_path)
    table, device, _ = fe.own_table(archive_path)
    if table is None:
        raise ValueError(f"no format table in {archive_name}: {device}")
    family_row = next((row for row in fe.FAMILIES if row["key"] == family), None)
    if family_row is None:
        raise ValueError(f"unknown family {family!r}")

    with fe.ArchiveScanner(archive_path) as scanner:
        chosen, rule = fe.old.sample(list(scanner.names), fe.MAX_CLASSIFIED, fe.SAMPLE_QUOTA)
        payloads = scanner.read(set(chosen))
        skipped = list(scanner.skipped)

    accepted, rejected, duplicates, not_applicable = 0, 0, 0, 0
    reasons: Counter[str] = Counter()
    seen: set[str] = set()
    records: list[dict] = []
    for name in chosen:
        raw = payloads.get(name)
        if raw is None:
            rejected += 1
            reasons["member could not be read after selection"] += 1
            continue
        if not raw.startswith(fe.YZJM_HEAD):
            not_applicable += 1
            reasons["not applicable: member is not YZJM-wrapped"] += 1
            continue
        try:
            payload = decode_yzjm(raw, table).payload
        except (ValueError, IndexError, KeyError, TypeError, OverflowError) as exc:
            rejected += 1
            reasons[f"decode error: {type(exc).__name__}: {exc}"] += 1
            continue
        digest = hashlib.sha256(payload).hexdigest()
        if digest in seen:
            duplicates += 1
            reasons["duplicate: decoded payload SHA-256 already accepted"] += 1
            continue
        try:
            structure = family_row["structure"](payload)
        except (ValueError, IndexError, KeyError, TypeError, OverflowError, StopIteration) as exc:
            rejected += 1
            reasons[f"structure rejected: {type(exc).__name__}: {exc}"] += 1
            continue
        seen.add(digest)
        accepted += 1
        records.append({"member": name, "stem": fe.stem(name), "payloadSha256": digest,
                        "decodedBytes": len(payload), "structure": structure})

    out_dir.mkdir(parents=True, exist_ok=True)
    brand_slug = PurePosixPath(archive_name).stem.lower()
    document = {
        "format": FORMAT, "formatVersion": FORMAT_VERSION, "generatedAt": utc_now(),
        "brand": brand_slug, "archive": archive_name, "family": family,
        "sourceSha256": source_sha256, "formatTableSha256": hashlib.sha256(table).hexdigest(),
        "samplingRule": rule,
        # Never true: a research pack is not an app pack. The app index pins one
        # source SHA-256 and one Toyota-only format; see the module docstring.
        "appPackCompatible": False, "manualSelectionOnly": True, "vehicleTested": False,
        "fieldMeaningsEstablished": False, "records": records,
    }
    body = json.dumps(document, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
    compressed = zlib.compress(body, level=6)
    if len(compressed) >= MAX_PACK_BYTES:
        raise ValueError("research pack exceeds the container bound")
    pack_path = out_dir / f"{brand_slug}-{family}-research.bin"
    pack_path.write_bytes(compressed)
    return {
        "brand": brand_slug, "archive": archive_name, "family": family,
        "sourceSha256": source_sha256, "packFile": pack_path.name,
        "packBytes": len(compressed), "packJsonBytes": len(body),
        "packSha256": hashlib.sha256(compressed).hexdigest(),
        "accepted": accepted, "rejected": rejected, "duplicates": duplicates,
        "notApplicable": not_applicable, "sampled": len(chosen),
        "skippedMembers": len(skipped), "reasons": dict(sorted(reasons.items())),
        "underBound": len(compressed) < MAX_PACK_BYTES, "appPackCompatible": False,
    }


def missing_label(row: dict) -> str:
    """Short, stable label for the first gate requirement this pair failed."""
    missing = row.get("missing") or []
    if not missing:
        return "none"
    first = str(missing[0])
    label = first.split(":", 1)[0].strip()
    if len(label) > 48:
        label = label[:45] + "..."
    return label or first[:48]


def bound(text: str, limit: int = MAX_SUMMARY_BYTES) -> str:
    """Never let the report exceed what a GitHub check-run summary accepts."""
    encoded = text.encode("utf-8")
    if len(encoded) <= limit:
        return text
    note = ("\n\n---\n\n*Report truncated to fit the GitHub check-run summary limit "
            f"({limit} bytes). The untruncated report and every per-pair row are in the "
            "`stage2-multibrand-<sha>.json` and `.md` release assets.*\n")
    keep = limit - len(note.encode("utf-8"))
    cut = encoded[:keep].decode("utf-8", "ignore")
    cut = cut[:cut.rfind("\n")] if "\n" in cut else cut
    return cut + note


def render(summary: dict) -> str:
    lines = [
        "# Multi-brand Stage 2 Report",
        "",
        f"- Commit SHA: `{summary['commit_sha']}`",
        "- Gate: wrapper + structure + own-role consumer + field meanings "
        "(`tools/thinkdiag/stage2_multibrand.py:established`)",
        f"- Inventory-7 Toyota control: {summary['control']}",
        f"- (archive, family) pairs measured: {summary['measured']}",
        f"- Pairs passing the gate: {summary['allowed']}",
        f"- Research packs built: {summary['built']}",
        "- App packs built: **0** — the app index pins one Toyota source SHA-256 and the "
        "`bod3-toyota-vehicle-index` format; nothing here is app-compatible",
        "",
        "## Reconciliation",
        "",
        "### Pairs the gate actually had something to judge",
        "",
        "| archive | family | Inventory-7 verdict | parsed | wrapper | own-role consumers | gate | first missing requirement |",
        "| --- | --- | --- | ---: | --- | --- | --- | --- |",
    ]
    detail, groups = [], {}
    for row in summary["evaluations"]:
        if row["allowed"] or row["verdict"] == "CANDIDATE" or row["parsed"] > 0:
            detail.append(row)
        else:
            groups.setdefault((row["verdict"], missing_label(row)), []).append(row)
    for row in detail[:MAX_TABLE_ROWS]:
        missing = row["missing"][0] if row["missing"] else "none"
        lines.append(
            f"| `{row['archive']}` | `{row['family']}` | {row['verdict']} | {row['parsed']} | {row['wrapper']} "
            f"| {fe.old.escape(', '.join(row['own_role_consumers']) or 'none')} "
            f"| {'ALLOWED' if row['allowed'] else 'REJECTED'} | {fe.old.escape(missing)} |")
    if len(detail) > MAX_TABLE_ROWS:
        lines.append(f"| ... | | | | | | | {len(detail) - MAX_TABLE_ROWS} further pair(s) in the JSON |")

    lines += ["", "### Every other pair, grouped by its first missing requirement", "",
              "| Inventory-7 verdict | first missing requirement | pairs | archives |",
              "| --- | --- | ---: | --- |"]
    for (verdict, label), rows_in in sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        names = sorted({row["archive"] for row in rows_in})
        shown = ", ".join(f"`{fe.old.escape(name)}`" for name in names[:6])
        if len(names) > 6:
            shown += f" +{len(names) - 6} more"
        lines.append(f"| {verdict} | {fe.old.escape(label)} | {len(rows_in)} | {shown} |")
    lines += ["",
              f"Accounted for: {len(detail)} pair(s) shown individually + "
              f"{sum(len(rows) for rows in groups.values())} grouped = {len(summary['evaluations'])} "
              "= every pair measured. Each individual row is in the JSON report.", ""]

    if summary["packs"]:
        lines += ["## Built research packs", "",
                  "| archive | family | sampled | accepted | rejected | duplicates | not applicable | bytes | SHA-256 |",
                  "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
        for pack in summary["packs"]:
            lines.append(f"| `{pack['archive']}` | `{pack['family']}` | {pack['sampled']} | {pack['accepted']} "
                         f"| {pack['rejected']} | {pack['duplicates']} | {pack['notApplicable']} "
                         f"| {pack['packBytes']} | `{pack['packSha256']}` |")
        lines += ["", "### Rejection reasons", ""]
        for pack in summary["packs"]:
            reasons = "; ".join(f"{fe.old.escape(k)}: {v}" for k, v in pack["reasons"].items()) or "none"
            lines.append(f"- `{pack['archive']}` · `{pack['family']}` — {reasons}")
    else:
        lines += ["## Built research packs", "",
                  "None. No (archive, family) pair satisfied the gate, so no converter was written and no "
                  "pack was built. Inventing one from a filename, a magic string or a shared header is "
                  "explicitly out of scope."]
    best = summary["best_candidates"]
    lines += ["", "## Best candidates and their exact missing evidence", ""]
    if not best:
        lines.append("None: no non-Toyota archive produced a CANDIDATE verdict in Inventory-7.")
    for row in best:
        lines += [f"### `{row['archive']}` · `{row['family']}`", "",
                  f"- Inventory-7 verdict: {row['verdict']} ({row['parsed']} parsed of {row['attempted']} sampled).",
                  f"- Reader citation: `{row['citation']}`",
                  f"- Own-role consumers: {', '.join(row['own_role_consumers']) or 'none'}.",
                  "- Satisfied:", ""]
        lines += [f"  - {item}" for item in row["satisfied"]] or ["  - none"]
        lines += ["", "- Missing:", ""]
        lines += [f"  - {item}" for item in row["missing"]]
        lines.append("")
    return bound("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory7-json", type=Path, required=True,
                        help="Inventory-7 report produced for this run")
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", ""))
    parser.add_argument("--local-dir", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--commit-sha", default=os.environ.get("GITHUB_SHA", "local"))
    parser.add_argument("--max-packs", type=int, default=8)
    args = parser.parse_args()

    state = load_inventory7(args.inventory7_json)
    rows = candidates(state)
    evaluations, packs = [], []
    assets: dict[str, dict] = {}
    if not args.local_dir:
        if not args.repo:
            raise SystemExit("STOP: --repo or GH_REPO is required when --local-dir is not set")
        assets = {asset["name"]: asset for asset in fe.old.release_assets(args.repo)}
    with tempfile.TemporaryDirectory() as work:
        for row in rows:
            allowed, satisfied, missing = established(row)
            entry = {**row, "allowed": allowed, "satisfied": satisfied, "missing": missing}
            evaluations.append(entry)
            if not allowed or len(packs) >= args.max_packs:
                continue
            archive_name = row["archive"]
            if args.local_dir:
                path = args.local_dir / archive_name
            else:
                asset = assets.get(archive_name)
                if asset is None:
                    entry["allowed"] = False
                    entry["missing"].append(f"archive is not a published release asset: {archive_name}")
                    continue
                path = fe.old.download(asset, Path(work))
            if not path.is_file():
                entry["allowed"] = False
                entry["missing"].append(f"archive unavailable: {path}")
                continue
            try:
                packs.append(extract_family_pack(path, archive_name, row["family"], args.out_dir))
            except (ValueError, OSError, zipfile.BadZipFile, zlib.error) as exc:
                entry["allowed"] = False
                entry["missing"].append(f"extraction failed: {type(exc).__name__}: {exc}")
            finally:
                if not args.local_dir:
                    path.unlink(missing_ok=True)

    allowed_rows = [row for row in evaluations if row["allowed"]]
    summary = {
        "format": "bod3-stage2-multibrand",
        "formatVersion": FORMAT_VERSION,
        "generated_at": utc_now(),
        "commit_sha": args.commit_sha,
        "control": state["control"]["toyotaControl"] + (" (PASS)" if state["control"]["passed"] else " (FAIL)"),
        "gate": ["wrapper", "structure", "consumer", "meanings"],
        "meaning_evidence_records": len(MEANING_EVIDENCE),
        "measured": len(evaluations),
        "allowed": len(allowed_rows),
        "built": len(packs),
        "app_packs_built": 0,
        "evaluations": evaluations,
        "not_attempted": sum(1 for row in evaluations if row["attempted"] == 0),
        "table": [row for row in evaluations if row["allowed"] or row["attempted"] > 0][:MAX_TABLE_ROWS],
        "table_rows": len([row for row in evaluations if row["allowed"] or row["attempted"] > 0][:MAX_TABLE_ROWS]),
        "packs": packs,
        # "Best" means the gate actually had something to judge: pairs where a
        # sampled decoded member was run through the family's reader.
        "best_candidates": ([row for row in evaluations if row["allowed"] or row["parsed"] > 0]
                            or evaluations)[:12],
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / f"stage2-multibrand-{args.commit_sha}.json").write_text(
        json.dumps(summary, indent=2, default=str) + "\n")
    (args.out_dir / f"stage2-multibrand-{args.commit_sha}.md").write_text(render(summary))
    print(f"stage2: {summary['measured']} pair(s) measured, {summary['allowed']} allowed, "
          f"{summary['built']} research pack(s), 0 app pack(s)", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
