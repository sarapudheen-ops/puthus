"""Bounded DTC caller/detail-loading differential experiments, never a driver.

External I/O, support results and final fault sender are explicit fakes. Actual
FDATA table walking and DTCF query/setup/membership instructions execute. The
experiment does NOT claim physical replies, adapter timing, decoding, retry or
permission to enter a source diagnostic session.
"""
import hashlib
import struct

from native_replay import NativeSandbox, ReplayError
from toyota_dtc_workflow import (DTCF_SHA256, compile_dtc_workflow, default_detail_rows,
                                default_add_info_rows, member_enabled, setup_result, support_result)
from toyota_frames import SourceFrame
from vehicle_bundle import canonical

FDATA_SHA256 = '4fb2d50be9ac69ab971f2a183e8672d3fbaaf447c68c2d4580ae417d08ea75e2'


def _sandbox(libraries, name, digest):
    raw = libraries[name]
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ReplayError('DTC workflow experiment needs the exact supplied library hash')
    return NativeSandbox({name: raw})


def compare_setup_predicates(libraries):
    name = 'libTOYOTA_DTCF.so'
    n = _sandbox(libraries, name, DTCF_SHA256)
    context = n.allocate(0x12000)
    data = n.allocate(256)
    # The original GetDTCAskCmdResult takes the slot index, NOT a frame ID.
    # One explicitly populated slot is sufficient for its mask consumer.
    count = 0; digest = hashlib.sha256()
    for mask, expected in ((b'', b''), (b'\xff', b'\x53'),
                           (b'\x00\xff', b'\x53\x01'),
                           (b'\x00\xf0\x0f', b'\x53\x70\x0f')):
        frame = SourceFrame(1, b'\x13\xb0', mask, expected, ('synthetic',))
        n.write(context + 0x10905, bytes([len(mask)]) + mask)
        n.write(context + 0x1086e, bytes([len(expected)]) + expected)
        for rule in range(5):
            for index in (0, 2):
                for value in range(256):
                    payload = bytes([value, value ^ 255, value])
                    n.write(data, payload)
                    actual = n.call(name, 'GetDTCAskCmdResult', data, rule, index, 0, context) & 65535
                    actual = actual - 65536 if actual & 0x8000 else actual
                    wanted = setup_result(payload, rule, index, frame)
                    if actual != wanted:
                        raise AssertionError(f'DTC setup predicate differs: {rule}/{index}/{payload.hex()}/{mask.hex()}')
                    digest.update(bytes([rule, index, value, len(mask)]) + mask + struct.pack('<i', actual))
                    count += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': DTCF_SHA256,
            'vectors': count, 'resultsSha256': digest.hexdigest(),
            'fakeBoundaries': ['one explicitly populated communication frame slot; byte-array replies, no I/O'],
            'hardening': 'Port rejects short predicate buffers and mask windows instead of out-of-bounds native loads'}


