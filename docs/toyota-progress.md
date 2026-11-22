# Toyota/Lexus implementation progress

**Branch/PR:** `arena/73b9af37-puthus`; [PR #26](https://github.com/sarapudheen-ops/puthus/pull/26).
**Baseline head:** `766b617c2463948de834224d182de679d7c46dc2` (main tip from which this branch was created). Stage 0 validation run head: `2a5e4d7d1489eac4414c9e17ae15bf911c40ac59`.
**Terminology:** link status means *natively consistent (call-trace level), not vehicle-tested*.

## Stage status

| Stage | Status | Evidence / blockers |
|---|---|---|
| 0 — health and baseline | Complete | Four required checks passed on `2a5e4d7d1489eac4414c9e17ae15bf911c40ac59`: Android push, Android PR, release-data, actionlint. The prior PR #25 actionlint SC2034 finding was fixed. CI reran source/census reports; see evidence links below. |
| 1 — K-Line categories | Blocked / incomplete | Head 91d6672's probe only re-counted existing converter statuses (39,363 due to a transport-1-only filter); it did not invoke source translators. Do not treat those counts as Stage 1 evidence. Read-only review posted at https://github.com/sarapudheen-ops/puthus/pull/26#issuecomment-7087047687. No new category enablement. |
| 2 — Kotlin/app generalization | Not started | Depends on Stage 1 findings. |
| 3 — CAN and remaining link classes | Not started | Transport classes 4/3 and small classes require read-then-probe evidence. Transport-5 fallback and RX convention remain unresolved. |
| 4 — tests/coding/special functions | Not started | Schemas and native request reproduction are not established. |
| 5 — speed/size/docs/final | Not started | Container is below cap in existing report; caching, staged documentation, and final measurements remain. |

## Baseline measurements (existing CI data)

- Source census: 287,030 systems; 2 enabled systems.
- Enabled K-Line systems: ECMECT EU/function 61/selector `0x11` and the pinned Innova slice. Aggregate enabled definitions: 60 PIDs, 2,828 DTCs, 2 ECU-info definitions. The currently pinned individual systems retain their existing 30/1,414/1 slices.
- K-Line link census: 284 tuples / 39,543 systems. 267 transport-1 tuples / 39,364 systems are native-trace-consistent; 17 transport-5 tuples / 179 systems are unproven (`link_representation_unsupported:transport-5-flags-33`).
- Latest Android CI census container: 388,968,215 bytes / 378,285 reachable objects (below 460 MiB shared-table review threshold and 480 MiB hard cap). The 421,223,645-byte value from the promotion conversion report is a different measured output and is retained in the report link; do not conflate these containers.
- CI tests: 603 tests, 0 failures, 0 errors, 0 skipped in 73 classes.
- Remaining known broad classes: CAN code 4 ~151k systems, code 3 ~94k systems; smaller classes, FLASH 881 excluded. Approximate class sizes are context only and must be re-counted by CI before promotion.
- Existing report says RX address-swap comparison disagreed on 284/284 tuples; cause is not established. New links must omit RX until explained.
- Existing current-head tests/checks: see the PR check list. Feature-level test counts and baseline container reproduction on this branch are not yet established.

## Evidence links

- [PR #25 full promotion report comment](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829427)
- [PR #25 promotion key numbers](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829609)
- [Stage 0 release-data run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067947971) — success, 19m22s.
- [Stage 0 Android push run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067947991) — success, 6m48s build plus 1m31s API 24 on-demand smoke.
- [Stage 0 Android PR run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067952245) — success, 7m55s.
- [Stage 0 actionlint run](https://github.com/sarapudheen-ops/puthus/actions/runs/45067952281) — success, 10s.
- [PR #25 actionlint failure annotations](https://api.github.com/repos/sarapudheen-ops/puthus/check-runs/137264305657/annotations) — SC2034 at workflow line 217; fix removes the unused assignment.

## Stage 1 reconciliation / gate (NOT PASSED)

Head `91d6672ec6b2de9391456624278e67cb0b22e3ba` is the PR #26 head. Its `toyota_stage1_probe.py` classified existing `categoryStatus`; it did not call a source translator. The exact population partition is 39,543 eligible records: 39,363 transport-1 oracle-proven + 1 separately pinned ECMECT system + 179 transport-5 oracle-unproven. The ECMECT row is not included in the 39,363 or the 179. CODE: `convert_vehicle_packs.py:359-405` assigns `is_ecmect` by exact system ID, runs its separately pinned path, and later sets `linkStatus` proven for enabled rows; `toyota_ecmect_pack.py:216-239` checks exact IDs and defines enabled; `toyota_runtime_compile.py:110-111` pins `SYSLIST_EU.BIN@0009d8f2` and `EU/Ecu_Data_0081.BIN`, and `:135-143` verifies function 61/selector 0x11/caller-mode 11 and entry `SYSTEMENTER.BIN@00002f63`. Its native transport comes from that exact entry and is 5, but it is proven by the dedicated ECMECT profile, not the transport-5 oracle. The prior probe's transport-1-only filter incorrectly omitted it. Updated probe population uses the exact 39,543 partition.

Container measurements are not interchangeable: the promotion report for this head says before=388,968,586 and after=421,223,645 bytes; Stage 0 Android census reports 388,968,215 bytes. CODE: `.github/actions/prepare-vehicle-packs/action.yml:42-46` invokes `convert_vehicle_packs.py` with `--read-artifacts` and no `--oracle-evidence`; `.github/workflows/thinkdiag-data.yml:161-165` is the release-data baseline with those same flags and no evidence, while `:167-172` is the promoted invocation adding `--oracle-evidence .local/thinkdiag/report/code1-link-evidence.json`. `convert_vehicle_packs.py:730-731` says absent evidence means no new links are promoted. The 33,057-byte difference between the reported before-size and Android census remains unexplained.

Android PR run 45073303355 carried annotation: “CENSUS_RELEASE concluded failure; the APK build/data publication remains enabled.” The workflow makes this upload step `continue-on-error` at `.github/workflows/android.yml:157-160`; exact failure cause is NOT ESTABLISHED because run-log download returned EOF. The current evidence does not prove it was a permissions issue; do not change the PR behavior on that basis.

Stage 0 verification: one check-runs request for `2a5e4d7d1489eac4414c9e17ae15bf911c40ac59` returned 463 completed check-runs and zero non-success conclusions. The `ci-reports-v1` asset `stage-0-report.md` exists (4,470 bytes).

The read-only source inventory is posted at https://github.com/sarapudheen-ops/puthus/pull/26#issuecomment-7087047687. It found bounded components (DTC, fault/FUNCDATA, ECUF kind-14, direct-21 live/CALC) but no generic translators for actuators, coding, or special functions; the direct-21 candidate is disabled and exact-source constrained. The old status-only probe's per-category counts and reasons are explicitly invalid as Stage 1 evidence. Corpus-wide source translation counts, literal reason histograms, samples/groups, unknown-table examples, exact serialized control comparison, and 1,000-input native CALC comparisons are NOT ESTABLISHED. No categories were newly enabled.

## Stage 1 gate report

Head `91d6672ec6b2de9391456624278e67cb0b22e3ba`. Checks: release-data 45073299752 success (18m17s); Android push 45073299750 success (20m15s); Android PR 45073303355 success (15m45s, 603 tests); actionlint 45073303315 success (9s). Enabled count remains 2 (no new enablement); source-translated executable counts tied to new comparisons: NOT ESTABLISHED for all six categories. Container: promoted 421,223,645 bytes; no-promotion report's pre-conversion measurement 388,968,586 bytes; independent Android census 388,968,215 bytes. The Stage 1 gate is NOT PASSED. Report comment: https://github.com/sarapudheen-ops/puthus/pull/26#issuecomment-7087068851.

## Open blockers / next step

1. Replace the status-only probe with a per-system source-translator probe over all 39,364 proven records, grouping exact `(Ecu_Data file, function, selector)` identities.
2. Implement only existing verified translators, add exact serialized ECMECT control comparison and per-record oracle coverage; do not enable categories absent those comparisons.
3. Run release-data CI and publish full JSON/Markdown plus chunked PR comments, then update this gate report. No merge.
