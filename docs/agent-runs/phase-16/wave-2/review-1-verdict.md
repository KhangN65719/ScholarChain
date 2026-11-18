# Phase 16 Wave 2 review 1 verdict (batched, P16-27)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Scope: W2-S, W2-E, W2-R, W2-B, W2-H, W2-I against baseline 51814c3. Read/Grep/Glob only; current files read, no diff against 51814c3.

**Verdict: REQUEST CHANGES.** 4 MAJOR, 6 MINOR, 7 LOW.

## Focus points

1. Cost guards: mostly sound. Cap and USD stop checked before every Titan and Haiku call (`CostGuard.scala:130-151`); planner and synthesis both use `ports.model` and go through `GuardedModel` (`Workflow.scala:123,145`); a refused call raises a non-retryable `ModelCallException` and the stop stays set; the Bedrock SDK client has retries off. Ceilings match: G 0.25, H 2.00, H call cap 250. Diagnostic limit 4 clamped (`AnswerEval.scala:480-485`) and re-enforced before any write (`:119-124`). Region locked to us-east-2; no real-resource defaults; no secrets. Exceptions: M3, L1, L2.
2. Text-only: holds (`Ask` takes text, session, mode; `QuestionRec` has `questionId` and `text`; `Session` carries no state).
3. Scoring: consistent today. Rules match the benchmark README and P16-10. Only `RetrievalScoring.effectiveStratum` reads `label.operation`, only for free-form labels; generated labels scored by `stratum`; `AnswerAudit` and `RatingSample` never read `operation`. Remaining risk L7.
4. BenchmarkFill: correct. 8 texts match P16-23 verbatim; write-once; refuses before writing; appends to old bytes. Guards fit the real files (`pending-human` slots with `text: null`, no `changes` key yet, `vectorExportDir` matches). `--generator-commit $(git rev-parse HEAD)` correct if run straight after the Wave 2 commit.
5. ErExtras: correct (stratified bootstrap keeps stratum sizes, seeded; per-path recall correct; 12-pair claim reported "not-identifiable" with a labelled proxy set). See m5.
6. Manifests G/H: valid under `run-experiment.sh`; JSON copies match; comments stale (m1).
7. AwsRuntimeSuite: not weakened; the 200 ms settle makes it stricter.
8. Harness edits: not weakened; G/H cases should pass as A-F do (unobserved).
9. H-4 without a manifest: M2.

## Findings

