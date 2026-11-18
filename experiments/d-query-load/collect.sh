#!/usr/bin/env bash
# Experiment D (and E) read-only collector. Run by the human after the run has ended. Every AWS
# call is a describe-, list- or get- call or logs filter-log-events (load-tests/lib/collect-common.sh
# refuses any other verb). Writes <run dir>/collected/ once, through a staging directory.
#
# Usage:
#   AWS_PROFILE=scholarchain bash experiments/d-query-load/collect.sh --run-dir /abs/<OUTPUT_ROOT>/<run id>
# Environment (identifiers only; the same names serve D and E, which run on the same stack):
#   SCHOLARCHAIN_QL_ECS_CLUSTER, SCHOLARCHAIN_QL_ECS_SERVICE   the service that answers /v1/query
#   SCHOLARCHAIN_QL_ALB             CloudWatch LoadBalancer dimension, app/<name>/<hash>
#   SCHOLARCHAIN_QL_TARGET_GROUP    TargetGroup dimension, targetgroup/<name>/<hash>
#   SCHOLARCHAIN_QL_NEPTUNE_CLUSTER Neptune DBClusterIdentifier
#   SCHOLARCHAIN_QL_DDB_TABLES      comma-separated DynamoDB table names the services use
#   SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP     log group of the load-client task (REQUIRED: the raw
#                                   request lines live there; default retention is 3 days, so
#                                   collect within 3 days of the run)
#   SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX awslogs-stream-prefix/container-name of that task
#                                   definition (read it from the deployed definition); the stream is <prefix>/<task id>
#   SCHOLARCHAIN_QL_SERVICE_LOG_GROUP        optional: the service's log group, for the per-request
#                                   stage timings (planning, graph, vector, fusion, synthesis,
#                                   verification). The retrieval-mode field is `retrievalMode`.
#   SCHOLARCHAIN_QL_ALARM_PREFIX             optional: alarm name prefix of the scaling alarms
# Files (raw, one call each): cloudwatch-metric-data.json, metric-queries.json,
# loadclient-logs.json, ecs-describe-services.json, optional service-logs.json,
# app-autoscaling-*.json, cloudwatch-list-metrics-*.json, cloudwatch-describe-alarms.json,
# CALL_STATUS.txt, COLLECTED.txt, SHA256SUMS.
# REDACTION before a results commit: ecs-describe-services.json and the logs may hold ARNs with the
# AWS account ID; commit a copy with the 12-digit ID replaced by 000000000000.
set -euo pipefail

SC_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-tests/lib/collect-common.sh
source "$SC_HERE/../../load-tests/lib/collect-common.sh"

cc_parse_args "$@"
for v in SCHOLARCHAIN_QL_ECS_CLUSTER SCHOLARCHAIN_QL_ECS_SERVICE SCHOLARCHAIN_QL_ALB SCHOLARCHAIN_QL_TARGET_GROUP \
  SCHOLARCHAIN_QL_NEPTUNE_CLUSTER SCHOLARCHAIN_QL_DDB_TABLES SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX; do
  [[ -n "${!v:-}" ]] || cc_die "$v is not set"
done
cc_init

CLUSTER="$SCHOLARCHAIN_QL_ECS_CLUSTER"
SERVICE="$SCHOLARCHAIN_QL_ECS_SERVICE"
RESOURCE_ID="service/$CLUSTER/$SERVICE"
TASK_ARN="$(cc_py json-field --file "$CC_RUN_DIR/raw/load-client-run-task.json" --path tasks.0.taskArn)"
[[ -n "$TASK_ARN" ]] || cc_die "raw/load-client-run-task.json holds no task ARN (the load client never started)"
TASK_ID="${TASK_ARN##*/}"
FROM_MS="$(cc_py epoch-ms --time "$CC_FROM")"
TO_MS="$(cc_py epoch-ms --time "$CC_TO")"

cc_metrics "$SC_HERE/queries.template.json" "ALB=$SCHOLARCHAIN_QL_ALB" "TARGET_GROUP=$SCHOLARCHAIN_QL_TARGET_GROUP" \
  "CLUSTER=$CLUSTER" "SERVICE=$SERVICE" "NEPTUNE_CLUSTER=$SCHOLARCHAIN_QL_NEPTUNE_CLUSTER" "TABLES=$SCHOLARCHAIN_QL_DDB_TABLES"
cc_save loadclient-logs.json logs filter-log-events --log-group-name "$SCHOLARCHAIN_QL_LOADCLIENT_LOG_GROUP" \
  --log-stream-names "$SCHOLARCHAIN_QL_LOADCLIENT_STREAM_PREFIX/$TASK_ID" --start-time "$FROM_MS" --end-time "$TO_MS" \
  --filter-pattern '?"LOADCLIENT_RAW" ?"LOADCLIENT_EVENT"'
cc_save ecs-describe-services.json ecs describe-services --cluster "$CLUSTER" --services "$SERVICE"
if [[ -n "${SCHOLARCHAIN_QL_SERVICE_LOG_GROUP:-}" ]]; then
  cc_save_optional service-logs.json logs filter-log-events --log-group-name "$SCHOLARCHAIN_QL_SERVICE_LOG_GROUP" \
    --start-time "$FROM_MS" --end-time "$TO_MS"
fi
cc_save_optional app-autoscaling-activities.json application-autoscaling describe-scaling-activities --service-namespace ecs --resource-id "$RESOURCE_ID"
cc_save_optional app-autoscaling-policies.json application-autoscaling describe-scaling-policies --service-namespace ecs --resource-id "$RESOURCE_ID"
cc_save_optional app-autoscaling-targets.json application-autoscaling describe-scalable-targets --service-namespace ecs --resource-ids "$RESOURCE_ID"
cc_save_optional cloudwatch-list-metrics-alb.json cloudwatch list-metrics --namespace AWS/ApplicationELB --dimensions "Name=LoadBalancer,Value=$SCHOLARCHAIN_QL_ALB"
cc_save_optional cloudwatch-list-metrics-ecs.json cloudwatch list-metrics --namespace ECS/ContainerInsights --dimensions "Name=ClusterName,Value=$CLUSTER"
cc_save_optional cloudwatch-list-metrics-neptune.json cloudwatch list-metrics --namespace AWS/Neptune --dimensions "Name=DBClusterIdentifier,Value=$SCHOLARCHAIN_QL_NEPTUNE_CLUSTER"
if [[ -n "${SCHOLARCHAIN_QL_ALARM_PREFIX:-}" ]]; then
  cc_save_optional cloudwatch-describe-alarms.json cloudwatch describe-alarms --alarm-name-prefix "$SCHOLARCHAIN_QL_ALARM_PREFIX"
fi
cc_finish "ECS_SERVICE=$RESOURCE_ID" "LOADCLIENT_TASK_ID=$TASK_ID"
