# Phase 9 decisions (2026-11-08)

Standing rule from the human: ask (AskUserQuestion) only for decisions that change scope, cost or dependencies; otherwise take the conservative option and record it here. Any new dependency needs the human's approval first.

## Facts established before dispatch (from a read-only inventory and direct reads)
- `modules/rag` has Phase 8 sources only: Chunking, EmbeddingModels, IngestMain, RagTemplates, SemanticContext, SemanticRetrieval. No runtime agents, no model port, no prompts.
- `rag` depends on `core` and `storage` (build.sbt). `rag` does not depend on `entity-resolution`, and the module rules say job modules are not in the runtime graph. A resolver therefore reads canonical researchers through a rag-defined port backed by storage (`nodes-researcher` via `TableSource`, `EntityLinkCache`).
- Core already has `QueryPlan`, `EvidenceItem`, `EvidenceBundle`, `AnswerClaim`, `AnswerResponse`, `ResearcherMetrics`, and enums `QueryOperation`, `EvidenceType`, `VerificationStatus`, `CollaborationMetric`, `MatchOutcome`.
- Storage read side: `NeptuneQueryPort` with `CypherCatalog` holding only `coauthorsOf` and `edgeEvidence`; `PathEvidence.read` for precomputed shortest paths; `ResearcherMetricCache` has a writer but no typed reader; `QuerySession` and `EvidenceBundleTable` have writers and a bundle loader.
- `prompts/runtime` and `prompts/schemas` exist and are empty.

## P1 prompts and schemas live in `modules/rag` (taken, conservative)
`docs/agents.md` assigns no writer to the top-level `prompts/` tree. Per its rule an unowned path blocks writes until the human ratifies an owner. Taken: versioned prompts and JSON schemas are source constants (and test-visible resources) under `modules/rag/**`, each with a version string and a content hash. `prompts/` stays empty. Follow-up for the human: assign `prompts/**` to rag-engineer via factory-engineer in manual mode if the grader expects files there. Not done here.

## P2 VerificationStatus mismatch (taken, conservative)
The README asks the verifier to return VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED or CONFLICTING. Core `VerificationStatus` has only Verified, Unsupported, Uncertain. Changing core is domain-engineer work and a wire change. Taken: rag defines its own `VerificationVerdict` (the four README values) for the whole-answer and per-claim result, and maps to core: Verified to Verified, Unsupported and Conflicting to Unsupported with the conflict named in `uncertainty`, PartiallySupported to Uncertain. The mapping is documented and tested. If the human wants a core enum change, that is a separate domain-engineer order.

## P3 model port, no real model (taken)
`ModelPort` (structured request: prompt id, prompt version, schema id, input JSON, token budget; structured response: raw JSON, token counts, model version) with `DeterministicStubModel`, which is rule-based, offline and bit-for-bit repeatable. Real Bedrock adapter, model IDs and token accounting against a real tokenizer are DEFERRED to Phase 12. Token counts reported by the stub are estimates and labelled so.

## P4 QueryPlanner is deterministic first (taken)
The planner parses with deterministic rules into a core `QueryPlan` over the allowlisted `QueryOperation` enum. The model port may only classify within that closed set and its output is validated against it; anything outside is refused. No Cypher text is ever built from user text: the PathFinder selects a named template and passes parameters.

## P5 shortest path source (taken, conservative)
Storage's `CypherCatalog` has no shortest-path template, and a live Neptune path query is AWS-only. Taken: shortest-path hops come from the precomputed Spark `path-evidence` table read through a storage-provided read port with a local fake (bounded depth, result count and a timeout parameter honored by the port). Direct collaborators use the existing `coauthorsOf` template. A live bounded Neptune `shortestPath` openCypher template is DEFERRED to Phase 12 (storage-engineer then). Where the table says `unreachable-within-bound`, the agent says so and never claims no path exists.

## P6 storage scope (taken)
Storage-engineer adds only read-side pieces and keeps the module free of rag: a typed `ResearcherMetricCache` reader, a path-evidence read port plus fake, component and community read ports plus fakes (from the Spark `components` and `communities` tables), a researcher-directory read over `nodes-researcher`, and an audit write helper for QuerySession and EvidenceBundle if one is missing. No new dependency.

## P7 audit persistence (taken)
The audit stage writes through storage's `QuerySession` and `EvidenceBundleTable` fakes. A Phase 12 adapter swaps the fake for DynamoDB. Latency is measured by an injected clock so tests are deterministic; token counts are stub estimates.

## P8 benchmark suite (taken)
The benchmark-query suite is the test-engineer's, independent of the implementer: a hand-built fixture world (researchers, windows, communities, paths, vectors) with at least 30 questions covering coauthor breadth, maximum team size, direct collaboration, shortest paths, components, communities, bridge researchers, temporal change, topic, institution and evidence, each with expected deterministic facts. Graph retrieval and semantic retrieval are scored separately. No real-data run is planned unless a defect needs one; none is scheduled.

## P9 dispatch is sequential (taken; same reason as Phase 8 E8)
Never two sbt commands at once, and agents share one working tree. Order: storage-engineer (WO 01), rag-engineer (WO 02), test-engineer (WO 03), build-engineer only if a build edge is reported missing, then one agent `sbt check` before review, then the reviewer. test-engineer writes from the specification and these work orders, not from the implementers' tests.

## G3 process gap: rag repair report not delivered (2026-11-08)
Same mechanism as Phase 8 G1/G2. The rag-engineer's work order 04 left one root test red (`tests/adversarial/Phase9ModelSuite.scala:754` expects `synthesis-failed`; m3 requires `input-over-budget`), so its `sbt quick` exited 1 and its Stop gate stayed open. Two requests (the first message, then an explicit resend) returned only status text. No `report-04-rag-repair1.md` exists as received and none is fabricated. Known from its messages: all 10 rag suites pass, 168 tests, 0 failed; the only failure is that root test. The re-review reads the repaired rag source against work order 04 and review.md directly; prompt/schema literal hashes (m2) are verified from source and tests.

## P11 m1 storage side deferred (taken)
Review m1 also cites the storage fake (`Neptune.scala` 1108, dispatches on query name). Only the rag allowlist wrapper is repaired in round 1 (compares query text and parameter names to the catalog template). The storage fake is left as is: storage-engineer work, defence in depth, no path from user text exists. Carried to Phase 12 with the live Neptune adapter.

## P10 deferred to Phase 12
Bedrock or any real LLM and embedder; real tokenizer counts; live Neptune query templates beyond the catalog (shortest path, component, community); DynamoDB-backed audit; AWS latency and cost; semantic embedder (Option B from Phase 8); Phase 8 follow-ups N1 (concurrent same-label runs) and the unrepaired review notes N2 to N6.
