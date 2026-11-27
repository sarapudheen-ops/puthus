#!/usr/bin/env python3
"""Stage 1f: measure the exact Stage 1c detail-row rejection, fail closed."""
import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import traceback

from parameterized_dtc_probe import VerifiedSources, stream_eligible, system_row, CONTROL_SYSTEM
from stage1c_group_oracle import compile_libraries, container_bytes
from toyota_category_translation import normalize_ecu_file
from toyota_category_translation_v2 import parameterized_dtc_group
from toyota_dtc_workflow import default_detail_rows, default_add_info_rows
from toyota_native_workflow import _sandbox, FDATA_SHA256

REJECT = 'ValueError:No unambiguous exact DTC function-detail source rows'


def group_rows(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(normalize_ecu_file(row['region'], row.get('ecuFile')),
                row['functionKey'], row['entrySelector'])].append(row)
    return groups


def classify(n):
    return 'zero rows' if n == 0 else ('exactly one row' if n == 1 else 'several rows')


def candidate_rows(source, decoded, row):
    """The same exact join for controls and rejected systems."""
    raw = system_row(decoded, row['region'], row['id'])
    if (int.from_bytes(raw[:4], 'little'), raw[27]) != (row['functionKey'], row['entrySelector']):
        raise ValueError(f"system identity mismatch {row['id']}")
    return default_detail_rows(source, row['region'], row['functionKey'], 1)


def controls(source, decoded, ledger):
    from toyota_innova_2007_reads import SYSTEM as INNOVA_SYSTEM
    by_id = {r['id']: r for r in ledger}
    if len(by_id) != len(ledger): raise ValueError('duplicate system identities in ledger')
    chosen = {
        'ECMECT': [by_id[CONTROL_SYSTEM]], 'Innova': [by_id[INNOVA_SYSTEM]],
        'Stage 1d EU 0x11': [r for r in ledger if r.get('region') == 'EU'
            and r.get('entrySelector') == 0x11 and (r.get('groupDtcStatus') or {}).get('status') == 'enabled'],
        'Stage 1d JP 0x11': [r for r in ledger if r.get('region') == 'JP'
            and r.get('entrySelector') == 0x11 and (r.get('groupDtcStatus') or {}).get('status') == 'enabled'],
    }
    if [len(chosen[k]) for k in chosen] != [1, 1, 78, 30]:
        raise ValueError('control population differs from pinned 1, 1, 78, 30: ' + str({k:len(v) for k,v in chosen.items()}))
    result = {}
    for label, rows in chosen.items():
        per_system = [{'id': r['id'], 'rows': len(candidate_rows(source, decoded, r))} for r in rows]
        result[label] = {'systems': len(rows), 'rowsHistogram': dict(sorted(Counter(x['rows'] for x in per_system).items())),
                         'perSystem': per_system}
    return result


def object_bytes(directory, path, identity):
    from vehicle_bundle import read_object
    index = json.loads((directory / 'index.json').read_text())
    container = directory / index['container']
    page = read_object(container, index['catalogRoot'])
    for name in path:
        page = read_object(container, next(c['object'] for c in page['children'] if c['name'] == name))
    manual = read_object(container, page['systemsSource'])
    ref = next(x['object'] for x in manual['systems'] if x['id'] == identity)
    value = read_object(container, ref)
    if value.get('id') != identity: raise ValueError('pack object identity differs')
    with container.open('rb') as f:
        f.seek(ref['offset']); packed = f.read(ref['bytes'])
    if hashlib.sha256(packed).hexdigest() != ref['sha256']: raise ValueError('compressed object hash mismatch')
    raw = gzip.decompress(packed)
    return {'compressedSha256': hashlib.sha256(packed).hexdigest(), 'jsonSha256': hashlib.sha256(raw).hexdigest(),
            'compressedBytes': len(packed), 'jsonBytes': len(raw)}


def compare_pinned_objects(current, baseline):
    from toyota_innova_2007_reads import SYSTEM as INNOVA_SYSTEM
    prefix = ('Toyota', 'Kijang Innova')
    paths = {'ECMECT': (prefix + ('TGN · 2009.07-2017.07', 'MINI VAN · w/ VSC (Vehicle Stability Control)'), CONTROL_SYSTEM),
             'Innova': (prefix + ('TGN · -2009.07', 'MINI VAN · w/o VSC (Vehicle Stability Control)'), INNOVA_SYSTEM)}
    result = {}
    for name, (path, identity) in paths.items():
        b = object_bytes(baseline, path, identity); c = object_bytes(current, path, identity)
        result[name] = {'identity': identity, 'baseline': b, 'promoted': c,
                        'byteIdentical': b['compressedSha256'] == c['compressedSha256'] and b['jsonSha256'] == c['jsonSha256']}
    return result


