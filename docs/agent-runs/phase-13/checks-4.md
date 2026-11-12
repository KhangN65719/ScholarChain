# Human check run 4 (2026-11-11): after review repair round 1 (work orders 15 to 17)

Command: `bash -n scripts/*.sh tests/scripts/run.sh`; `shellcheck -x` on the same; `bash tests/scripts/run.sh`. Logs: /Users/winnguyen/scholarchain-data/logs/phase-13-checks-4.log and phase-13-harness-4.log. CHECKS_RC=1 (shellcheck only).

Observed:
- `bash -n`: no output, passed on all scripts and the harness.
- Harness: `RESULT pass=570 fail=0` (was 546 before the repair-round cases: 24 added). No failing case, so the new R3, R4, R6, R7, smoke-test, pipeline and `WOULD_RUN` cases all pass against the repaired scripts. This is the first run evidence for the work order 15 edits in their dry-run and refusal paths. It does not exercise `run_bounded`'s process-group stop, the real smoke steps, the pipeline record file (real run only) or any live call.
- `shellcheck`: five warnings, nothing else:
  - scripts/lib.sh:562 SC2034 `i` unused in `for i in 1 2 3 4 5 6 7 8 9 10` (a counting loop; use `_`).
  - scripts/lib.sh:616 `SC_RB_STRAY` and :619 `SC_RB_LEFT` SC2034 (new `run_bounded` result variables, read by run-experiment.sh after the call; need a disable with the reason, or a different hand-over).
  - tests/scripts/run.sh:1174 and :1178 SC2054 comma in an array element (`--stop-on first-failure,dlq-nonempty`), the same pattern fixed at the old :517 with a `stop_on` variable; two new lines repeat it.
Routed to repair round 2 (the last allowed): infra-engineer for lib.sh, test-engineer for run.sh.
