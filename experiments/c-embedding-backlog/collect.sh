#!/usr/bin/env bash
# Experiment C read-only collector. Run by the human after the experiment run has ended.
# Every AWS call is a describe-, list- or get- call or logs filter-log-events (see
# load-tests/lib/collect-common.sh, which refuses any other verb). Writes <run dir>/collected/
# once, through a staging directory, and refuses an existing collected/.
#
# Usage:
#   AWS_PROFILE=scholarchain bash experiments/c-embedding-backlog/collect.sh --run-dir /abs/<OUTPUT_ROOT>/<run id>
# Environment (same identifiers as the run):
#   SCHOLARCHAIN_C_QUEUE_URL, SCHOLARCHAIN_C_DLQ_URL, SCHOLARCHAIN_C_ECS_CLUSTER, SCHOLARCHAIN_C_ECS_SERVICE
#   SCHOLARCHAIN_C_WORKER_LOG_GROUP   CloudWatch Logs group of the worker (REQUIRED: the retry
#                                     count and the outcome counts come from its WORKER_MSG lines;
#                                     the default retention is 3 days, so collect within 3 days)
#   SCHOLARCHAIN_C_ALARM_PREFIX       optional: alarm name prefix of the scaling alarms
#
# Files written (raw, as returned by the CLI, one call each): cloudwatch-metric-data.json,
# metric-queries.json, sqs-queue-attributes.json, sqs-dlq-attributes.json, ecs-describe-services.json
# (its `events` list holds the service scaling and placement events), worker-logs.json,
# optional: app-autoscaling-{activities,policies,targets}.json, cloudwatch-list-metrics-{sqs,ecs}.json,
# cloudwatch-describe-alarms.json; CALL_STATUS.txt, COLLECTED.txt, SHA256SUMS.
# REDACTION before a results commit: the sqs and ecs files hold ARNs with the AWS account ID;
# make a copy with the 12-digit ID replaced by 000000000000 (see experiments/b-burst/README.md).
set -euo pipefail

SC_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-tests/lib/collect-common.sh
source "$SC_HERE/../../load-tests/lib/collect-common.sh"

cc_parse_args "$@"
for v in SCHOLARCHAIN_C_QUEUE_URL SCHOLARCHAIN_C_DLQ_URL SCHOLARCHAIN_C_ECS_CLUSTER SCHOLARCHAIN_C_ECS_SERVICE SCHOLARCHAIN_C_WORKER_LOG_GROUP; do
  [[ -n "${!v:-}" ]] || cc_die "$v is not set"
done
cc_init

QUEUE_NAME="${SCHOLARCHAIN_C_QUEUE_URL##*/}"
DLQ_NAME="${SCHOLARCHAIN_C_DLQ_URL##*/}"
CLUSTER="$SCHOLARCHAIN_C_ECS_CLUSTER"
SERVICE="$SCHOLARCHAIN_C_ECS_SERVICE"
RESOURCE_ID="service/$CLUSTER/$SERVICE"

cc_metrics "$SC_HERE/queries.template.json" "QUEUE_NAME=$QUEUE_NAME" "DLQ_NAME=$DLQ_NAME" "CLUSTER=$CLUSTER" "SERVICE=$SERVICE"
cc_save sqs-queue-attributes.json sqs get-queue-attributes --queue-url "$SCHOLARCHAIN_C_QUEUE_URL" --attribute-names All
cc_save sqs-dlq-attributes.json sqs get-queue-attributes --queue-url "$SCHOLARCHAIN_C_DLQ_URL" --attribute-names All
cc_save ecs-describe-services.json ecs describe-services --cluster "$CLUSTER" --services "$SERVICE"
cc_save worker-logs.json logs filter-log-events --log-group-name "$SCHOLARCHAIN_C_WORKER_LOG_GROUP" \
  --start-time "$(cc_py epoch-ms --time "$CC_FROM")" --end-time "$(cc_py epoch-ms --time "$CC_TO")" --filter-pattern "WORKER_MSG"
cc_save_optional app-autoscaling-activities.json application-autoscaling describe-scaling-activities --service-namespace ecs --resource-id "$RESOURCE_ID"
cc_save_optional app-autoscaling-policies.json application-autoscaling describe-scaling-policies --service-namespace ecs --resource-id "$RESOURCE_ID"
cc_save_optional app-autoscaling-targets.json application-autoscaling describe-scalable-targets --service-namespace ecs --resource-ids "$RESOURCE_ID"
cc_save_optional cloudwatch-list-metrics-sqs.json cloudwatch list-metrics --namespace AWS/SQS --dimensions "Name=QueueName,Value=$QUEUE_NAME"
cc_save_optional cloudwatch-list-metrics-ecs.json cloudwatch list-metrics --namespace ECS/ContainerInsights --dimensions "Name=ClusterName,Value=$CLUSTER"
if [[ -n "${SCHOLARCHAIN_C_ALARM_PREFIX:-}" ]]; then
  cc_save_optional cloudwatch-describe-alarms.json cloudwatch describe-alarms --alarm-name-prefix "$SCHOLARCHAIN_C_ALARM_PREFIX"
fi
cc_finish "QUEUE_NAME=$QUEUE_NAME" "DLQ_NAME=$DLQ_NAME" "ECS_SERVICE=$RESOURCE_ID"
