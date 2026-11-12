# Work order 21: code-reviewer (fresh, Opus, read-only), Phase 13 review round 3: S1 and the wording fixes only

You have Read, Grep and Glob only. Never edit, never fix, never approve your own work. Verify every candidate finding against the actual files. Batch all findings in one list. This is the final review: no further repair round is available without the human's decision, so classify precisely (BLOCKING, MAJOR, MINOR, ACCEPTABLE-OPEN) and say whether the phase can go to the human's `sbt clean check`.

Read first: CLAUDE.md, docs/agents.md, decisions.md (D10, D15), review-round-2.md (S1 to S7), report-20-repair-3.md, checks-5.md, checks-6.md.

## Scope (only these)
1. S1 in scripts/lib.sh `run_bounded` (about lines 590-640): read the whole function again. Confirm the marker now decides the timeout whatever `rc` is. Walk each case and state the exit status and what the callers record: finishes before the limit; TERM suffices; TERM ignored and KILL needed; the command exits 0 after catching TERM at the limit (the S1 case); nonzero exit on its own before the limit; the command exits at almost exactly the limit (race, safe direction); Ctrl-C. Callers: run-experiment.sh (`ENTRY_RC`, the `FINAL=STOPPED_WALL_CLOCK` mapping, record fields, final die), check.sh `gate_sbt`, package.sh, the `docker()` wrapper and the docker call sites in deploy.sh and cleanup or other scripts (the infra-engineer could not read these: read them and say whether any caller treats a 124 that arrives with rc 0 incorrectly, or any path relied on the old behaviour). Any new defect introduced by the one-line change? Does the stale-marker handling still work (marker removed on every path, no marker left behind for the next call)?
2. The S7 wording fixes: scripts/README.md (:3, :84, :106, :205, :320 and their neighbourhood), tests/scripts/README.md (:7-8), README.md (:46, :83, :93), experiments/README.md (:77, and :84 which the experiment-engineer flagged: `MANIFEST_VALID` and `LIMITS` expected for both commands but `--print-digest` prints neither). Is every statement true against checks-5.md and checks-6.md and the code? Any sentence that claims more than the 570/0 run proves, any remaining stale "unrun" or 546 text, any contradiction between the four files, any claim that the S1 repair is tested?
3. Nothing else changed in round 3: check that no file outside the work order 20 paths was edited by looking at what you can see (ownership: infra-engineer scripts/lib.sh and scripts/README.md; test-engineer tests/scripts/README.md; documentation-engineer README.md; experiment-engineer experiments/README.md), and that S2 to S6 were not silently changed.
4. Carry-over: confirm the open items are all disclosed and none is hidden: S2 to S6, R10 (D3, D11, D12, D13, D14, concurrency and task limits not enforced), R11 (`require_human` detects only Claude Code), experiments/README.md:84.

Do not re-review anything that review rounds 1 and 2 already verified and that round 3 did not touch.

## Output
APPROVE or REQUEST CHANGES; findings with ID (prefix T), severity, file and line, scenario, impact, owner; "Verified"; "Pending gates"; a closing statement saying whether the phase may go to the human's `sbt clean check` and what the human must record as accepted-open.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
