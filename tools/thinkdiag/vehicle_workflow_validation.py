"""Closed structural checks for the source P3/P4 DTC caller, independent of conversion."""
from vehicle_system_validation import (obj, require, integer, sequence, records, HEX)


def validate_workflow(workflow, *, function, raw_system, dialect, primary, layout, bound):
    obj(workflow, 'faults.workflow', ('kind', 'functionKey', 'nativeMode', 'nativeTransport', 'sourceRecords', 'details', 'initial', 'queries'))
    require(workflow.get('kind') == 'TOYOTA_P3P4' and dialect == 'KWP' and layout.get('kind') == 'TOYOTA_P3P4',
            'DTC workflow requires its exact KWP/P3P4 caller/layout')
    require(type(workflow.get('functionKey')) is int and workflow['functionKey'] == function, 'DTC workflow crossed its selected function')
    mode = integer(workflow.get('nativeMode'), 'workflow.nativeMode', maximum=255)
    require(mode not in (7, 9), 'Native caller mode 7/9 overrides are not this workflow')
    integer(workflow.get('nativeTransport'), 'workflow.nativeTransport', 1, 5); bound(workflow, 'workflow')
    if raw_system is not None:
        require(mode == raw_system[33] and layout.get('modeLowBits') == raw_system[27] & 7,
                'DTC caller/decoder modes differ from the two distinct SYSLIST source bytes')

    def bytes_(value, p, max_bytes=255, empty=False):
        require(isinstance(value, str) and (value == '' and empty or HEX.fullmatch(value)) and len(value) // 2 <= max_bytes, p + ': invalid source byte string')
        return bytes.fromhex(value)

    def frame(value, p, allow_session=False):
        obj(value, p, ('frameId', 'request', 'mask', 'response', 'sourceRecords')); bound(value, p)
        integer(value.get('frameId'), p + '.frameId', maximum=65535)
        req = bytes_(value.get('request'), p + '.request', 63)
        bytes_(value.get('mask'), p + '.mask', empty=True)
        rep = bytes_(value.get('response'), p + '.response', empty=True)
        source_session = allow_session and req == b'\x10\x5f' and rep == b'\x50\x5f'
        read = ((req[:1] in (b'\x01', b'\x09', b'\x1a', b'\x21') and len(req) == 2)
            or req == b'\xa8\x01' or req[:1] == b'\x22' and len(req) >= 3 and len(req) % 2 == 1
            or req in (b'\x07', b'\x0a', b'\x81', b'\x3e', b'\x3e\x00', b'\x12\xff\x01\x00', b'\x12\xff\x01\xff')
            or req[:1] == b'\x13' and len(req) == 2 or req[:1] == b'\x18' and len(req) == 4 or req[:1] == b'\x19' and len(req) <= 4)
        require(read or source_session, p + ': not a source read or separately confirmed initial 105F')
        require(not rep or rep[0] == req[0] + 0x40, p + ': wrong positive service')
        echo = 2 if req[:1] == b'\x22' else 1 if req[:1] in (b'\x01', b'\x09', b'\x1a', b'\x21', b'\xa8', b'\x10') else 0
        require(not rep or rep[1:1 + echo] == req[1:1 + min(echo, len(rep) - 1)], p + ': mismatched source prefix/echo')
        return req

    details = sequence(workflow.get('details'), 'workflow.details', 255); require(bool(details), 'No DTC function-detail source rows')
    detail_ids = set()
    for i, row in enumerate(details):
        p = f'workflow.details[{i}]'; obj(row, p, ('detailId', 'supportMode', 'expectedByte', 'minimumLength', 'frame', 'sourceRecords')); bound(row, p)
        identity = integer(row.get('detailId'), p + '.detailId', maximum=65535)
        require(identity not in detail_ids, p + ': duplicate detail row'); detail_ids.add(identity)
        support = integer(row.get('supportMode'), p + '.supportMode', maximum=65535)
        integer(row.get('expectedByte'), p + '.expectedByte', maximum=255); integer(row.get('minimumLength'), p + '.minimumLength', maximum=255)
        require((support == 0) == ('frame' not in row), p + ': ambiguous unconditional/query source forms')
        if support: frame(row['frame'], p + '.frame')
    for i, value in enumerate(sequence(workflow.get('initial'), 'workflow.initial', 1)):
        frame(value, f'workflow.initial[{i}]', allow_session=True)
    queries = sequence(workflow.get('queries'), 'workflow.queries', 255); require(bool(queries), 'No exact DTC source queries')
    primary_found = False
    for i, query in enumerate(queries):
        p = f'workflow.queries[{i}]'; obj(query, p, ('template', 'queryKind', 'membershipWord', 'setupAlternatives', 'frames', 'sourceRecords')); bound(query, p)
        integer(query.get('template'), p + '.template', maximum=65535)
        kind = integer(query.get('queryKind'), p + '.queryKind', 1, 10)
        integer(query.get('membershipWord'), p + '.membershipWord', maximum=65535)
        values = sequence(query.get('frames'), p + '.frames', 20); require(bool(values), p + ': no exact source frames')
        for j, value in enumerate(values):
            req = frame(value, p + f'.frames[{j}]')
            primary_found |= kind == layout.get('queryKind') and req.hex().upper() == primary.get('request')
        for j, setup in enumerate(sequence(query.get('setupAlternatives'), p + '.setupAlternatives', 5)):
            sp = p + f'.setupAlternatives[{j}]'; obj(setup, sp, ('template', 'rule', 'index', 'frames', 'sourceRecords')); bound(setup, sp)
            integer(setup.get('template'), sp + '.template', maximum=65535); integer(setup.get('rule'), sp + '.rule', maximum=4)
            integer(setup.get('index'), sp + '.index', maximum=255)
            values = sequence(setup.get('frames'), sp + '.frames', 20); require(bool(values), sp + ': no exact setup frames')
            for k, value in enumerate(values): frame(value, sp + f'.frames[{k}]')
    require(primary_found, 'The primary fault primitive does not belong to the same source workflow')
