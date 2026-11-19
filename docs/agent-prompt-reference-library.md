# Prompt: make the ThinkDiag brand archives usable offline on the phone

Hand the block below to a coding agent that has this repository checked out **and working internet**
(so it can download release assets). It is written for the `puthus` / `com.bod3.app` repo.

---

## MISSION

This is a **personal-use, single-user, non-published** Android OBD app (`com.bod3.app`) for a working
mechanic. There is a GitHub release `assets-v1` on `sarapudheen-ops/puthus` containing **73 brand
archives** (`AUDI.zip`, `BENZ.zip`, `BMW.zip`, `TOYOTA.zip`, … **5.39 GB total**; `BMW.zip` alone is
1.97 GB). These are the vendor's own diagnostic data files, recovered/decrypted at rest by tooling
already in this repo. The owner wants the **complete content available offline on the phone**, loaded
from local storage, because it is far too large to bundle in the APK.

Deliver a **mechanic-usable offline reference + per-make fault-code text**, multi-brand, loadable from
local storage. Do **not** attempt to turn the recovered data into executable diagnostic requests —
that is a separate, unsolved problem (see "Hard boundaries").

Work in **phases with gates**. Stop at the end of each phase, report the exact numbers listed in
"Report back", and wait for approval before starting the next phase. Do not batch all phases into one
giant PR.

---

## WHAT ALREADY EXISTS (read before writing code — do not rebuild it)

Everything below is already implemented and tested. **Reuse it.**

**Decoders (Python, static, never execute vendor code):**
| File | Purpose |
|---|---|
| `tools/thinkdiag/inspect_pack.py` | Zip inspection. **Already brand-agnostic** — no Toyota assumptions. |
| `tools/thinkdiag/yzjm.py` | `YZJM` wrapper: 3 length-prefixed ASCII strings + LE32 seed, 8 derived key words, 32-byte format table. Header parse is brand-agnostic; the key table is not. |
| `tools/thinkdiag/ggp.py` | `GGP` directory (LE32 size at byte 7, count at byte 11, `13 + 72*count`, checksum last byte, 32-byte Rijndael-256 encrypted filenames) and `LAH`/`WCX` rev 5.03 indexed text. |
| `tools/thinkdiag/decode_toyota.py` | Drives YZJM unwrap → decoded payloads + SHA-256 manifest. **Hard-refuses any archive whose SHA-256 ≠ `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`.** |
| `tools/thinkdiag/extract_english.py` | GGP/LAH → all member files + ID→UTF-8 JSON maps, omitting non-UTF-8 rather than guessing. |
| `tools/thinkdiag/convert_dtcs.py` | `TCODE_EN.GAG.json` → `bod3-datapack` v2 `dtcGroups`. Keep-only rules: explicit SAE-shaped base/extended pairs, unique remaining description, normalized whitespace, excludes placeholder/generic text, **never resolves conflicts by frequency**. |
| `tools/thinkdiag/build_app_database.py` | Builds the full reference SQLite (`toyota-source.zip` + `manifest.json`). Schema is `tools/thinkdiag/schema/app_schema.sql`. |
| `tools/thinkdiag/verify_app_database.py` | Re-hashes and census-verifies that database. |
| `tools/thinkdiag/sweep_toyota.py`, `toyota_structure.py` | Menu / SYSLIST / FUNCDATA / SYSTEMENTRID / frame / command-pool structure recovery. |
| `tools/thinkdiag/convert_vehicle_packs.py`, `vehicle_bundle.py`, `verify_vehicle_bundle.py` | On-demand per-system packs (currently **0 executable definitions**; keep it that way). |

