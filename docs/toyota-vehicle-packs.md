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
