# Report for work order 51 (spark-engineer, read-only): Spark partitions and run id

Archived by the orchestrator as received from spark-engineer (2026-11-15 UTC). The agent's tooling note: the Grep and Glob tools did not exist in its session and the guard blocked Bash `grep`, so it used only `Read` and `ls`; its log counts are sampled, not counted. No file was edited, no sbt, no AWS command, no commit.

STATUS: COMPLETE for the read-only investigation. Four items are not proven, and the report names each one (no grep, no log counts, no Futures scan, no ER or IngestMain read).

OWNER: spark-engineer
WORK ORDER: 51

## FINDINGS

### 1. Partitions

**Where the settings come from**
- **Shuffle partitions:** the value is `config.partitions`. `--partitions` has default 8 and range 1 to 10000 (`modules/spark/src/main/scala/scholarchain/spark/Config.scala:179`). It is applied with `session.conf.set("spark.sql.shuffle.partitions", config.partitions.toString)` (`CollaborationPipeline.scala:229`).
- **Other settings:** AQE is hard-coded off (`CollaborationPipeline.scala:230`) and `autoBroadcastJoinThreshold` is hard-coded to -1, so there are no broadcast joins (`:231`).
- **Not set anywhere:** `spark.default.parallelism` does not appear in `CollaborationPipeline.scala`, so Spark's default applies. Reading all of `modules/spark` for it was not possible without grep.
- **Repartition and coalesce:** the only ones I saw are in `Export.scala:155-156`. They are `repartition(col("window"))` for windowed tables and `repartition(1)` for the rest. A full-module scan was not possible.
- **No `--partitions` passed:** `run-s-pipeline.sh:320-321` passes only `--er-run`, `--hadoop-root` and `--out-root`. So the effective value is 8.

**What the EMR config sets**
- `scripts/emr/spark-yarn.conf` has no partition, parallelism or AQE keys. It holds only `POPULATE_HADOOP_CLASSPATH`, `DEPLOY_MODE`, the memory keys, `EXECUTOR_CORES` and `EXECUTOR_INSTANCES`.
- `write_spark_conf` (`lib-emr.sh:453-486`) writes a fixed list of keys. Dynamic allocation is off and the other keys are memory, cores, instances, codecs and umask. It also writes no partition, parallelism or AQE keys.
- `conf_value` reads only keys the script asks for by name. An extra key in `spark-yarn.conf` would be ignored.

**Which wins**
- The code wins for the three keys it sets: `spark.sql.shuffle.partitions`, `spark.sql.adaptive.enabled` and `spark.sql.autoBroadcastJoinThreshold`.
- They are set with `session.conf.set` after the session exists, which is a runtime SQL conf, not a builder `.config`. That overrides `spark-defaults.conf` and anything passed on `spark-submit`.
- Yes, the partition count is hard-coded in effect. A `spark.sql.shuffle.partitions` entry in `spark-yarn.conf` or `spark-defaults.conf` would have no effect. Only `--partitions` changes it.

**What the log shows**
- **Tasks per stage:** `Submitting 8 missing tasks ... Adding task set 2072.0 with 8 tasks` (log line 70030-70031). Stage 2073 completed as (8/8) at line 70009. Some stages have 32 tasks (stage 3509, `(32/32)` at line 120025). The 32-task stages are most likely the Parquet scans of checkpoint files (`Support.scala:93`), but I did not confirm that.
- **Task duration:** typically 45 to 250 ms. Examples are 48 ms, 47 ms and 56 ms at lines 70002-70007, and 49 to 209 ms at lines 120001-120025.
- **Task rate:** TID 13845 was at 05:21:51 and TID 24078 at 05:29:30. That is about 10,200 tasks in 459 s, roughly 22 tasks/s. This is my estimate from two samples.
- **Not counted:** I did not count the distinct stages or the total tasks, and I did not read any 'Getting N non-empty blocks' lines.
- **Executors:** two executors registered at 05:08:38 with 2 cores each, so 4 task slots (log lines 12-13 and 115-116). The node-manager container limit is 12288 MB (line 38).
- **End of run:** the process shut down at 05:33:18 (line 142854), which is 1,480 s after the executors registered. The line-142870 FAILED message, `IllegalStateException: Cannot call methods on a stopped SparkContext`, is a consequence of the `timeout` kill. It is not an independent fault.
- **RejectedExecutionException** (line 142876): its Future/`ExecutionContextImpl` trace is Spark and Scala internals during shutdown.

### 2. Run id

**How the directory name is derived**
- **Spark run id:** it is neither generated nor taken from an argument. `RunContext.runIdFor(seed)` is a SHA-256 hash of the seed (`Inputs.scala:113-117`). The seed is the input digests, the ER manifest digest, the config JSON, `transformVersion` and the main-sources digest (`Inputs.scala:102-110`, `CollaborationPipeline.scala:277-283`).
- **Directory name:** `run=<runId with ':' replaced by '-'>` (`Inputs.scala:119`).
- **Consequence:** `run=run-v1-6cc96e2e-...` is deterministic for these inputs, config and code. It has no relation to the script's `run-v1-882f7ab3-...`.
- **Hadoop run id:** the Spark log line 125 prints `hadoop-run: run:v1:882f7ab3-...`, which matches the script's id. It comes from the Hadoop root's parent directory name (`CollaborationPipeline.scala:192-199`). The pipeline reads the ER run id from the ER manifest (`:270`), which is where `er-run: run:v1:f7611f8c-...` (log line 124) comes from.
- **ER side:** I did not read how ErBatch derives its run id.

