# Work order 22: hadoop-engineer, PipelineTool launch contract on EMR (read-only)

Owner: hadoop-engineer (modules/hadoop/**). Read-only: no edits, no sbt, no AWS, no commit.

Evidence: docs/agent-runs/phase-14/decisions-d13-d14.md (D21), report-19-infra-engineer-compare-designs.md item 8, reports-14-15.md. Source: modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/PipelineDriver.scala (PipelineTool at line 244, main at 344) and the job classes it calls.

Questions, answered from code and marked read or UNVERIFIED:
1. Does `main` call `ToolRunner.run`, so that `-D` and `-libjars` generic options work with `hadoop jar <thin jar> scholarchain.hadoop.jobs.PipelineTool`? Exact argument syntax with an example for the 3,472-work subset (the four dataset files under datasets/subset/, the DBLP input dblp-subset.xml; `-Dscholarchain.run.snapshots=...` naming exactly the supplied datasets).
2. Do the paths accept URIs (`file:///...`, `hdfs://...`, `s3a://...`) or only plain local paths (java.nio.file or Hadoop Path)? Which classes read and write them (the sink, ArtifactSink, InputFiles), and what does a MapReduce job on YARN on ONE node require (inputs readable by the tasks, output on a filesystem the driver can later read with java.nio)? State what would break on YARN with `file:` paths and the default filesystem left as HDFS.
3. Which Hadoop APIs does the code use that exist in 3.5.0 but not 3.4.1 (the ledger pins hadoop 3.5.0 as Provided; the cluster has 3.4.1-amzn-2)? From the sources only; say what you cannot check without the jars.
4. A fixture for the capability check: the smallest input (one record) that PipelineTool accepts end to end, or say that none exists and what the smallest valid input is. Name the files in the repository (datasets/ or tests/) you would use.
5. Does the job run in-process as a MapReduce job (Job.waitForCompletion) or does it use LocalJobRunner by configuration? Report the `mapreduce.framework.name` handling, so the report can state "MapReduce on YARN" or "MapReduce in local mode on an EMR node" truthfully.

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS (1 to 5), RISKS, UNVERIFIED LIST, NEXT GATE.
