# Work order 02: test-engineer, Phase 12 carry-over F2

Owner: test-engineer. Local only. Do not edit production code, CLAUDE.md, docs/agents.md or `.claude/**`. Do not weaken or delete any test. Do not commit.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-12/review-round-3.md (F2), docs/agent-runs/phase-13/decisions.md.

## F2: N2 control grace period

The N2 control in Phase10EarlyCloseSuite.scala (around line 597) uses `graceMillis = 1000`. The baseline (Phase10Http.scala around line 595) and the real-service checks (Phase10RepairSuite.scala around lines 308 and 318) use the default 500 ms. The control must use the grace period it claims to prove.

Do:
1. Find the exact lines (the test sources may live under modules/services test trees or tests/src; locate by name; edit only files under tests/** or a path you own).
2. Make the N2 control use the same grace period as the baseline and the real-service checks (the shared default, referenced by name where one exists, not a repeated literal 500).
3. Run it. If the control fails at that grace period, do NOT raise the delay or loosen the assertion to make it pass: report the failing output and the exact numbers, and route the production question to the service owner via `BLOCKED-ON`.
4. Before editing, state which test proves this item.

Run `sbt quick` once (exactly that two-token form; never two sbt commands at once; no one else is running sbt in this step). Report exact pass and fail counts per suite group. A written but unexecuted test is not evidence.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
