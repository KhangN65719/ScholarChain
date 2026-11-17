# Experiment F: failure and recovery (Phase 15, Wave 2, WO-11)

Status: authored, NOT executed. No AWS call, no run, no approval record. Rehearsals have not been run by the author (the agent shell cannot run them); the human runs them (command 0).

## The one fault
A poison message on the embedding queue: schemaVersion 2 (valid JSON, refused by the WO-7 worker, which never deletes it, so the queue's `maxReceiveCount` 3 moves it to the dead-letter queue). Plus one duplicate: a second send of the body of normal message 24. Total 52 messages: 50 normal (rows 0 to 49 of the C chunk file, the same sender and schema as C), the poison in the middle of the send order, the duplicate right after its original. (A hash-mismatch poison is also available: `enqueue.py --poison-kind hash`; the committed entry point uses `version`. Changing it is a new script digest.)

No other fault is injected, nothing is restarted and no setting changes. `run.sh` sends once, then polls read-only every 15 s until the main queue is drained AND the poison message is in the dead-letter queue, or MAX_MINUTES minus 5.

## What the verdict table shows (`derive_f.py`, raw files only)
| Course item | Check in `verdict.csv` |
|---|---|
| retry | the poison message's `receive_count` goes 1, 2, 3, every line `outcome=poison` with its reason token |
| dead letter | DLQ holds at least 1 message at the end; DLQ arrival time is after the third receive; the poison message has no `done` outcome (never written) |
| idempotency | across the two deliveries of the duplicate: exactly one `done` (embedded and written) and at least one `already_done`; the ledger item for its key exists (`dynamodb get-item`) |
| recovery | all 50 normal messages end `done` or `already_done`; the main queue is 0 at the last poll; how many normals finished after the first poison receipt is shown, not judged (SQS standard queues do not guarantee order) |
Each row is PASS, FAIL, UNKNOWN (input missing) or INFO. A FAIL is a result of the experiment and is reported. The WO-7 worker log format (`WORKER_MSG id= outcome= receive_count= latency_ms= [reason=] at=`) is parsed in `load-tests/lib/worker_log.py`, the only place that names it.

## Timing
A poison message needs 3 receives, each followed by a visibility timeout (the worker's `SCHOLARCHAIN_WORKER_VISIBILITY_SECONDS` default 120 s, or the queue's 300 s if the worker leaves the queue default), so the dead-letter arrival comes roughly 5 to 15 minutes after the start. If the worker is slower to receive than that (for example a scaled-to-zero service waiting for its scaling alarm), the 25 minute observation window can end before it settles; the run is then recorded as `not-settled-before-limit`, a failed run, kept.

## Prerequisites
WO-10 (worker service running with at least one task, log group), the same chunk file as C (outside the repository). F runs BEFORE C (P15-10 order); its 50 normal rows are rows of C's file, so C will find those vectors done. Manifest changes: see the report.

## Paste-ready commands (human, `!` prefix, zsh-safe)
0. Rehearsal, no AWS. Expected `REHEARSAL pass=8 fail=0`:
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/f-failure-recovery/rehearse.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-rehearse.log" | tail -n 6; echo "exit: $?"
```
1. Dry run of the injection (no AWS; plans and validates 52 messages, writes `messages.jsonl` only). Expected `ENQUEUE_DONE status=dry-run planned=52 sent=0`:
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/c-embedding-backlog/enqueue.py --chunk-file "/Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl" --expect-sha256 "1ccaefcb892b728b4ed6f97207d29827edca912df970d9c6b47c0f957d220c92" --count 50 --poison 1 --poison-kind version --duplicate-of 24 --max-messages 200 --out-dir "/Users/winnguyen/scholarchain-data/phase15-f-dryrun-1" --dry-run 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-dryrun.log" | tail -n 8; echo "exit: $?"
```
2. The run, through `run-experiment.sh` (manifest, approval record, clean tree):
```
set -o pipefail
export SCHOLARCHAIN_F_QUEUE_URL="REPLACE_WITH_EMBEDDING_WORK_QUEUE_URL"
export SCHOLARCHAIN_F_DLQ_URL="REPLACE_WITH_EMBEDDING_WORK_DLQ_URL"
export SCHOLARCHAIN_F_CHUNK_FILE="/Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl"
export SCHOLARCHAIN_F_ECS_CLUSTER="REPLACE_WITH_CLUSTER_NAME"
export SCHOLARCHAIN_F_ECS_SERVICE="REPLACE_WITH_WORKER_SERVICE_NAME"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_F_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_F_APPROVAL.env" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-run.log" | tail -n 60; echo "exit: $?"
```
Expected: `exit: 0` after about 5 to 20 minutes when the queue drained and the poison message reached the DLQ.
3. Collection, read-only, within 3 days:
```
set -o pipefail
export SCHOLARCHAIN_F_WORKER_LOG_GROUP="REPLACE_WITH_WORKER_LOG_GROUP"
export SCHOLARCHAIN_F_LEDGER_TABLE="REPLACE_WITH_RUN_LEDGER_TABLE_NAME"
export SCHOLARCHAIN_F_VECTOR_BUCKET="REPLACE_WITH_VECTOR_BUCKET_NAME"
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/f-failure-recovery/collect.sh --run-dir "${RUN_DIR}" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-collect.log" | tail -n 60; echo "exit: $?"
```
(the queue, DLQ, cluster and service variables of step 2 stay exported; `SCHOLARCHAIN_F_VECTOR_BUCKET` is optional.) Expected: `COLLECT_COMPLETE`.
4. Derive (no AWS):
```
set -o pipefail
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
python3 -I /Users/winnguyen/ScholarChain/experiments/f-failure-recovery/derive_f.py derive --send-log "${RUN_DIR}/raw/enqueue/send-log.jsonl" --worker-logs "${RUN_DIR}/collected/worker-logs.json" --poll "${RUN_DIR}/raw/poll.jsonl" --ledger-item "${RUN_DIR}/collected/ledger-item.json" --out-dir "${RUN_DIR}/derived-verdict" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-derive-1.log" | tail -n 8; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/queue_derive.py --poll "${RUN_DIR}/raw/poll.jsonl" --events "${RUN_DIR}/raw/events.jsonl" --enqueue-summary "${RUN_DIR}/raw/enqueue/summary.json" --worker-logs "${RUN_DIR}/collected/worker-logs.json" --out-dir "${RUN_DIR}/derived-queue" --title "Experiment F: queue, DLQ and worker" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-derive-2.log" | tail -n 8; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/lib/metrics_derive.py derive --collected-dir "${RUN_DIR}/collected" --out-dir "${RUN_DIR}/derived-metrics" --events-file "${RUN_DIR}/raw/events.jsonl" --title "Experiment F: CloudWatch metrics" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-f-derive-3.log" | tail -n 8; echo "exit: $?"
```
Expected on a clean recovery: `DERIVE_COMPLETE pass=9 fail=0 unknown=0 info=3` (one poison message, one duplicate; omit `--ledger-item` if `ledger-item.json` was not collected, and the ledger row is then UNKNOWN). Any FAIL or UNKNOWN is read and reported, not edited.

## Metric names
As C (the CloudWatch queries are C's template); UNVERIFIED this turn. DLQ arrival is read from the poll file first; CloudWatch SQS metrics are the second source.

## Limitations
- The ledger key layout (`pk EMBEDWORK#<summaryId>`, `sk WORK#<index>#<textHash>`) and the S3 Vectors `get-vectors` CLI name are from the WO-7 report and my reading of it; UNVERIFIED on a live table. A failed optional call is recorded, and the row is UNKNOWN.
- Only one fault class (poison) is shown; transient downstream failure and worker termination are not injected.
- Receives counted by the worker include deferrals toward `maxReceiveCount` (WO-7 risk note): a message deferred or released by the worker uses up receives, so a normal message under heavy contention could reach the DLQ. `verdict.csv` judges only that the DLQ holds at least the poison count; a DLQ total above 1 shows in `queue_derive`'s `dlq_max` and is reported as a finding, not hidden.
