"""Bounded source lifecycle control-flow model, NOT an adapter driver or link approval.

SYSP FastKWPEnterMode @109b8 uses EndCombination's signed 16-bit result,
not the queued CommSubFunc return. Only a negative combined result selects the
alternate ToyotaKwp... API; a zero result retries the primary API. Negative ECU
service 7F is its own 00FF result and does NOT select transport-5 ISO fallback.

The source's zero-result success is preserved as native_status=0 but explicitly
has_positive_reply=False. It can never establish initialization in the app: the
app must independently validate complete framing, checksum, ECU and C1 reply.
No native timing argument is renamed P1/P2/P3/P4 by this model.
"""
from dataclasses import dataclass

from toyota_lifecycle import FastKwpLifecycle
from toyota_transport import ConsumerCall


def signed16(value):
    if type(value) is not int or not -0x8000 <= value <= 0xffffffff:
        raise ValueError('Not a bounded native return word')
    value &= 65535
    return value - 65536 if value & 32768 else value


@dataclass(frozen=True)
class CombinedReply:
    """Explicit fake combined-adapter boundary, not a wire or real response."""
    result: int
    service: int | None = None

    def __post_init__(self):
        value = signed16(self.result)
        if self.service is not None and (type(self.service) is not int or not 0 <= self.service <= 255):
            raise ValueError('Synthetic service is not a byte')
        if value > 0 and self.service is None:
            raise ValueError('A positive synthetic adapter result needs an explicit service')


@dataclass(frozen=True)
class FastEntryResult:
    native_status: int
    has_positive_reply: bool
    consumed_replies: int
    calls: tuple[ConsumerCall, ...]

    def wants_iso_fallback(self, native_transport):
        if type(native_transport) is not int or native_transport not in (1, 5):
            raise ValueError('Not a source fast-entry transport')
        # SystemEnterPart @ff0c compares the LOW SIXTEEN bits with FFFF.
        # In particular the negative-ECU-service return FFFE does not match.
        return native_transport == 5 and self.native_status & 65535 == 65535


def fast_entry_trace(lifecycle, replies, *, connector=16):
    """Exact single-template class with at most two pulse attempts per pin.

    Every pair/pulse/start packet is retained; no next pin after an ECU negative
    service, no 10416->10400 normalization, no sibling source entry is selected.
    All real source transport-1/5 entries have one pin pair and one init template;
    the bounded multi-pair path remains testable without claiming source coverage.
    """
    if not isinstance(lifecycle, FastKwpLifecycle) or not isinstance(replies, tuple) or not 1 <= len(replies) <= 4 * len(lifecycle.entry.pins):
        raise ValueError('Expected an exact lifecycle and bounded explicit adapter results')
    if any(not isinstance(reply, CombinedReply) for reply in replies):
        raise ValueError('Untyped combined reply')
    calls = []
    at = 0
    positive = False
    wire = lifecycle.entry.packet(lifecycle.initial.request).hex()
    pulse = ConsumerCall('SetCommunicationLineVoltage', (2, *lifecycle.entry.timing[3:]))

    def combined(alternative):
        nonlocal at
        if at >= len(replies):
            raise ValueError('Undeclared adapter result; no implicit success or retry reply')
        calls.extend((ConsumerCall('BeginCombination', ()), pulse,
            ConsumerCall('ToyotaKwpSendDataToEcuGetMultiFrameAnswer', (300, wire, 255, 50)) if alternative else
            ConsumerCall('CommSubFunc_00000003', (255, 255, 1, wire)),
            ConsumerCall('EndCombination', ())))
        value = replies[at]; at += 1
        return value

    def finish(status):
        if at != len(replies):
            raise ValueError('Unused adapter results; this is not the claimed source control-flow trace')
        return FastEntryResult(status, positive, at, tuple(calls))

    for pin in range(len(lifecycle.entry.pins)):
        calls.extend(lifecycle.entry.configuration_calls(connector=connector, pin_pair_index=pin))
        for attempt in range(2):
            if attempt:
                calls.append(ConsumerCall('usleep', (500,)))
            reply = combined(False)
            if signed16(reply.result) < 0:
                reply = combined(True)
            result = signed16(reply.result)
            if result > 0 and reply.service != 0x7f:
                positive = True
                return finish(-2 if result & 65535 == 255 else 0)
            # Native rejects a negative ECU service (00FF) separately from the
            # negative return of the adapter API. Only the latter selects ISO.
            if result > 0:
                result = 255
            if attempt == 0:
                continue
            if result <= 0 and pin + 1 < len(lifecycle.entry.pins):
                break
            return finish(-2 if result == 255 else -1 if result < 0 else 0)
    return finish(0)