**App (Kotlin) — already present but currently Toyota-only and mostly unwired:**
| File | Note |
|---|---|
| `app/src/main/java/com/bod3/app/source/SourceBrowser.kt` | Bounded, read-only queries over the reference DB. `validateMetadata()` hard-requires `format == "bod3-toyota-source"`, `sourceSha256 == SourceManifest.SOURCE_SHA256`, `executableDefinitions == 0`, `manualSelectionOnly == true`. |
| `app/src/main/java/com/bod3/app/source/SourceModels.kt` | `SourceLocation`, `SourceRequest` (PAGE_SIZE 40, BYTE_PAGE 512, TEXT_PAGE 2048, MAX_QUERY 120). `SourceManifest.DATABASE_NAME = "toyota-source.db"`. |
| `app/src/main/java/com/bod3/app/source/SourceInstaller.kt` | Streams a zip **from APK assets**, exact size + SHA-256 + single-entry checks, atomic rename, installs into `noBackupFilesDir/<sha>.db`, prunes only content-addressed old versions. **Read this carefully — you will need a second install path.** |
| `app/src/main/java/com/bod3/app/android/ToyotaSourceLibrary.kt` | Wires `SourceInstaller` to `noBackupFilesDir/toyota-source` and `assets/toyota-source/...`. |
| `app/src/main/java/com/bod3/app/ui/screens/ToyotaSourceScreen.kt`, `ToyotaSourceViewModel.kt` | Existing browser UI. `Bod3App.kt` already has `Screen.TOYOTA_SOURCE`. |
| `app/src/main/java/com/bod3/app/datapack/DataPackCodec.kt`, `DataPackStore.kt` | `bod3-datapack` v2 with make-scoped `dtcGroups`. Already renders in fault screens. |
| `app/src/main/java/com/bod3/app/packs/PackFiles.kt` | Import detection. `MAX_BYTES = 16 MiB` — this is the current ceiling for any file the user picks. |
| `app/src/main/java/com/bod3/app/vehiclepack/PackBundle.kt`, `PackHttp.kt` | On-demand range loader. `PackObjectStore` **hard-rejects** container names not matching `toyota-[0-9a-f]{64}\.bin`; `VehiclePackCatalog` requires `format == "bod3-toyota-vehicle-index"`. |
| `app/build.gradle.kts` | `assets.srcDir(".local/vehicle-pack-assets")`; `preBuild` runs `verify_vehicle_bundle.py --index-only`. Only **one** file (`index.json`) is allowed into assets there. |

**Docs that define the existing hard lines — obey them:** `docs/toyota-data.md`,
`docs/conversion-report.md`, `docs/ecu-read-definitions.md`, `docs/toyota-vehicle-packs.md`,
`docs/toyota-app-library.md`, `docs/vehicle-catalog.md`, `tools/thinkdiag/README.md`.

**CI:** `.github/workflows/thinkdiag-data.yml` (triggers on `workflow_dispatch`, push to `arena/**`
touching `tools/thinkdiag/**`; 20-minute timeout) and `.github/actions/prepare-vehicle-packs/action.yml`
(downloads the release asset with `gh`, decodes, verifies, stages only the index). **Do heavy work in
Actions, never on the phone.**

**Open work to build on, not duplicate:** PR **#12** (`arena/223c8487-puthus`, DRAFT, "Add read-only
ECU bring-up and portable evidence") is already in this area. Rebase on it or coordinate with it; do
not create a second parallel loader.

---

## HARD BOUNDARIES (violating these fails review)

1. **Never execute anything from the archives.** No `dlopen`, no running vendor `.so`, no evaluating
   recovered `EXPRESS` records, no invoking vendor binaries. Static ELF/format reading only
   (`pyelftools` / `capstone`, as `static_readers.py` does).
2. **Never invent diagnostic semantics.** Do not produce CAN IDs, request bytes, response masks,
   formulas, scaling factors, units, session/security sequences or coding/actuator recipes from
   positional guesses. `docs/conversion-report.md` documents why the repo refuses this. Executable
   definitions must stay at **0** for recovered data. A recovered *label* is not a formula.
3. **Fail closed.** Every artifact is pinned by SHA-256 and verified on build, install and use.
   Truncated, corrupt, mismatched or missing data must error — never degrade to an empty or partial
   "success". No generic fallback ECU, no substituted vehicle, no silent import.
