# Work order 14: spark-engineer, how the pipeline stages take paths and run on EMR (read-only analysis)

Owner: spark-engineer (modules/spark/**). Read-only: no edits, no sbt, no AWS, no commit.

Evidence: docs/agent-runs/phase-14/decisions-d13-d14.md (D16), reports-11-12-13.md (report 11 gaps 2 and 3). Entry points: Spark `scholarchain.spark.CollaborationPipeline` (modules/spark/.../CollaborationPipeline.scala:123), entity resolution `scholarchain.er.ErBatch` (modules/entity-resolution/.../ErBatch.scala:773; read-only for you, owned by entity-resolution-engineer), Hadoop `scholarchain.hadoop.jobs.PipelineTool` (modules/hadoop/.../PipelineDriver.scala:244).

Questions (answer from code, mark UNVERIFIED anything not read):
1. Exact command-line arguments and required environment of CollaborationPipeline, ErBatch and PipelineTool, and the data flow between them: what each reads and writes (directory layouts, file formats), who produces Spark's `--er-run`, `--hadoop-root` and `--out-root` inputs, and in what order the three run locally (see the earlier local real-data runs: phase4-out, phase6-out, phase8-out under /Users/winnguyen/scholarchain-data, listings only).
2. Does Spark (and ErBatch) use `java.nio.file.Path` only? Could they read `s3://` through Hadoop `FileSystem` on EMR, or must the data be on a local or HDFS path? State the smallest change (class, function, lines) that lets Spark run on EMR with S3 or HDFS paths, or conclude that a local-disk approach is simpler.
3. Smallest viable EMR design for the S run, with options and their risk: (a) three EMR steps (Hadoop, ER, Spark) reading and writing S3 or HDFS; (b) one script step on the single EMR node that copies the inputs from S3 to local disk, runs the three tools in order against local paths and copies results to S3 (needs `aws s3 cp` on the node and a script uploaded to the artifacts bucket); (c) anything simpler. For each: what the workflow module (infra-engineer) and the job code (hadoop, ER, spark owners) would change, the expected runtime on one m5.xlarge at 3,472 works, and what could fail.
4. What the loader (`scholarchain.rag.IngestMain`, rag module, read-only for you) expects from the pipeline outputs (paths, format) so the chain closes.

No guesses at class names or flags. You cannot run bash (guard D11); state nothing was executed.

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS (1 to 4), RECOMMENDATION, RISKS, BLOCKED-ON OWNER, NEXT GATE.
