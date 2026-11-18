# Complete offline Toyota source library

## What is integrated

Open **Data packs → Browse recovered Toyota data**, or **Vehicle catalog → Recovered Toyota diagnostic data**. Toyota/Lexus model pages can explicitly search that model in the manual source menu. This never selects a vehicle, connects an adapter or changes a catalog definition.

The APK contains a compressed, indexed SQLite derivative of the **entire recovered sweep corpus**, not just the passive DTC subset:

| Dataset | Included |
|---|---:|
| Decoded source files, every byte including opaque data/padding | 1,410 / 114,797,280 bytes |
| Per-ECU directories | 1,307 |
| Typed ECU/shared tables | 15,215 (15,130 ECU + 85 shared) |
| Menu nodes / saved-parameter edges | 149,731 / 149,730 |
| Regional vehicle keys / packed system rows | 10,144 / 287,030 |
| FUNCDATA directory entries, with complete variable payloads | 1,275 |
| Eight English libraries, all indexed records | 198,091 |
| Non-UTF-8 records, retained as exact raw bytes | 4,502 |
| Byte dictionary descriptors, including the three invalid terminal entries | 9,774 |
| Frame rows / function-frame rows | 8,775 / 36,997 |
| Original/nested archive inventory (metadata, not executable payloads) | 1,604 |

The browser provides:

- Manual source hierarchy and cross-path model/chassis/engine/date search. Automatic/VIN-labelled source branches are separately available for **inspection only**, never as automatic-selection logic.
- Regional **vehicle key → system → ECU file / FUNCDATA directory** navigation. Missing FLASH references stay visible. Actual filenames are resolved case-insensitively, not manufactured from numeric keys.
- Every source file's parser status, SHA-256, section slots, independent embedded schema tags, row counts/strides, raw rows and complete byte paging.
- Search across every English record, including text **after embedded NULs**. Original NULs/trailing bytes are preserved. Non-UTF-8 data is shown in hex, not guessed. EXPRESS records are inert text and never evaluated.
- Reader-backed **function/frame → frame → byte-dictionary** links, applying only the proven selector/bias rules. Unproven field roles and numeric namespaces remain separate.
- Explicit opaque/damaged-data views. Descriptor 3,258 has no padded/fabricated byte sequence. All eight unresolved whole-file formats remain inspectable.
- Select/copy text and share the **displayed evidence page** with source provenance. Page export is not an operational data-pack export.

The initial pack was 35,819,360 compressed bytes and 250,707,968 database bytes (about 239 MiB). Exact generated size/hash depends on the processor fingerprint and SQLite version; the bundled `manifest.json` is authoritative. Storage is allocated on first opening the library, on an IO dispatcher, not at app startup. Installation is streamed, checksum/size/entry-bound checked, atomically renamed, versioned by database hash, and stored in `noBackupFilesDir`. Reopening uses read-only SQLite, bounded pages and small substrings, not a giant catalog/JSON document. The UI reports failures and retries; it never treats a missing/corrupt pack as a successful empty import.

## Important operational boundary

**Complete source-data integration is not complete enhanced Toyota diagnostic support.** This implementation intentionally does not turn unresolved source bytes into executable requests.

The existing **11,989-meaning manufacturer-scoped passive DTC reference** remains integrated into real fault displays, unchanged in scope/hash. Existing OBD, configured ECU/PID, coding and actuator features are preserved. The source browser's types have no conversion to `EcuDefinition`, `CustomPid`, coding/test recipes or adapter/session requests.

Still required before enabling enhanced reads for a source path: reader-proven **manual menu leaf → vehicle key → system/ECU → transport/CAN addressing/session → request/frame → response validation → extraction/unit/formula**. FUNCDATA pointers are file offsets, not CAN IDs. Shared frame-field roles are not assumed to be request/mask/response solely from their bytes. Equal numbers across text, function, formula and dictionary namespaces do not prove a relationship.

Enhanced KWP remains outside the current ECU workbench. Coding/actuators/security require the existing verified-semantic conversion and Expert/confirmation/whitelist/backup → write → verify flows. This milestone enables **no recovered write or security algorithm** and claims no physical adapter/vehicle validation. The source contains diagnostic data; the remaining limitation is semantic conversion and validation, not absence of source data.

## Build and reproduce

Raw vendor archives/ELFs/GAGs and large generated derivatives stay **out of Git**. Android CI prepares the complete data library from the repository's pinned release before building **every APK**, caches only the generated data assets, then verifies the APK actually contains the same full library. The inspection workflow independently builds/publishes `toyota-app-library` (14-day artifact). There is no optional/missing-library production build or runtime download.

With JDK 17, Android SDK 35, Python 3.10+ and GitHub CLI installed, from the repository root:

```bash
mkdir -p .local/thinkdiag
# Download only Toyota, not all 73 release archives.
gh release download assets-v1 --repo sarapudheen-ops/puthus --pattern TOYOTA.zip --dir .local/thinkdiag
python3 -m venv .local/toyota-venv
.local/toyota-venv/bin/python -m pip install -r tools/thinkdiag/requirements.txt
PY=.local/toyota-venv/bin/python
"$PY" tools/thinkdiag/decode_toyota.py .local/thinkdiag/TOYOTA.zip \
  --output .local/thinkdiag/decoded --report .local/thinkdiag/report
"$PY" tools/thinkdiag/extract_english.py .local/thinkdiag/TOYOTA.zip \
  --output .local/thinkdiag/english --report .local/thinkdiag/report
"$PY" tools/thinkdiag/build_app_database.py .local/thinkdiag/TOYOTA.zip \
  --decoded .local/thinkdiag/decoded --english .local/thinkdiag/english \
  --manifest .local/thinkdiag/report/decoded-manifest.json \
  --output .local/toyota-app-assets/toyota-source
python3 tools/thinkdiag/verify_app_database.py --directory .local/toyota-app-assets/toyota-source
./gradlew assembleDebug lintDebug testDebugUnitTest
python3 tools/thinkdiag/verify_app_database.py --directory .local/toyota-app-assets/toyota-source \
  --apk app/build/outputs/apk/debug/app-debug.apk
```

The source SHA-256 is pinned to `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`. Builders fail on a changed archive, decoded hash mismatch, incomplete census, schema error or unresolved reader-backed frame join. A processor/schema fingerprint prevents silently reusing stale generated assets. Regenerate after changes to recovery/schema/build processors. Database/ZIP bytes are deterministic for the same input, processor and SQLite/zlib toolchain; cross-toolchain byte identity is not assumed.

The shared SQL schema is `tools/thinkdiag/schema/app_schema.sql`. Python tests exercise generation, full byte preservation, UTF-8/opaque records, reference rejection and actual APK contents. JVM tests execute the real app query layer through SQLite JDBC against that same schema; installer/manifest tests cover corruption, truncation, bounds, failed-upgrade preservation, cancellation, cache repair and traversal/extra-entry rejection. Existing catalog/import/migration/diagnostic safety tests remain in the full suite. Push CI also installs the actual complete APK on an **Android 24 emulator**, exercising real asset streams, private installation, Android SQLite/CursorWindow, full corpus counts, NUL-aware search, opaque bytes, system/file navigation, frame links, invalid descriptors and read-only enforcement. These tests should also be run on current physical Android devices; neither emulator nor fixture tests are car/adapter validation.

See [the sweep evidence](toyota-diagnostic-sweep.md), [passive DTC semantics](toyota-data.md) and [recovery tools](../tools/thinkdiag/README.md) for format provenance and open semantics.
