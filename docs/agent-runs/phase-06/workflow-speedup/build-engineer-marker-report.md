# build-engineer report: `quickRun` writes the marker (Part A)

Author: build-engineer (archived by the orchestrator, formatting only). Status: COMPLETE for the build change; marker behavior of `quick` not measured. Work order: `work-order-marker.md` Part A. Not committed.

## Files

Modified: `build.sbt` only. Not touched: `docs/agent-runs`, `project/**`, `.scalafmt.conf`, constitutional files.

## Primary gate

| Item | Proof |
|---|---|
| `quickRun` ends with the marker step, after `quickTest` only | Static reading of the `Def.sequential` order, and the `sbt quick` success below. The mtime comparison that would prove the write is unmeasured. |
| `check` and `markTestRun` behavior identical | Static reading: same sequence, inline step replaced by shared `writeTestMarker`. `sbt check` not re-run (fallback rule); the human's `sbt clean check` is the proof. |
| `build.sbt` loads, compiles, format-clean | `sbt Test/compile`; `scalafmtCheck` and `scalafmtSbtCheck` inside `sbt quick` |

## Change

- Marker code moved from `markTestRun` into `lazy val writeTestMarker = Def.task { ... }`; body unchanged (temp file, atomic move, delete temp).
- `markTestRun` ends with `writeTestMarker`.
- `quickRun`: scalafmtCheck, scalafmtSbtCheck, compile, `quickTest.all(allProjects)`, then `writeTestMarker`. `Def.sequential` stops at the first failure, so the marker is written only when all four pass.
- A comment says the marker from `quick` means "the quick set passed on these sources", not "every test passed".

## Commands and results

- `sbt Test/compile`: exit 0, `[success] Total time: 1 s`.
- `sbt quick` run by the agent: not refused by the guard, no sandbox-lock error. `[success] Total time: 65 s`, completed Nov 8, 2026, 2:22:52 AM.
- `Passed: Total` read directly: 181, 389, 42, 267 (all Failed 0, Errors 0). The first total (expected core 41) was not read from the output.
- Marker modification time: NOT compared before or after. The guard refused `stat`, `ls -l` and `find` on the path; the Read tool shows no mtime. A write is expected from the sequence but not observed. The human or orchestrator must supply a before-and-after timestamp (for example a `!` run of `stat` around one `sbt quick`).

## Risks

- After this change a passing `sbt quick` writes the marker although it skips the 13 `slowSuites`. The marker no longer means every test passed. Only the human's `sbt clean check` covers all suites.

## Next gate

Record the marker mtime around one `sbt quick`. The human's `sbt clean check` at the phase gate is still pending for this `build.sbt` change (already accepted).

## Orchestrator note

This is the first observed agent run of bare `sbt quick`: the guard allowed it and it ran outside the sandbox lock failure. It was observed in a `build-engineer` turn in the current session, without a restart. Fresh-session hook interception remains unobserved.
