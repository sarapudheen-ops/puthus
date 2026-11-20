"""Exact regional function/template -> frame -> command-pool resolution.

COMM GetFuncCommFrameInfo(_MultiCmd), GetCommFrameInfo and GetCmdDataInfo
establish the three roles and the >10000 (not >=10000) index bias. These
functions do not establish a transport/header or operation authorization.
"""
from collections import defaultdict
from dataclasses import dataclass

from toyota_structure import audit_command_pool, frame_pool_references, typed_table, uint


@dataclass(frozen=True)
class SourceFrame:
    frame_id: int
    request: bytes
    mask: bytes
    response: bytes
    source_records: tuple[str, ...]


class SourceFrames:
    def __init__(self, sources, region):
        if region not in ('EU', 'NA', 'JP'):
            raise ValueError('Frame resolution needs the exact source region')
        self.prefix = region + '/'
        self.pool_file = self.prefix + 'Toyota_bin_00.BIN'
        self.frame_file = self.prefix + 'Toyota_bin_11.BIN'
        self.binding_file = self.prefix + 'Toyota_bin_12.BIN'
        raw = sources[self.pool_file]
        if uint(raw, 0, 2) != 0:
            raise ValueError('Wrong regional command-pool tag')
        self.pool = audit_command_pool(raw)
        frames = sources[self.frame_file]
        typed_table(frames, 17)
        self.frames = frame_pool_references(frames, self.pool)
        # The pinned EU table's tag is 0x0112; JP/NA use 0x0012 with the
        # same 16-byte row schema. Never borrow the EU table for another region.
        table = typed_table(sources[self.binding_file], 0x112 if region == 'EU' else 0x12)
        if table.count and table.stride != 16:
            raise ValueError('Wrong function/frame binding stride')
        self.bindings = [(10 + i * 16, table.body[i * 16:(i + 1) * 16]) for i in range(table.count)]
        self._frames = defaultdict(list)
        for row in self.frames:
            self._frames[row['frameId']].append(row)
        self._bindings = defaultdict(list)
        for at, row in self.bindings:
            if uint(row, 8, 2) == 0:
                self._bindings[uint(row, 0, 2), uint(row, 2, 2)].append((at, row))

    def frame(self, frame_id):
        if type(frame_id) is not int or not 0 <= frame_id <= 65535:
            raise ValueError('Frame ID needs an exact source word')
        matches = self._frames[frame_id]
        if len(matches) != 1:
            raise ValueError(f'No unique exact communication frame {frame_id}')
        row = matches[0]
        records = [f"{self.frame_file}@{row['rowOffset']:08x}"]
        values = []
        for field in (2, 4, 6):
            r = row['referencesByFieldOffset'][str(field)]
            if 'error' in r:
                raise ValueError(f'Unbounded communication-frame field {frame_id}+{field}')
            values.append(bytes.fromhex(r['bytesHex']))
            if r['poolIndex']:
                item = self.pool['entries'][r['poolIndex'] - 1]
                records += [f"{self.pool_file}@{item['descriptorOffset']:08x}#pool{r['poolIndex']}",
                            f"{self.pool_file}@{item['bytesOffset']:08x}"]
        return SourceFrame(frame_id, *values, tuple(dict.fromkeys(records)))

    def function(self, function, template):
        """All default bindings in source order, matching the native MultiCmd consumer.

        Nonzero +8 belongs to a different consumer. It must not be used as an
        alternate/default simply because the requested binding is absent.
        """
        if any(type(i) is not int or not 0 <= i <= 65535 for i in (function, template)):
            raise ValueError('Function/template need exact source words')
        results = []
        for at, row in self._bindings[function, template]:
            frame = self.frame(uint(row, 6, 2))
            results.append(SourceFrame(frame.frame_id, frame.request, frame.mask, frame.response,
                (f'{self.binding_file}@{at:08x}',) + frame.source_records))
            if len(results) > 20:
                raise ValueError('Function frame list exceeds the native 20-command limit')
        return results
