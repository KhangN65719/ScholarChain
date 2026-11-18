# W2-RB1: experiment-engineer repair batch 1 report

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed; commands verbatim).

---

**Status: COMPLETE, pending independent re-review.** Work order W2-RB1. No AWS, no Python, no commit.

**Validation.** Bare `sbt quick`, third run: `[success] Total time: 156 s (0:02:36.0), completed Nov 18, 2026, 12:25:30 PM`; root `Passed: Total 1126, Failed 0, Errors 0`. Evaluation project (sum of 18 JUnit XMLs under `experiments/evaluation/target/test-reports`): 303 tests, 0 failures, 0 errors, 0 skipped. Runs 1 and 2 failed for test-code reasons only (munit `intercept` does not catch `InterruptedException`; golden hashes started as placeholders).

**Findings: status and proving tests**

| Item | Status | Proving test |
|---|---|---|
| M1 | Done | AnswerEval and RetrievalEval "killed mid-way keeps the records…" (fatal `InterruptedException` mid-run; lines on disk, no `RAW-SHA256SUMS`, summary or seal); "absolute deadline stops the run…", "deadline already past…", CLI `--deadline-epoch-seconds`, "raw file hashed before summary", `RunDeadline` earlier-bound (CostGuardSuite) |
| M2 | Done (README) | AnswerEval "diagnostic refuses a USD ceiling above 0.15… and the command line clamps it" |
| M3 | Done | not code-testable; manifest, JSON copy, `run.sh`, README agree by reading |
| m1 | Done | stale BLOCKED comments replaced |
| m2 | Done | AnswerAudit "claim the verifier calls a definition is still audited when it states data" |
| m3 | Done | AnswerEval "rating CSV has no questionId…" |
| m4 | Done (command + golden test) | BenchmarkGen "golden: the generator writes the same three files for the fixture" |
| m5 | Done | ErRun "pooled numbers are compared with the recorded replay" |
| L1 | Done | LaptopPorts "Titan client settings turn the SDK retry off…" |
| L2 | Done | CostGuardSuite "basis calls every figure an estimate…" |
| L3 | Done | AnswerEval "answer that completed and pushed spend past the ceiling is not cost-stopped"; "a call the guard refuses marks its answer cost-stopped" |
| L4 | Done | BenchmarkFill "move that fails part-way names the replaced and pending files"; "first move that fails rethrows" |
| L5 | Done | BenchmarkFill "version other than v1 is refused…" |
| L6 | Done | docs only |
| L7 | Done | FactoryCountsCli "misspelt option is refused by name"; BenchmarkCli "seed that is not a whole number is refused" |
| OD-3 | Done | GraphChecks composed-counts, wrong-figure, no-figure, source-required, CLI reconciliation tests |

**Changes**
- M1: absolute deadline epoch passed by `run.sh` = `MAX_MINUTES` minus 10 minutes from `run.sh` start (sbt start, compile and graph load inside the window; their duration unmeasured). Margin covers the longest question plus closing writes (H about 6 min: 6 Haiku calls at 40 s + 2 Titan embeds at 60 s; G about 5 min). H `answers.jsonl` and `diagnostic.jsonl` appended and flushed per answer; G `records.jsonl` per question group (a kill loses at most the group in flight). Raw files hashed before any summary. `run.sh` no longer passes `--max-minutes` (option remains).
- M2: H-4 README cites P16-28, in-process bounds (at most 4 calls, USD ceiling at most 0.15), no manifest or approval. Added the USD clamp (P16-28 said 0.15 but only the call count was clamped): `--usd-ceiling` above 0.15 is lowered; a guard allowing more is refused before anything is written. Existing diagnostic tests now pass `usd = 0.15`.
- M3: call cap exactly 460 = 76 × (3 planner + 3 synthesis) = 456 + 4 slack. `MAX_TASKS` 460 in manifest, JSON copy and `run.sh` check; `HAIKU_MAX_CALLS=460`. USD 2.00 binding. Tier S valid by reading `scripts/run-experiment.sh` (burn 0.80 × 90 / 60 = 1.20 < 2.00).
- L1: evaluation-side Titan client `RetryPolicy.none()` (same idiom as `BedrockAnswerModel`), 60 s and 180 s deadlines kept. Setting tested only.
- OD-3: `GraphChecks` reports `composedEdges` = edge-table row sum + RESOLVES_TO (edges-authored rows with `mentionId` ≠ researcher id) + DERIVED_FROM (nodes-work rows); `composedVertices` likewise (distinct resolved mentions, distinct `provenance.snapshotId`). `edgeDifference`/`vertexDifference` = composed − expected; old differences kept as `…RowSumMinusExpected`. `edgesTotal` documented as coauthor edges only; new `coauthorEdgesTotal`. Hardcoded 135788/32225 defaults removed; an expected figure requires `--expect-source TEXT` (recorded in `run-info.json` and basis); no "Neptune load". Prints `GRAPHCHECKS RECONCILIATION …`.
- m5: `er-README.md` documents the reference check. L3: costStopped follows `CostGuard.refusedCount`. L4: partial move failure names replaced and pending files and asks for a restore.
- Docs: benchmark README operation vocabulary and regeneration-check sections; `experiments/README.md` G and H rows and manifest count; G and H READMEs.