4. **Nothing large enters Git.** `/assets/`, `.local/` are already gitignored. Raw archives, decoded
   corpora, ELFs and multi-GB databases stay in `.local/`, Actions cache/artifacts and release storage.
   Nothing multi-GB at any point in the Git history.
5. **Do not weaken the app's safety model.** Read-only-by-default session policy, one-shot clear
   confirmations, Expert-mode gating, write-grant/backup/verify flows must remain unchanged. Recovered
   coding/actuator/security material must never become enabled.
6. **Do not break existing behavior.** All current tests must keep passing; existing Toyota DTC
   reference bytes/hash must be unchanged unless a phase explicitly says otherwise.
7. Keep the app **offline-first and single-user**. No accounts, no telemetry, no network requirement
   for the reference library.

---

## PHASE 1 — Reconnaissance across all 73 archives (no app changes)

**Goal:** determine how much of the Toyota decode pipeline is genuinely shared, so later phases are
planned on evidence instead of assumption.

Build `tools/thinkdiag/brand_recon.py` (bounded, read-only, `argparse`, follows the style and safety
comments of `inspect_pack.py`):

- Input: a directory of the 73 zips, or `--release assets-v1` to fetch each with `gh release download`
  one at a time (never all at once — 5.39 GB).
- Per archive, emit: SHA-256, size, entry count, uncompressed bytes, extension histogram, count of
  ZIP-encrypted entries, and for a bounded prefix of each member: detected type, entropy, whether it
  starts with `YZJM`, and whether a nested `BIN.ZIP` exists (entry count + region prefixes).
- For each `.so` member: file name, SHA-256, machine arch, whether it is little-endian AArch64, and
  whether these Toyota-pinned data locations are even *addressable* (do **not** assume the constants
  match — just record section/symbol presence): `libDEVICEID.so`, `libSTD.so`, and the brand's
  `lib<BRAND>_FILE.so`. Record `.dynsym` names only (bounded count) — useful to see if
  `GetVehicleInfoData` / `GetSystemListData` / `GetGagOffsetFromGGP` / `GagDecrypt` exist per brand.
- **Effort ladder:** fully inspect `EOBD2.zip` (7.1 MB) and `DEMO.zip` (60.5 MB); do deep member
  inspection on archives under ~150 MB; for `BMW.zip` (1.97 GB) / `BENZ.zip` (818 MB) read the ZIP
  central directory and member headers only — never decompress the whole thing in one process.
- Output `.local/brand-recon/summary.json` + `summary.md` + one page per brand.
- If (and only if) `libSTD.so` for another brand yields the same 32-byte format table at the same
  address as Toyota **and** the same Rijndael key, record that as a *candidate* shared format — do not
  act on it yet.

Validation: unit tests with synthetic zips (truncated, encrypted flag set, path traversal, oversized,
zero-byte) asserting fail-closed. Add `tools/thinkdiag/test_brand_recon.py`.

**Gate:** report the table. Do not modify the app.

---

## PHASE 2 — Per-brand pipeline made data-driven (still no app changes)

**Goal:** adding a brand becomes *data*, not code.

1. Introduce `tools/thinkdiag/brands/<BRAND>.json` pins:
   `{ "brand": "...", "archiveSha256": "...", "version": "...", "yzjmTableSource": {...},
   "textLibraries": [...], "regions": [...], "notes": [...] }`
   with the constant locations read statically from **that brand's own** `.so`, recorded with the
   library SHA-256 they came from. Unknown/unverifiable constants → the brand is `unsupported` with an
   explicit reason, not a guess.
2. Refactor the pinned-SHA gate out of `decode_toyota.py` / `build_app_database.py` into a shared
   `brand_pin.py` that fails closed on any unpinned or mismatched archive. **Toyota's existing pin and
   output must remain byte-identical** — prove it with `cmp` against the currently bundled assets.