def compare_ecmect_query_selection(libraries, sources):
    name = 'libTOYOTA_DTCF.so'
    n = _sandbox(libraries, name, DTCF_SHA256)
    workflow = compile_dtc_workflow(sources, region='EU', function=61)
    block = workflow.block
    token = n.allocate(1024)
    n.write(n.symbol(name, 'g_MToken'), struct.pack('<Q', token))
    context = n.allocate(0x18000)
    native_block = n.allocate(4096)
    n.write(context, struct.pack('<I', 61))
    n.write(native_block + 0x10, bytes([len(block.initial_templates)]))
    n.write(native_block + 0x12, b''.join(struct.pack('<H', t) for t in block.initial_templates))
    n.write(native_block + 0x2c, bytes([len(block.queries)]))
    n.write(native_block + 0x10e, b'\x01')  # No result-list/UI allocation in this selection experiment.
    for i, query in enumerate(block.queries):
        at = native_block + 0x2e + i * 28
        n.write(at, bytes([query.kind, 0]) + struct.pack('<HHB', query.word1, query.template, len(query.conditions)))
        for j, condition in enumerate(query.conditions):
            n.write(at + 8 + j * 2, struct.pack('<H', condition.key))
            n.write(at + 0x12 + j, bytes([condition.byte2]))
            n.write(at + 0x17 + j, bytes([condition.byte3]))
    frames = {t: f for t in workflow.block.initial_templates for f in workflow.initial}
    for query in workflow.queries:
        if len(query.frames) != 1 or any(len(s.frames) != 1 for s in query.setup):
            raise AssertionError('ECMECT query experiment expects the exact single-command bindings')
        frames[query.template] = query.frames[0]
        for setup in query.setup: frames[setup.template] = setup.frames[0]
    events = []; details = set()
    def lookup(template, indexes, function, table, runtime, *_):
        if function != 61 or runtime != context or template not in frames:
            raise ReplayError('DTC caller crossed its exact function/template boundary')
        frame = frames[template]
        n.write(indexes, b'\x00\x00')
        n.write(context + 0x1083b, bytes([len(frame.request)]) + frame.request)
        n.write(context + 0x10905, bytes([len(frame.mask)]) + frame.mask)
        n.write(context + 0x1086e, bytes([len(frame.response)]) + frame.response)
        return 1
    def send(out, capacity, runtime, request, length, flags, extra, *_):
        if runtime != context or capacity != 255:
            raise ReplayError('Unbounded DTC setup sender')
        request = n.read(request, length)
        events.append(['setup', request.hex()])
        # Reply is explicit synthetic data AFTER its service. A type-0 setup
        # predicate does not test the content, but it still needs a full send.
        data = b'\x5f' if request == b'\x10\x5f' else b'\x01\x80'
        n.write(out, data)
        return len(data)
    def search(runtime, key, kind, *_):
        if runtime != context or kind != 1: raise ReplayError('Unexpected detail lookup ABI')
        return int(key in details)
    def sender(template, kind, *_):
        events.append(['query', template, kind]); return 0
    n.bind('GetFuncCommFrameInfo_MultiCmd', lookup, indirect=True, library=name)
    n.bind('SendCmdAndGetReceive', send, indirect=True, library=name)
    n.bind('SearchFuncDetailID', search, indirect=True, library=name)
    n.bind('SendCmdAndGetDtcP3P4', sender, library=name)
    digest = hashlib.sha256(); vectors = 0
    # All membership combinations relevant to this source block; neither the
    # setup-template 132 nor its absence is used as a membership flag.
    keys = (1, 2, 3, 43, 57)
    for bits in range(1 << len(keys)):
        details = {key for i, key in enumerate(keys) if bits & (1 << i)}
        events.clear()
        actual = n.call(name, 'ReadDtcModeP3P4', 0, native_block, context) & 65535
        wanted = [['setup', f.request.hex()] for f in workflow.initial]
        selected = workflow.selected(details, native_mode=0)
        for query in selected:
            wanted.extend(['setup', f.request.hex()] for s in query.setup for f in s.frames)
            wanted.append(['query', query.template, query.kind])
        if events != wanted or actual != (0 if selected else 65535):
            raise AssertionError(f'ECMECT DTC membership/setup flow differs for {sorted(details)}: {events} != {wanted}')
        digest.update(canonical({'details': sorted(details), 'events': list(events), 'return': actual}))
        vectors += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': DTCF_SHA256,
        'systemRecord': 'SYSLIST_EU.BIN@0009d8f2', 'functionKey': 61, 'vectors': vectors,
        'resultsSha256': digest.hexdigest(), 'workflow': workflow.report(),
        'fakeBoundaries': ['explicit current function-detail memberships',
            'exact source frame lookup projected into a native slot; independent COMM comparison covers that lookup',
            'synthetic setup replies, final fault sender returns success without I/O/decoding',
            'result-list/UI allocation bypassed by native block+10e=1; no applicability inferred from that setting'],
        'notCovered': ['physical/adapter behavior', 'retry/re-entry', 'current ECU support results', 'operation permissions']}


