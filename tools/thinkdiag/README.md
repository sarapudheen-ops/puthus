# Toyota database recovery tools

Source conversion and owner-authorized offline semantic comparison for this repository's [`assets-v1/TOYOTA.zip`](https://github.com/sarapudheen-ops/puthus/releases/tag/assets-v1) specification. `native_replay.py` now executes selected supplied AArch64 instructions in bounded emulated memory, with explicit file-array/memory/formatting fakes. There is no host `dlopen`, JNI, syscall, adapter or ECU bridge. No vendor code or EXPRESS is shipped/executed by Android. Opaque source bytes alone are **not** runnable diagnostic recipes. See [on-demand packs and current limits](../../docs/toyota-vehicle-packs.md), the [conversion report](../../docs/conversion-report.md) and the [passive DTC derivative](../../docs/toyota-data.md).

## Reproduce

Requires Python 3.10+ and GitHub CLI access to this repository's release. Work from the repository root. The root `fetch-assets.sh` helper downloads **all 73 brands** into ignored `/assets/`; it is not needed for this Toyota-only pipeline. The commands below fetch only Toyota into `.local/thinkdiag/`, keeping raw data separate from the app's tracked `app/src/main/assets/`.

```sh
mkdir -p .local/thinkdiag
# Skip the download when you already have the correct, non-empty archive.
gh release download assets-v1 --pattern TOYOTA.zip --dir .local/thinkdiag
python3 -m venv .local/thinkdiag/venv
PY=.local/thinkdiag/venv/bin/python
"$PY" -m pip install -r tools/thinkdiag/requirements.txt
"$PY" -m unittest discover -s tools/thinkdiag -p 'test_*.py' -v

"$PY" tools/thinkdiag/inspect_pack.py .local/thinkdiag/TOYOTA.zip --output .local/thinkdiag/report
"$PY" tools/thinkdiag/analyze_toyota.py .local/thinkdiag/TOYOTA.zip --output .local/thinkdiag/report
"$PY" tools/thinkdiag/static_readers.py .local/thinkdiag/TOYOTA.zip --output .local/thinkdiag/report
"$PY" tools/thinkdiag/decode_toyota.py .local/thinkdiag/TOYOTA.zip \
  --output .local/thinkdiag/decoded --report .local/thinkdiag/report
"$PY" tools/thinkdiag/extract_english.py .local/thinkdiag/TOYOTA.zip \
  --output .local/thinkdiag/english --report .local/thinkdiag/report
"$PY" tools/thinkdiag/sweep_toyota.py .local/thinkdiag/TOYOTA.zip \
  --decoded .local/thinkdiag/decoded --english .local/thinkdiag/english \
  --manifest .local/thinkdiag/report/decoded-manifest.json \
  --output .local/thinkdiag/sweep --report .local/thinkdiag/report
"$PY" tools/thinkdiag/convert_dtcs.py .local/thinkdiag/english/TCODE_EN.GAG.json \
  --output .local/thinkdiag/converted --report .local/thinkdiag/report
cmp .local/thinkdiag/converted/toyota-lexus-dtcs.json app/src/main/assets/datapacks/toyota-lexus-dtcs.json
```

The archive-specific drivers refuse a different SHA-256. Native virtual addresses and format constants are specific to Toyota V10.62, **not** a general ThinkDiag decoder. Generic readers validate bounded inputs, supported revisions, safe paths, directory checksums, record bounds, permutations and duplicate IDs; tests include corrupt/truncated files and traversal attempts. An unknown format fails closed. No passwords, activation material or runtime device credentials are requested.

The GitHub Actions workflow `.github/workflows/thinkdiag-data.yml` publishes recovered-data, native-comparison and conversion reports. Use it when local release-asset redirects are unavailable. A zero-byte failed download is not an archive. Large recovered/raw inputs and full audits stay outside Git in `.local/`. Android CI prepares on-demand range objects and **only the small pinned index**, alongside the unchanged passive DTC reference; the old full SQLite derivative is research-only. The offline native oracle is a required cache-miss preparation gate, and every cached report is checked against reviewed result hashes before staging the index.

