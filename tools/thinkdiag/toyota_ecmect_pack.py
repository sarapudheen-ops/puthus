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
# come from the selected source. initAddress is a mandatory driver field used
# only by FIVE_BAUD init: 00 is an unused FAST-only configuration placeholder,
# NOT a decoded Toyota slow-init address. No FIVE_BAUD fallback is enabled.
LINK = {
    'protocol': 'ISO_14230_4_KWP_FAST', 'dialect': 'KWP2000', 'testerPresent': '3E',
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


def data_for(review):
    verify_source_read_review(review)
    diagnostics = copy.deepcopy(review['diagnostics'])
    diagnostics['faults'].pop('clear', None)  # This slice never exposes clearing.
    link = copy.deepcopy(LINK)
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
            'initialFrameHex': '8113F08105', 'sourcePlan': copy.deepcopy(review['transportPlan'])}


def runtime_for(review):
    data = data_for(review)
    return {'data': data, 'identificationId': data['ecus'][0]['diagnostics']['identification'][0]['read']['id'],
            'livePidId': next(p['id'] for p in data['pids'] if p['request'] == '2105'),
            'session': 'DEFAULT', 'identityKind': 'NON_UNIQUE'}


def enable(system, review):
    """Join only the exact selected record; no other system may use this enablement."""
    require(system['id'] == MANDATORY_SYSTEM and system['kind'] == 'vehicle-system' and system['sourceVerified'] is True and
            system['functionKey'] == 61 and system['entrySelector'] == 17 and system['rawHex'] == review['rawSystemHex'] and
            system['sourceRecords'] == [MANDATORY_SYSTEM, FUNCTION, 'SYSTEMENTER.BIN@000001c6', ENTRY] and
            system['ecuSource']['sourceFile'] == MANDATORY_ECU and
            system['functionSource']['sourceRecord'] == FUNCTION and system['functionSource']['functionKey'] == 61 and
            system['functionSource']['declaredLiveRows'] == 1015 and
            system['entrySource']['sourceRecord'] == ENTRY and system['entrySource']['selector'] == 17,
            'ECMECT source join differs')
    require(system['ecuSource']['sha256'] == next(i['sha256'] for i in review['inputs'] if i['file'] == MANDATORY_ECU),
            'ECMECT ECU input hash differs')
    system.update(enabled=True, reasons=[], readOnly=True, transportPolicy=transport_for(review), data=data_for(review))
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
