"""Fail-closed compiler for exact, oracle-audited Toyota Code-1 link evidence.

This compiles an entry only when the regional binding join has supplied exact
wire frames. SYSTEMENTER binding IDs alone are never protocol frames. The
transport/pin/timing mapping is intentionally limited to the two reviewed
byte-exact timing profiles and the OBD pin pair 07/07.
"""
from __future__ import annotations


class LinkUntranslated(ValueError):
    pass


PROFILES = {
    ("8025", "053c00230f"): (9600, "iso9141_2", {"p1": 5, "p2": 60, "p3": 0, "p4": 35, "w4": 15}),
    ("b028", "053c001919"): (10416, "kwp2000_fast", {"p1": 5, "p2": 60, "p3": 0, "p4": 25, "w4": 25}),
}


def _frame(target: int, mode: int) -> bytes:
    payload = bytes((0x81, target, 0xF0, mode))
    return payload + bytes((sum(payload) & 0xFF,))


def compile_code1(entry: dict, binding_frames: dict[int, bytes], *, oracle_verified: set[int] | None = None) -> dict:
    """Compile a Code-1 entry against the exact COMM/native-oracle frame join.

    `binding_frames` is keyed by native SYSTEMENTER binding ID and contains the
    corresponding already-validated wire bytes. Unsupported IDs, rows, or
    profile variations raise LinkUntranslated; there is no fallback.
    """
    if not isinstance(entry, dict) or str(entry.get("transportCode")) != "1":
        raise LinkUntranslated("link_code_unsupported")
    fields = entry.get("sourceFields") or {}
    baud_raw = entry.get("baud")
    baud_hex = f"{baud_raw:04x}" if isinstance(baud_raw, int) else str(baud_raw).lower()
    timing_hex = str(fields.get("runtime107f0", "")).lower()
    profile = PROFILES.get((baud_hex, timing_hex))
    if profile is None:
        raise LinkUntranslated("link_profile_unreviewed")
    baud, protocol, timings = profile
    raw_address = str(fields.get("runtime107b0", "")).lower()
    if len(raw_address) != 4 or any(c not in "0123456789abcdef" for c in raw_address):
        raise LinkUntranslated("link_address_invalid")
    # SYSTEMENTER stores this 16-bit field little-endian: F0 13 -> target 13.
    address_bytes = bytes.fromhex(raw_address)
    target, tester = address_bytes[1], address_bytes[0]
    if tester != 0xF0:
        raise LinkUntranslated("link_tester_address_unreviewed")
    pin_pairs = str(fields.get("pinPairs", "")).lower()
    if pin_pairs != "0707":
        raise LinkUntranslated("link_pin_pair_unreviewed")
    init_ids, keep_ids, exit_ids = (entry.get(key) for key in ("initBindings", "keepaliveBindings", "exitBindings"))
    if not isinstance(init_ids, list) or not init_ids or not isinstance(keep_ids, list) or not keep_ids:
        raise LinkUntranslated("link_binding_ids_missing")
    if not isinstance(exit_ids, list):
        raise LinkUntranslated("link_binding_ids_missing")
    if not isinstance(binding_frames, dict) or not isinstance(oracle_verified, set):
        raise LinkUntranslated("link_native_oracle_evidence_missing")
    def matched(ids, expected):
        frames = []
        for ident in ids:
            raw = binding_frames.get(ident)
            if not isinstance(raw, bytes) or not raw:
                raise LinkUntranslated("link_binding_frames_missing")
            if ident not in oracle_verified:
                raise LinkUntranslated("link_native_oracle_evidence_missing")
            frames.append(raw)
        if expected is not None and any(frame != expected for frame in frames):
            raise LinkUntranslated("link_binding_frame_mismatch")
        return frames
    init_mode = 0x81 if baud == 10416 else 0x01
    native_init = _frame(target, init_mode)
    init = matched(init_ids, native_init)
    # Native keepalive bytes are not universally 3E; retain the exact oracle-
    # checked binding rather than substituting a generic tester-present frame.
    keepalive = matched(keep_ids, None)
    matched(exit_ids, None)
    return {
        "protocol": protocol, "baudRate": baud,
        "txHeader": f"80{target:02X}F0", "rxHeader": f"80F0{target:02X}",
        "initBytes": init[0].hex().upper(), "keepAliveBytes": keepalive[0].hex().upper(),
        "initMethod": "fast" if baud == 10416 else "five_baud_slow",
        "fiveBaudAddress": target if baud == 9600 else None,
        "pinPair": pin_pairs.upper(), "timings": timings,
    }
