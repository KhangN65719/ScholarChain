# Work order: constitution update for `sbt quick`

Author: orchestrator. Owner: `factory-engineer`. Follows `work-order.md` and `build-engineer-report.md` in this directory.

## Human decisions (2026-11-08)

- The speed-up `sbt clean check` is skipped. Phase 7's final `sbt clean check` is the recorded gate for the `build.sbt` change, and the `build.sbt` review joins Phase 7's `code-reviewer` pass.
- Do not commit.

## Scope

1. `.claude/settings.json`: add the literal `sbt quick` to `sandbox.excludedCommands`, next to `sbt check` and `sbt Test/compile`. Exact match, bare two-token form. Change nothing else (no allow rules, deny lists, unsandboxing switches or hook wiring). Keep the guard consistent: confirm in `.claude/hooks/**` that `sbt quick` is not refused and needs no hook change; if a hook change is needed, make it and say so.
2. `CLAUDE.md` and `docs/agents.md`, minimal edits that state:
   - During work and repair rounds, agents use `sbt quick` or per-module tests.
   - The full `sbt clean check` runs once at each phase's final gate, and before any re-review that depends on it.
   - Real-data reruns happen only when that pipeline's code changed.
   - A new slow Spark or Hadoop suite must be added to `slowSuites` in `build.sbt`, the quick exclusion list. Nothing is deleted, skipped or weakened by `quick`; `check` runs every test.
   - Update the existing "An agent can run exactly `sbt check` and `sbt Test/compile`" paragraph and the `build-engineer` and other role statements only where they would now contradict this (the role files list permitted sbt commands: check `.claude/agents/**` and update them, since you own them).
   - Keep `sbt quick` out of the recorded phase gate: an agent's own `sbt quick` is turn evidence only, and it does not write `.claude/.last-test-run`. State that the Stop gate marker is still written only by `sbt check`.
3. Do not claim live interception for the new exclusion. Measured evidence only: the exclusion is exact-match; `sbt "quick"` and `sbt  quick` would run sandboxed. Record anything not measured as unmeasured.
4. Run the hook probes (the direct script probes documented in `docs/agent-runs/phase-01/orchestrator-update/`) after the edits. Report exact pass and fail counts and keep direct probes separate from live interception. Constitutional changes need independent read-only review, human ratification and a fresh orchestrator session; do not mark anything ratified.

## Report

Status, files changed, diff summary, probe results with counts, what is unmeasured, open gates (review, ratification, fresh session, a live `sbt quick` run by an agent after restart). Return it to the orchestrator. Do not commit.
