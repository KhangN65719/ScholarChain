# Phase 14 pipeline runs 6 to 11 (tier S)

Record author: deploy-engineer (WO 115). Records only, written afterwards from `runs/s-N/*.record.env`, `approvals/pipeline-s-N.env` and `logs/phase14-s-run-*.log` in `scholarchain-data/`. No command was run to write it.

Reading notes:
- Execution ARN pattern: `arn:aws:states:us-east-2:<account-id>:execution:scholarchain-course-pipeline:run-<start UTC compact>-<size>`.
- All six runs used run id `run-v1-192de0fd-1442-4508-9065-09c44775643b`. Every run record shows `RECORD_COMPLETE=yes`, a stop rule of first failure or non-empty DLQ, and a 20 USD environment cost ceiling.
- "Estimated spend" is the human-supplied hourly burn multiplied by elapsed time. It is not a bill. Actual spend is owed by the human from AWS billing. It also excludes the idle hours between runs; see `README.md`.
- Which image a run used is stated where the ECS task record was read (runs 7 and 8). For the others it is inferred from the deploy order and timing.
- Pipeline script and args digests are given as 16-character prefixes; full values are in the record files.
- Input files live in `scholarchain-data/pipeline-inputs/`. Loader log timestamps carry no zone; they match the execution windows in UTC, so I read them as UTC.

| Run | Mode, size | Start UTC | End UTC | Elapsed | Final | Est. spend |
|---|---|---|---|---|---|---|
| 6 | pipeline (EMR then loader), large | 2026-11-15T19:21:11Z | 2026-11-15T20:48:22Z | 87.2 min | FAILED | 0.90 USD (0.62 USD/h) |
| 7 | loader-only, large | 2026-11-16T02:42:11Z | 2026-11-16T03:47:03Z | 64.9 min | FAILED | 0.29 USD (0.27 USD/h) |
| 8 | loader-only, large | 2026-11-16T08:33:33Z | 2026-11-16T09:20:47Z | 47.2 min | FAILED | 0.21 USD (0.27 USD/h) |
| 9 | loader-only, graph-only, small | 2026-11-16T17:15:01Z | 2026-11-16T17:18:09Z | 3.1 min | FAILED | 0.02 USD (0.37 USD/h) |
| 10 | loader-only, graph-only, small | 2026-11-16T20:15:35Z | 2026-11-16T20:18:42Z | 3.1 min | FAILED | 0.02 USD (0.37 USD/h) |
| 11 | loader-only, graph-only, small | 2026-11-16T21:32:22Z | 2026-11-16T21:37:03Z | 4.7 min | SUCCEEDED | 0.08 USD (0.97 USD/h) |

Sum of the six per-run estimates: 1.52 USD (executions only).

## Run 6: FAILED (loader timeout)

- Execution: `...:run-20261115T192111Z-large`. Used deploy session-10. Approved 2026-11-15T19:02:43Z. Ceiling 1.20 USD, 110 minutes max.
- Input: `s-run-6.json`, 292 bytes, sha256 `858233c7d63efbd1befe065fb02e9a054f0962b73ef7b848f1e575850de7a21c`. Dataset snapshot `phase5-dblp-subset` with snapshots dblp=2026-11-05, openalex=2026-11-07, crossref=2026-11-07, orcid=2026-11-07. Script digest prefix `7ea56ce24da633e6`, args digest prefix `4ae3f4a538d77de3`.
- Outcome: the Step Functions history ends with `TaskTimedOut` (`States.Timeout`) on the ECS `runTask.sync` loader step, then `PipelineFailed`. The export had been written to the semantic-context prefix before the loader started (26 export objects seen by the loader at 20:18:50Z). The loader log shows only its input line.
- Vector state at failure (`phase14-s-run-6-vector-count.log`, time not recorded): `researcher-profile-v1` 16,000 vectors, the other four indexes 0.
- The loader hit its 1,800 s cap. Sources: `docs/agent-runs/phase-14/decisions-d36.md:10` ("`States.Timeout` at its 1,800 s cap (`ecs runTask.sync`)") and `infra/terraform/environments/course/experiment-size-s.tfvars` lines 73 to 76 and 101 (the S loader cap was 1,800 s until work order 86 raised it to 5,400 s). That fits the logs: the loader's first line is at 20:18:50Z and the execution ended at 20:48:22Z.
- Progress at the cap, from D36 lines 11: 16,162 documents of 41,885 summary rows written, serially at about 9 items per second. The loader was healthy but slow, not hung.
- Teardown check: no active EMR cluster.

