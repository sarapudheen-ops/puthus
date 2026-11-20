"""Exact ECUPROGRAM regional/full-system-key dispatch and kind-14 identity.

FILE GetSupEcuFromEcuProgList @19044 populates ECUF's program record +10
from the NINE-byte ECUPROGRAM row +8, not from SYSTEMENTER's selector.
ECUF GetEcuCalitrationID @10ad4 dispatches kind 14 to @133e0, which uses
function template 136 (0904 on function 61) even on a K-line source entry.
The '_CAN' export name does not override the selected source transport.

No device, session, permission, unique-serial claim, regional or ECU fallback.
The native reader rounds up and copies a partial last 16-byte ID; the port
rejects that incomplete record atomically instead of reading past the reply.
"""
from dataclasses import dataclass
import struct

from toyota_frames import SourceFrames
from toyota_structure import uint

FILE_SHA256 = 'dae1765c3e870f56f8195c9e7690088f45669c90dc20c6399f504d93d5b5c7c0'
ECUF_SHA256 = '7aac28dfd7762a22a109c270aa70abbe51cb7456c8e2ca0572f95bf7a656ce49'
# Verified independently by the oracle loader against the supplied archive.
FILE_CONSUMER = 'libTOYOTA_FILE.so@00019044#GetSupEcuFromEcuProgList'
ECUF_DISPATCH = 'libTOYOTA_ECUF.so@00010ad4#GetEcuCalitrationID'
ECUF_READER = 'libTOYOTA_ECUF.so@000133e0#GetEcuCalitrationID_CAN'
REGIONS = {1: 'EU', 2: 'NA', 3: 'JP'}


def exact(value, maximum, field):
    if type(value) is not int or not 0 <= value <= maximum:
        raise ValueError(field + ': exact unsigned source integer required')
    return value


@dataclass(frozen=True)
class EcuProgram:
    region: int
    function: int
    program: int
    dispatch: int
    offset: int
    region_row: int

    @property
    def source_record(self): return f'ECUPROGRAM.BIN@{self.offset:08x}'

    def report(self):
        return {'region': REGIONS[self.region], 'functionKey': self.function, 'program': self.program,
                'dispatch': self.dispatch, 'sourceRecord': self.source_record,
                'sourceRecords': [self.source_record, FILE_CONSUMER, ECUF_DISPATCH]}


class EcuProgramIndex:
    def __init__(self, raw):
        if not isinstance(raw, bytes) or not raw or len(raw) > 16 * 1024 * 1024:
            raise ValueError('Missing, oversized or non-byte ECUPROGRAM input')
        count = uint(raw, 0, 1)
        if not 1 <= count <= 3 or 1 + count * 5 > len(raw):
            raise ValueError('Invalid regional ECUPROGRAM directory')
        regions = {}; all_rows = {}; spans = [(0, 1 + count * 5)]
        for i in range(count):
            region, start = struct.unpack_from('<BI', raw, 1 + i * 5)
            if region not in REGIONS or region in regions:
                raise ValueError('Unknown or duplicate ECUPROGRAM source region')
            total = uint(raw, start, 2)
            stop = start + 2 + 9 * total
            if stop > len(raw): raise ValueError('Truncated nine-byte ECUPROGRAM rows')
            spans.append((start, stop)); rows = {}; ordered = []
            for j in range(total):
                at = start + 2 + 9 * j
                function, program, dispatch = struct.unpack_from('<IIB', raw, at)
                item = EcuProgram(region, function, program, dispatch, at, j)
                ordered.append(item)
                # FILE stops at the FIRST exact full-key match. The source really
                # has five function-233 rows. Retain them for accounting; do not
                # merge their programs or select a more convenient later dispatch.
                rows.setdefault(function, item)
            regions[region] = rows; all_rows[region] = tuple(ordered)
        cursor = 0; self.unclaimed = []
        for start, stop in sorted(spans):
            if start < cursor: raise ValueError('Overlapping ECUPROGRAM blocks/directory')
            if start > cursor: self.unclaimed.append((cursor, raw[cursor:start]))
            cursor = stop
        if cursor < len(raw): self.unclaimed.append((cursor, raw[cursor:]))
        # The supplied file has explicit FFFFFFFF separators. Different unknown
        # content needs its own reader, not silent truncation or a guessed table.
        if any(any(v != 255 for v in data) for _, data in self.unclaimed):
            raise ValueError('Untranslated ECUPROGRAM bytes outside framed source blocks')
        self.regions = regions
        self.rows_by_region = all_rows

    def select(self, region, function):
        exact(region, 255, 'region'); exact(function, 0xffffffff, 'function')
        if region not in REGIONS: raise ValueError('Exact ECUPROGRAM source region required')
        return self.regions.get(region, {}).get(function)


def calibration_ids_14(payload):
    """Whole positive-service reply, exact 4904/one count byte/1..15 complete IDs.

    As in native ECUF, the count byte is retained as a source header rather than
    trusted for an unchecked buffer copy. Actual bounded length determines rows.
    No partial row, non-ASCII control byte or records beyond the native 255-byte post-service capacity accepted.
    """
    if (not isinstance(payload, bytes) or not payload.startswith(b'\x49\x04') or
            not 19 <= len(payload) <= 243 or (len(payload) - 3) % 16):
        raise ValueError('Incomplete or mismatched kind-14 calibration-ID reply')
    rows = []
    for at in range(3, len(payload), 16):
        row = payload[at:at + 16]
        # Fixed-width native text slots are NUL padded. Reject embedded NUL then
        # non-padding bytes rather than hiding a malformed second value.
        text, _, padding = row.partition(b'\0')
        if any(padding) or any(v not in range(32, 127) for v in text) or not text.strip():
            raise ValueError('Invalid fixed-width ASCII calibration-ID text')
        rows.append(text.decode('ascii').rstrip())
    return tuple(rows)


def compile_calibration_14(sources, *, region, function):
    if region not in ('EU', 'NA', 'JP'): raise ValueError('Exact identity source region required')
    exact(function, 0xffffffff, 'function')
    program = EcuProgramIndex(sources['ECUPROGRAM.BIN']).select({v: k for k, v in REGIONS.items()}[region], function)
    if program is None or program.dispatch != 14:
        raise ValueError('Selected source system does not use ECUF dispatch kind 14')
    frames = SourceFrames(sources, region).function(function & 65535, 136)
    if len(frames) != 1 or (frames[0].request, frames[0].mask, frames[0].response) != (b'\x09\x04', b'\xff\xff', b'\x49\x04'):
        raise ValueError('Untranslated exact kind-14 identity frame binding')
    frame = frames[0]
    return {'read': {'id': program.source_record + '#calibration', 'name': 'Calibration ID',
        'request': frame.request.hex().upper(), 'responsePrefix': frame.response.hex().upper(),
        'dataOffset': 1, 'minBytes': 16, 'maxBytes': 240,
        'sourceRecords': [program.source_record, FILE_CONSUMER, ECUF_DISPATCH, ECUF_READER, *frame.source_records]},
        'encoding': 'ASCII_RECORDS', 'recordBytes': 16}
