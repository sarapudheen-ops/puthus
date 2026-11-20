# Toyota vehicle packs: on-demand delivery and conversion status

**Work in progress, not completed enhanced Toyota support.** The full-source converter currently
produces **zero executable recovered definitions**. See [the conversion report](conversion-report.md)
for exact counts, source IDs, unknown denominators and disabling reasons. Source verification by the
user is accepted; translating the source's relationships into executable definitions is separate work.

## What goes in the APK

Only `assets/toyota-vehicle-packs/index.json` (at most 256 KiB), the source hash, and the existing small
manual catalog/templates/passive DTC reference. The full recovered source database, range container,
vendor ELFs and EXPRESS programs are **not** packaged. The old full SQLite source-library build is a
research derivative, no longer the Android delivery mechanism.

The owner's full-archive verification is recorded in
[`tools/thinkdiag/verification/toyota-verified.json`](../tools/thinkdiag/verification/toyota-verified.json).
It explicitly covers nested members and authorizes inspection, decoding and sandbox execution for
conversion work. It does not confer write authorization or physical-ECU proof. Actions checks the
complete archive's byte length/SHA against this attestation and all 1,410 decoded payloads, converts
and verifies the hash tree (including the exact attestation), and stages **only** the index. The
manifest participates in the conversion cache key. The APK verifier rejects a missing
or mismatched index, any `assets/toyota-source/` corpus, or a bundled Toyota container/vendor library.

## Manual path and system scope

Open **Vehicle catalog → Choose Toyota vehicle pack** (also available from **Data packs**).
Navigate **Make → Model → Generation → Variant**, review the exact source path, disconnect, then tap
**Select this variant and open systems**. Browsing/downloading do not select configuration. VINs and
saved profiles never select configuration, and missing pack data never enables a generic fallback.

The explicit picker persists the four name-only levels, recents and selection in one catalog document
write. Downloaded executable data is not copied into the 16 MiB user catalog. Existing user declarations,
platforms, favorites, imports and profile links are preserved. Hierarchy IDs survive restart; selecting
a system explicitly reloads only that path/system. Ordinary manual catalog behavior is unchanged.

Each system shows converted/unresolved PID, DTC, test, coding, ECU-info and special-function counts.
`unknown` is not zero. Disabled entries explain why and allow bounded inspection of source-record IDs.
The manual catalog now exposes **24,653 regional vehicle paths** (EU 13,941; NA 5,762; JP 4,950),
not just the four starting Innova choices. The source's model is preserved exactly. Its first detail
branch is shown as a **source generation/group**, and every remaining detail, the communication menu,
and region are preserved in the variant. Absent detail levels say “Not specified in source”; they are
not inferred chassis generations, fuels, engines or transmissions. Exact source-node IDs prevent
identical labels from collapsing two selections. The four reviewed Kijang Innova/TGN branches retain
their existing IDs and month boundaries; none establishes AN40/diesel applicability. Nine short,
non-vehicle manual tasks remain in the lossless source tree and accounting rather than being invented
as vehicles. Hierarchy coverage is not executable diagnostic coverage.

The diagnostic overlay is closed to the selected loaded system: no global PID list, another system's
ECUs, legacy actuator template or unrelated vehicle can fill an empty downloaded scope. Per-system live
choices are stored separately. Resolved features route into the existing live graphs/recording and guarded
actuator/coding controllers. Explicit per-ECU identification/fault definitions can now route KWP (K-line
or CAN) and UDS into the ECU workbench, including a separately confirmed, exact-group clear. Converted
ECUs must provide an explicit definition set—even when empty—so this path cannot substitute generic
UDS reads. See [the read-definition schema and safeguards](ecu-read-definitions.md). The real recovered
DTC/ECU-info actions remain disabled while their mappings are unconverted; freeze-frame and special-function
mappings are also unresolved. Enabling a screen is not semantic conversion.

## Offline native comparison gate

