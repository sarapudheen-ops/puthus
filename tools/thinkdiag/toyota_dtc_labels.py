"""Exact selected-function TCODE joins and the supplied DTCF display rules.

TCODE IDs are complete 32-bit keys. Identical printed P-codes are NOT identical
records: an upper word, a trailing status/failure-type byte or another ECU's
mapping must never be used as a suffix match. The optional unknown-code display
below is a port of GetShowDtcInfo/InitDtcNum, not a global SAE-description lookup.
These data transformations grant neither ECU applicability nor an operation.
"""
from dataclasses import dataclass
import re

from toyota_functions import FaultBlock

TCODE_FILE = 'TOYOTA_EN.GGP/TCODE_EN.GAG'
TEXT_FILE = 'TOYOTA_EN.GGP/TEXT_EN.GAG'


def uint32(value):
    if type(value) is not int or not 0 <= value <= 0xffffffff:
        raise ValueError('Native DTC key must be a complete uint32')
    return value


def fallback_code(code, assembly_kind):
    """GetShowDtcInfo masks to 24 bits before calling InitDtcNum @12788.

    The latter chooses 4/6 nibbles by the source assembly kind, then derives
    P/C/B/U from the top nibble of that width. Do not shift a trailing byte or
    strip the upper word of an existing TCODE key before an exact lookup.
    """
    uint32(code)
    if type(assembly_kind) is not int or assembly_kind not in range(1, 8):
        raise ValueError('Fallback display needs a source assembly kind')
    digits = (4, 4, 4, 6, 4, 6, 4)[assembly_kind - 1]
    value = (code & 0xffffff) & ((1 << (digits * 4)) - 1)
    top = value >> ((digits - 1) * 4)
    return 'PCBU'[top >> 2] + f'{value & ((1 << (digits * 4 - 2)) - 1):0{digits}X}'


def fallback_description(code, source_text):
    uint32(code)
    if not isinstance(source_text, str) or '\0' in source_text or len(source_text) > 4096:
        raise ValueError('Invalid native missing-label source text')
    return '[00, %02x, %02x, %02x]-%s' % ((code >> 16) & 255, (code >> 8) & 255, code & 255, source_text)


@dataclass(frozen=True)
class CodeLabel:
    native_code: int
    code: str
    description: str
    source_records: tuple[str, ...]

    def app(self):
        return {'nativeCode': f'{self.native_code:08X}', 'code': self.code,
                'description': self.description, 'sourceRecords': list(self.source_records)}


class FaultLabels:
    """Complete validated English pairs, scoped to exact FUNCDATA mapping rows.

    Unknown/legacy/plain numeric display strings are retained as supplied. This
    is not the passive reference converter, which excludes ambiguous P-code
    wording. Two records with the same printed code remain distinct here.
    Optional locations come from GagFile.indexed_records(), not guessed offsets.
    """
    def __init__(self, records, locations=None):
        if not isinstance(records, dict) or len(records) > 65535 or any(not isinstance(k, str) for k in records):
            raise ValueError('Invalid TCODE record table')
        self.labels = {}
        locations = {} if locations is None else locations
        if not isinstance(locations, dict) or locations and locations.keys() != records.keys():
            raise ValueError('TCODE provenance does not cover exactly the record table')
        # JSON serialization/order is not source semantics. Canonicalize the
        # global key index; per-function output still follows exact mapping-row
        # order, and indexed provenance retains the original GAG locations.
        for key, pair in sorted(records.items()):
            if not isinstance(key, str) or not re.fullmatch('[0-9A-F]{8}', key) or not isinstance(pair, str):
                raise ValueError('TCODE needs exact uppercase uint32 keys and text')
            fields = pair.split('\0')
            if (len(fields) != 2 or not fields[0] or len(fields[0]) > 64
                    or any(ord(c) < 32 or ord(c) == 127 for c in fields[0]) or len(fields[1]) > 4096):
                raise ValueError('TCODE must contain exactly one bounded literal NUL-delimited code/text pair')
            sources = [f'{TCODE_FILE}#id{key}']
            if locations:
                record = locations[key]
                if not isinstance(record, dict) or any(type(record.get(k)) is not int or record[k] < 0
                        for k in ('indexOffset', 'textOffset', 'encodedBytes')):
                    raise ValueError('Invalid TCODE indexed provenance')
                sources += [f'{TCODE_FILE}@{record["indexOffset"]:08x}#id{key}',
                            f'{TCODE_FILE}@{record["textOffset"]:08x}#encoded{record["encodedBytes"]}']
            self.labels[int(key, 16)] = CodeLabel(int(key, 16), *fields, tuple(sources))

    def for_block(self, block, function_file):
        if not isinstance(block, FaultBlock) or function_file not in ('FUNCDATA_EU.BIN', 'FUNCDATA_NA.BIN', 'FUNCDATA_JP.BIN'):
            raise ValueError('Labels require an exact regional source fault block')
        sources = {}
        for row in block.mappings:
            sources.setdefault(row.code, []).append(f'{function_file}@{row.source_offset:08x}#dtc-map')
        labels, missing = [], []
        for key, provenance in sources.items():
            label = self.labels.get(key)
            if label is None:
                missing.append({'nativeCode': f'{key:08X}', 'sourceRecords': provenance})
            else:
                labels.append(CodeLabel(key, label.code, label.description,
                                        label.source_records + tuple(provenance)).app())
        return labels, missing


