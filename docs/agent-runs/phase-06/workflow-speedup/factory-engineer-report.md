# factory-engineer report: constitution update for `sbt quick`

Author: factory-engineer (archived by the orchestrator, formatting only). Status: COMPLETE, awaiting review. Work order: `work-order-factory.md`. Not committed. Nothing ratified.

## Files changed (14)

- `.claude/settings.json`: added `"sbt quick"` as the third entry of `sandbox.excludedCommands`. Nothing else changed.
- `.claude/hooks/policy.py`: a hook change was needed. The guard allowed sbt through `CHECK_SBT = {"check", "Test/compile"}` and refused `sbt quick`; added `"quick"`. Shape unchanged: one bare argument; still refused for `deploy-engineer` and `incident-responder`; `code-reviewer` has no shell.
- `.claude/hooks/probe_policy.py`: exclusion-list assertion updated to three entries; 19 probes added: 5 role allow/refuse, 8 near-miss (`quickRun`, `quickTest`, `quick --foo`, `-batch quick`, `clean quick`, `quick check`, `root/quickRun`, `quick Test/compile`), 2 quoted/double-space forms labelled "(inferred)", 4 chained or expanded forms.
- `CLAUDE.md`: new test-cadence project rule; the exact-command paragraph names three commands; exact-match list extended; `sbt "quick"` and `sbt  quick` outcomes marked inferred; network-widening sentence says three commands; turn-evidence sentence covers `sbt quick` (not the phase gate, does not write the marker).
- `docs/agents.md`: `build-engineer` row maintains `slowSuites`; new "Test cadence" paragraph before the orchestrator paragraph.
- `.claude/agents/`: build, domain, hadoop, spark, entity-resolution, dependency-updater, storage, rag, service and test engineers updated (three sbt forms; `sbt quick` during work and repair, `sbt check` at the final gate or when the work order asks; `quick` never writes the marker). Role-specific: build-engineer owns `slowSuites`; hadoop and spark report new slow suites to build-engineer; entity-resolution reruns real-data pipelines only when its code changed; dependency-updater reports a version change only after `sbt check`; test-engineer treats a changed Spark integration suite as pending until `sbt check`. No change needed: dataset, deploy, incident, infra, experiment, documentation, code-reviewer, factory.

Full diff: `git diff` on these paths (`build.sbt` appears only from the earlier uncommitted build-engineer change).

## Probes and validation (direct scripts, not live interception)

- `.claude/hooks/probe-policy.sh`: baseline 160 passed, 0 failed; after edits 179 passed, 0 failed (+19).
- `.claude/hooks/validate-agent-files.sh`: 4 PASS lines (18 agents with explicit tools; 18 ownership rows and routes; 5 commands; 18 JSON files parsed, 10 shell scripts checked, executable bits and event wiring).

## Not measured

- No agent run of `sbt quick` after the settings change; live effect of the exclusion unobserved.
- `sbt "quick"` and `sbt  quick` running sandboxed is inferred, not measured.
- `quick` leaving `.claude/.last-test-run` untouched rests on static reading of `build.sbt`; no marker timestamp compared across a run.
- Native hook interception of `sbt quick` in a fresh session unobserved.

## Issues for the orchestrator and human

1. Per-module tests are unavailable to agents. The guard refuses any sbt form beyond the three bare ones and per-module tasks are not sandbox-excluded. Not widened; adding one needs a human decision.
2. A quick-only turn still hits the Stop gate: the gate wants a marker newer than changed sources and only `sbt check` writes it. A source-changing agent ending with only `sbt quick` is blocked on the first stop and reports "unverified" on the repeat. Works against the speed-up unless the human relaxes the gate; not relaxed.
3. Primary gates now read as phase-final: the `docs/agents.md` cadence paragraph says "run sbt check" gates are met at the phase's final gate, not every round. This loosens per-round gate wording; needs explicit review.
4. The `slowSuites` review and the `build.sbt` clean check are deferred to Phase 7; no claim recorded about either.
5. Grep and Glob were unavailable to the agent; it used `git ls-files`.

## Open gates

Independent read-only review (orchestrator supplies `git diff` for constitutional paths), human ratification, fresh orchestrator session, a live agent `sbt quick` run after restart (also record whether the marker timestamp changes).
