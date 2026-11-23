"""Group-keyed native DTC oracle (Stage 1c). Read-only; writes nothing.

`faultDecoderComparison` and `p5DecoderComparison` in `toyota_native_oracle.py`
(`:609`, `:726`) compare the native DTCF decoder over a fixed layout grid that is
chosen by the comparison, not by any one compiled group, so neither can approve a
specific `(Ecu_Data file, function, selector)` group. This module keeps the same
bounded native sandbox, the same native consumers
(`GetDTCCodeFromAnsP3P4` @0x14ae8, `GetShowDtcInfo` @0x11f90) and the same Python
ports (`toyota_faults.NativeFaultLayout.decode`, `toyota_dtc_labels.FaultLabels`),
but every vector comes from one compiled group's own layout and label set.

Three legs per group:

* ``layout``  — the group's own count index, record start/width, assembly kind,
  presence flag and mode low-bits are packed into the native layout word exactly
  as `compare_fault_decoder` does, and the native DTC projection is compared to
  `NativeFaultLayout.decode` for the group's own query kinds.
* ``linkage`` — for sampled compiled DTC definitions, the label's own native code
  is encoded into a record of the group's own width and replayed; the native
  projection must return that exact code and must equal the Python port.
* ``display`` — for every compiled DTC definition of the group, native
  `GetShowDtcInfo` must render exactly the compiled code/description pair.

A pass means the native decoder and display agree with the compiled group over the
compared vectors. It is NOT vehicle validation: payload vectors are synthesised
(no ECU reply exists here), auxiliary P5 side tables and mask discovery are not
covered, and no transport, applicability or enablement is granted.
"""
import hashlib
import struct

from toyota_dtc_labels import FaultLabels
from toyota_faults import NativeFaultLayout, state_ids

LIBRARY = 'libTOYOTA_DTCF.so'
# GetDTCCodeFromAnsP3P4 is not the kind-5 consumer: compile_p3p4_layout refuses
# kind 5 (`toyota_dtc_labels.py:127-131`), so the oracle never claims it.
DECODABLE_KINDS = (1, 2, 3, 4, 6, 7, 8, 9, 10)
RECORD_KIND_VALUES = (0, 1, 4, 8, 0x10, 0x20, 0x28, 0x29, 0x7f, 0x80, 0xff)
MASK_KIND_VALUES = (0, 1, 2, 4, 8, 15, 85, 255)
DEFAULT_MASK = 0xff
MISMATCH_LIMIT = 5


def layout_from_compiled(compiled):
    """Rebuild the exact native layout the compiled group definition declares."""
    required = ('countIndex', 'recordStart', 'recordBytes', 'assemblyKind',
                'countIsPresence', 'modeLowBits')
    missing = [key for key in required if key not in compiled]
    if missing:
        raise ValueError('compiled layout is missing ' + ','.join(missing))
    return NativeFaultLayout(compiled['countIndex'], compiled['recordStart'], compiled['recordBytes'],
                             compiled['assemblyKind'], compiled['countIsPresence'],
                             compiled['modeLowBits'])


def code_bytes(layout):
    """Record bytes that carry the DTC value, mirroring NativeFaultLayout.decode."""
    if layout.assembly_kind in (5, 6):
        return layout.record_bytes - 1
    if layout.assembly_kind == 7:
        return layout.record_bytes - 3
    return layout.record_bytes


def encode_row(layout, code, status=0xff):
    """Encode one concrete DTC value as a record of the group's own width."""
    width = code_bytes(layout)
    if not 0 <= code < 1 << (8 * width):
        raise ValueError(f'code 0x{code:X} does not fit {width} record bytes')
    head = code.to_bytes(width, 'big')
    if layout.assembly_kind in (5, 6):
        return head + bytes([status & 0xff])
    if layout.assembly_kind == 7:
        return head + b'\x55\xaa\xff'
    return head


def record_row(layout, value):
    """Synthetic record for the layout sweep, exactly shaped as compare_fault_decoder does."""
    head = bytes([value]) + bytes(range(1, code_bytes(layout)))
    if layout.assembly_kind in (5, 6):
        return head + bytes([value])
    if layout.assembly_kind == 7:
        return head + b'\x55\xaa\xff'
    return head


