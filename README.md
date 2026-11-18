# bod3

OBD-II diagnostic app for Android (read-only so far), built with Kotlin, Jetpack Compose and Material 3.
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
  descriptions. Two packs are bundled in `assets/datapacks`: `generic-obd.json` (about 200 SAE J2012 code
  descriptions, sample PIDs, generic 11/29-bit OBD ECUs) and `vw-group-mqb.json` (Tiguan AD1, Golf 7,
  Passat B8, A3 8V, Octavia 5E and Leon 5F with 11 UDS ECUs each). Your own additions (PIDs, vehicles,
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
android/            SharedPreferences store, ReportSharer (FileProvider), AssetDataPacks
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