## Bounded native differential checks

After decoding with the same pinned archive/manifest:

```sh
"$PY" tools/thinkdiag/toyota_native_oracle.py run .local/thinkdiag/TOYOTA.zip \
  --decoded .local/thinkdiag/decoded --manifest .local/thinkdiag/report/decoded-manifest.json \
  --output .local/thinkdiag/report/native-oracle.json
"$PY" tools/thinkdiag/toyota_native_oracle.py verify .local/thinkdiag/report/native-oracle.json
```

`native_replay.py` accepts only bounded little-endian AArch64 ET_DYN images and supported relocations.
Unknown external calls, memory faults and instruction/time-budget exhaustion abort. Constructors are
skipped; host native code, files, JNI, syscalls, sockets and adapters are not exposed. Decoded-byte-array
file APIs, no online overrides and limited libc/string/printf behavior are explicit experiment fakes.
There is no physical validation. The report pins exact library identities, counts and result digests;
missing/partial/retyped reports fail. `schema/native-oracle-v1.json` is the reviewed reference, not
vendor code or a device-test claim.

Current coverage: FILE all 1,308 entries and all regional 1,186 fault / 1,184 clear blocks (180,316 code
mappings); COMM all 8,775 regional frame records and 34,671 default/declared-fault template lookups;
DTCF 70,144 state/mask + 2,112 assembly vectors, all 27,035 exact TCODE pairs, 3,584 numeric-display
vectors and 196,608 exact-key byte-merge calls; P5 49,152 core record-projection vectors; CALC 65,792 unsigned-byte/unsigned-word numeric vectors
for **two** expression forms and eight full-script edges. IdSearch/English-text/libc formatting remain
declared shims, not native search/decryption/libc proof. Kotlin independently produces the DTCF state,
assembly, numeric-display and byte-merge result hashes. General CALC/control flow, transport/init,
query conditions and complete source feature-to-runtime conversion remain unfinished; executable
recovered vehicle definitions remain zero.

`toyota_transport.py` separately ports the exact COMM transport-1/5 configuration call sequence and
source flags `81`/`33` short addressed packet branch. `toyota_native_transport.py` executes the actual
COMM consumers: **284 source entries, 568 configuration traces, 17,892 source packets, eight synthetic
configuration-branch probes and 2,016 native/Python/Kotlin short-packet contract vectors**. The source
baud is preserved (including the native 9,600-baud exception and binary32 argument conversion), pin
pairs require an explicit index, and full-selector/region/function misses do not select an alternative.
The alternate connector's shifted **four-byte** timing-word argument is retained; reducing it to a
three-byte slice is a tested regression. Payloads above 63 bytes reject instead of wrapping addressing
bits or silently substituting a different source packet flag. **Every adapter API is a trace-only fake.**
The three timing arguments keep their source API positions, not guessed P1–P4 names. ST/VT-equivalent
pin/line/timing configuration, SYSP fast/slow startup, replies, sessions, keepalive/exit, other native
transports and production emission remain incomplete. The projection never approves an ECU link.

`toyota_lifecycle.py` now adds exact single-template initial/keepalive/exit joins for that same closed
284-entry source class. The expanded oracle links real FILE → SYSP → COMM guest functions through
six source-identified pointer cells: **284 first-positive-entry, 568 keepalive-on/off and 284 exit
traces** match the independent projection. No Python callback supplies the frame lookup, packet
construction or configuration in this comparison. Five native library identities are now pinned.
Hardware APIs and the positive `C1` reply remain explicit fakes; failure/retry and transport-5 ISO
fallback are not ported or validated. Library-scoped Open*Dll hooks cannot override a sibling's
same-named export. Synthetic regression tests also execute cross-library guest linkage without a
callback in the supplier function.

In particular, keepalive is **not universally `3E`**: exact bindings are `0100` (42), `2100` (174),
`2102` (4), `21E1` (3), `3E` (41), `3E01` (20). Their literal native `SetLinkKeep` arguments are
`(wire, length, 3, 800)`; the 800 is not an app P3-derived default or a proven adapter scheduling unit.
Exit requests are source `82` (236), `3E` (42), or `3E01` (6). Native exit uses a variadic packet-pointer
API, **not** a pointer/length pair, then `usleep(500)`; original response bytes remain provenance,
not an invented enforced handshake. Source pulse argument pairs are `25/25` (78) and `35/15` (206).
These findings do not approve MIC3313 mapping, physical initialization or executable emission.