## Run 7: FAILED (loader exit 1; PutVectors rejected records with empty arrays)

- Execution: `...:run-20261116T024211Z-large`. Used deploy session-11. Approved 2026-11-16T02:30:34Z. Ceiling 0.50 USD, 95 minutes max.
- Input: `s-loader-7.json`, 176 bytes, sha256 `c34042a7fe61c9e106e09cbdba3fd30c30392e92bb472ec56d220dfa2b138b23` (reused by run 8). Script digest prefix `b3d0cdce96ad62eb`, args digest prefix `0a5626dc02cb9d60`.
- ECS task record: loader image tag `2cc20a86...`, task definition revision 12, 512 CPU and 1024 MiB, container exit code 1.
- Cause as recorded in the run ledger: S3 Vectors `PutVectors` returned HTTP 400 `ValidationException` ("Empty arrays are not allowed in metadata") for batches in `collaboration-community-v1`. The first page of the ledger query lists 16 such failures; the output is paginated, so the total is not known from the file.
- I did not read the loader's final stderr lines, only the ledger and the task record.
- Fix shipped in the next deploy (session-12): omit empty evidence arrays from vector metadata.

## Run 8: FAILED (graph phase OutOfMemoryError on the 1 GB loader task; vector ingest completed first)

- Execution: `...:run-20261116T083333Z-large`. Used deploy session-12. Approved 2026-11-16T08:32:51Z (the same second as the session-12 deploy approval; the approval records are separate files). Ceiling 0.50 USD, 95 minutes max.
- Input: `s-loader-7.json` (same file and digest as run 7).
- ECS task record: loader image tag `02437a90...`, digest `sha256:7798a86f53a7df6aabe8e161ff2ca00a6f69f5bcf6daed8fc3aa631ce0c8702d`, task definition revision 13, 512 CPU and 1024 MiB, exit code 1.
- Vector ingest: phase `ingest` done at 2554 s; progress shows 41,885 of 41,885 items, `failed_batches=0`, 6,725 written in this run and 35,160 skipped (already present from earlier runs). The loader then ran sample queries against the indexes.
- Failure: `java.lang.OutOfMemoryError: Java heap space` at 09:20:17Z, 3 minutes 43 seconds into the graph phase.
- Led to session-13 (loader 2048 CPU and 8192 MiB).

## Run 9: FAILED (graph-only; edges-COAUTHORED_WITH bulk load LOAD_FAILED, insert errors)

- Execution: `...:run-20261116T171501Z-small`. Used deploy session-13 (inferred from timing). Approved 2026-11-16T17:14:09Z. Ceiling 0.40 USD, 45 minutes max.
- Input: `s-graph-only-1.json`, 191 bytes, sha256 `ec4d178cfd114f0d71838560f9d22cc8e62592f3e6373dd7ec05cbb9ba263094`. Script digest prefix `4b6ca5d7fa02a30d`, args digest prefix `ccbe160d452051dd`.
- Loader log: download, open, project and export phases completed (30 seconds), then `NEPTUNE_LOAD=FAILED`: the Neptune bulk load of `edges-COAUTHORED_WITH.csv` ended `LOAD_FAILED` with records 92,256, duplicates 0, parsing errors 0, datatype mismatches 0, insert errors 3. Loader elapsed 136 s. `LOADER_RESULT=FAILED`.
- The vector ingest was not run (graph-only).

## Run 10: FAILED (graph-only; Neptune OUT_OF_MEMORY_ERROR on the db.t4g.medium writer)