- **M1 (MAJOR)** `RetrievalEval.scala:239`, `:351-359`; `AnswerEval.scala:137`, `:238-247`; `experiments/g-retrieval/run.sh:46`; `experiments/h-answers/run.sh:59`. The in-process deadline (`MAX_MINUTES - 5`) starts after sbt startup, compile and the 6 GB `InMemoryNeptune` load, and is checked only between questions (one H question up to 3 × 180 s synthesis plus planner). `run_bounded` kills at `MAX_MINUTES`; `records.jsonl`/`answers.jsonl` are written only after the loop, so a kill loses every record after the spend. Required: deadline from process start (or an absolute deadline) with a stated margin for graph load and the longest question; append-and-flush raw records as produced, hash before the summary; test "killed mid-run keeps the records so far". Owner: experiment-engineer.
- **M2 (MAJOR)** `experiments/h-answers/README.md:18-25`. D10 (approved, P16-1) names the Haiku diagnostic among `run-experiment.sh` runs with manifest, approval record and clean tree; the README runs it directly via `sbt runMain`. Required: (a) recorded human amendment of D10 for H-4, or (b) a diagnostic entry point (e.g. `experiments/h-answers/diagnostic.sh`) plus a manifest. Owner: human decides; experiment-engineer implements (b).
- **M3 (MAJOR)** `experiment-h-answers.manifest:21-24`; `experiments/h-answers/run.sh:30`, `:40`; W2-R report. "76 × 3 = 228" omits planner calls: on a rule miss the planner calls Haiku through `StructuredCall` with up to 3 attempts (`Model.scala:540-546`, via `Workflow.scala:123`); free-form and out-of-scope questions likely miss; worst case about 6 calls per answer. With cap 250 H can halt early (and with M1, lose the run). Required: correct the arithmetic; raise the cap (USD stop binding) or record the human's acceptance of an early halt; manifest, `run.sh` and README agree. Owner: experiment-engineer; human re-confirms P16-3.
- **M4 (MAJOR, open gate)** W2-T not delivered (`tests/adversarial/P16W2*.scala` absent; P16-27 does not list it). Required: run W2-T with exact counts, at least for cost guards, before any AWS run (H-4, G, H), or an explicit human waiver. Owner: orchestrator dispatches test-engineer.
- **m1 (MINOR)** `experiment-g-retrieval.manifest:10-11`, `experiment-h-answers.manifest:10-11`: stale "BLOCKED until infra-engineer widens ... A to F only". Owner: experiment-engineer.
- **m2 (MINOR)** `AnswerAudit.scala:370-383`, `:401-402`: claim `kind` taken from the verifier's report decides `factual`; a claim the verifier calls `definition` skips structural-support and contradiction rates. Required: classify on the evaluation side or report the count excluded by verifier kind; test with a mislabelled factual claim. Owner: experiment-engineer.
- **m3 (MINOR)** `RatingSample.scala:126`: `questionId` in the rating CSV encodes the stratum (e.g. `Q-UA-03`, `Q-FF-01`), contradicting `:139-141` and the rubric, biasing "abstention appropriate". Required: drop `questionId` from the CSV (key file maps `sampleId`). Owner: experiment-engineer.
- **m4 (MINOR)** `BenchmarkGen.scala` refactor (shared label builders) has no evidence it still produces the frozen 48 lines. Required: after the Wave 2 commit, rerun BenchmarkGen with the 08:40 flags into a scratch directory outside the repository and compare sha256 with the manifest (no AWS), or add a golden test. Owner: experiment-engineer authors; human runs.
- **m5 (MINOR)** ErExtras re-implements counting rules, checked on fixtures only. Required: `er-README.md` and the paste-ready expectation state pooled P/R/F1 and TP/FP must equal `phase-05/gold/eval-v3-r3/evaluation-report.txt` (1.000, 0.802, 0.890); preferably the tool prints a comparison line. Owner: experiment-engineer.
- **m6 (MINOR)** The fill modifies committed benchmark files; `run-experiment.sh:297` (`require_clean_tree`) then refuses G/H. P16-27 has no commit of the fill output. Required: add "commit the filled benchmark" between steps (2) and (5). Owner: orchestrator, human.
- **L1 (LOW)** `AwsSupport.scala:196-203`: Titan keeps the SDK default retry policy, so one admitted call can make several requests. State in `CostGuard.Basis` or set `RetryPolicy.none()` on the evaluation client.
- **L2 (LOW)** `CostGuard.scala:126-128`: characters ÷ 4 is an estimate, not a bound; spend can pass the ceiling by up to one call. Say "estimate" in `CostGuard.Basis` and the manifests.
- **L3 (LOW)** `AnswerEval.scala:169`, `CostGuard.scala:170-171`: a completed answer that pushed spend past the ceiling is marked `costStopped` and excluded. Mark only when a call was refused.
- **L4 (LOW)** `BenchmarkFill.scala:375-382`, `:459-463`: a failure between the four file moves still reports "nothing written".
- **L5 (LOW)** `BenchmarkFill.scala:452-453`: any `--version` fills with v1 questions. Refuse other than v1.
- **L6 (LOW)** `experiments/benchmark/README.md:70` ("lands in the next slice"); `experiments/README.md:9`, `:81` ("six manifests").
- **L7 (LOW)** `FactoryCounts.scala:814-822` accepts unknown options silently (a misspelt `--rewrites` gives zero rewrites); `BenchmarkGen.scala:1621-1624` silently defaults a non-numeric `--seed`. Document the `operation` vocabulary difference (runtime names vs stratum names) in the label rules before any WO-9 planner-accuracy metric.

All L items owner: experiment-engineer.

## Checks not performed (pending)

- Any sbt run; the orchestrator's full Wave 2 `sbt check` is not in the evidence (AwsRuntimeSuite fix unproven under load).
- Harness cases `8.committed-manifest.{G,H,count}`, `8.refuse.unknown-experiment-id`, renamed `15.*`.
- `bash -n`/`shellcheck` on both `run.sh` and `scripts/run-experiment.sh`; `check-manifest-json.sh`.
- Real v1 manifest through the fill's round-trip guard; FF-01 (≤25) and FF-02 (≤30) on the real export.
- m4 regeneration; m5 reproduction of eval-v3-r3.
- Live Titan, S3 Vectors, Bedrock; graph-load duration (M1); pricing rates.
- Diff of `BenchmarkGen.scala`, `QueryService.scala`, `AwsRuntimeSuite.scala` against 51814c3.

Next gate: one repair round (experiment-engineer: M1, M3, m1-m5, L1-L7; W2-T dispatch), re-review of changed items, orchestrator `sbt check`, harness run.
