# Phase 15 experiment results

Author: experiment-engineer (P15-46 step 3). Status: written for independent review; nothing here is a reviewed or accepted result. Region `us-east-2` for every run. Dataset snapshot `scholarchain-s-20261107-10dd7f34121b` (tier S). Account ID masked (`000000000000`). Times are UTC; local time is UTC-6.

Rules for the numbers in this file:

- Every number comes from a derived file (cited by path under `experiments/results/`, see `results/README.md` for the copy step), from a run record (`run-record.env`), or, for A and the smoke tests, from the recorded session evidence named at the number. I computed no statistic. A number I need and no file holds is written NOT DERIVED.
- The derives were run by the human (the project guard blocks agent Python). The agent did not rerun, check or modify any derive script.
- The `SHA256SUMS` files in `results-copy/` hash the raw files, not the redacted copies; the committed `SHA256SUMS` hashes the copies and `RAW-SHA256SUMS` keeps the raw hashes as provenance.
- Cost figures per run are estimates (hourly burn x elapsed time, from `run-record.env`), not bills. Billed cost is PENDING (next-day Billing reading).
- The course text is `docs/agent-runs/phase-15/prompt.md` (it quotes the Phase 15 section of `/Users/winnguyen/scholarchain-data/course-README.md`); there is no `prompt.md` at the repository root. The grading-wide documents (final report, README) belong to other owners.

## 1. Runs

