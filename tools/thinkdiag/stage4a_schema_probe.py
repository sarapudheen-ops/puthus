#!/usr/bin/env python3
"""Read-only FUNCDATA slot census; unparsed spans are never called layouts."""
import argparse
from collections import Counter
import gzip
import json
import os
from pathlib import Path
import signal
import traceback

from toyota_functions import FunctionBlocks
from toyota_entries import EntryIndex
from toyota_structure import uint

LABELS = {1: 'fault (not a requested category)', 2: 'clear (not a requested category)',
          3: 'live data (not a requested category)', 4: 'category NOT ESTABLISHED',
          5: 'category NOT ESTABLISHED'}
CITES = {1: 'toyota_functions.py:163-165', 2: 'toyota_functions.py:167-169',
         3: 'toyota_live.py:185-206', 4: 'toyota_structure.py:275-287',
         5: 'toyota_structure.py:275-287'}


def live_skeleton(raw, start, stop):
    count, commands = uint(raw, start, 2), uint(raw, start + 2, 2)
    at = start + 4 + count * 30
    if at > stop: raise ValueError('live rows exceed span')
    items = []
    for _ in range(commands):
        if at >= stop: raise ValueError('missing live command length')
        n = raw[at]
        if at + 1 + n > stop: raise ValueError('live command exceeds span')
        items.append(raw[at + 1:at + 1 + n].hex())
        at += n + 1
    if at != stop: raise ValueError('untranslated live tail')
    return {'bytes': stop - start, 'rows': count, 'rowBytes': 30,
            'commandLengths': [len(bytes.fromhex(v)) for v in items]}, items


def shape(blocks, key, slot):
    span = blocks.span(key, slot)
    if span is None: return None
    start, stop = span
    if slot == 1:
        b = blocks.faults(key)
        return {'bytes': stop-start, 'initialTemplates': len(b.initial_templates),
                'queries': len(b.queries), 'conditionsPerQuery': [len(q.conditions) for q in b.queries],
                'mappings': len(b.mappings)}
    if slot == 2:
        b = blocks.clear(key)
        return {'bytes': stop-start, 'queries': len(b.queries)}
    if slot == 3: return live_skeleton(blocks.raw, start, stop)[0]
    return {'spanBytes': stop-start, 'recordSkeleton': 'NOT ESTABLISHED'}


def selected(rows):
    result = [r for r in rows if r.get('nativeTransport') == 1 and r.get('linkStatus') == 'proven']
    if len(result) != 39363: raise ValueError(f'expected 39363 proven transport-1 systems, got {len(result)}')
    return result


def census(rows, regions):
    counts = {slot: Counter() for slot in range(1, 6)}
    cache = {}
    for r in rows:
        region, key = r['region'], r['functionKey']
        blocks = regions[region]
        if key not in blocks.rows: raise ValueError(f'missing FUNCDATA function: {region}/{key}')
        for slot in range(1, 6):
            k = (region, key, slot)
            if k not in cache:
                try:
                    s = shape(blocks, key, slot)
                except ValueError as e:
                    a, b = blocks.span(key, slot)
                    s = {'spanBytes': b-a, 'recordSkeleton': 'NOT ESTABLISHED', 'parseError': str(e)}
                cache[k] = None if s is None else json.dumps(s, sort_keys=True, separators=(',', ':'))
            if cache[k] is not None: counts[slot][cache[k]] += 1
    return counts


def ecmect(regions, entry_index, libraries, sources):
    from toyota_frames import SourceFrames
    from toyota_native_oracle import compare_frames
    index = regions['EU']
    if index.index_offsets.get(61) != 0x5e9: raise ValueError('ECMECT FUNCDATA pin differs')
    entry = entry_index.select(1, 61, 0x11)
    if not entry or entry.source_id != 'SYSTEMENTER.BIN@00002f63': raise ValueError('ECMECT SYSTEMENTER pin differs')
    frames = SourceFrames(sources, 'EU')
    output = []
    for slot in range(1, 6):
        span = index.span(61, slot)
        if span is None: continue
        row = {'slot': slot, 'source': f'FUNCDATA_EU.BIN@{span[0]:08x}', 'spanBytes': span[1]-span[0],
               'category': LABELS[slot], 'entries': []}
        templates = []
        try:
            if slot == 1:
                b = index.faults(61)
                templates = [('initial', i, t) for i, t in enumerate(b.initial_templates)]
                templates += [('fault query', i, q.template) for i, q in enumerate(b.queries)]
            elif slot == 2:
                b = index.clear(61)
                templates = [('clear query', i, q.template) for i, q in enumerate(b.queries)]
            elif slot == 3:
                _, commands = live_skeleton(index.raw, *span)
                for i, command in enumerate(commands):
                    row['entries'].append({'entry': f'live command record {i}', 'name': 'NOT ESTABLISHED',
                        'storedCommandHex': command, 'result': 'not replayable',
                        'reason': 'native request-builder entrypoint and its blocking import or cell NOT ESTABLISHED; no matching native call inferred'})
            else:
                row['reason'] = 'entry layout and names NOT ESTABLISHED; no entry or command bytes inferred'
            for kind, i, template in templates:
                found = frames.function(61, template)
                obj = {'entry': f'{kind} {i}', 'name': 'NOT ESTABLISHED', 'template': template,
                       'storedRequestsHex': [f.request.hex() for f in found]}
                try:
                    result = compare_frames(libraries, sources, 'EU', 61, [template])
                    if not found:
                        obj.update(result='not replayable', reason='no default frame binding; blocking import or cell NOT ESTABLISHED')
                    else:
                        obj.update(result='matches', reason='COMM GetFuncCommFrameInfo_MultiCmd native/source frame bytes agree; not a complete operation replay', nativeFrames=result['templates'][0]['frames'])
                except AssertionError as e:
                    obj.update(result='mismatches', reason=str(e))
                except Exception as e:
                    obj.update(result='not replayable', reason=f'{type(e).__name__}: {e}; blocking import or cell as reported by exception, otherwise NOT ESTABLISHED')
                row['entries'].append(obj)
        except ValueError as e:
            row['reason'] = f'layout NOT ESTABLISHED: {e}'
        output.append(row)
    return output


