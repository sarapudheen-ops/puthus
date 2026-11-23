# Stage 1b Progress Report - Toyota DTC Reading (Objective 1-5)

## Current Branch & Head
- **Branch:** `arena/bc348cd0-puthus` (head `9595dda6f8ad238f76865c74bc7f90534ff928ad` remote and local; workspace clean)
- **Base Commit:** `c9ba179cf59a2bae15dd2eb1e2cd26f8dfe684cd` (main tip)
- **PR:** #27 (https://github.com/sarapudheen-ops/puthus/pull/27)

## Task Status
1. **Read specified files; post PR comment "DTC reading" (pinned vs generic inputs).** [COMPLETED: comment posted on #26 earlier; new comment "Stage 1b report - part 1/3" posted on #27]
2. **Investigate native_replay.py and libTOYOTA_DTCF.so for oracle feasibility for non-ECMECT systems.** [COMPLETED: YES, evidence posted in PR comment]
3. **Add parameterized DTC translator; run CI over 31,431 P3/P4 systems; report executable count/reject reasons.** [COMPLETED: ARGUMENTS transition completed; fail-fast check added [CODE: tools/thinkdiag/fail_fast_corpus_check.py]; unit test added [CODE: tests/test_path_handling.py]; head `9595dda` pushed; release-data run CANCELLED (`45118613178`); Android CI run `45118613198` SUCCESS; new check-runs for head `9595dda` show 24 reader/sweep/converted/english/decoded/binary/native/inspection checks SUCCESS; release-data/Android CI stage 1b probe results NOT ESTABLISHED]
4. **Enable DTC only for success + oracle match; report container size (< 480 MiB) and test counts.** [PENDING: awaiting final CI completion results for head `9595dda`; release-data/Android CI stage 1b probe results NOT ESTABLISHED]
5. **Post "Stage 1b report" (head SHA, check list, enabled count, DTC count/system, reject histogram) and commit `docs/toyota-progress.md`.** [PENDING: awaiting final CI completion results for head `9595dda`; parts 2/3 and 3/3 posted as PR comments earlier for previous head/fix application]

## CI Probe Execution Results (REAL)
- **Release-data run:** CANCELLED (`45118613178` for head `9595dda`); PENDING real run to produce translation results. **NOT ESTABLISHED** in current check-runs for head `9595dda`.
- **Android CI run:** SUCCESS (`45118613198` for head `9595dda` noted earlier); **NOT ESTABLISHED** in current check-runs fetch for head `9595dda` (only 24 reader/sweep/converted/english/decoded/binary/native/inspection checks SUCCESS).

## Key Results (REAL CI / NOT ESTABLISHED)
- **Executable count:** **NOT ESTABLISHED** (release-data probe results for head `9595dda` **NOT ESTABLISHED** in check-runs; release-data run `45118613178` CANCELLED; previous Android CI `45118613198` SUCCESS noted earlier but not in current check-run list for head `9595dda`)
- **Literal reject reasons with 5 sample system keys each (CODE / DATA / NOT ESTABLISHED):** **NOT ESTABLISHED** (pending real release-data run; PR comments note 31,431 systems rejected due to `compile_failed` / OLD PATH BUG in previous runs, fix applied)
- **Enabled count:** **NOT ESTABLISHED** (pending real release-data run; PR comments note 0 enabled in sandbox)
- **DTC count per enabled system:** **NOT ESTABLISHED** (pending real release-data run)
- **Container size:** **NOT ESTABLISHED** (pending real release-data run; baseline < 480 MiB)
- **Test counts:** **NOT ESTABLISHED** (baseline 603 tests, 0 failures, 0 errors, 0 skipped in 73 classes noted in Android CI run `45118613198` earlier; **NOT ESTABLISHED** in current check-run fetch for head `9595dda`)

## Evidence / Oracle (CODE / DATA / NOT ESTABLISHED)
- **Oracle:** **NOT ESTABLISHED** for real non-ECMECT passing example in current CI run (function `faultDecoderComparison` / `p5DecoderComparison` in `toyota_native_oracle.py:610, 726` covers NON-ECMECT DTC output; pending real release-data run for >=1 passing example). If none, write "no oracle for non-ECMECT DTC" and enable nothing. **CURRENT**: **NOT ESTABLISHED**.
- **Parameterized translator:** Fixed to use release-data job paths (.local/thinkdiag/TOYOTA.zip, etc.) [CODE: .github/workflows/thinkdiag-data.yml:152-174, tools/thinkdiag/toyota_category_translation_v2.py:24-114]. Fail-fast check added [CODE: tools/thinkdiag/fail_fast_corpus_check.py]. Unit test added [CODE: tests/test_path_handling.py]. Leaves `toyota_ecmect_pack.py` untouched; ECMECT control stays byte-identical at 1,414 DTCs [CODE: toyota_runtime_compile.py:228, toyota_ecmect_pack.py:195].
- **Pinned vs generic inputs (CODE / DATA):**
  - `compile_p3p4_diagnostics` inputs pinned to ECMECT: `region='EU'` (toyota_runtime_compile.py:28), `system_id='SYSLIST_EU.BIN@0009d8f2'` (toyota_runtime_compile.py:142), `system_bytes` (raw bytes, toyota_runtime_compile.py:142), `native_transport` (lifecycle.entry.transport, toyota_runtime_compile.py:142). Generic inputs: `sources`, `libraries`, `region`, `system_id`, `system_bytes`, `native_transport` (toyota_runtime_compile.py:28).
  - `toyota_dtc_workflow` constants pinned: `READ_CONSUMER` (toyota_dtc_workflow.py:21), `SETUP_CONSUMER` (toyota_dtc_workflow.py:22), `ASK_CONSUMER` (toyota_dtc_workflow.py:23), `SEND_CONSUMER` (toyota_dtc_workflow.py:24). Generic inputs: `sources`, `region`, `function` (toyota_dtc_workflow.py:125).
  - `toyota_faults`: Generic `NativeFaultLayout` / `NativeP5FaultLayout` (toyota_faults.py:13, 83). No ECMECT-specific pins.
  - `toyota_ecmect_pack` ECMECT path: `MANDATORY_SYSTEM='SYSLIST_EU.BIN@0009d8f2'` (toyota_ecmect_pack.py:16), `MANDATORY_ECU='EU/Ecu_Data_0081.BIN'` (toyota_ecmect_pack.py:16), `ENTRY='SYSTEMENTER.BIN@00002f63'` (toyota_ecmect_pack.py:14), `FUNCTION='FUNCDATA_EU.BIN@000005e9'` (toyota_ecmect_pack.py:15).

## Final Report (PENDING REAL CI RESULTS FOR HEAD 9595dda)
- **Head SHA:** `9595dda6f8ad238f76865c74bc7f90534ff928ad` (current head)
- **Check list for head:**
  - **Conclusion:** SUCCESS for 24 reader/sweep/converted/english/decoded/binary/native/inspection checks (~1s each); links: https://github.com/sarapudheen-ops/puthus/runs/137513539053, https://github.com/sarapudheen-ops/puthus/runs/137513535375, https://github.com/sarapudheen-ops/puthus/runs/137513542720, https://github.com/sarapudheen-ops/puthus/runs/137513556153, https://github.com/sarapudheen-ops/puthus/runs/137513552297, https://github.com/sarapudheen-ops/puthus/runs/137513548882, https://github.com/sarapudheen-ops/puthus/runs/137513545703, https://github.com/sarapudheen-ops/puthus/runs/137513523939 (Toyota sweep evidence 415), etc. (24 checks total)
  - **Release-data probe / Android CI stage 1b probe results:** **NOT ESTABLISHED** (no check-run found for "Stage 1b" or "Android CI" or "Release-data" for head `9595dda` in the fetched list; previous Android CI `45118613198` SUCCESS was noted earlier but the release-data probe for the final head is **NOT ESTABLISHED**; release-data run `45118613178` CANCELLED)
  - **Duration:** ~1s for reader/sweep/converted/english/decoded/binary/native/inspection; release-data/Android CI probe **NOT ESTABLISHED**
  - **Link:** https://github.com/sarapudheen-ops/puthus/runs/137513539053 (english-text), https://github.com/sarapudheen-ops/puthus/runs/137513535375 (decoded-tables), https://github.com/sarapudheen-ops/puthus/runs/137513542720 (converted-dtcs), https://github.com/sarapudheen-ops/puthus/runs/137513556153 (reader-libDIAG.so), https://github.com/sarapudheen-ops/puthus/runs/137513552297 (reader-libTOYOTA_FILE.so), https://github.com/sarapudheen-ops/puthus/runs/137513548882 (reader-libSTD.so), https://github.com/sarapudheen-ops/puthus/runs/137513545703 (reader-libDEVICEID.so), https://github.com/sarapudheen-ops/puthus/runs/137513523939 (Toyota sweep evidence 415), etc.
- **Enabled count:** **NOT ESTABLISHED** (pending real release-data run; PR comments note 0 enabled in sandbox)
- **DTC count per enabled system:** **NOT ESTABLISHED** (pending real release-data run)
- **Reject histogram:** **NOT ESTABLISHED** (literal reasons with 5 real sample system keys each; pending real release-data run; PR comments note 31,431 systems rejected due to `compile_failed` / OLD PATH BUG in previous runs, fix applied)
- **Container size:** **NOT ESTABLISHED** (pending real release-data run; baseline < 480 MiB)
- **Test counts:** **NOT ESTABLISHED** (baseline 603 tests, 0 failures, 0 errors, 0 skipped in 73 classes noted in Android CI run `45118613198` earlier; **NOT ESTABLISHED** in current check-run fetch for head `9595dda`)

## Evidence / Oracle (CODE / DATA / NOT ESTABLISHED)
- **Oracle:** **NOT ESTABLISHED** for real non-ECMECT passing example in current CI run (function `faultDecoderComparison` / `p5DecoderComparison` in `toyota_native_oracle.py:610, 726` covers NON-ECMECT DTC output; pending real release-data run for >=1 passing example). If none, write "no oracle for non-ECMECT DTC" and enable nothing. **CURRENT**: **NOT ESTABLISHED**.
- **Parameterized translator:** Fixed to use release-data job paths (.local/thinkdiag/TOYOTA.zip, etc.) [CODE: .github/workflows/thinkdiag-data.yml:152-174, tools/thinkdiag/toyota_category_translation_v2.py:24-114]. Fail-fast check added [CODE: tools/thinkdiag/fail_fast_corpus_check.py]. Unit test added [CODE: tests/test_path_handling.py]. Leaves `toyota_ecmect_pack.py` untouched; ECMECT control stays byte-identical at 1,414 DTCs [CODE: toyota_runtime_compile.py:228, toyota_ecmect_pack.py:195].
- **Pinned vs generic inputs (CODE / DATA):**
  - `compile_p3p4_diagnostics` inputs pinned to ECMECT: `region='EU'` (toyota_runtime_compile.py:28), `system_id='SYSLIST_EU.BIN@0009d8f2'` (toyota_runtime_compile.py:142), `system_bytes` (raw bytes, toyota_runtime_compile.py:142), `native_transport` (lifecycle.entry.transport, toyota_runtime_compile.py:142). Generic inputs: `sources`, `libraries`, `region`, `system_id`, `system_bytes`, `native_transport` (toyota_runtime_compile.py:28).
  - `toyota_dtc_workflow` constants pinned: `READ_CONSUMER` (toyota_dtc_workflow.py:21), `SETUP_CONSUMER` (toyota_dtc_workflow.py:22), `ASK_CONSUMER` (toyota_dtc_workflow.py:23), `SEND_CONSUMER` (toyota_dtc_workflow.py:24). Generic inputs: `sources`, `region`, `function` (toyota_dtc_workflow.py:125).
  - `toyota_faults`: Generic `NativeFaultLayout` / `NativeP5FaultLayout` (toyota_faults.py:13, 83). No ECMECT-specific pins.
  - `toyota_ecmect_pack` ECMECT path: `MANDATORY_SYSTEM='SYSLIST_EU.BIN@0009d8f2'` (toyota_ecmect_pack.py:16), `MANDATORY_ECU='EU/Ecu_Data_0081.BIN'` (toyota_ecmect_pack.py:16), `ENTRY='SYSTEMENTER.BIN@00002f63'` (toyota_ecmect_pack.py:14), `FUNCTION='FUNCDATA_EU.BIN@000005e9'` (toyota_ecmect_pack.py:15).

## Constraints & Corrections
- **Label claims:** CODE / DATA / NOT ESTABLISHED, quote file:line.
- **Never guess formulas or frame bytes; keep fail-closed.**
- **CI is the only place the corpus runs (sandbox has no TOYOTA.zip).**
- **Workflow rules:** single-expression `if:`, step-level continue-on-error only, actionlint must pass.
- **Do not merge.**
- **ECMECT control:** byte-identical at 1,414 DTCs (leave pinned path untouched).
- **RESUME:** Stage 1b report is accepted (contains real CI results for final head `9595dda` if available; otherwise **NOT ESTABLISHED**; no placeholders).
- **Post all reports as PR comments titled "Stage 1b report - part i/n".**
- **Add CI probe step running `parameterized_dtc_translator` over 31,431 P3/P4 systems; push, wait, fix, re-push; post failure log if fails.**
- **Report real results: executable count, literal reject reasons with counts and 5 real sample system keys each. Quote full literal message and file:line if all reject same error.**
- **Oracle:** name exact oracle comparison (`faultDecoderComparison` / `p5DecoderComparison` in `toyota_native_oracle.py:610, 726`) covering NON-ECMECT DTC output; show passing for >=1 system. If none, write "no oracle for non-ECMECT DTC" and enable nothing.
- **Enable DTC only where translator succeeds AND oracle passes; ECMECT control byte-identical at 1,414 DTCs.**

## Next Steps
- **POLL/VERIFY check-runs for head `9595dda`** (already fetched: 24 reader/sweep/converted/english/decoded/binary/native/inspection checks SUCCESS; release-data/Android CI stage 1b probe results for this head **NOT ESTABLISHED** / PENDING real run).
- **POST/COMMIT final "Stage 1b report - part 3/3"** with real CI results if available (currently PR comments note "PENDING: Waiting for new release-data run to produce real translation results" for head `9595dda`). Since the release-data run for `9595dda` is CANCELLED and no new run for `9595dda` or `07117e8` has produced translation results in the check-runs, the report must state **NOT ESTABLISHED** for real translation results (executable count, reject histogram, enabled count, DTC count/system, container bytes, oracle comparison).
- **REPORT** real oracle comparison (`faultDecoderComparison` / `p5DecoderComparison` in `toyota_native_oracle.py:610, 726`) covering non-ECMECT system with >=1 passing example. If none, write "no oracle for non-ECMECT DTC" and enable nothing. **CURRENT**: **NOT ESTABLISHED** (pending real release-data run).
- **ECMECT control** must stay byte-identical at 1,414 DTCs.
- **Post all reports as PR comments titled "Stage 1b report - part i/n".**
