# Toyota diagnostic-source re-sweep

## Conclusion

**The Toyota folder contains substantially more than a DTC dictionary.** The renewed, data-only sweep recovers a large vehicle menu, regional vehicle/system/ECU relationships, shared diagnostic byte dictionaries, communication-frame references, live-data tables and expressions, and actuator/customization reader paths. The earlier passive-only **app integration** was not evidence that these source families were absent.

This report records the evidence/recovery milestone, **not a claim of complete Toyota support in bod3**. Structural validity is not the same as correct diagnostic semantics, and source presence is not proof of support on every model/year/ECU. At that milestone the app's only recovered Toyota dataset was the manufacturer-scoped **11,989-meaning passive DTC reference**. The subsequent [offline source-library milestone](toyota-app-library.md) indexed all recovered decoded payloads and English records for research. The current [on-demand delivery path](toyota-vehicle-packs.md) replaces full-corpus APK assets; [conversion accounting](conversion-report.md) separately records which operational mappings remain unresolved. Source-only types are isolated from runnable diagnostic definitions. No recovered request is sent, no vendor library or EXPRESS record is executed, and no physical vehicle/adapter test was performed.

## Pinned source and reproducibility

Source: this repository's [`assets-v1/TOYOTA.zip`](https://github.com/sarapudheen-ops/puthus/releases/tag/assets-v1), Toyota V10.62, 71,755,650 bytes, SHA-256:

```text
26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a
```

The [inspection workflow](../.github/workflows/thinkdiag-data.yml) downloads that source, runs synthetic parser/disassembly/report tests, independently recovers the YZJM and English GGP/LAH data, validates each recovered file against its SHA-256 manifest, and produces the sweep reports. It also regenerates the existing passive reference and requires an exact byte-for-byte match with the bundled asset.

