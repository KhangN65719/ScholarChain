# hadoop-engineer report: PipelineDriver missing per-source artifacts

Author: hadoop-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. No commit, edits only under modules/hadoop/**.

## Cause (confirmed by the agent)
`PipelineDriver.workPaths`, `researcherPaths` and `exportInputs` built paths for every dataset and artifact class. orcid writes only researchers; crossref writes works, researchers, venues; openalex writes works, researchers, topics, institutions. Identifier join, blocking, team expansion and graph export would all hit missing inputs (join failed first at `dataset=orcid/works/v1`). DBLP researchers exist only from `www` records or editors. Nothing tested PipelineDriver before.

## Fix
- `SourceNormalizers.expectedOutputs`: per-dataset required outputs (openalex works, crossref works, orcid researchers), drift-tested against real normalizer output.
- Reducers increment a per-stream counter (`scholarchain.streams/<label>`); the driver reads it off the completed job, not from the directory. Optional enrichments (researchers, topics, institutions, venues) are read only where written.
- A required output with zero records stops the run with `stage <name> finished without writing expected output <segment>` and exit 1.
- Layout and wire formats unchanged.

Files modified: PipelineDriver.scala, HadoopSink.scala, TaskSink.scala, SourceNormalizers.scala. Created: PipelineDriverOutputsSuite.scala (7 tests: all seven stages for dblp+openalex+crossref+orcid under LocalJobRunner; work paths; export inputs; counters; expected-output failure; missingExpected logic; declaration drift).

## Agent's `sbt check`
`[success]`; module totals 41, 130, 190, 177 (hadoop 177 including 7 new), all 0 failed, 0 errors; full output read, not truncated. Not the recorded gate.

## Open items from the agent
- F6: oversize same-name CJK blocks keep the `maxBlockSize` smallest members deterministically, counted as OversizeBlocks / OVERSIZE_BLOCK reject; recall for very common CJK names is still bounded like Latin names.
- `DblpEntities` table (W3C HTML4 set) has NOT been diffed against the pinned `dblp.dtd`.
- Latent: `candidate-features` reads `blocking-members` unconditionally; fails at submission if blocking emits nothing. Not fixed (policy decision).
- New driver tests write scratch under the Hadoop default tmp dir.

Next gate: human `!sbt check`, then v4 rerun, then independent review.
