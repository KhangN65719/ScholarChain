# Phase 16 Wave 2 review 3 verdict (re-review of W2-RB2 and W2-TR)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only; no commands.

**Verdict: REQUEST CHANGES.** One new LOW finding, docs-only. All six review-2 findings RESOLVED.

## Review-2 findings

1. F1 RESOLVED. `RawAppenderSuite.scala:9-18` reads back after each `append` and before `close` (8-byte lines, so any user-space buffer fails it). Kill tests read the raw file inside `dying` (`AnswerEvalSuite.scala:467-490`, `RetrievalEvalSuite.scala:261-277`). The surviving `AnswerEvalSuite` mutant does not matter: `BufferedOutputStream.write(b, off, len)` with `len >= 8192` writes through; both entry points use the same class (`AnswerEval.scala:173`, `RetrievalEval.scala:279`), pinned by `RawAppenderSuite`.
2. F2 RESOLVED. `RetrievalEval.scala:293` records refusals at question start; `:368-369` `costStopped = refusedCount > refusedAtQuestion || records(i).costStopped`. Real refusals still count (`CostGuard.scala:115-124`); regression `RetrievalEvalSuite.scala:185-212`; wall-clock case `:290-323`.
3. F3 RESOLVED. `GraphChecks.scala:697-704`, `:720`, `:731-735`; test `GraphChecksSuite.scala:350-397`; the human's `-2` command uses only known flags.
4. F4 RESOLVED. `P16W2GraphCompositionSuite.scala` independent (GraphChecks imports nothing from storage; projector reads the same export via `JsonlExportSource.open`, `:71-75`); literal pins `:86-87` (19 edges, 14 vertices); non-vacuous `:97`, `:111`; no-resolved-mention case `:115-126`. Residual: one fixture shape.
5. F5 RESOLVED as asked; repair introduced N1. "other seven" (`README.md:98`); G row `:77` and H row `:78` match their manifests; Sum arithmetic over printed rows correct.
6. F6 RESOLVED. `P16W2CostGuardSuite.scala:481-488` lower bounds follow from the premise.

## New finding

**N1, LOW (docs), owner experiment-engineer.** `experiments/README.md:71-76`, `:79`: A-F rows carry Phase 13 draft values that no longer match the manifests, so the Sum (10.30, 7.835) matches no set of manifests (line 5 says the table predates Phase 15).

| Manifest | Max minutes (table / manifest) | Ceiling (table / manifest) | Burn (table / manifest) |
|---|---|---|---|
| A | 60 / 75 (`experiment-a-baseline.manifest:29`) | 0.75 / 0.75 | 0.60 / 0.60 |
| B | 90 / 90 | 3.50 / 2.40 | 2.02 / 1.55 (`:39-40`) |
| C | 60 / 60 | 1.00 / 0.60 | 0.70 / 0.45 (`:38-39`) |
| D | 90 / 70 | 1.50 / 0.65 | 0.85 / 0.55 (`:40-44`) |
| E | 60 / 60 | 0.80 / 0.45 | 0.62 / 0.42 (`:37-38`) |
| F | 30 / 30 | 0.50 / 0.25 | 0.62 / 0.40 (`:43-44`) |

Current-manifest ceilings sum to 7.35; projected about 6.09. Required: (a) update A-F rows and recompute, or (b) label the mixed Sum and give current totals. No sbt needed.

## Note

`GraphChecks.scala:711` exhaustivity warning predates the repair (E029, `wave-1/reports/wo-04-experiment-engineer-slice-1.md:14`, `rb1-a-experiment-engineer.md:49`); logically exhaustive; optional cleanup `case _ =>` with `rest.head`.

## Not performed

Any sbt run, the mutation run, H record sizes; hand derivation of the 19/14 pins; `check-manifest-json.sh`; diff against 51814c3. Pending with owners: agent `sbt check`, human GraphChecks `-2`, harness run, human `sbt clean check`.

Next gate: experiment-engineer fixes N1; re-review of `experiments/README.md:65-79` only.
