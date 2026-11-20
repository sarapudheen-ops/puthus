"""Deterministic, content-checked range bundle for on-demand vehicle definitions.

Each object is an independent gzip-compressed JSON document. The small HTTPS
index contains the root reference; every child reference carries exact byte
range, compressed SHA-256 and expansion bound. A client downloads only the
manually chosen path/vehicle/system, never the full corpus or a monolithic APK
asset. No pickle, bytecode, native code, vendor scripts or EXPRESS evaluation.
"""
import gzip
import hashlib
import json
from pathlib import Path

MAGIC = b"BOD3PACKS1\n"
MAX_OBJECT_BYTES = 8 * 1024 * 1024
MAX_COMPRESSED_BYTES = 4 * 1024 * 1024
MAX_CONTAINER_BYTES = 512 * 1024 * 1024
FORMAT = "bod3-toyota-vehicle-index"
VERSION = 1
SOURCE_SHA256 = "26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a"


def canonical(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


class BundleWriter:
    def __init__(self, output: Path):
        output.mkdir(parents=True, exist_ok=True)
        self.output = output
        self.path = output / "bundle.partial"
        self.stream = self.path.open("wb")
        self.stream.write(MAGIC)
        self.refs = {}

    def add(self, value):
        raw = canonical(value)
        if not 1 <= len(raw) <= MAX_OBJECT_BYTES:
            raise ValueError("Vehicle object exceeds bounded per-document JSON size; split it")
        packed = gzip.compress(raw, compresslevel=9, mtime=0)
        if len(packed) > MAX_COMPRESSED_BYTES:
            raise ValueError("Compressed vehicle object is too large")
        key = hashlib.sha256(packed).hexdigest()
        if key in self.refs:
            return self.refs[key]
        offset = self.stream.tell()
        if offset + len(packed) > MAX_CONTAINER_BYTES:
            raise ValueError("Vehicle bundle exceeds container limit; shard the manufacturer")
        ref = {"offset": offset, "bytes": len(packed), "jsonBytes": len(raw), "sha256": key}
        self.stream.write(packed)
        self.refs[key] = ref
        return ref

    def finish(self, root, summary, catalog_root=None, source_inventory=None):
        self.stream.flush()
        self.stream.close()
        data = self.path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        name = f"toyota-{digest}.bin"
        self.path.replace(self.output / name)
        index = {"format": FORMAT, "formatVersion": VERSION, "sourceSha256": SOURCE_SHA256,
                 "converterVersion": 1, "manualSelectionOnly": True, "container": name,
                 "containerBytes": len(data), "containerSha256": digest, "root": root,
                 "catalogRoot": catalog_root, "summary": summary}
        if source_inventory is not None:
            index["sourceInventory"] = source_inventory
        (self.output / "index.json").write_bytes(canonical(index) + b"\n")
        return index


def read_object(path, ref):
    """Strict verifier used by converter tests and CI. Not a forgiving source decoder."""
    if set(ref) != {"offset", "bytes", "jsonBytes", "sha256"}:
        raise ValueError("Unknown range reference field")
    if (type(ref["offset"]) is not int or type(ref["bytes"]) is not int or type(ref["jsonBytes"]) is not int
            or not len(MAGIC) <= ref["offset"] <= MAX_CONTAINER_BYTES
            or not 1 <= ref["bytes"] <= MAX_COMPRESSED_BYTES
            or not 1 <= ref["jsonBytes"] <= MAX_OBJECT_BYTES
            or ref["offset"] + ref["bytes"] > path.stat().st_size):
        raise ValueError("Invalid range reference")
    with path.open("rb") as stream:
        stream.seek(ref["offset"])
        packed = stream.read(ref["bytes"])
    if hashlib.sha256(packed).hexdigest() != ref["sha256"]:
        raise ValueError("Range object checksum mismatch")
    # zlib's explicit output bound also rejects concatenated gzip members and bombs.
    import zlib
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    raw = decoder.decompress(packed, ref["jsonBytes"] + 1)
    if (len(raw) != ref["jsonBytes"] or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail):
        raise ValueError("Range object expansion mismatch")
    return json.loads(raw)
