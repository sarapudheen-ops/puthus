"""Exact source-to-app read definitions. Transport approval is a separate gate.

This compiler neither invents adapter timing roles nor enables systems. Its
outputs are actual typed read/workflow objects consumed by Kotlin, not raw source
scripts. The selected full SYSLIST row supplies runtime+18 (selector/decoder mode)
and runtime+19 (caller mode) independently. Neither value is inferred from CAN,
ECUPROGRAM's dispatch kind, a sibling selector or an example ECU.
"""
from toyota_dtc_workflow import (compile_dtc_workflow, default_detail_rows, READ_CONSUMER,
                                SEND_CONSUMER, SETUP_CONSUMER, ASK_CONSUMER)
from toyota_dtc_labels import FaultLabels, compile_p3p4_layout
from toyota_ecuprogram import compile_calibration_14
from toyota_frames import SourceFrames
from toyota_functions import FunctionBlocks
from toyota_structure import uint

SYSLIST_READER = 'libTOYOTA_FILE.so@00011574#GetSystemListData'


def source_id(file, offset, suffix=''):
    return f'{file}@{offset:08x}{suffix}'


def distinct(records):
    return list(dict.fromkeys(records))


def compile_p3p4_diagnostics(sources, libraries, *, region, system_id, system_bytes, native_transport):
    """Compile the complete default P3/P4 DTC caller, labels, clear and dispatch-14 ID.

    Conditional table suffixes / native caller modes 7 and 9 are not this class.
    A longer initial-template list is not silently shortened. Application-level
    execution of 105F still requires its separate one-operation permission; it
    is never a read-only-whitelist exception. No source/physical trust is granted.
    """
    if (region not in ('EU', 'NA', 'JP') or not isinstance(system_bytes, bytes) or len(system_bytes) != 37
            or type(native_transport) is not int or native_transport not in (1, 2, 3, 4, 5)
            or not isinstance(system_id, str) or not system_id.startswith(f'SYSLIST_{region}.BIN@')):
        raise ValueError('Runtime compilation needs the exact regional SYSLIST record/transport')
    function = uint(system_bytes, 0, 4)
    selector, caller_mode = system_bytes[27], system_bytes[33]
    if caller_mode in (7, 9):
        raise ValueError('Native caller mode 7/9 has additional unported overrides')
    functions = FunctionBlocks(sources[f'FUNCDATA_{region}.BIN'])
    function_id = source_id(f'FUNCDATA_{region}.BIN', functions.index_offsets[function])
    plan = compile_dtc_workflow(sources, region=region, function=function)
    frames = SourceFrames(sources, region)

    def frame(value):
        return {'frameId': value.frame_id, 'request': value.request.hex().upper(), 'mask': value.mask.hex().upper(),
                'response': value.response.hex().upper(), 'sourceRecords': distinct([function_id, *value.source_records])}

    details = []
    for row in default_detail_rows(sources, region, function, 1):
        if row['commandCondition']:
            raise ValueError('DTC detail row requires its unported command-condition consumer')
        unconditional = not row['supportMode'] or not row['frameId']
        details.append({'detailId': row['detailId'], 'supportMode': 0 if unconditional else row['supportMode'],
            'expectedByte': row['expectedByte'], 'minimumLength': row['minimumLength'],
            'sourceRecords': distinct([function_id, *row['sourceRecords']]),
            **({} if unconditional else {'frame': frame(frames.frame(row['frameId']))})})
    if not details or len({r['detailId'] for r in details}) != len(details):
        raise ValueError('No unambiguous exact DTC function-detail source rows')
    queries = []
    for query in plan.queries:
        queries.append({'template': query.template, 'queryKind': query.kind, 'membershipWord': query.membership_word,
            'sourceRecords': [function_id, query.source_record, READ_CONSUMER, SEND_CONSUMER],
            'frames': [frame(value) for value in query.frames],
            'setupAlternatives': [{'template': setup.template, 'rule': setup.rule, 'index': setup.index,
                'sourceRecords': [function_id, setup.source_record, SETUP_CONSUMER, ASK_CONSUMER],
                'frames': [frame(value) for value in setup.frames]} for setup in query.setup]})
    # A simple source primitive is retained for independent read-only bring-up.
    # This is NOT permission to skip the workflow in EcuWorkbench.readDtcs().
    primary = next((q for q in plan.queries if q.kind == 1 and not q.setup), None)
    if primary is None or len(primary.frames) != 1:
        raise ValueError('No exact single-frame primary P3/P4 fault-read primitive')
    read_frame = primary.frames[0]
    if not read_frame.response:
        raise ValueError('Fault-read primitive has no explicit positive service')
    labels = FaultLabels(libraries['TCODE_EN.GAG'])
    context = [system_id, SYSLIST_READER, function_id]
    layout = compile_p3p4_layout(plan.block, next(q for q in plan.block.queries if (q.kind, q.template) == (primary.kind, primary.template)),
        plan.function_file, labels, libraries['TEXT_EN.GAG'], mode_lowbits=selector & 7, availability_mask=0,
        context_source_records=context)
    for label in layout['labels']:
        label['sourceRecords'] = distinct([*context, *label['sourceRecords']])
    definition = {'read': {'id': primary.source_record, 'name': 'Source current fault records',
        'request': read_frame.request.hex().upper(), 'responsePrefix': read_frame.response[:1].hex().upper(),
        'dataOffset': 0, 'minBytes': max(plan.block.record_start, plan.block.count_index + 1) if plan.block.count_index != 255 else plan.block.record_start,
        'maxBytes': 4096 - len(read_frame.response[:1]), 'sourceRecords': distinct([function_id, primary.source_record, *read_frame.source_records])},
        'layout': layout,
        'workflow': {'kind': 'TOYOTA_P3P4', 'functionKey': function, 'nativeMode': caller_mode, 'nativeTransport': native_transport,
            'sourceRecords': distinct([*context, source_id(plan.function_file, plan.block.source_offset, '#fault-header'), READ_CONSUMER, SEND_CONSUMER]),
            'initial': [frame(value) for value in plan.initial], 'details': details, 'queries': queries}}
    clear = functions.clear(function)
    if clear is not None:
        if len(clear.queries) != 1:
            raise ValueError('Clear has multiple native queries; not an exact confirmed single clear')
        query = clear.queries[0]
        values = frames.function(function & 65535, query.template)
        if len(values) != 1 or query.kind != 1 or values[0].request != b'\x04' or values[0].response != b'\x44':
            raise ValueError('Not the exact single KWP04/44 clear class')
        definition['clear'] = {'request': '04', 'responsePrefix': '44',
            'sourceRecords': distinct([function_id, source_id(plan.function_file, query.source_offset, '#clear-query'), *values[0].source_records])}
    identity = compile_calibration_14(sources, region=region, function=function)
    identity['read']['sourceRecords'] = distinct([function_id, system_id, *identity['read']['sourceRecords']])
    return {'identification': [identity], 'faults': definition}


