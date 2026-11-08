# Work order 02: spark-engineer (adapt Spark to the core mention-coauthor ID)

Owner: spark-engineer. Output: modules/spark/** only. Prerequisite: work order 01 (report-01-domain.md) is complete.

## Task
1. Replace Spark's local `coauthor:v1:` edge ID for mention endpoints (`EdgeIds.edgeId` in Coauthorship.scala) with core `StableIds.coauthorEdgeFor(left, right, window, policyVersion)`. Resolved pairs stay unchanged. Format for mention edges: `mention-coauthor:v1:<hex>`.
2. Make Spark metrics/graph rows consistent with the widened core contracts (`CoauthorEdge`, `GraphEdge`, `ResearcherMetrics` accept `mention:` endpoints). Where Spark can now construct core records, do; do not invent core fields. Spark columns without a core form (`nodeKind`, `unresolvedCoauthorOccurrences`, `distinctUnresolvedCoauthorCount`) stay Spark-local and are reported.
3. Update the tests that pinned the old local ID (e.g. CoauthorshipSuite "an edge ID is the core ID, local only for a mention endpoint") to pin the new core ID. Do not weaken or delete tests: replace the assertion with an equal-or-stronger one; add a test that every emitted edge ID round-trips through the core `CoauthorEdge` validation.
4. Update the module DESIGN.md and the limitation text that mentions the Spark-local ID. Note that the run ID / existing Phase 6 outputs on disk carry the old IDs, so Phase 7 loaders must read only runs produced after this change; do NOT rerun the real-data pipeline unless needed (it is long); if you judge a rerun needed, report it as a recommendation instead of running it.
5. List which test proves each gate item before implementing. Run `sbt quick` only (never two sbt at once). Report exact counts.

Do not edit core, tests/**, CLAUDE.md, docs/agents.md or .claude/**. No commit. Read-only git only. No AWS.
