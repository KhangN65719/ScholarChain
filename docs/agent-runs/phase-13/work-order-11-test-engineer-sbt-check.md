# Work order 11: test-engineer, the one agent `sbt check` before review

Owner: test-engineer. No source edits in this order. Run exactly `sbt check` once (bare two-token form, never two sbt commands at once; nothing else is running sbt now). Do not edit production code, scripts/**, CLAUDE.md, docs/agents.md or `.claude/**`. Do not weaken or delete a test. Do not commit.

Context: Phase 13 changed only tests/adversarial (F2, report 02), tests/scripts, scripts, experiments, load-tests, README.md, infra and docs. The only Scala edits are the two F2 files (Phase10Http.scala, Phase10EarlyCloseSuite.scala). The goal is a passing `sbt check` that refreshes the marker over the current sources, with exact per-group counts.

Report: the exact pass and fail counts for every suite group, read from the sbt summary lines (find each `Passed: Total` / `Failed` line; if the output is too large to read whole, read the end of the output and every summary line, and say which you could not read), the `[success]` or error line, total time, and whether the marker was refreshed if you can tell without a shell command. A failure is reported verbatim with the first failing test and its assertion output; do not retry to hide it, and do not edit anything to make it pass. If a failure is outside tests you own, report `BLOCKED-ON` with the owner.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
