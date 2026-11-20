# Toyota conversion report

**PARTIAL — 1 executable system · 1,445 executable definitions. Physical vehicle validation is PENDING.**

Source archive SHA-256: `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`.

| Measure | Count |
|---|---:|
| ecuFiles | 1307 |
| manualLeaves | 24662 |
| manualLeavesWithSystemJoin | 24642 |
| normalizedVehicleChoices | 24653 |
| systemEntryRecords | 1308 |
| systems | 287030 |
| systemsWithExactEntryJoin | 286149 |
| unresolvedHierarchyChoices (non-vehicle tasks) | 9 |
| unresolvedSourceRecords | 1055750 |
| ecmectOmittedLiveRows | 985 |
| Executable definitions | 1445 |
| Enabled source systems | 1 |

## Exact per-item accounting

- `unresolved-items.jsonl.gz`: every framed ECU row, declared live row, SYSTEMENTER entry and unclaimed entry-file span, stable file/offset/slot/row ID, exact source bytes and all disabling reasons.
- `entry-index.json`: complete reader-backed regional function/selector directory, framed entry fields, binding IDs and every unclaimed byte span. Opaque header/timing values are not approved transport settings.
- `vehicles.jsonl.gz`: every manual source leaf, full exact path, region/key, all system IDs and hierarchy reasons.
- `systems.jsonl.gz`: every regional system record and converted/unresolved category counts. `null` means **unknown**, not zero or no source support.
- The range bundle retains the same per-system pages. Selecting a vehicle does not fetch the full corpus.
- Unclassified rows are not counted as working PIDs, DTCs, tests or coding. Source label/byte recovery is not an executable mapping.
- Opaque whole-file formats and non-live functional row schemas are not yet enumerated as individual features. Full original source IDs remain in the separate research library; this report is not yet an exhaustive feature census.

## Disabling reasons

- `ecu_entry_untranslated`: Per-ECU SYSTEMENTER address/header, baud, init/session and selector are not fully translated.
- `service_reply_untranslated`: Application service/identifier and reply-mask joins are not fully translated.
- `formula_untranslated`: Signal byte/bit positions, signedness, scaling, unit and runtime applicability are not fully translated.
- `record_schema_untranslated`: This source-row schema has not been translated into an executable definition.
- `hierarchy_untranslated`: The exact source path is retained; normalization into model/generation/variant fields is not yet validated for this path.
- `flash_unsupported`: Source system uses FLASH; flashing/download services are intentionally outside the app's write whitelist.
- `missing_ecu_file`: Source ECU file could not be joined. No generic ECU or address is substituted.
- `missing_function`: No regional FUNCDATA row joins this source function key.
- `missing_system_entry`: No SYSTEMENTER entry exactly matches this region, function and complete selector byte; another selector or ECU is never substituted.
- `missing_system_key`: The ordinary manual MENU leaf has no matching SYSLIST key in its source region.
- `nonordinary_menu_task`: This manual leaf uses a special task, not the proven ordinary VehicleSystemScan key path.

## Reviewed manual choices — exact system accounting

The full checksum-verified manual MENU tree now supplies 24,653 vehicle paths: EU 13,941, NA 5,762,
JP 4,950. Nine short non-vehicle tasks are not fabricated as vehicle models. Source models are literal;
the first detail is a source generation/group, not an inferred chassis generation, and variants retain
all remaining details plus region/communication menu and exact source-node identity. The four starting
reviewed Innova paths below keep their existing IDs and month boundaries. The table is a starting-choice
excerpt, not the full ledger and not executable coverage of those vehicles or of AN40 diesel.

The whole archive is owner-verified by
[`toyota-verified.json`](../tools/thinkdiag/verification/toyota-verified.json), including nested members.
This attestation is verified before conversion and included in the index; its sandbox-execution permission
is separate from executable conversion, operation grants and physical testing. Shared source-system lists
and ECU/function metadata have explicit typed hash references; a reference cannot cross the selected
vehicle/ECU/function boundary. Full regeneration/Android evidence for these changes is recorded below
when it completes, not inferred from historical CI.

