"""Source-derived Kotlin lifecycle contracts, not enabled vehicle definitions.

Every plan and expected consumer trace is recomputed from the immutable decoded
source arrays. Binding/fast/ISO digests MUST equal the separately executed native
oracle reference. No adapter, guessed time-role mapping, or physical proof is
supplied by these scripted I/O contracts; the generated file is never an APK asset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct

from toyota_entries import EntryIndex
from toyota_entry_runtime import CombinedReply as R, fast_entry_trace, iso_entry_trace
from toyota_frames import SourceFrames
from toyota_lifecycle import selected_fast_kwp_lifecycle
from toyota_transport import ConsumerCall
from vehicle_bundle import canonical, SOURCE_SHA256

PIN = Path(__file__).parent / 'schema' / 'source-kwp-contracts-v1.json'
ORACLE = Path(__file__).parent / 'schema' / 'native-oracle-v1.json'
FILENAME = 'source-kwp-contracts.json'
FAST_CASES = {
    'firstPositive': (R(9, 0xc1),),
    'zeroThenPositive': (R(0), R(9, 0xc1)),
    'negativeServiceThenPositive': (R(9, 0x7f), R(9, 0xc1)),
    'negativeServiceTwice': (R(9, 0x7f), R(9, 0x7f)),
    'alternatePositive': (R(-1), R(9, 0xc1)),
    'alternateFailureThenPrimaryPositive': (R(-1), R(-1), R(9, 0xc1)),
    'adapterFailuresExhausted': (R(-1),) * 4,
    'nativeZeroIsNotPositiveProof': (R(0), R(0)),
}
ISO_CASES = {
    'addressFirstZero': ((0,), 1), 'addressLowWordZero': ((0x10000,), 1),
    'addressRetryNegative': ((-1, 0), 1), 'addressRetryPositive': ((1, 0), 1),
    'addressFailuresExhausted': ((-1, -1), 1), 'addressNonzeroExhausted': ((1, 0x7fff), 1),
    'initialNegative': ((0,), -1), 'initialZeroNotReplyProof': ((0,), 0),
}


def build_contracts(sources):
    index = EntryIndex(sources['SYSTEMENTER.BIN'])
    bindings, fast, iso = (hashlib.sha256() for _ in range(3))
    entries = []
    for region_id, region in enumerate(('EU', 'NA', 'JP'), 1):
        frames = SourceFrames(sources, region)
        for function, row in index.regions[region_id].items():
            for entry in index.lists[row['listOffset']].entries:
                if entry.transport not in (1, 5): continue
                plan = selected_fast_kwp_lifecycle(index, frames, region=region_id, function=function, selector=entry.descriptor)
                report = plan.report()
                identity = f'{region}/{function}/{entry.source_id}'
                bindings.update(canonical(report) + b'\n')
                fast_cases, iso_cases = [], []
                for stage, replies in FAST_CASES.items():
                    result = fast_entry_trace(plan, replies)
                    fallback = result.wants_iso_fallback(entry.transport)
                    calls = [c.report() for c in result.calls]
                    # Exact SAME encoding/domain as the pinned native dispatch
                    # comparison, including its explicitly fake ISO sentinel.
                    native_calls = calls + ([ConsumerCall('ISOEnterModeBoundary', (function,)).report()] if fallback else [])
                    status = 0x1234 if fallback else result.native_status
                    fast.update(identity.encode() + b':' + stage.encode() + b':' + canonical(native_calls) + struct.pack('<i', status) + b'\n')
                    fast_cases.append({'case': stage, 'replies': [{'word': r.result, 'service': r.service} for r in replies],
                        'status': result.native_status, 'positiveSourceReply': result.has_positive_reply,
                        'wantsIsoFallback': fallback, 'calls': calls})
                for stage, (words, initial) in ISO_CASES.items():
                    result = iso_entry_trace(plan, words, initial_result=initial)
                    calls = [c.report() for c in result.calls]
                    iso.update(identity.encode() + b':' + stage.encode() + b':' + canonical(calls) + struct.pack('<i', result.native_status) + b'\n')
                    iso_cases.append({'case': stage, 'addressWords': list(words), 'initialWord': initial,
                        'status': result.native_status, 'calls': calls})
                entries.append({'identity': identity, 'plan': report, 'fast': fast_cases, 'iso': iso_cases,
                    'keepaliveOn': [c.report() for c in plan.keepalive_calls(enabled=True)],
                    'keepaliveOff': [c.report() for c in plan.keepalive_calls(enabled=False)],
                    'exit': [c.report() for c in plan.exit_calls()]})
    digests = {'sourceBindingsSha256': bindings.hexdigest(), 'fastConsumerCallsSha256': fast.hexdigest(),
               'isoConsumerCallsSha256': iso.hexdigest()}
    reference = json.loads(ORACLE.read_text())
    for domain, name in (('kwpRetryComparison', 'fastConsumerCallsSha256'), ('isoEntryComparison', 'isoConsumerCallsSha256')):
        expected = reference[domain]
        if (expected['sourceBindingsSha256'] != digests['sourceBindingsSha256'] or
                expected['consumerCallsSha256'] != digests[name] or expected['sourceEntries'] != len(entries)):
            raise ValueError('Kotlin lifecycle contracts differ from the independently pinned native domain: ' + domain)
    source_names = ['SYSTEMENTER.BIN'] + [f'{region}/Toyota_bin_{number}.BIN'
        for region in ('EU', 'NA', 'JP') for number in ('00', '11', '12')]
    return {'kind': 'source-kwp-lifecycle-contracts', 'formatVersion': 1, 'sourceSha256': SOURCE_SHA256,
        'productionExecutableDefinitions': 0,
        'boundary': 'Scripted source API I/O only; no MIC3313 mapping, electrical initialization or vehicle acceptance',
        'inputs': [{'file': name, 'bytes': len(sources[name]), 'sha256': hashlib.sha256(sources[name]).hexdigest()} for name in source_names],
        'sourceEntries': len(entries), 'fastVectors': sum(len(e['fast']) for e in entries),
        'isoVectors': sum(len(e['iso']) for e in entries), **digests, 'entries': entries}


def artifact_pin(raw, contracts):
    return {'file': FILENAME, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
            **{key: contracts[key] for key in ('sourceEntries', 'fastVectors', 'isoVectors', 'productionExecutableDefinitions',
                                             'sourceBindingsSha256', 'fastConsumerCallsSha256', 'isoConsumerCallsSha256')}}


def verify_contract_file(directory):
    from toyota_runtime_compile import strict_json
    raw = (directory / FILENAME).read_bytes()
    document = strict_json(raw, maximum_bytes=8 * 1024 * 1024)
    pin = json.loads(PIN.read_text())
    if raw != canonical(document) + b'\n' or artifact_pin(raw, document) != pin:
        raise ValueError('Changed/truncated/noncanonical source KWP lifecycle contracts')
    return {key: document[key] for key in ('sourceEntries', 'fastVectors', 'isoVectors', 'productionExecutableDefinitions')}


def write_contracts(sources, directory):
    first, second = build_contracts(sources), build_contracts(sources)
    raw = canonical(first) + b'\n'
    if raw != canonical(second) + b'\n': raise ValueError('Nondeterministic source lifecycle contracts')
    if artifact_pin(raw, first) != json.loads(PIN.read_text()):
        raise ValueError('Lifecycle contracts differ from the reviewed full-output regression pin')
    directory.mkdir(parents=True, exist_ok=True)
    (directory / FILENAME).write_bytes(raw)
    return {**verify_contract_file(directory), 'deterministicRegeneration': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    compile_ = sub.add_parser('compile')
    compile_.add_argument('archive', type=Path)
    compile_.add_argument('--decoded', type=Path, required=True)
    compile_.add_argument('--manifest', type=Path, required=True)
    compile_.add_argument('--output', type=Path, required=True)
    verify = sub.add_parser('verify'); verify.add_argument('directory', type=Path)
    args = parser.parse_args()
    if args.command == 'compile':
        from convert_vehicle_packs import load_sources
        result = write_contracts(load_sources(args.archive, args.decoded, args.manifest), args.output)
    else:
        result = verify_contract_file(args.directory)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__': main()
