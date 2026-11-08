# Phase 6 repair history

## R1. F1, F2 and part-file naming (spark-engineer, work order 05)
Source of findings: `test-engineer` (agent-reported; its full report body could not be archived, see validation.md V4). Failing before repair: 6 root tests (4 in `SparkDegenerateInputsSuite` = F1, 2 in `SparkSummariesSuite` = F2).

All items below are agent-reported by `spark-engineer`; the orchestrator did not observe the sbt runs.

- **F1** `Canonical.scala`: `Canonical.distinctByKey` reduces researcher links (mentionId), work links (workId), works (workId), canonical researchers (canonicalResearcherId) and mentions (mentionId) to one row per key (smallest row by struct order on conflict). Cause: joins on non-unique keys multiplied rows. `input.*` counts stay raw. New module test in `CanonicalBuilderSuite`.
- **F2** `Summaries.scala`: profile `evidenceMetrics` gains `unresolvedCoauthorOccurrences`; unreachable-path text drops the hop number ("within the configured hop limit"; the number stays in `path-evidence.hopLimit` and the run manifest). The owner's own module test text was updated, not relaxed. No `tests/**` file touched.
- **Finding 3** `Export.scala`: part files renamed `part-NNNNN.parquet` (and `.crc` removed). Trigger: `SparkOutputsSuite` "no manifest holds a bucket, an ARN, an account, a region or a host" failed once on a 12-digit token (`199120200193`). Owner hypothesis (unconfirmed): the digit-only last group of Spark's random part-file UUID. Probabilistic; run 2 passed, which does not prove the cause.
- `sbt check` run 1 exit 1 (root 372/373, the Finding 3 test); run 2 exit 0, 776 s: sparkAnalytics 45/0/0, root 373/0/0. core, entityResolution and hadoop counts for run 2 not read.
- Owner-stated risks: `pairs.*` disposition counts still count a pair row given twice as two rows; conflicting duplicates are resolved silently by smallest row; Finding 3 cause unconfirmed.
- `canonical.authoredNotCounted` (2609) = rows in `canon.authored` with `countedInMetrics = false`: valid AUTHORED edges of resolved mentions in non-representative records of canonical works. Kept as edges, excluded from publication counts, pair weights and team sizes. `summary.txt` column headers for `metrics`, `components`, `communities`, `coreness` are in `spark-owner-report.md` addendum below.

### summary.txt column headers (spark-engineer)
- metrics: window, researchers, maxP, maxC, maxT, meanA, maxM, maxW, medianC, p90C
- components: window, nodeCount, edgeCount, componentCount, largestComponentSize, largestComponentFraction, singletonCount
- communities: window, communities, largest, meanSize, singletons
- coreness: window, maxCoreness, zeroCoreness

