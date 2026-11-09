# Review brief, round 1: Phase 9 gate (code-reviewer, read-only, Opus)

You have Read, Grep and Glob only. Do not edit anything, and do not approve work you wrote. Return `APPROVE` or `REQUEST CHANGES`, with every finding batched into one list (id, severity, file:line, evidence, which gate bullet or rule it breaks). A finding that is not a gate or rule violation goes under non-blocking notes.

## Read first
`CLAUDE.md`, `docs/agents.md`, `docs/agent-runs/phase-09/prompt.md` (README Phase 9 section verbatim, plus scope note), `decisions.md` (P1 to P10), work orders 01 to 03, reports 01 to 03, `validation.md`, and `docs/agent-runs/phase-08/decisions.md` (F1).

## Changed files (all untracked/new except those marked modified; no git diff available for new files, so read them)
- storage: `modules/storage/src/main/scala/scholarchain/storage/` `ReadSupport.scala`, `PathPort.scala`, `GraphPartitions.scala`, `ResearcherDirectory.scala`, `Audit.scala` (new); `DynamoDb.scala`, `PathEvidence.scala`, `Support.scala` (modified); test `modules/storage/src/test/scala/scholarchain/storage/ReadPortsSuite.scala`.
- rag: `modules/rag/src/main/scala/scholarchain/rag/` `RuntimeSupport`, `Facts`, `Model`, `Claims`, `Ports`, `Planner`, `Resolver`, `Retrieval`, `EvidenceBuilder`, `Synthesis`, `StubModel`, `Verification`, `Workflow`, `DemoWorld`, `RuntimeDemoMain`; tests in `modules/rag/src/test/scala/scholarchain/rag/` (`PlannerSuite`, `ResolverSuite`, `RetrievalAgentsSuite`, `EvidenceBuilderSuite`, `VerificationSuite`, `ModelPortSuite`, `WorkflowSuite`, `EvaluationSuite`, `RuntimeDemoSuite`, `RuntimeTestSupport`).
- tests: `tests/adversarial/Phase9World.scala`, `Phase9Steer.scala`, `Phase9GateSuite.scala`, `Phase9ModelSuite.scala`; `tests/end-to-end/Phase9BenchmarkSuite.scala`.

## Review the Phase 9 gate, item by item, citing code and the test that proves it
1. User text cannot become arbitrary database code: trace every path from question text to `NeptuneQueryPort`/vector query/audit; confirm only named allowlisted templates with parameters.
2. Ambiguous researcher names never silently resolve; `Session.disambiguation` cannot pick a non-candidate; mention nodes handled.
3. Every numerical and path claim has evidence IDs that resolve in the bundle; look for numbers or claims the verifier can miss (rendering path, units, years, percentages, ranks, spelled numbers, claims restated by the model).
4. F1: no number or structural claim is supported by a `TextChunk`/vector snippet; check the verifier, the evidence builder and the synthesis input for any leak of snippet numbers into deterministic facts.
5. Unsupported claims are removed, weakened or labeled, and the change is recorded; the verdict mapping to core (P2) is sound and documented.
6. Prompt injection: retrieved text, display names and question text are data only; check for any place untrusted text reaches an instruction, a template name, a filter value or a key.
7. Model port: every model output validated against a versioned schema; retries bounded; stub offline and deterministic; no chain-of-thought field; no network or AWS client code anywhere.
8. Graph and semantic retrieval are evaluated separately in the benchmark and tests; the benchmark is not circular (expected facts come from an oracle independent of rag).
9. Honest non-path reporting (only `different-component` is "no path"); depth/result/timeout limits enforced.
10. Audit contents per README item 10, determinism, and evidence persistence order.
11. Module boundaries: storage does not depend on rag; rag does not depend on entity-resolution or job modules; no new dependency; no secrets; no Bedrock/AWS.
12. Authority: only owners touched their paths (storage `modules/storage/**`, rag `modules/rag/**`, test-engineer `tests/**`); no constitutional file touched; no weakened or deleted test.
13. Evidence honesty in the reports: claims in reports match the code; flag anything unverified (the literal prompt hashes were not captured; the benchmark file `target/phase9-benchmark.txt` is generated).

Known limits already recorded and not findings by themselves: P1 to P10, rag report section "Known failure modes", Phase 8 H1/H4.
