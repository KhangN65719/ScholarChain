# Phase 14 results, tier S (3,472 works), real data on AWS. Recorded by the orchestrator on 2026-11-16 UTC from the evidence in D36, D37 and D38. The human ratifies. No account ID in this file.

Scope: one data size (S) was run on AWS. No second size, no node-elasticity experiment (Experiment B is not demonstrated). Nothing here supports a data-size scaling comparison. All spend figures are estimates (burn rate times elapsed time), not bills; actual AWS billing is owed by the human and is the only cost figure to quote.

## What was validated on real data, and how
| Stage | Real data on AWS? | Evidence |
|---|---|---|
| Hadoop MapReduce (9 applications on YARN), entity resolution, Spark graph analytics on YARN (client mode), export of the semantic summaries and graph tables, on one single-node EMR cluster (emr-7.10.0), one data size (S) | Yes, S run 6 | D36: Hadoop 727 s, ER 104 s, Spark 1,735 s, export 65 s, preflight 21 s, `STAGE_SECONDS_TOTAL_BEFORE_PUBLISH=2706`; all `SUCCEEDED`; cluster terminated |
| Embedding (Titan, 256 dimensions) and S3 Vectors writes, 41,885 summary rows | Yes, S runs 6 to 8 (the ingest was spread over three loader runs; no single run did it all) | D36, D37 |
| Retrieval check against S3 Vectors | Sampled only: two queries per index (a narrow filtered one and a wide one) on the four non-empty indexes, source row at rank 1. It shows the indexes answer and return the row they were built from; it is not an evaluation of retrieval quality and does not go through the RAG service | D37 (run 8 loader log) |
| Neptune bulk load of the projected graph | Yes, S run 11 (after two out-of-memory failures and a writer scale-up) | D38; the loader's own reports; no independent query |
| Loader error naming, bounded tolerance, graph-only mode, local graph rehearsal | Rehearsal on the real export without Neptune; the tolerance policy is fixture-tested; on real Neptune only the `LOAD_FAILED` hedge was exercised once (run 10, a memory error, then rejected by the policy) and the tolerated-errors result never occurred (run 11 had 0 errors) | D38 |
| Graph-RAG query service on AWS | Not covered by these runs (services stayed at desired count 0) | - |

## Dataset sizes (S run 6, run id `run-v1-192de0fd-1442-4508-9065-09c44775643b`, from `aws s3 ls --summarize` counts in D36)
- `curated/`: 238 objects, 1,195,432,130 bytes. `graph/`: 201 objects, 86,035,837 bytes. `semantic-context/` (the export the loader reads): 26 objects, 324,156,384 bytes. `reports/`: 25 objects, 29,232,130 bytes.
- The export: 11 tables, 325,349 rows, 324,127,857 bytes of data files (the local copy). Semantic summaries: 41,885 rows. Researcher metrics: 35,160 rows.
- Graph projected from the export: 32,225 vertices and 135,788 edges (12 CSV files plus a manifest; largest 69,366,964 bytes).

## Input snapshot sizes (what was uploaded for run 6; from `phase14-upload-7.log`, `datasets/dataset-manifest.yaml` and the subset receipts)
| Source (snapshot) | Objects | Bytes | Records |
|---|---|---|---|
| DBLP `dblp-subset.xml` (2026-11-05) | 1 | 2,111,266 | 3,472 works (1,972 seed + 1,500 coauthor) |
| OpenAlex `openalex.jsonl` (2026-11-07) | 1 | 940,131 | 396 rows (400 DOIs requested) |
| Crossref `crossref.jsonl` (2026-11-07) | 1 | 174,011 | 251 rows (300 DOIs requested) |
| ORCID `orcid.jsonl` (2026-11-07) | 1 | 19,675 | 100 rows |
| **Data inputs total** | **4** | **3,245,083** | **4,219 rows** (sum, orchestrator arithmetic) |
| Whole upload (inputs plus jars, the Spark 4.0.5 tarball and jars zip, bootstrap scripts) | 42 | 1,038,107,404 | not applicable |
- The raw inputs are 3.2 MB; about 1.03 GB of the upload is software (the Spark tarball 549,108,494 bytes and the jars zip 461,044,535). The DBLP subset was cut from a 1,116,868,681-byte compressed dump (8,848,160 records). Sizes of the full upstream datasets (OpenAlex 755 GB, ORCID and Crossref marked unconfirmed in the manifest) are not tier S inputs.
- Counts and figures that are NOT reconciled and must not be quoted as one number: 3,472 works in the input, 4,118 work records indexed by entity resolution (D30), and 3,556 rows in the export's `nodes-work` table. `experiment-size-s.tfvars` line 1 still says "5,000 works" (stale; the real size is 3,472).
- Other counts recorded: 3,299 distinct DOIs; ER researcher clusters 2,075 merged and 473 integrity-failed (D30); export tables `nodes-researcher` 17,569, `nodes-venue` 1,342, `nodes-institution` 469, `nodes-topic` 473, `edges-coauthored` 98,661, `edges-authored` 21,268, `edges-published-in` 3,488, `researcher-metrics` 35,160.

