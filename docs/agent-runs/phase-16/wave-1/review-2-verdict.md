# Phase 16 Wave 1 re-review 2 (code-reviewer, Opus), received 2026-11-18, recorded by the orchestrator

Reviewer: code-reviewer (fresh context, read-only). Scope: items changed by repair batch 1 (RB1-A to RB1-D).

**Verdict: REQUEST CHANGES.** No HIGH, no MEDIUM; 9 LOW (A to I, C folded into B).

## Review-1 findings
RESOLVED: 1, 2, 3, 4, 5, 6, 10, 11, 12, 13, 14 (test deferred to slice 2), 15, 16, 17, 18. PARTIAL: 7 (gaps in G), 9 (dedup key in B). PENDING: 8 (per-project totals of a full run). NOT RESOLVED: 19 (A).

## New findings (LOW)
- A. `tests/scripts/README.md:141`: the "R3" paragraph describes RB1-B; R3's `run.sh` work (`5.smoke.question-{omit,empty}.*`, four extra cases, `SM_Q_MODE`) has no README text. Owner: test-engineer.
- B. `FactoryCounts.scala:202-215` (used `:488, 516, 527`): dedup key (phase + first id) can merge different events (finding ids restart per review; `WO-n` shared). Key on `d.scope` and the review file for finding ids; summary lines (`:753-754`) say counts are approximate; reword `proseCounterBasis` (`:705-708`) to match `:220-221` (the state-changing count is the lower bound). Owner: experiment-engineer.
- D. `BenchmarkGen.scala:291`, `BenchModel.scala:304-306`, `experiments/benchmark/README.md:27`: researcher lookup takes the default `retrieve` branch (`Workflow.scala:569-584`), metrics only, no coauthors; the 25-coauthor cap is not a runtime limit and graph-mode recall on this stratum is capped. State as a threat to validity. Owner: experiment-engineer.
- E. `BenchmarkGen.scala:415-416`, `README.md:29`: ties are broken deterministically by researcher id (`Ports.scala:66-67`), not arbitrarily; correct the stated reason. Owner: experiment-engineer.
- F. `VectorCoverage.scala:121-137`, `README.md:63`: bytes and rows reported but not compared; mismatches only in the manifest, nothing on the command line (`BenchmarkGen.scala:1252-1255`). Compare them and print the match flags. Owner: experiment-engineer.
- G. `P16LabelRulesSuite`: `:217-242` hubs of 20 and 25 should be labelled (generator accepts 2-25); `:582-634` no check of `expectedCodes` against the committed mapping; `:331-342` ranking label matches any metric's oracle, not the named metric; `:371` loose bound `labels.size < 5`. Owner: test-engineer.
- H. `tests/scripts/p16-wo7.sh:183-184`: case name says "nothing prints the body file" but checks only `RESPONSE_HEAD` absence; add a static check that `$BODY_OUT` is used only by `json_field`, `wc -c`, `curl -o`. Owner: test-engineer.
- I. `LocalExportStore.scala:32-39`: `emptyObjects` entries not checked against the root (`../x` passes construction, listed by `listSizes` `:114-116`, refused by `get`, so `list()` throws). Refuse in the constructor. Owner: storage-engineer.

## Checked with no finding
Runtime limits (`Retrieval.scala:70`, `Ports.scala:64-71`); P16-10 tiers; `P16LabelRulesSuite` computes expectations from its own fixtures (does not copy the generator); GraphChecks INCOMPLETE path; no answer text in `smoke-test.sh`; authority (from git status and reports).

Out of scope, noted: `scripts/README.md:245` still says "UNVERIFIED until Phase 14" (not part of the repair).

## Pending
`p16-wo7.sh`; full `run.sh`; `check.sh --skip-sbt --skip-terraform`; `grep -n 'RESPONSE_HEAD\|bounded-query-answer' tests/scripts/run.sh`; per-project totals of a full `sbt check`; slow suites; real-data runs; slice-2 test for finding 14.
