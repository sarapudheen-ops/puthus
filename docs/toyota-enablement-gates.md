# Toyota pack enablement: code-derived gates and live-path notes

This is an observational catalog; it does not change enablement. The generated corpus is absent from this checkout, so run `tools/thinkdiag/pack_report.py` in CI for corpus-specific counts/examples. The report intentionally uses `vehicle_bundle.read_object` and bounds output; it is not a replacement parser or validator.

## Converter gate catalog (stable reason keys)

The canonical reason-key-to-user-text map is `tools/thinkdiag/convert_vehicle_packs.py:26-41` (`REASONS`). The exact user-facing text and corresponding code key are:

| Key | Exact reason text | Code location | Evidence needed / likely evidence class |
|---|---|---|---|
| `ecu_entry_untranslated` | `Per-ECU SYSTEMENTER address/header, baud, init/session and selector are not fully translated.` | `convert_vehicle_packs.py:27`, applied at :292 | Translate exact source fields and validate runtime mapping (source parsing + native emulation; adapter proof for physical equivalence) |
| `service_reply_untranslated` | `Application service/identifier and reply-mask joins are not fully translated.` | `:28` | Exact source joins and reply semantics (source parsing/native emulation; adapter proof for physical behavior) |
| `formula_untranslated` | `Signal byte/bit positions, signedness, scaling, unit and runtime applicability are not fully translated.` | `:29` | Exact formula/field semantics and applicability (source parsing + native emulation; adapter proof for actual readings) |
| `record_schema_untranslated` | `This source-row schema has not been translated into an executable definition.` | `:30`, emitted for unclassified rows at :187-194 | Parse schema, corroborate native reader semantics as necessary; physical proof for actual ECU behavior |
| `hierarchy_untranslated` | `The exact source path is retained; normalization into model/generation/variant fields is not yet validated for this path.` | `:31`, hierarchy normalization call sites in converter | Validate source hierarchy (source parsing) |
| `flash_unsupported` | `Source system uses FLASH; flashing/download services are intentionally outside the app's write whitelist.` | `:32`, system reason branch :286-287 | Not satisfiable by more parsing: intentionally excluded write capability |
| `missing_ecu_file` | `Source ECU file could not be joined. No generic ECU or address is substituted.` | `:33`, :288 | Recover/parse exact source join (source parsing) |
| `missing_function` | `No regional FUNCDATA row joins this source function key.` | `:34`, :289 | Recover/parse exact regional function join (source parsing) |
| `missing_system_entry` | `No SYSTEMENTER entry exactly matches this region, function and complete selector byte; another selector or ECU is never substituted.` | `:35`, :290 | Exact source entry/selector join (source parsing); runtime meaning may require native emulation; physical proof for operational claim |
| `missing_system_key` | `The ordinary manual MENU leaf has no matching SYSLIST key in its source region.` | `:36` | Exact menu/list source join (source parsing) |
| `nonordinary_menu_task` | `This manual leaf uses a special task, not the proven ordinary VehicleSystemScan key path.` | `:37` | Parse and explicitly model task semantics; potentially native emulation; physical proof for execution |
| `live_applicability_untranslated` | `The source row's runtime applicability conditions are not fully translated.` | `:38` | Translate conditions; native emulation for behavioral equivalence; physical evidence for applicability |
| `calc_semantics_unreviewed` | `The exact EXPRESS/CALC expression is not in the runtime-compiled subset.` | `:39` | Parse/port exact semantics and compare native execution; physical evidence for vehicle behavior |
| `dynamic_scaling_untranslated` | `The source row's dynamic scaling behavior is not fully translated.` | `:40` | Translate and compare scaling semantics; native emulation; physical evidence for live values |

System defaulting is visible in `convert_vehicle_packs.py:284-320`: reasons begin with `ecu_entry_untranslated`; FLASH replaces that list with `flash_unsupported`; missing ECU/function/entry add their respective keys. Counts are initialized at :290-294 with `converted: 0` and `unresolved: None`, except PID unresolved is set to declared live rows when function metadata exists. One reviewed artifact path may enable a specifically checked system at :302-318; this document does not alter that pin or its validator.

Function source rows are deliberately represented in disabled-item records, not silently turned into runnable definitions: `disabled()` adds `enabled: False`, `reasons`, `rawHex`, `sourceRecords`, and `sourceVerified` (`convert_vehicle_packs.py:112-115`); source ECU typed rows without a recognized stride/schema are recorded as `record_schema_untranslated` (`:180-194`). The source-row reason mapping is not a guarantee that every function category has one uniform reason: records retain their own `kind`, source ID, raw bytes, and reason list.

