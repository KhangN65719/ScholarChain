# WO-10-R2: documentation-engineer report (review 26 N1, H-12 correction, advisories)

Author: documentation-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `docs/evaluation.md`; no commands.

- N1: section 4 adds Phase 15 SMOKE2 truncation at `maxOutputTokens` 2,000 UNVERIFIED (EVALUATION.md 329) and cost rates UNVERIFIED (329, 410, 577; 4.1). Grep of `UNVERIFIED` in EVALUATION.md: 286, 329, 410, 577, all now listed; claims at lines 9 and 194 hold. Lines 410 and 577 read as match snippets only.
- H-12: heading "Unresolved conflict" became "H-12 (B to F regeneration): corrected result" with the P16-47 counts (42 of 44: D 9/9, E 5/5 original title, F2 12/12, C 10/10, B 6/8; B's two differ only in input sha256 and source path from the redacted copy; relayed F2 15 and C 12 were miscounts corrected by the human); links EVALUATION.md 7.3; old conflict text kept, marked superseded; "I state the conflict as unresolved" became "at the time ... P16-47 has since settled the counts"; line 137 now 42 of 44; section 6 item 7 asks only for the sample-pipeline checksum replay and notes EVALUATION.md 7.3 needs updating; H-12 was not in the missing-data table.
- Advisories: negative result item 3 cites the `h4-diagnostic-2` row of `human-commands.md`; header (line 3) names R1 and R2 and links checked by review 26; appendix adds an R1/R2 note and excepts P16-47 from "did not read decisions.md".

Unverified: the `human-commands.md` row not opened (cited as review 26 describes it); P16-47 counts not checked against the compare log; the author expected EVALUATION.md 7.3 and section 8 still to say unresolved (outside its write set).