Both source inspection and cache-miss pack preparation now run `toyota_native_oracle.py` against the
original owner-verified archive and the **complete hash-checked decoded manifest**. Five individually
pinned supplied libraries execute only inside bounded AArch64 emulation. All 1,308 entry records,
1,186 regional fault blocks / 1,184 clear blocks / 180,316 code mappings, all 8,775 regional frame
records and 34,671 default/declared-fault template lookups, 70,144 DTC state/mask and 2,112 fault-record
vectors, all 27,035 exact TCODE pairs, 3,584 source numeric-display vectors, 196,608 exact-key byte-merge
calls, 49,152 P5 core record-projection vectors (not auxiliary P5 freeze/mask/query workflows), and
65,792 numeric vectors for two CALC forms are compared. The separate fast-KWP consumer port adds
284 source entries, 568 configuration traces, 17,892 source packets and 2,016 native/Python/Kotlin
short-packet contract vectors, with every adapter API explicitly faked. A separate closed lifecycle
projection additionally matches real linked FILE → SYSP → COMM execution for 284 first-positive-entry,
568 keepalive-on/off and 284 exit traces. It preserves all six exact keepalive request variants and
source-bound exit packets instead of substituting generic testerPresent/stop commands. This does not
establish ST/VT timing/pin mapping or a working diagnostic link and is not an executable vehicle pack.
The extended suite additionally pins 2,272 fast-entry negative/zero/retry/ISO-dispatch vectors,
2,272 direct ISO-consumer component vectors, and 1,032 failed-read reentry vectors. The latter
observe temporary FF context, Exit/Enter, restoration and one identical retry; the slow sender
and adapter remain explicit fake boundaries. Native zero-result success and ignored lifecycle
failures are not accepted as initialization/vehicle proof. These source control-flow comparisons
are not the missing MIC3313 lifecycle integration. All 179,770 distinct per-function
mapping keys have exact source labels; repeated printed codes are never suffix-matched. English pairs
are re-extracted from the pinned archive, with original GAG index/text offset provenance. The reviewed
counts/library/result hashes live in `tools/thinkdiag/schema/native-oracle-v1.json`.
Cached reports must match that reference before the index is staged. The report is an Actions artifact,
not an APK object or a runtime trust grant.

The Kotlin fault decoder has the same state/assembly result digests and explicit exact-key labels,
with strict atomic rejection of malformed replies and no global-description fallback. Physical KWP
`04` clearing and `12`/`13` reads retain their exact dialect and confirmation scope. These runtime/port
changes do **not** finish transport/init, general expressions, source query conditions/workflows,
ECU-info, coding, actuators or special-function conversion. The executable vehicle count remains zero.
No native-libc/file-decryption/adapter/physical proof is inferred from declared memory/file-array shims.

## Authenticated bounded objects, not a full download

`toyota-<container SHA-256>.bin` is a data-only range bundle. Every hierarchy/system object is independent
gzip-compressed JSON. Parent references contain the exact byte range, compressed hash and expansion size;
the root hash is pinned in the APK. Downloaded indexes and imported `verified` flags cannot become a trust
root. Ordinary imports, edits, and copies still mark writes/security unverified.

The loader requires HTTPS, HTTP **206**, the exact `Content-Range`, identity content encoding and allowlisted
GitHub release redirects. A host returning HTTP 200 is rejected **before reading the corpus**. Limits:
4 MiB compressed / 8 MiB JSON per object, 512 MiB per container, 128 MiB private content-addressed cache.
Objects are rehashed, fully validated and atomically installed under `noBackupFilesDir`; corruption,
cancellation or failure does not install partial data. Cached selected objects can reopen offline.

Push CI publishes content-addressed containers to the `toyota-vehicle-packs-v1` prerelease without
clobbering older hashes, then verifies scoped hierarchy/system delivery. Variant objects reference
the exact source leaf's system list; ECU/function metadata is shared rather than duplicated across
287,030 system records. Both Python and Kotlin reject references to a different source path, ECU or
function. Referencing metadata does not broaden applicability. The cache maintains a bounded LRU
inventory instead of rescanning/sorting its entire directory for every installed object. Android 24 smoke tests
use the actual APK's index, production HTTPS loader and private cache, and then reopen with network access
explicitly denied. Publication/network/device-test success must be checked on the current revision; a
successful format/fixture test alone is not proof that delivery is live.

### Observed delivery evidence and TLS boundary

