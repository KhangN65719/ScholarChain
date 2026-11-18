# WO-3 part 1 report (rag-engineer), received 2026-11-18, archived as received

**Status: COMPLETE.** `sbt quick` passed on my change and the slot is released.

## sbt result (bare `sbt quick`, one run)
- It finished with `[success] Total time: 163 s (0:02:43.0), completed Nov 17, 2026, 11:33:00 PM`.
- Its last two summary lines read `Passed: Total 77, Failed 0, Errors 0, Passed 77` and `Passed: Total 1041, Failed 0, Errors 0, Passed 1041`.
- I did not isolate the rag-only total, because my shell grep on the output file was refused. I also did not map the two summary lines to specific projects.
- All 15 `StopReasonAttemptsSuite` tests are in the output as passing (lines 2145 to 2160).
- Every existing rag suite I read in the output passed unchanged, including `BedrockAnswerModelSuite` (21 tests) and `ModelPortSuite` (14).
- scalafmt checks passed and nothing failed to compile.

## Test-to-gate list
All tests are in the new `modules/rag/src/test/scala/scholarchain/rag/StopReasonAttemptsSuite.scala`.

| Gate item | Test |
|---|---|
| `end_turn` is logged and exposed | "end_turn is logged and exposed, outcome ok" |
| `max_tokens` is detected and logged, returned not thrown | "max_tokens is detected, logged and returned, not thrown" |
| `guardrail_intervened` and `content_filtered` are logged and thrown | "guardrail_intervened and content_filtered log the reason and throw" |
| Other reasons (`tool_use`, `stop_sequence`) | "other service reasons (tool_use, stop_sequence) are kept as is" |
| Absent or free-text reason, no text leak | "an absent stop reason is none; a free-text one becomes other" |
| Client failure | "a client failure logs stopReason=none and exposes nothing" |
| Per-attempt rejection codes, in order | "each attempt keeps its own rejection code, in order" |
| Retry that succeeds | "an accepted retry keeps the earlier rejection and its own stop reason" |
| Stub output leaves no record | "an accepted first attempt with no stop reason leaves no record" |
| Failed model call is a code | "a failed model call is recorded as a code, with no exception text" |
| Codes never carry model text | "RejectionReason.code never returns model text" |
| `max_tokens` rejection reaches the audit, the result and the log lines | "synthesis rejected at max_tokens reaches the audit and the result" |
| Schema rejection reaches the audit as a code | "a schema rejection reaches the audit as a code, not as model text" |
| Accepted reply at `end_turn` is recorded | "a complete reply at end_turn records its stop reason as accepted" |
| Existing audits unchanged | "the offline stub leaves the audit without modelAttempts" |

## Files changed (all in `modules/rag/**`)
- `src/main/scala/scholarchain/rag/BedrockAnswerModel.scala`
  - Every Converse reply's `stopReason` is normalized to lower-case snake_case, `None` when empty, and `other` for anything else.
  - Every call log line now ends with `stopReason=<x>`, which is `none` on a client failure. A `max_tokens` reply logs `outcome=max-tokens`.
  - A `max_tokens` reply is returned, not thrown, so `StructuredCall` decides whether the cut-off text parses.
  - The blocked path is unchanged except that its log line carries the reason.
- `src/main/scala/scholarchain/rag/Model.scala`
  - `ModelResponse` gains `stopReason: Option[String] = None`.
  - New `ModelAttempt(promptId, attempt, accepted, rejections, stopReason, outputTokens)` with `toJson` and `logLine(queryId)`.
  - New `RejectionReason.code`: known fixed strings pass through, `unexpected key <k>` is cut to `unexpected key`, and anything unrecognized becomes `other`.
  - `ModelUsage` gains `attempts: Vector[ModelAttempt] = Vector.empty`.
  - `StructuredCall` records one attempt only when it was rejected or the reply had a stop reason.
- `src/main/scala/scholarchain/rag/Workflow.scala`
  - `WorkflowResult` gains `modelAttempts` and `attemptLogLines`.
  - The audit payload gets a `modelAttempts` key.
- `src/test/scala/scholarchain/rag/StopReasonAttemptsSuite.scala` is new.
- `maxOutputTokens` in `Synthesis.scala` is untouched.

## Where the evaluation entry point reads `stopReason` and the rejection codes
- **`WorkflowResult.modelAttempts`** is a `Vector[ModelAttempt]` covering both planner and synthesis calls. Each entry has `promptId`, `attempt`, `accepted`, `rejections` (at most 8 codes), `stopReason` and `outputTokens`.
- **`WorkflowResult.audit.payload("modelAttempts")`** holds the same data as a JSON array with fields `promptId`, `attempt`, `accepted`, `stopReason`, `outputTokens` and `rejections`. The key is absent when no attempt was rejected and no stop reason was reported, so stub-backed audits are unchanged.
- **`WorkflowResult.attemptLogLines`** gives one line per attempt: `model-attempt queryId=… promptId=… attempt=N accepted=… stopReason=… outputTokens=… rejections=code|code`.
- **`ModelResponse.stopReason`** is available to anything that calls `ModelPort` directly.
- **`BedrockAnswerModel`'s per-call log** carries `stopReason=` and `outcome=` on every call.

Rejection codes are `output is not valid JSON`, `output is too long`, `an evidence ID is malformed`, `model call failed: <kind>`, `<jsonpath>: <schema rule>` (for example `$.confidence: above the maximum`, `$: unexpected key`) and `other`.

## Not verified and open items
- **Service log (needs a follow-up order):** I did not edit `modules/services`, so the service does not log the attempt lines yet. The service-engineer needs to log `result.attemptLogLines`.
- **Attempt 0 failures:** over-budget and unknown-schema failures make no model call and create no `ModelAttempt`. Their reasons are still in `Failed.reasons` and the stage text.
- **Stage text:** "failed after N attempt(s)" is unchanged.
- **Planner path:** attempts from the planner are carried by the same mechanism, but I wrote no test that forces a planner model call.
- **Rag-only test total:** not isolated.
- **Pending gates:** `sbt check` at the final gate, and the human's `sbt clean check` at the phase gate.
- **Part 2:** `maxOutputTokens` is pending the human's diagnostic.

## Stop hook note
The hook reported changed sources newer than the marker after my run. I made no edit after my passing `sbt quick`; other agents (WO-4, WO-7) are still editing sources. Unverified stop for any sources changed since 11:33 PM.

## Orchestrator note (not part of the report)
- The first delivery was replaced by the agent's reply to the Stop hook; this is the second delivery.
- Follow-up order for Wave 2: service-engineer logs `result.attemptLogLines` in the query service (finding 3's "log the rejection reasons" on the deployed path). Not needed for the laptop evaluation, which reads `WorkflowResult.modelAttempts` directly.
- The two printed totals (77, 1041) did not change after WO-2 and WO-3 added suites, so they are not the storage or rag totals. Per-project totals are still unread; the review's full-suite run must report them.
