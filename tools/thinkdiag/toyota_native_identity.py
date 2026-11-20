"""Bounded FILE regional program joins and ECUF kind-14 record comparisons.

Only immutable decoded-file APIs / synthetic sender replies are bridged. Neither
native filename 'CAN' nor a successful offline replay proves a vehicle protocol,
ECU serial, physical initialization or permission to change a session.
"""
import hashlib
import struct

from native_replay import NativeSandbox, ReplayError
from toyota_ecuprogram import (EcuProgramIndex, FILE_SHA256, ECUF_SHA256,
                               calibration_ids_14)
from vehicle_bundle import canonical


def checked(libraries, name, digest):
    raw = libraries[name]
    if hashlib.sha256(raw).hexdigest() != digest:
        raise ReplayError('Identity comparison requires the exact supplied library: ' + name)
    return NativeSandbox({name: raw})


def compare_ecu_programs(libraries, sources, memory_files):
    name = 'libTOYOTA_FILE.so'; n = checked(libraries, name, FILE_SHA256)
    raw = sources['ECUPROGRAM.BIN']; port = EcuProgramIndex(raw)
    files = memory_files(n); files.bind(name); handle = files.register(raw)
    def open_file(mode, path, kind, *_):
        if n.string(path) != b'./ECUPROGRAM.BIN' or n.string(kind) != b'rb':
            raise ReplayError('Native program reader attempted an undeclared file')
        return handle
    n.bind('CheckOnLineFileIsExist', lambda *_: 0)
    n.bind('OpenDeviceIdDll', lambda *_: 0)
    n.bind('fopenEx', open_file, indirect=True, library=name)
    n.bind('fcloseEx', lambda h, *_: 0 if h == handle else (_ for _ in ()).throw(ReplayError('Unknown program file')), indirect=True, library=name)
    context = n.allocate(0x2800000); systems = n.allocate(0x174d8); result = n.allocate(0x2c4)
    n.write(context, b'\x01\0'); n.write(context + 8, struct.pack('<Q', systems))
    digest = hashlib.sha256(); vectors = 0; regions = {}
    for region, rows in port.rows_by_region.items():
        n.write(context + 0x9d, bytes([region]))
        for source_row in rows:
            function = source_row.function
            item = port.select(region, function)
            n.write(systems, struct.pack('<II', function, 0x1234))
            n.write(systems + 0x18, b'\x11')  # Explicit selector, independent of dispatch.
            n.write(result, b'\0' * 0x2c4)
            actual = n.call(name, 'GetSupEcuFromEcuProgList', 1, result, context)
            wanted = struct.pack('<IIIIBB', 0, function, 0x1234, item.program, item.dispatch, 0x11)
            if actual != 1 or n.read(result, len(wanted)) != wanted:
                raise AssertionError(f'Native regional/full-key ECU program join differs at {region}/{function}: result={actual}, {n.read(result, len(wanted)).hex()} != {wanted.hex()}')
            digest.update(canonical(item.report()) + wanted); vectors += 1
        regions[str(region)] = len(rows)
    # A missing full key and the native skip flag must not borrow another row.
    for region in (1, 2, 3):
        n.write(context + 0x9d, bytes([region])); n.write(systems, struct.pack('<I', 0xfffffffe))
        if n.call(name, 'GetSupEcuFromEcuProgList', 1, result, context) != 0:
            raise AssertionError('Native ECU program reader substituted a missing full key')
        n.write(systems, struct.pack('<I', 61)); n.write(systems + 0x10824, b'\x01')
        if n.call(name, 'GetSupEcuFromEcuProgList', 1, result, context) != 0:
            raise AssertionError('Native ECU program skip flag was ignored')
        n.write(systems + 0x10824, b'\0')
    return {'nativeInstructionsExecuted': True, 'librarySha256': FILE_SHA256,
        'sourceFileSha256': hashlib.sha256(raw).hexdigest(), 'vectors': vectors, 'byRegion': regions,
        'resultsSha256': digest.hexdigest(), 'missingAndSkippedVectors': 6,
        'ecmect': port.select(1, 61).report(),
        'fakeBoundaries': ['fopenEx/fseekEx/freadEx/fcloseEx over the exact supplied decoded ECUPROGRAM array',
            'one explicit non-skipped native system slot and selector; no menu/discovery inference',
            'OpenDeviceIdDll bypass and no online override; no native decryption or host file access']}


def compare_calibration_14(libraries):
    name = 'libTOYOTA_ECUF.so'; n = checked(libraries, name, ECUF_SHA256)
    context = n.allocate(512); runtime = n.allocate(0x174d8); output = n.allocate(0x2c4)
    n.write(context + 8, struct.pack('<Q', runtime))
    n.write(output + 0x10, b'\x0e')
    sends = []; reply = b''
    def sender(flags, template, index, dst, capacity, actual_runtime, extra, *_):
        if (flags, template, index, capacity, actual_runtime, extra) != (1, 136, 0, 255, runtime, 1):
            raise ReplayError('Kind-14 identity crossed its exact source template/context')
        if len(reply) > capacity: raise ReplayError('Unbounded synthetic calibration reply')
        sends.append(template); n.write(dst, reply)
        return len(reply)
    n.bind('SendCmdBitAndGetReceive', sender, indirect=True, library=name)
    digest = hashlib.sha256(); vectors = 0
    # The original sender capacity is 255 POST-service bytes, so only 1..15
    # complete rows are reachable in this source caller, despite its cap of 20.
    for count in range(1, 16):
        for header in (0, 1, count, 255):
            rows = [f'CAL{i:013d}'.encode('ascii') for i in range(count)]
            reply = bytes([4, header]) + b''.join(rows)
            n.write(output + 0x13, b'\0')
            n.call(name, 'GetEcuCalitrationID', output, context)
            actual_count = n.read(output + 0x13, 1)[0]
            actual = [n.read(output + 0x14 + i * 30, 16) for i in range(actual_count)]
            wanted = calibration_ids_14(b'\x49' + reply)
            if actual != rows or tuple(r.decode() for r in actual) != wanted:
                raise AssertionError('Native kind-14 fixed-width calibration records differ')
            digest.update(bytes([count, header]) + b''.join(actual)); vectors += 1
    # Native rounds incomplete buffers up. This is documented hardening, NOT a
    # reason to accept an unchecked copy or silently discard the last bytes.
    hardened = 0
    for length in (0, 1, 15, 17, 31, 33, 253):
        reply = b'\x04\x01' + b'A' * length
        n.write(output + 0x13, b'\0'); n.call(name, 'GetEcuCalitrationID', output, context)
        try: calibration_ids_14(b'\x49' + reply)
        except ValueError: hardened += 1
        else: raise AssertionError('Port accepted an incomplete native calibration record')
    return {'nativeInstructionsExecuted': True, 'librarySha256': ECUF_SHA256,
        'dispatch': 14, 'template': 136, 'vectors': vectors, 'resultsSha256': digest.hexdigest(),
        'hardenedIncompleteVectors': hardened, 'senderCapacityAfterService': 255,
        'fakeBoundaries': ['SendCmdBitAndGetReceive returns explicit synthetic post-service replies; no COMM/adapter/device access',
            'one ordinary native runtime (not alternate mode 34); no lifecycle or applicability inference'],
        'notCovered': ['other ECUF dispatch kinds', 'physical calibration-ID validity', 'unique ECU serial or write proof'],
        'hardening': 'Incomplete fixed-width records and invalid ASCII are rejected, not native rounded-up out-of-bounds copies'}
