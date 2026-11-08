# Phase 8 review brief (round 1)

Reviewer: code-reviewer (Opus), read-only (Read, Grep, Glob). You cannot run anything, so mark anything you cannot verify as PENDING. Return APPROVE or REQUEST CHANGES with exact `file:line` locations and evidence, batching ALL findings into one list. Never fix or approve your own work.

## Read first
CLAUDE.md, docs/agents.md, docs/agent-runs/phase-08/{prompt.md, decisions.md}. The verbatim Phase 8 gate is in prompt.md. Work orders 01 to 05 and reports 01 to 05 are in the same directory. Phase 7 context: docs/agent-runs/phase-07/ratification.md and decisions.md.

## Evidence of verification
- Orchestrator ran one `sbt check`: exit 0 (`[success]`) after all work was complete. Per-module totals were not read by the orchestrator; the owners' quick-set totals are in the reports (storage 163, rag 68, root 643, spark 56, all 0 failed). The human's `sbt clean check` is PENDING and is the phase gate.
- The real-data runs (spark JSONL export, then `rag/IngestMain`) have NOT run yet; the human runs them after review. Real `nodes-work` / `researcher-metrics` schemas are unverified.

## Files changed in Phase 8 (all untracked or modified; use Read)
- `modules/spark/src/main/scala/scholarchain/spark/JsonlExport.scala` and `modules/spark/src/test/scala/scholarchain/spark/JsonlExportSuite.scala` (spark-engineer)
- `build.sbt` (the root `rag % "test->compile"` edge only) (build-engineer)
- `modules/storage/src/main/scala/scholarchain/storage/`: new `HashEmbedder.scala`, `JsonlExportSource.scala`, `SemanticDocuments.scala`, `EmbeddingWork.scala`, `EmbeddingIngest.scala`; modified `Support.scala`, `Vectors.scala`; tests `HashEmbedderSuite`, `JsonlExportSourceSuite`, `EmbeddingIngestSuite`, `ExportFixtures` (storage-engineer)
- `modules/rag/src/main/scala/scholarchain/rag/`: `RagTemplates`, `Chunking`, `EmbeddingModels`, `SemanticContext`, `SemanticRetrieval`, `IngestMain`; tests under `modules/rag/src/test/scala/scholarchain/rag/` (rag-engineer)
- `tests/adversarial/Phase8*.scala`, `tests/end-to-end/Phase8EndToEndSuite.scala` (test-engineer)
- Not constitutional: no CLAUDE.md, docs/agents.md or .claude/** edit was made (verify none of those paths appear changed beyond `.claude/.last-test-run`).

## Review the gate (README Phase 8) and the scope rules
1. The same vector key cannot silently refer to different text. Check `EmbeddingIngest`, `VectorWriter`/fake interplay, the resume path, the content-hash skip, and what happens on a same-label rerun over changed rows (test-engineer finding 1, below).
2. The embedding model version is stored in every vector's metadata.
3. Source or profile text is fetchable from a vector result through its ordinary S3 pointer with hash verification.
4. Retrieval smoke tests pass and are meaningful, not tautological.
5. Exact counts, shortest paths, components and bridge metrics do not come from vector similarity; check the context format and metadata for any leak.
6. Cost and object counts are reported (local only; AWS deferred).
7. Scope: local only, no AWS client code, no credentials, no new dependency (check `project/DependencyVersions.scala` and build.sbt are unchanged except the one edge), module boundaries (storage must not depend on rag or spark; rag may depend on storage and core; spark must not depend on storage).
8. Long text never in vector metadata; compact metadata; `topic` and `institution` never invented; `Required` vs `RequiredForIngest` split sound.
9. Determinism claims of `HashEmbedder`, `JsonlExport`, work manifest, `IngestMain` reports. Is `hash-embed-v1` spec identical in code, scaladoc and the test reference?
10. Failure handling: RunLedger records for failed batches and retries; rejected vector records; bounds; path escape and symlink handling in `JsonlExportSource` and `JsonlExport` output-directory refusals.
11. Tests: not weakened or deleted (compare Phase 7 test files against what you can see; the Phase 7 suites in tests/adversarial and modules/storage must still assert the same things). A `.fail`-marked test exists in Phase8IngestSuite: judge whether that pattern is acceptable or must be replaced after the repair.
12. Process: the spark-engineer and rag-engineer reports admit writing code before stating the test-to-item list. Note it; it is not a code defect.
13. The agent's own choice in report-04: the semantic context cuts text over 1500 chars at a word boundary with `snippetTruncated: true`. Say whether that is acceptable.

## Open findings already raised by test-engineer (include in your verdict if you agree)
1. Same `executionLabel` rerun over a changed row skips the batch as "resumed" without reporting the content change (storage-engineer).
2. `Chunker` message for a whitespace run longer than `maxChars` is wrong ("A word ... is longer than") (rag-engineer, low).
3. `JsonlExportSource.open` accepts a symlinked part file if the sha matches (storage-engineer, low).

## Accepted limits (do not flag as defects)
AWS adapter, SQS, bounded AWS ingestion of 10,000 vectors, AWS latency and cost: deferred to Phase 12. topic-institution index empty (no Spark source). Phase 7 accepted limits in ratification.md.

## Output
One verdict with every finding batched: severity, `file:line`, evidence, owner (storage-engineer, rag-engineer, spark-engineer, test-engineer, build-engineer), and PENDING items.
