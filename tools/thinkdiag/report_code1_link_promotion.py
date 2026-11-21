#!/usr/bin/env python3
"""Render link-promotion counts from deterministic converter output."""
import argparse
from collections import Counter
import gzip
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--baseline-index', type=Path)
    parser.add_argument('--sweep-json', type=Path)
    args = parser.parse_args()
    path = args.directory / 'code1-link-promotion.json'
    report = json.loads(path.read_text())
    if args.baseline_index:
        baseline = json.loads(args.baseline_index.read_text())
        report['containerBytesBefore'] = baseline['containerBytes']
        report['containerBytesAdded'] = report['containerBytesAfter'] - baseline['containerBytes']
        path.write_text(json.dumps(report, sort_keys=True, separators=(',', ':')) + '\n')
    if args.sweep_json:
        sweep = json.loads(args.sweep_json.read_text())
        with gzip.open(args.directory / 'systems.jsonl.gz', 'rt') as stream:
            systems = [json.loads(line) for line in stream]
        tuple_systems = Counter((r['region'], r['functionKey'], r['entrySelector']) for r in systems)
        packet_counts = {name: Counter() for name in ('initPacketHex', 'keepalivePacketHex', 'exitPacketHex')}
        for item in sweep['tuples']:
            key = (item['region'], item['function'], item['selector'])
            weight = tuple_systems[key]
            for field in packet_counts:
                packet_counts[field][item[field]] += weight
        report['distinctInitPackets'] = len(packet_counts['initPacketHex'])
        report['topInitPackets'] = packet_counts['initPacketHex'].most_common(20)
        report['distinctKeepalivePackets'] = len(packet_counts['keepalivePacketHex'])
        report['topKeepalivePackets'] = packet_counts['keepalivePacketHex'].most_common(20)
        keepalive_first = Counter()
        for packet, count in packet_counts['keepalivePacketHex'].items(): keepalive_first[packet[:2]] += count
        report['keepaliveFirstByteDistribution'] = dict(sorted(keepalive_first.items()))
        report['distinctExitPackets'] = len(packet_counts['exitPacketHex'])
        report['topExitPackets'] = packet_counts['exitPacketHex'].most_common(20)
        exit_first = Counter()
        for packet, count in packet_counts['exitPacketHex'].items(): exit_first[packet[:2]] += count
        report['exitFirstByteDistribution'] = dict(sorted(exit_first.items()))
        path.write_text(json.dumps(report, sort_keys=True, separators=(',', ':')) + '\n')

    def table(title, headers, rows):
        out = [f'## {title}', '', '| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join('---' for _ in headers) + ' |']
        out += ['| ' + ' | '.join(str(v) for v in row) + ' |' for row in rows]
        return out + ['']
    lines = ['# Code 1 link promotion', '',
        f"Eligible system records: {report['eligibleSystemRecords']}; distinct links: {report['distinctLinkObjects']}; enabled systems: {report['enabledSystems']}.",
        f"Container bytes: before={report['containerBytesBefore']}, after={report['containerBytesAfter']}, added={report['containerBytesAdded']}.", '']
    lines += table('A. Systems by link status / region / transport / baud',
        ['Status', 'Region', 'Transport', 'Baud', 'Systems'],
        [[r['linkStatus'], r['region'], r['transport'], r['baud'], r['systems']] for r in report['systemsByLinkStatusRegionTransportBaud']])
    lines += ['Reason histogram:', '', *[f"- `{k}`: {v}" for k, v in report['reasonHistogram'].items()], '']
    lines += table('A. Every unproven tuple and affected systems',
        ['Reason', 'Region', 'Transport', 'Baud', 'Function', 'Selector', 'Systems'],
        [[r['reason'], r['region'], r['transport'], r['baud'], r['function'], f"0x{r['selector']:02x}", r['systems']]
         for r in report['deviations']])
    lines += table('B. Top distinct links', ['Systems', 'Link'],
        [[r['systems'], json.dumps(r['link'], sort_keys=True, separators=(',', ':'))] for r in report['topLinksBySystems']])
    for section, top in (('C. Init packets', 'topInitPackets'), ('C. Keepalive packets', 'topKeepalivePackets'), ('C. Exit packets', 'topExitPackets')):
        lines += table(section, ['Packet', 'Systems'], report[top])
    lines += ['Keepalive first-byte distribution: ' + json.dumps(report['keepaliveFirstByteDistribution'], sort_keys=True),
              'Exit first-byte distribution: ' + json.dumps(report['exitFirstByteDistribution'], sort_keys=True), '']
    lines += table('D. Response-header ISO address-swap convention check', ['Outcome', 'Tuples'],
        [[k, v] for k, v in report['responseHeaderConventionChecks'].items()])
    lines += table('D. Provenance by link field', ['Field', 'Provenance'],
        [[k, v] for k, v in report['provenanceFields'].items()])
    lines += ['## E. ECMECT and feature totals', '',
        f"Enabled systems: {report['enabledSystems']}; feature counts: `{json.dumps(report['featureCounts'], sort_keys=True)}`.",
        f"Container bytes before / after / added: {report['containerBytesBefore']} / {report['containerBytesAfter']} / {report['containerBytesAdded']}.", '']
    lines += ['## F. 9600-baud systems', '', '| Status | Systems |', '|---|---:|']
    lines += [f'| {k} | {v} |' for k, v in report['9600Systems'].items()]
    (args.directory / 'code1-link-promotion.md').write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
