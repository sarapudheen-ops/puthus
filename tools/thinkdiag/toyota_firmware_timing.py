#!/usr/bin/env python3
"""Bounded, offline native -> command-record -> Cortex-M timing experiment.

The public ThinkDiag Mini image is a supplemental *consumer*, not the Toyota
specification, MIC3313 firmware, an installed-device identification, or vehicle
proof. Raw firmware/native libraries never enter Git, a pack, or the APK. Only
these exact checksummed inputs are accepted. No host dlopen/syscall/peripheral,
network, adapter, or ECU is exposed to guest instructions. Unknown MMIO aborts.

An ACK at the capture boundary and UART bytes below are explicitly synthetic.
They let the real serializer/parser/consumer run, not prove an electrical link.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct

from native_replay import NativeSandbox, ReplayError
from vehicle_bundle import canonical

ABSTRACT = 'libCOMM_ABSTRACT_LAYER.so'
ABSTRACT_SHA256 = 'd8f12c620599def5a4f4651a314d73b2ef4de1e69f3f8322ef349fd0a1f4e85a'
FIRMWARE_SHA256 = '59f32108e108f5d0fc03a91462c46e38ed26eef3306ed6a1e48faa1f8f21a116'
FIRMWARE_BYTES = 393216
PUBLIC_ZIP_SHA256 = '189d657117a7b571bbe5944dfe93009b45a3286649b814653f081f8b0b93092a'
PUBLIC_ZIP_URL = 'https://drive.google.com/file/d/1xHe0q9gCBXmSZ5fBkXwhZ79wRSnagHmx/view'
PUBLIC_REFERENCE = 'https://github.com/porloc/thinkdiag2-reversing/blob/e8e87b5d3af9f4a9d45a483fd73f4412cb73afe9/PROGRESS.md'


def checked_bytes(raw, size, digest, kind):
    if not isinstance(raw, bytes) or len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
        raise ReplayError('Wrong, truncated, or unpinned ' + kind)
    return raw


def integral(value, lower, upper):
    if type(value) is not int or not lower <= value <= upper:
        raise ValueError('Exact bounded integer required')
    return value


def float32(value):
    if type(value) not in (float, int) or not math.isfinite(value):
        raise ValueError('Finite float32 required')
    try:
        value = struct.unpack('<f', struct.pack('<f', value))[0]
    except (OverflowError, struct.error) as error:
        raise ValueError('Out-of-range float32') from error
    if not math.isfinite(value):
        raise ValueError('Out-of-range float32')
    return value


@dataclass(frozen=True)
class IntervalRecord:
    """Closed 6003 record. Names describe observed consumers, NOT guessed P2/P3.

    +0: pre-/inter-request delay, milliseconds; +4: UART TX gap, microseconds;
    +8: UART receive poll count (TIM3's normal-mode period is established
    separately). The source's third API parameter is not a generic P2* field.
    """
    request_delay_ms: int
    tx_gap_us: int
    receive_ticks: int
    keepalive_tick_ms: int

    @classmethod
    def decode(cls, record):
        if not isinstance(record, bytes) or len(record) != 7 or record[:2] != b'\x60\x03':
            raise ValueError('Expected exactly one complete 6003 record')
        tx, delay, receive = int.from_bytes(record[2:4], 'big'), record[4], int.from_bytes(record[5:7], 'big')
        if delay == 0 or receive == 0:
            raise ValueError('Firmware rejects zero request delay / receive count')
        # The firmware stores the rounded quotient in a BYTE before checking 0.
        tick = ((tx + 5) // 10) & 255
        return cls(delay * 10, tx * 100, receive * 10, max(tick, 1))

    @classmethod
    def from_arguments(cls, tx_gap, request_delay, receive_wait):
        """Reviewed nonwrapping positive ABI domain; never silently clamp/wrap."""
        tx = float32(float32(tx_gap) * 10)
        if not 0 <= tx + 0.5 < 65536:
            raise ValueError('TX interval is outside the nonwrapping source domain')
        request_delay = integral(request_delay, 5, 2545)
        receive_wait = integral(receive_wait, 5, 655345)
        raw = b'\x60\x03' + int(tx + 0.5).to_bytes(2, 'big')
        raw += bytes([(request_delay + 5) // 10]) + ((receive_wait + 5) // 10).to_bytes(2, 'big')
        return raw, cls.decode(raw)

    def report(self):
        return {'requestDelayMs': self.request_delay_ms, 'txGapUs': self.tx_gap_us,
                'receivePollCount': self.receive_ticks, 'keepaliveTimerQuantumMs': self.keepalive_tick_ms}


class AbstractCapture:
    """Execute the supplied serializer; explicitly fake its adapter-I/O boundary."""
    def __init__(self, library):
        from unicorn import UC_HOOK_CODE
        # NativeSandbox also checks the ELF type, segments, permissions and ABI.
        if not isinstance(library, bytes) or hashlib.sha256(library).hexdigest() != ABSTRACT_SHA256:
            raise ReplayError('Wrong native abstract-layer identity')
        self.native = n = NativeSandbox({ABSTRACT: library})
        self.base = n.libraries[ABSTRACT].base
        self.records = []
        # Explicit board-encoding domain, NOT the connected adapter's identity.
        # 50/60 use a 32-bit literal baud; other boards use a legacy divisor.
        n.write(n.symbol(ABSTRACT, 'ConnectorType'), b'\x60')
        at = self.base + 0x53d0
        n.hooks[at] = ('captured-command/synthetic-ACK', self._capture)
        n.uc.hook_add(UC_HOOK_CODE, n._hook, begin=at, end=at)

    def _capture(self, *_):
        n = self.native
        size = struct.unpack('<H', n.read(self.base + 0x500a0, 2))[0]
        if not 2 <= size <= 512:
            raise ReplayError('Unexpected abstract command size')
        self.records.append(n.read(n.u64(self.base + 0x50090), size))
        return 1  # Explicit synthetic adapter acknowledgement; not hardware.

    def call(self, function, *args, f32=None):
        from unicorn.arm64_const import UC_ARM64_REG_S0
        if function not in {'SetEcuCommTimeInterval', 'SetEcuBaudRate', 'SetLinkKeep'}:
            raise ReplayError('Unreviewed serializer entry point')
        if f32 is not None:
            self.native.uc.reg_write(UC_ARM64_REG_S0, struct.unpack('<I', struct.pack('<f', float32(f32)))[0])
        before = len(self.records)
        self.native.call(ABSTRACT, function, *args)
        if len(self.records) != before + 1:
            raise ReplayError('No unambiguous serialized record')
        return self.records[-1]

    def keepalive(self, packet):
        if not isinstance(packet, bytes) or len(packet) > 20:
            raise ReplayError('Unbounded source keepalive packet')
        ptr = self.native.allocate(len(packet), packet) if packet else 0
        return self.call('SetLinkKeep', ptr, len(packet), 3 if packet else 0, 800 if packet else 0)


class MiniTimingSandbox:
    """Exact pinned Cortex-M image, RAM init + selected timing consumers only.

    There is no peripheral memory mapping. Every substituted peripheral leaf
    is fixed below, recorded, and returns only an explicit test stimulus. No
    ELF loader, firmware installer, host device access, or arbitrary import API.
    """
    FLASH, RAM, RAM_BYTES = 0x08000000, 0x20000000, 0x30000
    RETURN, STACK, INPUT, OUTPUT = 0x08000100, 0x2002e000, 0x20028000, 0x20029000
    TIMING = 0x200029c4
    # Reviewed pure/consumer entry points; no main/reset/interrupt scheduler.
    ENTRIES = {0x0803c040, 0x08029a0c, 0x08021282, 0x0801ea30, 0x080155a6,
               0x080213b2, 0x0801ba46, 0x08028d9c}

    def __init__(self, firmware):
        from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UC_HOOK_CODE, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
        from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_PC
        checked_bytes(firmware, FIRMWARE_BYTES, FIRMWARE_SHA256, 'public Mini firmware')
        self.uc = u = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        u.mem_map(self.FLASH, FIRMWARE_BYTES, UC_PROT_READ | UC_PROT_EXEC)
        u.mem_write(self.FLASH, firmware)
        u.mem_map(self.RAM, self.RAM_BYTES, UC_PROT_READ | UC_PROT_WRITE)
        self.trace, self._returned, self._failure = [], False, None
        self._stimulus = b''; self._received = 0; self._sent = bytearray(); self._expected_tx = b''
        self._peripherals = False
        # Callbacks are a closed allowlist of actual source-identified leaves.
        self._leaves = {0x0801fee8: self._delay, 0x0801eb1c: self._tx,
                        0x0801efca: self._rx, 0x0801fef8: self._gpio,
                        0x08023b9c: self._timer_reset, 0x08023cde: self._timer_base,
                        0x0801ffe2: self._gpio_bit, 0x08023724: self._clock_enable,
                        0x0801fe20: self._systick_arm, 0x0801fe70: self._systick_wait,
                        0x0801f0be: self._uart_apply, 0x0802483a: self._uart_disable,
                        0x080248f2: self._uart_clear_flag}
        # Exact timer-control leaves reached by the real keepalive timer
        # setup. Bodies would touch MMIO; their arguments, not effects, are logged.
        for leaf in (0x08023d70, 0x08023d7a, 0x08023d8e, 0x08023da4,
                     0x08023db4, 0x08023de6):
            self._leaves[leaf] = lambda leaf=leaf: self._timer_control(leaf)
        def hook(uc, address, size, _):
            if address == self.RETURN:
                self._returned = True; uc.emu_stop(); return
            try:
                if not self._peripherals:
                    raise ReplayError('Unexpected peripheral leaf during RAM initialization')
                result = self._leaves[address]()
                if result is not None: self.setreg(0, result)
                uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
            except Exception as error:
                self._failure = error; uc.emu_stop()
        for address in (*self._leaves, self.RETURN):
            u.hook_add(UC_HOOK_CODE, hook, begin=address, end=address)
        self.call(0x0803c040, instruction_limit=200_000)
        self.initial_ram = self.read(self.RAM, self.RAM_BYTES)
        # Check the table was really expanded by the guest IAR initializer.
        if (self.u32(0x20002c00 + 3 * 4) != 0x08021283 or self.u32(0x20002cc0 + 9 * 4) != 0x080155a7
                or self.u32(0x0803c2ac + 2 * 4) != 0x6109):
            raise ReplayError('Unexpected initialized timing/KWP dispatch entries or 6402 alias')
        self._peripherals = True

    def reg(self, number):
        from unicorn.arm_const import UC_ARM_REG_R0
        return self.uc.reg_read(UC_ARM_REG_R0 + number)

    def setreg(self, number, value):
        from unicorn.arm_const import UC_ARM_REG_R0
        self.uc.reg_write(UC_ARM_REG_R0 + number, value)

    def read(self, address, size):
        if type(size) is not int or not 0 <= size <= self.RAM_BYTES:
            raise ReplayError('Unbounded firmware read')
        return bytes(self.uc.mem_read(address, size))

    def write(self, address, raw):
        if not isinstance(raw, bytes) or not self.RAM <= address <= self.RAM + self.RAM_BYTES - len(raw):
            raise ReplayError('Firmware write outside guest SRAM')
        self.uc.mem_write(address, raw)

    def u32(self, address):
        return int.from_bytes(self.read(address, 4), 'little')

    def call(self, address, *args, instruction_limit=100_000):
        from unicorn import UcError
        from unicorn.arm_const import UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC
        if (address not in self.ENTRIES or len(args) > 4 or not 1 <= instruction_limit <= 200_000
                or any(type(a) is not int or not 0 <= a <= 0xffffffff for a in args)):
            raise ReplayError('Unreviewed firmware entry/ABI/bound')
        for i in range(13): self.setreg(i, args[i] if i < len(args) else 0)
        self.uc.reg_write(UC_ARM_REG_SP, self.STACK)
        self.uc.reg_write(UC_ARM_REG_LR, self.RETURN | 1)
        self._returned, self._failure = False, None
        try:
            self.uc.emu_start(address | 1, self.RETURN + 2, timeout=2_000_000, count=instruction_limit)
        except UcError as error:
            raise ReplayError(f'Firmware stopped at {self.uc.reg_read(UC_ARM_REG_PC):#x}: {error}') from error
        if self._failure is not None: raise ReplayError(str(self._failure)) from self._failure
        if not self._returned: raise ReplayError('Firmware did not return within its instruction/time budget')
        return self.reg(0)

    def reset(self):
        self.write(self.RAM, self.initial_ram)
        self.trace.clear(); self._sent.clear(); self._received = 0
        self._stimulus = b''; self._expected_tx = b''

    def interval(self, raw):
        # Validate full input independently BEFORE the native parser can overread.
        expected = IntervalRecord.decode(raw)
        self.write(self.INPUT, raw + bytes(16)); self.write(self.OUTPUT, bytes(32))
        self.call(0x08029a0c, self.INPUT, self.OUTPUT)
        if [self.u32(self.OUTPUT + n) for n in (0, 4, 8)] != [0x6003, 5, self.INPUT + 2]:
            raise AssertionError('Firmware command parser differs from the complete 6003 span')
        result = self.call(0x08021282, 5, self.INPUT + 2, self.OUTPUT + 16)
        actual = IntervalRecord(self.u32(self.TIMING), self.u32(self.TIMING + 4),
                                self.u32(self.TIMING + 8), self.read(self.TIMING + 12, 1)[0])
        if result != 2 or actual != expected:
            raise AssertionError('Firmware 6003 consumer differs: ' + repr((result, actual, expected)))
        return actual

    def normal_rx_timer(self):
        self.call(0x0801ea30, 0)
        rows = [r for r in self.trace if r['boundary'] == 'TIM_TimeBaseInit']
        if len(rows) != 1: raise AssertionError('Ambiguous normal-mode UART timer')
        row = rows[0]
        clock = self.u32(0x20002f00)
        # This is the firmware's initialized clock DECLARATION, not an oscillator measurement.
        if row['timer'] != 0x40000400 or clock != 168_000_000 or row['prescaler'] != 167 or row['period'] != 999:
            raise AssertionError('UART normal-mode timer configuration changed')
        return {**row, 'declaredClockHz': clock,
                'periodUsAtDeclaredClock': (row['prescaler'] + 1) * (row['period'] + 1) * 1_000_000 // clock}

    def _dispatch(self, raw):
        """Private: callers validate the complete closed command span first.

        Run the real dispatcher/alias table, rather than substituting 6109 for
        source PRIMARY's 6402. No arbitrary command API is exposed by the CLI.
        """
        self.write(self.INPUT, raw + bytes(256)); self.write(self.OUTPUT, bytes(512))
        self.write(self.OUTPUT + 512, len(raw).to_bytes(4, 'little'))
        self.call(0x08028d9c, self.OUTPUT + 512, self.INPUT, self.OUTPUT)
        size = self.u32(self.OUTPUT + 512)
        if not 2 <= size <= 512:
            raise AssertionError('Firmware dispatcher returned an unbounded result')
        return self.read(self.OUTPUT, size)

    def literal_baud(self, raw):
        if raw != bytes.fromhex('6002000028b000'):
            raise ReplayError('Not the exact reviewed literal 10416 baud/8N1 record')
        reply = self._dispatch(raw)
        if reply != b'\xff\x00' or [self.u32(0x200029b4 + i * 4) for i in range(4)] != [10416, 0, 0, 0]:
            raise AssertionError('Firmware changed the literal source baud or serial parameters')
        return reply

    def exchange(self, raw_request, response=b'', *, primary_pulses=None):
        if (not isinstance(raw_request, bytes) or not 5 <= len(raw_request) <= 50
                or raw_request[0] & 0xc0 != 0x80 or (raw_request[0] & 63) + 4 != len(raw_request)
                or sum(raw_request[:-1]) & 255 != raw_request[-1]):
            raise ReplayError('Not one complete checksummed source KWP request')
        if not isinstance(response, bytes) or len(response) > 255:
            raise ReplayError('Unbounded synthetic UART stimulus')
        self._sent.clear(); self._expected_tx = raw_request; self._stimulus = response; self._received = 0
        # wait-selector FF, request count 1, complete raw packet. The exact
        # source PRIMARY symbol is CommSubFunc0003 -> 6402, not the similarly
        # named missing CommSubFunc_00000003 export. OpenAbstractCommDll's
        # dlsym instructions establish that pointer-cell/export pairing.
        args = bytes([255, 1, len(raw_request)]) + raw_request
        prefix, opcode = b'', b'\x61\x09'
        if primary_pulses is not None:
            if primary_pulses not in ((25, 25), (35, 15)):
                raise ReplayError('Unreviewed source pulse arguments')
            prefix = b'\x60\x07\x00' + primary_pulses[0].to_bytes(2, 'big')
            prefix += b'\x60\x07\xff' + primary_pulses[1].to_bytes(2, 'big')
            opcode = b'\x64\x02'
        reply = self._dispatch(prefix + opcode + args)
        if bytes(self._sent) != raw_request or self._received != len(response):
            raise AssertionError('Firmware consumed a different UART TX/RX span')
        return reply

    def keepalive(self, record, packet):
        """Execute real 6004 + its real timer callback; advance only synthetic IRQs.

        The native API's third argument is an RX-byte drain bound, NOT a retry
        count. The first byte becomes due strictly AFTER interval/quantum ticks;
        800 / 5 therefore first transmits at tick 161 (805 declared-clock ms).
        This is not evidence that ATSW 28 (800 ms) has identical scheduling.
        """
        if (not isinstance(packet, bytes) or not 5 <= len(packet) <= 20 or
                not isinstance(record, bytes) or record != b'\x60\x04' + bytes([len(packet)]) + packet + b'\x03\x50'):
            raise ReplayError('Not the exact bounded source keepalive record')
        self.write(self.INPUT, record[2:] + bytes(32)); self.write(self.OUTPUT, bytes(32))
        result = self.call(0x080213b2, len(record) - 2, self.INPUT, self.OUTPUT)
        state = 0x20002a98
        if (result != 2 or self.read(state, 1) != b'\x04' or self.u32(state + 4) != 800 or
                self.read(state + 8, 1) != b'\x05' or self.read(state + 16, 2) != bytes([len(packet), 3]) or
                self.read(state + 18, len(packet)) != packet or self.u32(0x200153b8) != 0x0801ba47):
            raise AssertionError('Source 6004 packet, timer callback or RX drain bound changed')
        timer = [r for r in self.trace if r['boundary'] == 'TIM_TimeBaseInit']
        if len(timer) != 1 or timer[0]['prescaler'] != 16799 or timer[0]['period'] != 49:
            raise AssertionError('Source 5 ms keepalive callback period changed')
        self.trace.clear(); self._sent.clear(); self._expected_tx = packet
        self._stimulus, self._received = b'\xa1\xb2\xc3', 0
        events = []
        for tick in range(1, 162 + len(packet)):
            before = len(self.trace)
            self.call(0x0801ba46)
            events.extend({'tick': tick, **event} for event in self.trace[before:])
        expected = [{'tick': 161 + i, 'boundary': 'UART_TX', 'byte': byte} for i, byte in enumerate(packet)]
        expected += [{'tick': 161 + len(packet), 'boundary': 'UART_RX', 'ticks': 1000, 'available': True}] * 3
        if events != expected or self._received != 3 or bytes(self._sent) != packet or self.read(state + 8, 1) != b'\x05':
            raise AssertionError('Keepalive timer/byte pacing/RX drain differs from the executed firmware')
        return {'recordHex': record.hex(), 'packetHex': packet.hex(), 'intervalMs': 800,
                'callbackPeriodMsAtDeclaredClock': 5, 'firstTransmitTick': 161,
                'firstTransmitMsAtDeclaredClock': 805, 'receiveDrainBytes': 3,
                'syntheticIrqCalls': 161 + len(packet), 'events': events}

    def disable_keepalive(self, record):
        if record != b'\x60\x04\x00\x00\x00':
            raise ReplayError('Wrong source keepalive-disable record')
        self.trace.clear()
        self.write(self.INPUT, record[2:] + bytes(32)); self.write(self.OUTPUT, bytes(32))
        if self.call(0x080213b2, 3, self.INPUT, self.OUTPUT) != 2:
            raise AssertionError('Source keepalive-disable did not return')
        if (self.read(0x20015428, 1) != b'\x00' or self.read(0x20002a98, 1) != b'\x00' or
                any(r['boundary'] in ('UART_TX', 'UART_RX') for r in self.trace)):
            raise AssertionError('Source keepalive-disable sent bytes or retained an active timer')

    def _gpio_bit(self):
        port, mask, bit = (self.reg(i) for i in range(3))
        if port != 0x40010c00 or mask != 1024 or bit not in (0, 1):
            raise ReplayError('Unreviewed pulse GPIO bit write')
        self.trace.append({'boundary': 'GPIO_BitWrite', 'port': port, 'mask': mask, 'bit': bit})

    def _clock_enable(self):
        value = self.reg(0)
        if value != 0x603: raise ReplayError('Unreviewed pulse GPIO clock enable')
        self.trace.append({'boundary': 'GPIO_ClockEnable', 'encodedBit': value})

    def _systick_arm(self):
        value = self.reg(0)
        if value not in (15000, 25000, 35000): raise ReplayError('Unreviewed source pulse delay')
        self.trace.append({'boundary': 'SysTick_DelayArmUs', 'value': value})

    def _systick_wait(self):
        self.trace.append({'boundary': 'SysTick_DelayWait'})

    def _uart_apply(self):
        if self.reg(0) != 0 or self.reg(1) != 0x200029b4:
            raise ReplayError('Unreviewed UART configuration leaf')
        self.trace.append({'boundary': 'UART_Apply', 'channel': 0,
                           'settingsWords': [self.u32(0x200029b4 + i * 4) for i in range(4)]})

    def _uart_disable(self):
        if self.reg(0) != 0x40004800: raise ReplayError('Unreviewed UART disable')
        self.trace.append({'boundary': 'UART_Disable', 'uart': self.reg(0)})

    def _uart_clear_flag(self):
        if self.reg(0) != 0x40004800 or self.reg(1) not in (0x305, 0x306):
            raise ReplayError('Unreviewed UART flag clear')
        self.trace.append({'boundary': 'UART_ClearFlag', 'uart': self.reg(0), 'flag': self.reg(1)})

    def _timer_control(self, address):
        self.trace.append({'boundary': f'timer-leaf-{address:08x}', 'arguments': [self.reg(i) for i in range(2)]})

    def _delay(self):
        value = self.reg(0)
        if not 0 <= value <= 60_000_000: raise ReplayError('Unbounded synthetic delay')
        self.trace.append({'boundary': 'delayUs', 'value': value})

    def _tx(self):
        value = self.reg(0)
        if value > 255 or len(self._sent) >= len(self._expected_tx): raise ReplayError('Unexpected UART TX byte')
        self._sent.append(value)
        self.trace.append({'boundary': 'UART_TX', 'byte': value})
        return 1

    def _rx(self):
        ticks = self.reg(1)
        if ticks > 65535: raise ReplayError('Unbounded UART polling')
        available = len(self._sent) == len(self._expected_tx) and self._received < len(self._stimulus)
        self.trace.append({'boundary': 'UART_RX', 'ticks': ticks, 'available': available})
        if available:
            self.write(self.reg(0), self._stimulus[self._received:self._received + 1]); self._received += 1
        return int(available)

    def _gpio(self):
        self.trace.append({'boundary': 'GPIO_Init', 'arguments': [self.reg(i) for i in range(4)]})

    def _timer_reset(self):
        self.trace.append({'boundary': 'TIM_DeInit', 'timer': self.reg(0)})

    def _timer_base(self):
        p = self.reg(1)
        self.trace.append({'boundary': 'TIM_TimeBaseInit', 'timer': self.reg(0),
                          'prescaler': int.from_bytes(self.read(p, 2), 'little'),
                          'period': self.u32(p + 8)})


class NativeFirmwareExchange:
    """Execute native queuing, EndCombination and the actual native reply parser.

    Unlike AbstractCapture there is NO 0x53d0 ACK hook here. The single explicit
    RPC shim copies bounded command bytes between two guest address spaces; the
    pinned Cortex-M consumer produces the reply bytes. UART/timer leaves remain
    synthetic and recorded, so even a full C1 fixture is not vehicle evidence.
    """
    def __init__(self, abstract, firmware):
        if not isinstance(abstract, bytes) or hashlib.sha256(abstract).hexdigest() != ABSTRACT_SHA256:
            raise ReplayError('Wrong native abstract-layer identity')
        self.native = NativeSandbox({ABSTRACT: abstract})
        self.firmware = MiniTimingSandbox(firmware)
        self.native.write(self.native.symbol(ABSTRACT, 'ConnectorType'), b'\x60')
        self.stimulus, self.records, self.primary_pulses = b'', [], None
        self.native.bind('SendDataToSmartBoxGetBinaryString', self._rpc, indirect=True, library=ABSTRACT)

    def _rpc(self, ptr, size, out, capacity, wait, *_):
        if not 2 <= size <= 512 or not 1 <= capacity <= 513 or wait != 5000:
            raise ReplayError('Unreviewed source RPC span or host observation budget')
        raw = self.native.read(ptr, size)
        m = self.firmware
        before = len(m.trace)
        if raw[:2] == b'\x60\x03':
            m.interval(raw)
            reply = m.read(m.OUTPUT + 16, 2)
        elif raw[:2] == b'\x60\x02':
            reply = m.literal_baud(raw)
        elif (raw[:4] == b'\x61\x09\xff\x01' and 5 <= len(raw) - 5 <= 50 and raw[4] == len(raw) - 5):
            reply = m.exchange(raw[5:], self.stimulus)
        elif self.primary_pulses is not None:
            low, high = self.primary_pulses
            expected = b'\x60\x07\x00' + low.to_bytes(2, 'big') + b'\x60\x07\xff' + high.to_bytes(2, 'big')
            packet = bytes.fromhex('8113f08105')
            if raw != expected + b'\x64\x02\xff\x01\x05' + packet:
                raise ReplayError('Unexpected native PRIMARY/pulse transaction')
            reply = m.exchange(packet, self.stimulus, primary_pulses=self.primary_pulses)
        else:
            raise ReplayError('Unexpected serialized command in source RPC bridge')
        if len(reply) > capacity:
            raise ReplayError('Firmware result exceeds the native requested receive buffer')
        self.native.write(out, reply)
        self.records.append({'commandHex': raw.hex(), 'firmwareResultHex': reply.hex(),
                             'nativeReceiveCapacity': capacity, 'nativeHostBudgetMs': wait,
                             'firmwareTrace': list(m.trace[before:])})
        return len(reply)

    def combined(self, stimulus, *, primary_pulses=None):
        from unicorn.arm64_const import UC_ARM64_REG_S0
        if not isinstance(stimulus, bytes) or len(stimulus) > 32:
            raise ReplayError('Unbounded source-entry UART fixture')
        if primary_pulses is not None and primary_pulses not in ((25, 25), (35, 15)):
            raise ReplayError('Unreviewed source PRIMARY pulse fixture')
        self.firmware.reset(); self.records.clear(); self.stimulus = stimulus; self.primary_pulses = primary_pulses
        n = self.native
        if primary_pulses is not None:
            n.uc.reg_write(UC_ARM64_REG_S0, struct.unpack('<I', struct.pack('<f', 10416.))[0])
            if n.call(ABSTRACT, 'SetEcuBaudRate') != 0 or self.records[-1]['firmwareResultHex'] != 'ff00':
                raise AssertionError('Actual native literal-baud ACK parser changed')
        n.uc.reg_write(UC_ARM64_REG_S0, struct.unpack('<I', struct.pack('<f', 5.))[0])
        setting = n.call(ABSTRACT, 'SetEcuCommTimeInterval', 60, 600)
        if setting != 0 or self.records[-1]['firmwareResultHex'] != 'ff00':
            raise AssertionError('Actual native ACK parser changed')
        packet = bytes.fromhex('8113f08105')
        source = n.allocate(len(packet), packet); out = n.allocate(256)
        before = len(self.records)
        n.call(ABSTRACT, 'BeginCombination')
        if primary_pulses is None:
            queued = n.call(ABSTRACT, 'KwpSendDataToEcuGetAnswer', 255, out, 255, 1, source)
        else:
            if n.call(ABSTRACT, 'SetCommunicationLineVoltage', 2, *primary_pulses) != 0:
                raise AssertionError('Source pulse queuing failed')
            queued = n.call(ABSTRACT, 'CommSubFunc0003', out, 255, 255, 1, source)
        if queued != 0 or len(self.records) != before:
            raise AssertionError('Queued source send/pulse performed I/O before EndCombination')
        word = n.call(ABSTRACT, 'EndCombination') & 65535
        signed = int.from_bytes(word.to_bytes(2, 'little'), 'little', signed=True)
        return {'uartStimulusHex': stimulus.hex(), 'queuedWord': queued, 'endLowWord': word,
                'endSignedLowWord': signed, 'outputHex': n.read(out, signed).hex() if signed > 0 else '',
                'rpcCalls': list(self.records)}


def run(abstract, firmware):
    capture, consumer = AbstractCapture(abstract), MiniTimingSandbox(firmware)
    # The actual lifecycle uses 5/60/500 or 600; extra vectors establish rounding,
    # unequal fields and BYTE truncation, rather than naming three equal values.
    vectors = [(5., 60, 600), (5., 60, 500), (0.1, 10, 10), (1.25, 65, 605),
               (19.95, 123, 4567), (255., 2545, 655345), (256., 10, 100), (6553.4, 60, 600)]
    results = []
    for tx, delay, receive in vectors:
        consumer.reset()
        port, expected = IntervalRecord.from_arguments(tx, delay, receive)
        native = capture.call('SetEcuCommTimeInterval', delay, receive, f32=tx)
        if native != port: raise AssertionError('Native 6003 serialization differs from the bounded port')
        observed = consumer.interval(native)
        results.append({'arguments': [tx, delay, receive], 'recordHex': native.hex(), **observed.report()})
    # Independently exercise actual per-byte and request pacing consumers.
    consumer.reset(); consumer.interval(bytes.fromhex('6003003206003c'))
    timer = consumer.normal_rx_timer(); consumer.trace.clear()
    packet = bytes.fromhex('8113f08105'); reply = bytes.fromhex('83f013c1e98fbf')
    traces = []
    for stimulus in (reply, reply, b''):
        consumer.trace.clear()
        raw = consumer.exchange(packet, stimulus)
        traces.append({'uartStimulusHex': stimulus.hex(), 'firmwareReplyHex': raw.hex(), 'trace': list(consumer.trace)})
    tx_delays = [r['value'] for r in traces[0]['trace'] if r['boundary'] == 'delayUs']
    rx_ticks = [r['ticks'] for r in traces[0]['trace'] if r['boundary'] == 'UART_RX' and r['available']]
    if tx_delays != [5000] * 4 or rx_ticks != [600] * len(reply):
        raise AssertionError('Actual UART timing use does not match the 6003 record')
    if [r['value'] for r in traces[1]['trace'] if r['boundary'] == 'delayUs'] != [60000] + [5000] * 4:
        raise AssertionError('Actual repeated-request delay does not match the source record')
    keepalives = []
    for payload in ('2100', '0100', '3e', '3e01', '2102', '21e1'):
        body = bytes([0x80 + len(bytes.fromhex(payload)), 0x13, 0xf0]) + bytes.fromhex(payload)
        wire = body + bytes([sum(body) & 255])
        consumer.reset(); consumer.interval(bytes.fromhex('6003003206003c'))
        keepalives.append(consumer.keepalive(capture.keepalive(wire), wire))
        consumer.disable_keepalive(capture.keepalive(b''))
    baud = capture.call('SetEcuBaudRate', f32=10416.)
    if baud != bytes.fromhex('6002000028b000'):
        raise AssertionError('Literal source 10416 baud encoding changed')
    bridge = NativeFirmwareExchange(abstract, firmware)
    native_results, primary_results = [], []
    for name, data in [('completeC1', reply), ('noData', b''), ('corruptChecksum', reply[:-1] + b'\xbe'),
                       ('partialReply', reply[:-1]), ('wrongEcu', bytes.fromhex('83f014c1e98fc0')),
                       ('negativeService', bytes.fromhex('83f0137f8122a8'))]:
        native_results.append({'case': name, **bridge.combined(data)})
        primary = {'case': name, **bridge.combined(data, primary_pulses=(25, 25))}
        if primary['endSignedLowWord'] != native_results[-1]['endSignedLowWord'] or primary['outputHex'] != native_results[-1]['outputHex']:
            raise AssertionError('Native PRIMARY/6402-alias result differs from KWP/6109')
        primary_results.append(primary)
    unequal = bridge.combined(reply, primary_pulses=(35, 15))
    for row, pulses in [(r, (25, 25)) for r in primary_results] + [(unequal, (35, 15))]:
        events = row['rpcCalls'][-1]['firmwareTrace']
        if ([r['value'] for r in events if r['boundary'] == 'SysTick_DelayArmUs'] != [v * 1000 for v in pulses]
                or [r['bit'] for r in events if r['boundary'] == 'GPIO_BitWrite'] != [1, 0, 1, 1]
                or [r['settingsWords'] for r in events if r['boundary'] == 'UART_Apply'] != [[10416, 0, 0, 0]]):
            raise AssertionError('Actual source pulse consumption or declared UART settings changed')
    if [(r['case'], r['endSignedLowWord']) for r in native_results[:2]] != [('completeC1', 9), ('noData', -70)]:
        raise AssertionError('Actual EndCombination reply/failure mapping changed')
    return {'kind': 'offline-native-firmware-timing-comparison', 'formatVersion': 1,
            'nativeLibrarySha256': ABSTRACT_SHA256, 'publicFirmwareSha256': FIRMWARE_SHA256,
            'publicFirmwareBytes': FIRMWARE_BYTES, 'publicZipSha256': PUBLIC_ZIP_SHA256,
            'publicZipUrl': PUBLIC_ZIP_URL, 'publicReference': PUBLIC_REFERENCE,
            'firmwareInstructionsExecuted': True, 'nativeInstructionsExecuted': True,
            'boardEncodingDomain': 96, 'source10416RecordHex': baud.hex(),
            'intervalVectors': results, 'normalUartTimer': timer, 'exchangeVectors': traces,
            'keepaliveVectors': keepalives, 'sourceKeepaliveDisableRecordHex': '6004000000',
            'nativeCombinedResults': native_results, 'nativePrimaryResults': primary_results,
            'nativeUnequalPulseComponent': unequal,
            'primaryLinkage': {'consumer': 'libTOYOTA_COMM.so:OpenAbstractCommDll@0001e860..0001e884',
                               'pointerCell': 'CommSubFunc_00000003', 'abstractExport': 'CommSubFunc0003',
                               'serializedOpcodeHex': '6402', 'firmwareAliasTable': '0803c2ac',
                               'firmwareAliasTargetHex': '6109'},
            'resultSha256': hashlib.sha256(canonical([results, timer, traces, keepalives, baud.hex(), native_results, primary_results, unequal])).hexdigest(),
            'fakeBoundaries': ['Serializer-only vectors: native boundary returns synthetic ACK; combined vectors: explicit guest-to-guest RPC copy',
                               'GPIO/clock/timer/UART configuration peripheral leaves record arguments only; physical pin/polarity/baud-clock behavior not modeled',
                               'UART byte receive/transmit, SysTick completion, delays and keepalive IRQs are explicit synthetic stimuli'],
            'provesMic3313Equivalence': False, 'physicalValidation': False,
            'productionExecutableDefinitions': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abstract', required=True, type=Path)
    parser.add_argument('--firmware', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    report = run(args.abstract.read_bytes(), args.firmware.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical(report) + b'\n')
    print(json.dumps({k: report[k] for k in ('kind', 'resultSha256', 'productionExecutableDefinitions')}))


if __name__ == '__main__':
    main()
