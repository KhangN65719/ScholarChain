# P17-41 cleanup report (documentation-engineer), verbatim as received 2026-11-20

Orchestrator name check (P17-41), 2026-11-20:
- I read `docs/runbook.md:31` and `reports/final-report.md:94-96` after the edit. The 2026-11-20 run cites only `phase-17/human-commands.md`.
- The Phase 16 count of 1949/0 is supported: `docs/agent-runs/phase-16/human-gate.md:7` cites `ratification.md` for "final script harness 1949 passed, 0 failed".
- The parenthetical at `:94` has no internal full stop, and the decision IDs and groups are unchanged. A blank line is restored before `## 5`.
- Result: PASS.
- Not covered: P17-40 to P17-42, decided after this list was written, are not in the final report's by-source list. They can go into commit Z.

---

STATUS: COMPLETE

OWNER: documentation-engineer

WORK ORDER: Phase 17 final gate, P17-41 (N1, N2, N3 from `review-F5-rereview-1.md`, "Optional cleanups" and non-blocking item 8)

FILES MODIFIED:

1. `/Users/winnguyen/ScholarChain/docs/runbook.md`, line 31 (N1).
   - Before: `... section "Phase 17 final gate, F3"; `docs/agent-runs/phase-16/ratification.md`, Gates). The hook harness ...`
   - After: `... section "Phase 17 final gate, F3"). The Phase 16 gate recorded the same count, 1949/0 (`docs/agent-runs/phase-16/ratification.md`, Gates). The hook harness ...`
   - The 2026-11-20 run now cites only `docs/agent-runs/phase-17/human-commands.md`. The Phase 16 file is cited only for the Phase 16 count.

2. `/Users/winnguyen/ScholarChain/reports/final-report.md`, line 94 (N3).
   - Before: `... appended a dated, attributed note. This is an edit by the orchestrator ... so it is not an M entry. P17-36 extended the redaction ...`
   - After: `... appended a dated, attributed note; this is an edit by the orchestrator ... so it is not an M entry; P17-36 extended the redaction ...`
   - The parenthetical now has no internal full stop. All decision IDs and their source groupings are unchanged.

3. `/Users/winnguyen/ScholarChain/reports/final-report.md`, lines 94-95 (N2).
   - Before: `... with the orchestrator stopping the WO-F2 agent).` followed directly by `## 5. Authority and ownership design`.
   - After: the same two lines with one blank line between them.

BLOCKED-ON: none.

Commands run: none, as instructed. I checked the edits only through the tool results, with no read-back. The 1949/0 Phase 16 count comes from the work order. I did not open `phase-16/ratification.md` to confirm it. Both docs are still uncommitted modifications. The next gate is the orchestrator's re-review record, then human acceptance.
