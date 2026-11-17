#!/usr/bin/env bash
# Experiment E entry point: retrieval modes. The SAME 351 fixed questions are sent in graph,
# vector and fused mode (1,053 requests, one client) against the stub answer model (P15-4).
# ORDER: interleaved by question, with the mode order rotated (question i starts at mode
# i mod 3). Why not one mode after the other: a block per mode would put any warm-up, cache or
# time-of-day drift of the stack entirely into one mode, and the first mode would always pay
# the cold start. Interleaving gives the three modes the same questions in the same period of
# time, and the rotation keeps any one mode from always being first for a question.
# It starts ONE load-client ECS task and waits; see experiments/d-query-load/run.sh for the AWS
# calls and settings (the same SCHOLARCHAIN_LC_* identifiers), plus:
#   SCHOLARCHAIN_E_BASE_URL   internal ALB URL, http://<dns name>[:port], no path
# Caps from the manifest through the environment: --max-clients MAX_CONCURRENCY,
# --max-requests MAX_TASKS (needs at least 1,053), --max-minutes MAX_MINUTES minus 10.
# The retrieval-mode request field name is the client default `retrievalMode`
# (PENDING-WO-8: change load_client.py --mode-field default or this constant when WO-8 reports the
# landed name; a change is a new script digest and a new approval record).
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"
# shellcheck source=load-tests/lib/entry-common.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/entry-common.sh"
# shellcheck source=load-tests/lib/ecs-load-client.sh
source "$SC_ENTRY_DIR/../../load-tests/lib/ecs-load-client.sh"

MODE_FIELD="retrievalMode"

exp_init E
ec_begin
trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; elc_stop_if_running; exit 143' TERM INT
# Optional subset (M1): SCHOLARCHAIN_E_MODES (default graph,vector,fused) and
# SCHOLARCHAIN_E_LIMIT_QUERIES (default 351 = all). The environment is not bound by the approval
# digest, so both values are written to the run ledger (ec_add) and a non-default run is labeled
# run_scope=subset-smoke. Examples: graph only = MODES graph (351 requests); labeled mixed smoke =
# MODES graph,vector,fused with LIMIT_QUERIES 12 (36 requests).
E_MODES="${SCHOLARCHAIN_E_MODES:-graph,vector,fused}"
E_LIMIT="${SCHOLARCHAIN_E_LIMIT_QUERIES:-351}"
# No leading zero: bash arithmetic reads 010 as octal 8 while load_client.py reads it as 10.
[[ "$E_LIMIT" =~ ^[1-9][0-9]*$ ]] && [[ "$E_LIMIT" -le 351 ]] || exp_die "SCHOLARCHAIN_E_LIMIT_QUERIES must be 1 to 351 with no leading zero, got '$E_LIMIT'"
case "$E_MODES" in
  graph | vector | fused | graph,vector,fused | graph,vector | graph,fused | vector,fused) ;;
  *) exp_die "SCHOLARCHAIN_E_MODES '$E_MODES' must be one of graph, vector, fused or a comma list in that order" ;;
esac
E_MODE_COUNT=$(printf '%s\n' "$E_MODES" | tr ',' '\n' | wc -l | tr -d ' ')
[[ "$E_MODE_COUNT" -ge 1 && "$E_MODE_COUNT" -le 3 ]] || exp_die "SCHOLARCHAIN_E_MODES '$E_MODES' gave mode count '$E_MODE_COUNT', expected 1 to 3"
REQUESTS=$((E_LIMIT * E_MODE_COUNT))
E_SCOPE=full
if [[ "$E_MODES" != "graph,vector,fused" || "$E_LIMIT" -ne 351 ]]; then E_SCOPE=subset-smoke; fi

ec_require_env SCHOLARCHAIN_E_BASE_URL SCHOLARCHAIN_LC_CLUSTER SCHOLARCHAIN_LC_TASK_DEFINITION \
  SCHOLARCHAIN_LC_CONTAINER SCHOLARCHAIN_LC_SUBNETS SCHOLARCHAIN_LC_SECURITY_GROUPS
[[ "$SCHOLARCHAIN_MAX_TASKS" -ge "$REQUESTS" ]] || exp_die "MAX_TASKS $SCHOLARCHAIN_MAX_TASKS is below the $REQUESTS requests of this experiment"
CLIENT_MINUTES=$((SCHOLARCHAIN_MAX_MINUTES - 10))
[[ "$CLIENT_MINUTES" -ge 20 ]] || exp_die "MAX_MINUTES $SCHOLARCHAIN_MAX_MINUTES leaves under 20 minutes for the client"
TAG="E$(printf '%s' "$SCHOLARCHAIN_RUN_ID" | tr -cd 'A-Za-z0-9' | tail -c 8)"
COMMAND_JSON="[\"--base-url\",\"$SCHOLARCHAIN_E_BASE_URL\",\"--out-dir\",\"/tmp/loadclient/$TAG\",\"--run-tag\",\"$TAG\",\"--sequence\",\"rotate-modes\",\"--modes\",\"$E_MODES\",\"--limit-queries\",\"$E_LIMIT\",\"--clients\",\"1\",\"--mode-field\",\"$MODE_FIELD\",\"--think-min\",\"0.2\",\"--think-max\",\"0.4\",\"--timeout\",\"30\",\"--max-clients\",\"$SCHOLARCHAIN_MAX_CONCURRENCY\",\"--max-requests\",\"$SCHOLARCHAIN_MAX_TASKS\",\"--max-minutes\",\"$CLIENT_MINUTES\",\"--echo\"]"
ec_add run_tag "$TAG"
ec_add order "interleaved-rotated"
ec_add run_scope "$E_SCOPE"
ec_add modes "$E_MODES"
ec_add limit_queries "$E_LIMIT"
ec_add planned_requests "$REQUESTS"

max_wait=$((SCHOLARCHAIN_MAX_MINUTES * 60 - 300 - SECONDS))
rc=0
elc_run E "$COMMAND_JSON" "$max_wait" || rc=$?
case "$rc" in
  0) ec_set_status completed ;;
  3) ec_set_status client-stopped-at-a-cap ;;
  *) ec_set_status failed ;;
esac
exit "$rc"
