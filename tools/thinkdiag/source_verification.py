"""Owner attestation for the complete immutable Toyota specification.

Source legitimacy, conversion correctness, and fresh physical-ECU proof are
separate facts. This manifest establishes only the first. Never deserialize an
attestation from an imported vehicle pack to grant runtime authority.
"""
import argparse
import hashlib
import json
from pathlib import Path

from vehicle_bundle import SOURCE_SHA256

MANIFEST = Path(__file__).parent / "verification" / "toyota-verified.json"
SOURCE_BYTES = 71755650


def load_verification(path=MANIFEST):
    raw = Path(path).read_bytes()
    if len(raw) > 16384:
        raise ValueError("Oversized Toyota source-verification manifest")
    value = json.loads(raw)
    if (not isinstance(value, dict) or value.get("format") != "bod3-source-verification"
            or value.get("formatVersion") != 1 or value.get("make") != "Toyota"
            or value.get("source") != {"repository": "sarapudheen-ops/puthus", "release": "assets-v1",
                "asset": "TOYOTA.zip", "bytes": SOURCE_BYTES, "sha256": SOURCE_SHA256}
            or value.get("verification") != {"verified": True, "assertedBy": "repository-owner",
                "scope": "entire-archive-including-nested-members", "authoritativeSpecification": True,
                "completeSource": True, "sandboxInspectionDecodingAndExecutionAuthorized": True}
            or value.get("runtimeAuthority") != {"automaticVehicleSelection": False, "crossSystemFallback": False,
                "physicalEcuVerification": False, "writeAuthorization": False}):
        raise ValueError("Missing or mismatched full-archive owner verification")
    return value


def verify_archive(archive, manifest=MANIFEST):
    value = load_verification(manifest)
    archive = Path(archive)
    digest = hashlib.sha256()
    with archive.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    if archive.stat().st_size != SOURCE_BYTES or digest.hexdigest() != SOURCE_SHA256:
        raise ValueError("Archive does not match the owner-verified Toyota specification")
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    args = parser.parse_args()
    print(json.dumps(verify_archive(args.archive, args.manifest), sort_keys=True))