**What the loader reads**
- **Entry point:** `AwsIngestMain` is in `modules/rag/src/main/scala/scholarchain/rag/AwsIngestMain.scala`.
- **Prefix read:** it takes one argument, the run id. It lists and downloads only `semantic-context/runs/<runId>/export/` (`:78-79`, `:241-253`). It never reads `graph/runs/`.
- **Internal directory:** it does not need the `run=<id>` directory name.
- **Manifest handling:** `JsonlExportSource.open` needs `_SUCCESS` and `export-manifest.json` at the export root. It uses the manifest's `sourceRunId` internally (`modules/storage/src/main/scala/scholarchain/storage/JsonlExportSource.scala:163-193`).
- **Mismatch behavior:** if the manifest's `sourceRunId` differs from the argument, the loader only prints `RUN_ID_MATCH=false export_run=... argument=...` (`AwsIngestMain.scala:504-508`). That is informational.
- **Graph step:** it uses `source.sourceRun.runId`, which is the Spark id, not the argument (`:455`).

**Would it find what the script publishes**
- `run-s-pipeline.sh:342` has JsonlExport write to `$xout/export`.
- `:366` copies `$xout` to `s3://<data>/semantic-context/runs/<run_id>/`. The copy is recursive and excludes only `_work/*` (`lib-emr.sh:832`).
- The result is `semantic-context/runs/run-v1-882f7ab3-.../export/...`, which is exactly `exportPrefix` for argument `run-v1-882f7ab3-...`.
- That holds provided `_SUCCESS`, the manifest and the part files are under `export/`. I did not read JsonlExport's writer, so that part is unverified.

**Gaps**
- Whether `IngestMain.execute` enforces run-id equality is unverified. I did not read it.
- Whether the export manifest's `sourceRunId` equals the Spark run id is unverified. I did not read `JsonlExport`.

### 3. Concurrency and why two executors do not help

**Concurrent submission in the code**
- I found no `Future`, `.par` or `ExecutionContext` in the files I read: `CollaborationPipeline` through line 400, `Config`, `Export`, `Inputs`, `Support` and `Summaries` (head only).
- Not read: the rest of `CollaborationPipeline` (400 onward), `Coauthorship`, `GraphAlgorithms` and `Temporal`. Absence there is unproven.

**What the log and code show instead**
- **Scheduling:** `Algo.checkpoint` (`Support.scala:89-94`) is an eager Parquet write plus read-back. `Support.scala:93` appears in the log as `parquet at Support.scala:93`.
- **Concurrent stages:** the log shows stages running concurrently inside one job, for example `running: HashSet(ShuffleMapStage 2070, 2076, 2079)` at line 70013. That is Spark's own DAG parallelism, not user-code concurrency.
- **Workload shape:** by the log's stage ids, the run was at about stage 4047 and Job 392 when killed. The tasks are tiny (45 to 250 ms) and arrive in sequence. The work is dominated by driver-side planning, scheduling and checkpoint writes, so adding an executor adds nothing.
- **Executors:** the driver and both executors share the one node's 4 vCPUs, and the driver reaches executors over 127.0.0.1. That is my inference; I did not measure the driver.
- **Block manager:** `spark.driver.memory` is 2g (`spark-yarn.conf:26`). The driver block manager shows 1048.8 MiB (log line 111).

## SCALA CHANGE NEEDED

**No**, for completing the run and for the loader. The loader finds the data by the script's run id alone. The Spark `run=` directory name is irrelevant to it. A Scala change is only warranted for run speed (fewer jobs, stages and checkpoints), and that is a separate work order.

## CONFIG-ONLY FIX POSSIBLE

**Yes** for finishing the run. The partition count cannot be fixed in `scripts/emr/spark-yarn.conf` alone, because no key there reaches Spark.
1. **Raise the cap:** raise `SC_SPARK_TIMEOUT`, currently the 1500 s default at `lib-emr.sh:504`, together with the outer step cap. Both are outside `spark-yarn.conf`, and the infra-engineer owns them. Run 4 reached about 70% of stages in 1,480 s, so I estimate 2,100 to 2,400 s with margin. That is an estimate, not a measurement.
2. **Fewer shuffle partitions:** the app argument `--partitions 4` would match the 4 slots and halve the task count. I did not measure the benefit, and tasks are already short, so the gain may be small. This is a one-line script change at `run-s-pipeline.sh:320-321`. It changes the config text, so the Spark run id changes. That is harmless because the loader does not use it.
3. **Keys that would have no effect:** no `spark-yarn.conf` key for shuffle partitions, parallelism or AQE would do anything. `write_spark_conf` does not emit them and the code overrides them.

I do not recommend raising partitions or adding executors. Neither would speed up this workload.

## COMMANDS RUN

Read-only: `Read` on the files cited above, and Bash `ls` on `modules/spark/src/main/scala/scholarchain/spark`, `scripts/emr` and the rag and storage source directories. One Bash `grep` was attempted and refused by the guard. No file was edited, no sbt, no AWS command, no commit. Git tree untouched.

## RISKS

- The per-stage and total counts are sampled, not counted (see Tooling limit). The "70% of stages" figure is the work order's, not mine.
- The Futures and parallel-collection scan is incomplete for the files listed in finding 3.
- Raising only the timeout may still run into the outer step cap. The infra-engineer should check both.
- I did not examine why the S run is about 3.6 times slower than the local Phase 6 run (1,480+ s vs 405 s) apart from the symptoms above. No profiling was done.

## NEXT GATE

The orchestrator decides between:
1. a script-only rerun with a higher `SC_SPARK_TIMEOUT` and step cap, and optionally `--partitions 4`, routed to infra-engineer; or
2. a spark-engineer work order to cut job and stage count. I recommend it only if a longer timeout is not enough.

If the orchestrator wants the open items closed, a follow-up with grep access could count stages and tasks, scan the rest of `modules/spark` for Futures, and read `IngestMain`, `JsonlExport` and `ErBatch`.