| Manual vehicle choice | Source system ID / name | PIDs converted / unresolved candidates | DTCs | Tests | Coding | Reasons |
|---|---|---:|---|---|---|---|
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d8f2` ECMECT (Engine and ECT) | 30 / 985 | 1414 / unknown | 0 / unknown | 0 / unknown | enabled read-only; physical validation PENDING |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d917` ABS (Anti-lock Braking System)/VSC (Vehicle Stability Control)/TRC (Traction Control System) | 0 / 490 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d93c` SRS (Supplemental Inflatable Restraint System) | 0 / 20 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d961` HAL (HL AutoLeveling) | 0 / 121 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d986` IMMO (Immobiliser) | 0 / 68 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d9ab` IMMO (Immobiliser) | 0 / 32 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d732` ECMECT (Engine and ECT) | 0 / 1015 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d757` ABS (Anti-lock Braking System)/VSC (Vehicle Stability Control)/TRC (Traction Control System) | 0 / 307 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d77c` SRS (Supplemental Inflatable Restraint System) | 0 / 20 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d7a1` HAL (HL AutoLeveling) | 0 / 121 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d7c6` IMMO (Immobiliser) | 0 / 68 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d7eb` IMMO (Immobiliser) | 0 / 32 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00062d9e` ECMECT (Engine and ECT) | 0 / 1015 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00062dc3` ABS (Anti-lock Braking System)/VSC (Vehicle Stability Control)/TRC (Traction Control System) | 0 / 490 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00062de8` SRS (Supplemental Inflatable Restraint System) | 0 / 20 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00062e0d` IMMO (Immobiliser) | 0 / 68 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00062e32` IMMO (Immobiliser) | 0 / 32 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00023a75` ECMECT (Engine and ECT) | 0 / 1015 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00023a9a` ABS (Anti-lock Braking System)/VSC (Vehicle Stability Control)/TRC (Traction Control System) | 0 / 307 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00023abf` SRS (Supplemental Inflatable Restraint System) | 0 / 20 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00023ae4` IMMO (Immobiliser) | 0 / 68 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
| Toyota / Kijang Innova / TGN · -2009.07 / MINI VAN · w/o VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@00023b09` IMMO (Immobiliser) | 0 / 32 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |

Exactly one reviewed system is enabled, read-only: `SYSLIST_EU.BIN@0009d8f2` ECMECT (function 61, full
selector 0x11, `EU/Ecu_Data_0081.BIN`; TX `8013F0` / RX `80F013`, 10416 baud, init frame `8113F08105`)
with 30 exact live PIDs, 1,414 exact DTC labels, 4 ordered fault queries and 1 exact 0904
calibration-ID read. Clearing, coding, actuators, security access and flashing are excluded; all other
systems remain disabled. The 985 remaining reviewed live rows are itemized in `ecmect-live-omissions.json`.
No guessed CAN IDs, local identifiers, formulas, security algorithms or source applicability were enabled.
Source verification is distinct from completed conversion and from physical vehicle testing.

## Exact ECU-entry joins (reader-backed; not operational settings)

The bounds-checked `SYSTEMENTER.BIN` reader now retains **1,308 entries in 1,276 lists** across three
regional directories, including every init/keep-alive/exit binding ID and all five unclaimed spans
(four `ffffffff` markers and the two trailing zero bytes). Field names describe native runtime offsets;
they are **not yet approved ECU addresses or adapter timings**. The converter publishes `entry-index.json`
and individual disabled-entry pages, links them into each selected system's on-demand pack, and lists
an explicit `missing_system_entry` reason when a complete selector byte does not match.

The verified full regional SYSLIST excerpts give these exact joins (no low-nibble/protocol fallback):

| Region | Systems | Exact entry joins | Missing exact entry joins |
|---|---:|---:|---:|
| EU | 157,500 | 157,348 | 152 |
| NA | 74,985 | 74,256 | 729 |
| JP | 54,545 | 54,545 | 0 |
| Total | 287,030 | 286,149 | 881 |

