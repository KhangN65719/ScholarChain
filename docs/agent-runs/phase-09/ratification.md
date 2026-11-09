# Phase 9 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-08.
- Basis: review.md (code-reviewer, Opus): round 1 REQUEST CHANGES (blocker B1, majors M1 and M2), round 2 APPROVE after one batched repair round. Also decisions.md, validation.md, the work orders and reports 01, 02, 03 and 05.
- Phase gate: the human ran `sbt clean check`, which finished at 6:02 PM with exit 0 and [success] in 1096 s. It ran after the last source change. Log: phase-09-clean-check.log.
- Benchmark: 45 of 45 questions pass on deterministic graph retrieval, and semantic retrieval is scored separately at 17 of 17 checks. Everything ran on local fakes and a fixture world, with no real-data run.

## Delivered
- Storage read ports with local fakes: typed metrics, precomputed shortest paths, components, communities, a researcher directory and audit writes.
- The rag runtime workflow: planner, resolver, metric, path, community and semantic retrieval, evidence builder, synthesis, verification and audit. The model sits behind a port with a deterministic offline stub.
- F1 enforced: every number comes from the graph or metric stores. Text shown for a verified claim is always system-rendered (B1). Numbers are checked against the asserted field (M1). Names and titles are passed as untrusted data and scanned for injection (M2).

## Accepted limits and deferrals
- G3: the rag-engineer's repair report never arrived. The reviewer verified the repair from source, and the rag-side test edits are documented only by their counts.
- Carried to Phase 11 (adversarial testing): m5, claim IDs written by the model, and n1, an abstention-only answer not marked abstained.
- Accepted as minor: m6 (the resolver skips the query wrapper), m7 (publication source and orcid not scanned) and m8 (a weak cap test).
- P11: the Neptune fake still dispatches on the query name only; deferred to Phase 12.
- Prompts live in modules/rag, because prompts/ has no owner (P1).
- Round-0 prompt hashes were never recorded. The shipped hashes are pinned in tests.
- Deferred to Phase 12: a real model and embedder (Bedrock), a real tokenizer, live Neptune templates, DynamoDB-backed audit, and AWS latency and cost.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
