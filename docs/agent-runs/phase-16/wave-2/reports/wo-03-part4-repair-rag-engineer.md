# WO-3 part 4 repair: rag-engineer report (review 10 findings 1-4, 7; P16-41)

Author: rag-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: BLOCKED-ON test-engineer.** Rag repair complete, rag suites pass; `sbt quick` fails 3 tests in `tests/adversarial/Phase9RepairSuite.scala` pinning the old hash. Marker not written.

New prompt hash: `synthesis` stays 1.2.0 (uncommitted, unshipped, pinned by no committed doc or manifest; no convention found requiring a bump before commit): `ada69809dac953a6b32d96fc8ef7eb385f4c72af26fe79829e907318bbc74cd0` (was `c3d58edb…6b9d`). Schema `synthesis-output/3` unchanged (`3ff03e97…`).

## Test per item
1. Finding 1: `RankingClaimCapSuite` "the synthesis prompt is 1.2.0 and states the ranking rule" asserts the exact "For a ranking, keep…" text and that the unscoped wording is gone; `ModelPortSuite` m2 holds the new hash.
2. Finding 2 / P16-41: new "the cap at the planner's largest top-N fits the 4096 limit" derives 12 → 14 claims → 3,482 tokens from `PlannerConfig().maxLimit`, asserts < 4096 and that a top-20 cap (5,386) would not fit; `PlannerSuite` "the ranking metric follows the words of the question" asserts top 500 and top 20 clamp to 12, top 12 stays 12.
3. Finding 3: new "the stub's bridge and multi-metric rankings stay within the cap" (two bridge-ranking questions, one multi-metric): each ranks, does not abstain, stays ≤ entries + 2, at most one definition; existing "the stub's ranking answer stays within the cap and still verifies" passes.
4. Finding 4: new "an over-cap reply cut at max_tokens is not retried": 1 model call, `attempts` = 1, `TooManyRankingClaims` reason, `max_tokens` recorded.
5. Finding 7: code comment only.

## Changes
- `Model.scala:86`: rule 6 sentence starts "For a ranking, keep each claim "text" and the "analysis" to one short sentence."
- `Planner.scala:18`: `maxLimit` 20 → 12 with a P16-41 doc comment; `limitOf` (line 227) already clamps.
- `StubModel.scala` (~277): with a ranking fact, at most one definition (`rankMetric` only); per-wanted-metric and bridge terms skipped; non-ranking unchanged.
- `Synthesis.scala` (comment above `overCap`, ~108): reject-not-trim and the cost (up to 3 attempts at ~0.027 USD each at 4096 output, then `synthesis-failed`).
- Tests: `RankingClaimCapSuite.scala` (three new, extended prompt test; `synthesize` helper takes a `ModelPort`); `PlannerSuite.scala` (clamp via `PlannerConfig().maxLimit`); `ModelPortSuite.scala:64` (new hash).

## sbt quick
Exit 1 (3 `Phase9RepairSuite` failures); console truncated, no `[success]`/`[error]` line or totals. JUnit XML (22:44), 0 failures, 0 errors: `RankingClaimCapSuite` 14, `ModelPortSuite` 14, `PlannerSuite` 17, `Phase11ClaimIdSuite` 8, `RepairRound1Suite` 26, `SynthesisOutputLimitSuite` 5, `WorkflowSuite` 32, `EvaluationSuite` 6, `RuntimeDemoSuite` 2. `Phase9RepairSuite` 25 tests, 3 failures; `Phase8RagSuite` 32, 0 failures.

## Pins for test-engineer
`tests/adversarial/Phase9RepairSuite.scala`, `c3d58edb…6b9d` → `ada69809dac953a6b32d96fc8ef7eb385f4c72af26fe79829e907318bbc74cd0`: "m2: the shipped prompt and schema versions are pinned to literal hashes" (628); "m2 (Phase 11): the pinned literals equal an independent sha256" (643); "m2: an audit record carries the pinned hashes" (671).

## Unverified
Haiku compliance with scoped rule 6; per-claim size and frame; total `sbt quick` counts; no `sbt check`; whether `tests/**` or `experiments/**` assume a top-N above 12 (grep blocked).
