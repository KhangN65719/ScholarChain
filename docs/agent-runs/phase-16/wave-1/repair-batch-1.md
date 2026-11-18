# Phase 16 Wave 1 repair batch 1 (from review 1)

Issued by the orchestrator on 2026-11-18. Source: `review-1-verdict.md`; decisions P16-10 to P16-12. Common rules of `work-orders.md` apply. sbt slot order: storage-engineer, then experiment-engineer, then test-engineer; infra-engineer runs no sbt. Each report goes in the final message, repeated in any Stop-hook reply.

## RB1-A experiment-engineer (findings 1-6, 9, 11-14; P16-10)
Write set: `experiments/evaluation/**`, `experiments/benchmark/**`.
1. Coauthor labels (1): include every `edges-coauthored` edge for (researcher, window), `mention-coauthor:v1:` and `coauthor:v1:`; item caps at or above the runtime limits (coauthors 25, `Retrieval.scala:70`); community members cap at or above the group size allowed. State the rules in `experiments/benchmark/README.md`.
2. Ranking ties (2): refuse metric and window pairs whose tie crosses the cut-off N; label exactly N items. Grades tiered per P16-10 (ranks 1-3 grade 3, 4-6 grade 2, 7-10 grade 1).
3. Absent-pair path questions (3): label `abstain=false` with expected path status `NotPrecomputed`; the scorer accepts either that status or an abstention as correct (plan: "expect abstention or unreachable"). State it.
4. Abstention mapping (4): commit a class-to-runtime-code table; add a stub-mode test running each unanswerable template through `CollaborationWorkflow` (no AWS) that pins the code actually produced; templates that do not abstain (for example UA-08) are changed or dropped, with the reason stated.
5. FactoryCounts aliases (5): aliases only for document attribution; finding text matches full role names or explicit owner tags only; a test with a sentence that falsely matches an alias.
6. First-pass evidence (6): one evidence row per (scope, agent) pair naming the attributing document and the first-round review file.
7. Prose counters (9): deduplicate one event across files where an id allows it; classify hook refusals into harmless-read refusals vs refusals of a state-changing or unsafe action; output upper-bound labels where classification is not possible.
8. GraphChecks (11): non-zero exit and an `INCOMPLETE` line when a required table is missing; count support rows to non-existent edges; fix the `danglingEndpointRate` denominator.
9. VectorCoverage (12): check s-export file sha256 against `chunkset-manifest.json` `source.files` and the `by_window` counts; report subjectId overlap between the A and S exports.
10. Manifest (13): record a dirty-tree flag; keep the versioned name `BENCHMARK-MANIFEST-v1.json` and state it in the README (plan D4 is amended to the versioned name).
11. Questions file (14): keep `stratum` in the questions file only if RetrievalEval/AnswerEval send `text` alone; record that rule in the README (the test lands with those entry points in slice 2).
Gate: tests for each item; `sbt quick` when granted the slot.

## RB1-B test-engineer (findings 7, 19; plus a case for P16-11)
Write set: `tests/**`.
1. Independent label-rule oracle tests from the plan and P16-10, on hand-built export fixtures, against the BenchmarkGen API in `reports/wo-04-experiment-engineer-slice-1.md`: coauthor items include both edge prefixes and respect caps; ranking labels exactly N with tiered grades and refusal on a boundary tie; absent-pair path label non-abstaining with `NotPrecomputed`; abstention classes per the committed mapping; questions carry no label fields beyond `stratum`.
2. A harness case: the smoke output contains no answer text (P16-11), using a scripted body with a distinctive answer string.
3. `tests/scripts/README.md`: R3 paragraph.
Gate: exact counts; `sbt quick` when granted the slot.

## RB1-C storage-engineer (finding 15)
Write set: `modules/storage/**`. `listSizes` does not list files whose real path leaves the root; `emptyObjects` and real files agree in `get` and `listSizes` (refuse the overlap or serve one consistently). Tests. `sbt quick` (holds the slot first).

## RB1-D infra-engineer (findings 16, 17; P16-11)
Write set: `scripts/smoke-test.sh`, `scripts/README.md`. Replace `RESPONSE_HEAD` with identifier fields only (queryId, abstained, evidence count, abstentionReason). README: remove the stale UNVERIFIED at `:249` (fields confirmed at `QueryService.scala:449-450`, `Contracts.scala:425-428, 484-489`), the duplicate digest sentence and the stale "Cases to add" at `:254`. No sbt.
