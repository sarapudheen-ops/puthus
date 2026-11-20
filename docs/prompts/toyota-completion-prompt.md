# Agent brief — finish Toyota support in `bod3` from the verified vehicle files

> **Kickoff message (short).** If you only send one line to the agent, send:
> *"Read `docs/prompts/toyota-completion-prompt.md` on this branch and execute it end to end. Start
> with M0; before coding, reply with your understanding of the gap and the first three PRs."*
>
> **How to use this document.** Paste this whole file as the task brief to the coding agent that has
> write access to `sarapudheen-ops/puthus`. It is self-contained: it states the mission, the exact
> current state, the contract an enabled Toyota system must satisfy, the workstreams, the acceptance
> gates and the evidence rules. The agent must read the linked repository docs before changing code.
> Do not shorten it into "enable Toyota by parsing the files" — the hard part is semantic translation,
> and everything below exists to make that work verifiable rather than plausible.

---

## 1. Mission

The owner has a complete, owner-verified ThinkDiag Toyota/Lexus corpus (release asset `TOYOTA.zip`,
71,755,650 bytes, SHA-256 `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`).
The Android app already has the delivery system, the selection UI and the guarded diagnostic engines.
What it does **not** have is a single executable recovered Toyota definition: the converter currently
reports **0 executable definitions**, so every recovered Toyota system is disabled.

Your job is to convert that verified source into **first-class app features**, not a browsable
reference library:

- a driver picks Toyota → model → generation → variant from the source's own hierarchy,
- the system list shows real systems (engine, ABS/VSC, SRS, immobiliser, body, …) with real coverage,
- opening a system reads identification, live values, fault codes with exact source labels, and — only
  behind the existing Expert gates — recovers source-backed actuator tests and coding where the source
  semantics are exact,
- every emitted value carries the exact source record IDs that justify it,
- and the app's existing loaders, verifiers and safety engines accept the result without a single
  weakened check.

Success is not "the converter prints more numbers". Success is the app executing converted Toyota
definitions that the repo's own verifier recomputes and accepts, with truthful coverage counts.

---

## 2. Standing constraints (do not negotiate these away)

1. **Repository and branch.** Work only in `sarapudheen-ops/puthus` on the current `arena/...` branch.
   Commit and push to that branch only. Open one PR per workstream against `main`. Do not merge your
   own PRs; the owner merges.
2. **The owner develops on a low-spec PC.** Never require a local Android SDK, Gradle build, emulator
   or lint run. All APK builds, lint, JVM tests, Android 24 device tests, conversion and native
   comparison run in GitHub Actions. Locally you may edit text and run the small Python unit tests.
3. **No large artefacts in Git.** Raw archives, decoded corpora, containers, ledgers and reports stay
   in `.local/` (ignored), Actions artifacts and release assets. Nothing above a few MiB may be
   committed. Never commit `assets/`, `TOYOTA.zip`, `.bin` containers or generated JSON packs.
4. **Toyota/Lexus only.** Do not change other brands, the generic OBD paths, the VW group data packs
   or the shared catalog behaviour except where a Toyota feature genuinely requires an additive,
   backward-compatible schema change.
5. **Read-only by default.** Do not widen the read-only whitelist, do not add a write service, do not
   auto-clear, do not invent security algorithms. Actuator and coding output must flow through the
   existing `WriteGate`/confirmation/backup/rollback engines and stay Expert-gated.
6. **No vendor code on Android.** No EXPRESS interpreter, no vendor ELF, no armeabi blob, no native
   library, no downloaded script. The app executes only plain typed definitions.
7. **Source verification is settled.** The owner has verified the archive; the repo's attestation
   (`tools/thinkdiag/verification/toyota-verified.json`) is an integrity gate, not a doubt to
   re-litigate. Do not spend turns re-checking provenance. Do not weaken the attestation either: it
   must keep failing on a different archive.
8. **Do not fake completion.** The verifier recomputes counts from the actual objects and the app
   rejects enabled systems whose counts disagree with their definitions. Cosmetic counter changes,
   disabled definitions counted as converted, or deleted/relaxed tests are grounds for rejection of
   the whole PR.
9. **Every claim has a source record ID and a test.** A converted definition without
   `sourceRecords`, or a feature without a test, is not converted.
10. **Never claim physical validation.** CI proves format, joins and control flow. Only a bench run on
    a real car proves a link. Keep that distinction in code, docs and PR text.

---

## 3. Where everything lives

| Area | Path / resource |
|---|---|
| App (Kotlin, Compose, Material 3) | `app/src/main/java/com/bod3/app/` |
| Pack delivery, index, range loader | `app/src/main/java/com/bod3/app/vehiclepack/` |
| Selected-vehicle UI | `app/src/main/java/com/bod3/app/ui/screens/ToyotaVehiclePacksScreen.kt` |
| Catalog/data/ECU models | `catalog/CatalogModels.kt`, `datapack/Models.kt`, `diagnostics/ReadDefinitions.kt` |
| K-line/CAN link model & adapter commands | `obd/kwp/DiagnosticLink.kt`, `obd/kwp/KLineAdapter.kt`, `obd/protocol/ObdProtocol.kt` |
| Converter and semantic ports | `tools/thinkdiag/` (see `tools/thinkdiag/README.md`) |
| Conversion pipeline action | `.github/actions/prepare-vehicle-packs/action.yml` |
| APK CI (build, lint, JVM, Android 24) | `.github/workflows/android.yml` |
| Source inspection / conversion CI | `.github/workflows/thinkdiag-data.yml` |
| Current limits and evidence | `docs/toyota-vehicle-packs.md`, `docs/conversion-report.md`, `docs/toyota-live-prerequisites.md`, `docs/toyota-numeric-review.md`, `docs/ecu-read-definitions.md`, `docs/ecu-bring-up.md` |
| Source archive | release `assets-v1` → `TOYOTA.zip` |
| Published pack containers | pre-release `toyota-vehicle-packs-v1` |
| Generated index (not in Git) | `.local/vehicle-pack-assets/toyota-vehicle-packs/index.json` |

