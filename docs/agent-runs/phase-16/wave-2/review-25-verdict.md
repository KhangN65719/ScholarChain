# Phase 16 review 25 verdict (WO-8 final review: EVALUATION.md, docs/evaluation.md, tests/scripts/README.md note)

Author: code-reviewer (Opus). Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES.** Course gates mostly hold: interpretation separated from facts, all nine required threats present, negative results kept. One factual misreading plus precision and completeness fixes.

## H-12, settled from the files
- `phase16-h12-compare.log`: 49 lines, 46 SAME, 3 DIFF. D 9/9 SAME (1-9); E other title 4 SAME, 1 DIFF `latency.svg` (10-14); F2 12/12 SAME (2 verdict, 6 queue, 4 metrics; 15-26); C 10/10 SAME (6 queue, 4 metrics; 27-36); E title-matched 5/5 SAME (37-41, no directory prefix); B 6 SAME, 2 DIFF `derived.json`, `summary.md` (42-49).
- `phase16-h12-regen.log`: 8 `DERIVE_COMPLETE` (D 2, E 1, F2 3, C 2) then `EXIT=0`; none for B or the title-matched E rerun.
- `SCD/phase16-h12-regen`: 49 files in 10 output directories.
- Committed derived files in scope: D 9, E 5, F2 12, C 10, B 8 = 44; with the title-matched E run, **42 of 44 identical**.
- No file supports F2 15/15 or C 12/12. Reviewer's reconstruction (arithmetic, not a recorded fact): relayed 47 = 49 lines minus B's 2 DIFFs (other-title E DIFF not counted); relayed F2 + C = 27 exceeds the log's 22 by 5, the other-title E run's line count. Human to confirm.

## experiments/EVALUATION.md (experiment-engineer)
1. **Major, gated on the human.** `:932`, `:948`: replace the unresolved count with the figures above once the human corrects the H-12 row; "both 49 and the relay's figures need … correction" is wrong (log and listing confirm 49).
2. **Minor.** `:932`: `e-latency-titlematch` is inferred from the listing, not the log; mark inferred.
3. **Minor.** `:932`: the title-matched E rerun also lacks a `DERIVE_COMPLETE` line; name both.
4. **Advisory.** `:948`: move the B-F and clean-checkout facts out of the "NOT DERIVED" bullet.

## docs/evaluation.md (documentation-engineer)
5. **Major.** `:230`: "Raising the limit to 4,096 fixed the non-ranking question" is wrong: EVALUATION.md 5.2/5.3 show it was accepted at 2,000 (1,990 tokens, `end_turn`); all 3 `max_tokens` stops at 2,000 were the ranking question; the raise gave headroom (2,559) and fixed no failure.
6. **Minor.** `:77`: "section 5.3 below" does not exist; the history is section 5 item 4.
7. **Minor.** `:159`: "One human" did all five tasks contradicts 6.4.6 (identity across tasks not stated): each task had a single human; same person not stated.
8. **Minor.** `:225`: "15 of the 22" mixes denominators; of the 13 false abstentions in 6.2.4, 11 identity-unresolved (1+4+4+2), 1 evidence-cap, 1 researcher-count: state 11 of 13, marked counted.
9. **Minor.** `:235`: drop "the one place" where verifier and audit disagree (only aggregates; 267 verdicts vs 199 claims unexplained). The reading that both contradicted claims were VERIFIED holds (`AnswerAudit.scala:604-607`, `:672`).
10. **Minor.** `:151`: free-form vs ranking: numbers correct (0.4689 n 6 vs 1.0000 n 10) but selective (free-form near coauthor-lookup 0.5000, above shortest-path and community-component 0); nDCG used as a stand-in for planner accuracy (state the confound); ranking and temporal strata are entirely template questions, not "mostly".
11. **Minor.** `:227`: verifier window-year flag absent (searched EVALUATION.md, `docs/agent-runs/phase-16/**`, `experiments/**/*.md`, `h-audit-2`, `modules/rag/src/main`); only hits: EVALUATION.md:53 (H-8 year-in-window, 10/10), `decisions.md:28` P16-23 (planner window miss is a measured result), `w2-rb11` report line 23 (unit-test failure fixed), `w2-tr2` report line 11 (fixture numbers). Remove the item.
12. **Minor.** `:196-215` missing-data table omits: F1 INVALID, not derived (4.2); fused mode skipped the vector stage on 17 requests, cause UNVERIFIED (4.2; also missing from negative results; UNVERIFIED not a tracked label); D abstention reasons not captured (4.5); which two H replies the claim cap rejected (6.2.2); Haiku temperature not read (6.2.6); the 5 non-DBLP traced edges not checked (1.2).
13. **Minor.** `:105`: "lower bounds on uncertainty [4.5]" is the author's inference; mark as a reading.
14. **Advisory.** `:26` 0.917 is a sensitivity figure under a proxy, not a bound; `:100` "retrieval stage costs" cites end-to-end p50s, and "about 330 to 340" excludes fused 342.416; `:67` "n from 4 to 10" holds for all-questions only (vector-available free-form n 3); `:78` list omits publications (6) and groups (9); `:24` cite the Wilson interval [0.952, 1.000] (6.5.1) over the degenerate bootstrap.

## tests/scripts/README.md:151 (test-engineer)
Accurate: `Model.scala:512-515`; `ClaimVerificationAgent` `Verification.scala:103`; example string equals `Phase9ModelSuite.scala:613`; descriptive, addressed to no grader or model.
15. **Minor.** Omits suites containing the string: `modules/rag/src/test` `EvidenceBuilderSuite`, `ModelPortSuite`, `PlannerSuite`, `RepairRound1Suite` (3 hits), `WorkflowSuite`; `tests/adversarial` `Phase11CarryOverSuite` and fixture `Phase9World.scala`.
16. **Minor.** "The case that obeys the instruction is Phase9ModelSuite": `ADVERSARIAL_COVERAGE.md` row 12 says `Phase11FaultSuite` also uses an obedient model.

## Redaction Grep `[0-9]{12,}|scholarchain-course-|arn:`
EVALUATION.md 0; docs/evaluation.md 0; tests/scripts/README.md 1 (line 83, `123456789012`, an existing test-fixture placeholder outside the new note; human to confirm).

Needs the human: correct the H-12 row; say what "verifier window-year flag" referred to (possibly P16-23); confirm the line 83 placeholder. Orchestrator: confirm the changes are docs-only (no new `sbt check`). Next: one batched repair round (1-4 experiment-engineer after the human's correction; 5-14 documentation-engineer; 15-16 test-engineer); re-review of changed items only.
