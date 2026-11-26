#!/usr/bin/env python3
"""Measure the decoded Toyota control without publishing any brand verdicts."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tempfile
import zipfile

import brand_inventory as inventory  # Sets up the thinkdiag module path.
from analyze_toyota import TOYOTA_SHA256
from decode_toyota import elf_bytes
from toyota_entries import EntryIndex
from toyota_structure import ECU_FILE, section_directory, typed_table, function_index, system_lists, menu_nodes
from yzjm import decode_yzjm


def reader(name, data):
    name = name.replace('\\', '/').split('!/', 1)[-1]
    base = PurePosixPath(name).name
    if re.fullmatch(ECU_FILE, name):
        sections = section_directory(data)
        for section in sections:
            typed_table(data[section.offset:section.end])
        return 'section_directory + typed_table'
    if base == 'SYSTEMENTER.BIN':
        EntryIndex(data)
        return 'EntryIndex'
    if re.fullmatch(r'FUNCDATA_(EU|JP|NA)\.BIN', base):
        function_index(data)
        return 'function_index'
    if re.fullmatch(r'SYSLIST_(EU|JP|NA)\.BIN', base):
        system_lists(data)
        return 'system_lists'
    if base == 'MENU.BIN':
        menu_nodes(data)
        return 'menu_nodes'
    raise ValueError('not applicable: no converter container reader for this name')


def eligible(name):
    name = name.replace('\\', '/').split('!/', 1)[-1]
    base = PurePosixPath(name).name
    return bool(re.fullmatch(ECU_FILE, name) or base in ('SYSTEMENTER.BIN', 'MENU.BIN') or
                re.fullmatch(r'(FUNCDATA|SYSLIST)_(EU|JP|NA)\.BIN', base))


def control(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != TOYOTA_SHA256:
        raise ValueError('Unexpected archive SHA-256; native table offsets are version-specific')
    results = []
    with zipfile.ZipFile(path) as archive:
        files = [i for i in archive.infolist() if not i.is_dir()]
        libs = [i for i in files if PurePosixPath(i.filename).name == 'libDEVICEID.so']
        if len(libs) != 1:
            raise ValueError(f'Expected one libDEVICEID.so; found {len(libs)}')
        table = elf_bytes(archive.read(libs[0]), 0xF88C, 32)

        def run(name, data):
            try:
                if not data.startswith(b'\x05\x00YZJM\0'):
                    raise ValueError('Not a YZJM wrapped file')
                decoded = decode_yzjm(data, table)
                parsed = reader(name, decoded.payload)
                results.append({'path': name, 'parsed': True, 'reader': parsed})
            except (ValueError, IndexError, KeyError, TypeError) as error:
                results.append({'path': name, 'parsed': False, 'error': f'{type(error).__name__}: {error}'})

        outer = {i.filename: i for i in files if eligible(PurePosixPath(i.filename).name)}
        selected, outer_rule = inventory.sample(list(outer), 100, 50)
        for name in selected:
            run(PurePosixPath(name).name, archive.read(outer[name]))
        nested = [i for i in files if PurePosixPath(i.filename).name == 'BIN.ZIP']
        if len(nested) != 1:
            raise ValueError(f'Expected one BIN.ZIP; found {len(nested)}')
        with tempfile.TemporaryFile() as temp:
            with archive.open(nested[0]) as source:
                import shutil
                shutil.copyfileobj(source, temp)
            temp.seek(0)
            with zipfile.ZipFile(temp) as inner:
                names = {i.filename: i for i in inner.infolist() if not i.is_dir() and eligible(i.filename)}
                selected, nested_rule = inventory.sample(list(names), 100, 50)
                for name in selected:
                    run(name, inner.read(names[name]))
    passed = sum(row['parsed'] for row in results)
    errors = Counter(row['error'] for row in results if not row['parsed'])
    return {'sample': len(results), 'parsed': passed, 'passed': bool(results) and passed * 2 > len(results),
            'sampling': {'outer': outer_rule, 'nested': nested_rule}, 'errors': dict(sorted(errors.items())),
            'results': results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = control(args.archive)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'control.json').write_text(json.dumps(result, indent=2) + '\n')
    lines = ['# Toyota decoded-BIN control', '',
             'Path: `tools/thinkdiag/decode_toyota.py:39-44`; '
             '`tools/thinkdiag/convert_vehicle_packs.py:79-91,171,248-249,281,340`.', '',
             f"Control: {result['parsed']}/{result['sample']} parsed; majority required (>50%): "
             f"{'PASS' if result['passed'] else 'FAIL'}.", '',
             f"Sampling: outer {result['sampling']['outer']}; nested {result['sampling']['nested']}. "
             'Only filenames for which the converter has a container reader are applicable.', '',
             '## Literal failure histogram', '', '| error | count |', '| --- | ---: |']
    for error, count in result['errors'].items():
        lines.append(f"| {error.replace('|', '&#124;')} | {count} |")
    if not result['errors']:
        lines.append('| no failures | 0 |')
    lines += ['', 'No brand verdicts were published by this control-only run.']
    (args.output / 'control.md').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
