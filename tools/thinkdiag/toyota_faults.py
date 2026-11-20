"""Port of DTCF GetDtcStateResult and GetDTCCodeFromAnsP3P4.

The native receive buffer here starts AFTER the positive service byte, not
necessarily after an identifier/subfunction. Keep that origin when compiling
DiagnosticRead windows. State IDs refer to TEXT_EN.GAG; they are not fabricated
UDS statuses. Returned code integers still need the exact ECU code-table join.

As in the app's ordinary fault decoder, malformed/truncated frames are rejected
atomically, even where the original native loop would ignore a partial tail.
No request, protocol, condition result or ECU identity is inferred here.
"""
from dataclasses import dataclass

STATE_CURRENT = 0xffff0050
STATE_HISTORY = 0xffff0051
STATE_PENDING = 0xffff0052
STATE_READNESS = 0xffff0053  # Preserve the spelling/ID in the source text.
STATE_PERMANENT = 0xffff0054
STATE_FAILED = 0xffff0055
STATE_CONFIRMED = 0xffff0056


def state_ids(query_kind, status, availability_mask=255):
    if (type(query_kind) is not int or type(status) is not int or type(availability_mask) is not int
            or not 0 <= query_kind <= 255 or not 0 <= status <= 255 or not 0 <= availability_mask <= 255):
        raise ValueError('Invalid native fault-state input')
    if query_kind in range(1, 8):
        return ((STATE_CURRENT, STATE_HISTORY, STATE_PENDING, STATE_READNESS,
                 STATE_PERMANENT, STATE_FAILED, STATE_CONFIRMED)[query_kind - 1],)
    if query_kind == 8:
        return (STATE_CURRENT, STATE_HISTORY) if status <= 0x28 else (STATE_HISTORY,)
    if query_kind == 9:
        return (STATE_CURRENT if status & 0x10 else STATE_HISTORY,)
    if query_kind == 10:
        bits = status & availability_mask
        return ((STATE_FAILED,) if bits & 3 else ()) + ((STATE_PENDING,) if bits & 4 else ()) + ((STATE_CONFIRMED,) if bits & 8 else ())
    return ()


@dataclass(frozen=True)
class NativeFaultLayout:
    count_index: int
    record_start: int
    record_bytes: int
    assembly_kind: int
    count_is_presence: bool = False
    # DTCF exempts native runtime+18 lowbits==2 from the kind-3 override.
    mode_lowbits: int = 0

    def __post_init__(self):
        if (any(type(v) is not int or v not in range(256) for v in
                (self.count_index, self.record_start, self.record_bytes, self.assembly_kind))
                or type(self.count_is_presence) is not bool or type(self.mode_lowbits) is not int or not 0 <= self.mode_lowbits <= 7):
            raise ValueError('Invalid native fault layout fields')
        minimum = 2 if self.assembly_kind in (5, 6) else 4 if self.assembly_kind == 7 else 1
        code_bytes = self.record_bytes - (1 if self.assembly_kind in (5, 6) else 3 if self.assembly_kind == 7 else 0)
        if self.assembly_kind not in range(1, 8) or not minimum <= self.record_bytes <= 32 or not 1 <= code_bytes <= 4:
            raise ValueError('Invalid native fault record width or assembly kind')

    def decode(self, payload, query_kind, availability_mask=255):
        if not isinstance(payload, bytes) or len(payload) > 4096:
            raise ValueError('Invalid native fault payload')
        # Validate state inputs even when this is an empty/no-fault payload.
        state_ids(query_kind, 0, availability_mask)
        count_at, start = self.count_index, self.record_start
        if query_kind == 3 and self.mode_lowbits != 2:
            count_at, start = 255, 0
        if start > len(payload) or (count_at != 255 and count_at >= len(payload)):
            raise ValueError('Short native fault count/start')
        body = payload[start:]
        if len(body) % self.record_bytes:
            raise ValueError('Truncated native fault record; no partial results accepted')
        rows = [body[at:at + self.record_bytes] for at in range(0, len(body), self.record_bytes)]
        if count_at != 255:
            count = payload[count_at]
            if not self.count_is_presence and count > len(rows):
                raise ValueError('Native fault count exceeds complete records')
            if count == 0:
                return ()
        result = []
        for row in rows:
            code_data = row[:-1] if self.assembly_kind in (5, 6) else row[:-3] if self.assembly_kind == 7 else row
            code = int.from_bytes(code_data, 'big')
            status = row[-1] if self.assembly_kind in (5, 6) else (code >> 8) & 0xf0 if self.assembly_kind == 3 else 0
            if not code:
                continue
            for state in state_ids(query_kind, status, availability_mask):
                pair = (state, code)
                if pair not in result:
                    result.append(pair)
        return tuple(result)


@dataclass(frozen=True)
class NativeP5FaultLayout:
    """P5 core record projection, not its auxiliary freeze-frame/mask workflow.

    GetDTCCodeFromAnsP5 @151c4 takes the start/stride from packed bytes 1/2,
    ignores count/assembly bytes, and uses the last record byte as status.
    Unlike P3/P4, kind 3 never changes the record origin. Here this is exactly
    the existing assembly-6 projection with no count and no origin override.
    """
    record_start: int
    record_bytes: int

    def __post_init__(self):
        NativeFaultLayout(255, self.record_start, self.record_bytes, 6, False, 2)

    def decode(self, payload, query_kind, availability_mask):
        return NativeFaultLayout(255, self.record_start, self.record_bytes, 6, False, 2).decode(
            payload, query_kind, availability_mask)
