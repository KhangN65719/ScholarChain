# Report 07: test-engineer, run_bounded with real processes (condensed by the orchestrator; author: test-engineer)

STATUS: COMPLETE (written, never executed). No result observed.

Created: tests/scripts/run-bounded-real.sh. Modified: tests/scripts/README.md (one paragraph, run with `bash tests/scripts/run-bounded-real.sh`). Sources the real scripts/lib.sh; local processes only; TMPDIR points at the test's own temp dir; nothing written inside the repository.

Expected on a correct lib.sh: `RESULT pass=16 fail=0`, exit 0, about 30 to 40 s (unobserved). Cases: 1/1b true returns 0 and stdin reaches the command; 2/2b false returns 1, exit 7 returns 7; 3 `exec sleep 30` at a 1 s limit returns 124 in 1 to 4 s; 4 cutoff returns 124, marker seen, EVIDENCE line, STOPPED_WALL_CLOCK mapping never COMPLETED; 4b run-experiment.sh still holds the three mapping lines; 5a (S1 core) command traps TERM, confirms the marker exists, exits 0, result must be 124; 5b command ignores TERM and exits 0 after the marker and before KILL, result 124; 5c three real races at the limit accept 0 or 124 but 124 needs the EVIDENCE line; 6a child ignoring TERM needs KILL (124 after 5 to 15 s, nothing left); 6b/6c stray grandchild gives 0, SC_RB_STRAY=1, SC_RB_LEFT=0; 6d cutoff with grandchild kills both; 7 every recorded pid and group gone, no sc-rb.* marker left.

FINDING (infra-engineer, for the repair batch): the Phase 13 ratification (F2) and the work order say the shared grace period is 500 ms. scripts/lib.sh implements a 5 s TERM-to-KILL window (watchdog `sleep 5`; 0.5 s is only the polling step in `sc_group_stop`). Either the documentation or the constant is wrong. The README also says "TERM, then KILL 5 s later", so the ratification wording may be the stale one. No behavioral defect found.

Risks: integer-second timing margins under load; zombies read as alive to `kill -0` (5 s poll allowance); POSIX-mode sh on macOS unobserved. Not covered: Ctrl-C and TERM forwarding (S2, S3), setsid escapes, real docker login/build.
