#!/usr/bin/env bash
# Experiment C entry point: embedding backlog. Enqueue 2,000 text chunks on the embedding-work
# queue once, then watch the queue, the dead-letter queue and the worker service until the queue
# is drained or the time limit comes. It does not scale, tune, restart or retry anything; the
# worker service and its scaling policy are what Terraform built (WO-10).
#
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-c-embedding-backlog.manifest. The only AWS write call is
# `sqs send-message-batch` inside enqueue.py (the approved workload). The polling is read-only.
#
# Human-supplied environment (identifiers only, no secret):
#   SCHOLARCHAIN_C_QUEUE_URL     the embedding-work queue URL
#   SCHOLARCHAIN_C_DLQ_URL       its dead-letter queue URL
#   SCHOLARCHAIN_C_CHUNK_FILE    absolute path of the chunk file outside the repository (P15-14)
#   SCHOLARCHAIN_C_ECS_CLUSTER   ECS cluster of the embedding worker service
#   SCHOLARCHAIN_C_ECS_SERVICE   the embedding worker ECS service name
# Committed constants (the approval digest binds them):
#   2,000 chunks; chunk file sha256 from datasets/tier-s/chunks-c/chunkset-manifest.json;
#   poll every 30 s; stop when the backlog has been 0 for 2 polls in a row after it was seen.
# Raw output in SCHOLARCHAIN_RUN_DIR (raw/): events.jsonl, enqueue/ (messages.jsonl, send-log.jsonl,
# summary.json), enqueue.log, poll.jsonl, poll.log, window.json.
# The poll duration is the manifest MAX_MINUTES minus 5 minutes (time to write the window), so
# the experiment limit stays above it, and a time-out is a failed run, kept and reported.
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"
# shellcheck source=load-tests/lib/entry-common.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/entry-common.sh"

CHUNK_COUNT=2000
CHUNK_SHA256="1ccaefcb892b728b4ed6f97207d29827edca912df970d9c6b47c0f957d220c92"
LIB="$SC_ENTRY_DIR/../../load-tests/lib"

exp_init C
ec_begin
ec_require_env SCHOLARCHAIN_C_QUEUE_URL SCHOLARCHAIN_C_DLQ_URL SCHOLARCHAIN_C_CHUNK_FILE SCHOLARCHAIN_C_ECS_CLUSTER SCHOLARCHAIN_C_ECS_SERVICE
[[ "$SCHOLARCHAIN_MAX_TASKS" -ge "$CHUNK_COUNT" ]] || exp_die "MAX_TASKS $SCHOLARCHAIN_MAX_TASKS is below the $CHUNK_COUNT chunks this experiment sends"

rc=0
exp_event ENQUEUE_START "count=$CHUNK_COUNT"
python3 -I "$SC_ENTRY_DIR/enqueue.py" \
  --chunk-file "$SCHOLARCHAIN_C_CHUNK_FILE" --expect-sha256 "$CHUNK_SHA256" \
  --queue-url "$SCHOLARCHAIN_C_QUEUE_URL" --count "$CHUNK_COUNT" \
  --max-messages "$SCHOLARCHAIN_MAX_TASKS" --out-dir "$EXP_RAW/enqueue" \
  --region "$SCHOLARCHAIN_REGION" > "$EXP_RAW/enqueue.log" 2>&1 || rc=$?
exp_event ENQUEUE_END "exit=$rc"
if [[ "$rc" -ne 0 ]]; then
  ec_set_status enqueue-failed
  exit "$rc"
fi

remaining=$((SCHOLARCHAIN_MAX_MINUTES * 60 - 300 - SECONDS))
[[ "$remaining" -ge 120 ]] || exp_die "less than 120 s of the experiment limit is left for the drain"
exp_event POLL_START "duration_seconds=$remaining"
rc=0
python3 -I "$LIB/aws_poll.py" --out "$EXP_RAW/poll.jsonl" \
  --queue-url "$SCHOLARCHAIN_C_QUEUE_URL" --dlq-url "$SCHOLARCHAIN_C_DLQ_URL" \
  --ecs-cluster "$SCHOLARCHAIN_C_ECS_CLUSTER" --ecs-service "$SCHOLARCHAIN_C_ECS_SERVICE" \
  --region "$SCHOLARCHAIN_REGION" --interval 30 --duration-seconds "$remaining" \
  --stop-when drained --consecutive 2 --require-backlog > "$EXP_RAW/poll.log" 2>&1 || rc=$?
exp_event POLL_END "exit=$rc"
case "$rc" in
  0) ec_set_status drained ;;
  5) ec_set_status not-drained-before-limit ;;
  *) ec_set_status poll-failed ;;
esac
exit "$rc"
