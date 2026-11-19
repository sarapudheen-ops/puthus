# Toyota live-data prerequisite handling (work in progress)

**Runtime/schema groundwork, not runnable recovered Toyota coverage.** The production converter and
pack verifier still emit/accept **zero executable recovered Toyota definitions**. The numeric review
translator is deliberately not a producer of app `data.pids`. No physical adapter/vehicle test is claimed.

## Native reply origin, not a guessed sensor offset

The checksum-verified static-reader reports in
[inspection run 44482990080](https://github.com/sarapudheen-ops/puthus/actions/runs/44482990080)
(`d782c9a94b87e032fd9725aceb2d0154edcaa05a`, 527 recovered reports) establish this sequence:

1. `COMM GetKwpRecData @0x16998` retains the first positive service byte. On later A8 messages it
   changes the strip width from 3 to 4, stripping the repeated service **and** identifier before
   concatenating their data. It is not the final public reply buffer.
2. `COMM SendCmdAndGetReceive_EX @0x14f30..0x14f34` saves `output+1`; at
   `0x1593c..0x15960` it moves from that pointer to the output and returns `length-1`.
   Thus the public buffer has had the first service byte removed.
3. `FDATA SendCmdAndGetPid @0x19f2c..0x19f94` skips one byte of that public buffer and reads
   `[local ID, length, bytes...]` records. With the exact EU/function-61 template-19 binding
   (`Toyota_bin_12` → frame 10327 → pool request `A801`, expected prefix `E801`), the skipped
   public byte is `01`, **not** another service byte or the first record ID.
4. `FDATA SendCmdGetSupid @0x3373c..0x33764` similarly copies from public reply `+1` for a
   two-byte local-ID request. For a `21 xx` sample, the public buffer is `[xx, sample bytes...]`.
   App formulas instead see bytes after the complete `61 xx` echo. Native position 1 therefore
   corresponds to app `dataOffset=0`; adding/removing a second identifier would be wrong.

These are static consumer observations, not a captured ECU exchange or proof of every transport
variant. Native adapter packet wrappers are not sent as ECU requests. The app independently checks
physical ECU headers/checksums and every positive-service/identifier echo.

## Applicability is separate from a live sample

`GetDataMonitorListP4` calls `SendCmdAndGetPid`, `GetEnablePidListP4` and `ChangeSignalLSB` before
selecting monitor rows. For the bounded conditional numeric class:

- `PidCheckSupportP4 @0x1a858..0x1a990` first requires the local ID in the discovered list. It then
  finds that ID in the **A801 support-record cache**, checks that the entire monitor bit window fits,
  and applies MSB-first, 32-bit `CaculateBit`. The sensor sample being nonzero is not the condition.
- `GetEnablePidListP4 @0x19fec` uses template 15 and each leading slot-3 discovery-group row.
  `AnalyzeDsCmdDataP3 @0x19390` includes the group itself after a nonempty reply and maps the first
  31 MSB-first mask bits to following local IDs. The continuation bit is not a feature ID.
- `GetDSCmdDataInfoP4 @0x1a998` prunes membership against the leading slot-6 command rows/cache.
  A similar local ID or a shared display label is not a substitute for this join.
- The monitor cutoff, numeric class/bit position, dynamic-LSB override, reserved signal ranges and
  function-specific `Viewer_bin_12` exclusions also apply. `Viewer_bin_*` tables are distinct from
  `Toyota_bin_*`; a missing viewer file must not be interpreted as an empty exclusion list.

`tools/thinkdiag/toyota_live.py` now requires those shared support inputs and exact ECU-table joins
before returning even a **disabled numeric review candidate**. It retains original IDs for the row,
monitor, EXPRESS text, unit/name, command, support bindings/pool entries, discovery group, command-info
row and exclusion table. It does not run vendor scripts or approve CALC semantics, addresses or init.
Unreviewed classes/joins remain individually rejected with a reason. Its output has `enabled:false`
and cannot be used as an ordinary PID import.

## App schema and lifetime

`CustomPid.supportChecks` is an ordered list (maximum 32) of same-ECU read-only prerequisites:

- `request`: one complete read-only service and identifier, not a clear, session, security or write.
- `formula`: a bounded byte-dependent expression; nonzero means the prerequisite holds.
- `dataOffset`: bytes skipped after the exact echo (or within the selected support record).
- `sourceRecords`: the original prerequisite record IDs, required separately for converted packs.
- `recordId`: optional one-byte A801 TLV record ID. It is not allowed on other requests.

A physical request/response address pair is mandatory. No prerequisite can switch to another ECU.
A801 alone can concatenate up to 256 exact `E801` messages, bounded to 8192 data bytes. Every TLV,
including trailing and unrelated records, is validated before any record can admit a feature.
Truncated/duplicate records, wrong echoes/ECUs, mixed negative replies, invalid K-line checksums and
incomplete/out-of-order ISO-TP exchanges fail closed. Ordinary reads remain single-positive-message.

`LiveReader` caches prerequisite **replies**, not sensor values or a cached authorization boolean,
within one polling run and one ECU/link epoch. Conditions are evaluated against those replies for
each definition. ECU changes, routing resets, expired K-line initialization, explicit startCommunication,
state-changing operations and failed/cancelled exchanges invalidate the scope. Resetting midway
through discovery cannot combine prerequisites from different ECU sessions. A fresh polling run
creates a fresh reader. Legitimate zero sensor readings are accepted on the very first sample.

Catalog JSON needs **formatVersion 3**, and datapack JSON **formatVersion 4**, for prerequisites or
an explicit bare-KWP `3E` keepalive. Without those additions, the existing minimum versions are retained
(including catalog 2/datapack 3 for exact ECU identification/fault definitions). Version gates and
strict PID/check keys prevent older apps from silently dropping prerequisites. Obsolete
`supportFormula` declarations are rejected, not silently converted to a sensor-nonzero rule.

Bare `3E` is configured per KWP ECU; UDS retains `3E00`. Keepalive never chooses another ECU,
initializes a new link, or retries a write. This metadata is **not yet a source-bound Toyota link**.
Imported/edited/copied recipes remain unverified regardless of prerequisite provenance.

## Validation and remaining work

`NativeLiveScenarios` / `LiveDataTest` cover first-zero readings, prerequisite ordering/absence,
multiple definitions sharing replies without sharing authorization, multi-message A801 on both
synthetic K-line and CAN, malformed/duplicate records, hidden incomplete transport frames,
NRCs/wrong ECU/echo, reset/expiry/disconnect, exact keepalive, JSON versions and round trips.
`VehiclePackTest` requires prerequisite provenance and checks that it never grants import write trust.
The Python tests exercise exact source joins and refusal of absent, ambiguous or mismatched inputs.
These are synthetic regression tests, not converted real-vehicle features.

Still required before enabling a recovered system: complete source-bound address/baud/P1–P4/init and
session behavior (including native fast/slow alternatives where present), CALC expression semantics,
remaining applicability/decoder classes, production conversion integration and per-feature accounting,
exact fault/freeze-frame/ECU-info definitions, tests/coding/special-function translations and their
existing safety-engine integration. The all-disabled production guard remains in place.
