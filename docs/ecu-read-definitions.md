# Definition-driven ECU reads

This is the runtime/schema for exact identification and fault reads, **not proof that a recovered
Toyota definition has been translated**. The current Toyota converter still emits zero executable
definitions. The addresses and records used in the tests are explicitly synthetic.

## Scope and backwards compatibility

An `EcuDefinition` may carry `diagnostics`:

- `identification`: a list of named `read` definitions with `ASCII` or `HEX` presentation.
- `faults`: an optional `read`, its exact record `layout` (SAE/UDS or explicitly tagged native Toyota), and an optional exact `clear`.
- Each read/clear retains its own `sourceRecords`, in addition to the ECU's source IDs.

For a **converted system**, `diagnostics` is mandatory, including when empty. An explicit empty
object disables identification/fault reads; it never falls back to generic UDS `22 F1xx`, UDS `19 02`,
standard emissions requests, another ECU, or another vehicle's declarations. Missing reads produce a
reason without accessing the adapter. Freeze frames/extended data for converted ECUs remain disabled
until their exact definitions are supported and converted; CAN transport does not imply UDS semantics.

Existing ordinary UDS/OBD declarations without `diagnostics` retain the legacy behavior. Ordinary KWP
declarations without read definitions remain disabled for fault/identification requests. The wire
protocol and application dialect are separate: KWP over CAN uses KWP request/response rules too.

Catalog JSON requires at least **formatVersion 2** when any ECU has `diagnostics`, and datapack JSON
at least **formatVersion 3**. Ordered live prerequisites or an explicit bare-KWP `3E` keepalive require
catalog **3** / datapack **4**; see [Toyota live prerequisites](toyota-live-prerequisites.md).
Earlier versions remain readable and are still emitted when their new fields are absent. New fields
cannot be declared in an earlier format version. This makes an older app reject an enhanced export
rather than silently discard its read restrictions and send generic requests.
CSV cannot express enhanced links/read definitions: such an export fails before writing anything and
asks for JSON. JSON round trips preserve the link, exact requests, reply windows, layouts and source IDs.

## Exact response windows

A `read` contains:

| Field | Meaning |
|---|---|
| `id`, `name` | Stable definition identity and display label |
| `request` | Complete read-only service/parameters as hex bytes |
| `responsePrefix` | Complete positive-service/identifier echo, plus any documented fixed prefix |
| `dataOffset` | Bytes skipped **after** that prefix, not an assumed native reply-buffer origin |
| `minBytes`, `maxBytes` | Bounds of the remaining data window |
| `sourceRecords` | Original source record IDs, not a trust flag |

Prefixes must match the positive service and identifier/subfunction echo. Writes, session changes and
security requests are rejected by this schema and the session read policy. Prefix mismatches, short or
oversized replies, and multiple positive messages cannot be reported as successful values. The runtime
does not retry an unresolved variant or infer a missing prefix/offset from a familiar PID label.

A fault layout explicitly specifies record width, two-byte J2012 or three-byte J2012-plus-failure-type
code position, optional status position and an optional leading big-endian record count. Every record
must be complete and any count must agree with the body; partial results are rejected. Manufacturer/KWP
status defaults to `RAW` and is displayed as raw hex, **not interpreted as UDS status bits**. `UDS`
status interpretation requires an explicit status byte. A separate `kind: "TOYOTA_P3P4"` layout now
ports native DTCF assembly/state semantics; it is not an alias for this SAE/UDS layout. Other encodings
are not guessed.

### Native Toyota fault records

`TOYOTA_P3P4` requires explicit `countIndex`, `recordStart`, `recordBytes`, `assemblyKind`,
`countIsPresence`, `modeLowBits`, `queryKind` and `availabilityMask`. Optional `labels` carry an exact
eight-hex-digit `nativeCode`, source `code`/`description`, and their own source record IDs. Matching is
by the **whole native key**, never a P-code suffix or another system's descriptions. Unknown keys display
as raw `0xXXXXXXXX` unless an explicit `sourceFallback` supplies `missingDescription` and nonempty
`sourceRecords`. That optional branch ports the supplied `GetShowDtcInfo`/`InitDtcNum` numeric display
(4/6 nibbles by assembly kind) and its `[00, xx, xx, xx]-<source text>` note; it does not consult the
passive reference. The original 32-bit key is retained even when the displayed source number is shorter.
The selected ECU screen uses the exact supplied text without filling gaps from other systems.

The native byte origin is after the positive service byte. A converter must retain/normalize that
origin explicitly, including any subfunction echo. Assembly kinds 1–7 retain the native big-endian code,
status-tail or embedded-nibble semantics. Count guards distinguish a presence byte from a bounded
record count; neither is silently replaced with the strict SAE layout's exact-count rule. Query kind 3
has the source's mode-specific offset override. State IDs `FFFF0050`–`FFFF0056` are Current, History,
Pending, Readness, Permanent, Test Failed, Confirmed. Current does **not** imply a MIL, UDS bit 0 or
Confirmed. Deduplication is by `(native code, source state)`, so two different states survive.

Invalid fields, missing count/start, oversized data and partial records fail the entire read. This is
intentional hardening over native partial-tail handling, not simulated transport success. The parser,
Python port and Kotlin port have independent tests; native/Kotlin result digests agree on 70,144
state/mask and 2,112 assembly vectors, plus 3,584 source numeric-display vectors and 196,608 exact-key
byte-merge calls. The latter preserves opaque source bytes and first-seen order, with atomic count-wrap
rejection; those bytes are not write/identity grants. `toyota_dtc_labels.py` compiles exact per-function
labels from the source mapping rows, with GAG record/offset provenance when extracted from the archive.
PSA/P5/flash callers and the source P3/P4 kind-5 non-decoding workflow query are not ordinary P3/P4 fault
reads. This schema alone is not complete source-to-executable vehicle conversion, multi-query/discovery
workflow coverage, transport proof or physical testing.

## Confirmed clearing

A clear definition must supply an exact `14` request and its positive reply: a two-byte group for KWP,
a three-byte group for UDS. The source's physical KWP `04` → `44` variant is also supported explicitly;
it is **not** the broadcast OBD clear and cannot use a broadcast confirmation. KWP fault reads `13 xx`
and the exact `12 FF 01 00/FF` variants are preserved, never rewritten to `18`/`19`.
The dialog displays the selected ECU, dialect and **actual request bytes**.
A one-shot `ClearConfirmation` is bound to those exact bytes and that physical target; mismatches are
rejected before sending or consuming the confirmation. It expires and cannot be replayed. Clears have
no automatic retries, no alternate groups, and no fallback to the legacy UDS clear. The positive clear
reply must exactly match its documented prefix. Faults are read back afterwards through the same ECU's
definition. This does not create coding/actuator/security write grants or relax their existing engines.

Only the APK-pinned vehicle-pack loading path may treat converted source-verified writes as verified.
Imported, edited and copied actuator/coding/security assertions remain unverified, as before.

## Tests and limits

`EcuReadDefinitionsTest` exercises atomic whole-document rejection, JSON version gates/round trips,
source IDs, explicit byte windows, counted/uncounted fault layouts, raw-vs-UDS status, K-line KWP,
KWP-over-CAN, UDS, exact one-shot clears, malformed/NRC responses, and no-fallback behavior.
`VehiclePackTest` rejects a purported converted ECU that omits the explicit read-definition set.
These simulated ECUs establish runtime behavior only; they do not count as recovered Toyota coverage
or physical MIC3313/vehicle validation.