Baseline that must stay green: `python3 -m unittest discover -s tools/thinkdiag -p 'test_*.py'`
currently runs **238 tests, all passing**; the latest documented CI run reports **500 JVM tests,
zero failures**; both Android 24 device tests are documented passing on the latest evidence run —
re-confirm them on your own revision rather than assuming it.

---

## 4. What is already finished — do not rebuild it

**Source recovery and accounting (done).**

- The archive is decoded into **1,410 checksum-verified payloads** with a complete manifest.
- English text recovery (`TEXT_EN`, `DSTREAM_EN`, `EXPRESS`, `TCODE_EN`) is done, including NUL-delimited
  TCODE pairs and 4,502 non-UTF-8 records.
- Reader-backed structure: `MENU.BIN` (24,653 vehicle paths + 9 non-vehicle tasks), three regional
  `SYSLIST` files (**287,030 systems**: EU 157,500 / NA 74,985 / JP 54,545), `SYSTEMENTER.BIN`
  (**1,308 entries in 1,276 lists**), three `FUNCDATA` files, **1,307 ECU files**, the shared command
  pool, frame table and function/frame bindings.
- Exact joins: 286,149 of 287,030 systems have an exact entry join (EU 152 / NA 729 / JP 0 missing).
- The conversion ledger accounts for **1,055,750 unresolved source records** individually.
- DTC text: **180,316 mapping rows** collapse to **179,770 distinct per-function keys**, joined with
  **zero missing TCODE keys**; 1,186 regional fault blocks / 1,184 clear blocks; 27,035 exact TCODE
  pairs.
- Live-data review: the bounded direct-21 class for EU/function 61/ECU 0081 accounts for all **1,015
  rows** as **353 disabled candidates** and **662 rejected**, with **30** numerically reviewed rows and
  **two** exact CALC equivalences (`y=x-40;`, `y=(0x100*x1+x2)/4;`).
- Native differential: bounded AArch64 emulation of five pinned supplier libraries matches 1,308
  FILE entries, 8,775 frame records, 34,671 template lookups, 284 fast-KWP entries with 568 traces and
  17,892 packets, 2,016 short-packet vectors, 70,144 DTC state/mask and 2,112 assembly vectors, 49,152
  P5 vectors, 27,035 TCODE pairs, 196,608 byte-merge calls and 65,792 CALC numeric vectors. Kotlin
  matches the state/assembly/label/merge digests.
- The passive Toyota/Lexus DTC text pack is bundled and reproduces byte-for-byte.

**The app (done).**

- Source-pinned index (≤256 KiB) in the APK; content-addressed container on a release; strict
  HTTPS/HTTP-206 range loader with rehash, atomic install, 128 MiB private LRU cache, offline reopen.
- Manual-only selection (Make → Model → Generation → Variant), no VIN selection, no automatic choice.
- Selected-system scope is closed: one system's data cannot be filled by another system, a legacy
  template or a global list.
- Per-ECU K-line link model: init type, init address, baud (1,200–115,200), P1/P2/P2*, P3/P3-max, P4,
  fast-init low/high pulses, init/data parity, optional W0–W5 slow-init timings, exact keepalive.
- CAN/ISO-TP addressing, 11/29-bit, custom bit rates, ISO 15765-2 extended addressing, flow control.
- Exact Toyota P3/P4 fault layout port, P5 layout port, six source keepalive variants, physical KWP
  `04`→`44` clear (never `14`, never broadcast), source-faithful byte-merge and label logic.
- Live prerequisites (`supportChecks`) with per-ECU address pairs, A801 record IDs, cached replies per
  polling run, invalidation on session/route change.
- Guarded actuator (`2F`/`31`) and coding (`2E`, long coding/adaptation/DID) engines with Expert gate,
  single-use confirmation, backups, read-back verification, rollback and audit log.
- Read-only ECU probe/bring-up with evidence, verified-setup store and data-pack transfer.
- Every log/report shareable; the app never contacts a real ECU in CI.

**Therefore:** the missing work is *semantic translation and app wiring*, not extraction, not another
decoder, not a database viewer.

---

## 5. The actual gap

The converter (`tools/thinkdiag/convert_vehicle_packs.py`) stops at:

- `ecu_entry_untranslated` — entry address/header, baud, init/session, selector, timing are framed but
  not mapped to the app's link model.
- `service_reply_untranslated` — source service/identifier/reply-mask joins are not turned into
  `DiagnosticRead` requests/prefixes.
- `formula_untranslated` — signal byte/bit positions, signedness, scaling, unit and applicability are
  not turned into `CustomPid` formulas/units.
- `record_schema_untranslated` — ECU typed-table rows are framed but not classified into
  identification, faults, live data, actuators, coding or customisation.
- `hierarchy_untranslated` / `missing_system_key` / `missing_system_entry` / `missing_function` /
  `missing_ecu_file` — remaining applicability and join gaps (including 152 EU + 729 NA systems
  without an exact entry).
- `flash_unsupported` — by policy.

Each system therefore ships as `"data": {}` with `enabled: false`, and `counts` are
`{converted: 0, unresolved: null-or-N}`. The whole job is to replace that with real definitions.

---

## 6. The contract an enabled Toyota system must satisfy

This is enforced by `VehiclePackCatalog.system()` in
`app/src/main/java/com/bod3/app/vehiclepack/VehiclePackCatalog.kt` and by
`tools/thinkdiag/verify_vehicle_bundle.py`. Do not weaken either; satisfy them.

