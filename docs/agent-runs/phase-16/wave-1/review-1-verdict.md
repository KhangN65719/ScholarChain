# Phase 16 Wave 1 review 1 (code-reviewer, Opus), received 2026-11-18, recorded by the orchestrator

Reviewer: code-reviewer (fresh context, read-only: Read, Grep, Glob). Scope: WO-1 to WO-7, R1, R2, R3. Diff given: `git diff` of tracked files at review time plus the untracked files listed in the request.

**Verdict: REQUEST CHANGES.** 10 MEDIUM, 9 LOW, no HIGH.

## MEDIUM
1. Coauthor labels exclude `mention-coauthor:v1:` edges and cap items below runtime limits (`BenchmarkGen.scala:174-184, 280, 321-326, 653, 656-662`); runtime `coauthors-of` matches every `COAUTHORED_WITH` edge (`Neptune.scala:1163-1183`), limit 25 (`Retrieval.scala:70`). Owner: experiment-engineer.
2. Ranking labels keep ties past the cut-off (up to N+3, `BenchmarkGen.scala:398-413`); runtime returns exactly `take(limit)` with a tie flag (`Ports.scala:64-71`). Owner: experiment-engineer.
3. Absent-pair path questions labelled `abstain=true` (`BenchmarkGen.scala:594-624`), but the runtime records `NotPrecomputed` as deterministic `path-status` evidence (`EvidenceBuilder.scala:320-340, 390-394`). Owner: experiment-engineer.
4. Benchmark abstention classes have no committed mapping to the 12 runtime codes (`Workflow.scala`); UA-08 may not abstain. Fix: committed mapping plus a stub-mode test pinning each unanswerable template's actual code. Owner: experiment-engineer.
5. FactoryCounts matches file-name aliases (`test`, `service`, ...) in free text and misattributes findings (`FactoryCounts.scala:212-216, 302, 317, 333`; `factory-mapping.tsv:30-41`). Owner: experiment-engineer.
6. First-pass acceptance has no evidence row per (scope, agent) pair (`FactoryCounts.scala:450-484`). Owner: experiment-engineer.
7. Independent label-rule oracle tests (WO-7 item 1) missing; needed before BenchmarkGen produces v1. Owner: test-engineer.
8. No full run has shown `LocalExportStoreSuite` and the new rag suite by count; report per-project totals of the next full run. Pending, not a defect.
9. FactoryCounts prose counters overcount (hook refusals of harmless reads; one event counted per file) (`FactoryCounts.scala:168-180, 387, 416-418`): classify, deduplicate, or label as upper bounds. Owner: experiment-engineer.
10. nDCG gain 2^g - 1 with ranking grades up to 10 makes the top item dominate (`RetrievalMetrics.scala:43-44`, `BenchmarkGen.scala:389, 434`); needs a human decision before run G. Owners: human, then experiment-engineer.

## LOW
11. GraphChecks exits 0 with missing tables; support rows to non-existent edges only in `detail`; `danglingEndpointRate` denominator mismatch (`GraphChecks.scala:135-138, 245, 313, 698-699`). Owner: experiment-engineer.
12. VectorCoverage checks the deletion set by type counts only; no sha256 or `by_window` check, no subjectId overlap between A and S exports (`VectorCoverage.scala:43-81`). Owner: experiment-engineer.
13. BenchmarkGen manifest: no dirty-tree record (`BenchmarkGen.scala:1130-1132`); name `BENCHMARK-MANIFEST-v1.json` vs plan's `BENCHMARK-MANIFEST.json` (`:1065`). Owner: experiment-engineer.
14. `questions-v1.jsonl` carries `stratum` (`BenchModel.scala:195-209`); RetrievalEval/AnswerEval must send only `text`, with a test. Owner: experiment-engineer.
15. LocalExportStore: `listSizes` lists an out-of-root symlinked file (`:89-90`); `emptyObjects` vs real file size inconsistency (`:68, 99-101`). Owner: storage-engineer.
16. `scripts/README.md:249` stale UNVERIFIED (field names confirmed at `QueryService.scala:449-450`, `Contracts.scala:425-428, 484-489`); `:254` duplicate digest sentence and stale "Cases to add". Owner: infra-engineer.
17. `scripts/smoke-test.sh:302` `RESPONSE_HEAD` prints the first 300 bytes of the response, which includes answer text (pre-existing). Owner: infra-engineer, after a decision.
18. R1 departed from P16-9's wording; human acknowledgement not recorded. Owner: orchestrator.
19. `tests/scripts/README.md` has no R3 paragraph. Owner: test-engineer.

## Checked with no finding
Metric math and bootstrap; GraphChecks path-hop rule (P16-8) and disjoint counters; rag rejection codes carry no model text, `max_tokens` path, stub path unchanged, no test changed; load_client `abstention_reason`; derive bootstrap default-off path; smoke-test fail-closed checks and `--question` order (`smoke-test.sh:105-108`, `lib.sh:141, 462`); build.sbt leaf and Test-only edge; authority, no constitutional file, no account id, no `PENDING-WO` under `load-tests/` or `experiments/`, no raw output in the repository.

## Pending (not run, not assumed)
`check.sh --skip-sbt --skip-terraform`; full `tests/scripts/run.sh`; re-run of `p16-wo7.sh`; per-project `sbt check` totals; forked `-Xmx6g` runMain; real-data runs of GraphChecks, FactoryCounts, BenchmarkGen; H-2; Wave 2 service-engineer logging of `attemptLogLines`.
