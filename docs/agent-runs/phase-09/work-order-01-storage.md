# Work order 01: storage-engineer, read-side ports for the Phase 9 runtime agents

Owner: storage-engineer (`modules/storage/**`). Phase 9, decisions P5, P6, P7, P10. LOCAL ONLY: no AWS, credentials, Bedrock, paid APIs, Terraform or deploys, and NO new dependency. Do not edit `modules/rag`, `tests/**`, `build.sbt`, `project/**`, CLAUDE.md, docs/agents.md or `.claude/**`; report `BLOCKED-ON <owner>` for any need there. storage must not depend on rag.

## Read first
`docs/agent-runs/phase-09/prompt.md`, `decisions.md`, `docs/agent-runs/phase-07/` and `phase-08/` reports for what exists. Sources: `Neptune.scala` (`NeptuneQueryPort`, `CypherCatalog`, `InMemoryNeptune`), `PathEvidence.scala`, `DynamoDb.scala` (`ResearcherMetricCache`, `QuerySession`, `EvidenceBundleTable`, `RunLedger`, `DynamoRunLoader`), `Support.scala` (`TableSource`, `Rows`), `Vectors.scala`.

## Why
The runtime agents (rag-engineer, work order 02) must read exact metrics, paths, components, communities and the researcher directory only through storage ports with local fakes. Today there is a metric-cache writer with no typed reader, a path-evidence reader that is a loader function rather than a port, and no component, community or researcher-directory read port.

## Required work
1. `ResearcherMetricCache` typed read: look up `ResearcherMetrics` by researcher ID and window (and list windows for a researcher), returning the core `ResearcherMetrics` with its provenance (run ID, transform version). Absent is `None`, never a default. Bounded result counts.
2. Path-evidence read port (trait + in-memory fake built from `PathEvidenceRow`s): shortest path between two researcher IDs in a window, returning ordered hops (from, to, edgeId, jointPublicationCount, supportingWorkIds, years where the table has them, provenance) or a typed non-path result carrying `status` and `unreachableReason` (`trivial`, `endpoint-not-in-window`, `unreachable-within-bound`, `different-component`, `beyond-hop-limit`). Parameters: maximum depth, maximum results, and a timeout the fake honors by contract (a deterministic test hook, no real sleeping). Never answer "no path" for `unreachable-within-bound`.
3. Component and community read ports with fakes, from the Spark `components` and `communities` tables: component size and membership page, community size, members page, cross-community edge count, neighboring communities. Confirm the real column names by reading `modules/spark` output code (`Export.scala`, `GraphAlgorithms.scala`, `Summaries.scala`); do not guess them. Bounded pages.
4. Researcher-directory read port with fake over `nodes-researcher` (`nodeId, displayName, nodeKind, orcid, linkStatus, idRule, memberCount, mentionCount`): candidates by normalized name, returning every candidate with ORCID, link status and counts. Mention nodes and unresolved nodes are marked, not hidden. If affiliation or coauthor context is not in that table, expose coauthor context via the existing `coauthorsOf` template result and say affiliation is unavailable; do not invent it.
5. Audit write helper: a typed function that writes a `QuerySession` turn and stores an `EvidenceBundle` through the existing tables, if the existing `appendTurn` and `store` are not sufficient. Reuse them where they are.
6. Keep `CypherCatalog` closed: no new Cypher template in this phase (decision P5). If you find a template change is needed, report it; do not add it.
7. Feature tests for every behavior change in `modules/storage/src/test`: present, absent, bounded paging, each non-path status, malformed rows refused, ambiguity returned as multiple candidates, rerun determinism.

## Before writing code
List which specific test proves each item above. Put the list FIRST in your report.

## Commands
`sbt quick` for work and repairs; never two sbt commands at once; do not run `sbt check`.

## Report
Role-file contract plus: each new port's signature, fake behavior, column mapping confirmed from Spark source, bounds, and anything deferred to Phase 12. Orchestrator archives it as `report-01-storage.md`.
