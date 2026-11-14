#!/usr/bin/env bash
# run-bounded-pipeline.sh: start the Step Functions pipeline once, watch it, and
# stop it (and any EMR cluster it created) at the wall-clock limit or a stop
# condition. Refuses to start unless size, wall-clock limit, spend ceiling,
# hourly burn estimate, stop condition, execution input and approval are given.
#
# Usage:
#   bash scripts/run-bounded-pipeline.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 20 --approval-record /abs/approval.env \
#     --size small --max-minutes 60 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 \
#     --stop-on first-failure,dlq-nonempty --input-file /abs/execution-input.json
#
#   --size            small | medium | large, tiers S, M and L of the three-size
#                     plan in infra/terraform/COST_ESTIMATE.md (5,000, 25,000 and
#                     100,000 works). PROVISIONAL: decision D2c marks the sizes as
#                     proposed until the human confirms them. The wall-clock caps
#                     below come from the estimate's HIGH pipeline node-hours on
#                     one node (S 1.0, M 1.4, L 2.4) rounded up: small 60, medium
#                     90, large 150 minutes. They are caps, not forecasts.
#                     Reference burn for the whole session while the run is up, at
#                     HIGH rates (all UNVERIFIED, see COST_ESTIMATE.md), default
#                     endpoint egress, Neptune on (the tier overlays turn it on):
#                     one m5.xlarge node 0.35 + loader task 0.0247 + 8 interface
#                     endpoints at 0.015 each 0.12 + Neptune db.t4g.medium 0.10 =
#                     0.59; with the runtime tasks of each overlay about 0.60 for
#                     S (none), 0.62 for M (2 tasks), 0.65 for L (4 tasks). Master
#                     plus 4 core nodes, M overlay: 5 x 0.35 + 0.0247 + 0.12 + 0.10
#                     + 0.0247 = about 2.02. Add 0.015 per hour when
#                     bedrock-runtime is on. The 0.41 and 1.81 of earlier drafts
#                     left out the endpoints and Neptune. Large also needs the
#                     human's spend gate (G-L) before it is approved.
#   --stop-on         comma list of first-failure (after any non-SUCCEEDED end, end
#                     leftover clusters of this run) and dlq-nonempty (stop the
#                     execution when a dead-letter queue holds a message). The
#                     wall-clock limit always applies.
#   --hourly-burn-usd the estimated cost per hour of the running pipeline, taken
#                     from the cost estimate. The script refuses when
#                     burn * max-minutes / 60 exceeds --spend-ceiling-usd.
#   --input-file      execution input JSON, no secrets; scanned before use. Contract:
#                     {"mode":"capability-check"} or {"mode":"pipeline","snapshot":ID,
#                     "snapshots":SPEC,"run_id":ID,"loader_command":[...]}; any other
#                     shape is refused (scripts/README.md, "Execution input contract").
#
# Run record: a real run creates <out-dir>/pipeline-<UTC stamp>-<size>.record.env
# once (mode 600, outside the repository, never overwritten) and appends to it. It
# holds the input file path, sha256 and size, the limits, the state machine and
# execution ARNs, start and end UTC, the final status and the s3:// URIs that appear
# in the input file. In pipeline mode it also holds INPUT_MODE, DATASET_SNAPSHOT (the
# input's "snapshot"), RUN_SNAPSHOTS (its "snapshots" spec, whole), RUN_ID and the
# OUTPUT_PREFIX key prefixes of the run; in capability-check mode DATASET_SNAPSHOT is
# n/a. The s3:// URIs are only those written in the input. See scripts/README.md,
# "run-bounded-pipeline.sh run record".
#
# AWS does not enforce a spend limit. The enforcement here is the time limit, the
# DLQ stop and the pre-start arithmetic. Actual spend is read by the human from
# billing afterwards; Cost Explorer calls are billed and are not made here.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

