# WO-3 part 6: rag-engineer report (P16-45, review 15 finding 2: reply without usage)

Author: rag-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** No commit, no AWS.

Design: `ConverseReply` gains `usageReported: Boolean = true`; missing counts stay an explicit 0 placeholder (CostGuard ignores the counts of an estimated reply); partial usage keeps the reported count and 0 for the missing one; the default leaves every existing 4-argument call unchanged.

Changes: `modules/rag/src/main/scala/scholarchain/rag/BedrockAnswerModel.scala` lines 36-47 (field and doc); `SdkConverseClient.converse` sets `usageReported = in.isDefined && out.isDefined`; `complete` sets `tokensEstimated = !reply.usageReported` (was hard-coded `false`). `modules/rag/src/test/scala/scholarchain/rag/BedrockAnswerModelSuite.scala`: `UsageStub` client, `sdkModel` helper, four tests.

Tests: "a reply with no usage is flagged as estimated, counts 0"; "a reply with only one usage count is flagged as estimated"; "a reply with both usage counts is metered, not estimated" (5 and 7); "the model carries a client's usageReported = false as estimated". Tests 1-3 run through `SdkConverseClient` with a stub `BedrockRuntimeClient` returning a built `ConverseResponse` with a chosen `TokenUsage`. The existing "request shape" test covers full usage through the fake client (11 and 22).

CostGuard (read only): `experiments/evaluation/src/main/scala/scholarchain/evaluation/runtime/CostGuard.scala` `settleHaiku` 169-180: a reply with `!r.tokensEstimated` is metered; every other case, including an estimated reply, is charged `worstCase(request)` and counted in `haikuEstimated`. So a reply with no or partial usage is now charged the worst case, as the comment at 76-80 promises. No `experiments/**` test change needed; no CostGuard test of this path run.

Validation: one bare `sbt quick`: `[success] Total time: 158 s (0:02:38.0), completed Nov 19, 2026, 8:01:11 AM`; `Passed: Total 77, Failed 0, Errors 0`; `Passed: Total 1306, Failed 0, Errors 0`. JUnit `BedrockAnswerModelSuite` tests=26, errors=0, failures=0 (was 22).

Routed: none. Unverified: one deprecation warning in `modules/rag` main (not identified; may predate); slow suites not run; whether real Bedrock ever omits `usage` unobserved.
