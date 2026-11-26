#!/usr/bin/env python3
"""Run the unchanged Toyota pack census as the Inventory-5 control."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

EXPECTED = 287030


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    census_json = args.output / 'pack-census.json'
    census_md = args.output / 'pack-census.md'
    command = [sys.executable, 'tools/thinkdiag/pack_census.py', str(args.packs),
               '--json', str(census_json), '--markdown', str(census_md)]
    result = subprocess.run(command, text=True, capture_output=True)
    (args.output / 'census.stdout').write_text(result.stdout)
    (args.output / 'census.stderr').write_text(result.stderr)
    lines = ['# Inventory-5 Toyota census control', '',
             'The existing `tools/thinkdiag/pack_census.py:55-57,80-86,120` reads generated '
             'Toyota packs, not a vendor ZIP. This control uses the existing pinned pack cache.', '']
    passed = False
    if result.returncode:
        lines += [f'Control could not run: census exit status {result.returncode}.', '',
                  '```text', result.stderr[-20000:] or result.stdout[-20000:], '```']
    else:
        data = json.loads(census_json.read_text())
        total = data['treeCounts']['systems']
        passed = total == EXPECTED
        lines += [f"Census `treeCounts.systems`: {total:,}; required: {EXPECTED:,}. "
                  f"{'PASS' if passed else 'FAIL'}.", '',
                  'Entry counts by native transport code: ' + json.dumps(
                      {k: v['entryCount'] for k, v in data['sourceCensus']['systementer'].items()}, sort_keys=True)]
    lines += ['', 'Control only; no brand census or verdict is published until the control passes.']
    (args.output / 'control.md').write_text('\n'.join(lines) + '\n')
    (args.output / 'control-result.json').write_text(json.dumps({'passed': passed}) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