def run(args):
    from toyota_native_oracle import load_libraries
    from convert_vehicle_packs import load_sources
    with gzip.open(args.systems, 'rt', encoding='utf8') as f: rows = [json.loads(line) for line in f if line.strip()]
    rows = selected(rows)
    regions = {r: FunctionBlocks((args.decoded / f'FUNCDATA_{r}.BIN').read_bytes()) for r in ('EU', 'NA', 'JP')}
    counts = census(rows, regions)
    sources = load_sources(args.archive, args.decoded, args.manifest)
    results = ecmect(regions, EntryIndex(sources['SYSTEMENTER.BIN']), load_libraries(args.archive), sources)
    slots = []
    for slot, hist in counts.items():
        ordered = sorted(hist.items(), key=lambda x: (-x[1], x[0]))
        parsed = [(k, v) for k, v in ordered if 'recordSkeleton' not in json.loads(k)]
        slots.append({'slot': slot, 'category': LABELS[slot], 'evidence': CITES[slot],
                      'systemsWithBlock': sum(hist.values()), 'distinctObservedSkeletons': len(parsed) if slot <= 3 else None,
                      'unparsedSystems': sum(v for k, v in ordered if 'recordSkeleton' in json.loads(k)),
                      'spanLengthBuckets': len(hist) if slot > 3 else None,
                      'top10': [{'shape': json.loads(k), 'systems': v} for k,v in ordered[:10]],
                      'otherSystems': sum(v for _,v in ordered[10:]),
                      'histogram': [{'shape': json.loads(k), 'systems': v} for k,v in ordered]})
    return {'headSha': os.environ.get('GITHUB_SHA'), 'systems': len(rows), 'slots': slots, 'ecmect': results}


def markdown(data):
    out = ['# Stage 4a schema probe', '', f"Head: `{data['headSha']}`; proven transport-1 K-Line systems: {data['systems']:,}. ECMECT is separately pinned, not in the 39,363.", '',
           'Investigation only; no category enabled. Histograms are system-weighted, not unique-function counts. Unknown slots have span-length buckets only, not inferred record shapes. Distinct skeletons exclude unparsed spans; see JSON unparsedSystems.', '',
           '| slot | category evidence | non-null systems | distinct skeletons | top ten shapes and system counts | other systems |', '|---:|---|---:|---:|---|---:|']
    for x in data['slots']:
        top = '<br>'.join(f"`{json.dumps(p['shape'], separators=(',',':'))}`: {p['systems']}" for p in x['top10']) or 'none'
        distinct = x['distinctObservedSkeletons'] if x['distinctObservedSkeletons'] is not None else 'NOT ESTABLISHED'
        out.append(f"| {x['slot']} | {x['category']} ({x['evidence']}) | {x['systemsWithBlock']} | {distinct} | {top} | {x['otherSystems']} |")
    out += ['', '## Field evidence', '', '- Directory pointers and bounds ESTABLISHED: `toyota_structure.py:275-287`; null sentinel and span ESTABLISHED: `toyota_functions.py:148-161`.',
            '- Slot 1 fault counts/queries/mappings ESTABLISHED: `toyota_functions.py:79-114`; slot 2 clear count/queries ESTABLISHED: `toyota_functions.py:117-145`.',
            '- Slot 3 live row and length-prefixed command skeleton ESTABLISHED: `toyota_live.py:185-206`.',
            '- Actuator, coding and special-function category assignment, record names, parameters, security/seed-key marker and algorithm: NOT ESTABLISHED for every slot. Frame request bytes resolved through `toyota_frames.py:1-5` are not operation authorization.', '', '## ECMECT pinned source', '']
    for row in data['ecmect']:
        out.append(f"### Slot {row['slot']} — {row['category']}; {row['source']}, {row['spanBytes']} bytes")
        if row.get('reason'): out.append('- ' + row['reason'])
        if not row['entries'] and not row.get('reason'): out.append('- No framed command records in this block.')
        for item in row['entries']:
            out.append('- `' + json.dumps(item, ensure_ascii=True, separators=(',', ':')) + '`')
        out.append('')
    out += ['## Enabling blockers', '', 'The requested three categories cannot be ranked by systems unlocked: category-to-slot assignment, their record layouts and native operation replay are NOT ESTABLISHED. No inferred unlock counts, requests, frames or security algorithm.', '']
    return '\n'.join(out)


def main():
    p = argparse.ArgumentParser()
    for name in ('archive','decoded','manifest','systems','output'):
        p.add_argument('--'+name, required=True, type=Path)
    args = p.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    def timeout(_sig, _frame): raise TimeoutError('Stage 4a probe exceeded 530 seconds')
    signal.signal(signal.SIGALRM, timeout); signal.alarm(530)
    try:
        result = run(args); text = markdown(result); status = 0
    except Exception:
        error = traceback.format_exc(); result = {'headSha': os.environ.get('GITHUB_SHA'), 'error': error}
        text = '# Stage 4a schema probe — FAILED\n\n```text\n' + error + '\n```\n'; status = 1
    finally: signal.alarm(0)
    (args.output / 'stage-4a-probe.md').write_text(text)
    (args.output / 'stage-4a-probe.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(text)
    return status

if __name__ == '__main__': raise SystemExit(main())
