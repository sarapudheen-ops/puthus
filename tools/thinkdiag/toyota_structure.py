"""Bounded Toyota V10.62 table structures, for offline research only.

These parsers recover file boundaries/opaque fields, not executable diagnostic
recipes. In particular, a section number, integer or text label is NOT a PID,
CAN address, service or coding definition without independent reader evidence.
"""
from dataclasses import dataclass
import struct

MAX_RECORDS = 200_000
ECU_FILE = r"(?:EU|JP|NA)/Ecu_Data_[0-9a-fA-F]+\.BIN"


@dataclass(frozen=True)
class TypedTable:
    identity: int
    count: int
    body: bytes
    padding: bytes

    @property
    def stride(self) -> int | None:
        return len(self.body) // self.count if self.count and len(self.body) % self.count == 0 else None


def typed_table(data: bytes, expected_id: int | None = None) -> TypedTable:
    """LE16 tag, LE32 row count, LE32 payload size, payload, <=3 padding bytes.

    Native evidence: GetToyotaTblData (0x103F8), GetEcuTblData (0x105C0).
    The caller still needs the table-specific row schema and provenance.
    """
    identity, count, size = uint(data, 0, 2), uint(data, 2, 4), uint(data, 6, 4)
    if expected_id is not None and identity != expected_id:
        raise ValueError("Typed-table tag disagrees with directory/file")
    if size > len(data) - 10:
        raise ValueError(f"Typed-table size {size} exceeds span {len(data) - 10}")
    if len(data) - 10 - size > 3:
        raise ValueError(f"Typed-table has {len(data) - 10 - size} unexplained trailing bytes")
    if count > MAX_RECORDS * 10 or (count == 0) != (size == 0):
        raise ValueError("Invalid typed-table count/size")
    return TypedTable(identity, count, data[10:10 + size], data[10 + size:])


def leading_typed_table(data: bytes) -> tuple[TypedTable, bytes]:
    """The native loader reads only the leading size-declared table.

    Return, rather than discard, any bytes outside that frame. They require
    their own interpretation and are not silently called padding.
    """
    end = 10 + uint(data, 6, 4)
    if end > len(data):
        raise ValueError("Leading typed table is truncated")
    return typed_table(data[:end]), data[end:]


def audit_command_pool(data: bytes) -> dict:
    """Audit every descriptor, retaining malformed entries as explicit findings.

    Unlike the strict parser this research-only view can describe a damaged
    frame. No missing bytes are padded, no invalid byte sequence is returned,
    and it cannot be used as an app pack/import result.
    """
    count, declared = uint(data, 2, 4), uint(data, 6, 4)
    if count > MAX_RECORDS or 10 + count * 6 > len(data):
        raise ValueError("Truncated or excessive command-pool descriptor array")
    body_end = min(len(data), 10 + declared)
    blob_start = 10 + count * 6
    if blob_start > body_end:
        raise ValueError("Command descriptors exceed the declared table body")
    records = []
    for index in range(count):
        pos = 10 + index * 6
        offset, length, flag = uint(data, pos, 4), data[pos + 4], data[pos + 5]
        start = blob_start + offset
        row = {"index": index + 1, "descriptorOffset": pos, "bytesOffset": start, "length": length, "flag": flag}
        if offset >= 0x80000000 or start + length > body_end:
            row["error"] = "Byte range outside available/declared command blob"
        else:
            row["bytesHex"] = data[start:start + length].hex(" ")
        records.append(row)
    return {"declaredEntries": count, "declaredBodyBytes": declared, "availableBodyBytes": body_end - 10,
            "bodyShortfallBytes": max(0, 10 + declared - len(data)),
            "trailingBytes": max(0, len(data) - 10 - declared), "entries": records,
            "boundedEntries": sum("error" not in row for row in records),
            "invalidEntries": [row for row in records if "error" in row]}


def command_pool(data: bytes) -> tuple[list[dict], bytes]:
    """GetCmdDataInfo (libTOYOTA_COMM.so 0x1ACB0): six-byte descriptors.

    Each has an LE32 blob-relative offset, u8 length, u8 opaque flag. Indices
    are one-based; caller IDs above 10000 may use the native 10000 bias. These
    are opaque byte sequences, NOT approved vehicle requests.
    """
    table, trailing = leading_typed_table(data)
    descriptors = table.count * 6
    if descriptors > len(table.body):
        raise ValueError("Truncated command-pool descriptor array")
    records = []
    for index in range(table.count):
        pos = index * 6
        offset = uint(table.body, pos, 4)
        length = table.body[pos + 4]
        flag = table.body[pos + 5]
        # The reader sign-extends this LE32 relative pointer. Never follow a
        # negative pointer or a range outside the declared command-data blob.
        if offset >= 0x80000000 or offset + length > len(table.body) - descriptors:
            raise ValueError("Command-pool byte range is outside its declared blob")
        start = descriptors + offset
        records.append({"index": index + 1, "descriptorOffset": 10 + pos,
                        "bytesOffset": 10 + start, "length": length, "flag": flag,
                        "bytesHex": table.body[start:start + length].hex(" ")})
    return records, trailing


