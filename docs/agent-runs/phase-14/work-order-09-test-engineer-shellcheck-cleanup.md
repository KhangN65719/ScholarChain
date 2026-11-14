# Work order 09: test-engineer, shellcheck cleanup of tests/scripts/run-bounded-real.sh

Owner: test-engineer (tests/**). Local only: no AWS, no docker, no sbt, no commit, no edits outside tests/**.

Evidence: the human's `shellcheck -x` run (docs/agent-runs/phase-14/checks-4.md) and the real run `RESULT pass=15 fail=0`.

Findings on tests/scripts/run-bounded-real.sh:
- line 40 SC2034 (warning): `CAT="$(type -P cat)"` is unused. Remove it only if it is truly unused.
- line 56 SC1091: `source "$LIB"` cannot be followed. Add a `# shellcheck source=` directive that resolves from the repo root, or `# shellcheck disable=SC1091` with no restating comment.
- lines 82, 168, 459 SC2013: `for p in $(cat ... pids.*)`. Replace with a `while read` loop or add a targeted `disable` if word splitting is intended. Keep behavior identical.
- lines 300, 327 SC2016: `$$`, `$PIDS` and `$l` inside single quotes are intended (the inner shell expands them). Add a targeted `# shellcheck disable=SC2016`.

Rules: change no test behavior, no timing, no case name or count (15 cases must remain). Do not add comments that restate code. Check tests/scripts/README.md says 15 cases if it states a count (report 07 wrongly said 16); fix it if so.

You cannot run bash or shellcheck (guard, decision D11). State that nothing was executed. The human re-runs `bash -n`, `shellcheck -x` and `bash tests/scripts/run-bounded-real.sh` (expect pass=15 fail=0).

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, RISKS, NEXT GATE.
