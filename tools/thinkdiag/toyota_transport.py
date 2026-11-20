"""Source-backed COMM fast-KWP configuration/packet port, not an adapter driver.

InitCommSet_Ex @18c84 -> InitKWPISOMode @19830 consumes native transport 1/5
and the low-nibble-1 SYSTEMENTER schema. The API names/argument positions below
are literal source semantics. In particular the three SetEcuCommTimeInterval
arguments are NOT renamed P1/P2/P3. Mapping those APIs to MIC3313 ST/VT commands,
reply addressing, SYSP fast/slow alternatives and production emission remain
separate requirements. This module grants no operation or ECU proof.
"""
from dataclasses import dataclass
import re
import struct

from toyota_entries import Entry, EntryIndex

COMM_SHA256 = 'e4f02f44c116b30623ecba3e6fe2e3c70b97a976d61c585b1049888ed0d2f7f2'
CONSUMER_RECORDS = (
    'libTOYOTA_COMM.so@00018c84#fast-configuration-dispatch',
    'libTOYOTA_COMM.so@00019830#kwp-iso-configuration',
    'libTOYOTA_COMM.so@00016124#kwp-packet-header',
    'libTOYOTA_COMM.so@00016414#packet-payload',
    'libTOYOTA_COMM.so@000167dc#packet-checksum',
)


def _integer(value, low, high, field):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f'{field}: expected an exact integer in {low}..{high}')
    return value


def _field(fields, key, size):
    value = fields.get(key)
    if (not isinstance(value, str) or len(value) != size * 2
            or re.fullmatch('[0-9a-f]*', value) is None):
        raise ValueError(f'{key}: malformed or truncated source field')
    return bytes.fromhex(value)


@dataclass(frozen=True)
class ConsumerCall:
    api: str
    arguments: tuple

    def report(self):
        return {'api': self.api, 'arguments': list(self.arguments)}


