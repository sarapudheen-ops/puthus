# Read-only ECU bring-up

## Status and proof boundary

The app now has a source-candidate browser, a read-only probing engine, saved setup/evidence storage,
a report share action, and verified-setup pack transfer. **The current Toyota converter still produces
zero executable recovered definitions.** Its entry/address/init/timing and identification/DTC mappings
remain untranslated. Those candidates are listed and ranked, but explicitly blocked from transmission.
This feature does not turn an opaque native field into an app timing, invent an identification request,
or approve a live formula merely because an ECU replied.

No real vehicle was contacted during development. Simulated tests establish software behavior, not
physical coverage. There are four different assertions:

1. A source record was recovered and belongs to the manually selected source branch.
2. A definition's service, byte layout, formula and applicability have been translated/reviewed.
3. A correctly framed ECU answered the three reads on a particular vehicle/connection.
4. A write recipe is independently reviewed and all existing write-operation safeguards pass.

None implies the next. In particular, imported `verified` flags imply none of them.

## Using the screen

1. Disconnect before choosing **Toyota vehicle packs → Make → Model → Generation → Variant**.
   Choose a system. Nothing is automatically selected by VIN, saved profile or pack import.
2. In **Read-only ECU bring-up**, enter a unique vehicle instance: a garage label or a VIN entered
   manually. Two cars of the same model need different labels. This is separate from model applicability.
3. Inspect **all** recovered alternatives from that source function. Rank is descending distinct-source
   support, then source ID. The production converter counts byte-identical SYSTEMENTER records,
   **not** the number of menu references or the number of vehicles presumed compatible. Other selectors
   are visible evidence, not permission to fall back to another ECU.
4. For an eligible translated candidate, connect the adapter and choose automatic ranked probing or
   select a single candidate. Progress shows candidate and current identification/live/DTC step.
   **Stop bring-up** or Back cancels. It stops at the first working setup; later candidates are not sent.
   If the list is entirely unresolved, **Report blocked candidates** works without ECU traffic.
5. A successful, fully saved run displays **Verified on vehicle · this connection**. Only its exact
   recovered definitions become the selected system's runtime data. Live prerequisites/applicability
   checks still run independently; success does not override them.
6. **Share bring-up report** shares one JSON file, including the entire candidate list, attempted
   candidates, outcomes, positive payloads, timeout/error details and unfiltered adapter exchange text.
   Cancelled/failed/blocked runs can also be shared. Reports can contain VINs/serials/private data.
7. **Export verified setups data pack** (also under Data packs) exports saved successful reports for
   multiple vehicles/systems. Import through **Data packs → Choose file → preview → Import** while
   disconnected. Imported reports are labelled **Transferred evidence — re-verify here**.

A saved or transferred candidate can be explicitly selected for a read-only retry. It must still match
an exact source-pinned candidate fingerprint in the installed build. An import never installs a new
executable candidate, changes the vehicle selection, activates formulas, or grants write trust. Different
cars may reuse the same source setup only after selecting their own instance and probing it again.
After reconnect, app restart, changing vehicle instance, changing system, or changed definitions, current
verification is absent until another successful bring-up. Saved history and logs remain available.

Enhanced-only ECUs need not pass generic OBD negotiation. If a selected candidate is executable, its
explicit protocol can create the diagnostic session without inventing a generic fallback protocol.

## Probe and safety contract

An executable candidate must come from the APK-pinned hash tree and contain:

- One exact physical ECU address pair and an explicit protocol/dialect/link, including a complete
  K-line init/baud/parity/timing configuration where applicable. No functional/masked response range.
- An explicit default diagnostic session. Bring-up does **not** send service `10` or security access.
- A source-bound nonempty identification definition, one source-bound live PID, and an explicit DTC
  read with its complete record decoder. No generic UDS or emissions definitions are substituted.
- Same-ECU provenance on all definitions and live prerequisites. Unknown mappings stay blocked.

The engine serializes configuration, init and reads using the existing Session operation lease. Adapter
routing cannot interleave with live polls, scans, coding or active tests. A separate read-only context
rejects write/clear grants even if a caller supplies a valid existing grant. KWP `81` is used only by the
existing documented communication initialization, not as diagnostic session control. No native vendor
code or EXPRESS interpreter is run or shipped.

Each candidate is capped at 12 seconds. Diagnostic requests have a 1-second host timeout; configuration
commands have 1 second; fast init 2 seconds; 5-baud init up to 6 seconds. For K-line probes, the adapter's
response timeout is capped at 500 ms (or the smaller source limit), leaving Bluetooth/prompt headroom.
This is an observation ceiling, **not a newly inferred source P2\***. The candidate's documented normal
response timeout is restored and acknowledged before activation or a subsequent guarded operation.
A valid reply demonstrates the successful exchange, not every extreme of a timing window.

