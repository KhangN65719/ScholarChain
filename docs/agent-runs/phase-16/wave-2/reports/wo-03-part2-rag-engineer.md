# WO-3 part 2: rag-engineer report (P16-32)

Author: rag-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** `sbt quick` passed with the 4096 change.

**Change.** `modules/rag/src/main/scala/scholarchain/rag/Synthesis.scala`: the synthesis request takes the new constant `AnswerSynthesisAgent.MaxOutputTokens` = 4096 (P16-32), replacing the literal `2000` (request at line 105). The planner classifier has its own limit of 200 (`Planner.scala`), never shared the 2000, and is untouched (no planner truncation in the evidence). No prompt or schema changed.

**Tests** (new `modules/rag/src/test/scala/scholarchain/rag/SynthesisOutputLimitSuite.scala`):

| Item | Test |
|---|---|
| Constant 4096, `maxInputTokens` still 12000 | "the synthesis limit is 4096 and the input budget is unchanged" |
| A synthesis call carries 4096 | "a synthesis call carries 4096 as its output limit" |
| Every retry keeps 4096 | "every synthesis retry keeps the 4096 limit" |
| Planner keeps 200 | "the planner classifier keeps its own limit of 200" |
| Prompt and schema versions not bumped | "the prompt and schema versions are unchanged by the limit" |

**Dependencies checked**
- Input budget: `maxInputTokens` (12000) checked in `StructuredCall.run` on input only; `Phase9RepairSuite` m3 unaffected.
- Total context: no total-context limit in the module; 2096 extra output tokens well inside Haiku's window.
- Deadline: no 180 s synthesis deadline in the module; `BedrockAnswerModel` `DefaultAttemptTimeout` 30 s, `DefaultCallTimeout` 40 s, no SDK retry. Diagnostic: 2000 tokens about 8.4 s, so 4096 about 17 s (about 1.7× margin under 30 s). Whether the laptop H run passes its own timeouts to `sdkClient` not checked (no grep available).
- Pins: value not in prompt text, schema or hashes; `Phase9RepairSuite` m2 unchanged; synthesis prompt 1.1.0, schema `synthesis-output/3`.
- Audit record does not record the output limit.

**Result.** Bare `sbt quick`: `[success] Total time: 148 s (0:02:28.0), completed Nov 18, 2026, 4:06:17 PM`; `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1304, Failed 0, Errors 0`. New suite compiled; rag-only total and the five suite lines not isolated.

**Routed.** test-engineer: none found (could not search `tests/**` for `maxOutputTokens == 2000`; full `sbt quick` passed). service-engineer: the part 1 request to log `result.attemptLogLines` (done in W2-S).

**Release record.** Needed once deployed: the deployed service's `max_tokens` rises, with worst-case output cost and latency per synthesis call; deploy-engineer records it. The laptop H run uses it now.

**Unverified.** Real 4096-token latency against the 40 s call deadline; caller overrides of SDK timeouts. Pending: `sbt check`, the human's `sbt clean check`.