- Execution: `...:run-20261116T201535Z-small` (the record file name carries `T201534Z`). Used deploy session-14 (inferred from timing). Approved 2026-11-16T20:15:18Z. Ceiling 0.40 USD, 45 minutes max.
- Input: `s-graph-only-2.json`, 216 bytes, sha256 `b37ff40b51303eeb1528c4d38e4408dc57b8354b6595151f9ea344899899c302`. Script digest prefix `4b6ca5d7fa02a30d`, args digest prefix `bbfc4c017be8cab8`.
- Loader log: Neptune job 9 (`edges-COAUTHORED_WITH.csv`) reported `OUT_OF_MEMORY_ERROR` ("Out of memory error. Resume load and try again."). `NEPTUNE_LOAD_ERRORS_TOTAL n=153760 named=1`. Job status `LOAD_FAILED`; the loader treats this error as never tolerated. Loader elapsed 122.9 s. `LOADER_RESULT=FAILED`.
- Run 10's report in S3 (`<loader prefix>/20261116T201756Z/graph-report.json`), read by the human with a status-only filter: 12 jobs, jobs 1 to 8 `LOAD_COMPLETED`, job 9 `LOAD_FAILED`, jobs 10 to 12 (DERIVED_FROM, PUBLISHED_IN, RESOLVES_TO) `LOAD_COMPLETED`. The loader log has no per-job lines for this run, so this is the evidence that the loop continued past job 9. Per-job counts in the report were not read.
- Led to session-15 (writer to `db.r6g.xlarge`).

## Run 11: SUCCEEDED (graph-only; NEPTUNE_LOAD=LOADED, 12 jobs LOAD_COMPLETED, 0 errors)

- Execution: `...:run-20261116T213222Z-small`. Used deploy session-15 (inferred from timing; the writer was `db.r6g.xlarge` by the read-only check). Approved 2026-11-16T21:32:08Z. Ceiling 0.80 USD (projected 0.73), 45 minutes max, burn 0.97 USD/h.
- Input: `s-graph-only-2.json` (same file and digest as run 10). Script digest prefix `4b6ca5d7fa02a30d`, args digest prefix `159e47425b15ee86`.
- Step Functions final status `SUCCEEDED` after 280 seconds of polling. Teardown check: loader-only, no cluster created.
- Loader log: `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788 files=12 jobs=12`. All 12 Neptune jobs `LOAD_COMPLETED` with `errors=0`: seven vertex files (AuthorMention, DatasetSnapshot, Institution, Researcher, Topic, Venue, Work) and five edge files (AUTHORED, COAUTHORED_WITH, DERIVED_FROM, PUBLISHED_IN, RESOLVES_TO). `LOADER_RESULT=SUCCESS`. Loader elapsed 208.3 s.
- The vector ingest was not run in this run (graph-only). Its last evidence is run 8.

## Limits of this evidence

