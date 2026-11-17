# WO-3 report, experiment-engineer (received 2026-11-17; preserved as received, tables kept; author: experiment-engineer subagent)

Status: BLOCKED-ON the orchestrator or human for execution. Authoring complete; nothing run. The guard refuses every command except `ls`, including `bash scripts/run-experiment.sh --print-digest`, `python3 -I` and the rehearsal scripts. No AWS, no sbt, no commit.

## A verdict: RE-RUN
Run 6 `run-v1-192de0fd-1442-4508-9065-09c44775643b`, 2026-11-15T19:21:11Z to 20:48:22Z. Records hold no shuffle (MapReduce or Spark) and no MapReduce task counts.

| Field | Recorded? | Value | Source |
|---|---|---|---|
| Input records | Partly (bytes yes, record counts only in upload receipts) | 2,111,266 / 940,131 / 174,011 / 19,675 bytes; ER read 362,905 candidate pairs, indexed 25,686 records (4,118 works, 21,568 mentions) | `runs/s-6/reports/run-record.json`, `er.log`; `results-s-tier.md` |
| Output records | Documented, not in a run file | export 11 tables 325,349 rows (local copy, counted after); 41,885 summaries; 32,225 vertices, 135,788 edges | `results-s-tier.md`, `decisions-d36.md`, `er.log` |
| Task counts | MR jobs yes, tasks no; Spark tasks derivable | 9 MR jobs succeeded, 2 Spark applications; `TaskSetManager Finished task` lines in `spark.log`, `export.log` | `run-record.json`, `mapreduce-job-ids.txt`, `spark.log`, `export.log` |
| Duration | Yes | Hadoop 727 s, ER 104 s, Spark 1,735 s, export 65 s, 2,706 s before publish; execution 87.2 min incl. loader failure; create+fetch ~374 s and publish+terminate ~350 s derived | `run-record.json`, pipeline record, `results-s-tier.md` |
| Shuffle | No | no MR counters, no Spark event log, no shuffle bytes in `spark.log` | none |
| Failures | Partly | all MR and Spark SUCCEEDED, preflight ok; pipeline FINAL_STATUS FAILED at loader timeout; task-level failures derivable from log lines | `run-record.json`, pipeline record |
| Estimated cost | Yes, with a limit | 0.90 USD (0.62 USD/h x 87.2 min) incl. loader; no EMR-only figure; billing 3.94 USD MTD | pipeline record, `results-s-tier.md` |

A re-run helps only if the node records more. BLOCKED-ON infra-engineer (`scripts/emr/**`), asks written in `experiments/a-baseline/README.md`:
- `mapred job -status <id>` per job into `$rep/mapreduce-counters/`.
- Plain Spark event log (`spark.eventLog.enabled=true`, `spark.eventLog.compress=false`) published with the reports.
- `wc -l` of the four inputs in the run record.
- An S overlay without Neptune and Bedrock for the single-node A session.

## Blockers for B
1. `scripts/emr/run-s-pipeline.sh` is single-node by design (`file://` on the primary, "breaks on a second node"); with core 1, B is expected to fail in the Hadoop stage unless paths move to HDFS or S3. BLOCKED-ON infra-engineer.
2. Possibly nothing pending to trigger scale-out: `EXECUTOR_INSTANCES=2` fixed, S MapReduce jobs small; depends on the partition settings reaching the node.
3. When read, `run-experiment.sh` still had S caps 60/4/1,000; `--print-digest` on A (75 min) and B (80 min) refuses until the infra caps land. Harness cases `8.committed-manifest.A`/`B` may be affected.
4. No-op loader: input templates use `"loader_command":["-version"]`; whether the loader task definition accepts the override in a Neptune-off session is UNVERIFIED.
5. aws-documentation MCP unavailable; EMR CloudWatch metric names UNVERIFIED; `collect.sh` saves `cloudwatch list-metrics` for the cluster; URLs in `b-burst/README.md`, not fetched.
6. Spend: B ceiling 2.10 USD (80 min x 1.55 USD/h), 0.10 above plan session-1 HIGH 2.0; A re-run adds up to 0.75 (session HIGH about 2.85); A needs a different tfvars from B (separate apply).

## Gate items (none executed)
| Gate item | Test | Status |
|---|---|---|
| Manifests parse, within caps | `run-experiment.sh --print-digest` on A and B | not run; needs infra S caps |
| Derive scripts use raw only, refuse overwrite | `b-burst/rehearse.sh` (24 checks), `a-baseline/rehearse.sh` (24 checks) | not run |
| Collector read-only, write-once | `aws_read()` verb guard, `mkdir`/noclobber in `collect.sh` | no test; stub-`aws` harness case is a gap for test-engineer |
| Entry points start once, no tuning, UTC events | review only | no test; stubbed `run-bounded-pipeline.sh` case is a gap |

Expected: both rehearsals print `REHEARSAL pass=24 fail=0`.
```
bash scripts/run-experiment.sh --print-digest --manifest /Users/winnguyen/ScholarChain/experiments/manifests/experiment-a-baseline.manifest --environment course --account-id 123456789012 --region us-east-2 --cost-ceiling-usd 20
```
(same for B)

## Files (under `experiments/`)
Created: `lib/exp-lib.sh`; `a-baseline/run.sh`, `derive-table.py`, `rehearse.sh`, `README.md`, `fixtures/synthetic-run/**`; `b-burst/run.sh`, `collect.sh`, `derive-capacity.py`, `rehearse.sh`, `README.md`, `fixtures/synthetic-scaling/**`, `fixtures/synthetic-no-scaling/**`; `pipeline-inputs/s-a-baseline.template.json`, `pipeline-inputs/s-b-burst.template.json`.
Modified: `manifests/experiment-a-baseline.manifest`, `manifests/experiment-b-burst.manifest`, `README.md` (status note). `pipeline-inputs/s-run-input.draft.json` is stale (could not delete; README notes it).

| | A | B |
|---|---|---|
| Tier | S | S |
| MAX_MINUTES | 75 | 80 |
| MAX_CONCURRENCY / MAX_TASKS | 1 / 1 | 1 / 1 |
| HOURLY_BURN_USD | 0.60 | 1.55 |
| SPEND_CEILING_USD | 0.75 | 2.10 |
| DATASET_SNAPSHOT | `PLACEHOLDER-WO4-S-SNAPSHOT-ID` | same |

## Human execution (2026-11-17, `!` path; recorded by the orchestrator)
- `bash experiments/a-baseline/rehearse.sh`: 24 PASS lines, `REHEARSAL pass=24 fail=0`, exit 0 (log `phase15-wave1-rehearse-a.log`).
- `bash experiments/b-burst/rehearse.sh`: scaling fixture `scale_out_observed=yes scale_in_observed=yes`, no-scaling fixture `no`/`no`, 24 PASS lines, `REHEARSAL pass=24 fail=0`, exit 0 (log `phase15-wave1-rehearse-b.log`).

## Open items
- Run the two rehearsals (done, above) and digests; run `derive-table.py` (command in `a-baseline/README.md`) on the real run 6 files to count Spark tasks and failures; optional S3 log fetch may add MR task counts (UNVERIFIED).
- WO-4 snapshot ID needed before approval.
- B's Wait state name and whether EMR counts the primary in the bounds unknown; the derive script finds the Wait by event type.
