# Review: the `assets-v1` ThinkDiag brand archives vs. what this app can load

Personal-use project, no store distribution, no third-party users. This note is an assessment of
whether the 73 vendored brand archives can be turned into files the app reads from local storage, and
what I would actually build first.

## What I could inspect in this sandbox

The release **metadata** is reachable, the release **payload** is not: `gh release download` and the
Actions artifact endpoint both fail at `release-assets.githubusercontent.com` /
`productionresultssa9.blob.core.windows.net` with `SSL_ERROR_SYSCALL`. So I could not open a zip here.
Everything below about archive internals comes from the repository's own pinned, tested decoders and
reports (`tools/thinkdiag/`, `docs/toyota-*.md`, `docs/conversion-report.md`), not from a live run.

Re-size the problem from the release listing:

| | |
|---|---:|
| Brand archives | 73 |
| Total download | 5,387,898,317 B ≈ **5.39 GB** |
| Largest | `BMW.zip` 1.97 GB, `BENZ.zip` 818 MB |
| `TOYOTA.zip` (the only one worked through) | 71.8 MB |
| `DEMO.zip` / `EOBD2.zip` | 60.5 MB / 7.1 MB |

## 1. The zips are not encrypted — that is not the blocker

`docs/toyota-data.md`: *"The original outer ZIP is **not** password-encrypted. Its contained database
formats needed data-only decoding."*

What is actually going on is a three-layer format, and the repo already reverse-engineered and
implements all three, without running any vendor code:

| Layer | Format | Repo implementation |
|---|---|---|
| Outer | plain ZIP | `inspect_pack.py` (fully generic, no brand assumptions) |
| Payload | `YZJM` wrapper: 3 length-prefixed ASCII strings (`YZJM`, make, version) + LE32 seed, then 32-bit words rotated via 8 derived keys and a 32-byte table | `yzjm.py` — header parsing is brand-agnostic |
| Whole files | `GGP` directory with 32-byte filenames encrypted by **Rijndael-256** (256-bit block, *not* AES) | `ggp.py` |
| Text libraries | `LAH`/`WCX` revision 5.03 substitution permutation | `ggp.py`, `extract_english.py` |

So "decrypt it and make it readable" is already solved and proven — **for one brand, at one version.**

## 2. Why that does not automatically carry over to the other 72

Three concrete couplings in the current code:

1. **Key material is read out of the brand's own native library.** The 32-byte format table comes from
   `libDEVICEID.so` VA `0xF88C`; the Rijndael-256 key from `libSTD.so` VA `0x423B3`; the 15-byte
   constant for LAH/WCX from `libSTD.so` VA `0x4C81C`. Each brand ships its **own** `.so` set, and the
   symbol/constant layout can differ per brand *and* per version. Every brand needs its own static
   read of its own library to pin those constants.
2. **The archive driver fails closed on SHA-256.** `decode_toyota.py` and `build_app_database.py`
   refuse any archive whose hash is not `26c77a39…`. That is deliberate, and it means each new brand
   is a new pinned pipeline, new tests, new provenance record — not a flag flip.
3. **The semantic layers are brand-specific too.** `SYSLIST`/`FUNCDATA`/`SYSTEMENTER`/MENU layouts
   were established by disassembling `libTOYOTA_FILE.so`/`libCOMM.so`/`libCALC.so`/`libDIAG.so`.
   Another make will have the same *shape* of tables (the vendor SDK is shared) but different field
   roles, different strides, different selector rules.

Realistic cost: the Toyota work in this repo is many thousands of lines plus 500+ tests and dozens of
CI runs, and it is scoped to **one** 71.8 MB archive. Seventy-two more, one of them 27× larger.

## 3. The part even Toyota has not cracked: semantics

This is the finding that matters most, and it is already written down in this repo
(`docs/conversion-report.md`):

- 1,410 decoded files, 114,797,280 decoded payload bytes, 1,307 per-ECU files, 198,091 English
  records, 287,030 system records, 15,215 typed tables, 8,775 frame rows — **all recovered and browsable**.
- **Executable definitions produced: 0.**
- Only 4 literal source paths have a reviewed vehicle/variant normalization, and they are still
  `ecu_entry_untranslated` with 0 PIDs converted out of 1,015 candidates.

Getting *bytes* out of these archives is data recovery. Getting *"PID X on ECU Y of variant Z is
request `22 12 34`, reply window `62 12 34 …`, scaled by `(A*256+B)*0.1`, unit °C"* is a translation
problem, and the repo has explicitly refused to guess at it. No amount of decrypting closes that gap.

So if the goal is "the app should do enhanced (non-generic-OBD) diagnostics using vendor data", the
honest status is: **the bottleneck is not encryption, and not storage — it is semantic conversion, and
it is currently unsolved even for the one brand already fully decoded.**

## 4. Your plan (decrypt → copy to local storage → app loads) conflicts with the app as it stands

Current delivery design, per `docs/toyota-vehicle-packs.md` and the code:

- APK carries only `assets/toyota-vehicle-packs/index.json` (≤ 256 KiB). The full corpus is **not**
  packaged, and a verifier **rejects** an APK that contains `assets/toyota-source/`.
