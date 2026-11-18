# Phase 15 Ratification

Written by the orchestrator on 2026-11-17 (Phase 16 WO-0, decision D9 of `../phase-16/plan.md`) from `decisions.md` P15-48, in the Phase 14 form. Pending the human's confirmation (Phase 16 task H-2); the billing section stays open until H-1.

- Decision: ACCEPTED by the human owner (Khang), 2026-11-18 (P15-48: "Phase 15 ACCEPTED"). Commit 848fb64.
- Decisions ratified: P15-1 to P15-48 as recorded in `decisions.md`.
- Basis: `plan.md`, `decisions.md`, `wave-1/` to `wave-3/` (reviews, repairs, reports), the session folders `session-1/`, `session-ab/`, `session-tonight/`, `experiments/RESULTS.md`, `experiments/LIMITATIONS.md`, the release records in `docs/releases/phase-15/`, and the final review `wave-3/review-2.md` (APPROVE, N1 to N5; N5 deferred to Phase 16).
- Phase gate: the human's `sbt clean check` `[success]`, suites 411, 167, 260, 99, 1162, 0 failed (P15-48).
- Script harness: `RESULT pass=1935 fail=0`, `SKIPPED_CASES 0`, real repository untouched; `scripts/check.sh --skip-sbt` PASS_WITH_SKIPS (sbt covered by the clean check) (P15-48).
- Results copies: 7 `SUMS_OK` in `experiments/results`. Account-ID scan clean (P15-48).

## Delivered (real AWS runs, tier S, us-east-2)
- A (baseline, re-run at 5c69ba9): pipeline SUCCEEDED in 61.3 min; export 17 tables, 34 objects. Course fields not derived (see accepted-open).
- B (EMR managed scaling): scale-out and scale-in observed (core 1 to 3 and back), `derived.json` in `experiments/results/b/`.
- C (embedding backlog): 2,000 chunks, backlog peak 1,804, drain 406 s, workers 1 to 4 and back to 1.
- D (query load, stub answers): four levels 1, 8, 24, 48 clients, p50/p95/p99, errors, ECS scale-out and scale-in.
- E (retrieval modes, stub answers, mixed provenance P15-44): graph, vector and fused, 351 requests each.
- F2 (failure and recovery): PASS 9, FAIL 0, INFO 3, UNKNOWN 0; F1 kept, labelled invalid.
- Numbers and their source files: `experiments/RESULTS.md`.

## Actual AWS billing
- OPEN. Last reading 6.18 USD month-to-date (P15-43, before session 2). The Phase 15 billed total and its reconciliation against the 14 USD gate are Phase 16 task H-1 (Wed 2026-11-18 morning, P16-4).

## Accepted-open, carried forward
- Smoke before D and E (graph plus vector answer with evidence IDs on AWS): NOT MET; SMOKE1 and SMOKE2 failed, STUB1 passed (`docs/releases/phase-15/smoke-tn.md`).
- Haiku synthesis rejected (finding 3, diagnosis UNVERIFIED) and `abstentionReason` not captured (finding 5) (P15-45).
- Separate vector, rank and synthesis services not built; one combined query service (P15-47).
- A partially derived; DynamoDB throttles NO_DATA; Step Functions `Retry` not shown (P15-47, `RESULTS.md` section 9).
- C's 2,000 vector keys deleted from three indexes after E (P15-45 finding 4).
- Stale PENDING-WO-8 markers (review-2 N5).
- Full list: `experiments/LIMITATIONS.md`.

## Required in Phase 16
- Haiku synthesis truncation and rejection reasons (findings 3 and 5), stale PENDING-WO-8 markers (N5), billed-cost reconciliation (P15-48).
- The Phase 16 evaluation groups (`../phase-16/prompt.md`).

## Excluded from the commit
- Not recorded in P15-48.
