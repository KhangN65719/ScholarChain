# W2-T: test-engineer report (independent tests, P16-30)

Author: test-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**W2-T: COMPLETE.** Five new suites, 174 tests, 0 failures, 0 errors. `sbt quick`: `[success] Total time: 157 s`; root `Passed: Total 1300, Failed 0, Errors 0`; exit 0. Per-suite counts from the JUnit XML in `target/test-reports`.

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `P16W2CostGuardSuite` | 34 | 0 | 0 |
| `P16W2AnswerAuditSuite` | 67 | 0 | 0 |
| `P16W2RetrievalScoringSuite` | 37 | 0 | 0 |
| `P16W2ErExtrasSuite` | 29 | 0 | 0 |
| `P16W2TextOnlySuite` | 7 | 0 | 0 |
| **Total** | **174** | **0** | **0** |

`P16W2Support.scala` is a helper with no tests. `sbt Test/compile` also passed. First run had 2 failures in `P16W2CostGuardSuite`, both test errors (after a stop a fused-mode question raises `CostStopped` out of the workflow, by design); fixed with an `askAfterStop` helper and rerun. No production code edited.

## Coverage

1. Cost guards: caps 0, 1, 3, 7 let exactly that many calls through; Titan cap 4; 16 threads cannot exceed cap 25; after the USD stop nothing reaches the model over 25 attempts; a ceiling below one call's worst case refuses the first call; failed calls charged; either stop blocks model and embedder; an unbillable stub is capped but never money-stopped; a refused call is non-retryable and planner and synthesis paths each log exactly one refusal (checked at the spy model and embedder); nothing reaches either service after a stop; Titan cap 0 in vector mode sends no embed and `CostStopped` reaches the caller. Committed limits: H cap 460 in manifest, JSON copy, `run.sh` and `--haiku-max-calls`; H USD 2.00 and G 0.25 everywhere; region us-east-2, tier S. Diagnostic: CLI clamps `--haiku-max-calls` to 4 and `--usd-ceiling` to 0.15; a guard of 5 calls or 0.1501 refused before writing; 4 and 0.15 accepted; always-failing and junk models get at most 4 calls; no question or answer text; no repeat pass; non-us-east-2 live run refused.
2. AnswerAudit (hand-built export): coauthor edge (valid, missing, wrong endpoints, wrong window, wrong joint count, within tolerance); path hops (chain break, hop count, supporting work not in support, wrong window); publication (missing, title mismatch, trimmed whitespace); metric, ranking, group size, identity; tolerance `abs + rel*|x|` at the boundary; structural support, identifier and numeric accuracy, contradiction rate; review m2 mislabelled `definition` claim still audited, counted as reclassified, wrong number flagged; real definitions excluded and counted; `claimsVerifiedButAuditContradicted`; abstention recall, false abstention, reason-code match, accepted outcomes; file run skips repeat, cost-stopped and failed answers; outputs hold no claim or answer text.
3. RetrievalEval scoring: hand-computed Recall, Precision, MRR, nDCG at K = 2 and 5 with tiered grades and gain 2^g−1; graph, vector, fused ranking; `NotPrecomputed`, `acceptedOutcomes`, `expectedCodes` (code match in graph and fused only; accepted-outcome abstention is not false abstention); free-form `operation` as stratum name (runtime name `community` stays `free-form`); pooled means, `vectorAvailable` scope, outcome rates.
4. ErExtras: per-path recall (pooled TP 7, FP 2, precision 7/9, recall 7/12; path contributions sum to pooled recall); bootstrap matches an independent replay; seed determinism, order independence; strata keep size; percentile index rule; undefined resamples dropped and counted; CLI end to end (identical outputs per seed; "not-identifiable"; exclude and relabel scenarios).
5. Text-only: RetrievalEval (36 asks) and AnswerEval (primary and repeat) pass only question text; label marker strings never reach the ask, session id, model request or embedded query.

## Findings

No test fails against production; nothing routed. Risks not turned into tests:
- USD admission uses spend updated after a call returns, so with several threads more calls than the ceiling allows could be admitted. Not a problem for G and H (`MAX_CONCURRENCY=1`), but not thread-safe.
- The embedder USD figure is an estimate (as the manifests say).

`sbt quick` skips `slowSuites`. Files: `tests/adversarial/{P16W2Support,P16W2CostGuardSuite,P16W2AnswerAuditSuite,P16W2RetrievalScoringSuite,P16W2ErExtrasSuite,P16W2TextOnlySuite}.scala`.