### Validation layers inspected

- `vehicle_bundle.py`: canonical JSON, deterministic gzip, byte ranges, hashes and hard size limits; `read_object()` verifies range key set, bounds, hash, decompression length and JSON.
- `vehicle_system_validation.py`, `vehicle_formula_validation.py`, `vehicle_workflow_validation.py`: independent structural/semantic validators. They reject invalid objects rather than make them enabled.
- `verify_vehicle_bundle.py`: bundle/index/corpus consistency checks; `--require-executable` verifies the expected enabled-data condition.
- `toyota_functions.py`, `toyota_entry_runtime.py`, `toyota_runtime_compile.py`, `toyota_lifecycle_contracts.py`: bounded source/runtime compilation and contracts. Their tests do not establish physical adapter equivalence.
- Kotlin `VehiclePackCatalog.kt`: requires pinned index, exact object kind/ID/path/source bindings, expected count keys, source-record provenance and ECU target consistency. Disabled/unresolved records remain disabled; no imported flag is a trust root.

Per-condition validator failures are not all converter reason strings: these validators fail the build/verification rather than attach a user-facing unresolved reason. A full exhaustive list of every validator predicate is intentionally not paraphrased as reason keys here; consult the named validators at the indicated files. No gate was changed by this report feature.

## Converter JSON shapes

The generated corpus is not checked into this branch. Shapes below are field names/types established by converter construction; optional branches can vary. Values are not examples of a particular generated corpus object.

| `kind` | `level` | Fields emitted by converter / value types |
|---|---|---|
| `catalog-level` | hierarchy level string | source node fields plus `kind: str`, `children: list[object-ref entries]`; fields derive from normalized catalog hierarchy (`convert_vehicle_packs.py:400-414`) |
| `manual-menu` | n/a | `kind: str`, `id: str`, `name: str`, `children: list` (`:354`) |
| `manual-vehicle` | n/a | `kind: str`, `region: str`, `sourceKey: str`, `systems: list`, plus source menu fields (`:361-365`) |
| `converted-vehicle` | `variant` | `kind`, `level`, IDs/names/selection fields, hierarchy resolution/reasons, `systems` inline or referenced (`:371-378`) |
| `vehicle-system` | n/a | `kind`, `id`, `name`, nullable `description`, `sourceRecords: list[str]`, `sourceVerified: bool`, `enabled: bool`, `reasons: list[str]`, `functionKey: int`, `entrySelector: int`, `transportLabel: str`, `rawHex: str`, source references, `bringUpPages: list[range]`, `counts: map`, `data: object`; enabled reviewed branch adds parsed data/read-only metadata (`:291-318`) |
| `disabled-items` | n/a | `kind: str`, `items: list[disabled-record]`; pages chunked to 128 (`:109-115`) |
| `ecu-source` | n/a | `kind`, `sourceFile: str`, `sha256: str`, `sections: list[object]`, `unclassifiedSourceRecords: int`, `pages: list[range]` (`:195-196`) |
| `function-source` | n/a | `kind`, `sourceRecord`, `functionKey`, `declaredLiveRows`, source pages/metadata (`:251`; optional reference/page layout) |
| `bring-up-candidates` | n/a | `kind`, `candidates: list[object]`; each candidate includes ID/name, source/support records, support count, metadata map, blockers, runtime (null or translated runtime) (`:157-170`) |
| `source-inventory-page` | n/a | `kind`, `systems: list` of `{id, object}` entries (`:449`) |
| `source-inventory` | n/a | `kind`, system-page refs, ECU/function source refs, entry/unclaimed page refs (`:452-454`) |

This is a code-derived inventory of construction sites, not a promise that every branch emits every kind. The exact index keys are emitted by `BundleWriter.finish()` in `vehicle_bundle.py:57-66`: `format`, `formatVersion`, `sourceSha256`, `converterVersion`, `manualSelectionOnly`, `container`, `containerBytes`, `containerSha256`, `root`, `catalogRoot`, `summary`, and optional `sourceInventory`. Range reference keys are exactly `offset`, `bytes`, `jsonBytes`, `sha256` (`vehicle_bundle.py:48-53`).

## Kotlin model declarations and live path

Verbatim pack declarations (`app/src/main/java/com/bod3/app/vehiclepack/VehiclePackCatalog.kt`):