def download_baseline(directory):
    run = '45941071218'
    sha = '1b7aaf1c666effea7bfd62d83b7c66427c1b02b9'
    output = subprocess.check_output(['gh', 'run', 'view', run, '--json', 'headSha,headBranch,event,status,conclusion'], text=True)
    meta = json.loads(output)
    if (meta['headSha'], meta['headBranch'], meta['event'], meta['status'], meta['conclusion']) != (sha, 'main', 'push', 'completed', 'success'):
        raise ValueError(f'baseline run identity/conclusion mismatch: {meta}')
    subprocess.run(['gh', 'run', 'download', run, '--name', 'toyota-vehicle-packs', '--dir', str(directory)], check=True)
    return {'run': run, 'headSha': sha}


def unconditional_native(libraries, sources, region, function, rows):
    """Replay native row loading only when it needs no invented ECU support reply."""
    if any(r['commandCondition'] for r in rows):
        return {'verdict': 'not replayable', 'reason': 'CmdCheckSupport command condition requires unestablished ECU state'}
    if any(r['supportMode'] and r['frameId'] for r in rows):
        return {'verdict': 'not replayable', 'reason': 'GetSupportBit requires ECU-dependent reply; source fields alone do not determine support'}
    extras = [a for r in rows for a in default_add_info_rows(sources, region, function, r['detailId'])]
    if any(a['commandCondition'] for a in extras):
        return {'verdict': 'not replayable', 'reason': 'add-info CmdCheckSupport requires unestablished ECU state'}
    name = 'libTOYOTA_FDATA.so'
    try:
        n = _sandbox(libraries, name, FDATA_SHA256)
        tables = n.allocate(256); context = n.allocate(0x18000); out = n.allocate(4)
        for off, suffix in ((0x70, '1B'), (0x78, '1C')):
            raw = sources[f'{region}/Toyota_bin_{suffix}.BIN']
            n.write(tables + off, struct.pack('<Q', n.allocate(len(raw), raw)))
        def support(_function, mode, frame, result_byte, supported, _expected, _minimum, runtime):
            if runtime != context or (mode and frame): raise ValueError('unexpected conditional GetSupportBit')
            n.write(supported, struct.pack('<I', 1)); n.write(result_byte, b'\x00'); return 0
        n.bind('GetSupportBit', support, library=name)
        n.write(context + 0x1059e, b'\x00'); n.write(context + 0x10604, b'\x00')
        status = n.call(name, 'EcuFuncListDetailsTable', function, 1, out, tables, context)
        count = n.read(context + 0x1059e, 1)[0]
        actual = list(struct.unpack('<' + 'H' * count, n.read(context + 0x105a0, 2 * count)))
        expected = [r['detailId'] for r in rows]
        if status != 0 or actual != expected:
            return {'verdict': 'disagree', 'reason': 'native/source detail list differs', 'nativeStatus': status,
                    'nativeDetailIds': actual, 'sourceDetailIds': expected}
        if len(actual) != 1:
            return {'verdict': 'disagree', 'reason': 'native loader returns multiple details, not exactly one; no single-row selection rule',
                    'nativeDetailIds': actual, 'sourceDetailIds': expected}
        return {'verdict': 'agree', 'reason': 'native unconditional detail list has exactly one element', 'nativeDetailIds': actual}
    except Exception as e:
        return {'verdict': 'not replayable', 'reason': f'{type(e).__name__}: {e}'}


