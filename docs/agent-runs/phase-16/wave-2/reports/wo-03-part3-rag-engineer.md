# WO-3 part 3: rag-engineer report (P16-36, ranking truncation measures)

Author: rag-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: BLOCKED-ON experiment-engineer** (rag work done).

Last `sbt quick` (final sources, alone): `[error] (evaluation / Test / testOnly) sbt.TestsFailedException: Tests unsuccessful`, `Total time: 156 s, completed Nov 18, 2026, 5:40:23 PM`; `Passed: Total 77` and `Passed: Total 1304`, Failed 0. Rag, services and root passed, incl. new `ReplyMeasuresSuite` (15/15). Two failures in `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/AnswerEvalSuite.scala`, both from item 2: line 269 expects 3 `max_tokens` attempts, gets 1; line 326 expects `fake.calls == 4`, gets 3 (later assertions 339-354 change too).

## Changes
- `modules/rag/src/main/scala/scholarchain/rag/ReplyMeasures.scala` (new): `ReplyShape`, `InputShape`, `InputShape.of(bundle)`, `ReplyMeasures.{of, repeatRatio, claimsStarted}`.
- `Model.scala:553-611`: `ModelAttempt` gains defaulted `reply`, `input`, `retryDecision`; in `toJson`/`logLine` only when set. `:677` `MaxTokensNoRetry = "max_tokens-no-retry"`. `:679-684` `StructuredCall.run` takes optional `inputShape`. `:762-764` a rejected `max_tokens` attempt stops the loop and records the decision (failure reason still "output is not valid JSON").
- `Synthesis.scala`: passes `InputShape.of(bundle)`.
- Tests: `ReplyMeasuresSuite.scala` (new); `StopReasonAttemptsSuite.scala` edited (three tests no longer rely on `max_tokens` retrying).
- Prompt, schema, `maxOutputTokens` untouched.

## Test per item (`ReplyMeasuresSuite` unless noted)
Reply length: "reply length is the character count of the reply". Claims started: "claims started counts every claim object, complete or cut"; "claims started ignores braces in strings and other claims keys". Repeat ratio: "a looping reply has a repeat ratio near 1"; "a long reply that never repeats a 32-character window is 0"; "a short reply is 0 and the ratio is deterministic". No text: "the measures of a reply hold no reply text"; workflow canary. Evidence/ranking/ties: "a bundle without a ranking..."; "a ranking reports entries passed in, ranked total and cut-off ties"; "the real ranking evidence is measured from its own fact". Reach result, audit, log: "a truncated ranking answer: one call, measures in result, audit, log". One call on `max_tokens`: "a max_tokens reply makes exactly one call and records why"; "a schema-invalid max_tokens reply is not retried either". Others retry: "other invalid replies still retry up to the bound" (3 calls). Unchanged: "a retry bound of 0 and a valid reply at max_tokens are unchanged".

## Measures (numbers only)
- `replyChars`: length of `rawJson` (Bedrock: first `{` to last `}`); `outputTokens` is exact.
- `claimsStarted`: claim objects opened in the top-level `claims` array, counted in a cut reply.
- `repeatRatio`: share of the reply covered by 32-character windows that began earlier (union / length, 4 decimals; < 32 chars = 0.0; first 65536 chars scanned). A loop approaches 1.0; JSON keys repeat, so a healthy reply is above 0 (no baseline yet).
- `evidenceItems`: items in the bundle sent. Ranking: `rankingEntries` (passed in), `rankedTotal` (ranked in the store), `cutoffTies` (entries sharing the last entry's value), `tieAtCutoff` (retriever flag).

Hypothesis check: `rank()` passes only `resultLimit` entries and the tie flag says equal values beyond the cut are not listed, so the bundle probably does not carry the whole tie list; the tie hypothesis may be wrong.

## Routed
1. experiment-engineer: update `AnswerEvalSuite.scala:269-285`, `:326-354` for one call per `max_tokens` answer (unblocks `sbt quick`).
2. experiment-engineer: `AttemptRec` (`AnswerRecords.scala:81-110`) copies 6 fields; add optional `replyChars`, `claimsStarted`, `repeatRatio`, `evidenceItems`, `rankingEntries`, `rankedTotal`, `cutoffTies`, `tieAtCutoff`, `retryDecision` (set in `AttemptRec.of`, absent-tolerant `fromJson`, `toJson` when present); `diagnosticLine` and `diagnostic-summary.json` pick them up.
3. experiment-engineer: `diagnosticPairs` (`AnswerEval.scala:116-123`) takes the first answerable question per stratum; add `--question-ids Q-RK-01,Q-RK-02` (order given, unknown refused, 4-call and 0.15 bounds kept); Q-RK-01 now costs one synthesis call, so two fit; update `Usage` and the diagnostic test.
4. orchestrator: rerun `sbt quick` after (1).

Unverified: `[success]` and marker write; real `repeatRatio` of a healthy reply; ranking bundle size.