def compile_p3p4_layout(block, query, function_file, labels, texts, *, mode_lowbits, availability_mask, context_source_records):
    """Compile an exact source P3/P4 query's app decoder, NOT its workflow/link.

    Mode and availability come from the source caller, never a guessed default.
    PSA/P5/flash blocks have different consumers and are not re-labelled P3/P4.
    Requests/conditions/init must still be compiled separately before use.
    """
    if (not isinstance(block, FaultBlock) or block.byte1 != 1 or query not in block.queries
            or not isinstance(labels, FaultLabels) or not isinstance(texts, dict)
            or type(mode_lowbits) is not int or not 0 <= mode_lowbits <= 7
            or type(availability_mask) is not int or not 0 <= availability_mask <= 255
            or not isinstance(context_source_records, list) or not context_source_records
            or any(not isinstance(s, str) or not s or len(s) > 256 for s in context_source_records)):
        raise ValueError('P3/P4 layout needs exact source block/query/context')
    from toyota_faults import NativeFaultLayout
    layout = NativeFaultLayout(block.count_index, block.record_start, block.record_bytes, block.assembly_kind,
                               block.byte1 == 2, mode_lowbits)
    if not 1 <= query.kind <= 10 or query.kind == 5:
        # SendCmdAndGetDtcP3P4 @140cc branches around the decoder for kind 5.
        # Its reply may serve workflow/support logic; it is not a fault read.
        raise ValueError('Source caller does not decode this query as P3/P4 fault records')
    selected, missing = labels.for_block(block, function_file)
    if missing:
        raise ValueError('Selected fault mappings have missing exact TCODE keys')
    note = texts.get('FFFF0048')
    fallback_description(0, note)  # Strict validation without changing the source wording.
    return {'kind': 'TOYOTA_P3P4', 'countIndex': layout.count_index, 'recordStart': layout.record_start,
        'recordBytes': layout.record_bytes, 'assemblyKind': layout.assembly_kind,
        'countIsPresence': layout.count_is_presence, 'modeLowBits': layout.mode_lowbits,
        'queryKind': query.kind, 'availabilityMask': availability_mask, 'labels': selected,
        'sourceFallback': {'missingDescription': note, 'sourceRecords': [
            f'{TEXT_FILE}#idFFFF0048', 'libTOYOTA_DTCF.so@00011f90#GetShowDtcInfo',
            'libTOYOTA_DTCF.so@00012788#InitDtcNum']},
        'sourceRecords': list(dict.fromkeys([f'{function_file}@{block.source_offset:08x}#fault-header',
            f'{function_file}@{query.source_offset:08x}#fault-query', *context_source_records,
            'libTOYOTA_DTCF.so@00014ae8#GetDTCCodeFromAnsP3P4',
            'libTOYOTA_DTCF.so@00015618#GetDtcStateResult']))}


def compile_p5_layout(block, query, function_file, labels, texts, *, availability_mask, context_source_records):
    """Compile only the compared P5 core projection, not mask/query discovery.

    All 395 source P5 blocks use the compared header below. The selected caller
    must still supply its actual discovered availability mask and provenance.
    This cannot promote source mask flags, history or an imported report into
    current ECU applicability or an executable system by itself.
    """
    if (not isinstance(block, FaultBlock)
            or (block.byte1, block.byte2, block.count_index, block.record_start, block.record_bytes, block.assembly_kind)
                != (3, 8, 255, 2, 4, 6)
            or query not in block.queries or query.kind != 10
            or not isinstance(labels, FaultLabels) or not isinstance(texts, dict)
            or type(availability_mask) is not int or not 0 <= availability_mask <= 255
            or not isinstance(context_source_records, list) or not context_source_records
            or any(not isinstance(s, str) or not s or len(s) > 256 for s in context_source_records)):
        raise ValueError('P5 projection needs the exact compared source header/query/mask context')
    selected, missing = labels.for_block(block, function_file)
    if missing:
        raise ValueError('Selected fault mappings have missing exact TCODE keys')
    note = texts.get('FFFF0048')
    fallback_description(0, note)
    return {'kind': 'TOYOTA_P5', 'recordStart': block.record_start, 'recordBytes': block.record_bytes,
        'queryKind': query.kind, 'availabilityMask': availability_mask, 'labels': selected,
        'sourceFallback': {'missingDescription': note, 'sourceRecords': [
            f'{TEXT_FILE}#idFFFF0048', 'libTOYOTA_DTCF.so@00011f90#GetShowDtcInfo',
            'libTOYOTA_DTCF.so@00012788#InitDtcNum']},
        'sourceRecords': list(dict.fromkeys([f'{function_file}@{block.source_offset:08x}#fault-header',
            f'{function_file}@{query.source_offset:08x}#fault-query', *context_source_records,
            'libTOYOTA_DTCF.so@000151c4#GetDTCCodeFromAnsP5',
            'libTOYOTA_DTCF.so@00015618#GetDtcStateResult']))}


@dataclass(frozen=True)
class MergedCode:
    code: int
    byte4: int
    byte5: int


class DiagnosticCodeMerge:
    """SetDiagCodeData @15760: exact-key, source-order byte ORs.

    The original count is one byte. A 256th distinct record is rejected here
    atomically instead of copying native wraparound. These opaque source bytes
    do not establish UDS statuses, supported writes or current-ECU verification.
    """
    def __init__(self):
        self.rows = []

    def merge(self, code, arg0, arg1):
        uint32(code)
        if any(type(v) is not int or not 0 <= v <= 255 for v in (arg0, arg1)):
            raise ValueError('Merge arguments must be bytes')
        for i, row in enumerate(self.rows):
            if row.code == code:
                self.rows[i] = MergedCode(code, row.byte4 | arg1, row.byte5 | arg0)
                return True
        if arg0 == 0 or arg1 == 0:
            return False
        if len(self.rows) >= 255:
            raise ValueError('Native code merge count would wrap')
        self.rows.append(MergedCode(code, arg1, arg0))
        return True