MANDATORY_SYSTEM = 'SYSLIST_EU.BIN@0009d8f2'
MANDATORY_ECU = 'EU/Ecu_Data_0081.BIN'


def compile_mandatory_source_reads(sources, libraries, *, calc_evidence):
    """Actual mandatory-target definitions, without fabricating an adapter mapping.

    This is a typed source compilation artifact, NOT an enabled vehicle-system.
    A transport gate is still required before binding it into data.ecus/pids.
    Both the exact source row and the MENU/SYSLIST join remain independently
    audited by the bundle converter; a function number never selects a vehicle.
    """
    import hashlib
    from toyota_entries import EntryIndex
    from toyota_lifecycle import selected_fast_kwp_lifecycle
    from toyota_live import review_direct21
    from toyota_structure import system_lists
    from vehicle_bundle import SOURCE_SHA256, canonical
    raw_systems = sources['SYSLIST_EU.BIN']
    _, blocks = system_lists(raw_systems)
    matching = [raw for start, rows in blocks.items() for i, raw in enumerate(rows)
                if start + 2 + 37 * i == 0x9d8f2]
    if len(matching) != 1:
        raise ValueError('Mandatory SYSLIST record is missing or no longer a complete source row')
    raw = matching[0]
    if (uint(raw, 0, 4) != 61 or raw[27] != 0x11 or raw[33] != 11 or
            libraries['TEXT_EN.GAG'].get(f'{uint(raw, 23, 4):08X}') != 'Ecu_Data_0081.BIN'):
        raise ValueError('Mandatory ECMECT function/full-selector/caller-mode/ECU join changed')
    lifecycle = selected_fast_kwp_lifecycle(EntryIndex(sources['SYSTEMENTER.BIN']), SourceFrames(sources, 'EU'),
                                           region=1, function=61, selector=0x11)
    if lifecycle.entry.source_record != 'SYSTEMENTER.BIN@00002f63' or lifecycle.entry.baud != 10416:
        raise ValueError('Mandatory exact entry/baud changed; no sibling-selector substitution')
    diagnostics = compile_p3p4_diagnostics(sources, libraries, region='EU', system_id=MANDATORY_SYSTEM,
                                          system_bytes=raw, native_transport=lifecycle.entry.transport)
    review = review_direct21(sources, libraries['DSTREAM_EN.GAG'], libraries['EXPRESS.GAG'], region='EU',
                             function_key=61, ecu_file=MANDATORY_ECU, calc_evidence=calc_evidence)
    fields = ('id', 'name', 'request', 'formula', 'unit', 'min', 'max', 'decimals', 'dataOffset', 'supportChecks', 'sourceRecords')
    live = [{key: item[key] for key in fields} for item in review['items'] if item.get('calcReview') is not None]
    by_request = {p['request']: p for p in live}
    for request, formula, offset in (('2105', '(A-40)', '00051245'), ('210C', '(((256*A)+B)/4)', '00051371')):
        if (request not in by_request or by_request[request]['formula'] != formula or
                not any(r.startswith('FUNCDATA_EU.BIN@' + offset) for r in by_request[request]['sourceRecords'])):
            raise ValueError('Mandatory exact coolant/RPM signal/window/formula/unit/applicability compilation changed')
    names = sorted({r['file'] for r in review['inputs']} |
                   {'SYSLIST_EU.BIN', 'SYSTEMENTER.BIN', 'ECUPROGRAM.BIN', 'EU/Toyota_bin_1B.BIN'})
    return {'kind': 'compiled-source-read-review', 'formatVersion': 1, 'sourceSha256': SOURCE_SHA256,
        'systemId': MANDATORY_SYSTEM, 'functionKey': 61, 'entrySelector': 0x11, 'ecuFile': MANDATORY_ECU,
        'rawSystemHex': raw.hex(), 'transportPlan': lifecycle.report(),
        'transportGaps': ['MIC3313 ST/VT timing/pin/line API-equivalence mapping',
                          'Native negative-entry/retry/transport-5 ISO-fallback lifecycle port'],
        'diagnostics': diagnostics, 'liveReads': live,
        'sourceLiveRows': review['sourceRows'], 'numericReviewedCandidates': review['disabledReviewCandidates'],
        'compiledLiveReads': len(live), 'uncompiledLiveRows': review['sourceRows'] - len(live),
        'productionExecutableDefinitions': 0,
        'inputs': [{'file': name, 'bytes': len(sources[name]), 'sha256': hashlib.sha256(sources[name]).hexdigest()} for name in names],
        # These are decoded record dictionaries, not the encrypted GAG members.
        # Keep that boundary explicit rather than giving them an archive-member hash.
        'decodedTextInputs': [{'file': name, 'records': len(libraries[name]),
            'canonicalRecordsSha256': hashlib.sha256(canonical(libraries[name])).hexdigest()}
            for name in ('DSTREAM_EN.GAG', 'EXPRESS.GAG', 'TCODE_EN.GAG', 'TEXT_EN.GAG')],
        'calcEvidenceLibrarySha256': calc_evidence['librarySha256']}


