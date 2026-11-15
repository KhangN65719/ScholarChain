# Reports for work orders 55 to 59 (repair rounds 1 and 2), as received

Archived by the orchestrator (2026-11-15 UTC), each report as received from its author. Nothing was executed by the agents; the human's runs are noted in the orchestrator notes. Context: decisions-d33.md.

---

## Work order 55 (infra-engineer): repair round 1

**STATUS:** COMPLETE for the edits. No check was run, so nothing is validated. 3 harness cases will fail until work order 56 updates them (see BLOCKED-ON).

**OWNER:** infra-engineer. **WORK ORDER:** 55 (repair round 1, review of 3d20a22..6485d23).

**FILES MODIFIED** (no Terraform value, Rule A number or IAM statement changed)
- `scripts/emr/lib-emr.sh`
- `scripts/emr/run-s-pipeline.sh`
- `scripts/README.md`
- `infra/terraform/README.md`
- `infra/terraform/environments/course/variables.tf` (description string only)
- `infra/terraform/environments/course/experiment-size-s.tfvars`, `experiment-size-m.tfvars`, `experiment-size-l.tfvars` (comments only)

**Work done, by finding**
- **Finding 2.** `scripts/README.md` now states the current MapReduce rule:
  - `yarn` needs exactly nine MAPREDUCE applications listed by the ResourceManager and no `job_local` id in the log, with `MAPREDUCE_PROOF`.
  - Job ids come from the Hadoop log, else `mapred job -list all` / `-list`, else derived from the application ids (`MAPREDUCE_JOB_IDS_SOURCE`).
  - `MAPREDUCE_JOB_IDS` is cut at 400 characters; `mapreduce-job-ids.txt` is the full list.
  - `MAPREDUCE_ATTENTION` is set for anything but `yarn`.
  - An external kill does not run the exit trap, so the progress checkpoints are the only evidence. This is stated as observed in S run 3 per your order; I did not read decisions-d30.md.
  - The superseded job-id-count rule is gone, and the "may not run the trap (UNVERIFIED)" wording is replaced. The text at line 382 already matched; line 385 now says the same about the trap.
- **Finding 1 (text only).**
  - The S run 5 command line, projected 1.14 USD, and the fact that `--size` is only the wall-clock tier (dataset is the 3,472-work S subset) are now in the `scripts/README.md` "Pipeline sizes" paragraph, the `infra/terraform/README.md` "Pipeline bounds" text and the S overlay comments.
  - The infra README has a new paragraph "S run 5 sequential budget".
  - The sequential sums are 6,960 s (low step estimate), 7,560 s (3,900 s step) and 8,460 s (step at its 4,800 s cap), against the 6,600 s stop and the 7,200 s timeout.
  - "No conflict expected" is withdrawn, and the unverified `StopExecution` versus Fargate `runTask.sync` question is noted.
- **Finding 4.**
  - `sc_publish_failed_logs` has a shared deadline: `SC_FAILED_PUBLISH_BUDGET_SECONDS`, default 600, falling back to 600 if not all digits.
  - Each upload gets `min(300, time left)`. Once the budget is spent, each remaining file prints `FAILED_LOGS_SKIPPED file=<name> reason=time-budget-spent (budget N s)`, plus one NOTE with the count.
  - The existing key-material skip and the existing `NOTE: failed-log publish: ...` line are unchanged.
  - Kept as before: the secret scan, no sync, no `--delete`, no `--sse-c`, `FAILED_LOGS_PUBLISHED=`, always `return 0`.
  - One addition you did not ask for: `run-record.env` is now uploaded first, so a spent budget costs the key=value record last. This changes upload order.
- **Finding 5.**
  - `timeout -k 30` is now on `run_spark_submit`, the Hadoop stage and the ER stage; the 124 comments are kept.
  - `yarn_final_state` now runs `timeout 120 yarn application -status`. A timeout makes the pipeline non-zero, so `|| s=""` fires and it still returns `unknown`.
  - README note: under `-k`, a command that ignores TERM and needs KILL makes GNU `timeout` exit 137, not 124. I cite this to the coreutils manual and have not observed it on the node.