**Paste-ready commands**: copied into `../../human-commands.md` (Pending): m4 regeneration check (expect three `REGEN_MATCH` lines; any `REGEN_DIFFERS` means do not run the fill), GraphChecks rerun `graphchecks-a-export-2` (expect `edgeDifference=0`, `vertexDifference=0`; rests on the P16-21 arithmetic), ErExtras (expect `ER_REFERENCE_CHECK status=MATCH … TP=77 FP=0 … precision=1.000 recall=0.802 F1=0.890`). H-4, G and H commands unchanged apart from the new H manifest digest; a new approval record is needed for H; G and H manifests unapproved.

**Unverified**
- Not run: `bash -n`/shellcheck on both `run.sh` files; `experiments/check-manifest-json.sh` (H JSON copy hand-edited); the regeneration check, GraphChecks rerun and ErExtras reproduction.
- `tests/scripts/**` may pin old G/H values (250), `--max-minutes` or `HAIKU_MAX_CALLS`; not read.
- Golden hashes were recorded from the refactored generator: they catch future drift only; equivalence to the 08:40 files rests on the human regeneration check.
- `provenance.snapshotId` key taken from the fixture layout in `tests/adversarial/P16GraphChecksSuite.scala`; a missing key on the real export would show as vertex difference -1.
- One `RetryPolicy` deprecation warning, as in `BedrockAnswerModel`.

Files: under `experiments/evaluation/src/main/scala/scholarchain/evaluation/`: `io/ExportIo.scala`, `runtime/{CostGuard,LaptopPorts}.scala`, `runtime/RunDeadline.scala` (new), `retrieval/RetrievalEval.scala`, `answer/{AnswerEval,AnswerAudit,RatingSample}.scala`, `graph/GraphChecks.scala`, `benchmark/{BenchmarkGen,BenchmarkFill}.scala`, `factory/FactoryCounts.scala`, `er/ErExtras.scala`, `er/ErReference.scala` (new). Tests under `experiments/evaluation/src/test/scala/scholarchain/evaluation/`: `answer/{AnswerEvalSuite,AnswerAuditSuite}`, `retrieval/RetrievalEvalSuite`, `runtime/{CostGuardSuite,LaptopPortsSuite}`, `GraphChecksSuite`, `BenchmarkGenSuite`, `BenchmarkCliSuite`, `BenchmarkFillSuite`, `FactoryCountsCliSuite`, `er/ErRunSuite`. Scripts and manifests: `experiments/{g-retrieval,h-answers}/run.sh`, `experiments/manifests/experiment-{g-retrieval,h-answers}.manifest`, `experiments/manifests/json/experiment-h-answers.json`. Docs: `experiments/{g-retrieval,h-answers}/README.md`, `experiments/benchmark/README.md`, `experiments/README.md`, `experiments/evaluation/er-README.md`.
