# Review B12c, re-review 1 (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: REQUEST CHANGES (owner: documentation-engineer).

## Findings 1 to 20 and the P17-26/27 addenda
All closed or accepted, each checked against its source. Two exceptions:
- **Must fix 1 (finding 3 not fully closed):**
  - `final-report.md:68` lists P17-18 as "both", but its source is the orchestrator (`decisions.md:23`); the human confirmed it in P17-19.
  - P17-28 (human) is missing from the human list.
- **Minor 2:** `:61` M6 "the human's commit `38421c6`" should be reworded ("human-side commit") to agree with P17-27's commit practice.
- **Minor 3:** `:59` M4 says "no independent review recorded", which is now false; cite this re-review.
- **Advisory 4:** the M-ids jump from M8 to M10; mark "(formerly M9)" at `:50`.

## Commit `c830b90` (P17-18, M4): content APPROVED
`EVALUATION.md:949` reads "D 9 of 9, E 5 of 5, F2 12 of 12, C 10 of 10, B 6 of 8". This equals the earlier fractions and agrees with `:932` (42 of 44).

The reviewer asked for diff evidence for "no other change". **Orchestrator evidence (2026-11-19):**
- `git show --stat c830b90` gives `c830b9076972fb3870b351e56c75d62727b02319`, "Write the regeneration counts in words in EVALUATION.md", `experiments/EVALUATION.md | 2 +-`, `1 file changed, 1 insertion(+), 1 deletion(-)`.
- The full `git show c830b90 -- experiments/EVALUATION.md` (read earlier and quoted in P17-18) shows one changed line, 949, with only the five fractions written in words.

So "no other change" is shown by git. M4's independent review is closed by this verdict plus the diff. The missing "Phase 17" label in the commit message stays disclosed (V1 G03).
