# Report 02: spark-engineer (condensed as received)

Status: COMPLETE (quick set only). `sbt quick` [success] 64 s; groups 181, 49 (core), 389, Spark 44; all Failed 0, Errors 0.

Changed: `Coauthorship.scala` (`EdgeIds.edgeId` always calls `StableIds.coauthorEdgeFor`; local SHA-256 ID removed), `CoauthorshipSuite.scala` (old test replaced by stronger one; 2 new tests: every edge row builds a core CoauthorEdge+GraphEdge, 14 distinct IDs incl. 4 mention; every metrics row builds a core ResearcherMetrics), `DESIGN.md`.

Spark-local columns with no core form: nodeKind, unresolvedCoauthorOccurrences, distinctUnresolvedCoauthorCount, endpointAUnresolved, endpointBUnresolved.

Not verified this turn: CollaborationPipelineSuite and ConflictingKeysSuite are slow suites (skipped by quick); end-to-end run and Parquet round-trip of the new IDs are covered only by the human's `sbt clean check`. Phase 6 runs on disk carry old `coauthor:v1:` IDs on mention edges (also in edge-support and path-evidence) and fail core validation. Rerun recommended before any load (not run). `transformVersion` unchanged `spark-analytics/1.0.0` (bump is a human decision, see decisions.md D4/D5).