For a system object with `"kind": "vehicle-system"`:

- `enabled: true` ⇒ `reasons` is empty and the vehicle's `hierarchyResolved` is true.
- `data.ecus` contains exactly **one** ECU, and that ECU has a non-null `link`.
- `data.pids` is non-empty; every PID `target` equals that ECU's target.
- `data.dtcs`, `data.dtcGroups`, `data.moduleNames`, `data.security`, `data.connection` are empty and
  `data.exclude` is the default — a system is closed to one ECU, not a catalog dump.
- `ecu.diagnostics.identification` is non-empty; `diagnostics.faults` is present; the layout is
  `ToyotaFaultLayout` or `ToyotaP5FaultLayout` with non-empty `labels`.
- Every identification read, fault read, support check, layout, clear and workflow carries non-empty
  `sourceRecords`; the fault read's request belongs to the same source workflow.
- `counts` equals reality: `pids.converted == data.pids.size`, `tests.converted == data.actuators.size`,
  `coding.converted == data.coding.size`, `ecuInfo.converted == identification.size`,
  `dtcs.converted == labels.size`, and `specialFunctions.converted == 0` (the app has no special-function
  executor; write-only functions are never converted as features). Do not smuggle a source "special
  function" into the actuator list; if the owner wants them, that is a separate gated design with its
  own confirmation/cleanup rules, not a conversion trick.
- Every actuator/coding recipe names the same ECU target and has provenance; `verified` is only set by
  this pinned pack path, never by an import.
- The verifier recomputes `summary.executableDefinitions`, `enabledSystems`, `unresolvedSourceRecords`,
  walks the hash tree, checks the ledger SHA-256 against the pinned index, and rejects
  `enabled:false` items that contain executable definitions. `--require-executable` must pass.
- Hard budgets: index ≤ 256 KiB, object ≤ 8 MiB JSON / 4 MiB compressed, container ≤ 512 MiB, app cache
  ≤ 128 MiB. The last published container was already **466,298,841 bytes**, so plan the budget before
  adding data for 287,030 systems.

**Illustrative shape only.** Field names below are the app's real codec keys. Every `⟨…⟩` must be
derived and evidenced in W1–W6; nothing here is a value to copy. A partial system keeps
`unresolved > 0` and a truthful `coverage`; a complete one has `unresolved == 0`.

```jsonc
{
  "kind": "vehicle-system",
  "id": "SYSLIST_EU.BIN@0009d8f2",
  "name": "ECMECT (Engine and ECT)",
  "sourceRecords": ["SYSLIST_EU.BIN@0009d8f2", "FUNCDATA_EU.BIN@000005e9",
                    "SYSTEMENTER.BIN@00002f63"],
  "sourceVerified": true,
  "enabled": true,
  "reasons": [],
  "counts": {
    "pids":   {"converted": 2, "notApplicable": 0, "unresolved": 1013},
    "dtcs":   {"converted": 1414, "notApplicable": 0, "unresolved": 0}, // == layout.labels.size
    "tests":  {"converted": 0, "notApplicable": 0, "unresolved": 0},
    "coding": {"converted": 0, "notApplicable": 0, "unresolved": 0},
    "ecuInfo":{"converted": 1, "notApplicable": 0, "unresolved": 0},
    "specialFunctions": {"converted": 0, "notApplicable": 0, "unresolved": 0}
  },
  // Coverage counts SOURCE RECORDS (see section 7), not features; required in real objects.
  "coverage": {"mode": "partial", "basis": "source-records"},
  "data": {
    "ecus": [{
      "name": "Engine and ECT",
      "address": "⟨source address/short code⟩",
      "requestId": "⟨3- or 8-hex request ID, derived⟩",
      "responseId": "⟨3- or 8-hex response ID, derived⟩",
      "protocol": "KWP",
      "link": {
        "protocol": "ISO_14230_4_KWP_FAST",
        "dialect": "KWP2000",
        "kLine": {"init": "FAST", "initAddress": 16, "baud": 10416,
                  "p1MaxMs": "⟨derived⟩", "p2MaxMs": "⟨derived⟩", "p2StarMs": "⟨derived⟩",
                  "p3MinMs": "⟨derived⟩", "p3MaxMs": "⟨derived⟩", "p4Ms": "⟨derived⟩",
                  "fastLowMs": 25, "fastHighMs": 25,
                  "initParity": "⟨derived⟩", "dataParity": "⟨derived⟩"}
      },
      "sourceRecords": ["SYSLIST_EU.BIN@0009d8f2", "SYSTEMENTER.BIN@00002f63"],
      "diagnostics": {
        "identification": [{
          "read": {"id": "⟨id⟩", "name": "⟨source name⟩", "request": "⟨read⟩",
                   "responsePrefix": "⟨full positive prefix⟩", "dataOffset": 0,
                   "minBytes": 16, "maxBytes": 4096,
                   "sourceRecords": ["⟨…⟩"], "supportChecks": []},
          "encoding": "ASCII_RECORDS", "recordBytes": 16
        }],
        "faults": {
          "read": {"id": "⟨id⟩", "name": "Source current fault records",
                   "request": "13B0", "responsePrefix": "53", "dataOffset": 0,
                   "minBytes": "⟨layout bound⟩", "maxBytes": "⟨layout bound⟩",
                   "sourceRecords": ["⟨…⟩"], "supportChecks": []},
          "layout": {"countIndex": "⟨…⟩", "recordStart": "⟨…⟩", "recordBytes": "⟨…⟩",
                     "assemblyKind": "⟨…⟩", "countIsPresence": "⟨…⟩", "modeLowBits": 1,
                     "queryKind": "⟨…⟩", "availabilityMask": "⟨…⟩",
                     "labels": [{"nativeCode": "⟨8 hex⟩", "code": "P0128",
                                 "description": "⟨exact source text⟩",
                                 "sourceRecords": ["⟨…⟩"]}],
                     "sourceRecords": ["⟨…⟩"]},
          "clear": {"request": "04", "responsePrefix": "44", "sourceRecords": ["⟨…⟩"]},
          "workflow": {"kind": "TOYOTA_P3P4", "functionKey": 61, "nativeMode": 11,
                       "nativeTransport": 1, "sourceRecords": ["⟨…⟩"],
                       "initial": [], "details": [], "queries": []}
        }
      }
    }],
    "pids": [{
      "id": "⟨stable id⟩", "name": "Engine coolant temperature", "request": "2105",
      "formula": "(A-40)", "unit": "°C", "min": -40, "max": 215, "decimals": 0,
      "header": "⟨derived⟩", "responseHeader": "⟨derived⟩", "dataOffset": 0,
      "sourceRecords": ["FUNCDATA_EU.BIN@00051245", "⟨…⟩"], "supportChecks": []
    }],
    "actuators": [], "coding": []
  }
}
```

