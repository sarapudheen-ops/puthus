# Toyota/Lexus release data recovery

## What is usable in bod3 now

`app/src/main/assets/datapacks/toyota-lexus-dtcs.json` is a **passive English DTC reference**, recovered from this repository's `assets-v1/TOYOTA.zip` release asset (Toyota V10.62).

- **11,989 code meanings:** 4,538 base codes and 7,451 exact extended codes (`Pxxxx-yy`).
- Only explicit code/description pairs with one unique non-placeholder wording were retained. Whitespace was normalized; spelling was not rewritten.
- **3,522 conflicting codes were excluded**, even when their alternatives merely look similar. 3,776 placeholder/empty records and 536 unsupported/blink-code records were also excluded. The complete source has 27,035 indexed English TCODE records; duplicate identical pairs account for the difference between record and distinct-code counts.
- Extended codes are **never promoted to base codes** to inflate coverage. For example, the accepted `P0301-00` text is not used as a new definition for `P0301`. Existing generic OBD text remains the fallback.
- The pack contains **no PIDs, ECU addresses, vehicle scan lists, security algorithms, coding recipes or actuator tests**. It does not enable enhanced Toyota diagnostics or make a vehicle support a service it did not support before.

The source archive SHA-256 is `26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a`. The bundled derivative is 897,993 bytes with SHA-256 `00c23401fcadca510317bc7230c5b3a34eee4cc10976bbc85ee69b20b1ed9682`. Machine-readable provenance and conversion counts are in [`tools/thinkdiag/toyota-dtcs.provenance.json`](../tools/thinkdiag/toyota-dtcs.provenance.json). The original release remains the source of the vendor content, not a bod3 authorship or vehicle-coverage claim.

### Where it appears

- OBD fault codes and freeze frames, per-ECU fault codes, and scanned/saved module fault details use the reference lookup and show the text's source.
- It applies **only** to a known make of `Toyota`, `Lexus` or `Toyota Thailand` (case-insensitive exact match). Make evidence is, in order: the loaded profile, its explicitly saved catalog/legacy link, then the new **manual catalog selection** if neither profile nor link supplies a make.
- A known profile make takes precedence over a conflicting saved link or stale manual selection. A catalog left on Toyota cannot supply Toyota-only meanings to a profile known to be another manufacturer.
- Without any known profile/link/manual make, or for an unrelated make, the text stays generic. Beginning a new protocol/VIN read and disconnecting clear the *observed-profile* context; an explicit manual catalog choice stays selected until the user changes it. Passive text lookup never selects a catalog node or applies connection settings, ECU lists or write definitions. See the [manual catalog guide](vehicle-catalog.md).
- Your manually entered descriptions still override references. A user's exact or base-code note wins; otherwise reference lookup tries the exact code across applicable imported/scoped/generic packs before trying the base code.
- Imported manufacturer groups retain their makes and source when persisted or re-exported. They do not become global user notes. Removing a manual override restores the scoped reference rather than silently deleting it.

A reference is not a confirmed diagnosis or repair instruction. The recovered text may contain vendor errors or abbreviations and has **not** been checked against every repair manual, model, ECU or vehicle. Check the code's meaning in the repair information for the actual vehicle and ECU. No physical adapter/car testing was performed for this conversion.

## Data-pack format v2

Existing v1 files still work and are still exported as v1 when no scoped references are present. Manufacturer-specific text must use v2 `dtcGroups`, never the global `dtcs` map:

```json
{
  "format": "bod3-datapack",
  "formatVersion": 2,
  "id": "toyota-reference-example",
  "name": "Toyota/Lexus passive DTC example",
  "version": 1,
  "dtcGroups": [
    {
      "id": "toyota-lexus-example",
      "makes": ["Toyota", "Lexus"],
      "source": "ThinkDiag Toyota/Lexus V10.62 - TCODE_EN.GAG",
      "dtcs": {
        "P0301-00": "Cylinder 1 Misfire Detected"
      }
    }
  ]
}
```

Each group needs a unique stable ID, at least one non-empty manufacturer, and a non-empty valid code-to-text map. `source` is optional for user-created packs; the bundled Toyota group provides it. An imported group replaces the user's group with the same ID as a unit. Otherwise groups are retained separately. Preview lists the makes and number of texts before import, and *All my data* exports them without losing scope. Exporting a bundled pack also keeps v2 and its source. Older bod3 versions refuse v2 rather than silently accepting a manufacturer group without understanding its scope. Missing/v1 version with a non-empty `dtcGroups` field is rejected by the current app too.

## What was decoded outside Git

The original outer ZIP is **not password-encrypted**. Its contained database formats needed data-only decoding:

| Output | Result | App integration |
|---|---:|---|
| YZJM binary wrapper | 1,410 tables; 114,797,280 decoded payload bytes | Not interpreted as commands |
| English GGP/LAH members | 8 files; 198,091 indexed records | Selected TCODE pairs only |
| Valid UTF-8 records | 193,589 | JSON exports for further analysis |
| Non-UTF-8 ADAS records | 4,502 | Omitted from text JSON; original member bytes retained, encoding not guessed |

The eight members are TEXT_EN.GAG (35,882 records), DSTREAM_EN.GAG (44,800), TCODE_EN.GAG (27,035), RCODE_EN.GAG (1,885), EXPRESS.GAG (12,594), VEHICLEADAS.GAG (61,656), VEHICLEMANUAL.GAG (13,538), and VEHICLEDTCS.GAG (701).

**Not yet converted:** vehicle/system-to-ECU mappings, request/response definitions, live-data formulas/units/request links, legacy protocols, coding and bidirectional operations. A decoded label or source-like expression is not enough to establish any of those semantics. EXPRESS content is never evaluated. Vendor vehicle-year coverage claims are not bod3 coverage.

## Reproduce and retrieve the results

See [`tools/thinkdiag/README.md`](../tools/thinkdiag/README.md) for local commands and the static format evidence. The **Inspect Toyota release data** GitHub Actions workflow downloads the SHA-pinned release archive, runs the decoder tests, extracts data, converts DTCs, and compares the generated reference **byte-for-byte** with the bundled asset. No vendor native library is loaded or executed.

Its artifacts are:

- **`toyota-recovered-data`** (14 days): decoded YZJM payloads, all eight original GGP member files, ID-to-UTF-8 JSON maps, the converted app pack, the per-code source-ID/conflict audit, and the binary manifest/text-library summaries. Non-UTF-8 records can still be recovered from the retained member files with the data-only reader.
- **`toyota-data-inspection`** (7 days): bounded inspection, disassembly, extraction and conversion reports. The main reports are also published as check summaries; compact converted-text shards are checksummed for inspection where artifact-download hosts are blocked.
- **`app-debug-apk`** from **Android CI**: the debug APK including the scoped reference.

Regenerate the research artifacts when they expire. Full archives, raw libraries and decoded research datasets stay in ignored `.local/thinkdiag/`, **not in Git or the APK**. The only bundled Toyota dataset is the validated passive DTC subset. Existing read-only/Expert-mode, confirmation, coding-engine and actuator-engine restrictions are unchanged.
