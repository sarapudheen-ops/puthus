# Toyota conversion report

**PARTIAL — NOT runnable enhanced Toyota coverage.**

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
| Executable definitions | 0 |

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
| Toyota / Kijang Innova / TGN · 2009.07-2017.07 / MINI VAN · w/ VSC (Vehicle Stability Control) | `SYSLIST_EU.BIN@0009d8f2` ECMECT (Engine and ECT) | 0 / 1015 | 0 / unknown | 0 / unknown | 0 / unknown | ecu_entry_untranslated |
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