def verify_source_read_review(review):
    """Independent bounded structure/mode/provenance checks, not transport approval."""
    from vehicle_bundle import SOURCE_SHA256
    from vehicle_formula_validation import formula_bytes
    from vehicle_system_validation import require, integer, records, read_definition, fault_layout
    from vehicle_workflow_validation import validate_workflow
    require(isinstance(review, dict) and review.get('kind') == 'compiled-source-read-review' and
            type(review.get('formatVersion')) is int and review['formatVersion'] == 1 and
            review.get('sourceSha256') == SOURCE_SHA256, 'Unknown/unpinned source-read review')
    require(review.get('systemId') == MANDATORY_SYSTEM and review.get('functionKey') == 61 and
            review.get('entrySelector') == 17 and review.get('ecuFile') == MANDATORY_ECU and
            review.get('productionExecutableDefinitions') == 0 and bool(review.get('transportGaps')),
            'A source-read artifact cannot approve a transport, target another ECU or claim production coverage')
    raw = bytes.fromhex(review['rawSystemHex'])
    require(len(raw) == 37 and uint(raw, 0, 4) == 61 and raw[27] == 17 and raw[33] == 11,
            'Source-read review changed the exact source selector/function/caller bytes')
    function_id = 'FUNCDATA_EU.BIN@000005e9'
    def bound(item, path):
        refs = records(item, path)
        require(function_id in refs and all(not r.startswith(('NA/', 'JP/', 'FUNCDATA_NA.', 'FUNCDATA_JP.')) for r in refs),
                path + ': crossed mandatory source scope')
        require(all(not r.startswith('EU/Ecu_Data_') or r.startswith(MANDATORY_ECU + '@') for r in refs), path + ': crossed ECU source')
    faults = review['diagnostics']['faults']; primary = faults['read']; layout = faults['layout']
    read_definition(primary, 'faults.read', 'KWP', bound); count = fault_layout(layout, bound)
    require(count == 1414 and layout['modeLowBits'] == 1 and primary['request'] == '13B0', 'Mandatory source fault layout/primary changed')
    validate_workflow(faults['workflow'], function=61, raw_system=raw, dialect='KWP', primary=primary, layout=layout, bound=bound)
    ids = review['diagnostics']['identification']
    require(len(ids) == 1 and ids[0].get('encoding') == 'ASCII_RECORDS' and ids[0].get('recordBytes') == 16 and
            ids[0]['read'].get('request') == '0904', 'Mandatory identity dispatch/record class changed')
    read_definition(ids[0]['read'], 'identification.read', 'KWP', bound)
    live = review['liveReads']; require(isinstance(live, list) and 1 <= len(live) <= 1015, 'Invalid bounded mandatory live reads')
    require(len({p['id'] for p in live}) == len(live) == review['compiledLiveReads'] and
            review['sourceLiveRows'] == 1015 and review['uncompiledLiveRows'] == 1015 - len(live),
            'Source-read counts differ from actual definitions')
    for i, pid in enumerate(live):
        bound(pid, f'liveReads[{i}]')
        require(formula_bytes(pid['formula']) > 0 and pid['supportChecks'] and
                any(r.startswith(MANDATORY_ECU + '@') for r in pid['sourceRecords']), 'Missing exact live formula/applicability/ECU source')
    counts = {'compiledLiveReads': len(live), 'faultLabels': count, 'sourceFaultQueries': len(faults['workflow']['queries']),
              'identificationReads': len(ids), 'productionExecutableDefinitions': 0}
    # This pin is a reviewed COMPILER-output regression contract. It never
    # approves an adapter mapping or an enabled production vehicle-system.
    import hashlib
    from pathlib import Path
    from vehicle_bundle import canonical
    pin = strict_json((Path(__file__).parent / 'schema' / 'mandatory-read-artifacts-v1.json').read_bytes())
    payload = canonical({k: v for k, v in review.items() if k != 'diagnosticsFile'}) + b'\n'
    diagnostic = canonical(review['diagnostics']) + b'\n'
    require(pin['sourceSha256'] == SOURCE_SHA256 and pin['counts'] == counts and
            pin['review'] == {'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()} and
            pin['diagnostics'] == {'bytes': len(diagnostic), 'sha256': hashlib.sha256(diagnostic).hexdigest()},
            'Compiled source-read artifact differs from the reviewed deterministic source pin')
    return counts


def write_source_read_review(review, output):
    import hashlib
    from vehicle_bundle import canonical
    counts = verify_source_read_review(review)
    output.mkdir(parents=True, exist_ok=True)
    diagnosis = canonical(review['diagnostics']) + b'\n'
    pin = {'file': 'mandatory-ecmect-diagnostics.json', 'bytes': len(diagnosis), 'sha256': hashlib.sha256(diagnosis).hexdigest()}
    (output / pin['file']).write_bytes(diagnosis)
    (output / 'mandatory-ecmect-source-reads.json').write_bytes(canonical({**review, 'diagnosticsFile': pin}) + b'\n')
    return counts


def strict_json(raw, *, maximum_bytes=2 * 1024 * 1024):
    """Canonical artifact input: no duplicate keys, nonfinite numbers or huge input."""
    import json
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= maximum_bytes:
        raise ValueError('Missing or oversized compiled source-read artifact')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: raise ValueError('Duplicate compiled-artifact key: ' + key)
            result[key] = value
        return result
    def nonfinite(value): raise ValueError('Nonfinite compiled-artifact number: ' + value)
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=nonfinite)
    except (UnicodeError, RecursionError) as error:
        raise ValueError('Malformed compiled-artifact JSON') from error


