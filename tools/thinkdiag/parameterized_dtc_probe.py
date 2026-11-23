#!/usr/bin/env python3
"""Stage 1b group-based parameterized DTC probe (read-only, fail-closed).

One execution per distinct (Ecu_Data file, function, selector) group. Every corpus
path is supplied by the workflow as an argument; the probe fails fast with exit 2
when a required source cannot be opened, so an unopenable corpus or an unreadable
English table is never recorded as a per-system reject.

Two independent read-only measurements are reported:

* Mode A -- the existing strict pinned translator (`toyota_category_translation.translate_categories`),
  the same path the Stage 1 probe used. Only the exact pinned ECMECT system is executable.
* Mode B -- the Stage 1b parameterized P3/P4 DTC compile
  (`toyota_category_translation_v2.parameterized_dtc_group`, backed by
  `toyota_runtime_compile.compile_p3p4_diagnostics`, `toyota_runtime_compile.py:28`)
  attempted once per group on that group's representative source row.

No oracle is claimed for non-ECMECT DTC output: `faultDecoderComparison` /
`p5DecoderComparison` are decoder-level synthetic-vector comparisons
(`toyota_native_oracle.py:609`, `:726`) and are not keyed by group. A compiled
non-ECMECT group therefore stays a candidate translation; nothing is enabled and
no pack or definition file is written.
"""
import argparse
import gzip
import hashlib
import json
import os
import resource
import sys
import time
import zipfile
from collections import Counter, defaultdict
from functools import lru_cache
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from toyota_category_translation import CATEGORIES, normalize_ecu_file, translate_categories
from toyota_category_translation_v2 import NO_ORACLE, parameterized_dtc_group
from toyota_ecmect_pack import load_review
from toyota_stage1_probe import ecmect_entry, ecmect_reference, ecmect_source_lifecycle, strict_group_representative
from toyota_structure import system_lists

CONTROL_SYSTEM = 'SYSLIST_EU.BIN@0009d8f2'
CONTROL_COUNTS = {'pids': 30, 'dtcs': 1414, 'ecuInfo': 1}
PINNED_DTCS = 1414
SAMPLE_LIMIT = 5
CORPUS_EXIT = 2


class CorpusError(Exception):
    """The corpus or one of its required inputs cannot be opened."""


def require_file(path, label, checks):
    if not path.is_file():
        raise CorpusError(f'{label}:missing:{path}')
    size = path.stat().st_size
    if size == 0:
        raise CorpusError(f'{label}:empty:{path}')
    checks.append(f'{label}={path} ({size} bytes)')
    return path


def require_directory(path, label, checks):
    if not path.is_dir():
        raise CorpusError(f'{label}:missing_directory:{path}')
    checks.append(f'{label}={path}')
    return path


def read_json(path, label, checks, *, expect='dict'):
    try:
        value = json.loads(path.read_text())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CorpusError(f'{label}:unreadable:{path}:{type(error).__name__}') from error
    if expect == 'dict' and (not isinstance(value, dict) or not value):
        raise CorpusError(f'{label}:not_a_nonempty_object:{path}')
    if expect == 'list' and (not isinstance(value, list) or not value):
        raise CorpusError(f'{label}:not_a_nonempty_list:{path}')
    return value


