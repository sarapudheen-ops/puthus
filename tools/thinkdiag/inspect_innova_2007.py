#!/usr/bin/env python3
"""Report the exact 2007 Innova ECMECT source join; never compile a link."""
import argparse
import json
from pathlib import Path

from convert_vehicle_packs import load_sources
from toyota_entries import EntryIndex
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_structure import function_index, system_lists, uint

SYSTEM = 'SYSLIST_EU.BIN@00023a75'
OFFSET = 0x23a75


def report(sources, texts):
    data = sources['SYSLIST_EU.BIN']
    _, blocks = system_lists(data)
    matches = [row for offset, rows in blocks.items() for i, row in enumerate(rows)
               if offset + 2 + 37 * i == OFFSET]
    if len(matches) != 1 or len(matches[0]) != 37:
        raise ValueError('Expected one complete source row at ' + SYSTEM)
    raw = matches[0]
    function, selector = uint(raw, 0, 4), raw[27]
    def text_field(offset):
        key = uint(raw, offset, 4)
        return {'id': f'{key:08X}', 'text': texts.get(f'{key:08X}')}
    index = EntryIndex(sources['SYSTEMENTER.BIN'])
    directory = index.regions.get(1, {}).get(function)
    selected = index.select(1, function, selector)
    entries = None if directory is None else index.lists[directory['listOffset']].entries
    functions, _ = function_index(sources['FUNCDATA_EU.BIN'])
    rows = [(i, row) for i, row in enumerate(functions) if row[0] == function]
    if len(rows) > 1:
        raise ValueError('Ambiguous function index')
    function_info = None
    if rows:
        i, row = rows[0]
        ptr = row[3]
        count = 0 if ptr in (0, 0xffffffff) else uint(sources['FUNCDATA_EU.BIN'], ptr, 2)
        function_info = {'id': f'FUNCDATA_EU.BIN@{1 + 28*i:08x}', 'fields': list(row),
                         'rawHex': sources['FUNCDATA_EU.BIN'][1+28*i:1+28*(i+1)].hex().upper(),
                         'declaredLiveRows': count}
    entry_info = None if selected is None else selected.report()
    lifecycle, blocker = None, None
    if selected is not None:
        try:
            plan = selected_fast_kwp_lifecycle(index, SourceFrames(sources, 'EU'),
                                               region=1, function=function, selector=selector)
            lifecycle = plan.report()
            lifecycle['initialWireHex'] = plan.entry.packet(plan.initial.request).hex().upper()
            lifecycle['keepaliveWireHex'] = plan.entry.packet(plan.keepalive.request).hex().upper()
        except ValueError as error:
            blocker = str(error)
    pilot = selected_fast_kwp_lifecycle(index, SourceFrames(sources, 'EU'), region=1, function=61, selector=0x11)
    if pilot.entry.source_record != 'SYSTEMENTER.BIN@00002f63':
        raise ValueError('Pilot lifecycle entry differs from pinned source')
    return {'pilotSourceKeepalive': pilot.report()['keepalive'],
            'systemId': SYSTEM, 'region': 'EU', 'rawHex': raw.hex().upper(),
            'functionKey': function, 'name': text_field(4), 'description': text_field(8),
            'ecuFilename': text_field(23), 'selector': selector, 'transportLabel': text_field(28),
            'callerMode': raw[33],
            'directory': None if directory is None else {'sourceRecord': f"SYSTEMENTER.BIN@{directory['offset']:08x}",
                'listOffset': directory['listOffset'], 'listOffsetHex': f"{directory['listOffset']:08x}",
                'descriptors': [entry.descriptor for entry in entries]},
            'selectedEntry': entry_info, 'functionIndex': function_info,
            'sourceLifecycle': lifecycle, 'sourceLifecycleBlocker': blocker,
            'adapterBlocker': 'Source lifecycle is not evidence of MIC3313 timing, pin, address or keepalive equivalence; no receive header or FAST init address is inferred.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--decoded', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--english', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    sources = load_sources(args.archive, args.decoded, args.manifest)
    texts = json.loads((args.english / 'TEXT_EN.GAG.json').read_text())
    result = report(sources, texts)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
