# WO-3 part 5: rag-engineer report (review 14 finding 3, tokensEstimated)

Author: rag-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** One bare `sbt quick`: `Passed: Total 77, Failed 0, Errors 0`; `Passed: Total 1306, Failed 0, Errors 0`; `[success] Total time: 149 s (0:02:29.0), completed Nov 19, 2026, 7:46:36 AM`. Nothing outside `modules/rag/**`; no commit, no AWS.

Change: `modules/rag/src/main/scala/scholarchain/rag/Model.scala:663-667` `ModelUsage.zero` now `estimated = false` (identity for `+`); `+` (:659) still ORs, so any estimated call makes the total estimated. Other readers of `zero`: `Planner.scala:94, :103, :112` (refusal or rule-planned, no call); `Model.scala:700, :706` (unknown schema, over budget, no call); `Workflow.scala:197`, `Model.scala:709` fold real calls; `Workflow.scala:672` writes `tokens.estimated` to the audit. A no-call workflow reports `estimated = false` with `modelCalls = 0`. Rule-planned questions are not estimated until synthesis calls; with the stub (`tokensEstimated = true`) synthesis still makes the total estimated.

Tests (`modules/rag/src/test/scala/scholarchain/rag/ModelPortSuite.scala`): "p16 usage: zero is the identity, so zero plus metered is metered"; "p16 usage: zero plus an estimated call is estimated"; "p16 usage: any estimated call makes the total estimated"; "p16 usage: a workflow whose calls are all metered is not estimated" (checks `audit.payload("tokens")("estimated")`; wrapper keeps stub replies, reports `tokensEstimated = false`); "p16 usage: one estimated call makes the workflow estimated" (metered malformed reply then estimated valid retry; `modelCalls = 2`, estimated true); "p16 usage: a workflow that makes no model call is not estimated" (blank question, `invalid-question`, `modelCalls = 0`).

Routed: none needed. `EvidenceService.scala:114` (service-engineer) exposes the audit flag: its API output changes from always true to false when no call was made or every call was metered (intended correction); no services test failed. No `experiments/**` or `tests/**` failure (could not search for pins; `slowSuites` not covered by quick). The existing H run keeps its recorded `tokensEstimated: true`; per-call `tokensEstimated` and `haikuCallsChargedAtWorstCase` stay the reliable signals for that run.

Unverified: per-module rag totals; new tests not confirmed by name in the 6,389-line log; a fresh `answer-summary` showing false needs a rerun.