- The graph load is evidenced only by the loader's own report lines and Neptune's job statuses relayed through the loader. Neptune cannot be queried from outside the VPC and no independent query was made. Neptune was destroyed in `deploy-down-5` without a snapshot.
- Run 11 shows `duplicates` equal to `records` for 11 of the 12 files, and `duplicates=0` for `edges-COAUTHORED_WITH.csv` (1,578,576 records). The duplicate counts are not established by the logs. The loader runs one job at a time, vertex files first, then edge files (`modules/storage/src/main/scala/scholarchain/storage/AwsNeptuneBulkLoader.scala` lines 171 to 184 and 452 to 472). `edges-COAUTHORED_WITH.csv` is job 9 of 12 (run 10 log: `job=9`). What each run did after job 9 depends on `failOnError`:
  - Run 9 ran without `--tolerate-load-errors`, so `failOnError` was true (`AwsIngestMain.scala:307`, `failOnError = !config.tolerateLoadErrors`). The loader stopped at job 9 with `BulkLoadFailed`. Jobs 1 to 8 (seven vertex files and `edges-AUTHORED.csv`) completed in run 9 (INFERENCE from the stop-at-first-failure rule).
  - Run 10 ran with `--tolerate-load-errors` (input file), so `failOnError` was false. With `failOnError` false the loader returns a `LOAD_FAILED` job that has counted errors instead of throwing (`RecordErrorStatuses`, `AwsNeptuneBulkLoader.scala:32`; decision at lines 466 to 470), so the loop continued to jobs 10 to 12 (`edges-DERIVED_FROM.csv`, `edges-PUBLISHED_IN.csv`, `edges-RESOLVES_TO.csv`). The rag policy rejected the result afterwards because `OUT_OF_MEMORY_ERROR` is never tolerated. Supporting log facts: 36 seconds of loading between the job 9 error lines (20:17:20) and `graph-load done` (20:17:56), and only `edges-COAUTHORED_WITH.csv` has a `NEPTUNE_JOB_STATUS` line.
  - SHOWN by run 10's report: the human read run 10's `graph-report.json` in S3 (`<loader prefix>/20261116T201756Z/graph-report.json`, no bucket name given here) with a status-only filter. It lists 12 jobs: jobs 1 to 8 `LOAD_COMPLETED`, job 9 (`edges-COAUTHORED_WITH.csv`) `LOAD_FAILED`, and jobs 10 to 12 (`edges-DERIVED_FROM.csv`, `edges-PUBLISHED_IN.csv`, `edges-RESOLVES_TO.csv`) `LOAD_COMPLETED`. So jobs 1 to 8 and jobs 10 to 12 all completed a load in run 10, before run 11. That covers each of the 11 files for which run 11 shows `duplicates` equal to `records`, and it is shown by the report's statuses; it does not depend on run 9. The `LOAD_FAILED` hedge (returned, not thrown, with `failOnError` false) ran on real Neptune in run 10. The per-job counts in that report were NOT read, and run 11's report (`<loader prefix>/20261116T213609Z/graph-report.json`) is still unread.
  - INFERENCE only: run 9 also completed jobs 1 to 8 (from the loader code and the run 9 log; run 9's report was not read). That run 11's `duplicates` equal to `records` for 11 files are CAUSED by those earlier loads is also an inference, consistent with the statuses but not shown.
  - Not explained: the AWS documentation defines openCypher `duplicates` only for node-file rows, so the duplicate counts on the edge files (AUTHORED, DERIVED_FROM, PUBLISHED_IN, RESOLVES_TO) are not predicted by the documentation. `edges-COAUTHORED_WITH.csv` shows `duplicates=0` in run 11 although run 9 processed 92,256 records of it and run 10's job 9 failed again. A failed job's partial data may not have been kept; that was not verified.
  - Whether every row of every file was inserted is UNVERIFIED. `docs/agent-runs/phase-14/decisions-d38.md` (section "S run 11") and `docs/agent-runs/phase-14/results-s-tier.md` (section "Graph load counts") describe the duplicate counts as consistent with earlier loads, treat causation as an inference and cite run 10's report statuses; this record says the same.
- The same cluster received three load attempts (runs 9, 10, 11); the contents of that cluster were never read back.
- The loader's totals (32,225 vertices, 135,788 edges) are not the same quantities as the per-file Neptune record counts; they are not reconciled here.
- Runs 9 to 11 log `RUN_ID_MATCH=false`. This is expected, per `docs/agent-runs/phase-14/report-53-storage-engineer-loader-path-check.md` section 3 (lines 73 to 86): no code enforces equality, the loader takes the run id from the export manifest (`run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`, the Spark run id), and the pipeline argument is `run-v1-192de0fd-...`. The consequence to keep: the graph in Neptune is named for the Spark run `9f6e6fb0` (the loaded CSV prefix is `graph/neptune/v=v1/run=run-v1-9f6e6fb0-.../`), while the loader reports and run records belong to the `192de0fd` run id (`reports/runs/run-v1-192de0fd-.../loader/`). Report 53 notes that the marker is permanent and uninformative, so it is not a failure signal.
- The `--tolerate-load-errors` flag in runs 10 and 11 is evidenced only by the input file and its digest. The pipeline script prints a marker only for `--graph-only`; see `README.md`, open items.
- Runs 6 to 8 reported FAILED; there is no successful end-to-end run (EMR, vectors and graph in one execution) in this record. Vector ingest completed in run 8; graph load completed in run 11; they were separate executions.
