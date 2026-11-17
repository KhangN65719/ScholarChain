# WO-2a report, hadoop-engineer (received 2026-11-17; preserved as received; author: hadoop-engineer subagent)

Status: COMPLETE for `modules/hadoop/**`; one BLOCKED-ON infra-engineer item. No production code added.

## Setting
- `scholarchain.run.partitionCount`, default 4, passed as Hadoop generic option `-Dscholarchain.run.partitionCount=<n>`. `PipelineTool` runs through `ToolRunner`; `PipelineOptions.runContext` reads it; `JobSupport.configureOutput` applies `job.setNumReduceTasks(run.partitionCount)`. Read from `PipelineOptions.scala`, `JobSupport.scala`; `DblpExtractionJob` confirmed to call `configureOutput`; the other six stages not checked by reading (the stage tests below cover them). `-D` also changes the number of part files per artifact.
- Split size already configurable: `-Dmapreduce.input.fileinputformat.split.maxsize=<bytes>`, honored by `DblpXmlInputFormat` (`MapReduceEndToEndSuite` uses it).

## BLOCKED-ON infra-engineer
`scripts/emr/run-s-pipeline.sh` does not pass the option. Add, before the positional paths next to the other `-D` lines, only when `SC_PARTITION_COUNT` is a positive integer (unset adds nothing, default stays 4):
```
"-Dscholarchain.run.partitionCount=${SC_PARTITION_COUNT}"
```
Optionally the same for `-Dmapreduce.input.fileinputformat.split.maxsize`.

## Tests per gate item (all 8 in new `modules/hadoop/src/test/scala/scholarchain/hadoop/PartitionCountSettingSuite.scala`)
- "the setting name is the documented generic option"
- "the default partition count stays 4 when the option is absent"
- "the generic option sets the partition count of the run context"; "the tool plan carries the option into the run context"
- "every stage reduces with the default count without the option"; "every stage reduces with the configured count" (16)
- "the default run and an explicit 4 write byte-identical output"
- "a larger partition count runs the fixture to the same records" (16 vs 4)

## Results
- New suite 8 of 8 passed (from `target/test-reports`).
- First `sbt quick` exited 1 (two new tests used an `s3://` output root; fixed to a local temp directory). Second `sbt quick`: `[success] Total time: 116 s (0:01:56.0), completed Nov 16, 2026, 6:53:33 PM`; last module group `Passed: Total 73, Failed 0, Errors 0`. Hadoop-group total not extracted (guard blocked reading the output). Two runs instead of one; never concurrent.
- A `format-scala.sh` hook failed once because another sbt was running; the next edit reformatted the file; `sbt quick` included scalafmtCheck and passed.

## Open items and risks
1. `LocalJobRunner` collapses reducers above 1 to one; no test proves multi-reducer output. Effect on YARN UNVERIFIED until the S run.
2. Experiment B may not scale usefully: `run-s-pipeline.sh` stages inputs and MapReduce output on the primary node's local disk through `file:///` URIs and "breaks on a second node". Containers on added nodes cannot read those paths. Settle before B (task capacity that reaches the data, or move the Hadoop stage to HDFS or S3).
3. The runner's YARN job-count verdict expects 1 + datasets + 5 jobs; reducer count does not change it.
4. No `slowSuites` change; the suite takes about 5 s.