def menu_nodes(data: bytes) -> dict[int, dict]:
    """MENU packed nodes: 38-byte header and 23-byte edge records.

    Structural fields only; task IDs/flags/saved parameters are not executed.
    Traversal consumers must track visited nodes (back-links may be present).
    """
    nodes = {}
    pos = 0
    while pos < len(data):
        if len(data) - pos <= 3 and not any(data[pos:]):
            break
        size = uint(data, pos, 4)
        if size < 38 or pos + size > len(data) or len(nodes) >= MAX_RECORDS:
            raise ValueError(f"Invalid menu node size at {pos:#x}")
        count = uint(data, pos + 36, 2)
        if size != 38 + count * 23:
            raise ValueError(f"Unknown menu node/edge layout at {pos:#x}")
        edges = []
        for i in range(count):
            edge = pos + 38 + i * 23
            edges.append({"offset": edge, "flags": uint(data, edge, 2), "tag": data[edge + 2],
                          "parametersHex": data[edge + 3:edge + 19].hex(), "target": uint(data, edge + 19, 4)})
        nodes[pos] = {"offset": pos, "bytes": size,
                      "headerWords": [uint(data, pos + i, 4) for i in range(4, 36, 4)], "edges": edges}
        pos += size
    if not nodes:
        raise ValueError("Empty menu")
    for node in nodes.values():
        if any(edge["target"] not in (0, 0xFFFFFFFF) and edge["target"] not in nodes for edge in node["edges"]):
            raise ValueError("Menu edge points outside node boundaries")
    return nodes


def uint(data: bytes, offset: int, width: int) -> int:
    if offset < 0 or width not in (1, 2, 4) or offset + width > len(data):
        raise ValueError(f"Truncated integer at {offset:#x} ({width} bytes)")
    return int.from_bytes(data[offset:offset + width], "little")


@dataclass(frozen=True)
class Section:
    identity: int
    offset: int
    end: int


def section_directory(data: bytes) -> list[Section]:
    """Ecu_Data: LE32 count, then count (u8 kind, LE32 offset) entries.

    All spans must fit before any are returned. Payload semantics stay opaque.
    EOF padding is included in the final span, not interpreted as a record.
    """
    count = uint(data, 0, 4)
    if not 1 <= count <= 256:
        raise ValueError(f"Invalid section count {count}")
    end = 4 + 5 * count
    if end > len(data):
        raise ValueError("Truncated section directory")
    entries = [struct.unpack_from("<BI", data, 4 + 5 * i) for i in range(count)]
    ids = [i for i, _ in entries]
    offsets = [o for _, o in entries]
    if len(set(ids)) != count or len(set(offsets)) != count:
        raise ValueError("Duplicate section identity or offset")
    if offsets != sorted(offsets) or offsets[0] < end or offsets[-1] >= len(data):
        raise ValueError("Overlapping, unordered or out-of-range section offsets")
    return [Section(identity, offset, stop) for (identity, offset), stop in
            zip(entries, offsets[1:] + [len(data)])]


def sentinel_index(data: bytes, start: int = 0, stride: int = 8) -> tuple[list[tuple[int, ...]], int]:
    """LE32-keyed index terminated by a LE32 FFFFFFFF key.

    SYSLIST uses 8-byte entries. FUNCDATA starts with a stride byte and has
    28-byte entries. Values remain opaque here; consumers validate pointers.
    """
    if start < 0 or stride < 8 or stride > 128 or stride % 4:
        raise ValueError("Invalid index shape")
    rows = []
    identities = set()
    pos = start
    while len(rows) < MAX_RECORDS:
        identity = uint(data, pos, 4)
        if identity == 0xFFFFFFFF:
            return rows, pos + 4
        if identity in identities:
            raise ValueError(f"Duplicate index identity {identity}")
        if pos + stride > len(data):
            raise ValueError("Truncated index row / missing sentinel")
        identities.add(identity)
        rows.append(struct.unpack_from("<" + "I" * (stride // 4), data, pos))
        pos += stride
    raise ValueError("Index record limit exceeded / missing sentinel")


def system_lists(data: bytes) -> tuple[list[tuple[int, int]], dict[int, list[bytes]]]:
    """SYSLIST: keyed block offsets, LE16 count, packed 37-byte ECU rows.

    Reader evidence: libTOYOTA_FILE.so GetVehicleInfoData / GetSystemListData
    at 0x112B4 / 0x11574. Shared offsets are legal; partial overlaps are not.
    """
    index, header_end = sentinel_index(data)
    blocks = {}
    extents = []
    for _, offset in index:
        if offset < header_end:
            raise ValueError("System-list block overlaps its index")
        if offset in blocks:
            continue
        count = uint(data, offset, 2)
        end = offset + 2 + count * 37
        if end > len(data):
            raise ValueError("Truncated packed system-list records")
        blocks[offset] = [data[p:p + 37] for p in range(offset + 2, end, 37)]
        extents.append((offset, end))
    extents.sort()
    if any(b[0] < a[1] for a, b in zip(extents, extents[1:])):
        raise ValueError("Partially overlapping system-list blocks")
    return index, blocks


def system_fields(record: bytes) -> dict:
    """Source fields, deliberately named by byte offset until semantics are traced."""
    if len(record) != 37:
        raise ValueError("A system-list record must contain exactly 37 bytes")
    return {"u32": {str(i): uint(record, i, 4) for i in (0, 4, 8, 12, 16, 23, 28)},
            "u8": {str(i): record[i] for i in (20, 21, 22, 27, 32, 33, 34, 35, 36)}}


def function_index(data: bytes) -> tuple[list[tuple[int, ...]], int]:
    """FUNCDATA's stride-tagged directory, with bounded section pointers.

    Pointer columns are kept by offset, not assigned guessed command meanings.
    """
    stride = uint(data, 0, 1)
    if stride != 28:
        raise ValueError(f"Unknown FUNCDATA index stride {stride}")
    rows, end = sentinel_index(data, 1, stride)
    for row in rows:
        for pointer in row[1:6]:
            if pointer not in (0, 0xFFFFFFFF) and not end <= pointer < len(data):
                raise ValueError("FUNCDATA pointer outside payload / overlapping index")
    return rows, end
