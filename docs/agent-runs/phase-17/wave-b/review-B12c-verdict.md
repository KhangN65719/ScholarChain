# Review B12c verdict on reports/final-report.md (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator; findings kept)

Verdict: REQUEST CHANGES. All findings are for documentation-engineer unless marked.

## Must fix
1. `:213` says "most first-pass work needed some repair", which contradicts `:48` (45 of 74 with no finding; `EVALUATION.md:117-134`). Remove or reverse it, keeping the `:179` caveats.
2. `:160`: the B to F regeneration was run by Claude in chat (`EVALUATION.md:827, 932`, P16-5), not the human; the human corrected the counts (P16-47).
3. `:68`: P17-11, -12, -14, -15, -20, -21 and -23 are orchestrator decisions (decisions.md source column); P17-20 and -21 were later accepted by the human (P17-22). Split the class-4 list.
4. `:42`, `:66`: add Phase 2 to the Codex disclosure (`phase-02/review.md:3`, `validation.md:21`, `ratification.md:13`).
5. `:50`: the class-2 example is wrong. P17-15 sent three findings to test-engineer, each in that role's own files, so it is not a cross-agent repair. Cite a real one or drop the claim.
6. `:215`: add V2 and V3 on `4341dbb` (`human-commands.md:88-89`); the final-commit runs stay PENDING.

## Minor
7. `:150`: the KMS cost wording (`infra/terraform/README.md:57`).
8. `:106`: the bridge formula is at `modules/spark/DESIGN.md:172-178` (participation coefficient, no betweenness). Section 9 must describe it.
9. `:50`: repair round 3 came after the ratification (`orchestrator-update/repairs.md:57`, `ratification.md:6`).
10. `:62`, `:64`: define class 3. M9 is the orchestrator (an agent); M7 mixes execution with editing.
11. `:48`: exclude the code-reviewer 6/6 from the class-1 proxy (`EVALUATION.md:136`), which gives 39 of 68, or note it.
12. `:184`: 351 requests, not questions.
13. `:221`: the 14 USD stop predates Phase 17 (`RESULTS.md:280-282`, `phase-16/decisions.md:20, 44`).
14. `:223`: cite P16-15 and `EVALUATION.md:271` for 2.73.
15. H-8 and H-9 are ambiguous between Phases 16 and 17; add the phase (`:68`, `:152`, `:215`, `:295`).
16. `:279`: the defect was in `h-audit-1`; review 14 found it (`EVALUATION.md:611`).
17. `:94`: "resolved" overstates `phase-04/ratification.md:36` (a remedy); cite Phase 5 or say "remedy".
18. `:187`: keep the "Reading (mine…)" label (`docs/evaluation.md:105`).
19. `:235`: a sentence with no verb.
20. `:112`: 0.85% is from the Phase 6 subset run (`phase-06/ratification.md:10`; `DESIGN.md:201-203`).

## Checked correct
- All 25 sections, in order; not-implemented components stated.
- The ER, G, H, audit, rating, A to F, Phase 14 and billing figures.
- The arithmetic.
- Negative results kept.
- M1 to M6 and M8 sources.
- Class-2 files exist.
- PENDING items clearly marked.
- No identifiers; identity matches the README; links resolve.

## Outside this review
- `c830b90` (P17-18) has no independent review (orchestrator to route).
- **Advisory, done by the orchestrator:** `human-commands.md:90` contradicted the CLOSED H-2 item and is corrected.

## Orchestrator rulings for the repair
- **Finding 10:** class 3 = edits to agent-owned artifacts made outside the subagent workflow by the human, Claude in chat or Codex. M9 (orchestrator repair after review) moves to class 2 with a note. M7 keeps only the path rewrite and `SHA256SUMS` regeneration as an edit; running the derive scripts is execution, not an edit.
- **Finding 5:** use a real cross-agent case from the records, or say class 2 here means a fresh-context repair by the owning specialist after an independent review, and drop "different specialist".
