# Manual vehicle catalog

The configuration catalog is **Make → Model → Generation (name and years) → Variant (engine, fuel, transmission)**. It replaces the flat Vehicle & ECUs picker. Catalog entries are definitions; saved VIN profiles are observations. These are deliberately different stores.

## Choosing and editing a vehicle

- Open **Vehicle catalog** before connecting. Browse the hierarchy or search all levels, including year ranges and variant equipment. Favorites and the last 20 distinct selections are saved.
- Browsing, favorites, imports, a VIN read and opening a saved profile **never change the selection**. Tap **Select this model/generation/variant** explicitly. A make alone is not a vehicle selection.
- A selection supplies the resolved ECU list, custom PIDs, test/coding recipes, DTC text and adapter-connection overrides. Disconnect before changing the vehicle or editing catalog configuration. The next connection applies its settings; they do not mutate the separately saved adapter profile. Existing read-only PID testing/fault-note edits and the guarded coding editor remain available during a connection.
- **No vehicle selected** means no catalog ECU or connection fallback. Standard OBD reads and automatic *transport protocol* detection still work. The preserved unscoped legacy PID/template library is not a generic vehicle profile; its ECU lists and connection settings are never used as a default.
- Add a make at the root, then models, generations and variants. **Edit level** changes metadata without changing its stable ID. **Duplicate branch** copies descendants to new IDs, without selecting them or retaining verification claims. Removing a branch does not remove saved VIN profiles/scans/logs.
- **ECUs** has a form editor with request/response IDs, inferred 11/29-bit addressing and UDS/OBD/KWP. **PID editor** supports adding/editing/duplicating read-only PIDs at the browsed level. The advanced PID, DTC, test, coding, security, connection and module-name editors edit local structured JSON; they validate together before saving. **Copy / duplicate from existing** can copy documented definitions from any catalog level/platform/library. Blank definitions deliberately have no invented ECU IDs or identifiers and cannot be saved until valid.

A saved VIN profile can remember a catalog link. Choose it from the profile ECU screen. A link is metadata only: **linking and selecting are separate explicit actions**. Its ECU list can still be explicitly copied into that profile's manual ECU list. Manually saved per-profile ECUs, user module names and all recorded observations remain available. A known make can still select *passive fault-reference wording* or annotate saved observations; it cannot select connection settings, recipes or catalog probe targets. For manufacturer-restricted text, the loaded profile's known make wins over a conflicting saved link or manual selection; the manual selection is used only when neither profile nor link supplies a make. This prevents a stale catalog choice from giving Toyota meanings to a known unrelated car.

## Inheritance

At each ancestor, apply its explicitly attached reusable platforms (in listed order), then its local data. Process make, model, generation, variant in that order. A child overrides matching keys and retains unrelated parent items:

| Data | Override key |
| --- | --- |
| ECU | Uppercase request ID |
| PID, test, coding, security, DTC reference group | Stable `id` |
| DTC text | Normalized DTC code (including optional failure-type suffix) |
| Module name | Request ID / optional response ID / make restrictions |
| Connection | Individual setting name |

A child's `exclude` lists suppress inherited keys; a still-lower declaration can restore one. Removing a local override restores the parent. Saving a child never copies its inherited definitions. Importing matching IDs merges local declarations; it does not erase unrelated data from generated ancestor rows. Editor saves replace the local declarations being edited, permitting deliberate removal.

Connection data is a sparse patch over the user's adapter profile. Omit a key to inherit it; explicit `null` clears a nullable option (e.g. `extendedAddress` or `forcedProtocol`). All current protocol choices, timing, filtering, CAN baud, flow control and extended addressing are supported. Imported extra init commands are limited to adapter configuration; raw diagnostic payloads, STPX macros, memory writes, monitor/bootloader commands and security requests are refused. Existing manually configured adapter/terminal facilities retain their own policy. Unknown ST/VT commands are still sent directly by adapter setup, logged on `?`, and skipped; there is no firmware capability gate.