`toyota_dtc_labels.py` joins **179,770 distinct per-function keys** from the 180,316 mapping rows to
exact source pairs, with **zero missing keys** across EU/NA/JP. Repeated printed codes are not merged,
legacy codes are retained, and no other ECU's text is substituted. The English reader now retains GAG
index/text offsets and encoded lengths; source runs re-extract the labels from the pinned archive
rather than trusting mutable JSON. The layout compiler preserves the source-defined missing-key
numeric display only when explicitly supplied with its source text/provenance. Raw 32-bit keys remain
attached to every decoded record. PSA/P5/flash callers and P3/P4 kind-5 non-decoding workflow queries
are not mislabeled as ordinary P3/P4 reads. The separate P5 compiler requires the exact header shared by
all 395 P5 blocks and actual caller-supplied mask provenance. Native/Python/Kotlin core record digests
match; auxiliary P5 freeze/mask side tables are explicitly disabled in that isolated native comparison,
not validated. Global text-key comparison is independent of source-index/JSON iteration order, while
per-function output retains original mapping-row order.

`toyota_functions.py` preserves/round-trips every fault/clear field with exact offsets; `toyota_frames.py`
resolves request/mask/reply roles and source-order default bindings, including EU's `0112` versus JP/NA's
`0012` table tags. Conditional bindings are not unconditional fallbacks. No synthetic fixture address
is asserted to be a source Toyota address.

## Format evidence

`analyze_toyota.py` and `static_readers.py` statically examine ELF symbols, sections, constants and selected AArch64 instructions with pyelftools/Capstone. The decoders are independent Python implementations of the observed data-reader operations, with format constants read as **data** from SHA-pinned ELF segments:

### YZJM binary wrapper

- Three little-endian 16-bit-length/NUL ASCII strings (`YZJM`, make, version), followed by a little-endian 32-bit seed.
- Rotations, eight derived key words and a 32-byte format table at `libDEVICEID.so` VA `0xF88C` transform big-endian payload words. Zero/all-one sentinel words are preserved. See `yzjm.py` for the exact transformations and validations.
- Reader evidence: `fopenEx` `0x8828`, `freadEx` `0x8488`, `fseekEx` `0x83F4`, key derivation `0x8DC4`.
- `decode_toyota.py` removes this wrapper from top-level and nested BIN.ZIP tables and writes a SHA-256 manifest. It does not assign meanings to arbitrary table fields.

### GGP directory and LAH/WCX indexed text

- GGP directory size is LE32 at byte 7, record count byte 11, directory length `13 + 72 * count`; the last byte is the additive checksum. Each 72-byte entry has a 32-byte encrypted filename and LE32 offset/length at +64/+68.
- Filenames use **Rijndael with a 256-bit block and key**, not standard AES. The ASCII-hex format key is read from `libSTD.so` VA `0x423B3`.
- Only LAH/WCX revision **5.03** is implemented. Its encoded 256-byte substitution permutation starts at byte 7; a repeating 15-byte constant is read from STD VA `0x4C81C`. The secondary-key length (BE16) is at 267; the key starts at 269. After it, a five-byte encoded header gives the record count and ID size, followed by ID/encrypted-pointer index entries. Encoded record lengths include their two-byte length field.
- Reader evidence in `libSTD.so`: `GetGAGOffsetFromGGP` `0x219D8`, initializer `0x21B68`, `IdSearch` `0x20E0C`, `OpenFileIssueSearchID` `0x35710`, `GagDecrypt` `0x35E94`, `EncryptNumber` `0x36224`.
- `ggp.py` returns raw record bytes; `extract_english.py` writes all original member files plus ID-to-UTF-8 maps. It omits non-UTF-8 records from the JSON rather than guessing an encoding and reports their counts/examples. Their original member bytes remain available.
- **TCODE pairs contain a literal NUL byte (`00`) between the explicit code and text.** A check/log renderer may display NUL as `\^@`; splitting on that visible rendering produces no real pairs. The converter uses the actual NUL-delimited JSON data, not check-rendered samples, and rejects extra fields.

