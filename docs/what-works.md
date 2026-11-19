# What the app does today

Everything below is present in the code on this branch and covered by the 64 JVM test classes
(500+ tests, green in CI) unless a line says otherwise. **No real-car validation has been recorded in
this repository** — the tests use synthetic adapters, fixtures and an emulator, so "implemented" means
"the logic exists and is tested", not "verified on your car".

Install: <https://github.com/sarapudheen-ops/puthus/releases/download/apk-latest/bod3-debug-0c3e9a8.apk>

---

## 1. Connecting to a car

| Feature | Notes |
|---|---|
| Bluetooth Classic (SPP) adapter | ELM327-compatible; tuned for MIC3313/vLinker (Vgate, vLinker) |
| Android 12+ `BLUETOOTH_CONNECT` permission | Handled |
| Paired-device picker | Shows paired adapters, connects and holds the link |
| Adapter info | `ATI`, `AT@1`, firmware via `VTVERS` / `STI` |
| Battery voltage monitor | `ATRV` every 5 s, graph, min/max, low/charging thresholds, pauses during scans |
| Reconnect | Automatic retry sequence with the remembered protocol; live data resumes |
| **12 protocols** | J1850 PWM/VPW, ISO 9141-2, KWP2000 (5-baud + fast), ISO 15765-4 CAN 11/29-bit at 500/250k, J1939, User1/User2 CAN with custom bit rate, STN 31/32/41/42, GM SW-CAN, Ford MS-CAN, VT KWP1281 |
| Automatic protocol search | `ATSP0` first, then every supported protocol in turn, each logged with raw reply + `ATDP` |
| Protocol remembered per adapter | Next connection and every reconnect tries it first |
| Connection profiles | Force protocol, request/search timeouts, `ATST` or vLinker `VTTOST`, CAN bit rate 10–1000 kbit/s, extra AT/ST/VT init commands, adaptive timing, receive filter, custom flow control, ISO-TP extended addressing |
| K-line support | ISO 9141-2 and KWP2000: live data, VIN, OBD fault codes |

## 2. Reading the car

| Feature | Notes |
|---|---|
| VIN | OBD `09 02`, plus the five-message K-line reply; falls back to module `22 F190` |
| Vehicle identity | Make/model/year via NHTSA vPIC when online; offline fallback from WMI + model year |
| **Module scan** | OBD broadcast, physical `7E0–7E7`, then UDS `700–7FF` (and the other CAN ID length). Progress + cancel. Any reply counts, so modules with non-standard reply IDs are found |
| Module naming | Module's own system name (`F197`), else OBD ECU name (`09 0A`), else a generic address label |
| **ECU identification** | 25+ standard DIDs per module: part number `F187`, software number/version `F188`/`F189`, supplier, serial, VIN `F190`, hardware `F191`/`F193`, supplier software `F195`, system name `F197`, coding data set `F19E`, VW long coding `0600` |
| **Fault codes** | OBD stored (03), pending (07), permanent (0A); UDS `19 02` per module with status |
| Readiness | `01 01`: MIL, code count, petrol/diesel monitors, complete/incomplete per monitor |
| Freeze frame | `02`: the code that stored frame 0 and every supported PID inside it |
| Extended data / snapshot | UDS `19 06` and `19 04` for a selected code |
| **Clear codes** | OBD `04` or UDS `14 FF FF FF`, one ECU at a time |
| Clear safety | One-shot token bound to the exact request and ECU, valid 60 s; refuses while the engine is running; UDS clear sent once, no auto-retry; codes re-read afterwards |
| Read-only policy | The session layer refuses every service except `01 02 03 06 07 09 0A`, `22`, `19` (report sub-functions only) and `3E 00/80`. Clears, writes, routines, IO control, security access, session control and resets are rejected before reaching the adapter |

## 3. Live data

| Feature | Notes |
|---|---|
| **56 standard PIDs** | Coolant, RPM, speed, MAF, MAP, throttle, pedal, fuel trims, O2 sensors, rail pressure, EGR, evap, catalyst/DPF temperatures, ambient, barometric, module voltage, odometer, run time, distance since clear… |
| PID support filtering | Only the PIDs the ECU reports as supported |
| Display modes | Value, gauge (min–max) or graph of the last 120 samples, per tile |
| Choose and order values | Pencil icon on the live screen; saved |
| **Recording** | One row per polling cycle, up to 100,000 rows, elapsed + UTC time + one column per value |
| **CSV export** | Share sheet, empty cells for missing readings |
| **Custom PIDs** | Request (`01`/`09`/`22`), formula over reply bytes, unit, gauge range, decimals, optional ECU request/response ID, data offset |
| Formula language | `+ - * / %`, bit ops, `0x` hex, `min max abs round floor signed8/16/32 bit` |
| Test read | Sends the request once and shows what came back |
| Validation | Request checked against the read-only policy; formula errors shown with position |
| Prerequisites | Ordered same-ECU support reads, evaluated per link epoch |