def collect(args):
    from toyota_native_oracle import load_libraries
    from toyota_group_dtc_enable import TARGETS
    source = VerifiedSources(args.decoded, json.loads(args.manifest.read_text()))
    libraries = load_libraries(args.archive)
    codes = json.loads((args.english / 'TCODE_EN.GAG.json').read_text())
    texts = json.loads((args.english / 'TEXT_EN.GAG.json').read_text())
    texts = compile_libraries(codes, texts)
    _, eligible = stream_eligible(args.systems)
    with gzip.open(args.systems, 'rt', encoding='utf-8') as stream:
        ledger = [json.loads(line) for line in stream if line.strip()]
    control_results = controls(source, args.decoded, ledger)
    if any(int(n) == 0 and count for group in control_results.values() for n, count in group['rowsHistogram'].items()):
        raise ValueError('control has zero detail rows: join candidate_rows -> default_detail_rows requires investigation; reject census not reported: ' + json.dumps(control_results, sort_keys=True))
    baseline_meta = download_baseline(args.baseline)
    comparisons = compare_pinned_objects(args.packs, args.baseline)
    if not all(item['byteIdentical'] for item in comparisons.values()):
        raise ValueError('pinned objects differ from main baseline: ' + json.dumps(comparisons, sort_keys=True))
    groups = group_rows(eligible); rejected = []; all_rejects = Counter(); systems_by_reason = Counter()
    for key, members in sorted(groups.items(), key=lambda x: (str(x[0][0]), x[0][1], x[0][2])):
        rep = next((r for r in members if r.get('id') == CONTROL_SYSTEM), members[0])
        try:
            raw = system_row(args.decoded, rep['region'], rep['id'])
            if (int.from_bytes(raw[:4], 'little'), raw[27]) != (rep['functionKey'], rep['entrySelector']):
                raise ValueError('system_row_identity_mismatch')
            result = parameterized_dtc_group(source, texts, region=rep['region'], system_id=rep['id'],
                                             system_bytes=raw, native_transport=rep['nativeTransport'])
            reason = result['rejectReason']
        except Exception as e:
            reason = f'{type(e).__name__}:{e}'
        if reason:
            all_rejects[reason] += 1; systems_by_reason[reason] += len(members)
        if reason != REJECT: continue
        regions = {r['region'] for r in members}
        if regions != {rep['region']}: raise ValueError(f'group spans regions {key}')
        for r in members:
            raw = system_row(args.decoded, r['region'], r['id'])
            if (int.from_bytes(raw[:4], 'little'), raw[27]) != (key[1], key[2]):
                raise ValueError(f"system identity mismatch {r['id']}")
        details = candidate_rows(source, args.decoded, rep)
        n = len(details); verdict = None
        if n > 1: verdict = unconditional_native(libraries, source, rep['region'], key[1], details)
        if verdict and verdict['verdict'] == 'agree':
            raise ValueError('unexpected exactly-one native result from several source rows: enablement requires separate full gating')
        rejected.append({'group': list(key), 'region': rep['region'], 'representative': rep['id'],
                         'systems': len(members), 'systemIds': [r['id'] for r in members],
                         'candidateRowsPerSystem': n, 'class': classify(n), 'detailRows': details,
                         'oracle': verdict, 'enabled': False,
                         'literalReject': reason, 'existingEnableTarget': (rep['region'], key[0], key[1], key[2]) in TARGETS})
    if len(rejected) != 137 or sum(x['systems'] for x in rejected) != 24832:
        raise ValueError(f"Stage 1c reject control changed: {len(rejected)} groups / {sum(x['systems'] for x in rejected)} systems")
    stage1c = json.loads(args.stage1c.read_text())
    if stage1c.get('headSha') != os.environ['GITHUB_SHA'] or not stage1c.get('ecmectControl', {}).get('passed'):
        raise ValueError('same-SHA Stage 1c ECMECT control is missing or failed')
    container = container_bytes(args.packs)
    if not container.get('under480MiB'): raise ValueError(f'container missing or not under 480 MiB: {container}')
    return {'headSha': os.environ['GITHUB_SHA'], 'groups': rejected,
            'rankedRejects': [{'reason': k, 'groups': all_rejects[k], 'systems': systems_by_reason[k]}
                              for k in sorted(all_rejects, key=lambda k: (-systems_by_reason[k], k))],
            'container': container, 'newEnabledSystems': 0, 'newEnabledDtcs': 0,
            'stage1cControl': stage1c['ecmectControl'], 'detailControls': control_results,
            'baselineRun': baseline_meta, 'pinnedObjectComparison': comparisons}


