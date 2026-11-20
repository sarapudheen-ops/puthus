"""Source-defined P3/P4 DTC *control flow*, separate from transport or permission.

DTCF ReadDtcModeP3P4 @12fc4 uses the TWO BYTES of FaultQuery.word1 as
OR-ed SearchFuncDetailID keys. FaultCondition.key is instead a SETUP TEMPLATE
passed to SendCmdInitialDtc @13c34. Conflating the two loses pending queries.

This compiler retains global setup (including non-read session control), query
setup alternatives, non-decoded kind-5 commands and native re-entry/retry policy.
It never invents a link, sends a command, omits a non-read setup step, or promotes
this control-flow projection to an enabled vehicle-system.
"""
from dataclasses import dataclass

from toyota_frames import SourceFrame, SourceFrames
from toyota_functions import FaultBlock, FunctionBlocks
from toyota_structure import typed_table, uint

DTCF_SHA256 = '686dc71e111d2a82205df75ddfa9d2a873ae4d7eea5de7aef81abcde295c39b7'
# The pinned library hash is checked by the archive/native-oracle driver; these
# offsets identify consumers, not instructions that the phone will execute.
READ_CONSUMER = 'libTOYOTA_DTCF.so@00012fc4#ReadDtcModeP3P4'
SETUP_CONSUMER = 'libTOYOTA_DTCF.so@00013c34#SendCmdInitialDtc'
ASK_CONSUMER = 'libTOYOTA_DTCF.so@0001494c#GetDTCAskCmdResult'
SEND_CONSUMER = 'libTOYOTA_DTCF.so@00013e4c#SendCmdAndGetDtcP3P4'


def _word(value):
    if type(value) is not int or not 0 <= value <= 65535:
        raise ValueError('Exact source membership word required')
    return value


def membership_ids(word):
    """Zero is NOT unconditional; FF in either byte is. Native order is low first."""
    _word(word)
    return tuple(value for value in (word & 255, word >> 8) if value)


def member_enabled(word, details):
    keys = membership_ids(word)
    if not isinstance(details, (set, frozenset)) or any(type(v) is not int or not 0 <= v <= 65535 for v in details):
        raise ValueError('Current ECU function-detail membership set required')
    return any(key == 255 or key in details for key in keys)


def _frame_report(frame):
    return {'frameId': frame.frame_id, 'request': frame.request.hex().upper(),
            'mask': frame.mask.hex().upper(), 'response': frame.response.hex().upper(),
            'sourceRecords': list(frame.source_records)}


def setup_result(payload, rule, index, frame):
    """GetDTCAskCmdResult; payload begins AFTER the positive service byte.

    Return 0..255/-1 as the signed native result. Short/unbounded data is rejected,
    unlike the unchecked original memory accesses. A result of -1 lets the
    caller try the next source alternative; zero does not enable the query.
    """
    if (not isinstance(payload, bytes) or len(payload) > 4096 or type(rule) is not int or rule not in range(5)
            or type(index) is not int or not 0 <= index <= 255 or not isinstance(frame, SourceFrame)):
        raise ValueError('Unsupported or unbounded source DTC setup predicate')
    if rule == 0:
        return 1
    if rule in (1, 2, 3):
        if index >= len(payload):
            raise ValueError('Short DTC setup predicate reply')
        value = payload[index]
        if rule == 1:
            return value
        return (1 if rule == 2 else -1) if value not in (0, 255) else 0
    if len(frame.mask) <= 1:
        return 1
    if len(frame.response) < len(frame.mask) or len(payload) < len(frame.mask) - 1:
        raise ValueError('Unbounded source DTC mask/reply predicate')
    return 1 if all((payload[i - 1] & frame.mask[i]) == frame.response[i]
                    for i in range(1, len(frame.mask))) else -1


@dataclass(frozen=True)
class SupportDecision:
    supported: bool
    masked_byte: int | None = None


