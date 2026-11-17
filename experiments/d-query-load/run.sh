#!/usr/bin/env bash
# Experiment D entry point: query load at four concurrency levels (1, 8, 24, 48 clients, 8 minutes
# each, then 15 minutes with no client so the services can scale in; decision P15-1). It starts
# ONE load-client ECS task (load-tests/query-load/load_client.py, in the VPC, against the internal
# load balancer) and waits for it. It changes no setting during the run, retries nothing and uses
# the offline stub answer model (P15-4: no Bedrock in D).
#
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-d-query-load.manifest. The only AWS write calls are
# `ecs run-task` (one task) and, at the time limit or on TERM, `ecs stop-task` for that task
# (load-tests/lib/ecs-load-client.sh).
#
# Human-supplied environment (identifiers only; the query token comes from Secrets Manager through
# the load-client task definition):
#   SCHOLARCHAIN_D_BASE_URL          internal ALB URL, http://<dns name>[:port], no path
#   SCHOLARCHAIN_LC_CLUSTER, SCHOLARCHAIN_LC_TASK_DEFINITION, SCHOLARCHAIN_LC_CONTAINER,
#   SCHOLARCHAIN_LC_SUBNETS, SCHOLARCHAIN_LC_SECURITY_GROUPS   (see load-tests/lib/ecs-load-client.sh)
# Caps come from the manifest through the environment run-experiment.sh sets:
#   --max-clients MAX_CONCURRENCY, --max-requests MAX_TASKS, --max-minutes MAX_MINUTES minus 15.
# The schedule and the think time are committed constants (the approval digest binds them).
# Think time 2.0 to 4.0 s uniform per request: the request bound is
#   (1 + 8 + 24 + 48) x (floor(480 / 2.0) + 1) = 81 x 241 = 19,521 < 20,000, see
#   load-tests/query-load/README.md.
# The container's disk is lost with the task, so the client echoes every request line to stdout
# (LOADCLIENT_RAW) and the collector reads them back from CloudWatch Logs
# (experiments/d-query-load/collect.sh). Raw output here: events.jsonl, load-client-*.json,
# window.json.
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"
# shellcheck source=load-tests/lib/entry-common.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/entry-common.sh"
# shellcheck source=load-tests/lib/ecs-load-client.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/ecs-load-client.sh"

exp_init D
ec_begin
trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; elc_stop_if_running; exit 143' TERM INT
ec_require_env SCHOLARCHAIN_D_BASE_URL SCHOLARCHAIN_LC_CLUSTER SCHOLARCHAIN_LC_TASK_DEFINITION \
  SCHOLARCHAIN_LC_CONTAINER SCHOLARCHAIN_LC_SUBNETS SCHOLARCHAIN_LC_SECURITY_GROUPS

CLIENT_MINUTES=$((SCHOLARCHAIN_MAX_MINUTES - 15))
[[ "$CLIENT_MINUTES" -ge 50 ]] || exp_die "MAX_MINUTES $SCHOLARCHAIN_MAX_MINUTES leaves under 50 minutes for the client (the schedule takes 47)"
TAG="D$(printf '%s' "$SCHOLARCHAIN_RUN_ID" | tr -cd 'A-Za-z0-9' | tail -c 8)"
COMMAND_JSON="[\"--base-url\",\"$SCHOLARCHAIN_D_BASE_URL\",\"--out-dir\",\"/tmp/loadclient/$TAG\",\"--run-tag\",\"$TAG\",\"--levels\",\"1,8,24,48\",\"--level-seconds\",\"480\",\"--cooldown-seconds\",\"900\",\"--think-min\",\"2.0\",\"--think-max\",\"4.0\",\"--timeout\",\"30\",\"--max-clients\",\"$SCHOLARCHAIN_MAX_CONCURRENCY\",\"--max-requests\",\"$SCHOLARCHAIN_MAX_TASKS\",\"--max-minutes\",\"$CLIENT_MINUTES\",\"--echo\"]"
ec_add run_tag "$TAG"
ec_add task_definition "$SCHOLARCHAIN_LC_TASK_DEFINITION"

max_wait=$((SCHOLARCHAIN_MAX_MINUTES * 60 - 300 - SECONDS))
rc=0
elc_run D "$COMMAND_JSON" "$max_wait" || rc=$?
case "$rc" in
  0) ec_set_status completed ;;
  3) ec_set_status client-stopped-at-a-cap ;;
  *) ec_set_status failed ;;
esac
exit "$rc"
