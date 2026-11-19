# Phase 16 Ratification

Written by the orchestrator on 2026-11-19, in the Phase 15 form, from `decisions.md` P16-1 to P16-47, `human-commands.md` "Results recorded" and the review verdicts in `wave-1/` and `wave-2/`. Content confirmed by the human on 2026-11-19 with the Phase 17 addition (a) and (b); the final harness passed (1949/0); the Decision line and the commit by Claude in chat are pending.

- Decision: ACCEPTED by the human owner (Khang), 2026-11-19, in the commit that adds this record (parent b5a55c3). Gates: phase-gate `sbt clean check` [success], clean-checkout build matching it, final harness `RESULT pass=1949 fail=0` on a quiet tree, final Opus review 27 and re-review 28 approved.
- Decisions ratified: P16-1 to P16-47 as recorded in `decisions.md`; open decisions OD-1 to OD-5 all resolved (`open-decisions.md`).
- Basis: `plan.md` (approved with amendments A1 to A4), `decisions.md`, `open-decisions.md`, `human-commands.md`, `morning.md`, `wave-1/` and `wave-2/` (work orders, reports, reviews 1 to 27, repairs), `experiments/EVALUATION.md` (measured facts, WO-9) and `docs/evaluation.md` (interpretation, WO-10). Final review: WO-8 review 25 (Opus, REQUEST CHANGES), repair rounds 1 and 2, closed by review 27 (APPROVE, accepted-open A1 to A4 below).
- Phase gate: the human's `sbt clean check` at `ccbc1ec`, `[success]` 1314 s, suites 1427, 99, 260, 170, 473, 389, 501 (1 skipped: `ExportHeapMeasureSuite`, opt-in on `SCHOLARCHAIN_S_EXPORT_DIR`), 331, 49, all 0 failed and 0 errors. Changes after `ccbc1ec` are docs-only (EVALUATION.md, docs/evaluation.md, `tests/scripts/README.md` note, phase-16 records), so no further `sbt check` is required (CLAUDE.md).
- Clean-checkout build (H-13): fresh clone of `ccbc1ec`, `sbt clean check` `[success]` 1315 s; all nine suite totals equal to the phase gate group by group, the same single skip (review 23).
- Script harness: last run `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, real repository untouched (2026-11-19, before the final docs). Final run after the docs (2026-11-19): `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, real repository untouched (log `phase16-final-harness-2.log`; an earlier run gave 1948/1 only because files were written during it).
- Redaction: the strict scan `[0-9]{12,}|scholarchain-course-|arn:` found 0 hits on EVALUATION.md and docs/evaluation.md (review 27); the one hit in `tests/scripts/README.md:83` is AWS's documented example id (P16-47).

## Delivered
- **Tooling** (`experiments/evaluation`, Scala, `evaluation` sbt project): BenchmarkGen and BenchmarkFill, GraphChecks, FactoryCounts, ErExtras, RetrievalEval (G), AnswerEval (H) with CostGuard, AnswerAudit, Bootstrap, rating-sample export; manifests and run scripts for G and H through `scripts/run-experiment.sh` with approval records.
- **RAG fixes**: stop-reason logging and rejection reasons; synthesis limit 4096 with no retry on `max_tokens`; ranking claim cap (prompt 1.2.0, rule 6) and `PlannerConfig.maxLimit` 12; usage-missing replies charged at the worst case (P16-45).
- **Benchmark v1**: 56 questions (48 generated, 8 human-written), labels from the export, spot-check 15 of 15 (human) and 25 of 25 (machine) (P16-42).
- **Graph correctness**: `graphchecks-a-export-2` all integrity counters 0, reconciliation difference 0 and 0; H-8 provenance audit 10 of 10.
- **Retrieval G** `exp-G-20261119T053608Z-2e8943cf`: 336 of 336 records; **answers H** `exp-H-20261119T053926Z-2e8943cf`: 76 of 76 (34 answered, 22 abstained, 20 repeats), 0.891881 USD metered; independent audit `h-audit-2` (ranking 12 of 12, `exportMatchedRate` 0.954323, contradictions 2 of 199); human rating of 20 answers (usefulness 4.00, clarity 4.15, supported 20 of 20; P16-46).
- **Entity resolution**: replay equal to `eval-v3-r3` (P 1.000, R 0.802, F1 0.890; 0 of 230 changed); ErExtras `ER_REFERENCE_CHECK` MATCH.
- **Factory**: FactoryCounts over phases 1 to 15; H-9 audit of Phase 14, 16 of 20 counted items correct.
- **Performance**: D bootstrap CIs (WO-5); H-12 regeneration of B to F, 42 of 44 committed derived files identical (P16-47).
- **Reproducibility**: clean-checkout build equal to the phase gate; ER replay; benchmark regeneration (m4).
- Numbers and their source files: `experiments/EVALUATION.md`; interpretation, threats and negative results: `docs/evaluation.md`.

