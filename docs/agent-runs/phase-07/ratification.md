# Phase 7 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-08.
- Basis: review.md (code-reviewer, Opus): round 1 REQUEST CHANGES, then APPROVE on rounds 2 to 4. Also decisions.md (D1 to D13), validation.md and morning-summary.md.
- Phase gate (V9): the human ran `sbt clean check`, which finished 10:17:56, exit 0, [success], in 1128 s, after the last docs edit. Log: phase-07-clean-check.log.
- Real data: the Phase 6 Spark pipeline was rerun on the new code (D4) as run-v1-4600a8f4-d438-4683-8852-db8743eb23f0, exit 0. It wrote 25 tables and 0 integrity violations.
- Workflow: the build ran unattended overnight under an explicit human prompt. Human rulings were applied in the morning.

## Delivered
- Core: mention-endpoint coauthor edges get the core ID `mention-coauthor:v1:`, used by Spark. Spark's transformVersion is spark-analytics/1.1.0.
- Storage module adapters, all against local fakes:
  - S3: versioned keys, manifests, hashes.
  - Neptune: bulk-load CSV, parameterized read-only openCypher, endpoint and evidence validation; RESOLVES_TO and DERIVED_FROM included.
  - DynamoDB: 6 tables, explicit keys, conditional writes, TTL only on disposable tables.
  - S3 Vectors: 5 indexes, query port with 7 metadata filters, batch, retry, rejected-vector records.
- AUTHORED and PUBLISHED_IN edge IDs come from core StableIds.
- AWS integration suite is gated behind SCHOLARCHAIN_AWS_IT=1, and a test proves it is skipped by default.
- Service limits are cited in service-limits.md.

## Accepted limits and deferrals
- No mention-coauthor:v1: ID was read back from the new Spark run (no Parquet reader, D6).
- The bounded AWS integration run is DEFERRED to Phase 12.
- The Extended loader and the new vector facets have not run on real Parquet. topic and institution are allowed vector keys but unset, since Spark has no source data for them.
- AFFILIATED_WITH, HAS_TOPIC and SUPPORTED_BY are not loaded, because the Spark outputs have no source columns for them (documented).
- D2, D3, D7, D8, D9 and D12 are accepted as recorded, along with the non-blocking reviewer notes.

## Excluded from the commit (D11)
- AGENTS.md, .codex/ and .agents/ are the human's local Codex setup, out of scope.
- Also excluded: datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