SIZE=""
MAX_MINUTES=""
SPEND_CEILING=""
HOURLY_BURN=""
STOP_ON=""
INPUT_FILE=""
POLL_SECONDS=30

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --size) need_arg "$@"; SIZE="$2"; shift 2 ;;
    --max-minutes) need_arg "$@"; MAX_MINUTES="$2"; shift 2 ;;
    --spend-ceiling-usd) need_arg "$@"; SPEND_CEILING="$2"; shift 2 ;;
    --hourly-burn-usd) need_arg "$@"; HOURLY_BURN="$2"; shift 2 ;;
    --stop-on) need_arg "$@"; STOP_ON="$2"; shift 2 ;;
    --input-file) need_arg "$@"; INPUT_FILE="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

step validate-params
init_common yes
require_cmd aws awk grep date python3
[[ -n "$SIZE" ]] || die "--size is required (small, medium or large)"
[[ -n "$MAX_MINUTES" ]] || die "--max-minutes is required"
[[ -n "$SPEND_CEILING" ]] || die "--spend-ceiling-usd is required"
[[ -n "$HOURLY_BURN" ]] || die "--hourly-burn-usd is required"
[[ -n "$STOP_ON" ]] || die "--stop-on is required"
[[ -n "$INPUT_FILE" ]] || die "--input-file is required"

case "$SIZE" in
  small) SIZE_CAP_MINUTES=60 ;;
  medium) SIZE_CAP_MINUTES=90 ;;
  large) SIZE_CAP_MINUTES=150 ;;
  *) die "size must be small, medium or large, got: $SIZE" ;;
esac
[[ "$MAX_MINUTES" =~ ^[0-9]+$ ]] || die "--max-minutes must be a whole number"
[[ "$MAX_MINUTES" -ge 1 && "$MAX_MINUTES" -le "$SIZE_CAP_MINUTES" ]] || die "--max-minutes must be 1 to $SIZE_CAP_MINUTES for size $SIZE (provisional cap)"
validate_number "$SPEND_CEILING"
validate_number "$HOURLY_BURN"
num_gt0 "$SPEND_CEILING" || die "spend ceiling must be greater than zero"
num_gt0 "$HOURLY_BURN" || die "hourly burn must be greater than zero"
num_le "$SPEND_CEILING" "$SC_COST" || die "spend ceiling $SPEND_CEILING exceeds the cost ceiling $SC_COST"
PROJECTED="$(awk -v b="$HOURLY_BURN" -v m="$MAX_MINUTES" 'BEGIN { printf "%.2f", b * m / 60 }')"
num_le "$PROJECTED" "$SPEND_CEILING" || die "projected spend $PROJECTED USD (burn $HOURLY_BURN x $MAX_MINUTES min) exceeds the spend ceiling $SPEND_CEILING"

WATCH_FAILURE=0
WATCH_DLQ=0
OLD_IFS="$IFS"
IFS=','
for c in $STOP_ON; do
  case "$c" in
    first-failure) WATCH_FAILURE=1 ;;
    dlq-nonempty) WATCH_DLQ=1 ;;
    *) IFS="$OLD_IFS"; die "unknown stop condition: $c" ;;
  esac
done
IFS="$OLD_IFS"

