# Phase 16 morning summary (night of 2026-11-18)

Written by the orchestrator while the human slept (unattended, per the human's instruction). Updated as the night proceeds; the last section is the latest state.

## 1. Pre-commit check (item 5)

`sbt clean check` is the human's command: CLAUDE.md says agents cannot run it, and the guard allows only bare `sbt check`, `sbt quick`, `sbt Test/compile`. The orchestrator ran the closest allowed form, **bare `sbt check`** (no clean), on the Wave 1 tree with no edits during the run.

- Result: **FAILED**, exit 1. `[error] (services / Test / test) sbt.TestsFailedException: Tests unsuccessful`, `[error] Total time: 1345 s (0:22:25.0), completed Nov 18, 2026, 4:31:05 AM`. Last totals printed: `Passed: Total 99, Failed 0, Errors 0` and `Passed: Total 1247, Failed 0, Errors 0`.
- Failing suite (from `modules/services/target/test-reports/TEST-scholarchain.services.AwsRuntimeSuite.xml`, timestamp 2026-11-18T04:08:41, `sun.java.command ... sbt-launch.jar check`): **`scholarchain.services.AwsRuntimeSuite`**, 18 tests, 1 failure: test **"one structured line per query request, with no question text"**, `AwsRuntimeSuite.scala:241` `assertEquals(logged.size, 1)`: obtained 3, expected 1. The other 12 services suites: 0 failures.
- Reading (orchestrator, not verified): Wave 1 changed no file under `modules/services`; the same suite passed in the orchestrator's `sbt check` at 12:21 AM and in every agent `sbt quick`. The test collects log lines (`eventuallyLines(1)`) right after the async-query test, so a late line from another request can land in its capture. This is consistent with a timing flake, and it may explain the two unexplained `quick` exit-1 runs (`repair-batch-4.md`). Routed to service-engineer in Wave 2 (W2-S, below) to diagnose and fix test isolation without weakening the assertion.
- Consequence for the Wave 1 commit: the Wave 1 tree has not passed a full check in this run. Your `! sbt clean check` (section 3) is the gate; if `AwsRuntimeSuite` fails again there, Wave 1 can still be committed only by your decision, with this failure recorded; or wait for W2-S's fix (which is a Wave 2 file and would then be part of a separate commit).

## 2. Results relayed by the human (Claude in chat)
- `bash tests/scripts/p16-wo7.sh`: `RESULT pass=44 fail=0`.
- `grep -n 'RESPONSE_HEAD\|bounded-query-answer' tests/scripts/run.sh`: no matches.

## 3. Commands for you (paste-ready; record who ran each)

Wave 1 gate, before the Wave 1 commit:
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wave1-clean-check.log | tail -n 60; echo "exit: $?"
```
If it fails, the failing suite is in `modules/*/target/test-reports/TEST-*.xml` (attribute `failures="1"`).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wave1-run.log | tail -n 60; echo "exit: $?"
```
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash scripts/check.sh --skip-sbt --skip-terraform 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wave1-check-sh.log | tail -n 60; echo "exit: $?"
```
Expect `CHECK_RESULT=PASS_WITH_SKIPS`.

Also pending: H-1 Billing reading (Phase 15 reconciliation), H-2 confirm `docs/agent-runs/phase-15/ratification.md`.

## 4. Wave 1 commit file list (captured before any Wave 2 edit)

Wave 2 edits none of these (the human's rule). Tracked, modified:
```
build.sbt
experiments/d-query-load/README.md
experiments/d-query-load/collect.sh
experiments/e-retrieval-modes/README.md
experiments/e-retrieval-modes/run.sh
experiments/f-failure-recovery/collect.sh
load-tests/lib/ecs-load-client.sh
load-tests/lib/service_log.py
load-tests/query-load/README.md
load-tests/query-load/derive_latency.py
load-tests/query-load/load_client.py
modules/rag/src/main/scala/scholarchain/rag/BedrockAnswerModel.scala
modules/rag/src/main/scala/scholarchain/rag/Model.scala
modules/rag/src/main/scala/scholarchain/rag/Workflow.scala
modules/storage/src/test/scala/scholarchain/storage/ExportHeapMeasureSuite.scala
scripts/README.md
scripts/smoke-test.sh
tests/scripts/README.md
tests/scripts/run.sh
```
New:
```
docs/agent-runs/phase-15/ratification.md
docs/agent-runs/phase-16/decisions.md
docs/agent-runs/phase-16/human-commands.md
docs/agent-runs/phase-16/open-decisions.md
docs/agent-runs/phase-16/plan.md
docs/agent-runs/phase-16/prompt.md
docs/agent-runs/phase-16/wave-1/   (all files)
experiments/benchmark/README.md
experiments/evaluation/factory-mapping.tsv
experiments/evaluation/factory-rewrites.tsv
experiments/evaluation/src/main/scala/.gitkeep
experiments/evaluation/src/main/scala/scholarchain/evaluation/benchmark/BenchModel.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/benchmark/BenchmarkGen.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/benchmark/VectorCoverage.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/factory/FactoryCounts.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/graph/GraphChecks.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/io/ExportIo.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/metrics/Bootstrap.scala
experiments/evaluation/src/main/scala/scholarchain/evaluation/metrics/RetrievalMetrics.scala
experiments/evaluation/src/test/scala/.gitkeep
experiments/evaluation/src/test/scala/scholarchain/evaluation/AbstentionMappingSuite.scala
experiments/evaluation/src/test/scala/scholarchain/evaluation/BenchmarkGenSuite.scala
experiments/evaluation/src/test/scala/scholarchain/evaluation/FactoryCountsSuite.scala
experiments/evaluation/src/test/scala/scholarchain/evaluation/GraphChecksSuite.scala
experiments/evaluation/src/test/scala/scholarchain/evaluation/MetricsSuite.scala
experiments/evaluation/src/test/scala/scholarchain/evaluation/VectorCoverageSuite.scala
modules/rag/src/test/scala/scholarchain/rag/StopReasonAttemptsSuite.scala
modules/storage/src/main/scala/scholarchain/storage/LocalExportStore.scala
modules/storage/src/test/scala/scholarchain/storage/LocalExportStoreSuite.scala
tests/adversarial/P16BootstrapSuite.scala
tests/adversarial/P16GraphChecksSuite.scala
tests/adversarial/P16LabelRulesSuite.scala
tests/adversarial/P16RetrievalMetricsSuite.scala
tests/scripts/p16-wo7.sh
tests/scripts/p16/test_load_derive.py
```
Note: `docs/agent-runs/phase-16/morning.md` and `docs/agent-runs/phase-16/wave-2/` are written tonight; they are orchestrator records, not Wave 1 code. Include `morning.md` in whichever commit you prefer; `wave-2/` belongs with Wave 2.

## 5. Wave 2 (dispatched tonight on the uncommitted tree)

Rule (human): Wave 2 edits no file in section 4. Deferred until after the Wave 1 commit: OD-2 (BenchmarkGen `Usage` message) and anything else touching a Wave 1 file (for example `experiments/benchmark/README.md`, `tests/scripts/README.md`, `ExportIo.scala`, `build.sbt`, `modules/rag/**` Wave 1 files). Scala edits are serialized (one agent at a time) because concurrent edits trip the format hook (`wave-1/repair-batch-2.md`).

Order and write sets (details in `wave-2/work-orders.md`):

| Order | Owner | Write set (new files or untouched paths only) |
|---|---|---|
| W2-S | service-engineer | `modules/services/**` (no Wave 1 file there): log `WorkflowResult.attemptLogLines`; fix the `AwsRuntimeSuite` flake |
| W2-E | experiment-engineer | `experiments/evaluation/src/{main,test}/scala/scholarchain/evaluation/er/**` (new): ErExtras |
| W2-R | experiment-engineer | `.../evaluation/runtime/**`, `.../evaluation/retrieval/**`, `.../evaluation/answer/**` (main and test, new); `experiments/manifests/experiment-g-retrieval.manifest`, `experiment-h-answers.manifest` and their `manifests/json/` copies (new); `experiments/g-retrieval/**`, `experiments/h-answers/**` (new) |
| W2-T | test-engineer | new files only under `tests/adversarial/` (`P16W2*`) |
| review | code-reviewer | verdict only |

Then one `sbt check` at the end of the night.

## 6. Night log
(updated below as orders complete)

- W2-S service-engineer: COMPLETE (`wave-2/reports/w2-s-service-engineer.md`). Flake cause (code reading): one shared log queue across all runtimes in `AwsRuntimeSuite`; the service writes its `query_request` line after sending the response, so late lines from earlier tests landed in a later test's capture. Fix: per-launch capture plus a requestId check; the assertion `logged.size == 1` kept. Attempt lines now logged on `scholarchain.services.attempts` (no text). Two `sbt quick` passes (4:37, 4:40 AM). Note: this fix is a Wave 2 file (`modules/services/**`), so the Wave 1 commit alone still contains the old flaky test.
- W2-E experiment-engineer (ErExtras): COMPLETE (`wave-2/reports/w2-e-experiment-engineer.md`). Per-path recall, stratified bootstrap CI, sensitivity (GOLD-MANIFEST names no suspect pair ids: "not-identifiable", plus a labelled proxy set), tier-S population view; `ErStatsSuite` 17, `ErRunSuite` 15, 0 failures; `sbt quick` passed 4:51 AM. Paste-ready human command in the report (after the Wave 1 commit).
