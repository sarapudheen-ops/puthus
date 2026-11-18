# bod3

OBD-II diagnostic app for Android (read-only by default; gated Expert-mode coding/tests), built with Kotlin, Jetpack Compose and Material 3.
Phase 1 targets ELM327-compatible Bluetooth Classic adapters based on the MIC3313 chip (Vgate / vLinker).

| | |
|---|---|
| Package | `com.bod3.app` |
| minSdk / targetSdk | 24 / 35 |
| Gradle | 8.9 (wrapper) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Room (KSP) | 2.6.1 (KSP 2.0.21-1.0.28) |

## Features (Phase 1)

- Pick a paired Bluetooth adapter and connect over SPP (handles the Android 12+ `BLUETOOTH_CONNECT` permission).
- Adapter info: `ATI`, `AT@1`, firmware via `VTVERS` / `STI`.
- Vehicle info: detected protocol, VIN (Service 09 PID 02), battery voltage (`ATRV`), responding ECUs.
- Live data for the Service 01 PIDs the vehicle reports as supported.
- **Read-only:** the session layer refuses every service except the OBD reads (01, 02, 03, 06, 07, 09, 0A)
  and three UDS reads, which are allowed only with read-only sub-functions: `22` ReadDataByIdentifier,
  `19` ReadDTCInformation (report sub-functions 01/02/03/04/06/0A only) and `3E 00/80` TesterPresent.
  Clears (04, 14), writes (2E, 3B), routines (31), IO control (2F), security access (27), session control
  (10), resets (11) etc. are rejected before anything reaches the adapter.

## Features (Phase 2): module scan and vehicle profiles

- **Module scan** (CAN vehicles), with progress and cancel:
  1. OBD broadcast `01 00` on `7DF` / `18DB33F1`.
  2. OBD addresses `7E0`–`7E7` / `18DA10F1`–`18DA17F1` with `3E 00`, then `01 00`.
  3. UDS addresses `700`–`7FF` / `18DAxxF1` with `3E 00`, then `22 F190`.
  4. The other CAN ID length (29-bit on an 11-bit car and the other way round), optional.

  Any reply counts as a module, including a negative response (`7F`). During probing the adapter
  accepts any reply ID in the range (`ATCF`/`ATCM`), so modules with non-standard reply IDs are found.
  The adapter reply timeout is shortened (`ATST`, 100 ms by default) and restored afterwards, even
  when the scan is cancelled.
- **Per module:** UDS `22` for system name, part number (F187), software number/version (F188/F189),
  hardware (F191/F193), supplier software (F195), supplier, serial, VIN (F190), coding data set (F19E) and
  VW long coding (0600); DTCs via `19 02 FF` (3-byte codes with status). OBD modules also get `09 0A`
  (ECU name), `09 04` (calibration ID) and `03` as a DTC fallback. Negative responses show as "Not
  supported" (or the NRC) instead of failing; `7F xx 78` (response pending) and `21` (busy) are retried.
  Non-standard request/response pairs get ISO-TP flow control on the request ID (`ATFCSH`/`ATFCSD`/`ATFCSM1`).
- **Vehicle profiles (Room):** keyed by VIN, with make/model/year from NHTSA vPIC (`DecodeVinValues`).
  Offline, it falls back to WMI + model year from the VIN and upgrades to vPIC on a later connection.
  It also stores the protocol, every module, and the raw request/response log of the latest scan.
  Reconnecting to the same VIN loads the saved profile. If OBD reports no VIN, the VIN that the modules
  report in `22 F190` is used.
- **Screens:** scan (progress, modules found live, cancel), module list (name or address, versions, fault
  count), module detail (identifiers with raw hex, DTCs with status, raw request/response log).
  Copy or share the module or full log in one tap.

## Features (M1): connection for any vehicle

- **Protocol negotiation:** tries the protocol that last worked with this adapter first, then the
  automatic search (`ATSP0`), then every other protocol the vLinker MC+ supports (see *Protocols* below).
  Each attempt sends the protocol's selection commands, its read-only probe and `ATDP`; the raw reply,
  `ATDP` text and duration are logged and shown under "Protocol detection". The working protocol is
  remembered per adapter. If nothing answers, the adapter stays connected so you can turn the ignition
  on and tap *Detect vehicle*.
