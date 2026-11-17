# Experiment C: embedding backlog (Phase 15, Wave 2, WO-11)

Status: authored, NOT executed. No AWS call, no run, no approval record. Rehearsals on stubs are the only checks, and they have not been run by the author (the agent shell cannot run them); the human runs them (command 0).

## Design
- Sends the 2,000 chunks of `datasets/tier-s/chunks-c/` (P15-14: the file is outside the repository, `/Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl`, sha256 `1ccaefcb...0c92` from the committed manifest) to the `embedding-work` SQS queue, once, in the WO-7 message schema v1 (`message_schema.py`, the only place that knows the schema), 10 messages per `send-message-batch`.
- Then polls, read-only, every 30 s: main queue depth (visible and in flight), dead-letter queue depth, worker service desired, running and pending tasks (`load-tests/lib/aws_poll.py`), until the backlog has been 0 for two polls in a row or the time limit (MAX_MINUTES minus 5) comes. No scaling, tuning, restart or retry by the experiment.
- After the run the human runs `collect.sh` (read-only: CloudWatch `get-metric-data`, `sqs get-queue-attributes`, `ecs describe-services`, worker log events, Application Auto Scaling scaling activities) and `derive` (tables and plots from raw files only).

## What is recorded (course Experiment C and the "Embedding workers" row of the scaling table)
| Course item | Source |
|---|---|
| queue depth | `raw/poll.jsonl` every 30 s; CloudWatch `ApproximateNumberOfMessagesVisible` and `...NotVisible` per minute |
| desired and running worker tasks | `raw/poll.jsonl`; Container Insights `DesiredTaskCount`, `RunningTaskCount`; `collected/app-autoscaling-activities.json` (scale-out and scale-in events with causes and times) |
| throughput | `NumberOfMessagesDeleted` per minute (CloudWatch); `throughput_estimate_msgs_per_s` in `summary.json` (an estimate from polled counts) |
| retry count | worker log lines with `receive_count` above 1 (`messages_with_retries`); `NumberOfMessagesReceived` minus `NumberOfMessagesDeleted` per minute |
| dead-letter count | `dlq_max` in `summary.json`; CloudWatch on the DLQ |
| drain time | `drain_time_s`: from `ENQUEUE_END` to the first poll from which the backlog stays 0 (resolution 30 s) |

