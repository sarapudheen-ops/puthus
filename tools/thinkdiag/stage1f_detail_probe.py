#!/usr/bin/env python3
"""Stage 1f: measure the exact Stage 1c detail-row rejection, fail closed."""
import argparse
from collections import Counter, defaultdict
import json
import os
from pathlib import Path
import signal
import struct
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
        details = default_detail_rows(source, rep['region'], key[1], 1)
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
            'stage1cControl': stage1c['ecmectControl']}


def report(data):
    counts = Counter(); groupcounts = Counter(); oracle = Counter()
    for g in data['groups']:
        counts[g['class']] += g['systems']; groupcounts[g['class']] += 1
        if g['oracle']: oracle[g['oracle']['verdict']] += g['systems']
    lines = ['# Stage 1f function-detail rejection', '', f"Head `{data['headSha']}`; read-only; no new systems or DTCs enabled.", '',
        'Reject origin: `toyota_runtime_compile.py:54-63`: no default rows OR duplicate detail IDs. Several distinct detail rows are not themselves a reject. Grouping and representative follow `stage1c_group_oracle.py:101-128`.', '',
        'Candidate row count is identical for every system in the group: the exact region/function/main-category=1 source filter is shared; every system row identity is checked. JSON gives every system ID, candidate row and native evidence.', '',
        '| class | groups | systems |', '|---|---:|---:|']
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
    for name in ('archive','decoded','manifest','english','systems','stage1c','packs','output'):
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
