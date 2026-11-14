# Work order 10: infra-engineer, grace-period wording (F2)

Owner: infra-engineer (scripts/**, infra/terraform/**). Local only: no AWS, no terraform apply, no commit, no edits outside your paths.

Finding (report 07): the Phase 13 ratification (F2) and its work order say the shared TERM-to-KILL grace period is 500 ms. scripts/lib.sh implements a 5 s window (watchdog `sleep 5`); 0.5 s is only the polling step in `sc_group_stop`. scripts/README.md already says "TERM, then KILL 5 s later". The real-process test (15/15 pass) observed the 5 s behavior.

Decision (orchestrator, D9, for human ratification): the code is the source of truth and the constant is NOT changed. Docs and comments in your paths must say 5 s.

Task:
1. Search scripts/** and infra/terraform/** for "500 ms", "0.5 s", "grace" and make each statement match lib.sh (5 s TERM-to-KILL window, 0.5 s polling step). Do not change `sleep 5`, `sc_group_stop` or any logic.
2. If lib.sh comments or usage text give a different number, correct the text only.
3. Do not edit docs/agent-runs/** (ratification records belong to the human). Report the exact file and line of every stale statement outside your paths so the orchestrator can record the correction.
4. If you change any script text, list files for the human's `bash -n` and `shellcheck -x`, and say the harness `bash tests/scripts/run.sh` must be re-run (a SCRIPT_DIGEST changes, so no earlier approval record is valid anyway).

You cannot run bash, shellcheck or terraform (guard, decision D11). Nothing may be reported as executed.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, STALE STATEMENTS OUTSIDE MY PATHS, COMMANDS RUN, VALIDATION RESULTS, RISKS, NEXT GATE.