These joins do not change the **zero executable definitions** figure. The system/entry/row counts above
were originally established by the full conversion in [Actions run 44482990080](https://github.com/sarapudheen-ops/puthus/actions/runs/44482990080)
for `d782c9a94b87e032fd9725aceb2d0154edcaa05a` (conversion check `135420684960`). The 1,313 additional
unresolved records versus the pre-entry-reader report are the 1,308 entry records and five unclaimed
spans, not newly supported diagnostic features. This run published 527 checksum-verified static
reports; static disassembly and exact joins alone are not executable coverage.

## Evidence and current integration status

This is the **full 1,307-ECU-source accounting**, published by
[Actions run 44482990080](https://github.com/sarapudheen-ops/puthus/actions/runs/44482990080)
for `d782c9a94b87e032fd9725aceb2d0154edcaa05a`, not the earlier 47-BIN/12-ECU local sample.
Download that run's `toyota-vehicle-packs` artifact for the exact ledgers named above.
Large generated objects/ledgers remain outside Git and outside the APK.

The selected-path loader, closed selected-system scope, manual confirmation, bounded range cache,
and system/feature UI are being integrated in this PR. They do **not** supply missing diagnostic
semantics. Until the joins listed above are translated and tested, every recovered real system
stays disabled. Synthetic KWP/CAN tests establish engine behavior, not recovered Toyota coverage.

### Native execution and fault-decoder port — not counted as executable

Owner-authorized supplied functions now execute in bounded offline AArch64 emulation. This supersedes
older static-only descriptions; no host `dlopen`, constructors, JNI, syscalls, network, adapter or ECU
is bridged. `native-oracle.json` records explicit file-array/libc/formatting fakes. Full-archive owner
verification precedes every source run, and every decoded payload is checked against the complete
manifest before any native reader sees it. The reviewed individual library identities/result hashes
are in [`native-oracle-v1.json`](../tools/thinkdiag/schema/native-oracle-v1.json).

| Executed native consumer | Compared domain | Result |
|---|---|---|
| FILE `GetInfoFromEnterList` | all 1,308 source entries | byte-for-byte loaded fields match |
| FILE `GetRDtcDataFromRDTCPart` / `GetCDtcDataFromCDTCPart` | 1,186 fault blocks / 1,184 clear blocks; 1,881 / 1,208 queries; 180,316 mappings across EU/NA/JP | exact fields, offsets, mapping arrays and source round trips match |
| COMM `GetCommFrameInfo` / `GetFuncCommFrameInfo_MultiCmd` | all 8,775 regional frame records, 34,671 default/declared-fault template lookups; 18 starting EU/function-61 joins | exact request/mask/reply bytes, frame IDs and source order match; 372 nondefault bindings are counted, not executed; not transport proof |
| COMM `InitCommSet_Ex` / `InitKWPISOMode` / `MultiPackFunc` | 284 fast-KWP source entries, 568 configuration traces, 17,892 source packets; eight synthetic configuration branches; 2,016 shared short-packet contract vectors | Python calls/packets match native; Kotlin packet digest matches; adapter APIs are trace-only fakes, not ST/VT configuration proof |
| Linked FILE → SYSP → COMM entry / keepalive / exit | 284 first-positive-entry, 568 keepalive-on/off and 284 exit traces with exact regional/function/full-selector source joins | native FILE loading, COMM lookup/configuration/packing and SYSP consumers execute together; positive adapter reply is synthetic; negative/retry/ISO fallback, adapter scheduling and physical semantics remain unvalidated |
| DTCF `GetDtcStateResult` / `GetDTCCodeFromAnsP3P4` | 70,144 status/mask and 2,112 record vectors | independent Python and app Kotlin result hashes match |
| DTCF `GetDTCCodeFromAnsP5` | 49,152 core record-projection vectors; 395 source P5 blocks share the compared header | native/Python/Kotlin hashes match; auxiliary freeze/mask side-table allocation is explicitly disabled, not validated |
| DTCF `GetShowDtcInfo` / `InitDtcNum` / `SetDiagCodeData` | 27,035 exact TCODE pairs; 3,584 width/display vectors; 21 missing-key display calls; 196,608 exact-key byte merges | Python matches native consumer instructions; Kotlin display/merge hashes match; English IdSearch/TEXT/libc boundaries are declared shims |
| CALC numeric evaluator / `ResultCalcFromScript` | 65,792 numeric vectors, two arithmetic forms, eight script edges | numeric results match; libc/printf is a declared shim |

The new strict app `TOYOTA_P3P4` fault layout preserves the native count/presence rules, query/mode
origin override, assembly kinds, exact 32-bit code keys and source state IDs. It rejects malformed
partial replies atomically rather than copying native partial-success behavior. Source states are not
assumed UDS bits, and missing selected-system labels do not fall back to global descriptions. Exact
KWP `12`/`13` reads and the separately confirmed physical KWP `04` → `44` clear variant never become
`18`/`14` substitutions or broadcast commands. Same-current-ECU guards are checked before consuming a
clear confirmation; the read/write/clear policy tests are unchanged.

The exact label compiler joins **179,770 distinct per-function keys** from all 180,316 mapping rows,
with **zero missing TCODE keys** (EU 66,396; NA 56,342; JP 57,032). It preserves alternate descriptions
with identical printed codes and every source mapping row ID. GAG extraction now retains original
index/text offsets and encoded lengths. Source runs re-extract English records from the pinned archive,
not mutable JSON. An optional source-defined unknown-code presentation preserves the original key;
it is not a global P-code lookup. The app-layout compiler rejects PSA/P5/flash callers and the P3/P4
kind-5 workflow query whose native caller deliberately skips fault decoding.

The separate P5 layout/compiler preserves P5's record origin without applying P3/P4 count/origin
rules, and requires an explicit caller-supplied availability mask. Its 395 source headers match the
compared shape; mask discovery, auxiliary side tables and query workflows remain separate work.

The separate `toyota_transport.py` projection preserves literal COMM API arguments for the 284
native transport-1/5 entries. It retains source baud, pin-pair selection and the 9,600-baud timing
exception. The alternate connector receives the high byte of the original four-byte timing load;
a tempting three-byte slice produced a real native-comparison failure and now has a regression test.
Source flags `81`/`33` packets reject payloads above 63 bytes rather than changing addressing bits or
substituting an extended-length source variant. This is not yet an approved adapter configuration.

`toyota_lifecycle.py` retains the function-specific initial/keepalive/exit packets instead of inserting
a generic testerPresent or stopCommunication. Of the 284 fast-KWP entry joins, only **41** keepalives
are bare `3E`: the others are `0100` (42), `2100` (174), `2102` (4), `21E1` (3), and `3E01` (20).
Native fast keepalive passes literal `(wire, length, 3, 800)` to `SetLinkKeep`; the API units and
MIC3313 scheduler mapping are still open. Exit requests are source `82` (236), `3E` (42), or `3E01` (6),
using the variadic native packet-pointer API, not a pointer/length pair. Original expected-response
bytes are retained, but SYSP exit does not enforce them. Pulse arguments are 25/25 (78) or 35/15 (206).
The oracle now pins five library identities and links six explicitly identified SYSP cells directly
to real COMM guest exports. None of this is same-current-ECU proof or production emission.

Local checks at this checkpoint: **186 Python tests**, **122 Kotlin source files compiled**, and
**111 standalone core scenario groups passed**, including DTCF P3/P4 and P5 core/state/display/merge
and the short-KWP-packet digest. Standalone results are **not** Gradle/JUnit, lint, Android or physical
results. The complete local expanded native/reference run passed in **396.26 seconds**, with English
JSON keys reversed, on restored hash-checked inputs. It is not the original-ZIP/full-manifest CLI check.

The first full-archive CI attempt at `d85c290` failed its native-reference gate. After canonicalizing
global label keys independently of GAG/JSON iteration order (without changing original offsets or
per-function mapping order), the corrected `b0e3e96` full-archive inspection
[44672825656](https://github.com/sarapudheen-ops/puthus/actions/runs/44672825656) **passed**. Push Android
[44672825723](https://github.com/sarapudheen-ops/puthus/actions/runs/44672825723) and PR Android
[44672832977](https://github.com/sarapudheen-ops/puthus/actions/runs/44672832977) also **passed**, including
`assembleDebug lintDebug testDebugUnitTest` and APK-index verification. The push run installed the
actual APK on API 24: **two tests passed in 2.008 seconds**, proving index-only packaging, exact manual
selected-path range delivery/offline cache and no automatic vehicle selection—not ECU functionality.
The PR run deliberately skips the release-publishing emulator job. The subsequent fast-KWP head
`156cb70` also passed [inspection 44675858188](https://github.com/sarapudheen-ops/puthus/actions/runs/44675858188),
[push Android 44675858091](https://github.com/sarapudheen-ops/puthus/actions/runs/44675858091) and
[PR Android 44675863827](https://github.com/sarapudheen-ops/puthus/actions/runs/44675863827): **556 JVM tests,
zero failures/errors/skips in 67 classes**, Gradle assemble/lint and exact APK checks, plus **2 actual-APK
API-24 tests PASS in 1.076 seconds**. Its native check **136088714661** passes the original archive/full
manifest and fast-KWP reference. Those runs precede the separate linked-lifecycle domain above;
expanded linked-lifecycle CI is recorded separately when available. The exact original `d85c290` log
exception could not be retrieved from the signed log host,
so iteration order is not asserted to be its independently confirmed cause. Failed gates now retain
raw evidence and publish explicit failure annotations/reports. **The API-24 nonempty executable Toyota
system acceptance criterion remains unmet**, despite delivery/UI smoke tests passing.

**Still incomplete:** complete transport/init/session/timing mapping, source fault-query conditions/
selection and multi-query workflows, general EXPRESS/control flow/signed/string helpers, ECU-info,
coding, actuator and special-function conversion. Exact parsing and native equivalence do not fill
these gaps. **Executable recovered definitions remain 0**; no physical test was performed.

### Current live conversion work — not counted as executable

The native A801 reply origin and separate support/discovery pipeline have now been traced; the runtime
has ordered same-ECU prerequisite reads and link-epoch invalidation, with bounded atomic support-table
validation. Sensor-nonzero admission is not used. See [native evidence, schema and remaining blockers](toyota-live-prerequisites.md).
The bounded direct-21 numeric translator emits **disabled review candidates only** and requires exact
support bindings, command/discovery tables and viewer exclusions. It is not integrated as an executable
pack producer. Numeric/byte-join candidates, runtime tests and source verification do not increase the
converted counts above. [Two exact numeric forms now have bounded semantic evidence](toyota-numeric-review.md),
including all unsigned-byte inputs and native numeric formatting; this applies to 30 rows within the
unchanged 353 disabled numeric candidates in one representative function/ECU review. Per-ECU
transport/init still blocks all of them, and other CALC classes remain unreviewed.
Fault/freeze-frame, test, coding and special-function translations remain unfinished.

The migration from full offline source assets to the small pinned index is documented in
[on-demand delivery and reproduction](toyota-vehicle-packs.md). This report must be regenerated
when actual executable conversion is implemented; zero/unknown counts must not be relabeled as
completed coverage. No physical vehicle/adapter test has been performed.

## Source DTC caller and explicit session UI (current work, not executable acceptance)

The current converter still emits **zero executable definitions**. The full decoded
input run checks 1,410 BIN arrays (114,797,280 bytes), retains 287,030 source system
records, and verifies a 378,283-object graph. Its 3,767-byte index and 366,409,541-byte
container are delivery/accounting results, not working ECU coverage.

`toyota_runtime_compile.py compile` now also emits a separately named, non-production
source-read review for the exact `SYSLIST_EU.BIN@0009d8f2` / function 61 / selector
`0x11` / `EU/Ecu_Data_0081.BIN` join. It contains 30 literal-reviewed numeric live
reads with exact source units, windows and A801/2100 applicability, 1,414 exact-key
fault labels, four native ordered fault queries and one dispatch-14 calibration-ID
read. These are **not** an enabled `vehicle-system.data`: the adapter timing/pin API
mapping and full native retry/fallback lifecycle remain separate missing gates.
The generated review explicitly reports `productionExecutableDefinitions: 0` and
985 source live rows not compiled by this closed numeric class. No production
coverage count includes these sidecars.

The prepare action and inspection workflow compile and verify these source
sidecars. Gradle unit tests receive their exact paths and fail when the expected
generated files are missing, rather than silently skipping the actual-source
cases. Synthetic adapter simulations consume the source request/formula/unit/
applicability and fault caller objects. Their synthetic link, replies and ID do
not establish the mandatory target's MIC3313 timing or physical behavior.

The ECU detail screen now offers an **Expert-only, operation-specific** `10 5F`
consequences dialog. Ordinary Refresh never changes the diagnostic session. The
non-persisted dialog intent binds the displayed full ECU definition, Session
instance, routing generation and a monotonic 60-second expiry. It is consumed
only after acquiring the bus; reset, reconnection, definition change, dismissal,
Expert disable and expiry cannot revive it. A fresh selected-ECU identity recheck
then precedes a separate single-use, initialized-epoch-bound `105F` capability.
The source-read lease cannot clear or consume a WriteGrant, including through
nested active-operation calls. Coding, active tests and clears still require the
separate fresh same-current-ECU guard and reviewed unique ECU serial. Calibration
IDs remain nonunique and cannot unlock writes. Exact bounded exchanges and the
outcome are appended to the local diagnostic-session audit even on cancellation;
no generic restoration command is invented.

Saved module readouts now preserve source request/name/display text, unsigned
32-bit exact fault keys and Toyota status meaning/state without relabeling them
as UDS bits. Older records retain legacy interpretation; unknown explicit future
status schemas display raw metadata. Saved/imported results grant no authority.

Latest local commands/results:

- `PYTHONPATH=.local/python:tools/thinkdiag python -m unittest discover -s tools/thinkdiag -p 'test_*.py' -q`
  **226 tests PASS** (0.359 s on the final pre-commit run).
- Complete decoded source-read compilation and
  `python tools/thinkdiag/toyota_runtime_compile.py verify .local/toyota-complete/vehicle-packs`:
  **30 live reads / 1,414 labels / four source queries / one ID / zero production definitions**.
- Standalone Kotlin 2.2.21 / JDK 17 harness: **102 scenario groups PASS**, including
  actual generated definitions, queued-intent expiry/reset/cancellation, source
  lease non-escalation and saved metadata. This is not Gradle/Compose/Android
  acceptance; the project uses its own Kotlin/Android toolchain in CI.
- Protected `ReadOnlyPolicyTest`, `WritePolicyTest` and `ClearConfirmationTest`
  remain unchanged. Android 24 still tests index/range/cache delivery only, not
  nonempty executable Toyota live/DTC screens. No physical validation is claimed.


## Extended native lifecycle and artifact regressions (still not enabled coverage)

The complete decoded-input seven-library comparison (all **1,410** manifest/hash-checked
BIN arrays) passed in **363.546 s**, including all the new negative-entry domains below
and the existing reviewed domains. This local run did not read `TOYOTA.zip`; it must not
be called original-ZIP verification. Inspection CI independently loads the original archive
and verifies its owner-attested SHA.

New bounded source control-flow ports and independent native comparisons add:

- **2,272** fast-entry vectors over all **284** transport-1/5 source entries. They preserve
  EndCombination's signed-16 result, the alternate Toyota sender on negative combined
  returns only, the second pulse attempt and 500-microsecond native delay. A negative ECU
  service produces `FFFE`, not the `FFFF` that selects ISO fallback. All **17** source
  transport-5 entries select that same-entry fallback on exhausted adapter failures.
- **2,272** direct ISO-consumer vectors over those same source entry contexts. These test
  zero-low-word handshake success, two attempts per exact pin pair, unchanged address byte,
  exact initial-template lookup, and failure on a negative initial sender result. Direct
  ISO-consumer tests on transport-1 contexts are component vectors, **not** a claim that
  native transport 1 dispatches an ISO fallback. The high-level slow-init sender remains an
  explicit fake boundary; it does not establish a MIC3313 electrical implementation.
- **1,032** exact ECMECT kind-5/template-165 read-reentry vectors, including **576**
  exit/enter/restore/read-once sequences. These inspect context `+174d1 = FF` at Exit/Enter,
  restoration before the identical read, signed-negative/`7FFF` trigger conditions, and
  native transport 1/2/5 restriction. Native ignores lifecycle failure; the app must not
  treat that behavior as proof or replay `105F`. The later source-API Session integration
  below connects this callback under an exact source transport lease; no adapter mapping is implied.

Android's custom fast initializer now always requires **one complete selected-ECU C1
reply with its two keyword bytes**, even outside bring-up. Plain adapter `OK`, empty
native-style success, short/long/partial replies, wrong ECU, duplicate replies and mixed
positive/negative/error evidence do not initialize a link. The standalone Kotlin run
passed **102 scenario groups**, including the actual compiled read definitions and this
new rejection matrix. This is simulation, not a physical vehicle validation.

`mandatory-read-artifacts-v1.json` is a **compiler-output regression pin**, not a source
attestation or executable-system approval. The compile CLI recompiles the exact source
twice and compares canonical bytes. The source-read review now includes the previously
omitted `EU/Toyota_bin_1B.BIN` input hash, all four canonical decoded text dictionary hashes
(explicitly not encrypted GAG hashes), and the CALC evidence library hash. The generated
files are independently checked against the reviewed canonical hash and round-tripped.
`test-artifacts` runs **48** real-output negative vectors: corrupt/truncated/missing files,
noncanonical/duplicate JSON, changed function/full-selector/ECU/region, 10416-to-10400
substitution, pin/pulse changes, formula/unit/offset/applicability changes, changed labels,
partial queries, changed ID, forged counts/input hashes and path traversal. Both prepare
and inspection workflows run these tests, including on cache hits; no sidecar enters the APK.

Inspected evidence for the preceding `09e59bf` push (not an acceptance claim for this patch):

- [Inspection run 44708729866](https://github.com/sarapudheen-ops/puthus/actions/runs/44708729866): success.
- [Android run 44708730041](https://github.com/sarapudheen-ops/puthus/actions/runs/44708730041):
  assemble/lint/tests success; job `136194736850` reports **579 tests, 0 failures, 0 errors,
  0 skipped in 70 classes**. This includes actual Android/Compose compilation of the session UI.
- Android 24 job `136204801894` and evidence check `136205546508`: **2 tests PASS, 2.254 s**,
  actual APK installation, index/range/private-cache/offline delivery only. This is **not**
  manual-selection-to-executable-live/DTC acceptance. Disposable emulator CA augmentation
  remains documented; the APK has no TLS/hostname bypass or test CA.

**Remaining acceptance failures are unchanged:** zero enabled production systems; missing
native-to-MIC3313 timing/pin/line equivalence and production routing of retry/reentry/ISO;
mandatory nonempty live/DTC/ECU selection on Android 24; complete remaining live language,
identity/transport/workflow classes, coding/actuators/special functions and whole-source
coverage. PR14 is draft, with no merge authorization for this new work; source completeness
is not being blamed for these implementation gaps.


## Portable lifecycle and Session integration (scripted source API, not MIC3313 approval)

`SourceKwpLifecycle` now ports the bounded native API control flow and exact source argument
positions into Kotlin. `SourceKwpPlanCodec` reads a closed projection, snapshots it before
callbacks, and rejects changed classes, cross-region frame provenance, incomplete byte fields,
unknown keys, integer overflow and session/write smuggling. This is deliberately **not** an
adapter command or enabled vehicle-pack codec. Native configuration failures are hardened to
abort, and native zero/adapter ACK cannot replace one complete, checksum-valid, exact-ECU C1.

`Session.withSourceKwpTransport` holds the existing executor/operation lease across configuration,
entry, reads, function-specific keepalive and bounded noncancellable cleanup. It does not use
legacy KLineConfig default timing/pacing or generic AT/ST/VT initialization. It rejects profile
addressing overrides, scope switches, write/clear/coding/actuator escalation and later generic
K-line substitution for a source-used address. An I/O error/reset invalidates current-target and
epoch evidence; cleanup does not reconnect to send an exit. C1 is link evidence only, never a
reviewed ECU serial or write proof.

The actual workbench now invokes the source reentry callback when this exact source transport
lease exists: only an unambiguous missing read, exact function/transport/ECU, exit → same-entry
initialization with C1 → context restoration → one identical read. It never retries `105F`,
setup/discovery, malformed/mixed replies or a disconnected exchange. No-data plus a data frame
is explicitly incomplete, not a trigger for reconnect/retry. Context restoration runs on cancellation.

`source-kwp-contracts.json` is a **4,956,450-byte test-only artifact**, SHA-256
`b26df7cb4cc791e66363bfaac1cd9b09603e68ec83a4c1e1395624584c296004`. The generator's
**double regeneration PASS** checked all 1,410 recovered decoded input lengths/hashes and
matched the independently pinned native binding/fast/ISO domains: **284 entries, 2,272 fast
vectors, 2,272 ISO vectors, zero production executable definitions**. The local run did not
verify the original ZIP. Prepare/inspection workflows now compile it from the original archive
and verify the complete pinned artifact on cache hits too. Only the existing small index is staged
for the APK; this sidecar remains outside assets.

Local results after Session integration: **229 Python tests PASS** and **113 standalone Kotlin
scenario groups PASS** (real generated definitions, scripted API I/O and synthetic ECU replies).
The Kotlin scenarios include the actual 30-live/four-query/1,414-label/0904 definition artifacts,
all 284 lifecycle bindings, six keepalive variants, rejected configuration, invalid C1, cancellation,
exclusive bus ownership and actual workbench reentry failure/success. The production driver
is still unimplemented: these tests are not MIC3313/physical proof or enabled manual-selection
acceptance. Protected policy test files remain unchanged.

Observed CI failure: `c1129eb` Android run **44716990874** failed
`:app:compileDebugAndroidTestKotlin` on an invalid top-level `assertDoesNotExist` import;
that API is a node member in this Compose version. Its JVM result was **580 tests, zero
failures/errors/skips, 70 classes**. Commit `770ef89` removed the import; the new build was
not complete when this note was written. Inspection run **44715073185** succeeded for the
preceding `f2b96ed` baseline. None of these observations certifies this lifecycle patch.

**Still zero enabled production definitions and 1,055,750 unresolved records in the last
whole-source accounting.** Exact native → MIC3313 timing/pin/electrical mapping remains the
mandatory target's blocker. Android 24 must still install the actual app and perform explicit
manual Toyota selection into nonempty executable live/DTC definitions, followed by complete
source feature/vehicle coverage. Draft PR14 is not ready to merge.

## Supplemental timing, primary transaction and native result boundary

The independent `tools/thinkdiag/toyota_firmware_timing.py` CLI now executes the pinned supplied
abstract layer and a pinned supplemental public Mini Cortex-M firmware image. The Toyota archive
remains authoritative; this auxiliary image is **not identified as MIC3313 firmware**. Raw inputs
remain outside Git/APK. No host native loader, syscall, network or device is exposed; unknown MMIO
aborts. GPIO/clock/timer/UART configuration, byte I/O, delay completion and IRQ arrival are explicitly
synthetic leaves, not silently modeled electrical behavior.

The local CLI was run twice with byte-identical results: **8 interval / 3 UART exchange / 6 keepalive /
6 native KWP-result / 6 native PRIMARY-result vectors plus 1 unequal-pulse component probe**.
The canonical saved report is 31,466 bytes, SHA-256
`824e4cb8eb7cd56b5a900e0ef792e91f4256e79cac500acdbc0fa1eb22042e48`;
its comparison digest is `c8780bc9d1092560c6b4cb77cb0516c7bcaa80150b58fbf27f8b4f60dbcec646`.
The exact input pins, public provenance, bounded reproduction command and fake boundaries are
in the [tool guide](../tools/thinkdiag/README.md#supplemental-native-to-firmware-timing-and-result-experiment).

Important distinctions established by executed instructions:

- `OpenAbstractCommDll@0001e860..0001e884` resolves source cell `CommSubFunc_00000003`
  to the **`CommSubFunc0003`** export. That real primary serializer emits `6402`, and the
  firmware's real dispatcher/alias table maps it to the `6109` consumer. The queued source
  pulse+PRIMARY transaction is **`60070000196007ff00196402ff01058113f08105`**, emitted only
  at EndCombination. This is not the same claim as merely calling the separate KWP/6109 API.
- Native `(5,60,600)` encodes `6003003206003c`. The consumer uses 5,000-us TX gaps,
  a 60-ms repeated-request delay and 600 normal RX polls, not three guessed generic P2/P3 values.
  Literal 10416 reaches UART-settings RAM as **10416**, without 10400 normalization.
- Pulse 25/25 reaches two 25,000-us delay-arm leaves; the unequal 35/15 component reaches
  35,000/15,000 us with other settings deliberately fixed. Neither GPIO-bit trace proves OBD
  pin mapping, transceiver polarity or physical timing. Complete native configuration/lifecycle
  routing and MIC3313 equivalence remain unfinished.
- Real 6004 setup, callback and disable execute for all six source keepalive payloads.
  Third argument **3 is an RX-byte drain bound in this supplemental image**, not retries.
  With synthetic 5-ms IRQ ticks, first TX is tick **161 / 805 declared-clock ms**, then one byte
  per tick and three 1,000-tick drains. Firmware clock declarations are not an oscillator/clock-tree
  measurement. `ATSW28` must not be approved simply because its nominal period is 800 ms.
- The real native queue/EndCombination/result parser—not a fake internal ACK—maps a complete
  synthetic C1 to count **9**, records `010783f013c1e98fbf`; no RX gives firmware `ff02` and
  signed-low-word **−70**. Corrupt checksum, partial packet, wrong ECU and negative service also
  yield **positive copied-byte counts**. Positive native return alone cannot establish ECU proof.

`SourceKwpNativeResultCodec` ports only the bounded answer-record envelope. It rejects mismatched
counts, truncated records, overflow, ignored tails and data attached to a failed return. It preserves
raw packets for independent exact ECU/header/checksum/service/C1 verification. The app regressions
consume both six-case native domains and require that only complete selected-ECU C1 initializes the
lifecycle. Saved-result checking is not rerunning guest code; normal CI has no supplemental firmware
input. These changes do not add a physical adapter implementation or enable a production pack.

Refreshed local evidence: **238 Python tests PASS** (0.432 s), **115 standalone Kotlin scenario
groups PASS** (real generated artifacts, synthetic I/O), compile PASS, catalog-seed check PASS,
`git diff --check` PASS. The nine timing unit tests verify encodings/pins/saved results, while the
separate explicitly invoked CLI performed guest execution.

The preceding lifecycle head **`d02481d`** has now completed all three observed parent runs:
[inspection 44720562676](https://github.com/sarapudheen-ops/puthus/actions/runs/44720562676),
[push Android 44720562551](https://github.com/sarapudheen-ops/puthus/actions/runs/44720562551),
and [PR Android 44720576573](https://github.com/sarapudheen-ops/puthus/actions/runs/44720576573)
are **success**. Push build check `136235018008` reports **591 JVM tests / 0 failures / 0 errors /
0 skipped / 71 classes**. Android 24 check `136248033562` reports **5 tests PASS / 3.673 s**:
two real APK range/cache tests and three synthetic callback-only consent UI tests. The documented
disposable-emulator CA augmentation still applies. This proves neither untouched-2016 trust nor
manual selected-system executable diagnostics, and does not certify later local edits.

**Production remains 0 executable definitions / 1,055,750 unresolved records at last whole-source
accounting.** Mandatory enabled-system loading, native-to-MIC3313 mapping, selected-system Android
24 live/DTC/ECU acceptance and every-source executable feature coverage are still outstanding.
