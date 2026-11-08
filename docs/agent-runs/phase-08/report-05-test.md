# Report 05: test-engineer (archived by the orchestrator; abridged only in the per-suite lists)

**Status: COMPLETE** (quick set only). Owner: test-engineer. Work order: work-order-05-test. `sbt check` not run, as ordered. 131 new tests pass on the final run; no production defect found; three behaviours flagged.

## Test-to-item list (fixtures: `Phase8Fixtures.scala`, object `P8`)
- 1 HashEmbedder, `Phase8HashEmbedderSuite`: independent reference (SHA-256, BigInteger mod 384, sign bit, 1+ln, L2) compared on 18 named and 200 random texts (Unicode, mixed case, punctuation runs, repeats, 20,000 words, a 100,000-char word); "abc" known answer; featureless refusal; bit-identical across threads; Turkish vs English default locale.
- 2 overwrite and skip, `Phase8IngestSuite`: same key different text; same text and model; changed model; changed dimension; duplicates (first kept, source order, identical collapse); row-order independence.
- 3 textRef, `Phase8IngestSuite`, `Phase8EndToEndSuite`: round trip for all 11 vectors; 9 tamper cases (edit, rehash, delete, swap, misdirect, outside prefix, wrong hash, garbage, wrong schema); retrieval against a tampered store and a foreign model.
- 4 compact metadata, `Phase8IngestSuite`, `Phase8RagSuite`, `Phase8EndToEndSuite`: required and allowed keys only; no metric or text key; size under 2 KB; no `topic`/`institution`; honest `evidenceWorkCount` (200 with lists capped at 5); bad rows rejected and run continues; exact context key set; metric-name scan of the retrieval and context surface.
- 5 indexes, `Phase8IngestSuite`: five exist, dimension 384, cosine; exist for an empty export; topic-institution empty; topic and institution rows unmapped and persisted.
- 6 resume and failure, `Phase8IngestSuite`: kill mid-run then resume; failing batch (FAILED, redone at attempt 2); transient retries; exhaustion after exactly `maxAttempts`; batch-size bounds; deleted document restored on rerun; forged document caught on rerun; 5,200 rows in 11 batches of at most 500.
- 7 export integrity, `Phase8ExportSuite`: wrong sha, byte size, row counts; missing `_SUCCESS`; edits before and after open; truncated line; non-object lines; `../` and absolute paths; unknown table; missing part file; 10 manifest mutations; bounds; NUL in text; `IngestMain` refusals.
- 8 rag, `Phase8RagSuite`: templates under five locales and shuffled map order; no invented number or ID; fixed wording no digit; chunking over 300 random texts; every context cap and refusal; `IngestMain` report bytes identical on rerun and in a second output dir.
- 9 end to end, `Phase8EndToEndSuite`: multi-part export on disk through `EmbeddingIngest` with `RagTemplates`, `LocalFsObjectStore` and the fake bucket; expected key sets for researcher, year window, source, community, documentType filters and AND combinations; `fetchText` equals template text; `IngestMain` `objects/` match the same composition byte for byte; export without facet tables.
- 10 retrieval smoke: known top neighbours in distinct vocabularies; query with words in no document returns an ordered list, distances above 0.3, no exception; punctuation-only query refused.
- 11 gate by structure: reflection fixes public methods and fields of retrieval and context types, the `VectorBucketPort` methods and numeric-only context fields; no public name mentions graph metrics.

## Commands and results
- `sbt Test/compile` exit 0. `sbt quick` three times: first no failures; second one deliberate probe failure (since removed); third `[success] Total time: 77 s`, scalafmt passed, marker written.
- Final log: root `Passed: Total 643, Failed 0, Errors 0`; sparkAnalytics `Total 56, Failed 0, Errors 0`. 643 = previous 512 + 131 new (the 634 line earlier was rag, 68, per agent).
- Own suites from JUnit XML: HashEmbedder 27, Ingest 37, Export 20, Rag 27, EndToEnd 20; 0 failures, 0 errors.
- Not read: core, hadoop, entity-resolution, storage totals (log too large).

## Findings
1. **Same label, changed text, silent** (owner: storage-engineer). Rerunning with the same `executionLabel` over a changed row neither rewrites the vector (safe) nor reports the change; the batch is skipped as "resumed" without comparing content. Test "the same execution label over a changed row is not silent" is marked `.fail` (passes while the gap exists, fails loudly once closed); a companion test confirms nothing is overwritten.
2. **Whitespace run longer than `maxChars`** (owner: rag-engineer, low). `Chunker` refuses with `BoundExceeded` and the message "A word … is longer than" (e.g. `"left" + 3000 spaces + "right"`): nothing lost, wrong message, text not chunked. Phase 6 summaries unaffected.
3. **Symlinked part file accepted** (owner: storage-engineer, low). `JsonlExportSource.open` accepts a part file symlinked to an outside file if the sha matches (checked once by probe, no test left).

## Files
Created under `tests/`: `adversarial/Phase8Fixtures.scala`, `Phase8HashEmbedderSuite.scala`, `Phase8IngestSuite.scala`, `Phase8ExportSuite.scala`, `Phase8RagSuite.scala`, `end-to-end/Phase8EndToEndSuite.scala`. No production, owner-test, build or dependency change; no existing test weakened.

## Assumptions and risks
- Export manifest and row shape follow report 01 and the `JsonlExportSource` reader; part-file path form confirmed against `JsonlExport.scala`; real `nodes-work` and `researcher-metrics` schemas unverified.
- Reference uses `Math.log` vs production `StrictMath.log`, tolerance 2e-6.
- `Locale.setDefault` changed briefly in two tests and restored in `finally`; root suites run in parallel in one JVM (small interference risk).
- Latency and throughput are local only. No new suite starts Spark; nothing for `slowSuites`.

## Next gate
Independent review, then the human's `sbt clean check` and the real-data runs.
