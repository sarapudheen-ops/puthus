"""Exact FUNCDATA fault/clear framing from Toyota V10.62 FILE consumers.

GetRDtcDataFromRDTCPart @133dc and GetCDtcDataFromCDTCPart @13b80
(libTOYOTA_FILE.so) establish these packed layouts. Query kinds, conditions and
code/state mappings are retained, not reinterpreted as UDS services/statuses.
A framed source query is not a resolved transport, applicability or ECU grant.
"""
from dataclasses import dataclass
import struct

from toyota_structure import function_index


class Cursor:
    def __init__(self, raw, start, stop):
        if type(start) is not int or type(stop) is not int or not 0 <= start <= stop <= len(raw):
            raise ValueError('Invalid FUNCDATA block bounds')
        self.raw, self.start, self.pos, self.stop = raw, start, start, stop

    def take(self, n):
        if type(n) is not int or n < 0 or self.pos + n > self.stop:
            raise ValueError(f'Truncated FUNCDATA record at {self.pos:#x}')
        data = self.raw[self.pos:self.pos + n]
        self.pos += n
        return data

    def number(self, width):
        return int.from_bytes(self.take(width), 'little')

    def finish(self):
        if self.pos != self.stop:
            raise ValueError(f'Unconsumed FUNCDATA bytes at {self.pos:#x}..{self.stop:#x}')


@dataclass(frozen=True)
class FaultCondition:
    source_offset: int
    key: int
    byte2: int
    byte3: int

    def encode(self):
        return struct.pack('<HBB', self.key, self.byte2, self.byte3)


@dataclass(frozen=True)
class FaultQuery:
    source_offset: int
    kind: int
    word1: int
    template: int
    conditions: tuple[FaultCondition, ...]

    def encode(self):
        return struct.pack('<BHHB', self.kind, self.word1, self.template, len(self.conditions)) + b''.join(r.encode() for r in self.conditions)


@dataclass(frozen=True)
class FaultMapping:
    source_offset: int
    code: int
    byte4: int
    byte5: int

    def encode(self):
        return struct.pack('<IBB', self.code, self.byte4, self.byte5)


@dataclass(frozen=True)
class FaultBlock:
    source_offset: int
    initial_templates: tuple[int, ...]
    # FILE writes these six bytes to runtime +1c,+1d,+20,+21,+22,+23,
    # respectively. GetDTCCodeFromAnsP3P4 consumes +20..+23 as count index,
    # record start, stride and assembly kind. The other fields retain source names.
    byte1: int
    byte2: int
    count_index: int
    record_start: int
    record_bytes: int
    assembly_kind: int
    queries: tuple[FaultQuery, ...]
    mappings: tuple[FaultMapping, ...]

    def encode(self):
        return (bytes([len(self.initial_templates)]) + b''.join(struct.pack('<H', i) for i in self.initial_templates)
            + bytes([len(self.queries), self.byte1, self.byte2, self.count_index, self.record_start, self.record_bytes, self.assembly_kind])
            + b''.join(q.encode() for q in self.queries) + struct.pack('<H', len(self.mappings))
            + b''.join(m.encode() for m in self.mappings))

    @classmethod
    def parse(cls, raw, start, stop):
        c = Cursor(raw, start, stop)
        initial = tuple(c.number(2) for _ in range(c.number(1)))
        query_count, b1, b2, count_at, record_at, stride, kind = c.take(7)
        queries = []
        for _ in range(query_count):
            at = c.pos
            query_kind, word, template, condition_count = struct.unpack('<BHHB', c.take(6))
            conditions = []
            for _ in range(condition_count):
                off = c.pos
                conditions.append(FaultCondition(off, *struct.unpack('<HBB', c.take(4))))
            queries.append(FaultQuery(at, query_kind, word, template, tuple(conditions)))
        mappings = []
        for _ in range(c.number(2)):
            off = c.pos
            mappings.append(FaultMapping(off, *struct.unpack('<IBB', c.take(6))))
        c.finish()
        result = cls(start, initial, b1, b2, count_at, record_at, stride, kind, tuple(queries), tuple(mappings))
        if result.encode() != raw[start:stop]:
            raise ValueError('Fault block did not round-trip exactly')
        return result


@dataclass(frozen=True)
class ClearQuery:
    source_offset: int
    kind: int
    template: int

    def encode(self):
        return struct.pack('<BH', self.kind, self.template)


@dataclass(frozen=True)
class ClearBlock:
    source_offset: int
    queries: tuple[ClearQuery, ...]

    def encode(self):
        return bytes([len(self.queries)]) + b''.join(q.encode() for q in self.queries)

    @classmethod
    def parse(cls, raw, start, stop):
        c = Cursor(raw, start, stop)
        queries = []
        for _ in range(c.number(1)):
            off = c.pos
            queries.append(ClearQuery(off, *struct.unpack('<BH', c.take(3))))
        c.finish()
        result = cls(start, tuple(queries))
        if result.encode() != raw[start:stop]:
            raise ValueError('Clear block did not round-trip exactly')
        return result


class FunctionBlocks:
    def __init__(self, raw):
        self.raw = raw
        rows, self.index_end = function_index(raw)
        self.rows = {r[0]: r for r in rows}
        self.index_offsets = {r[0]: 1 + i * 28 for i, r in enumerate(rows)}
        starts = sorted({p for row in rows for p in row[1:6] if p not in (0, 0xffffffff)})
        self.stops = dict(zip(starts, starts[1:] + [len(raw)]))

    def span(self, function, column):
        if column not in range(1, 6):
            raise ValueError('Not a FUNCDATA payload column')
        start = self.rows[function][column]
        return None if start in (0, 0xffffffff) else (start, self.stops[start])

    def faults(self, function):
        span = self.span(function, 1)
        return FaultBlock.parse(self.raw, *span) if span else None

    def clear(self, function):
        span = self.span(function, 2)
        return ClearBlock.parse(self.raw, *span) if span else None
