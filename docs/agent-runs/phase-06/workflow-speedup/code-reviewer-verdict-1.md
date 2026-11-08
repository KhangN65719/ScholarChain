# code-reviewer verdict 1: sbt quick speed-up

Author: code-reviewer (archived by the orchestrator, formatting only). Verdict: **REQUEST CHANGES**. The reviewer created no files and ran no commands (Read, Grep, Glob only).

## What checks out

1. build.sbt: `markTestRun` (build.sbt:243-251) runs the same sequence and still uses `(Test / test).all(allProjects)`; marker code moved into `writeTestMarker` (build.sbt:17-29), body identical. `quickRun` (173-181): format check, sbt-file format check, compile, `quickTest`, then `writeTestMarker`; `Def.sequential` stops at first failure. `quickTest` (119-126) drops only `slowSuites`; no `Test / testOptions` change. Fork settings still apply through `testGrouping` (189-201) and modules/spark `Test / fork` (308-309). All 13 `slowSuites` names verified as real classes and packages (8 tests/integration, 2 modules/spark, 3 modules/hadoop). No test files modified.
2. settings.json: only the third `excludedCommands` entry; probe_policy.py:116-144 still pins deny list, allow list and permission modes.
3. policy.py:146-148: `quick` only as one bare argument; same role set as `check`; deploy-engineer and incident-responder refused; code-reviewer refused at 166-167. Probes at probe_policy.py:156-165 and 184-194 cover near-miss and chained forms; the derived tie at 112-115 links `CHECK_SBT` to the exclusion list.
4. Ten role files state the marker ruling; none still says "never writes".

## Findings (one repair round)

- **F1 (medium, factory-engineer):** probe_policy.py:153-155 still says "It skips only the slowSuites list and never writes the Stop-gate marker". Contradicts ruling 1. The factory marker report did not search this file.
- **F2 (low, factory-engineer):** probe_policy.py:104 "any ... third entry would silently widen" should say "any further entry"; line 145 "both excluded commands" is stale (three commands).
- **F3 (medium, factory-engineer), CLAUDE.md:50 and :54 vs evidence:**
  - Understated: line 50 says no agent run observed; the build-engineer marker report (:27, :41) records an agent's bare `sbt quick` in this session (guard allowed, no sandbox-lock error, `[success] Total time: 65 s`). State same session, no restart; fresh-session run still unobserved.
  - Mislabelled: the human's 66 s `!` run predates `writeTestMarker` in `quickRun`; label it pre-marker-change. The post-change run is the agent's 65 s run, whose first total (core 41) was not read.
  - Out of date: line 54 says the marker write counts as measured only when the build-engineer report records the mtime; that report now exists and records it as not measured (guard refused `stat`, `ls -l`, `find`). Say so; name the human `!` `stat` around one `sbt quick` as the way to close it.
  - Placement: "This closes the Phase 1 gate." now follows the quick sentences; move it back next to the 2026-11-06 measurements.
- **F4 (low-medium, factory-engineer):** CLAUDE.md:54 calls the marker write "a build design, not yet a measurement" then flatly says "A passing `sbt check` or `sbt quick` writes that marker". Use "is designed to write" for `quick`.
- **F5 (medium, factory-engineer):** CLAUDE.md:56 Stop-gate repair rule now reads "run `sbt quick` or `sbt check` ... and record the result before review, commit or ratification". Ruling 1 lets `quick` clear the Stop gate, not stand in before commit or ratification (the human's `sbt clean check`, CLAUDE.md:54). Split the sentence.
- **F6 (low-medium, factory-engineer):** CLAUDE.md:25 and docs/agents.md:61 say the full `sbt clean check` runs once at each phase's final gate and before any re-review that depends on it, without saying who runs it; agents cannot (guard refuses `sbt clean check`, probe_policy.py:171). Name the human, or say "an agent's `sbt check`" for the re-review case. "Once" conflicts with "before every commit" at CLAUDE.md:54.
- **F7 (low, factory-engineer):** `.claude/agents/build-engineer.md:16` "never delete, skip or weaken a test through it" contradicts `slowSuites`, which skips suites in `quick`. Suggested: "never remove a test from `check`".
- **F8 (low, informational):** CLAUDE.md:18 still says "Run `sbt check` for implementation changes"; ruling 3 covers it; a pointer to the cadence rule at :25 would avoid an apparent conflict.
- **F9 (low, risk note, build-engineer):** `quickTest.all(allProjects)` (build.sbt:178) covers only projects that include `quickSettings`; a future module that omits it would, by inference (not verified), be silently skipped by `quick` while `check` still runs it. A one-line comment next to `quickSettings` would cover it.

Outside the diff (orchestrator and human): untracked `AGENTS.md` (lines 49, 51) and `.codex/hooks/probe_policy.py:59` still carry the old two-command text. Those paths and `.agents/` have no owner in docs/agents.md; an unowned artifact needs a human ruling before anyone edits it.

## Pending (read-only tools could not check)

- `probe-policy.sh` and `validate-agent-files.sh` not re-run after the marker wording edits (last known 179 passed / 0 failed, 4 PASS); re-run after F1 and F2.
- Marker mtime across one `sbt quick` unmeasured; the quick marker write is a design.
- Fresh-session interception, fresh-session agent `sbt quick` and the Stop-gate loop (P4-D) unobserved.
- Human `sbt clean check` for these build.sbt changes deferred to Phase 7 by human decision.
- Whether sbt runs or skips a project lacking `quickTest` under `.all` (F9) needs a build-engineer run.

## Next gate

factory-engineer fixes F1-F7 (F8 optional), build-engineer optionally F9, scripts re-run, then a re-review covering only changed items, then human ratification and a fresh orchestrator session.