- **Connection profiles** (gear icon on the adapter list and the dashboard): force a protocol (with or
  without fallbacks), request and search timeouts, adapter reply timeout (`ATST` or vLinker `VTTOST`), a
  CAN bit rate for user CAN B/C and STN CAN (10-1000 kbit/s), extra AT/ST/VT init commands, adaptive timing (`ATAT0/1/2`), a broadcast receive filter
  (`ATCF`/`ATCM`), custom flow control (`ATFCSH`/`ATFCSD`/`ATFCSM1`) and ISO 15765-2 extended addressing
  (`ATCEA`). Two built-in profiles (Automatic and a patient K-line profile). Rejected setup commands are
  logged and skipped, never fatal.
- **K-line** (ISO 9141-2, ISO 14230-4 KWP2000): live data, VIN (the five-message K-line reply) and OBD fault
  codes; module scans stay CAN-only.
- **Fault codes (OBD):** stored (03), pending (07) and permanent (0A) codes per ECU, on CAN and K-line.
  Read-only.
- **Reconnect:** if the Bluetooth link drops, live data and scans stop, and the app reconnects after 1,
  3, 6 and 10 s, trying the remembered protocol first. Live data resumes after a successful reconnect.
- **Adapter info:** ATI, AT@1, the firmware versions from `VTVERS` and `STI` (read once per connection,
  display and report only), plus the profile's setup steps.
- **Battery voltage monitor:** `ATRV` every 5 s with a graph, min/max and low/charging thresholds
  (12.0 V / 13.2 V). It pauses during module scans and while the terminal is open.
- **Logs:** every log (adapter log, raw scan log, module log, protocol attempts, terminal) has Copy, Share
  and Clear. Clear only clears what is on screen; the raw log saved with a vehicle profile is kept.
- **Share diagnostic report:** a text file with adapter info, profile, setup, protocol attempts, vehicle,
  voltage, fault codes, modules, reconnects and all logs, shared through the share sheet (FileProvider,
  cache only).
- **Expert mode and raw terminal:** off by default; you must acknowledge the risks to turn it on. The terminal
  sends AT, ST and VT commands as typed, except baud-rate, memory, low-power, monitor and bootloader/factory
  reset commands. OBD/UDS requests must pass the same read-only policy as the rest of
  the app, so writes, clears and actuator tests are blocked. When the terminal closes, the app restores
  `ATE0 ATS0 ATH1 ATCAF1 ATR1` and, if routing was changed, re-selects the protocol and re-sends its routing.

### Protocols (vLinker MC+ / MIC3313)

Every protocol is listed under *Connection settings → Supported protocols*, can be forced in a profile, and
is tried by automatic detection when the `ATSP0` search fails:

| Code | Protocol | Selection |
|---|---|---|
| 1, 2 | SAE J1850 PWM / VPW | `ATSP1`, `ATSP2` |
| 3, 4, 5 | ISO 9141-2, ISO 14230-4 KWP (5-baud, fast init) | `ATSP3`–`ATSP5` |
| 6–9 | ISO 15765-4 CAN 11/29-bit, 500/250 kbit/s | `ATSP6`–`ATSP9` |
| A | SAE J1939 (probe: PGN 00FECA) | `ATSPA` |
| B, B/29 | User1 CAN, 11 or 29-bit, custom bit rate (default 125k) | `ATPB oo dd`, `ATSPB` (+ `STPBR` for e.g. 1 Mbit/s) |
| C, C/29 | User2 CAN, 11 or 29-bit, custom bit rate (default 50k) | `ATPP 2E/2F`, `ATSPC` (+ `STPBR`) |
| STN 31/32 | ISO 15765 11/29-bit, custom bit rate | `STP 31/32` (+ `STPBR`) |
| STN 41/42 | ISO 11898 raw CAN 11/29-bit | `STP 41/42` |
| STN 51–54 | GM SW-CAN (ISO 15765 and raw) | `STP 51`–`STP 54` |
| STN 61–64 | Ford MS-CAN (ISO 15765 and raw) | `STP 61`–`STP 64` |
| VT KWP1281 | VAG KWP1281 on K-line | `VTP291` |

ISO 15765-2 extended addressing uses `ATCEA hh`. ST and VT commands are sent directly with no capability
gate: if the adapter answers `?` the reply is logged and skipped (a rejected *selection* command only fails
that protocol attempt). Profiles can add any extra AT/ST/VT commands (e.g. `STCSEGT1`, `VTVLCW`).

