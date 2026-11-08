# Report 08: rag-engineer, repair round 1 and follow-up 08b (as received, delivered after G2; condensed)

**Status: COMPLETE.** `sbt quick` passed (exit 0). Open: the human's `sbt clean check`. Owner: rag-engineer. Work orders 08 and 08b.

## 08b fix
The test, not the production cut, was wrong. `"alpha beta gamma delta " * 200` has period 23 and 1500 mod 23 = 5, so index 1500 is the space after "alpha"; the first 1500 characters end on the whole word "alpha". The old `endsWith("delta")` came from an earlier cut. `SemanticContextSuite.scala:143` now asserts `charAt(1500) == ' '`, `snippet == long.take(1500)`, `endsWith("alpha")`, `charAt(snippet.length) == ' '` (stricter, exact cut).

## Test-to-item (modules/rag/src/test/scala/scholarchain/rag/)
- L1 `ChunkingSuite`: "a whitespace run longer than a chunk is refused and named as such" (3000-space case: message names the whitespace run and does not say "A word"; word that fills the window keeps the word message; exactly-`maxChars` word then a run; leading run); "a whitespace run that fits is chunked without loss" (`reassemble` exact).
- L5 `SemanticContextSuite`: "long documents at the maximum item count fit: cut and marked" (20 long ASCII, CJK, `é`, emoji items; total bytes at or under 24576; all marked truncated; JSON round trip); "the total snippet bytes cap still refuses items built another way" (eight `é`x1500 pass, nine refused `BoundExceeded` via constructor and `read`); existing item-cap and read-refusal tests unchanged.
- L7 `IngestMainSuite`: "a wide sample per index lists neighbours and finds the source row" (four `WIDE-SAMPLE` lines with `LOCAL latency`, `other candidates: true`, rank not "absent"; 12 `wide hit` lines); first test also asserts exactly four `SAMPLE ` lines.
- L10 `IngestMainSuite`: "the local cost is stated in the output and the report" (exact text, `localCost` field, follows `objects all-objects:`); determinism by the existing rerun test.
- Nits `SemanticContextSuite`: "a snippet cut never splits a surrogate pair"; "no whitespace in the leading text: cut on a code point, marked"; "a long text is cut at a word boundary and marked, never silently" (with the 08b fix).
- Process note: code and tests written in the same turn; the list was written up afterwards.

## Files (all modules/rag)
Main: `Chunking.scala`, `SemanticContext.scala`, `SemanticRetrieval.scala` (doc note only), `IngestMain.scala`. Tests: `ChunkingSuite.scala`, `SemanticContextSuite.scala` (incl. 08b; the old "total snippet bytes are capped" test replaced by the two L5 tests), `IngestMainSuite.scala`. Nothing created.

## Outcomes
- L1: refusal (`BoundExceeded`) with an accurate message. Word at chunk start longer than `maxChars`: "A word of ... is longer than ...". Otherwise: "A whitespace run of N characters of <entity> starting at P leaves no word start within M characters of the chunk starting at S; refusing to cut inside it". The agent chose refusal over lossless chunking (lossless would need whitespace-only chunks that cannot be embedded and would break the rule that chunks start and end at word starts).
- L5: `fromHits` gives each of n items a snippet budget of `MaxTotalSnippetBytes / n` UTF-8 bytes (new public `snippetBytesFor`) on top of the 1500-character cap; cut at a word boundary where one exists, else at a code point boundary; `snippetTruncated` set on every cut. `MaxItems` stays 20, so `topK` up to 20 is safe. Direct construction and `read` still enforce the total cap and refuse.
- L7: per index a second `WIDE-SAMPLE` query filtered by document type only, top 5, full keys and distances, `LOCAL latency`; the run fails unless the source row is in the results while other candidates exist. Existing `SAMPLE` lines and `fetchText` check unchanged.
- L10: `local cost: $0 (in-process fakes); AWS cost deferred to Phase 12` printed after object counts and written as `localCost` in `ingest-report.json`; report stays deterministic.
- Snippet cut: never splits a surrogate pair; with no whitespace cuts on a code point and sets `snippetTruncated`.

## Caps (schema `scholarchain.rag.semantic-context/1` unchanged)
Items 20; 1500 characters per snippet; 24576 snippet bytes (UTF-8) in all; 5 evidence IDs per list; 500 query characters. Per-item budget in `fromHits` is 24576 / n bytes (1536 or more for up to 16 items; 1228 for 20; non-ASCII reaches the byte share first). A cut is always marked (`snippetTruncated`, full `textChars`, full text via `textRef`); over-cap input arriving another way is refused, never silently cut.

## Counts
One `sbt quick`, read from `target/streams`: rag `Passed: Total 68, Failed 0, Errors 0`; root `Passed: Total 749, Failed 0, Errors 0`; `Phase8RagSuite` 32 tests, 0 failures. Marker mtime not checked. `sbt check` not run.

## Gate status
`sbt quick` passed (turn evidence, quick set only). Re-review ready for L1, L5, L7, L10 and the snippet-cut nit. Pending: the human's `sbt clean check`; real-data runs (the wide-sample rank check has run only on the hand-built fixture).
