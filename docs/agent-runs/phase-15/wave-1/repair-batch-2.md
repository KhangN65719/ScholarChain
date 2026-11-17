# Wave 1 repair batch 2, from review 1 (orchestrator, 2026-11-17)

Basis: `review-1-verdict.md` (REQUEST CHANGES), `doc-verification.md` section K2 (VERIFIED: ComputeLimits apply only to core and task nodes), decisions P15-16 and P15-17. Common rules of `work-orders.md` apply. Do not weaken any assertion. No AWS, no sbt, no commit. When the stop hook fires, repeat your full report.

## R4 infra-engineer
1. **K2** `infra/terraform/modules/emr/main.tf:74-77`: managed-scaling limits must exclude the primary node. Keep `emr_managed_scaling_max_units` meaning total instances including the primary (so the overlays and validation keep their meaning) or redefine it; either way B (`elasticity-s.tfvars`: primary 1, core 1) must render `MinimumCapacityUnits=1`, `MaximumCapacityUnits=3`, `MaximumCoreCapacityUnits=3`, `MaximumOnDemandCapacityUnits=3` (core-only scaling, Scenario 1 of the cited page). Fixed-size shapes must still emit no policy or the same effective bounds. Update comments and `elasticity-s.tfvars:14-16`. Cite the pages in `doc-verification.md`.
2. **K1** `elasticity-s.tfvars:47-48` comment (says the stop is 90 min; it was 74): make it match P15-16 (about 88-minute pipeline stop) and recheck the worst-case sum and Rule A.
3. **M1** pin in `scripts/emr/spark-yarn.conf`: `a-baseline.DATA_PATH=file`, `a-baseline.SPARK_MASTER=yarn`, `b-burst.DATA_PATH=hdfs`, `b-burst.SPARK_MASTER=local`. With `hdfs` forced, a missing remote NodeManager must fail clearly, not fall back to `file://`; consider waiting a bounded time for the core NodeManager to register.
4. **M2 / P15-16** `b-burst.SHUFFLE_PARTITIONS=8`, `b-burst.SPARK_EVENT_LOG` off.
5. **M4** (support) copy the Spark run-manifest counts and the export-manifest counts into `reports/` so the experiment derive scripts can read output records; keep MR counters (the "Map input records" counter is the input-records source).
6. **m2** `run-s-pipeline.sh:110` `RUN_SCOPE` must describe the actual mode (file/YARN or HDFS/local Spark).
7. **m7** bounded timeout on `hdfs dfs -mkdir` (`run-s-pipeline.sh:217`) and every other new `hdfs` call.
8. **a1** the subdirectory key-material scan must not fail the run on the Spark event log size; decide how (skip event logs or scan in bounded fashion) and state it.
9. **a5 / a7** add to `scripts/README.md` "Phase 15 pre-session checks": a read-only DLQ depth check for both queues (`sqs get-queue-attributes ApproximateNumberOfMessages`), and, if the API exists (verify; cite), a read-only rendered state-machine validation (`stepfunctions validate-state-machine-definition`) for the overlay's definition before apply.

## R5 experiment-engineer
1. **K1 / P15-16** `experiments/b-burst/run.sh` pipeline stop about 88 min; `experiment-b-burst.manifest` `MAX_MINUTES=90`, `SPEND_CEILING_USD` about 2.40 with `HOURLY_BURN_USD` consistent; keep the entry limit strictly above the pipeline limit as the existing case requires.
2. **M3** `derive-capacity.py`: read the managed-scaling policy; label any instance that only restores the policy floor; report pending YARN containers (`ContainerPending`/`ContainerPendingRatio`) near each scale event; only demand-linked increases count as scale-out evidence. Update fixtures and the rehearsal (keep the 24 existing checks, add new ones).
3. **M4** `derive-table.py`: input records from the MR "Map input records" counter (state `INPUT_LINES_*` as line counts only); output records from the reports infra copies (R4 item 5). Fixtures and rehearsal updated.
4. **M5 / P15-17** commit a generated JSON copy for each of the six manifests and a check script (in `experiments/`) that fails if any JSON and its KEY=VALUE file differ; document it in `experiments/README.md`.
5. **m1** stale text in `experiments/b-burst/README.md:6,9,16-19`, `a-baseline/README.md:13-18,21`, `experiments/README.md:8`.
6. **m5** `collect.sh`: one failing call must not leave an unrecoverable partial `collected/` (write to a temp dir and rename on success, or record per-call status); add `ContainerPendingRatio` (and `ContainerPending` if valid) to `METRICS`.
7. **a2 / a3** limitations: HDFS `-get` may not copy `.crc` files (objects differ from file mode, keys unchanged, UNVERIFIED); raw `sfn-history.json` and `window.json` hold ARNs with the account ID and must be redacted before any results commit; state how.

## R6 dataset-engineer
**m6** `datasets/scripts/chunkset.py:75-85` in-repo check must be case-insensitive on macOS (compare `os.path.normcase`/realpath consistently, or casefold), with a test; line 399 message must not say "repo-only" when `validate` reads the external file.

## R7 test-engineer
1. **M1** update the static case at `tests/scripts/run.sh:10622` for the new a-baseline and b-burst pins (an update, not a weakening); add cases that forced `hdfs` with no remote node fails clearly.
2. **M6** cases for the corrected ComputeLimits: B renders Min 1, Max 3, MaxCore 3, MaxOnDemand 3; fixed-size shapes unchanged; replace the `min = 1 + core` assumption at `run.sh:9626` with the verified rule (cite `doc-verification.md` K2).
3. **m3** no Catch targets `WaitBeforeTerminate`; dynamic copy-back case (`-get` failure dies, `-rm` failure only notes) at `run-s-pipeline.sh:393-397`.
4. **m4** fix the comment at `run.sh:9534`.
5. Cases for R4 items 4, 6, 7, 8 and R5 item 4 (JSON and KEY=VALUE equality check fails on a mismatch). Work from this specification in parallel with the owners.

## R8 build-engineer
**a4** `.gitignore` backstop for `datasets/tier-s/chunks-c/chunks.jsonl` (P15-14).