| Exp | Run id | Commit | Final status (harness) | Window (UTC) | Elapsed | Est. spend / ceiling (USD) | Manifest sha256 (run record) | Role |
|---|---|---|---|---|---|---|---|---|
| A | `exp-A-20261117T161757Z-5c69ba90` | `5c69ba9006b9f4a3b83d4e103d6db5710483f4e0` | COMPLETED | pipeline 16:18:00Z to 17:19:19Z (session record) | 3682 s | 0.61 / 0.75 | NOT RECORDED here (A's run record is not in the copies) | baseline; NOT DERIVED |
| B | `exp-B-20261117T185928Z-5c69ba90` | `5c69ba9006b9f4a3b83d4e103d6db5710483f4e0` | COMPLETED | 2026-11-17T18:59:28Z to 20:20:34Z | 4858 s | 2.09 / 2.40 | `61a02d91...6d64` | burst, scale-out and scale-in |
| C | `exp-C-20261118T014553Z-38421c6f` | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9` | COMPLETED | 2026-11-18T01:45:53Z to 01:54:33Z | 509 s | 0.06 / 0.60 | `726d1ac6...e738` | embedding backlog |
| D | `exp-D-20261118T002157Z-38421c6f` | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9` | COMPLETED | 2026-11-18T00:21:57Z to 01:10:37Z | 2896 s | 0.44 / 0.65 | `5c50341e...1e945` (full value in the run record) | query load |
| E | `exp-E-20261118T012347Z-38421c6f` | `38421c6f...` (as D) | COMPLETED | 2026-11-18T01:23:47Z to 01:35:25Z | 673 s | 0.08 / 0.45 | `bfef07b3...8cf7` | retrieval modes; mixed provenance |
| F1 | `exp-F-20261118T011431Z-38421c6f` | `38421c6f...` | COMPLETED by the harness; INVALID as a result | 2026-11-18T01:14:32Z to 01:18:47Z | 245 s | 0.03 / 0.25 | `38811b80...e016` | kept, not derived |
| F2 | `exp-F-20261118T013740Z-38421c6f` | `38421c6f...` | COMPLETED | 2026-11-18T01:37:40Z to 01:41:57Z | 246 s | 0.03 / 0.25 | `38811b80...e016` | the F result |

Truncated hashes above are the first and last characters of the `MANIFEST_SHA256` line of each `run-record.env`; the full values are in `results/<exp>/<run id>/run-record.env`. I did not re-hash the committed manifests: whether the committed file under `experiments/manifests/` still equals the recorded hash is checked by `bash scripts/run-experiment.sh --print-digest ...` (human), not here.

Common limits from the run records: `STOP_CONDITIONS=wall-clock,first-failure,spend-estimate`, `SIZE_TIER=S`; `MAX_MINUTES`, `MAX_CONCURRENCY`, `MAX_TASKS`: B 90 / 1 / 1, D 70 / 48 / 20000, E 60 / 2 / 1500, F 30 / 2 / 200, C 60 / 4 / 2000. Hourly burn: B 1.55, D 0.55, E 0.42, F 0.40, C 0.45 USD per hour (UNVERIFIED rates, `COST_ESTIMATE.md`). Every run record shows `EXIT_STATUS=0`, `SPEND_ESTIMATE_EXCEEDED=no`, `METRIC_EXPORT_FAILURES=0`, `RECORD_COMPLETE=yes`.

Raw data (immutable run directories; the read-only bits are not a security control): `/Users/winnguyen/scholarchain-data/experiments/runs/<run id>/` (`raw/`, `metrics/`, `collected/`, `run-record.env`, `SHA256SUMS`). Session logs: `/Users/winnguyen/scholarchain-data/logs/phase15-*`. Redacted copies: `/Users/winnguyen/scholarchain-data/results-copy/`. Committed copies: `experiments/results/`. A failed earlier B run (session 1, before the P15-23 profile) exists; its collected files are named in decision P15-23 and no derived output of it is in my inputs.

## 2. Course requirement map (every requirement in `prompt.md`)

Status words: MET, PARTIAL, NOT MET, NOT VERIFIED (my sources do not show it either way).

| # | Requirement (source in prompt.md) | Experiment | Answering file or plot | Status |
|---|---|---|---|---|
| 1 | At least one scale-out and one scale-in event, synchronized timestamps (gate; minimum acceptance criterion 12) | B (EMR), D (ECS query service), C (ECS worker) | B `results/b/<run>/derived/scale-events.csv`, `capacity-timeline.csv`, `capacity.svg`; D `collected/app-autoscaling-activities.json`, `derived-metrics/metrics.svg`; C `collected/app-autoscaling-activities.json`, `derived-queue/queue.svg` | MET (B: derive says `scale_out_observed=yes`, `scale_in_observed=yes`; D and C below). All series are UTC; autoscaling activity times are logged at -06:00 and converted by +6 h in section 3 |
| 2 | Deployed runtime answers a graph plus vector RAG query on AWS with evidence IDs, shown BEFORE D and E (hard requirement; minimum acceptance criteria 8 and 9) | smoke | `docs/releases/phase-15/smoke-tn.md` | NOT MET as written. SMOKE1 FAILED, SMOKE2 FAILED, STUB1 PASSED; see section 4. No smoke ran vector retrieval; evidence IDs are not captured (counts only) |
| 3 | Every experiment has a committed YAML or JSON manifest | A to F | `experiments/manifests/*.manifest` and generated `experiments/manifests/json/*.json` (decision P15-17; `check-manifest-json.sh` is the human's check) | MET for the six manifests; the check was not run by me |
| 4 | Raw metrics immutable after collection | A to F | run directories (section 1); `RAW-SHA256SUMS` | MET by design, NOT VERIFIED (immutability not checked). The harness design: `run-experiment.sh` writes once and makes `raw/` and `metrics/` read-only; I did not check the files or hashes |
| 5 | Derived plots and tables reproducible from scripts | B to F | `load-tests/query-load/derive_latency.py`, `load-tests/lib/metrics_derive.py`, `load-tests/lib/queue_derive.py`, `experiments/b-burst/derive-capacity.py`, `experiments/f-failure-recovery/derive_f.py`; the exact commands are in each experiment README | PARTIAL: reproducible for B to F; A not derived; E has no derived CloudWatch metrics |
| 6 | Record commit SHA, dataset snapshot, configuration, resource limits, start and end time, Region, run IDs | A to F | `run-record.env` per run (section 1) | MET for B to F; A's run record is not in the copies (fields taken from the session record) |
| 7 | Never silently discard failed runs | F1, smoke, B session 1 | F1 `results/f/exp-F-20261118T011431Z-38421c6f/README.md`; `smoke-tn.md`; P15-23 | MET: F1 kept and labeled INVALID, SMOKE1 and SMOKE2 reported FAIL, the session-1 B failure recorded |
| 8 | Do not tune the implementation during a run and report as one experiment | A to F | run records; commit per run | MET per the run records (one commit per run). The session changed commit between SMOKE and D (`38421c6`, deviation 2) and F was rerun after a data change; each is a separate run (limitations L18, L19) |
| 9 | Produce raw data, plots, results summary, limitations section | all | this file, `LIMITATIONS.md`, the plots listed in section 3 | MET with the gaps listed in `LIMITATIONS.md` |
| 10 | Experiment A: bounded corpus at minimum EMR capacity; record input records, output records, task counts, duration, shuffle, failures, estimated cost | A | `results/a/<run>/README.md` (no derived file) | PARTIAL. Duration 3682 s (harness), pipeline 61.3 min, estimated cost 0.61 USD, status SUCCEEDED, export 17 tables / 34 objects / 373,888,058 bytes. Input records, task counts, shuffle, failures: NOT DERIVED |
| 11 | Experiment B: larger manifest or higher partition count; observe EMR managed scaling; record requested, provisioned and released capacity over time | B | `derived/capacity-timeline.csv`, `capacity.svg`, `scale-events.csv`, `summary.md` | MET (section 3.B), with two caveats stated there |
| 12 | Experiment C: enqueue a controlled number of chunks; observe queue depth, worker task count, throughput, retry count, drain time | C | `derived-queue/queue-timeline.csv`, `queue.svg`, `summary.json`; `derived-metrics/` | MET for depth, task count, throughput, drain time; retry count for C's own window NOT DERIVED separately (the derived `messages_with_retries` is F2's message, see C) |
| 13 | Experiment D: at least four concurrency levels (course example 1, 10, 50, 100, "adjusted for budget"); p50, p95, p99, throughput, error rate, ECS task count, CPU, memory, Neptune latency, DynamoDB throttles, vector-query latency, LLM latency | D | `derived-latency-v2/latency-by-level.csv`, `stage-latency-by-level.csv`, `latency.svg`, `timeline-30s.csv`; `derived-metrics/metrics-summary.csv`, `metrics.svg` | PARTIAL. Four levels (1, 8, 24, 48; tier S cap, P15-1): p50, p95, p99, throughput, error rate, task count, CPU, memory MET. Neptune latency: only Neptune CPU and pending requests (no per-query latency metric); graph-stage latency (service log, `stage-latency-by-level.csv`) MET: p50 3 to 4 ms at every level, p95 12 to 27 ms (section 3.D). DynamoDB throttles: no datapoints returned (NO_DATA). Vector-stage latency MET from the service log: p50 485, 311, 416, 3,250 ms and p95 862, 505, 3,578, 6,495 ms at 1, 8, 24, 48 clients; vector read skipped on 5, 35, 116, 163 requests. LLM latency: the stub's, not Haiku |
| 14 | Experiment E: compare graph-only, S3-Vectors-only, fused; fourth mode only if OpenSearch exists | E | `derived-latency-modes-v2/latency-by-mode.csv`, `stage-latency-by-mode.csv`, `latency.svg` | PARTIAL. Three modes, 351 requests each, labeled MIXED provenance (P15-44). Per-mode evidence counts and evidence IDs NOT DERIVED. Stage latency MET (P15-47 re-derive): graph retrieval p50 4 ms, p95 12 ms (graph and fused modes); vector retrieval p50 315 ms (vector mode), 317 ms (fused). No OpenSearch, so no fourth mode (not required) |
| 15 | Experiment F: one safe failure; show retry, dead-letter, idempotency, recovery | F2 (F1 invalid) | `results/f/exp-F-20261118T013740Z-38421c6f/derived-verdict/verdict.csv`, `summary.json` | MET: PASS 9, FAIL 0, INFO 3, UNKNOWN 0 |
| 16 | Scaling row EMR: managed scaling or fleet policy with bounded min and max; node or capacity units over time, pending work, duration | B | `derived.json` (`policy_minimum_units` 1, `policy_maximum_units` 3, `policy_unit_type` Instances), `cloudwatch-series.csv` | MET (section 3.B). `TotalUnitsRequested` and `TotalUnitsRunning` returned no data |
| 17 | Scaling row embedding workers: target tracking or step scaling on SQS backlog; queue depth, desired and running tasks, drain time | C | `derived-queue/`, `collected/app-autoscaling-policies.json`, `app-autoscaling-activities.json` | MET: policy names `...-worker-backlog-out` and `...-worker-backlog-in` (step scaling on backlog alarms) |
| 18 | Scaling row graph retriever: ECS target tracking on CPU or request count with latency alarm; task count, CPU, p95 | D | `derived-metrics/metrics-summary.csv`, `latency-by-level.csv`, `app-autoscaling-activities.json` | PARTIAL. Target tracking on CPU and requests per target is shown for the single `scholarchain-course-query` service. Latency alarm: DEFINED in Terraform and seen in `OK` once, NOT shown firing. Defined as `aws_cloudwatch_metric_alarm.query_p95_latency` (`infra/terraform/modules/runtime/main.tf:659-675`): p95 of `TargetResponseTime` on the query target group, 60 s periods, 3 of 3 periods at or above `p95_latency_alarm_seconds` (default 2 s, `modules/runtime/variables.tf:266-270`; the deployed value was not read), `treat_missing_data = notBreaching`, no scaling action, `alarm_actions` default empty; `variables.tf:267` describes it as the graph-retriever row's alarm. Observed: `OK` in the post-A2 read-only checks, when the query service was not yet running (`docs/releases/phase-15/deploy-tn-s2-a2.md:48`, no timestamps). Not shown: any alarm state or history during D (none was collected in my sources). D's per-minute ALB p95 maximum was 10.720 s (section 3.D), but whether three consecutive minutes reached the threshold is not derived, so I do not say the alarm fired |
| 19 | Scaling row vector retriever: ECS target tracking; task count, query latency, errors | D, E | `derived-latency-modes-v2/latency-by-mode.csv` (vector mode), `derived-latency-v2/stage-latency-by-level.csv` | NOT MET as a separate service: no separate vector service scaling data. Vector-mode client latency and errors are shown in E; vector stage latency is in D and E (v2 files, section 3) |
| 20 | Scaling row rank and synthesis: queue depth, CPU or request scaling with bounded maximum; task count, queue, p95, LLM latency | D | as 18 | NOT MET as separate services: one combined query service scaled; LLM latency is the stub's |
| 21 | Scaling row DynamoDB: on-demand or autoscaled; consumed capacity and throttles | D | `derived-metrics/metrics-summary.csv` | PARTIAL. Consumed write units per table are shown (section 3.D); throttle series returned NO_DATA; the billing mode was not read by me |
| 22 | Scaling row Step Functions and SQS: retry and dead-letter policy; execution state, retries, DLQ count | B, F2, C | B `collected/sfn-execution.json`, `sfn-history.json` (execution state); F2 `derived-verdict`; C `derived-queue/summary.json` | PARTIAL. SQS retry and dead-letter: MET (F2). Step Functions `Retry`: NOT SHOWN (the Phase 15 survey in `plan.md` recorded Catch only; I did not read whether WO-1 changed it) |
| 23 | Dashboards: a CloudWatch dashboard in Terraform showing scaling metrics on one synchronized time axis, plus evidence-archiving script output per run (prompt.md hard requirements) | all | per-run plots: `capacity.svg`, `metrics.svg`, `queue.svg`, `latency.svg` | PARTIAL. Terraform dashboard: DEFINED and reported CREATED, display NOT shown. Defined as `aws_cloudwatch_dashboard.scaling` (`infra/terraform/modules/observability/scaling_dashboard.tf:203-220`; count 1 only with `enable_dashboard` and `enable_scaling_dashboard`), with widgets for EMR nodes and pending containers, ECS desired and running tasks, ECS CPU and memory, ALB p50, p95, p99 (lines 104-121), request count and 5xx, SQS depth and dead letters, DynamoDB throttles and Neptune (lines 27-200); the file states one dashboard has one time range, so the widgets share one axis (lines 1-2). Created in A2: the apply log lines read confirm "the scaling dashboard created" (`docs/releases/phase-15/deploy-tn-s2-a2.md:34`). Not shown: a screenshot or export of it, or any data on it; the file marks several metric names UNVERIFIED (lines 10-17); the latency alarm is not a widget. The evidence-archiving output (`collected/`, `COLLECTED.txt`) is present for B to F |
| 24 | Costs and limits are stated (gate) | all | section 6 | MET for estimates and limits; billed cost PENDING |
| 25 | Experiment cost per run and projected cost before approval (prompt.md sizing) | all | section 6 | MET as recorded; the approval records are the human's |

## 3. Results by experiment

### A. Baseline (NOT DERIVED)

Decision P15-2: reuse S run 6 only if its records held task counts, shuffle and failures; the audit verdict was RE-RUN (run 6 holds no shuffle and no MapReduce task counts; `a-baseline/README.md`), so A was re-run in the A + B session at commit `5c69ba9`. The re-run's derive (`a-baseline/derive-table.py`) was not done, so the course fields are not in a derived file.

Recorded (from `session-ab/session-record.md`, not derived): run `exp-A-20261117T161757Z-5c69ba90`, `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=3682`, `estimated_spend_usd=0.61` (ceiling 0.75), pipeline SUCCEEDED 16:18:00Z to 17:19:19Z (61.3 min). Export: 17 tables, 34 objects, 373,888,058 bytes; the export size and row checks PASS, computed outside the repository by another Claude session and relayed by the human (nine loaded tables plus root 100.9 MiB against a 128 MiB limit; maximum rows 101,478; nine loaded tables 140,022 rows). Finding: `path-evidence` holds 42 rows (P15-38).

NOT DERIVED: input records, MapReduce and Spark task counts, shuffle, failures.

### B. Burst (EMR managed scaling)

Source: `results/b/exp-B-20261117T185928Z-5c69ba90/derived/` (`summary.md`, `derived.json`, `capacity-timeline.csv`, `scale-events.csv`, `capacity.svg`). Profile: P15-23 (`SPLIT_MAX_BYTES=262144`, `PARTITION_COUNT=8`, `MAP_MEMORY_MB=2048`, `REDUCE_MEMORY_MB=3072`). Managed scaling policy from the derive: unit type Instances, minimum 1, maximum 3, maximum core 3, maximum on-demand 3 (`emr-managed-scaling-policy.json`).

| Quantity (`derived.json`) | Value |
|---|---|
| `scale_out_observed` / `scale_in_observed` | yes / yes |
| Cluster created, ready | 2026-11-17T18:59:33Z, 19:03:45Z |
| Pipeline step entered, exited (3526 s) | 19:05:26Z, 20:04:12Z |
| Scale-out: instances, first requested, first provisioned, request to ready | 2, 19:08:13Z, 19:10:51Z, 157 s |
| Scale-out after pipeline step start | 166 s |
| Peak requested, peak provisioned instances (primary plus core) | 4, 4 |
| Peak non-primary provisioned | 3 |
| Scale-in: instances, first released, last released | 3, 19:34:14Z, 20:04:34Z |
| Wait entered, exited (900 s) | 20:04:12Z, 20:19:12Z |
| `scale_in_before_terminate_seconds` | 878 |
| `floor_restoring_instances`, `increase_without_demand_evidence`, `ended_and_replaced_instances`, `instances_role_not_recorded` | 0, 0, 0, 0 |

Capacity over time (`capacity-timeline.csv`, requested / provisioned instances): 2 / 0 at 18:59:40Z; 2 / 2 at 19:03:21Z (primary 1, core 1); 3 / 2 at 19:08:13Z; 3 / 3 at 19:10:51Z (core 2); 4 / 3 at 19:12:14Z; 4 / 4 at 19:14:52Z (core 3); 2 / 2 at 19:34:14Z (core back to 1, two released); 0 / 0 at 20:04:34Z. Each scale-out request is linked to container demand in `scale-events.csv` (the demand-linked rows show 61 then 348 pending containers; pending ratio 6.1 then 21.75). CloudWatch (`cloudwatch-series.csv`, period 300 s): `ContainerPending` first 8, max 348, last 0; `CoreNodesRunning` first 1, max 3, last 1; `TotalNodesRunning` first 1, max 3, last 1. Human polls of `describe-cluster` (session-b-now record) agree: core running 3 at 19:31:37Z and 19:33:25Z, running 1 at 19:44:36Z.

Two caveats the derive output carries, stated here as they are:

1. The third release (20:04:34Z, `ci-07035699C76S3LKELIT`) has state `INSTANCE_FAILURE`, not `TERMINATED`; the derive counts it in `scale_in_instances` 3. The two managed scale-in releases are the `TERMINATED` ones at 19:34:14Z. Whether the 20:04:34Z release is a managed-scaling release or a node failing as the cluster ended is not established by the derived files.
2. `cluster_ended_utc` (2026-11-17T20:05:37Z) is earlier than `terminate_cluster_entered_utc` (20:19:12Z, after the 900 s Wait). The cluster ended about 14 minutes before the state machine's `TerminateCluster` state was entered; the derived files do not explain why. `ended_and_replaced_instances` is 0.

Limitation P15-12 applies: only the MapReduce stage ran across the core nodes (see `LIMITATIONS.md`).

### C. Embedding backlog

Source: `results/c/exp-C-20261118T014553Z-38421c6f/` (`derived-queue/`, `derived-metrics/`, `collected/app-autoscaling-activities.json`). Setup: 2,000 chunks sent to the embedding queue (`messages_sent` 2000), worker service starting at 1 task, step scaling on backlog alarms (policies `backlog-out` and `backlog-in`). The embedding path (Titan and S3 Vectors writes) is as designed in `c-embedding-backlog/README.md`; I did not verify the embedder from a derived file.

| Quantity | Value (file) |
|---|---|
| Peak backlog (visible plus in flight) | 1,804 at 01:47:04Z (`summary.json`) |
| Drain time | 406 s, drained 01:53:50Z (`summary.json`) |
| Throughput estimate | 4.926 msg/s ("about 4.9"; from polled counts, `summary.json`) |
| Worker tasks: first desired, max desired, max running | 1, 4, 4 |
| First scale-out seen by the poll | 2026-11-18T01:49:40Z |
| DLQ max in C's own polls | 0 |
| Poll ticks, ticks with error | 15, 0 |
| Oldest message age max (CloudWatch) | 341 s |
| Worker CPU max, memory max (CloudWatch) | 23.78 %, 20.83 % |

Scaling activities (`collected/app-autoscaling-activities.json`, logged -06:00, converted to UTC by adding 6 h): desired 2 at 01:49:31Z, 3 at 01:50:31Z, 4 at 01:52:31Z (all by `scholarchain-course-worker-backlog-high` through the `backlog-out` policy, `Successful`); desired 2 at 02:00:25Z (`Successful`) and desired 1 at 02:04:25Z (`InProgress` when collected; `scholarchain-course-worker-backlog-low` through the `backlog-in` policy). The derived `scale_in_observed=false` only reflects the poll window, which ended at the drain (01:54:21Z); the scale-in is in the activities file, and `session-tonight/evidence.md` records the worker back at 1/1 at 20:04 local (02:04Z).

Worker outcomes in C's OWN window (from 01:45:53Z): 1,950 embedded, 50 `already_done`, 0 poison (human tally, P15-45, from `collected/worker-logs.json`). The derived `worker-outcomes.csv` shows 2,000 embedded, 51 skipped-done and 3 poison because the collector window started at 01:35:53Z, before F2 began (01:37:40Z), and so spans all of F2 (01:37:40Z to 01:41:57Z), so it includes F2's lines (F2: 50 embedded, 1 `already_done`, 3 poison receives). Consistent: 1,950 + 50 = 2,000 embedded and 50 + 1 = 51 skipped-done; the 3 poison and the 1 message with retries are F2's. The derived embedding latency (p50 402 ms, p95 536 ms) therefore also contains F2's 50 embedded lines; a C-only latency is NOT DERIVED. The 50 `already_done` messages are skips of vectors F2 had written, so the throughput figure mixes 1,950 embeds with 50 skips (the README of C names this).

The CloudWatch DLQ series for C's metric window shows 52 visible at its first point (01:35Z). That value matches F1's 52 dead-lettered messages; this is an inference (purge times are not in a derived file), and C's own polls read 0.

### D. Query load

Source: `results/d/exp-D-20261118T002157Z-38421c6f/derived-latency-v2/` (the re-derive of P15-47; the client-side columns are identical to the first derive) and `derived-metrics/`. 7,428 requests in total (`SUMMARY.txt`); one load-client ECS task ran the schedule against the internal load balancer; stub answer model; nearest-rank percentiles; latency percentiles are over 2xx responses; client-side view.

| Level | Clients | Window (UTC) | Requests | OK | Errors | Error rate | OK throughput (req/s) | ok p50 (ms) | ok p95 (ms) | ok p99 (ms) | ok max (ms) | Abstained | Zero evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L0-c1 | 1 | 00:22:17 to 00:30:16 | 134 | 134 | 0 | 0.000000 | 0.2792 | 511.345 | 930.569 | 2442.285 | 4371.113 | 23 | 0 |
| L1-c8 | 8 | 00:30:16 to 00:38:16 | 1130 | 1130 | 0 | 0.000000 | 2.3527 | 337.545 | 597.965 | 3300.535 | 9010.578 | 226 | 0 |
| L2-c24 | 24 | 00:38:17 to 00:46:16 | 2840 | 2837 | 3 | 0.001056 | 5.8717 | 507.921 | 3807.554 | 5868.466 | 9837.529 | 687 | 0 |
| L3-c48 | 48 | 00:46:20 to 00:54:19 | 3324 | 3310 | 14 | 0.004212 | 6.8456 | 3768.982 | 7990.338 | 10130.334 | 14017.316 | 617 | 0 |

Each level is 480 s of load (`window_seconds` 480.0 to 483.5), then the run idles for 15 minutes with no client (design, P15-1). Abstained responses are counted, not hidden: the stage table shows synthesis, fusion and verification skipped for exactly the abstained counts (23, 226, 687, 617). The abstention reason is not captured (finding 5).

Per-stage timing from the service log (`derived-latency-v2/stage-latency-by-level.csv`, stub synthesis): planning p50 0 ms at every level; synthesis p50 4, 3, 3, 3 ms and p95 43, 8, 13, 9 ms; verification p50 2, 1, 1, 1 ms and p95 8, 4, 5, 4 ms. These are the stub's times and say nothing about Haiku.

Graph and vector retrieval stages (same file; columns measured, skipped, p50, p95, p99, max in ms):

| Level | Graph measured / skipped | Graph p50 | Graph p95 | Graph p99 | Graph max | Vector measured / skipped | Vector p50 | Vector p95 | Vector p99 | Vector max |
|---|---|---|---|---|---|---|---|---|---|---|
| L0-c1 | 134 / 0 | 4 | 14 | 99 | 105 | 129 / 5 | 485 | 862 | 2,152 | 4,267 |
| L1-c8 | 1,130 / 0 | 3 | 12 | 49 | 109 | 1,095 / 35 | 311 | 505 | 3,517 | 8,983 |
| L2-c24 | 2,837 / 0 | 4 | 27 | 211 | 917 | 2,721 / 116 | 416 | 3,578 | 5,476 | 9,817 |
| L3-c48 | 3,310 / 0 | 4 | 16 | 93 | 395 | 3,147 / 163 | 3,250 | 6,495 | 7,424 | 9,378 |

Finding: under load, vector retrieval is the bottleneck in D. Its p95 rises from 505 ms at 8 clients to 3,578 ms at 24 and 6,495 ms at 48 (p50 3,250 ms at 48), while graph retrieval stays flat at about 4 ms p50 (p95 12 to 27 ms). This is the service-log view of stage time; the client-side ok p95 in the table above (3,808 ms at 24 clients, 7,990 ms at 48) rises with the vector stage. I state the association, not a proven cause: the stage files do not show why vector retrieval slowed (for example whether the vector backend or the service tasks were the limit; that is not in a derived file, UNVERIFIED).

Skipped: per `load-tests/lib/service_log.py:54-55` a stage whose logged value is null or the string `skipped` is recorded as skipped and has no time. In D the vector stage is skipped on 5, 35, 116 and 163 requests while the graph stage is never skipped. The file does not say why those requests skipped the vector read; the cause is UNVERIFIED. The skipped vector counts are not equal to the abstained counts (23, 226, 687, 617). The v2 stage rows also show vector retrieval ran in D, so the earlier statement that D was graph-only is not supported by the stage file. D's request mode is readable: every request line carries `"mode": "none"` (`results/d/exp-D-20261118T002157Z-38421c6f/derived-requests/requests.jsonl:1`; I read line 1 only). "none" means no override: `load_client.py` defaults `--retrieval-mode` to `none` and then leaves the mode field out of the request body (`load_client.py:210-211`, `:334`), and `experiments/d-query-load/run.sh:45` passes no `--retrieval-mode`. D therefore ran with the service's default retrieval mode. What that default is was not read by me, but the vector stage measured in D (above) shows it includes vector retrieval.

Defect history (P15-47): the service log names the stages `graph-retrieval` and `vector-retrieval`, and `STAGE_ALIASES` in `load-tests/lib/service_log.py` listed neither hyphenated name, so the first derive wrote `measured 0, skipped 0` for the `graph` and `vector` rows. Those two aliases were added (no other alias changed); the human re-derived into `derived-latency-v2/` (log `/Users/winnguyen/scholarchain-data/logs/phase15-p15-47-rederive.log`). The planning, synthesis and verification figures were not affected by the defect.

ECS scaling and load balancer (`derived-metrics/metrics-summary.csv`, per-minute CloudWatch, window 00:11Z to 01:13Z):

| Series | Min | Max | Mean | At max (UTC) |
|---|---|---|---|---|
| Service tasks desired | 1 | 3 | 1.714 | 00:46 |
| Service tasks running | 1 | 3 | 1.714 | 00:47 |
| Healthy hosts | 1 | 3 | 1.694 | 00:47 |
| Service CPU % | 0.223 | 50.945 | 13.595 | 00:45 |
| Service memory % | 22.424 | 71.289 | 48.251 | 00:33 |
| ALB target response time p50, p95, p99 (s) | max 4.627, 10.720, 13.179 | | | 00:47, 00:48, 00:48 |
| ALB requests per minute | 0 | 437 | 119.871 | 00:53 |
| Neptune CPU % | 10.489 | 13.265 | 11.378 | 01:00 |
| Neptune `MainRequestQueuePendingRequests` | 0 | 0 | 0 | no non-zero point |
| ALB target 5xx per minute (8 points) | 1 | 9 | 2.125 | 00:48; first 00:41 |

ALB ELB-5xx returned no data (NO_DATA).

ECS scale-out and scale-in events (`collected/app-autoscaling-activities.json`, logged -06:00, converted +6 h, all `Successful`):

| Time (UTC) | Change | Cause |
|---|---|---|
| 2026-11-18T00:43:03Z | desired 1 to 2 | alarm high, policy `scholarchain-course-query-requests-per-target` |
| 2026-11-18T00:46:03Z | desired 2 to 3 | alarm high, policy `...-requests-per-target` |
| 2026-11-18T01:03:42Z | desired 3 to 2 | alarm low, policy `scholarchain-course-query-cpu` |
| 2026-11-18T01:09:45Z | desired 2 to 1 | alarm low, policy `...-requests-per-target` |

DynamoDB: no throttle datapoints for any of the 12 read and write throttle series (the derive prints NO_DATA for all 12: CloudWatch returned no points, which I read as no throttle events recorded, not as a measured zero). Consumed write capacity units per minute (max): `scholarchain-course-evidence-bundle` 10,243 (at 00:39Z), `scholarchain-course-query-session` 2,713 (at 00:53Z); consumed read units and the other tables' write units are 0 throughout.

Reading: latency rose at 24 and 48 clients (p95 3,808 ms and 7,990 ms against 598 ms at 8 clients; the vector stage p95 rose to 3,578 ms and 6,495 ms while the graph stage stayed at 4 ms p50, table above), when the service ran 1 to 3 tasks and the scale-out to 3 tasks came at 00:46:03Z, after most of the 24-client level; errors were 3 and 14 (0.1056 % and 0.4212 %). The cause of the errors is not in a derived file (target 5xx datapoints exist from 00:41Z). Neptune CPU stayed between 10.5 % and 13.3 %.

### E. Retrieval modes (MIXED PROVENANCE, P15-44 D1 option 2)

Source: `results/e/exp-E-20261118T012347Z-38421c6f/derived-latency-modes-v2/` (the re-derive of P15-47; the client-side columns are the same as in the first derive). Label: the vectors come from the earlier ingest run; the graph and read models come from A (`run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`). The earlier ingest `sourceRunId` was to be recorded beside A's; its value is not in my sources (NOT STATED here). `GAP_NOTE_TRUE_COUNT=0` (evidence.md: no response carried a true `vector_gap_note`). The same 351 questions in each mode, 1,053 requests (`RAW_LINES=1053`), one client with think time, order interleaved with rotation, stub model.

| Mode | Requests | OK | Errors | Error rate | ok p50 (ms) | ok p95 (ms) | ok p99 (ms) | ok max (ms) | OK throughput (req/s) | Abstained | Zero evidence |
|---|---|---|---|---|---|---|---|---|---|---|---|
| graph | 351 | 351 | 0 | 0.000000 | 26.847 | 38.124 | 81.316 | 191.529 | 0.5622 | 17 | 0 |
| vector | 351 | 350 | 1 | 0.002849 | 332.538 | 469.376 | 3899.090 | 8103.663 | 0.5607 | 0 | 0 |
| fused | 351 | 351 | 0 | 0.000000 | 342.416 | 512.055 | 1802.811 | 7448.527 | 0.5638 | 83 | 0 |

Per-stage (stub synthesis, `stage-latency-by-mode.csv`): synthesis p50 3, 0, 3 ms (graph, vector, fused); verification p50 1, 0, 1 ms; planning p50 0 ms in all. Graph and vector retrieval stages (`derived-latency-modes-v2/stage-latency-by-mode.csv`; measured, skipped, p50, p95, p99, max in ms; same defect and fix as D, P15-47, L16):

| Mode | Graph stage | Vector stage |
|---|---|---|
| graph | 351 / 0, p50 4, p95 12, p99 13, max 79 | 0 / 351 (skipped on every request) |
| vector | 0 / 350 (skipped on every request) | 350 / 0, p50 315, p95 453, p99 3,884, max 8,046 |
| fused | 351 / 0, p50 4, p95 12, p99 13, max 91 | 334 / 17, p50 317, p95 491, p99 1,785, max 7,394 |

Finding: vector retrieval costs about 315 ms p50 (453 to 491 ms p95) per request against about 4 ms p50 (12 ms p95) for graph retrieval, so the fused mode's latency is the vector stage's. The vector tail is long (p99 3,884 ms and max 8,046 ms in vector mode). In fused mode the vector stage was skipped on 17 requests (the stage value null or `skipped`, `service_log.py:54-55`); the file does not say why, so the cause is UNVERIFIED. The count 17 equals the graph mode's abstained count, but the files do not show that the two are related; I draw no conclusion. Graph and fused modes skipped synthesis, fusion and verification for exactly their abstained counts (17, 83); vector mode skipped none.

NOT DERIVED: evidence items per mode (the per-request counts exist in `derived-requests/requests.jsonl` but no per-mode table was derived), evidence IDs (the client keeps counts, not IDs), E's CloudWatch metrics, Bedrock cost per query (stub, no Bedrock). The comparison here is end-to-end client latency per mode with its error and abstention counts; the abstention reason is not captured.

Correct statement: vector retrieval first ran in D, not in E. The v2 stage file for D shows it measured on 129, 1,095, 2,721 and 3,147 requests at the four levels (section 3.D), and D ran before E. E's vector and fused modes are the first requests that selected vector retrieval explicitly by a mode field; D sent no mode (`"mode": "none"`, section 3.D) and the service default applied. No smoke exercised vector retrieval (section 4).

### F. Failure and recovery

The F result is F2, `results/f/exp-F-20261118T013740Z-38421c6f/derived-verdict/`: PASS 9, FAIL 0, INFO 3, UNKNOWN 0 (`summary.json`). One injected fault class: a poison message (schema version 2, refused by the worker) plus one duplicate; 52 messages (50 normal, 1 duplicate, 1 poison).

| Check (`verdict.csv`) | Observed | Result |
|---|---|---|
| Retry: poison received 3 times, each time refused as poison | receives 1, 2, 3 | PASS |
| Poison reason logged | `unsupported_version` | INFO |
| Poison never written (no embedded outcome) | 0 embedded lines | PASS |
| DLQ holds the poison message at the end (at least 1) | 1 | PASS |
| DLQ arrival time | 2026-11-18T01:41:13Z | INFO |
| DLQ arrival after the third receive | `2026-11-18T01:41:13Z vs 76 s` (as printed by the derive) | PASS |
| Duplicate: exactly one embed and write across both deliveries | 1 | PASS |
| Duplicate: the other delivery skipped as already done (at least 1) | 1 | PASS |
| Duplicate: one ledger entry exists | present, DONE | PASS |
| Normal messages completed | 50 of 50 | PASS |
| Normal messages finished after the first poison receipt (order not guaranteed; shown, not judged) | 31 | INFO |
| Main queue drained at the last poll | 0 | PASS |

Queue view (`derived-queue/summary.json`): peak backlog 50 at 01:37:43Z, drain 226 s (drained 01:41:29Z), DLQ first non-zero 01:41:13Z, DLQ max 1, worker desired and running 1 throughout (no scaling in F), embedded latency p50 502 ms and p95 1,596 ms, `messages_with_retries` 1 (the poison). The CloudWatch DLQ series for F2 shows 52 visible at 01:27Z, residue of F1 (inference, as in C); F2's own polls start at 0.

F1 (`exp-F-20261118T011431Z-38421c6f`) is INVALID as a recovery result and is not derived: see `results/f/exp-F-20261118T011431Z-38421c6f/README.md`. It still shows the poison, retry and DLQ path and the no-overwrite guarantee (`ChangedVectorContent`).

## 4. Smoke before D and E (what was tried, exactly)

Source: `docs/releases/phase-15/smoke-tn.md`; each smoke is one load-client task, graph mode, 4 queries, 1 client. The course and `prompt.md` require a graph plus vector answer with evidence IDs on AWS before D and E.

| Smoke | Revision | Answer model | Result |
|---|---|---|---|
| SMOKE1 | `32e401a0` | Bedrock Haiku configured | FAILED: 4 of 4 status 200 but `abstained: true` with 0 evidence items on every request (question set; finding 2). The script verdict printed `SMOKE_RESULT=PASS` because it tests status and counts only; the runbook rule `evidence_items > 0` read by hand caught the failure |
| SMOKE2 | `38421c6f` | Bedrock Haiku | FAILED under the runbook rule: evidence items 4, 3, 3, 8 (above 0) but all 4 abstained after synthesis; ten Bedrock synthesis calls returned `outcome=ok` and the eleventh hit `cap-exceeded maxCalls=10` (finding 3) |
| STUB1 | `38421c6f` | stub | PASSED: 4 of 4 status 200, `abstained: false`, evidence items 4, 3, 3, 8, every stage ran |

What this does and does not show:

- No smoke exercised vector retrieval: in all three, `vector-retrieval` was `skipped` (graph mode). STUB1 shows graph retrieval with evidence on AWS through the stub, not a graph plus vector answer.
- No answer produced by Bedrock Haiku passed (SMOKE2). D, E, F and C ran with the stub answer model (P15-4 and the session decision after SMOKE2).
- Evidence IDs: the smoke and load-client records keep `evidence_items` counts, not IDs. Evidence-ID presence per answer is not in any file I read (NOT DERIVED).
- The first AWS requests that used vector retrieval were D's (the v2 stage file shows the vector stage measured at every level, section 3.D; D sent no mode override, so the service default applied). E's vector and fused modes were the first to select it explicitly. In E, vector mode returned 350 of 351 OK with 0 zero-evidence responses and 0 abstentions; fused returned 351 of 351 OK with 0 zero-evidence responses and 83 abstentions (section 3.E).
- The session continued to D and E after the SMOKE2 failure; the human's explicit decision for that is not recorded in my sources (`smoke-tn.md`, "UNVERIFIED").

## 5. Required scaling rows: what is and is not shown

| Component | Evidence shown | Not shown |
|---|---|---|
| EMR | B: managed scaling 1 to 3 instances, node counts over time, pending containers, pipeline duration 3526 s | `TotalUnits*` series (no data); Spark and export ran on the primary only (P15-12) |
| Embedding workers | C: backlog 1,804 to 0, desired 1 to 4 and back to 1, drain 406 s | C-only latency and retry count (window overlap) |
| Graph retriever | D: task count 1 to 3, CPU, p95 per level, scale-out and scale-in events; graph-stage p50 3 to 4 ms, p95 12 to 27 ms at all four levels | the latency alarm firing: it is defined (`modules/runtime/main.tf:659-672`) and was `OK` after A2 (`deploy-tn-s2-a2.md:48`); no alarm state during D was collected |
| Vector retriever | D: vector-stage p50 485, 311, 416, 3,250 ms and p95 862, 505, 3,578, 6,495 ms; E: vector-mode client latency and errors, vector-stage p50 315 ms | a separately scaled vector service |
| Rank and synthesis | D, E: stub synthesis and verification stage times | separate services; queue-based scaling; real LLM latency |
| DynamoDB | D: consumed write units per table | throttle values (NO_DATA); billing mode |
| Step Functions and SQS | F2: retry to dead letter, idempotency; B: Step Functions execution history collected | Step Functions `Retry` policy evidence |

## 6. Costs

Per-run estimate is `HOURLY_BURN_USD` x elapsed time from each `run-record.env` (UNVERIFIED rates; burn x time, not a bill; experiment windows only, not session setup or teardown):

| Run | Estimate (USD) | Ceiling (USD) |
|---|---|---|
| A | 0.61 | 0.75 |
| B | 2.09 | 2.40 |
| D | 0.44 | 0.65 |
| E | 0.08 | 0.45 |
| F1 | 0.03 | 0.25 |
| F2 | 0.03 | 0.25 |
| C | 0.06 | 0.60 |
| Reload (graph-only, A's export) | 0.08 | pipeline ceiling 1.70 |

`session-tonight/evidence.md` states the estimated experiment spend of the evening session as 0.64 USD (D, E, F, F2, C) plus the reload 0.08 USD. I did not recompute it.

Gate figures (decisions; billed values are the human's Billing readings):

| Gate | Billed month-to-date | Lag allowance | Session HIGH | Total | Limit | Source |
|---|---|---|---|---|---|---|
| A + B session (P15-32) | 5.33 | 1.15 (OD-7) | 3.90 | 10.38 | 14 | `decisions.md` P15-32 |
| B-only session | 5.33 | 1.10 | 2.93 | 9.36 | 14 | `session-b-now-record.md` |
| Tonight, D, E, F, C (P15-43) | 6.18 | 2.27 (the B-only session: run 2.09 plus endpoints about 0.18, not yet billed) | 4.50 | 12.95 | 14 (human stop near 15; AWS spend limit 20) | `decisions.md` P15-43 |

Billed cost for Phase 15: PENDING (the human reads Billing the next day against the P15-43 gate). The 20 USD figure is the AWS spend limit and the code cost ceiling; neither was changed.

## 7. Manifests, configuration and reproducibility

- Manifests: `experiments/manifests/experiment-<a..f>-*.manifest` (flat `KEY=VALUE`, source of the approval digest) and `experiments/manifests/json/*.json` (generated, checked copy, P15-17).
- Approval ledger and plan digests: `docs/agent-runs/phase-15/session-tonight/evidence.md` and `docs/releases/phase-15/README.md`. Experiment approval args digests: D `4513b718`, F and F2 `e35cab71`, E `9cb63983`, C `56873bc3`; B `cbf66f79` and A `2680af41` per `session-record.md` (B's experiment record was reused in the B-only session). Experiment script digest `e3787c42...`.
- The P15-47 re-derive (D and E stage latency) was run by the human; log `phase15-p15-47-rederive.log`. Its log records `REMOVE_FAILED` and `FAILURES=1` for the removal of the v1 folders inside the sandbox where it ran, with VM paths; the human reports the v1 folders were then removed from `experiments/results/` and `SHA256SUMS` regenerated (the run directories contain only the v2 folders and the committed D `SHA256SUMS` lists them; I did not run `shasum -c`).
- Derive commands: in the experiment READMEs (`d-query-load`, `e-retrieval-modes`, `c-embedding-backlog`, `f-failure-recovery`, `b-burst`, `a-baseline`). Derive logs by the human: `phase15-wave3-derive-d.log`, `-e.log`, `-fc.log`.
- Release records for the sessions: `docs/releases/phase-15/` (deploy-engineer's).

## 8. NOT DERIVED (consolidated)

A: input records, task counts, shuffle, failures (no derived file). C: C-only embedding latency; C-only retry count. D: per-query Neptune latency, DynamoDB throttle values (NO_DATA), real LLM latency, the cause of the skipped vector reads (UNVERIFIED). E: evidence items per mode, evidence IDs, CloudWatch metrics, earlier-ingest `sourceRunId`, the cause of the 17 skipped vector reads in fused mode (UNVERIFIED). F1: everything. All: evidence IDs per answer; abstention reasons; billed cost.

## 9. Not met (summary of section 2)

Requirement 2 (graph plus vector smoke with evidence IDs before D and E) is NOT MET as written. Requirement 19 (vector retriever as a separately scaled service) and 20 (rank and synthesis as separately scaled services) are NOT MET. Requirement 4 is MET by design but NOT VERIFIED (section 2, line 40). Requirements 5, 10, 13, 14, 18, 21, 22, 23 are PARTIAL (18: the latency alarm is defined and was `OK` after A2, `modules/runtime/main.tf:659-675`, `deploy-tn-s2-a2.md:48`, never shown firing; 23: the Terraform dashboard is defined, `scaling_dashboard.tf:203`, and reported created in A2, `deploy-tn-s2-a2.md:34`, its display not shown; 22: Step Functions `Retry` is NOT SHOWN, no `Retry` in `infra/terraform/modules` per the orchestrator's search, not re-run by me). Details and reasons: `experiments/LIMITATIONS.md`.
