# WO-5 interim report, rag-engineer (received 2026-11-17; summary as received; author: rag-engineer subagent)

Status: BLOCKED-ON orchestrator after `format-scala.sh` reported "sbt scalafmtOnly touched files outside this edit". No sbt run.

- Written: `modules/rag/.../RetrievalMode.scala` (`enum RetrievalMode(val wire)` Graph/Vector/Fused, `runsGraph`, `runsVector`, `Default = Fused`, `wires`, `fromWire` exact lower-case), `StageTimings.scala` (`PipelineStage`, `StageTiming.Ran(millis)/Skipped`, `StageClock`, `StageTimings` with `logFields`, `toJson`, `empty`).
- Planned signatures: `CollaborationWorkflow.answer(question, session, mode = Fused): WorkflowResult`; `WorkflowResult` gains `mode` and `timings` (last, defaulted); `WorkflowConfig` gains `stageClock`; `BedrockAnswerModel(client: ConverseClient, modelId, maxCalls = 10, log) extends ModelPort`; `BedrockAnswerModel.sdkClient(region, attemptTimeout, callTimeout)` (explicit region, bounded, no SDK retry); `SdkConverseClient`; `ModelCallException(kind, message, retryable = false, cause)`.
- Behavior: Graph skips semantic; Vector skips graph with a Neptune-free resolver; Fused byte-identical (no new stage entries, no audit key).
- Planned suites: `RetrievalModeSuite`, `BedrockAnswerModelSuite` (fake client; cap: call 11 refused with no client call; verifier still applied; timings Ran/Skipped).
- Settings: rag reads no environment variables. Bedrock SDK already reachable through storage.

## Orchestrator ruling (2026-11-17)
False positive from parallel writers: every "outside" file listed (`build.sbt`, `packaging/**`, storage `EmbeddingWorkMessage.scala`, `WorkQueue.scala`) belongs to another active Wave 2 owner. The hook compares tree state around the edit, so concurrent writes appear as collateral. rag-engineer resumed with the rule: continue unless a rag or prompts file other than its own edit changes. Planned signatures accepted.