def support_result(payload, *, mode, frame_id, mask, expected_byte, minimum_length, succeeded=True):
    """FDATA GetSupportBit @1532c; reply origin is AFTER its positive service.

    A nonzero minimum overrides mask comparisons and counts the removed service
    in the length. Otherwise ANY nonzero mask byte after service may match the
    ONE expected byte. This is deliberately not the DTC setup predicate's ALL
    mask/response comparison. Unconditional rows do not perform an ECU query.
    """
    _word(mode); _word(frame_id)
    if (not isinstance(mask, bytes) or len(mask) > 255 or type(expected_byte) is not int or expected_byte not in range(256)
            or type(minimum_length) is not int or minimum_length not in range(256) or type(succeeded) is not bool):
        raise ValueError('Invalid exact source support predicate')
    if not mode or not frame_id: return SupportDecision(True)
    if not succeeded: return SupportDecision(False)
    if not isinstance(payload, bytes) or len(payload) > 255:
        raise ValueError('Missing or unbounded post-service support reply')
    if len(mask) <= 1: return SupportDecision(True)
    if minimum_length: return SupportDecision(len(payload) + 1 >= minimum_length)
    if len(payload) < len(mask) - 1:
        raise ValueError('Short native support mask window; no out-of-bounds load accepted')
    matched = False; last = None
    for value, selection in zip(payload, mask[1:]):
        if selection:
            last = value & selection
            matched |= last == expected_byte
    return SupportDecision(matched, last)


@dataclass(frozen=True)
class DtcSetup:
    source_record: str
    template: int
    rule: int
    index: int
    frames: tuple[SourceFrame, ...]

    def report(self):
        return {'sourceRecords': [self.source_record, SETUP_CONSUMER, ASK_CONSUMER],
                'template': self.template, 'rule': self.rule, 'index': self.index,
                'frames': [_frame_report(frame) for frame in self.frames]}


@dataclass(frozen=True)
class DtcQueryPlan:
    source_record: str
    kind: int
    membership_word: int
    template: int
    setup: tuple[DtcSetup, ...]
    frames: tuple[SourceFrame, ...]

    def report(self):
        return {'sourceRecords': [self.source_record, READ_CONSUMER, SEND_CONSUMER],
                'queryKind': self.kind, 'detailAny': list(membership_ids(self.membership_word)),
                'membershipWord': self.membership_word, 'template': self.template,
                'setupAlternatives': [s.report() for s in self.setup],
                'decodeFaultRecords': self.kind != 5,
                'frames': [_frame_report(frame) for frame in self.frames]}


@dataclass(frozen=True)
class DtcWorkflow:
    function_file: str
    function: int
    block: FaultBlock
    initial: tuple[SourceFrame, ...]
    queries: tuple[DtcQueryPlan, ...]

    def selected(self, details, *, native_mode):
        if type(native_mode) is not int or not 0 <= native_mode <= 255:
            raise ValueError('Explicit native runtime+19 mode required')
        if native_mode in (7, 9):
            raise ValueError('Native mode 7/9 has additional caller overrides, not this projection')
        return tuple(query for query in self.queries if member_enabled(query.membership_word, details))

    def report(self):
        return {'kind': 'source-dtc-workflow', 'region': self.function_file[9:11], 'functionKey': self.function,
                'sourceRecords': [f'{self.function_file}@{self.block.source_offset:08x}#fault-header', READ_CONSUMER],
                'initialTemplates': list(self.block.initial_templates),
                'initial': [_frame_report(frame) for frame in self.initial],
                'queries': [query.report() for query in self.queries],
                'nativeReadFailurePolicy': {'reenterOnceOnTransports': [1, 2, 5],
                    'sequence': ['SystemExitPart', 'SystemEnterPart', 'retrySameRead'],
                    'sourceRecords': [SEND_CONSUMER]},
                'requiresNonReadSetup': any(frame.request[:1] not in (b'\x01', b'\x07', b'\x0a', b'\x12',
                    b'\x13', b'\x18', b'\x19', b'\x1a', b'\x21', b'\x22') for frame in self.initial)}


