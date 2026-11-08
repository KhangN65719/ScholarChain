# Workflow speed-up ratification

- Decision: RATIFIED by the human owner (Khang), 2026-11-08.
- Basis: code-reviewer-verdict-4.md (APPROVE after four passes), human-facts.md, and the build-engineer and factory-engineer reports in this folder.
- Change: an `sbt quick` alias (format, compile, and all tests except the 13 `slowSuites`; it writes the marker). `check` is unchanged. Agents may run `sbt quick`, which is added to excludedCommands and the policy.py guard (19 new probes). CLAUDE.md, docs/agents.md and 10 role files carry the new cadence: quick during work and repairs, the human's `sbt clean check` at each phase's final gate and before each phase commit.
- Evidence:
  - The agent's `sbt check` passed (18m29s).
  - `sbt quick` ran three times (66 s, 65 s, 71 s) with 920 tests passing and 151 excluded.
  - The marker was rewritten at 02:22 and 02:31, observed by Claude (Cowork).
  - probe-policy.sh passed 179 with 0 failed, and validate-agent-files.sh gave 4 PASS lines.
- Recorded exception: this commit has no `sbt clean check`. The human deferred it to Phase 7's final gate, which is the recorded gate for this build.sbt change.
- Open (for the next factory change or after restart): CLAUDE.md:54 and docs/agents.md:61 still say the marker rewrite is "not measured", and "phase commit" is undefined in the constitution. Not yet observed: an agent running `sbt quick` in a fresh session, and the Stop-gate loop (P4-D).
