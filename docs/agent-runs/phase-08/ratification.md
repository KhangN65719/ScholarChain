# Phase 8 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-08.
- Basis: review.md (code-reviewer, Opus): round 1 REQUEST CHANGES, round 2 APPROVE, round 3 APPROVE after repair round 2. Also decisions.md, validation.md, prompt.md and the owner reports.
- Phase gate: the human ran `sbt clean check`, which finished with exit 0 and [success] in 1219 s. It ran after the last source change. Log: phase-08-clean-check.log.
- Real-data runs (human):
  - Spark JSONL export: SUCCESS. transformVersion spark-analytics/1.1.0, 41,885 semantic-summary rows. edges-coauthored has 79,178 `mention-coauthor:v1:` IDs and no `coauthor:v1:` ID with a mention endpoint. This closes the Phase 7 unverified-ID item.
  - Ingest 2: SUCCESS. Vectors 35,160 / 6,663 / 20 / 42 / 0, with 0 rejects and 0 failed batches. Every fetchText hash verified, and every sample source row ranked 1 with a tie size of 1.
  - Ingest 1 failed on a 12-word-prefix sample query and was repaired without weakening the check.

## Delivered
- An Embedder port with hash-embed-v1: 384 dimensions, deterministic, offline, no new dependency (E1, human ruling).
- A Spark JSONL export job feeding storage without a Parquet reader (E2, human ruling).
- Semantic documents and chunking. Long text goes to S3 and compact metadata to vectors. The model version is stored.
- Idempotent ingest with a ledger, manifest and batch digests. A rerun under the same label with changed input is refused (B1).

## Accepted limits and deferrals
- F1: snippets keep the Phase 6 numbers, with a notice that they aren't metrics. Phase 9 must take every number from graph or metric stores.
- Hash embedding is lexical, so retrieval quality beyond the identity checks is unproven. A semantic embedder needs a ledger entry and human approval (Phase 12).
- H1: review notes N1 to N6 are not repaired. N1 (concurrent same-label runs) must be fixed before the Phase 12 persistent ledger.
- G1/G2: the storage repair report (report-06) never arrived, so the storage repair was verified from source.
- Deferred to Phase 12: the AWS adapters, the SQS queue, the bounded AWS ingestion, and AWS latency and cost.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
