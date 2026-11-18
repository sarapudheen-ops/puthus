#!/usr/bin/env python3
"""Publish/recover checksum-verified sweep reports using a few bounded checks.

The human overview is readable directly. Detailed text reports are a compressed
JSON bundle, not executable code; raw vendor libraries are never included.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zlib

FORMAT = "bod3-toyota-sweep-reports"
MAX_BYTES = 32 * 1024 * 1024
SAFE_NAME = re.compile(r"sweep-[A-Za-z0-9_.-]+\.md")


def validate_reports(reports):
    seen = set()
    if not isinstance(reports, list) or not 1 <= len(reports) <= 1000:
        raise ValueError("Invalid report bundle list")
    for report in reports:
        if not isinstance(report, dict) or set(report) != {"file", "text"}:
            raise ValueError("Invalid report bundle record")
        name = report["file"]
        if not isinstance(name, str) or not SAFE_NAME.fullmatch(name) or name in seen:
            raise ValueError("Unsafe/duplicate report name")
        if not isinstance(report["text"], str):
            raise ValueError("Report text must be a string")
        seen.add(name)


def make_parts(reports, chunk_chars=48000):
    validate_reports(reports)
    if not 1000 <= chunk_chars <= 48000:
        raise ValueError("Invalid bundle chunk size")
    raw = json.dumps(reports, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    if len(raw) > MAX_BYTES:
        raise ValueError("Report bundle is too large")
    compressed = zlib.compress(raw, 9)
    encoded = base64.b64encode(compressed).decode("ascii")
    chunks = [encoded[i:i + chunk_chars] for i in range(0, len(encoded), chunk_chars)]
    return [{"format": FORMAT, "formatVersion": 1, "part": i + 1, "parts": len(chunks),
             "compression": "zlib", "compressedSha256": hashlib.sha256(compressed).hexdigest(),
             "uncompressedSha256": hashlib.sha256(raw).hexdigest(), "dataBase64": chunk}
            for i, chunk in enumerate(chunks)]


def unpack_parts(parts):
    if not parts or len(parts) > 1000:
        raise ValueError("Missing/excessive report bundle parts")
    parts = sorted(parts, key=lambda p: p["part"])
    for i, part in enumerate(parts, 1):
        if (part["format"] != FORMAT or part["formatVersion"] != 1 or part["compression"] != "zlib"
                or part["parts"] != len(parts) or part["part"] != i
                or part["compressedSha256"] != parts[0]["compressedSha256"]
                or part["uncompressedSha256"] != parts[0]["uncompressedSha256"]
                or not isinstance(part["dataBase64"], str) or len(part["dataBase64"]) > 48000):
            raise ValueError("Inconsistent/missing/duplicate report bundle part")
    compressed = base64.b64decode("".join(p["dataBase64"] for p in parts), validate=True)
    if hashlib.sha256(compressed).hexdigest() != parts[0]["compressedSha256"]:
        raise ValueError("Compressed report checksum mismatch")
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, MAX_BYTES + 1)
    if len(raw) > MAX_BYTES or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Truncated, oversized or concatenated report bundle")
    if hashlib.sha256(raw).hexdigest() != parts[0]["uncompressedSha256"]:
        raise ValueError("Uncompressed report checksum mismatch")
    reports = json.loads(raw)
    validate_reports(reports)
    return reports


def publish_check(repository, head, name, summary):
    payload = {"name": name, "head_sha": head, "status": "completed", "conclusion": "success",
               "output": {"title": name, "summary": summary}}
    for attempt in range(3):
        result = subprocess.run(["gh", "api", "--method", "POST", f"repos/{repository}/check-runs",
                                 "--input", "-", "--jq", ".id"], input=json.dumps(payload),
                                text=True, capture_output=True, timeout=60)
        if result.returncode == 0:
            print(f"Published {name}: {result.stdout.strip()}")
            return
        if attempt < 2 and re.search(r"rate limit|secondary|HTTP 429", result.stderr, re.I):
            time.sleep(10 * (attempt + 1))
            continue
        raise RuntimeError(f"Failed to publish {name}: {result.stderr[:3000]}")


def publish(reports_dir, output, repository, head):
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("A real repository and current commit SHA are required")
    index = json.loads((reports_dir / "sweep-reports.json").read_text())
    reports = []
    for item in index["reports"]:
        if not SAFE_NAME.fullmatch(item["file"]):
            raise ValueError("Unsafe report manifest name")
        data = (reports_dir / item["file"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError("Report manifest checksum mismatch")
        reports.append({"file": item["file"], "text": data.decode("utf-8")})
    parts = make_parts(reports)
    output.mkdir(parents=True, exist_ok=True)
    for part in parts:
        (output / f"report-bundle-{part['part']:03d}.json").write_text(json.dumps(part, indent=2) + "\n")
    publish_check(repository, head, "Toyota diagnostic sweep", (reports_dir / "sweep-overview.md").read_text())
    for part in parts:
        publish_check(repository, head, f"Toyota sweep evidence {part['part']:03d}", json.dumps(part))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    send = commands.add_parser("publish")
    send.add_argument("--reports", type=Path, required=True)
    send.add_argument("--output", type=Path, required=True)
    send.add_argument("--repository", default=os.environ.get("GH_REPO", ""))
    send.add_argument("--head", default=os.environ.get("GITHUB_SHA", ""))
    restore = commands.add_parser("unpack")
    restore.add_argument("parts", type=Path, nargs="+")
    restore.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "publish":
        try:
            publish(args.reports, args.output, args.repository, args.head)
        except Exception as error:
            message = str(error).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Sweep evidence publication::{message}", flush=True)
            raise
    else:
        reports = unpack_parts([json.loads(p.read_text()) for p in args.parts])
        args.output.mkdir(parents=True, exist_ok=True)
        for report in reports:
            path = args.output / report["file"]
            if path.is_symlink():
                raise ValueError("Refusing to overwrite a report symlink")
            path.write_text(report["text"], encoding="utf-8")
        print(f"Restored {len(reports)} checksum-verified text reports.")
