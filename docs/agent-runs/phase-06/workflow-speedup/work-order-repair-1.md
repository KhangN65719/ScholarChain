# Work order: repair round 1 (batched review findings)

Author: orchestrator. Source: `code-reviewer-verdict-1.md` in this directory (read it first; it has exact locations).

## Rulings that bind this round

- Do not re-litigate: class-name filter; `quick` writes the marker; no per-module sbt for agents; primary gates met at the phase final gate; policy.py change accepted.
- Observed so far: an agent ran bare `sbt quick` in this session without a restart (guard allowed, no sandbox-lock error, 65 s). Marker mtime NOT measured. Fresh-session interception unobserved.
- The `sbt clean check` at the phase gate is run by the human; agents cannot run it.
- Do not commit. Do not ratify.

## factory-engineer: findings F1 to F8

- F1, F2: fix the stale comments in `.claude/hooks/probe_policy.py` (lines about "never writes the Stop-gate marker", "third entry", "both excluded commands"). Only comment and wording changes unless an assertion encodes the old claim; if an assertion changes, say which.
- F3: in CLAUDE.md, state the same-session agent `sbt quick` observation (no restart; fresh session still unobserved); label the human's 66 s `!` run as before the marker change and the agent's 65 s run as after it (core's 41 not read); say the mtime is unmeasured and name the human `!` `stat` around one `sbt quick` as the way to close it; move "This closes the Phase 1 gate." back beside the 2026-11-06 measurements.
- F4: use "is designed to write" for `quick` where the doc says it flatly.
- F5: split the Stop-gate repair sentence: `sbt quick` or `sbt check` clears the Stop gate; the human's `sbt clean check` is required before commit or ratification.
- F6: in CLAUDE.md and docs/agents.md, name who runs the full `sbt clean check` (the human, with `!`), and reconcile "once at each phase's final gate" with "before every commit" and "before a re-review that depends on it" (for re-review an agent's `sbt check` may serve, per the human's recorded gate rule; if you cannot reconcile without a new decision, report it as a question).
- F7: `.claude/agents/build-engineer.md`: replace "never delete, skip or weaken a test through it" with wording that does not contradict `slowSuites`, for example "never remove a test from `check`".
- F8: add a short pointer from the "Run `sbt check` for implementation changes" rule to the cadence rule. Optional; do it if it is a one-line change.
- Then run `probe-policy.sh` and `validate-agent-files.sh`. They were refused for you last time. Try the exact documented probe invocation once; if the guard refuses, report BLOCKED-ON with the exact refusal and stop. Do not work around it.
- Do not edit `AGENTS.md`, `.codex/**` or `.agents/**`: they have no owner and need a human ruling.

## build-engineer: finding F9

- Add a one-line comment next to `quickSettings` in `build.sbt`: a project that omits it is silently skipped by `quick`'s `quickTest.all(allProjects)` while `check` still runs it. Check that all 7 projects include `quickSettings` and report it. Do not change behavior.
- Run `sbt quick` once more (it also confirms the format check). Report totals for every module (read all five `Passed: Total` lines, including core's 41).

## Report

Each agent: status, files changed, which finding each edit closes, commands and exact counts, anything unmeasured, blockers. Do not write to `docs/agent-runs`.
