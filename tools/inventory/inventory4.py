#!/usr/bin/env python3
"""Read-only decoded BIN measurements and filename-stem census."""
from collections import Counter
import json
from pathlib import PurePosixPath
import re
import shutil
import tempfile
import zipfile

import brand_inventory as old
import inventory3
from toyota_structure import typed_table
from yzjm import decode_yzjm

LABEL = 'exploratory: filename ignored, success is self-verifying only if the container reader accepts the layout'
THRESHOLD = 7.2
reference_device = None
measure_this_archive = False


def stem(name):
    """Basename, no .BIN; strip explicit region and Vnn.nn tokens, casefold."""
    name = PurePosixPath(name.replace('\\', '/')).name
    name = re.sub(r'(?i)\.bin$', '', name)
    name = re.sub(r'(?i)(?:^|[_\-.])v\d{2}\.\d{2}(?=$|[_\-.])', '_', name)
    name = re.sub(r'(?i)(?:^|[_\-.])(?:EU|JP|NA)(?=$|[_\-.])', '_', name)
    return re.sub(r'[_\-.]+', '_', name).strip('_').upper()


def stems(path):
    """Count all outer BIN names and bounded, enumerated nested ZIP central directories."""
    counts = Counter()
    skipped = []
    with zipfile.ZipFile(path) as archive:
        files = [i for i in archive.infolist() if not i.is_dir()]
        for info in files:
            if info.filename.lower().endswith('.bin'):
                counts[stem(info.filename)] += 1
        zips = [i for i in files if i.filename.lower().endswith('.zip')]
        eligible = [i for i in zips if not i.flag_bits & 1 and i.file_size <= old.NESTED_ZIP_MAX_BYTES][:old.NESTED_ZIP_COUNT]
        for info in zips:
            if info not in eligible:
                skipped.append(info.filename)
                continue
            with tempfile.TemporaryFile() as temp:
                with archive.open(info) as source:
                    shutil.copyfileobj(source, temp)
                temp.seek(0)
                with zipfile.ZipFile(temp) as inner:
                    for member in inner.infolist():
                        if not member.is_dir() and member.filename.lower().endswith('.bin'):
                            counts[stem(member.filename)] += 1
    return {'counts': dict(sorted(counts.items())), 'skippedNested': skipped}


def measure(payload):
    value = old.entropy(payload[:4096])
    result = {'entropy': value, 'label': 'looks structured' if value < THRESHOLD else 'looks random',
              'first8': payload[:8].hex(), 'first8Complete': len(payload) >= 8}
    try:
        table = typed_table(payload)
        result['generic'] = {'parsed': True, 'rows': table.count, 'stride': table.stride}
    except (ValueError, IndexError, OverflowError) as exc:
        result['generic'] = {'parsed': False, 'error': f'{type(exc).__name__}: {exc}'}
    return result


def decorate_experiment(table, own, error):
    base = original_experiment(table, own, error)
    def describe(name, data):
        row = base(name, data)
        if measure_this_archive and data.startswith(inventory3.MAGIC):
            try:
                row['decodedMeasurement'] = measure(decode_yzjm(data, table).payload)
            except (ValueError, IndexError, TypeError, OverflowError) as exc:
                row['measurementError'] = f'{type(exc).__name__}: {exc}'
        return row
    return describe


def decorate_walk(path, budget, so_limit=old.SO_SAMPLE):
    global reference_device, measure_this_archive
    device_bytes, _ = inventory3.device(path)
    if reference_device is None:
        reference_device = device_bytes
    measure_this_archive = device_bytes is not None and device_bytes == reference_device
    report = original_walk(path, budget, so_limit)
    try:
        report['stems'] = stems(path)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
        report['stems'] = {'counts': {}, 'skippedNested': [], 'error': f'{type(exc).__name__}: {exc}'}
    return report


def summary(entry, toyota_values):
    rows = [r['decodedMeasurement'] for r in entry['report']['bins'] if 'decodedMeasurement' in r]
    histogram = Counter(r['first8'] for r in rows if r['first8Complete'])
    matches = histogram.keys() & toyota_values
    errors = Counter(r['generic']['error'] for r in rows if not r['generic']['parsed'])
    return {'decoded': len(rows), 'structured': sum(r['label'] == 'looks structured' for r in rows),
            'random': sum(r['label'] == 'looks random' for r in rows),
            'first8Top5': sorted(histogram.items(), key=lambda x: (-x[1], x[0]))[:5],
            'matchingValues': sorted(matches), 'matchingFiles': sum(histogram[x] for x in matches),
            'parsed': sum(r['generic']['parsed'] for r in rows), 'attempted': len(rows),
            'errors': dict(sorted(errors.items())),
            'measurementErrors': dict(Counter(r['measurementError'] for r in entry['report']['bins'] if 'measurementError' in r))}