The pre-existing **Volkswagen Group MQB** ECU/test/coding data is shared once under platform `vw-group-mqb` and explicitly attached to its original generations. Local changes remain overrides. No other generation is assumed to be MQB merely because it has a VW-group make.

## JSON packs (lossless)

```json
{
  "format": "bod3-vehicle-catalog",
  "formatVersion": 1,
  "id": "my-catalog",
  "name": "My catalog",
  "version": 1,
  "nodes": [
    { "id": "my-make", "level": "make", "name": "My documented make" },
    { "id": "my-model", "parentId": "my-make", "level": "model", "name": "My model" },
    { "id": "my-generation", "parentId": "my-model", "level": "generation", "name": "My generation", "yearFrom": 2004, "yearTo": 2015,
      "data": { "ecus": [], "pids": [], "dtcs": {}, "actuators": [], "coding": [], "security": [], "connection": {}, "exclude": {} } }
  ],
  "platforms": [],
  "library": {}
}
```

`data` is accepted at every level, on reusable platforms, and in the explicitly unscoped legacy library. It accepts `ecus`, `pids`, `dtcs`, `dtcGroups`, `moduleNames`, `actuators`, `coding`, `security`, `connection`, `exclude`. PIDs and recipes use the existing documented pack schemas. Security definitions are declarative level/algorithm/constant objects, not executable code and not permissions. `platformIds` explicitly links a node to shared definitions.

**Export JSON at any level** includes that subtree, the ancestors needed to resolve inheritance, and only referenced platforms. It excludes sibling branches. Full-catalog JSON also preserves the unscoped library. Manufacturer-scoped `dtcGroups` retain their `makes`, `source` and original meanings. The Toyota/Lexus reference is not flattened into global DTC text.

## CSV packs (hierarchy and ECU declarations)

Required header columns (order can vary):

```csv
make,model,generation,year_from,year_to,engine,fuel,ecu_name,req_id,resp_id,addressing,protocol
Toyota,Innova,AN40,2004,2015,,,,,,,
```

The example is intentionally **names/years only**, with no invented ECU addressing.

- UTF-8 (optional BOM), LF/CRLF, quoted commas/newlines and escaped double quotes are supported.
- Higher-level declarations leave lower hierarchy columns empty. An entirely empty ECU block is a names-only row. A partially populated ECU block is an error.
- ECU rows require name, request ID, response ID, addressing (`11`/`29`, also `11-bit`/`29-bit`) and protocol (`UDS`, `OBD`, `KWP`). 11-bit IDs have three hex digits and fit `000–7FF`; 29-bit IDs have eight and fit `00000000–1FFFFFFF`. IDs must differ and use the same width.
- Optional columns are `transmission`, `variant` and `exclude_ecus` (semicolon-separated inherited request IDs to suppress). Engine/fuel/transmission create a variant only when supplied; they are never guessed. `year_to` may be open. Conflicting repeated year ranges/ECU definitions, invalid years, missing ancestors, malformed quotes, unknown/duplicate columns and wrong row widths reject the **whole** file.
- CSV paths are matched to existing stable catalog IDs, including editor-created IDs. An ECU CSV reimport does not wipe unrelated parent definitions.
- **CSV is not a lossless backup of non-ECU data.** The screen and preview state this explicitly. CSV exports hierarchy and ECU declarations/suppressions; platform ECUs are materialized at their attachment point. Use JSON for PID formulas, DTC attribution, test/coding/security definitions, connection patches and shared-platform identity.

Both formats have a 16 MiB UTF-8 limit. File reads are bounded before allocating the entire input. The entire document is parsed and validated first, including graph references, inherited connection settings and recipe safety schemas. The preview shows counts, replacements, warnings and all errors. Nothing is written until confirmation, which revalidates against current data. Catalog definitions, selection metadata and legacy link mappings are persisted as **one preference value**, not row-by-row. A failed validation/storage operation cannot leave a partial import. Recipe previews also refuse to apply if the manual destination changed after previewing. Closing/cancelling a preview invalidates late parse results; an import already being committed cannot be dismissed into a second import. The merged storage-size limit is checked in the preview as well as at commit.

