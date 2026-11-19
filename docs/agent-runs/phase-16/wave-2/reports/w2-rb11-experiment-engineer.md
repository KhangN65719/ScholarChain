# W2-RB11: experiment-engineer report (review 14 findings 1, 2, 4-8)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: BLOCKED-ON test-engineer.** Code and doc changes made; `sbt quick` fails on a file outside the write set; marker not updated.

Blocker: last completed `sbt quick` ended `[error] Failed: Total 1304, Failed 4, Errors 0, Passed 1300`, `[error] Failed tests: scholarchain.tests.adversarial.P16W2AnswerAuditSuite`, 7:33:28 AM, exit 1. `tests/adversarial/P16W2AnswerAuditSuite.scala` `rankingFact` default `metric: String = "publicationCount"` (~250), the camelCase name the runtime never writes, now `unknown-metric`. Inferred (not read from failure lines): the 4 failures are the ranking tests at ~591-620 (valid, `value-differs`, `order`, `mention-ranked`). Repair for test-engineer: default `"publication-count"` (not run).

Evaluation module (JUnit): 20 suites, 330 tests, 0 failures, 0 errors; `AnswerAuditSuite` 32/32 (was 26).

Stray runs: three background `sbt quick` started, overlapping. (Orchestrator: `b9s8cmqo1` already failed; `b2gcuvfy3` stopped by the orchestrator.)

## Code (`experiments/evaluation/src/main/scala/scholarchain/evaluation/answer/AnswerAudit.scala`, `AuditRules`)
- New table `RankingMetricKeys` (runtime kebab-case → export camelCase, 9 entries: `publication-count`, `distinct-coauthor-count`, `total-coauthor-occurrences`, `average-coauthors-per-paper`, `maximum-coauthors-on-one-paper`, `weighted-degree`, `component-size`, `bridge-score`, `coreness`); helper `rankingRows`.
- Ranking problem codes in order: `empty`, `unknown-metric`, `row-missing`, `key-missing`, `value-differs`, `order`, `mention-ranked`.
- `exportNumbers` ranking branch uses the same table.
- Behaviour change: a ranked mention with no export row is now `row-missing` (was `value-differs`).
- Not verified: `MetricValues.field` in rag not located (grep refused); list built from `CollaborationMetric`, the evidence metric fact and the Planner; `shortest-path-length` deliberately left out (`unknown-metric`).

## Tests (`AnswerAuditSuite.scala`; `AuditFixture.rankingFact` default now `"publication-count"`)
Kebab-case validates (4 metrics); kebab-case wrong value still `value-differs`; row or key missing has its own codes; unmapped name is `unknown-metric` (`publicationCount`, `shortest-path-length`, empty); every table entry maps to a camelCase export key; `exportNumbers` returns ranking values; a ranking claim's numbers match and its evidence is valid; existing mention-ranked expectation updated to `row-missing`. First run failed one test (window years missing in `exportNumbers`), fixed.

## EVALUATION.md
- Finding 2: "affected by the metric-name defect at AnswerAudit.scala (review 14 finding 1); to be replaced by h-audit-2" on ranking validity (~593), numeric accuracy (~597: export-matched count, evidence-only 70, `exportMatchedRate`), `claimsCitingInvalidEvidence` 59 (~599), ranking paragraph (~609, with the review's cause), known item (~784). No h-audit-2 numbers.
- Finding 4 (~541): cap = ranking-fact entries + 2; Q-FF-06 5 entries, cap 7; `claims` post-verifier; 6 ≤ 7.
- Finding 5 (~637, ~783): the review's explanation (same gold v3, `6e5342ddd6`).
- Finding 6 (~270, ~782): stale text removed; `SUMMARY.txt` lines 2-3 cite local paths.
- Finding 7 (~779): points to a new Redaction note bullet (hand scan; parts of 6-7 not re-read; review 14 Grep 0 hits).
- Finding 8 (~611): "Arithmetic, not a field: 140 + 59 = 199".
- Not changed: 0.89 USD wording at 545, 781 (pending the human).
- No 12-digit number added (not grep-confirmed).

## h-audit-2 command
Copied into `../../human-commands.md` Pending by the orchestrator. Run only after the test repair and a passing `sbt quick`.

Next gate: test-engineer repairs the default metric; bare `sbt quick`; human runs `h-audit-2`; re-review; experiment-engineer replaces the marked figures with h-audit-2 values.
