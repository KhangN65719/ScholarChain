# Report 01: domain-engineer (summary as received, orchestrator-condensed)

Status: COMPLETE. `sbt quick` exit [success] 66 s, suite groups all Failed 0 / Errors 0: 181, 49 (core: 41 + 8 new), 389, 42, 267. `sbt check` not run (not requested). Log not written to the data dir (guard refuses redirection); output was in the tool result.

Files: modified `modules/core/.../StableIds.scala`, `Contracts.scala`; created `modules/core/src/test/scala/scholarchain/core/MentionCoauthorSuite.scala` (8 tests).

API for Spark:
- Edge ID format `mention-coauthor:v1:<64 hex>`; SHA-256 over length-framed fields `mention-coauthor, v1, a, b, startYear, endYear, policyVersion`, (a, b) sorted.
- `StableIds.mentionCoauthorEdge(left, right, window, policyVersion)`; `StableIds.coauthorEdgeFor(...)` dispatcher (researcher pair unchanged `coauthor:v1:`; any mention endpoint gives the mention form; otherwise IllegalArgumentException); `StableIds.isCoauthorNode(id)`.
- `CoauthorEdge.researcherA/B`, `GraphEdge` CoauthoredWith endpoints and `ResearcherMetrics.researcherId` now accept `researcher:` or `mention:` IDs; ascending order enforced; `edgeId` must equal `coauthorEdgeFor(...)`. Mention IDs sort before `researcher:`.
- No schemaVersion bump (stays 1; widening only). Risk: pre-change readers reject such v1 edges. Whether to bump is a human decision (see decisions.md D2).

Test map: determinism; order independence; binding to window/policy/endpoints; resolved vs unresolved separation; cross-kind collision; wire round-trip with provenance; malformed endpoint rejection (all in MentionCoauthorSuite).

Open: `ResearcherMetrics` has no core form for Spark's `nodeKind`, `unresolvedCoauthorOccurrences`, `distinctUnresolvedCoauthorCount` (see decisions.md D3).
