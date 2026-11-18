# Phase 16 decisions

Recorded by the orchestrator on 2026-11-17 from the human's message approving `plan.md`. The human is the decision owner. IDs are local to Phase 16 (P16-1 and on).

| ID | Decision (human, 2026-11-17) |
|---|---|
| P16-1 | Plan approved with amendments A1 to A4 (P16-2 to P16-5). D1 to D10 of `plan.md` are approved as written there, as amended below. |
| P16-2 | A1: H-4 (the Haiku diagnostic) depends on WO-4 `AnswerEval` with a `--diagnostic` mode (at most 4 Haiku calls, `stopReason` logged), not only on WO-3 part 1. H-4 moves to Thu 2026-11-19. |
| P16-3 | A2: run H's call cap covers the worst case: 78 answers x up to 3 synthesis attempts, about 250 calls. The token-metered USD stop is the binding limit; the manifest states both. |
| P16-4 | A3: H-1 (Billing reading) on Wed 2026-11-18 morning, because session 2 charges lag about a day. |
| P16-5 | A4: Python and local steps in `human-commands.md` may be run on the human side through Claude in chat; each step records who ran it. |
| P16-7 | (2026-11-18) OD-1 option (b): `scripts/smoke-test.sh --question` becomes required (no default). `scripts/README.md` shows the example "Who are the top 3 researchers by average coauthors per paper between 2015 and 2019?" (answered in STUB1 with 4 evidence items). |
| P16-8 | (2026-11-18) Accepted: GraphChecks matches a path hop by exact `edgeId`, then endpoints, then window (no normalising); raw outputs go to `/Users/winnguyen/scholarchain-data/experiments/runs/phase16`; BenchmarkGen runs after the Wave 1 commit; FactoryCounts measures first-pass acceptance per scope and agent, stated as a threat to validity. |
| P16-9 | (2026-11-18) WO-7 harness failure is real (3 PENDING-WO markers outside WO-5's list: `load-tests/lib/ecs-load-client.sh:14`, `experiments/d-query-load/collect.sh:18`, `experiments/f-failure-recovery/collect.sh:11`). Repair: experiment-engineer, write set widened to those 3 files, resolve to `retrievalMode`; the test is not changed. Fold this and the OD-1 change into one batch before the code-reviewer, then the batched review. |
| P16-10 | (2026-11-18, review 1 finding 10) Ranking relevance grades are tiered: ranks 1-3 grade 3, 4-6 grade 2, 7-10 grade 1 (the coauthor-lookup scale). nDCG keeps gain 2^g - 1; WO-7's oracle tests stay. |
| P16-11 | (2026-11-18, review 1 finding 17) `scripts/smoke-test.sh` prints identifier fields only (queryId, abstained, evidence count, abstentionReason) in place of `RESPONSE_HEAD`; no answer text in console or log. |
| P16-12 | (2026-11-18, review 1 finding 18) R1's deviation from P16-9 is accepted: the three PENDING-WO-10 markers were removed and the comments point to the deployed definition. |
| P16-13 | (2026-11-18, re-review 4) Fix all four LOW findings N1-N4 in one last small batch (repair batch 4): N1 BenchmarkGen refuses unknown options and requires `--vector-export` or an explicit no-vector flag recorded in the manifest; N2-N4 wording and comments. A narrow re-review covers only N1-N4. |
| P16-14 | (2026-11-18, H-2) The human confirms the Phase 15 ratification (`docs/agent-runs/phase-15/ratification.md`). |
| P16-15 | (2026-11-18, H-1) Billing 8.91 USD month-to-date at 2026-11-18 08:07 (about 2.73 USD for Phase 15 session 2 so far). Phase 16 gate: 8.91 + about 0.50 lag + 2.40 HIGH = about 11.81 <= 14 USD: GO. |
| P16-16 | (2026-11-18, Wave 1 harness) Failure (a), `5.smoke.question-empty.*` x4, must be fixed before the Wave 1 commit. infra-engineer: an empty `--question` value produces the required-question message (`--question is required`, `no default`), fail closed before any work. Write set `scripts/smoke-test.sh` only; the test is not changed. After the repair the human is told; Claude in chat reruns the check and commits Wave 1. |
| P16-17 | (2026-11-18, Wave 1 harness) Failures (b), `8.committed-manifest.{G,H,count}` and `15.experiment.manifests.each-of-the-six...`, are caused by uncommitted Wave 2 files. The G and H manifests stay out of the Wave 1 commit. After the Wave 1 commit, Wave 2 updates those harness counts and makes G and H validate. |
| P16-6 | Order: record these decisions, do WO-0, then dispatch Wave 1: WO-1 first, then WO-2, WO-3 part 1, WO-5, WO-6, WO-7, and WO-4 starting with `BenchmarkGen`, `GraphChecks`, `FactoryCounts`. One sbt at a time. No AWS, no commit. |
