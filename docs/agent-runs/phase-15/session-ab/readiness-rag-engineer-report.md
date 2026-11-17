# Readiness B2 and M1: rag-engineer analysis (received 2026-11-17; summary as received)

Read-only; search tools refused, direct reads only; storage tests, `RetrievalModeSuite`, `RetrievalModeRequestSuite` not read.

B2 (graph-only on a 17-table export):
1. Graph-only downloads everything under `export/` (`AwsIngestMain.scala:356-383`), then `JsonlExportSource.open(exportDir)` with no `only` (`:687`).
2. `open` verifies all manifest tables (`JsonlExportSource.scala:216-307`): row bound, file count, byte total, size, SHA-256, row count.
3. Default bounds 1,000,000 rows per table, 10,000 files, 1 GiB total (`Support.scala:203-204`); exceeding gives `BoundExceeded`.
4. Load reads 9 tables only: `GraphStep.RequiredTables` (`GraphStep.scala:26-36`); `SparkGraphProjector` by name (`Neptune.scala:178-305`).
5. Extra tables are verified, never loaded, never fail as unknown; a missing required table is `Skipped` = failure with a Neptune host (`GraphStep.scala:43-62`, `AwsIngestMain.scala:726-729`).
6. Tests use stubs (`AwsLoaderFixtures.scala:34-43, :129`; `AwsIngestGraphOnlySuite.scala:149`; `AwsIngestMainSuite.scala:253`); none with a 17-table manifest, extra table or oversized table.
7. Missing test: real 17-table export through `JsonlExportSource` and `SparkGraphProjector` (9 load, 6 ignored, `BoundExceeded` -> `NEPTUNE_LOAD=FAILED`). Optional code change: `open(..., only = Some(RequiredTables.toSet))`.

M1 (vectors from an earlier run, graph from A):
- No run filter or label: `VectorFilter` has no run field (`Vectors.scala:291-300`); `SemanticRetrieval.search` filters by index suffix and `embeddingModel` (`SemanticRetrieval.scala:40-59`); vector metadata `runId` (`Vectors.scala:103`) dropped by `ContextItem` (`SemanticContext.scala:27-43`); `graphRunId` pins metric and read-model reads only (`Workflow.scala:131-135`); `textRef` embeds the old run id (`SemanticDocuments.scala:49-62`).
- E: vector mode returns old-run snippets; fused returns A's graph and metrics plus old snippets, unflagged.
- Naming gap: runtime queries fixed `-v1` names (`Vectors.scala:35-39`, `AwsBackend.scala:166`); mismatch unverified; a failed vector read degrades fused silently to graph-only plus a gap note (`Retrieval.scala:258-263`).
- Re-ingest not clean: `ChangedVectorContent` on changed text (`EmbeddingIngest.scala:287-296`); unchanged vectors keep old `runId` (`:262-277`); failed ingest skips the graph load in a full run (`AwsIngestMain.scala:667-671`); new index generation refused (`EmbeddingModels.scala:77-83`).
- Recommendation: E in graph mode (no vector call, `RetrievalMode.scala:7-8, 24-25`); vector and fused only as a labeled mixed-run smoke recording the earlier `ingest-report.json` `sourceRunId` beside A's, checking `textRef` run segments. Test: graph mode makes no vector call (spy vector bucket) if existing suites do not cover it.

Orchestrator note on B2 bounds, from 2.10 (session-record): largest table 101,478 rows (< 1,000,000), 34 objects (< 10,000), 373,888,058 bytes total (< 1 GiB = 1,073,741,824). A's export is inside the default bounds; the missing 17-table test stays a follow-up (needs sbt; not tonight).
