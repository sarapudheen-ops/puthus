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
- Adapter info: `ATI`, `AT@1`, firmware via `VTVERS`, detected vendor extensions.
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
  automatic search (`ATSP0`), then `ATSP5, 4, 3, 6, 7, 8, 9` (KWP fast, KWP slow, ISO 9141-2, the four
  CAN variants). Each attempt sends `ATSPx`, the read-only probe `0100` and `ATDP`; the raw reply,
  `ATDP` text and duration are logged and shown under "Protocol detection". The working protocol is
  remembered per adapter. If nothing answers, the adapter stays connected so you can turn the ignition
  on and tap *Detect vehicle*.
- **Connection profiles** (gear icon on the adapter list and the dashboard): force a protocol (with or
  without fallbacks), request and search timeouts, adapter reply timeout (`ATST`, or `VTTOST` when the
  adapter supports vLinker VT commands), adaptive timing (`ATAT0/1/2`), a broadcast receive filter
  (`ATCF`/`ATCM`), custom flow control (`ATFCSH`/`ATFCSD`/`ATFCSM1`) and ISO 15765-2 extended addressing
  (`ATCEA`). Two built-in profiles (Automatic and a patient K-line profile). Rejected setup commands are
  logged and skipped, never fatal.
- **K-line** (ISO 9141-2, ISO 14230-4 KWP2000): live data, VIN (the five-message K-line reply) and OBD fault
  codes; module scans stay CAN-only.
- **Fault codes (OBD):** stored (03), pending (07) and permanent (0A) codes per ECU, on CAN and K-line.
  Read-only.
- **Reconnect:** if the Bluetooth link drops, live data and scans stop, and the app reconnects after 1,
  3, 6 and 10 s, trying the remembered protocol first. Live data resumes after a successful reconnect.
- **Adapter capabilities:** ATI, AT@1, VT (`VTVERS`) and ST (`STI`) detection, plus the profile's setup steps.
- **Battery voltage monitor:** `ATRV` every 5 s with a graph, min/max and low/charging thresholds
  (12.0 V / 13.2 V). It pauses during module scans and while the terminal is open.
- **Logs:** every log (adapter log, raw scan log, module log, protocol attempts, terminal) has Copy, Share
  and Clear. Clear only clears what is on screen; the raw log saved with a vehicle profile is kept.
- **Share diagnostic report:** a text file with adapter info, profile, setup, protocol attempts, vehicle,
  voltage, fault codes, modules, reconnects and all logs, shared through the share sheet (FileProvider,
  cache only).
- **Expert mode and raw terminal:** off by default; you must acknowledge the risks to turn it on. The terminal
  sends AT commands as typed, except baud-rate, memory, low-power and monitor commands. ST/VT commands are
  limited to identification queries. OBD/UDS requests must pass the same read-only policy as the rest of
  the app, so writes, clears and actuator tests are blocked. When the terminal closes, the app restores
  `ATE0 ATS0 ATH1 ATCAF1 ATR1` and, if routing was changed, re-selects the protocol and re-sends its routing.

## Architecture

```
ui/                 Compose screens + ObdViewModel
bluetooth/          permissions, paired-device listing (Android)
obd/transport/      Transport interface; BluetoothSppTransport (BLE / Wi-Fi / USB can be added later)
obd/elm/            AdapterDriver: init sequence, FIFO command queue with timeouts,
                    capability detection, response parsing (ElmResponseParser)
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
obd/dtc/            ObdDtcReader: services 03 / 07 / 0A on CAN and K-line
obd/terminal/       TerminalPolicy, RawTerminal (expert raw terminal)
report/             DiagnosticReport (shareable text report)
android/            SharedPreferences store, ReportSharer (FileProvider)
```

Everything under `obd/` and `profile/` except `BluetoothSppTransport` and `profile/db/` is plain Kotlin
with no Android dependencies, and the unit tests under `app/src/test` cover it. The scanner tests use
`testing/SimulatedCanAdapter`, a simulated ELM327 on a CAN bus. It models headers, receive filters, ISO-TP
framing, both ID lengths and per-module behaviour (negative replies, response pending, lost flow
control), and it fails any test that sends a request outside the read-only policy.
`testing/SimulatedVehicleElm` simulates a vLinker MC+ plugged into a vehicle on any protocol (automatic
search, explicit `ATSPx`, `ATDP`/`ATDPN`, VT detection, K-line framing with checksums, ISO-TP on CAN). The
connection tests use it for negotiation fallbacks, profile setup, K-line VIN/live data/DTCs and link loss.

**Adapter init:** `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP0`, then `ATI`, `AT@1`. `ATH1` and `ATSP0` are
required; the others are tolerated if a clone rejects them. ST/VT vendor commands are blocked until the
`VTVERS` / `STI` probes confirm support.

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
