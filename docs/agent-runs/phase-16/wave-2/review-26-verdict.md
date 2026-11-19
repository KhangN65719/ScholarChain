# Phase 16 review 26 verdict (re-review of round-1 repairs of review 25)

Author: code-reviewer (Opus). Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES** (one Minor in a repaired passage; all else holds).

## docs/evaluation.md (documentation-engineer)
- **N1, Minor (new, from the finding 12 repair).** Lines 9 and 194 claim section 4 lists every UNVERIFIED item of EVALUATION.md; the table has one UNVERIFIED row (line 205, fused skip, EVALUATION.md:286). Missing: Phase 15 SMOKE2 truncation at `maxOutputTokens` 2,000 UNVERIFIED (EVALUATION.md:329); cost rates UNVERIFIED (EVALUATION.md:329, :410, :577; docs/evaluation.md:187 says "unverified rates" but section 4 omits it). Fix: add both rows or narrow the claim.
- Findings 5-14 hold: 5 (234-235) matches 5.2/5.3, the derived claim holds via `human-commands.md:28` (h4-diagnostic-2 row: Q-RK-01 "same as at 2000", Q-RL-01 "1990 at the 2000 limit"; `:30` gives only `end_turn:1,max_tokens:3`), advisory: cite that row; 6 (77) correct; 7 (159) matches 3.5 and 6.4.6:751; 8 (231) 11 of 13 correct; 9 (240) correct; 10 (151) matches 6.1.3:455-461, "entirely template" supported; 11 window-year gone; renumbered items 1-14 with all cross-references resolving (77, 188, 260, 244, 261); 12 six rows match 4.2 (:287, :286), 4.5 (:320), 6.2.2 (:573), 6.2.6 (:605), 1.2 (:53), item 14 cites 4.2; 13 (105) labelled; 14 all five hold (26, 100, 67, 78, 24). All 10 relative links exist; every cited section number exists.
- Advisory: header (line 3) and appendix (266-267) not updated for R1.

## tests/scripts/README.md:151-158 (test-engineer)
- Finding 16 holds (`ADVERSARIAL_COVERAGE.md:33`; obedient model `Phase11FaultSuite.scala:363-375`; `Phase9ModelSuite.scala:635`). Finding 15 holds: all 7 files contain injection text (case-insensitive Grep `ignore all previous instructions|InjectionScreen`): EvidenceBuilderSuite 1 (268), ModelPortSuite 4, PlannerSuite 1 (138), RepairRound1Suite 3, WorkflowSuite 1 (480), Phase11CarryOverSuite 1 (130), Phase9World 2 (329, 336). Descriptive only.
- Advisory: "bodies were not re-read" could be replaced by Grep evidence (owner must run it); "Two suites use an obedient model" is a closed count not checked against rag module suites; suggest "row 12 names two suites ...".

Redaction Grep `[0-9]{12,}|scholarchain-course-|arn:`: docs/evaluation.md 0; tests/scripts/README.md 1 (line 83 placeholder `123456789012`, outside the edit; human to confirm). Out of scope, pending the human: EVALUATION.md H-12 (review 25 findings 1-4) and the docs/evaluation.md:225 H-12 subsection.
