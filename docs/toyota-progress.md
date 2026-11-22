# Toyota/Lexus implementation progress

**Branch/PR:** `arena/73b9af37-puthus`; PR pending creation.  
**Baseline head:** `766b617c2463948de834224d182de679d7c46dc2` (main tip from which this branch was created).  
**Terminology:** link status means *natively consistent (call-trace level), not vehicle-tested*.

## Stage status

| Stage | Status | Evidence / blockers |
|---|---|---|
| 0 — health and baseline | In progress | Main's latest observed Android push run is green; prior PR #25 actionlint failure was traced and a one-line workflow lint fix is included in this branch. Baseline counts below come from the latest available promotion-report PR comment, not a rerun on this head. |
| 1 — K-Line categories | Not started | First dependency is a CI dry-run/probe covering the natively consistent link set; category executable counts are not established. |
| 2 — Kotlin/app generalization | Not started | Depends on Stage 1 findings. |
| 3 — CAN and remaining link classes | Not started | Transport classes 4/3 and small classes require read-then-probe evidence. Transport-5 fallback and RX convention remain unresolved. |
| 4 — tests/coding/special functions | Not started | Schemas and native request reproduction are not established. |
| 5 — speed/size/docs/final | Not started | Container is below cap in existing report; caching, staged documentation, and final measurements remain. |

## Baseline measurements (existing CI data)

- Source census: 287,030 systems; 2 enabled systems.
- Enabled K-Line systems: ECMECT EU/function 61/selector `0x11` and the pinned Innova slice. Aggregate enabled definitions: 60 PIDs, 2,828 DTCs, 2 ECU-info definitions. The currently pinned individual systems retain their existing 30/1,414/1 slices.
- K-Line link census: 284 tuples / 39,543 systems. 267 transport-1 tuples / 39,364 systems are native-trace-consistent; 17 transport-5 tuples / 179 systems are unproven (`link_representation_unsupported:transport-5-flags-33`).
- Existing promotion report container: 421,223,645 bytes (below 460 MiB shared-table review threshold and 480 MiB hard cap).
- Remaining known broad classes: CAN code 4 ~151k systems, code 3 ~94k systems; smaller classes, FLASH 881 excluded. Approximate class sizes are context only and must be re-counted by CI before promotion.
- Existing report says RX address-swap comparison disagreed on 284/284 tuples; cause is not established. New links must omit RX until explained.
- Existing current-head tests/checks: see the PR check list. Feature-level test counts and baseline container reproduction on this branch are not yet established.

## Evidence links

- [PR #25 full promotion report comment](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829427)
- [PR #25 promotion key numbers](https://github.com/sarapudheen-ops/puthus/pull/25#issuecomment-7081829609)
- [PR #25 release-data run](https://github.com/sarapudheen-ops/puthus/actions/runs/45040709914)
- [PR #25 actionlint failure annotations](https://api.github.com/repos/sarapudheen-ops/puthus/check-runs/137264305657/annotations) — SC2034 at workflow line 217; fix removes the unused assignment.

## Open blockers / next step

1. Run this branch's CI after the lint fix; wait for Android push, Android PR, release-data, and actionlint.
2. Create the PR early and post the Stage 0 report comment plus corresponding `ci-reports-v1` artifact.
3. Proceed to Stage 1 only after CI supplies a complete dry-run census and native comparison evidence. Do not enable additional systems until their category records pass the native oracle.
