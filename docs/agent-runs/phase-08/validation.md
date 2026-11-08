# Phase 8 validation (orchestrator record; the human owns ratification)

## Phase gate
- Human `sbt clean check`, 2026-11-08, finished 15:07:44, `[success] Total time: 1219 s (0:20:19.0)`, exit 0. Log: `/Users/winnguyen/scholarchain-data/logs/phase-08-clean-check.log`. Last two summary lines read: `Passed: Total 81, Failed 0, Errors 0, Passed 81` (a module group, the last before root; the console tail did not name it) and `Passed: Total 770, Failed 0, Errors 0, Passed 770` (root). Per-module totals for core, hadoop, entity-resolution, storage, rag and the other groups were not shown in the 40-line tail; they are in the log. The agents' reported counts did not reconcile with each other (for example rag 68 before and after a repair that added tests), so the log, not the reports, is the count of record.
- It ran after the last source change (repair round 2, rag `IngestMain.scala` and `IngestSampleSuite.scala`) and after the last review (round 3, APPROVE).

## Real-data runs (human)
- Spark JSONL export, 14:33, `SPARK-EXPORT RESULT: SUCCESS` (decisions H3). 98,661 coauthor edges: 79,178 `mention-coauthor:v1:`, 19,483 `coauthor:v1:`, 0 other, none with a mention endpoint. Closes the Phase 7 unverified-ID item (D4).
- Ingest 1, 14:35: all 41,885 vectors written, then FAILED on one sample (12-word prefix query; reviewer N6). Repair round 2.
- Ingest 2, 14:46, same output directory: `PHASE8-INGEST RESULT: SUCCESS` (decisions H5). Vectors 35,160 / 6,663 / 20 / 42 / 0; 0 rejects, 0 failed batches; every `fetchText` hash verified; every sample source row rank 1, tie size 1. Local timing 14,063 ms (in-process, not AWS).

## README Phase 8 gate, local evidence
- Same vector key cannot silently refer to different text: `ChangedVectorContent` refusal, immutable content-hashed documents, `ExecutionInputChanged` on a same-label rerun over changed input (review rounds 1 and 2).
- Embedding model version stored: `embeddingModel` required on every ingest write; `hash-embed-v1`, 384.
- Source text fetchable through the S3 pointer: `fetchText` verified on every real-data sample.
- Retrieval smoke tests pass: module, independent and real-data identity checks. Retrieval quality beyond identity checks is unproven (H4 known limitation).
- Exact counts, paths, components, bridge metrics not from vector similarity: structural tests only; snippets keep Phase 6 numbers by design (F1, human may overrule).
- Cost and object counts: local only (41,886 objects, 85,204,197 bytes, local cost $0). AWS cost deferred.

## Deferred to Phase 12
AWS S3 Vectors API adapter, SQS queue, bounded AWS ingestion of at least 10,000 vectors, AWS throughput and latency, AWS cost report, semantic embedder (Option B), topic and institution summaries.

## Reviews
Round 1 REQUEST CHANGES; round 2 APPROVE with conditions; round 3 APPROVE (`review.md`). Repair rounds used: 2 of 2. Open low notes: N1 to N6 and the round 3 notes (H1).

## Process gaps recorded
G1 (storage repair report never delivered), G2 (rag repair report delayed), spark and rag test-to-item lists written after code, constitutional suggestion in G2. No `.claude/**`, `CLAUDE.md` or `docs/agents.md` edit was made.

## Not committed
No commit was made. Keep out of the commit: `AGENTS.md`, `.agents/`, `.codex/`, `datasets/slice/`, `docs/agent-runs/phase-06/readback-paths.py`, the `.DS_Store` files.
