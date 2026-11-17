# shellcheck shell=bash
# Start ONE load-client ECS task and wait for it (Experiments D and E). Source after
# experiments/lib/exp-lib.sh. Never run it.
#
# The load client (load-tests/query-load/load_client.py, image from packaging/load-client/) must
# run inside the VPC to reach the internal load balancer, so the entry point starts it as a
# Fargate task and waits. AWS calls here: `ecs run-task` (the approved workload, one task),
# `ecs describe-tasks` (read) and `ecs stop-task` (only at the experiment time limit or on TERM,
# for the task this script started). Nothing else is created, changed or tuned.
#
# Settings (environment, identifiers only, no secret); the task definition is built by WO-10:
#   SCHOLARCHAIN_LC_CLUSTER          ECS cluster to run the task in
#   SCHOLARCHAIN_LC_TASK_DEFINITION  task definition family or ARN of the load client
#   SCHOLARCHAIN_LC_CONTAINER        container name inside that task definition (PENDING-WO-10)
#   SCHOLARCHAIN_LC_SUBNETS          comma-separated private subnet IDs
#   SCHOLARCHAIN_LC_SECURITY_GROUPS  comma-separated security group IDs (the ALB admits this one)
# The query bearer token reaches the container through the task definition's `secrets`
# (Secrets Manager); it never appears in this script, its arguments or its output.
#
# elc_run EXPERIMENT_ID COMMAND_JSON_ARRAY MAX_WAIT_SECONDS
#   writes raw/load-client-run-task.json, raw/load-client-task-final.json and events TASK_*;
#   returns the container exit code (0 ok, 3 the client stopped at a cap, 1 unknown).

ELC_TASK_ARN=""

elc_json() {
  python3 -I "$EXP_REPO_ROOT/load-tests/lib/metrics_derive.py" json-field --file "$1" --path "$2"
}

elc_stop_if_running() {
  if [[ -n "$ELC_TASK_ARN" ]]; then
    exp_event TASK_STOP_REQUEST "arn_suffix=${ELC_TASK_ARN##*/}"
    aws ecs stop-task --cluster "$SCHOLARCHAIN_LC_CLUSTER" --task "$ELC_TASK_ARN" \
      --reason "experiment limit or signal" --region "$SCHOLARCHAIN_REGION" --output json \
      > "$EXP_RAW/load-client-stop-task.json" 2>/dev/null || true
  fi
}

elc_run() {
  local exp_id="$1" command_json="$2" max_wait="$3" waited=0 stopped=0 last="" status="" net overrides failure code
  net="awsvpcConfiguration={subnets=[${SCHOLARCHAIN_LC_SUBNETS}],securityGroups=[${SCHOLARCHAIN_LC_SECURITY_GROUPS}],assignPublicIp=DISABLED}"
  overrides="{\"containerOverrides\":[{\"name\":\"${SCHOLARCHAIN_LC_CONTAINER}\",\"command\":${command_json}}]}"
  exp_event TASK_START_REQUEST "cluster=$SCHOLARCHAIN_LC_CLUSTER task_definition=$SCHOLARCHAIN_LC_TASK_DEFINITION"
  (
    set -C
    aws ecs run-task --cluster "$SCHOLARCHAIN_LC_CLUSTER" --task-definition "$SCHOLARCHAIN_LC_TASK_DEFINITION" \
      --launch-type FARGATE --count 1 --started-by "sc-exp-$exp_id" \
      --network-configuration "$net" --overrides "$overrides" \
      --region "$SCHOLARCHAIN_REGION" --output json > "$EXP_RAW/load-client-run-task.json"
  ) || { exp_event TASK_START_FAILED "run-task exit nonzero"; return 1; }
  failure="$(elc_json "$EXP_RAW/load-client-run-task.json" failures.0.reason)"
  ELC_TASK_ARN="$(elc_json "$EXP_RAW/load-client-run-task.json" tasks.0.taskArn)"
  if [[ -n "$failure" || -z "$ELC_TASK_ARN" ]]; then
    exp_event TASK_START_FAILED "reason=${failure:-no-task}"
    ELC_TASK_ARN=""
    return 1
  fi
  exp_event TASK_STARTED "task_id=${ELC_TASK_ARN##*/}"
  while true; do
    aws ecs describe-tasks --cluster "$SCHOLARCHAIN_LC_CLUSTER" --tasks "$ELC_TASK_ARN" \
      --region "$SCHOLARCHAIN_REGION" --output json > "$EXP_RAW/.task-poll.json" 2>/dev/null || true
    status="$(elc_json "$EXP_RAW/.task-poll.json" tasks.0.lastStatus 2>/dev/null || true)"
    if [[ "$status" != "$last" && -n "$status" ]]; then
      exp_event TASK_STATUS "status=$status"
      last="$status"
    fi
    [[ "$status" == "STOPPED" ]] && break
    if [[ "$stopped" -eq 0 && "$waited" -ge "$max_wait" ]]; then
      exp_event TASK_WAIT_LIMIT "waited=${waited}s"
      elc_stop_if_running
      stopped=1
    fi
    if [[ "$waited" -ge $((max_wait + 150)) ]]; then
      break
    fi
    sleep 30
    waited=$((waited + 30))
  done
  (set -C; cp "$EXP_RAW/.task-poll.json" "$EXP_RAW/load-client-task-final.json") || true
  rm -f "$EXP_RAW/.task-poll.json"
  code="$(elc_json "$EXP_RAW/load-client-task-final.json" tasks.0.containers.0.exitCode 2>/dev/null || true)"
  exp_event TASK_END "exit_code=${code:-unknown} stopped_reason=$(elc_json "$EXP_RAW/load-client-task-final.json" tasks.0.stoppedReason 2>/dev/null | tr -d '"=' | tr ' ' '-')"
  ELC_TASK_ARN=""
  [[ "$code" =~ ^[0-9]+$ ]] || return 1
  return "$code"
}