def compare_ecmect_detail_loading(libraries, sources):
    name = 'libTOYOTA_FDATA.so'
    n = _sandbox(libraries, name, FDATA_SHA256)
    rows = default_detail_rows(sources, 'EU', 61, 1)
    if any(row['commandCondition'] for row in rows):
        raise AssertionError('Unexpected ECMECT CmdCheckSupport condition')
    tables = n.allocate(256); context = n.allocate(0x18000); out = n.allocate(4)
    for offset, file in ((0x70, 'EU/Toyota_bin_1B.BIN'), (0x78, 'EU/Toyota_bin_1C.BIN')):
        raw = sources[file]; pointer = n.allocate(len(raw)); n.write(pointer, raw)
        n.write(tables + offset, struct.pack('<Q', pointer))
    additions = {row['detailId']: default_add_info_rows(sources, 'EU', 61, row['detailId']) for row in rows}
    if any(a['commandCondition'] for items in additions.values() for a in items):
        raise AssertionError('Unexpected ECMECT add-info CmdCheckSupport condition')
    enabled = set(); calls = []
    def support(function, mode, frame, result_byte, supported, expected, minimum, runtime):
        if function != 61 or runtime != context:
            raise ReplayError('Function-detail support crossed context')
        calls.append([mode, frame, expected, minimum])
        n.write(supported, struct.pack('<I', int(mode == 0 or frame == 0 or frame in enabled)))
        n.write(result_byte, bytes([int(frame in enabled)]))
        return 0
    n.bind('GetSupportBit', support, library=name)
    all_rows = rows + [a for items in additions.values() for a in items]
    varying = sorted({row['frameId'] for row in all_rows if row['supportMode'] and row['frameId']})
    digest = hashlib.sha256(); vectors = 0
    for bits in range(1 << len(varying)):
        enabled = {frame for i, frame in enumerate(varying) if bits & (1 << i)}
        calls.clear(); n.write(context + 0x1059e, b'\x00'); n.write(context + 0x10604, b'\x00')
        result = n.call(name, 'EcuFuncListDetailsTable', 61, 1, out, tables, context)
        count = n.read(context + 0x1059e, 1)[0]
        actual = list(struct.unpack('<' + 'H' * count, n.read(context + 0x105a0, count * 2)))
        wanted = [r['detailId'] for r in rows if not r['supportMode'] or not r['frameId'] or r['frameId'] in enabled]
        wanted_calls = []; wanted_additions = []
        for row in rows:
            wanted_calls.append([row['supportMode'], row['frameId'], row['expectedByte'], row['minimumLength']])
            if row['detailId'] not in wanted: continue
            for extra in additions[row['detailId']]:
                if extra['supportMode'] and extra['frameId']:
                    wanted_calls.append([extra['supportMode'], extra['frameId'], 0, 0])
                    value = int(extra['frameId'] in enabled)
                else: value = extra['literalValue']
                wanted_additions.append([1, row['detailId'], extra['id'], value])
        added = n.read(context + 0x10604, 1)[0]
        actual_additions = [list(struct.unpack('<4H', n.read(context + 0x10606 + i * 8, 8))) for i in range(added)]
        if result != 0 or actual != wanted or calls != wanted_calls or actual_additions != wanted_additions:
            raise AssertionError(f'ECMECT FDATA detail/add-info loading differs for {sorted(enabled)}')
        if 57 not in actual or not member_enabled(0x3903, set(actual)):
            raise AssertionError('Source pending query was incorrectly suppressed by setup-template 132')
        digest.update(canonical({'supportedFrames': sorted(enabled), 'details': actual,
            'supportCalls': list(calls), 'additionalInfo': actual_additions}))
        vectors += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': FDATA_SHA256,
        'functionKey': 61, 'mainCategory': 1, 'vectors': vectors, 'resultsSha256': digest.hexdigest(),
        'detailRows': rows,
        'fakeBoundaries': ['GetSupportBit returns every combination of explicit synthetic support outcomes; no ECU access'],
        'notCovered': ['support-query replies or live mask evaluation', 'other main categories', 'transport', 'physical validation']}


