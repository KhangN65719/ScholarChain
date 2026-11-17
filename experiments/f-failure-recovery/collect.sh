#!/usr/bin/env bash
# Experiment F read-only collector. Run by the human after the run has ended. Read-only verbs
# only (load-tests/lib/collect-common.sh refuses any other); writes <run dir>/collected/ once.
#
# Usage:
#   AWS_PROFILE=scholarchain bash experiments/f-failure-recovery/collect.sh --run-dir /abs/<OUTPUT_ROOT>/<run id>
# Environment (same identifiers as the run, plus):
#   SCHOLARCHAIN_F_WORKER_LOG_GROUP   REQUIRED, the worker's log group (WORKER_MSG lines; default
#                                     retention 3 days, collect within 3 days)
#   SCHOLARCHAIN_F_LEDGER_TABLE       the run-ledger DynamoDB table name (the ledger item of the
#                                     duplicated message is read with get-item; PENDING-WO-10 for the
#                                     name, key layout from WO-7 and UNVERIFIED live)
#   SCHOLARCHAIN_F_VECTOR_BUCKET      optional: the S3 Vectors bucket; with it the duplicate's vector
#                                     is read with s3vectors get-vectors (CLI name UNVERIFIED)
# Files: cloudwatch-metric-data.json, metric-queries.json (the template of Experiment C),
# sqs-queue-attributes.json, sqs-dlq-attributes.json, ecs-describe-services.json, worker-logs.json,
# ledger-item.json (optional), duplicate-vector.json (optional), CALL_STATUS.txt, COLLECTED.txt, SHA256SUMS.
set -euo pipefail

SC_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-tests/lib/collect-common.sh
source "$SC_HERE/../../load-tests/lib/collect-common.sh"

cc_parse_args "$@"
for v in SCHOLARCHAIN_F_QUEUE_URL SCHOLARCHAIN_F_DLQ_URL SCHOLARCHAIN_F_ECS_CLUSTER SCHOLARCHAIN_F_ECS_SERVICE SCHOLARCHAIN_F_WORKER_LOG_GROUP SCHOLARCHAIN_F_LEDGER_TABLE; do
  [[ -n "${!v:-}" ]] || cc_die "$v is not set"
done
cc_init

QUEUE_NAME="${SCHOLARCHAIN_F_QUEUE_URL##*/}"
DLQ_NAME="${SCHOLARCHAIN_F_DLQ_URL##*/}"
CLUSTER="$SCHOLARCHAIN_F_ECS_CLUSTER"
SERVICE="$SCHOLARCHAIN_F_ECS_SERVICE"
MESSAGES="$CC_RUN_DIR/raw/enqueue/messages.jsonl"
[[ -f "$MESSAGES" ]] || cc_die "$MESSAGES is missing (the injection did not run)"
LEDGER_KEY="$(python3 -I "$SC_HERE/derive_f.py" ledger-key --messages "$MESSAGES")"

cc_metrics "$SC_HERE/../c-embedding-backlog/queries.template.json" "QUEUE_NAME=$QUEUE_NAME" "DLQ_NAME=$DLQ_NAME" "CLUSTER=$CLUSTER" "SERVICE=$SERVICE"
cc_save sqs-queue-attributes.json sqs get-queue-attributes --queue-url "$SCHOLARCHAIN_F_QUEUE_URL" --attribute-names All
cc_save sqs-dlq-attributes.json sqs get-queue-attributes --queue-url "$SCHOLARCHAIN_F_DLQ_URL" --attribute-names All
cc_save ecs-describe-services.json ecs describe-services --cluster "$CLUSTER" --services "$SERVICE"
cc_save worker-logs.json logs filter-log-events --log-group-name "$SCHOLARCHAIN_F_WORKER_LOG_GROUP" \
  --start-time "$(cc_py epoch-ms --time "$CC_FROM")" --end-time "$(cc_py epoch-ms --time "$CC_TO")" --filter-pattern "WORKER_MSG"
cc_save_optional ledger-item.json dynamodb get-item --table-name "$SCHOLARCHAIN_F_LEDGER_TABLE" --key "$LEDGER_KEY" --consistent-read
if [[ -n "${SCHOLARCHAIN_F_VECTOR_BUCKET:-}" ]]; then
  DUP_ID="$(python3 -I "$SC_HERE/derive_f.py" ledger-key --messages "$MESSAGES" | python3 -I "$CC_LIB_DIR/metrics_derive.py" json-field --file /dev/stdin --path pk.S)"
  DUP_ID="${DUP_ID#EMBEDWORK#}"
  DUP_INDEX="$(python3 -I "$SC_HERE/derive_f.py" ledger-key --messages "$MESSAGES" | python3 -I "$CC_LIB_DIR/metrics_derive.py" json-field --file /dev/stdin --path sk.S)"
  DUP_INDEX="${DUP_INDEX#WORK#}"
  DUP_INDEX="${DUP_INDEX%%#*}"
  cc_save_optional duplicate-vector.json s3vectors get-vectors --vector-bucket-name "$SCHOLARCHAIN_F_VECTOR_BUCKET" \
    --index-name "$DUP_INDEX" --keys "$DUP_ID"
fi
cc_finish "QUEUE_NAME=$QUEUE_NAME" "DLQ_NAME=$DLQ_NAME" "ECS_SERVICE=service/$CLUSTER/$SERVICE"