For `059ff398`, push run [44371455414](https://github.com/sarapudheen-ops/puthus/actions/runs/44371455414)
published the immutable **466,298,841-byte** container
`toyota-f63ee61ba2b0117cd2e8dafee28284b7eb4fa814ba2946d86902d1acc30d0bc4.bin`
and passed the real hosted range checker. Both push and PR APK/lint builds passed; the PR run reported
**481 JVM tests, zero failures**. The device APK/index-exclusion test passed, but its network/cache test
failed at TLS chain validation (`Trust anchor for certification path not found`), before fetching any
object. That failed device run is not offline-cache or stock-Android-24 network proof.

CI now synchronizes only the disposable emulator's system CA store with the runner's existing trusted
CA bundle and records verified host issuers/fingerprints. It does **not** change the app's TLS configuration,
ship a CI/proxy CA, disable hostname/certificate verification, or trust a downloaded leaf.

For `7148ffdb`, [push run 44373983385](https://github.com/sarapudheen-ops/puthus/actions/runs/44373983385)
passed both actual-APK Android 24 tests (job `135053798575`, evidence check `135054693825`): the small-index /
corpus-exclusion check and the exact selected-path range download → private offline-cache check.
The device did not select a vehicle automatically and sent no ECU requests. PR Android run
[44373996151](https://github.com/sarapudheen-ops/puthus/actions/runs/44373996151) and full inspection / conversion run
[44373983393](https://github.com/sarapudheen-ops/puthus/actions/runs/44373983393) also passed.
The disposable emulator added 120 runner-trusted roots, preserving existing roots; the runner CA-bundle
SHA-256 was `185d96cba669be5e215d2b73c15e289930779717163f9d5c6ea3c0a55f2183cc`.
This is delivery/cache proof with current CAs, **not** proof that an untouched 2016 device CA store trusts
the current release service, and not proof of executable recovered Toyota diagnostics.

For `72c608e`, [push run 44489358832](https://github.com/sarapudheen-ops/puthus/actions/runs/44489358832)
again passed both actual-APK Android 24 tests (job `135443323437`, evidence check `135443940475`),
including exact selected-path ranges, private-cache reopening with network access denied, and no
automatic selection. The same disposable-emulator CA synchronization applied; no ECU traffic was sent.
[PR Android CI 44489366752](https://github.com/sarapudheen-ops/puthus/actions/runs/44489366752)
reported **500 JVM tests, zero failures/errors/skips**. These successful runs validate that specific
revision's build/delivery behavior, not later edits or executable recovered Toyota diagnostics.

## Build and reproduce

Heavy conversion is intended for GitHub Actions (`.github/actions/prepare-vehicle-packs`). Large generated
outputs stay in `.local/`, Actions cache/artifacts and release storage, never Git. To reproduce with Python
3.12, GitHub CLI, JDK 17 and Android SDK 35:

```bash
mkdir -p .local/toyota-conversion
gh release download assets-v1 --repo sarapudheen-ops/puthus --pattern TOYOTA.zip --dir .local/toyota-conversion
python3 -m pip install -r tools/thinkdiag/requirements.txt
python3 tools/thinkdiag/source_verification.py .local/toyota-conversion/TOYOTA.zip
python3 tools/thinkdiag/decode_toyota.py .local/toyota-conversion/TOYOTA.zip \
  --output .local/toyota-conversion/decoded --report .local/toyota-conversion/report
python3 tools/thinkdiag/extract_english.py .local/toyota-conversion/TOYOTA.zip \
  --output .local/toyota-conversion/english --report .local/toyota-conversion/report
python3 tools/thinkdiag/convert_vehicle_packs.py .local/toyota-conversion/TOYOTA.zip \
  --decoded .local/toyota-conversion/decoded --english .local/toyota-conversion/english \
  --manifest .local/toyota-conversion/report/decoded-manifest.json --output .local/toyota-vehicle-packs
python3 tools/thinkdiag/toyota_runtime_compile.py compile .local/toyota-conversion/TOYOTA.zip \
  --decoded .local/toyota-conversion/decoded \
  --manifest .local/toyota-conversion/report/decoded-manifest.json --output .local/toyota-vehicle-packs
python3 tools/thinkdiag/toyota_lifecycle_contracts.py compile .local/toyota-conversion/TOYOTA.zip \
  --decoded .local/toyota-conversion/decoded \
  --manifest .local/toyota-conversion/report/decoded-manifest.json --output .local/toyota-vehicle-packs
python3 tools/thinkdiag/toyota_lifecycle_contracts.py verify .local/toyota-vehicle-packs
python3 tools/thinkdiag/toyota_native_oracle.py run .local/toyota-conversion/TOYOTA.zip \
  --decoded .local/toyota-conversion/decoded \
  --manifest .local/toyota-conversion/report/decoded-manifest.json \
  --output .local/toyota-vehicle-packs/native-oracle.json
python3 tools/thinkdiag/toyota_native_oracle.py verify .local/toyota-vehicle-packs/native-oracle.json
python3 tools/thinkdiag/verify_vehicle_bundle.py --directory .local/toyota-vehicle-packs
mkdir -p .local/vehicle-pack-assets/toyota-vehicle-packs
cp .local/toyota-vehicle-packs/index.json .local/vehicle-pack-assets/toyota-vehicle-packs/index.json
python3 -m unittest discover -s tools/thinkdiag -p 'test_*.py' -v
./gradlew assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest
python3 tools/thinkdiag/verify_vehicle_bundle.py --directory .local/toyota-vehicle-packs \
  --apk app/build/outputs/apk/debug/app-debug.apk
# After publishing that exact container (fails closed if absent or range support is wrong):
python3 tools/thinkdiag/check_pack_delivery.py --directory .local/toyota-vehicle-packs
```

Python/zlib version can affect deterministic gzip bytes across toolchains. The locally generated index
and container must match each other; an APK must pin a container actually published under its hash.
No fabricated local seed/index is substituted when source preparation is missing.

## Execution safeguards do not change

Read-only by default. Writes still require Expert mode, an operation-specific one-time confirmation,
known requests, a backup/diff (coding), safety checks, read-back verification, rollback where supported,
cleanup and an audit log. KWP grants are scoped to **one ECU + one identifier per operation**. A fully
resolved item from this source may be source-verified/default-enabled, but verification never bypasses
those engine safeguards or provides physical-test evidence. The app never executes vendor code or
EXPRESS. Owner-authorized native replay inputs are confined to ignored research evidence for offline
semantic comparison, not Android assets, runtime downloads or normal imported packs.

## Exact compiled read artifacts (not vehicle-system data)

The mandatory ECMECT source-read sidecars are separate from the range-container executable
counts. They contain 30 live reads, 1,414 exact fault labels, four ordered source queries and
one 0904 calibration-ID definition, with `productionExecutableDefinitions: 0`. The compiler
regenerates twice and verifies a reviewed output pin. `test-artifacts` checks canonical round-trip
and 48 corrupt/truncated/out-of-scope cases on the actual generated files, also for caches.
The source review records all input hashes, including the detail table and canonical decoded
English dictionaries. No raw EXPRESS or vendor library executes on Android. A passing artifact
test cannot enable a system, grant writes, or turn calibration IDs into unique ECU serial proof.


### Source lifecycle regression artifact

Prepare now generates and fully pins `source-kwp-contracts.json` outside APK assets. Gradle
passes `toyota.lifecycle.contracts.file` to the JUnit source-API lifecycle/Session/workbench
regressions: 284 actual entry plans, 2,272 fast and 2,272 ISO traces, six keepalive bindings,
exact C1 checks and bounded same-entry DTC reentry. The driver in these tests is scripted;
there is **no approved MIC3313 mapping or enabled production definition**. See the latest
[conversion evidence and remaining acceptance gates](conversion-report.md#portable-lifecycle-and-session-integration-scripted-source-api-not-mic3313-approval).

### Supplemental native/firmware result boundary

A separate pinned public Mini image now consumes the real abstract-layer timing records and queued
pulse/PRIMARY transaction in bounded offline emulation. This is not MIC3313 firmware identification or
physical adapter approval. The source's `CommSubFunc_00000003` cell resolves to export `CommSubFunc0003`
(6402), whose firmware dispatch alias is 6109. Both native API paths preserve positive copied-byte
counts for partial/corrupt/wrong-ECU replies; the new bounded Kotlin answer-record codec and lifecycle
tests require independent complete C1 proof, never an ACK/count alone. Keepalive callback timing also shows
why a nominal 800-ms AT period cannot simply replace the source API. See the
[reproducible timing experiment](../tools/thinkdiag/README.md#supplemental-native-to-firmware-timing-and-result-experiment).
The saved report is test evidence only, not an APK asset or an enabled system.

The preceding lifecycle revision `d02481d` now has successful
[push Android 44720562551](https://github.com/sarapudheen-ops/puthus/actions/runs/44720562551),
[PR Android 44720576573](https://github.com/sarapudheen-ops/puthus/actions/runs/44720576573) and
[inspection 44720562676](https://github.com/sarapudheen-ops/puthus/actions/runs/44720562676).
It reports 591 JVM tests without failures and five Android 24 tests: two actual delivery/cache and three
synthetic consent-UI callbacks. This is **not** the outstanding manual-selection-to-executable-live/DTC/ECU
acceptance. Zero production executable definitions remain enabled.