## 4. Bidirectional (Expert mode only)

| Feature | Notes |
|---|---|
| Expert mode | Off by default; must acknowledge the risks to enable |
| Raw terminal | AT/ST/VT as typed; baud-rate, memory, low-power, monitor and bootloader commands blocked; OBD/UDS must pass the read-only policy |
| **Actuator / IO control** | `2F <DID> 01/02/03` with start, stop and optional status poll |
| **Local routines** | `31 01` / `31 02` / `31 03` start, stop and results (KWP `31/32/33`) |
| Run safety | One-shot confirmation per run, bounded duration, live status, stop button, audit log |
| **Coding read** | Read current value with backup saved before any write |
| **Coding write** | Diff shown, confirmed, written, read back to verify |
| **Coding rollback** | Restore from a saved backup, himself confirmed |
| Write gating | Write grant covers only the exact request; audit entry per step |

## 5. Knowledge and reference

| Feature | Notes |
|---|---|
| **Vehicle catalog** | Make → Model → Generation → Variant, 27 makes / 66 models / 153 generations seeded (Audi, BMW, Mercedes-Benz, VW, Škoda, SEAT, Cupra, Opel-adjacent makes, plus Toyota, Ford, Nissan, Hyundai, Kia, Honda, Mazda, Mitsubishi, Peugeot, Citroën, Renault, Subaru, Suzuki, Volvo, Land Rover, Jeep, Chevrolet, Tata, Mahindra, Lexus) |
| Inheritance | ECUs, PIDs, fault text, tests, coding and connection settings inheritable at every level |
| Browse / search / favorites / recent | Yes |
| In-app add / edit / duplicate | Whole-file JSON and ECU-CSV preview, import and export |
| **Generic DTC text** | 203 SAE J2012 descriptions bundled |
| **Toyota / Lexus DTC text** | 11,989 recovered manufacturer meanings, make-scoped |
| **VW / Audi / Škoda / SEAT DTC text** | 38,826 / 38,826 / 37,579 / 37,579 recovered meanings — download and import (`dtc-packs` release) |
| VW Group MQB definitions | Tiguan AD1, Golf 5G, Passat B8, A3 8V, Octavia 5E, Leon 5F — 11 UDS ECUs each with real addresses; 3 coding recipes, 3 test templates |
| Your own overrides | Any code can be relabelled; your text always wins over bundled/make-scoped text |
| Data packs | JSON import/export with per-field validation errors (`pids[2].formula: …`), preview before import, annotated sample files |

## 6. Saving and sharing

| Feature | Notes |
|---|---|
| Saved vehicles (Room) | Keyed by VIN: protocol, every module, all identification, fault codes, raw request/response log of the last scan |
| Reconnect to a known VIN | Loads the saved profile |
| Browse a saved profile | Without a car connected |
| Delete a saved profile | With confirmation |
| **Diagnostic report** | Text file with adapter info, profile, setup, protocol attempts, vehicle, voltage, fault codes, modules, reconnects and all logs — via the share sheet |
| Copy / share / clear logs | Adapter log, raw scan log, module log, protocol attempts, terminal |

## 7. Data recovered from your brand archives

| Feature | Notes |
|---|---|
| Brand recon | Any of the 73 archives inspected on GitHub runners; support matrix, member list, library hashes |
| Text recovery | Address-free key discovery; VW Group all four brands extract (4.8M records) |
| DTC packs | Built and published as downloadable, app-importable JSON |
| **Not yet available** | Recovered live-data formulas and labels are decoded but not wired into the app: which ECU/request each value belongs to is unproven, so nothing is enabled |

---

## Honest gaps

- **No real-car test evidence in this repository.** Everything is validated synthetically.
- **Enhanced manufacturer live data is not enabled.** Recovered labels and formulas are inert text.
- **Older VAG cars (pre-MQB, KWP1281/TP2.0)** only have generic OBD today.
- **No iOS, no cloud sync, no accounts** — single-user by design.
- **Debug-signed APK.** Personal use; not a store build.
