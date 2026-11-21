"""Exact 2007 Innova ECMECT construction; no cross-system fallback.

This module constructs only the pinned source slice. The pack producer and
bundle verifier must both call the independent pin/identity checks before it can
be published. Physical vehicle validation remains pending.
"""
import copy
import json
from pathlib import Path

from toyota_ecmect_pack import FUNCTION, LINK, MANDATORY_SYSTEM
from toyota_innova_2007_reads import ECU, ENTRY, SYSTEM, verify_candidate_bytes
from vehicle_system_validation import require

FILENAME = 'innova-2007-source-read-candidate.json'
TX, RX = '8110F0', '81F010'


def load_candidate(directory):
    path = Path(directory) / FILENAME
    return verify_candidate_bytes(path.read_bytes(), path.name)


def data_for(review):
    plan = review['transportPlan']
    require(review.get('systemId') == SYSTEM and review.get('entryId') == ENTRY and
            review.get('functionKey') == 61 and review.get('selector') == 0x51 and
            review.get('ecuFile') == ECU, 'Wrong 2007 source review identity')
    require(plan['entry']['sourceRecord'] == ENTRY and plan['entry']['selector'] == 0x51 and
            plan['entry']['nativeTransport'] == 5 and plan['entry']['baud'] == 10416 and
            plan['initialWireHex'] == '8110F08102' and plan['keepalive']['requestHex'] == '3e',
            'Wrong 2007 exact source lifecycle')
    require(MANDATORY_SYSTEM not in json.dumps(review), '2007 source review contains pilot identity')
    link = copy.deepcopy(LINK)
    link['kLine']['initAddress'] = 0x10
    link['testerPresent'] = plan['keepalive']['requestHex'].upper()
    diagnostics = copy.deepcopy(review['diagnostics'])
    diagnostics['faults'].pop('clear', None)
    ecu = {'address': '0081', 'name': 'ECMECT (Engine and ECT)', 'protocol': 'KWP',
           'requestId': TX, 'responseId': RX, 'link': link,
           'sourceRecords': list(dict.fromkeys([SYSTEM, FUNCTION, ENTRY, *plan['sourceRecords']])),
           'diagnostics': diagnostics, 'notes': 'Read-only; physical validation PENDING. Calibration ID is not a unique serial.'}
    pids = [{**copy.deepcopy(row), 'header': TX, 'responseHeader': RX, 'link': copy.deepcopy(link)}
            for row in review['liveReads']]
    result = {'ecus': [ecu], 'pids': pids}
    def scope(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == 'sourceRecords':
                    require(isinstance(item, list) and bool(item), 'Definition lacks source provenance')
                    value[key] = list(dict.fromkeys([SYSTEM, ENTRY, FUNCTION, *item]))
                else:
                    scope(item)
        elif isinstance(value, list):
            for item in value:
                scope(item)
    scope(result)
    return result


def enable(system, review, pilot_review):
    """Validate the full selected join and ECU hash before emitting definitions."""
    require(system.get('id') == SYSTEM and system.get('kind') == 'vehicle-system' and
            system.get('sourceVerified') is True and system.get('functionKey') == 61 and
            system.get('entrySelector') == 0x51 and
            system.get('rawHex', '').lower() == review.get('rawSystemHex', '').lower() and
            system.get('sourceRecords') == [SYSTEM, FUNCTION, 'SYSTEMENTER.BIN@000001c6', ENTRY],
            '2007 ECMECT system source row or selector differs')
    ecu = system.get('ecuSource') or {}
    function = system.get('functionSource') or {}
    entry = system.get('entrySource') or {}
    require(ecu.get('sourceFile') == ECU and function.get('sourceRecord') == FUNCTION and
            function.get('functionKey') == 61 and function.get('declaredLiveRows') == 1015 and
            entry.get('sourceRecord') == ENTRY and entry.get('selector') == 0x51,
            '2007 ECMECT exact ECU/function/entry join differs')
    expected_hash = next(i['sha256'] for i in pilot_review['inputs'] if i['file'] == ECU)
    require(ecu.get('sha256') == expected_hash, '2007 ECMECT ECU source checksum differs')
    data = data_for(review)
    require(len(data['pids']) == 30 and len(data['ecus'][0]['diagnostics']['faults']['layout']['labels']) == 1414
            and len(data['ecus'][0]['diagnostics']['identification']) == 1, '2007 source feature counts differ')
    system.update(enabled=True, reasons=[], readOnly=True, data=data,
                  transportPolicy={'driver': 'MIC3313_KWP', 'physicalValidation': 'PENDING',
                    'timingBasis': 'Existing app policy defaults; source-to-adapter equivalence unproven',
                    'sourcePlan': copy.deepcopy(review['transportPlan']),
                    'blockers': ['transport5_iso_fallback_not_ported'], 'initialFrameHex': '8110F08102'})
    for kind, count in {'pids': 30, 'dtcs': 1414, 'ecuInfo': 1, 'tests': 0, 'coding': 0, 'specialFunctions': 0}.items():
        system['counts'][kind] = {'converted': count, 'unresolved': review['uncompiledLiveRows'] if kind == 'pids' else None}
    return system


def candidate_for(system, review):
    records = list(system['sourceRecords'])
    data = data_for(review)
    return {'id': system['id'] + '#translated-entry', 'name': system['name'],
            'sourceRecords': records, 'supportRecords': records, 'supportCount': len(records),
            'metadata': {'supportBasis': 'Distinct source records attached to this translated system',
                         'physicalValidation': 'PENDING', 'transport': 'FAST init; ISO fallback not ported'},
            'blockers': [], 'runtime': {'data': data,
                'identificationId': data['ecus'][0]['diagnostics']['identification'][0]['read']['id'],
                'livePidId': next(p['id'] for p in data['pids'] if p['request'] == '2105'),
                'session': 'DEFAULT', 'identityKind': 'NON_UNIQUE'}}


def verify_enabled(system, review, pilot_review):
    require(isinstance(system.get('bringUpPages'), list) and len(system['bringUpPages']) == 1,
            '2007 ECMECT needs its exact candidate page')
    expected = copy.deepcopy(system)
    enable(expected, review, pilot_review)
    require(system == expected and system.get('readOnly') is True and
            'clear' not in system['data']['ecus'][0]['diagnostics']['faults'],
            '2007 ECMECT differs from pinned read-only definitions')
    return True
