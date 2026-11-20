"""Execute only COMM configuration/packing consumers, with explicit adapter fakes.

This is an offline oracle for toyota_transport, not a MIC3313 API implementation
or evidence of hardware timing. Unknown native calls abort in NativeSandbox.
"""
from collections import Counter
from dataclasses import replace
import hashlib
import struct

from native_replay import NativeSandbox
from toyota_entries import EntryIndex
from toyota_transport import COMM_SHA256, ConsumerCall, FastKwpEntry, fast_kwp_packet
from vehicle_bundle import canonical


def compare_fast_kwp(libraries, source):
    name = 'libTOYOTA_COMM.so'
    raw = libraries[name]
    if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != COMM_SHA256:
        raise ValueError('Fast KWP comparison needs the exact supplied COMM library')
    native = NativeSandbox({name: raw})
    global_state = native.allocate(4096)
    native.write(native.symbol(name, 'g_MToken'), struct.pack('<Q', global_state))
    native.bind('OpenAbstractCommDll', lambda *_: 0)
    trace = []
    for function, count in (
            ('BeginCombination', 0), ('EnableLLine', 1), ('SelectPIN', 5),
            ('SetCommunicationMaximumWaitTime', 1), ('SetEcuCommunicationVoltageLogic', 2),
            ('SetCommunicationParameter', 4), ('EndCombination', 0)):
        def callback(*args, api=function, n=count):
            trace.append(ConsumerCall(api, tuple(args[:n])))
            return 1  # Explicit adapter success fake, NOT a native/device result.
        native.bind(function, callback, indirect=True, library=name)

    def baud(*_):
        trace.append(ConsumerCall('SetEcuBaudRate', (native.f32(),)))
        return 1

    def timing(arg0, arg1, *_):
        trace.append(ConsumerCall('SetEcuCommTimeInterval', (native.f32(), arg0, arg1)))
        return 1

    native.bind('SetEcuBaudRate', baud, indirect=True, library=name)
    native.bind('SetEcuCommTimeInterval', timing, indirect=True, library=name)
    context = native.allocate(0x20000)
    tx = native.allocate(64)
    output = native.allocate(128)
    index = EntryIndex(source)
    by_transport = Counter()
    covered = []
    for block in index.lists.values():
        for entry in block.entries:
            by_transport[str(entry.transport)] += 1
            if entry.transport in (1, 5):
                covered.append(FastKwpEntry.from_entry(entry))

    if not covered:
        raise ValueError('No source fast-KWP entries were compared')
    configuration_digest = hashlib.sha256()
    configuration_count = 0
    packet_digest = hashlib.sha256()
    packet_count = 0
    context_digest = hashlib.sha256()
    for profile in covered:
        context_digest.update(canonical(profile.report()) + b'\n')
        native.write(context, bytes(0x20000))
        native.write(context + 0x10828, bytes([profile.transport, profile.flags]))
        native.write(context + 0x107c8, struct.pack('<I', profile.baud))
        native.write(context + 0x107b0, profile.send_address)
        native.write(context + 0x107e4, bytes([profile.runtime107e4]))
        pins = b''.join(bytes(pair) for pair in profile.pins)
        native.write(context + 0x107e5, bytes([len(pins)]) + pins)
        native.write(context + 0x107f0, profile.timing)
        for connector in (0x10, 0x20):
            native.write(global_state + 0x9e, bytes([connector]))
            for pin_index in range(len(profile.pins)):
                trace.clear()
                result = native.call(name, 'InitCommSet_Ex', pin_index, context)
                expected = profile.configuration_calls(connector=connector, pin_pair_index=pin_index)
                actual_bytes = canonical([call.report() for call in trace])
                if result != 1 or actual_bytes != canonical([call.report() for call in expected]):
                    raise AssertionError(f'Native configuration calls differ for {profile.source_record}, connector {connector}, pin pair {pin_index}: {actual_bytes!r}')
                configuration_digest.update(profile.source_record.encode() + bytes([connector, pin_index]) + actual_bytes + b'\n')
                configuration_count += 1
        for size in range(1, 64):
            payload = bytes((i * 73 + size) & 255 for i in range(size))
            native.write(tx, payload)
            result = native.call(name, 'MultiPackFunc', tx, size, output, 128, profile.flags, context)
            expected = profile.packet(payload)
            if result != len(expected) or native.read(output, result) != expected:
                raise AssertionError(f'Native short KWP packet differs for {profile.source_record}/{size}')
            packet_digest.update(profile.source_record.encode() + bytes([size]) + expected)
            packet_count += 1

    # Explicitly synthetic argument probes exercise InitKWPISOMode's baud==9600
    # exception and nonzero line argument; they are not recovered source records.
    synthetic_digest = hashlib.sha256()
    synthetic_count = 0
    for connector in (0x10, 0x20):
        for baud_value in (9600, 10416):
            for line in (0, 255):
                profile = replace(covered[0], baud=baud_value, pins=((7, 15),), timing=bytes([9, 47, line, 25, 25]))
                trace.clear()
                result = native.call(name, 'InitKWPISOMode', connector,
                    native.allocate(2, bytes(profile.pins[0])), 9, 47, int.from_bytes(profile.timing[2:4], 'little'), baud_value)
                actual_bytes = canonical([call.report() for call in trace])
                expected = profile.configuration_calls(connector=connector, pin_pair_index=0)
                if result != 1 or actual_bytes != canonical([call.report() for call in expected]):
                    raise AssertionError('Native synthetic KWP configuration branch differs')
                synthetic_digest.update(bytes([connector, line]) + struct.pack('<I', baud_value) + actual_bytes)
                synthetic_count += 1

    # Stable, source-independent comparison contract shared with actual Kotlin
    # KLineFrames.encode. Each of the two source flags is compared separately.
    contract_digest = hashlib.sha256()
    contract_count = 0
    native.write(context + 0x10828, b'\x01')
    for flags in (0x81, 0x33):
        for source_byte in (0, 0xf0, 0xf1, 255):
            for target_byte in (0, 0x10, 0x13, 255):
                native.write(context + 0x107b0, bytes([source_byte, target_byte]))
                for size in range(1, 64):
                    payload = bytes((i * 73 + size) & 255 for i in range(size))
                    native.write(tx, payload)
                    result = native.call(name, 'MultiPackFunc', tx, size, output, 128, flags, context)
                    expected = fast_kwp_packet(bytes([source_byte, target_byte]), flags, payload)
                    if result != len(expected) or native.read(output, result) != expected:
                        raise AssertionError('Native shared short-packet contract differs')
                    contract_digest.update(bytes([flags, source_byte, target_byte, size]) + expected)
                    contract_count += 1
    return {
        'nativeInstructionsExecuted': True, 'librarySha256': native.libraries[name].sha256,
        'sourceEntries': len(covered), 'sourceEntryResultsSha256': context_digest.hexdigest(),
        'allEntryCensusByNativeTransport': dict(sorted(by_transport.items())),
        'configurationVectors': configuration_count, 'configurationResultsSha256': configuration_digest.hexdigest(),
        'sourcePacketVectors': packet_count, 'sourcePacketResultsSha256': packet_digest.hexdigest(),
        'syntheticConfigurationVectors': synthetic_count, 'syntheticConfigurationResultsSha256': synthetic_digest.hexdigest(),
        'wireContractVectors': contract_count, 'wireContractResultsSha256': contract_digest.hexdigest(),
        'fakeBoundaries': ['OpenAbstractCommDll does not load host code',
            'Every adapter API is an explicit trace-only fake returning 1; not initialization/timing proof',
            'libc memory functions'],
        'notCovered': ['MIC3313 ST/VT equivalent timing/pin/line configuration',
            'SYSP fast/slow entry, sessions, replies, keepalive/exit and retry behavior',
            'Native transports other than 1/5 and packets above 63 source payload bytes',
            'Production executable-definition emission and physical validation'],
    }
