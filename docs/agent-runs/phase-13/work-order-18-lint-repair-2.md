# Work order 18: review repair round 2, shellcheck warnings only (two delegated runs, one per owner)

Same limits as before: no behavior change, no case weakened or deleted, no edits outside your own paths, no new tools, no sbt, no commit, read and edit only (guard, D11), write shellcheck-clean by careful reading. Read first: docs/agent-runs/phase-13/checks-4.md.

## infra-engineer (scripts/lib.sh only)
- :562 SC2034 `i` unused in the counting loop `for i in 1 2 3 4 5 6 7 8 9 10`: use `_` as the loop variable (same iterations, same behavior).
- :616 `SC_RB_STRAY` and :619 `SC_RB_LEFT` SC2034: read after `run_bounded` by run-experiment.sh (check where, by reading). Add `# shellcheck disable=SC2034` on its own line above each assignment with the reason on the line before the directive (as done for `SC_SHIFT`), or restructure only if a restructure is clearly behavior-preserving. Confirm in the report that the variables really are read, and by which line.
Reread the whole of `run_bounded` once more for anything else shellcheck would flag. SCRIPT_DIGEST of every script changes again (no approval records exist).

## test-engineer (tests/scripts/run.sh only)
- :1174 and :1178 SC2054: the argument `--stop-on first-failure,dlq-nonempty` written with a comma inside an array. Use the same fix as the earlier `stop_on` variable at the old :517 (a quoted variable passed as `--stop-on "$stop_on"`), keeping the argument value identical. Check for any other new array line with the same pattern and fix it.
The human reruns `bash -n`, `shellcheck -x`, `bash tests/scripts/run.sh` (expect `RESULT pass=570 fail=0` unchanged).

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS (not run), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