[[ "$INPUT_FILE" == /* && -f "$INPUT_FILE" ]] || die "--input-file must be an existing absolute path"
INPUT_DIGEST="$(sha256_file "$INPUT_FILE")"
INPUT_BYTES="$(wc -c < "$INPUT_FILE" | tr -d ' ')"
# The execution input contract (infra/terraform/modules/workflow/main.tf, design B):
#   {"mode":"capability-check"}  or
#   {"mode":"pipeline","snapshot":ID,"snapshots":SPEC,"run_id":ID,"loader_command":[...]}
# Unknown keys, a bad mode or a malformed field are refused before anything is started.
# The old free-form lists (hadoop_args, spark_args) are no longer accepted.
INPUT_FIELDS="$(python3 -I - "$INPUT_FILE" <<'PY'
import json, re, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        data = json.load(fh)
except Exception as error:
    sys.exit("input is not valid JSON: " + error.__class__.__name__)
if not isinstance(data, dict):
    sys.exit("input must be a JSON object")
mode = data.get("mode")
ident = re.compile(r"[A-Za-z0-9._-]{1,64}")
snaps = re.compile(r"[A-Za-z0-9._=,:-]{1,300}")
if mode == "capability-check":
    allowed = {"mode"}
elif mode == "pipeline":
    allowed = {"mode", "snapshot", "snapshots", "run_id", "loader_command"}
else:
    sys.exit("mode must be capability-check or pipeline")
extra = sorted(set(data) - allowed)
if extra:
    sys.exit("unknown input key(s) for mode " + mode + ": " + ", ".join(extra))
out = ["MODE=" + mode]
if mode == "pipeline":
    for key, rx in (("snapshot", ident), ("snapshots", snaps), ("run_id", ident)):
        value = data.get(key)
        if not isinstance(value, str) or not rx.fullmatch(value):
            sys.exit(key + " is missing or not valid")
        if key != "snapshots" and value in (".", ".."):
            sys.exit(key + " must not be . or ..")
        out.append(key.upper() + "=" + value)
    cmd = data.get("loader_command")
    if not isinstance(cmd, list) or not cmd or not all(isinstance(x, str) and x for x in cmd):
        sys.exit("loader_command must be a non-empty list of non-empty strings")
print("\n".join(out))
PY
)" || die "execution input refused (see the line above); contract in scripts/README.md, Execution input contract"
INPUT_MODE="$(printf '%s\n' "$INPUT_FIELDS" | awk -F= '$1 == "MODE" { print $2; exit }')"
INPUT_SNAPSHOT="$(printf '%s\n' "$INPUT_FIELDS" | awk -F= '$1 == "SNAPSHOT" { print $2; exit }')"
INPUT_SNAPSHOTS="$(printf '%s\n' "$INPUT_FIELDS" | awk -F= '$1 == "SNAPSHOTS" { sub(/^[^=]*=/, ""); print; exit }')"
INPUT_RUN_ID="$(printf '%s\n' "$INPUT_FIELDS" | awk -F= '$1 == "RUN_ID" { print $2; exit }')"
# Output locations are fixed by the on-node scripts: run prefixes named by run_id, or
# the capability report prefix (its time stamp is chosen on the node). Bucket names are
# not in the input, so only the key prefixes are recorded, plus any s3:// URI the
# input itself names (the loader command may).
OUTPUT_PREFIXES=""
if [[ "$INPUT_MODE" == "pipeline" ]]; then
  OUTPUT_PREFIXES="curated/runs/$INPUT_RUN_ID/ graph/runs/$INPUT_RUN_ID/ semantic-context/runs/$INPUT_RUN_ID/ reports/runs/$INPUT_RUN_ID/"
else
  OUTPUT_PREFIXES="reports/capability/"
fi
OUTPUT_URIS="$(grep -oE 's3://[A-Za-z0-9._/-]+' "$INPUT_FILE" | LC_ALL=C sort -u || true)"
EXTRA="size=$SIZE;max_minutes=$MAX_MINUTES;spend_ceiling=$SPEND_CEILING;hourly_burn=$HOURLY_BURN;stop_on=$STOP_ON;input=$INPUT_DIGEST"
maybe_print_digest pipeline "$EXTRA"
printf 'PROJECTED_SPEND_USD=%s CEILING_USD=%s\n' "$PROJECTED" "$SPEND_CEILING"
printf 'INPUT_FILE=%s\nINPUT_SHA256=%s\nINPUT_BYTES=%s\n' "$INPUT_FILE" "$INPUT_DIGEST" "$INPUT_BYTES"
printf 'LIMITS size=%s max_minutes=%s spend_ceiling_usd=%s hourly_burn_usd=%s stop_on=%s cost_ceiling_usd=%s\n' \
  "$SIZE" "$MAX_MINUTES" "$SPEND_CEILING" "$HOURLY_BURN" "$STOP_ON" "$SC_COST"
if [[ -n "$OUTPUT_URIS" ]]; then
  for u in $OUTPUT_URIS; do
    printf 'OUTPUT_LOCATION=%s\n' "$u"
  done
else
  printf 'OUTPUT_LOCATIONS=none-named (no s3:// URI appears in the input file)\n'
fi
printf 'INPUT_MODE=%s\n' "$INPUT_MODE"
for u in $OUTPUT_PREFIXES; do
  printf 'OUTPUT_PREFIX=%s (key prefix in the data bucket)\n' "$u"
done
if [[ "$INPUT_MODE" == "pipeline" ]]; then
  printf 'DATASET_SNAPSHOT=%s snapshots=%s run_id=%s\n' "$INPUT_SNAPSHOT" "$INPUT_SNAPSHOTS" "$INPUT_RUN_ID"
else
  printf 'DATASET_SNAPSHOT=n/a (capability-check reads no dataset)\n'
fi
step_ok

step input-scan
scan_for_secrets "$INPUT_FILE"
step_ok

if is_dry_run; then
  dry_banner pipeline "$EXTRA"
fi

step approval-record
verify_approval pipeline "$EXTRA" "n/a" "n/a"
step_ok

if is_dry_run; then
  dry_approval_unconsumed
  dry_step identity-check
  dry_would "aws sts get-caller-identity (skipped)"
  dry_step preflight
  dry_would "find state machine ${SC_NAME_PREFIX}-pipeline; refuse if an execution is RUNNING or an EMR cluster of that name is active; resolve DLQ URLs when dlq-nonempty is set; refuse an --out-dir inside the repository; create the run record $SC_OUT_DIR/pipeline-<UTC stamp>-$SIZE.record.env once (mode 600, never overwritten)"
  dry_step start-execution
  dry_would "consume the approval record; aws stepfunctions start-execution with input file $INPUT_FILE; append EXECUTION_ARN and START_UTC to the run record"
  dry_step monitor
  dry_would "poll every $POLL_SECONDS s up to $MAX_MINUTES minutes; stop on: $STOP_ON"
  dry_step teardown-check
  dry_would "up to 10 checks for active EMR clusters created after the start"
  dry_step report
  dry_would "print elapsed time and estimated spend (burn x time, ceiling $SPEND_CEILING USD)"
  dry_finish
fi

step identity-check
verify_identity
step_ok

step preflight
SM_NAME="${SC_NAME_PREFIX}-pipeline"
EMR_NAME="${SC_NAME_PREFIX}-pipeline"
SM_ARN="$(aws stepfunctions list-state-machines --query "stateMachines[?name=='${SM_NAME}'].stateMachineArn | [0]" --output text)"
[[ -n "$SM_ARN" && "$SM_ARN" != "None" ]] || die "state machine $SM_NAME not found (enable_pipeline and deploy first)"
RUNNING="$(aws stepfunctions list-executions --state-machine-arn "$SM_ARN" --status-filter RUNNING --query 'executions[].executionArn' --output text)"
[[ -z "$RUNNING" || "$RUNNING" == "None" ]] || die "an execution is already running: $RUNNING"
ACTIVE="$(aws emr list-clusters --active --query "Clusters[?Name=='${EMR_NAME}'].Id" --output text)"
[[ -z "$ACTIVE" || "$ACTIVE" == "None" ]] || die "an EMR cluster named $EMR_NAME is already active: $ACTIVE"
DLQ_URLS=""
if [[ "$WATCH_DLQ" -eq 1 ]]; then
  for q in run-requests embedding-work; do
    DLQ_URLS="$DLQ_URLS $(aws sqs get-queue-url --queue-name "${SC_NAME_PREFIX}-${q}-dlq" --query QueueUrl --output text)"
  done
fi
printf 'PREFLIGHT_OK state_machine=%s\n' "$SM_NAME"

# The run record: written outside the repository, created once (noclobber) and only
# appended to afterwards. It is made before the approval record is consumed, so a
# location that cannot be written refuses the run before anything is spent.
require_external_dir "$SC_OUT_DIR" "--out-dir"
make_out_dir
REC="$SC_OUT_DIR/pipeline-$(utc_stamp)-${SIZE}.record.env"
(umask 077; set -C; : > "$REC") || die "cannot create the run record (it is written once and never overwritten): $REC"

rec() {
  printf '%s=%s\n' "$1" "$2" >> "$REC"
}

rec RECORD_VERSION 1
rec ACTION pipeline
rec ENVIRONMENT "$SC_ENV"
rec ACCOUNT_ID "$SC_ACCOUNT"
rec REGION "$SC_REGION"
rec SCRIPT_DIGEST "$(script_digest)"
rec ARGS_DIGEST "$(args_digest pipeline "$EXTRA")"
rec APPROVED_BY "$(approval_value APPROVED_BY)"
rec APPROVED_AT "$(approval_value APPROVED_AT)"
rec INPUT_FILE "$INPUT_FILE"
rec INPUT_SHA256 "$INPUT_DIGEST"
rec INPUT_BYTES "$INPUT_BYTES"
rec SIZE "$SIZE"
rec MAX_MINUTES "$MAX_MINUTES"
rec SPEND_CEILING_USD "$SPEND_CEILING"
rec HOURLY_BURN_USD "$HOURLY_BURN"
rec COST_CEILING_USD "$SC_COST"
rec STOP_ON "$STOP_ON"
rec STATE_MACHINE_ARN "$SM_ARN"
if [[ -n "$OUTPUT_URIS" ]]; then
  for u in $OUTPUT_URIS; do
    rec OUTPUT_LOCATION "$u"
  done
else
  rec OUTPUT_LOCATIONS none-named
fi
rec INPUT_MODE "$INPUT_MODE"
for u in $OUTPUT_PREFIXES; do
  rec OUTPUT_PREFIX "$u"
done
if [[ "$INPUT_MODE" == "pipeline" ]]; then
  rec DATASET_SNAPSHOT "$INPUT_SNAPSHOT"
  rec RUN_SNAPSHOTS "$INPUT_SNAPSHOTS"
  rec RUN_ID "$INPUT_RUN_ID"
else
  rec DATASET_SNAPSHOT n/a
fi
printf 'PIPELINE_RECORD=%s\n' "$REC"
step_ok

stop_everything() {
  local cause="$1" ids
  printf 'STOPPING cause=%s\n' "$cause"
  if [[ -n "${EXEC_ARN:-}" ]]; then
    aws stepfunctions stop-execution --execution-arn "$EXEC_ARN" --cause "$cause" --error "BoundedPipelineStop" >/dev/null || printf 'WARN: stop-execution failed for %s\n' "$EXEC_ARN" >&2
  fi
  # stop-execution skips the state machine's own cleanup, so end any cluster this run created.
  ids="$(aws emr list-clusters --active --created-after "$START_ISO" --query "Clusters[?Name=='${EMR_NAME}'].Id" --output text 2>/dev/null || true)"
  if [[ -n "$ids" && "$ids" != "None" ]]; then
    printf 'TERMINATING_EMR clusters=%s\n' "$ids"
    # shellcheck disable=SC2086
    aws emr terminate-job-flows --job-flow-ids $ids || printf 'WARN: terminate-job-flows failed for %s: end it by hand\n' "$ids" >&2
  fi
}

on_signal() {
  printf 'SIGNAL received\n' >&2
  SC_STEP_OPEN=0
  stop_everything "interrupted by signal"
  rec END_UTC "$(utc_now)" || true
  rec FINAL_STATUS INTERRUPTED || true
  exit 130
}

step start-execution
consume_approval pipeline
START_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
START_EPOCH="$(date +%s)"
EXEC_ARN=""
trap on_signal INT TERM
EXEC_ARN="$(aws stepfunctions start-execution --state-machine-arn "$SM_ARN" \
  --name "run-$(date -u +%Y%m%dT%H%M%SZ)-$SIZE" --input "file://$INPUT_FILE" \
  --query executionArn --output text)" || die "start-execution failed"
printf 'EXECUTION_ARN=%s\nSTART=%s size=%s max_minutes=%s\n' "$EXEC_ARN" "$START_ISO" "$SIZE" "$MAX_MINUTES"
rec EXECUTION_ARN "$EXEC_ARN"
rec START_UTC "$START_ISO"
printf 'START_UTC=%s\n' "$START_ISO"
step_ok

step monitor
DEADLINE=$((START_EPOCH + MAX_MINUTES * 60))
FINAL=""
STOPPED_BY=""
while :; do
  STATUS="$(aws stepfunctions describe-execution --execution-arn "$EXEC_ARN" --query status --output text)"
  NOW="$(date +%s)"
  printf 'POLL status=%s elapsed_s=%s\n' "$STATUS" "$((NOW - START_EPOCH))"
  if [[ "$STATUS" != "RUNNING" ]]; then FINAL="$STATUS"; break; fi
  if [[ "$NOW" -ge "$DEADLINE" ]]; then
    STOPPED_BY="wall-clock limit of $MAX_MINUTES minutes"
    break
  fi
  if [[ "$WATCH_DLQ" -eq 1 ]]; then
    for url in $DLQ_URLS; do
      DEPTH="$(aws sqs get-queue-attributes --queue-url "$url" --attribute-names ApproximateNumberOfMessages --query 'Attributes.ApproximateNumberOfMessages' --output text)"
      if [[ "$DEPTH" != "0" ]]; then
        STOPPED_BY="dead-letter queue ${url##*/} holds $DEPTH message(s)"
        break 2
      fi
    done
  fi
  sleep "$POLL_SECONDS"
done
if [[ -n "$STOPPED_BY" ]]; then
  stop_everything "$STOPPED_BY"
  FINAL="STOPPED_BY_SCRIPT"
fi
printf 'FINAL_STATUS=%s\n' "$FINAL"
rec FINAL_STATUS "$FINAL"
step_ok

step teardown-check
LEFT=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  LEFT="$(aws emr list-clusters --active --created-after "$START_ISO" --query "Clusters[?Name=='${EMR_NAME}'].Id" --output text)"
  [[ -z "$LEFT" || "$LEFT" == "None" ]] && break
  if [[ "$FINAL" != "SUCCEEDED" && "$WATCH_FAILURE" -eq 1 ]]; then
    printf 'STOP_CONDITION first-failure: ending leftover cluster(s) %s\n' "$LEFT"
    # shellcheck disable=SC2086
    aws emr terminate-job-flows --job-flow-ids $LEFT || true
  fi
  sleep 30
done
if [[ -n "$LEFT" && "$LEFT" != "None" ]]; then
  die "EMR cluster still active after the run: $LEFT. Terminate it now; it bills hourly."
fi
printf 'NO_ACTIVE_CLUSTER name=%s\n' "$EMR_NAME"
step_ok

step report
END_EPOCH="$(date +%s)"
ELAPSED_MIN="$(awk -v s="$START_EPOCH" -v e="$END_EPOCH" 'BEGIN { printf "%.1f", (e - s) / 60 }')"
EST="$(awk -v b="$HOURLY_BURN" -v m="$ELAPSED_MIN" 'BEGIN { printf "%.2f", b * m / 60 }')"
printf 'RUN_REPORT execution=%s final=%s elapsed_min=%s estimated_spend_usd=%s ceiling_usd=%s\n' \
  "$EXEC_ARN" "$FINAL" "$ELAPSED_MIN" "$EST" "$SPEND_CEILING"
END_ISO="$(utc_now)"
printf 'END_UTC=%s\n' "$END_ISO"
rec END_UTC "$END_ISO"
rec ELAPSED_MINUTES "$ELAPSED_MIN"
rec ESTIMATED_SPEND_USD "$EST"
rec RECORD_COMPLETE yes
note "estimated spend is burn x time, not a bill. Record actual spend from AWS billing, then run cleanup.sh when finished."
step_ok

[[ "$FINAL" == "SUCCEEDED" ]] || die "pipeline did not succeed: $FINAL"