## R2. Review round 1 repair (spark-engineer, work order 06; all agent-reported, not observed)
- B1: per-dataset `input.duplicateKeyRows.<ds>` and `input.conflictingDuplicateKeys.<ds>` counts (datasets: researcherLinks, workLinks, canonicalResearchers, works, mentions, authorPapers, pairCandidates); conflicts raise the warning invariant `conflictingDuplicateInputKeys` (not integrity violations, because independent tests assert the integrity table row for row).
- B2: sources digest added to the run-ID seed (`RunContext.seed`); `transformVersion` not bumped because `SparkOutputsSuite.scala:215` asserts the literal `spark-analytics/1.0.0`; rule in `DESIGN.md`. Risk: any source edit changes the run ID; digest is `unavailable` outside a git checkout.
- N2: unresolved-mention profile text, "nodes" wording, `nodeKind` column in `semantic-summaries` and s3-vectors metadata, shared-ORCID uncertainty sentence. N3: new `path-evidence.unreachableReason` (`different-component` | `beyond-hop-limit`). N4: local edge ID only for `mention:` endpoints. N5: `<name>-columns [...]` header lines in `summary.txt`. N6: `pairs.<k>.distinct` added. N11: work summary cites `year`; `DESIGN.md` aligned. B3 code half: fragmentation limitation in bridge and path limitation texts, manifest and `DESIGN.md` (figures 148 / 17,490 / 3,035 copied from the review, not recomputed).
- `sbt check` run 1: sparkAnalytics 52 total, 2 failed (the owner's own new assertions assumed mapped rows equal distinct pairs; hand derivation 10 rows, 9 distinct; the assertions were corrected, code unchanged), root 373/0/0, hadoop 207/0/0. Run 2: exit 0, sparkAnalytics 52/0/0; core, entityResolution and root lines of run 2 not read. No `tests/**` file touched.
- New columns affect Phase 7 loaders: `semantic-summaries.nodeKind`, `path-evidence.unreachableReason`.
- Pending after R2: human `sbt clean check`; real re-run (new run ID expected); B3 evidence from the new run; limited re-review; B4, B5 human decisions.

## R3. Path results in text (spark-engineer, work order 07; agent-reported, not observed)
- Cause: B3 path results existed only as Parquet; the human's readback script (`readback-paths.py`) found neither pyarrow nor duckdb (exit 3).
- Added to `summary.txt`: `path-status-columns [window, status, unreachableReason, pairs]` and `path-status [...]` rows (distinct pairs per window, status and reason; `unreachableReason` null unless `unreachable-within-bound`, then `different-component` or `beyond-hop-limit`); `path-length-estimates-columns [...]` and `path-length-estimates [...]` per window (all columns except `provenance`, plus `windowStart`, `windowEnd`). Manifest gains top-level `pathResults` (statusCounts, lengthEstimates, note). Aggregations go through the guarded helper. Files: `GraphAlgorithms.scala` (`PathReport`), `CollaborationPipeline.scala`, `PathsSuite`, `CollaborationPipelineSuite`, `DESIGN.md`. No `tests/**` touched.
- Tests: `PathsSuite` status-count test (hand-derived rows) and estimate-line test (5-node path: mean 2.0, effective diameter 3), `CollaborationPipelineSuite` summary/manifest test. The reason split is proven only by the `PathsSuite` test (the synthetic pipeline fixture has no such pair).
- `sbt check` exit 0, 913 s: sparkAnalytics 55/0/0, hadoop 207/0/0, root 373/0/0; core and entityResolution not read. The agent started a second `sbt check` by mistake (`bqgqv9lad`); the orchestrator stopped it before the human gate.
- Pending: human gate, real re-run (new run ID), V10 path readback from `summary.txt`, limited re-review.

## R4. Round-2 blocking repair (spark-engineer, work order 08; agent-reported, not observed)
- R2-B1(b): new `error` invariant `conflictingIdentityInputKeys` over researcherLinks, workLinks, canonicalResearchers (run exits 1 with `FAILED invariants conflictingIdentityInputKeys`, tables and manifest written, no `_SUCCESS`); `conflictingDuplicateInputKeys` stays `warn` over works, mentions, authorPapers, pairCandidates. R2-B1(a): new `ConflictingKeysSuite` (6 tests: Hadoop conflict counted/warned/non-fatal; identity conflicts fail incl. work links and canonical researchers; `inRunIdSeed` entry; `_work` removed after success and kept after failure via `finishWork`).
- `inRunIdSeed` now true only when the sources digest is not `unavailable` (`CodeInfo.manifestEntry`, plus a `runIdTracksCode` text); `_work` deleted after a successful run (the run stops a session it created first); `DESIGN.md` updated (split, rule, failure paragraph, subset run wording).
- `sbt check` once, exit 0, 1100 s: core 41, entityResolution 389, hadoop 207, sparkAnalytics 61 (was 55, +6), root 373, all 0 failed, 0 errors. Module names were assigned by suite order, not printed. No `tests/**` touched; independent root tests unchanged at 373 passing.
- Owner risks: `_work` removal untested end to end (tests use a provided session); the four new pipeline tests add about 330 s; independent tests' use of invariant names was not grep-checked (root passed). Unfixed: `authorPapers` key with `authorPosition`, N1, N9, N10 and the stale comment.
- Pending: human gate, final re-run (new run ID; expect `_work` gone, `inRunIdSeed` true), final-run convergence and shuffle totals, limited re-review.

## Pending
- Human `sbt clean check` (gate) after R1.
- Real subset re-run with `--overwrite` (summaries and part-file names changed).
- Independent review.
- Suggested follow-up for review: `summary.txt` has no header lines in code; the owner supplied headers only in this report.