## Features (M2): diagnostics

- **Live data for every supported PID:** about 90 standard Service 01 PIDs (fuel trims, O2 sensors,
  pressures, temperatures, EGR, evap, catalyst temperatures, odometer…), filtered by the PIDs the ECU
  reports as supported, plus custom PIDs. Choose the values and their order (the pencil on the live data
  screen; saved). Each tile can show a value, a gauge (min–max) or a graph of the last 120 samples.
- **Recording and CSV export:** *Record* stores one row per polling cycle (elapsed seconds, UTC time, one
  column per value, empty cells for missing readings, at most 100,000 rows). *Share* exports it as CSV
  through the share sheet. Recording stops when you leave live data or the link drops; what was recorded
  so far is kept.
- **Custom PIDs:** a read request (Service 01/09 or UDS `22` + DID), a formula over the reply data bytes
  `A`, `B`, `C`… (`+ - * / %`, bit operators, `0x` hex, `min max abs round floor signed8/16/32 bit`), a unit,
  gauge range, decimals and optionally an ECU request/response ID. The editor validates the request
  against the read-only policy and shows formula errors with their position. *Test read* sends the request
  once. Stored in the user data pack.
- **Data packs (JSON, `"format": "bod3-datapack"`):** custom PIDs, vehicles with ECU lists, and DTC
  descriptions. Three packs are bundled in `assets/datapacks`: `generic-obd.json` (about 200 SAE J2012 code
  descriptions, sample PIDs, generic 11/29-bit OBD ECUs), `vw-group-mqb.json` (Tiguan AD1, Golf 7,
  Passat B8, A3 8V, Octavia 5E and Leon 5F with 11 UDS ECUs each), and `toyota-lexus-dtcs.json`
  (11,989 manufacturer-scoped, passive fault-code meanings; see below). Your own additions (PIDs, vehicles,
  edited ECU lists, descriptions) are a separate user pack that overrides bundled entries with the same ID.
  Every pack is validated; errors name the field (`pids[2].formula: …`).
- **Fault codes:** stored, pending and permanent codes with descriptions. Lookup order: your own
  description, bundled packs, then a generic category (`P1xxx` = manufacturer-specific…). UDS sub-types
  (`B1012-13`) show the failure type ("circuit open"). Tap *Edit* on any code to add your own description.
- **Readiness** (01 01): MIL, code count, petrol/diesel monitor set, and the complete/incomplete state of
  each supported monitor. **Freeze frame** (02): the code that stored frame 0 and every supported PID in it.
- **Clearing fault codes, always with confirmation:** OBD service 04 (all emission ECUs) or UDS
  `14 FF FF FF` for one ECU. The dialog lists what is deleted and needs a tick before *Clear* is enabled.
  The confirmation is a one-shot token, valid for 60 s and bound to the exact request and ECU; the `Session`
  refuses a clear without it. The terminal cannot get one. For OBD, the app reads the RPM first and refuses
  while the engine is running. A UDS clear is sent once (no automatic retry); a refusal shows its NRC
  ("conditions not correct – engine off?"), and the codes are read again afterwards.
- **Vehicle & ECUs (make/model selection, like Car Scanner):** pick a bundled or your own vehicle. Its ECU
  list shows address, name, request/response IDs (11-bit `7E0`, 29-bit `18DA10F1`) and protocol (UDS or
  OBD). The list is editable: add, remove and change ECUs, or add a whole vehicle. Edits to a bundled
  vehicle are saved as a copy that can be reset.
- **Per-ECU UDS:** identification (25 `F1xx` DIDs: part number, software and hardware versions, VIN,
  serial, system name, coding…, stopping after one timeout if the module is absent), fault codes (19 02),
  extended data (19 06) and snapshot (19 04) for a selected code, and a confirmed clear (14).

## Features (M3): module scan and vehicle profiles

- **Saved vehicles** (button on the adapter list and the dashboard): every VIN profile with its last scan.
  Open a profile to browse its modules, identification, fault codes and raw log without the car; delete it
  with confirmation.
- **Profile export / import:** one JSON file per vehicle (`bod3-vehicle-profile`, format 1) with the
  profile header, every module (identifiers, DTCs, coding), the raw log, the manual ECU list and module
  names, shared through the share sheet. Import validates the whole file first and lists every problem
  with its JSON path (nothing is changed on error); an existing VIN is only replaced after confirmation.
