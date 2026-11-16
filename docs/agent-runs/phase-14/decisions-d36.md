# Phase 14 decision D36 (2026-11-15 UTC): S run 6 outcome, the loader timeout, and the loader-only rerun. Recorded by the orchestrator from the human's `!` runs and the agents' reports. The human ratifies. No account ID in this file.

## What S run 6 proved (real data, tier S, 3,472 works; evidence copied to `/Users/winnguyen/scholarchain-data/runs/s-6/reports/`)
- Execution `run-20261115T192111Z-large`, run id `run-v1-192de0fd-1442-4508-9065-09c44775643b`, HEAD 7d9de22. Start 19:21:11Z, end 20:48:22Z, 87.2 min. Final status FAILED at the `RunLoader` state, not at EMR.
- The EMR step succeeded and the cluster terminated (`NO_ACTIVE_CLUSTER`). On the node, from `run-record.env`: preflight `PREFLIGHT_RESULT=ok` in 21 s (targets hadoop, er, spark, export all ok; smokes old-api and parquet ok; the node's `mapred.output.committer.class` DirectFileOutputCommitter was overridden by ours and the old-API smoke passed); Hadoop 727 s with 9 MapReduce applications on the YARN ResourceManager (`MAPREDUCE=yarn`, `MAPREDUCE_ATTENTION=no`); ER 104 s; Spark 1,735 s (`application_..._0010 SUCCEEDED`, `SPARK_ON_YARN=client:yes`); export 65 s (`application_..._0011 SUCCEEDED`, `EXPORT_ON_YARN=client:yes`). `STAGE_SECONDS_TOTAL_BEFORE_PUBLISH=2706`. The export stage that failed in S run 5 (D34) passed.
- Published to S3 for the run id: `curated/` 238 objects, 1,195,432,130 bytes; `graph/` 201 objects, 86,035,837 bytes; `semantic-context/` 26 objects, 324,156,384 bytes; `reports/` 25 objects, 29,232,130 bytes (counts from `aws s3 ls --summarize`).
- Estimated spend by the script: 0.90 USD (burn times time, not a bill). Billing for the sessions is still owed by the human.

## Why the loader failed (diagnosed from S3 and the code, not from loader logs)
- The loader logged one line, `LOADER_INPUT ... objects=26` at 20:18:50, and nothing else until `States.Timeout` at its 1,800 s cap (`ecs runTask.sync`). The ingest prints nothing until it ends.
- Evidence of progress: `semantic-context/documents/` held 16,162 objects (24.7 MB) and `embedding-work/` held the 24 MB work manifest. The export manifest says 41,885 semantic-summary rows. `EmbeddingIngest.runBatch` embeds one text per Titan call, serially, then writes the document, so 16,162 documents is about 39% done in about 1,760 s: about 9.2 items per second (about 109 ms each). Serial total about 4,560 s (76 min); remaining about 2,800 s plus a few minutes of skip checks on rerun.
- The hypothesis "the first Titan or S3 Vectors call hung through the endpoint" is not supported: Titan access and the Bedrock endpoint worked, and documents were written.
- Not proven: S3 Vectors `PutVectors`, the vector count, the retrieval check, the Neptune bulk load.
- After the failure the human confirmed no ECS tasks were running, and after the teardown (`plan-down-4`, `deploy-down-4`, `DEPLOY_COMPLETE`, plan digest 3a888bcc...) no Neptune cluster, only the S3 and DynamoDB gateway endpoints, no active EMR cluster, no NAT.

## Decisions for the rerun (human plan, 2026-11-15)
1. Outputs exist and only the loader failed: fix the loader and run it alone against the existing export; no new EMR run.
2. Small script fixes get static checks and the harness only; reviews only for real code changes.
3. After S fully succeeds, in the same session run a smaller subset (about 1,000 works) for the second data size.
4. Hard stop: end of Nov 17. If not working by then, close Phase 14 with the proven stages and report the rest honestly.
- Concurrency in the embedder is out of scope for now (Titan quota unknown; resume makes the serial rerun feasible).

## Work done (uncommitted at the time of writing; Cowork commits)
- Work order 85 (storage-engineer): `EmbeddingIngest` progress callback (`LOADER_PROGRESS` start line, one line per batch, one line per 100 embeds in a batch); SDK `apiCallAttemptTimeout`/`apiCallTimeout` on every AWS client (Bedrock 60/180 s, others 60/120 s). Agent `sbt check` exit 0.
- Work order 86 (infra-engineer): `loader-only` execution mode in the workflow module (skips EMR), S overlay loader cap 1,800 -> 5,400 s, `run-bounded-pipeline.sh` and READMEs accept the mode. Not run: terraform and static checks.
- Work order 87 (test-engineer): four repaired cases, 139 new cases. Not run: the harness is the human's.
- Work order 88 (rag-engineer): wire progress and `LOADER_PHASE` markers in `IngestMain` and `AwsIngestMain`.

## Open
- Verify on AWS: whether `stop-execution` stops a Fargate loader task (the script only prints a note); S3 Vectors writes; Neptune bulk load; the loader retrieval check.
- A hung embed can still take about 15 minutes (5 attempts x 180 s) before it fails.
- The `scripts/README.md` preflight sentence still says the S loader is 1,800 s; the `Failed` state Cause text still says "cluster termination state".
- D9 and D11 to D36 await human ratification. M/L caps (D10) and endpoints versus NAT (P4) stay undecided until Billing is read. Finding 8 (on-node limits do not scale to M/L) stays open before any M/L run.
- Carried from D35: bash-3.2 `case_skipped` hardening; `tests/scripts/README.md:42` stale; `scripts/README.md` rehearsal 5; the 400 versus 600 character note; `run.sh` DirectFileOutputCommitter allowance counts both lines together; D34 cosmetic items.
- Verbatim agent reports for work orders 85 to 88 are in the session transcript (`/Users/winnguyen/.claude/projects/-Users-winnguyen-ScholarChain/74151a12-df26-41ab-9f46-ab188025e8d8.jsonl`).