3. Generalize `format`/version strings away from `bod3-toyota-source`: add `brand` + `archiveSha256`
   to the DB metadata, keeping `format == "bod3-brand-source"` v2 with `executableDefinitions: 0`
   and `manualSelectionOnly: true`. Provide a reader that still accepts the existing v1 Toyota DB
   during migration (see Phase 4).
4. Generalize `convert_dtcs.py` to a brand argument producing `bod3-datapack` v2 `dtcGroups` per make,
   from that brand's `TCODE_EN.GAG` (name may differ per brand — resolve via the brand pin, fail
   closed if absent).
5. Run for **`DEMO` + `EOBD2` + `TOYOTA`** only. Emit conversion counts per brand and a diff report
   proving Toyota's DTC pack is unchanged byte-for-byte.

Validation: extend the existing test suite; add adversarial cases (wrong hash, missing member, wrong
library hash, unpinned brand). `python3 -m unittest discover -s tools/thinkdiag -p 'test_*.py'` green.

**Gate:** report per-brand counts, Toyota byte-identity proof, and which of the 73 are
supported/unsupported + why.

---

## PHASE 3 — Local-storage reference library (the mechanic-facing value)

**Goal:** the mechanic can put **converted reference databases** on the phone's storage and the app
opens them read-only. No network needed.

### 3a. Two artifacts per brand (keep phone storage sane)

Producing a single database containing every decoded byte for `BMW` would plausibly be 4–6 GB. Split:

- `reference-<brand>.db` — **the useful part, aimed at what a mechanic actually looks things up in:**
  all indexed text libraries (fault-code text, component/system names, live-data labels and units,
  ADAS/manual text), the full manual menu tree with exact source paths, regional vehicle → system →
  ECU file joins, ECU/shared table row inventories and schemas, function/frame/command-pool references.
  Target: tens of MiB per brand.
- `raw-<brand>.zip` — **optional** every-decoded-byte payload, byte-identical, hash-manifested. Off by
  default; the user chooses whether a brand's raw bytes are worth the space.

