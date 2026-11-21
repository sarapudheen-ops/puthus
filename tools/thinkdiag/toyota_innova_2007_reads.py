#!/usr/bin/env python3
"""Compile a non-executable, system-specific 2007 ECMECT read-review candidate.

This is research output, not the reviewed deterministic pin or an enabled pack.
It regenerates diagnostics from the 0x51 system row and never carries the pilot
system identity into the candidate. Adapter equivalence is not established here.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

from convert_vehicle_packs import load_sources
from inspect_innova_2007 import report
from toyota_ecmect_pack import load_review
from toyota_runtime_compile import MANDATORY_SYSTEM, compile_p3p4_diagnostics
from vehicle_bundle import SOURCE_SHA256, canonical

SYSTEM = 'SYSLIST_EU.BIN@00023a75'
ENTRY = 'SYSTEMENTER.BIN@00002f96'
ECU = 'EU/Ecu_Data_0081.BIN'
PIN = Path(__file__).parent / 'schema' / 'innova-2007-source-read-candidate-v1.json'


def verify_candidate_bytes(raw, filename):
    from toyota_runtime_compile import strict_json
    pin = json.loads(PIN.read_text())
    if (filename != pin['file'] or pin['sourceSha256'] != SOURCE_SHA256 or
            len(raw) != pin['bytes'] or hashlib.sha256(raw).hexdigest() != pin['sha256']):
        raise ValueError('2007 source-read candidate does not match its pinned bytes')
    value = strict_json(raw)
    if raw != canonical(value) + b'\n' or value.get('systemId') != SYSTEM or value.get('entryId') != ENTRY:
        raise ValueError('2007 source-read candidate has invalid canonical source identity')
    return value


def rebind_live(live):
    """Keep function-derived reads but bind their scope to this exact system."""
    rows = copy.deepcopy(live)
    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == 'sourceRecords':
                    if not isinstance(item, list):
                        raise ValueError('Malformed source record list')
                    value[key] = list(dict.fromkeys(SYSTEM if r == MANDATORY_SYSTEM else r for r in item))
                else:
                    visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(rows)
    for row in rows:
        refs = row.get('sourceRecords')
        if not isinstance(refs, list) or not refs:
            raise ValueError('Live read lacks source records')
        row['sourceRecords'] = list(dict.fromkeys([SYSTEM, ENTRY, *refs]))
    if MANDATORY_SYSTEM in json.dumps(rows):
        raise ValueError('Pilot system identity leaked into 2007 live reads')
    return rows


def compile_review(sources, libraries, pilot):
    joined = report(sources, libraries['TEXT_EN.GAG'])
    entry = joined.get('selectedEntry') or {}
    if (joined['systemId'] != SYSTEM or joined['functionKey'] != 61 or joined['selector'] != 0x51
            or joined['callerMode'] != 11 or joined['ecuFilename']['text'] != 'Ecu_Data_0081.BIN'
            or entry.get('id') != ENTRY or entry.get('transportCode') != 5
            or joined.get('sourceLifecycle') is None):
        raise ValueError('Exact 2007 source join or lifecycle changed')
    if pilot['systemId'] != MANDATORY_SYSTEM or pilot['functionKey'] != 61 or pilot['ecuFile'] != ECU:
        raise ValueError('Source live reads are not from the reviewed function and ECU')
    diagnostics = compile_p3p4_diagnostics(sources, libraries, region='EU', system_id=SYSTEM,
                                         system_bytes=bytes.fromhex(joined['rawHex']), native_transport=5)
    live = rebind_live(pilot['liveReads'])
    if len(live) != 30 or len(diagnostics['faults']['layout']['labels']) != 1414:
        raise ValueError('Unexpected source definition counts')
    result = {'kind': 'innova-2007-source-read-candidate', 'formatVersion': 1, 'sourceSha256': SOURCE_SHA256,
              'systemId': SYSTEM, 'entryId': ENTRY, 'selector': 0x51, 'functionKey': 61, 'ecuFile': ECU,
              'rawSystemHex': joined['rawHex'], 'transportPlan': joined['sourceLifecycle'],
              'diagnostics': diagnostics, 'liveReads': live, 'sourceLiveRows': 1015,
              'uncompiledLiveRows': 985, 'productionExecutableDefinitions': 0}
    if MANDATORY_SYSTEM in json.dumps(result):
        raise ValueError('Pilot system identity leaked into 2007 review')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--decoded', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--english', required=True, type=Path)
    parser.add_argument('--pilot-artifacts', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    sources = load_sources(args.archive, args.decoded, args.manifest)
    libraries = {name: json.loads((args.english / (name + '.json')).read_text())
                 for name in ('TEXT_EN.GAG', 'TCODE_EN.GAG')}
    pilot = load_review(args.pilot_artifacts)
    first = canonical(compile_review(sources, libraries, pilot)) + b'\n'
    if first != canonical(compile_review(sources, libraries, pilot)) + b'\n':
        raise ValueError('Nondeterministic 2007 read-review compilation')
    verify_candidate_bytes(first, args.output.name)
    args.output.write_bytes(first)
    print(json.dumps({'file': args.output.name, 'bytes': len(first), 'sha256': hashlib.sha256(first).hexdigest(),
                      'sourceSystem': SYSTEM, 'entry': ENTRY, 'executableDefinitions': 0}, sort_keys=True))


if __name__ == '__main__':
    main()
