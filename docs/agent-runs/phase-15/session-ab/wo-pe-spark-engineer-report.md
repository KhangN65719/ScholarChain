# WO-PE report: why `path-evidence` has 42 rows (spark-engineer, received 2026-11-17; summary as received)

Read-only; no code, sbt, AWS, git or file writes. One item inferred, not read: the arguments A's run used.

Cause: with no `--path-pairs` file, `CollaborationPipeline.pathPairs` takes the top `autoPairTop` researchers per window by weighted degree and pairs them (`CollaborationPipeline.scala:885-915`; join `x.nodeId < y.nodeId`, `rnk <= autoPairTop`, `:911-913`). Default `--auto-pair-top 4` (`Config.scala:188`, range 0-50) gives C(4,2) = 6 pairs per window; 7 windows (`Config.scala:18-19`) give 42 pairs. `PathSearch.evidence` (`GraphAlgorithms.scala:545-633`) emits one row per hop of a found path, or one row for a trivial, out-of-window or unreachable pair (`:541-544`); hop limit default 6 (`Config.scala:187`, passed at `CollaborationPipeline.scala:500-514`). No row cap found. 42 rows means each pair is one row: a direct edge or unreachable (not distinguished by the manifest; the run's `path-status` summary lines would tell). `JsonlExport.scala` not read (no export filter inferred).

Expected: yes, default design; a top-4-hub sample per window, not the graph's path set.

Manifest rows: `edges-coauthored` 98,661; `edge-support` 101,478; `researcher-metrics` 35,160; `nodes-researcher` 17,569; `semantic-summaries` 41,885; `path-evidence` 42.

Impact on session 2 (E): any path query outside the 42 hub pairs has no `path-evidence` rows; not usable for arbitrary or varied seed pairs. Graph-mode path queries need the coauthor graph (Neptune openCypher, WO-R item 3). Retrieval comparisons using `path-evidence` should be limited to the 42 pairs, else "no path evidence" is a coverage artifact.

Recommendation (human decision):
1. Re-run Spark with a larger `--auto-pair-top` (up to 50: at most 7 x 1,225 = 8,575 pairs plus hops) or `--path-pairs <file>` of E's pairs (capped by `--driver-row-cap` 5000, `Config.scala:192`; error `CollaborationPipeline.scala:175-176`). New EMR run: cost decision.
2. Cheaper: no Spark change; E's path queries against Neptune graph-only data; the 42 rows as a hub-pair fixture.
3. Tests for a re-run (spark-owned): pairs count = windows x C(min(k, nodes), 2); file pairs appear once with status `found`, `endpoint-not-in-window` or `unreachable-within-bound`. Existence of such tests not checked.

Pending: A's run arguments; single-hop vs unreachable split; `JsonlExport.scala` filters.