If you change the object schema, bump `converterVersion`/format in `vehicle_bundle.py`,
`verify_vehicle_bundle.py` and the Kotlin index/catalog readers together, and keep old objects failing
closed rather than being reinterpreted.

---

## 7. Counting, completeness and honest partial support

Today `counts[kind] = {converted, unresolved}` and a system may only be enabled when every kind has
`unresolved == 0`, which is unreachable for a 1,015-row engine ECU in the first release. Resolve this
explicitly, not by lying.

**Required model.**

- Every source record gets exactly one disposition:
  - `converted` — an executable definition exists and is counted;
  - `notApplicable` — it is provably not a read feature the app may execute: a FLASH/download system, a
    write-only service outside the whitelist, a marker/padding/unclaimed span, or a non-diagnostic menu
    task. Duplicate references are de-duplicated by source identity, not counted as extra records.
  - `unresolved` — semantics still unknown.
- `counts[kind] = {converted, notApplicable, unresolved}`. `unknown` is still not zero.
- Add a per-system `coverage` object: `{"mode": "complete" | "partial", "converted": N,
  "notApplicable": M, "unresolved": K, "basis": "source-records"}`.
- Verifier: recompute all three; accept `enabled: true` only when every emitted definition is exact and
  provenance-complete, `converted > 0`, and either `mode == "complete"` with `unresolved == 0` or
  `mode == "partial"` with truthful non-zero `unresolved`. Reject any `notApplicable` that lacks one of
  the allow-listed reasons and a source ID.
- App: show the coverage badge ("37 live values · 978 not yet converted") on the systems and feature
  screens; never present a partial system as complete support.
- `summary.complete` stays false until every system is `mode == "complete"`. Add
  `summary.coverage = {systems, complete, partial, disabled, records: {total, converted, notApplicable,
  unresolved}}` and publish it in the conversion report and a dedicated CI check
  ("Toyota conversion coverage") that shows the numbers over time.
- **Anti-abuse gate:** `notApplicable` is limited to the allow-list above; a test must prove that an
  unknown transport, an unknown row schema, or a missing join is *not* classifiable as
  `notApplicable`. Never move a row to `notApplicable` because converting it is hard.

**Progressive enablement is the recommended default** (it is truthful and it gets real features into
the app early), but the final definition of done in §13 requires `unresolved == 0` everywhere.

---

## 8. Source-of-truth map

| Source | Holds | Existing code |
|---|---|---|
| `MENU.BIN` | 24,653 paths, titles, tasks; 9 non-vehicle tasks | `convert_menu`, `toyota_hierarchy.py` |
| `SYSLIST_EU/NA/JP.BIN` | 287,030 systems: function key, name/description IDs, ECU file ID, transport label, selector, caller mode | `convert_systems`, `toyota_structure.py` |
| `SYSTEMENTER.BIN` | 1,308 entries: descriptor (low nibble = class), transport code, flags, baud, per-class runtime fields, pin pairs, timing words, init/keepalive/exit bindings | `toyota_entries.py` |
| `FUNCDATA_*.BIN` | per function: index row, 30-byte live rows, command rows, fault/clear queries, detail rows, mappings, EXPRESS key at +26 | `toyota_functions.py`, `toyota_live.py`, `toyota_dtc_workflow.py` |
| `Toyota_bin_00.BIN` | command pool (6-byte descriptors, 10,000 bias) | `toyota_structure.py` |
| `Toyota_bin_11.BIN` | 8,775 frame records (request/mask/reply roles) | `toyota_frames.py` |
| `Toyota_bin_12.BIN` | function/frame bindings, EU tag `0x0112`, NA/JP tag `0x0012`, 16-byte rows | `toyota_frames.py` |
| `ECUPROGRAM.BIN` | program dispatch kind, caller modes, calibration `14` records | `toyota_ecuprogram.py` |
| `*_Ecu_Data_*.BIN` | 1,307 ECU typed tables: fault blocks, live-support tables, DS/ACT tests, coding/customisation tables | `convert_ecus` (currently unframed) |
| `TEXT_EN.GAG` | names, units, status text | `extract_english.py` |
| `DSTREAM_EN.GAG` | data-stream display names | `extract_english.py`, `toyota_live.py` |
| `EXPRESS.GAG` | formulas (several syntax classes) | `toyota_live.py`, `toyota_calc.py` |
| `TCODE_EN.GAG` | DTC labels keyed by exact 32-bit TCODE | `toyota_dtc_labels.py` |
| `Viewer_bin_*.BIN` | per-function viewer exclusions | `toyota_live.py` |
| Pinned libraries (FILE, COMM, FDATA, DTCF, CALC, SYSP) | native semantics and traces | `native_replay.py`, `toyota_native_*.py`, oracle |