- **Finding 6.**
  - The M and L overlay comments use the one-step design.
  - M: 1440 + 300 + 2700 + 900 + 2700 = 8,040 > 7,200 (840 s over), so the O1 limitation applies.
  - L: 1440 + 300 + 3600 + 900 + 3600 = 9,840 <= 10,800, so O1 does not apply against the execution timeout. The 150-minute script cap (9,000 s) is shorter and can still cut a full-timeout run.
  - The infra README (Rule B) already said this, so it needed no fix.
- **Finding 7.**
  - The "unchanged from repair round 2" sentence (infra README line 229) is corrected.
  - The CleanupOnFailure routing text in `variables.tf` and infra README line 249 now matches `modules/workflow/main.tf`. FetchScripts, CapabilityStep and PipelineStep route there via the `States.ALL` catch, and ChooseStep reaches it via its Choice default. CreateCluster, TerminateCluster and RunLoader go straight to `Failed`.
- **Finding 9.**
  - `scripts/README.md` now says a reused `run_id` passes Hadoop, ER and Spark and is caught only at the publish count, after `curated/runs/<id>/` objects may be overwritten. The guard is a fresh id each time.
  - Pre-start S3 check: worth a later work order. It is one `aws s3 ls` of the run prefixes in `validate-arguments` on the node (the emr_ec2 role should allow listing `curated/`, `graph/` and `semantic-context/`, but I did not confirm the ListBucket prefix conditions), or on the operator side in `run-bounded-pipeline.sh`. Either changes script digests and needs new harness cases.
  - Not changed: the `die` message at `run-s-pipeline.sh` line 67 still says "FileOutputFormat refuses an existing output directory". It is stale for the same reason, but it is runtime text and a test may assert it, so I left it.

**COMMANDS RUN:** Read and Edit only. One `wc -l` on the harness was refused by the guard and had no effect.

**VALIDATION RESULTS:** Nothing was executed, so the edits are unvalidated. The human must run: `terraform fmt -check -recursive -diff infra/terraform`; `terraform -chdir=infra/terraform/environments/course init -backend=false`, then `validate`; `bash -n scripts/emr/*.sh` and `shellcheck -x scripts/emr/*.sh`; `bash tests/scripts/run.sh`. Expect the three failures named below until work order 56.

**BLOCKED-ON: test-engineer (work order 56).** The three updated cases (`14.emr.timeouts.spark-limit-default-is-3000-...`, `...er-limit-is-sc-er-timeout-default-3000-...`, `...hadoop-stage-limit-stays-1500-...`) needed `-k 30` needles; `14.terraform.m-and-l-overlays.comment-arithmetic-matches-the-values` to be extended with the worst-case lines; new cases for the failure-path publish, `yarn_final_state` and the static `-k 30` check; and stub caution (the child `timeout` stubs drop only one argument, so a case calling `run_spark_submit` needs a stub that also drops `-k 30`).

**RISKS**
- **Discrepancy with D33.** D33 said "about 1,100 to 2,000 s remain after the step". This report's README text says 1,740 to 2,340 s remain after creation (about 660 s), fetch counted at its 300 s bound, and a 3,300 to 3,900 s step. Terminate (up to 900 s) leaves about 840 to 1,440 s for the loader. (The orchestrator corrected D33 to this arithmetic.)
- **Re-upload and re-approval.** Changes to `lib-emr.sh` and `run-s-pipeline.sh` change the `upload-inputs.sh` file digests. Run 5 needs a fresh upload record before the pipeline record. The top-level `scripts/*.sh` and `lib.sh` were not touched in this round.
- **Untested code.** `-k 30`, the failure-publish budget and the `yarn_final_state` timeout have never run on a node. The 137 behavior is from the manual only.
- **Finding 8** (on-node limits versus M and L) is untouched, as decided.

---

## Work order 56 (test-engineer): harness cases for repair round 1

**STATUS:** COMPLETE for authoring. Nothing in `tests/scripts/run.sh` had been run. `sbt quick` passed, but it does not exercise this shell harness.

