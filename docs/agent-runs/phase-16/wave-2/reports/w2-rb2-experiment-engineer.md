# W2-RB2: experiment-engineer repair batch 2 report

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**W2-RB2: COMPLETE for F1, F2, F3, F5. F4 not done. Awaiting re-review.** No files outside `experiments/evaluation/**` and `experiments/README.md`; no AWS, Python or commit.

Final `sbt quick`: `[success] Total time: 157 s (0:02:37.0), completed Nov 18, 2026, 12:59:31 PM`. Evaluation JUnit XML: 307 tests, 0 failures, 0 errors, 19 suites. Root: `Passed: Total 77` and `Passed: Total 1300`, Failed 0, Errors 0.

| # | Status | Proving test |
|---|---|---|
| 1 | Done | `RawAppenderSuite` "every append is on disk before close" (new); kill tests in `AnswerEvalSuite` and `RetrievalEvalSuite` read the raw file inside `dying` before the throw |
| 2 | Done | `RetrievalEvalSuite` "a wall-clock halt that skips the direct search does not mark cost-stopped" |
| 3 | Done | `GraphChecksSuite` "an unknown option is refused by name and nothing is written" |
| 4 | Not done | see below |
| 5 | Done | docs |

- F1 verification: temporarily wrapped the `RawAppender` stream in an unflushed `BufferedOutputStream`; `sbt quick` failed `RawAppenderSuite` ("Obtained empty output!") and the `RetrievalEvalSuite` kill test; reverted, final run clean. The `AnswerEvalSuite` kill test did not fail under the mutation (inferred: its records exceed the 8 KB buffer; not confirmed). For H, `RawAppenderSuite` proves per-append flushing.
- F2: `costStopped` set only when `guard.refusedCount` rose during the question (`refusedAtQuestion` captured at question start); `directNote` unchanged.
- F3: `GraphChecks.parseArgs` returns `Unknown option: <name>` outside the six documented flags; exit 2, no run directory; all six still accepted.
- F5: "other seven"; G and H rows in the cost table; Sum 7.835 projected, 10.30 ceilings (G 60 min, 0.10 burn, 0.25 ceiling; H 90 min, 0.80 burn, 2.00 ceiling).
- F4 not done: `SparkGraphProjector(...).project()` needs a Spark session; the evaluation project has no `Test / fork` or `--add-opens`, and such a suite belongs in `slowSuites` (`build.sbt`, build-engineer). A `tests/` suite with "Spark" in its name already gets the forked group; suggest test-engineer, or accept F4 as optional.

Unverified: no real-data run; `GraphChecks.scala:711` exhaustivity warning appears on recompile, origin not checked.

Files: `experiments/evaluation/src/main/scala/scholarchain/evaluation/{retrieval/RetrievalEval,graph/GraphChecks}.scala`; `experiments/evaluation/src/test/scala/scholarchain/evaluation/{RawAppenderSuite (new),GraphChecksSuite,answer/AnswerEvalSuite,retrieval/RetrievalEvalSuite}.scala`; `experiments/README.md`. `ExportIo.scala` mutated and reverted (no net change).
