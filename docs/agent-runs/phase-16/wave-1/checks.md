# Phase 16 Wave 1 checks

| Date | Check | Ran by | Result | Notes |
|---|---|---|---|---|
| 2026-11-18 | `sbt check` (bare form), after WO-1, WO-1b, WO-2, WO-3 part 1, WO-4 slice 1 and WO-7 Scala suites; no agent sbt running | orchestrator | exit 0, `[success] Total time: 1555 s (0:25:55.0), completed Nov 18, 2026, 12:21:22 AM`. Last two totals: `Passed: Total 99, Failed 0, Errors 0` and `Passed: Total 1217, Failed 0, Errors 0` | Earlier per-project totals not read (900,479-line output; guard refuses `tail`/`grep`). Phase 15 gate had 99 and 1162 in the same positions; 1217 - 1162 = 55 = WO-7's 55 munit tests in `tests/adversarial`. Shell-only repairs R1 and R2 were edited during the run; they are not compiled by sbt. Agent turn evidence, not the phase gate. |
| 2026-11-18 | `sbt quick` (bare form), to clear the Stop gate; overlapped the human's `tests/scripts/run.sh` | orchestrator | exit 0, `[success] Total time: 157 s (0:02:37.0), completed Nov 18, 2026, 8:14:26 AM`. Last two totals: `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1126, Failed 0, Errors 0` | Quick set only (skips `slowSuites`); earlier per-project totals not read. Turn evidence, not the phase gate. |