The expanded sweep and frame/reference checks were reproduced successfully in [Toyota run 44262343661](https://github.com/sarapudheen-ops/puthus/actions/runs/44262343661), commit `86a0667b732d34e6cbcf859466717d991676fe9d`; [Android run 44262343672](https://github.com/sarapudheen-ops/puthus/actions/runs/44262343672) also passed. That inspection run passes **52 tool tests**, the full source sweep and exact passive-reference regeneration. Its **104 detailed text reports** were restored locally through **10 checksum-verified evidence parts**, without the check renderer's earlier control-byte corruption. See the successful run associated with the reviewed commit for any subsequent evidence refresh.

## Census: what was actually checked

| Source family | Result | What that establishes |
|---|---:|---|
| Archive and nested files | 1,604 | Every entry inventoried, not just files with attractive names |
| ELF libraries | 23 | Static export census; selected reader instructions disassembled, never loaded |
| Decoded binary payloads | 1,410 / 114,797,280 bytes | Wrapper recovery and per-file SHA-256 verification |
| Per-ECU files | 1,307 / zero directory failures | Packed section offsets/ordering/extents validated |
| Per-ECU typed sections | 15,130 / zero framing errors | Header/body bounds, counts and padding accepted; **not** every field's semantics |
| Declared rows in those sections | 859,670 | Structural row counts, not unique PIDs, functions or supported ECUs |
| Shared Toyota/Viewer typed files | 85 of 88 framed | Three regional command-pool copies need an explicit shortfall audit |
| Whole decoded files accepted by current structural parsers | 1,399 of 1,410 | Directory/table/menu framing only; not 99% enhanced diagnostic implementation |
| MENU nodes / edges | 149,731 / 149,730 | All targets resolve, all nodes reachable within depth 32, every title ID resolves |
| English indexed records | 198,091 | Eight original members retained |
| Valid UTF-8 records | 193,589 | ID-to-text maps recovered without guessing other encodings |
| Non-UTF-8 records | 4,502 | Retained in original member bytes, not discarded or guessed |

The extension `.so` is not itself proof of an executable library: `Funcfg.so` in this source is a small `[SYSTEM_LIST]` configuration file, not a 24th ELF.

### Regional vehicle/system relationships

SYSLIST is not a flat list of fault text. It contains keyed vehicle blocks with packed 37-byte system rows. Reader-backed references lead to FUNCDATA directories and regional `Ecu_Data_*.BIN` files:

| Region | Vehicle-index entries | System rows in unique blocks | Function-key matches | ECU-file references resolved |
|---|---:|---:|---:|---:|
| EU | 5,654 | 157,500 | 157,348 | 157,348 |
| JP | 1,764 | 54,545 | 54,545 | 54,545 |
| NA | 2,726 | 74,985 | 74,256 | 74,256 |

The remaining EU 152 and NA 729 rows all carry the source transport label `FLASH`. They stay listed in the audit; they are **not** silently assigned an ECU file or counted as ordinary enhanced ECU definitions. Numeric function keys and text IDs use overlapping namespaces: an accidental text match is not a valid relationship. In particular, some apparent filename IDs resolve to an error-message string. The sweep retains those discrepancies.

Transport labels include Can1/Can2/Can3/Can4, K-Line, VPW, DENSO and FLASH. They are source labels, **not** an established mapping to ATSP numbers, CAN identifiers, addressing width or baud settings.

### Innova evidence

The complete menu contains **137 Innova-matching labelled nodes** and **630 descendant leaves** beneath such paths. These include manual and automatic source menus and duplicate the same choices across paths/transports; they are **not** counts of unique supported vehicles.

Examples of recovered **manual** paths:

- `Manual Select → 16PIN DLC (Europe and Other) → can bus_01 → Innova → KUN → MINI VAN → -2009.07`
- `Manual Select → 16PIN DLC (Europe and Other) → can bus_P5 → Innova → GUN1## → 1GD-FTV → MINI VAN → w/ Smart Key`
- `Manual Select → 16PIN DLC (Europe and Other) → can bus_P5 → Innova → MAGA10 → M20A-FKS → 2024.04- → w/ Front Recognition Camera`

Other paths include legacy KWP choices and Kijang Innova/HEV labels. These are source-menu evidence, **not** newly seeded diagnostic configurations. Header task IDs, packed saved parameters and menu-return values must be traced before joining a particular manual leaf to a vehicle key. An integer that happens to match a SYSLIST key is insufficient. The vendor's automatic/VIN menus will **not** become automatic vehicle selection in bod3.

## Diagnostic definitions and relationships found

### Shared byte and frame dictionaries

Each regional `Toyota_bin_00.BIN` declares **3,258 six-byte descriptors**. The independent audit recovers **3,257 bounded byte sequences**, including read-shaped `21 …` and `22 …` sequences, alongside other protocol fragments, response patterns and state-changing-looking bytes. A dictionary entry is **not** automatically a request and is never approved for transmission solely by its first byte.

All three recovered copies are 33,376 bytes. Their header declares a 33,368-byte body, but only 33,366 body bytes are available. Descriptor **3,258** refers to two bytes at file offset **33,376**, outside the available payload. The strict whole-table parser refuses this frame; the research audit preserves all descriptors, identifies the single invalid range, and never invents/pads the missing bytes. Whether this is an unused terminal entry or another source/runtime convention remains unresolved.

Static readers establish the following reference chain:

1. FILE `GetAllToyotaTblData` loads `Toyota_bin_00`, `Toyota_bin_11` and `Toyota_bin_12` into separate shared-table slots; slot number, embedded schema tag and filename suffix are **different namespaces**.
2. COMM `GetCmdDataInfo` uses one-based six-byte descriptors. IDs above 10,000 use the native 10,000 bias; zero means an empty sequence. Signed-relative offsets and byte lengths are checked against the actual available blob.
3. COMM `GetCommFrameInfo` looks up eight-byte rows in `Toyota_bin_11`: an LE16 frame key plus three LE16 dictionary references. The sweep resolves all three by source field offset and retains unresolved links as errors, rather than guessing their roles.
4. COMM `GetFuncCommFrameInfo` / `_MultiCmd` read `Toyota_bin_12` rows, compare their selector fields and reference a frame ID at row offset 6 when the offset-8 selector is zero. The sweep cross-checks those eligible frame references too.

The full source cross-check produces the following counts (dictionary references include the native zero/empty reference; counts are not unique requests or supported functions):

| Region | Frame rows / unique frame keys | Dictionary links checked | Unresolved dictionary links | Eligible function bindings resolving to frames |
|---|---:|---:|---:|---:|
| EU | 2,925 / 2,925 | 8,775 | 0 | 12,064 / 12,064 |
| JP | 2,925 / 2,925 | 8,775 | 0 | 12,558 / 12,558 |
| NA | 2,925 / 2,925 | 8,775 | 0 | 12,003 / 12,003 |

For example, frame key **10001** at source table offset **18** references dictionary entries **138**, **146**, **142**, recovering `21 00`, `ff ff`, `61 00` respectively. This is a source relationship example, **not** a send recipe or an Innova applicability claim. The malformed terminal dictionary entry is not referenced by these shared frame rows; that does not establish whether another consumer uses it.

This is real cross-table diagnostic data, not keyword matching. It still does **not** establish the complete vehicle/ECU/service/session/address/response-validation chain needed for a bod3 operation.

### Live data and calculations

- `DSTREAM_EN.GAG`: **44,800** indexed English records, including live-value names and other diagnostic labels. This is not a count of supported PIDs.
- `EXPRESS.GAG`: **12,594** expression/script records, including arithmetic/condition/indirection forms. This is not a count of safely portable formulas.
- Per-ECU section 10 has **116,367** 104-byte rows; the P5 section 23 has **87,203** 80-byte rows. Other sections provide ID lists, parameters and related tables. Counts are across regional files and contain duplicates.
- FILE `GetDsDataFromDSPart` reads packed FUNCDATA live-data rows and their variable-length associated byte lists. FDATA readers derive strides from table sizes/counts and compare request/data selectors and extraction fields.
- CALC `ResultCalcFromCalcID` opens `EXPRESS.GAG`, looks up a four-byte big-endian expression ID, follows `ID(hex)` indirection, and passes retrieved text to a script evaluator. Only this static lookup path is traced; **EXPRESS is never evaluated** by the recovery tools.

Still required: reader-validated meanings of every relevant field, exact text/unit/calculation bindings, byte/bit extraction semantics, signedness, endianness, null/error encodings, request/response association, ECU/session applicability and safe read-service classification. Coincident small integers in text, formula and command namespaces must not be joined by guesswork.

### Coding, customization and bidirectional functions

The source contains customization/learning/calibration/active-test labels and dedicated native readers such as `GetActDataFromACTPart`, `GetActCmdDataInfoP3/P4`, `GetCustomizeFuncData`, `GetCustItemInfoData`, `GetSFDataFromSFPart` and `GetRoBDataFromRoBPart`. Per-ECU sections and top-level customization/special-function files remain available for further tracing; this is not a source limited to OBD fault descriptions.

Some of these native procedures also depend on shared runtime interfaces and staged communication state. Recovering their tables or names does **not** establish safe write sequences, preconditions, security algorithms, parameter constraints or verification behavior. No activation/security bypass was attempted. No recovered write/test/security definition is installed in the app or considered verified.

## Corrections and remaining unknowns

Two sweep bugs materially understated the source and are fixed with regression tests:

- ECU filenames use **hexadecimal** suffixes. Decimal-only matching missed hundreds of files. The corrected census covers all 1,307.
- A directory slot / filename suffix need not equal the embedded schema tag. Enforcing equality incorrectly rejected P5 and EU-revision tables. Their size/count/stride/bound checks now succeed without treating the distinct namespaces as interchangeable.

Old disassembly reports also silently stopped at 12,000 function bytes and/or 60,000 display characters. Full per-function reports now include a decoded-byte coverage footer and are split without losing text. A legacy display-limited report has an explicit truncation notice. Complete evidence is compressed/checksummed for publication rather than issuing scores of large check requests; report uploads run even if a later step fails.

**Eight whole-file formats remain outside the complete structural parsers:** `CUSTLISTDATA.BIN`, `CUSTOMLISTM.BIN`, `DTCH_CFG.BIN`, `ECUPROGRAM.BIN`, `FUNCH_CFG.BIN`, `OBD2_SYS_DATA.BIN`, `SPECFUNC.BIN`, `SYSTEMENTER.BIN`. Reader paths/prefixes are reported where available. Together with the three partially audited command-pool copies, these account for the 11 whole-file exceptions. This means parser work remains, **not that their diagnostic data is absent**.

`GetInfoFromAddrPart` is a particularly important naming trap: its “addresses” are FUNCDATA **file offsets**. They must not be promoted to CAN request/response IDs.

## Artifacts and next conversion boundary

The [tool README](../tools/thinkdiag/README.md) includes complete reproduction and report recovery commands. Successful runs publish:

- `toyota-recovered-data`: decoded files, original English members/maps, complete menu/system/section inventories, frame/byte-reference audits, keyword evidence and DTC conversion audit. Retained 14 days.
- `toyota-data-inspection`: human reports, full selected native disassemblies, report manifest and checksum-verified text-evidence bundles. Retained 7 days.
- A readable **Toyota diagnostic sweep** check plus **Toyota sweep evidence NNN** checks carrying the same detailed text reports as a compressed JSON bundle. This avoids lossy NUL rendering and works through the GitHub API when artifact-download hosts are blocked.

Archives, libraries and research directories stay in ignored `.local/thinkdiag/`, never Git or copied wholesale into the APK. The subsequent [offline app library](toyota-app-library.md) deliberately generates a complete data-only SQLite derivative, including every decoded byte and every English record; the APK also retains the unchanged passive DTC derivative. Native code, archives and disassembly are not shipped or executed.

For **operational enhanced diagnostics**, the next conversion boundary must select a **manually chosen** vehicle/variant and establish an end-to-end, provenance-bearing read-only definition: source menu/vehicle key → SYSLIST system → ECU file/function → transport/address/session → frame/request → response validation → data extraction → unit/formula. Start with a bounded subset, validate simulated frames and then the actual adapter/ECU. Only after that should enhanced reads be enabled. Coding/actuators/security require their existing Expert-mode, confirmation, whitelist and backup → write → verify flows, with imported/recovered definitions unverified by default.
