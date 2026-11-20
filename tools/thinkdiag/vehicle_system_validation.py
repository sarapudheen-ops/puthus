"""Strict structural validation of compiled, closed-scope Toyota system objects.

This is not a source translator or physical verification. Deterministic conversion
and native comparisons establish separate source semantics. No verification flag,
label, generic protocol default or imported data confers an operation permission.
"""
import math
import re

from vehicle_formula_validation import formula_bytes

KINDS = frozenset(('pids', 'dtcs', 'ecuInfo', 'tests', 'coding', 'specialFunctions'))
RECORD = re.compile(r'[A-Za-z0-9_./-]+\.(?:BIN|GAG|so)(?:@[0-9a-f]{8}(?:#[^\s]+)?|#(?:id)?[0-9A-F]{8})\Z')
HEX = re.compile(r'(?:[0-9A-F]{2})+\Z')
KEEPALIVES = frozenset((b'\x3e', b'\x3e\x00', b'\x3e\x01', b'\x21\x00', b'\x01\x00', b'\x21\x02', b'\x21\xe1'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def obj(value, path, allowed=None):
    require(isinstance(value, dict), path + ': expected object')
    if allowed is not None:
        require(value.keys() <= set(allowed), path + ': unknown field (nothing is silently discarded)')
    return value


def text(value, path, *, empty=False, maximum=4096):
    require(isinstance(value, str) and len(value) <= maximum and '\x00' not in value
            and (empty or bool(value.strip())), path + ': invalid literal text')
    return value


def records(value, path):
    refs = value.get('sourceRecords')
    require(isinstance(refs, list) and 1 <= len(refs) <= 8192 and
            all(isinstance(r, str) and len(r) <= 256 and RECORD.fullmatch(r) for r in refs)
            and len(set(refs)) == len(refs), path + ': missing, malformed or duplicate source records')
    return refs


def byte_string(value, path, minimum=1, maximum=4096):
    require(isinstance(value, str) and HEX.fullmatch(value) is not None and minimum <= len(value) // 2 <= maximum,
            path + ': expected bounded uppercase byte hex')
    return bytes.fromhex(value)


def integer(value, path, minimum=0, maximum=4096):
    require(type(value) is int and minimum <= value <= maximum, path + ': invalid integer')
    return value


def number(value, path):
    require(type(value) in (int, float) and math.isfinite(value), path + ': invalid finite number')
    return value


def sequence(value, path, maximum=65535):
    require(isinstance(value, list) and len(value) <= maximum, path + ': expected bounded list')
    return value


def validate_link(link, dialect, tx, rx):
    obj(link, 'link', ('protocol', 'dialect', 'baudKbps', 'kLine', 'testerPresent'))
    require(link.get('dialect') == ('KWP2000' if dialect == 'KWP' else 'UDS'), 'Explicit link dialect differs')
    keep = byte_string(link.get('testerPresent'), 'testerPresent', maximum=2)
    require(keep in KEEPALIVES if dialect == 'KWP' else keep == b'\x3e\x00', 'Invalid selected-dialect keepalive')
    if 'kLine' in link:
        a, b = byte_string(tx, 'request header', 3, 3), byte_string(rx, 'response header', 3, 3)
        require(a[1:] == b[1:][::-1], 'K-line ECU source/target pair differs')
        k = obj(link['kLine'], 'kLine')
        fields = {'init', 'initAddress', 'baud', 'p1MaxMs', 'p2MaxMs', 'p2StarMs', 'p3MinMs', 'p3MaxMs', 'p4Ms',
                  'fastLowMs', 'fastHighMs', 'initParity', 'dataParity', 'slowInitTimingsMs'}
        require(k.keys() == fields, 'Converted K-line must specify every setting; defaults are not source mappings')
        expected = {'ISO_14230_4_KWP_FAST': 'FAST', 'ISO_14230_4_KWP_SLOW': 'FIVE_BAUD', 'ISO_9141_2': 'FIVE_BAUD'}
        require(dialect == 'KWP' and link.get('protocol') in expected and k['init'] == expected[link['protocol']],
                'K-line protocol/init/dialect mismatch')
        if link['protocol'] != 'ISO_9141_2':
            require(a[0] & 0xc0 == b[0] & 0xc0 == 0x80, 'KWP needs exact addressed physical headers')
        integer(k['initAddress'], 'initAddress', maximum=255); integer(k['baud'], 'baud', 1200, 115200)
        for field in ('p1MaxMs', 'p2MaxMs', 'p2StarMs', 'p3MinMs', 'p3MaxMs'):
            integer(k[field], field, 1, 65535)
        require(k['p2StarMs'] >= k['p2MaxMs'] and k['p3MaxMs'] > k['p3MinMs'], 'Invalid K-line timing intervals')
        integer(k['p4Ms'], 'p4Ms', maximum=65535)
        for field in ('fastLowMs', 'fastHighMs'): integer(k[field], field, 1, 1000)
        require(k['initParity'] in ('N', 'O', 'E') and k['dataParity'] in ('N', 'O', 'E'), 'Invalid K-line parity')
        for v in sequence(k['slowInitTimingsMs'], 'slowInitTimingsMs', 6): integer(v, 'slowInitTimingsMs', maximum=65535)
        require('baudKbps' not in link, 'K-line cannot use CAN baud')
    else:
        require(isinstance(tx, str) and isinstance(rx, str) and
                re.fullmatch(r'(?:[0-7][0-9A-F]{2}|[01][0-9A-F]{7})', tx) is not None and
                re.fullmatch(r'(?:[0-7][0-9A-F]{2}|[01][0-9A-F]{7})', rx) is not None and len(tx) == len(rx),
                'Invalid exact CAN address pair')
        protocols = {'ISO_15765_4_CAN_11_500': 3, 'ISO_15765_4_CAN_29_500': 8,
                     'ISO_15765_4_CAN_11_250': 3, 'ISO_15765_4_CAN_29_250': 8}
        custom = {'STN_ISO_15765_11': 3, 'STN_ISO_15765_29': 8,
                  'USER1_CAN_11': 3, 'USER1_CAN_29': 8, 'USER2_CAN_11': 3, 'USER2_CAN_29': 8}
        p = link.get('protocol')
        require(p in protocols or p in custom, 'Untranslated converted CAN protocol')
        require(len(tx) == (protocols | custom)[p], 'CAN identifier width differs from selected protocol')
        if p in custom:
            require(10 <= number(link.get('baudKbps'), 'baudKbps') <= 1000, 'Custom CAN needs explicit bounded baud')
        else:
            require('baudKbps' not in link, 'Fixed-rate CAN cannot override baud')


def support_checks(checks, path, dialect, bound):
    for i, check in enumerate(sequence(checks, path, 32)):
        p = f'{path}[{i}]'; obj(check, p, ('request', 'formula', 'dataOffset', 'sourceRecords', 'recordId')); bound(check, p, ecu_record=True)
        request = byte_string(check.get('request'), p + '.request', maximum=3)
        if request == b'\xa8\x01':
            require(dialect == 'KWP', p + ': A801 is an exact KWP support-record request')
            integer(check.get('recordId'), p + '.recordId', maximum=255)
        else:
            require('recordId' not in check, p + ': only A801 has a support record ID')
            require(((request[0] in (1, 9, 0x1a) or request[0] == 0x21 and dialect == 'KWP') and len(request) == 2)
                    or (request[0] == 0x22 and len(request) == 3), p + ': not a single-identifier support read')
        width = formula_bytes(check.get('formula'))
        require(width > 0, p + ': constant support predicate cannot establish ECU support')
        integer(check.get('dataOffset', 0), p + '.dataOffset', maximum=4095)


def read_definition(read, path, dialect, bound):
    obj(read, path, ('id', 'name', 'request', 'responsePrefix', 'dataOffset', 'minBytes', 'maxBytes', 'sourceRecords', 'supportChecks', 'dataBytes'))
    text(read.get('id'), path + '.id', maximum=256); text(read.get('name'), path + '.name'); bound(read, path)
    request = byte_string(read.get('request'), path + '.request', maximum=64)
    prefix = byte_string(read.get('responsePrefix'), path + '.responsePrefix', maximum=64)
    require(request[0] in (0x09, 0x1a, 0x21, 0x22, 0x12, 0x13, 0x18, 0x19), path + ': not an explicit enhanced read')
    require(prefix[0] == request[0] + 0x40, path + ': positive service mismatch')
    if dialect == 'KWP':
        shape = (request == b'\x09\x04' or request[0] in (0x1a, 0x21) and len(request) == 2 or request[0] == 0x22 and len(request) >= 3 and len(request) % 2 == 1 or
                 request[0] == 0x12 and request in (b'\x12\xff\x01\x00', b'\x12\xff\x01\xff') or
                 request[0] == 0x13 and len(request) == 2 or request[0] == 0x18 and len(request) == 4 or request[0] == 0x19 and 1 <= len(request) <= 4)
    else:
        shape = request[0] == 0x22 and len(request) >= 3 and len(request) % 2 == 1 or request[0] == 0x19 and len(request) >= 2 and request[1] in (1, 2, 3, 4, 6, 10)
    require(shape, path + ': request shape is not allowed for the selected dialect')
    echo = 2 if request[0] == 0x22 else 1 if request[0] in (0x09, 0x1a, 0x21) or request[0] == 0x19 and dialect == 'UDS' else 0
    require(len(request) > echo and len(prefix) > echo and prefix[1:1 + echo] == request[1:1 + echo], path + ': wrong identifier echo')
    offset = integer(read.get('dataOffset', 0), path + '.dataOffset', maximum=4095)
    minimum = integer(read.get('minBytes', 0), path + '.minBytes')
    maximum = integer(read.get('maxBytes', 4096), path + '.maxBytes', minimum=minimum)
    require(len(prefix) + offset + maximum <= 8192, path + ': oversized reply window')
    if 'dataBytes' in read: integer(read['dataBytes'], path + '.dataBytes', 1, minimum)
    support_checks(read.get('supportChecks', []), path + '.supportChecks', dialect, bound)
    return request


def fault_layout(layout, bound):
    obj(layout, 'faults.layout'); bound(layout, 'faults.layout')
    from toyota_faults import NativeFaultLayout, NativeP5FaultLayout
    common = {'kind', 'recordStart', 'recordBytes', 'queryKind', 'availabilityMask', 'sourceRecords', 'labels', 'sourceFallback'}
    if layout.get('kind') == 'TOYOTA_P3P4':
        obj(layout, 'faults.layout', common | {'countIndex', 'assemblyKind', 'countIsPresence', 'modeLowBits'})
        NativeFaultLayout(layout.get('countIndex'), layout.get('recordStart'), layout.get('recordBytes'),
                          layout.get('assemblyKind'), layout.get('countIsPresence'), layout.get('modeLowBits'))
    else:
        require(layout.get('kind') == 'TOYOTA_P5', 'Missing explicit source-specific fault assembly')
        obj(layout, 'faults.layout', common)
        NativeP5FaultLayout(layout.get('recordStart'), layout.get('recordBytes'))
    integer(layout.get('queryKind'), 'faults.layout.queryKind', 1, 10)
    integer(layout.get('availabilityMask'), 'faults.layout.availabilityMask', maximum=255)
    labels = sequence(layout.get('labels'), 'faults.layout.labels'); keys = set()
    for i, label in enumerate(labels):
        p = f'faults.layout.labels[{i}]'; obj(label, p, ('nativeCode', 'code', 'description', 'sourceRecords')); bound(label, p)
        key = byte_string(label.get('nativeCode'), p + '.nativeCode', 4, 4)
        require(key not in keys, p + ': duplicate exact TCODE key'); keys.add(key)
        text(label.get('code'), p + '.code', maximum=64); text(label.get('description'), p + '.description', empty=True)
    fallback = layout.get('sourceFallback')
    if fallback is not None:
        obj(fallback, 'sourceFallback', ('missingDescription', 'sourceRecords')); records(fallback, 'sourceFallback')
        text(fallback.get('missingDescription'), 'sourceFallback.missingDescription', empty=True)
    return len(keys)


def feature_counts(data):
    """Counts the actual executable objects, not source rows, repeated states or printed-code matches."""
    ecus = data.get('ecus', [])
    return {'pids': len(data.get('pids', [])),
            'dtcs': sum(len(e.get('diagnostics', {}).get('faults', {}).get('layout', {}).get('labels', [])) for e in ecus),
            'ecuInfo': sum(len(e.get('diagnostics', {}).get('identification', [])) for e in ecus),
            'tests': len(data.get('actuators', [])), 'coding': len(data.get('coding', [])),
            'specialFunctions': len(data.get('specialFunctions', []))}


def validate_system(system):
    """Return recomputed category counts; reject empty, cross-scope and miscounted enabled objects."""
    obj(system, 'system'); require(system.get('kind') == 'vehicle-system', 'Not a vehicle-system object')
    require(type(system.get('enabled')) is bool, 'Missing system enabled status')
    counts = obj(system.get('counts'), 'counts'); require(counts.keys() == KINDS, 'Invalid feature count categories')
    for name, value in counts.items():
        obj(value, name); require(value.keys() == {'converted', 'unresolved'}, f'Invalid {name} count')
        integer(value['converted'], name + '.converted', maximum=10_000_000)
        if value['unresolved'] is not None: integer(value['unresolved'], name + '.unresolved', maximum=10_000_000)
    data = obj(system.get('data'), 'data')
    if not system['enabled']:
        require(data == {} and isinstance(system.get('reasons'), list) and bool(system['reasons']) and
                all(isinstance(r, str) and r.strip() for r in system['reasons']) and all(c['converted'] == 0 for c in counts.values()),
                'Disabled system contains executable data or overstates its conversion coverage')
        return dict.fromkeys(KINDS, 0)
    require(system.get('sourceVerified') is True and system.get('reasons') == [], 'Enabled system has unresolved bindings')
    scope = records(system, 'system')
    require(isinstance(system.get('id'), str) and system['id'] in scope and
            re.fullmatch(r'SYSLIST_(EU|NA|JP)\.BIN@[0-9a-f]{8}', system['id']), 'System lacks its exact SYSLIST record')
    region = system['id'][8:10]
    ecu_source = obj(system.get('ecuSource'), 'ecuSource').get('sourceFile')
    function = obj(system.get('functionSource'), 'functionSource'); entry = obj(system.get('entrySource'), 'entrySource')
    function_source, entry_source = function.get('sourceRecord'), entry.get('sourceRecord')
    require(isinstance(ecu_source, str) and re.fullmatch(region + r'/Ecu_Data_[0-9a-fA-F]{4}\.BIN', ecu_source), 'No exact regional ECU source')
    require(isinstance(function_source, str) and re.fullmatch('FUNCDATA_' + region + r'\.BIN@[0-9a-f]{8}', function_source) and
            function_source in scope and isinstance(entry_source, str) and re.fullmatch(r'SYSTEMENTER\.BIN@[0-9a-f]{8}', entry_source) and entry_source in scope,
            'No exact source function/entry join')
    integer(system.get('functionKey'), 'functionKey', maximum=0xffffffff); integer(system.get('entrySelector'), 'entrySelector', maximum=255)
    require(function.get('functionKey') == system['functionKey'] and entry.get('selector') == system['entrySelector'], 'Source selector/function boundary mismatch')
    if 'rawHex' in system:
        # rawHex is lossless source bytes, unlike compiled requests which are canonical uppercase.
        raw = system['rawHex']; require(isinstance(raw, str) and re.fullmatch(r'[0-9a-fA-F]{74}', raw), 'Invalid exact SYSLIST bytes')
        raw = bytes.fromhex(raw)
        require(int.from_bytes(raw[:4], 'little') == system['functionKey'] and raw[27] == system['entrySelector'], 'SYSLIST bytes differ from declared selector/function')
    obj(data, 'data', ('ecus', 'pids', 'dtcs', 'dtcGroups', 'actuators', 'coding'))
    ecus = sequence(data.get('ecus'), 'ecus', 1); require(len(ecus) == 1, 'Enabled system must have exactly one ECU')
    ecu = obj(ecus[0], 'ecu', ('address', 'name', 'requestId', 'responseId', 'protocol', 'notes', 'link', 'sourceRecords', 'diagnostics', 'addressing'))
    text(ecu.get('name'), 'ecu.name'); require(ecu.get('protocol') in ('KWP', 'UDS'), 'Enabled ECU needs an explicit enhanced dialect')
    tx, rx, link = ecu.get('requestId'), ecu.get('responseId'), ecu.get('link')
    require(isinstance(tx, str) and isinstance(rx, str) and tx != rx, 'Missing or equal request/response addresses')
    validate_link(link, ecu['protocol'], tx, rx)
    def bound(item, path, *, ecu_record=False):
        refs = records(item, path)
        require(all(not re.match(r'(?:EU|NA|JP)/', r) or r.startswith(region + '/') for r in refs), path + ': cross-region source')
        require(all(not re.match(r'(?:EU|NA|JP)/Ecu_Data_', r) or r.startswith(ecu_source + '@') for r in refs), path + ': cross-ECU source')
        require(all(not re.match(r'FUNCDATA_(?:EU|NA|JP)\.BIN@', r) or r.startswith('FUNCDATA_' + region + '.BIN@') for r in refs), path + ': cross-region function')
        require(any(r.startswith(ecu_source + '@') for r in refs) if ecu_record else function_source in refs,
                path + ': not bound to the exact selected source')
    bound(ecu, 'ecu'); require(entry_source in ecu['sourceRecords'], 'ECU is not bound to the exact selected entry')
    pids = sequence(data.get('pids'), 'pids'); require(bool(pids), 'Enabled system lacks live definitions'); ids = set()
    for i, pid in enumerate(pids):
        p = f'pids[{i}]'; obj(pid, p, ('id', 'name', 'request', 'formula', 'unit', 'min', 'max', 'decimals', 'header', 'responseHeader', 'notes', 'link', 'dataOffset', 'sourceRecords', 'supportChecks'))
        bound(pid, p); bound(pid, p, ecu_record=True)
        identity = text(pid.get('id'), p + '.id', maximum=256); require(identity not in ids, p + ': duplicate PID id'); ids.add(identity)
        text(pid.get('name'), p + '.name'); text(pid.get('unit'), p + '.unit', empty=True)
        require((pid.get('header'), pid.get('responseHeader'), pid.get('link')) == (tx, rx, link), p + ': cross-ECU routing')
        req = byte_string(pid.get('request'), p + '.request', maximum=3)
        require(((req[0] in (1, 9) or req[0] == 0x21 and ecu['protocol'] == 'KWP') and len(req) == 2) or
                (req[0] == 0x22 and len(req) == 3), p + ': not a live read')
        require(formula_bytes(pid.get('formula')) > 0, p + ': live formula is not byte-dependent')
        require(number(pid.get('min'), p + '.min') < number(pid.get('max'), p + '.max'), p + ': invalid display bounds')
        integer(pid.get('decimals'), p + '.decimals', maximum=6); integer(pid.get('dataOffset', 0), p + '.dataOffset', maximum=4095)
        support_checks(pid.get('supportChecks', []), p + '.supportChecks', ecu['protocol'], bound)
    diagnostics = obj(ecu.get('diagnostics'), 'diagnostics', ('identification', 'faults'))
    identification = sequence(diagnostics.get('identification'), 'identification', 256); require(bool(identification), 'Enabled system lacks explicit identification'); ids = set()
    for i, ident in enumerate(identification):
        p = f'identification[{i}]'; obj(ident, p, ('read', 'encoding', 'byteValues', 'defaultText', 'recordBytes'))
        req = read_definition(ident.get('read'), p + '.read', ecu['protocol'], bound)
        require(req[0] in (0x09, 0x1a, 0x21, 0x22) and len(req) in (2, 3), p + ': invalid identification request')
        identity = ident['read']['id']; require(identity not in ids, p + ': duplicate identification id'); ids.add(identity)
        encoding = ident.get('encoding'); require(encoding in ('HEX', 'ASCII', 'BYTE_ENUM', 'ASCII_RECORDS'), p + ': invalid identification encoding')
        if encoding == 'BYTE_ENUM':
            require(ident['read'].get('dataBytes') == 1, p + ': byte enum needs one selected byte')
            values = obj(ident.get('byteValues'), p + '.byteValues'); require(1 <= len(values) <= 256, p + ': empty/excessive byte enum')
            for key, value in values.items():
                require(isinstance(key, str) and re.fullmatch('[0-9A-F]{2}', key), p + ': invalid enum byte key'); text(value, p + '.byteValues')
            text(ident.get('defaultText'), p + '.defaultText')
        else: require(not ident.get('byteValues') and ident.get('defaultText') is None, p + ': enum fields with non-enum encoding')
        require(ident['read'].get('minBytes', 0) > 0, p + ': empty identification cannot establish an ECU read')
        if encoding == 'ASCII_RECORDS':
            width = integer(ident.get('recordBytes'), p + '.recordBytes', 1, 255)
            require(ident['read'].get('dataBytes') is None and ident['read']['minBytes'] % width == 0 and
                    ident['read'].get('maxBytes', 4096) % width == 0, p + ': invalid whole-record identity window')
        else: require('recordBytes' not in ident, p + ': recordBytes requires ASCII_RECORDS')
    faults = obj(diagnostics.get('faults'), 'faults', ('read', 'layout', 'clear'))
    req = read_definition(faults.get('read'), 'faults.read', ecu['protocol'], bound)
    require(req[0] in ((0x12, 0x13, 0x18, 0x19) if ecu['protocol'] == 'KWP' else (0x18, 0x19)), 'Not a selected-dialect fault-read service')
    fault_layout(faults.get('layout'), bound)
    if faults.get('clear') is not None:
        clear = obj(faults['clear'], 'faults.clear', ('request', 'responsePrefix', 'sourceRecords')); bound(clear, 'faults.clear')
        cr = byte_string(clear.get('request'), 'clear.request', maximum=4); cp = byte_string(clear.get('responsePrefix'), 'clear.responsePrefix', maximum=4)
        require((cr == b'\x04' and ecu['protocol'] == 'KWP' and cp == b'\x44') or
                (cr[0] == 0x14 and len(cr) == (3 if ecu['protocol'] == 'KWP' else 4) and cp in (b'\x54', b'\x54' + cr[1:])), 'Invalid exact confirmed clear')
    for category in ('actuators', 'coding'):
        seen = set()
        for i, item in enumerate(sequence(data.get(category, []), category)):
            p = f'{category}[{i}]'; obj(item, p); bound(item, p)
            identity = text(item.get('id'), p + '.id', maximum=256); require(identity not in seen, p + ': duplicate recipe id'); seen.add(identity)
            text(item.get('name'), p + '.name'); module = obj(item.get('module'), p + '.module')
            require((module.get('requestId'), module.get('responseId'), module.get('link')) == (tx, rx, link), p + ': cross-ECU module')
            require(isinstance(item.get('preconditions'), dict) and bool(item.get('source')), p + ': missing independent source/preconditions')
            # Full operation-specific policy/bounds are independently revalidated by the
            # Kotlin recipe codecs and unchanged write-policy tests in Android acceptance.
    require(not data.get('dtcs') and not data.get('dtcGroups'), 'Use exact source-key DTC labels, not a global printed-code fallback')
    actual = feature_counts(data)
    require(actual['dtcs'] > 0, 'Enabled system lacks nonempty source-key DTC definitions')
    require(all(counts[k]['converted'] == actual[k] for k in KINDS), 'System feature counts disagree with executable data')
    return actual