## External (non-AWS) costs
None found in any record. The DBLP dump was a local download; enrichment was at most 500 public unauthenticated HTTPS requests (1.5 s apart, no key); `enrichment-receipt.json` records `live_requests: 0` for the S run; tier S ran no new downloads (D15). Licences recorded: DBLP, OpenAlex and ORCID CC0-1.0, Crossref NOASSERTION. This is absence of a recorded cost, not a statement from the data providers.

## EMR cluster timings (run 6; the API timestamps were not kept)
Node: `m5.xlarge` primary only, `core_instance_count` 0 (single node; the type comes from the Terraform default and docs, not read from a live cluster), emr-7.10.0 with Hadoop 3.4.1-amzn-2 observed in capability run 3, our own Spark 4.0.5 tarball, Java 17, 4 vCPU and about 15.8 GB. Run 6: on-node stages in D36 (2,706 s before publish). Derived, not measured: cluster create plus input fetch about 374 s (checkpoint 20:12:23Z at 2,698 s minus the 19:21:11Z start); publish plus terminate about 350 s (inseparable). Earlier runs: run 3 create plus fetch 301 s, run 4 about 313 s. The overlay comments give cluster creation as "about 660 s" and "its observed 660 s" (`experiment-size-s.tfvars:84,88`); that figure has no source and looks like an overestimate.

## Execution times
- EMR pipeline (run 6, the one run with the full EMR stage): 87.2 min from start to the loader failure; the EMR stages above sum to 2,706 s before publish.
- Vector ingest: 41,885 rows across runs 6 to 8. Serial embedding, one Titan call per text, about 9 items per second in run 6 (about 109 ms each); the last complete ingest pass (run 8, mostly resuming) took 2,534 s of its 2,833 s. No run embedded all rows alone.
- Graph: local rehearsal 3.7 s on a laptop; the Neptune graph-only load, run 11, 280 s for the whole execution (download, export, 12 load jobs).
- Execution elapsed time (including the monitor's polling) of runs 6 to 11: 87.2 (run 6, includes EMR), 64.9, 47.2, 3.1, 3.1 and 4.7 minutes. Loader-container times for runs 9 to 11, from the run records: 136 s, 122.9 s and 208.3 s.

## Estimated spend (burn times time; NOT a bill)
Runs 6 to 11: 0.90 (run 6, EMR plus loader), 0.29, 0.21, 0.02, 0.02, 0.08 USD, 1.52 USD in total; runs 1 to 5, 1.70 USD (D29, D30, D32, D34). These per-run figures exclude idle session time and must not be quoted as the project cost. The Neptune cluster and the interface endpoints bill by the hour while a session is up; the five sessions lasted 32.06 hours of which 25.83 were idle. Phase-wide arithmetic (`docs/releases/phase-14/idle-time-and-spend.md`, with its inputs and sources): about 10.8 USD (low case) to about 13.3 USD (high case), a stress case about 19.6 USD (not expected). It uses unverified burn constants, omits Bedrock, S3, S3 Vectors, DynamoDB, ECR, CloudWatch and data transfer, and makes no claim of being below the 15 or 20 USD limits: the named cases approach the 15 USD operating stop and the stress case exceeds it. External (non-AWS) costs: none found (see above). Actual billing: not yet recorded here (human); it is the only cost figure to quote.

## Vector ingestion counts (run 8 loader log; the run wrote no S3 report)
`researcher-profile-v1` 35,160; `collaboration-community-v1` 6,663; `paper-abstract-v1` 20; `collaboration-path-v1` 42; `topic-institution-summary-v1` 0 (no source rows). Total 41,885 vectors, 0 rejected, 0 failed batches in run 8; 62,086,569 bytes of documents.

## Graph load counts (run 11)
`NEPTUNE_LOAD=LOADED vertices=32225 edges=135788 files=12 jobs=12`, all 12 jobs `LOAD_COMPLETED` with 0 parsing, datatype and insert errors. `vertices=` and `edges=` are the export's own counts, not counts read back from Neptune. For 11 of the 12 jobs Neptune reported duplicates equal to records; only `edges-COAUTHORED_WITH.csv` shows 0 duplicates (1,578,576 Neptune records, all inserted by the loader's derived count). The duplicate counts are consistent with those files having been loaded by earlier attempts: run 10's report in S3 lists jobs 1 to 8 and jobs 10 to 12 as `LOAD_COMPLETED`, around its failed job 9 (run 10 used `failOnError=false`; per-job counts of that report were not read; run 9's report was not read, so that run 9 also completed jobs 1 to 8 before failing is an inference from the loader's code and log). That the earlier loads CAUSE the run 11 duplicate counts is still an inference, and the documented duplicate definition covers node rows only (D38). Whether every row was inserted is not established, and there is no independent count (Neptune cannot be queried from outside its VPC and was destroyed without a snapshot). Neptune "records" are property-level counts (rows times properties plus one), not CSV lines.

## Not demonstrated
A second data size; node elasticity; an independent Neptune query or count; the tolerated-errors (`LOADED_WITH_ERRORS`) path on real Neptune; the AWS-hosted query service; a single end-to-end run in which every stage ran in one execution (the stages were proven across runs 6 to 11 on the same export).
