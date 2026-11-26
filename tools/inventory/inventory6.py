#!/usr/bin/env python3
"""Cluster observed BIN filename stems and profile representative archives, read-only."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

import brand_inventory as old
from inventory4 import stem

THRESHOLDS = (0.8, 0.5, 0.2)


def library_stem(path):
    name = PurePosixPath(path.replace('\\', '/')).name
    if name.lower().endswith('.so'):
        name = name[:-3]
    return stem(name + '.BIN')


def clusters(entries, threshold):
    """Connected components of the Jaccard >= threshold graph, including Toyota."""
    entries = sorted(entries, key=lambda e: e['brand'])
    sets = [set(e['report']['stems']['counts']) for e in entries]
    parent = list(range(len(entries)))
    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for i, left in enumerate(sets):
        for j in range(i):
            union = left | sets[j]
            if union and len(left & sets[j]) / len(union) >= threshold:
                parent[root(i)] = root(j)
    groups = defaultdict(list)
    for i, entry in enumerate(entries):
        groups[root(i)].append(entry)
    result = []
    for members in groups.values():
        members.sort(key=lambda e: e['brand'])
        shared = set(members[0]['report']['stems']['counts'])
        for e in members[1:]:
            shared.intersection_update(e['report']['stems']['counts'])
        result.append({'members': [e['brand'] for e in members], 'commonStems': sorted(shared),
                       'totalBins': sum(sum(e['report']['stems']['counts'].values()) for e in members),
                       'partial': [e['brand'] for e in members if e['report']['stems'].get('skippedNested')
                                   or e['report']['stems'].get('error')],
                       'representative': min(members, key=lambda e: (e['report']['archiveBytes'], e['brand']))['brand']})
    return sorted(result, key=lambda group: (-len(group['members']), -group['totalBins'], group['members']))


def describe_archive(path):
    """Central directory census, plus hashes of bounded .so members, no extraction."""
    totals = defaultdict(lambda: {'count': 0, 'bytes': 0})
    libs = []
    skipped = []
    def scan(archive, prefix=''):
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = prefix + info.filename
            if info.filename.lower().endswith('.bin'):
                row = totals[stem(info.filename)]
                row['count'] += 1
                row['bytes'] += info.file_size
            if info.filename.lower().endswith('.so'):
                row = {'path': name, 'stem': library_stem(info.filename), 'bytes': info.file_size}
                if info.flag_bits & 1:
                    row['error'] = 'ZIP-encrypted member'
                elif info.file_size > old.SO_MAX_BYTES:
                    row['error'] = f'member exceeds {old.SO_MAX_BYTES} bytes'
                else:
                    digest = hashlib.sha256()
                    with archive.open(info) as source:
                        for block in iter(lambda: source.read(1024 * 1024), b''):
                            digest.update(block)
                    row['sha256'] = digest.hexdigest()
                libs.append(row)
    with zipfile.ZipFile(path) as archive:
        scan(archive)
        nested = [i for i in archive.infolist() if not i.is_dir() and i.filename.lower().endswith('.zip')]
        eligible = [i for i in nested if not i.flag_bits & 1 and i.file_size <= old.NESTED_ZIP_MAX_BYTES][:old.NESTED_ZIP_COUNT]
        for info in nested:
            if info not in eligible:
                skipped.append(info.filename)
                continue
            with tempfile.TemporaryFile() as scratch:
                with archive.open(info) as source:
                    shutil.copyfileobj(source, scratch)
                scratch.seek(0)
                with zipfile.ZipFile(scratch) as inner:
                    scan(inner, info.filename + '!/')
    return {'stems': dict(sorted(totals.items())), 'libraries': sorted(libs, key=lambda r: r['path']),
            'skippedNested': skipped}


def build(source):
    if not source.get('control', {}).get('passed') or source.get('pending'):
        raise ValueError('Inventory source is incomplete or Toyota control failed')
    entries = source['brands']
    if len(entries) != 73 or len({e['brand'] for e in entries}) != 73:
        raise ValueError(f'Expected Toyota plus 72 distinct brands, got {len(entries)}')
    if any(e['report'].get('error') or e['report']['stems'].get('error') for e in entries):
        raise ValueError('At least one archive or stem census failed; clustering would be incomplete')
    return {str(t): clusters(entries, t) for t in THRESHOLDS}


def report(state):
    out = ['# Inventory-6: observed BIN-stem similarity', '',
           f"Head: `{state['headSha']}`; source inventory head: `{state['sourceHead']}`.", '',
           'Read-only evidence, not a claim of shared format or support. Each vertex is Toyota or one of the 72 '
           'non-Toyota brands. The set comprises the distinct normalized BIN stems from Inventory-4. '
           'Jaccard = intersection size / union size. At each threshold, single-linkage clusters are the '
           'connected components of the graph of pairs with similarity at least the threshold. '
           'Similarity is transitive through edges; members need not all be similar to each other. '
           'Two empty sets do not form an edge. Toyota is included when counting members.', '',
           'Stems use Inventory-4’s basename, region/version-token removal and uppercase rule. '
           'BIN totals are counts in outer and enumerated nested central directories; skipped nested ZIPs '
           'make the indicated brand sets and totals partial, so their cluster and singleton status are provisional. '
           'No semantic meaning is assigned to any stem.', '', '## Documentation summary', '']
    for threshold in THRESHOLDS:
        groups = state['clusters'][str(threshold)]
        single = sum(len(g['members']) == 1 for g in groups)
        brand_single = sum(len(g['members']) == 1 and g['members'][0] != 'TOYOTA' for g in groups)
        toyota = next(g for g in groups if 'TOYOTA' in g['members'])
        out.append(f"- Jaccard {threshold}: {len(groups)} clusters; {single} singleton clusters ({brand_single} non-Toyota brands); Toyota cluster has {len(toyota['members'])} member(s).")
    partial = sorted(e['brand'] for e in state['entries'] if e['report']['stems'].get('skippedNested'))
    out += [f"- Incomplete nested ZIP census: {', '.join(partial) if partial else 'none'}. Clusters are based only on observed stems.", '',
            f"- Representative profiles complete: {len(state['profiles'])}/{len(state['representatives'])}.", '']
    for threshold in THRESHOLDS:
        groups = state['clusters'][str(threshold)]
        out += [f'## Clusters at {threshold}', '', '| cluster | brands | members | common stems | observed BINs | partial members |',
                '| ---: | ---: | --- | --- | ---: | --- |']
        for i, group in enumerate(groups, 1):
            out.append(f"| {i} | {len(group['members'])} | {', '.join(group['members'])} | "
                       f"{old.escape(', '.join(group['commonStems']) or 'none')} | {group['totalBins']} | "
                       f"{', '.join(group['partial']) or 'none'} |")
        out.append('')
    out += ['## Representatives at 0.5', '',
            'Each representative is the smallest compressed release ZIP in its cluster (ties by brand name). '
            'BIN sizes are summed uncompressed sizes; libraries are compared by SHA-256 with every hashed Toyota library, '
            'regardless of name. Unreadable libraries are reported as unavailable, not as different. '
            'Entropy values are only those already measured by Inventory-4 and do not establish correct decryption.', '']
    lookup = {e['brand']: e for e in state['entries']}
    for i, group in enumerate(state['clusters']['0.5'], 1):
        brand = group['representative']
        entry = lookup[brand]
        profile = state['profiles'].get(brand)
        out += [f'### Cluster {i}: {brand}', '', f"- ZIP bytes: {entry['report']['archiveBytes']}; "
                f"observed BINs: {group['totalBins']} across the cluster."]
        if profile is None:
            out += ['- Profile not yet processed.', '']
            continue
        out += ['- BIN stems (count, total uncompressed bytes):', '', '| stem | files | bytes |', '| --- | ---: | ---: |']
        for name, values in profile['stems'].items():
            out.append(f"| `{old.escape(name)}` | {values['count']} | {values['bytes']} |")
        out += ['', '| library | bytes | SHA-256 | identical Toyota library/libraries |', '| --- | ---: | --- | --- |']
        for lib in profile['libraries']:
            ident = ', '.join(lib.get('toyotaMatches', [])) or ('unavailable' if lib.get('error') else 'none')
            out.append(f"| `{old.escape(lib['path'])}` | {lib['bytes']} | {lib.get('sha256', old.escape(lib.get('error', 'unavailable')))} | {old.escape(ident)} |")
        if not profile['libraries']:
            out.append('| no libraries found | | | |')
        out += ['', f"- Skipped nested ZIPs: {old.escape(', '.join(profile['skippedNested']) or 'none')}.",
                '- Previously measured decoded entropy (first 4 KiB):']
        measured = [(r['path'], r['decodedMeasurement']['entropy'], r['decodedMeasurement']['label'])
                    for r in entry['report']['bins'] if 'decodedMeasurement' in r]
        if measured:
            for name, value, label in measured:
                out.append(f"  - `{old.escape(name)}`: {value:.3f} ({label})")
        else:
            out.append('  - not measured in Inventory-4')
        out.append('')
    out += ['## Ranking at 0.5', '', 'Sorted by brand count, then observed BIN count, both descending.', '',
            '| rank | brands | observed BINs | members |', '| ---: | ---: | ---: | --- |']
    for i, group in enumerate(state['clusters']['0.5'], 1):
        out.append(f"| {i} | {len(group['members'])} | {group['totalBins']} | {', '.join(group['members'])} |")
    return '\n'.join(out) + '\n'


def emit(state, output):
    text = report(state)
    old.write_atomic(output / 'inventory6.md', text)
    # Entries contain measured metadata but no vendor payloads; preserve full source metadata separately.
    compact = {k: v for k, v in state.items() if k != 'entries'}
    compact['brandSets'] = {e['brand']: {'binStems': sorted(e['report']['stems']['counts']),
                           'libStems': sorted({library_stem(p.replace('!/', '/')) for p in e['report']['soNames']}),
                           'partial': e['report']['stems'].get('skippedNested', [])} for e in state['entries']}
    old.write_atomic(output / 'inventory6.json', json.dumps(compact, indent=2, sort_keys=True) + '\n')
    parts = []
    for index, piece in enumerate(old.chunk(text), 1):
        path = output / f'part-{index:03d}.md'
        old.write_atomic(path, piece)
        parts.append({'name': 'Inventory-6 clusters' if index == 1 else f'Inventory-6 clusters part {index}', 'file': str(path)})
    old.write_atomic(output / 'parts.json', json.dumps(parts))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    source = json.loads(args.source.read_text())
    if source.get('headSha') != os.environ['GITHUB_SHA']:
        raise ValueError('Inventory source SHA does not match the current workflow SHA')
    groups = build(source)
    entries = source['brands']
    assets = old.release_assets(os.environ['GH_REPO'])
    baseline, brands = old.classify(assets)
    if not baseline:
        raise ValueError('Toyota reference ZIP not found')
    by_brand = {Path(a['name']).stem: a for a in [baseline] + brands}
    reps = sorted({group['representative'] for group in groups['0.5']})
    state = {'headSha': os.environ['GITHUB_SHA'], 'sourceHead': source['headSha'], 'entries': entries,
             'clusters': groups, 'representatives': reps, 'profiles': {}}
    emit(state, args.output)
    path = old.download(baseline, args.work)
    try:
        toyota = describe_archive(path)
        hashes = defaultdict(list)
        for lib in toyota['libraries']:
            if 'sha256' in lib:
                hashes[lib['sha256']].append(lib['path'])
        if 'TOYOTA' in reps:
            for lib in toyota['libraries']:
                if 'sha256' in lib:
                    lib['toyotaMatches'] = hashes[lib['sha256']]
            state['profiles']['TOYOTA'] = toyota
            emit(state, args.output)
    finally:
        path.unlink(missing_ok=True)
    for brand in reps:
        if brand == 'TOYOTA':
            continue
        if brand not in by_brand:
            raise ValueError(f'Representative release ZIP missing: {brand}')
        path = old.download(by_brand[brand], args.work)
        try:
            profile = describe_archive(path)
            for lib in profile['libraries']:
                if 'sha256' in lib:
                    lib['toyotaMatches'] = hashes.get(lib['sha256'], [])
            state['profiles'][brand] = profile
            emit(state, args.output)
            print(f"{brand}: {len(profile['stems'])} stems, {len(profile['libraries'])} libraries", flush=True)
        finally:
            path.unlink(missing_ok=True)
    return 0


if __name__ == '__main__':
    main()
