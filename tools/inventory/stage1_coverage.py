#!/usr/bin/env python3
"""Multi-brand Stage 1: exhaustive archive-to-app data coverage."""
from __future__ import annotations

import argparse
from collections import Counter
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
THINKDIAG = HERE.parent / "thinkdiag"
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
if str(THINKDIAG) not in sys.path:
    sys.path.insert(0, str(THINKDIAG))

import brand_inventory as bi  # noqa: E402
import toyota_control as tc  # noqa: E402
from analyze_toyota import native_info  # noqa: E402
from inspect_pack import signatures  # noqa: E402
from yzjm import decode_yzjm  # noqa: E402

YZJM_HEAD = b"\x05\x00YZJM\0"
ELF_HEAD = b"\x7fELF"
ZIP_HEAD = b"PK\x03\x04"
EXPECTED_OTHER_BRANDS = 72
MAX_NESTING_DEPTH = 8
MAX_PARSER_BYTES = 64 * 1024 * 1024

APP_INTEGRATION_POINTS = {
    "pack_schema": "app/src/main/java/com/bod3/app/vehiclepack/PackBundle.kt:13-73",
    "pack_index": "app/src/main/java/com/bod3/app/vehiclepack/VehiclePackCatalog.kt:15-43; app/src/main/java/com/bod3/app/android/AssetVehiclePacks.kt:11-21",
    "download_hash_verification": "app/src/main/java/com/bod3/app/vehiclepack/PackHttp.kt:7-58; app/src/main/java/com/bod3/app/vehiclepack/PackBundle.kt:38,132",
    "cache": "app/src/main/java/com/bod3/app/vehiclepack/PackBundle.kt:87-132",
    "category_ui": "app/src/main/java/com/bod3/app/vehiclepack/PackSystemStatusUi.kt:4-52; app/src/main/java/com/bod3/app/ui/screens/ToyotaVehiclePacksScreen.kt:51-58",
}

CONSUMER_MAP = [
    ("TOYOTA.zip :: SYSTEMENTER.BIN", "tools/thinkdiag/toyota_entries.py:1-120 -> tools/thinkdiag/convert_vehicle_packs.py:1-50 -> app/src/main/java/com/bod3/app/vehiclepack/VehiclePackCatalog.kt:15-43"),
    ("TOYOTA.zip :: FUNCDATA_*.BIN", "tools/thinkdiag/toyota_functions.py:1-120 -> tools/thinkdiag/convert_vehicle_packs.py:1-50 -> app/src/main/java/com/bod3/app/vehiclepack/PackSystemStatusUi.kt:4-52"),
    ("TOYOTA.zip :: SYSLIST_*.BIN", "tools/thinkdiag/toyota_structure.py:1-120 -> tools/thinkdiag/convert_vehicle_packs.py:1-50"),
    ("TOYOTA.zip :: MENU.BIN", "tools/thinkdiag/toyota_structure.py:1-120 -> tools/thinkdiag/convert_vehicle_packs.py:1-50"),
    ("TOYOTA.zip :: ECU/*.BIN", "tools/thinkdiag/toyota_structure.py:1-120 -> tools/thinkdiag/convert_vehicle_packs.py:1-50"),
    ("TOYOTA.zip :: libDEVICEID.so", "tools/thinkdiag/analyze_toyota.py:1-80 (offline analysis only; NOT CONSUMED by app)"),
    ("Other 72 brand archives :: all members", "NOT CONSUMED"),
]


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()


def magic_description(head: bytes) -> str:
    if head.startswith(YZJM_HEAD):
        return "YZJM encrypted/encoded container"
    if head.startswith(ELF_HEAD):
        return "ELF shared object"
    if head.startswith(ZIP_HEAD):
        return "ZIP archive"
    if head.startswith(b"SQLite format 3"):
        return "SQLite 3 database"
    if head.startswith(b"\x1f\x8b"):
        return "gzip compressed data"
    sigs = signatures(head)
    if sigs:
        return ", ".join(sigs)
    return "unknown/binary"


