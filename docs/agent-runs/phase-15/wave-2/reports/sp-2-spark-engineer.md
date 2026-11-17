# SP-2 report, spark-engineer (received 2026-11-17; summary as received; author: spark-engineer subagent)

Status: COMPLETE. Files: `modules/spark/.../JsonlExport.scala` (`analyticsTables`, 6 entries appended to `ExportTables.all`; storage's `required` lists; idColumns path-evidence `pairKey`, components `window, componentId`, memberships `window, researcherId`, communities `window, communityId`, cross-edges `window, communityA, communityB`), `JsonlExportSuite.scala` (fixture writes the six Parquet tables; 4 new tests; 1 adapted). No `CollaborationPipeline` change.

Tests: six tables with required columns, provenance and sort order; existing 11 tables' file names and sha256 still hash to the committed pins (`822da06d...c6e`, `e94b999f...c71`); a path-evidence status row with null `hopIndex` exports; manifest lists 17 tables and is byte-identical across two runs; missing analytics table or column refused. `JsonlExportSuite` 26/0/0.

`sbt quick`: `[success] Total time: 162 s` (spark 77/0/0). `sbt check` (agent, to run slow suites): exit 1 on one stale assertion, `tests/integration/SparkRoundTripSuite.scala:106` `assertEquals(names.size, 11)` "Obtained 17" (test-engineer owns; fix 11 -> 17 and the "eleven tables" title); the real pipeline fixture exported all 17 tables, so real Parquet schemas meet the required lists; the other 10 tests in that suite pass (JsonlExportSource open, projection, Neptune CSV); `SparkOutputsSuite` 11, `SparkReproducibilitySuite` 5, `SparkPathEvidenceSuite` 12, `CollaborationPipelineSuite` 16 all 0 failures. Full log not scanned.

Open: hop order lexical beyond hop 9 (storage indexes by `hopIndex`); six-table size unknown until A's export.