### Diagnostic table and menu evidence

`sweep_toyota.py` inventories every archive/nested file, all native exports and all recovered English libraries. It checks every decoded payload against its SHA-256 manifest, rather than extrapolating from one ECU. It emits a complete MENU graph, regional system/function indexes, per-ECU section records, shared-table schemas/strides, command/frame-reference audits and explicit whole-file/record exceptions. Reports/disassemblies stay research artifacts. `build_app_database.py` separately accounts for every decoded byte and every English record in the app's passive source database; it never converts unresolved records into executable catalog definitions.

`toyota_structure.py` implements bounded reader-backed layouts:

- SYSLIST: LE32 vehicle-key/offset pairs to an FFFFFFFF sentinel; blocks have an LE16 count and packed 37-byte rows. Function key is at row offset 0; filename text ID at 23; transport-label text ID at 28. Numeric/text namespaces collide, so other fields remain named by source offset until traced.
- FUNCDATA: stride byte 28 and seven-LE32 directory rows to an FFFFFFFF sentinel. File offsets stay file offsets, not CAN identifiers.
- ECU files: **hexadecimal** filename suffixes; LE32 section count followed by five-byte `(u8 slot, LE32 absolute offset)` entries. All spans validate before use.
- Typed table: LE16 schema tag, LE32 count, LE32 body size, body at +10, up to three padding bytes. **Directory slot, schema tag and filename suffix need not match.** This matters for P5 and revised EU tables. Row layouts remain table-specific.
- MENU: length-prefixed 38-byte node headers and 23-byte edges; title ID at node+8 and child targets at edge+19. Raw task/saved-parameter fields are retained, never executed or treated as vehicle keys without reader evidence.
- Shared command pool: six-byte `(signed LE32 blob-relative offset, u8 length, u8 opaque flag)` descriptors, one-based IDs, native 10000 bias. The strict reader refuses out-of-bounds data. The separate **audit** preserves malformed descriptors as errors, without padding missing bytes or returning an invalid byte sequence.
- Shared frame table: eight-byte `(u16 key, three u16 dictionary references)` rows, with references resolved by original field offset. Function-binding references at row+6 are cross-checked separately; their selector semantics are not guessed.

Native evidence includes FILE `GetVehicleInfoData`, `GetSystemListData`, `GetAllToyotaTblData`, `GetEcuTblData` and the DS/ACT/customization readers; COMM `GetCmdDataInfo`/`GetCommFrameInfo`/`GetFuncCommFrameInfo`; CALC `ResultCalcFromCalcID`; and DIAG's packed-menu readers. Full per-function reports include a decoded-byte count and are split losslessly. Display-limited legacy reports explicitly announce truncation.

### Semantic boundaries

The recovered source clearly extends beyond DTC text: vehicle/system-to-ECU links, frame/byte dictionaries, live-data/expression tables and dedicated coding/actuator/customization paths are present. This does **not** establish the complete vehicle → ECU → addressing/session → request/response → extraction/formula/unit chain needed for an app definition. Counts are often duplicated across regions and are not unique PIDs or confirmed vehicle coverage. Live-data labels and EXPRESS records alone do not prove usable formulas or request mappings. Owner-authorized offline expression/native evaluation is allowed for conversion and differential tests. Neither it nor source verification grants writes: executable app definitions still need the exact vehicle/ECU joins and existing guarded coding/actuator flows. Android never runs the supplied language or native libraries.

### Retrieve complete sweep evidence through the GitHub API

The workflow keeps the full reports in `toyota-data-inspection` and a readable **Toyota diagnostic sweep** check. Detailed text reports are compressed JSON/base64 in numbered **Toyota sweep evidence NNN** checks, with compressed and uncompressed SHA-256 hashes. This avoids lossy control-byte rendering and excessive individual check publications. A publication failure has a GitHub error annotation, and recovered-data/report uploads use `always()`.

