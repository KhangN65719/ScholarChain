# Experiment D: query load (Phase 15, Wave 2, WO-11)

Status: authored, NOT executed. No AWS call, no run, no approval record. Rehearsals have not been run by the author (the agent shell cannot run them); the human runs them (command 0).

## Design
1,8,24,48 concurrent clients, 8 minutes each, then 15 minutes with no client (P15-1), against the internal load balancer, stub answer model (P15-4; the synthesis time is the stub's and is labelled so in the results, it does not represent Haiku). One load-client ECS task runs the whole schedule (`load-tests/query-load/load_client.py`; the request arithmetic is in its README: at most 19,521 requests, cap 20,000). `run.sh` starts that one task, waits for it, and writes `window.json`. It changes nothing during the run.

## Evidence
| Course item | Source |
|---|---|
| p50, p95, p99 latency, throughput, error rate per level | `derive_latency.py derive --group-by level` from the client's raw request lines (client view); ALB `TargetResponseTime` p50, p95, p99 per minute (load balancer view) |
| ECS task count | Container Insights `DesiredTaskCount`, `RunningTaskCount`; `app-autoscaling-activities.json` (the scale-out and scale-in events, with times and causes) |
| CPU, memory | `AWS/ECS` `CPUUtilization`, `MemoryUtilization` |
| Neptune | `AWS/Neptune` `CPUUtilization`, `MainRequestQueuePendingRequests` (cluster dimension, UNVERIFIED); graph retrieval time per request from the service log (`stage-latency`, `graph`) |
| DynamoDB throttles | `ReadThrottleEvents`, `WriteThrottleEvents`, consumed units per table |
| vector-query latency, LLM latency | the service's per-request stage timings (`vector`, `synthesis`) from `collected/service-logs.json`; `load-tests/lib/service_log.py` is the one place that names the keys. Status after the D run: the stage names `graph-retrieval` and `vector-retrieval` were missing from its `STAGE_ALIASES` and were added in P15-47; with them the v2 stage file has graph and vector rows with measured counts above 0, so the log line format is no longer pending (see `experiments/RESULTS.md` section 3.D). The earlier stage-timing markers are therefore resolved for D's stage timings (the stub synthesis stage is in the same file); the retrieval-mode field is final, `retrievalMode` (`QueryService.scala`) |
| errors | client `status` counts; ALB `HTTPCode_Target_5XX_Count`, `HTTPCode_ELB_5XX_Count` |

Scaling rows (graph retriever, vector retriever, rank and synthesis, DynamoDB): the one service behind the load balancer scales as WO-10 defines. The experiment shows its task count against CPU and p95 and its scale-out and scale-in around the schedule; whether the rows for separate vector and synthesis services apply depends on WO-10's service layout and is not claimed here.

## Prerequisites (outside this directory)
1. WO-8 `AwsRuntime` and its health check, WO-10 (held) internal ALB, ECS target tracking on the runtime service, Container Insights, the load-client task definition (with `SCHOLARCHAIN_QUERY_TOKEN` from Secrets Manager) and its log group, WO-9 load-client image.
2. A passing smoke test (graph plus vector answer with evidence IDs on AWS) BEFORE D: otherwise D measures error responses. The raw lines record `evidence_items` per response.
3. Manifest changes (applied after the Wave 1 commit): see the report.
4. The load client's task needs `ecs:RunTask` and `iam:PassRole` for the human's role in the approved session, and the load-client security group is the only one the ALB admits (P15-3).

## Files
`run.sh` (entry point), `collect.sh` (read-only collector, also used by E), `queries.template.json` (CloudWatch queries, also used by E), `../../load-tests/query-load/` (client, derive, rehearsal), `../../load-tests/lib/` (shared).

## Paste-ready commands (human, `!` prefix, zsh-safe)
0. No AWS: rehearsals. Expected `pass=42 fail=0` (client) and `pass=24 fail=0` (shell parts):
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/rehearse.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-rehearse-1.log" | tail -n 6; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/rehearse_shell.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-rehearse-2.log" | tail -n 6; echo "exit: $?"
```
1. The run, through `run-experiment.sh` after the manifest, approval record and a clean tree. The identifiers come from `terraform output`:
```
set -o pipefail
export SCHOLARCHAIN_D_BASE_URL="REPLACE_WITH_INTERNAL_ALB_URL"
export SCHOLARCHAIN_LC_CLUSTER="REPLACE_WITH_CLUSTER_NAME"
export SCHOLARCHAIN_LC_TASK_DEFINITION="REPLACE_WITH_LOAD_CLIENT_TASK_DEFINITION"
export SCHOLARCHAIN_LC_CONTAINER="REPLACE_WITH_LOAD_CLIENT_CONTAINER_NAME"
export SCHOLARCHAIN_LC_SUBNETS="REPLACE_WITH_SUBNET_IDS_COMMA_SEPARATED"
export SCHOLARCHAIN_LC_SECURITY_GROUPS="REPLACE_WITH_LOAD_CLIENT_SECURITY_GROUP_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_D_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_D_APPROVAL.env" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-run.log" | tail -n 60; echo "exit: $?"
```
Expected: the run takes about 50 minutes; `exit: 0` when the client finished its schedule, nonzero (and a kept FAILED record) when it stopped at a cap or failed.
2. Collection, read-only, within 3 days (log retention):
```
set -o pipefail
export SCHOLARCHAIN_QL_ECS_CLUSTER="REPLACE_WITH_CLUSTER_NAME"
export SCHOLARCHAIN_QL_ECS_SERVICE="REPLACE_WITH_RUNTIME_SERVICE_NAME"
export SCHOLARCHAIN_QL_ALB="REPLACE_WITH_app/NAME/HASH"
export SCHOLARCHAIN_QL_TARGET_GROUP="REPLACE_WITH_targetgroup/NAME/HASH"
export SCHOLARCHAIN_QL_NEPTUNE_CLUSTER="REPLACE_WITH_NEPTUNE_CLUSTER_IDENTIFIER"
export SCHOLARCHAIN_QL_DDB_TABLES="REPLACE_TABLE_1,REPLACE_TABLE_2"
export SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP="REPLACE_WITH_LOAD_CLIENT_LOG_GROUP"
export SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX="REPLACE_WITH_AWSLOGS_STREAM_PREFIX/CONTAINER_NAME"
export SCHOLARCHAIN_QL_SERVICE_LOG_GROUP="REPLACE_WITH_SERVICE_LOG_GROUP"
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/d-query-load/collect.sh --run-dir "${RUN_DIR}" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-collect.log" | tail -n 60; echo "exit: $?"
```
Expected: `COLLECT_COMPLETE window=...`; an existing `collected/` is refused; a failed required call keeps `collect-failed.<stamp>/` and can be repeated.
3. Derive (no AWS):
```
set -o pipefail
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py extract --logs-json "${RUN_DIR}/collected/loadclient-logs.json" --out-dir "${RUN_DIR}/derived-requests" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-derive-1.log" | tail -n 8; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "${RUN_DIR}/derived-requests/requests.jsonl" --events "${RUN_DIR}/derived-requests/events.jsonl" --service-logs "${RUN_DIR}/collected/service-logs.json" --group-by level --out-dir "${RUN_DIR}/derived-latency-v2" --title "Experiment D: latency by level" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-derive-2.log" | tail -n 8; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/metrics_derive.py derive --collected-dir "${RUN_DIR}/collected" --out-dir "${RUN_DIR}/derived-metrics" --events-file "${RUN_DIR}/derived-requests/events.jsonl" --title "Experiment D: CloudWatch metrics" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-d-derive-3.log" | tail -n 8; echo "exit: $?"
```
Expected: `EXTRACT_COMPLETE requests=<n> events=<m>` with n at most 19,521 (the raw line count is the request count); `DERIVE_COMPLETE`; (the committed D run used the folder name `derived-latency-v2`: the P15-47 re-derive after the stage-alias fix, run by the human; the first derive's `derived-latency` folder is history in `results-copy/` only) `latency-by-level.csv` has four rows (`L0-c1`, `L1-c8`, `L2-c24`, `L3-c48`). Omit `--service-logs` when `service-logs.json` was not collected.

## Metric names
UNVERIFIED this turn (the aws-documentation tools were not available); the list-metrics files saved by the collector are the empirical proof.
- ALB `AWS/ApplicationELB`, dimension `LoadBalancer` (and `TargetGroup` for `HealthyHostCount`): `TargetResponseTime` with extended statistics p50, p95, p99 (the percentile statistic on this metric is UNVERIFIED), `RequestCount`, `HTTPCode_Target_5XX_Count`, `HTTPCode_ELB_5XX_Count`, `HealthyHostCount`. https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-cloudwatch-metrics.html
- ECS and Container Insights: as in `../c-embedding-backlog/README.md`.
- DynamoDB `AWS/DynamoDB`, dimension `TableName`: `ReadThrottleEvents`, `WriteThrottleEvents`, `ConsumedReadCapacityUnits`, `ConsumedWriteCapacityUnits`. https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/metrics-dimensions.html
- Neptune `AWS/Neptune`, dimension `DBClusterIdentifier` (UNVERIFIED; instance-level metrics may need `DBInstanceIdentifier`): `CPUUtilization`, `MainRequestQueuePendingRequests`. https://docs.aws.amazon.com/neptune/latest/userguide/cw-metrics.html . There is no per-query Neptune latency metric that I know of; graph retrieval time comes from the service log.
- Run task and logs: https://docs.aws.amazon.com/AmazonECS/latest/APIReference/API_RunTask.html , https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_FilterLogEvents.html , filter pattern syntax (`?"A" ?"B"` for either term, UNVERIFIED) https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html

## Limitations to carry into the results
- The client runs in one ECS task (48 threads); if its own CPU saturates, latency includes client queueing. The task's CPU is not measured unless WO-10 sets Container Insights for it.
- Latency percentiles are over 2xx responses; failures are counted, not hidden (`error_rate`, `all_*` columns).
- LLM latency is the stub's. Neptune latency is the service-side graph stage, not a database metric.
- Log retention of 3 days bounds how long the raw request lines exist in CloudWatch; the collected copy is the preserved raw data.
