"""Reader-backed SYSTEMENTER framing and exact selector joins.

FILE GetSystemEnterInfoData @11f70 and GetInfoFromEnterList @125e8
(libTOYOTA_FILE.so, Toyota V10.62) read these fields. Values named by
source/runtime offset are deliberately NOT diagnostic addresses or timings.
COMM packing/address/init consumers must establish those semantics separately.
No native code is loaded or run. Every byte, including inter-directory markers,
is either in a framed record or in an explicit unclaimed span.
"""
from dataclasses import dataclass

from toyota_structure import uint


@dataclass(frozen=True)
class Entry:
    offset: int
    end: int
    descriptor: int
    transport: int
    flags: int
    baud: int
    fields: dict
    init: tuple[int, ...]
    keepalive: tuple[int, ...]
    exit: tuple[int, ...]
    raw: bytes

    @property
    def source_id(self):
        return f"SYSTEMENTER.BIN@{self.offset:08x}"

    def report(self):
        return {"id": self.source_id, "descriptor": self.descriptor,
                "transportCode": self.transport, "flags": self.flags, "baud": self.baud,
                "sourceFields": self.fields, "initBindings": list(self.init),
                "keepaliveBindings": list(self.keepalive), "exitBindings": list(self.exit),
                "rawHex": self.raw.hex()}


@dataclass(frozen=True)
class EntryList:
    offset: int
    end: int
    header: bytes
    entries: tuple[Entry, ...]

    def select(self, descriptor):
        # FDATA GetSystemEnterIndex and FILE GetInfoFromEnterList compare the
        # complete selector byte, not its low nibble or the transport code.
        return next((entry for entry in self.entries if entry.descriptor == descriptor), None)


class EntryIndex:
    def __init__(self, data: bytes):
        self.data = data
        count = uint(data, 0, 1)
        if not 1 <= count <= 16:
            raise ValueError("Invalid SYSTEMENTER region count")
        self.regions = {}
        claimed = [(0, 1 + 5 * count)]
        for i in range(count):
            at = 1 + i * 5
            region, start = uint(data, at, 1), uint(data, at + 1, 4)
            n = uint(data, start, 2)
            end = start + 2 + n * 8
            if region in self.regions or not 1 <= n <= 65535 or end > len(data):
                raise ValueError("Invalid SYSTEMENTER function directory")
            rows = {}
            for j in range(n):
                row = start + 2 + j * 8
                function, pointer = uint(data, row, 4), uint(data, row + 4, 4)
                if function in rows or not 0 <= pointer < len(data):
                    raise ValueError("Duplicate function or invalid entry-list pointer")
                rows[function] = {"offset": row, "listOffset": pointer}
            self.regions[region] = rows
            claimed.append((start, end))
        starts = sorted({row["listOffset"] for region in self.regions.values() for row in region.values()})
        # A list may end before a subsequent directory, not consume it. Include
        # directory starts in the hard boundaries and reject any overlap below.
        boundaries = sorted(set(starts + [a for a, _ in claimed] + [len(data)]))
        self.lists = {}
        for start in starts:
            stop = next(p for p in boundaries if p > start)
            item = self._list(start, stop)
            self.lists[start] = item
            claimed.append((start, item.end))
        claimed.sort()
        if any(b[0] < a[1] for a, b in zip(claimed, claimed[1:])):
            raise ValueError("Overlapping SYSTEMENTER records/directories")
        self.unclaimed = []
        previous = 0
        for start, end in claimed + [(len(data), len(data))]:
            if start > previous:
                self.unclaimed.append({"offset": previous, "rawHex": data[previous:start].hex()})
            previous = end

    def _list(self, start, stop):
        data = self.data
        pos = start

        def take(n):
            nonlocal pos
            if n < 0 or pos + n > stop:
                raise ValueError(f"Truncated SYSTEMENTER list at {start:#x}")
            raw = data[pos:pos + n]
            pos += n
            return raw

        header = take(4)
        entries = []
        selectors = set()
        for _ in range(header[0]):
            at = pos
            h = take(7)
            descriptor, transport, flags = h[:3]
            if descriptor in selectors:
                raise ValueError("Ambiguous duplicate SYSTEMENTER selector")
            selectors.add(descriptor)
            fields = {}

            def value(name, n):
                raw = take(n)
                fields[name] = raw.hex()
                return raw

            def pins():
                n = take(1)[0]
                # Native code reads pairs, n/2. Refuse to discard an odd byte.
                if n % 2:
                    raise ValueError("Odd SYSTEMENTER pin-pair byte count")
                value("pinPairs", n)

            low = descriptor & 15
            if low == 1:
                value("runtime107b0", 2)
                value("runtime107e4", 1)
                pins()
                value("runtime107f0", 5)
            elif low in (2, 10):
                value("runtime107b0", 2)
                value("runtime107d0", 2)
                value("runtime107b4", 1)
                value("runtime107c4", 2)
                pins()
                value("runtime107f2", 1)
                n = value("extraAddressFlag", 1)[0]
                if n:
                    value("runtime107b8", 2)
                    value("runtime107bc", 2)
                    value("runtime107d4", 2)
            elif low == 7:
                pins()
                value("runtime107f2", 1)
            elif low == 12:
                value("runtime107b0", 2)
                pins()
                value("runtime107f0", 3)
            # For all other low nibbles the native switch jumps straight to
            # the three binding lists. No unknown byte is guessed or skipped.
            bindings = []
            for _ in range(3):
                n = take(1)[0]
                raw = take(n * 2)
                bindings.append(tuple(uint(raw, i * 2, 2) for i in range(n)))
            entries.append(Entry(at, pos, descriptor, transport, flags, uint(h, 3, 4), fields,
                                 *bindings, data[at:pos]))
        return EntryList(start, pos, header, tuple(entries))

    def select(self, region, function, descriptor):
        row = self.regions.get(region, {}).get(function)
        return self.lists[row["listOffset"]].select(descriptor) if row is not None else None

    def report(self):
        return {"regions": {str(key): rows for key, rows in self.regions.items()},
                "lists": [{"offset": item.offset, "headerHex": item.header.hex(),
                           "entries": [entry.report() for entry in item.entries]}
                          for item in self.lists.values()], "unclaimedSpans": self.unclaimed}
