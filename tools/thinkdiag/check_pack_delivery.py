#!/usr/bin/env python3
"""Verify real public range delivery of the APK-pinned, data-only pack.

Never downloads a complete container and never follows a redirect to an arbitrary host.
Run in Actions after publishing; the phone enforces the same 206/range/identity/hash rules.
"""
import argparse
import hashlib
import zlib
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

from verify_vehicle_bundle import load_index
from vehicle_bundle import MAX_COMPRESSED_BYTES, MAX_OBJECT_BYTES

RELEASE_URL = "https://github.com/sarapudheen-ops/puthus/releases/download/toyota-vehicle-packs-v1"
HOSTS = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}


def check_url(url):
    p = urllib.parse.urlsplit(url)
    if p.scheme != "https" or p.hostname not in HOSTS or p.username or p.password or p.port not in (None, 443):
        raise ValueError("Untrusted vehicle-pack URL")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(container, total, ref, opener=None):
    if not (type(ref["bytes"]) is int and 1 <= ref["bytes"] <= MAX_COMPRESSED_BYTES
            and type(ref["jsonBytes"]) is int and 1 <= ref["jsonBytes"] <= MAX_OBJECT_BYTES
            and type(ref["offset"]) is int and 11 <= ref["offset"] <= total - ref["bytes"]):
        raise ValueError("Invalid pack range")
    opener = opener or urllib.request.build_opener(NoRedirect())
    url = f"{RELEASE_URL}/{container}"
    for attempt in range(4):
        check_url(url)
        request = urllib.request.Request(url, headers={
            "Range": f'bytes={ref["offset"]}-{ref["offset"] + ref["bytes"] - 1}',
            "Accept-Encoding": "identity", "User-Agent": "bod3-pack-delivery-check/1",
        })
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.status in (301, 302, 303, 307, 308):
                location = response.headers.get("Location")
                if attempt == 3 or not location:
                    raise ValueError("Missing/excessive pack redirect")
                url = urllib.parse.urljoin(url, location)
                continue
            expected = f'bytes {ref["offset"]}-{ref["offset"] + ref["bytes"] - 1}/{total}'
            if (response.status != 206 or response.headers.get("Content-Range") != expected
                    or response.headers.get("Content-Encoding", "identity").lower() != "identity"
                    or response.headers.get("Content-Length") not in (None, str(ref["bytes"]))):
                raise ValueError(f"Pack host rejected exact byte-range delivery (HTTP {response.status})")
            packed = response.read(ref["bytes"] + 1)
            if len(packed) != ref["bytes"] or hashlib.sha256(packed).hexdigest() != ref["sha256"]:
                raise ValueError("Published pack range is truncated, oversized or corrupt")
        # Expand at most the declared size + 1; reject extra gzip members and bombs.
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        raw = decoder.decompress(packed, ref["jsonBytes"] + 1)
        if len(raw) != ref["jsonBytes"] or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise ValueError("Published JSON exceeds/differs from its declared bound")
        return json.loads(raw)
    raise ValueError("Pack redirect did not resolve")


def check(directory, downloader=fetch):
    index = load_index(directory / "index.json")
    requests = 0
    transferred = 0

    def get(ref):
        nonlocal requests, transferred
        requests += 1
        transferred += ref["bytes"]
        if requests > 8 or transferred > 2 * 1024 * 1024:
            raise ValueError("Delivery smoke check exceeded its on-demand budget")
        return downloader(index["container"], index["containerBytes"], ref)

    page = get(index["catalogRoot"])
    # This is a delivery check, not a configuration selection or any ECU request.
    for expected in ("make", "model", "generation", "variant"):
        children = page["children"]
        if not children:
            raise ValueError("No reviewed path is available to verify pack delivery")
        child = children[0]
        if child["level"] != expected:
            raise ValueError("Published hierarchy skipped a manual level")
        page = get(child["object"])
        if page["id"] != child["id"]:
            raise ValueError("Published path identity mismatch")
    if page["kind"] != "converted-vehicle" or not page["systems"]:
        raise ValueError("Published selected vehicle has no system accounting")
    system = get(page["systems"][0]["object"])
    if system["kind"] != "vehicle-system":
        raise ValueError("Published selected system has the wrong object type")
    return {"requests": requests, "rangeBytes": transferred, "containerBytes": index["containerBytes"],
            "container": index["container"], "vehicleId": page["id"], "systemId": system["id"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(check(args.directory), sort_keys=True))
