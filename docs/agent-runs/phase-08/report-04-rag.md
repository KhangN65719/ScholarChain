# Report 04: rag-engineer (archived by the orchestrator; sections abridged only where marked)

**Status: COMPLETE** (quick set only). Owner: rag-engineer. Work order: work-order-04-rag (E1, E4, E9). `IngestMain` not run on the real export. `sbt check` not run, per the work order.

## Process deviation
The agent wrote `RagTemplates`, `Chunking` and `EmbeddingModels` before stating the test-to-item list in chat. The list was stated before `SemanticContext`, `SemanticRetrieval`, `IngestMain` and every test.

## Test-to-item list (tests in `modules/rag/src/test/scala/scholarchain/rag/`)
- 1 Templates, `RagTemplatesSuite`: fixed field order and wording; every summary type renders; path and work summaries render; deterministic in any locale (Turkish locale, shuffled row); no number or ID appears that is not in the row; fixed wording holds no digit; missing fields are stated; unknown summary type refused; row without text, subject or type refused; renderer text is what ingest hashes, stores and embeds.
- 2 Chunking, `ChunkingSuite`: short text is one chunk; bounded, ordered, word boundaries; overlap at most the limit; no loss (`reassemble` exact, incl. unicode and newlines); zero overlap; determinism and stable IDs (equal to `StableIds.chunk`); ID depends on entity, version, type, ordinal and text; blank text and over-long word refused; policy sizes; Phase 6 sized summaries stay whole.
- 3 Model versioning, `EmbeddingModelsSuite`: registry entry (`hash-embed-v1`, 384, `-v1`); mismatched embedder fails `EmbeddingInvalid`; mismatched embedder cannot build the retrieval; index specs checked for suffix and dimension; new model gets generation 2 and `-v2` names; bad registry refused.
- 4 Smoke, `RetrievalSmokeSuite`: five indexes, topic index empty; name finds researcher; members find community; title words find paper; endpoints find path; filters narrow by year, community, researcher, document type, combined; `fetchText` for all 12 rows.
- 5 Context format, `SemanticContextSuite`: fields and notice; JSON round trip; null window; exact key set, no metric key; item cap; marked cut; total-bytes cap; query cap; read refuses unknown key, missing key, metric field, bad notice or flag, oversize, 21 items, inconsistent lengths, non-JSON; item validation.
- 6 IngestMain, `IngestMainSuite`: tiny export (counts, objects, 4 samples, result line); rerun identical report; bound; different bound refused; missing export; no `_SUCCESS`; changed part file; output inside export or foreign; rejected row gives FAILED; argument parsing; `main` returns or throws.
- Gate: same key never refers to different text = "the same key never refers to different text" (changed text gives `ChangedVectorContent`, stored vector and text unchanged), "a changed template needs a new key or index" (all 12 rows rejected under pass-through), "a rerun skips"; model version stored = "the model version and the template version are stored with every vector"; text fetchable = "fetchText returns the rendered text through the S3 pointer"; smoke = the nine tests; metrics not from vectors = "exact graph metrics are not answered from vector similarity" plus exact-key-set test (structural only).

## Commands
- `sbt quick` run 1: compile passed, 2 failures (ujson writes a Long as a string so report byte counts were strings; one smoke test assumed the wrong Ada profile ranked first). Fixed. Run 2: `[success] Total time: 78 s`, scalafmt passed, marker written. Last two lines `Passed: Total 56, Failed 0, Errors 0` and `Passed: Total 512, Failed 0, Errors 0`.
- rag from JUnit XML: 68 passed, 0 failed, 0 errors (Chunking 10, EmbeddingModels 7, IngestMain 11, RagTemplates 10, RetrievalSmoke 18, SemanticContext 12). Other modules' totals not read. A format hook failed once on the Scala 3 keyword `export`; renamed.

## Files
Created in `modules/rag/src/main/scala/scholarchain/rag/`: `RagTemplates`, `Chunking`, `EmbeddingModels`, `SemanticContext`, `SemanticRetrieval`, `IngestMain`. Tests: `RagFixtures`, `RagTemplatesSuite`, `ChunkingSuite`, `EmbeddingModelsSuite`, `SemanticContextSuite`, `RetrievalSmokeSuite`, `IngestMainSuite`. Nothing outside `modules/rag` touched; no dependency added.

## Templates
Fixed wording holds no digit. Fields: heading, Subject, Window, Window years, Node kind, Summary (Spark text unchanged), Metrics named by the evidence, Evidence works, Evidence nodes. Versions `rag-template/<type>/1`. Headings: Researcher profile, Community profile, Representative paper, Collaboration path. Null `nodeKind` prints `not recorded in the source row`; work rows print `Window: not-windowed`; empty evidence prints `none recorded in the source row`; unreachable path text is kept; unknown type gives `UnmappedSummary`.

