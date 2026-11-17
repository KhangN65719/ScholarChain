# Wave 1 review 1 verdict (code-reviewer, Opus, read-only; received 2026-11-17; recorded by the orchestrator with attribution, findings preserved as delivered)

**Verdict: REQUEST CHANGES.**

## Blockers
- **K1** B likely stopped by its own wall clock: `experiments/b-burst/run.sh:38` (`exp_run_pipeline medium 74 2.00 1.55`, 74 min = 4,440 s); run 6 create-to-terminate about 57 min plus 900 s Wait about 72 min; `elasticity-s.tfvars:54-56` realistic case about 4,300 s (slack about 140 s); B adds HDFS put/get, 16 reducers on 3 GiB containers on one core node against `timeout -k 30 1500` (`run-s-pipeline.sh:271`), 32 shuffle partitions on `local[4]`, event log and counters. `elasticity-s.tfvars:47-48` comment ("tier S stop (90 minutes)") wrong (74). If fired, `run-bounded-pipeline.sh:446-463` stops the execution and terminates the cluster (scale-in window cut, FAILED). Fix: re-budget (higher `--max-minutes` and manifest `MAX_MINUTES`/`SPEND_CEILING_USD` within the 90-min cap, shorter Wait, or drop Spark overheads); ceiling change is a human cost decision. Owners: experiment-engineer (`run.sh`, `experiment-b-burst.manifest:98-107,120,126`), infra-engineer (comment, Wait).
- **K2** Managed-scaling bounds assume EMR counts the primary: `infra/terraform/modules/emr/main.tf:74-77` (min = core + 1, max core = cap - 1 - tasks). Reviewer recollection (UNVERIFIED): ComputeLimits apply only to core and task nodes. If so, B's minimum is 2 core nodes (EMR raises core 1 to 2 at start: false-positive scale-out in `derive-capacity.py:327`), scale-in floor 2 core, 4th unit only as a task node (no task group). Fix: orchestrator documentation check, then infra corrects the formula. Owners: infra-engineer; orchestrator for the check.

## Major
- **M1** `run-s-pipeline.sh:202-210, :440` `DATA_PATH=auto`, `SPARK_MASTER=auto` (runtime detection via `yarn node -list`, `hostname -s`): misdetection moves A off run 6's path or makes B fall back to `file://`. Fix: pin `a-baseline.DATA_PATH=file`, `a-baseline.SPARK_MASTER=yarn`, `b-burst.DATA_PATH=hdfs`, `b-burst.SPARK_MASTER=local` in `spark-yarn.conf:68-82`; update static case `tests/scripts/run.sh:10622` (pins a-baseline at 2 lines). Owners: infra-engineer, test-engineer.
- **M2** `b-burst.SHUFFLE_PARTITIONS=32` (`spark-yarn.conf:78`) adds Spark runtime on `local[4]` with no YARN demand; risks `SC_SPARK_TIMEOUT` 3000 s (run 6 1,735 s); feeds K1. Recommend 8; reconsider `b-burst.SPARK_EVENT_LOG` (line 76). Owners: experiment-engineer decides, infra-engineer edits.
- **M3** `derive-capacity.py:318-335` classifies scale-out by time only (non-primary instance created > 300 s after the first); no link to pending YARN demand or the policy minimum; `emr-managed-scaling-policy.json` only hashed (line 202). Fix: label floor-restoring instances (K2); report pending containers near each event. Owner: experiment-engineer.
- **M4** A re-run still leaves two course fields empty: `derive-table.py:202-208` hard-codes input records NOT RECORDED although `INPUT_LINES_*` exist (`run-s-pipeline.sh:126`; `wc -l` of XML is lines, not records; "Map input records" counter is the right source); `derive-table.py:231-237` hard-codes output records NOT RECORDED; no run file carries Spark run-manifest or export-manifest counts. Owners: experiment-engineer; infra-engineer if the node must copy counts into reports.
- **M5** Course requires a committed YAML or JSON manifest (`prompt.md:12, :83`); A and B are flat KEY=VALUE. No Wave 1 order covers it. Owner: orchestrator to route (experiment-engineer; infra-engineer for `run-experiment.sh`).
- **M6** No harness case for the ComputeLimits change (`emr/main.tf:76`): no "fixed-size shapes unchanged", no elasticity-s values; case `run.sh:9626` (`min = 1 + core`) hard-codes the K2 assumption. Owner: test-engineer.

## Minor
- m1 stale docs: `experiments/b-burst/README.md:6,9,16-19`, `a-baseline/README.md:13-18,21` (loader `-version`, delivered asks); `experiments/README.md:8` (snapshot placeholder). experiment-engineer.
- m2 `run-s-pipeline.sh:110` `RUN_SCOPE` says single-node, Spark and MapReduce on YARN, even in B. infra-engineer.
- m3 test gaps: no case that no Catch targets `WaitBeforeTerminate`; no dynamic case for copy-back (`-get` failure dies, `-rm` failure notes) at `run-s-pipeline.sh:393-397`. test-engineer.
- m4 comment `tests/scripts/run.sh:9534` "within two lines" vs ten-line window. test-engineer.
- m5 `collect.sh:64,92-111` one failing call leaves partial `collected/` that cannot be redone; `get-managed-scaling-policy` on a terminated cluster UNVERIFIED; add `ContainerPendingRatio` to `METRICS`. experiment-engineer.
- m6 `chunkset.py:75-85` in-repo check case-sensitive on case-insensitive macOS; line 399 prints "repo-only" though `validate` reads the external file. dataset-engineer.
- m7 `hdfs dfs -mkdir` at `run-s-pipeline.sh:217` has no timeout. infra-engineer.

## Advisory
a1 subdirectory secret scan (`run-s-pipeline.sh:516`) can fail the run on a large event log (infra). a2 HDFS `-get` likely copies no `.crc` files; record in limitations (UNVERIFIED). a3 raw `sfn-history.json`, `window.json` hold ARNs with the account ID; redact before committing results. a4 no `.gitignore` backstop for `datasets/tier-s/chunks-c/chunks.jsonl` (build-engineer). a5 `terraform validate` does not render the overlay state machines; suggest a read-only rendered validation before apply. a6 `PartitionCountSettingSuite:157` may not run 16 reducers under LocalJobRunner. a7 `--stop-on dlq-nonempty` (`exp-lib.sh:93`) would stop A or B if a DLQ holds an old message; human read-only depth check before the run. a8 `emr/main.tf` change outside WO-1's list, inside infra paths, reported.

## Confirmed OK by reading
Default single-node `file://` path unchanged; S3 publish keys unchanged; Rule A holds (7140, 6840 <= 7200); Wait absent at 0, failures never wait, RunLoader reachable in loader-only mode; A has no scaling policy at core 0; entry points one start, no retry, wrong-ID refusal before event log, UTC, write-once; collector read-only verbs, write-once; chunk file outside repo; no real account IDs; no agent-runnable AWS-mutating path; no ownership violations.

UNVERIFIED (reviewer): K2, EMR scale-in timing, `yarn node -list`/`mapred job -status` formats, CloudWatch metric names, B's Hadoop time against 1,500 s.