When release/artifact download redirects are blocked, choose a **successful** sweep's commit SHA and restore its checks without any vendor execution:

```sh
REPO=sarapudheen-ops/puthus
SHA=86a0667b732d34e6cbcf859466717d991676fe9d  # or the reviewed successful commit
EVIDENCE=".local/thinkdiag/evidence-$SHA"
mkdir -p "$EVIDENCE/parts"
gh api "repos/$REPO/commits/$SHA/check-runs?per_page=100" --paginate \
  --jq '.check_runs[] | select(.name | startswith("Toyota sweep evidence ")) | .id' |
while read -r id; do
  gh api "repos/$REPO/check-runs/$id" --jq '.output.summary' > "$EVIDENCE/parts/$id.json"
done
python3 tools/thinkdiag/sweep_reports.py unpack "$EVIDENCE"/parts/*.json --output "$EVIDENCE/reports"
```

Use a fresh directory / one coherent run: missing, duplicate, inconsistent, corrupted or oversized parts are rejected. The bundle is non-executable report text, not a diagnostic import. Source-byte excerpts inside it remain research files outside Git and must be checked against their own declared size/SHA-256 before inspection.

## Complete app source library

After the decoding/English recovery commands above, build the audited app assets (not a catalog import):

```sh
"$PY" tools/thinkdiag/build_app_database.py .local/thinkdiag/TOYOTA.zip \
  --decoded .local/thinkdiag/decoded --english .local/thinkdiag/english \
  --manifest .local/thinkdiag/report/decoded-manifest.json \
  --output .local/toyota-app-assets/toyota-source
python3 tools/thinkdiag/verify_app_database.py --directory .local/toyota-app-assets/toyota-source
```

`build_app_database.py` retains every decoded byte, rehashes every stored payload, includes all English records (including 4,502 non-UTF-8 records), and validates the full census and reader-backed frame joins. Unknown schemas and the three invalid command descriptors remain explicit. A separate NUL-free `search_text` derivative handles old Android SQLite's NUL-terminating `LOWER()` without altering original text/raw bytes. The shared SQL schema is tested by Python and by the app's SQLite JDBC fixtures.

This full SQLite build is a legacy/research derivative, **not a current Gradle prerequisite or APK asset**. Android CI instead requires the verified `.local/vehicle-pack-assets/toyota-vehicle-packs/index.json`, builds the actual APK, verifies corpus exclusion, and runs the exact selected-path range/offline-cache tests on Android 24. See the [current delivery/build guide](../../docs/toyota-vehicle-packs.md). Raw archives, ELF files, source scripts and research directories are not copied into the APK.

## Conversion audit

`convert_dtcs.py` keeps only explicit SAE-shaped base/extended code pairs with a unique remaining description, normalizes whitespace, and excludes placeholder and generic-category text. It preserves extended-code suffixes and never resolves conflicts by frequency. The full `toyota-dtc-audit.json` maps every accepted code to source record IDs and lists all conflicting variants; it is a research artifact, not a bundled dataset. [`toyota-dtcs.provenance.json`](toyota-dtcs.provenance.json) pins the bundled derivative's hash, size, scope, counts and rules. CI regenerates and compares the derivative byte-for-byte; app unit tests also validate the real asset and scoped import/export.

### Manual catalog integration

The application now migrates the unchanged passive `dtcGroups` reference into a reusable catalog platform, retaining the original make restrictions and source once rather than copying 11,989 meanings into every generation. Catalog JSON exports preserve that platform and its restrictions; ECU CSV intentionally does not carry DTC text. A known profile make wins over a conflicting saved link or manual catalog choice for passive fault wording, but no reference lookup selects a vehicle, ECU list or connection profile. See the [catalog format/migration guide](../../docs/vehicle-catalog.md) and the catalog JVM migration/integration tests. This integration does not change the converter or bundled derivative's bytes/hash, and introduces no recovered diagnostic requests.

Tests establish format handling, reproducibility and application isolation, **not** correctness of vendor descriptions for every vehicle. No real-car/adapter validation has been performed.