- **Manual ECU list per vehicle** (*Modules → ECUs*): add, edit and remove ECUs (name, address code,
  request/response ID, UDS/OBD, notes) or copy the data-pack vehicle's list. Every scan probes listed ECUs
  directly with their exact reply ID (`3E 00`, `22 F190`, `01 00`), so modules outside `700–7FF` or with
  unusual reply IDs are found; a 29-bit listed ECU adds the 29-bit pass on its own. *Quick scan* probes only
  the listed ECUs.
- **Module naming**, in priority order: your name for the module on this vehicle (*Module → Rename*), the
  manual ECU list, the data-pack vehicle chosen for this VIN (or the globally selected one), the module's own
  name (`F197`), data-pack address names for the vehicle's make (`moduleNames`, e.g. the bundled VW-group
  addresses for VW/Audi/Skoda/SEAT/Cupra), the OBD ECU name (`09 0A`) and standard OBD addresses. The module
  list shows where each name came from.
- Data packs gain an optional `moduleNames` list (`requestId`, `responseId`, `address`, `name`, `makes`),
  validated like the rest of the pack.

## Features (M4): bidirectional tests

Actuator tests switch an output (`2F` InputOutputControlByIdentifier) or run a routine (`31`
RoutineControl) in one module for a limited time, then hand control back to the module.

- **Recipes** are JSON (`bod3-actuators`, format version 1). Each recipe names its module (CAN request and
  response IDs), the diagnostic session (`01` or `03`), optional security access (level and key
  algorithm), the start request, the stop request, an optional status request, the time limit, the
  preconditions, safety notes, applicability (makes, models, module part numbers, software versions),
  source and notes. Bundled templates live in `assets/actuators` and ship as **unverified**, because their
  identifiers are placeholders. You can import your own recipes from a file, export them through the share
  sheet, and delete them. Imports are validated, and every problem is listed with its JSON path
  (`recipes[1].stop: must be "2F 05 10 00" …`).
- **Validation:** a recipe is rejected unless:
  - it starts with `2F <DID> <01|02|03>` and stops with `2F <DID> 00`, or starts with `31 01 <id>` and
    stops with `31 02 <id>`;
  - its session is `01` or `03` (never programming);
  - its security level is odd;
  - its time limit is 120 s or less;
  - it has at least one safety note.
- **Run flow:**
  1. Expert mode check.
  2. Safety-notes confirmation, which is single-use, bound to the recipe and valid for 60 s.
  3. Fresh precondition check:
     - battery at or above the recipe minimum (12.0 V by default);
     - ignition on (the engine ECU answers);
     - engine off or running as required (`01 0C`);
     - vehicle stationary (`01 0D`).

     An unknown value counts as a failure. A make/model or `F187`/`F189` mismatch blocks the run unless
     you override it explicitly.
  4. Open the session, then security access (seed and key).
  5. Start.
  6. While the test runs, the screen shows live status and a countdown. The session is kept alive with the
     status request or `3E 00`, at least every 2 s.
- **Stopping:** STOP is always available. The test also stops automatically:
  - at the time limit;
  - when the module stays silent for two polls;
  - when the Bluetooth link drops;
  - when you leave the screen;
  - when the coroutine is cancelled.

  The stop request is always sent (retried once), followed by `10 01`. If the stop is not acknowledged, the
  app says so and explains that the module hands control back when its session times out.
- **Audit log:** every run, including refused ones, is stored with timestamps and every request/response
  pair. The log cannot be edited and can be shared as text.
- **Write policy:** non-read requests leave the app only with a `WriteGrant`. Grants are issued by
  `WriteGate`, only in Expert mode, for one module and one purpose, and they expire or are revoked when the
  operation ends. The actuator whitelist is `10 01/03`, `27`, `3E`, `2F` (parameters 00-03) and
  `31 01/02/03`. ECU reset, communication control, DTC setting, memory writes and downloads are never
  sent. Without a grant the read-only policy applies unchanged, and the expert terminal never gets a grant.

