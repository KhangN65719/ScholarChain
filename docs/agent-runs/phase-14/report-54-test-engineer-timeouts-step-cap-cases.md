# Report for work order 54 (test-engineer): harness cases for the new timeouts and the 4,800 s S step cap

Archived by the orchestrator as received from test-engineer (2026-11-15 UTC). The agent ran nothing; the human's runs are noted at the end.

STATUS: COMPLETE for authoring. Nothing has been run: the guard blocks agent `bash -n`, `shellcheck` and the harness, so every expectation below comes from reading the files.

OWNER: test-engineer

WORK ORDER: 54

**Stale cases (every case that asserted an old value)**
- I read all of `tests/scripts/run.sh` (lines 1 to 4518). One case was stale: `14.terraform.s-overlay.timeouts-are-7200-1440-3600-1800` (expected step 3600, name carried 3600).
- I renamed it to `14.terraform.s-overlay.timeouts-are-7200-1440-4800-1800` and it now expects 4800. The four assertions are the same.
- No case asserted the old 8040 worst case or an on-node 1500 for Spark or ER.
- `14.terraform.s-overlay.comment-arithmetic-and-the-module-worst-case-figure-match-the-values` computes both figures from the overlay values it reads. It follows the new text (6840 and 9240) without an edit.
- `14.terraform.rule-a.the-evaluator-in-this-harness-is-exact-at-the-boundary` feeds the evaluator its own numbers (5640, 1440, 3600). It does not read the overlay, so it is unaffected.
- The module and course defaults case (`14.terraform.rule-a.module-defaults-pass-it-and-agree-with-the-course-variables`) is unaffected. The module default step is still 3600 in `modules/workflow/variables.tf`.

**Test plan (item to case)**

| Item | Case |
|---|---|
| Spark limit default 3000, overridable, status 124 documented | `14.emr.timeouts.spark-limit-default-is-3000-overridable-with-sc-spark-timeout-and-status-124-is-documented` |
| ER limit is `SC_ER_TIMEOUT` with default 3000, no `timeout 1500` on the ER line | `14.emr.timeouts.er-limit-is-sc-er-timeout-default-3000-and-the-old-1500-literal-is-gone-from-the-er-line` |
| Hadoop stage limit still 1500 | `14.emr.timeouts.hadoop-stage-limit-stays-1500-and-comes-before-the-er-line` |
| Rule A 6840 against 7200 and worst case 9240 read from the files | `14.terraform.s-overlay.rule-a-and-worst-case-figures-in-the-text-are-read-from-the-files-and-match-d32` |
| Spark 3000 + 874 < 4800, computed from the values read | `14.emr.timeouts.spark-limit-plus-the-run-4-pre-spark-time-stays-under-the-s-step-cap` |

- **Spark case:** it checks `timeout "${SC_SPARK_TIMEOUT:-3000}" "${cmd[@]}"` in the comment-stripped `lib-emr.sh`, and that `SC_SPARK_TIMEOUT:-1500` is gone. It also checks the comment texts "Returns the exit status (124 on the time limit)." and "default 3000 (was 1500)." in the raw file.
- **ER case:** it takes the `scholarchain.er.ErBatch` line and checks it carries the `SC_ER_TIMEOUT:-3000` form and not `timeout 1500`. It also checks `SC_ER_TIMEOUT:-1500` is absent and "Override with SC_ER_TIMEOUT." is present.
- **Hadoop case:** it checks `HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout 1500`, and that the first `timeout 1500` comes before the ER line.
- **Terraform case:** it reads the figures from the overlay comment and the module description with `sed`, then compares them with arithmetic on the overlay values. The checks are:
  - the text figures equal the computed ones, and the compared maximum equals `TF_MAX`;
  - the computed values equal the D32 numbers 6840 and 9240, which appear as literals only here;
  - Rule A is at most the maximum, and the worst case still exceeds it, so the accepted-limitation text stays honest.
- **Step-cap case:** it reads the Spark and ER defaults from the scripts. It checks Spark + 874 < step cap, Spark limit > 2400 (the D32 upper estimate) and ER limit < step cap. The 874 is the S run 4 checkpoint after stage 6.

**Case count:** 1,026 + 5 = 1,031. This is arithmetic from the edited sources, not a run. One case was renamed and none were removed. The stub tripwire cases are unchanged.

FILES MODIFIED
- `tests/scripts/run.sh`: renamed the S overlay case (value 4800) and added `static_wo54_cases` before "Case 12".
- `tests/scripts/README.md`: added a work order 54 paragraph and extended the case 14 table row.

COMMANDS RUN: none. I used Read and Edit only, and read the scripts, the overlay and the module variables read-only.

VALIDATION RESULTS: unrun.
- **Human runs:**
  - `bash -n tests/scripts/run.sh`
  - `shellcheck -x tests/scripts/run.sh`
  - `bash tests/scripts/run.sh`
- **Expected:** `RESULT pass=1031 fail=0`, tripwire empty. I expect all five new cases and the renamed case to pass against the current script, overlay and module text.
- **Shellcheck style:** the new block sits in a function under `# shellcheck disable=SC2016`, as the existing blocks do. It uses no `A && B || C`. The `&&` chain in the `if` uses backslash continuation only.

RISKS (things I could not confirm by reading)
- **Sed extraction:** the figures are read by BSD/GNU-portable BRE `sed`. They depend on the comment wording ("+ 600 = N <= M", "+ 900 + L = N > M" and "S overlay: N against M"). A rewording would fail the case on purpose, with a message saying which figure could not be read.
- **Line-order check:** the Hadoop-before-ER check uses the first `timeout 1500` in `run-s-pipeline.sh` code. It would give a wrong order if another code line before line 174 carried that literal. I did not use a count of `timeout 1500` for this reason.
- **Not covered:** the Spark limit is never run through a real `timeout`, so exit status 124 is checked only as documented text. `capability-check.sh` and the export stage also call `run_spark_submit` and are not read here.
- **README wording:** the README states that I read the whole file and cites the old figures from the work order. Those counts are from the work order, not recounted.

BLOCKED-ON OWNER: none.

NEXT GATE: the human runs the three commands above. The `sbt quick` / `sbt check` gates are not applicable to this change (shell harness and README only, no Scala source). Independent review follows.

## Orchestrator note (not part of the report)
The human ran them: `phase14-static-checks-17.log` (bash -n and shellcheck exit 0 on tests/scripts/run.sh, scripts/emr/*.sh; terraform fmt and validate pass) and `phase14-harness-7.log` (`RESULT pass=1031 fail=0`, `SKIPPED_CASES 0`).