Legacy data/test/coding JSON formats remain importable through the same preview/confirmation screen. Saved-profile files still go to the garage's existing overwrite-confirmation flow.

## Existing-data migration

On first successful startup migration:

1. Load the names/years seed, then convert the exact existing bundled packs. Keep MQB data under its reusable platform. Keep generic example ECU lists explicitly copyable as library platforms, not selectable fallback vehicles.
2. Convert the old user pack, actuator pack and coding pack without deleting their preference keys. Preserve global custom PIDs, notes and unscoped templates in the legacy library. Translate only an **already explicit** old vehicle selection to its catalog ID. A legacy generic transport example does not become a selected vehicle.
3. Save the user overlay, explicit selection, migration marker and old `make|model` link mappings atomically. The migration is idempotent. Unreadable legacy preferences are reported rather than silently marked migrated.
4. Lazily translate per-VIN explicit links to `catalogNodeId`, retaining the old key, notes, manual ECUs and module names. This never activates a vehicle. Unknown links remain retained, not guessed or retargeted.

There is **no Room schema migration**: profiles, module observations, scans, timestamps and raw logs are not rewritten by the catalog. Coding backups and audit logs keep their stores. Unreadable catalog/profile extras are preserved under recovery keys, not silently reset to a generic vehicle. A visible error blocks manual-list updates and module scans when saved extras need recovery, so an empty UI fallback cannot overwrite the original document.

## Safety and coverage

All imported, copied and edited coding/actuator/security definitions are **unverified**, regardless of input flags. The legacy direct-import APIs also strip verification claims. Expert mode still defaults off; imported connection data cannot enable it. Read-only services stay whitelisted. Actuator requests remain inside the M4 flow; coding writes remain inside M5's backup → confirmation → single write → read-back verification flow. Confirmation is still required for DTC clear. No automatic write/drop retry is introduced.

`KWP` is stored distinctly from `UDS` and `OBD`. The existing adapter's K-line/KWP transport and standard OBD functionality remain supported. Enhanced KWP identification/fault memory is **not guessed or dispatched as UDS**: the per-ECU workbench explains that documented read definitions are needed and sends no incorrect UDS request. KWP entries are also excluded from the direct CAN/UDS listed-ECU scan instead of losing their protocol type during conversion to scan addresses. A separately requested generic UDS address sweep remains a UDS discovery operation, not enhanced KWP support. KWP fault clearing is not routed through the UDS clear flow. This catalog does not invent new enhanced protocol/identifier coverage.

The starter seed has main names/generation years for the requested makes, including Innova AN40 2004–2015 and later Innova generations. Ranges are broad labels, vary by market/model year and are not fitment/coverage claims. The seed contains **no ECU addresses, PID requests, DIDs, security algorithms or coding/test definitions**. The separately preserved Toyota/Lexus data remains passive fault-text reference, not enhanced Toyota diagnostic coverage. No physical adapter/vehicle validation is claimed.

## Checks

```sh
python3 tools/generate_catalog_seed.py --check
./gradlew assembleDebug lintDebug testDebugUnitTest
python3 -m unittest discover -s tools/thinkdiag -p 'test_*.py' -v
```

Regression coverage includes all data types at every inheritance level, overrides/exclusions/platform precedence, sparse connection patches, no-selection behavior, strict JSON/CSV validation, final-row failures, atomic failure/retry, scoped reference attribution, actual bundled-data migration, per-VIN links, stable-ID rename/duplication/removal, any-level export, cross-level search, favorites/recents and legacy recipe verification stripping. The existing simulated transport, Expert-mode, confirmation, backup/verify/rollback and failure/drop tests remain in CI.