def fail_fast(args):
    """Open every declared source before measuring anything. Never returns a partial truth."""
    checks = []
    require_file(args.archive, 'corpus_archive', checks)
    try:
        with zipfile.ZipFile(args.archive) as archive:
            names = archive.namelist()
    except (OSError, zipfile.BadZipFile) as error:
        raise CorpusError(f'corpus_archive:unopenable:{args.archive}:{type(error).__name__}') from error
    if not names:
        raise CorpusError(f'corpus_archive:no_members:{args.archive}')
    checks.append(f'corpus_archive_members={len(names)}')
    require_directory(args.decoded, 'decoded_directory', checks)
    require_directory(args.english, 'english_directory', checks)
    require_directory(args.ecmect_review_dir, 'ecmect_review_directory', checks)
    for name in ('SYSTEMENTER.BIN', 'FUNCDATA_EU.BIN', 'SYSLIST_EU.BIN', 'ECUPROGRAM.BIN',
                 'EU/Toyota_bin_00.BIN', 'EU/Toyota_bin_11.BIN', 'EU/Toyota_bin_12.BIN',
                 'EU/Toyota_bin_1B.BIN'):
        require_file(args.decoded / name, f'decoded:{name}', checks)
    for name in ('TCODE_EN.GAG', 'TEXT_EN.GAG'):
        table = read_json(args.english / (name + '.json'), f'english_table:{name}', checks)
        checks.append(f'english_table:{name}_records={len(table)}')
    manifest = read_json(args.manifest, 'decoded_manifest', checks, expect='list')
    checks.append(f'decoded_manifest_entries={len(manifest)}')
    for name in ('mandatory-ecmect-source-reads.json', 'mandatory-ecmect-diagnostics.json'):
        require_file(args.ecmect_review_dir / name, f'ecmect_review:{name}', checks)
    try:
        with gzip.open(args.systems, 'rt', encoding='utf-8') as stream:
            first = stream.readline()
    except (OSError, EOFError, gzip.BadGzipFile) as error:
        raise CorpusError(f'systems:unopenable:{args.systems}:{type(error).__name__}') from error
    if not first.strip():
        raise CorpusError(f'systems:no_records:{args.systems}')
    try:
        json.loads(first)
    except json.JSONDecodeError as error:
        raise CorpusError(f'systems:first_record_unparsable:{args.systems}') from error
    checks.append(f'systems={args.systems}')
    if args.native_oracle is not None and args.native_oracle.exists():
        checks.append(f'native_oracle={args.native_oracle} ({args.native_oracle.stat().st_size} bytes)')
    elif args.native_oracle is not None:
        checks.append(f'native_oracle=MISSING {args.native_oracle} (reported, not a reject)')
    return checks


class VerifiedSources(dict):
    """Decoded-source mapping that verifies each file against the decoded manifest on first read.

    `convert_vehicle_packs.load_sources` builds the same checked bytes eagerly for
    1,410 files. The probe only needs the handful of files the DTC compiler reads,
    so each is checksum-verified once, cached, and nothing is trusted by name only.
    """

    def __init__(self, decoded, manifest):
        super().__init__()
        self.decoded = decoded
        self.entries = {item['file']: item for item in manifest}
        self.reads = []

    def __missing__(self, name):
        item = self.entries.get(name)
        if item is None:
            raise KeyError(f'unlisted decoded source {name}')
        path = self.decoded / name
        raw = path.read_bytes()
        if len(raw) != item['bytes'] or hashlib.sha256(raw).hexdigest() != item['sha256']:
            raise ValueError(f'Decoded source checksum mismatch: {name}')
        self[name] = raw
        self.reads.append(name)
        return raw


def stream_eligible(path):
    """Stream the system ledger, retaining only the promotion partition."""
    total = 0
    eligible = []
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        for line in stream:
            if not line.strip():
                continue
            total += 1
            row = json.loads(line)
            if (row.get('nativeTransport') in (1, 5)
                    and row.get('linkStatus') in ('proven', 'unproven')):
                eligible.append({key: row.get(key) for key in (
                    'id', 'region', 'functionKey', 'entrySelector', 'ecuFile',
                    'nativeTransport', 'linkStatus')})
    return total, eligible


@lru_cache(maxsize=3)
def _framed_rows(path):
    """Framed SYSLIST rows keyed by their exact source address (37 bytes each)."""
    data = Path(path).read_bytes()
    _, blocks = system_lists(data)
    rows = {}
    for block_offset, block in blocks.items():
        for index, row in enumerate(block):
            if len(row) != 37:
                raise ValueError(f'unexpected_system_row_length:{len(row)}')
            rows[block_offset + 2 + 37 * index] = bytes(row)
    return rows


def system_row(decoded, region, system_id):
    """Return the exact framed 37-byte SYSLIST row addressed by a system id."""
    try:
        offset = int(system_id.split('@')[1], 16)
    except (IndexError, ValueError) as error:
        raise ValueError(f'malformed_system_id:{system_id}') from error
    rows = _framed_rows(str(decoded / f'SYSLIST_{region}.BIN'))
    if offset not in rows:
        raise ValueError(f'not_a_framed_system_row:{system_id}')
    return rows[offset]