```kotlin
data class PackSystemChoice(val id: String, val name: String, val enabled: Boolean, val reasons: List<String>,
                            val counts: Map<String, FeatureCount>, val range: PackRange, val readOnly: Boolean = false)
class PackVehicle internal constructor(
    val id: String, val path: CatalogPack, val sourceSelection: List<String>,
    val hierarchyResolved: Boolean, val reasons: List<String>, val systems: List<PackSystemChoice>,
) {
    val variantId: String get() = path.nodes.last().id
    val title: String get() = CatalogTree(path).resolve(variantId)!!.title
}
class PackSystem internal constructor(
    val id: String, val name: String, val enabled: Boolean, val reasons: List<String>,
    val counts: Map<String, FeatureCount>, val sourceRecords: List<String>, val data: CatalogData,
    val unresolvedPages: List<PackRange>, val bringUpPages: List<PackRange> = emptyList(),
    val readOnly: Boolean = false,
) { /* coverage is derived from data/counts */ }
```

Verbatim `BringUpCandidate` declaration is in `app/src/main/java/com/bod3/app/bringup/BringUpModels.kt`; its complete fields are `id`, `name`, `sourceRecords`, `supportRecords`, `metadata`, `blockers`, `runtime`, and internal `pinned`. It exposes `supportCount`, `reasons`, `runnable`, `fingerprint`, and `target`; validation requires source records and bounds. See that file for the full class body. The declaration is not duplicated here to avoid maintaining a second, potentially stale copy.

The known app-side path is:

1. `ToyotaVehiclePacksScreen.kt` calls UI callbacks for browsing/selecting a vehicle and opening systems.
2. `ObdViewModel.openToyotaPacks` / pack-screen callbacks construct/use `VehiclePacksController` (controller in `vehiclepack/VehiclePacksController.kt`). `browse`, `selectPreview`, `openSelected`, and `openSystem` load/select the manually chosen hierarchy/system; downloads do not send diagnostic requests.
3. `VehiclePackCatalog.vehicle()` and `.system()` load checked objects, then `system()` decodes accepted feature data into existing `CatalogData` definitions (`VehiclePackCatalog.kt`).
4. `VehiclePacksController.selectedData()` returns an empty `CatalogData` unless the explicitly selected vehicle/system matches; otherwise it returns that system’s data.
5. `ObdViewModel.openSelectedSystemLive()` sets the selected-system live definitions and delegates to the existing live-data path. Generic `VehicleReader.readPid()` in `obd/VehicleReader.kt` sends the definition PID through `Session.request(target, 0x01, definition.pid)` and decodes payload bytes through `PidDefinition.decode()`.
6. DTC reads use the selected ECU’s explicit `DiagnosticRead`/fault layout and existing `ObdViewModel` diagnostics/workbench path; Toyota-specific layouts/workflow are in `diagnostics/ToyotaFaultLayout.kt` and `ToyotaFaultWorkflow.kt`. ECU identification reads are explicit `DiagnosticRead` definitions routed through the diagnostic session; decoded output is handled by the read-definition/workbench layer.

The exact full runtime call sequence differs by feature and cannot be represented by a single PID function. `ToyotaVehiclePacksScreen` and `VehiclePacksController` do not themselves execute adapter requests.

### Physical bring-up gate

The model and engine are `BringUpRuntime`, `BringUpCandidate`, `BringUpEngine`, and persisted/validated `BringUpReport` in `app/src/main/java/com/bod3/app/bringup/`. A working report requires successful positive **identification, live PID, and DTC** probes for the exact candidate, complete logs, and matching candidate fingerprint; validation checks each response against that translated read definition (`BringUpModels.kt`, `BringUpEngine.kt`). The user must manually choose the exact four-level vehicle path/system and vehicle instance, connect the adapter, then run the source candidate bring-up flow and obtain a positive result. A menu selection or transport probe alone does not satisfy the gate.

### UI path

1. Open Vehicle catalog (or Data packs) → choose Toyota vehicle pack.
2. Manually browse Make → Model → Generation → Variant and review source path.
3. Disconnect as prompted, explicitly select the variant, then choose the system.
4. Connect adapter; complete required source bring-up for features gated on it.
5. Open Live Data / selected-system live view for available PIDs; use Diagnostics/DTC view for the selected ECU’s explicit DTC read. The screens do not enable unsupported systems or substitute generic data.