def report(data):
    counts = Counter(); groupcounts = Counter(); oracle = Counter()
    for g in data['groups']:
        counts[g['class']] += g['systems']; groupcounts[g['class']] += 1
        if g['oracle']: oracle[g['oracle']['verdict']] += g['systems']
    lines = ['# Stage 1f function-detail rejection', '', f"Head `{data['headSha']}`; read-only; no new systems or DTCs enabled.", '',
        'Reject origin: `toyota_runtime_compile.py:54-63`: no default rows OR duplicate detail IDs. Several distinct detail rows are not themselves a reject. Grouping and representative follow `stage1c_group_oracle.py:101-128`.', '',
        'Candidate row count is identical for every system in the group: the exact region/function/main-category=1 source filter is shared; every system row identity is checked. JSON gives every system ID, candidate row and native evidence.', '']
    if 'detailControls' in data:
        lines += ['## Same-join positive controls', '', '| control | systems | candidate-row count → systems |', '|---|---:|---|']
        for name, item in data['detailControls'].items():
            histogram = ', '.join(f'{n} → {v}' for n, v in item['rowsHistogram'].items())
            lines.append(f"| {name} | {item['systems']} | {histogram} |")
        lines += ['', 'All controls have non-zero rows. Both controls and rejected groups use `candidate_rows` and `default_detail_rows` (`toyota_dtc_workflow.py:209-236`), filtering by exact region, function and main category 1.', '',
                  '## Pinned promoted pack objects versus main baseline', '',
                  f"Baseline run: {data['baselineRun']['run']}, head `{data['baselineRun']['headSha']}`. Hashes cover the exact compressed object bytes and the exact decompressed JSON bytes.", '',
                  '| object | baseline compressed SHA-256 | promoted compressed SHA-256 | baseline JSON SHA-256 | promoted JSON SHA-256 | identical |', '|---|---|---|---|---|---|']
        for name, item in data['pinnedObjectComparison'].items():
            b, c = item['baseline'], item['promoted']
            lines.append(f"| {name} | `{b['compressedSha256']}` | `{c['compressedSha256']}` | `{b['jsonSha256']}` | `{c['jsonSha256']}` | {item['byteIdentical']} |")
    lines += ['', '## Rejected-group classes', '', '| class | groups | systems |', '|---|---:|---:|']
    for c in ('zero rows','exactly one row','several rows'):
        lines.append(f'| {c} | {groupcounts[c]} | {counts[c]} |')
    lines += [f"| total | {len(data['groups'])} | {sum(counts.values())} |", '',
        '## Native selection evidence', '',
        '`libTOYOTA_FDATA.so@0001570c#EcuFuncListDetailsTable` loads a list, not necessarily one row; `toyota_native_workflow.py:142-195` compares the native list under synthetic support decisions. DTCF `libTOYOTA_DTCF.so@00012fc4#ReadDtcModeP3P4` tests detail-ID membership, not a single-row tie-break (`toyota_native_workflow.py:62-135`; `toyota_dtc_workflow.py:1-7,30-43`).',
        'Support may require actual ECU replies (`libTOYOTA_FDATA.so@0001532c#GetSupportBit`; `toyota_dtc_workflow.py:94-117`). No selection rule from static fields alone is established for conditional rows. Unconditional native loader replay does not establish real ECU applicability or full DTC operation.', '',
        '| group (ECU, function, selector) | systems | rows per system | class | oracle | literal reason |', '|---|---:|---:|---|---|---|']
    for g in data['groups']:
        o=g['oracle'] or {'verdict':'not applicable','reason':'no several-row candidate to select'}
        key=g['group']; lines.append(f"| `{key[0]}`, {key[1]}, 0x{key[2]:02x} | {g['systems']} | {g['candidateRowsPerSystem']} | {g['class']} | {o['verdict']} | {o['reason']} |")
    lines += ['', f"Native several-row systems: agree {oracle['agree']}; disagree {oracle['disagree']}; not replayable {oracle['not replayable']}.",
        'No group has passed the required single-row rule and native per-system agreement; no speculative ties or enablement. Existing read-only group pins, per-system hashes and write guards remain unchanged; clear remains unresolved.',
        f"New enabled systems: {data['newEnabledSystems']}; new enabled DTCs: {data['newEnabledDtcs']}. Container: {data['container']['bytes']} bytes; under 480 MiB: {data['container']['under480MiB']}.", '',
        '## Remaining rejects ranked by affected systems', '', '| rank | literal reason | groups | systems |', '|---:|---|---:|---:|']
    for i, r in enumerate(data['rankedRejects'], 1):
        lines.append(f"| {i} | `{r['reason']}` | {r['groups']} | {r['systems']} |")
    return '\n'.join(lines)+'\n'


def main():
    p=argparse.ArgumentParser()
    for name in ('archive','decoded','manifest','english','systems','stage1c','packs','baseline','output'):
        p.add_argument('--'+name, required=True, type=Path)
    a=p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
    def timeout(_sig, _frame): raise TimeoutError('Stage 1f probe exceeded 530 seconds')
    signal.signal(signal.SIGALRM, timeout); signal.alarm(530)
    try:
        data=collect(a); md=report(data); status=0
    except Exception:
        error=traceback.format_exc(); data={'headSha':os.environ.get('GITHUB_SHA'),'error':error}
        md='# Stage 1f probe failed\n\n```text\n'+error+'\n```\n'; status=1
    finally: signal.alarm(0)
    (a.output/'stage-1f-probe.json').write_text(json.dumps(data, indent=2, sort_keys=True)+'\n')
    (a.output/'stage-1f-probe.md').write_text(md)
    print(md)
    return status

if __name__ == '__main__': raise SystemExit(main())