Use the native oracle as the differential referee: implement the port, execute the same consumer over
the whole decoded corpus with bounded fakes, and require byte-identical results before any definition
can be converted. Extend the fakes only in ways that keep them explicit and pinned.

---

## 9. Workstreams

Each workstream is one PR (or a small series). Every PR must state its coverage delta and include the
evidence described.

### W1 — Entry, link and transport equivalence

**Goal:** turn each `SYSTEMENTER` entry into the exact app link (`DiagnosticLink` + `KLineConfig` for
K-line; protocol/bit rate/request+response IDs for CAN/KWP-over-CAN), or a terminal reason.

Tasks:

1. Add `tools/thinkdiag/toyota_link_map.py` producing, per entry ID:
   `{transportCode, protocol, dialect, kLine{init, initAddress, baud, p1..p4, fastLow, fastHigh,
   initParity, dataParity, slowTimings}, requestHeader, responseHeader, baudKbps, keepalive, exit,
   retryPolicy, sourceRecords, evidence, blockers}`.
2. Establish field semantics from native traces, not names: the per-class runtime fields
   (`runtime107b0`, `runtime107e4`, `runtime107f0`, `runtime107d0`, `runtime107b4`, `runtime107c4`,
   `runtime107b8`, `runtime107bc`, `runtime107d4`, `runtime107f2`, `pinPairs`, `extraAddressFlag`) must
   each be mapped or explicitly refused. The current names describe offsets, not meaning.
3. Reproduce the closed 284-entry fast-KWP class first: transport 1/5, exact baud (including the
   native 9,600 exception and the binary32 conversion), explicit pin pairs, both pulse pairs,
   `SetEcuCommTimeInterval` arguments kept in their source positions, six keepalive variants and three
   exit variants. Never substitute `3E` for a source keepalive, never relabel the 5/60/600 arguments as
   P1/P2/P3 without evidence.
4. Produce a **MIC3313/vLinker equivalence table** mapping each app primitive
   (`ATPC`, `ATSPx`, `ATH1`, `ATSW00`, `ATKW0`, `ATST`, `ATAT0`, `STPBR`, `STIAT0`, `STIP1X`, `STPTO`,
   `STPTRQ`, `STIP4`, `STIIAP`, `STIP`, `STITW`, `ATIIA`, `ATSH`, `STIFI`, `ATSI`) to the source
   semantics and back. If the adapter has no equivalent for a source field (for example pin/line
   selection), say so explicitly, keep the default only if the source default is proven, and record a
   blocker. Add a new primitive only with adapter documentation, a negative test for `?`, and a
   fail-closed fallback.
5. Keep exact header rules: K-line three-byte physical headers must be a complementary pair; KWP over
   CAN stays KWP2000; UDS is not a KWP substitute; 0x51 is a different native alternative to 0x11.
6. Handle FLASH systems as `notApplicable` (policy), never as read systems.
7. Add a bench harness path: an Expert-mode bring-up that emits exactly this sequence for the selected
   ECU, captures adapter replies, shows the first failing step, and exports evidence (adapter info,
   commands, replies, timing, app commit, pack hash). CI verifies the format only.

**Exit:** the 284-entry class maps with zero guessed fields, all native trace comparisons pass, and the
Kotlin link objects validate against `KLineConfig.problems()`/`DiagnosticLink.problems()`. A bench run
on the pilot ECU (§10, M1) able to initialize and answer `21 05`/`21 0C` is owner evidence, attached to
the PR when available — it is a physical gate, not a CI gate, and must not be claimed without it.

### W2 — Identification and ECU information

**Goal:** convert each system's identification reads into `EcuReadDefinitions.identification`.

Tasks:

- Compile the source identification paths (`1A xx`, `21 xx`, `22 xxxx`, calibration `14`, ASCII
  records, byte enums) into `DiagnosticRead` with exact request, full positive prefix, data window and
  `supportChecks`.
- Keep the mandatory example exact: 16-byte `ASCII_RECORDS`, not a guessed UDS `F1xx` DID.
- Use `byteValues`/`defaultText` only where the source has an explicit enum and default; no invented
  text.
- Preserve `sourceRecords` for every read and enum.
- `counts.ecuInfo.converted` must equal `identification.size`.

**Exit:** every enabled system has at least one exact identification read; labels/unit text comes from
the pinned English records; tests cover short, wrong-echo and enum-out-of-range replies.

### W3 — Live data

**Goal:** convert the 30-byte live rows and their support/command joins into `CustomPid` definitions.

Tasks:

- Implement the general live-row translator on top of the existing bounded one:
  - request/local ID from the frame + command pool,
  - data offset after the exact echo (native position 1 = app `dataOffset` 0),
  - formula class (arithmetic, signed helpers, bitfields, masks, unit conversion), each class with an
    independent test; only reviewed classes may convert,
  - unit, decimals, min/max from source or a documented display rule,
  - applicability: monitor bit window in the A801 support record, discovery-group membership, command
    info row, declared polling length, dynamic LSB override, viewer exclusions, function-specific
    exclusions.
- Emit `supportChecks` (max 32, same ECU) with exact A801 record IDs where the source requires them.
- Skip duplicate rows by exact source identity, not by label; keep the first source order.
- Extend the app only if needed for a real class the source contains (for example enum/text display):
  additive, versioned, tested, and shown in the UI. Do not silently drop such rows and do not pretend a
  numeric proxy is the same feature.
- Enforce a polling budget: no more than the existing per-cycle request bound; group requests to the
  same ECU; never poll another ECU.

**Exit:** the pilot ECU's 1,015 rows are each `converted`, `notApplicable` with a reason, or explicitly
`unresolved` with a blocker (target: zero `unresolved` by M2); `2105` and `210C` match the reviewed
formulas and source IDs exactly; live graphs/recording work; malformed/short/duplicate replies fail
closed.

