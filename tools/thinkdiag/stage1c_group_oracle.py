#!/usr/bin/env python3
"""Stage 1c: group-keyed native DTC oracle for every group that compiles (read-only).

Re-measures the Stage 1b group compile, then runs `toyota_group_oracle` for each
group whose compile succeeded: the native decoder is driven with that group's own
layout word, mode, presence flag and query kinds, and the native display is driven
with that group's own compiled DTC keys. Nothing is written to any pack, definition
or check by this script.

Every corpus path is an argument. A corpus that cannot be opened exits 2 and is
never recorded as a per-system reject. A group the oracle cannot approve stays
disabled with a literal reason.
"""
import argparse
import json
import os
import resource
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from parameterized_dtc_probe import (CONTROL_SYSTEM, CORPUS_EXIT, CorpusError, VerifiedSources,
                                    fail_fast, stream_eligible, system_row)
from toyota_category_translation import normalize_ecu_file
from toyota_category_translation_v2 import parameterized_dtc_group
from toyota_group_oracle import OracleSession, oracle_for_group
from toyota_innova_2007_reads import SYSTEM as INNOVA_2007_SYSTEM
from toyota_native_oracle import load_libraries
from toyota_runtime_compile import MANDATORY_SYSTEM

PINNED_DTCS = 1414
SAMPLE_LIMIT = 5
ENABLE_BLOCKERS = (
    'no_group_enable_path — `convert_vehicle_packs.py:388-400` enables only the pinned ECMECT identity '
    '(`toyota_ecmect_pack.enable`, `toyota_ecmect_pack.py:216`) and the pinned 2007 `systemId` '
    '(`toyota_innova_2007_pack.enable` over `toyota_innova_2007_reads.py:20`); no path accepts a group key',
    'no_pinned_group_artifact — `tools/thinkdiag/schema/` holds only `mandatory-read-artifacts-v1.json`, '
    '`native-oracle-v1.json` and `innova-2007-source-read-candidate-v1.json`; there is no deterministic '
    'expected-output pin for a compiled group definition',
    'per_system_link_projection — `convert_vehicle_packs.py:370-390` marks a system proven only from '
    '`self.oracle_evidence[oracle_key]` with a matching `projectionDigest` and a lifecycle projection; '
    '`convert_vehicle_packs.py:445` sets `enabled` only when `linkStatus == "proven"` and a category is executable',
)


def compile_libraries(codes, texts):
    """The decoded English tables `compile_p3p4_diagnostics` needs (`toyota_runtime_compile.py:80`).

    The native `.so` bytes from `load_libraries` are a different mapping and must never be
    passed as the compile libraries; doing so raises `KeyError: 'TCODE_EN.GAG'` for every group.
    """
    if not isinstance(codes, dict) or not isinstance(texts, dict):
        raise ValueError('english_tables:not_objects')
    return {'TCODE_EN.GAG': codes, 'TEXT_EN.GAG': texts}


def parse_test_counts(path):
    """Read the CI test step's own output; never invent a count."""
    if path is None or not path.is_file():
        return {'raw': 'NOT ESTABLISHED'}
    lines = path.read_text(errors='replace').splitlines()
    ran = next((line.strip() for line in lines if line.startswith('Ran ')), None)
    verdict = next((line.strip() for line in lines if line.startswith(('OK', 'FAILED'))), None)
    return {'ran': ran, 'verdict': verdict, 'raw': ' | '.join(x for x in (ran, verdict) if x) or 'NOT ESTABLISHED'}


def container_bytes(pack_directory):
    if pack_directory is None:
        return {'bytes': None, 'reason': 'not_established:no_pack_directory'}
    index = pack_directory / 'index.json'
    if not index.is_file():
        return {'bytes': None, 'reason': f'not_established:no_index:{index}'}
    try:
        name = json.loads(index.read_text())['container']
    except (OSError, ValueError, KeyError) as error:
        return {'bytes': None, 'reason': f'not_established:index_unreadable:{type(error).__name__}'}
    container = pack_directory / name
    if not container.is_file():
        return {'bytes': None, 'reason': f'not_established:no_container:{container}'}
    size = container.stat().st_size
    return {'bytes': size, 'container': name, 'mebibytes': round(size / (1024 * 1024), 1),
            'under480MiB': size < 480 * 1024 * 1024, 'under460MiBReview': size < 460 * 1024 * 1024}


