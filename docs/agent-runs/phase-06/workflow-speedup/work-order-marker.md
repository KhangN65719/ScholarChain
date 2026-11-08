# Work order: `quick` writes the test marker; rulings on factory issues

Author: orchestrator. Follows `factory-engineer-report.md` in this directory.

## Human rulings (2026-11-08)

1. `quickRun` also writes `.claude/.last-test-run`, using the same marker step as `markTestRun`, so a quick-only work turn passes the Stop and SubagentStop hook. The phase gate is still the human's `sbt clean check`.
2. No per-module sbt tasks for agents. The guard stays as it is.
3. Accepted: the primary gates that say "run sbt check" are met at the phase's final gate, not every round.
4. Accepted: the `.claude/hooks/policy.py` change that allows bare `sbt quick`.
5. Do not commit.

Consequence to keep visible: a passing `sbt quick` now writes the marker although it skips the `slowSuites`. The marker no longer means "every test passed". It means "the quick set passed on these sources". Only the human's `sbt clean check` at the phase gate covers every test. Say this plainly in the docs; do not soften it.

## Part A: build-engineer (build.sbt only)

- Make `quickRun` end with the same marker-writing step as `markTestRun`, after `quickTest` succeeds and only then. Share one definition rather than copying the code, without changing what `check` does (`check` and `markTestRun` behavior must stay identical).
- The marker is written only after scalafmtCheck, scalafmtSbtCheck, compile and `quickTest` all pass.
- Run `sbt Test/compile`. Try bare `sbt quick` once: the guard and exclusion were changed this turn series and the live effect is unobserved, so report the exact outcome whichever way it goes (if it is refused or fails on the sandbox lock, say so and do not work around it). If `quick` runs, report its totals (expect 41/181/389/42/267) and whether `.claude/.last-test-run` changed (compare the file's modification time before and after, with the read tools or an allowed command).
- If `sbt quick` cannot run, run `sbt check` instead and report that the marker behavior of `quick` is unmeasured.
- Report in your role-file format. Do not write to `docs/agent-runs`. Do not commit.

## Part B: factory-engineer (constitutional files only)

Update every statement that says `sbt quick` does not write `.claude/.last-test-run`, so that it says the opposite, with the caveat above:

- `CLAUDE.md` (project rule and the Hook operation paragraphs, including the Stop gate text and the turn-evidence sentence).
- `docs/agents.md` (the Test cadence paragraph; also record the accepted primary-gate wording as a human-accepted decision dated 2026-11-08, not as a draft).
- `.claude/agents/**` role files that carry the sentence "`sbt quick` never writes the marker" or equivalent.
- The hook scripts and probes only if any text or assertion encodes the old claim; otherwise leave them. Re-run `probe-policy.sh` and `validate-agent-files.sh` and report exact counts.
- Keep: the phase gate is the human's `sbt clean check`; an agent's own run is turn evidence only; no per-module sbt for agents; the three exact-match commands; live behavior unmeasured until a fresh session observes it. The "not measured" statement about the marker should say it is a build design now, and is measured only when Part A's report records the timestamp.
- Do not edit `build.sbt` and do not ratify anything. Do not commit. Return the report to the orchestrator; do not write to `docs/agent-runs`.
