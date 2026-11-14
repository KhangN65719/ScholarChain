# Work order 15: build-engineer, job jars for EMR and the loader image (read-only analysis)

Owner: build-engineer (build.sbt, project/**, packaging/**, Dockerfile). Read-only: no edits, no sbt, no docker, no AWS, no commit.

Evidence: docs/agent-runs/phase-14/decisions-d13-d14.md (D16), reports-11-12-13.md (report 11 gap 1, report 12). Entry points: Hadoop `scholarchain.hadoop.jobs.PipelineTool`, entity resolution `scholarchain.er.ErBatch`, Spark `scholarchain.spark.CollaborationPipeline`, loader candidate `scholarchain.rag.IngestMain`.

Questions (answer from build.sbt, project/** and packaging/**; mark UNVERIFIED anything not read):
1. What `packageJobs` and `stageJob` produce for each job module: a thin jar plus `lib/`, or something else; whether any `Main-Class` or assembly exists. Which dependencies EMR already provides (Hadoop and Spark libraries; the EMR release in the Terraform emr module) and which of ours are missing at runtime (core, upickle, others).
2. The smallest build change that makes each job runnable on EMR: a fat (assembly) jar per job module with Hadoop and Spark marked `provided`, versus `hadoop jar ... -libjars` with the `lib/` jars uploaded, versus `spark-submit --jars`. Say which dependency ledger entries or plugins this needs (project/DependencyVersions.scala is dependency-updater's for values; plugins in project/plugins.sbt are yours), the effect on `sbt check` and `sbt quick` runtime, and a risk of dependency conflicts (Scala 3 with Spark; the Spark Scala version).
3. For the loader: the services image classpath is `lib/*` with `java -cp lib/*`. Confirm from build.sbt that `rag` classes (IngestMain) are in `target/docker-local/lib` via the services runtime classpath, and that no separate loader context is needed.
4. A test or check the human could run to prove a jar is complete without AWS (for example `java -cp ... <MainClass> --help` or listing jar contents), plus how the proposal changes `package.sh` and the `deploy.sh` context digest.

You cannot run bash (guard D11); state nothing was executed.

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS (1 to 4), RECOMMENDATION, RISKS, BLOCKED-ON OWNER, NEXT GATE.
