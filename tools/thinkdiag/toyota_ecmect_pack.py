"""The single enabled read-only ECMECT slice, using the app's existing KWP driver.

This is deliberately not a general source/native-to-adapter equivalence layer.
The existing MIC3313 transport policy is explicit; the exact Toyota source plan
is retained separately and physical validation remains pending.
"""
import copy
import json
from toyota_runtime_compile import (MANDATORY_ECU, MANDATORY_SYSTEM, verify_source_read_files,
                                    verify_source_read_review, write_source_read_review)
from vehicle_system_validation import require

ENTRY = 'SYSTEMENTER.BIN@00002f63'
FUNCTION = 'FUNCDATA_EU.BIN@000005e9'

# Existing KLineConfig transport-policy defaults, NOT inferred Toyota native
# P1/P2/P3 meanings. Exact baud, packet addresses and FAST-init frame/pulses
# come from the selected source. initAddress 00 is a mandatory app placeholder,
# NOT a decoded Toyota address. The driver still sends ATIIA in FAST mode; its
# effect on MIC3313 is unproven. No FIVE_BAUD fallback is enabled.
LINK = {
    'protocol': 'ISO_14230_4_KWP_FAST', 'dialect': 'KWP2000',
    'kLine': {'init': 'FAST', 'initAddress': 0, 'baud': 10416,
              'p1MaxMs': 20, 'p2MaxMs': 50, 'p2StarMs': 5000,
              'p3MinMs': 55, 'p3MaxMs': 5000, 'p4Ms': 5,
              'fastLowMs': 25, 'fastHighMs': 25,
              'initParity': 'N', 'dataParity': 'N', 'slowInitTimingsMs': []},
}
TX, RX = '8013F0', '80F013'
ARTIFACTS = ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json')


def load_review(directory):
    try:
        verify_source_read_files(directory)
        return json.loads((directory / ARTIFACTS[0]).read_bytes())
    except OSError as error:
        raise ValueError('Pinned recovered ECMECT source-read artifacts are missing or unreadable') from error


def live_omissions(sources, english, review):
    """Exact IDs and first implementation blocker for this ECU's other 985 rows."""
    import hashlib
    from collections import Counter
    from toyota_live import review_direct21
    from vehicle_bundle import canonical
    libraries = {name: json.loads((english / (name + '.json')).read_bytes())
                 for name in ('DSTREAM_EN.GAG', 'EXPRESS.GAG')}
    for item in review['decodedTextInputs']:
        if item['file'] in libraries:
            value = libraries[item['file']]
            require(len(value) == item['records'] and hashlib.sha256(canonical(value)).hexdigest() == item['canonicalRecordsSha256'],
                    'ECMECT recovered English input differs: ' + item['file'])
    rows = review_direct21(sources, libraries['DSTREAM_EN.GAG'], libraries['EXPRESS.GAG'],
                          region='EU', function_key=61, ecu_file=MANDATORY_ECU)
    require(rows['sourceRows'] == 1015 and rows['disabledReviewCandidates'] == 353,
            'ECMECT live-row omission boundaries changed')
    compiled = {r['id']: r for r in review['liveReads']}
    omitted = []
    for row in rows['items']:
        if row['id'] in compiled:
            require(all(row[k] == v for k, v in compiled[row['id']].items() if k != 'sourceRecords'),
                    'ECMECT recovered live definition differs')
            continue
        numeric = row['kind'] == 'numeric-live-review'
        omitted.append({'id': row['id'], 'reasons': ['calc_semantics_unreviewed'] if numeric else row['reasons'],
            'detail': 'Exact EXPRESS/CALC expression is not in the runtime-compiled subset' if numeric else row['detail'],
            'sourceRecords': row['sourceRecords']})
    require(len(omitted) == review['uncompiledLiveRows'] == 985, 'ECMECT omitted live-row count differs')
    return {'kind': 'ecmect-live-omissions', 'systemId': MANDATORY_SYSTEM, 'ecuFile': MANDATORY_ECU,
            'sourceSha256': review['sourceSha256'], 'sourceLiveRows': 1015, 'compiledLiveReads': 30,
            'omittedLiveRows': 985, 'reasons': dict(sorted(Counter(r['reasons'][0] for r in omitted).items())), 'items': omitted}


