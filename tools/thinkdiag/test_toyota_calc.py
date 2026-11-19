"""Small reviewed scalar-table contract, not a native binary or vehicle fixture."""
import copy
import hashlib
import struct
import unittest

from toyota_calc import CALC_LIBRARY_SHA256, OBJECTS, NativeCalcEvidence


def calc_evidence():
    # Operator IDs / binding powers are the bounded, published static-data
    # observations, not invented vehicle applicability or machine instructions.
    tokens = ["//", "<<", ">>", "&&", "||", ">=", "<=", "!=", "==", "*", "/", "%", "+", "-",
              ">", "<", "&", "^", "|", "(", ")", "$", "\n", " ", ""]
    opcodes = [5, 8, 9, 19, 20, 12, 13, 14, 15, 2, 3, 4, 6, 7, 10, 11, 16, 17, 18, 21, 22, 1, 23, 0xffffffff, 23]
    operators = b"".join(struct.pack("<4sII", token.encode(), max(1, len(token)), opcode) for token, opcode in zip(tokens, opcodes))
    incoming = [13, 11, 11, 11, 11, 10, 10, 9, 9, 8, 8, 8, 8, 7, 7, 6, 5, 4, 3, 2, 13, 1, 1]
    stacked = [12, 11, 11, 11, 11, 10, 10, 9, 9, 8, 8, 8, 8, 7, 7, 6, 5, 4, 3, 2, 1]
    branches = [-65704, -65640, -65632, -65608, -65584, -65548, -65540, -65532, -65512, -65492,
                -65480, -65468, -65448, -65428, -65416, -65404, -65384, -65364, -65344, -65316]
    values = [operators, struct.pack("<23i", *incoming), struct.pack("<21i", *stacked) + b"error\0\0\0", struct.pack("<20i", *branches)]
    return {"kind": "static-data-review", "library": "libTOYOTA_CALC.so", "librarySha256": CALC_LIBRARY_SHA256,
            "objects": [{"selector": selector, "address": hex(address), "bytes": len(raw), "hex": raw.hex(" "),
                         "sha256": hashlib.sha256(raw).hexdigest()}
                        for (selector, (address, _, _)), raw in zip(OBJECTS.items(), values)]}


class NumericReviewTest(unittest.TestCase):
    def test_exact_evidence_confirms_only_the_two_reviewed_numeric_equivalences(self):
        review = NativeCalcEvidence(calc_evidence())
        for script, formula, width, identity in (("y=x-40;", "(A-40)", 1, "u8-minus40-v1"),
                                                ("y=(0x100*x1+x2)/4;", "(((256*A)+B)/4)", 2, "u16be-quarter-v1")):
            result = review.describe(script, formula, width)
            self.assertEqual(identity, result["id"])
            self.assertEqual(width, result["inputBytes"])
            self.assertEqual(CALC_LIBRARY_SHA256, result["librarySha256"])
            self.assertIn("libTOYOTA_CALC.so@00010f38#unsigned-byte-load", result["sourceRecords"])
            self.assertNotIn("enabled", result)  # numeric evidence is not an ECU operation

    def test_similar_or_python_only_syntax_never_inherits_an_arithmetic_approval(self):
        review = NativeCalcEvidence(calc_evidence())
        for script in (None, "y=x1-40;", "y=X-40;", "y=x-41;", "y=-x-40;", "y=x/0;", "y=x/x2;",
                       "y=1e-20*x;", "y=(x1*256+x2)/4;", "y=(256*x1+x2)//4;", "y=UCHAR(x)-40;", "y=x.bit0;",
                       "y=x-4 0;", "y = x-40;", "y=x -40;", " y=x-40;", "y=x-40;\n"):
            self.assertIsNone(review.describe(script, "(A-40)", 1))
        self.assertIsNone(review.describe("y=x-40;", "(A-39)", 1))
        self.assertIsNone(review.describe("y=x-40;", "(A-40)", 2))
        self.assertIsNone(review.describe("y=(256*x1+x2)/4;", "(((256*A)+B)/4)", 2))

    def test_missing_forged_moved_or_changed_data_never_approves_a_script(self):
        base = calc_evidence()
        for field, value in (("kind", "executable"), ("library", "similar.so"), ("librarySha256", "0" * 64),
                             ("objects", []), ("objects", base["objects"] + [base["objects"][0]])):
            bad = {**base, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError): NativeCalcEvidence(bad)
        for index in range(4):
            for field, value in (("address", "0x1234"), ("bytes", 1), ("sha256", "0" * 64), ("hex", "00"),
                                 ("error", "missing data"), ("selector", "unreviewed")):
                bad = copy.deepcopy(base); bad["objects"][index][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(ValueError): NativeCalcEvidence(bad)
            # Same size/address and an internally consistent NEW checksum is
            # still not the reviewed source object. A copied checksum cannot
            # conceal changed bytes either.
            bad = copy.deepcopy(base)
            raw = bytearray.fromhex(bad["objects"][index]["hex"]); raw[-1] ^= 1
            bad["objects"][index]["hex"] = raw.hex(" ")
            with self.assertRaises(ValueError): NativeCalcEvidence(bad)
            bad["objects"][index]["sha256"] = hashlib.sha256(raw).hexdigest()
            with self.assertRaises(ValueError): NativeCalcEvidence(bad)
        for value in (None, {}, {**base, "objects": [None] * 4}):
            with self.assertRaises(ValueError): NativeCalcEvidence(value)


if __name__ == "__main__":
    unittest.main()
