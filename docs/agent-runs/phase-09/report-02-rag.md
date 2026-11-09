# Report 02: rag-engineer, runtime collaboration-analysis workflow (as received; condensed)

**Status: COMPLETE.** Work order 02. Only `modules/rag/**` edited; no Phase 8 file modified; no new dependency. `sbt check` and real-data runs not run (as ordered). No slow suite to add (rag tests about 1 s).

## Test-to-item mapping (test dir `modules/rag/src/test/scala/scholarchain/rag/`)
1 Planner: `PlannerSuite` (17; all 11 `QueryOperation` cases, windows, names, ranking metric, no composite score, off-allowlist output refused after 3 calls, null refused, malformed retried, `reasoning` key rejected, rules decide before model). 2 Resolver: `ResolverSuite` (10). 3 Metrics, 4 Paths, 5 Community, 6 Semantic: `RetrievalAgentsSuite` (23) plus `WorkflowSuite` bridge test and `EvaluationSuite` semantic top-1. 7 Evidence: `EvidenceBuilderSuite` (12). 8 Synthesis, 12 Prompts/schemas: `ModelPortSuite` (13) and `WorkflowSuite`. 9 Verification: `VerificationSuite` (26). 10 Audit and 11 Workflow: `WorkflowSuite` (32), 13 Demo: `RuntimeDemoSuite` (2).
README gate bullets: arbitrary DB code impossible (`RetrievalAgentsSuite` allowlist wrapper; `WorkflowSuite` "user text becomes no database code"); ambiguity (`ResolverSuite`, `WorkflowSuite`); numeric/path claims have evidence IDs (`VerificationSuite`, `EvaluationSuite`, `WorkflowSuite`); unsupported removed/weakened/labeled (`VerificationSuite`, `WorkflowSuite` steered-model test); prompt injection (question side `PlannerSuite`/`WorkflowSuite`; retrieved-text side `EvidenceBuilderSuite`/`WorkflowSuite`; model side steered-model test); graph vs semantic evaluated separately (`EvaluationSuite`: graph-only world answers 9 of 9 benchmark questions; semantic retrieval 6 of 6 at top-1; adding semantic context changes no deterministic claim).

## Files
Created (`modules/rag/src/main/scala/scholarchain/rag/`): `RuntimeSupport`, `Facts`, `Model`, `Claims`, `Ports`, `Planner`, `Resolver`, `Retrieval`, `EvidenceBuilder`, `Synthesis`, `StubModel`, `Verification`, `Workflow`, `DemoWorld`, `RuntimeDemoMain`. Tests: `RuntimeTestSupport`, `PlannerSuite`, `ResolverSuite`, `RetrievalAgentsSuite`, `EvidenceBuilderSuite`, `VerificationSuite`, `ModelPortSuite`, `WorkflowSuite`, `EvaluationSuite`, `RuntimeDemoSuite`.

## Commands and results
`sbt Test/compile` x3 exit 0. `sbt quick` run 1 exit 1: real bug, tied ranks made the verifier call the second holder Conflicting and the renderer name the wrong person; fixed in `Verification.scala` and `Claims.scala` with a tie regression test. Run 2 `[success] Total time: 82 s`. Run 3 (after `RuntimeDemoSuite`) `[success] Total time: 76 s`; every `Passed:` line Failed 0, Errors 0 (181, 224, 389, 62, 664; rag line for run 3 not located, 226 inferred). New rag tests: Planner 17, Resolver 10, RetrievalAgents 23, Verification 26, ModelPort 13, EvidenceBuilder 12, Evaluation 6, Workflow 32, RuntimeDemo 2. `RuntimeDemoMain` run only through its test.

## Specification details
- Prompts `planner-classify` 1.0.0 and `synthesis` 1.0.0; schemas `scholarchain.rag.plan-output/1`, `synthesis-output/1`, `verifier-report/1`. Source constants, sha256 computed at runtime, written into every audit payload (`prompts[].hash`, `schemaHashes`) and pinned by test equality. Literal hash values not captured.
- Allowed plan operations: exactly the 11 core `QueryOperation` cases; model returns one or null; else malformed, retried at most 2 times, then refused.
- Identity: resolves only when exactly one canonical record matches with confidence >= 0.8 (exact 0.95, word-order 0.85); otherwise returns all candidates, chooses none. Lone mention gives MentionOnly; shared ORCID gives LowConfidence (0.65); both abstain. Only `Session.disambiguation` naming a listed candidate resolves an ambiguous name. Affiliation not invented.
- Templates: `coauthors-of` and `edge-evidence` through an allowlist wrapper; shortest paths from the precomputed path-evidence port (P5).
- Verification: facts are JSON in `text` of non-`TextChunk` evidence; `TextChunk` text is ignored (forged-metric snippet test). Every number in a claim must appear in a cited fact; spelled numbers 2 to 20 also checked. Definitions must equal the fixed glossary.
- Verdict mapping (P2): Verified to Verified; PartiallySupported to Uncertain; Unsupported and Conflicting to Unsupported (Conflicting claims removed, conflict named in the report). Whole answer: all Verified is Verified; any Conflicting is Conflicting; all Unsupported is Unsupported; else PartiallySupported; abstained answer has no verdict.
- Abstention: planner refusal, unresolved identity, wrong researcher count, one window where two are needed, no deterministic evidence, evidence cap exceeded, synthesis failure, all claims removed, storage read error; each audited.
- Model port: `ModelPort.complete(ModelRequest): ModelResponse`; request has prompt ID, version, schema ID, JSON input, token budget; reply is raw JSON + token estimates; schema `additionalProperties` false so no reasoning field; retry hints never echo model text; input over `maxInputTokens` fails before any call.

## Known failure modes
Name extraction only finds runs of two or more capitalized words ("Smith, John" and lowercase not found; a researcher ID can be passed). Rules misclassify unusual phrasing; no real-question benchmark. Injection screen is a regex list that flags but does not block; safety rests on the stub never interpreting retrieved text and the verifier never accepting a snippet as fact. Spoken numbers outside 2-20 not checked. Full coauthor page may be truncated ("at least"). Ranking uses a rag-defined `MetricRanking` port and title lookup a rag-defined `WorkCatalog` port, each with a local fake, because storage has no such index. Topic, institution and venue filters cannot be applied (no deterministic linkage) and the answer says so. Question text enters the audit record unredacted.

## Phase 12 deferrals
Bedrock/real model/tokenizer; live Neptune shortest-path, component, community templates; storage ranked-metric index and title lookup; TTL-aware reads; DynamoDB audit adapter, AWS latency/cost; semantic embedder (Option B); N1 to N6; no real-data run needed.

## Assumptions and risks
P1: `prompts/**` unowned, prompts live in rag. Demo world has only 2000-2009 and 2010-2019 windows; an unstored window (e.g. "since 2015", default "all") finds no metrics and abstains with a stated gap. Verifier thresholds and confidence numbers (0.95, 0.85, 0.8, minus 0.3 shared ORCID) are the agent's choices and need review.

## Gate status
`sbt quick` passed on these sources (turn evidence). Pending: test-engineer WO 03, `sbt check`, independent review, human `sbt clean check`.
