#!/usr/bin/env python3
"""Read-only decoded BIN and ELF inventory, gated by a Toyota control."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import time
import zipfile

import brand_inventory as old
import toyota_control as control
from decode_toyota import elf_bytes
from yzjm import decode_yzjm

NAMED = ('SystemEnterPart', 'GetDTCCodeFromAnsP3P4', 'CanSetParameterNormal',
         'SendCmdAndGetDtcP3P4', 'ReadDtcModeP3P4', 'GetShowDtcInfo')


def table_for(path):
    with zipfile.ZipFile(path) as archive:
        candidates = [item for item in archive.infolist()
                      if not item.is_dir() and PurePosixPath(item.filename).name == 'libDEVICEID.so']
        if not candidates:
            return None, 'not applicable: no libDEVICEID.so'
        if len(candidates) != 1:
            return None, f'not applicable: {len(candidates)} libDEVICEID.so candidates'
        try:
            return elf_bytes(archive.read(candidates[0]), 0xF88C, 32), None
        except Exception as error:
            return None, f'not applicable: libDEVICEID.so table unavailable: {type(error).__name__}: {error}'


def decoded_describer(table, table_error):
    def describe(name, data):
        value = old.entropy(data[:4096])
        row = {'path': name, 'bytes': len(data), 'magic': old.signatures(data[:512]),
               'headerHex': data[:16].hex(), 'entropyFirst4KiB': value,
               'entropyLabel': 'looks structured' if value < 7.2 else 'looks encrypted/compressed',
               'parsed': None}
        canonical = name.replace('\\', '/').split('!/', 1)[-1]
        if '!/' not in name:
            canonical = PurePosixPath(canonical).name
        if table_error:
            row['readerError'] = table_error
        elif not control.eligible(canonical):
            row['readerError'] = 'not applicable: no converter container reader for this name'
        else:
            try:
                if not data.startswith(b'\x05\x00YZJM\0'):
                    raise ValueError('Not a YZJM wrapped file')
                payload = decode_yzjm(data, table).payload
                row['reader'] = control.reader(canonical, payload)
                row['parsed'] = True
            except (ValueError, TypeError, IndexError, KeyError, OverflowError) as error:
                row['parsed'] = False
                row['readerError'] = f'{type(error).__name__}: {error}'
        return row
    return describe


def summarize(state):
    brands = state['brands']
    if not brands:
        return
    reference = brands[0]['report']
    toyota_sos = reference['sos']
    toyota_exports = set().union(*(set(row.get('exports', [])) for row in toyota_sos))
    names = set(old.hooked_names()) & toyota_exports
    counts = Counter()
    for entry in brands[1:]:
        counts.update(set().union(*(set(row.get('exports', [])) for row in entry['report']['sos'])) & names)
    toyota_only = {n for n in names if any(n in row.get('exports', []) for row in toyota_sos if re.fullmatch(r'libTOYOTA_.*\.so', PurePosixPath(row['path']).name)) and not any(n in row.get('exports', []) for row in toyota_sos if not re.fullmatch(r'libTOYOTA_.*\.so', PurePosixPath(row['path']).name))}
    generic = {n for n in names if counts[n] >= 10 and n not in toyota_only}
    discriminating = names - generic
    role_names = sorted({PurePosixPath(row['path']).name for row in toyota_sos
                         if re.fullmatch(r'libTOYOTA_[A-Za-z0-9]+\.so', PurePosixPath(row['path']).name)})
    suffixes = sorted({name[len('libTOYOTA_'):-3] for name in role_names})
    state['reference'] = {'oracleNames': sorted(names), 'genericNames': sorted(generic),
                          'discriminatingNames': sorted(discriminating), 'brandCounts': dict(counts),
                          'toyotaRoleLibraries': role_names, 'roleSuffixes': suffixes,
                          'classificationComplete': not state['pending']}
    for entry in brands:
        report = entry['report']
        exports = set().union(*(set(row.get('exports', [])) for row in report['sos']))
        rows = report['bins']
        applicable = [row for row in rows if row.get('parsed') is not None]
        parsed = sum(row.get('parsed') is True for row in applicable)
        errors = Counter(row.get('readerError', 'unknown') for row in applicable if not row['parsed'])
        na = Counter(row.get('readerError', 'unknown') for row in rows if row.get('parsed') is None)
        brand = entry['brand']
        paths = [PurePosixPath(name.replace('!/', '/')).name for name in report.get('soNames', [])]
        roles = sorted(name for name in paths if any(name.upper() == f'LIB{brand.upper()}_{s.upper()}.SO' for s in suffixes))
        if brand == 'TOYOTA':
            verdict, reason = 'same family as Toyota', 'Toyota reference; decoded control passed'
        elif parsed:
            verdict, reason = 'same family as Toyota', f'Toyota control passed; {parsed} sampled BIN(s) passed decoded converter reader'
        else:
            verdict, reason = 'NOT ESTABLISHED', ('Toyota control passed, but no sampled BIN passed a decoded converter reader'
                                                  if applicable else 'no sampled BIN was applicable to the decoded converter readers')
        entry['summary'] = {'brand': brand, 'files': report['entries'], 'size': report['archiveBytes'],
                            'decodedParsed': parsed, 'decodedApplicable': len(applicable), 'errors': dict(errors),
                            'notApplicable': dict(na), 'genericMatches': sorted(exports & generic),
                            'discriminatingMatches': sorted(exports & discriminating),
                            'namedExports': {name: name in exports for name in NAMED}, 'roleLibraries': roles,
                            'verdict': verdict, 'reason': reason}


def write_report(state, output):
    summarize(state)
    c = state['control']
    lines = ['# Brand inventory — decoded control and discriminating exports', '',
             f"CI head SHA: `{state['headSha']}`.", '',
             'Read-only analysis; no brand or diagnostic capability is enabled.', '',
             'Decoder: `tools/thinkdiag/decode_toyota.py:39-44`; container readers: '
             '`tools/thinkdiag/convert_vehicle_packs.py:79-91,171,248-249,281,340`.', '',
             f"Toyota control: {c['parsed']}/{c['sample']} parsed; strict majority (>50%): "
             f"{'PASS' if c['passed'] else 'FAIL'}.", '',
             f"Sampling: outer {c['sampling']['outer']}; nested {c['sampling']['nested']}. "
             'Only names selected by the converter readers are applicable.', '', '## Control error histogram', '',
             '| literal error | count |', '| --- | ---: |']
    for error, count in c['errors'].items():
        lines.append(f"| {old.escape(error)} | {count} |")
    if not c['errors']:
        lines.append('| no failures | 0 |')
    if c['passed']:
        ref = state['reference']
        lines += ['', f"Brands processed: {len(state['brands']) - 1}; pending: {len(state['pending'])}.",
                  'Generic means exported by at least 10 processed non-Toyota brands, unless exported only by Toyota role libraries within Toyota. All other Toyota reference names are discriminating. This classification is provisional until every brand is processed.',
                  'A match does not prove compatibility: the verdict requires the passing control and at least one '
                  'decoded BIN parsed with a converter reader. The table address is taken from the Toyota version; '
                  'other brands use their own library at that address, whose identity has not independently been established.', '',
                  'Toyota role libraries: ' + ', '.join(f'`{x}`' for x in ref['toyotaRoleLibraries']), '',
                  '## Summary', '', '| brand | files | archive bytes | decoded parsed/applicable | generic overlap | discriminating overlap | named exports¹ | role libraries | verdict | reason |',
                  '| --- | ---: | ---: | --- | --- | --- | --- | --- | --- | --- |']
        for entry in state['brands']:
            s = entry['summary']
            named = ', '.join(f"{i+1}: {'yes' if s['namedExports'][n] else 'no'}" for i, n in enumerate(NAMED))
            roles = ', '.join(s['roleLibraries']) or 'none'
            lines.append(f"| {s['brand']} | {s['files']} | {s['size']} | {s['decodedParsed']}/{s['decodedApplicable']} | "
                         f"{len(s['genericMatches'])}/{len(ref['genericNames'])} | {len(s['discriminatingMatches'])}/{len(ref['discriminatingNames'])} | "
                         f"{named} | {roles} | {s['verdict']} | {old.escape(s['reason'])} |")
        lines += ['', '¹ Named export order: ' + '; '.join(f'{i+1}. `{name}`' for i, name in enumerate(NAMED)), '',
                  '## Ranking', '', 'Descending decoded parsed/applicable rate, then discriminating overlap; '
                  'no-applicable brands rank last. This is evidence, not a measure of enabled functionality.', '',
                  '| rank | brand | decoded parsed/applicable | discriminating overlap |', '| ---: | --- | --- | ---: |']
        ranked = sorted(state['brands'][1:], key=lambda e: (-(e['summary']['decodedParsed'] / e['summary']['decodedApplicable']
                        if e['summary']['decodedApplicable'] else -1), -len(e['summary']['discriminatingMatches']), e['brand']))
        for index, entry in enumerate(ranked, 1):
            s = entry['summary']
            lines.append(f"| {index} | {s['brand']} | {s['decodedParsed']}/{s['decodedApplicable']} | {len(s['discriminatingMatches'])} |")
        for entry in state['brands']:
            s = entry['summary']
            lines += ['', f"## {s['brand']}: decoded errors and overlaps", '',
                      'Generic matches: ' + (', '.join(s['genericMatches']) or 'none'), '',
                      'Discriminating matches: ' + (', '.join(s['discriminatingMatches']) or 'none'), '',
                      '| literal failure or not-applicable reason | count |', '| --- | ---: |']
            histogram = Counter(s['errors']); histogram.update(s['notApplicable'])
            if not histogram:
                lines.append('| none | 0 |')
            for error, count in sorted(histogram.items()):
                lines.append(f'| {old.escape(error)} | {count} |')
        lines += ['', 'Per-file ELF exports, DT_NEEDED, BIN measurements, and literal reader outcomes are in the JSON report.',
                  '', '## Release asset listing', '', '| release | asset | bytes |', '| --- | --- | ---: |']
        for a in state['assets']:
            lines.append(f"| {old.escape(a['tag'])} | {old.escape(a['name'])} | {a['bytes']} |")
    else:
        lines += ['', 'STOP: control did not pass. No brand verdicts are published.']
    output.mkdir(parents=True, exist_ok=True)
    text = '\n'.join(lines) + '\n'
    old.write_atomic(output / 'brand-inventory.md', text)
    old.write_atomic(output / 'brand-inventory.json', json.dumps(state, indent=1, sort_keys=True) + '\n')
    manifest = []
    for index, part in enumerate(old.chunk(text), 1):
        path = output / f'part-{index:03d}.md'
        old.write_atomic(path, part)
        manifest.append({'name': 'Brand inventory' if index == 1 else f'Brand inventory part {index}', 'file': str(path)})
    old.write_atomic(output / 'parts.json', json.dumps(manifest))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', default=os.environ.get('GH_REPO'), required=not os.environ.get('GH_REPO'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, default=Path('.local/inventory/downloads'))
    parser.add_argument('--max-seconds', type=int, default=1800)
    args = parser.parse_args()
    start = time.monotonic()
    assets = old.release_assets(args.repo)
    baseline, brands = old.classify(assets)
    if not baseline or not brands:
        raise ValueError('Toyota reference or brand assets missing')
    path = old.download(baseline, args.work)
    try:
        result = control.control(path)
        state = {'headSha': os.environ['GITHUB_SHA'], 'control': result, 'assets': assets,
                 'brands': [], 'pending': [a['name'] for a in brands]}
        if not result['passed']:
            write_report(state, args.output)
            return 0
        budget = dict(bytesRead=0, binsInspected=0, librariesInspected=0)
        table, error = table_for(path)
        if error:
            raise ValueError(error)
        old.describe_bin = decoded_describer(table, error)
        report = old.walk(path, budget, old.SO_ALL_LIMIT)
        state['brands'].append({'brand': 'TOYOTA', 'report': report})
        write_report(state, args.output)
    finally:
        path.unlink(missing_ok=True)
    for asset in brands:
        if time.monotonic() - start > args.max_seconds:
            break
        path = old.download(asset, args.work)
        try:
            table, error = table_for(path)
            old.describe_bin = decoded_describer(table, error)
            report = old.walk(path, budget)
            state['brands'].append({'brand': PurePosixPath(asset['name']).stem, 'report': report})
            state['pending'].remove(asset['name'])
            write_report(state, args.output)
            print(f"{asset['name']}: {len(report['bins'])} BINs inspected", flush=True)
        finally:
            path.unlink(missing_ok=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