## Chunking
`rag-chunking/1`: max 1000 chars, overlap 100 (0 to maxChars/2; maxChars at least 50). Within the max = one unchanged chunk; else cut at the last word start within the limit, next chunk starts at the first word start at or after `end - overlap` and after the previous start; no word split. A word longer than the max gives `BoundExceeded`; blank gives `MissingText`. Ordinals from 0; chunk ID = core `StableIds.chunk(entity, sourceVersion, documentType, Some(ordinal), text)` (no domain gap). `Chunker.reassemble` is exact. Phase 6 summaries are not chunked.

## Model registry
`EmbeddingModelSpec(version, dimension, generation)`, suffix `-v<generation>`; one entry `hash-embed-v1`, 384, generation 1. A new model or dimension is a new entry with the next generation and new `-vN` index names. `requireMatches` and `requireIndexSpecs` fail with `EmbeddingInvalid`; `IngestMain` and `SemanticRetrieval` call them. `HashEmbedder` not duplicated.

## Context format `scholarchain.rag.semantic-context/1`
Top level: `schema`, `embeddingModel`, `query`, `deterministicMetricsIncluded` (always false), `metricsNotice`, `items`. Notice: metrics "come from graph or metric stores, not from this retrieval"; numbers in a snippet restate template text and must not be quoted as metrics. Item: `vectorKey`, `index`, `documentType`, `distance`, `window`, `windowStart`, `windowEnd`, `subjectId`, `evidenceWorkIds`, `evidenceNodeIds`, `textRef`, `templateVersion`, `textHash`, `snippet`, `snippetTruncated`, `textChars`. Caps: 20 items, 1500 snippet chars, 24 KiB total snippet bytes, 5 evidence IDs per list, 500 query chars; over a cap = `BoundExceeded` on build and read. **Agent's choice for review:** a text over 1500 chars is cut at a word boundary with `snippetTruncated: true` and full `textChars` (full text stays reachable via `textRef`); the only cut. The reviewer may prefer refusal.

## IngestMain
```
! SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt -J-Xmx4g "rag/runMain scholarchain.rag.IngestMain /Users/winnguyen/scholarchain-data/phase8-out/jsonl/run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0 /Users/winnguyen/scholarchain-data/phase8-out/ingest/run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-08-ingest.log
```
Optional third argument bounds the summary rows to the first N (needs its own output directory). Success: header lines with `LOCAL: in-process fakes, not AWS`; per-index lines `index <name>: planned= written= skipped= rejected= duplicates= vectors=` (`topic-institution-summary-v1` has 0 by design); `objects documents: count= bytes=` plus embedding-work and all-objects; `rejected=0 unmapped=0 failedBatches=0`; `PHASE8-INGEST RESULT: SUCCESS`; four `SAMPLE <index>: LOCAL latency N ms ... nearest is the source row: true` blocks each with `fetchText: textHash verified`. Failure prints `PHASE8-INGEST RESULT: FAILED <reason>` and exits nonzero (any reject, unmapped type, failed batch, count mismatch or failed sample). Files under the output dir: `ingest-report.json` (deterministic, byte-identical on rerun), `ingest-run.json` (timings, created-or-unchanged split), `objects/` (`LocalFsObjectStore`). Refused: missing or incomplete export; output dir overlapping the export, non-empty without a report, or holding a different export or bound.
Memory (estimate, not measured): ~8 KB per boxed vector, ~350 MB for 41,885 plus rows, documents and facets, so default heap may be insufficient; `-J-Xmx4g` recommended. Fake query latency may be seconds per sample on the real run; LOCAL, not predictive of AWS.

## Assumptions and risks
- Phase 6 summary text holds names, titles and computed numbers, not topics, so researcher and community retrieval is lexical over names and papers over titles; real-data retrieval unproven until the human's run.
- Real `nodes-work` and `researcher-metrics` JSONL schemas unverified (as in reports 01 and 03).
- Bounded source reads summaries twice at start-up (time and transient memory only).
- An oversize real metadata row is rejected and the run shows FAILED with a reject count.
- Evidence ID lists appear in full in rendered text, so long path evidence lengthens documents.
- The format hook rewrote the files; disk holds the formatted version.

## Deferred to Phase 12
AWS S3 Vectors adapter (read before write); SQS; bounded AWS ingestion of at least 10,000 vectors; AWS throughput and latency samples; AWS cost and object-count report; a semantic embedder (Option B, ONNX; needs ledger and human approval; generation 2, `-v2` indexes); topic and institution summaries (no Spark source).

## Blocked-on
None. Next gate: independent code review, then the human's real-data ingest run.
