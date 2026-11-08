# Report 11: rag-engineer, repair round 2 (as received; tables condensed)

**Status: COMPLETE.** Work order 11 plus the human's binding constraints (no weakening; tie size reported; approach stated; known limitation). `sbt quick` passed. Only `modules/rag/**` edited.

## Test-to-item (all in `modules/rag/src/test/scala/scholarchain/rag/IngestSampleSuite.scala`, written before the implementation)
- Full-text query; 12-word query misses the source row as a regression precondition: "regression: the 12-word query misses the source row, full text hits it".
- Tie size 1 in report and console: "templated path summaries pass the run, tie size 1, in the report".
- Exact-duplicate text is a tie of size 2 and passes: "an exact-duplicate text pair is a tie of size 2 and passes".
- Deterministic report with a tie: "the report with a tie is deterministic on a rerun".
- FAIL cases: "a source row missing from the index FAILS the run"; "a source row whose vector is altered FAILS the run"; "a tie that does not include the source row FAILS the run".
- Rejects, failed batches, count mismatches, `fetchText` hash mismatches keep failing: existing `IngestMainSuite` "a rejected summary makes the run FAILED, with the count"; each tied hit is now also `fetchText`-verified.
- Rerun into the same directory after a FAILED sample run: "a FAILED sample run can be rerun into the same directory".
- Printed query line is a labelled display prefix: "the printed query line is a labelled display prefix".
- Tie logic unit: "standing: the top-score group is the prefix at the best distance".
- All existing `IngestMainSuite` tests kept and pass without edits.

## Approach chosen
Full rendered text as the query (`RagTemplates.render(row).text`, the exact string `EmbeddingIngest` embeds), with a pass only when the source row is in the top-score group (exactly the best distance), and the tie size reported. Endpoint fields not used (would need per-type knowledge and a hand-built query, and would no longer test the embedded text). A tie that excludes the source row, or a source row below an equal-score group, fails.

## Changes (all `IngestMain.scala`)
- SAMPLE and WIDE-SAMPLE queries use the full text and request up to 20 hits (`MaxHits = SemanticContext.MaxItems`); console lists the first 3 narrow and 5 wide hits.
- Helpers `standing(hits, summaryId)` (hits, `tieSize`, `sourceRank`, `nearest` = in the top-score group), `queryText`, `displayPrefix`.
- Console: `... nearest is the source row: true, top-score tie size N, source row rank R`; wide: `... top-score tie size N, source row in the top-score group: bool`; query line `query: display prefix (first 12 words of the N-character rendered text actually used): <12 words>`.
- `ingest-report.json` gains `samples[]` (`index`, `summaryType`, `sourceKey`, `typeRows`, `queryChars`, `queryKind: "full-rendered-text"`, `narrow` and `wide` blocks with `hits`, `tieSize`, `sourceRank`, `nearest`); no latency or timestamps, reruns byte-identical.
- Test seam: `execute(args, out, sampleBucket: VectorBucketPort => VectorBucketPort = identity)` wraps only the bucket the sample queries read (default changes nothing for real runs); corruption tests use it.
- Scaladoc of the sample check carries the known-limitation sentence.

## Known limitation
`hash-embed-v1` is lexical. Identical or near-identical rendered text gives identical or near-identical vectors, so ties and prefix collisions are expected for templated summaries and the embedder cannot separate them. A semantic embedder (Option B) is a Phase 12 or later item.

## Before the human reruns
Nothing is required: `checkOutDir` accepts a directory with an `ingest-report.json` of the same export manifest SHA and bound, whatever its `result`; `objects/` documents are immutable and identical so they report as unchanged; ledger and bucket are in memory; the report is overwritten atomically ("a FAILED sample run can be rerun into the same directory"). Optionally delete `/Users/winnguyen/scholarchain-data/phase8-out/ingest/run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0` for a clean state. A different export or bound is refused ("use a new output directory").

## Commands and results
`sbt quick` once: `[success] Total time: 88 s`. Totals read: an earlier group (not named; likely hadoop and entity-resolution) 181 passed; rag 85 passed (includes 10 new `IngestSampleSuite` tests); last module group before root 62 passed; root 664 passed; 0 failed, 0 errors in all. Core and other module groups not read. No root test red; nothing BLOCKED-ON test-engineer. `sbt check` not run; `tests/**` not edited.

## Assumptions and risks
- Distances are `Double`; exact equality identifies a tie (identical text gives identical vectors and computation). A tie group larger than 20 would cut off the source row by key order and fail loudly.
- The real export was not read by the agent; the prefix-collision precondition is proven on a synthetic fixture (three path rows share 14 opening words; the 12-word query does not rank the source row first).
- WIDE-SAMPLE rule is now "source row in the top-score group", equal or stricter than "rank at most 5" whenever the group is 5 or fewer.
- Each sample now `fetchText`-verifies up to 20 hits (stricter, trivial cost).
- `ingest-report.json` gained a field but `ReportVersion` is still `/1`; Phase 12 readers should not assume the old shape.

## Gate status
`sbt quick` passed on these sources and wrote the marker (turn evidence only). Pending: independent re-review of this change only; the human's rerun of the ingest with the same command and output directory; the human's `sbt clean check`.
