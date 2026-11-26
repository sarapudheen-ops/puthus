#!/usr/bin/env python3
"""Inventory-5: strictly gate the existing pack census; never create brand packs."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import brand_inventory as old

# Exact input names referenced by the converter and its SourceFrames helper.
REQUIRED = ('MENU', 'SYSTEMENTER', 'SYSLIST', 'FUNCDATA', 'ECU_DATA')
OPTIONAL_PIPELINE = ('TOYOTA_BIN_00', 'TOYOTA_BIN_11', 'TOYOTA_BIN_12')


def stem_groups(counts):
    present = set()
    for stem in counts:
        if re.fullmatch(r'ECU_DATA_[0-9A-F]+', stem):
            present.add('ECU_DATA')
        elif stem in REQUIRED + OPTIONAL_PIPELINE:
            present.add(stem)
    return sorted(present)


def make_report(state, output):
    lines = ['# Inventory-5: Toyota census on candidate brands', '',
             f"Head: `{state['headSha']}`. Toyota control: 287,030 systems (PASS).", '',
             'Read-only; no brand is enabled. Source: `convert_vehicle_packs.py:171,248-249,278,326,533` '
             'and `toyota_frames.py:27-29`; census `pack_census.py:55-57,80-86,120`. '
             'Optional TOYOTA_BIN_00/11/12 stems are shown when present; name presence alone does not establish layout, regional completeness or verified source.', '',
             f"Reference report head: `{state['sourceHead']}`; processed {len(state['rows'])}/{state['total']}; "
             f"pending {state['total'] - len(state['rows'])}.", '',
             '| brand | stems present | stems missing | census outcome |', '| --- | --- | --- | --- |']
    for row in state['rows']:
        present = ', '.join(row['present']) or 'none'
        missing = ', '.join(row['missing']) or 'none'
        outcome = row['outcome'].replace('|', '&#124;').replace('\n', '<br>')
        lines.append(f"| {row['brand']} | {present} | {missing} | {outcome} |")
    lines += ['', '## Ranking by systems found', '',
              '| brand | systems |', '| --- | ---: |']
    successes = [row for row in state['rows'] if row.get('systems') is not None]
    for row in sorted(successes, key=lambda r: (-r['systems'], r['brand'])):
        lines.append(f"| {row['brand']} | {row['systems']} |")
    if not successes:
        lines.append('| no completed brand censuses | not applicable |')
    output.mkdir(parents=True, exist_ok=True)
    old.write_atomic(output / 'inventory5.md', '\n'.join(lines) + '\n')
    old.write_atomic(output / 'inventory5.json', json.dumps(state, indent=2, sort_keys=True) + '\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--control', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    control = json.loads(args.control.read_text())
    if not control.get('passed'):
        raise SystemExit('STOP: Toyota census control did not reproduce 287,030 systems')
    source = json.loads(args.source.read_text())
    if not source.get('control', {}).get('passed') or source.get('pending'):
        raise SystemExit('STOP: Inventory-4 source is incomplete or its Toyota control failed')
    brands = [b for b in source['brands'] if b['brand'] != 'TOYOTA' and b['device']['status'] == 'identical']
    if len(brands) != 33:
        raise SystemExit(f'STOP: expected 33 byte-identical brands from Inventory-4, found {len(brands)}')
    assets = old.release_assets(os.environ['GH_REPO'])
    _, brand_assets = old.classify(assets)
    by_name = {Path(asset['name']).stem: asset for asset in brand_assets}
    state = {'headSha': os.environ['GITHUB_SHA'], 'sourceHead': source['headSha'],
             'total': len(brands), 'rows': []}
    make_report(state, args.output)
    for brand in brands:
        counts = brand['report']['stems']
        if counts.get('error') or counts.get('skippedNested'):
            outcome = 'not run: incomplete ZIP stem census: ' + str(counts.get('error') or counts['skippedNested'])
            present = stem_groups(counts['counts'])
            missing = sorted(set(REQUIRED) - set(present))
        else:
            present = stem_groups(counts['counts'])
            missing = sorted(set(REQUIRED) - set(present))
            outcome = 'not run: missing ' + ', '.join(missing) if missing else ''
        row = {'brand': brand['brand'], 'present': present, 'missing': missing, 'outcome': outcome}
        if not outcome:
            asset = by_name.get(brand['brand'])
            if asset is None:
                row['outcome'] = 'not run: source release asset not found'
            else:
                path = old.download(asset, args.work)
                try:
                    result = subprocess.run([sys.executable, 'tools/thinkdiag/pack_census.py', str(path),
                                             '--json', str(args.work / 'candidate-census.json'),
                                             '--markdown', str(args.work / 'candidate-census.md')],
                                            capture_output=True, text=True)
                    if result.returncode:
                        row['outcome'] = f'census error (exit {result.returncode}): ' + (result.stderr.strip() or result.stdout.strip())
                    else:
                        data = json.loads((args.work / 'candidate-census.json').read_text())
                        row['systems'] = data['treeCounts']['systems']
                        row['transports'] = {k: v['entryCount'] for k, v in data['sourceCensus']['systementer'].items()}
                        row['outcome'] = f"systems {row['systems']}; native transport entry counts {json.dumps(row['transports'], sort_keys=True)}"
                finally:
                    path.unlink(missing_ok=True)
                    (args.work / 'candidate-census.json').unlink(missing_ok=True)
                    (args.work / 'candidate-census.md').unlink(missing_ok=True)
        state['rows'].append(row)
        make_report(state, args.output)
        print(f"{row['brand']}: {row['outcome']}", flush=True)


if __name__ == '__main__':
    main()
