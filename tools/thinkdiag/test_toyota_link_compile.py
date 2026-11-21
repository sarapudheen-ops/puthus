import unittest
from toyota_link_compile import LinkUntranslated, compile_code1


class LinkCompilerTest(unittest.TestCase):
    def entry(self, target="13", baud="b028", timing="053c001919", pins="0707"):
        return {"transportCode": 1, "baud": baud, "sourceFields": {
            "runtime107b0": "f0" + target, "runtime107f0": timing, "pinPairs": pins},
            "initBindings": [0x68], "keepaliveBindings": [0x66], "exitBindings": [0x7f]}

    def evidence(self, target="13", mode=0x81):
        from toyota_link_compile import _frame
        return {0x68: _frame(int(target, 16), mode), 0x66: bytes.fromhex("80" + target + "f0221234"),
                0x7f: b"oracle-reviewed-exit"}

    def compile(self, entry, evidence):
        return compile_code1(entry, evidence, oracle_verified=set(evidence))

    def test_dynamic_fast_profile_and_target_addresses(self):
        for target in ("13", "32", "c1"):
            got = self.compile(self.entry(target), self.evidence(target))
            self.assertEqual(got["protocol"], "kwp2000_fast")
            self.assertEqual(got["baudRate"], 10416)
            self.assertEqual(got["txHeader"], "80" + target.upper() + "F0")
            self.assertEqual(got["rxHeader"], "80F0" + target.upper())
            self.assertEqual(got["initBytes"], "81" + target.upper() + "F081" + f"{(0x81 + int(target,16) + 0xF0 + 0x81)&255:02X}")

    def test_reviewed_slow_profile_requires_exact_native_join(self):
        target = "13"
        result = self.compile(self.entry(target, "8025", "053c00230f"), self.evidence(target, 1))
        self.assertEqual(result["protocol"], "iso9141_2")
        self.assertEqual(result["baudRate"], 9600)
        self.assertEqual(result["initMethod"], "five_baud_slow")
        self.assertEqual(result["fiveBaudAddress"], 0x13)
        self.assertEqual(result["timings"], {"p1": 5, "p2": 60, "p3": 0, "p4": 35, "w4": 15})

    def test_unknown_profiles_pins_and_nonmatching_oracle_frames_fail_closed(self):
        with self.assertRaisesRegex(LinkUntranslated, "profile"):
            self.compile(self.entry(baud="ffff"), {})
        with self.assertRaisesRegex(LinkUntranslated, "pin_pair"):
            self.compile(self.entry(pins="0606"), {})
        bad = self.evidence()
        bad[0x68] = b"wrong"
        with self.assertRaisesRegex(LinkUntranslated, "frame_mismatch"):
            self.compile(self.entry(), bad)
        with self.assertRaisesRegex(LinkUntranslated, "native_oracle"):
            compile_code1(self.entry(), {})


if __name__ == "__main__":
    unittest.main()
