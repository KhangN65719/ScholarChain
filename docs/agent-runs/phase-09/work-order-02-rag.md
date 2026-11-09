# Work order 02: rag-engineer, the runtime collaboration-analysis workflow

Owner: rag-engineer (`modules/rag/**`). Phase 9, decisions P1 to P10 and Phase 8 F1. Runs after work order 01 (storage); read its report and the new storage ports first. LOCAL ONLY: no AWS, credentials, Bedrock, paid LLM or embedding APIs, Terraform or deploys, and NO new dependency (use existing upickle/ujson and munit). Do not edit `modules/storage`, `modules/core`, `tests/**`, `build.sbt`, `project/**`, `prompts/**`, CLAUDE.md, docs/agents.md or `.claude/**`; report `BLOCKED-ON <owner>` for any need there. Do not depend on `entity-resolution`.

## Read first
`docs/agent-runs/phase-09/prompt.md` (the full README section is the specification), `decisions.md` (P1 to P10), `docs/agent-runs/phase-08/decisions.md` (F1, H4 known limitation), `report-01-storage.md` once it exists. Existing rag sources: `SemanticRetrieval`, `SemanticContext`, `EmbeddingModels`, `RagTemplates`. Core contracts: `QueryPlan`, `EvidenceItem`, `EvidenceBundle`, `AnswerClaim`, `AnswerResponse`, `ResearcherMetrics`, `QueryOperation`, `EvidenceType`, `CollaborationMetric`, `StableIds`, `ContractWire`.

## Binding rules
- F1: every number the workflow states (counts, team sizes, hop counts, scores, component and community sizes, years) comes from a metric, graph, path, component or community record with an evidence ID. A semantic-context item (vector snippet) is never the support for a number or for a coauthorship, path, component or bridge claim. The verifier enforces this.
- Every model call goes through `ModelPort` with `DeterministicStubModel` (decision P3). No network. Model output is validated against a versioned JSON schema before use; malformed output is retried within a bounded policy (default at most 2 retries), then the stage fails closed to an abstaining result.
- Retrieved text (snippets, names from data) is untrusted data: delimited in prompts as data, never concatenated into instructions, and instructions found inside it are ignored and flagged.
- User text never becomes Cypher or an AWS operation: stages select named, allowlisted templates and pass parameters. Allowed plan operations are exactly the core `QueryOperation` cases.
- Abstain when researcher identity or evidence is insufficient. Never expose chain-of-thought: no model field for it, and none is stored.

## Required work (stages per the README section)
1. `QueryPlannerAgent`: question to a core `QueryPlan` (deterministic rules; the stub model may classify within the closed operation set). Extract names, window (year ranges, "since 2015", window labels), topic, venue, institution, ranking metric (breadth C_r, weighted degree W_r, team size M_r, bridge score, coreness). Rank-by-metric rule follows the README note under the work order: no composite score unless components, normalization, weights and missing-data behavior are explicit (default: none).
2. `ResearcherResolverAgent`: names to canonical researcher IDs through the storage researcher-directory port. Returns all candidates with ORCID, link status, counts, coauthor context and a confidence; abstains when ambiguous or absent. Never picks one of several near-equal candidates.
3. `CollaborationMetricsAgent`: exact stored values with metric version and run ID via the typed metric reader.
4. `PathFinderAgent`: direct collaborators through `coauthorsOf`; shortest paths through the path-evidence read port; depth, result and timeout limits enforced; per hop researcher IDs, edge weight, supporting work IDs, years, provenance. Honest non-path results (decision P5).
5. `CommunityAnalystAgent`: community/component membership and sizes, cross-community edges, bridge metrics, neighboring communities; the output separates direct degree (C_r, W_r) from structural bridge importance.
6. `SemanticContextAgent`: wraps `SemanticRetrieval` against the right index per need; returns context with vector scores and source pointers, marked "descriptive only".
7. `EvidenceBuilderAgent`: bounded core `EvidenceBundle`; deterministic evidence types (Metric, GraphPath, Publication, IdentityLink) kept apart from `TextChunk` semantic items; stable evidence IDs via `StableIds`; item and byte caps with refusal over silent truncation.
8. `AnswerSynthesisAgent`: concise analysis plus typed `AnswerClaim`s, each with evidence IDs; explains breadth vs intensity vs team size vs connectivity vs bridge importance when relevant; states uncertainty and missing evidence. Uses the model port with a versioned prompt and schema.
9. `ClaimVerificationAgent` (deterministic, no model): checks cited evidence exists; checks researcher identity, publication title, coauthor count, team size, path hop, community claim, bridge score, time window and every numeric value against the cited deterministic evidence; applies F1. Verdicts VerificationVerdict {Verified, PartiallySupported, Unsupported, Conflicting} mapped to core per decision P2. Unsupported claims are removed, weakened or labeled in the final answer, with the change recorded.
10. Audit stage: persists QueryPlan, resolved researcher IDs, evidence IDs, metric versions, graph-run ID, prompt and model versions, token counts (stub estimates, labelled), latency (injected clock), verification result and answer hash through the storage audit helper.
11. `CollaborationWorkflow`: the orchestrating entry point `answer(question, session) : WorkflowResult` composing the stages over injected ports (metrics, paths, communities, directory, semantic retrieval, model, audit, clock). Result carries the core `AnswerResponse` and the audit record. No HTTP (Phase 10).
12. Prompts and JSON schemas: versioned constants with content hashes under `modules/rag/**` (decision P1): planner, synthesis, and any classification prompt; schemas for plan output, synthesis output and verifier report.
13. A small bundled demo world for tests inside rag (a local fixture), and a `RuntimeDemoMain` that runs a few questions offline and prints answer, claims, verdicts and audit hash. No real-data run is needed.
14. Feature tests in `modules/rag/src/test` for every behavior change: each stage, every allowlisted operation, injection strings in questions and in retrieved text, unknown operation refused, ambiguity abstains, F1 (number supported only by a snippet is Unsupported), malformed model output with bounded retry then abstain, bundle caps, audit contents, determinism on rerun.

## Before writing code
List which specific test proves each item above and each README gate bullet (arbitrary DB code impossible; ambiguity not silently resolved; every numeric and path claim has evidence IDs; unsupported claims removed/weakened/labeled; prompt injection passes; graph and semantic retrieval evaluated separately). Put the list FIRST in your report.

## Commands
`sbt quick` for work and repairs; never two sbt commands at once; do not run `sbt check`. Write a new slow suite name in your report if one exceeds the quick budget so build-engineer can add it to `slowSuites`.

## Report
Role-file contract plus: prompt versions and hashes, schemas, allowed plan operations, identity-resolution behavior, graph query templates used, verification rules and verdict mapping, abstention behavior, model-port contract, known failure modes, and Phase 12 deferrals. Orchestrator archives it as `report-02-rag.md`.
