# WO-5 report, rag-engineer (received 2026-11-17; summary of the report as received; author: rag-engineer subagent)

Status: READY-FOR-SBT. Nothing compiled or run. Interim report and the orchestrator's formatter ruling: `wo-05-rag-engineer-interim.md`.

Formatter: later `format-scala.sh` runs failed with `sbt.boot.lock` "Address already in use" (concurrent formatter sbt processes from parallel Scala writers), so formatting is unverified; expect `scalafmtCheck` findings at the first sbt run.

## Files (`modules/rag/src/`)
Created: `main/.../RetrievalMode.scala`, `StageTimings.scala`, `BedrockAnswerModel.scala`; `test/.../RetrievalModeSuite.scala`, `BedrockAnswerModelSuite.scala`. Modified: `Workflow.scala` (modes, timings, Neptune-free resolver for Vector, audit key), `Model.scala` (`ModelCallException`; `StructuredCall` stops on non-retryable), `EvidenceBuilder.scala` (`hasSemantic`). `prompts/**` untouched.

## Signatures
- `enum RetrievalMode(val wire)` Graph/Vector/Fused; `runsGraph`, `runsVector`; `Default = Fused`, `wires`, `fromWire(text): Option[RetrievalMode]` (exact lower-case). Use `RetrievalMode.Vector` qualified (shadows the collection).
- `CollaborationWorkflow.answer(question, session, mode = RetrievalMode.Fused): WorkflowResult`; `WorkflowResult(..., audit, mode = Fused, timings = StageTimings.empty)`; `WorkflowConfig(..., auditTtlSeconds, stageClock = StageClock.system)`.
- `PipelineStage` (6), `StageTiming { Ran(millis), Skipped }`, `StageTimings` (`of`, `millis`, `ran`, `all`, `logFields` keys `planningMs`, `graphRetrievalMs`, `vectorRetrievalMs`, `fusionMs`, `synthesisMs`, `verificationMs` as number or "skipped", `toJson`).
- `ConverseClient`, `ConverseTurn(modelId, system, user, maxTokens, temperature)`, `ConverseReply(text, inputTokens, outputTokens, stopReason)`, `SdkConverseClient(BedrockRuntimeClient)`.
- `BedrockAnswerModel(client, modelId, maxCalls = 10, log, nanos) extends ModelPort` (`modelVersion` "bedrock:<modelId>", `callsMade`); `DefaultMaxCalls 10`, `DefaultAttemptTimeout 30 s`, `DefaultCallTimeout 40 s`; `sdkClient(region, attemptTimeout, callTimeout)` (explicit region, default credential chain, no SDK retry); `mapError`.
- `ModelCallException(kind, message, retryable = false, cause)`; kinds `cap-exceeded`, `timeout`, `throttled`, `access-denied`, `rejected`, `service-error`, `client-error`, `blocked`, `unknown-prompt`, `unknown-schema`.

## Behavior
Graph: no embedding, no vector read. Vector: no graph or Neptune read; semantic chunks (and identity record) may be cited; uncertainty note "Graph evidence was not retrieved..."; no chunks gives `no-deterministic-evidence`. Fused unchanged (no new stage entries, no `retrievalMode` audit key; stage clock separate from `RuntimeClock`). Skipped stages recorded `Skipped`. Bedrock: a call that reaches the client counts against the cap; call 11 refused with no client call; client failures non-retryable; invalid replies retried by `StructuredCall` within the cap; one log line per call (IDs, sizes, ms, outcome), never prompt, answer or SDK message text.

## Settings
rag reads no environment variables; WO-8 owns `SCHOLARCHAIN_*`.

## Tests per gate item
RetrievalModeSuite: Graph/Vector evidence and retriever isolation; Fused identical with and without the argument (plus existing `WorkflowSuite`, `Phase11SemanticDegradeSuite`); insufficient-evidence per mode; timings non-negative and Skipped (not 0 ms); log fields and JSON; wire parsing. BedrockAnswerModelSuite: request shape and SDK translation; cap (default 10, call 11 refused, failed call counts, cap through the workflow); eight error-mapping tests; non-retryable ends after one attempt; other exceptions still retried; verifier still drops an unsupported claim; logs hold no text.

## Unverified
Nothing compiled. SDK builder chains in `SdkConverseClient` and tests written from memory (`AwsServiceException.builder()`, `BedrockRuntimeClient` stub, `RetryPolicy.none()` possibly deprecated). Vector demo index returning chunks for the Ada 2000-2009 question assumed. Fused audit stage list derived by reading.
