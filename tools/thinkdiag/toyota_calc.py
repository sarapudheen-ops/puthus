"""Two reviewed numeric equivalences, NOT an EXPRESS interpreter or ECU approval.

The native operators, binding powers and arithmetic branches are tied to one
SHA-pinned CALC library and its bounded static-data report. Only the two literal
scripts below have been reviewed across their ENTIRE unsigned-byte domains.
They use exact binary arithmetic and survive native %.0f / %.3f formatting
unchanged. No variable denominators, unary rules, signed helpers, exponent
notation, control flow, strings or other native-language classes are approved.

This metadata is for DISABLED conversion reviews. It grants no app operation,
transport setting, applicability, import trust or executable-system status.
"""
import hashlib
import struct


CALC_LIBRARY_SHA256 = "364645c275977f139e92ac0a9b7995ab20939f32a86371876e0bdbe04a7e1197"
# Exact file-backed, non-code objects from inspection 72c608e. The second binding
# power slice contains bytes beyond ID 21; they are hashed, NOT interpreted as
# additional priorities. Arithmetic uses IDs 2/3/6/7, incoming ')' uses ID 22,
# and stacked '(' uses ID 21. No stacked value beyond ID 21 is interpreted.
OBJECTS = {
    "opchTbl": (0x38008, 300, "dfd328bbb6203cc53df6d37e2c0d5ce62001fecd9762bc08051d34a20e4fd575"),
    "@0x20cac:0x5c": (0x20cac, 92, "ed73796e1df0809ceef0e8185525773af94ea34e9723d6c9fde6af03e1bfdad5"),
    "@0x20d08:0x5c": (0x20d08, 92, "577d72e3709918c28389a2f568341bbc177c93998b2cb13dfeb8a7e163bc4159"),
    "@0x20980:0x50": (0x20980, 80, "703afbd71eaae74f97902d01256389159ca1f97649a69dc20c0b108463b9cfef"),
}

# Native x and x1 select the first unsigned byte. x2 selects the next byte.
# These are literal reviewed programs, not an algebraic simplifier/allowlist of
# arbitrary syntax that happens to produce the same formula with Python rules.
REVIEWED = {
    "y=x-40;": ("u8-minus40-v1", "(A-40)", 1),
    "y=(0x100*x1+x2)/4;": ("u16be-quarter-v1", "(((256*A)+B)/4)", 2),
}


class NativeCalcEvidence:
    def __init__(self, report):
        if not isinstance(report, dict) or report.get("kind") != "static-data-review" or report.get("library") != "libTOYOTA_CALC.so" or report.get("librarySha256") != CALC_LIBRARY_SHA256:
            raise ValueError("Numeric review needs the exact SHA-pinned native CALC evidence")
        records = report.get("objects")
        if not isinstance(records, list) or len(records) != len(OBJECTS):
            raise ValueError("Missing or duplicate CALC data objects")
        data = {}
        try:
            for record in records:
                selector = record["selector"]
                if selector in data or selector not in OBJECTS or "error" in record:
                    raise ValueError("Unreviewed/duplicate/missing CALC data object")
                address, size, digest = OBJECTS[selector]
                if not isinstance(record["hex"], str) or len(record["hex"]) > 3 * size:
                    raise ValueError("Oversized CALC data object")
                raw = bytes.fromhex(record["hex"])
                if (int(record["address"], 0) != address or record["bytes"] != size or len(raw) != size
                        or record["sha256"] != digest or hashlib.sha256(raw).hexdigest() != digest):
                    raise ValueError("CALC data object checksum/address/bounds mismatch")
                data[selector] = raw
        except (KeyError, TypeError) as error:
            raise ValueError("Malformed native CALC data evidence") from error
        operators = {}
        raw = data["opchTbl"]
        for at in range(0, len(raw), 12):
            token = raw[at:at + 4].split(b"\0")[0]
            size, opcode = struct.unpack_from("<II", raw, at + 4)
            operators[token] = (size, opcode)
        if any(operators.get(token) != (1, opcode) for token, opcode in
               ((b"*", 2), (b"/", 3), (b"+", 6), (b"-", 7), (b"(", 21), (b")", 22))):
            raise ValueError("Unreviewed native arithmetic operator IDs")
        incoming = struct.unpack("<23i", data["@0x20cac:0x5c"])
        stacked = struct.unpack("<23i", data["@0x20d08:0x5c"])
        targets = struct.unpack("<20i", data["@0x20980:0x50"])
        # @10830 compares incoming binding power to the stacked operator's.
        # Equality reduces first: these four operators are left associative.
        for opcode, precedence, branch in ((2, 11, 0x10918), (3, 11, 0x10920),
                                           (6, 10, 0x10974), (7, 10, 0x1097c)):
            if incoming[opcode - 1] != precedence or stacked[opcode - 1] != precedence or 0x20980 + targets[opcode - 1] != branch:
                raise ValueError("Unreviewed CALC precedence/arithmetic branch")
        if incoming[20] != 13 or stacked[20] != 1 or incoming[21] != 1:
            raise ValueError("Unreviewed CALC parentheses binding")
        self.source_records = tuple(f"libTOYOTA_CALC.so@{address:08x}#static-data" for address, _, _ in OBJECTS.values()) + (
            "libTOYOTA_CALC.so@0000f9bc#numeric-assignment", "libTOYOTA_CALC.so@00010b88#byte-variable",
            "libTOYOTA_CALC.so@00010f38#unsigned-byte-load", "libTOYOTA_CALC.so@00010830#precedence",
            "libTOYOTA_CALC.so@00010ae0#hex-literal", "libTOYOTA_CALC.so@00010918#multiply",
            "libTOYOTA_CALC.so@00010920#guarded-division", "libTOYOTA_CALC.so@00010974#addition",
            "libTOYOTA_CALC.so@0001097c#subtraction", "libTOYOTA_CALC.so@0000fde0#numeric-format",
        )

    def describe(self, script, formula, bytes_needed):
        if not isinstance(script, str):
            return None
        # Do not collapse whitespace, case-fold, simplify, or substitute hex and
        # decimal spellings: that would approve programs not literally reviewed.
        reviewed = REVIEWED.get(script)
        if reviewed is None or (formula, bytes_needed) != reviewed[1:]:
            return None
        return {"id": reviewed[0], "inputBytes": bytes_needed, "librarySha256": CALC_LIBRARY_SHA256,
                "sourceRecords": list(self.source_records)}
