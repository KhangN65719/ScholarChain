# Review A9 verdict (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: REQUEST CHANGES. The guard, `.mcp.json`, `CLAUDE.md:37` and the harness are correct.
- **F1 (documentation-engineer, HIGH):** at `README.md:9` the F07 and K02 bullets are joined on one line (`...(line 37).- **Fake key in history (K02).**`). Restore the newline.
- **F1b:** report-A8 says a planned-state sentence ends the bullet, but the file does not have it. Orchestrator resolution: the sentence was removed by WO-A8b (orchestrator message after WO-A6 landed). The A8b report is archived in `report-A8b-documentation-engineer.md`. The F1 join most likely came from that edit.
- **F2 (documentation-engineer, MEDIUM):** `docs/threat-model.md:61` still says `.mcp.json` holds `${SCHOLARCHAIN_GITHUB_READ_TOKEN}` (a held Wave B doc).
- **F3 (test-engineer, LOW):** the `tests/scripts/hooks-guard.sh:7-9` comment says only `policy.py guard` runs; sections 6 and 7 also run `guard-dangerous.sh`.
- **F4 (human decision, LOW):** `CLAUDE.md:33` "The project template preserves variable names". No hard conflict (a `${VAR}` reference is kept), but the rename departs from any external template that used the old name. Confirm the wording or ask for a factory edit.
- **Verified:**
  - All 18 examples are refused by `policy.py` for test-engineer, each traced to its line.
  - The interface matches, and no-argument and JSON invocations reach the unchanged `exec`.
  - The array only feeds `printf`.
  - `.mcp.json:7` is exact, and `validate.py` does not check it.
  - The remaining old-name hits are `docs/threat-model.md:61` (F2) and archived phase-17 evidence (not edited).
  - The harness cases match report-A7, with nothing weakened.
- **Expected harness total:** 114 (not 115; the old file had 97, matching the recorded run). Correction: the 97 run was made by Claude in chat (`human-commands.md`).
- **Pending:**
  - `bash -n` on bash 3.2
  - `validate-agent-files.sh`
  - `--list-categories` (18 lines, exit 0)
  - `hooks-guard.sh` (`RESULT pass=114 fail=0`)
  - probes P1 and P1b in a fresh session
  - V1 re-run
