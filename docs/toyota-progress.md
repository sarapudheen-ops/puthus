# Toyota/Lexus implementation progress

**Branch/PR:** `arena/59245dbb-puthus` (Stage 1b); [PR #27](https://github.com/sarapudheen-ops/puthus/pull/27) is the source branch that was merged in.
**Baseline head:** `c9ba179cf59a2bae15dd2eb1e2cd26f8dfe684cd` (main tip from which this branch was created).
**Terminology:** link status means *natively consistent (call-trace level), not vehicle-tested*.

## Stage status

| Stage | Status | Evidence / blockers |
|---|---|---|
| 0 — health and baseline | Complete | Baseline and evidence links below. |
| 1 — K-Line categories | In progress / Stage 1d enablement landed | The exact ECMECT strict-translator control passes in release-data CI (30 PIDs, 1,414 DTCs, 1 ecuInfo, byte-identical). Stage 1d enabled 108 compiled-group DTC-read-only systems via the deterministic group pin (see Stage 1d); the two pinned slices stay byte-identical and the delivered pack is unchanged. The transport-5 link class (93 group systems) stays unproven; clear is deliberately unresolved. Stage 1e made the release-data pipeline observable on main and fixed the promotion-comment posting (see Stage 1e). No physical validation is claimed. |
| 2 — Kotlin/app generalization | In progress — Stage 2a complete | Stage 2a: per-system link badge, literal per-category status, DTC-only contract lines, "Link verified" filter + name search over the selected vehicle's systems only (no corpus load), and JVM-gated executable actions so an unproven link never enables one. Evidence in the Stage 2a report comment on the PR. |
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

The documentation follow-up initially triggered a cancelled run; a subsequent push-triggered run on `86eac0303a5e437313365fb9d73609708f3fb704` completed successfully: [release-data run 45098479618](https://github.com/sarapudheen-ops/puthus/actions/runs/45098479618), 14m36s.

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

## Stage 1b — parameterized non-ECMECT DTC probe

STATUS: measured in release-data CI only. The sandbox has no `TOYOTA.zip`, so every number below comes
from a completed release-data run on the head recorded under *Measurements*. The identical numbers were
reproduced on two later heads, each on a green run: [45184668780](https://github.com/sarapudheen-ops/puthus/actions/runs/45184668780)
(head `672fe1896fb52932c1b781cf2b9a702657b3ab7c`) and [45187523338](https://github.com/sarapudheen-ops/puthus/actions/runs/45187523338)
(head `916facaec04aa2bdcd2b18a1c77661d95d6a557d`, probe step 18 success in 12s, probe wall clock 12.45s).
Each run posts its own probe report as a PR comment, and the run's own head SHA is what the Stage 1b
report comment cites.

### What changed (CODE)

- `tools/thinkdiag/parameterized_dtc_probe.py` is one execution per distinct `(Ecu_Data file, function, selector)`
  group. Every corpus path (`--archive`, `--decoded`, `--manifest`, `--english`, `--systems`,
  `--native-oracle`, `--ecmect-review-dir`) is supplied by the workflow; `fail_fast` opens the archive,
  the decoded members, both English tables, the decoded manifest, both pinned ECMECT review artifacts and
  the system ledger before anything is measured, and exits 2 with a `FAIL-FAST:` reason instead of
  recording a per-system reject.
- Mode A is the existing strict pinned translator (`toyota_category_translation.translate_categories`,
  the Stage 1 path). Mode B is `toyota_category_translation_v2.parameterized_dtc_group`, which calls
  `toyota_runtime_compile.compile_p3p4_diagnostics` (`toyota_runtime_compile.py:28`) on the exact framed
  37-byte `SYSLIST` row of the group representative (`toyota_structure.system_lists`). A compiler refusal
  becomes a literal reject reason string; a success is a candidate translation only.
- The probe writes no pack, no definition file and no check; it has no write functions.
- No oracle is claimed for non-ECMECT DTC output: `compare_fault_decoder(libraries)` (`toyota_native_oracle.py:609`)
  and `compare_p5_decoder(libraries, sources)` (`:726`) are decoder-level synthetic-vector comparisons and are
  not keyed by `(Ecu_Data file, function, selector)`, so they cannot produce a per-group verdict.
- Workflow ordering: the probe runs after the source join, native comparison, pack conversion and Code 1
  sweep, and before every `Publish ...` step. Every `Publish ...` step has step-level `continue-on-error: true`,
  so no publish failure can skip the probe and no probe result depends on a publish step.
- Group time budget is `--max-seconds 600` inside a 15-minute step timeout in a 45-minute job. Groups not
  reached are reported as `notProcessed`, never as a per-system reject.

### Measurements (release-data CI only)

Source run: [45181808200](https://github.com/sarapudheen-ops/puthus/actions/runs/45181808200),
head `09f5b96b33532d7dfbdd78cca6f74e79b2500c5b`, conclusion **success**, 13:13:36Z–13:29:23Z.
Probe step (job step 18, "Run Stage 1b parameterized DTC probe (group-based, read-only)") conclusion
**success**, 13:23:31Z–13:23:43Z; the probe's own wall clock 12.4s, peak RSS 146.4 MiB, 16 decoded source
files checksum-verified. Report comment: [Stage 1b probe report](https://github.com/sarapudheen-ops/puthus/pull/28#issuecomment-7102476324);
artifacts `stage-1b-probe.md` / `stage-1b-probe.json` in `ci-reports-v1`.

| Measure | Value |
|---|---:|
| System records read from `systems.jsonl.gz` | 287,030 |
| Eligible promotion-partition systems | 39,543 |
| Partition: transport-1 proven / exact pinned ECMECT / transport-5 unproven | 39,362 / 1 / 180 |
| Distinct (Ecu_Data file, function, selector) groups executed | 284 of 284 |
| Systems beyond one representative per group | 39,259 |
| Groups without a decision (budget, 600s cap) | 0 |

Mode A (existing strict pinned translator, group-executed): `pids`, `dtcs` and `ecuInfo` each have
1 executable group / 1 executable system and 283 rejected groups / 39,542 rejected systems;
`actuators`, `coding` and `specialFunctions` have 0 executable groups and 284 rejected groups / 39,543
rejected systems. Zero translator exceptions.

Mode A literal reject reasons (systems, five sample keys in the PR comment):
`ecmect_specific:mandatory_system_and_live_read_review` 39,542; `ecmect_specific:mandatory_system_and_p3p4_workflow`
39,542; `ecmect_specific:mandatory_system_and_ecu_identification` 39,542; `no_generic_translator:actuators`
39,542; `no_generic_translator:coding` 39,542; `no_generic_translator:specialFunctions` 39,542;
`ecmect_compiler_returned_no_definitions` 1 each for actuators, coding and specialFunctions
(`SYSLIST_EU.BIN@0009d8f2`).

Mode B (Stage 1b parameterized P3/P4 compile, one execution per group): **4 executable groups covering
203 systems, 5,353 DTC labels, 4 groups with a source `04`/`44` clear**; 280 rejected groups covering
39,340 systems; 0 groups without a decision. These are *candidate* translations only: not oracle-approved,
not enabled, and no pack or definition file is written.

The 4 executable groups (DATA, this run), all with read request `13B0` and clear request `04`:
`EU/Ecu_Data_0081.BIN` function 61 selector `0x11` — 79 systems, 1,414 labels, representative
`SYSLIST_EU.BIN@0009d8f2` (the pinned ECMECT row); `EU/Ecu_Data_0081.BIN` function 61 selector `0x51` —
79 systems, 1,414 labels, representative `SYSLIST_EU.BIN@00023995` (the pinned 2007 Innova candidate row);
`JP/Ecu_Data_0081.BIN` function 61 selector `0x11` — 30 systems, 1,111 labels, representative
`SYSLIST_JP.BIN@00003875`; `NA/Ecu_Data_0081.BIN` function 61 selector `0x51` — 15 systems, 1,414 labels,
representative `SYSLIST_NA.BIN@0002bf2a`. Two of the four are already-pinned rows, so only **2 groups /
45 systems** are new source-compilable candidates, and neither has an oracle.

Mode B literal reject reasons (groups / systems): `ValueError:No unambiguous exact DTC function-detail
source rows` 137 / 24,832; `ValueError:Not a source P3/P4 fault block` 60 / 8,112;
`ValueError:No exact single-frame primary P3/P4 fault-read primitive` 47 / 4,386;
`ValueError:Not the exact single KWP04/44 clear class` 25 / 1,369; `ValueError:Native caller mode 7/9 has
additional unported overrides` 7 / 547; `ValueError:Selected source system does not use ECUF dispatch kind 14`
4 / 94.

ECMECT control (DATA, this run): Mode A counts `{'pids': 30, 'dtcs': 1414, 'ecuInfo': 1}` on
`SYSLIST_EU.BIN@0009d8f2`, byte-identical to the enabled-pack output **True**, control **passed**; the
parameterized P3/P4 compile on the same row also returns **1,414 DTC labels**, read request `13B0`, clear
request `04`.

Oracle (NOT ESTABLISHED for non-ECMECT DTC): the run's native comparison contains
`faultDecoderComparison` (70,144 state vectors, 2,112 record vectors) and `p5DecoderComparison`
(49,152 record vectors, 395 source blocks), both with `nativeInstructionsExecuted: true`. Both are
decoder-level and not keyed by `(Ecu_Data file, function, selector)` (`toyota_native_oracle.py:609`, `:726`),
so **no oracle result for non-ECMECT DTC exists** and nothing is enabled. The 4 compiled groups remain
candidates; enabling any of them would require a per-group oracle that does not exist yet.

## Stage 1c — group-keyed DTC oracle

STATUS: every mechanism and the ranking below are CODE/DATA; per-group oracle numbers are filled only from a
completed release-data run (cited under *Per-group oracle result*). The sandbox has no `TOYOTA.zip`, so no
native vector was executed locally.

### Why the existing comparisons cannot approve a group (CODE)

`faultDecoderComparison` (`toyota_native_oracle.py:609`) and `p5DecoderComparison` (`:726`) choose their own
layout grid — eight assembly/stride pairs x two modes x two presence flags, and the P5 start/width grid — and
neither takes a `(Ecu_Data file, function, selector)` group. Stage 1c keeps the same bounded sandbox and the same
native consumers but takes the layout from one compiled group:

| Leg | Native consumer | Compiled / Python side | What a pass means |
|---|---|---|---|
| layout | `GetDTCCodeFromAnsP3P4` @0x14ae8; layout word and `context+0x18`/`context+0x1b0` built exactly as `compare_fault_decoder` builds them | `NativeFaultLayout.decode` (`toyota_faults.py:41`) with the group's own count index, record start/width, assembly kind, presence flag and mode low-bits | native projection equals the port for that group's own kinds, values and payload shapes |
| linkage | same consumer | `encode_row`/`build_payload` (`toyota_group_oracle.py`) with the group's compiled `nativeCode` set | each sampled compiled DTC code survives the group's own record width and returns as itself |
| display | `GetShowDtcInfo` @0x11f90 with the DTCF display imports | `FaultLabels` (`toyota_dtc_labels.py:59`) and the group's compiled `code`/`description` | native renders exactly the compiled pair for every compiled key of the group |

Not covered, and therefore NOT ESTABLISHED: ECU replies (payload vectors are synthesised), P5 auxiliary
freeze-frame/mask side tables and mask discovery, transport/lifecycle negotiation, and per-system vehicle
applicability. The record-leg mask is `0xFF` for every group; the state half of each pair is covered
exhaustively by the global comparison, so the group-keyed claim is the DTC code projection and the label set.

### Pinned values the compiling groups satisfied (CODE)

`compile_p3p4_diagnostics` (`toyota_runtime_compile.py:28`) and `compile_dtc_workflow`
(`toyota_dtc_workflow.py:173`) gate on these, so a group that compiles has satisfied all of them:

- `toyota_runtime_compile.py:36-39` exact regional SYSLIST record: region in EU/NA/JP, a complete 37-byte row,
  native transport 1..5 and a `SYSLIST_<region>.BIN@` id.
- `:41-43` caller mode (`system_bytes[33]`) is not 7 or 9.
- `:44-46` the region's `FUNCDATA_<region>.BIN` index contains the function and `compile_dtc_workflow` accepts the
  block: `toyota_dtc_workflow.py:177-179` (`byte1` is 1 or 2, i.e. a source P3/P4 fault block), `:180-183` at most
  one initial template, `:184-191` that template binds to exactly one non-empty request, `:194-200` query kinds in
  1..10 with at most five conditions.
- `toyota_runtime_compile.py:54-63` every function-detail row is unconditional (no `commandCondition`) and detail
  ids are non-empty and unique.
- `:74-79` exactly one single-frame primary kind-1 fault-read primitive with an explicit positive service.
- `toyota_dtc_labels.py:117-135` (`compile_p3p4_layout`): `byte1 == 1`, a decodable kind (kind 5 is refused,
  `:127-131`), mode low-bits 0..7, availability mask 0, and no missing exact TCODE key.
- `toyota_runtime_compile.py:95-102` the clear block is a single query of the exact KWP `04`/`44` class.
- `:105` -> `toyota_ecuprogram.py:123-127` the system uses ECUF dispatch kind 14 with the exact
  `09 04` / `FF FF` / `49 04` identity frame.

The two groups this stage targets satisfy all of the above by construction of a successful compile. What they do
**not** have is any of the enable pins listed under *Enablement*.

### Reject reasons ranked by systems unlocked if that blocker were ported (DATA, Stage 1b run 45191744205)

| Rank | Literal reject reason | Groups | Systems |
|---:|---|---:|---:|
| 1 | `ValueError:No unambiguous exact DTC function-detail source rows` | 137 | 24,832 |
| 2 | `ValueError:Not a source P3/P4 fault block` | 60 | 8,112 |
| 3 | `ValueError:No exact single-frame primary P3/P4 fault-read primitive` | 47 | 4,386 |
| 4 | `ValueError:Not the exact single KWP04/44 clear class` | 25 | 1,369 |
| 5 | `ValueError:Native caller mode 7/9 has additional unported overrides` | 7 | 547 |
| 6 | `ValueError:Selected source system does not use ECUF dispatch kind 14` | 4 | 94 |

Ranking basis: systems behind each reason, i.e. the systems that would move forward if only that blocker were
ported. Rank 1 alone would unlock more systems than everything else combined (24,832 of 39,340 rejected).

### Oracle result per compiled group (DATA, release-data run 45222723609, head `08fd98f`)

| Group | Systems | DTC labels | Compiled layout `count,start,bytes,asm,presence,mode` | Read | Clear | Oracle ran | Layout / Linkage / Display vectors | Result |
|---|---:|---:|---|---|---|---|---|---|
| `EU/Ecu_Data_0081.BIN`, fn 61, sel `0x11` | 79 | 1,414 | `0,1,2,2,False,1` | `13B0` | `04` | yes | 99 / 34 / 1,414 | **PASS** |
| `EU/Ecu_Data_0081.BIN`, fn 61, sel `0x51` | 79 | 1,414 | `0,1,2,2,False,1` | `13B0` | `04` | yes | 99 / 34 / 1,414 | **PASS** |
| `JP/Ecu_Data_0081.BIN`, fn 61, sel `0x11` | 30 | 1,111 | `0,1,2,2,False,1` | `13B0` | `04` | yes | 66 / 34 / 1,111 | **PASS** |
| `NA/Ecu_Data_0081.BIN`, fn 61, sel `0x51` | 15 | 1,414 | `0,1,2,2,False,1` | `13B0` | `04` | yes | 99 / 34 / 1,414 | **PASS** |

All four compiled groups describe the same record shape — count byte at 0, records from 1, two record bytes,
assembly kind 2, no presence flag, mode low-bits 1 — so the decoder agreement is one shape confirmed four times,
not four independent shapes. Result digests are recorded per leg in the run's own report.

What the pass does **not** say: the payloads are synthesised, the record path is confirmed but no ECU reply was
ever seen, and none of the P5 auxiliary side tables, mask discovery, transport or lifecycle behaviour is covered.
Two probe iterations were needed to reach a clean pass and both were caught by the control: run
`45210440758` compiled 0 groups (the compile was handed the native `.so` mapping instead of the English tables:
`KeyError:'TCODE_EN.GAG'`), and run `45218407745` passed layout and display but failed linkage only on the label
whose native code is `00000000`, where native and the port both correctly return no record.

### ECMECT control (DATA, same run)

- Pinned control row `SYSLIST_EU.BIN@0009d8f2`: 1,414 compiled DTC labels, required exactly 1,414 — satisfied.
- The control row's own group-keyed oracle: PASS.
- The pinned ECMECT packet path is untouched by this stage; its enabled output stays byte-identical at
  30 PIDs / 1,414 DTCs / 1 ecuInfo.

### Enablement (decision)

Groups enabled: **0**. Systems enabled: **0**. New DTC definitions enabled: **0**. DTCs per enabled system are
unchanged: ECMECT 1,414 and the pinned 2007 Innova slice 1,414.

A group-keyed oracle pass is necessary but not sufficient, and the three blockers are exactly:

1. `no_group_enable_path` — `convert_vehicle_packs.py:388-400` enables only the pinned ECMECT identity
   (`toyota_ecmect_pack.enable`, `toyota_ecmect_pack.py:216`) and the pinned 2007 `systemId`
   (`toyota_innova_2007_pack.enable` over `toyota_innova_2007_reads.py:20`); no path accepts a group key.
2. `no_pinned_group_artifact` — `tools/thinkdiag/schema/` holds only `mandatory-read-artifacts-v1.json`,
   `native-oracle-v1.json` and `innova-2007-source-read-candidate-v1.json`; there is no deterministic
   expected-output pin for a compiled group definition.
3. `per_system_link_projection` — `convert_vehicle_packs.py:370-390` marks a system proven only from
   `self.oracle_evidence[oracle_key]` with a matching `projectionDigest` and a lifecycle projection, and
   `convert_vehicle_packs.py:445` sets `enabled` only when `linkStatus == "proven"` and a category is executable.

### Stage 1c cost (DATA, same run)

- Oracle step wall clock 18.1 s, peak RSS 163.9 MiB (whole inspect job stays inside the 45-minute budget).
- Container: 421,223,645 bytes (401.7 MiB), under the 480 MiB limit and under the 460 MiB review threshold.
- Tests in the same run's own discovery: `Ran 311 tests ... OK (skipped=1)`.

## Stage 1d — compiled-group DTC read-only enablement

STATUS: complete. All numbers below are DATA from the cited release-data runs; claims marked CODE are
implementation facts. No physical ECU validation is claimed.

### The gate that kept enabled at 0 (DATA)

`tools/thinkdiag/schema/dtc-group-enable-v1.json` is the deterministic expected-output pin: an empty
`groups` list enables nothing, and a group enables only when its representative's measured
`definitionSha256`/`definitionHash` match byte-for-byte. Round 1 pushed the empty pin (head `d133329`,
run 45310645082): the gate `group_pin_absent_or_mismatched:no pin entry` held every system at
`unresolved-pin` — `- Pinned groups: 0 (none); Groups: 4; members: 201; qualified: 0; enabled: 0;
unresolved: 201`. Round 2 (head `4129ad2`, run 45313528051) filled the pin with exactly the round-1
measured hashes; enablement followed.

### Per-system qualification (DATA, final run 45318134431, head `45177ba`)

`Groups: 4; members: 203; qualified: 108; enabled: 108; unresolved: 93; pinned-path: 2` — 203/203
systems accounted for.

| Group (Ecu_Data, fn 61) | Representative | Members | Enabled | Unresolved | Pinned-path | Literal failure reason |
|---|---|---|---|---|---|---|
| EU/Ecu_Data_0081.BIN `0x11` | `SYSLIST_EU.BIN@0009d8f2` | 79 | 78 | 0 | 1 | — |
| EU/Ecu_Data_0081.BIN `0x51` | `SYSLIST_EU.BIN@00023995` | 79 | 0 | 78 | 1 | `link_unproven:link_representation_unsupported:transport-5-flags-33` |
| JP/Ecu_Data_0081.BIN `0x11` | `SYSLIST_JP.BIN@00003875` | 30 | 30 | 0 | 0 | — |
| NA/Ecu_Data_0081.BIN `0x51` | `SYSLIST_NA.BIN@0002bf2a` | 15 | 0 | 15 | 0 | `link_unproven:link_representation_unsupported:transport-5-flags-33` |

The two `pinned-path` rows are the pinned ECMECT and Innova slices (`SYSLIST_EU.BIN@0009d8f2`,
`SYSLIST_EU.BIN@00023a75`): byte-identical packs, never group-enabled. All 93 failures are the
transport-5 link representation gate (Stage 1's unresolved class); no member failed its definition hash.

### The shipped slice is DTC READ only (CODE, deliberate)

The shipped group slice is `read` + `layout` (labels) only, `readOnly=true`: executable `dtcs`
category, `pids`/`ecuInfo` unresolved. ECMECT's DTC read depends on the 10 5F session-change workflow
(`EcuWorkbench.kt:65` → `ToyotaFaultWorkflow.kt:127,:157`); ECMECT never ships a clear
(`toyota_ecmect_pack.py:163`, `:258-259`) and no clear exchange is probe-verified (the probes are
read-only), so clear cannot be shown to follow a verified shipped-clear path. Clear stays unresolved
with the literal reason `clear_unresolved:read-only-slice-never-exposes-clearing`
(`groupDtcEnable.clearReason`; `verify_group_dtc_system` rejects any shipped clear or workflow). The
compiled workflow (10 5F) is likewise unshipped; the DTC read is the plain documented request
(`ToyotaWorkflowTest.dtcOnlySliceReadsWithoutSessionAndNeverClears`). No safety guard, confirmation
or session requirement was weakened.

### Final enabled counts (DATA)

- Enabled systems: **110** (2 pinned + 108 group DTC-read-only) — acceptance evidence, run 45313528051.
- DTCs per enabled system: 1,414 labels for each of the 78 EU `0x11` systems; 1,111 labels for each of
  the 30 JP systems. Pinned slices unchanged (30 PIDs/1,414 DTCs/1 ECU-info + the Innova slice).
  Total executable definitions: **146,512** = 2,890 pinned + 78×1,414 + 30×1,111.
- Promoted on-demand container: **424,385,502 bytes** (404.7 MiB; cap 503,316,480 = 480 MiB) —
  qualification check-run, run 45318134431. Deterministic; reproduces on the final head's run.
- ECMECT and Innova packs stay byte-identical (pinned-path rows; the delivered pack, baseline
  conversion and `prepare-vehicle-packs` are untouched).

### Verification (CODE)

- `verify_vehicle_bundle.py:137-155`: enabled set = the two pinned slices + pin-verified extras
  (`verify_group_dtc_system`: pin entry, representative `definitionSha256`, per-member `definitionHash`
  over the shipped read surface, read-only slice shape, category truthfulness, `validate_system`).
- Kotlin `VehiclePackCatalog.kt:212-218` accepts DTC-only enabled systems; JVM tests
  `VehiclePackScenarios.dtcOnlyReadOnlySystemIsAcceptedByTheProductionValidator` and
  `dtcOnlyCategoryTruthfulnessStaysEnforced` prove acceptance and the category gate.
- Tests: Python `Ran 328 tests OK (skipped=1)` (`python3 -m unittest discover -s tools/thinkdiag
  -p 'test_*.py'`); JVM additions `VehiclePackTest` +2 and `ToyotaWorkflowTest` +1 (fast unit tests).
- Not established: physical ECU/transport validation, the transport-5 link representation (the 93
  unresolved systems), any clear or session capability, and Android delivered-pack wiring (the
  delivered container keeps the pinned two slices only).

## Stage 1e — release-data observability on main and the promotion-step root cause

Scope: `.github/workflows/thinkdiag-data.yml` only (no pack, converter, or Kotlin change; Python tests unchanged at `Ran 328 tests OK (skipped=1)`). No step's published targets were changed.

**Trigger.** `push.branches` now includes `main` alongside `arena/**` (`thinkdiag-data.yml:11`); `workflow_dispatch` is unchanged. Overwrite analysis: every `ci-reports-v1` asset uses a fixed name uploaded with `--clobber` (`code1-link-evidence.json`, `code1-link-promotion.md/.json`, `stage-1b-probe.md/.json`, `stage-1c-oracle.md/.json`, `code1-lifecycle-sweep.md/.json`, `code1-link-evidence-summary.md/.json`, `stage-0-report.md`), so a run on main **would overwrite** the same-named assets from arena runs and vice versa (newest run wins). Check-runs are keyed per `head_sha`, PR comments are append-only, and actions artifacts (`toyota-vehicle-packs`, `toyota-recovered-data`, `toyota-data-inspection`) are per-run: those are never overwritten. Non-release publishers: check-runs only for the Innova join, ECU program input, native differential, convert-step log, Innova read candidate, source link evidence, sweep evidence (`sweep_reports.py publish`), static-analysis and DTC-shard loops, inspection report, and source-input recovery (`source_input_evidence.py publish`).

**Masked-failure visibility.** All 13 `continue-on-error` steps now carry explicit `id`s (`code1_lifecycle_sweep`, `publish_ecu_input`, `publish_native_outcome`, `publish_link_promotion`, `publish_stage1b_report`, `publish_stage1c_report`, `publish_sweep_reports`, `publish_convert_log`, `publish_sweep_evidence`, `publish_static_reports`, `publish_dtc_shards`, `publish_inspection_check`, `publish_stage0_report`). A final `if: always()` step (`thinkdiag-data.yml:544`) publishes the raw `steps.<id>.outcome` for all of them (raw outcome is not masked; step conclusions and the job conclusion are) plus the last 80 lines of the promotion step's tee'd log (`exec > >(tee .local/thinkdiag/report/promotion-step.log) 2>&1`, `thinkdiag-data.yml:285`) into the check-run **"Toyota masked-step outcomes"** (its conclusion is `failure` iff any masked step's raw outcome is `failure`). On run 45320524658 two steps exited 1 (each produced a `failure` annotation "Process completed with exit code 1." on the job check-run): `publish_link_promotion` (root-caused below) and one further masked step — the annotations carry no step identity (`path: .github`, `start_line` 25/48 do not map to a step), so the second step is named by the per-run outcome table.

**Promotion step root cause — a script error.** "Publish Code 1 link-promotion report" posts its comment to **PR #25** (hardcoded `issues/25/comments`, `thinkdiag-data.yml:316`) and then verifies the exact bodies (`thinkdiag-data.yml:318`). The failure is **not** the PR number (PR #25 is merged since 2026-11-22T16:15Z, yet ten comment pairs landed on it afterwards, through 2026-11-23T09:48:55Z, run 45148221180, head `c7ecc63be686756a01aef888b99897b0c1eaf863`) and **not** permissions (`permissions.pull-requests: write` unchanged since that head; sibling steps post `issues/{n}/comments` successfully on push runs today). It is a **Python syntax error** on that verification line: the `--jq` argument quoting was corrupted to `"".[].body"]` (unterminated string literal), so the poster heredoc dies at parse time before posting anything. The break was introduced between heads `c7ecc63b…` and `09f5b96b` (run 45181808200, first run with no post), in the same edit that wrapped the step in `continue-on-error: true` — masking the failure it introduced. The report generator and `gh release upload` still completed in every failing run (assets `code1-link-promotion.md/.json` were refreshed inside each failing run's step window, e.g. 03:52:28–29 on run 45320524658), which is why only the comments vanished. Fixed at the root (valid Python restored on the verification line); step-level `continue-on-error` kept; the publish target (PR #25) unchanged.

**Known main-run caveat (left unchanged per "do not change what any step publishes").** On a main push `gh pr view "$GITHUB_REF_NAME"` resolves no PR: `publish_stage0_report`'s unguarded lookup (`thinkdiag-data.yml:540`) will fail raw and appear in the outcome table; the Stage 1b/1c comment steps guard with `|| true` and skip (`:333`, `:385`); the sweep publisher guards with `if [ -n "$PR_NUMBER" ]` (`:406`).

**NOT ESTABLISHED.** (a) Which masked step produced the second exit-1 annotation on run 45320524658 (and on run 45318134431) — it reproduces but is only named by the per-run "Toyota masked-step outcomes" table. (b) Job-log downloads remain unreachable from the agent sandbox (blob redirect EOF on `productionresultssa15.blob.core.windows.net` and `results-receiver.actions.githubusercontent.com`); the tee'd promotion log tail in the check-run is the readable substitute.

## Stage 2a — the app shows what the data really supports

Scope: Kotlin UI + JVM tests only — `app/src/main/java/com/bod3/app/vehiclepack/PackSystemStatusUi.kt`
(new), `app/src/main/java/com/bod3/app/ui/screens/ToyotaVehiclePacksScreen.kt`, and tests in
`app/src/test/java/com/bod3/app/vehiclepack/VehiclePackScenarios.kt` / `VehiclePackTest.kt`. No converter,
validator, write-safety guard, workflow, pack or Python change; ECMECT and Innova packs stay
byte-identical; androidTest stays at the existing 7 tests.

**Per-system evidence display.** Every system card (systems list and system detail) shows a link-status
badge: `linkStatus=proven` renders the exact text "Link natively consistent, not vehicle-tested";
unproven renders "Link not verified: \<literal linkReasons>". Each card then lists the literal
per-category status (`pids: executable`, `dtcs: unresolved`, `ecuInfo: unknown`, …) and the literal
disabling reasons (`Reason: <keys>`), and DTC-only systems (dtcs the only executable category) add the
two contract lines "Reads DTCs only; clearing is not available" and "ECU may reject the read without a
session; not vehicle-tested".

**Link-verified filter and name search.** The systems list gains a case-insensitive name search field
and a "Link verified" toggle chip; both run through `PackSystemStatusUi.filter` over the selected
vehicle's pinned system descriptors only (validator bound: 1,024 loaded with that vehicle,
`VehiclePackCatalog.kt:163`). The 287,030-row source corpus is never loaded or indexed here — the
source library keeps its paginated SQL query path (`SourceBrowser`, PAGE_SIZE 40). Filter state is
local UI state keyed to the selected variant.

**Executable-action gating.** The five feature actions in the detail view (live, fault codes, tests,
coding, ECU information) are enabled only when `PackSystemStatusUi.executableActions` includes them:
link status is checked first, so an unproven link yields an empty action set even when a category
claims executable — an unproven system can never show an enabled executable action.

**Tests (JVM, fast).** `VehiclePackTest` +4 wrappers over `VehiclePackScenarios`:
`linkStatusBadgeAndCategoryLinesAreLiteral`, `dtcOnlySystemsStateTheReadOnlyContract`,
`linkVerifiedFilterAndNameSearchStayWithinSelectedVehicleSystems`,
`unprovenLinkNeverOffersExecutableActions`. Python tests unchanged: `OK (skipped=1)`
(`python3 -m unittest discover -s tools/thinkdiag -p 'test_*.py'`).

**Evidence.** Android CI passed on the code head `7023d45` (push run 45465273798, PR run 45465331592,
each `610 tests, 0 failures, 0 errors, 0 skipped in 73 classes`; Android 24 device job green with the
existing 7 instrumented tests). The final head adds a comment-only `.github/workflows/android.yml`
touch so the paths-filtered actionlint workflow (`pull_request` → `.github/**`) runs on the same SHA
as release-data and Android CI. The instrumented suite (7 tests, unchanged) passed on the code head
`7023d45` in the push run's Android 24 device job; one later device attempt on the final head was
cancelled by that job's own 20-minute timeout while the emulator hung at APK install, with identical
app code. Check-runs for the final push SHA, the masked-step outcome table and the file:line change
list are in the "Stage 2a report" comment on this PR.

**NOT ESTABLISHED.** Physical/vehicle validation for any link; the transport-5 link class; any clear,
session, tests/coding/special-functions capability; any new enabled system or category (the enabled
set stays 110 and the two pinned slices stay byte-identical); instrumented coverage of the new
filter UI (androidTest stays at the existing 7 tests).

## Evidence audit / next work

CODE: ECMECT PIDs originate from `compile_mandatory_source_reads` calling `review_direct21` (`toyota_runtime_compile.py:114-145`), then `toyota_ecmect_pack.data_for` injects the 30 reviewed `liveReads` (`toyota_ecmect_pack.py:160-173`). Direct21 review items are disabled candidates (`toyota_live.py:313-318`). DTC/ECU-info definitions come from the same pinned compiler artifact (`toyota_runtime_compile.py:142-145`, `toyota_ecmect_pack.py:160-173`); `test_ecmect_pack.py` verifies 30/1,414/1 against the selected pack, and `verify_source_read_review` checks a deterministic compiler-output pin (`toyota_runtime_compile.py:173-220`, `tools/thinkdiag/schema/mandatory-read-artifacts-v1.json`). This is source/compiler regression evidence, not a native replay of emitted category records. **No oracle for non-ECMECT PID, DTC, or ecuInfo output is established.** No human sign-off hash is established beyond source/artifact SHA pins. Mode B must remain candidate-parse, not enabling.

**Next work:** rank source-bound read-only work from the successful report's literal Mode B and rejection histograms; then implement and test one category only when both executable translation and an oracle comparison exist. Keep Mode B non-enabling. Preserve the existing two enabled systems until another category clears those gates. No merge.
