#!/usr/bin/env python3
"""Owner-authorized offline native/source differential checks, never an ECU bridge.

The supplied FILE/COMM/DTCF/CALC instructions run in bounded AArch64 emulation. File
APIs below see only immutable, supplied decoded byte arrays. Adapter, discovery,
UI and open-library boundaries are explicit fakes, recorded as such. There is
no physical validation and no claim that mocked boundaries themselves worked.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import struct
import zipfile

from analyze_toyota import checked_infos
from convert_vehicle_packs import load_sources
from extract_english import read_text_libraries
from native_replay import NativeSandbox, ReplayError
from toyota_native_transport import compare_fast_kwp
from toyota_dtc_labels import (DiagnosticCodeMerge, FaultLabels, fallback_code, fallback_description)
from source_verification import verify_archive
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_faults import NativeFaultLayout, NativeP5FaultLayout, state_ids
from toyota_functions import FunctionBlocks
from vehicle_bundle import SOURCE_SHA256, canonical

REFERENCE = Path(__file__).parent / 'schema' / 'native-oracle-v1.json'


def verify_reference(result, reference=None):
    """Typed exact/subset comparison to the reviewed native identities/result hashes.

    Missing or changed results fail. In particular, True/1 and False/0 are not
    interchangeable evidence, nor is a partial/skipped run a complete oracle.
    """
    reference = json.loads(REFERENCE.read_text()) if reference is None else reference
    def equal(actual, expected, path):
        if type(actual) is not type(expected):
            raise ValueError(f'Native reference type mismatch at {path}')
        if isinstance(expected, dict):
            for key, value in expected.items():
                if key not in actual: raise ValueError(f'Missing native comparison at {path}.{key}')
                equal(actual[key], value, path + '.' + key)
        elif isinstance(expected, list):
            if len(actual) != len(expected):
                raise ValueError(f'Native reference list length mismatch at {path}')
            for i, value in enumerate(expected):
                equal(actual[i], value, f'{path}[{i}]')
        elif actual != expected:
            raise ValueError(f'Native reference mismatch at {path}')
    equal(result, reference, 'oracle')
    if 'frameComparison' in reference:
        frames = result['frameComparison']
        if (not isinstance(frames.get('templates'), list)
                or len(frames['templates']) != frames.get('comparedTemplates')
                or hashlib.sha256(canonical(frames['templates'])).hexdigest() != frames.get('frameResultsSha256')):
            raise ValueError('Native frame details do not match their reviewed result digest/count')


class MemoryFiles:
    """Explicit fseekEx/freadEx fakes, ONLY over experiment-provided decoded bytes."""
    def __init__(self, sandbox):
        self.sandbox = sandbox
        self.handles = {}
        self.reads = 0

    def register(self, raw):
        handle = self.sandbox.allocate(16)
        self.handles[handle] = [bytes(raw), 0]
        return handle

    def seek(self, handle, offset, whence, *_):
        raw, pos = self.handles[handle]
        offset = (offset & 0xffffffff) - (1 << 32) if offset & 0x80000000 else offset & 0xffffffff
        at = offset + ({0: 0, 1: pos, 2: len(raw)}[whence])
        if not 0 <= at <= len(raw):
            raise ReplayError('Native reader sought outside the supplied byte array')
        self.handles[handle][1] = at
        return 0

    def read(self, dst, size, count, handle, *_):
        raw, pos = self.handles[handle]
        n = size * count
        if n > 1_048_576 or pos + n > len(raw):
            raise ReplayError('Native reader requested bytes outside the supplied byte array')
        self.sandbox.write(dst, raw[pos:pos + n])
        self.handles[handle][1] += n
        self.reads += 1
        return count

    def bind(self, library='libTOYOTA_FILE.so'):
        self.sandbox.bind('fseekEx', self.seek, indirect=True, library=library)
        self.sandbox.bind('freadEx', self.read, indirect=True, library=library)


def compare_entries(libraries, source):
    name = 'libTOYOTA_FILE.so'
    native = NativeSandbox({name: libraries[name]})
    files = MemoryFiles(native); files.bind()
    handle = files.register(source)
    output = native.allocate(0x20000)
    index = EntryIndex(source)
    total, descriptors = 0, Counter()
    for entry_list in index.lists.values():
        for entry in entry_list.entries:
            native.write(output, bytes(0x20000))
            result = native.call(name, 'GetInfoFromEnterList', handle, entry_list.offset, entry.descriptor, output)
            if result != 0:
                raise AssertionError('Native GetInfoFromEnterList did not return success')
            def equal(offset, expected):
                actual = native.read(output + offset, len(expected))
                if actual != expected:
                    raise AssertionError(f'{entry.source_id}: native +{offset:x} {actual.hex()} != port {expected.hex()}')
            equal(0x10827, entry_list.header[1:2])
            equal(0x1082c, entry_list.header[2:3])
            equal(0x10826, entry_list.header[3:4])
            equal(0x10828, bytes([entry.transport, entry.flags]))
            equal(0x107c8, struct.pack('<I', entry.baud))
            for field, value in entry.fields.items():
                if field.startswith('runtime'):
                    equal(int(field[7:], 16), bytes.fromhex(value))
                elif field == 'pinPairs':
                    pins = bytes.fromhex(value)
                    equal(0x107e5, bytes([len(pins)]))
                    equal(0x107e6, pins)
            for at, bindings in ((0x107f5, entry.init), (0x10800, entry.keepalive), (0x1080c, entry.exit)):
                equal(at, bytes([len(bindings)]))
                equal(at + (1 if at == 0x107f5 else 2), b''.join(struct.pack('<H', b) for b in bindings))
            total += 1; descriptors[f'{entry.descriptor:02x}'] += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
            'function': 'GetInfoFromEnterList', 'comparedRecords': total, 'bySelector': dict(descriptors),
            'inMemoryReadCalls': files.reads,
            'fakeBoundaries': ['fseekEx/freadEx over decoded byte arrays; not libDEVICEID file decryption']}


def compare_frames(libraries, sources, region, function, templates):
    name = 'libTOYOTA_COMM.so'
    native = NativeSandbox({name: libraries[name]})
    context = native.allocate(0x20000)
    tables = native.allocate(0x100)
    for offset, filename in ((0, 'Toyota_bin_00.BIN'), (0x38, 'Toyota_bin_11.BIN'), (0x40, 'Toyota_bin_12.BIN')):
        raw = sources[f'{region}/{filename}']
        native.write(tables + offset, struct.pack('<Q', native.allocate(len(raw), raw)))
    indices = native.allocate(64)
    frames = SourceFrames(sources, region)
    report = []
    for template in templates:
        expected = frames.function(function, template)
        native.write(context, bytes(0x20000))
        count = native.call(name, 'GetFuncCommFrameInfo_MultiCmd', template, indices, function, tables, context)
        if count != len(expected):
            raise AssertionError(f'Native/port function binding count mismatch for {region}/{function}/{template}: {count} != {len(expected)}')
        for i, frame in enumerate(expected):
            slot = int.from_bytes(native.read(indices + i * 2, 2), 'little')
            at = context + slot * 0x102
            if struct.unpack('<H', native.read(at + 0x10836, 2))[0] != frame.frame_id:
                raise AssertionError('Native frame ID/order differs from the exact binding')
            values = []
            for length_at in (0x1083b, 0x10905, 0x1086e):
                size = native.read(at + length_at, 1)[0]
                values.append(native.read(at + length_at + 1, size))
            if values != [frame.request, frame.mask, frame.response]:
                raise AssertionError(f'Native frame-role/bytes mismatch for {region}/{function}/{template}/{frame.frame_id}')
        report.append({'template': template, 'frames': [{'frameId': f.frame_id, 'request': f.request.hex(),
            'mask': f.mask.hex(), 'response': f.response.hex(), 'sourceRecords': list(f.source_records)} for f in expected]})
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
            'function': function, 'region': region, 'templates': report, 'comparedTemplates': len(report),
            'frameResultsSha256': hashlib.sha256(canonical(report)).hexdigest(),
            'fakeBoundaries': ['libc memory functions']}


def compare_regional_templates(libraries, sources):
    """Every regional frame/default binding and declared RDTC/CDTC/initial template.

    Bindings are compared to the real unmodified regional tables, including
    genuine zero-result default lookups. Nondefault conditions are counted but
    not executed. This is not transport, communication or special-workflow proof.
    """
    name = 'libTOYOTA_COMM.so'
    native = NativeSandbox({name: libraries[name]})
    context = native.allocate(0x20000); tables = native.allocate(0x100); indices = native.allocate(64)
    digest = hashlib.sha256(); counts = {}
    for region in ('EU', 'NA', 'JP'):
        for offset, filename in ((0, 'Toyota_bin_00.BIN'), (0x38, 'Toyota_bin_11.BIN'), (0x40, 'Toyota_bin_12.BIN')):
            raw = sources[f'{region}/{filename}']
            native.write(tables + offset, struct.pack('<Q', native.allocate(len(raw), raw)))
        functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN'])
        frames = SourceFrames(sources, region); queries = set(); census = Counter()
        for row in frames.frames:
            frame = frames.frame(row['frameId'])
            native.write(context, bytes(0x20000))
            # Its caller uses sxth on the returned machine word: the source
            # success path returns count + 0xffff (e.g. 0x10000 for slot zero).
            slot = native.call(name, 'GetCommFrameInfo', frame.frame_id, tables, context) & 0xffff
            if slot != 0: raise AssertionError('Native standalone frame failed to load in an empty context')
            if struct.unpack('<H', native.read(context + 0x10836, 2))[0] != frame.frame_id:
                raise AssertionError('Native standalone frame ID mismatch')
            digest.update(b'frame' + region.encode() + struct.pack('<H', frame.frame_id))
            for offset, value in ((0x1083b, frame.request), (0x10905, frame.mask), (0x1086e, frame.response)):
                size = native.read(context + offset, 1)[0]
                if native.read(context + offset + 1, size) != value:
                    raise AssertionError(f'Native standalone frame bytes mismatch: {region}/{frame.frame_id}')
                digest.update(bytes([size]) + value)
            census['standaloneFrames'] += 1
        for function in functions.rows:
            block = functions.faults(function)
            if block:
                queries.update((function, t) for t in block.initial_templates)
                queries.update((function, q.template) for q in block.queries)
            block = functions.clear(function)
            if block: queries.update((function, q.template) for q in block.queries)
        census['faultClearInitialLookups'] = len(queries)
        census['defaultBindingKeys'] = len(frames._bindings)
        census['nondefaultBindingsNotExecuted'] = len(frames.bindings) - sum(len(rows) for rows in frames._bindings.values())
        queries.update(frames._bindings)
        for function, template in sorted(queries):
            expected = frames.function(function, template)
            native.write(context, bytes(0x20000)); native.write(indices, bytes(64))
            count = native.call(name, 'GetFuncCommFrameInfo_MultiCmd', template, indices, function, tables, context)
            if count != len(expected): raise AssertionError(f'Regional native/port fault-template count mismatch for {region}/{function}/{template}')
            digest.update(b'binding' + region.encode() + struct.pack('<HHH', function, template, count))
            census['templateLookups'] += 1
            census['resolvedFrames'] += count
            if not count: census['withoutDefaultBinding'] += 1
            for i, frame in enumerate(expected):
                slot = struct.unpack('<H', native.read(indices + i * 2, 2))[0]
                if slot >= 20: raise AssertionError('Unbounded native frame slot')
                at = context + slot * 0x102
                if struct.unpack('<H', native.read(at + 0x10836, 2))[0] != frame.frame_id:
                    raise AssertionError('Native regional fault frame ID/order mismatch')
                digest.update(struct.pack('<H', frame.frame_id))
                for offset, value in ((0x1083b, frame.request), (0x10905, frame.mask), (0x1086e, frame.response)):
                    size = native.read(at + offset, 1)[0]
                    if native.read(at + offset + 1, size) != value:
                        raise AssertionError(f'Regional native/port fault frame bytes mismatch: {region}/{function}/{template}/{frame.frame_id}')
                    digest.update(bytes([size]) + value)
        counts[region] = dict(census)
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'regions': counts, 'frameResultsSha256': digest.hexdigest(),
        'fakeBoundaries': ['libc memory functions; no adapter or query-condition execution']}


def compare_function_readers(libraries, sources):
    """Compare every FILE RDTC/CDTC loaded field, including all packed code rows."""
    name = 'libTOYOTA_FILE.so'
    native = NativeSandbox({name: libraries[name]})
    files = MemoryFiles(native); files.bind()
    handles = {f'./FUNCDATA_{region}.BIN'.encode(): files.register(sources[f'FUNCDATA_{region}.BIN'])
               for region in ('EU', 'NA', 'JP')}
    # Online overrides are not in the pinned source and this experiment has no
    # filesystem. Do not search the host or silently use another region.
    native.bind('CheckOnLineFileIsExist', lambda *_: 0)
    native.bind('OpenDeviceIdDll', lambda *_: 0)
    def open_file(_function, path, mode, *_):
        filename = native.string(path)
        if native.string(mode) != b'rb' or filename not in handles:
            raise ReplayError('FILE oracle requested an undeclared input')
        return handles[filename]
    def close_file(handle, *_):
        if handle not in files.handles: raise ReplayError('Closing an unknown in-memory file')
        return 0
    native.bind('fopenEx', open_file, indirect=True, library=name)
    native.bind('fcloseEx', close_file, indirect=True, library=name)
    out = native.allocate(16384)
    digest = hashlib.sha256(); counts = {}
    def equal(offset, expected):
        if native.read(out + offset, len(expected)) != expected:
            raise AssertionError(f'FILE native/port RDTC/CDTC field mismatch at +{offset:x}')
        digest.update(struct.pack('<I', offset) + expected)
    for region_id, region in enumerate(('EU', 'NA', 'JP'), 1):
        functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN'])
        census = Counter()
        for key in functions.rows:
            block = functions.faults(key)
            if block is not None:
                native.write(out, bytes(16384))
                if native.call(name, 'GetRDtcDataFromRDTCPart', block.source_offset, region_id, out) != 0:
                    raise AssertionError('Native FILE RDTC reader returned failure')
                equal(0x10, bytes([len(block.initial_templates)]))
                equal(0x12, b''.join(struct.pack('<H', i) for i in block.initial_templates))
                equal(0x1c, bytes([block.byte1, block.byte2]))
                equal(0x20, bytes([block.count_index, block.record_start, block.record_bytes, block.assembly_kind]))
                equal(0x2c, bytes([len(block.queries)]))
                for i, query in enumerate(block.queries):
                    at = 0x2e + i * 28
                    equal(at, bytes([query.kind]))
                    equal(at + 2, struct.pack('<HHB', query.word1, query.template, len(query.conditions)))
                    for j, condition in enumerate(query.conditions):
                        equal(at + 8 + j * 2, struct.pack('<H', condition.key))
                        equal(at + 0x12 + j, bytes([condition.byte2]))
                        equal(at + 0x17 + j, bytes([condition.byte3]))
                equal(0, struct.pack('<H', len(block.mappings)))
                pointer = native.u64(out + 8)
                actual = native.read(pointer, 8 * len(block.mappings)) if pointer else b''
                expected = b''.join(m.encode() + b'\0\0' for m in block.mappings)
                if actual != expected:
                    raise AssertionError('Native FILE RDTC packed code mapping differs from the port')
                digest.update(actual)
                census['faultBlocks'] += 1; census['faultQueries'] += len(block.queries)
                census['faultCodeMappings'] += len(block.mappings)
            block = functions.clear(key)
            if block is not None:
                native.write(out, bytes(16384))
                if native.call(name, 'GetCDtcDataFromCDTCPart', block.source_offset, region_id, out) != 0:
                    raise AssertionError('Native FILE CDTC reader returned failure')
                equal(0, bytes([len(block.queries)]))
                for i, query in enumerate(block.queries):
                    equal(1 + i, bytes([query.kind])); equal(6 + i * 2, struct.pack('<H', query.template))
                census['clearBlocks'] += 1; census['clearQueries'] += len(block.queries)
        counts[region] = dict(census)
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
            'regions': counts, 'comparedFieldBytesSha256': digest.hexdigest(), 'inMemoryReadCalls': files.reads,
            'fakeBoundaries': ['fopenEx/fseekEx/freadEx/fcloseEx over supplied decoded byte arrays; not libDEVICEID decryption',
                               'OpenDeviceIdDll does not load host code', 'no online override files']}


def compare_fault_decoder(libraries):
    name = 'libTOYOTA_DTCF.so'
    native = NativeSandbox({name: libraries[name]})
    out = native.allocate(256)
    state_digest = hashlib.sha256(); state_vectors = 0
    for kind in range(1, 11):
        for status in range(256):
            # Full state/mask domain for the only mask-dependent query kind.
            for mask in (range(256) if kind == 10 else (0, 255)):
                expected = state_ids(kind, status, mask)
                native.write(out, bytes(256))
                count = native.call(name, 'GetDtcStateResult', kind, status, mask, out)
                actual = tuple(struct.unpack('<' + 'I' * count, native.read(out, count * 4)))
                if actual != expected:
                    raise AssertionError(f'Native state IDs differ for {kind}/{status}/{mask}: {actual} != {expected}')
                state_digest.update(bytes([kind, status, mask]) + native.read(out, count * 4)); state_vectors += 1
    context = native.allocate(0x400)
    source_block = native.allocate(0x100)
    native.write(context + 0x1b0, struct.pack('<Q', source_block))
    data = native.allocate(4096)
    result_digest = hashlib.sha256(); vectors = 0
    for assembly, stride in ((1, 1), (1, 4), (2, 2), (3, 2), (4, 3), (5, 3), (6, 4), (7, 6)):
        for mode in (0, 2):
            for presence in (False, True):
                layout = NativeFaultLayout(0, 1, stride, assembly, presence, mode)
                native.write(source_block + 0x1c, bytes([2 if presence else 1]))
                native.write(context + 0x18, bytes([mode]))
                packed = int.from_bytes(bytes([layout.count_index, layout.record_start, stride, assembly]), 'little')
                for kind in (1, 2, 3, 8, 9, 10):
                    for value in (0, 1, 4, 8, 0x10, 0x20, 0x28, 0x29, 0x7f, 0x80, 0xff):
                        code_bytes = stride - (1 if assembly in (5, 6) else 3 if assembly == 7 else 0)
                        code = bytes([value]) + bytes(range(1, code_bytes))
                        row = code + (bytes([value]) if assembly in (5, 6) else b'\x55\xaa\xff' if assembly == 7 else b'')
                        start = 0 if kind == 3 and mode != 2 else 1
                        payload = (b'\x03' if start else b'') + row + row + bytes(stride)
                        expected = layout.decode(payload, kind, 0xff)
                        native.write(data, payload); native.write(out, bytes(256))
                        answer = native.call(name, 'GetDTCCodeFromAnsP3P4', len(payload), data, packed, 0, out, kind, 255, context)
                        if not answer: raise AssertionError('Native DTC decoder returned allocation/failure null')
                        n = struct.unpack('<I', native.read(answer, 4))[0]
                        if n > 20: raise AssertionError('Unbounded native DTC result')
                        actual = tuple(struct.unpack('<II', native.read(answer + 4 + i * 8, 8)) for i in range(n))
                        if actual != expected:
                            raise AssertionError(f'Native DTC assembly/state mismatch: {layout}/{kind}/{payload.hex()} => {actual} != {expected}')
                        result_digest.update(bytes([assembly, stride, mode, presence, kind, value]) + native.read(answer, 4 + n * 8)); vectors += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'stateVectors': state_vectors, 'stateResultsSha256': state_digest.hexdigest(),
        'recordVectors': vectors, 'recordResultsSha256': result_digest.hexdigest(),
        'fakeBoundaries': ['malloc/realloc/free within guest heap'],
        'atomicHardening': 'The port additionally rejects short counts, invalid layouts and partial record tails; native partial-result behavior is not enabled'}


def compare_p5_decoder(libraries, sources):
    """Native P5 state/code projection; auxiliary freeze/mask side tables disabled.

    Context+1e8 is an explicit experiment setting, not a vehicle applicability
    flag. The native routine still assembles every record, calls the real state
    function, deduplicates and grows its result. No auxiliary P5 workflow proof
    is inferred from this isolated comparison.
    """
    name = 'libTOYOTA_DTCF.so'
    native = NativeSandbox({name: libraries[name]})
    context = native.allocate(0x18000)
    native.write(context + 0x1e8, b'\x01')
    table = native.allocate(129)
    data = native.allocate(4096)
    output = native.allocate(4096)
    digest = hashlib.sha256(); vectors = 0
    for start in (0, 1, 2, 7):
        for width in (2, 3, 4, 5):
            layout = NativeP5FaultLayout(start, width)
            for kind in (1, 3, 8, 9, 10):
                for status in range(256):
                    for mask in ((0, 1, 2, 4, 8, 15, 85, 255) if kind == 10 else (255,)):
                        row = bytes((status + i) & 255 for i in range(width - 1)) + bytes([status])
                        payload = bytes(range(start)) + row + row + bytes(width)
                        expected = layout.decode(payload, kind, mask)
                        native.write(data, payload); native.write(output, bytes(4096))
                        packed = int.from_bytes(bytes([255, start, width, 6]), 'little')
                        answer = native.call(name, 'GetDTCCodeFromAnsP5', len(payload), data, packed, 0,
                                             output, kind, mask, table, context)
                        if not answer: raise AssertionError('Native P5 decoder failed to return its result')
                        count = struct.unpack('<I', native.read(answer, 4))[0]
                        if count > 6: raise AssertionError('Unbounded native P5 projection')
                        actual = tuple(struct.unpack('<II', native.read(answer + 4 + i * 8, 8)) for i in range(count))
                        if actual != expected:
                            raise AssertionError(f'Native P5 projection differs: {layout}/{kind}/{mask}/{payload.hex()}: {actual} != {expected}')
                        digest.update(bytes([start, width, kind, status, mask]) + native.read(answer, 4 + count * 8))
                        vectors += 1
    headers = {}; blocks = 0
    for region in ('EU', 'NA', 'JP'):
        functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN'])
        count = 0
        for key in functions.rows:
            block = functions.faults(key)
            if block is not None and block.byte1 & 7 == 3:
                fields = (block.byte1, block.byte2, block.count_index, block.record_start,
                          block.record_bytes, block.assembly_kind)
                if fields != (3, 8, 255, 2, 4, 6) or any(q.kind != 10 for q in block.queries):
                    raise AssertionError('A source P5 block differs from the compared source shape')
                count += 1
        headers[region] = count; blocks += count
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'recordVectors': vectors, 'recordResultsSha256': digest.hexdigest(), 'sourceBlocks': blocks,
        'sourceBlocksByRegion': headers, 'sourceLayoutBytes': '0308ff020406',
        'fakeBoundaries': ['malloc/realloc/free within guest heap',
                           'context+1e8=1 disables auxiliary P5 freeze-frame/mask side-table allocation'],
        'notCovered': 'P5 query selection, mask discovery, auxiliary diagnostic side tables, transport and ECU applicability',
        'atomicHardening': 'The port rejects an incomplete record tail instead of retaining a native partial result'}


def compare_fault_labels(libraries, sources, codes, texts):
    """Execute DTCF's display/split/fallback and exact-key byte-merge consumers.

    IdSearch is a declared fake over source-decoded English pairs. This compares
    the DTCF consumer, NOT libSTD's search/decryption or host locale/libc.
    """
    name = 'libTOYOTA_DTCF.so'
    native = NativeSandbox({name: libraries[name]})
    labels = FaultLabels(codes)
    source_block = native.allocate(0x100); context = native.allocate(0x400)
    native.write(context + 0x1b0, struct.pack('<Q', source_block))
    output = native.allocate(4096); description = native.allocate(4096); state = native.allocate(4096)
    native.bind('OpenLanguageDLL', lambda *_: 0)
    native.bind('OpenSearchIDDLL', lambda *_: 0)
    def language(dst, *_): native.write(dst, b'EN\0'); return 0
    native.bind('GetLangurageCode', language)
    def open_file(path, *_):
        if native.string(path) != b'TCODE_EN.GAG': raise ReplayError('Unexpected DTC label file')
        return 1
    def close_file(handle, *_):
        if handle != 1: raise ReplayError('Unexpected DTC label handle')
        return 0
    def search(key, dst, size, handle, *_):
        if handle != 1 or size != 512: raise ReplayError('Unexpected bounded DTC label lookup')
        identity = native.read(key, 4).hex().upper()
        pair = codes.get(identity)
        if pair is None: return 0
        raw = pair.encode('utf-8') + b'\0'
        if len(raw) > size: raise ReplayError('Source DTC pair exceeds native buffer')
        native.write(dst, raw); return len(raw)
    def text(identity, dst, size, *_):
        raw = texts[f'{identity & 0xffffffff:08X}'].encode('utf-8') + b'\0'
        if len(raw) > size: raise ReplayError('Source state text exceeds native buffer')
        native.write(dst, raw); return len(raw)
    for fn, callback in (('SearchIdOpenFile', open_file), ('SearchIdCloseFile', close_file),
                         ('IdSearch', search), ('GetTextFromTextLib', text)):
        native.bind(fn, callback, indirect=True, library=name)
    digest = hashlib.sha256(); vector_count = 0
    for key, label in labels.labels.items():
        native.write(source_block + 0x23, b'\x02')
        native.call(name, 'GetShowDtcInfo', key, 0xffff0050, output, description, state, context)
        actual = (native.string(output).decode('utf-8'), native.string(description).decode('utf-8'), native.string(state).decode('utf-8'))
        if actual != (label.code, label.description, texts['FFFF0050']):
            raise AssertionError(f'Native exact TCODE consumer mismatch at {key:08X}')
        digest.update(struct.pack('>I', key) + b'\0'.join(v.encode('utf-8') for v in actual) + b'\0'); vector_count += 1
    fallback_digest = hashlib.sha256(); fallback_vectors = 0
    # Every possible top byte and low-byte edge for both source widths, all
    # assembly kinds. A genuinely missing exact key is used for GetShowDtcInfo.
    for assembly in range(1, 8):
        for high in range(256):
            for low in (0, 0xff):
                key = (high << 16) | (high << 8) | low
                native.call(name, 'InitDtcNum', key, output, assembly)
                actual = native.string(output).decode('ascii')
                expected = fallback_code(key, assembly)
                if actual != expected: raise AssertionError('Native InitDtcNum fallback mismatch')
                fallback_digest.update(bytes([assembly]) + struct.pack('>I', key) + actual.encode() + b'\0'); fallback_vectors += 1
        for key in (0xffffffff, 0xdeadbeef, 0xfedcba98):
            if f'{key:08X}' in codes: raise AssertionError('Fallback fixture is not actually missing')
            native.write(source_block + 0x23, bytes([assembly]))
            native.call(name, 'GetShowDtcInfo', key, 0xffff0051, output, description, state, context)
            if (native.string(output).decode() != fallback_code(key, assembly)
                    or native.string(description).decode() != fallback_description(key, texts['FFFF0048'])
                    or native.string(state).decode() != texts['FFFF0051']):
                raise AssertionError('Native missing-TCODE display mismatch')
    scopes = {}; scope_digest = hashlib.sha256()
    for region in ('EU', 'NA', 'JP'):
        functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN']); census = Counter()
        for function in functions.rows:
            block = functions.faults(function)
            if block:
                selected, missing = labels.for_block(block, f'FUNCDATA_{region}.BIN')
                scope_digest.update(canonical([region, function, selected, missing]))
                census['faultBlocks'] += 1; census['exactLabels'] += len(selected); census['missingExactLabels'] += len(missing)
        scopes[region] = dict(census)
    # SetDiagCodeData mutates huge native record slots; only the first six
    # header bytes participate here. No buffer/type/status role is invented.
    merge_count = native.allocate(1); merge_rows = native.allocate(2 * 0x39c50)
    merge_digest = hashlib.sha256(); merge_vectors = 0
    for arg0 in range(256):
        for arg1 in range(256):
            port = DiagnosticCodeMerge()
            native.write(merge_count, b'\0'); native.write(merge_rows, bytes(6))
            for key, a, b in ((0x12345678, arg0, arg1), (0x12345678, 4, 8), (0x12345679, 0, 1)):
                expected = port.merge(key, a, b)
                result = native.call(name, 'SetDiagCodeData', a, b, key, merge_count, merge_rows) & 0xffff
                if result != (0 if expected else 0xffff) or native.read(merge_count, 1)[0] != len(port.rows):
                    raise AssertionError('Native exact-key merge result/count mismatch')
                actual = b''.join(native.read(merge_rows + i * 0x39c50, 6) for i in range(len(port.rows)))
                wanted = b''.join(struct.pack('<IBB', r.code, r.byte4, r.byte5) for r in port.rows)
                if actual != wanted: raise AssertionError('Native exact-key merge byte/order mismatch')
                merge_digest.update(bytes([a, b, expected]) + struct.pack('<I', key) + actual); merge_vectors += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'exactLabelVectors': vector_count, 'exactLabelResultsSha256': digest.hexdigest(),
        'fallbackVectors': fallback_vectors, 'fallbackResultsSha256': fallback_digest.hexdigest(),
        'missingLabelDisplayCalls': 21, 'scopedLabels': scopes, 'scopedLabelsSha256': scope_digest.hexdigest(),
        'mergeVectors': merge_vectors, 'mergeResultsSha256': merge_digest.hexdigest(),
        'fakeBoundaries': ['IdSearch over source-decoded English pairs; not native libSTD search/decryption',
            'English language selection and TEXT lookups over source-decoded records', 'libc string/printf shims; not native libc proof'],
        'atomicHardening': 'The merge port rejects a 256th distinct record instead of wrapping the native one-byte count'}


def compare_calc(libraries):
    name = 'libTOYOTA_CALC.so'
    native = NativeSandbox({name: libraries[name]})
    output = native.allocate(16384); code = native.allocate(4096); data = native.allocate(256)
    # Exhaust the complete u8 domain and every high/low combination of the
    # reviewed u16 expression. printf is a declared shim; the numeric result is
    # additionally read directly from native evaluator @1032c before formatting.
    vectors = [
        ('y=x-40;', lambda a, b: float(a - 40), ((a, 0) for a in range(256))),
        ('y=(0x100*x1+x2)/4;', lambda a, b: (256.0 * a + b) / 4.0,
         ((a, b) for a in range(256) for b in range(256))),
    ]
    results = []
    for script, expected, cases in vectors:
        count = 0; digest = hashlib.sha256()
        for a, b in cases:
            native.write(data, bytes([a, b]))
            # Call the numeric consumer directly, without printf or a Python
            # implementation of the supplied arithmetic/parser instructions.
            expression = script[2:-1].encode() + b'\0'
            native.write(code, expression)
            native.call(name, 0x1032c, code, data)
            actual = native.f64()
            if actual != expected(a, b):
                raise AssertionError(f'Native CALC mismatch for {script} at {a},{b}: {actual}')
            digest.update(bytes([a, b]) + struct.pack('<d', actual)); count += 1
        # Also execute the real script/control-flow/formatting caller on edge cases.
        formatting = []
        for a, b in ((0, 0), (0, 255), (127, 128), (255, 255)):
            native.write(data, bytes([a, b])); native.write(code, script.encode() + b'\0')
            result = native.call(name, 'ResultCalcFromScript', output, code, data, 0)
            if result != 0:
                raise AssertionError('Native CALC script returned an error')
            rendered = native.string(output).decode('ascii')
            if float(rendered) != expected(a, b):
                raise AssertionError('Native CALC result was changed by the formatting port')
            formatting.append({'input': bytes([a, b]).hex(), 'output': rendered})
        results.append({'scriptSha256': hashlib.sha256(script.encode()).hexdigest(), 'vectors': count,
                        'binary64ResultsSha256': digest.hexdigest(), 'scriptEdges': formatting})
    return {'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'vectors': results, 'fakeBoundaries': ['malloc/calloc/free within guest heap', 'libc string/printf shims; not native libc formatting proof']}


def run(libraries, sources, *, text_libraries, full_calc=True):
    counts = {}
    for region in ('EU', 'NA', 'JP'):
        functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN'])
        census = Counter()
        for key in functions.rows:
            for method in ('faults', 'clear'):
                block = getattr(functions, method)(key)
                if block is not None:
                    census[method + 'Blocks'] += 1
                    census[method + 'Queries'] += len(block.queries)
                    if method == 'faults': census['faultCodeMappings'] += len(block.mappings)
        counts[region] = dict(census)
    result = {'kind': 'offline-native-differential', 'sourceSha256': SOURCE_SHA256,
        'physicalValidation': False, 'adapterAccess': False, 'networkAccess': False,
        'entryComparison': compare_entries(libraries, sources['SYSTEMENTER.BIN']),
        'kwpTransportComparison': compare_fast_kwp(libraries, sources['SYSTEMENTER.BIN']),
        'frameComparison': compare_frames(libraries, sources, 'EU', 61,
            (1, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 19, 102, 103, 104, 127, 160)),
        'functionRoundTrips': counts,
        'functionReaderComparison': compare_function_readers(libraries, sources),
        'regionalTemplateComparison': compare_regional_templates(libraries, sources),
        'faultDecoderComparison': compare_fault_decoder(libraries),
        'p5DecoderComparison': compare_p5_decoder(libraries, sources),
        'faultLabelComparison': compare_fault_labels(libraries, sources,
            text_libraries['TCODE_EN.GAG'][0], text_libraries['TEXT_EN.GAG'][0])}
    if full_calc: result['calcComparison'] = compare_calc(libraries)
    return result


def load_libraries(path):
    verify_archive(path)
    names = {'libTOYOTA_FILE.so', 'libTOYOTA_COMM.so', 'libTOYOTA_CALC.so', 'libTOYOTA_DTCF.so'}
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        for info in checked_infos(archive):
            name = PurePosixPath(info.filename).name
            if name in names:
                if name in libraries: raise ValueError('Ambiguous source library')
                libraries[name] = archive.read(info)
    if set(libraries) != names:
        raise ValueError('Missing native comparison libraries')
    reference = json.loads(REFERENCE.read_text())
    for name, comparison in (('libTOYOTA_FILE.so', 'entryComparison'), ('libTOYOTA_COMM.so', 'frameComparison'),
            ('libTOYOTA_DTCF.so', 'faultDecoderComparison'), ('libTOYOTA_CALC.so', 'calcComparison')):
        if hashlib.sha256(libraries[name]).hexdigest() != reference[comparison]['librarySha256']:
            raise ValueError('Native comparison library differs from its reviewed identity: ' + name)
    return libraries


def summary(result):
    regions = result['functionReaderComparison']['regions'].values()
    return ('# Toyota offline native comparison\n\n'
        '**PASS — offline source/port semantics only, not completed Toyota support.**\n\n'
        f"Source SHA-256: `{result['sourceSha256']}`.\n\n"
        f"- FILE: {result['entryComparison']['comparedRecords']:,} SYSTEMENTER entries.\n"
        f"- FILE: {sum(r['faultBlocks'] for r in regions):,} regional fault blocks, "
        f"{sum(r['clearBlocks'] for r in regions):,} clear blocks and "
        f"{sum(r['faultCodeMappings'] for r in regions):,} code mappings.\n"
        f"- COMM: {sum(r['standaloneFrames'] for r in result['regionalTemplateComparison']['regions'].values()):,} regional frame records and "
        f"{sum(r['templateLookups'] for r in result['regionalTemplateComparison']['regions'].values()):,} default/declared-fault template lookups; "
        f"{len(result['frameComparison']['templates'])} starting EU/function-61 joins. Not transport proof.\n"
        f"- COMM fast KWP: {result['kwpTransportComparison']['sourceEntries']:,} source entries, "
        f"{result['kwpTransportComparison']['configurationVectors']:,} configuration traces, "
        f"{result['kwpTransportComparison']['sourcePacketVectors']:,} source packet vectors and "
        f"{result['kwpTransportComparison']['wireContractVectors']:,} native/Python/Kotlin short-packet contract vectors. "
        'Every adapter API is a trace-only fake; ST/VT timing/pin mapping and SYSP startup remain unresolved.\n'
        f"- DTCF: {result['faultLabelComparison']['exactLabelVectors']:,} exact TCODE pairs, "
        f"{result['faultLabelComparison']['fallbackVectors']:,} source numeric-display vectors, "
        f"{result['faultLabelComparison']['mergeVectors']:,} exact-key byte-merge calls. "
        'Selected-function labels have no missing exact keys; global printed-code matching is not used.\n'
        f"- DTCF: {result['faultDecoderComparison']['stateVectors']:,} state/mask vectors and "
        f"{result['faultDecoderComparison']['recordVectors']:,} record-assembly vectors.\n"
        f"- DTCF P5: {result['p5DecoderComparison']['recordVectors']:,} core record-projection vectors; "
        f"{result['p5DecoderComparison']['sourceBlocks']:,} source blocks have the compared source shape. "
        'Auxiliary freeze/mask tables and query workflows are not covered.\n'
        f"- CALC: {sum(v['vectors'] for v in result['calcComparison']['vectors']):,} numeric vectors, "
        'two arithmetic forms, eight full-script edge calls. Other expressions are not covered.\n\n'
        'Only supplied AArch64 instructions and explicit memory/libc shims execute. Constructors, host dlopen, '
        'syscalls, JNI, files, sockets and adapter/ECU access are not bridged. Decoded-array file APIs, '
        'no-online-overrides and formatting/memory shims are declared fakes, not native-libc/decryption/transport proof. '
        'The Android decoder rejects malformed partial records atomically even where native code would retain a partial result. '
        'All result/library hashes must equal `tools/thinkdiag/schema/native-oracle-v1.json`. '
        'No vendor code or expressions enter the APK. **Zero executable recovered vehicle definitions; no physical validation.**\n')


def write_checked_report(result, output):
    """Keep failed evidence inspectable, but never publish a PASS before gating.

    JSON existing on disk is not validation: every cache consumer must still
    run verify_reference. This also exposes the exact mismatching gate in CI
    annotations/reports when GitHub's signed log/artifact redirect is unavailable.
    """
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical(result) + b'\n')
    try:
        verify_reference(result)
    except Exception as error:
        output.with_suffix('.md').write_text('# Toyota offline native comparison\n\n'
            '**FAIL — result did not match the reviewed reference.**\n\n'
            + str(error) + '\n\nThe raw comparison report is retained for diagnosis. '
            'No index is staged by this failed gate; no adapter or ECU was contacted.\n')
        raise
    output.with_suffix('.md').write_text(summary(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    execute = commands.add_parser('run', help='Run the full pinned offline comparison')
    execute.add_argument('archive', type=Path)
    execute.add_argument('--decoded', type=Path, required=True)
    execute.add_argument('--manifest', type=Path, required=True)
    execute.add_argument('--output', type=Path, required=True)
    check = commands.add_parser('verify', help='Validate a complete cached report against reviewed identities/result hashes')
    check.add_argument('report', type=Path)
    args = parser.parse_args()
    if args.command == 'verify':
        if args.report.stat().st_size > 256 * 1024: raise ValueError('Unbounded native report')
        result = json.loads(args.report.read_text())
        verify_reference(result)
        print(summary(result))
        return
    # Verify every decoded payload against the complete source manifest before
    # allowing any supplied reader to see it. A pinned ZIP alone is insufficient.
    sources = load_sources(args.archive, args.decoded, args.manifest)
    result = run(load_libraries(args.archive), sources, text_libraries=read_text_libraries(args.archive))
    write_checked_report(result, args.output)
    print(summary(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error).replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print(f'::error title=Toyota offline native comparison::{message}', flush=True)
        raise
