# P16-21: GraphChecks edge reconciliation (experiment-engineer)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received. Read-only investigation under the sbt hold (P16-19/P16-20): no sbt, no edits.

---

**P16-21 report: status COMPLETE (read-only, no sbt, no edits)**

The -12,371 is fully explained. It is a definitional difference, not a loader or export defect; GraphChecks' reconciliation compares unlike things.

**1. Composition**

- **123,417 (VERIFIED).** GraphChecks sums three tables as `edgeTables` (`GraphChecks.scala:450-455`): edges-authored 21,268 + edges-published-in 3,488 + edges-coauthored 98,661 = 123,417 (row counts in `graph-check-report.json` rowTotals and `export-manifest.json`).
- **98,661 (VERIFIED).** `edgesTotal` is distinct `edgeId` in edges-coauthored only (`GraphChecks.scala:481`, `edges.size`): 79,178 `mention-coauthor:v1` + 19,483 `coauthor:v1`. A different quantity from the 123,417 sum; not comparable.
- **135,788 (VERIFIED origin).** Not read from any Neptune load, log or manifest. It is a hardcoded CLI default, `--expect-edges` = 135788 (`GraphChecks.scala:653`); the run did not pass the flag. It came from the Phase 14 local rehearsal, `/Users/winnguyen/scholarchain-data/logs/phase14-rehearsal-graph-2.log:20`: `REHEARSAL_OUTPUT vertices=32225 edges=135788`, the in-memory `NeptuneExportReport.edgeCount` (`GraphRehearsal.scala:174-180`, `projection.edges.size`, `Neptune.scala:936-941`). No AWS call, no Neptune load. The vertex default 32225 (`GraphChecks.scala:652`) is the same kind of number. The rehearsal's input rows and bytes match A's export exactly (e.g. edges-coauthored 102,184,837 bytes in both); run ids differ. INFERRED: the rehearsal ran on the same data as A's export, not the same run id.

The projector's default scope is `Extended` (`Neptune.scala:118, 80-81`); edges = `authored ++ published ++ coauthored ++ resolvesEdges ++ derivedEdges` (`Neptune.scala:699-703`):

| Edge type | Count | Source |
|---|---|---|
| AUTHORED | 21,268 | one per edges-authored row (`Neptune.scala:279-302`) |
| PUBLISHED_IN | 3,488 | one per edges-published-in row (`:304-313`) |
| COAUTHORED_WITH | 98,661 | one per edges-coauthored row (`:502-613`) |
| RESOLVES_TO | 8,815 | not in any export table (`:322-392`): one per edges-authored row with `mentionId != researcherNodeId` (accepted ER link); each such mention also becomes an `AuthorMention` vertex |
| DERIVED_FROM | 3,556 | not in any export table (`:401-440`): one per Work to its `DatasetSnapshot` vertex |

Total 135,788. `/Users/winnguyen/scholarchain-data/logs/phase14-graph-csv-check-1.log:10-15` confirms the per-file CSV rows and `EDGE_TOTAL ids=135788 duplicate_ids=0`. No bidirectional, extra mention-coauthor, path-hop or community/component membership edges are added; vertices and properties are not counted as edges.

**2. Arithmetic (VERIFIED)**

135,788 − 123,417 = 12,371 = RESOLVES_TO 8,815 + DERIVED_FROM 3,556, with no residual. Vertices close too: 5,116 Researcher + 21,268 AuthorMention + 469 + 473 + 1,342 + 3,556 + 1 DatasetSnapshot = 32,225; GraphChecks' 23,409 + 8,815 resolved-mention vertices + 1 snapshot vertex explains `vertexDifference` -8,816 (5,116 Researchers + 12,453 unresolved mentions = 17,569 nodes-researcher rows).

**3. Defect or expected?**

Expected definitional difference, exposing two problems in the reconciliation definition:
- It compares export edge-table rows with a projector `Extended` count; two projector-derived types (RESOLVES_TO, DERIVED_FROM) have no export table, so a nonzero difference is guaranteed.
- The doc comment and label say "Neptune load", but the number is a local rehearsal projection, not a loader job count. The real loader's `totalRecords` (`LoaderCounts`, `Neptune.scala:1003`) also includes vertex records, so it would never equal an edge count either.

No loader or export defect: export row counts match the rehearsal inputs and all integrity counters are 0.

**4. Recommended fix (owner of `GraphChecks.scala`: experiment-engineer; not made)**

- Rename and document `expectedEdges` as "projected edges (Extended scope) from the Phase 14 rehearsal", not "Neptune load".
- Make the expectation composable: report `edgeTablesRowSum` (123,417) as export-derived; add `expectedDerivedEdges` = edges-authored rows with `mentionId != researcherNodeId` (8,815, computable in GraphChecks) + `nodes-work` rows (3,556); reconcile 123,417 + 8,815 + 3,556 against 135,788.
- Same for vertices: 23,409 + resolved mentions (8,815) + distinct provenance `snapshotId` count (1) = 32,225.
- Label `edgesTotal` (98,661) as coauthor edges only.
- Or drop the hardcoded defaults and require `--expect-*` explicitly with a source citation.
- For a true Neptune comparison, use per-label counts from the load manifest (`graph/neptune-manifest/.../load-manifest.json`, `rows` per file) or a Neptune count query, not a constant.

**Limits (INFERRED, not read)**

- No `load-manifest.json` read directly; per-label counts come from the Phase 14 CSV check log.
- The `mentionId != researcherNodeId` rows in A's JSONL were not counted (no shell tools); exact closure plus the log's 8,815 supports it.
- grep and glob were blocked in this role; code traced by reading files.

Next gate: orchestrator routes the GraphChecks wording and logic fix to experiment-engineer, after the sbt hold is lifted.
