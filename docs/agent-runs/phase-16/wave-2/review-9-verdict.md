# Phase 16 review 9 verdict (re-review of review 8 findings 1 and 3, P16-37)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: APPROVE.** No new defects. Apart from finding 2 (decision rules, pending with the human), the review 8 items are closed; `h4-diagnostic-3` may run once the human fixes the P16-36 rules. Open for that run: the human's confirmation that `terraform output -raw data_bucket` works (finding 4).

**Finding 1 closed.** `BedrockAnswerModel.scala:210` sets `replyShape = Some(ReplyMeasures.of(reply.text))` from the untrimmed text (`rawJson` still from `jsonOf`, `:204`); `Model.scala:735-737` uses it, falling back to `rawJson` only when unset (stub, fakes). `ReplyShape` is `(Int, Int, Double)` (`ReplyMeasures.scala:17`); no new text field reaches `ModelAttempt`, audit or log (`Model.scala:570-614`). Accepted replies unchanged (length check `:745`, parsing and validation on `rawJson`); for Bedrock the recorded measures on accepted attempts now cover the full text incl. any fence or preamble (a recorded-number change only). Path to diagnostic output: `ModelResponse.replyShape` → `ModelAttempt.reply` (`Model.scala:784`) → `AttemptRec.of` (`AnswerRecords.scala:135-137`) → `attemptMeasures` (`AnswerEval.scala:506-516`); `GuardedModel.complete` passes the reply unchanged (`CostGuard.scala:268-299`); live wiring wraps a plain `BedrockAnswerModel` (`LaptopPorts.scala:286-292`). No evaluation test covers the full Bedrock path (info only). The new test (`ReplyMeasuresSuite.scala:234-263`) goes through a real `BedrockAnswerModel` with a fake `ConverseClient`; `chars == full.length` and `claimsStarted == 3` would fail on the trimmed text (2 claims, `:260`); `callsMade == 1`.

**Finding 3 / P16-37 closed.** Comment `Model.scala:676-681` cites `h4-diagnostic-2`. `stopOnMaxTokens` default `false` (`:690`), gated `:770-774`; `Synthesis.scala:114` passes `true`; `Planner.scala:184` default (the only two main call sites). Planner retries tested (`ReplyMeasuresSuite.scala:198-213`, `:215-232`); synthesis stops (`:163-196`, `:313-352`). `StopReasonAttemptsSuite` consistent (planner non-`max_tokens` `:121-194`; synthesis `max_tokens` `:218-254`, one attempt).

Findings 4 and 5 unchanged.

**Pending (not blocking):** the repair report did not read per-suite results, so execution of the new `ReplyMeasuresSuite` tests was not shown; real `repeatRatio` baseline; live full-text shape; marker; `sbt clean check`. Risk: measures on accepted Bedrock attempts now include any preamble, so baselines before and after are not directly comparable.

---

Orchestrator note (2026-11-18): the pending suite count is closed by `modules/rag/target/test-reports/TEST-scholarchain.rag.ReplyMeasuresSuite.xml`: `tests="18" errors="0" failures="0" skipped="0"`, timestamp 2026-11-18T18:10:16 (the 6:12:49 PM `sbt quick` run; 15 from part 3 + 3 new).
