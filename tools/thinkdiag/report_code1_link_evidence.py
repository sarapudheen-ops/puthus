#!/usr/bin/env python3
"""Audit native lifecycle evidence coverage against the Code 1 sweep tuple set."""
import argparse
from collections import Counter
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('sweep', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text())
    sweep = json.loads(args.sweep.read_text())
    if not isinstance(evidence, list) or not isinstance(sweep.get('tuples'), list):
        raise ValueError('Expected evidence array and lifecycle-sweep tuple array')
    actual = {(r['regionName'], r['function'], r['selector']) for r in evidence}
    wanted = {(r['region'], r['function'], r['selector']) for r in sweep['tuples']}
    transport = Counter(str(r['transport']) for r in evidence)
    region = Counter(r['regionName'] for r in evidence)
    baud = Counter(str(r['baud']) if r['baud'] in (9600, 10416) else 'other' for r in evidence)
    duplicate_count = len(evidence) - len(actual)
    summary = {
        'entryCount': len(evidence),
        'countByTransport': {str(k): transport.get(str(k), 0) for k in (1, 5)},
        'countByRegion': {k: region[k] for k in ('EU', 'NA', 'JP')},
        'countByBaud': {k: baud[k] for k in ('9600', '10416', 'other')},
        'sweepTupleCount': len(wanted),
        'uniqueEvidenceTupleCount': len(actual),
        'duplicateTupleCount': duplicate_count,
        'tupleSetEqual': actual == wanted and duplicate_count == 0,
        'missingFromEvidence': [list(k) for k in sorted(wanted - actual)],
        'extraInEvidence': [list(k) for k in sorted(actual - wanted)],
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'code1-link-evidence-summary.json').write_text(json.dumps(summary, sort_keys=True, indent=2) + '\n')
    lines = ['# Code 1 native lifecycle evidence coverage', '',
        f"Entries: {summary['entryCount']}; unique tuples: {summary['uniqueEvidenceTupleCount']}; duplicates: {duplicate_count}.",
        f"Tuple-key set equals sweep ({summary['sweepTupleCount']}): {summary['tupleSetEqual']}.", '',
        '## By transport', '', '| Transport | Entries |', '|---:|---:|']
    lines += [f'| {k} | {v} |' for k, v in summary['countByTransport'].items()]
    lines += ['', '## By region', '', '| Region | Entries |', '|---|---:|']
    lines += [f'| {k} | {v} |' for k, v in summary['countByRegion'].items()]
    lines += ['', '## By baud', '', '| Baud | Entries |', '|---|---:|']
    lines += [f'| {k} | {v} |' for k, v in summary['countByBaud'].items()]
    if summary['missingFromEvidence']:
        lines += ['', '## Missing tuples', '', *[f'- `{k}`' for k in summary['missingFromEvidence']]]
    if summary['extraInEvidence']:
        lines += ['', '## Extra tuples', '', *[f'- `{k}`' for k in summary['extraInEvidence']]]
    (args.output / 'code1-link-evidence-summary.md').write_text('\n'.join(lines) + '\n')
    if not summary['tupleSetEqual']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
