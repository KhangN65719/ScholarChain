# factory-engineer report: repair round 2, R1 to R4

Author: factory-engineer (archived by the orchestrator, formatting only). Status: edits done; BLOCKED-ON for the final script runs (owner: human or orchestrator). Work order: `work-order-repair-2.md`. Not committed, not ratified.

## Edits and findings closed

- R1 (`CLAUDE.md`, Stop-gate paragraph): "and record the result before review" is back on the `sbt quick` or `sbt check` sentence; the human's `sbt clean check` sentence no longer gates every review.
- R2 (`CLAUDE.md`, Stop-gate paragraph): ratification dropped as a trigger. Now "required at each phase's final gate and before each phase commit"; names the speed-up commit as a recorded exception covered by the human's Phase 7 deferral of the `build.sbt` clean check, and says the exception covers that commit only.
- R2 (`CLAUDE.md:25`, `:54`, `docs/agents.md:61`): "before every commit" is now "before each phase commit". Exception sentence added at `CLAUDE.md:25` and `docs/agents.md:61`; at `:54` only the phrase changed (exception stated at `:25` and `:56`).
- R2 (ten role files: build, dependency-updater, domain, entity-resolution, hadoop, rag, service, spark, storage, test engineer): same phrase change, now "before each phase commit stays the recorded phase gate". No exception sentence added to role files, so no new trigger.
- R3 (`CLAUDE.md:54`, `docs/agents.md:61`): "the marker's mtime is 02:22 local" now "was 02:22 local after that run (later runs have rewritten it)".
- R4 (`CLAUDE.md:50`): contradictory sentence replaced with "The `sbt quick` entry was added on 2026-11-08, and two `sbt quick` runs exist. The 2026-11-06 measurements further on cover only `sbt check` and `sbt Test/compile`."; closing clause is now "no allow rule was added for `sbt check`, `sbt Test/compile` or `sbt quick`".

F6 wording kept as ruled. Not touched: `build.sbt`, `AGENTS.md`, `.codex/**`, `.agents/**`, `docs/agent-runs`, hooks, settings. No authority changes.

## Blocker

`probe-policy.sh` and `validate-agent-files.sh` not run after the last edit. `bash <script>` refused ("Shell and interpreter wrappers are blocked"); direct calls refused ("Use structured file tools for constitutional paths and the factory workflow for edits"). The guard refuses any Bash call naming a constitutional path other than the four read-only git forms. No workaround tried. The earlier 179 passed / 0 failed and 4 PASS predate these edits. The human should run both with `!`.

## Open

- Role files other than the ten were not searched for "before every commit" (`factory-engineer.md` was read and has none; others, such as `code-reviewer.md`, unchecked).
- Re-review of `CLAUDE.md` lines 25, 50, 54, 56 and `docs/agents.md:61`.
- The Stop gate may block this turn (constitutional edits, no tests run); `sbt quick` clears it.
- Unobserved: fresh-session `sbt quick`, hook interception, P4-D loop. Human `sbt clean check` deferred to Phase 7 for `build.sbt`.