def safe_member_path(name: str) -> str:
    norm = name.replace("\\", "/").lstrip("/")
    parts = [p for p in norm.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise ValueError(f"unsafe path traversal: {name!r}")
    return "/".join(parts)


def file_sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
            total += len(chunk)
    return digest.hexdigest(), total


def _walk_zip_stream(
    zf: zipfile.ZipFile,
    archive_name: str,
    prefix: str,
    depth: int,
    members: list[dict],
    skipped: list[dict],
    run_parsers: bool,
    parser_stats: dict,
) -> None:
    if depth > MAX_NESTING_DEPTH:
        skipped.append({"path": prefix, "reason": f"max nesting depth {MAX_NESTING_DEPTH} exceeded"})
        return

    for info in zf.infolist():
        if info.is_dir():
            continue
        try:
            rel_path = safe_member_path(info.filename)
        except ValueError as exc:
            skipped.append({"path": f"{prefix}{info.filename}", "reason": str(exc)})
            continue

        full_path = f"{prefix}{rel_path}"
        ext = PurePosixPath(rel_path).suffix.lower()

        try:
            with zf.open(info, "r") as stream:
                digest = hashlib.sha256()
                size = 0
                head = b""
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    if not head:
                        head = chunk[:64]
                    digest.update(chunk)
                    size += len(chunk)
        except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError, MemoryError) as exc:
            skipped.append({"path": full_path, "reason": f"{type(exc).__name__}: {exc}"})
            continue

        sha256 = digest.hexdigest()
        magic = magic_description(head)
        rec = {
            "archive": archive_name,
            "path": full_path,
            "uncompressed_bytes": size,
            "sha256": sha256,
            "depth": depth,
            "extension": ext,
            "magic": magic,
        }
        members.append(rec)

        is_zip = ext == ".zip" or head.startswith(ZIP_HEAD)
        if is_zip:
            if size > MAX_PARSER_BYTES:
                skipped.append({"path": full_path, "reason": f"nested zip exceeds {MAX_PARSER_BYTES} bytes"})
            else:
                try:
                    raw_zip = zf.read(info)
                    with zipfile.ZipFile(io.BytesIO(raw_zip)) as nested_zf:
                        _walk_zip_stream(
                            nested_zf,
                            archive_name,
                            f"{full_path}!/",
                            depth + 1,
                            members,
                            skipped,
                            run_parsers,
                            parser_stats,
                        )
                except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError, MemoryError) as exc:
                    skipped.append({"path": full_path, "reason": f"nested zip error: {type(exc).__name__}: {exc}"})
            continue

        if not run_parsers:
            continue

        if size > MAX_PARSER_BYTES:
            parser_stats["skipped_large"] += 1
            continue

        # Run applicable decoders/parsers on matching inputs
        try:
            raw = zf.read(info)
        except (zipfile.BadZipFile, zlib.error, RuntimeError, OSError, MemoryError) as exc:
            parser_stats["read_errors"] += 1
            skipped.append({"path": full_path, "reason": f"parser read error: {type(exc).__name__}: {exc}"})
            continue

        if head.startswith(ELF_HEAD) or ext == ".so":
            parser_stats["elf_inputs"] += 1
            try:
                with tempfile.NamedTemporaryFile(suffix=".so") as tmp:
                    tmp.write(raw)
                    tmp.flush()
                    native_info(Path(tmp.name))
                parser_stats["elf_accepted"] += 1
            except Exception:
                parser_stats["elf_rejected"] += 1
            continue

        decoded = raw
        if head.startswith(YZJM_HEAD):
            parser_stats["yzjm_inputs"] += 1
            try:
                decoded = decode_yzjm(raw)
                parser_stats["yzjm_accepted"] += 1
            except Exception:
                parser_stats["yzjm_rejected"] += 1
                continue

        if tc.eligible(full_path):
            parser_stats["toyota_struct_inputs"] += 1
            try:
                tc.reader(full_path, decoded)
                parser_stats["toyota_struct_accepted"] += 1
            except Exception:
                parser_stats["toyota_struct_rejected"] += 1


def scan_archive(path: Path, archive_name: str, expected_bytes: int | None, run_parsers: bool) -> dict:
    sha256, actual_bytes = file_sha256_and_size(path)
    members: list[dict] = []
    skipped: list[dict] = []
    parser_stats = Counter()
    status = "complete"
    error = None

    try:
        with zipfile.ZipFile(path, "r") as zf:
            _walk_zip_stream(zf, archive_name, "", 0, members, skipped, run_parsers, parser_stats)
    except (zipfile.BadZipFile, OSError, EOFError) as exc:
        status = "unreadable"
        error = f"{type(exc).__name__}: {exc}"

    if status == "complete" and skipped:
        status = "partial"

    seen_hashes = set()
    duplicate_members = 0
    for m in members:
        if m["sha256"] in seen_hashes:
            duplicate_members += 1
        else:
            seen_hashes.add(m["sha256"])

    is_toyota = archive_name == "TOYOTA.zip"
    return {
        "archive": archive_name,
        "expected_bytes": expected_bytes,
        "actual_bytes": actual_bytes,
        "size_matches": (expected_bytes is None or expected_bytes == actual_bytes),
        "sha256": sha256,
        "status": status,
        "error": error,
        "member_count": len(members),
        "duplicate_member_hashes": duplicate_members,
        "skipped_count": len(skipped),
        "skipped": skipped,
        "parser_stats": dict(parser_stats),
        "statuses": {
            "source_enumerated": "DATA" if status == "complete" else "NOT ESTABLISHED",
            "parser_output_produced": "DATA" if (parser_stats.get("yzjm_accepted", 0) > 0 or parser_stats.get("elf_accepted", 0) > 0 or parser_stats.get("toyota_struct_accepted", 0) > 0) else "NOT ESTABLISHED",
            "rows_structurally_validated": "DATA" if (is_toyota and parser_stats.get("toyota_struct_accepted", 0) > 0) else "NOT ESTABLISHED",
            "app_pack_compatible": "CODE" if is_toyota else "NOT ESTABLISHED",
            "app_consumes": "CODE" if is_toyota else "NOT ESTABLISHED",
            "vehicle_tested": "NOT ESTABLISHED",
        },
        "members": members,
    }


