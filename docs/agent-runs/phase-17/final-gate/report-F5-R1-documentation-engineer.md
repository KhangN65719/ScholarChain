# WO-F5-R1 report (documentation-engineer), verbatim as received 2026-11-20

---

STATUS: COMPLETE. I edited four docs and ran no commands, so nothing was replayed or checked after the edits.

OWNER: documentation-engineer. WORK ORDER: WO-F5-R1 (documentation part).

FILES MODIFIED (matched on text):
- `/Users/winnguyen/ScholarChain/README.md`:
  - OBSERVED list under "Build, format, compile, test and package commands": new entry for the gate `sbt clean check` on a fresh clone of `264f24b`. It records exit 0, nine groups all Failed 0 and Errors 0, one skip, `[success]` 1360 s, with P17-39 cited.
  - Same list: the `run.sh` entry now holds the F3 result, and the `hooks-guard.sh` entry now holds the F3 `114/0` run. The "no run on the committed tree is recorded" text is gone.
  - Section "Script test harness": last recorded result updated to the F3 run.
  - Reproducing-the-agentic-workflow dangerous-command probe bullet: same `hooks-guard.sh` reword.
- `/Users/winnguyen/ScholarChain/reports/final-report.md`:
  - Class 4 paragraph: full stop mid-parenthetical fixed, closing P17-37 and P17-39 sentence removed.
  - Hook-fires paragraph: "no run on the committed tree is recorded" reworded.
  - Operations script harness paragraph: updated to F3 results.
  - "Final gate outcome" paragraph: `run.sh` PENDING replaced.
  - "Phase 17 demonstration, video and final-gate results" final-gate bullet: `run.sh` PENDING replaced.
- `/Users/winnguyen/ScholarChain/docs/runbook.md`: the script-harness replay paragraph now cites the F3 results.
- `/Users/winnguyen/ScholarChain/docs/demo-script.md`: section 1.4 billing sentence adds 10.44 USD at 12:58 on 2026-11-20 (P17-33), noted as account-level readings subject to Billing lag.

ITEMS:
- B2, README gate record and F3 `hooks-guard.sh`: FIXED.
- B2, reword of `README.md:84` and `final-report.md:104`: FIXED. The new text says no `.claude/` file changed after `5157e0e`.
- B2, optional `final-report.md:184` and `runbook.md:31`: FIXED.
- Harness result (2): FIXED in the README, final report and runbook, with the wording you gave and the F3 section cited. `docs/demo-script.md` has no `run.sh` PENDING text, so nothing changed there.
- Item 7 (demo script billing): FIXED.
- Item 8 (final report closing sentence and full stop): FIXED.

VALIDATION: none run. The edits are checked only by reading the cited sources: `human-commands.md` F3 section, `decisions.md` and the review verdict.

RISKS:
- The README "Last recorded result" and demo items still say "recorded" for results I did not replay. Their evidence is the human's relay in `human-commands.md`.
- The README "Script test harness" section still has no F3 detail beyond the one-line result.
- I kept the P17-39 detail to what you listed. The out-of-memory first attempt (P17-38) is left only where it already appeared in the final report.

BLOCKED-ON: none. Pending gates, owned by others: the human's re-review of these lines, and the final tag, V1 to V3 and demo results.