## Prerequisites (outside this directory)
1. WO-10 (infra, held): an embedding worker ECS service with scaling on the SQS backlog (target tracking or step scaling), Container Insights on, the worker log group. Without it there is nothing to observe.
2. The worker's own settings: `SCHOLARCHAIN_EMBEDDING_QUEUE_URL`, data bucket, vector bucket, table prefix (WO-7 report).
3. Manifest changes (applied after the Wave 1 commit): see the report. In particular `MAX_TASKS` 5000 to 2000, tier S, `METRIC_NAMES`.
4. The vector bucket must not already hold these 2,000 vectors. The worker skips a chunk whose identical vector exists (`already_done`), so a bucket filled in Phase 14 or by Experiment F (50 of the same rows) makes C measure a skip, not an embed. `worker-outcomes.csv` shows the split; check it before reading any throughput.
5. Validate the chunk file first: `python3 -B datasets/scripts/chunkset.py validate --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c` (WO-4's command) before the run.

## Files
| File | Role |
|---|---|
| `run.sh` | entry point started by `scripts/run-experiment.sh` |
| `enqueue.py`, `message_schema.py` | the sender (also used by F) and the schema |
| `collect.sh` | read-only AWS collector into `<run dir>/collected/` |
| `queries.template.json` | the CloudWatch queries (also used by F) |
| `rehearse.py` | stub rehearsal, expected `pass=35 fail=0` |
| `../../load-tests/lib/*` | shared poller, collector helpers, derive scripts |

## Paste-ready commands (human, `!` prefix, zsh-safe)
0. No AWS: rehearsals (they write only to a temp directory). Expected `REHEARSAL pass=35 fail=0` for C, `pass=24 fail=0` for the shell parts, `pass=42 fail=0` for the load client, `pass=8 fail=0` for F.
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/c-embedding-backlog/rehearse.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-rehearse.log" | tail -n 6; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/rehearse_shell.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-shell-rehearse.log" | tail -n 6; echo "exit: $?"
```
1. No AWS: a dry run of the sender against the real chunk file (plans and validates all 2,000 messages, writes `messages.jsonl` only). Expected: `ENQUEUE_DONE status=dry-run planned=2000 sent=0`.
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/c-embedding-backlog/enqueue.py --chunk-file "/Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl" --expect-sha256 "1ccaefcb892b728b4ed6f97207d29827edca912df970d9c6b47c0f957d220c92" --count 2000 --max-messages 2000 --out-dir "/Users/winnguyen/scholarchain-data/phase15-c-dryrun-1" --dry-run 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-dryrun.log" | tail -n 20; echo "exit: $?"
```
2. The run. The sending is the approved workload inside `run.sh`; it is started by the human through `run-experiment.sh` after the manifest, the approval record and a clean tree. Set the identifiers first (queue URLs from `terraform output`, never typed from memory):
```
set -o pipefail
export SCHOLARCHAIN_C_QUEUE_URL="REPLACE_WITH_EMBEDDING_WORK_QUEUE_URL"
export SCHOLARCHAIN_C_DLQ_URL="REPLACE_WITH_EMBEDDING_WORK_DLQ_URL"
export SCHOLARCHAIN_C_CHUNK_FILE="/Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl"
export SCHOLARCHAIN_C_ECS_CLUSTER="REPLACE_WITH_CLUSTER_NAME"
export SCHOLARCHAIN_C_ECS_SERVICE="REPLACE_WITH_WORKER_SERVICE_NAME"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_C_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_C_APPROVAL.env" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-run.log" | tail -n 60; echo "exit: $?"
```
3. After the run, read-only collection (within 3 days: the worker log group keeps 3 days by default):
```
set -o pipefail
export SCHOLARCHAIN_C_WORKER_LOG_GROUP="REPLACE_WITH_WORKER_LOG_GROUP"
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/c-embedding-backlog/collect.sh --run-dir "${RUN_DIR}" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-collect.log" | tail -n 60; echo "exit: $?"
```
(the queue, DLQ, cluster and service variables of step 2 must still be exported). Expected: `COLLECT_COMPLETE window=...`.
4. Derive (no AWS), one directory per tool:
```
set -o pipefail
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/queue_derive.py --poll "${RUN_DIR}/raw/poll.jsonl" --events "${RUN_DIR}/raw/events.jsonl" --enqueue-summary "${RUN_DIR}/raw/enqueue/summary.json" --worker-logs "${RUN_DIR}/collected/worker-logs.json" --out-dir "${RUN_DIR}/derived-queue" --title "Experiment C: embedding backlog" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-derive-1.log" | tail -n 20; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/metrics_derive.py derive --collected-dir "${RUN_DIR}/collected" --out-dir "${RUN_DIR}/derived-metrics" --events-file "${RUN_DIR}/raw/events.jsonl" --title "Experiment C: CloudWatch metrics" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-c-derive-2.log" | tail -n 20; echo "exit: $?"
```
A failed, timed-out or interrupted run is collected and derived the same way and reported; it is never deleted.

## Metric names (UNVERIFIED this turn)
The aws-documentation tools were not available in the authoring session, so no name below was checked against the documentation in this turn. They are the names in `queries.template.json`; each collector also saves `cloudwatch list-metrics`, which is the empirical proof of which names exist (a name without data is listed as `NO_DATA`, never dropped).
- SQS, namespace `AWS/SQS`, dimension `QueueName`: `ApproximateNumberOfMessagesVisible`, `ApproximateNumberOfMessagesNotVisible`, `ApproximateAgeOfOldestMessage`, `NumberOfMessagesSent`, `NumberOfMessagesReceived`, `NumberOfMessagesDeleted`. Page: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-available-cloudwatch-metrics.html
- ECS task counts, namespace `ECS/ContainerInsights`, dimensions `ClusterName`, `ServiceName`: `DesiredTaskCount`, `RunningTaskCount` (exist only with Container Insights on, P15-6). Page: https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Container-Insights-metrics-ECS.html
- ECS `AWS/ECS` `CPUUtilization`, `MemoryUtilization` (service level). Page: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/available-metrics.html
- Scaling events: Application Auto Scaling `describe-scaling-activities`: https://docs.aws.amazon.com/autoscaling/application/APIReference/API_DescribeScalingActivities.html
- `get-metric-data` and its query shape: https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_GetMetricData.html
- Sending: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessageBatch.html (10 entries per call, total body size limit per call is UNVERIFIED here; the sender's per-message bodies are about 1.6 KB on average, so a batch is far below any limit I know of).

## Limitations to carry into the results
- Poll resolution is 30 s, CloudWatch SQS and ECS metrics are 1 minute; scale events inside one period are visible with exact times only in `app-autoscaling-activities.json` and the ECS service events.
- `throughput_estimate_msgs_per_s` is derived from polled counts and an enqueue-end event; the exact figure is the sum of `NumberOfMessagesDeleted`.
- Titan embedding and S3 Vectors write latency are the worker's own `latency_ms` per message, not a separate measurement.
- C reuses rows that F wrote if F ran first (see prerequisite 4).