## Actual AWS billing
- H-1 (2026-11-18 08:07): 8.91 USD month-to-date; H-1b (18:29): 8.93; H-1c (2026-11-19 07:41): 10.39 USD, +1.46 since H-1b, the Phase 16 Bedrock usage (H, diagnostics 1 to 4, G) (P16-43). Headroom to the 14 USD stop: about 3.61 USD for Phase 17.
- The Phase 15 billed total beyond these readings: NOT DERIVED (EVALUATION.md section 8).

## Accepted-open, carried forward
- Review 27 A1 to A4 (`docs/evaluation.md` lines 227, 229, 265): routed by the human (P16-48) to a docs-only order outside WO-8 (WO-10-R3); fixed and closed by review 28 (APPROVE, `wave-2/review-28-verdict.md`). Listed here for the record; no longer open.
- Review 22 advisory: EVALUATION.md line 935 "453-464" vs line 147 "453-455 and 462-464" (no figure changes).
- Review 15 finding 7 (service-engineer, EvidenceService flag): advisory, recorded only. Review 10 observation C (misleading "top 20" test text) and review 4 (README lines 20 and 30 stale): not repaired.
- One unidentified deprecation warning in `modules/rag` main.
- Whether real Bedrock ever omits `usage` is unobserved (reviews 16, 17).
- G and H `raw/entrypoint.log` hold the unmasked account id in sbt's fork line (local only; P16-42 item 2); follow-up: pass bucket names without the echo.
- NOT DERIVED: bounded local sample-pipeline replay; Phase 15 billed total beyond the readings; release records not re-read; which Phase 14 first-pass pairs the FactoryCounts parser missed (reviews in decision files); why S08 is in the rating's abstention group.
- Negative results kept (docs/evaluation.md section 5): false-abstention rate 0.282609 on answerable questions; the synthesis truncation finding and its history; H repeats not byte-identical (`sameAnswerHash` 0.65); fused mode skipped the vector stage on 17 requests (cause UNVERIFIED); FactoryCounts precision 16 of 20.
- W2-RB5's report was lost (OD-5, accepted).

## Required in Phase 17
- P16-34: raise `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` to about 60000 before any demo deploy with Haiku; deploy-engineer notes it in the release record.
- Budget: about 3.61 USD headroom to the 14 USD stop (P16-43).
- The recommendations labelled as such in `docs/evaluation.md` section 6.
- (a) Autograder V1 (`CS441_Autograder_For_Students`; preview run by Claude in chat on a fresh public clone: 32 pass, 9 fail, 6 warn). Fix: P01 (per-phase human-gate, `commit.txt` and agent-report index files, filled from existing records); K01 (fake test keys rewritten so the patterns do not match, for example built at runtime); F05 (the `settings.json` hook command quoting that the checker misparses); R03 (list the guarded categories in `guard-dangerous.sh`, which delegates to `policy.py`). Explain in the README: F07 (`${VAR}` reference), K02 (history), E01 to E03 (archived report formats). Then run V1, V2 and V3 and email the CSVs to the TA by end of Friday 2026-11-20 (bonus).
- (b) README, final report, runbook, diagrams, bounded reproduction exercise, demo video.

## Excluded from the commit
- Local evidence outside the repository under `/Users/winnguyen/scholarchain-data` (run directories, logs, rating files, sheets, the clean-checkout clone `/tmp/scholarchain-clean.Xn18`).
- `target/p16-drafts/` (gitignored; includes the unused `h8-sheet.py`).
