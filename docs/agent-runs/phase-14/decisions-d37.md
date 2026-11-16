# Phase 14 decision D37 (2026-11-16 UTC): S runs 7 and 8, the empty-array metadata defect, and the graph-phase out-of-memory. Recorded by the orchestrator from the human's `!` runs and the agents' reports. The human ratifies. No account ID in this file.

## S run 7 (HEAD 2cc20a8, loader-only): FAILED, vector metadata rejected
- Execution `run-20261116T024211Z-large`, loader-only against the S export of run 6 (run id `run-v1-192de0fd-1442-4508-9065-09c44775643b`). 64.9 min, estimated spend 0.29 USD (burn times time, not a bill). The execution ended FAILED at 3,891 s of polling; the loader container exited with code 1 (not a timeout, not an out-of-memory exit).
- The progress lines worked (about 5 vectors per second). `researcher-profile-v1` completed 35,160 vectors (16,000 skipped from run 6 plus 19,160 written) and passed its retrieval sample (source row rank 1; wide sample 20 hits).
- `collaboration-community-v1` (6,663 rows), `paper-abstract-v1` (20) and `collaboration-path-v1` (42) failed every batch. S3 Vectors `PutVectors` answered HTTP 400 `ValidationException`, "Invalid record for key 'summary:v1:...': Empty arrays are not allowed in metadata". The full text came from the `failure` attribute of the run-ledger table; the loader log cut it at 160 characters. One bad record failed a whole batch of 500.
- Neptune load skipped (`NEPTUNE_LOAD=SKIPPED`), `LOADER_EMBEDDING calls=25893`, `topic-institution-summary-v1` had no source rows.
- Cause: empty `evidenceWorkIds` and `evidenceNodeIds` lists in the vector metadata (work rows always have empty node evidence; singleton communities and trivial or unreachable paths have empty work evidence).

## Repair (commit 02437a9, work orders 100 to 105)
- Storage: `metadataFor` omits empty evidence lists; `VectorMetadata.validate` rejects empty list values; a wire-rules checker (`S3VectorsRules`) is enforced in the local fake bucket and in the AWS vector-bucket test stub, so the local tests now refuse what S3 Vectors refuses; a live `LOADER_BATCH_FAILED` line; the ledger failure cap raised to 600 characters; `Text.maskAccountIds` masks 12-digit ids in failure text (`<account-id>`).
- Tests: new `S3VectorsRulesSuite`, `VectorEmptyListSuite`, `FailureTextMaskSuite` (10 tests), a `Phase7VectorQuerySuite` fixture fix, and a new Spark-to-vector-metadata suite in `tests/integration` (added to `slowSuites` after review finding R1).
- Rag: failure and failed-batch caps (up to 20 failed batches listed, a `failedBatches` array in `ingest-run.json` only); `ingest-report.json` stayed byte-identical (golden SHAs).
- Review 1 (Opus): REQUEST CHANGES, one finding (R1, `slowSuites`), repaired by work order 104. Narrow re-review: APPROVE.
- Gates: the agents' `sbt quick` and one `sbt check` green; the human's `sbt clean check` (`phase14-clean-check-2.log`): Spark group 93, default group 1,133, Failed 0, `[success]`, 1,730 s.
- Deployed by the human as package-7, plan-12 (3 add, 3 change, 3 destroy, task definitions for the image tag only, `STATEFUL_CHANGE=no`) and deploy-12, `DEPLOYED_REVISION=02437a90...`.

## S run 8 (HEAD 02437a9, loader-only): vector ingest complete, graph phase out of memory
- Execution `run-20261116T083333Z-large`, 47.2 min, estimated spend 0.21 USD. FINAL_STATUS FAILED at 2,833 s of polling; the loader container exited 1.
- Vector ingest completed (`LOADER_PHASE ingest` from 20 s to 2,554 s; `INGEST_STEP=SUCCESS`; `rejected=0 failedBatches=0`). Counts from the loader log:

| Index | Vectors |
|---|---|
| `researcher-profile-v1` | 35,160 (planned 35,160, written 0, skipped 35,160: already written by runs 6 and 7) |
| `collaboration-community-v1` | 6,663 written |
| `paper-abstract-v1` | 20 written |
| `collaboration-path-v1` | 42 written |
| `topic-institution-summary-v1` | 0 (no source rows) |
| Total summary rows | 41,885 of 41,885 (documents 62,086,569 bytes; embedding-work manifest 24,164,778 bytes) |

- The retrieval sample returned the source row at rank 1 on all four indexes. The narrow, filtered samples returned 1 hit for `researcher-profile-v1` and `collaboration-community-v1` and 20 hits for `paper-abstract-v1` and `collaboration-path-v1`; each wide sample returned 20 hits. This is two queries per index (a narrow filtered one and a wide one): it shows the index answers and finds the row it was built from. It is not an evaluation of retrieval quality and it does not go through the RAG service.
- Then `LOADER_PHASE graph` started at 09:16:34Z and, 3 min 43 s later, the JVM died with `java.lang.OutOfMemoryError: Java heap space` (exit 1) on the loader task of 0.5 vCPU and 1,024 MB (heap about 768 MB). No stack trace and no FAILED report reached the log, because an out-of-memory error is not caught by the loader's handlers.
- Evidence gap, stated plainly: run 8 wrote no S3 ingest report (the handlers were bypassed). The run's 41,885 vectors are evidenced by its CloudWatch loader log only (`phase14-s-run-8-loader.log`); the ingest report in S3 under the loader prefix is from an earlier run.

## Diagnosis (work order 106, storage-engineer, read-only)
- The graph phase had never run on real data. It reads each export table as a whole `Vector[Row]` of parsed JSON, projects the graph, builds every CSV as a String and only then writes. Estimated peak for S at the old code: 2.5 to 4 GB (an estimate, not measured). The earlier loader JVM also still held the ingest phase's data.
- Candidate repairs: more task memory (infra), streaming the export (storage, bigger), or both. The human chose: rehearse the graph phase locally on the real export first, then task memory 2 vCPU and 8 GB in the S overlay, a graph-only loader mode that skips the 40-minute ingest skip-check, small out-of-memory markers if they stay small, one review round, then one graph-only run.

## Open
- The ingest report for run 8 does not exist in S3 (above); the report in S3 under the loader prefix is from an earlier run.
- Work orders 89 to 99: no Phase 14 record lists them; their verbatim reports are only in the session transcript. D36 covers 85 to 88.
- Carried from D36: a hung embed can still take about 15 minutes (5 attempts x 180 s) to fail; finding 8 (on-node limits do not scale to M or L) stays open before any M or L run; the items carried from D35 remain open. The `scripts/README.md` preflight sentence about the S loader cap appears to be fixed (per the final review) and the `Failed` state Cause text still says "cluster termination state".
- `edges-COAUTHORED_WITH.csv` and the Neptune load are continued in D38.
- D9 and D11 to D38 await human ratification.
