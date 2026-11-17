#!/usr/bin/env bash
# Experiment F entry point: one safe failure on the embedding queue. It sends 50 normal messages,
# ONE poison message (schemaVersion 2: the worker refuses it and never deletes it) in the middle
# of the send order, and ONE duplicate (a second send of the body of normal message 24). Then it
# watches the queue, the dead-letter queue and the worker service until the main queue is drained
# and the poison message is in the dead-letter queue, or the time limit comes. It injects no other
# fault, restarts nothing and changes no setting; the worker is what Terraform built.
# What the run is meant to show (judged afterwards by derive_f.py from raw files, never here):
#   retry          the poison message is received 3 times (maxReceiveCount 3)
#   dead letter    it arrives in the dead-letter queue and is never written as a vector
#   idempotency    the duplicate is embedded and written once, the other delivery is skipped
#   recovery       the 50 normal messages complete and the worker keeps draining
# Timing note: the poison message needs 3 receives, each followed by a visibility timeout (the
# worker's SCHOLARCHAIN_WORKER_VISIBILITY_SECONDS, default 120; the queue default is 300), so the
# dead-letter arrival comes about 5 to 15 minutes after the start. The poll limit is the
# manifest MAX_MINUTES minus 5 minutes.
#
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-f-failure-recovery.manifest. The only AWS write call is
# `sqs send-message-batch` inside experiments/c-embedding-backlog/enqueue.py.
# Human-supplied environment (identifiers only):
#   SCHOLARCHAIN_F_QUEUE_URL, SCHOLARCHAIN_F_DLQ_URL, SCHOLARCHAIN_F_CHUNK_FILE (outside the
#   repository, P15-14), SCHOLARCHAIN_F_ECS_CLUSTER, SCHOLARCHAIN_F_ECS_SERVICE
# Order of sessions: F runs BEFORE C (P15-10), and its 50 normal rows are rows 0 to 49 of the C chunk
# file, so C will find those 50 vectors already done (the worker reports them as already_done).
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"
# shellcheck source=load-tests/lib/entry-common.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/entry-common.sh"

NORMAL_COUNT=50
DUPLICATE_OF=24
CHUNK_SHA256="1ccaefcb892b728b4ed6f97207d29827edca912df970d9c6b47c0f957d220c92"
LIB="$SC_ENTRY_DIR/../../load-tests/lib"

exp_init F
ec_begin
ec_require_env SCHOLARCHAIN_F_QUEUE_URL SCHOLARCHAIN_F_DLQ_URL SCHOLARCHAIN_F_CHUNK_FILE SCHOLARCHAIN_F_ECS_CLUSTER SCHOLARCHAIN_F_ECS_SERVICE
[[ "$SCHOLARCHAIN_MAX_TASKS" -ge $((NORMAL_COUNT + 2)) ]] || exp_die "MAX_TASKS $SCHOLARCHAIN_MAX_TASKS is below the $((NORMAL_COUNT + 2)) messages of this experiment"

rc=0
exp_event INJECT_START "normal=$NORMAL_COUNT poison=1 kind=version duplicate_of=$DUPLICATE_OF"
python3 -I "$SC_ENTRY_DIR/../c-embedding-backlog/enqueue.py" \
  --chunk-file "$SCHOLARCHAIN_F_CHUNK_FILE" --expect-sha256 "$CHUNK_SHA256" \
  --queue-url "$SCHOLARCHAIN_F_QUEUE_URL" --count "$NORMAL_COUNT" --poison 1 --poison-kind version \
  --duplicate-of "$DUPLICATE_OF" --max-messages "$SCHOLARCHAIN_MAX_TASKS" \
  --out-dir "$EXP_RAW/enqueue" --region "$SCHOLARCHAIN_REGION" > "$EXP_RAW/enqueue.log" 2>&1 || rc=$?
exp_event INJECT_END "exit=$rc"
if [[ "$rc" -ne 0 ]]; then
  ec_set_status inject-failed
  exit "$rc"
fi

remaining=$((SCHOLARCHAIN_MAX_MINUTES * 60 - 300 - SECONDS))
[[ "$remaining" -ge 120 ]] || exp_die "less than 120 s of the experiment limit is left for the observation"
exp_event POLL_START "duration_seconds=$remaining"
rc=0
python3 -I "$LIB/aws_poll.py" --out "$EXP_RAW/poll.jsonl" \
  --queue-url "$SCHOLARCHAIN_F_QUEUE_URL" --dlq-url "$SCHOLARCHAIN_F_DLQ_URL" \
  --ecs-cluster "$SCHOLARCHAIN_F_ECS_CLUSTER" --ecs-service "$SCHOLARCHAIN_F_ECS_SERVICE" \
  --region "$SCHOLARCHAIN_REGION" --interval 15 --duration-seconds "$remaining" \
  --stop-when drained --consecutive 2 --require-backlog --expect-dlq 1 > "$EXP_RAW/poll.log" 2>&1 || rc=$?
exp_event POLL_END "exit=$rc"
case "$rc" in
  0) ec_set_status drained-and-dead-lettered ;;
  5) ec_set_status not-settled-before-limit ;;
  *) ec_set_status poll-failed ;;
esac
exit "$rc"