### W4 — Fault codes, states and clearing

**Goal:** finish the fault feature breadth beyond the mandatory EU/function-61 P3/P4 case.

Tasks:

- P3/P4 caller modes 7 and 9 with their extra overrides; conditional detail suffixes; the P5 class with
  its exact shared header and mask provenance; auxiliary P5 freeze/mask tables only when ported.
- States: keep the source state IDs and assembly kinds; no UDS-bit assumption, no global label
  fallback, no suffix matching for printed codes.
- Labels: per-function exact 32-bit keys; alternate descriptions preserved; source fallback only when
  supplied with its own provenance.
- Clear: KWP `04`→`44` physical, single confirmation, same-ECU guard; UDS `14` group only where the
  source genuinely uses it; never broadcast, never retry.
- Freeze frame / snapshot reads: convert only where the app can display them without inventing a
  service; otherwise `notApplicable` with the reason.

**Exit:** `dtcs.converted == labels.size`, every enabled system reads its own codes, clears are
exact and audited, and the app's fault screens show source labels and states.

### W5 — Actuator tests

**Goal:** convert source DS/ACT tables into `ActuatorRecipe`s that the existing runner can execute.

Tasks:

- Map source start/stop/status routines to `2F <DID> 01|02|03` / `2F <DID> 00` or `31 01|02 <id>`;
  reject anything else.
- Preconditions (voltage, ignition, engine, stationary), time limit ≤120 s, at least one safety note,
  session 01/03, odd security level only when the source proves it; no key algorithm invention.
- Applicability from the exact system/part number/software version; no cross-vehicle reuse.
- Keep the run flow unchanged: Expert gate, single-use confirmation, live countdown, always-available
  STOP, automatic stop on silence/link loss/leave/cancel, `10 01` cleanup, audit log.
- Mark converted recipes `verified` only through the pinned pack path.

**Exit:** at least one real Toyota actuator runs in simulator tests end to end; refusals and aborts are
covered; the audit log contains every request/response.

### W6 — Coding and customisation

**Goal:** convert source coding/customisation tables into `CodingRecipe`s, or classify them.

Tasks:

- Only `long_coding`, `adaptation`, `did_value` via `22`/`2E`; never identification records
  `F180`–`F19F` (except `F198`/`F199`); single-byte masks; value maps/ranges that fit the field.
- Backups, re-read before write, single write without retry, read-back verification, rollback,
  `10 01` cleanup, audit log — unchanged.
- If a source customisation needs a service outside the whitelist (reset, write memory, download), mark
  it `notApplicable` with `write_outside_policy`; do not smuggle it through another service.
- Security access only from the source's exact level/seed-key routine, and only if the app can represent
  it; otherwise leave the feature disabled with an explicit blocker.

**Exit:** coding coverage is truthful, every write path is gated, and negative tests prove that
unverified imports still cannot write.

### W7 — Vehicle hierarchy and applicability

**Goal:** make selection exact and complete without inventing generations.

Tasks:

- Preserve all 24,653 paths, their exact source-node IDs, regions and communication menus; keep the
  four reviewed Innova paths' IDs and month boundaries.
- Normalise only what is validated; leave absent details absent; never infer chassis code, fuel, engine
  or transmission from a label.
- Keep the 9 non-vehicle tasks in the lossless tree, not as vehicles.
- For the 152 EU / 729 NA systems without an exact entry, keep `missing_system_entry`; attempt a
  reader-backed fix only, never a low-nibble or sibling-selector fallback. If the source genuinely lacks
  the join, report it as a source gap with the exact record IDs.
- Duplicate labels must not collapse nodes; IDs are the identity.
- No VIN-based selection, no profile-based selection, no fallback to a generic vehicle.

**Exit:** 24,653 variants navigate, every variant's systems belong to its exact source path (the loader
already enforces this), and the report explains every remaining hierarchy gap.

### W8 — App integration and UX

**Goal:** converted data is actually usable, honest and fast on a low-end phone.

Tasks:

- Systems screen: converted/not-applicable/unresolved counts, coverage badge, region and source path.
- System screen: identification, live values (graphs, units, min/max, recording), fault codes with
  source labels/states, freeze frame where converted, actuator and coding sections gated by Expert mode.
- Bring-up: keep the read-only probe, ranking, manual naming, evidence per vehicle instance, export/
  import of verified setups, and the explicit "evidence is not verification on another car" text.
- Unresolved items: keep the bounded, paginated inspection with reasons and source IDs; never present
  them as working features.
- Offline: selected objects reopen from the private cache; deleting the cache does not corrupt state;
  cancellation leaves no partial install.
- Performance: bounded memory, no full-corpus parse, no main-thread JSON, no ANR on Android 24 with a
  large system; cache LRU bounded.
- Accessibility and text: no truncation of long source names in a way that hides the variant; stable
  strings; no misleading "verified" badges.
- Tests: Compose UI tests for partial/complete/disabled systems, coverage badge, Expert gating, empty
  states, cancellation, offline reopen, and a synthetic end-to-end "select → open → read → clear" flow.

**Exit:** the APK passes the existing Android 24 device tests plus the new UI tests; the user can select
a converted Toyota, open a system and see live/DTC data without a crash or a misleading claim.

### W9 — Conversion pipeline, delivery and budget

**Goal:** CI produces, verifies and publishes the result within limits, every run.

Tasks:

- Keep the pipeline order: verify archive → decode → English → convert → runtime compile → lifecycle
  contracts → native oracle → verifier → stage index only.
- Cache key must include converter sources, schema, verification and the source manifest; a cache hit is
  re-verified, never trusted.