- Data is fetched **on demand over HTTPS with HTTP 206 range requests** from a content-addressed
  container (`toyota-<sha256>.bin`), max 4 MiB per object / 512 MiB per container, rehashed, then
  cached under `noBackupFilesDir`.
- `PackObjectStore` hard-rejects any container name not matching `toyota-[0-9a-f]{64}\.bin`, and the
  index must be `bod3-toyota-vehicle-index` v1 with `converterVersion` 1.0.
- The only user-facing "copy a file in" paths are `ActivityResultContracts.OpenDocument()` imports,
  capped at **16 MiB** (`CatalogCodec.MAX_BYTES`, `PackFiles.MAX_BYTES`).
- The old full-SQLite-in-APK path still exists (`SourceInstaller`) but is documented as
  **superseded**, and it too demands an exact size + SHA-256 manifest.

So "I drop the converted files on the phone and the app reads them" is **not** something the app does
today. It needs new code either way: a local-file/pack-directory source, read-only SQLite over a
large on-disk file (partial-page reads, not `readBytes()`), hash verification, and multi-brand naming
instead of the `toyota-` prefix. That is a normal, bounded feature — but it is a feature, not a
config change.

Size check for local storage with multi-GB sources: `BMW.zip` is 1.97 GB and Toyota's 71.8 MB of zip
expanded to 114.8 MB of decoded payload — expansion of 1.5–3× is typical. A BMW-scale corpus is
plausibly **4–6 GB of SQLite**. That must be a file-backed read-only DB with page-level access;
`SQLiteDatabase.openDatabase(FLAG_READONLY)` on external/SAF storage is unreliable, so realistically
it goes in app-private storage (copied once) — the `noBackupFilesDir` pattern the repo already uses.

## 5. What I would actually do, in order

**Step 0 — cut the scope to cars you own.** You said personal use. You do not need 73 brands and
5.39 GB; you need the 1–3 makes in your household. That is the single biggest reduction available and
it costs nothing. If it is a Toyota/Lexus, a large part of the work already exists in this repo.

**Step 1 — prove the pipeline on the cheap archives.** `EOBD2.zip` (7.1 MB) and `DEMO.zip` (60.5 MB)
are small, and EOBD2 is generic-emissions data — the most likely to convert cleanly into the app's
existing `bod3-datapack`/catalog formats. `inspect_pack.py` is already generic; run it on both first
to see container layout, member extensions, entropy and whether the `lib*.so` set matches Toyota's
naming. This tells us within one CI run how much of the format is shared across brands.

**Step 2 — ship the thing that is known to convert: DTC text.** Toyota produced **11,989 validated
passive code meanings** (4,538 base + 7,451 exact extended, 3,522 conflicts deliberately excluded).
That is real user value, needs **zero** wire-protocol semantics, and the format
(`bod3-datapack` v2 `dtcGroups`, make-scoped) already exists and is already integrated into fault
screens. Doing this per brand is a repeatable, low-risk win, and it is the one thing that scales
across all 73 archives without solving the hard problem.

**Step 3 — if you want identification data next**, ECU part/software numbers from `22 F1xx`-style
tables are the next most likely to be translatable, because the reply windows are self-describing
ASCII/hex with fixed prefixes. `docs/ecu-read-definitions.md` already defines the exact schema
(`request`, `responsePrefix`, `dataOffset`, `minBytes`/`maxBytes`, `sourceRecords`).

**Step 4 — only then consider live data.** This is where the repo is legitimately stuck
(`docs/toyota-live-prerequisites.md`), and it is where I would not promise anything.

**Step 5 — if local-file loading is a hard requirement** (offline, no GitHub), build it as a distinct
`PackSource` implementation beside `PackHttpSource`: same `PackRange`/hash validation, same cache,
just reading ranges from a user-picked file/document tree instead of HTTP. Add it behind the existing
verifier so a corrupt or truncated copy still fails closed. Keep it read-only.

## 6. Things worth flagging

- **PR #12 (`arena/223c8487-puthus`, DRAFT, "Add read-only ECU bring-up and portable evidence")** is
  already touching this area. Any new work should build on that branch's decisions rather than
  fork a second loader.
- **Keep the write restrictions.** The repo's read-only session policy, one-shot clear confirmations
  and Expert-mode gates are the reason this codebase is safe to point at a real car. Vendor data
  recovered from these archives includes coding/actuator/security paths; those should stay disabled
  until individually validated, personal use or not — a wrong coding write is not a bug you can undo
  from the app.
- **Storage discipline:** `.local/` and `/assets/` are gitignored; keep raw archives and decoded
  output there. Nothing multi-GB should ever enter Git or the APK.
- **CI is the right place to decrypt.** The heavy pipelines already run on `ubuntu-latest` with a
  20-minute timeout and publish artifacts. Do not try to do a 2 GB decode on the phone.

## Bottom line

The encryption is solvable and, for Toyota, already solved. The blocker for "the app gets vendor-grade
enhanced diagnostics" is semantic conversion, which is 0% done even for the fully decoded brand.
The realistic, high-value path is: narrow to your actual cars, convert **DTC text** first across those
brands, then ECU identification, and treat multi-GB live-data conversion as research, not a delivery
target. A local-file pack source is a reasonable small feature on top of that — but it delivers
already-converted data, it does not create it.
