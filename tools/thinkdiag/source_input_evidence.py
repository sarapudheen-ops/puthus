#!/usr/bin/env python3
"""Bounded, checksummed research-input recovery when asset redirects are blocked.

Publish runs ONLY in Actions after whole-archive owner verification and the full
1,410-file decoded-manifest validation. Restore reproduces those decoded arrays,
NOT the original archive or independent archive regeneration. Nothing here is
staged in the APK or Git. No source bytes, scripts or libraries execute on restore.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import zlib

from convert_vehicle_packs import load_sources
from sweep_reports import make_parts, publish_check
from vehicle_bundle import SOURCE_SHA256, canonical

MAX_DECODED_BYTES = 128 * 1024 * 1024
PREFIX = 'Toyota decoded source evidence '
REPORT = 'sweep-decoded-source-inputs.md'


def encode_inputs(sources):
    manifest = []; compressor = zlib.compressobj(9); chunks = []; total = 0
    for name, raw in sorted(sources.items()):
        if not isinstance(raw, bytes): raise ValueError('Research source input must be bytes')
        total += len(raw)
        if total > MAX_DECODED_BYTES: raise ValueError('Research input byte budget exceeded')
        manifest.append({'file': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
        chunks.append(compressor.compress(raw))
    chunks.append(compressor.flush())
    return {'kind': 'owner-source-decoded-inputs', 'formatVersion': 1, 'sourceSha256': SOURCE_SHA256,
        'manifest': manifest, 'decodedBytes': total, 'compression': 'zlib',
        'dataBase64': base64.b64encode(b''.join(chunks)).decode('ascii')}


def decode_inputs(record):
    if (not isinstance(record, dict) or record.keys() != {'kind', 'formatVersion', 'sourceSha256', 'manifest', 'decodedBytes', 'compression', 'dataBase64'}
            or record['kind'] != 'owner-source-decoded-inputs' or type(record['formatVersion']) is not int or record['formatVersion'] != 1
            or record['sourceSha256'] != SOURCE_SHA256 or record['compression'] != 'zlib'):
        raise ValueError('Unknown/unpinned research source evidence')
    manifest = record['manifest']; size = record['decodedBytes']
    if (not isinstance(manifest, list) or not 1 <= len(manifest) <= 1410 or type(size) is not int or not 1 <= size <= MAX_DECODED_BYTES
            or not isinstance(record['dataBase64'], str) or len(record['dataBase64']) > 32 * 1024**2):
        raise ValueError('Unbounded research source evidence')
    paths = set(); wanted = 0
    for item in manifest:
        if not isinstance(item, dict) or item.keys() != {'file', 'bytes', 'sha256'}: raise ValueError('Invalid source manifest item')
        name = item['file']
        if (not isinstance(name, str) or not name or '\\' in name or ':' in name or PurePosixPath(name).is_absolute()
                or any(part in ('', '.', '..') for part in name.split('/')) or name in paths
                or type(item['bytes']) is not int or not 0 <= item['bytes'] <= MAX_DECODED_BYTES
                or not isinstance(item['sha256'], str) or len(item['sha256']) != 64):
            raise ValueError('Unsafe, duplicate or unbounded source manifest item')
        paths.add(name); wanted += item['bytes']
    if wanted != size: raise ValueError('Research source manifest byte count differs')
    compressed = base64.b64decode(record['dataBase64'], validate=True)
    decoder = zlib.decompressobj(); data = decoder.decompress(compressed, size + 1)
    if len(data) != size or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError('Truncated, concatenated or oversized research source stream')
    sources = {}; at = 0
    for item in manifest:
        raw = data[at:at + item['bytes']]; at += item['bytes']
        if hashlib.sha256(raw).hexdigest() != item['sha256']: raise ValueError('Research source checksum mismatch')
        sources[item['file']] = raw
    return sources


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    send = sub.add_parser('publish')
    send.add_argument('archive', type=Path); send.add_argument('--decoded', type=Path, required=True)
    send.add_argument('--manifest', type=Path, required=True)
    restore = sub.add_parser('restore'); restore.add_argument('report', type=Path); restore.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'publish':
        sources = load_sources(args.archive, args.decoded, args.manifest)
        record = encode_inputs(sources)
        reports = [{'file': REPORT, 'text': canonical(record).decode('utf-8')}]
        parts = make_parts(reports)
        for part in parts:
            publish_check(os.environ['GH_REPO'], os.environ['GITHUB_SHA'], PREFIX + f"{part['part']:03d}", json.dumps(part))
        print('Published complete decoded research inputs:', len(sources), record['decodedBytes'], len(parts))
    else:
        if args.report.stat().st_size > 32 * 1024**2: raise ValueError('Oversized source evidence document')
        record = json.loads(args.report.read_text()); sources = decode_inputs(record)
        if len(sources) != 1410 or record['decodedBytes'] != 114_797_280:
            raise ValueError('Restore requires the complete owner-source decoded corpus, not a subset')
        # Validate ALL hashes/paths before writing any data, and never traverse symlinks.
        output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
        for name in sources:
            path = output / name
            if path.is_symlink() or output not in path.resolve().parents: raise ValueError('Research output escapes its directory')
        for name, raw in sources.items():
            path = output / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)
        (output.parent / 'decoded-manifest.json').write_bytes(canonical(record['manifest']) + b'\n')
        print('Restored checksummed decoded arrays, not the original archive:', len(sources), record['decodedBytes'])


if __name__ == '__main__': main()
