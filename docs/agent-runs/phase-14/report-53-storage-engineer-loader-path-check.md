# Report for work order 53 (storage-engineer, read-only): loader path against the real S run export layout

Archived by the orchestrator as received from storage-engineer (2026-11-15 UTC). Read-only: no file edited, no sbt, no AWS command, no commit.

**STATUS:** COMPLETE (read-only; no file edited, no sbt, no AWS command, no commit). No Terraform or code change is required for the loader to find the export the script publishes. Three things need your attention before S run 5: the 90-minute wall clock against loader time, the expected `RUN_ID_MATCH=false` line, and any single rejected vector failing the loader. The Neptune bulk load, S3 Vectors, Titan and the bulk-load role have never run live, so those parts are read-only conclusions.

**OWNER:** storage-engineer
**WORK ORDER:** 53 (Phase 14)

## FINDINGS

### 1. Prefixes and keys, per part

Paths are relative to `modules/rag/src/main/scala/scholarchain/rag/`, `modules/storage/src/main/scala/scholarchain/storage/`, and `infra/terraform/`.

**Export input (all parts).**
- `AwsIngestMain.scala:78-79` builds `semantic-context/runs/<argument run id>/export/`.
- `downloadExport` (`:241-268`) lists that prefix and does a GET per key into a temp directory. An empty prefix fails with `MissingSuccessMarker`.
- Nothing under `graph/runs/`, `curated/` or `reports/runs/<id>/` (outside the loader's own `loader/` directory) is ever read.

**Part A, embeddings and S3 Vectors.**
- It reads the downloaded `semantic-summaries` table (`IngestMain.scala:459, 484-491`) and the tables used for facets.
- Writes to ordinary S3:
  - Documents go to `semantic-context/documents/v=v1/run=<Spark run segment>/…` (`SemanticDocuments.scala:47-62`).
  - The embedding-work manifest goes to `semantic-context/embedding-work/…`.
  - Rejects go to `logs/vector-rejects/v=v1/run=<Spark run>/…` (`Vectors.scala:788-795`).
- Writes the five S3 Vectors indexes. The index names match Terraform's `index_names` (`modules/s3vectors/main.tf:15-21`).
- `ensureIndex` calls GetIndex only and checks dimension 256, float32, cosine and non-filterable keys `{textRef, textHash}` (`AwsVectorBucket.scala:228-254`). Terraform defaults match: dimension 256, cosine, `["textRef","textHash"]`.

**Part B, Neptune bulk load.** The files do not come from the export, from `graph/runs/`, or from anything the script published.
- The loader generates them itself, in the loader process:
  - `GraphStep.run` (`GraphStep.scala:50-67`) projects the 9 export tables through `SparkGraphProjector` and calls `NeptuneExporter.exportTo`.
  - The CSVs are written to `graph/neptune/v=v1/run=<segment of manifest sourceRunId>/vertices-<Label>.csv` and `edges-<Label>.csv` (`Neptune.scala:861-872`, `ObjectStore.scala:88-98`).
  - The manifest goes to `graph/neptune-manifest/v=v1/run=…/load-manifest.json` (`Neptune.scala:891-897`).
- `AwsNeptuneBulkLoader.load` reads that manifest, checks each file's presence and SHA-256 by HEAD, and starts one StartLoaderJob per file (`:119-252`). Each source is `s3://<data bucket>/graph/neptune/v=v1/run=…/<file>.csv`.
- Vertex files load first, then edge files. `failOnError` and `userProvidedEdgeIds` are both true.

**Part C, ledger and reports.**
- DynamoDB table `<TABLE_PREFIX>-run-ledger` (`DynamoDb.scala:242-243, 249-254`). Terraform names it `${table_prefix}-run-ledger` (`modules/dynamodb/main.tf:36`).
- `reports/runs/<argument run id>/loader/ingest-report.json` and `ingest-run.json` (`AwsIngestMain.scala:80, 533-552`).

**IAM and Terraform against those prefixes.**

| Role or setting | Evidence | Verdict |
|---|---|---|
| `loader` S3 | `modules/identity/main.tf:367` sets `loader_prefixes = [semantic-context, graph/neptune, graph/neptune-manifest, reports, logs]`; `:380-402` grants List (`StringLikeIfExists`), GetObject and PutObject on exactly those | Covers every prefix read or written above. |
| `loader` other | `:404-411` DynamoDB Put/Get/Query on run-ledger, artifact-registry, researcher-metric-cache; `:418-428` S3 Vectors GetIndex, Put, Get, List, Query on the index ARNs; `:430-444` Neptune StartLoaderJob and GetLoaderJobStatus; `:446-454` `bedrock:InvokeModel` on the Titan foundation-model ARN | Matches the adapter calls. |
| Neptune `bulk_load` | `modules/neptune/main.tf:38-56` is `s3:GetObject` on `graph/neptune/*` plus ListBucket with prefix `graph/neptune/*`; `:111` associates it through `iam_roles`; `:14-29` trusts `rds.amazonaws.com` | Reads exactly the CSV directory. The manifest under `graph/neptune-manifest/` is correctly outside its reach, because only the loader reads it. |
| `emr_ec2` | `modules/identity/main.tf:170-182` writes `curated`, `graph`, `semantic-context`, `reports`, `tmp` | Covers all five script publishes. |

Terraform allows exactly the prefixes the code uses.

### 2. Does the loader find what the script publishes?

Yes, by reading the code.

- **Script side.**
  - `run-s-pipeline.sh:343` runs `JsonlExport "$srun" "$xout/export"`, so `$xout` holds only `export/`.
  - Line 367 publishes `$xout` to `s3://<data>/semantic-context/runs/$run_id/`.
  - `lib-emr.sh:833-845` does `aws s3 cp --recursive --exclude '_work/*'` and compares object counts.
- **JsonlExport side.**
  - The final tree is `export/{_SUCCESS, export-manifest.json, <table>/part-NNNNN.jsonl}`.
  - `_work` is deleted before the manifest is written (`JsonlExport.scala:768`).
  - Staging `.crc` files are removed (`:481, 496-503`).
  - The staging marker is deleted before the atomic move (`:788-792`).
  - So the published keys are `semantic-context/runs/<id>/export/_SUCCESS`, `…/export-manifest.json`, `…/<table>/part-*.jsonl`, which is exactly `exportPrefix`.
- **Reader side.** `JsonlExportSource.open` needs `_SUCCESS` (`:163-168`), a manifest of type `scholarchain.spark.jsonl-export-manifest/1` (`:181-185`), `sourceSuccessSeen=true` (`:186-190`) and non-empty `sourceRunId`, `transformVersion`, `exportVersion` (`:191-195`). It then verifies every listed file by size, SHA-256 and row count (`:245`).
  - The manifest fields come from `JsonlExport.scala:676-689`. All are present.
- **Zero-byte `_SUCCESS` and empty parts.** The script copies them, `store.get` returns an empty array, and `lines("")` is empty, so a 0-row part passes against `rows=0`.
- **Required tables.** All nine tables the projector reads are in `ExportTables.all`, plus `semantic-summaries` and `researcher-metrics` (`JsonlExport.scala:56-176`). `GraphStep.RequiredTables` (`:26-36`) is satisfied, so the graph step will not be `SKIPPED`.
- **Limits.** The default bounds are 1,000,000 rows per table, 10,000 files and 1 GiB (`Support.scala:185-186`), and the S export is far below them.

### 3. Run id equality: not enforced

No code enforces equality between the argument run id and the manifest's `sourceRunId`.

- `IngestMain.execute` never sees the argument. It takes `runId = source.sourceRun.runId` from the manifest (`IngestMain.scala:516`).
- The only comparison is `AwsIngestMain.scala:504-508`, which prints `RUN_ID_MATCH=${id == config.runId} export_run=… argument=…`. It does not touch `success`, `reason` or the exit code (`:464-469`).
- The loader will print `RUN_ID_MATCH=false` on every S run. The argument is `run-v1-<uuid>`. The manifest id is Spark's id from `run-manifest.json` (`JsonlExport.scala:732`), which per D32 is a SHA-256 hash and is not even in the slug form. It will not match.
- Everything the loader writes is keyed by the Spark run segment, not the pipeline id.
  - The exceptions are the reports under `reports/runs/<pipeline id>/loader/` and the `loader-run:<arg>` source reference.
  - This is correct for "the run id is the one in the export", but `RUN_ID_MATCH=false` is permanent and uninformative. Anyone, or any script, that treats it as a failure will misread it.
  - I could not search `scripts/` or `docs/` for consumers of that marker, because the guard blocks grep. This is unverified.
- A separate internal check exists. Each summary row's `provenance.runId` must equal the manifest's `sourceRunId`, or that row is rejected with `MalformedRow` (`EmbeddingIngest.scala:136-141`). Spark writes both, so it should hold.
  - A mismatch would show up as rejects, and any reject fails the run (see 5g).
  - Local ingests passed it, but I did not verify that Spark's provenance run id equals its manifest run id.

### 4. Environment the loader needs, and what Terraform supplies

`AwsIngestMain.parseConfig` (`:128-158`) needs these variables. `runtime/main.tf:181-211` injects `local.loader_environment`, which the course environment builds at `environments/course/main.tf:43-54`.

| Variable | Required | Terraform |
|---|---|---|
| `AWS_REGION` | yes | `var.region`, locked to us-east-2 |
| `SCHOLARCHAIN_DATA_BUCKET` | yes | `module.data_lake.data_bucket`, that is `scholarchain-course-<acct>-data` |
| `SCHOLARCHAIN_VECTOR_BUCKET` | yes | `module.s3vectors.bucket_name` |
| `SCHOLARCHAIN_TABLE_PREFIX` | yes | `local.name_prefix` (`scholarchain-course`) |
| `SCHOLARCHAIN_NEPTUNE_HOST` | optional, but if set the next row is required | `module.neptune[0].endpoint`, the cluster writer hostname with no port, so the default 8182 applies |
| `SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN` | required exactly when the host is set | `bulk_load_role_arn`, set only when `enable_neptune`; the S overlay has `enable_neptune = true` (`experiment-size-s.tfvars:6`) |

- **Bedrock model id and Region:** nothing is passed and nothing is needed. The Titan model id is hard-coded to `amazon.titan-embed-text-v2:0` (`TitanEmbedder.scala:8`) and the Region comes from the client. Terraform's `bedrock_titan_model_id` default is the same string. IAM is region-qualified `foundation-model/…` (`course/main.tf:31`).
- **Index names and dimension:** hard-coded in code and matched to Terraform. Dimension 256 against `vector_dimension` default 256 is consistent.
- **Entry point:** the Dockerfile ENTRYPOINT is `java … -cp /opt/scholarchain/lib/*`. The state machine's `RunLoader` overrides only the container command with `$.loader_command` (`workflow/main.tf:225-230`). The command must be `["scholarchain.rag.AwsIngestMain","run-v1-<uuid>"]`.
- **Run id guard:** `run-bounded-pipeline.sh:197-201` requires the last `loader_command` element to equal `run_id` when it starts with `run-`.
- **Bedrock:** `enable_bedrock = true` in the S overlay (`:16`) adds both the loader's Bedrock IAM statement and the `bedrock-runtime` endpoint (`course/main.tf:33`). The default is false, so any non-S overlay needs the line.

### 5. What could fail on first contact (read-only judgement)

These are the points I would watch; none is a proven defect.

a. **Wall clock against loader time (highest risk).**
- D32 expects cluster creation of about 660 s plus a step of 3,300 to 3,900 s. That is about 4,000 to 4,600 s before terminate and the loader.
- `--size medium --max-minutes 90` stops the whole execution at 5,400 s (`run-bounded-pipeline.sh:386-395`, `stop_everything`).
- That leaves about 800 to 1,400 s for fetch (about 30 s), terminate (up to 900 s) and the loader, before the wall clock calls `stop-execution`.
- The loader's work is sequential:
  - Fargate start.
  - One Titan call per summary (`TitanEmbedder.embed`) with no concurrency; the summary count for S is unmeasured.
  - One HEAD per document in `store.list` (`EmbeddingIngest.scala:486`, plus `objectGroups`, `IngestMain.scala:207-230`).
  - Neptune load jobs polled every 5 s, with up to 720 polls allowed (`AwsNeptuneBulkLoader.scala:101-103`).
- The state machine's `loader_timeout_seconds` is 1,800 s, but the script's 5,400 s cap may bite first. I do not know the loader duration.

b. **Neptune loader role association.**
- It is attached via `iam_roles`, and the cluster is created at the start of the session, so it should be ACTIVE long before the loader runs.
- Whether the Neptune policy with `s3:ListBucket` conditioned on `graph/neptune/*` satisfies a single-object source is flagged UNVERIFIED in `modules/neptune/main.tf:35-37`.
- The loader's `HeadObject` on a missing key relies on `StringLikeIfExists` (`identity/main.tf:371-379`, also UNVERIFIED).

c. **Neptune data-plane IAM actions.** The loader grants StartLoaderJob and GetLoaderJobStatus (plus List, Cancel and ReadDataViaQuery). I did not verify against AWS docs that StartLoaderJob needs nothing more.

d. **Neptune CSV shape.** Headers are `:ID`, `:LABEL` for vertices and `:ID`, `:START_ID`, `:END_ID`, `:TYPE` for edges, with property types `String`, `Int`, `Long`, `Double`, `Bool`. A list becomes one `;`-joined String (`Neptune.scala:24-46, 819-825`). I did not verify these against the current openCypher loader format page. The `Bool` type name in particular is unconfirmed.

e. **Network.**
- The S3 and DynamoDB gateway endpoints are on the app and data route tables (`network/main.tf:151-167`).
- The loader reaches Neptune on `neptune_port` (`:346-372`) and the interface endpoints on 443.
- `s3vectors` is in the default `interface_endpoint_services` (`course/variables.tf:126`) and `bedrock-runtime` is added when Bedrock is on.
- I did not read `course.tfvars`, so whether it overrides the endpoint list is unverified.
- The service name `com.amazonaws.us-east-2.s3vectors` itself is unconfirmed.

f. **S3 Vectors request shape.**
- The index dimension 256 and Titan's `dimensions: 256` plus `normalize: true` agree, and Titan's request body is `inputText`, `dimensions`, `normalize` (`TitanEmbedder.scala:34-43`).
- Writes are PutVectors in batches of at most 500 with float32.
- Pre-validation counts all metadata, including `textRef` and `textHash`, against the 2,048-byte filterable limit (`Vectors.scala:233-239`).
- I could not estimate real metadata size. Five 70-to-80-character IDs in each of the two list fields plus the other keys is near the limit.

g. **Any reject fails the run.**
- `clean` requires no failed batches, no rejects and no `unmapped` summaries (`IngestMain.scala:513-515`).
- If Spark emits a summary type with no index, or one vector is oversized, the ingest step reports FAILED and `LOADER_RESULT=FAILED` even though the other vectors were written.
- The `topic-institution-summary-v1` index has no source and is allowed to be 0 planned (`Vectors.scala:41-65`).

h. **Titan availability.**
- Whether Titan Text Embeddings V2 can be invoked in us-east-2 in this account, and whether model access is enabled, is account-level and I could not read it.

i. **Loader image and sizing.**
- The loader task is 512 CPU units with an unread memory default. The Dockerfile uses `MaxRAMPercentage=75`.
- The S export is small, so memory should be fine, but `JsonlExportSource.rows` reads whole files into memory, repeatedly.
- That `lib/` contains the right jars and that `image_tag` is set at deploy is also unverified.

## MISMATCHES THAT NEED A CODE OR TERRAFORM CHANGE

None required for the loader to find and read the S export. Optional items and the owner for each:

1. **Expected `RUN_ID_MATCH=false`.** Not a defect. If it will confuse the run review, rag-engineer could add a clarifying note in a later work order, or the orchestrator can record it in the evidence file. A decision is needed on whether any script or doc treats the marker as a gate.
2. **Wall-clock budget against loader time (5a).** This is a bounds decision for the human and orchestrator, not a code change. The options are a larger `--max-minutes` than the `medium` 90-minute cap permits, a cap change, or accepting that the load may be stopped.

## UNVERIFIED

- Everything in 5b to 5e and 5h to 5i, which is AWS-service behaviour, account state, or files I did not read.
- Whether any script or doc parses the `RUN_ID_MATCH` marker.
- Spark's provenance run id equalling its `run-manifest.json` run id.
- S summary counts and real metadata byte sizes.
- `course.tfvars` contents and `TableNames` in the live account.
- No live run exists for any of the AWS-side adapters (Neptune, S3 Vectors, Titan, Dynamo).

## COMMANDS RUN

Only Read and Bash `ls` calls. Four Bash attempts were refused by the guard and did not run: one `ls` with chaining, a grep, a `git status`, and a second grep. No sbt, no AWS command, no commit, no file edit.

## RISKS

- **Loader time:** the loader may run into the 5,400 s wall clock, or its own 1,800 s step timeout (5a).
- **Reject policy:** one rejected vector fails the whole loader result (5g).
- **Misleading marker:** `RUN_ID_MATCH=false` is permanent for the S pipeline (3).
- **Live-only unknowns:** the Neptune and S3 Vectors items in 5b to 5f only resolve on the first live run.

## NEXT GATE

The orchestrator decides the wall-clock budget question in 5a and whether to record `RUN_ID_MATCH=false` as expected. The human runs the S overlay plan and S run 5. The first loader contact (Neptune bulk load, S3 Vectors, Titan) stays unproven until then, and any failure routes back to storage-engineer or infra-engineer with the loader log.