def verify_source_read_files(directory):
    """Verify the whole review and exact split payload, not just claimed file hashes."""
    import hashlib
    from vehicle_bundle import canonical
    raw_review = (directory / 'mandatory-ecmect-source-reads.json').read_bytes()
    review = strict_json(raw_review)
    counts = verify_source_read_review(review)
    if raw_review != canonical(review) + b'\n': raise ValueError('Noncanonical compiled source-read review')
    pin = review['diagnosticsFile']
    raw = (directory / 'mandatory-ecmect-diagnostics.json').read_bytes()
    diagnostic = strict_json(raw)
    if (set(pin) != {'file', 'bytes', 'sha256'} or pin.get('file') != 'mandatory-ecmect-diagnostics.json'
            or type(pin.get('bytes')) is not int or pin['bytes'] != len(raw)
            or pin.get('sha256') != hashlib.sha256(raw).hexdigest()
            or raw != canonical(review['diagnostics']) + b'\n' or raw != canonical(diagnostic) + b'\n'):
        raise ValueError('Compiled source-diagnostics file was truncated/corrupted or differs from its exact review')
    return counts


def check_source_read_artifacts(directory):
    """Exercise ACTUAL generated artifacts, corruption/truncation/scope isolation.

    Production definitions remain zero: passing these tests approves neither
    vehicle communication nor an electrical/timing mapping to a MIC3313.
    The compile CLI separately recompiles all source definitions twice.
    """
    import copy
    from pathlib import Path
    import tempfile
    from vehicle_bundle import canonical
    expected = verify_source_read_files(directory)
    names = ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json')
    original = {name: (directory / name).read_bytes() for name in names}
    vectors = 0
    def reject(files):
        nonlocal vectors
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            for name, data in files.items(): (out / name).write_bytes(data)
            try: verify_source_read_files(out)
            except (ValueError, KeyError, TypeError, FileNotFoundError): vectors += 1; return
            raise AssertionError('Damaged/out-of-scope source artifact passed independent verification')
    for name in names:
        raw = original[name]
        # Edges, midpoint, just before checksum-bearing closing objects, and
        # a nonempty valid JSON prefix cannot pass by ignoring a trailing tail.
        for length in sorted({0, 1, 2, len(raw) // 2, len(raw) - 2, len(raw) - 1}):
            reject({**original, name: raw[:length]})
        for at in (0, len(raw) // 2, len(raw) - 2):
            changed = raw[:at] + bytes([raw[at] ^ 1]) + raw[at + 1:]
            reject({**original, name: changed})
        reject({**original, name: raw + b'{}'})
        reject({**original, name: b' ' + raw})
        reject({key: value for key, value in original.items() if key != name})
    review = strict_json(original[names[0]])
    # Recomputed hashes/canonical JSON cannot bless a changed plan, scope, byte
    # window, unit, formula, label, identity, counter or hidden extra field.
    mutations = (
        lambda v: v.update(entrySelector=81), lambda v: v.update(functionKey=65597),
        lambda v: v.update(systemId='SYSLIST_NA.BIN@0009d8f2'), lambda v: v.update(ecuFile='EU/Ecu_Data_0082.BIN'),
        lambda v: v.update(productionExecutableDefinitions=1), lambda v: v.update(transportGaps=[]),
        lambda v: v['transportPlan']['entry'].update(baud=10400),
        lambda v: v['transportPlan']['entry'].update(sourceTimingHex='053c001818'),
        lambda v: v['transportPlan']['entry'].update(pinPairs=[[15, 15]]),
        lambda v: v['transportPlan']['entry'].update(sourceRecord='SYSTEMENTER.BIN@00002f7e'),
        lambda v: v['liveReads'][0].update(formula='(A+40)'),
        lambda v: v['liveReads'][0].update(unit='unreviewed-unit'),
        lambda v: v['liveReads'][0].update(dataOffset=1),
        lambda v: v['liveReads'][0].update(sourceRecords=['FUNCDATA_NA.BIN@000005e9']),
        lambda v: v['liveReads'][0]['supportChecks'][0].update(request='2101'),
        lambda v: v['diagnostics']['faults']['layout']['labels'][0].update(description='Changed label'),
        lambda v: v['diagnostics']['faults']['workflow']['queries'].pop(),
        lambda v: v['diagnostics']['identification'][0]['read'].update(request='21C1'),
        lambda v: v['inputs'][0].update(sha256='0' * 64),
        lambda v: v['decodedTextInputs'][0].update(records=0),
        lambda v: v.update(compiledLiveReads=True), lambda v: v.update(ignored='Do not silently skip this'),
        lambda v: v['diagnosticsFile'].update(file='../outside.json'),
    )
    for change in mutations:
        changed = copy.deepcopy(review); change(changed)
        reject({**original, names[0]: canonical(changed) + b'\n'})
    reject({**original, names[0]: original[names[0]][:1] + b'"kind":"duplicate",' + original[names[0]][1:]})
    # Actual write/read round-trip is canonical and byte-for-byte repeatable.
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp); write_source_read_review({k: v for k, v in review.items() if k != 'diagnosticsFile'}, out)
        if verify_source_read_files(out) != expected or any((out / name).read_bytes() != data for name, data in original.items()):
            raise AssertionError('Source-read artifact canonical round-trip differs')
    return {**expected, 'actualArtifactNegativeVectors': vectors, 'canonicalRoundTrip': True}


def main():
    import argparse
    import hashlib
    import json
    from pathlib import Path, PurePosixPath
    import zipfile
    from vehicle_bundle import canonical
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    compile_ = sub.add_parser('compile')
    compile_.add_argument('archive', type=Path)
    compile_.add_argument('--decoded', type=Path, required=True)
    compile_.add_argument('--manifest', type=Path, required=True)
    compile_.add_argument('--output', type=Path, required=True)
    verify = sub.add_parser('verify')
    verify.add_argument('directory', type=Path)
    tests = sub.add_parser('test-artifacts')
    tests.add_argument('directory', type=Path)
    args = parser.parse_args()
    if args.command == 'compile':
        from analyze_toyota import checked_infos
        from convert_vehicle_packs import load_sources
        from extract_english import read_text_libraries
        from static_readers import data_report
        from toyota_calc import OBJECTS
        sources = load_sources(args.archive, args.decoded, args.manifest)
        texts = read_text_libraries(args.archive, ('TCODE_EN.GAG', 'TEXT_EN.GAG', 'DSTREAM_EN.GAG', 'EXPRESS.GAG'))
        with zipfile.ZipFile(args.archive) as archive:
            matching = [info for info in checked_infos(archive) if PurePosixPath(info.filename).name == 'libTOYOTA_CALC.so']
            if len(matching) != 1: raise ValueError('Missing/ambiguous original CALC evidence')
            evidence = json.loads(data_report(archive.read(matching[0]), 'libTOYOTA_CALC.so', list(OBJECTS)))
        libraries = {k: v[0] for k, v in texts.items()}
        first = compile_mandatory_source_reads(sources, libraries, calc_evidence=evidence)
        second = compile_mandatory_source_reads(sources, libraries, calc_evidence=evidence)
        if canonical(first) != canonical(second): raise ValueError('Nondeterministic mandatory source-read regeneration')
        result = {**write_source_read_review(first, args.output), 'deterministicRegeneration': True}
        verify_source_read_files(args.output)
    elif args.command == 'test-artifacts':
        result = check_source_read_artifacts(args.directory)
    else:
        result = verify_source_read_files(args.directory)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
