# Work order 05: repair F1 and F2 (`modules/spark/**`)

Issued by: orchestrator, 2026-11-07. Owner: `spark-engineer`. Local only; no commit; no AWS.

## Source of the findings
`test-engineer` (independent tests under `tests/integration/Spark*.scala`, `tests/adversarial/Spark*.scala`) ran `sbt check`: core 41, entityResolution 389, hadoop 207, sparkAnalytics 44 all pass; root 367 of 373 pass, 6 fail. Its agent-reported summary of the failures (the orchestrator has not observed the run; the full report body could not be archived, see `validation.md` V4):

- **F1** (4 tests, `tests/integration/SparkDegenerateInputsSuite.scala`): duplicate works rows and duplicate link rows give a wrong `memberCount` and extra AUTHORED edges.
- **F2** (2 tests, `tests/integration/SparkSummariesSuite.scala`): summary text states a count of unresolved coauthors and a hop limit that are not in `evidenceMetrics`.

## Task
1. Read the failing tests and their fixtures (read only; do NOT edit `tests/**`). Derive by hand what the course contract and `DESIGN.md` require for duplicate input rows and for summary evidence. Do not copy expectations from the implementation.
2. Before repairing, list which test proves each of F1 and F2.
3. Repair `modules/spark/**` so duplicate works rows and duplicate link rows are deduplicated by identity (counted once; one AUTHORED edge per researcher-work), and so every number in a summary text appears in the `evidenceMetrics` it cites (add the unresolved-coauthor count and hop limit to the evidence columns, or drop them from the text; state which and why).
4. If you judge a test expectation wrong, do not change it: report the hand derivation and the disagreement for the orchestrator.
5. Also answer, from the code: what `canonical.authoredNotCounted=2609` counts (exact definition), and label the unlabeled `metrics`, `components`, `communities`, `coreness` lines of `summary.txt` (header names per column).
6. Add or keep module tests for the repaired behavior; do not weaken any test.
7. Run `sbt Test/compile` and `sbt check`; report exact counts per module (core, entityResolution, hadoop, sparkAnalytics, root). No other sbt invocation. If the root tests still fail, quote each failure verbatim.

## Report
STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN (exit codes), TEST RESULTS (exact counts), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE; plus the F1/F2 test-to-fix list, the `authoredNotCounted` definition, and whether the real subset run needs re-running because outputs change.