def build_payload(layout, kind, rows, count=None):
    """Place rows and the count/presence byte where the native decoder reads them."""
    start = 0 if (kind == 3 and layout.mode_lowbits != 2) else layout.record_start
    count_at = 255 if (kind == 3 and layout.mode_lowbits != 2) else layout.count_index
    body = b''.join(rows)
    size = start + len(body)
    if count_at != 255 and start <= count_at < start + len(body):
        raise ValueError('count_index falls inside the record body')
    if count_at != 255:
        size = max(size, count_at + 1)
    payload = bytearray(size)
    payload[start:start + len(body)] = body
    if count_at != 255 and start + len(body):
        payload[count_at] = len(rows) if count is None else count
    elif count_at != 255:
        payload[count_at] = 0 if count is None else count
    return bytes(payload)


def layout_cases(layout, kinds):
    """Bounded vector set keyed to this group's layout: kinds x values x shapes x masks."""
    cases = []
    shapes = {'twoRows': lambda row: [row, row], 'oneRow': lambda row: [row],
              'noRows': lambda row: []}
    for kind in kinds:
        for value in RECORD_KIND_VALUES:
            row = record_row(layout, value)
            for name, build in shapes.items():
                if name == 'noRows' and layout.count_index == 255:
                    continue
                cases.append({'kind': kind, 'mask': DEFAULT_MASK,
                              'payload': build_payload(layout, kind, build(row)), 'shape': name,
                              'value': value})
            if layout.count_is_presence:
                cases.append({'kind': kind, 'mask': DEFAULT_MASK,
                              'payload': build_payload(layout, kind, [row], count=0), 'shape': 'presenceZero',
                              'value': value})
        if kind == 10:
            for mask in MASK_KIND_VALUES:
                for value in (0, 0x29, 0xff):
                    cases.append({'kind': kind, 'mask': mask,
                                  'payload': build_payload(layout, kind, [record_row(layout, value), record_row(layout, value)]),
                                  'shape': 'maskSweep', 'value': value})
    return cases


def _native_packed(layout):
    return int.from_bytes(bytes([layout.count_index, layout.record_start,
                                 layout.record_bytes, layout.assembly_kind]), 'little')


def _decoder_buffers(native, layout):
    buffers = {'out': native.allocate(256), 'data': native.allocate(4096),
               'context': native.allocate(0x400), 'sourceBlock': native.allocate(0x100)}
    native.write(buffers['context'] + 0x1b0, struct.pack('<Q', buffers['sourceBlock']))
    native.write(buffers['context'] + 0x18, bytes([layout.mode_lowbits]))
    native.write(buffers['sourceBlock'] + 0x1c, bytes([2 if layout.count_is_presence else 1]))
    return buffers


def _call_decoder(native, buffers, layout, payload, kind, mask):
    native.write(buffers['data'], payload)
    native.write(buffers['out'], bytes(256))
    answer = native.call(LIBRARY, 'GetDTCCodeFromAnsP3P4', len(payload), buffers['data'],
                         _native_packed(layout), 0, buffers['out'], kind, mask, buffers['context'])
    if not answer:
        raise ValueError('native DTC decoder returned allocation/failure null')
    count = struct.unpack('<I', native.read(answer, 4))[0]
    if count > 20:
        raise ValueError('unbounded native DTC result')
    return tuple(struct.unpack('<II', native.read(answer + 4 + index * 8, 8)) for index in range(count))


def compare_layout(native, layout, kinds, *, cases=None):
    """Leg 1: native projection vs the Python port for this group's own layout."""
    cases = layout_cases(layout, kinds) if cases is None else cases
    buffers = _decoder_buffers(native, layout)
    digest = hashlib.sha256()
    mismatches = []
    for case in cases:
        expected = layout.decode(case['payload'], case['kind'], case['mask'])
        actual = _call_decoder(native, buffers, layout, case['payload'], case['kind'], case['mask'])
        digest.update(bytes([case['kind'], case['mask'], len(case['payload'])])
                      + case['payload'] + struct.pack('<I', len(actual))
                      + b''.join(struct.pack('<II', *pair) for pair in actual))
        if actual != expected:
            if len(mismatches) < MISMATCH_LIMIT:
                mismatches.append({'vector': case['shape'], 'kind': case['kind'], 'mask': case['mask'],
                                   'payloadHex': case['payload'].hex().upper(),
                                   'native': [list(pair) for pair in actual],
                                   'port': [list(pair) for pair in expected]})
    return {'vectors': len(cases), 'passed': not mismatches, 'mismatches': mismatches,
            'resultsSha256': digest.hexdigest(), 'kinds': list(kinds)}


