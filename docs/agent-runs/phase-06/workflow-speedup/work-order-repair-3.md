# Work order: repair round 3 (R5, R6)

Author: orchestrator. Source: `code-reviewer-verdict-3.md`. Owner: `factory-engineer`.

## Scope (wording only)

- R5: `CLAUDE.md:50`: replace "two `sbt quick` runs exist" with an accurate statement. Three runs are recorded: the human's 66 s run before the marker change, the agent's 65 s run completed 2:22:52 after it, and the agent's 71 s run completed 2:31:45 (all in `docs/agent-runs/phase-06/workflow-speedup/`). Keep the marker attribution as it is.
- R6: `CLAUDE.md:54` and `docs/agents.md:61`: replace "(later runs have rewritten it)" with "later runs are expected to have rewritten it; not measured". Keep "measured once" and its caveats.
- Change nothing else. No ratification, no commit. Do not edit `build.sbt`, `AGENTS.md`, `.codex/**`, `.agents/**`.
- Do not try to run `probe-policy.sh` or `validate-agent-files.sh`: the guard refuses them for agents and the human runs them with `!`. Report that as pending.

## Report

Files changed, the exact old and new text for each of the three places, blockers, open items. Do not write to `docs/agent-runs`.
