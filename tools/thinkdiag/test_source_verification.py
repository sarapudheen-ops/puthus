import copy
import json
from pathlib import Path
import tempfile
import unittest

from source_verification import MANIFEST, load_verification, verify_archive


class SourceVerificationTest(unittest.TestCase):
    def test_full_archive_owner_verification_does_not_confer_runtime_authority(self):
        v = load_verification()
        self.assertTrue(v["verification"]["verified"])
        self.assertEqual("entire-archive-including-nested-members", v["verification"]["scope"])
        self.assertTrue(v["verification"]["sandboxInspectionDecodingAndExecutionAuthorized"])
        self.assertTrue(all(flag is False for flag in v["runtimeAuthority"].values()))

    def test_refuses_changed_hash_partial_scope_or_invented_write_authority(self):
        original = json.loads(MANIFEST.read_text())
        changes = [("source", "sha256", "0" * 64), ("source", "bytes", 0),
                   ("verification", "scope", "four-vehicles"), ("verification", "verified", False),
                   ("verification", "assertedBy", "converter"),
                   ("runtimeAuthority", "writeAuthorization", True),
                   ("runtimeAuthority", "automaticVehicleSelection", True),
                   ("runtimeAuthority", "physicalEcuVerification", True),
                   ("verification", "verified", 1), ("runtimeAuthority", "writeAuthorization", 0),
                   ("source", "bytes", 71755650.0)]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "verification.json"
            for group, key, flag in changes:
                with self.subTest(group=group, key=key):
                    v = copy.deepcopy(original); v[group][key] = flag
                    p.write_text(json.dumps(v))
                    with self.assertRaises(ValueError): load_verification(p)
            p.write_text("{" + " " * 16385)
            with self.assertRaises(ValueError): load_verification(p)

    def test_attestation_cannot_approve_another_or_truncated_archive(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "TOYOTA.zip"; p.write_bytes(b"not the verified archive")
            with self.assertRaisesRegex(ValueError, "does not match"): verify_archive(p)
