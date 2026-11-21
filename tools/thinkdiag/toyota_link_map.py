#!/usr/bin/env python3
"""Fail-closed W1 inventory of exact source lifecycle evidence, not app links.

Input must be the separately pinned, native-differential lifecycle contracts.
No MIC3313 timing, electrical pin mapping, response header or init address is
inferred. This inventory cannot enable a vehicle or grant a diagnostic operation.
"""
import argparse
import json
from pathlib import Path

from toyota_lifecycle_contracts import FILENAME, verify_contract_file
from toyota_transport import fast_kwp_packet
from vehicle_bundle import SOURCE_SHA256, canonical


def map_plan(plan):
    entry = plan['entry']
    if (plan['kind'] != 'source-fast-kwp-lifecycle' or plan['region'] not in (1, 2, 3)
            or entry['nativeTransport'] not in (1, 5)):
        raise ValueError('Not a source fast-KWP lifecycle plan')
    address = bytes.fromhex(entry['sendAddressSourceHex'])
    def packet(frame):
        return fast_kwp_packet(address, entry['flags'], bytes.fromhex(frame['requestHex'])).hex().upper()
    blockers = [
        'response_header_unmapped', 'fast_init_address_unmapped',
        'mic3313_pin_line_equivalence_unproven', 'mic3313_timing_equivalence_unproven',
        'mic3313_keepalive_schedule_equivalence_unproven', 'physical_validation_pending',
    ]
    if plan['requiresIsoFallbackPort']:
        blockers.append('transport5_iso_fallback_not_approved')
    return {'region': plan['region'], 'function': plan['function'],
            'entryId': entry['sourceRecord'], 'selector': entry['selector'],
            'nativeTransport': entry['nativeTransport'], 'flags': entry['flags'],
            'baud': entry['baud'], 'pinPairs': entry['pinPairs'],
            'runtime107e4': entry['runtime107e4'], 'sendAddressSourceHex': entry['sendAddressSourceHex'],
            'sourceTimingHex': entry['sourceTimingHex'],
            'initial': plan['initial'], 'initialWireHex': packet(plan['initial']),
            'keepalive': plan['keepalive'], 'keepaliveWireHex': packet(plan['keepalive']),
            'exit': plan['exit'], 'exitWireHex': packet(plan['exit']),
            'sourceRecords': plan['sourceRecords'], 'blockers': blockers, 'executable': False}


def map_contracts(contracts):
    if (contracts.get('sourceSha256') != SOURCE_SHA256 or contracts.get('sourceEntries') != 284
            or len(contracts.get('entries', [])) != 284
            or contracts.get('productionExecutableDefinitions') != 0):
        raise ValueError('Not the complete 284-entry source lifecycle contract')
    rows = [map_plan(item['plan']) for item in contracts['entries']]
    identities = {(r['region'], r['function'], r['entryId'], r['selector']) for r in rows}
    if len(identities) != len(rows):
        raise ValueError('Duplicate regional function/entry identity')
    return {'kind': 'toyota-source-link-evidence', 'sourceSha256': SOURCE_SHA256,
            'basis': 'Pinned source lifecycle contracts and native differential; not adapter equivalence',
            'sourceEntries': len(rows), 'executableDefinitions': 0, 'entries': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    verify_contract_file(args.directory)
    document = json.loads((args.directory / FILENAME).read_bytes())
    result = map_contracts(document)
    args.output.write_bytes(canonical(result) + b'\n')
    print(json.dumps({'sourceEntries': result['sourceEntries'],
                      'executableDefinitions': result['executableDefinitions'],
                      'boundary': result['basis']}))


if __name__ == '__main__':
    main()
