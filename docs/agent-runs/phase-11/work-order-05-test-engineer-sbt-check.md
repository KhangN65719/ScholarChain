# Work order 05: test-engineer, the one agent `sbt check` before review

Owner: test-engineer. Date: 2026-11-09. Inputs: reports 01 to 04.

## Task

Run bare `sbt check` exactly once (the full suite, including slowSuites and the Spark integration suites). Run it as a background job and read its output file, because the foreground tool truncates. Never run another sbt command at the same time. If the run is silent for 15+ minutes, stop it, record a hang with the last output and report it (do not retry more than once, and say so).

Report:
- Exit code, the `[success]` or failure line, total time.
- The scalafmt, compile and test phases: did each pass.
- Per-module and root totals (`Passed: Total N, Failed F, Errors E`) for every group printed.
- Every failing or erroring test with suite, name, file and line, and the owning agent. In particular watch `tests/adversarial/Phase10RepairSuite` "m1: a 5 MiB body from an unauthenticated client library is refused", which failed once as a possible timing flake in a rag-engineer run (IOException fixed content-length 187, bytes received 0, broken pipe). If it fails here, classify it: product defect (service-engineer) or test-side race (test-engineer, your own). If you own the fix, do not weaken it: make the client-side send tolerant of an early server close only if the server's 401 or 413 is still asserted when the response is readable, and say exactly what you changed.
- Whether `.claude/.last-test-run` was written (you may not stat it; report only what the sbt output shows).
- Spark integration suite counts (tests/integration) since they were pending.

## Constraints

Local only. Edit nothing unless the one flake above is a test-side race in tests/**; then make only that edit and rerun `sbt quick` (not `check`) as confirmation. Do not edit production code, build.sbt, CLAUDE.md, docs/agents.md, .claude/**. Read-only git. No commit.

## Report

Standard contract, complete in the final message, with exact commands and exit codes.
