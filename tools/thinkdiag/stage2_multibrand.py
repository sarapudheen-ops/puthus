#!/usr/bin/env python3
"""Multi-brand Stage 2: first production extractor and deterministic pack builder."""
from __future__ import annotations

import argparse
from collections import Counter
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
INVENTORY = HERE.parent / "inventory"
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
if str(INVENTORY) not in sys.path:
    sys.path.insert(0, str(INVENTORY))

import toyota_control as tc  # noqa: E402
from yzjm import decode_yzjm  # noqa: E402

YZJM_HEAD = b"\x05\x00YZJM\0"
MAX_PACK_BYTES = 480 * 1024 * 1024


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def rank_candidates(stage1_report: dict) -> list[dict]:
    """Rank non-Toyota brands from Stage 1 coverage matrix."""
    candidates = []
    for b in stage1_report.get("brands", []):
        if b.get("archive") == "TOYOTA.zip":
            continue
        if b.get("status") != "complete":
            continue
        ps = b.get("parser_stats", {})
        yzjm_ok = ps.get("yzjm_accepted", 0)
        struct_ok = ps.get("toyota_struct_accepted", 0)
        if yzjm_ok > 0 and struct_ok > 0:
            candidates.append({
                "archive": b["archive"],
                "sha256": b["sha256"],
                "yzjm_accepted": yzjm_ok,
                "toyota_struct_accepted": struct_ok,
                "score": struct_ok * 100 + yzjm_ok,
            })
    return sorted(candidates, key=lambda c: (-c["score"], c["archive"]))


def extract_brand_pack(archive_path: Path, archive_name: str, out_dir: Path) -> dict:
    """Extract and package all validated members of a qualifying brand archive."""
    h = hashlib.sha256()
    with archive_path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    source_sha256 = h.hexdigest()

    accepted = 0
    rejected = 0
    duplicates = 0
    rejection_reasons: Counter[str] = Counter()
    seen_hashes: set[str] = set()
    systems: list[dict] = []

    with zipfile.ZipFile(archive_path, "r") as zf:
        for info in sorted(zf.infolist(), key=lambda i: i.filename):
            if info.is_dir():
                continue
            if not tc.eligible(info.filename):
                continue
            try:
                raw = zf.read(info)
            except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError) as exc:
                rejected += 1
                rejection_reasons[f"read_error:{type(exc).__name__}"] += 1
                continue

            member_sha = hashlib.sha256(raw).hexdigest()
            if member_sha in seen_hashes:
                duplicates += 1
                continue
            seen_hashes.add(member_sha)

            data = raw
            if raw.startswith(YZJM_HEAD):
                try:
                    data = decode_yzjm(raw)
                except Exception as exc:
                    rejected += 1
                    rejection_reasons[f"yzjm_error:{type(exc).__name__}"] += 1
                    continue

            try:
                reader_name = tc.reader(info.filename, data)
            except Exception as exc:
                rejected += 1
                rejection_reasons[f"reader_error:{type(exc).__name__}"] += 1
                continue

            accepted += 1
            systems.append({
                "member": info.filename,
                "sha256": member_sha,
                "bytes": len(data),
                "reader": reader_name,
            })

    out_dir.mkdir(parents=True, exist_ok=True)
    brand_slug = PurePosixPath(archive_name).stem.lower()
    pack_obj = {
        "brand": brand_slug,
        "archive": archive_name,
        "sourceSha256": source_sha256,
        "manualSelectionOnly": True,
        "vehicleTested": False,
        "systems": systems,
    }
    pack_bytes = json.dumps(pack_obj, sort_keys=True, separators=(",", ":")).encode("utf-8")
    compressed = zlib.compress(pack_bytes, level=6)
    pack_sha256 = hashlib.sha256(compressed).hexdigest()
    pack_path = out_dir / f"{brand_slug}-pack.bin"
    pack_path.write_bytes(compressed)

    return {
        "brand": brand_slug,
        "archive": archive_name,
        "sourceSha256": source_sha256,
        "accepted": accepted,
        "rejected": rejected,
        "duplicates": duplicates,
        "rejection_reasons": dict(rejection_reasons),
        "pack_file": pack_path.name,
        "pack_bytes": len(compressed),
        "pack_json_bytes": len(pack_bytes),
        "pack_sha256": pack_sha256,
        "under_480mib": len(compressed) < MAX_PACK_BYTES,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage1-json", type=Path, required=True)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", ""))
    parser.add_argument("--local-dir", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--commit-sha", default=os.environ.get("GITHUB_SHA", "local"))
    args = parser.parse_args()

    stage1 = json.loads(args.stage1_json.read_text())
    candidates = rank_candidates(stage1)
    results = []

    if args.local_dir:
        for c in candidates:
            zp = args.local_dir / c["archive"]
            if zp.exists():
                results.append(extract_brand_pack(zp, c["archive"], args.out_dir))
    else:
        if not args.repo:
            raise SystemExit("STOP: --repo or GH_REPO is required")
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            for c in candidates:
                subprocess.run(
                    ["gh", "release", "download", "assets-v1", "--repo", args.repo,
                     "--pattern", c["archive"], "--dir", str(tmp), "--clobber"],
                    check=True,
                )
                zp = tmp / c["archive"]
                results.append(extract_brand_pack(zp, c["archive"], args.out_dir))
                zp.unlink(missing_ok=True)

    summary = {
        "generated_at": utc_now(),
        "commit_sha": args.commit_sha,
        "selected_format": "YZJM + toyota_structure/EntryIndex",
        "candidate_count": len(candidates),
        "processed_count": len(results),
        "brands": results,
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / f"stage2-multibrand-{args.commit_sha}.json").write_text(json.dumps(summary, indent=2) + "\n")
    md_lines = [
        "# Multi-brand Stage 2 Report",
        "",
        f"- Commit SHA: `{args.commit_sha}`",
        f"- Selected format: `{summary['selected_format']}`",
        f"- Qualifying brands: {len(candidates)}",
        "",
        "| Brand | Archive | Accepted | Rejected | Duplicates | Pack Bytes | Pack SHA-256 (prefix) |",
        "| --- | --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for r in results:
        md_lines.append(
            f"| `{r['brand']}` | `{r['archive']}` | {r['accepted']} | {r['rejected']} | "
            f"{r['duplicates']} | {r['pack_bytes']} | `{r['pack_sha256'][:12]}` |"
        )
    (args.out_dir / f"stage2-multibrand-{args.commit_sha}.md").write_text("\n".join(md_lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
