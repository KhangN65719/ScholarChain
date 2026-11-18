# Phase 16 Wave 2 review 4 verdict (docs-only re-review of W2-RB3)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES.** N1 RESOLVED; one new LOW docs contradiction (N2).

## N1: RESOLVED

Every A-H row matches the manifests' `MAX_MINUTES`, `SPEND_CEILING_USD`, `HOURLY_BURN_USD`:

| Id | Manifest lines | Minutes / burn / ceiling | Projected | README row |
|---|---|---|---|---|
| A | a-baseline `:29,:33,:32` | 75 / 0.60 / 0.75 | 0.75 | `:71` ok |
| B | b-burst `:36,:40,:39` | 90 / 1.55 / 2.40 | 2.325 | `:72` ok |
| C | c-embedding-backlog `:35,:39,:38` | 60 / 0.45 / 0.60 | 0.45 | `:73` ok |
| D | d-query-load `:40,:44,:43` | 70 / 0.55 / 0.65 | 0.6417 | `:74` ok (0.642) |
| E | e-retrieval-modes `:34,:38,:37` | 60 / 0.42 / 0.45 | 0.42 | `:75` ok |
| F | f-failure-recovery `:40,:44,:43` | 30 / 0.40 / 0.25 | 0.20 | `:76` ok |
| G | g-retrieval `:54,:58,:57` | 60 / 0.10 / 0.25 | 0.10 | `:77` ok |
| H | h-answers `:64,:68,:67` | 90 / 0.80 / 2.00 | 1.20 | `:78` ok |

Sums `:79`, `:81`: projected 6.087 (A-F 4.787, G+H 1.30), ceilings 7.35 (A-F 5.10, G+H 2.25), labelled "Sum A to H". C cell `:73` matches `experiment-c-embedding-backlog.manifest:21-23`. Line 5 accurate. Line 81 source, formula and arithmetic accurate. Line 83 "(90 and 70 minutes)" correct; B 2.40 < HIGH 4.45, D 0.65 < LOW 2.70.

## New finding

**N2, LOW (docs), owner experiment-engineer.** `experiments/README.md:85` "may exceed the 60 and 90 minute caps of A and B" contradicts the repaired A row (`:71`, 75 minutes; `experiment-a-baseline.manifest:29`). Say 75 and 90, or mark 60 as the cap at decision O1 ("the then 60 (now 75) and 90 minute caps"). No sbt needed.

## Out-of-scope notes (not findings)

- Line 28 ("No manifest uses tier L"): still true.
- Line 30 ("B to F are tier M and stay drafts"; "No limit in any manifest was raised"): stale against current manifests, but the contradiction predates the repair (lines 5, 20); candidate for a later cleanup with line 20.
- Line 83, optional: E's ceiling 0.45 is also below its LOW 0.62.

Next gate: experiment-engineer fixes N2; re-review of `experiments/README.md:85` only.