Working requires **all three** reads to have exactly one complete positive reply from the selected ECU:
correct framing/address, service/identifier echo, independently validated K-line length/checksum or
complete CAN ISO-TP reassembly, no mixed positive/negative messages, correct identification data bounds,
finite live decode and a complete valid DTC layout. A valid empty fault list is fine; `NO DATA`, NRCs,
`OK`, pending-only, partial messages, wrong checksum/framing or a wrong ECU are not proof. No retry or
alternate service is substituted on a failed probe. Custom profile addressing/flow-control/baud options
that could change the candidate are refused.

An ELM timeout/cancellation must drain the outstanding prompt before another command. If it cannot,
the driver requires reconnect rather than risking attribution of a previous candidate's late reply to
the next one. The existing adapter-only ATZ reset retry is the sole reset-recovery exception. Timeout
logs retain unterminated response text observed so far. No ECU write is automatically replayed.

### Active tests, coding and clears

Recovered write features additionally require a source-reviewed **unique ECU serial** identification
(`identityKind: ECU_SERIAL`); part numbers/software versions alone (`NON_UNIQUE`, the default) cannot
prove it is the same ECU and keep writes disabled. Immediately before each leased ECU operation or
direct granted physical write/clear, Session calls the same-ECU guard **inside** the operation lease.
It re-reads the exact identification on that exact link and compares the full payload with the current
bring-up identity. A failure, exception or changed identity revokes current verification before any write.

This gate does not create a write grant or change imported recipe trust. Expert mode, applicability,
voltage/engine/speed checks, one-operation confirmations, backup/diff/read-back/rollback, owned keepalive,
output-off cleanup and audit remain in the existing engines. Ordinary legacy non-recovered catalogs
retain their existing behavior; this gate is added to manually selected recovered-system sessions.

## Files, provenance and bounds

- Report format: `bod3-ecu-bring-up-report`, version 1.
- Transfer format: `bod3-verified-ecu-setups`, version 1; `setups` is a list of successful reports.
- Candidate pages: `kind: bring-up-candidates`, up to 128 rows per range, at most 256 total candidates.
  Production pages currently contain raw metadata, support source IDs and explicit blockers only.
- Candidate fingerprint includes the exact definitions, formulas, source IDs, transport/session/identity
  kind and metadata, with write-trust flags stripped. A fingerprint is an integrity/matching key, not
  independent provenance or physical proof.
- Candidate definitions: at most 2 MiB. Full evidence: at most 16,384 exchanges / 4 MiB accounted text.
  The observer refuses further transmission on exhaustion; an incomplete run cannot be verified.
  It is independent of the pre-existing rolling 20,000-entry diagnostic log.
- JSON input/output: 16 MiB maximum, strict field/version/type/count validation, bounded strings/lists;
  at most 32 saved scopes per store. Capacity errors are explicit; no old vehicle evidence is silently
  evicted. Files are app-private, written through an fsynced temporary file and atomic rename.
- Local observations and transferred claims are stored separately. An all-or-nothing import cannot
  overwrite local evidence. Current-connection proof exists only in memory and references the Session
  object, selected instance/path/system and current source fingerprint; it is never deserialized.

The converter's all-disabled Toyota guard is intentionally retained and also rejects any accidentally
executable bring-up page in this converter version. Enabling production probes requires a real,
source-bound transport/identification/DTC/live converter with tests; changing an enable flag is not it.

## Tests

`BringUpTest` and the shared synthetic scenarios cover working/silent/wrong-framing/wrong-checksum ECUs,
wrong ECU/echo, mixed pending/positive replies, short live data, malformed DTCs, empty DTCs, ranking,
first-success stop, manual choice, rejected timing commands, no writes, no interleaving, cancellation,
full evidence versus rolling logs, evidence limits, atomic persistence/transfer, capacity, source
fingerprints, import distrust, connection/vehicle separation and same-ECU write denial. CAN scenarios
exercise both valid multi-frame identification and truncated reassembly. Adapter-driver tests cover
cancelled/late prompts, a missing resync prompt and partial timeout evidence. Converter tests check all
source alternatives, exact support counts, unknown fields, selector mismatch and no cross-ECU fallback.

These tests are simulation and software validation only. They do not claim an ECU has been contacted
or any current Toyota recovered system has become runnable.