def oracle_status(path):
    """Report decoder-level oracle evidence; never promote it to a per-group result."""
    base = {'groupKeyed': False, 'oracleStatus': NO_ORACLE,
            'source': 'toyota_native_oracle.py:609 compare_fault_decoder(libraries); '
                      ':726 compare_p5_decoder(libraries, sources)'}
    if path is None or not path.is_file():
        return {**base, 'available': False, 'reason': 'not_established:native_oracle_absent'}
    try:
        data = json.loads(path.read_text())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {**base, 'available': False, 'reason': 'not_established:native_oracle_unreadable'}
    detail = {}
    for key in ('faultDecoderComparison', 'p5DecoderComparison'):
        value = data.get(key)
        if isinstance(value, dict):
            detail[key] = {name: value.get(name) for name in (
                'nativeInstructionsExecuted', 'librarySha256', 'stateVectors',
                'recordVectors', 'sourceBlocks')}
    if not detail:
        return {**base, 'available': False, 'reason': 'not_established:decoder_comparisons_absent'}
    return {**base, 'available': True, 'reason': NO_ORACLE, 'decoderComparisons': detail}


def run(args, checks):
    started = time.perf_counter()
    review = load_review(args.ecmect_review_dir)
    control_system = ecmect_reference(args.ecmect_review_dir)
    exact_entry = ecmect_entry(args.decoded)
    control_lifecycle = ecmect_source_lifecycle(args.decoded)

    total_records, eligible = stream_eligible(args.systems)
    groups = defaultdict(list)
    for row in eligible:
        key = (normalize_ecu_file(row['region'], row.get('ecuFile')),
               row['functionKey'], row['entrySelector'])
        groups[key].append(row)
    group_keys = sorted(groups, key=lambda k: (str(k[0]), k[1], k[2]))

    libraries = {name: json.loads((args.english / (name + '.json')).read_text())
                 for name in ('TCODE_EN.GAG', 'TEXT_EN.GAG')}
    sources = VerifiedSources(args.decoded, json.loads(args.manifest.read_text()))

    mode_a = {category: Counter() for category in CATEGORIES}
    mode_a_reasons = Counter()
    mode_a_samples = defaultdict(list)
    compile_reasons = Counter()
    compile_samples = defaultdict(list)
    compile_counts = Counter()
    group_results = {}
    control = {}
    not_processed = {'groups': 0, 'systems': 0, 'reason': None}
    translate_errors = Counter()

    for position, key in enumerate(group_keys):
        elapsed = time.perf_counter() - started
        if elapsed > args.max_seconds:
            remaining = group_keys[position:]
            not_processed = {'groups': len(remaining),
                             'systems': sum(len(groups[k]) for k in remaining),
                             'reason': f'budget_exhausted:maxSeconds={args.max_seconds}:elapsedSeconds={elapsed:.1f}'}
            break
        members = groups[key]
        representative = strict_group_representative(members)
        control_member = representative.get('id') == CONTROL_SYSTEM

        # --- Mode A: existing strict pinned translator, per member (no I/O). ---
        try:
            outcome = translate_categories(
                representative['region'], representative['functionKey'],
                representative['entrySelector'], dict(representative, ecuFile=key[0]),
                ecmect_review=review,
                lifecycle=control_lifecycle if control_member else None,
                reference_data=control_system['data'] if control_member else None)
            representative_error = None
        except Exception as error:
            representative_error = f'{type(error).__name__}:{error}'
            translate_errors[type(error).__name__] += 1
        for member in members:
            if representative_error is not None:
                for category in CATEGORIES:
                    mode_a[category]['error_systems'] += 1
                continue
            if member.get('id') == CONTROL_SYSTEM and not control_member:
                member_outcome = translate_categories(
                    member['region'], member['functionKey'], member['entrySelector'],
                    member, ecmect_review=review)
            elif member is representative:
                member_outcome = outcome
            else:
                member_outcome = translate_categories(
                    member['region'], member['functionKey'], member['entrySelector'],
                    member, ecmect_review=review)
            for category in CATEGORIES:
                status = member_outcome['categories'][category]
                mode_a[category][status + '_systems'] += 1
                reason = member_outcome['reasons'].get(category)
                if reason:
                    mode_a_reasons[(category, reason)] += 1
                    if len(mode_a_samples[(category, reason)]) < SAMPLE_LIMIT:
                        mode_a_samples[(category, reason)].append(member['id'])
        if representative_error is not None:
            for category in CATEGORIES:
                mode_a[category]['error_groups'] += 1
        else:
            for category in CATEGORIES:
                mode_a[category][outcome['categories'][category] + '_groups'] += 1

        if control_member and representative_error is None:
            control['modeA'] = {
                'system': representative['id'],
                'groupId': list(key),
                'counts': {c: outcome['counts'].get(c) for c in ('pids', 'dtcs', 'ecuInfo')},
                'byteIdentical': outcome.get('ecmectControlByteIdentical'),
                'entry': exact_entry}

        # --- Mode B: parameterized P3/P4 DTC compile, once per group. ---
        try:
            raw = system_row(args.decoded, representative['region'], representative['id'])
            function = int.from_bytes(raw[0:4], 'little')
            selector = raw[27]
            if (function, selector) != (representative['functionKey'], representative['entrySelector']):
                raise ValueError(f'system_row_identity_mismatch:{function}/{selector}')
            result = parameterized_dtc_group(
                sources, libraries, region=representative['region'],
                system_id=representative['id'], system_bytes=raw,
                native_transport=representative['nativeTransport'])
        except Exception as error:
            result = {'executable': False, 'rejectReason': f'{type(error).__name__}:{error}',
                      'dtcCount': 0, 'readRequest': None, 'clearRequest': None,
                      'workflowQueries': 0, 'identificationCount': 0,
                      'oracleStatus': NO_ORACLE, 'definition': None}
        group_results[key] = {
            'representative': representative['id'], 'systems': len(members),
            'region': representative['region'],
            'nativeTransport': representative['nativeTransport'],
            'compiled': result['executable'], 'rejectReason': result['rejectReason'],
            'dtcCount': result['dtcCount'], 'readRequest': result['readRequest'],
            'clearRequest': result['clearRequest'], 'oracleStatus': result['oracleStatus']}
        if result['executable']:
            compile_counts['executable_groups'] += 1
            compile_counts['executable_systems'] += len(members)
            compile_counts['dtc_labels'] += result['dtcCount']
            if result['clearRequest']:
                compile_counts['groupsWithSourceClear'] += 1
        else:
            compile_counts['rejected_groups'] += 1
            compile_counts['rejected_systems'] += len(members)
            reason = result['rejectReason']
            compile_reasons[reason] += 1
            if len(compile_samples[reason]) < SAMPLE_LIMIT:
                compile_samples[reason].append(representative['id'])
        if control_member:
            control['parameterized'] = {
                'system': representative['id'], 'dtcCount': result['dtcCount'],
                'executable': result['executable'], 'rejectReason': result['rejectReason'],
                'readRequest': result['readRequest'],
                'clearRequest': result['clearRequest'],
                'matchesPinnedDtcCount': result['dtcCount'] == PINNED_DTCS}

    oracle = oracle_status(args.native_oracle)
    elapsed = time.perf_counter() - started
    peak_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024

    mode_a_control_valid = (control.get('modeA') is not None
                            and control['modeA']['byteIdentical'] is True
                            and control['modeA']['counts'] == CONTROL_COUNTS)
    control_status = 'passed' if mode_a_control_valid else 'failed'
    partial = not_processed['groups'] > 0
    head = args.head_sha or os.environ.get('GITHUB_SHA') or 'NOT ESTABLISHED'
    stamp = datetime.now(timezone.utc).isoformat()

    lines = ['# Stage 1b parameterized DTC probe', '',
             f'- Head SHA: `{head}`', f'- UTC: {stamp}',
             f'- Corpus inputs (argument-supplied, opened before measuring): {len(checks)} checks passed.',
             '- No write functions, no pack output, no definition file: this probe only reads decoded sources.',
             '']

    partition = Counter()
    for row in eligible:
        if row.get('id') == CONTROL_SYSTEM:
            partition['pinnedEcmect'] += 1
        elif row.get('nativeTransport') == 5:
            partition['transport5Unproven'] += 1
        else:
            partition['transport1Proven'] += 1
    lines += ['## Population and execution', '',
              '| Measure | Value |', '|---|---:|',
              f'| System records read from `systems.jsonl.gz` | {total_records:,} |',
              f'| Eligible promotion-partition systems | {len(eligible):,} |',
              f'| Partition: transport-1 proven | {partition["transport1Proven"]:,} |',
              f'| Partition: exact pinned ECMECT | {partition["pinnedEcmect"]:,} |',
              f'| Partition: transport-5 unproven | {partition["transport5Unproven"]:,} |',
              f'| Distinct (Ecu_Data file, function, selector) groups | {len(group_keys):,} |',
              f'| Groups executed | {len(group_keys) - not_processed["groups"]:,} |',
              f'| Systems beyond one representative per group | {sum(max(0, len(v) - 1) for v in groups.values()):,} |',
              '']

    if partial:
        lines += [f'**PARTIAL — {not_processed["reason"]}.** '
                  f'{not_processed["groups"]} groups / {not_processed["systems"]:,} systems were not measured. '
                  'Unmeasured groups are reported here, never as a per-system reject.', '']

    lines += ['## ECMECT control (1,414 DTCs)', '']
    if control.get('modeA'):
        mode_a_control = control['modeA']
        lines += [f'- Exact parsed ECMECT entry: `{mode_a_control["entry"]}` (system `{mode_a_control["system"]}`).',
                  f'- Mode A strict pinned translator counts: {mode_a_control["counts"]}; '
                  f'byte-identical to the enabled-pack output: **{mode_a_control["byteIdentical"]}**.',
                  f'- Mode A control status: **{control_status}**.']
    else:
        lines += ['- Mode A control group was not reached: NOT ESTABLISHED.']
    param_control = control.get('parameterized')
    if param_control:
        lines += [f'- Parameterized P3/P4 compile on the same pinned row (`{param_control["system"]}`): '
                  f'executable={param_control["executable"]}, DTC labels={param_control["dtcCount"]}, '
                  f'read request={param_control["readRequest"]}, clear request={param_control["clearRequest"]}, '
                  f'1,414-label match={param_control["matchesPinnedDtcCount"]}.'
                  + ('' if param_control['rejectReason'] is None else
                     f' Reject reason: `{param_control["rejectReason"]}`.')]
    lines += ['']

    lines += ['## Mode A — strict pinned translator (existing path, group-executed)', '',
              '| Category | Executable groups | Executable systems | Rejected groups | Rejected systems | Error groups | Error systems |',
              '|---|---:|---:|---:|---:|---:|---:|']
    for category in CATEGORIES:
        counts = mode_a[category]
        lines.append(f'| {category} | {counts["executable_groups"]:,} | {counts["executable_systems"]:,} | '
                     f'{counts["rejected_groups"]:,} | {counts["rejected_systems"]:,} | '
                     f'{counts["error_groups"]:,} | {counts["error_systems"]:,} |')
    lines += ['', 'Group status is the status of the group representative row; the pinned ECMECT row is '
              'preserved as representative exactly where it appears. System counts evaluate every member row.',
              '']

    lines += ['## Mode A rejection reasons (every literal reason)', '',
              '| Category | Reason | Systems | 5 sample system keys |', '|---|---|---:|---|']
    for (category, reason), count in mode_a_reasons.most_common():
        samples = ', '.join(f'`{value}`' for value in mode_a_samples[(category, reason)])
        lines.append(f'| {category} | `{reason}` | {count:,} | {samples} |')
    lines += ['']

    lines += ['## Stage 1b parameterized P3/P4 DTC compile (one execution per group)', '',
              '| Measure | Value |', '|---|---:|',
              f'| Executable groups | {compile_counts["executable_groups"]:,} |',
              f'| Executable systems covered by those groups | {compile_counts["executable_systems"]:,} |',
              f'| DTC labels produced by those groups | {compile_counts["dtc_labels"]:,} |',
              f'| Groups with a source clear (`04`/`44`) definition | {compile_counts["groupsWithSourceClear"]:,} |',
              f'| Rejected groups | {compile_counts["rejected_groups"]:,} |',
              f'| Rejected systems covered by those groups | {compile_counts["rejected_systems"]:,} |',
              f'| Groups without a decision (budget) | {not_processed["groups"]:,} |',
              '']
    lines += ['| Literal reject reason | Groups | Systems | 5 sample system keys |', '|---|---:|---:|---|']
    if compile_reasons:
        for reason, count in compile_reasons.most_common():
            window = sum(group_results[k]['systems'] for k in group_results
                         if group_results[k]['rejectReason'] == reason)
            samples = ', '.join(f'`{value}`' for value in compile_samples[reason])
            lines.append(f'| `{reason}` | {count:,} | {window:,} | {samples} |')
    else:
        lines.append('| (no rejects) | 0 | 0 | — |')
    lines += ['', 'A compiled group is a **candidate** DTC translation: it is not oracle-approved and it is '
              'not enabled. Compilation success proves only that the existing source compiler accepted that '
              'exact source row.', '']

    lines += ['## Oracle comparison (non-ECMECT)', '']
    if not oracle['available']:
        lines += [f"- **no oracle result for non-ECMECT DTC** — {oracle['reason']}. Enable nothing."]
    else:
        lines += [f'- `faultDecoderComparison` / `p5DecoderComparison` present in the native comparison output: '
                  f'`{json.dumps(oracle["decoderComparisons"], sort_keys=True)}`.',
                  f'- These comparisons are **decoder-level, not group-keyed** (CODE: {oracle["source"]}): they '
                  'replay synthetic state/record vectors and source blocks, and emit no per (Ecu_Data file, '
                  'function, selector) verdict.',
                  '- Therefore **no oracle result for non-ECMECT DTC** exists in this run. Enable nothing.']
    lines += ['', '## Timing and resources', '',
              f'- Probe wall clock: {elapsed:.1f}s. Peak RSS: {peak_rss:.1f} MiB.',
              f'- Distinct decoded source files opened and checksum-verified: {len(sources.reads)}.',
              f'- Translator classification exceptions: `{dict(translate_errors)}`.', '']

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text('\n'.join(lines))

    payload = {
        'kind': 'toyota-stage1b-parameterized-dtc-probe-v1',
        'headSha': head, 'utc': stamp, 'partial': partial,
        'corpusChecks': checks,
        'population': {'systemRecords': total_records, 'eligible': len(eligible),
                       'partition': dict(partition), 'groups': len(group_keys),
                       'groupsExecuted': len(group_keys) - not_processed['groups'],
                       'systemsBeyondRepresentative': sum(max(0, len(v) - 1) for v in groups.values())},
        'notProcessed': not_processed,
        'ecmectControl': {'status': control_status, **control},
        'modeA': {category: dict(counts) for category, counts in mode_a.items()},
        'modeAReasons': [{'category': key[0], 'reason': key[1], 'systems': count,
                          'samples': mode_a_samples[key]}
                         for key, count in mode_a_reasons.most_common()],
        'parameterized': {'counts': dict(compile_counts),
                          'rejects': [{'reason': key, 'groups': count, 'samples': compile_samples[key]}
                                      for key, count in compile_reasons.most_common()],
                          'groups': [{'group': list(key), **value} for key, value in group_results.items()]},
        'oracle': oracle,
        'elapsedSeconds': elapsed, 'peakRssMiB': peak_rss,
        'decodedSourcesOpened': sources.reads,
        'translatorExceptions': dict(translate_errors)}
    args.json_output.parent.mkdir(parents=True, exist_ok=True)
    args.json_output.write_text(json.dumps(payload, sort_keys=True, indent=2) + '\n')

    print(json.dumps({key: payload[key] for key in ('headSha', 'partial', 'population',
                                                    'ecmectControl', 'elapsedSeconds')}, sort_keys=True))
    if not mode_a_control_valid:
        print('ECMECT control failed: partial reports written and all translator results are invalid',
              file=sys.stderr)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--decoded', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--english', type=Path, required=True)
    parser.add_argument('--systems', type=Path, required=True)
    parser.add_argument('--native-oracle', type=Path)
    parser.add_argument('--ecmect-review-dir', type=Path, required=True)
    parser.add_argument('--head-sha')
    parser.add_argument('--max-seconds', type=float, default=600.0)
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
