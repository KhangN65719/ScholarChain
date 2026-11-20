# WO-B14 report (hadoop-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE (documentation only; no code change and no sbt). FILES CREATED: `modules/hadoop/JOB-CONTRACTS.md`.

## Contents
- **Section 0: shared facts.** The driver, `JobSupport`, `JobConfig` and `StableKeyPartitioner` (4 reducers by default).
- **Sections 1-7: one per job.** dblp-extraction, source-normalization, identifier-join, blocking, candidate-features, team-expansion, graph-export. Each gives paths and the `stage=.../v1` layout, mapper and reducer key/value types, partitioner, bounds (50/50/10000), counters, reject codes and tool arguments.
- **Section 8: the EMR S3 pattern and sample values.**
  - S3 pattern: `s3://<data-bucket>/curated/runs/<run-id>/hadoop/run=<slug>/snapshot=<12 hex>/stage=.../`, placeholders only.
  - Derived figures (labelled): coauthor key limit 100, pair suppression from 142, largest block 1225 candidates.
  - Sample counters from test assertions, NOT REPLAYED.
- **Section 9: tests per job.**
- **Section 10: risks.**

## Risks
- **Stale scaladoc:** `PipelineDriver.scala:28-29` (fix needs human approval).
- **No local `hadoop jar` command.**
- **Possible defects:**
  - identifier-join has no memory cap per identifier;
  - candidate-features does not collapse duplicate pairs across blocks;
  - rejects of one reason code share one reducer.
- **Files not read** (statements marked UNVERIFIED): `DblpParser`, `ExactIdentifiers`, `XmlRecordStream`, `TextNormalizer`, `LocalPipelineRunner`, most of `SourceNormalizers`, core `BlockStrategy.selective`, the manifest writer, and several suite bodies.
- **Test gaps:** no real-job reject coverage for jobs 3, 4, 5 and 7, and no multi-reducer run.
- **EMR script:** the `run-s-pipeline.sh` header still says "Written, not executed".

## Manual
- H-8 replay.
- Scaladoc decision.
- An optional README link to the file.