Tests (`actuator/`, `obd/session/WritePolicyTest`) run against `testing/SimUdsEcu`, a stateful simulated
UDS module (sessions, seed/key, IO control, routines, writes, NRCs, response pending, silence) on the
simulated CAN adapter. They cover:
- the whitelist and grant scoping;
- refusal outside Expert mode;
- every precondition;
- wrong keys and negative responses to start;
- response pending;
- connection loss mid-run;
- a silent module;
- cancellation;
- confirmation reuse;
- applicability overrides.

## Features (M5): coding

Coding changes a value stored in a module: a long-coding byte/bit (VW `0600`), an adaptation channel, or
any identifier value. All three are read with `22 <DID>` and written back whole with `2E <DID> <data>`.
Only the bits or bytes of the recipe's field change; every other byte is written back exactly as read.

- **Recipes** are JSON (`bod3-coding`, format version 1). Each recipe has a name, description, module (address,
  CAN request/response IDs), method (`long_coding`, `adaptation`, `did_value`), identifier (default `0600`
  for long coding), byte position, bit mask or length (1-4 bytes, big-endian), a value map or range
  (with optional factor/offset/unit for display), session, optional security access, preconditions
  (`minVoltage`, default 12.0 V; `allowEngineRunning`, default false), applicability (makes, models, module
  part numbers, software versions), source, notes and `verified`. Bundled examples live in `assets/coding`
  (generic templates and VW MQB, including Tiguan BCM comfort closing as a long-coding bit) and all ship as
  **unverified**: long-coding positions differ between part numbers. Recipes can be added at runtime: import
  a file, or create/edit one in the in-app JSON editor (editing a bundled recipe makes your own copy).
  Every problem is listed with its JSON path.
- **Validation:** identification records `F180`-`F19F` are never written (except `F198`/`F199`); long coding
  needs a mask; masks are single-byte; identifier values need a value map; every option must fit the field;
  sessions are `01`/`03` only; security levels are odd.
- **Write flow** (single verified steps, aborting cleanly on any error):
  1. **Verify the module:** `F187`/`F189` are read and matched against the recipe's part numbers and software
     versions. A mismatch (or an unreadable value) refuses unless you tick the override.
  2. **Read and back up:** the identifier is read and the whole data is saved as a persistent backup with
     module, session and security details, so it can be restored even if the recipe is deleted later.
  3. **Diff:** choose the new value and see before/after, byte by byte (hex and binary).
  4. **Confirm:** single-use confirmation bound to that backup and that value, valid for 60 s.
  5. **Safety checks:** Expert mode; battery ≥ the recipe minimum (unknown counts as a failure); ignition on;
     engine off unless the recipe allows it; vehicle stationary.
  6. **Re-read:** the value must still equal the backup, otherwise nothing is written.
  7. `10 03`, security access, `2E`. A write is sent once and never retried.
  8. **Verify:** read back and compare. Outcomes: verified, rejected (module unchanged), read-back differs, or
     unknown (connection lost); the last two offer rollback straight away.
  9. `10 01` and the grant is revoked, even after errors or cancellation.
- **Rollback:** one tap after a write, or from any backup in the list. It writes the backup through the same
  gates (Expert mode, confirmation, safety checks, session, security, read-back).
- **Audit log:** every write, rollback and refusal is recorded with the before/intended/read-back data,
  part number, software version, backup id, whether the version check was overridden, and every
  request/response.
- **Write policy:** the coding grant allows only `10 01/03`, `27`, `3E` and `2E` on one module. While a coding
  read or write runs, scans, ECU reads, actuator tests, the terminal and voltage polling are blocked, and the
  screen cannot be left.

Tests (`coding/`) cover the codec and validator, the field bit math, backup → write → verify → rollback,
refusal outside Expert mode, low/unknown battery voltage, engine running (and recipes that allow it),
version mismatch and override, a wrong security key, a negative response to `2E`, connection loss during the
write (before and after the module applied it), read-back mismatch, a module that silently ignores the
write, a value that changed since the backup, confirmation reuse/expiry, and the screen controller.

## Features (M6): data packs and polish

- **One import for every file** (Data packs screen, *Choose file…*): data packs (PIDs, vehicle ECU
  lists, module names, DTC texts), bidirectional test recipes, coding recipes and saved vehicle
  profiles. The type is detected from the `"format"` field (`bod3-datapack`, `bod3-actuators`,
  `bod3-coding`, `bod3-vehicle-profile`), with a fallback for older files without it.