Reuse `tools/thinkdiag/schema/app_schema.sql` as the base, add `brand`/`archiveSha256` to `metadata`,
and add the indices needed for **fast substring search** over `text_record.search_text` (the NUL-free
derivative already exists for Android 24's `LOWER()`). Verify with `verify_app_database.py`.

### 3b. New install path: from user-chosen local files

Add `LocalSourceInstaller` **beside** `SourceInstaller` — same verification rigor, different origin:

- Source = a real file path. **Use `Context.getExternalFilesDir(...)`** as the recommended location:
  it has a real filesystem path (so no SAF stream gymnastics), needs no storage permission on any
  supported API level, is reachable by the user over USB/MTP and most file managers, and has no small
  size cap. Document this path prominently in the UI ("copy `reference-bmw.db` here").
- Also accept `ActivityResultContracts.OpenDocument()` for one-off imports, **but** verify then copy
  into app-private/external-files storage before opening — do not rely on opening SQLite directly on a
  SAF URI (unreliable across providers/OEMs).
- Verify: exact size + SHA-256 + `PRAGMA user_version` + metadata brand/hash +
  `executableDefinitions == 0`, all **before** the file is used. Atomic install (write `.partial`,
  `fd.sync()`, rename). Never replace a working database with a failed one.
- Open **read-only** with page-level access (`SQLiteDatabase.openDatabase(path, null,
  OPEN_READONLY)` on a real path). Do **not** `readBytes()` a multi-hundred-MB file. Bound every query
  with the existing `SourceRequest` page sizes.
- Raise/parameterize the 16 MiB limits where a *reference database* is concerned — but keep the 16 MiB
  cap for the user's editable catalog/pack documents (`DataPackStore`, `CatalogCodec`), which are
  written back and rewritten as JSON. Different limits for different jobs; document why.
- Show free space before install and fail with a clear message; allow uninstall/delete per brand;
  never auto-delete a database the user installed.

### 3c. Multi-brand browser

Generalize `SourceBrowser` / `ToyotaSourceLibrary` / `ToyotaSourceScreen` to N brands:
brand list → overview → menu / vehicles & systems / files / text libraries / frames & commands.
Keep every existing safety comment intact (menu leaves are not joined to vehicle keys; FUNCDATA
offsets are not CAN IDs; numeric namespaces are not joined).

**Prize feature for a mechanic:** one **cross-brand text search** — "type `P0420`, or `oxygen sensor`,
or a component name" and get the hit across every installed brand's text libraries with
library/record/brand provenance, plus a jump into the fault screen's description. This is the single
most useful thing the archives can give a working mechanic offline, and it needs **zero** wire-protocol
semantics.

Also surface, as **reference only**, the DTC text for a code the app just read from a car (via the
existing make-scoped `dtcGroups` path — Phase 2 output).

Validation: JVM tests for the new installer (bad hash, truncated, wrong size, wrong schema, wrong
brand, disk-full, cancel mid-write, path traversal, symlink) and for search paging/bounds. Extend the
instrumentation tests. If you can, add an Android 24 smoke test that installs from a real file path,
opens offline with network denied, and runs one search.

**Gate:** report DB sizes per brand, install timings, and the storage math for a realistic set.

---

## PHASE 4 — App-facing integration and migration

- Migrate the existing bundled/installed `toyota-source.db` (v1 metadata) to the multi-brand layout
  **without data loss**; keep reading v1 if migration is not run.
- Keep `app/build.gradle.kts` asset staging to the small `index.json` only. **Do not** start bundling
  reference DBs in the APK — that is the thing we are avoiding. If you add a bundled file, it must stay
  small and must be justified.
- Wire per-brand DTC packs into the fault screens (make-scoped, never global). Vendor text must never
  override the user's own notes; conflicts stay excluded, never resolved by frequency.
- Make the branding/UI honest: label reference data as **vendor source data, unverified for any
  specific vehicle**; do not present recovered material as an app-authored diagnostic conclusion.
- Update docs: a new `docs/brand-reference-library.md` (what ships, how to convert, how to copy files
  to the phone, storage expectations, what the data does and does not prove) and update the existing
  Toyota docs' "superseded" notes so they point at the multi-brand path.

**Gate:** full green build (`./gradlew assembleDebug assembleDebugAndroidTest lintDebug
testDebugUnitTest`), all Python tests green, install/search verified offline, migration verified.

---

## REPORT BACK (what I will review)

At the end of each phase, give me:

1. **Exact counts and sizes** — per brand: archive SHA-256, decoded payload bytes, text records, menu
   nodes, systems, tables, resulting `reference-*.db` size and SHA-256.
2. **Support matrix** — for all 73 brands: `supported / unsupported`, and for unsupported the exact
   reason (no `YZJM`, unknown table/key location, missing text library, archive hash unpinned…).
3. **Proofs, not claims** — commands run + their output for: Toyota byte-identity (`cmp`), database
   verify, full test suite, APK build, offline install/search.
4. **What you deliberately did not do** and why (any semantic item you declined to enable).
5. **Storage math** for the brand set I actually care about, with a warning if any single brand is
   multi-GB.
6. **Anything that contradicts the assumptions in this prompt.** If a brand's format genuinely differs
   from Toyota's, say so plainly instead of forcing it through the Toyota path.

## SUCCESS CRITERIA

- I can copy one brand's reference database to the phone and browse/search its complete fault-code text,
  component names, system→ECU joins and manual tree **offline**.
- Fault codes read from a car show vendor text for the correct make, offline, with the source shown.
- Adding a 74th brand later is a JSON pin + a CI run, not a code change.
- Nothing executable was enabled; nothing large entered Git; the app's safety model is unchanged.
- Zero silent failures: corrupt/wrong/missing data always errors visibly.
