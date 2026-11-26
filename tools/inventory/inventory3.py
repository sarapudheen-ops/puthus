#!/usr/bin/env python3
"""Investigate BIN headers and a Toyota-table experiment, without enabling anything."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sys
import time
import zipfile

import brand_inventory as old
import inventory2
import toyota_control as control
from decode_toyota import elf_bytes
from yzjm import decode_yzjm

MAGIC = b'\x05\x00YZJM\0'
# These are explicit binary signatures, not guesses based on entropy or text.
OTHER_MAGIC = (b'SQLite format 3\x00', b'\x7fELF', b'MZ', b'PK\x03\x04',
               b'\x1f\x8b', b'7z\xbc\xaf\x27\x1c', b'Rar!',
               b'\x89PNG\r\n\x1a\n', b'\xff\xd8\xff')
LABEL = ('exploratory: Toyota table applied to other brands; success is self-verifying '
         'because the decoded bytes must parse in the container reader, failure proves nothing')


def device(path, toyota_bytes=None, toyota_table=None):
    with zipfile.ZipFile(path) as archive:
        candidates = [i for i in archive.infolist() if not i.is_dir()
                      and PurePosixPath(i.filename).name == 'libDEVICEID.so']
        if not candidates:
            return None, {'status': 'absent', 'size': None, 'sha256': None}
        if len(candidates) != 1:
            return None, {'status': 'ambiguous', 'candidates': len(candidates)}
        data = archive.read(candidates[0])
    result = {'status': 'reference' if toyota_bytes is None else
              ('identical' if data == toyota_bytes else 'different'),
              'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    if toyota_bytes is not None:
        try:
            result['sameVABytes'] = elf_bytes(data, 0xF88C, 32) == toyota_table
        except Exception as exc:
            result['sameVABytes'] = None
            result['vaError'] = f'{type(exc).__name__}: {exc}'
        result['containsToyotaTable'] = toyota_table in data
    return data, result


def experiment(table, own_table, own_error):
    describe = inventory2.decoded_describer(own_table, own_error)
    def run(name, data):
        result = describe(name, data)
        if data.startswith(MAGIC):
            result['headerClass'] = 'YZJM'
        elif any(data.startswith(magic) for magic in OTHER_MAGIC):
            result['headerClass'] = 'other recognized magic'
        else:
            result['headerClass'] = 'no recognized header'
        result['first8'] = data[:8].hex()
        canonical = name.replace('\\', '/').split('!/', 1)[-1]
        if '!/' not in name:
            canonical = PurePosixPath(canonical).name
        if not data.startswith(MAGIC):
            result['experiment'] = {'parsed': None, 'reason': 'not applicable: not YZJM'}
        elif not control.eligible(canonical):
            result['experiment'] = {'parsed': None, 'reason': 'not applicable: no converter container reader for this name'}
        else:
            try:
                payload = decode_yzjm(data, table).payload
                reader = control.reader(canonical, payload)
                result['experiment'] = {'parsed': True, 'reader': reader}
            except (ValueError, IndexError, KeyError, TypeError, OverflowError) as exc:
                result['experiment'] = {'parsed': False, 'error': f'{type(exc).__name__}: {exc}'}
        return result
    return run


def metrics(entry):
    rows = entry['report']['bins']
    classes = Counter(row.get('headerClass', 'unmeasured') for row in rows)
    other = Counter(row['first8'] for row in rows if row.get('headerClass') == 'other recognized magic')
    trials = [row['experiment'] for row in rows if row.get('experiment', {}).get('parsed') is not None]
    failures = Counter(row['error'] for row in trials if not row['parsed'])
    not_applicable = Counter(row['experiment']['reason'] for row in rows
                             if row.get('experiment', {}).get('parsed') is None and 'experiment' in row)
    return {'sample': len(rows), 'yzjm': classes['YZJM'], 'other': classes['other recognized magic'],
            'unrecognized': classes['no recognized header'], 'unmeasured': classes['unmeasured'],
            'otherFirst8Top5': other.most_common(5),
            'parsed': sum(bool(row['parsed']) for row in trials), 'applicable': len(trials),
            'errors': dict(sorted(failures.items())), 'notApplicable': dict(sorted(not_applicable.items()))}


def section(state):
    lines = ['## Inventory-3: BIN headers, libDEVICEID, and Toyota-table experiment', '',
             LABEL + '.', '',
             'YZJM magic: `05 00 59 5a 4a 4d 00`. Other recognized magic uses the explicit signatures '
             'listed in `tools/inventory/inventory3.py`; text, entropy and arbitrary nonzero bytes are not '
             'treated as recognized magic. Unmeasured samples include encrypted or oversized members.', '',
             'The same per-archive sampling rule as Inventory-2 is used; the exploratory denominator is '
             'sampled YZJM files with a converter reader keyed to the file name. An empty denominator is '
             'not a 0% failure. The Toyota table is kept only in memory, never published.', '',
             '| brand | sampled | YZJM | other magic (top first-8-byte hex: count) | no recognized header | unmeasured | libDEVICEID size / SHA-256 | vs Toyota | same VA bytes | contains Toyota table | exploratory parsed/applicable | outcome |',
             '| --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- | --- | --- | --- |']
    for entry in state['brands']:
        m, d = metrics(entry), entry['device']
        other = ', '.join(f'{h}: {n}' for h, n in m['otherFirst8Top5']) or 'none'
        identity = f"{d['size']} / `{d['sha256']}`" if d.get('sha256') else d['status']
        va = str(d.get('sameVABytes', 'not applicable')).lower()
        anywhere = str(d.get('containsToyotaTable', 'not applicable')).lower()
        outcome = ('decodes with Toyota table, container layout not verified'
                   if m['applicable'] and m['parsed'] * 10 >= m['applicable'] * 9 else 'no verdict change')
        lines.append(f"| {entry['brand']} | {m['sample']} | {m['yzjm']} | {other} (total {m['other']}) | "
                     f"{m['unrecognized']} | {m['unmeasured']} | {identity} | {d['status']} | {va} | {anywhere} | "
                     f"{m['parsed']}/{m['applicable']} | {outcome} |")
    lines += ['', '### Exploratory ranking', '',
              '| rank | brand | parsed/applicable | rate |', '| ---: | --- | --- | ---: |']
    ordered = sorted(state['brands'][1:], key=lambda e: (-(metrics(e)['parsed'] / metrics(e)['applicable']
                     if metrics(e)['applicable'] else -1), e['brand']))
    for rank, entry in enumerate(ordered, 1):
        m = metrics(entry)
        rate = f"{100 * m['parsed'] / m['applicable']:.1f}%" if m['applicable'] else 'not applicable'
        lines.append(f"| {rank} | {entry['brand']} | {m['parsed']}/{m['applicable']} | {rate} |")
    lines += ['', '### Exploratory literal error histograms', '']
    for entry in state['brands']:
        m = metrics(entry)
        lines.append(f"- **{entry['brand']}**: " + ('; '.join(f'{old.escape(k)}: {v}' for k, v in m['errors'].items()) or 'no failed applicable samples'))
        if entry['device'].get('vaError'):
            lines.append(f"  - VA read: {old.escape(entry['device']['vaError'])}")
    return '\n'.join(lines) + '\n\n'


def gated_summarize(state):
    original_summarize(state)
    for entry in state['brands'][1:]:
        m = metrics(entry)
        if m['applicable'] and m['parsed'] * 10 >= m['applicable'] * 9:
            entry['summary']['verdict'] = 'decodes with Toyota table, container layout not verified'
            entry['summary']['reason'] = f"exploratory {m['parsed']}/{m['applicable']} parsed (at least 90%)"
        else:
            entry['summary']['verdict'] = 'NOT ESTABLISHED'
            entry['summary']['reason'] = 'exploratory Toyota-table parse rate below 90% or not applicable'


original_summarize = inventory2.summarize


def publish(state, output):
    inventory2.summarize = gated_summarize
    inventory2.write_report(state, output)
    path = output / 'brand-inventory.md'
    text = path.read_text()
    if state['control']['passed'] and state['brands']:
        marker = '## Summary\n'
        text = text.replace(marker, section(state) + marker, 1)
    old.write_atomic(path, text)
    parts = []
    for i, part in enumerate(old.chunk(text), 1):
        part_path = output / f'part-{i:03d}.md'
        old.write_atomic(part_path, part)
        parts.append({'name': 'Brand inventory' if i == 1 else f'Brand inventory part {i}', 'file': str(part_path)})
    old.write_atomic(output / 'parts.json', json.dumps(parts))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', default=os.environ.get('GH_REPO'), required=not os.environ.get('GH_REPO'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, default=Path('.local/inventory/downloads'))
    parser.add_argument('--max-seconds', type=int, default=1800)
    args = parser.parse_args()
    started = time.monotonic()
    assets = old.release_assets(args.repo)
    baseline, brands = old.classify(assets)
    if not baseline or not brands:
        raise ValueError('Toyota reference or non-Toyota brand assets missing')
    path = old.download(baseline, args.work)
    try:
        try:
            c = control.control(path)
        except Exception as exc:
            c = {'parsed': 0, 'sample': 0, 'passed': False, 'sampling': {'outer': 'not run', 'nested': 'not run'},
                 'errors': {f'{type(exc).__name__}: {exc}': 1}, 'results': []}
        state = {'headSha': os.environ['GITHUB_SHA'], 'control': c, 'assets': assets, 'brands': [],
                 'pending': [a['name'] for a in brands]}
        if not c['passed']:
            publish(state, args.output)
            return 0
        reference, info = device(path)
        if reference is None:
            raise ValueError('Toyota libDEVICEID.so is absent or ambiguous')
        table = elf_bytes(reference, 0xF88C, 32)
        budget = dict(bytesRead=0, binsInspected=0, librariesInspected=0)
        own, error = inventory2.table_for(path)
        old.describe_bin = experiment(table, own, error)
        report = old.walk(path, budget, old.SO_ALL_LIMIT)
        state['brands'].append({'brand': 'TOYOTA', 'report': report, 'device': info})
        publish(state, args.output)
    finally:
        path.unlink(missing_ok=True)
    for asset in brands:
        if time.monotonic() - started > args.max_seconds:
            break
        path = old.download(asset, args.work)
        try:
            _, info = device(path, reference, table)
            own, error = inventory2.table_for(path)
            old.describe_bin = experiment(table, own, error)
            report = old.walk(path, budget)
            state['brands'].append({'brand': PurePosixPath(asset['name']).stem, 'report': report, 'device': info})
            state['pending'].remove(asset['name'])
            publish(state, args.output)
            print(f"{asset['name']}: {len(report['bins'])} sampled BINs", flush=True)
        finally:
            path.unlink(missing_ok=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