def response_header_check(lifecycle):
    wire = lifecycle.entry.packet(lifecycle.initial.request)
    expected = bytes([0x80, wire[2], wire[1]]) if len(wire) >= 3 else b''
    response = lifecycle.initial.response
    mask = lifecycle.initial.mask
    agrees = (len(response) >= 3 and len(mask) >= 3 and
              all((response[i] & mask[i]) == (expected[i] & mask[i]) for i in range(3)))
    return {'expectedConventionHex': expected.hex(),
            'responseTemplateHex': response[:3].hex(), 'responseMaskHex': mask[:3].hex(),
            'agrees': agrees}


def link_for(lifecycle):
    """Build the app DiagnosticLink plus a separately labelled source trace.

    Transport 5 dispatches an ISO fallback which this app link cannot express;
    refuse that projection rather than mapping it approximately.
    """
    entry = lifecycle.entry
    if entry.transport != 1 or entry.flags != 0x81:
        raise ValueError(f'link_representation_unsupported:transport-{entry.transport}-flags-{entry.flags:02x}')
    link = copy.deepcopy(LINK)
    link['kLine']['baud'] = entry.baud
    link['testerPresent'] = lifecycle.keepalive.request.hex().upper()
    wire = entry.packet(lifecycle.initial.request)
    request_header = (bytes([0x80]) + wire[1:3]) if len(wire) >= 3 else b''
    check = response_header_check(lifecycle)
    response_convention = bytes.fromhex(check['expectedConventionHex'])
    response_agrees = check['agrees']
    calls = [call.report() for call in lifecycle.fast_success_calls(connector=16, pin_pair_index=0)]
    return {
        'link': link,
        'source': {
            'baud': entry.baud,
            'initPacketHex': wire.hex(),
            'keepaliveRequestHex': lifecycle.keepalive.request.hex(),
            'keepalivePacketHex': entry.packet(lifecycle.keepalive.request).hex(),
            'exitRequestHex': lifecycle.exit.request.hex(),
            'exitPacketHex': entry.packet(lifecycle.exit.request).hex(),
            'requestHeaderHex': request_header.hex(),
            **({'responseHeaderHex': response_convention.hex()} if response_agrees else {}),
            'responseHeaderConventionAgrees': response_agrees,
            'responseHeaderCheck': check,
            'pinAndConnectorCalls': [c for c in calls if c['api'] in ('EnableLLine', 'SelectPIN')],
            'rawTimingHex': entry.timing.hex(),
            'transport': entry.transport,
            'flags': entry.flags,
            'entrySourceRecord': entry.source_record,
            'frameSourceRecords': {
                'init': list(lifecycle.initial.source_records),
                'keepalive': list(lifecycle.keepalive.source_records),
                'exit': list(lifecycle.exit.source_records),
            },
        },
        'provenance': {
            'protocol': 'policy', 'dialect': 'policy', 'kLine.init': 'policy',
            'kLine.initAddress': 'policy', 'kLine.baud': 'source',
            'kLine.p1MaxMs': 'policy', 'kLine.p2MaxMs': 'policy',
            'kLine.p2StarMs': 'policy', 'kLine.p3MinMs': 'policy',
            'kLine.p3MaxMs': 'policy', 'kLine.p4Ms': 'policy',
            'kLine.fastLowMs': 'policy', 'kLine.fastHighMs': 'policy',
            'kLine.initParity': 'policy', 'kLine.dataParity': 'policy',
            'kLine.slowInitTimingsMs': 'policy', 'testerPresent': 'source',
            'baud': 'source', 'transport': 'source', 'flags': 'source',
            'initPacketHex': 'source', 'keepaliveRequestHex': 'source',
            'keepalivePacketHex': 'source', 'exitRequestHex': 'source', 'exitPacketHex': 'source',
            'requestHeaderHex': 'convention', 'responseHeaderHex': 'convention',
            'pinAndConnectorCalls': 'source', 'rawTimingHex': 'source',
        },
    }