**Audit.** The whole harness (1 to 4596), `lib-emr.sh`, `run-s-pipeline.sh` lines 1 to 340, the M, L and S overlays and `scripts/README.md` 380 to 500 were read. Four cases asserted text work order 55 changed; all four updated without weakening. No case asserts the README wording on the verdict rule, the trap on an external kill or run-id reuse, nor the stale `FileOutputFormat` die message. `yarn_final_state`, `sc_publish_failed_logs` and `run_spark_submit` were not covered before this order.

**Updated cases (ids unchanged):** the Spark, ER and Hadoop limit cases now carry `timeout -k 30` (the ER case also refuses `timeout 1500` and `timeout -k 30 1500`; the Hadoop case's `f_has` and both `f_before` needles); `14.terraform.m-and-l-overlays.comment-arithmetic-matches-the-values` reads the loader value too and checks Rule A and its slack, the worst case `create + 300 + step + 900 + loader` with its sign and "(N s over)" or "(N s of slack)", and `DOES apply to M` / `does NOT apply to L`, every figure computed from the values read (M 8040 > 7200, L 9840 <= 10800).

**26 new cases:** 6 static (`14.emr.timeouts.k-30-...`, `...readme-documents-k-30-status-124-and-the-137-claim-as-not-observed`, `...readme-limit-table-names-the-same-commands-as-the-code`, `14.emr.lib-emr.yarn_final_state...`, `...sc_publish_failed_logs...`, `...sc_on_exit...`); 5 `14.emr.yarn-final-state.*` (SUCCEEDED, FAILED, a failing `yarn`, a `timeout` that exits 124, a `timeout` that exits 124 after partial SUCCEEDED output; each checks the two `timeout 120` wrappers); 3 `14.emr.timeouts.run-spark-submit-*` (a `timeout` script first on PATH because `env` execs `timeout`; default `-k 30 3000`, override `-k 30 77`, status 124 without running `spark-submit`); 12 `14.emr.failed-publish.*` (exit code 7 recorded as `FAILED_STAGE=1 boom-stage` and `FAILED_EXIT_CODE=7` and kept; uploads only under `reports/runs/run-v1-x/failed/<stamp>/`, one stamp, `run-record.env` first, one `aws s3 cp` per file, nothing under `progress/`, no `sync`, `--delete`, `--recursive` or `--sse-c`; the secret file skipped and never printed or uploaded; every call timed at 300 s while the budget is whole; a failing `aws` returns 0 and keeps the exit code through the trap; a stub clock gives per-call limits 300, 300 and about 100 and the fourth file skipped with `FAILED_LOGS_SKIPPED ... (budget 600 s)` plus the NOTE with the count; budgets `abc` and `-5` fall back to 600; budget 0 skips every file with no `aws` call; nothing before registration or with a missing reports directory). Total 1,031 + 26 = 1,057.

**FILES MODIFIED:** `tests/scripts/run.sh`, `tests/scripts/README.md`. **COMMANDS RUN:** `sbt quick`: `Passed: Total 73, Failed 0, Errors 0` and `Passed: Total 1011, Failed 0, Errors 0`, `[success] Total time: 113 s` (no Scala changed: turn evidence only); no `sbt check`; no `bash -n`, `shellcheck` or harness run (the guard blocks them).

**Risks.** The third per-call limit case accepts 95 to 100 (real clock ticking); the `timeout` script via `env` assumes `env` resolves `timeout` from the child's exported PATH and `${0%/*}` gives the stub's directory; the `timeout`-late `yarn` child relies on `pipefail` turning 124 into `unknown`; README needles match exact wording (a rewording fails on purpose); `lib-emr.sh` lines 529 to 530 said the status "stays 124 when the limit was reached", imprecise under `-k` (a note for infra-engineer, not a blocker); not covered: a real `timeout`, real `yarn` output, a real `aws` or clock. **BLOCKED-ON OWNER:** none.

*Orchestrator note:* the human ran the checks after work orders 55 to 57: `phase14-static-checks-18.log` (all exit 0) and `phase14-harness-8.log` (`RESULT pass=1057 fail=0`, `SKIPPED_CASES 0`).

---

## Work order 57 (infra-engineer): one comment in lib-emr.sh

**STATUS:** COMPLETE. **FILES MODIFIED:** `scripts/emr/lib-emr.sh`: the comment above `run_spark_submit` (about lines 529 to 532) now says the exit status is 124 when the command ended on the TERM sent at the limit and 137 when it needed the KILL after the 30 s grace; both statuses come from the coreutils manual; neither is observed on a node, and 124 has not been seen either, because S run 4 exited cleanly on TERM. **COMMANDS RUN:** Read, one Edit; no AWS, no commit. **VALIDATION:** only comment lines changed; no search of the harness (the guard blocks grep; the work order says it has no case on the sentence); no `bash -n` or shellcheck. **RISKS:** the header comment at about line 522 still says "Returns the exit status (124 on the time limit)", slightly imprecise (left because a harness case matches it). **NEXT GATE:** independent review and the harness run.

---

## Work order 58 (infra-engineer): repair round 2

**STATUS:** COMPLETE. All five items are edited. Nothing has been run.

**FILES MODIFIED** (no tests, Terraform values, Rule A numbers or IAM touched)
- `scripts/README.md`: item 1 rewrite of the paragraph after the on-node limits table; the failed-publish budget row in that table now reads "a whole number with no leading zero, else 600".
- `scripts/emr/lib-emr.sh`: item 2. The budget check is now `[[ "$budget" =~ ^(0|[1-9][0-9]*)$ ]] || budget=600`, with a comment saying why.
- `scripts/run-bounded-pipeline.sh`: item 2, second place found. `--max-minutes` was checked with `^[0-9]+$` and then used in `[[ -ge/-le ]]` and `$((MAX_MINUTES * 60))`; it now uses `^(0|[1-9][0-9]*)$`, so `0` still reaches the range message; the die text is "--max-minutes must be a whole number (no leading zero, which bash would read as octal)".
- `infra/terraform/README.md`: item 3, the sentence at about line 282 corrected.
- `infra/terraform/environments/course/experiment-size-s.tfvars`: item 4, comment only.

**Item 2, places checked for `^[0-9]+$` followed by arithmetic.** Fixed: `lib-emr.sh` (`sc_publish_failed_logs`) and `run-bounded-pipeline.sh` (`--max-minutes`: `08` died with a misleading range message, `010` passed as octal 8 for the deadline while awk used 10 for the projected spend). `SC_PROGRESS_BUDGET_SECONDS` is a constant 60; `SC_SPARK_TIMEOUT` and `SC_ER_TIMEOUT` go only to `timeout`; `conf_value` values are only printed; `lib.sh` `file_mtime_iso` takes epoch seconds. Not read: `capability-check.sh`, `upload-inputs.sh`, `run-experiment.sh`, `check.sh` and the rest (the human was asked to grep).

**Item 1, the new sentences** (`scripts/README.md`): a timeout makes `timeout` exit 124, but that status never reaches the report's exit code; every stage under `timeout` catches the status (`|| rc=$?`) and calls `die`, which always exits 1, so `sc_on_exit` records `FAILED_EXIT_CODE=1` for a timed-out stage, as S run 4 showed (D32); the 124 appears only in the text `die` writes to stderr (the EMR step log): `EVIDENCE: PipelineTool exited 124: ...`, `EVIDENCE: ErBatch exited 124: ...`, or `... (exit 124): ...` for the two Spark applications; it is in no key and no file of the reports directory, so `FAILED_EXIT_CODE` cannot tell a timeout from another failure. (GNU coreutils manual, not observed: a command that ignored the TERM and needed the KILL makes `timeout -k` exit 137, so the text would read 137; the recorded `FAILED_EXIT_CODE` stays 1 either way.) A timeout can be told from the stage seconds: `STAGE_SECONDS_<STAGE>=<n> (stage failed)` with n equal to the stage limit (up to 30 s more when the KILL was needed).

**Item 4:** the S overlay comment carries the third total; the READMEs' 8,460 s is 660 + 300 + 4,800 + 900 + 1,800 (creation at its observed 660 s), and with creation at its 1,440 s timeout the same sum is the O1 worst case 9,240 s; values unchanged. **Item 5:** line 67 of `run-s-pipeline.sh` left as is.

**Risks.** Editing `run-bounded-pipeline.sh` changes its `SCRIPT_DIGEST`; an approval record already written against the old digest would be invalid (none exists for S run 5). `--max-minutes 08` now fails with a message that begins "must be a whole number". The S overlay comment now holds more figures, which the two S figure cases extract by `sed`. **BLOCKED-ON test-engineer (work order 59):** the README 124/137 case (about lines 4705 to 4706), the limit-table case (about line 4715), the `sc_publish_failed_logs` regex case (about line 4730), new budget and `--max-minutes` cases, the two S overlay figure cases to be re-verified, `tests/scripts/README.md` line about 22, and the infra README sentence.

---

## Work order 59 (test-engineer): harness cases for repair round 2

**STATUS:** COMPLETE for the edits. Nothing run (the guard blocks `bash -n`, `shellcheck`, grep and the harness for agents). No case needed a script change.

**Updated:** `14.emr.timeouts.readme-documents-k-30-status-124-and-the-137-claim-as-not-observed` (pins `FAILED_EXIT_CODE=1` for a timed-out stage, `die` always exiting 1, the 124 only in the `EVIDENCE:` stderr text, `STAGE_SECONDS_<STAGE>=<n> (stage failed)`, the 137 claim with "stays 1 either way", `-k 30` and the "GNU coreutils manual, not observed" needles; asserts the old sentences are gone); `...readme-limit-table-names-the-same-commands-as-the-code` (new budget wording, old "(digits, else 600)" gone); `14.emr.lib-emr.sc_publish_failed_logs.is-a-budgeted-best-effort-scanned-per-file-copy-under-the-failed-stamp` (the new regex line; `=~ ^[0-9]+$` gone). No static needle on the old `--max-minutes` regex or die text existed; both S overlay figure cases were re-read and left unchanged; no case pinned the old infra README sentence.

**12 new cases:** 3 failed-publish budgets `08`, `09` and `0600` fall back to 600 (not octal 384; empty stderr); 7 `5.pipeline.*` dry-run cases (`08`, `010`, `00` refused with the whole-number message, no range message, no `LIMITS` line, no projected spend; `0` still gets "must be 1 to 150 for size large"; `--size large --max-minutes 90 --spend-ceiling-usd 1.00 --hourly-burn-usd 0.62` and the S run 5 command with `110`, `1.20`, `0.62` accepted, projected 0.93 and 1.14); `14.terraform.s-overlay.s-run-5-sequential-totals-are-computed-from-the-values-and-the-o1-worst-case-is-read-from-the-file` (6960, 7560 and 8460 computed from 660 + 300 + (3300, 3900 or the step cap) + 900 + the loader read from the file; the O1 9240 read from the file must exceed the third total by exactly the creation timeout minus 660); `14.terraform.readme-says-which-timeouts-keep-their-repair-round-2-values`. The `exit 124` `sc_on_exit` unit case is kept and annotated (a real timed-out stage records exit code 1). Total 1,057 + 12 = 1,069. `tests/scripts/README.md` updated.

**Risks.** `(digits, else 600)` is asserted gone from all of `scripts/README.md`; no case confirms each stage under `timeout` catches the status and calls `die` (the README text is what is asserted); `SC_PROGRESS_BUDGET_SECONDS` not checked for the leading-zero hazard (a constant 60, per work order 58); nothing runs a real `timeout`. **BLOCKED-ON OWNER:** none.

*Orchestrator note:* the human ran the checks: `phase14-static-checks-19.log` (terraform fmt and validate, bash -n and shellcheck -x on the five emr and pipeline scripts and `tests/scripts/run.sh`, all exit 0) and `phase14-harness-9.log` (`RESULT pass=1069 fail=0`, `SKIPPED_CASES 0`).
