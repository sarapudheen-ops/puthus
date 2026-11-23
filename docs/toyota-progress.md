# Stage 1b Progress Report - Toyota DTC Reading (Objective 1-5)

## Current Branch & Head
- **Branch:** `arena/bc348cd0-puthus` (head `07117e8` after fix)
- **Base Commit:** `c9ba179cf59a2bae15dd2eb1e2cd26f8dfe684cd` (main tip)
- **PR:** #27 (https://github.com/sarapudheen-ops/puthus/pull/27)

## Task Status
1. **Read specified files; post PR comment "DTC reading" (pinned vs generic inputs).** [COMPLETED: comment posted on #26 earlier; new comment "Stage 1b report - part 1/3" posted on #27]
2. **Investigate native_replay.py and libTOYOTA_DTCF.so for oracle feasibility for non-ECMECT systems.** [COMPLETED: YES, evidence posted in PR comment]
3. **Add parameterized DTC translator; run CI over 31,431 P3/P4 systems; report executable count/reject reasons.** [IN PROGRESS: translator fixed to use release-data job paths (.local/thinkdiag/TOYOTA.zip, etc.) [CODE: .github/workflows/thinkdiag-data.yml:152-174]; fail-fast check added [CODE: tools/thinkdiag/fail_fast_corpus_check.py]; unit test added [CODE: tests/test_path_handling.py]; pushing and waiting for release-data run]
4. **Enable DTC only for success + oracle match; report container size (< 480 MiB) and test counts.** [PENDING: awaiting CI completion]
5. **Post "Stage 1b report" (head SHA, check list, enabled count, DTC count/system, reject histogram) and commit `docs/toyota-progress.md`.** [PENDING: awaiting CI completion]

## CI Probe Execution Results (REAL)
- **Release-data run:** PENDING (waiting for push `07117e8` to trigger run)
- **Android CI run:** PENDING

## Key Results (PENDING CI)
- **Executable count:** NOT ESTABLISHED (pending CI)
- **Literal reject reasons with 5 sample system keys each (CODE / DATA / NOT ESTABLISHED):** NOT ESTABLISHED (pending CI)
- **Enabled count:** NOT ESTABLISHED (pending CI)
- **DTC count per enabled system:** NOT ESTABLISHED (pending CI)
- **Container size:** NOT ESTABLISHED (pending CI; baseline < 480 MiB)
- **Test counts:** NOT ESTABLISHED (pending CI; baseline 603 tests)

## Evidence / Oracle (CODE / DATA / NOT ESTABLISHED)
- **Oracle:** YES. Native fault read/parse can be replayed as an oracle for DTC output of a non-ECMECT system, verified against native code `libTOYOTA_DTCF.so` via `NativeSandbox` (native_replay.py:49) and `faultDecoderComparison` / `p5DecoderComparison` (toyota_native_oracle.py:610, 726). In CI, the oracle comparison will pass for >=1 system if the corpus (TOYOTA.zip) is available at .local/thinkdiag/TOYOTA.zip [CODE: .github/workflows/thinkdiag-data.yml:152-174].
- **Parameterized translator:** Fixed to use release-data job paths (.local/thinkdiag/TOYOTA.zip, etc.) [CODE: tools/thinkdiag/toyota_category_translation_v2.py:24-114]. Fail-fast check added [CODE: tools/thinkdiag/fail_fast_corpus_check.py]. Unit test added [CODE: tests/test_path_handling.py]. Leaves `toyota_ecmect_pack.py` untouched; ECMECT control stays byte-identical at 1,414 DTCs.
- **Pinned vs generic inputs (CODE / DATA):**
  - `compile_p3p4_diagnostics` inputs pinned to ECMECT: `region='EU'` (toyota_runtime_compile.py:28), `system_id='SYSLIST_EU.BIN@0009d8f2'` (toyota_runtime_compile.py:142), `system_bytes` (raw bytes, toyota_runtime_compile.py:142), `native_transport` (lifecycle.entry.transport, toyota_runtime_compile.py:142). Generic inputs: `sources`, `libraries`, `region`, `system_id`, `system_bytes`, `native_transport` (toyota_runtime_compile.py:28).
  - `toyota_dtc_workflow` constants pinned: `READ_CONSUMER` (toyota_dtc_workflow.py:21), `SETUP_CONSUMER` (toyota_dtc_workflow.py:22), `ASK_CONSUMER` (toyota_dtc_workflow.py:23), `SEND_CONSUMER` (toyota_dtc_workflow.py:24). Generic inputs: `sources`, `region`, `function` (toyota_dtc_workflow.py:125).
  - `toyota_faults`: Generic `NativeFaultLayout` / `NativeP5FaultLayout` (toyota_faults.py:13, 83). No ECMECT-specific pins.
  - `toyota_ecmect_pack` ECMECT path: `MANDATORY_SYSTEM='SYSLIST_EU.BIN@0009d8f2'` (toyota_ecmect_pack.py:16), `MANDATORY_ECU='EU/Ecu_Data_0081.BIN'` (toyota_ecmect_pack.py:16), `ENTRY='SYSTEMENTER.BIN@00002f63'` (toyota_ecmect_pack.py:14), `FUNCTION='FUNCDATA_EU.BIN@000005e9'` (toyota_ecmect_pack.py:15).

## Final Report (PENDING CI)
- **Head SHA:** `07117e8` (last push)
- **Check list for head:** NOT ESTABLISHED (pending CI)
- **Enabled count:** NOT ESTABLISHED (pending CI)
- **DTC count per enabled system:** NOT ESTABLISHED (pending CI)
- **Reject histogram:** NOT ESTABLISHED (pending CI)
- **Container size:** NOT ESTABLISHED (pending CI; baseline < 480 MiB)
- **Test counts:** NOT ESTABLISHED (pending CI; baseline 603 tests)

## Constraints & Corrections
- **Label claims:** CODE / DATA / NOT ESTABLISHED, quote file:line.
- **Never guess formulas or frame bytes; keep fail-closed.**
- **CI is the only place the corpus runs (sandbox has no TOYOTA.zip).**
- **Workflow rules:** single-expression `if:`, step-level continue-on-error only, actionlint must pass.
- **Do not merge.**
- **ECMECT control:** byte-identical at 1,414 DTCs (leave pinned path untouched).
- **RESUME:** Stage 1b report is accepted (contains real CI results for final head `c9ba179`); no placeholders; if unknown, write NOT ESTABLISHED.
- **Post all reports as PR comments titled "Stage 1b report - part i/n".**
- **Add CI probe step running `parameterized_dtc_translator` over 31,431 P3/P4 systems; push, wait, fix, re-push; post failure log if fails.**
- **Report real results: executable count, literal reject reasons with counts and 5 real sample system keys each. Quote full literal message and file:line if all reject same error.**
- **Oracle:** name exact oracle comparison (`faultDecoderComparison` / `p5DecoderComparison` in `toyota_native_oracle.py:610, 726`) covering NON-ECMECT DTC output; show passing for >=1 system. If none, write "no oracle for non-ECMECT DTC" and enable nothing.
- **Enable DTC only where translator succeeds AND oracle passes; ECMECT control byte-identical at 1,414 DTCs.**

## Next Steps
- **Push fix and wait for release-data run.**
- **Fix and re-push until the translator has really run.**
- **Post failure log if fails. Do not stop on a failure.**
- **Report real results and commit `docs/toyota-progress.md`.**
- **PR #27 remains open; no merge.**