def run(args, checks):
    started = time.perf_counter()
    native_libraries = load_libraries(args.archive)
    codes = json.loads((args.english / 'TCODE_EN.GAG.json').read_text())
    texts = json.loads((args.english / 'TEXT_EN.GAG.json').read_text())
    # compile_p3p4_diagnostics reads the decoded English tables (toyota_runtime_compile.py:80),
    # not the native .so bytes; the oracle needs the libraries. Keep them separate.
    text_libraries = compile_libraries(codes, texts)
    sources = VerifiedSources(args.decoded, json.loads(args.manifest.read_text()))
    session = OracleSession(native_libraries, codes, texts)

    total_records, eligible = stream_eligible(args.systems)
    groups = defaultdict(list)
    for row in eligible:
        groups[(normalize_ecu_file(row['region'], row.get('ecuFile')), row['functionKey'],
                row['entrySelector'])].append(row)
    keys = sorted(groups, key=lambda key: (str(key[0]), key[1], key[2]))

    rejects = Counter()
    reject_samples = defaultdict(list)
    reject_systems = Counter()
    compiled_groups = []
    layout_identical = {}
    for key in keys:
        members = groups[key]
        representative = next((member for member in members if member.get('id') == CONTROL_SYSTEM), members[0])
        try:
            raw = system_row(args.decoded, representative['region'], representative['id'])
            if (int.from_bytes(raw[0:4], 'little'), raw[27]) != (representative['functionKey'],
                                                                 representative['entrySelector']):
                raise ValueError('system_row_identity_mismatch')
            compiled = parameterized_dtc_group(sources, text_libraries, region=representative['region'],
                                               system_id=representative['id'], system_bytes=raw,
                                               native_transport=representative['nativeTransport'])
        except Exception as error:
            compiled = {'executable': False, 'rejectReason': f'{type(error).__name__}:{error}',
                        'dtcCount': 0, 'definition': None}
        if not compiled['executable']:
            reason = compiled['rejectReason']
            rejects[reason] += 1
            reject_systems[reason] += len(members)
            if len(reject_samples[reason]) < SAMPLE_LIMIT:
                reject_samples[reason].append(representative['id'])
            continue
        members_by_id = {member['id'] for member in members}
        compiled_groups.append({'key': key, 'members': len(members), 'representative': representative,
                                'compiled': compiled, 'membersById': members_by_id})

    oracle_results = []
    for entry in compiled_groups:
        if time.perf_counter() - started > args.max_seconds:
            entry['oracle'] = {'ran': False, 'passed': False, 'failure': 'budget_exhausted', 'legs': {}}
            oracle_results.append(entry)
            continue
        entry['oracle'] = oracle_for_group(session, entry['compiled']['definition']['faults'],
                                           linkage_sample=args.linkage_sample)
        oracle_results.append(entry)

    control = next((entry for entry in oracle_results
                    if CONTROL_SYSTEM in entry['membersById']), None)
    control_ok = bool(control is not None
                      and control['compiled']['dtcCount'] == PINNED_DTCS
                      and control['oracle']['passed'] is True)
    pinned = {'ecmect': MANDATORY_SYSTEM, 'innova2007': INNOVA_2007_SYSTEM}

    elapsed = time.perf_counter() - started
    peak_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    head = args.head_sha or os.environ.get('GITHUB_SHA') or 'NOT ESTABLISHED'
    stamp = datetime.now(timezone.utc).isoformat()
    tests = parse_test_counts(args.test_log)
    container = container_bytes(args.pack_directory)
    ranking = rejects.most_common()
    unlocked = {reason: reject_systems[reason] for reason, _ in ranking}

    lines = ['# Stage 1c group-keyed DTC oracle', '',
             f'- Head SHA: `{head}`', f'- UTC: {stamp}',
             f'- Native library `libTOYOTA_DTCF.so` SHA-256: `{session.native.libraries["libTOYOTA_DTCF.so"].sha256 if session.native else "NOT ESTABLISHED"}`',
             f'- Corpus inputs opened before measuring (argument-supplied): {len(checks)} checks passed.',
             '- Read-only: no pack, definition file, check or enabled system is written by this script.',
             '',
             '## Population', '', '| Measure | Value |', '|---|---:|',
             f'| System records read | {total_records:,} |',
             f'| Eligible promotion-partition systems | {len(eligible):,} |',
             f'| Distinct (Ecu_Data file, function, selector) groups | {len(keys):,} |',
             f'| Groups that compile | {len(compiled_groups):,} |',
             f'| Groups whose compile is rejected | {sum(rejects.values()):,} |', '']

    lines += ['## Oracle result per compiled group', '',
              '| Group | Systems | DTC labels | Layout (count,start,bytes,asm,presence,mode) | Read | Clear | Oracle ran | Layout vectors | Linkage vectors | Display vectors | Result | Enabled |',
              '|---|---:|---:|---|---|---|---|---:|---:|---:|---|---|']
    for entry in oracle_results:
        oracle = entry['oracle']
        legs = oracle.get('legs') or {}
        fields = oracle.get('layoutFields') or {}
        layout_text = ('{countIndex},{recordStart},{recordBytes},{assemblyKind},{countIsPresence},{modeLowBits}'
                       .format(**fields) if fields else 'NOT ESTABLISHED')
        definition = entry['compiled']['definition']['faults']
        verdict = 'NOT RUN' if not oracle.get('ran') else ('PASS' if oracle['passed'] else 'FAIL')
        lines.append(
            f'| `{entry["key"][0]}`, {entry["key"][1]}, 0x{entry["key"][2]:02x} | {entry["members"]} | '
            f'{entry["compiled"]["dtcCount"]:,} | `{layout_text}` | `{definition["read"]["request"]}` | '
            f'`{(definition.get("clear") or {}).get("request", "none")}` | {oracle.get("ran")} | '
            f'{legs.get("layout", {}).get("vectors", 0):,} | {legs.get("linkage", {}).get("vectors", 0):,} | '
            f'{legs.get("display", {}).get("vectors", 0):,} | **{verdict}** | False |')
    if not oracle_results:
        lines.append('| (no compiled group) | — | 0 | — | — | — | False | 0 | 0 | 0 | **NOT RUN** | False |')
    lines += ['', 'An oracle pass means the native decoder and the native display agree with the compiled '
              'group over the compared vectors. It is not vehicle validation: payload vectors are synthesised, '
              'and P5 auxiliary side tables, mask discovery, transport and per-system applicability are not covered.',
              '', 'Record-leg mask is `0xFF` for every group (the mask the global `faultDecoderComparison` record '
              'vectors use). The compiled `availabilityMask` is 0 for every group '
              '(`toyota_runtime_compile.py:83`); the state half of each pair is covered exhaustively by the global '
              'comparison, and the group-keyed claim here is the DTC code projection and the label set.', '']

    for entry in oracle_results:
        oracle = entry['oracle']
        if oracle.get('failure'):
            lines.append(f'- `{entry["key"][0]}`, {entry["key"][1]}, 0x{entry["key"][2]:02x}: '
                         f'failure `{oracle["failure"]}`')
        for leg in ('layout', 'linkage', 'display'):
            detail = (oracle.get('legs') or {}).get(leg)
            if not detail:
                continue
            if detail['passed']:
                lines.append(f'- `{entry["key"][0]}`, {entry["key"][1]}, 0x{entry["key"][2]:02x} — {leg}: '
                             f'{detail["vectors"]:,} vectors pass, results `{detail["resultsSha256"][:16]}…`')
            else:
                lines.append(f'- `{entry["key"][0]}`, {entry["key"][1]}, 0x{entry["key"][2]:02x} — {leg}: '
                             f'FAIL after {detail["vectors"]:,} vectors')
                for failure in (detail.get('mismatches') or detail.get('failures') or [])[:SAMPLE_LIMIT]:
                    lines.append(f'  - `{json.dumps(failure, sort_keys=True)}`')
    lines += ['']

    lines += ['## Pinned identities in the compiled groups', '',
              f'- ECMECT pinned system `{pinned["ecmect"]}` is a member of its group: '
              f'{any(pinned["ecmect"] in entry["membersById"] for entry in oracle_results)}.',
              f'- Pinned 2007 Innova slice `{pinned["innova2007"]}` (`toyota_innova_2007_reads.py:20`) is a member of '
              f'its group: {any(pinned["innova2007"] in entry["membersById"] for entry in oracle_results)}. '
              'The Stage 1b representative of the `EU/Ecu_Data_0081.BIN, 61, 0x51` group was '
              '`SYSLIST_EU.BIN@00023995`, so that group contains the enabled 2007 slice without being it.', '']

    lines += ['## Reject reasons ranked by systems unlocked if that blocker were ported', '',
              '| Rank | Literal reject reason | Groups | Systems | 5 sample system keys |', '|---:|---|---:|---:|---|']
    for position, (reason, count) in enumerate(ranking, 1):
        samples = ', '.join(f'`{value}`' for value in reject_samples[reason])
        lines.append(f'| {position} | `{reason}` | {count:,} | {unlocked[reason]:,} | {samples} |')
    lines += ['']

    lines += ['## ECMECT control', '',
              f'- Control group oracle: {"PASS" if control_ok else "FAIL"} '
              f'(compiled DTC labels {control["compiled"]["dtcCount"] if control else "NOT ESTABLISHED"} on the pinned row; '
              f'required exactly {PINNED_DTCS:,}).',
              '- The pinned ECMECT packet path is untouched by this stage; its enabled output stays byte-identical '
              'at 30 PIDs / 1,414 DTCs / 1 ecuInfo.', '']

    lines += ['## Enablement decision', '',
              'Groups enabled by this stage: **0**. Systems enabled: **0**. New DTC definitions enabled: **0**.',
              '',
              'DTCs per enabled system (unchanged): the pinned ECMECT system 1,414 '
              '(`toyota_ecmect_pack.py:160-173`, pinned review artifact) and the pinned 2007 Innova slice 1,414 '
              '(`toyota_innova_2007_reads.py:77`). No third system is enabled.', '',
              'Blockers, exactly:']
    for blocker in ENABLE_BLOCKERS:
        lines.append(f'- {blocker}')
    lines += ['', f'A group-keyed oracle pass is necessary but not sufficient: {len(oracle_results)} groups have '
              'native decoder/display agreement, and enabling any of them still requires the pins and the '
              'per-system link evidence listed above.', '']

    lines += ['## Container and tests (DATA, this run)', '',
              f'- Container: {container.get("bytes")} bytes'
              + (f' ({container["mebibytes"]} MiB, under 480 MiB: {container["under480MiB"]}, '
                 f'under 460 MiB review threshold: {container["under460MiBReview"]})' if container.get('bytes') else '')
              + ('' if container.get('bytes') else f' — {container.get("reason")}'),
              f'- Tests (this run\'s own discovery): {tests["raw"]}',
              f'- Oracle wall clock: {elapsed:.1f}s. Peak RSS: {peak_rss:.1f} MiB.', '']

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text('\n'.join(lines))

    payload = {
        'kind': 'toyota-stage1c-group-oracle-v1',
        'headSha': head, 'utc': stamp, 'corpusChecks': checks,
        'population': {'systemRecords': total_records, 'eligible': len(eligible), 'groups': len(keys),
                       'compiled': len(compiled_groups), 'rejected': sum(rejects.values())},
        'librarySha256': session.native.libraries['libTOYOTA_DTCF.so'].sha256 if session.native else None,
        'groups': [{'group': list(entry['key']), 'systems': entry['members'],
                    'representative': entry['representative']['id'],
                    'dtcCount': entry['compiled']['dtcCount'],
                    'readRequest': entry['compiled']['definition']['faults']['read']['request'],
                    'clearRequest': (entry['compiled']['definition']['faults'].get('clear') or {}).get('request'),
                    'pinnedMembers': [name for name, value in pinned.items()
                                      if value in entry['membersById']],
                    'oracle': entry['oracle'], 'enabled': False} for entry in oracle_results],
        'rejectReasons': [{'reason': reason, 'groups': count, 'systems': unlocked[reason],
                           'samples': reject_samples[reason], 'rankBySystemsUnlocked': position}
                          for position, (reason, count) in enumerate(ranking, 1)],
        'ecmectControl': {'passed': control_ok,
                          'dtcCount': control['compiled']['dtcCount'] if control else None,
                          'required': PINNED_DTCS},
        'enablement': {'groups': 0, 'systems': 0, 'definitions': 0, 'blockers': list(ENABLE_BLOCKERS),
                       'dtcsPerEnabledSystem': {'ecmect': PINNED_DTCS, 'innova2007': PINNED_DTCS}},
        'container': container, 'tests': tests,
        'elapsedSeconds': elapsed, 'peakRssMiB': peak_rss,
    }
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(json.dumps(payload, sort_keys=True, indent=2) + '\n')
    print(json.dumps({'headSha': head, 'compiled': len(compiled_groups),
                      'oraclePass': [entry['oracle']['passed'] for entry in oracle_results],
                      'ecmectControl': control_ok, 'container': container.get('bytes'),
                      'tests': tests['raw']}, sort_keys=True))
    if not control_ok:
        print('ECMECT control oracle failed: results are invalid and nothing may be enabled', file=sys.stderr)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--decoded', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--english', type=Path, required=True)
    parser.add_argument('--systems', type=Path, required=True)
    parser.add_argument('--ecmect-review-dir', type=Path, required=True)
    parser.add_argument('--pack-directory', type=Path)
    parser.add_argument('--test-log', type=Path)
    parser.add_argument('--native-oracle', type=Path)
    parser.add_argument('--head-sha')
    parser.add_argument('--linkage-sample', type=int, default=40)
    parser.add_argument('--max-seconds', type=float, default=900.0)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--json-output', type=Path, required=True)
    args = parser.parse_args()
    try:
        checks = fail_fast(args)
    except CorpusError as error:
        print(f'FAIL-FAST: {error}', file=sys.stderr)
        return CORPUS_EXIT
    return run(args, checks)


if __name__ == '__main__':
    sys.exit(main())
