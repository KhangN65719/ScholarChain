# Work order 07: test-engineer, run_bounded with real processes (Phase 13 required item S1)

Owner: test-engineer (tests/**). Local only: no AWS, no credentials, no docker, no sbt. No edits outside tests/**, no commit.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/{ratification.md (S1, F1, F2), decisions.md, review-round-3.md (S1)}, scripts/lib.sh (read-only: `run_bounded`, the wall-clock marker, the 124 return, the 500 ms grace period, STOPPED_WALL_CLOCK vs COMPLETED), tests/scripts/run.sh and README.md (how the stubbed harness is built, for style only).

Context: the 682-case harness stubs aws, terraform, docker, curl and sbt, so `run_bounded` has only ever been exercised with stubs. Phase 13 requires it to be run with real processes, including the S1 path: a run that is cut off ends as STOPPED_WALL_CLOCK and never COMPLETED, and `run_bounded` returns 124 whenever the wall-clock marker exists.

Task: create tests/scripts/run-bounded-real.sh (a separate file; do not make the stubbed harness depend on real sleeps). It sources the real scripts/lib.sh and uses only local processes (`sleep`, `sh -c`, `true`, `false`), a temp directory under $TMPDIR, and short bounds (seconds, not minutes). Cases, each printing `CASE <name> PASS|FAIL <reason>` and a final `RESULT pass=N fail=M`, exit nonzero on any fail:
1. Command finishes in time with 0: returns 0, no marker.
2. Command exits nonzero in time: returns that code, no marker.
3. Command exceeds its own step timeout: killed within timeout plus the grace period, return code as documented.
4. Wall-clock cutoff while the command is running: returns 124, marker exists, the run's final state is STOPPED_WALL_CLOCK, never COMPLETED.
5. S1 specifically: the command exits 0 at the same moment or just after the marker exists; the result must still be 124 and not COMPLETED.
6. A child process that ignores SIGTERM and one that forks a grandchild: the process group is gone after the grace period (check with `kill -0` on recorded PIDs; nothing is left running).
7. No stray processes from the test remain at exit (the script verifies its recorded PIDs).
Use the actual function signatures from lib.sh; do not copy the function into the test. If a case cannot be built against the real signature, report why instead of changing lib.sh. If you find a lib.sh defect, do not fix it: report `BLOCKED-ON` infra-engineer with the evidence.

Keep total runtime under about 90 seconds, avoid flaky timing (generous margins, poll with a deadline), never write inside the repository, never use `sudo` or touch other users' processes.

Add a short README paragraph in tests/scripts/README.md naming the file and how the human runs it:
`bash tests/scripts/run-bounded-real.sh`.

You cannot run it (bash is blocked for agents, Phase 13 D11). State expected results per case and say plainly that none were observed. The human runs it with `!`, plus `bash -n` and `shellcheck -x`.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