def source_keepalive(review):
    """Use only the pinned lifecycle compiler output for this exact entry."""
    verify_source_read_review(review)
    plan = review['transportPlan']
    require(plan['region'] == 1 and plan['function'] == 61 and
            plan['entry']['sourceRecord'] == ENTRY and plan['entry']['selector'] == 0x11,
            'ECMECT keepalive is not from the exact source entry')
    request = plan['keepalive']['requestHex']
    require(isinstance(request, str) and len(request) in (2, 4) and
            all(c in '0123456789abcdef' for c in request), 'Invalid source keepalive request')
    return request.upper()


def data_for(review, lifecycle=None):
    keepalive = source_keepalive(review)
    diagnostics = copy.deepcopy(review['diagnostics'])
    diagnostics['faults'].pop('clear', None)  # This slice never exposes clearing.
    link = link_for(lifecycle)['link'] if lifecycle is not None else {**copy.deepcopy(LINK), 'testerPresent': keepalive}
    provenance = list(dict.fromkeys([MANDATORY_SYSTEM, FUNCTION, ENTRY,
        *review['transportPlan']['sourceRecords']]))
    ecu = {'address': '0081', 'name': 'ECMECT (Engine and ECT)', 'protocol': 'KWP',
           'requestId': TX, 'responseId': RX, 'link': link,
           'sourceRecords': provenance, 'diagnostics': diagnostics,
           'notes': 'Read-only source slice. MIC3313 KWP transport policy; physical validation PENDING. 0904 is calibration ID, not ECU serial.'}
    pids = [{**copy.deepcopy(row), 'header': TX, 'responseHeader': RX, 'link': copy.deepcopy(link)}
            for row in review['liveReads']]
    return {'ecus': [ecu], 'pids': pids}


def transport_for(review):
    return {'driver': 'MIC3313_KWP', 'physicalValidation': 'PENDING',
            'nativeFirmwareEquivalenceRequired': False,
            'timingBasis': 'Existing app KLineConfig policy; not native Toyota firmware equivalence',
            'sourceKeepalive': copy.deepcopy(review['transportPlan']['keepalive']),
            'policyDefaults': {
                **{field: LINK['kLine'][field] for field in
                   ('p1MaxMs', 'p2MaxMs', 'p2StarMs', 'p3MinMs', 'p3MaxMs', 'p4Ms')},
                'initAddress': LINK['kLine']['initAddress'],
            },
            'blockers': [
                'MIC3313 keepalive scheduling and timing policy defaults are not established as equivalent to the source lifecycle',
                'FAST initAddress 00 is an app placeholder, not a decoded source address; ATIIA is sent and its effect is unproven',
                'Physical vehicle and adapter equivalence validation is pending',
            ],
            'initialFrameHex': '8113F08105', 'sourcePlan': copy.deepcopy(review['transportPlan'])}


def runtime_for(review):
    data = data_for(review)
    return {'data': data, 'identificationId': data['ecus'][0]['diagnostics']['identification'][0]['read']['id'],
            'livePidId': next(p['id'] for p in data['pids'] if p['request'] == '2105'),
            'session': 'DEFAULT', 'identityKind': 'NON_UNIQUE'}


