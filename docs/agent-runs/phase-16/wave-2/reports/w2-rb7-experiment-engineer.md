# W2-RB7: experiment-engineer report (P16-36: AnswerEval measures, --question-ids)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed; command verbatim in `experiments/h-answers/README.md`, "H-4 rerun on named questions").

---

**Status: COMPLETE.** `sbt quick`: `[success] Total time: 157 s (0:02:37.0), completed Nov 18, 2026, 5:47:24 PM`; other modules `Passed: Total 77` and `Passed: Total 1304`, Failed 0. Evaluation: 318 tests in 19 suites, 0 failures, 0 errors (`AnswerEvalSuite` 38), summed from the JUnit XMLs.

Files: `experiments/evaluation/src/main/scala/scholarchain/evaluation/answer/{AnswerRecords,AnswerEval}.scala`, `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/AnswerEvalSuite.scala`, `experiments/h-answers/README.md`.

1. Item 1: the metered-summary test (old :269-285) is a helper `meteredSummary(stop, n)` with every old assertion kept; `max_tokens` expects 1 call and 1 attempt; a new `end_turn` case keeps the 3-attempt strictness. The diagnostic cap test (old :326-354) uses `Broken(Some("end_turn"))`, still reaching 4 calls and 5 attempts; asserts `maxTokensAttempts` 0 and `retryDecisions` empty.
2. Item 2: `AttemptRec` gains nine optional fields (default `None`); `toJson` only when set, `fromJson` optional, `of` copies from `ModelAttempt`. `diagnostic.jsonl` and `answers.jsonl` carry them; `diagnostic-summary.json` gains `retryDecisions` counts and `attemptMeasures` (question id, attempt, stop reason, output tokens, measures; no `rejections` or `promptId`).
3. Item 3: `Config.questionIds` (recorded in the manifest); `selectedPairs` picks exactly the named questions in order (answerable filter not applied); refuses empty, repeated, unknown (listed) and more than 4 ids; `--question-ids` without `--diagnostic` refused; validated in `execute` before the wiring (graph load), exit 2, nothing written; 4-call and 0.15 USD clamps untouched; `Usage` and `Options` updated.

Tests (all `AnswerEvalSuite`): item 1 "a max_tokens model is metered, makes one call per answer...", "a model with another invalid reply is still retried to the bound...", "the diagnostic stops at four calls and records the stop reason and codes"; item 2 "a max_tokens diagnostic makes one call per question and carries the per-attempt measures, with no text", "an attempt record round-trips every new measure and writes only those that are set", "AttemptRec.of copies the measures of a model attempt, set or not"; item 3 "--question-ids selects exactly the named questions in the order given, answerable or not", "--question-ids refuses an unknown, repeated, empty or surplus id list", "a diagnostic command line with --question-ids answers those questions in that order and keeps the clamps", "a diagnostic command line refuses an unknown question id before writing anything", "a diagnostic run with --question-ids still refuses a guard above the caps, before writing".

Unverified: marker not read; bucket lines use `terraform -chdir=... output -raw data_bucket|vector_bucket` (local state; not run); command not run live; ranking fields covered only by a hand-built `ModelAttempt` (first real evidence is `h4-diagnostic-3`); expected `calls=2` assumes no planner Haiku call for these questions.