def section(state):
    entries = state['brands']
    reference = entries[0]
    toyota_values = {r['decodedMeasurement']['first8'] for r in reference['report']['bins']
                     if r.get('decodedMeasurement', {}).get('first8Complete')}
    selected = [e for e in entries if e['brand'] == 'TOYOTA' or e['device']['status'] == 'identical']
    toyota_stems = set(reference['report']['stems']['counts'])
    lines = ['## Inventory-4: decoded structure and filename stems', '', LABEL + '.', '',
             'Only Toyota and brands with byte-identical `libDEVICEID.so` are measured. '
             'Entropy is Shannon entropy of the first 4 KiB, rounded to three decimals; '
             'below 7.2 is “looks structured”, otherwise “looks random”. This heuristic does not verify correctness. '
             'Toyota table decoding has no integrity check. The generic reader is `toyota_structure.typed_table`; '
             'it can accept a structurally valid table without proving its semantics.', '',
             '| brand | decoded | structured / random | first 8 bytes top 5 (hex: count) | matches Toyota first 8 (files; values) | generic parsed / attempted |',
             '| --- | ---: | --- | --- | --- | --- |']
    for e in selected:
        s = summary(e, toyota_values)
        top = ', '.join(f'{x}: {n}' for x, n in s['first8Top5']) or 'none'
        match = ', '.join(s['matchingValues']) or 'none'
        lines.append(f"| {e['brand']} | {s['decoded']} | {s['structured']} / {s['random']} | {top} | {s['matchingFiles']}; {match} | {s['parsed']} / {s['attempted']} |")
    lines += ['', '### Decoded per-file entropy', '',
              'The values below are the first-4-KiB entropy of each sampled decoded BIN; paths are archive member names.', '']
    for e in selected:
        lines += [f"**{e['brand']}**", '']
        for row in e['report']['bins']:
            if 'decodedMeasurement' in row:
                m = row['decodedMeasurement']
                lines.append(f"- `{old.escape(row['path'])}`: {m['entropy']:.3f} — {m['label']}")
            elif 'measurementError' in row:
                lines.append(f"- `{old.escape(row['path'])}`: {old.escape(row['measurementError'])}")
        lines.append('')
    lines += ['### Generic reader literal error histograms', '']
    for e in selected:
        s = summary(e, toyota_values)
        errors = '; '.join(f'{old.escape(k)}: {v}' for k, v in s['errors'].items()) or 'none'
        lines.append(f"- **{e['brand']}** ({s['parsed']}/{s['attempted']}): {errors}")
    lines += ['', '### BIN filename stems', '',
              'Rule: use the BIN basename, remove `.BIN` case-insensitively, remove standalone region '
              'tokens EU, JP, NA and version tokens Vnn.nn delimited by underscore, hyphen or dot; '
              'collapse those delimiters and uppercase. Directory names (including region and version) '
              'are discarded. Numeric suffixes and other tokens are preserved. Counts cover all outer BINs '
              'and enumerated nested ZIPs, not just the decoded sample; the ZIP inspection limit is four '
              'unencrypted nested archives of at most 256 MiB each. A skipped or unreadable archive is reported, '
              'so a limited census is not called complete. `*` means the stem is also present in Toyota.', '',
              '| brand | distinct stems | top 15 stems (count; * = Toyota) | unenumerated nested ZIPs |',
              '| --- | ---: | --- | --- |']
    counts = Counter()
    for e in entries[1:]:
        counts.update(e['report']['stems']['counts'].keys())
    for e in entries:
        data = e['report']['stems']
        ordered = sorted(data['counts'].items(), key=lambda x: (-x[1], x[0]))[:15]
        top = ', '.join(f"`{old.escape(name)}`{'*' if name in toyota_stems else ''} ({count})" for name, count in ordered) or 'none'
        skipped = ', '.join(data['skippedNested']) or data.get('error', 'none')
        lines.append(f"| {e['brand']} | {len(data['counts'])} | {top} | {old.escape(skipped)} |")
    lines += ['', 'All distinct per-brand stem counts are in the JSON report.', '',
              '### Stems present in at least five non-Toyota brands', '',
              '| stem | brand count | in Toyota |', '| --- | ---: | --- |']
    for name, number in sorted(counts.items(), key=lambda x: (-x[1], x[0])):
        if number >= 5:
            lines.append(f"| `{old.escape(name)}` | {number} | {'yes' if name in toyota_stems else 'no'} |")
    return '\n'.join(lines) + '\n\n'


def publish(state, output):
    original_publish(state, output)
    path = output / 'brand-inventory.md'
    text = path.read_text()
    if state['control']['passed'] and state['brands']:
        text = text.replace('## Inventory-3:', section(state) + '## Inventory-3:', 1)
    old.write_atomic(path, text)
    manifest = []
    for index, part in enumerate(old.chunk(text), 1):
        part_path = output / f'part-{index:03d}.md'
        old.write_atomic(part_path, part)
        manifest.append({'name': 'Brand inventory' if index == 1 else f'Brand inventory part {index}', 'file': str(part_path)})
    old.write_atomic(output / 'parts.json', json.dumps(manifest))


original_experiment = inventory3.experiment
original_walk = old.walk
original_publish = inventory3.publish


if __name__ == '__main__':
    inventory3.experiment = decorate_experiment
    old.walk = decorate_walk
    inventory3.publish = publish
    inventory3.main()
