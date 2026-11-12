# Human `sbt clean check`, Phase 13 gate (2026-11-11)

Command (human, `!` prefix, machine plugged in and kept awake): `cd /Users/winnguyen/ScholarChain && set -o pipefail && SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false caffeinate -dims sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-13-clean-check-1.log | tail -n 60; echo "exit: $?"`

Observed (tail of the run; log phase-13-clean-check-1.log): `[info] Passed: Total 81, Failed 0, Errors 0, Passed 81`; `[info] Passed: Total 1098, Failed 0, Errors 0, Passed 1098`; `[success] Total time: 1179 s (0:19:39.0), completed Nov 11, 2026, 8:44:14 PM`; `exit: 0`. Same totals as the Phase 12 second clean check (81 and 1098). No exit 56.

Scope: this run covers every Scala test (including the slow Spark suites) plus scalafmtCheck and compile, over the sources as of 8:24 to 8:44 PM. It does NOT run `bash tests/scripts/run.sh`, `shellcheck` or `bash -n` (those are checks-1 to checks-6; the last, checks-6.md, is 570 pass, 0 fail), or Terraform (last checks-1.md).

Phase 13 changed one Scala area (the two F2 test files, report 02), so the totals are unchanged from Phase 12.