- **Validated before anything is saved.** The whole file is checked first; a preview lists what it
  contains (and how many items replace existing ones), warnings (e.g. unverified recipes) and every
  problem with its location – e.g. `Not valid JSON at line 3, column 9: … (often a missing comma,
  quote or bracket just before this point)` or `pids[0] (bad): request 2E… is not a read service`.
  A file with any problem imports nothing. Files over 2 MB are refused.
- **Merging:** imported items with the same id (PID id, vehicle make+model, module address, DTC code,
  recipe id) replace the user's own; bundled packs are never changed.
- **Export through the share sheet:** my custom PIDs, my vehicles and ECU lists, all my data as one
  data pack, my test recipes, my coding recipes, and any bundled pack. Profiles are exported from
  Saved vehicles.
- **Templates** (`assets/samples`): annotated data pack, actuator and coding files showing every
  field, shareable from the app as starting points. Unit tests validate them.
- **App settings:** theme (system / light / dark), keep screen on, and display units – temperature
  (°C/°F), distance-based units (km, km/h, L, L/h, Nm ↔ mi, mph, US gal, gal/h, lb·ft) and pressure
  (kPa / bar / psi). Units convert live data (values, gauges, graphs) and freeze frames for display
  only; CSV recordings, data packs and coding values stay in the original metric units.
- **Onboarding** on first start: pairing the adapter, permissions, read-only by default vs Expert
  mode, and the safety rules for tests and coding. It can be shown again from App settings.
- **Errors that explain themselves** (`ErrorExplainer`): Bluetooth failures (adapter unreachable,
  link dropped, permission), adapter replies (`UNABLE TO CONNECT`, `BUS INIT … ERROR`, `CAN ERROR`,
  `NO DATA`, `?`, `BUFFER FULL`, `LV RESET`, …), timeouts and UDS negative responses (0x22
  conditions not correct, 0x33/0x35/0x36 security, 0x31, 0x7F …) are mapped to the likely cause and
  what to do. Used for connection and reconnect failures, unavailable readouts and protocol
  detection.

## Toyota/Lexus recovered reference data

The `assets-v1/TOYOTA.zip` release database has been decoded **as data only**, without executing vendor
libraries. Recovery produced 1,410 YZJM binary payloads and eight English text libraries (198,091 indexed
records; 193,589 valid UTF-8). Recovered datasets remain outside Git and are available in the
**Inspect Toyota release data** workflow's `toyota-recovered-data` artifact.

The app bundles **11,989 unambiguous passive DTC meanings** from that data, with source attribution.
They apply only to a known Toyota/Lexus make in the loaded vehicle profile (or a vehicle explicitly linked
to that VIN when the make is unknown), **never an unrelated/unknown car or a stale global ECU-list choice**.
Data-pack v2 `dtcGroups` preserves manufacturer scope and source through import/export; v1 remains supported.
Conflicts, placeholders and unsupported code forms are excluded, and extended codes are not promoted to
base codes. No Toyota PIDs, ECU addresses, coding or actuator commands have been enabled from this data.

See [Toyota data, limitations, usage and schema](docs/toyota-data.md) and
[reproduction tools and static format evidence](tools/thinkdiag/README.md). CI regenerates the bundled
reference byte-for-byte; no physical adapter/car testing has been performed.

## Architecture