def compare_support_predicates(libraries):
    """Execute the real FDATA mask/min-length consumer with explicit cached replies.

    GetCommFrameInfo and GetSendCmdSearchIndex are declared source-slot/cache
    boundaries, not I/O. The independent COMM oracle checks the frame lookup.
    Native unchecked short-buffer loads are NOT approved by padding a fixture.
    """
    name = 'libTOYOTA_FDATA.so'; n = _sandbox(libraries, name, FDATA_SHA256)
    token = n.allocate(0x280e200); context = n.allocate(0x18000)
    supported = n.allocate(4); masked = n.allocate(1)
    n.write(n.symbol(name, 'g_MToken'), struct.pack('<Q', token))
    n.bind('GetCommFrameInfo', lambda *_: 0, indirect=True, library=name)
    def cache_index(function, length, request, out, *_):
        if function != 61 or n.read(request, length) != b'\x01\x01':
            raise ReplayError('Support predicate crossed its declared source frame')
        n.write(out, b'\0' * 4); return 0
    n.bind('GetSendCmdSearchIndex', cache_index, library=name)
    n.write(context + 0x1083b, b'\x02\x01\x01')
    n.write(token + 0x1fc, b'\x01')
    digest = hashlib.sha256(); vectors = 0
    masks = (b'', b'\xff', b'\x00\xff', b'\x00\xf0\x0f', b'\x00\x00\x00', b'\x00\x01\x80')
    for mask in masks:
        n.write(context + 0x10905, bytes([len(mask)]) + mask)
        for minimum in (0, 2, 5):
            for mode in (1, 2):
                for value in range(256):
                    payload = bytes([value, value ^ 255])
                    for expected in (0, 1, 0x80):
                        n.write(token + 0x232, bytes([len(payload)]) + payload)
                        n.write(masked, b'\xaa')
                        n.call(name, 'GetSupportBit', 61, mode, 1, masked, supported, expected, minimum, context)
                        wanted = support_result(payload, mode=mode, frame_id=1, mask=mask, expected_byte=expected, minimum_length=minimum)
                        actual = (int.from_bytes(n.read(supported, 4), 'little'), n.read(masked, 1)[0])
                        result = (int(wanted.supported), 0xaa if wanted.masked_byte is None else wanted.masked_byte)
                        if actual != result: raise AssertionError('FDATA ANY-mask/minimum-length support semantics differ')
                        digest.update(bytes([len(mask), minimum, mode, value, expected, *actual]) + mask); vectors += 1
    for mode, frame in ((0, 1), (1, 0), (0, 0)):
        n.write(supported, b'\0' * 4)
        n.call(name, 'GetSupportBit', 61, mode, frame, masked, supported, 0, 255, context)
        if n.read(supported, 4) != b'\x01\0\0\0': raise AssertionError('Unconditional native support row changed')
    return {'nativeInstructionsExecuted': True, 'librarySha256': FDATA_SHA256,
        'vectors': vectors, 'resultsSha256': digest.hexdigest(), 'unconditionalVectors': 3,
        'fakeBoundaries': ['GetCommFrameInfo returns one explicitly populated slot; independent COMM oracle covers lookup',
            'GetSendCmdSearchIndex selects an explicit successful immutable cached post-service reply; no sender/device access'],
        'hardening': 'Port rejects short mask windows instead of native out-of-bounds byte loads',
        'notCovered': ['native support-cache invalidation', 'physical ECU support', 'adapter or transport behavior']}


