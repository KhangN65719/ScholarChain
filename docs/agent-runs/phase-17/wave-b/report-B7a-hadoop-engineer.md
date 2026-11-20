# WO-B7a report (hadoop-engineer), as received 2026-11-19 (condensed by the orchestrator; citations kept)

STATUS: BLOCKED-ON (no standalone command). Read-only; nothing run.
- **Entry point:** `scholarchain.hadoop.jobs.PipelineTool` (`PipelineDriver.scala:349-350`), meant for `hadoop jar`.
  - Hadoop is `Provided` (`build.sbt:342`), and `hadoop` has no `Compile / run` override (the only one is `sparkAnalytics`, `build.sbt:372-383`).
  - No committed local `hadoop jar` recipe was found. The search was refused by the guard, so this is not confirmed by search.
  - The AWS path `scripts/run-bounded-pipeline.sh` is excluded.
- **The bounded local sample exists as test suites** over `datasets/samples/{dblp.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}` (`TestRun.scala:47-48`):
  - `LocalPipelineSuite`: in-process, seven stages, runs in `sbt quick`.
  - `PipelineDriverOutputsSuite` (`:183-216`): seven real jobs under `LocalJobRunner`, exit 0.
  - `MapReduceEndToEndSuite`: two real jobs.
  - The last two, and `PipelineDriverUnknownDatasetSuite`, are in `slowSuites` (`build.sbt:125-129`), so only `sbt check` runs the real jobs.
- **Expected values (from assertions, UNVERIFIED until H-8):**
  - DBLP `InputRecords=2`, `AcceptedRecords=2`, 0 rejects; each other source 2 and 2.
  - 6 works.
  - `EmittedPairs=30`, `SingleAuthorWorks=3`, 6 teams, 18 author-papers.
  - Largest team 5; largest pair expansion 10.
  - Oversize counters 0.
  - Candidates have `leftId < rightId`.
  - A repeat run is identical.
  - Driver returns 0; non-empty `part-*` outputs; researchers include "Michael Ley".
- **Run time:** about 10 s for the driver suite (the `build.sbt` comment); `sbt quick` about 65-71 s (CLAUDE.md); others NOT RECORDED.
- **Human-runnable per-suite form (not run, no record):** `sbt "hadoop / testOnly scholarchain.hadoop.PipelineDriverOutputsSuite"`.
- **Risks:** a README "Hadoop sample command" without a real run would overstate it. `PipelineDriver.scala:28-29` has a stale scaladoc ("no stage has executed on a Hadoop runtime"); later repair, owner hadoop-engineer.
- **Next gate:** the orchestrator chooses (a) document the test-based sample, or (b) a work order for a real local command.

## Orchestrator decision (P17-20)
Option (a): no new code in the final week. The README documents the test-based bounded sample, with `sbt check` for the real-job run and the per-suite form for the human, marked NOT REPLAYED until H-8.
