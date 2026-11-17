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

## Features (Phase 1)

- Pick a paired Bluetooth adapter and connect over SPP (handles the Android 12+ `BLUETOOTH_CONNECT` permission).
- Adapter info: `ATI`, `AT@1`, firmware via `VTVERS`, detected vendor extensions.
- Vehicle info: detected protocol, VIN (Service 09 PID 02), battery voltage (`ATRV`), responding ECUs.
- Live data for the Service 01 PIDs the vehicle reports as supported.
- **Read-only:** the session layer refuses every service except 01, 02, 03, 06, 07, 09 and 0A.

## Architecture

```
ui/                 Compose screens + ObdViewModel
bluetooth/          permissions, paired-device listing (Android)
obd/transport/      Transport interface; BluetoothSppTransport (BLE / Wi-Fi / USB can be added later)
obd/elm/            AdapterDriver: init sequence, FIFO command queue with timeouts,
                    capability detection, response parsing (ElmResponseParser)
obd/protocol/       ObdProtocol (ATDPN), FrameParser, ISO-TP reassembly
obd/session/        Session: per-request ECU targeting (ATSH / ATCP / ATCRA / ATAR), read-only policy,
                    ProtocolDetector
obd/pid/            PID formulas, supported-PID bitmaps, VIN decoding
obd/VehicleReader   high-level reads (supported PIDs, VIN, voltage, live PIDs)
```

Everything under `obd/` except `BluetoothSppTransport` is plain Kotlin with no Android dependencies,
and the unit tests under `app/src/test` cover it.

**Adapter init:** `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH1`, `ATSP0`, then `ATI`, `AT@1`. `ATH1` and `ATSP0` are
required; the others are tolerated if a clone rejects them. ST/VT vendor commands are blocked until the
`VTVERS` / `STI` probes confirm support.

**ECU targeting:** `session.request(EcuTarget.Physical("7E0", "7E8"), 0x01, 0x0C)` sends
`ATSH 7E0` and `ATCRA 7E8` (only when the target changes) and then the request; `EcuTarget.Broadcast` returns
to `7DF` with `ATAR`. 29-bit CAN headers use `ATCP` + `ATSH`.

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