def compile_dtc_workflow(sources, *, region, function):
    if region not in ('EU', 'NA', 'JP') or type(function) is not int or not 0 <= function <= 0xffffffff:
        raise ValueError('Exact regional function required')
    name = f'FUNCDATA_{region}.BIN'
    block = FunctionBlocks(sources[name]).faults(function)
    if block is None or block.byte1 not in (1, 2):
        raise ValueError('Not a source P3/P4 fault block')
    if len(block.initial_templates) > 1:
        # ReadDtcModeP3P4 loads only its first template. Do not silently
        # reinterpret a different source caller's longer setup list.
        raise ValueError('P3/P4 initial template list needs its exact caller')
    frames = SourceFrames(sources, region)
    def bound(template):
        values = tuple(frames.function(function & 65535, template))
        if not values:
            raise ValueError(f'No exact default DTC template binding {template}')
        if any(not f.request for f in values):
            raise ValueError('Empty source DTC request')
        return values
    initial = bound(block.initial_templates[0])[:1] if block.initial_templates else ()
    queries = []
    for query in block.queries:
        if query.kind not in range(1, 11) or len(query.conditions) > 5:
            raise ValueError('Unsupported source DTC query/setup count')
        setup = []
        for condition in query.conditions:
            if condition.byte2 not in range(5):
                raise ValueError('Unsupported source DTC setup predicate')
            setup.append(DtcSetup(f'{name}@{condition.source_offset:08x}#dtc-setup', condition.key,
                condition.byte2, condition.byte3, bound(condition.key)))
        queries.append(DtcQueryPlan(f'{name}@{query.source_offset:08x}#fault-query', query.kind,
            query.word1, query.template, tuple(setup), bound(query.template)))
    return DtcWorkflow(name, function, block, initial, tuple(queries))


def default_detail_rows(sources, region, function, main):
    """Closed main-category detail table, before live support checks are evaluated.

    EcuFuncListDetailsTable scans only the prefix whose +16 word is zero.
    Conditions stay explicit. Neither a label nor absence of a detail matching
    a setup-template number establishes current ECU support.
    """
    if region not in ('EU', 'NA', 'JP'):
        raise ValueError('Exact source region required')
    _word(function); _word(main)
    name = f'{region}/Toyota_bin_1B.BIN'
    table = typed_table(sources[name], 0x11b if region == 'EU' else 0x1b)
    if table.count and table.stride != 24:
        raise ValueError('Unsupported function-detail table stride')
    result = []
    for i in range(table.count):
        row = table.body[i * 24:(i + 1) * 24]
        if uint(row, 16, 2):
            break
        if (uint(row, 4, 2), uint(row, 6, 2)) != (function, main):
            continue
        result.append({'detailId': uint(row, 8, 2), 'frameId': uint(row, 10, 2),
            'commandCondition': uint(row, 12, 2), 'supportMode': uint(row, 14, 2),
            'expectedByte': row[20], 'minimumLength': row[21],
            'sourceRecords': [f'{name}@{10 + i * 24:08x}',
                'libTOYOTA_FDATA.so@0001570c#EcuFuncListDetailsTable',
                'libTOYOTA_FDATA.so@0001532c#GetSupportBit']})
    return result


def default_add_info_rows(sources, region, function, detail):
    """Ancillary values appended by 1C; these do not add/remove detail IDs."""
    if region not in ('EU', 'NA', 'JP'):
        raise ValueError('Exact source region required')
    _word(function); _word(detail)
    name = f'{region}/Toyota_bin_1C.BIN'
    table = typed_table(sources[name], 0x1c)
    if table.count and table.stride != 18:
        raise ValueError('Unsupported function-detail add-info stride')
    result = []
    for i in range(table.count):
        row = table.body[i * 18:(i + 1) * 18]
        if uint(row, 14, 2): break
        if (uint(row, 0, 2), uint(row, 2, 2)) == (function, detail):
            result.append({'id': uint(row, 4, 2), 'literalValue': row[6],
                'frameId': uint(row, 8, 2), 'commandCondition': uint(row, 10, 2),
                'supportMode': uint(row, 12, 2), 'sourceRecords': [f'{name}@{10 + i * 18:08x}',
                    'libTOYOTA_FDATA.so@000158d0#EcuFuncListAddInfoTable']})
    return result