```
ui/                 Compose screens + ObdViewModel
bluetooth/          permissions, paired-device listing (Android)
obd/transport/      Transport interface; BluetoothSppTransport (BLE / Wi-Fi / USB can be added later)
obd/elm/            AdapterDriver: init sequence, FIFO command queue with timeouts,
                    firmware version read, response parsing (ElmResponseParser)
obd/protocol/       ObdProtocol (ATDPN), FrameParser, ISO-TP reassembly
obd/session/        Session: per-request ECU targeting (ATSH / ATCP / ATCRA / ATCF+ATCM / flow control),
                    protocol switching (ATSP), ReadOnlyPolicy, ProtocolDetector
obd/pid/            PID formulas, supported-PID bitmaps, VIN decoding
obd/uds/            UdsClient (22 / 19 02 / 03 / 09 reads, negative responses as values), DTC decoding,
                    known identifiers, NRC names
obd/scan/           ModuleScanner (discovery), ModuleReader (per-module reads), ScanCoordinator
obd/elm/RecordingExecutor  raw request/response log
obd/VehicleReader   high-level reads (supported PIDs, VIN, voltage, live PIDs)
profile/            VehicleProfileService, vPIC client + parser, offline VIN decoder, ModuleCodec
profile/db/         Room entities, DAO, database, RoomProfileRepository (Android)
obd/connection/     ConnectionProfile (+ JSON codec), ConnectionStore (profiles, remembered protocols,
                    Expert mode), ProtocolNegotiator, AdapterSetup, ConnectionManager, VoltageMonitor,
                    ReconnectPolicy
obd/dtc/            ObdDtcReader: services 03 / 07 / 0A and the confirmed clear (04) on CAN and K-line;
                    FreezeFrameReader (02), ReadinessDecoder (01 01)
obd/pid/Formula     formula parser/evaluator for custom PIDs
datapack/           data pack models, JSON codec + validation, DataPackStore (user pack, selections),
                    DataRepository (bundled + user merge), DtcDescriptions
diagnostics/        EcuWorkbench: per-ECU identification, DTCs, records and clear
live/               LiveCatalog, LiveReader, LiveRecorder, CsvExporter, LiveHistory
obd/terminal/       TerminalPolicy, RawTerminal (expert raw terminal)
report/             DiagnosticReport (shareable text report)
android/            SharedPreferences store, ReportSharer (FileProvider), AssetDataPacks, AssetActuatorPacks,
                    AssetCodingPacks
obd/session/WriteGrant  WritePolicy (per-purpose whitelist), WriteGate (Expert mode only), WriteGrant
obd/uds/UdsWriteClient  session control, security access (seed/key algorithms), non-read requests as outcomes
actuator/           recipe model + validation, JSON codec, ActuatorLibrary (bundled + user), PreconditionChecker,
                    ActuatorRunner (run engine), ActuatorController (screen logic)
coding/             CodingRecipe + CodingField (bit math), JSON codec, CodingLibrary (bundled + user),
                    CodingBackupStore, CodingEngine (read/backup/write/verify/rollback), CodingController
audit/              AuditStore (permanent operation log), AuditFormatter
recipe/             JsonFields (path-aware JSON reading), Hex
```

Everything under `obd/` and `profile/` except `BluetoothSppTransport` and `profile/db/` is plain Kotlin
with no Android dependencies, and the unit tests under `app/src/test` cover it. The scanner tests use
`testing/SimulatedCanAdapter`, a simulated ELM327 on a CAN bus. It models headers, receive filters, ISO-TP
framing, both ID lengths and per-module behaviour (negative replies, response pending, lost flow
control), and it fails any test that sends a request outside the read-only policy. Clears that reach the
bus are recorded separately (`clears`), so tests can prove a clear only goes out with a confirmation.
`testing/SimulatedVehicleElm` simulates a vLinker MC+ plugged into a vehicle on any protocol (automatic
search, `ATSPx`, user CAN `ATPB`/`ATPP` with bit rates, `STP nn`/`STPBR`, `VTP291`, J1939, `ATDP`/`ATDPN`, K-line framing with checksums, ISO-TP on CAN). The
connection tests use it for negotiation fallbacks, profile setup, K-line VIN/live data/DTCs and link loss.

**Adapter init:** `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP0`, then `ATI`, `AT@1`. `ATH1` and `ATSP0` are
required; the others are tolerated if a clone rejects them. `VTVERS` / `STI` are read once for display and
the report; ST/VT commands are never gated on them.

**ECU targeting:** `session.request(EcuTarget.Physical("7E0", "7E8"), 0x01, 0x0C)` sends
`ATSH 7E0` and `ATCRA 7E8` (only when the target changes) and then the request; `EcuTarget.Broadcast` returns
to `7DF` with `ATAR`. 29-bit CAN headers use `ATCP` + `ATSH`. Header, receive filter and flow control are
tracked separately, so only the group that changed is re-sent.

## Building

Requires JDK 17 and the Android SDK (set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`).

```sh
./gradlew assembleDebug   # APK -> app/build/outputs/apk/debug/
./gradlew lintDebug       # report -> app/build/reports/
./gradlew testDebugUnitTest
```

## CI

`.github/workflows/android.yml` runs `assembleDebug lintDebug testDebugUnitTest` on every push and pull
request, and uploads the debug APK plus build, lint and test reports as workflow artifacts.
