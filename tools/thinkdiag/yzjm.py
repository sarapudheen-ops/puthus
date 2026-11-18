"""Data-only decoder for the YZJM wrapper identified in the Toyota release asset.

Derived from static analysis of libDEVICEID.so's fopenEx/freadEx. This is a
word-rotation/XOR file encoding, not ZIP encryption. No vendor library is loaded.
The 32-byte format table is supplied by the caller, not a device/license secret.
"""
from dataclasses import dataclass
import re
import struct

MASK32 = 0xFFFFFFFF


def ror32(value: int, bits: int) -> int:
    return ((value >> bits) | (value << (32 - bits))) & MASK32


@dataclass(frozen=True)
class DecodedFile:
    make: str
    version: str
    seed: int
    payload: bytes


def word_keys(make: str, version: str, seed: int, table: bytes) -> tuple[int, ...]:
    maker, ver = make.encode("ascii"), version.encode("ascii")
    if not maker or not re.fullmatch(rb"V[0-9]{2}\.[0-9]{2}", ver):
        raise ValueError("YZJM needs a make and six-character version Vnn.nn")
    if len(table) != 32:
        raise ValueError("The YZJM format table must contain 32 bytes")
    material = bytes(maker[i % len(maker)] for i in range(10)) + ver
    rotated = bytearray()
    for i, byte in enumerate(material):
        shift = (i % 4) or 3
        rotated.append(((byte >> shift) | (byte << (8 - shift))) & 0xFF)
    return tuple(
        int.from_bytes(table[pos:pos + 4], "big") ^ ror32(seed, 10) ^ int.from_bytes(rotated[pos % 16:pos % 16 + 4], "big")
        for pos in range(0, 32, 4)
    )


def decode_yzjm(data: bytes, table: bytes) -> DecodedFile:
    pos = 0

    def string() -> str:
        nonlocal pos
        if pos + 2 > len(data):
            raise ValueError("Truncated YZJM string length")
        size = int.from_bytes(data[pos:pos + 2], "little")
        pos += 2
        if not 1 <= size <= 128 or pos + size > len(data):
            raise ValueError("Invalid or truncated YZJM string")
        raw = data[pos:pos + size]
        pos += size
        if raw[-1] != 0 or b"\0" in raw[:-1]:
            raise ValueError("YZJM strings must end with one NUL")
        try:
            return raw[:-1].decode("ascii")
        except UnicodeDecodeError as error:
            raise ValueError("YZJM header is not ASCII") from error

    if string() != "YZJM":
        raise ValueError("Not a YZJM wrapped file")
    make, version = string(), string()
    if pos + 4 > len(data):
        raise ValueError("Missing YZJM seed")
    seed = int.from_bytes(data[pos:pos + 4], "little")
    pos += 4
    if (len(data) - pos) % 4:
        raise ValueError("YZJM payload is not aligned to 32-bit words")
    keys = word_keys(make, version, seed, table)
    output = bytearray(len(data) - pos)
    for index, (word,) in enumerate(struct.iter_unpack(">I", data[pos:])):
        # The vendor reader explicitly preserves these two sentinel words.
        plain = word if word in (0, MASK32) else ror32(word, 22) ^ keys[index % 8]
        struct.pack_into(">I", output, index * 4, plain)
    return DecodedFile(make, version, seed, bytes(output))
