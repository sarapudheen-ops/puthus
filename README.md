# bod3

Read-only OBD-II diagnostic app for Android, built with Kotlin, Jetpack Compose and Material 3.
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
```

Everything under `obd/` and `profile/` except `BluetoothSppTransport` and `profile/db/` is plain Kotlin
with no Android dependencies, and the unit tests under `app/src/test` cover it. The scanner tests use
`testing/SimulatedCanAdapter`, a simulated ELM327 on a CAN bus. It models headers, receive filters, ISO-TP
framing, both ID lengths and per-module behaviour (negative replies, response pending, lost flow
control), and it fails any test that sends a request outside the read-only policy.

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