@dataclass(frozen=True)
class FastKwpEntry:
    """A closed projection of one exact native transport-1/5 source entry.

    send_address is the original little-endian runtime107b0 byte sequence;
    MultiPackFunc reverses those two bytes on the wire. It is not a CAN ID,
    inferred receive header or authorization to use another entry/selector.
    """
    source_record: str
    selector: int
    transport: int
    flags: int
    baud: int
    send_address: bytes
    runtime107e4: int
    pins: tuple[tuple[int, int], ...]
    timing: bytes
    initial_templates: tuple[int, ...]
    keepalive_templates: tuple[int, ...]
    exit_templates: tuple[int, ...]

    def __post_init__(self):
        if not isinstance(self.source_record, str) or not re.fullmatch(r'SYSTEMENTER\.BIN@[0-9a-f]{8}', self.source_record):
            raise ValueError('Exact SYSTEMENTER source record required')
        _integer(self.selector, 0, 255, 'selector')
        _integer(self.transport, 0, 255, 'native transport')
        _integer(self.flags, 0, 255, 'packet flags')
        if (self.transport, self.flags) not in ((1, 0x81), (5, 0x33)) or self.selector & 15 != 1:
            raise ValueError('Not the native transport-1/5 fast-KWP entry class')
        _integer(self.baud, 1, 0x7fffffff, 'source baud')
        _integer(self.runtime107e4, 0, 255, 'runtime107e4')
        if not isinstance(self.send_address, bytes) or len(self.send_address) != 2 or not isinstance(self.timing, bytes) or len(self.timing) != 5:
            raise ValueError('Exact address/timing field widths required')
        if not isinstance(self.pins, tuple) or not 1 <= len(self.pins) <= 127:
            raise ValueError('Invalid source pin pairs')
        for pair in self.pins:
            if not isinstance(pair, tuple) or len(pair) != 2:
                raise ValueError('Invalid source pin pair')
            for pin in pair:
                _integer(pin, 0, 255, 'source pin')
        for bindings in (self.initial_templates, self.keepalive_templates, self.exit_templates):
            if not isinstance(bindings, tuple) or len(bindings) > 255:
                raise ValueError('Invalid source template list')
            for template in bindings:
                _integer(template, 0, 65535, 'source template')

    @classmethod
    def from_entry(cls, entry):
        if not isinstance(entry, Entry):
            raise ValueError('Fast KWP conversion needs a framed source entry')
        if not isinstance(entry.fields, dict) or set(entry.fields) != {'runtime107b0', 'runtime107e4', 'pinPairs', 'runtime107f0'}:
            raise ValueError('Unexpected or missing fast-KWP source fields')
        address = _field(entry.fields, 'runtime107b0', 2)
        extra = _field(entry.fields, 'runtime107e4', 1)[0]
        timing = _field(entry.fields, 'runtime107f0', 5)
        raw_pins = entry.fields['pinPairs']
        if not isinstance(raw_pins, str) or not 4 <= len(raw_pins) <= 508 or len(raw_pins) % 4:
            raise ValueError('Missing, odd or oversized source pin-pair list')
        pins = _field(entry.fields, 'pinPairs', len(raw_pins) // 2)
        _integer(entry.descriptor, 0, 255, 'selector')
        _integer(entry.baud, 1, 0x7fffffff, 'source baud')
        for bindings in (entry.init, entry.keepalive, entry.exit):
            if not isinstance(bindings, tuple) or len(bindings) > 255:
                raise ValueError('Invalid source template list')
            for template in bindings:
                _integer(template, 0, 65535, 'source template')
        return cls(entry.source_id, entry.descriptor, entry.transport, entry.flags, entry.baud,
                   address, extra, tuple(zip(pins[::2], pins[1::2])), timing,
                   entry.init, entry.keepalive, entry.exit)

    def configuration_calls(self, *, connector, pin_pair_index):
        """Literal InitKWPISOMode API sequence, not STN commands/P1-P4 metadata.

        The connector and pin-pair index are explicit runtime inputs. A second
        pin pair is not tried automatically. The configuration consumer does not
        inspect the API return values before EndCombination; this projection
        does NOT reinterpret that as a successful adapter initialization.
        """
        _integer(connector, 0, 255, 'connector')
        _integer(pin_pair_index, 0, len(self.pins) - 1, 'source pin-pair index')
        byte0, byte1, byte2 = self.timing[:3]
        # InitCommSet_Ex shifts a FOUR-byte load, not a three-byte slice.
        # EnableLLine masks to u8; the alternate SelectPIN call receives the
        # entire shifted u16 register, including timing[3] in its upper byte.
        # Do not erase this ABI detail or infer an electrical role from it.
        shifted_word = int.from_bytes(self.timing[2:4], 'little')
        first = ConsumerCall('EnableLLine', (byte2,)) if connector == 0x10 else ConsumerCall(
            'SelectPIN', (connector, 14, shifted_word, 0, 0))
        return (
            ConsumerCall('BeginCombination', ()),
            first,
            ConsumerCall('SelectPIN', (connector, 1, 0, *self.pins[pin_pair_index])),
            ConsumerCall('SetCommunicationMaximumWaitTime', (15000,)),
            ConsumerCall('SetEcuCommunicationVoltageLogic', (255, 255)),
            ConsumerCall('SetEcuBaudRate', (struct.unpack('<f', struct.pack('<f', self.baud))[0],)),
            ConsumerCall('SetCommunicationParameter', (1, 8, 1, 0)),
            ConsumerCall('SetEcuCommTimeInterval', (float(byte0), byte1, 500 if self.baud == 9600 else 600)),
            ConsumerCall('EndCombination', ()),
        )

    def packet(self, payload):
        return fast_kwp_packet(self.send_address, self.flags, payload)

    def report(self):
        return {
            'sourceRecord': self.source_record, 'selector': self.selector,
            'nativeTransport': self.transport, 'flags': self.flags, 'baud': self.baud,
            'sendAddressSourceHex': self.send_address.hex(), 'runtime107e4': self.runtime107e4,
            'pinPairs': [list(pair) for pair in self.pins], 'sourceTimingHex': self.timing.hex(),
            'initialTemplates': list(self.initial_templates), 'keepaliveTemplates': list(self.keepalive_templates),
            'exitTemplates': list(self.exit_templates),
            'sourceRecords': [self.source_record, *CONSUMER_RECORDS],
            'consumerLibrarySha256': COMM_SHA256,
        }


def selected_fast_kwp(index, *, region, function, selector):
    """No lower-nibble, region, function, protocol or sibling-selector fallback."""
    if not isinstance(index, EntryIndex):
        raise ValueError('Exact source SYSTEMENTER index required')
    _integer(region, 1, 3, 'source region')
    _integer(function, 0, 0xffffffff, 'source function')
    _integer(selector, 0, 255, 'source selector')
    entry = index.select(region, function, selector)
    if entry is None:
        raise ValueError('No exact SYSTEMENTER region/function/full-selector join')
    return FastKwpEntry.from_entry(entry)


def fast_kwp_packet(source_address, flags, payload):
    """MultiPackFunc's exact source 81/33 short addressed packet, with checksum.

    Native addition above 63 payload bytes can change the header's addressing
    bits instead of describing a valid short addressed frame. Reject it rather
    than silently replacing source flag 81/33 with extended-length flag 80.
    No source in this class is reinterpreted as a generic CAN/OBD transport.
    """
    if not isinstance(source_address, bytes) or len(source_address) != 2:
        raise ValueError('Fast KWP needs the two exact source address bytes')
    if type(flags) is not int or flags not in (0x81, 0x33):
        raise ValueError('Not a source fast-KWP 81/33 packet variant')
    if not isinstance(payload, bytes) or not 1 <= len(payload) <= 63:
        raise ValueError('Source short addressed KWP packet needs 1..63 payload bytes')
    frame = bytes([0x80 + len(payload), source_address[1], source_address[0]]) + payload
    return frame + bytes([sum(frame) & 255])