def compare_dtc_read_reentry(libraries, sources):
    """Execute DTCF's exact failed-read re-entry caller, not an adapter driver.

    The actual source kind-5/template-165 query deliberately bypasses the fault
    decoder. This isolates the sender/re-entry/sentinel control flow. Native
    Exit/Enter are explicit observed boundaries; their return values are NOT
    claimed as initialization proof, and no 105F or write is sent by this test.
    """
    from toyota_entry_runtime import reentry_is_selected, signed16
    name = 'libTOYOTA_DTCF.so'; n = _sandbox(libraries, name, DTCF_SHA256)
    plan = compile_dtc_workflow(sources, region='EU', function=61)
    queries = [q for q in plan.queries if q.kind == 5 and q.template == 165]
    if len(queries) != 1 or len(queries[0].frames) != 1:
        raise ReplayError('Read re-entry oracle needs the exact source single-frame non-decoded query')
    frame = queries[0].frames[0]
    token = n.allocate(4096); context = n.allocate(0x18000); native_block = n.allocate(4096)
    count = n.allocate(4); codes = n.allocate(8)
    n.write(n.symbol(name, 'g_MToken'), struct.pack('<Q', token))
    n.write(context, struct.pack('<I', 61)); n.write(context + 0x10829, b'\x81')
    events = []; returns = []; attempts = 0; lifecycle_status = 0
    def lookup(template, indexes, function, table, runtime, *_):
        if (template, function, runtime) != (165, 61, context): raise ReplayError('Read re-entry crossed source scope')
        n.write(indexes, b'\0\0')
        n.write(context + 0x1083b, bytes([len(frame.request)]) + frame.request)
        return 1
    def send(out, capacity, runtime, request, size, flags, extra, *_):
        nonlocal attempts
        raw = n.read(request, size)
        if runtime != context or capacity != 4096 or flags != 0x81 or raw != frame.request or attempts >= len(returns):
            raise ReplayError('Read re-entry changed the exact sender/capacity/request/reply budget')
        events.append(['read', raw.hex(), n.read(context + 0x174d1, 1)[0]])
        value = returns[attempts]; attempts += 1
        # Kind 5 never reads the response buffer. Do not fabricate/pad a buffer
        # whose size is implied by an arbitrary synthetic return-word test.
        return value
    def exit_(function, runtime, *_):
        if (function, runtime) != (61, context): raise ReplayError('Read re-entry exit crossed ECU')
        events.append(['exit', n.read(context + 0x174d1, 1)[0]])
        return lifecycle_status
    def enter(function, runtime, *_):
        if (function, runtime) != (61, context): raise ReplayError('Read re-entry enter crossed ECU')
        events.append(['enter', n.read(context + 0x174d1, 1)[0]])
        return lifecycle_status
    for symbol, callback in (('GetFuncCommFrameInfo_MultiCmd', lookup), ('SendCmdAndGetReceive', send),
                             ('SystemExitPart', exit_), ('SystemEnterPart', enter)):
        n.bind(symbol, callback, indirect=True, library=name)
    digest = hashlib.sha256(); vectors = 0; reentries = 0
    statuses = (-32768, -2, -1, 0, 1, 9, 0x7ffe, 0x7fff, 0x8000, 0xffff, 0x10000)
    for transport in range(12):
        n.write(context + 0x10828, bytes([transport]))
        for original in (0, 1, 11, 255):
            for first in statuses:
                eligible = reentry_is_selected(transport, first)
                for second in (-1, 0, 3, 0x7fff) if eligible else (0,):
                    for lifecycle_status in (0, -1) if eligible else (0,):
                        events.clear(); attempts = 0; returns = [first] + ([second] if eligible else [])
                        n.write(context + 0x174d1, bytes([original]))
                        value = n.call(name, 'SendCmdAndGetDtcP3P4', 165, 5, count, codes, 0, native_block, context) & 65535
                        wanted = [['read', frame.request.hex(), original]]
                        if eligible: wanted += [['exit', 255], ['enter', 255], ['read', frame.request.hex(), original]]
                        final = second if eligible else first
                        wanted_status = 65535 if signed16(final) < 0 or final & 65535 == 0x7fff else 0
                        if events != wanted or value != wanted_status or attempts != len(returns) or n.read(context + 0x174d1, 1) != bytes([original]):
                            raise AssertionError(f'Native read re-entry differs at {transport}/{original}/{first}/{second}/{lifecycle_status}: {events}/{value}')
                        digest.update(canonical({'transport': transport, 'savedContextByte': original, 'first': first,
                            'second': second if eligible else None, 'lifecycleReturn': lifecycle_status,
                            'events': events, 'nativeResult': value}))
                        reentries += int(eligible); vectors += 1
    return {'nativeInstructionsExecuted': True, 'librarySha256': DTCF_SHA256,
        'sourceSystem': 'SYSLIST_EU.BIN@0009d8f2', 'functionKey': 61, 'template': 165, 'request': frame.request.hex(),
        'vectors': vectors, 'reentries': reentries, 'resultsSha256': digest.hexdigest(),
        'fakeBoundaries': ['Exact source frame copied into one declared native slot; independent COMM oracle covers lookup',
            'Synthetic sender results only; kind 5 skips the fault decoder exactly as in the source caller',
            'Exit/Enter callbacks inspect temporary FF context and return scripted 0 or -1; no physical init or session control'],
        'hardening': 'App must reject malformed/ambiguous replies and require successful exact reinitialization; native lifecycle return values are ignored'}