def reentry_is_selected(native_transport, sender_result):
    """DTCF @14010..1402c: signed negative or 7FFF, only native transports 1/2/5."""
    if type(native_transport) is not int or not 0 <= native_transport <= 255:
        raise ValueError('Native transport must be a byte')
    value = signed16(sender_result)
    return native_transport in (1, 2, 5) and (value < 0 or value == 0x7fff)


def read_reentry_trace(native_transport, sender_result, saved_context_byte):
    """DTCF @1402c..1409c: temporary sentinel, exit, enter, restore, ONE read.

    The two lifecycle return values are ignored by the native caller, not
    promoted to proof. The portable app is deliberately stricter: reinitializing
    must succeed and malformed/wrong-ECU/ambiguous evidence cannot trigger retry.
    This is a context byte at +174d1, not a wire/session-control command.
    """
    if type(saved_context_byte) is not int or not 0 <= saved_context_byte <= 255:
        raise ValueError('Saved source-context byte must be a byte')
    if not reentry_is_selected(native_transport, sender_result):
        return ()
    return (ConsumerCall('SetContext174d1', (255,)), ConsumerCall('SystemExitPart', ()),
            ConsumerCall('SystemEnterPart', ()), ConsumerCall('SetContext174d1', (saved_context_byte,)),
            ConsumerCall('RetrySameReadOnce', ()))


@dataclass(frozen=True)
class IsoEntryResult:
    native_status: int
    consumed_handshakes: int
    calls: tuple[ConsumerCall, ...]


def iso_entry_trace(lifecycle, handshakes, *, initial_result=1, connector=16):
    """SYSP ISOEnterMode @10d54, with explicit address-init result words.

    A zero LOW word means address-init succeeded. Configuration is once per
    declared pin pair and there are at most two address-init attempts per pair.
    It then sends the SAME entry's initial frame(s), not a sibling transport-2
    definition, never the diagnostic-session 105F or a generic address 33.
    These are source API call contracts only; actual slow-init waveform and
    MIC3313 API equivalence are not approved by this control-flow model.
    """
    if (not isinstance(lifecycle, FastKwpLifecycle) or type(handshakes) is not tuple or
            not 1 <= len(handshakes) <= 2 * len(lifecycle.entry.pins)):
        raise ValueError('Exact lifecycle and bounded address-init result words required')
    for value in handshakes: signed16(value)
    signed16(initial_result)
    at = 0; calls = []
    def finish(status):
        if at != len(handshakes): raise ValueError('Unused slow-init results')
        return IsoEntryResult(status, at, tuple(calls))
    for pair in range(len(lifecycle.entry.pins)):
        calls.extend(lifecycle.entry.configuration_calls(connector=connector, pin_pair_index=pair))
        for _ in range(2):
            if at == len(handshakes): raise ValueError('Undeclared slow-init result')
            calls.append(ConsumerCall('SendCmdAndGetReceiveAddressInit', (lifecycle.entry.runtime107e4, 0, 0, 0x55, 0)))
            result = handshakes[at]; at += 1
            if result & 65535 == 0:
                calls.append(ConsumerCall('SendCmdAndGetReceiveInitial', (255, lifecycle.initial.request.hex(), lifecycle.entry.flags, 0)))
                return finish(-1 if signed16(initial_result) < 0 else 0)
    return finish(-1)
