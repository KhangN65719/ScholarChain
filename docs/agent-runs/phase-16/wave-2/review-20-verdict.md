# Phase 16 review 20 verdict (W2-DOC5, EVALUATION.md H-8 and H-9)

Author: code-reviewer. Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES** (two minor, one advisory; owner experiment-engineer). All numbers correct; two wording and attribution points.

Confirmed: line 3; H-8 (line 53) matches `human-commands.md:36`; 45 of 50 recounted (45 lines of `provenance-trace.jsonl` contain `workSource":"dblp"`); windows 6 "all" (sheet rows 1, 2, 4, 6, 7, 10) and 4 "2020-2029" (3, 5, 8, 9); selection rule as the sheet; no contradiction with `plan.md:71` (deviation and limits stated). H-9 (142-173): marks match `human-commands.md:37`; 6+5+5+4 = 20, 5+5+3+3 = 16, 80%; false-positive kinds as relayed (4 of 20); `decisions-d33/34/35.md` exist in `docs/agent-runs/phase-14/`; Q1 a parser limit, Q2 as relayed; no contradiction with `plan.md:75`, `:119-120`. No names, titles or question/answer text in W2-DOC5 text. Section 7 pending (831, 850, 910). No stale H-8/H-9 pending text (925, 926). Sources line 935. Redaction Grep `[0-9]{12,}|scholarchain-course-|arn:`: 0 hits.

Findings:
1. **Minor, `EVALUATION.md:169`, `:924`.** `factory-counts.json:462-464`: `firstPassPairs` 86, `firstPassPairsReviewed` 74, `firstPassPairsWithoutReview` 12, a total over phases 1-15 with no per-phase first-pass field, so "Phase 14's 12" rests on the sheet and the human only. The 2.2 denominators sum to 74 (8+6+3+1+1+4+2+4+4+4+5+5+3+6+4+14), the reviewed pairs, so the 12 unreviewed pairs are in no 2.2 row: the missed pairs are excluded from 2.2, not wrongly counted in rows. Repair: state both, attribute the arithmetic; keep "not recomputed" and NOT DERIVED for which pairs or agents.
2. **Minor, `EVALUATION.md:149`, `:147`.** Of the sheet's Phase 14 counts, BLOCKED-ON events 6 and repair files 5 match `factory-counts.json:262` (`blockedOnEventsByPhase`) and `:168` (`repairFilesByScope`); mentions 71, refusals 9, rulings 26 have no per-phase field (JSON totals only: 264, 150/153, 135). Add one sentence on which counts the JSON confirms. Line 147: "categories sampled with seed 16" is from the relay row (`human-commands.md:37`), not the sheet: attribute it so.
3. **Advisory, `EVALUATION.md:927`.** The redaction note names only the W2-DOC3 hand scan and reviews 14-15; this review's Grep (0 hits on W2-DOC5) could be added. Optional.

Out of scope, not a finding: lines 228-231 (section 3.3, earlier order) contain researcher names; W2-DOC5 did not touch them.

Assumption: one trace line per edge. Pending: clean-checkout build (7.1), B-F regeneration (H-12), the `.html` H-8 sheet not read. Next: one repair round; re-review of lines 147, 149, 169, 924 and newly touched lines.
