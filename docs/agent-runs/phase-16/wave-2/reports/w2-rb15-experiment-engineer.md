# W2-RB15: experiment-engineer report (review 20 repairs, EVALUATION.md)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md` written. Original line numbers (the 149 edit adds a sentence on the same line).

- 147 (finding 2): "categories sampled with seed 16" now attributed to the human's relay row in `human-commands.md` (H-9, 2026-11-19), not the sheet.
- 149 (finding 2): sentence added: BLOCKED-ON events 6 and repair files 5 match `factory-counts.json` `blockedOnEventsByPhase` "phase-14" (line 262) and `repairFilesByScope` "phase-14" (line 168), both read; mentions 71, refusals 9, rulings 26 have no per-phase field (totals only: 264 at 265, 153 and 150 at 454-455, 135 at 453), so they rest on the sheet.
- 169 (finding 1): "rows including Phase 14 pairs are affected" and "was not checked" removed; cites `factory-counts.json` 462-464 (86, 74, 12), totals over phases 1-15, no per-phase field, so the 12-for-Phase-14 rests on the sheet and the human; sum 8+6+3+1+1+4+2+4+4+4+5+5+3+6+4+14 = 74 = `firstPassPairsReviewed`, so the 12 unreviewed pairs are in no 2.2 row (excluded, not miscounted); "not recomputed" kept; which pairs and agents NOT DERIVED.
- 924 (finding 1): "rows affected" clause replaced by the same facts in short form, pointer to 2.3a.
- 927 (advisory 3): review 20's Grep (12-digit, bucket-prefix, ARN-prefix) 0 hits on the W2-DOC5 revision, separate from the author's scan.

Unverified: 2.2 table (119-134) sums to 74 as read; JSON line numbers as read now; no re-scan for the redaction patterns (shell grep refused; reviewer's Grep needed). No sbt, AWS, Python or commit; lines 228-231 untouched.