def compare_linkage(native, layout, labels, kinds, *, sample=40):
    """Leg 2: each sampled compiled DTC code survives the native record projection."""
    if sample < 1:
        raise ValueError('linkage sample must be positive')
    ordered = sorted(labels, key=lambda label: int(label['nativeCode'], 16))
    step = max(1, len(ordered) // sample) if ordered else 1
    selected = ordered[::step][:sample]
    buffers = _decoder_buffers(native, layout)
    kind = kinds[0]
    digest = hashlib.sha256()
    failures = []
    not_encodable = []
    checked = 0
    for label in selected:
        code = int(label['nativeCode'], 16)
        try:
            row = encode_row(layout, code)
        except ValueError as error:
            # The sample does not fit this group's record width; that is a limit of the
            # encoded sample, not a native disagreement, so it is counted separately and
            # can never turn an empty leg into a pass.
            not_encodable.append({'nativeCode': label['nativeCode'], 'reason': str(error)})
            continue
        payload = build_payload(layout, kind, [row])
        expected = layout.decode(payload, kind, DEFAULT_MASK)
        actual = _call_decoder(native, buffers, layout, payload, kind, DEFAULT_MASK)
        digest.update(bytes([kind]) + payload + b''.join(struct.pack('<II', *pair) for pair in actual))
        codes = {pair[1] for pair in actual}
        checked += 1
        if actual != expected or code not in codes:
            if len(failures) < MISMATCH_LIMIT:
                failures.append({'nativeCode': label['nativeCode'], 'payloadHex': payload.hex().upper(),
                                 'native': [list(pair) for pair in actual],
                                 'port': [list(pair) for pair in expected],
                                 'codePresent': code in codes})
    return {'sampled': len(selected), 'vectors': checked, 'passed': bool(checked) and not failures,
            'failures': failures, 'notEncodable': not_encodable[:MISMATCH_LIMIT],
            'notEncodableCount': len(not_encodable),
            'resultsSha256': digest.hexdigest(), 'kind': kind, 'of': len(ordered)}


def _display_buffers(native):
    buffers = {'sourceBlock': native.allocate(0x100), 'context': native.allocate(0x400),
               'output': native.allocate(4096), 'description': native.allocate(4096),
               'state': native.allocate(4096)}
    native.write(buffers['context'] + 0x1b0, struct.pack('<Q', buffers['sourceBlock']))
    return buffers


def bind_display(native, codes, texts):
    """Bind exactly the DTCF display/split imports that compare_fault_labels binds."""
    from native_replay import ReplayError

    native.bind('OpenLanguageDLL', lambda *_: 0)
    native.bind('OpenSearchIDDLL', lambda *_: 0)

    def language(dst, *_):
        native.write(dst, b'EN\0')
        return 0

    native.bind('GetLangurageCode', language)

    def open_file(path, *_):
        if native.string(path) != b'TCODE_EN.GAG':
            raise ReplayError('Unexpected DTC label file')
        return 1

    def close_file(handle, *_):
        if handle != 1:
            raise ReplayError('Unexpected DTC label handle')
        return 0

    def search(key, dst, size, handle, *_):
        if handle != 1 or size != 512:
            raise ReplayError('Unexpected bounded DTC label lookup')
        identity = native.read(key, 4).hex().upper()
        pair = codes.get(identity)
        if pair is None:
            return 0
        raw = pair.encode('utf-8') + b'\0'
        if len(raw) > size:
            raise ReplayError('Source DTC pair exceeds native buffer')
        native.write(dst, raw)
        return len(raw)

    def text(identity, dst, size, *_):
        raw = texts[f'{identity & 0xffffffff:08X}'].encode('utf-8') + b'\0'
        if len(raw) > size:
            raise ReplayError('Source state text exceeds native buffer')
        native.write(dst, raw)
        return len(raw)

    for name, callback in (('SearchIdOpenFile', open_file), ('SearchIdCloseFile', close_file),
                           ('IdSearch', search), ('GetTextFromTextLib', text)):
        native.bind(name, callback, indirect=True, library=LIBRARY)


def compare_display(native, buffers, codes, texts, labels, *, limit=None):
    """Leg 3: native GetShowDtcInfo renders exactly the group's compiled DTC labels."""
    expected_state = texts['FFFF0050']
    table = FaultLabels(codes)
    digest = hashlib.sha256()
    failures = []
    selected = labels if limit is None else labels[:limit]
    for label in selected:
        key = int(label['nativeCode'], 16)
        native.write(buffers['sourceBlock'] + 0x23, b'\x02')
        native.call(LIBRARY, 'GetShowDtcInfo', key, 0xffff0050, buffers['output'],
                    buffers['description'], buffers['state'], buffers['context'])
        actual = (native.string(buffers['output']).decode('utf-8'),
                  native.string(buffers['description']).decode('utf-8'),
                  native.string(buffers['state']).decode('utf-8'))
        reference = table.labels.get(key)
        digest.update(struct.pack('>I', key) + b'\0'.join(v.encode('utf-8') for v in actual) + b'\0')
        if (actual[0] != label['code'] or actual[1] != label['description']
                or actual[2] != expected_state or reference is None
                or reference.code != label['code'] or reference.description != label['description']):
            if len(failures) < MISMATCH_LIMIT:
                failures.append({'nativeCode': label['nativeCode'], 'native': list(actual),
                                 'compiled': [label['code'], label['description'], expected_state]})
    return {'vectors': len(selected), 'of': len(labels), 'passed': not failures, 'failures': failures,
            'resultsSha256': digest.hexdigest()}


class OracleSession:
    """One bounded native sandbox shared by every group in a run."""

    def __init__(self, libraries, codes, texts):
        self.libraries = libraries
        self.codes = codes
        self.texts = texts
        self.native = None
        self.display = None

    def ensure(self):
        if self.native is None:
            from native_replay import NativeSandbox
            self.native = NativeSandbox({LIBRARY: self.libraries[LIBRARY]})
            self.display = _display_buffers(self.native)
            bind_display(self.native, self.codes, self.texts)
        return self.native, self.display


def oracle_for_group(session, faults, *, linkage_sample=40):
    """Run all three legs for one compiled group's `faults` section; fail closed on any exception.

    `faults` is `compile_p3p4_diagnostics(...)['faults']`: it carries the group's own
    `layout` (with its compiled `labels`), `workflow` query kinds, `read` and optional `clear`.
    """
    result = {'ran': False, 'passed': False, 'legs': {}, 'failure': None,
              'librarySha256': None, 'notCovered': [
                  'synthesised payload vectors, not ECU replies',
                  'P5 auxiliary freeze-frame/mask side tables and mask discovery',
                  'transport, lifecycle and per-system vehicle applicability']}
    try:
        layout = layout_from_compiled(faults['layout'])
        workflow = faults.get('workflow') or {}
        kinds = sorted({query.get('queryKind') for query in workflow.get('queries') or []}
                       | {faults['layout'].get('queryKind')})
        kinds = [kind for kind in kinds if kind in DECODABLE_KINDS]
        excluded = sorted(kind for kind in {faults['layout'].get('queryKind')} - set(kinds) if kind is not None)
        if not kinds:
            raise ValueError('no decodable query kind in the compiled group')
        labels = faults['layout'].get('labels') or []
        native, display_buffers = session.ensure()
        layout_leg = compare_layout(native, layout, kinds)
        linkage_leg = compare_linkage(native, layout, labels, kinds, sample=linkage_sample)
        display_leg = compare_display(native, display_buffers, session.codes, session.texts, labels)
        result.update({'ran': True, 'legs': {'layout': layout_leg, 'linkage': linkage_leg, 'display': display_leg},
                       'librarySha256': native.libraries[LIBRARY].sha256, 'excludedKinds': excluded,
                       'layoutFields': {'countIndex': layout.count_index, 'recordStart': layout.record_start,
                                        'recordBytes': layout.record_bytes, 'assemblyKind': layout.assembly_kind,
                                        'countIsPresence': layout.count_is_presence,
                                        'modeLowBits': layout.mode_lowbits},
                       'recordMask': DEFAULT_MASK,
                       'availabilityMask': faults['layout'].get('availabilityMask')})
        result['passed'] = bool(layout_leg['passed'] and linkage_leg['passed'] and display_leg['passed'])
    except Exception as error:  # fail closed: an oracle that cannot run is not a pass
        result['failure'] = f'{type(error).__name__}:{error}'
    return result