- Budget the container: the last published container was 466,298,841 bytes against a 512 MiB cap. Plan sharding by region or by
  manifest group, extend the index to a list of containers with per-container hashes, update the Kotlin
  loader to fetch from the owning container, and keep old containers immutable and addressable. Do not
  raise the cap silently.
- Keep publication non-clobbering; a new hash is a new asset; old APKs keep working.
- Split long jobs if the 30-minute/20-minute timeouts are at risk; use a dedicated conversion job with
  its own cache and a 6-hour ceiling, and keep the APK job short.
- Add a "Toyota conversion coverage" check/annotation with the §7 numbers and a stable trend line.
- Regenerate `docs/conversion-report.md` from the actual run; never hand-edit counts.
- PR description must link the run, artifacts and the diff of coverage.

**Exit:** a fresh clone + one push produces a verified container, a pinned index, a passing APK, and a
report whose numbers match the artifacts.

---

## 10. Milestones

**M0 — Baseline and observability (1 PR).** Add the coverage block, per-disposition reasons, the
coverage check and the conversion dashboard. No enabling. Confirm the 238 Python tests and the CI
baseline.

**M1 — Pilot ECU, real features (1–2 PRs).** Use the anchors that already have exact reviews:

| Item | Exact value |
|---|---|
| System | `SYSLIST_EU.BIN@0009d8f2` — ECMECT, function 61, selector `0x11`, caller mode 11 |
| ECU | `EU/Ecu_Data_0081.BIN` |
| Entry | `SYSTEMENTER.BIN@00002f63`, transport 1, flags `0x81`, 10,416 baud, pins `07 07`, timings `05 3c 00 19 19`, pulses 25/25, native interval (5, 60, 600) |
| Live | `2105` coolant `(A-40)` at `FUNCDATA_EU.BIN@00051245`; `210C` RPM `(((256*A)+B)/4)` at `FUNCDATA_EU.BIN@00051371` |
| Faults | primary `13B0` → `53`, mode low bits 1, the layout resolves a count of 1,414 records |
| Identification | calibration `14`, 16-byte ASCII records |
| Labels | exact 32-bit TCODE keys for this function |

Deliver an enabled pilot system with complete provenance, its remaining rows truthfully `partial`, the
coverage badge, and JVM/device tests. The demo that proves the approach: pick the Innova TGN path, open
**ECMECT**, see identification, live coolant/RPM values and the engine's fault codes with source labels.
This is the proof the whole approach works.

**M2 — Pilot completeness.** All 1,015 live rows and all fault workflows for that ECU are converted or
`notApplicable`; the system flips to `mode: complete`.

**M3 — The 284-entry fast-KWP class.** Link mapping, lifecycle, keepalives and exits for that whole
class; enable the systems whose rows are complete.

**M4 — Breadth by mechanism.** Extend to all 1,307 ECU files / 1,308 entries by source class
(transport classes, table schemas, service classes), never one ECU at a time by hand. Track the count of
distinct schemas remaining, not just systems.

**M5 — Actuators and coding.** Real Toyota tests/coding converted and gated; everything else
terminally classified.

**M6 — Delivery and UX polish.** Container sharding if needed, cache/offline/performance work, docs,
report, release notes, bench evidence from the owner's car attached to the PR.

**M7 — Completion sweep.** `unresolved == 0` for every system; every system `mode: complete` or
disabled with a source-gap reason; `--require-executable` passes; final coverage dashboard.

**Rules for the sequence.** Do not start M4 before M2 proves the mechanism end to end. Do not enable a
system whose only converted feature is an identification string. Do not skip the pilot.

---

## 11. Verification and evidence

Local (text only, optional; no Android SDK):

```sh
python3 -m venv .local/venv
.local/venv/bin/pip install -r tools/thinkdiag/requirements.txt
.local/venv/bin/python -m unittest discover -s tools/thinkdiag -p 'test_*.py'   # 238 tests, must stay green
python3 tools/generate_catalog_seed.py --check
```

In Actions (required for anything that touches the app or the packs):

```sh
gh workflow run thinkdiag-data.yml --ref <branch>          # inspect + convert + native oracle
gh run watch <run-id> --exit-status
gh run download <run-id> -n toyota-vehicle-packs -D .local/packs
gh workflow run android.yml --ref <branch>                 # APK, lint, JVM tests, Android 24 device tests
```

Acceptance commands (must pass on the produced artifacts):

```sh
python3 tools/thinkdiag/verify_vehicle_bundle.py --directory .local/toyota-vehicle-packs
python3 tools/thinkdiag/verify_vehicle_bundle.py --directory .local/toyota-vehicle-packs --require-executable
python3 tools/thinkdiag/toyota_native_oracle.py verify .local/toyota-vehicle-packs/native-oracle.json
python3 tools/thinkdiag/toyota_runtime_compile.py verify .local/toyota-vehicle-packs
python3 tools/thinkdiag/toyota_lifecycle_contracts.py verify .local/toyota-vehicle-packs
python3 tools/thinkdiag/check_pack_delivery.py --directory .local/toyota-vehicle-packs
```

Every PR must include:

1. the run links (inspect, APK, device) and the artifact names;
2. the coverage delta table (`converted` / `notApplicable` / `unresolved` per kind, systems enabled,
   systems complete/partial/disabled);
3. the tests added and what they would catch;
4. explicit notes on anything still unproven, especially bench/physical claims.

---

## 12. Non-negotiable honesty rules

- Do not count a row as converted unless an executable definition exists and is reachable by the app.
- Do not enable a system whose definitions the loader would reject; fix the definitions, not the loader.
- Do not add a generic UDS/OBD fallback to fill an empty Toyota scope.
- Do not guess CAN IDs, K-line addresses, init addresses, baud, timings, session types, security levels,
  formulas, masks or applicability. A missing join stays missing.
- Do not treat a local ID as a DID, a low nibble as a selector, a sibling selector as a fallback, or
  `3E` as every keepalive.
