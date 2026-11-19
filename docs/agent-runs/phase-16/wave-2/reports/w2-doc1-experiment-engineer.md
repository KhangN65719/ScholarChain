# W2-DOC1: experiment-engineer report (EVALUATION.md draft, frozen outputs)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 (overnight) and preserved as received (formatting condensed).

---

**Status: COMPLETE** (draft only).

Draft path: `/Users/winnguyen/ScholarChain/target/p16-drafts/EVALUATION.md` (orchestrator's correction: the guard refuses writes outside the project; `target/` is gitignored). Nothing else written in the repository. Intended final path: `experiments/EVALUATION.md` (plan WO-9 write set; `docs/evaluation.md` is WO-10's).

Sections:
1. Graph integrity: `graphchecks-a-export-2` row totals, every counter and rate, the 50-edge trace, the composed reconciliation (edges 135,788, vertices 32,225, differences 0) with source `phase14-rehearsal-graph-2.log:20`; the old -12,371 only as the definitional difference (P16-21, OD-3).
2. Factory counts: `factorycounts-1` totals and per-agent first-pass counts as raw x/y; 44 unparsed review files, 23 unclassified findings, 0 ownership violations; P16-8 small-denominator threat.
3. Benchmark v1: 56 questions, strata, shortfall (shortest-path 0 of 2 absent), vector coverage checks and 2,000 deleted keys, label spot-check, m4 regeneration match, fill check values, generator state and limits.
4. Phase 15 performance: A-F from `experiments/RESULTS.md`, the D table, D p50/p95 with 95% CIs per level; method (nearest-rank percentiles, 1,000 resamples, seed 16, iid over OK latencies) and limits.
5. Truncation finding: h4-diagnostic-1 to -4 table, spend and tokens (total 0.255987 USD), P16-38 rules read against the measures (rule (a)), the fix (P16-41), the -4 confirmation (7 claims each, 1,828 and 1,821 tokens); -1/-2 have no measures; -3 ran prompt 1.1.0.
6. "NOT DERIVED: pending run" placeholders for G, H plus AnswerAudit and the human rating, ER replay plus ErExtras; reproducibility, H-8, H-9 NOT DERIVED.

Sources: repository `CLAUDE.md`, `docs/agents.md`, `phase-16/{plan,decisions,human-commands}.md`, `phase-15/ratification.md`, `experiments/{RESULTS,LIMITATIONS}.md`, `benchmark/{BENCHMARK-MANIFEST-v1.json,README.md}`, `evaluation/er-README.md`, `g-retrieval/README.md`, `h-answers/README.md`; data `runs/phase16/graphchecks-a-export-2/{graph-check-report.json,run-info.json}`, `runs/phase16/factorycounts-1/{factory-summary.txt,run-info.json}` and the first 150 lines of `factory-counts.json`, `runs/phase16/h4-diagnostic-{1..4}/{diagnostic-summary.json,run-manifest.json}`, `phase16-ci-on/{SUMMARY.txt,latency-ci-by-level.csv,latency-by-level.csv}`, `phase16-label-spotcheck.md`. `exp-G-*`, `exp-H-*`, `er-replay-*`, `erextras-*` not read.

Not found or not verified: spot-check count (P16-22 says 25 of 25; the file lists 15 rows, "all 15 MATCH"; both stated); `target/` gitignore not checked by the agent (guard blocked `git check-ignore`); `labels-v1.jsonl`, `questions-v1.jsonl` (no `vectorAvailable=false` count), `provenance-trace.jsonl`, `factory-evidence.tsv` and the phase-14 log line not read; h4-diagnostic-1 to -3 manifests record no prompt version (from the decision log and README); Phase 15 numbers from `experiments/RESULTS.md` and `LIMITATIONS.md`, not the phase-15 records; H-1 billed-cost reconciliation in no file read; no reproducibility section; GraphChecks' verification of the export's per-file sha256 not stated; section 3 notes no bridge-analysis stratum in v1; section 2 notes "ownership violations 0" is a parser count beside 150 refusal-prose events (no link drawn).

For the human: reconcile 15 vs 25; D CIs assume independence and cite the chat sandbox's paths; the draft moves to `experiments/EVALUATION.md` and WO-9 fills section 6 after the chain; H-4 evidence covers two ranking questions only.

No sbt, AWS, Python or git writes.
