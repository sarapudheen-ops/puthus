"""Data-only readers for the GGP container and LAH/WCX 5.03 text tables.

Format behavior was traced in libSTD.so (GetGAGOffsetFromGGP,
OpenFileIssueSearchID, GagDecrypt, EncryptNumber, IdSearch). Format tables are
arguments; there is no activation, license patching, or vendor code execution.
"""
from dataclasses import dataclass
from pathlib import PurePosixPath
import struct

from py3rijndael import Rijndael


@dataclass(frozen=True)
class GgpMember:
    name: str
    offset: int
    data: bytes


@dataclass(frozen=True)
class GagRecord:
    identity: bytes
    index_offset: int
    text_offset: int
    encoded_bytes: int
    data: bytes


def unpack_ggp(data: bytes, key: bytes) -> list[GgpMember]:
    if len(data) < 13 or data[:3] != b"GGP":
        raise ValueError("Not a GGP container")
    size = struct.unpack_from("<I", data, 7)[0]
    count = data[11]
    if size != 13 + count * 72 or size > len(data):
        raise ValueError("Invalid GGP directory size")
    if sum(data[:size - 1]) & 0xFF != data[size - 1]:
        raise ValueError("GGP directory checksum mismatch")
    if len(key) != 32:
        raise ValueError("GGP Rijndael-256 needs a 32-byte format key")
    cipher = Rijndael(key, block_size=32)
    members = []
    names = set()
    ranges = []
    for index in range(count):
        pos = 12 + index * 72
        raw = cipher.decrypt(data[pos:pos + 32]).split(b"\0", 1)[0]
        try:
            name = raw.decode("ascii")
        except UnicodeDecodeError as error:
            raise ValueError("GGP directory filename did not decode as ASCII") from error
        path = PurePosixPath(name)
        if not name or any(not 32 <= b < 127 for b in raw) or path.is_absolute() or ".." in path.parts or "\\" in name or name in names:
            raise ValueError("Unsafe, duplicate or incorrectly decoded GGP member name")
        offset, length = struct.unpack_from("<II", data, pos + 64)
        if offset < size or offset + length > len(data):
            raise ValueError(f"GGP member {name} is out of bounds")
        members.append(GgpMember(name, offset, data[offset:offset + length]))
        names.add(name)
        ranges.append((offset, offset + length))
    ordered = sorted(ranges)
    if any(right[0] < left[1] for left, right in zip(ordered, ordered[1:])):
        raise ValueError("Overlapping GGP members")
    return members


class GagFile:
    """LAH/WCX 5.03 only. Unknown revisions fail closed rather than guessing."""
    def __init__(self, data: bytes, format_xor: bytes):
        if len(data) < 269 or data[:3] not in (b"LAH", b"WCX"):
            raise ValueError("Not a LAH/WCX text library")
        if data[3:7] != b"\x00\x05\x03\x00":
            raise ValueError(f"Unsupported LAH/WCX revision: {data[3:7].hex()}")
        if len(format_xor) != 15:
            raise ValueError("The LAH 5.03 format table needs 15 bytes")
        permutation = bytes(byte ^ format_xor[i % 15] for i, byte in enumerate(data[7:263]))
        if len(set(permutation)) != 256:
            raise ValueError("LAH substitution table is not a permutation")
        inverse = bytearray(256)
        for i, byte in enumerate(permutation):
            inverse[byte] = i
        self.table = bytes(inverse)
        key_size = int.from_bytes(data[267:269], "big")
        if key_size > 500 or 269 + key_size + 5 > len(data):
            raise ValueError("Invalid LAH secondary table size")
        self.key = data[269:269 + key_size]
        self.start = 269 + key_size
        self.data = data
        header = self.metadata(data[self.start:self.start + 5])
        self.count = int.from_bytes(header[:4], "big")
        self.id_size = header[4]
        if not 1 <= self.id_size <= 16 or self.count > 500_000:
            raise ValueError("Invalid LAH record count or ID length")
        self.index_end = self.start + 5 + self.count * (self.id_size + 4)
        if self.index_end > len(data):
            raise ValueError("Truncated LAH index")

    def metadata(self, data: bytes) -> bytes:
        if not data:
            raise ValueError("Empty LAH metadata")
        last = data[-1] ^ self.table[121]
        return bytes(byte ^ self.table[121 + ((i + 1 + last) % 134)] for i, byte in enumerate(data[:-1])) + bytes([last])

    def pointer(self, data: bytes) -> int:
        raw = bytes(byte ^ self.table[99 + ((i + 18) % 156)] for i, byte in enumerate(data))
        return int.from_bytes(raw, "big")

    def text(self, data: bytes) -> bytes:
        if self.key:
            return bytes(self.table[byte ^ self.key[i % len(self.key)]] for i, byte in enumerate(data))
        return data.translate(self.table)

    def records(self):
        for row in self.indexed_records():
            yield row.identity, row.data

    def indexed_records(self):
        """Offsets are relative to this GAG member, never to the decoded JSON.

        Keep both the encrypted index row and encoded text span, since their
        physical order need not match the key or iteration order.
        """
        seen = set()
        for i in range(self.count):
            pos = self.start + 5 + i * (self.id_size + 4)
            raw_id = self.data[pos:pos + self.id_size]
            identity = bytes(byte ^ self.table[119 + j] for j, byte in enumerate(raw_id))
            if identity in seen:
                raise ValueError("Duplicate LAH record ID")
            seen.add(identity)
            offset = self.pointer(self.data[pos + self.id_size:pos + self.id_size + 4])
            if offset < self.index_end or offset + 2 > len(self.data):
                raise ValueError("LAH text offset is out of bounds")
            length = int.from_bytes(self.metadata(self.data[offset:offset + 2]), "big")
            if length < 2 or offset + length > len(self.data):
                raise ValueError("LAH text length is out of bounds")
            yield GagRecord(identity, pos, offset, length, self.text(self.data[offset + 2:offset + length]))