def candidate_for(system, review):
    """The ONE runnable read-only bring-up candidate of the enabled slice.

    The runtime carries only already-translated definitions; physical vehicle
    validation stays PENDING and the candidate never enters a non-default
    session, clears or writes."""
    records = list(system['sourceRecords'])
    return {'id': system['id'] + '#translated-entry', 'name': system['name'],
            'sourceRecords': records, 'supportRecords': records, 'supportCount': len(records),
            'metadata': {'supportBasis': 'Distinct source records attached to this translated system',
                         'physicalValidation': 'PENDING',
                         'transport': 'Existing MIC3313 KWP transport policy; native firmware equivalence not claimed'},
            'blockers': [], 'runtime': runtime_for(review)}


def enable(system, review, lifecycle=None):
    """Join only the exact selected record; no other system may use this enablement."""
    mismatches = []
    def check(label, actual, expected):
        if actual != expected:
            mismatches.append(f"{label}: got {actual!r} expected {expected!r}")
    check('id', system['id'], MANDATORY_SYSTEM)
    check('kind', system['kind'], 'vehicle-system')
    check('sourceVerified', system['sourceVerified'], True)
    check('functionKey', system['functionKey'], 61)
    check('entrySelector', system['entrySelector'], 17)
    check('rawHex', system['rawHex'], review['rawSystemHex'])
    check('sourceRecords', system['sourceRecords'], [MANDATORY_SYSTEM, FUNCTION, 'SYSTEMENTER.BIN@000001c6', ENTRY])
    check('ecuSource.sourceFile', system['ecuSource']['sourceFile'] if system['ecuSource'] else None, MANDATORY_ECU)
    check('functionSource.sourceRecord', system['functionSource']['sourceRecord'] if system['functionSource'] else None, FUNCTION)
    check('functionSource.functionKey', system['functionSource']['functionKey'] if system['functionSource'] else None, 61)
    check('functionSource.declaredLiveRows', system['functionSource']['declaredLiveRows'] if system['functionSource'] else None, 1015)
    check('entrySource.sourceRecord', system['entrySource']['sourceRecord'] if system['entrySource'] else None, ENTRY)
    check('entrySource.selector', system['entrySource']['selector'] if system['entrySource'] else None, 17)
    require(not mismatches, 'ECMECT source join differs: ' + '; '.join(mismatches))
    require(system['ecuSource']['sha256'] == next(i['sha256'] for i in review['inputs'] if i['file'] == MANDATORY_ECU),
            'ECMECT ECU input hash differs')
    projection = link_for(lifecycle) if lifecycle is not None else None
    system.update(enabled=True, reasons=[], readOnly=True, transportPolicy=transport_for(review), data=data_for(review, lifecycle))
    if projection is not None:
        system['linkSource'] = projection['source']
        system['linkProvenance'] = projection['provenance']
    for kind, count in {'pids': 30, 'dtcs': 1414, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}.items():
        system['counts'][kind] = {'converted': count, 'unresolved': review['uncompiledLiveRows'] if kind == 'pids' else None}
    # Other feature denominators stay unknown; read-only exclusion is not a
    # claim that the source contains no coding/tests/clear/special functions.
    return system


def verify_enabled(system, review):
    """Strict production allowlist in addition to the generic structural guard."""
    require(isinstance(system.get('bringUpPages'), list) and len(system['bringUpPages']) == 1,
            'Enabled ECMECT needs its exact runnable read-only candidate page')
    expected = copy.deepcopy(system)
    enable(expected, review)
    require(system == expected and system['readOnly'] is True,
            'Enabled ECMECT data/transport/provenance differs from the pinned exact read artifacts')
    require(system['data']['ecus'][0]['diagnostics']['faults'].get('clear') is None,
            'Read-only ECMECT cannot contain a clear operation')
    return True


def stage_review(review, output):
    # Retain the independent, canonical compiler-output pin alongside the pack,
    # not in the APK. No re-download or proprietary runtime library is needed.
    write_source_read_review({k: v for k, v in review.items() if k != 'diagnosticsFile'}, output)
