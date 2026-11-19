# WO-3 part 3 repair: rag-engineer report (review 8 findings 1 and 3, P16-37)

Author: rag-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

Both repairs in; bare `sbt quick` passes: `Passed: Total 77, Failed 0, Errors 0`; `Passed: Total 1304, Failed 0, Errors 0`; `[success] Total time: 163 s (0:02:43.0), completed Nov 18, 2026, 6:12:49 PM`. Per-suite output for the new tests not read (pass inferred from `[success]`); which total is rag not confirmed (3 tests added, 1304 unchanged); marker not verified. The earlier `AnswerEvalSuite` failures no longer appear (experiment-engineer's W2-RB7 edits).

**Tests**
- Finding 1: `ReplyMeasuresSuite` "Bedrock: measures come from the full reply, past the last }": `StructuredCall.run` through a real `BedrockAnswerModel` with a fake `ConverseClient`; reply `Sure: {"schema":"s","claims":[{"a":1},{"b":2},{"c":"` + 200 repeats of a sentence, `stop_reason=max_tokens`; asserts `chars == full.length`, `claimsStarted == 3`, `repeatRatio == ReplyMeasures.repeatRatio(full)` and `> 0.9`; shows the trimmed text would measure lower (2 claims, lower ratio).
- Finding 3, planner: "a planner max_tokens rejection still retries up to the bound" (3 calls, no `retryDecision`); "the planner agent retries a max_tokens reply with the hint" (`QueryPlannerAgent`, 3 requests, 2 and 3 carry `retry`).
- Finding 3, synthesis: "a max_tokens reply makes exactly one call and records why" and "a schema-invalid max_tokens reply is not retried either" pass `stopOnMaxTokens = true`; "a truncated ranking answer: one call, measures in result, audit, log" through `AnswerSynthesisAgent` and the workflow. `StopReasonAttemptsSuite` not opened.

**Changes**
- `Model.scala` `ModelResponse`: optional `replyShape: Option[ReplyShape]` (numbers only). `StructuredCall.run`: shape = `reply.replyShape.getOrElse(ReplyMeasures.of(reply.rawJson))`; new `stopOnMaxTokens: Boolean = false`, the no-retry rule fires only when true. `MaxTokensNoRetry` comment cites `h4-diagnostic-2` (all three Q-RK-01 retries stopped at 4096, each with the retry hint) and says the planner keeps hinted retries.
- `BedrockAnswerModel.scala` `complete`: `replyShape = Some(ReplyMeasures.of(reply.text))` from the untrimmed Converse text.
- `Synthesis.scala` `synthesize`: `stopOnMaxTokens = true`. `Planner.scala` not edited (default keeps retries).
- `ReplyMeasures.scala`: `ReplyShape` doc says `chars` is the whole reply. `ReplyMeasuresSuite.scala`: the tests above.

Routed: none new; the `LaptopPorts` log sink is no longer needed.

Unverified: real `repeatRatio` of a healthy reply; live Bedrock full-text shape (fake client only); no `sbt check`, no live run.
