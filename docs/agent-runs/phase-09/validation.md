# Phase 9 validation (agent evidence, not the phase gate)

## Agent `sbt check` before review (build-engineer, verification run, no edits)
- Bare `sbt check`, run once. Exit 0. `[success] Total time: 1104 s (0:18:24.0), completed Nov 8, 2026, 4:44:47 PM`. No failures or errors.
- `Passed: Total` lines read (console log 893,671 lines; lines 1 to 1499 unread; groups not named in the log; `target/test-reports` not read): 207, 81, 882, 49, 218, 226, 389, each Failed 0, Errors 0.
- Expected log noise, not failures: `ScholarChain pipeline failed at stage source-normalization/openalex` (PipelineDriverOutputsSuite, passed) and `PHASE8-INGEST RESULT: FAILED StorageException: MissingSuccessMarker` (IngestMainSuite "a missing export is refused", passed).
- `slowSuites`: no Phase 9 entry needed for the three `tests/` suites (each under about 1.5 s in total). The rag runtime suites and storage `ReadPortsSuite` per-test times were not read; expected fast (rag about 1 s per work order 02 report).
- Per-agent `sbt quick`: storage 75 s (ReadPortsSuite 35/35), rag 76 s, test-engineer 112 new tests 0 failed.
- This is turn evidence. The phase gate is the human's `sbt clean check` after any real-data run. No real-data run is planned for Phase 9.

## Agent `sbt check` after repair round 1 (build-engineer, verification run, no edits)
- Bare `sbt check`, run once, after work orders 04 and 05. Exit 0. `[success] Total time: 1132 s (0:18:52.0), completed Nov 8, 2026, 5:38:04 PM`. No failures.
- `Passed: Total` lines read (log about 893,500 lines of Spark INFO, middle not scanned, groups not attributed): 207 (line 2641, Hadoop), 81 (line 893541, root analytics), 906 (line 893542, last before `[success]`), each Failed 0, Errors 0.
- Root Phase 9 suites from `target/test-reports/*.xml` (timestamp 2026-11-08T17:19:15, inside the run window): Phase9GateSuite 20, Phase9ModelSuite 27, Phase9RepairSuite 24, Phase9BenchmarkSuite 65; 136 tests, 0 failures, 0 errors. rag (17 suite XMLs) and storage per-suite counts were not read; their status rests on `[success]` and the Failed 0 lines.
- Per-agent quick runs for the repair: test-engineer WO 05 exit 0; rag WO 04 168 rag tests 0 failed with the one known root red (fixed by WO 05).
- Review: round 1 REQUEST CHANGES, round 2 APPROVE (`review.md`). Five minor follow-ups m5 to m8 and n1 are not repaired (repair budget of 2 rounds used).
- Real-data run: none planned or needed. Turn evidence only; the phase gate is the human's `sbt clean check`.

## Phase gate: human `sbt clean check` (run 2026-11-08, after the last source change)
- Command: `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-09-clean-check.log | tail -n 60`.
- Result read from the tail (last 60 lines): `[info] Passed: Total 81, Failed 0, Errors 0, Passed 81`, `[info] Passed: Total 906, Failed 0, Errors 0, Passed 906`, `[success] Total time: 1096 s (0:18:16.0), completed Nov 8, 2026, 6:02:15 PM`, exit code 0. Earlier groups' totals are in the log, not in the tail. Log: `/Users/winnguyen/scholarchain-data/logs/phase-09-clean-check.log`.
- Recorded by the orchestrator from the output; ratification itself remains the human's decision.