def list_assets_v1(repo: str) -> list[dict]:
    raw = subprocess.check_output(
        ["gh", "api", "--paginate", f"repos/{repo}/releases/tags/assets-v1", "--jq", '.assets[] | [.name, (.size | tostring)] | @tsv'],
        text=True,
    )
    assets = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        name, size_s = line.split("\t", 1)
        if name.endswith(".zip") and not bi.OWN_ASSET.match(name):
            assets.append({"tag": "assets-v1", "name": name, "bytes": int(size_s)})
    return sorted(assets, key=lambda a: (a["name"] != "TOYOTA.zip", a["name"]))


def render_markdown(report: dict) -> str:
    lines = [
        "# Multi-brand Stage 1 Coverage Matrix",
        "",
        f"- Generated: `{report['generated_at']}`",
        f"- Commit SHA: `{report['commit_sha']}`",
        f"- Expected archives: `TOYOTA.zip` + {EXPECTED_OTHER_BRANDS} other brand archives ({1 + EXPECTED_OTHER_BRANDS} total)",
        f"- Actual archives enumerated: {report['archive_count']}",
        f"- Toyota YZJM control: `{report['controls']['yzjm_control']}`",
        "",
        "## App Integration Points",
        "",
    ]
    for k, v in APP_INTEGRATION_POINTS.items():
        lines.append(f"- **{k}**: `{v}`")

    lines += [
        "",
        "## Consumer Mapping",
        "",
        "| Member / Group | Consumer / Status |",
        "| --- | --- |",
    ]
    for grp, cons in CONSUMER_MAP:
        lines.append(f"| `{grp}` | `{cons}` |")

    lines += [
        "",
        "## Per-Brand Coverage Matrix",
        "",
        "| Archive | Status | Bytes | SHA-256 (prefix) | Members | Duplicates | Skipped | Enumerated | Parser Output | Validated | App Pack | App Consumes | Vehicle Tested |",
        "| --- | --- | ---: | --- | ---: | ---: | ---: | --- | --- | --- | --- | --- | --- |",
    ]
    for b in report["brands"]:
        st = b["statuses"]
        lines.append(
            f"| `{b['archive']}` | {b['status']} | {b['actual_bytes']} | `{b['sha256'][:12]}` | "
            f"{b['member_count']} | {b['duplicate_member_hashes']} | {b['skipped_count']} | "
            f"{st['source_enumerated']} | {st['parser_output_produced']} | {st['rows_structurally_validated']} | "
            f"{st['app_pack_compatible']} | {st['app_consumes']} | {st['vehicle_tested']} |"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GH_REPO", ""))
    parser.add_argument("--local-dir", type=Path, help="Scan local .zip files instead of downloading from assets-v1")
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-md", type=Path, required=True)
    parser.add_argument("--commit-sha", default=os.environ.get("GITHUB_SHA", "local"))
    args = parser.parse_args()

    brands = []
    controls = {"yzjm_control": "NOT RUN"}
    run_parsers = True

    if args.local_dir:
        zips = sorted(args.local_dir.glob("*.zip"), key=lambda p: (p.name != "TOYOTA.zip", p.name))
        for zp in zips:
            brands.append(scan_archive(zp, zp.name, zp.stat().st_size, run_parsers))
    else:
        if not args.repo:
            raise SystemExit("STOP: --repo or GH_REPO is required when --local-dir is not set")
        assets = list_assets_v1(args.repo)
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            for asset in assets:
                subprocess.run(
                    ["gh", "release", "download", asset["tag"], "--repo", args.repo, "--pattern", asset["name"], "--dir", str(tmp), "--clobber"],
                    check=True,
                )
                zp = tmp / asset["name"]
                if asset["name"] == "TOYOTA.zip":
                    try:
                        tc.control(zp)
                        controls["yzjm_control"] = "PASS (108/108)"
                    except Exception as exc:
                        controls["yzjm_control"] = f"FAIL: {exc}"
                        run_parsers = False
                brands.append(scan_archive(zp, asset["name"], asset["bytes"], run_parsers))
                zp.unlink(missing_ok=True)

    report = {
        "generated_at": utc_now(),
        "commit_sha": args.commit_sha,
        "archive_count": len(brands),
        "expected_archive_count": 1 + EXPECTED_OTHER_BRANDS,
        "controls": controls,
        "app_integration_points": APP_INTEGRATION_POINTS,
        "consumer_map": [{"group": g, "consumer": c} for g, c in CONSUMER_MAP],
        "brands": [{k: v for k, v in b.items() if k != "members"} for b in brands],
    }

    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_md.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(report, indent=2) + "\n")
    args.output_md.write_text(render_markdown(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