- Do not merge repeated printed DTC codes; keep exact 32-bit keys and alternate text.
- Do not ship vendor code, scripts, ELFs or an interpreter.
- Do not claim a car was tested unless the owner ran the bench harness and the evidence is attached.
- Do not edit generated reports by hand; regenerate them.
- Do not weaken, skip or delete a test to make a build pass.

---

## 13. Definition of done

A. **Conversion**
- Every source record has a terminal disposition; `unresolved == 0` across the corpus.
- `notApplicable` items each carry an allow-listed reason and source ID; a test proves the allow-list.
- All 1,308 entries have a resolved link or a proven source gap; 0 guessed fields.
- At final completion no system remains `partial`: every one is `mode: complete`, or disabled with a
  proven source-gap reason (missing entry, missing function, missing ECU file) reported with exact IDs.

B. **App**
- Every system that has a link and reads is enabled and usable; each has one ECU, identification,
  faults, live data and exact provenance.
- The systems list shows truthful counts; partial coverage is visibly labelled.
- Live graphs, DTC labels/states, clear (audited), bring-up and Expert-gated actuators/coding work.
- No fallback, no cross-ECU data, no hidden request.

C. **Delivery**
- Fresh CI run: archive verification, conversion, runtime compile, lifecycle contracts, native oracle,
  verifier, `--require-executable`, APK, lint, all JVM tests, both Android 24 device tests.
- Container(s) within budget, published under immutable hashes, index ≤256 KiB, old containers still
  addressable.
- Reproducible: the same commit and source produce byte-identical packs aside from documented
  toolchain-dependent gzip bytes; the index pins what it publishes.

D. **Docs**
- `README.md` Toyota section updated from "0 executable definitions" to the real state.
- `docs/conversion-report.md` regenerated; `docs/toyota-vehicle-packs.md`,
  `docs/toyota-live-prerequisites.md`, `docs/toyota-numeric-review.md`,
  `docs/ecu-read-definitions.md` updated with the new dispositions and limits.
- A new `docs/toyota-coverage.md` with the feature matrix (systems, PIDs, DTCs, identification,
  actuators, coding) and the bench-evidence status.
- The limitations section still states what is not proven.

E. **Bench (owner, not CI)**
- The owner runs the bring-up on at least one real Toyota for each supported transport class, exports
  the evidence, and the PR links it. CI checks format only, never claims physical validation.

---

## 14. Working agreement

- Start by reading the repo docs listed in §3 and running the Python tests.
- Before coding, reply with: your understanding of the gap, the first three PRs you will open, and the
  exact source IDs you will use. Keep it short.
- Work in small PRs. Do not open a 10,000-line PR.
- Ask the owner only for what you cannot derive: their exact Toyota vehicles (model, year, region,
  engine), adapter model/firmware, and whether they can run bench captures. Do not block code on it.
- When a decision affects schema or safety, present the options and your recommendation; do not silently
  choose.
- If a conversion cannot be done exactly, stop, write the blocker with source IDs, and move on to the
  next class. Never fill the gap with a plausible guess.
- At the end of each turn, report what changed, what the coverage delta is, and what is next.

---

## Appendix A — Anchors and constants (do not drift)

- Source SHA-256: `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`; archive 71,755,650 bytes.
- 1,410 decoded payloads; 1,307 ECU files; 1,308 entries in 1,276 lists; 287,030 systems
  (EU 157,500 / NA 74,985 / JP 54,545); 24,653 vehicle paths (EU 13,941 / NA 5,762 / JP 4,950);
  9 non-vehicle tasks.
- Exact entry joins 286,149; missing 881 (EU 152 / NA 729 / JP 0).
- 180,316 DTC mapping rows → 179,770 label keys; 1,186 fault / 1,184 clear blocks; 27,035 TCODE pairs.
- 8,775 frame records; 34,671 template lookups; 284 fast-KWP entries; 568 lifecycle traces; 17,892
  packets; 2,016 short-packet vectors.
- Keepalive variants: `0100` ×42, `2100` ×174, `2102` ×4, `21E1` ×3, `3E` ×41, `3E01` ×20.
- Exit variants: `82` ×236, `3E` ×42, `3E01` ×6. Pulses: 25/25 ×78, 35/15 ×206.
- Mandatory pilot: selector `0x11`, entry `0x2f63`, baud 10,416, flags `0x81`, pins `07 07`,
  timings `05 3c 00 19 19`, interval arguments (5, 60, 600), primary `13B0`→`53`, layout record count 1,414.
- Container cap 512 MiB; last published container 466,298,841 bytes; index cap 256 KiB; object cap
  8 MiB JSON / 4 MiB compressed; cache cap 128 MiB.

## Appendix B — Known traps

- ECU filenames use **hexadecimal** suffixes; directory slot, schema tag and filename suffix need not
  match.
- EU frame binding tag is `0x0112`; NA/JP use `0x0012` with the same 16-byte schema. Never borrow.
- Command-pool index bias is **> 10,000**, not `>=`.
- `SYSTEMENTER` selection compares the **complete** selector byte; the low nibble selects the record
  layout class only.
- `FUNCDATA +26` is an EXPRESS key, not a monitor key.
- TCODE pairs contain a literal NUL between code and text; split the real bytes, not a rendered view.
- Native public reply buffers have the service byte stripped; `dataOffset=0` means native position 1.
- 9,600 baud is a real native exception, not a default to normalise.
- A source selector `0x51` is an alternative entry, not permission to use `0x11`.
- Duplicate printed DTC codes are different features if the 32-bit keys differ.
- FLASH/download systems are outside the write whitelist: `notApplicable`, never converted.
- Empty `"data": {}` currently suppresses legacy generic UDS reads; do not replace it with a generic
  default to make a screen look populated.
