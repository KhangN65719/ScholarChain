# WO-F1-R1 report (documentation-engineer), verbatim as received 2026-11-20

---

STATUS: COMPLETE for the WO-F1-R1 scope. The deferred D03, D12, D14 and D19 are unchanged, as ordered.

OWNER: documentation-engineer
WORK ORDER: WO-F1-R1 (repair round for F1 B1 to B4 and B6)

**FILES MODIFIED**

`/Users/winnguyen/ScholarChain/README.md`
- Line 16: Phase 17 archive files were in many cases condensed by the orchestrator, as each file's header states, and the human accepted this under P17-35.
- Line 46: the "demo session" wording now says no live AWS session runs in Phase 17 (P17-33).
- Line 82: period added after "1427".
- Line 189: the demo-session deadline setting is resolved by P17-25 and P17-26, and per P17-33 no deploy with it is planned.
- Lines 218 and 219: links to `reports/final-report.md` and `docs/demo-script.md`, both committed in `dc15299`.
- D17: link to the demo script, status "present".
- D18: link to the final report, status "present".
- D20: the "after cleanup" inventory falls after the deadline (mid-December 2026). At submission none is recorded unless demo item 22 is run. Status stays PENDING.

`/Users/winnguyen/ScholarChain/reports/final-report.md`
- Section 5 (:102): P1 PASS, P1b PASS, P2 PASS, P3 PARTIAL and P4 PASS, with the P3 gap stated and the full block-then-pass loop deferred to the video (demo item 5).
- Section 15 (:180): H-8 part 1 run cited (exit 0, 1370 s, groups 49, 389, 331, 473, 501 with 1 skipped, 170, 260, 99, 1427).
- Section 15 (:184): "NOT REPLAYED until H-8" replaced by a statement that the H-8 records did not cover these commands.
- Section 19 (:239): H-8 part 2 PARTIAL restated, including the prefix difference, the 11-test vs 8-test comparison and the process deviation.
- Section 19 (:241): the final review and test run are WO-F1 and WO-F2. WO-F1 is REQUEST CHANGES and WO-F2 is PENDING.
- Section 14 (:176): the Terraform validate and plan results now point to WO-F3 instead of H-8.
- Section 7 (:116): aligned with README :97. The suite passed inside the H-8 part 1 `sbt check`, which is indirect evidence only.
- Section 8 (:132): now cites "the A01 item".
- Sections 4 and 19 (:92, :217, :251, :253): the by-source list extended and the optional live session text replaced by P17-33.
- Section 22 (:283), section 23 (:297), section 25 (:319, :324, :325, :329, :330): H-8 results restated, live-session wording removed, demo script linked, inventory after the deadline.
- Section 4: M12 (P17-30) and M13 (P17-31 item 1) added to the manual-edit table, and the sentence at :69 updated.
- Section 4 (:92): P17-30, 32, 33 and 35 added under human, P17-31 under orchestrator, and P17-34 under both. P17-34 is explained as an orchestrator edit to an orchestrator-owned archive, not an M entry.

`/Users/winnguyen/ScholarChain/docs/demo-script.md`
- Line 3, line 10 and the section 1 preamble (:43): the optional live path is removed.
- Line 19: the final report and demo script are committed in `dc15299`.
- Line 24: P1, P1b and P3 PARTIAL recorded.
- Item 4 (:120): P1 PASS and P1b PASS recorded with the recorded refusal messages.
- Item 5: P3 PARTIAL added, and the full loop is to be shown in the video.
- Item 11 and its table rows (:62, :416): ARCHIVED only. Path (i), its seven steps and its risks paragraph are removed.
- Line 263: no "option (i) session" wording.
- Line 308: H-8 part 2 PARTIAL recorded.
- Lines 323 and 329: stale H-8 references changed to WO-F3 and "human".
- Item 21 (:366): the committed state of the two files is stated.
- Item 22 (:391): the inventory falls after the deadline.
- Line 398: the final report is linked.
- Final checklist (:433): no live AWS session ran.

`/Users/winnguyen/ScholarChain/docs/runbook.md`
- Lines 3, 31 and 181: "NOT REPLAYED until H-8" changed to NOT REPLAYED, with a note of what H-8 covered.

`/Users/winnguyen/ScholarChain/docs/service-limits.md`
- Line 95: cost is now the orchestrator's reading, not a measured cost.
- Line 176: "NOT REPLAYED".

`/Users/winnguyen/ScholarChain/docs/threat-model.md`
- Line 3 and line 134: "NOT REPLAYED until H-8" changed to NOT REPLAYED, with a note of what H-8 covered.

**FINDING STATUS**
- B1 (now items): FIXED.
- B1 (D03, D12, D14): NOT FIXED, deliberately. Deferred to WO-F5 per the work order.
- B2: FIXED.
- B3: FIXED.
- B4: FIXED.
- B5: not in my scope (orchestrator, P17-34).
- B6: FIXED for `README.md:16`.
- P17-33: FIXED in the demo script, README and final report.
- Non-blocking 1 to 6: FIXED. Item 6 includes the :180 H-8 citation as optional.
- Non-blocking 2: only my four files were changed. `modules/hadoop/JOB-CONTRACTS.md` belongs to WO-F1-R1h.
- Non-blocking 7 to 9: not mine.

**VALIDATION**
- Docs only, with no commands run and no sbt.
- Grep checks:
  - No remaining "until H-8" in my files.
  - No remaining B4 or B5 PENDING pointers.
  - No remaining option (i) or Saturday text in the demo script.
  - The three new relative links in the final report resolve to existing files.
- I did not read lines 5, 65-71 and 140-174 of the final report in full.
- No account id was introduced.
- Nothing is addressed to graders.

**RISKS**
- Demo script item 13 and the cost note at its line 43 still say a laptop run against S3 Vectors or Bedrock is optional with its own approval. I read that as outside P17-33, which covers the live Neptune session. Please confirm that reading.
- `docs/api.md:205` still says "(H-8)" in a replay mark. It is outside this write set and is left for a later order.
- Runbook line 3 now says both "until the human's replay (H-8) is archived" and what the H-8 records cover. It is accurate but slightly awkward.

**BLOCKED-ON:** none.
**NEXT GATE:** independent re-review of the changed lines.
