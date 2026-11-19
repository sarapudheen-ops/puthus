# Definition-driven ECU reads

This is the runtime/schema for exact identification and fault reads, **not proof that a recovered
Toyota definition has been translated**. The current Toyota converter still emits zero executable
definitions. The addresses and records used in the tests are explicitly synthetic.

## Scope and backwards compatibility

An `EcuDefinition` may carry `diagnostics`:

- `identification`: a list of named `read` definitions with `ASCII` or `HEX` presentation.
- `faults`: an optional `read`, its exact SAE J2012 record `layout`, and an optional exact `clear`.
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
status interpretation requires an explicit status byte. Other code encodings/layouts remain unsupported,
not guessed.

## Confirmed clearing

A clear definition must supply an exact `14` request and its positive reply: a two-byte group for KWP,
a three-byte group for UDS. The dialog displays the selected ECU, dialect and **actual request bytes**.
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
