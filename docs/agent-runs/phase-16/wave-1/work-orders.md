# Phase 16 Wave 1 work orders

Issued by the orchestrator on 2026-11-17 under `../plan.md` and `../decisions.md` (P16-1 to P16-6). Common rules for every order:
- Read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md`, `../decisions.md` and `../prompt.md` first.
- No AWS command of any kind, no terraform, no deploy, no commit, read-only git. No Python (project guard): any Python step is a paste-ready `!` command for the human.
- Write only inside the write set of your order. A need outside it is reported as `BLOCKED-ON <owner>` with evidence.
- Before implementation, list which specific test proves each item of your gate; put the list in your report.
- sbt slot: one sbt process at a time across all agents. Only the order holding the slot runs sbt. If you do not hold it, finish your change and tests and report `READY-FOR-SBT`; the orchestrator resumes you when the slot is free. Bare two-token forms only (`sbt quick`, `sbt check`, `sbt Test/compile`). `sbt quick` during work; one `sbt check` before review only when the orchestrator says so.
- Paste-ready human commands: `AWS_PROFILE=scholarchain` only where AWS is touched, `set -o pipefail`, log tee'd to `/Users/winnguyen/scholarchain-data/logs/phase16-*.log`, ending in `| tail -n 60; echo "exit: $?"`; account id masked with sed where AWS output is printed; zsh-safe. Each step has a "ran by" line (P16-5).
- Do not weaken tests. No account ID in any file. No prompt or answer text in logs.
- Report: files changed, tests run with exact pass and fail counts, open items, anything not verified.

## WO-1 build-engineer (holds the sbt slot first)
1. New sbt project `evaluation` with base directory `experiments/evaluation` (plan D2). Depends on core, storage, rag, and spark (for the graph projector used by `InMemoryNeptune` loading, `GraphRehearsal.scala:150-172`). Nothing depends on it; it is not in the runtime dependency graph or any packaging. Aggregated into `check` and `quick` like the other projects.
2. `run` forked with `-Xmx6g` (plan finding 4), so the human can `sbt "evaluation/runMain ..."`.
3. Empty skeleton roots only (`src/main/scala`, `src/test/scala`); no source files.
4. Any evaluation suite later reported as slow goes to `slowSuites`; none exists yet.
5. Gate: `sbt Test/compile` and `sbt quick` pass; report the module graph change and that no version value changed.

## WO-2 storage-engineer
1. Promote the test-scoped flat-directory adapter (`DirStore`, `modules/storage/src/test/.../ExportHeapMeasureSuite.scala:55-89`) to a main-scope read-only `ObjectStore` over a flat local export directory (layout of `/Users/winnguyen/scholarchain-data/rehearsal/a-export`), usable by `AwsReadModels.fromExport` (`AwsReadModels.scala:312-343`). Rejects writes; refuses paths escaping the root.
2. The heap-measure suite uses the promoted class; its assertions unchanged.
3. Gate: feature tests (read, list, missing key, escape refused, write refused); `sbt quick` when granted the slot.

## WO-3 rag-engineer, part 1
1. `BedrockAnswerModel`: detect `stopReason` on every Converse reply, including `max_tokens`, and log it with the outcome (no prompt or answer text). Expose it to callers.
2. `StructuredCall` (`Model.scala:574-653`): keep the rejection reason of each attempt; `Workflow.scala:364-370` carries the reasons (codes or short fixed strings, no model text) into the audit record and the service log.
3. Do not change `maxOutputTokens` in this part (P16-2: part 2 follows the human's diagnostic).
4. Gate: feature tests for each stopReason path and for rejection reasons reaching the audit; existing tests unchanged; `sbt quick` when granted.

## WO-4 experiment-engineer (first slice)
Write set: `experiments/evaluation/**`, `experiments/benchmark/**`, `experiments/i-offline/**`. Start after WO-1 lands (the project exists).
1. `BenchmarkGen`: from A's local export (path an argument), seeded and stratified per plan section "Benchmark questions and labels"; writes `questions-v1.jsonl`, `labels-v1.jsonl`, `BENCHMARK-MANIFEST.json` (sha256, seed, `sourceRunId`, generator version). Reads `datasets/tier-s/chunks-c/chunkset-manifest.json` for `vectorAvailable`. Leaves 8 free-form slots for the human (H-5). No LLM.
2. `GraphChecks`: every check in plan group 2, including path-evidence hops against `edges-coauthored` (P15-28) and the 50-edge provenance trace; writes a run directory with `SHA256SUMS`.
3. `FactoryCounts`: plan group 6 over `docs/agent-runs/phase-01` to `phase-15`, per-phase parsing rules and a committed mapping table where formats drift; every count printed with file and line.
4. Paste-ready human commands for running each (sbt runMain) go in your report; the orchestrator archives them in `docs/agent-runs/phase-16/human-commands.md`.
5. Gate: unit tests on hand-built fixtures in `experiments/evaluation/src/test`; `sbt quick` when granted. `RetrievalEval`, `AnswerEval` (with `--diagnostic`, P16-2), `AnswerAudit`, `ErExtras`, `Bootstrap`, the G and H manifests (call cap about 250 plus the binding USD stop, P16-3) come in the next slice.

## WO-5 experiment-engineer
Write set: `load-tests/query-load/load_client.py`, `load-tests/lib/service_log.py`, `load-tests/query-load/derive_latency.py`, `load-tests/query-load/README.md`, `experiments/d-query-load/README.md`, `experiments/d-query-load/collect.sh`, `experiments/e-retrieval-modes/README.md`, `experiments/e-retrieval-modes/run.sh`.
1. `load_client.py`: capture `abstentionReason` (finding 5).
2. Resolve the PENDING-WO-8 markers (plan finding 8) to the final field `retrievalMode`.
3. `derive_latency.py`: optional bootstrap CI for p50 and p95 per group (seeded; default output unchanged when the option is off).
4. Gate: no sbt; paste-ready rehearsal commands for the human; the harness cases are test-engineer's (WO-7).

## WO-6 infra-engineer
Write set: `scripts/smoke-test.sh`, `scripts/README.md`.
1. The bounded-query step requires, for an answered query, `abstained=false` and evidence count above 0, and records `abstentionReason` (finding 5). An abstained response fails the check and prints its reason.
2. Gate: `bash scripts/check.sh --skip-sbt --skip-terraform` as a paste-ready human command if the guard refuses it for you; no sbt.

## WO-7 test-engineer
Write set: `tests/**`. Work from the plan, not from the implementations.
1. Oracle tests for the metric math (Recall@K, Precision@K, MRR, nDCG with graded labels, seeded bootstrap) and for the label rules on hand-built fixtures, as a spec the WO-4 code must pass (agree the package and function names with the orchestrator; report `BLOCKED-ON experiment-engineer` if the API is not yet there).
2. Adversarial `GraphChecks` fixtures: dangling edge endpoint, duplicate id, edge with no support row, joint count not matching support, path hop not in `edges-coauthored`, provenance `runId` mismatch.
3. Harness cases for WO-5 (`abstentionReason` captured; markers gone) and WO-6 (abstained answer fails the smoke check), none weakened.
4. Gate: exact pass and fail counts; `sbt quick` when granted; harness runs are the human's.
