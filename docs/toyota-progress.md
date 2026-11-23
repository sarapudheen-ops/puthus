# Toyota/Lexus implementation progress

**Branch/PR:** `arena/73b9af37-puthus`; [PR #26](https://github.com/sarapudheen-ops/puthus/pull/26).
**Baseline head:** `766b617c2463948de834224d182de679d7c46dc2` (main tip from which this branch was created).
**Terminology:** link status means *natively consistent (call-trace level), not vehicle-tested*.

## Stage status

| Stage | Status | Evidence / blockers |
|---|---|---|
| 0 — health and baseline | Complete | Baseline and evidence links below. |
| 1 — K-Line categories | In progress / no new enablement | The exact ECMECT strict-translator control now passes in release-data CI, with 30 PIDs, 1,414 DTCs, and 1 ecuInfo byte-identical to the enabled-pack output. This validates the pinned control path only; Mode B remains structural candidate-parse, not verified or enabling. No new categories are enabled. |
| 2 — Kotlin/app generalization | Not started | Depends on Stage 1 findings. |
| 3 — CAN and remaining link classes | Not started | Transport classes 4/3 and small classes require read-then-probe evidence. Transport-5 fallback and RX convention remain unresolved. |
| 4 — tests/coding/special functions | Not started | Schemas and native request reproduction are not established. |
| 5 — speed/size/docs/final | Not started | Existing Android census container is below the cap; caching, staged documentation, and final measurements remain. |

## Baseline measurements (existing CI data)

- Source census: 287,030 systems; 2 enabled systems.
- Enabled K-Line systems: ECMECT EU/function 61/selector `0x11` and the pinned Innova slice. Aggregate enabled definitions: 60 PIDs, 2,828 DTCs, 2 ECU-info definitions. The currently pinned individual systems retain their existing 30/1,414/1 slices.
- K-Line link census: 284 tuples / 39,543 systems. 267 transport-1 tuples / 39,364 systems are native-trace-consistent; 17 transport-5 tuples / 179 systems are unproven (`link_representation_unsupported:transport-5-flags-33`). This class census is distinct from Stage 1's eligible partition: 39,363 transport-1 proven + 1 separately pinned ECMECT + 179 transport-5 unproven.
- Android CI census container: 388,968,215 bytes / 378,285 reachable objects (below 460 MiB shared-table review threshold and 480 MiB hard cap). The promoted conversion measurement of 421,223,645 bytes is a different container build; do not conflate the two.
- CI tests: 603 tests, 0 failures, 0 errors, 0 skipped in 73 classes.
- Remaining known broad classes: CAN code 4 ~151k systems, code 3 ~94k systems; smaller classes, FLASH 881 excluded. Approximate class sizes are context only and must be re-counted by CI before promotion.
- Existing report says RX address-swap comparison disagreed on 284/284 tuples; cause is not established. New links must omit RX until explained.

## Evidence links

- [PR #25 full promotion report comment](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829427)
- [PR #25 promotion key numbers](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829609)
- [Stage 0 release-data run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067947971) — success, 19m22s.
- [Stage 0 Android push run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067947991) — success, 6m48s build plus 1m31s API 24 on-demand smoke.
- [Stage 0 Android PR run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067952245) — success, 7m55s.
- [Stage 0 actionlint run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067952281) — success, 10s.

## Stage 1 control correction and successful probe

Historical failed attempts are retained in the PR discussion; they are superseded by the successful run linked here. The literal strict-translator reject reasons observed before the fix included `ecmect_specific:mandatory_system_and_live_read_review`, `ecmect_specific:mandatory_system_and_p3p4_workflow`, and `ecmect_specific:mandatory_system_and_ecu_identification`.

Root cause (CODE): the strict translation call received the first row in a shared `(Ecu_Data file,function,selector)` group as its `system_row`; the converter instead calls `enable(value, review, lifecycle)` using the exact ECMECT system record. The probe had been applying the exact pinned acceptance path to a group representative, not the exact row `SYSLIST_EU.BIN@0009d8f2`. The fix preserves that exact system row for the control and supplies the source-derived lifecycle to the same `data_for(review, lifecycle)` builder used by `toyota_ecmect_pack.enable`. The pinned region/function/selector/system/ECU checks are unchanged. Tests verify the `data_for(review, lifecycle)` argument shape, 30/1,414/1 counts, byte identity, and that another system in the same tuple does not inherit ECMECT results.

**Successful release-data probe run:** [45094717078](https://github.com/sarapudheen-ops/puthus/actions/runs/45094717078), tested code head `59e2afd7904be433ec6de376756081412b938934`, conclusion success, job duration 14m37s. Report is posted in [Stage 1 probe comment](https://github.com/sarapudheen-ops/puthus/pull/26#issuecomment-7089300497) and the `ci-reports-v1` release assets `stage-1-probe.md` / `stage-1-probe.json`.

### Mode A — strict translator (control passed)

| Category | Executable systems | Rejected | Error |
|---|---:|---:|---:|
| pids | 1 | 39,542 | 0 |
| dtcs | 1 | 39,542 | 0 |
| ecuInfo | 1 | 39,542 | 0 |
| actuators | 0 | 39,543 | 0 |
| coding | 0 | 39,543 | 0 |
| specialFunctions | 0 | 39,543 | 0 |

The exact ECMECT control is 30 PIDs / 1,414 DTCs / 1 ecuInfo, byte-identical to the enabled-pack output. Corpus totals of 30/1,414/1 are the one pinned system only; they are translation outputs, not new oracle approvals. No additional system or category is enabled.

### Mode B — candidate parse (NOT VERIFIED / NOT ENABLING)

- Distinct `(Ecu_Data file,function,selector)` groups: 284; systems beyond one representative per group: 39,259.
- Candidate structural outcomes: 31,431 parsed; 8,112 rejected (`Not a source P3/P4 fault block`). Per-category candidate definitions remain zero. Structural parsing does not establish category semantics, oracle equivalence, or enablement.

### Top rejection reasons (systems; five representative exact keys each)

- `pids` / `ecmect_specific:mandatory_system_and_live_read_review` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `dtcs` / `ecmect_specific:mandatory_system_and_p3p4_workflow` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `ecuInfo` / `ecmect_specific:mandatory_system_and_ecu_identification` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `actuators` / `no_generic_translator:actuators` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `coding` / `no_generic_translator:coding` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `specialFunctions` / `no_generic_translator:specialFunctions` — 39,542: `SYSLIST_EU.BIN@0000b0db`, `@0000b171`, `@0000b251`, `@0000b30c`, `@0000b3a2`.
- `actuators` / `ecmect_compiler_returned_no_definitions` — 1: `SYSLIST_EU.BIN@0009d8f2`.
- `coding` / `ecmect_compiler_returned_no_definitions` — 1: `SYSLIST_EU.BIN@0009d8f2`.
- `specialFunctions` / `ecmect_compiler_returned_no_definitions` — 1: `SYSLIST_EU.BIN@0009d8f2`.

(The report gives ten samples per reason; `@` abbreviations above retain the same `SYSLIST_EU.BIN` prefix.) Literal regional/transport histograms, all ten samples, table/expression census, and exact parsed ECMECT entry are in the report comment/artifacts. Actuators, coding, and special functions have no generic translator; all non-ECMECT PID/DTC/ecuInfo categories remain blocked by their named pin dependencies.

### Same-head check summary and container measurement

For tested source head `59e2afd7904be433ec6de376756081412b938934`:

- Release-data: success, 14m37s — run 45094717078.
- Android push: success — run 45094717072; main build/test job and Android 24 on-demand smoke completed successfully. Android PR run 45094722547 had a failing census-release publication step, while build tests passed (603 tests, 0 failures, 0 errors, 0 skipped); do not represent that PR run as green.
- GitHub workflow lint: success — run 45094722587.
- Android census container measurement: 388,968,215 bytes / 378,285 reachable objects, under the 480 MiB cap. The release-data artifact zip size is not the decompressed container size.

## Evidence audit / next work

CODE: ECMECT PIDs originate from `compile_mandatory_source_reads` calling `review_direct21` (`toyota_runtime_compile.py:114-145`), then `toyota_ecmect_pack.data_for` injects the 30 reviewed `liveReads` (`toyota_ecmect_pack.py:160-173`). Direct21 review items are disabled candidates (`toyota_live.py:313-318`). DTC/ECU-info definitions come from the same pinned compiler artifact (`toyota_runtime_compile.py:142-145`, `toyota_ecmect_pack.py:160-173`); `test_ecmect_pack.py` verifies 30/1,414/1 against the selected pack, and `verify_source_read_review` checks a deterministic compiler-output pin (`toyota_runtime_compile.py:173-220`, `tools/thinkdiag/schema/mandatory-read-artifacts-v1.json`). This is source/compiler regression evidence, not a native replay of emitted category records. **No oracle for non-ECMECT PID, DTC, or ecuInfo output is established.** No human sign-off hash is established beyond source/artifact SHA pins. Mode B must remain candidate-parse, not enabling.

**Next work:** rank source-bound read-only work from the successful report's literal Mode B and rejection histograms; then implement and test one category only when both executable translation and an oracle comparison exist. Keep Mode B non-enabling. Preserve the existing two enabled systems until another category clears those gates. No merge.
