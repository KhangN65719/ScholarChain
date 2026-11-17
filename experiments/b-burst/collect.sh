#!/usr/bin/env bash
# Experiment B capacity collector. READ-ONLY: every AWS call below is a describe-, list-
# or get- call, enforced by aws_read(). The human runs it after the experiment run has
# ended and the cluster is gone (an EMR cluster stays describable for about two months;
# CloudWatch keeps 5-minute data for 63 days). It writes immutable raw files, one per
# call, into <run dir>/collected/ and refuses an existing collected/ directory.
#
# Usage:
#   bash experiments/b-burst/collect.sh --run-dir /abs/<OUTPUT_ROOT>/<run id> [--pad-minutes 10]
#
# AWS_PROFILE must be set by the caller (the paste-ready command in
# experiments/b-burst/README.md does it). The Region is fixed to us-east-2.
#
# Partial failure (review 1, m5). Calls write into <run dir>/collected.staging/, which becomes
# <run dir>/collected/ by ONE rename only after every REQUIRED call succeeded and the checksums
# are written. So collected/ never exists half-filled and a failed attempt can be repeated:
#   - a REQUIRED call that fails stops the collector; the staging directory is renamed to
#     <run dir>/collect-failed.<UTC stamp>/ and kept (never deleted, never reused), and the
#     next attempt starts a new staging directory;
#   - an OPTIONAL call (get-managed-scaling-policy, list-steps, list-metrics) that fails is
#     recorded as FAILED in CALL_STATUS.txt with its error in <name>.error.txt, and the
#     collection goes on. get-managed-scaling-policy on a terminated cluster is UNVERIFIED:
#     derive-capacity.py reports policy_available no when the file is absent.
#
# Files written (raw, as returned by the CLI, one call each):
#   sfn-execution.json              stepfunctions describe-execution            (required)
#   sfn-history.json                stepfunctions get-execution-history, with execution data (required)
#   emr-describe-cluster.json       emr describe-cluster                        (required)
#   emr-list-instance-groups.json   emr list-instance-groups                    (required)
#   emr-list-instances.json         emr list-instances: creation, ready and end time of every
#                                   instance, the provisioned and released timeline (required)
#   emr-managed-scaling-policy.json emr get-managed-scaling-policy: the bounds in force (optional)
#   emr-list-steps.json             emr list-steps                              (optional)
#   cloudwatch-list-metrics.json    cloudwatch list-metrics for the cluster: which metric names
#                                   really exist, the proof for the names below (optional)
#   metric-queries.json             the get-metric-data query file (derived from the history)
#   cloudwatch-metric-data.json     cloudwatch get-metric-data, period 300 s    (required)
#   CALL_STATUS.txt                 one line per call: name, OK or FAILED, exit code, UTC time
#   COLLECTED.txt                   run dir, window, cluster id, collection time (UTC)
#   SHA256SUMS
# REDACTION before any results commit: sfn-execution.json, sfn-history.json and
# raw/window.json hold ARNs with the AWS account ID, and a *.error.txt may too. The raw files in
# the run directory stay as they are; a commit copy is made by replacing the 12-digit account
# ID with 000000000000 in the COPY (see experiments/b-burst/README.md, "Before a results
# commit"). Derived plots and tables are made from the raw files only by
# experiments/b-burst/derive-capacity.py and carry no account ID.
set -euo pipefail

SC_COLLECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGION="us-east-2"
RUN_DIR=""
PAD_MINUTES=10
STAGE=""
STAGE_DONE=0

die() {
  printf 'COLLECT_REFUSED: %s\n' "$*" >&2
  exit 1
}

utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

# Keep a failed attempt's files under a new name (never delete evidence, never reuse the name).
keep_failed_stage() {
  local status=$?
  if [[ -n "$STAGE" && "$STAGE_DONE" -eq 0 && -d "$STAGE" ]]; then
    local failed
    failed="$RUN_DIR/collect-failed.$(date -u +%Y%m%dT%H%M%SZ)"
    if mv "$STAGE" "$failed" 2>/dev/null; then
      printf 'COLLECT_FAILED: partial files kept in %s (collected/ was not created; repeat the command after fixing the cause)\n' "$failed" >&2
    else
      printf 'COLLECT_FAILED: could not rename %s; move it aside by hand before repeating\n' "$STAGE" >&2
    fi
  fi
  return "$status"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-dir) [[ $# -ge 2 ]] || die "--run-dir needs a value"; RUN_DIR="$2"; shift 2 ;;
    --pad-minutes) [[ $# -ge 2 ]] || die "--pad-minutes needs a value"; PAD_MINUTES="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$RUN_DIR" == /* && -d "$RUN_DIR" ]] || die "--run-dir must be an existing absolute directory"
[[ "$PAD_MINUTES" =~ ^[0-9]{1,3}$ ]] || die "--pad-minutes must be a whole number"
[[ -n "${AWS_PROFILE:-}" ]] || die "AWS_PROFILE must be set"
type -P aws >/dev/null 2>&1 || die "aws is required"
type -P python3 >/dev/null 2>&1 || die "python3 is required"
[[ -f "$RUN_DIR/run-record.env" ]] || die "$RUN_DIR has no run-record.env: it is not an experiment run directory"
[[ -f "$RUN_DIR/raw/window.json" ]] || die "$RUN_DIR/raw/window.json is missing: the entry point did not reach the pipeline record"

OUT="$RUN_DIR/collected"
[[ ! -e "$OUT" ]] || die "$OUT already exists: collected files are written once and never overwritten"
STAGE="$RUN_DIR/collected.staging"
[[ ! -e "$STAGE" ]] || die "$STAGE already exists: a previous attempt was interrupted; move it aside (do not delete it) and repeat"
mkdir "$STAGE" || die "cannot create $STAGE"
trap keep_failed_stage EXIT
trap 'exit 143' TERM INT

# aws_read SERVICE VERB ARGS...: refuses any verb that is not describe-, list- or get-.
aws_read() {
  case "$2" in
    describe-* | list-* | get-*) ;;
    *) die "not a read-only AWS verb: $1 $2" ;;
  esac
  aws "$@" --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 60 --output json
}

# status NAME RESULT EXIT: append one line to CALL_STATUS.txt.
status() {
  printf '%s %s exit=%s at=%s\n' "$1" "$2" "$3" "$(utc_now)" >> "$STAGE/CALL_STATUS.txt"
}

# save NAME CMD...: REQUIRED call. Run one read call, keep its output in a new file (no
# clobber). A failure stops the collector (the stage is kept as collect-failed.<stamp>).
save() {
  local name="$1" rc=0
  shift
  printf 'CALL %s: %s\n' "$name" "$*"
  (set -C; aws_read "$@" > "$STAGE/$name" 2> "$STAGE/$name.error.txt") || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    status "$name" FAILED "$rc"
    die "required call $name failed (exit $rc); error text in $name.error.txt of the kept stage"
  fi
  rm -f "$STAGE/$name.error.txt"
  status "$name" OK 0
}

# save_optional NAME CMD...: like save, but a failure is recorded and the collection goes on.
save_optional() {
  local name="$1" rc=0
  shift
  printf 'CALL %s (optional): %s\n' "$name" "$*"
  (set -C; aws_read "$@" > "$STAGE/$name" 2> "$STAGE/$name.error.txt") || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    rm -f "$STAGE/$name"
    status "$name" FAILED "$rc"
    printf 'NOTE: optional call %s failed (exit %s); recorded in CALL_STATUS.txt, error in %s.error.txt\n' "$name" "$rc" "$name"
    return 0
  fi
  rm -f "$STAGE/$name.error.txt"
  status "$name" OK 0
}

EXEC_ARN="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" window-field --window "$RUN_DIR/raw/window.json" --field execution_arn)"
START_UTC="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" window-field --window "$RUN_DIR/raw/window.json" --field start_utc)"
END_UTC="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" window-field --window "$RUN_DIR/raw/window.json" --field end_utc)"
[[ -n "$EXEC_ARN" && -n "$START_UTC" ]] || die "window.json holds no execution ARN or start time (the pipeline did not start)"
if [[ -z "$END_UTC" ]]; then
  END_UTC="$(utc_now)"
  printf 'NOTE: window.json has no end time; using the collection time %s as the end of the window\n' "$END_UTC"
fi

save sfn-execution.json stepfunctions describe-execution --execution-arn "$EXEC_ARN"
save sfn-history.json stepfunctions get-execution-history --execution-arn "$EXEC_ARN" --include-execution-data

CLUSTER_ID="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" cluster-id --history "$STAGE/sfn-history.json")"
[[ -n "$CLUSTER_ID" ]] || die "no EMR cluster id in the execution history (the run may have failed before CreateCluster succeeded)"
printf 'CLUSTER_ID=%s\n' "$CLUSTER_ID"

save emr-describe-cluster.json emr describe-cluster --cluster-id "$CLUSTER_ID"
save emr-list-instance-groups.json emr list-instance-groups --cluster-id "$CLUSTER_ID"
save emr-list-instances.json emr list-instances --cluster-id "$CLUSTER_ID" --instance-states AWAITING_FULFILLMENT PROVISIONING BOOTSTRAPPING RUNNING TERMINATED
save_optional emr-managed-scaling-policy.json emr get-managed-scaling-policy --cluster-id "$CLUSTER_ID"
save_optional emr-list-steps.json emr list-steps --cluster-id "$CLUSTER_ID"
save_optional cloudwatch-list-metrics.json cloudwatch list-metrics --namespace AWS/ElasticMapReduce --dimensions "Name=JobFlowId,Value=$CLUSTER_ID"

WINDOW_FROM="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" shift-time --time "$START_UTC" --minutes "-$PAD_MINUTES")"
WINDOW_TO="$(python3 -I "$SC_COLLECT_DIR/derive-capacity.py" shift-time --time "$END_UTC" --minutes "$PAD_MINUTES")"
(set -C; python3 -I "$SC_COLLECT_DIR/derive-capacity.py" queries --cluster-id "$CLUSTER_ID" > "$STAGE/metric-queries.json")
save cloudwatch-metric-data.json cloudwatch get-metric-data \
  --metric-data-queries "file://$STAGE/metric-queries.json" \
  --start-time "$WINDOW_FROM" --end-time "$WINDOW_TO" --scan-by TimestampAscending

(
  set -C
  {
    printf 'RUN_DIR=%s\n' "$RUN_DIR"
    printf 'REGION=%s\n' "$REGION"
    printf 'EXECUTION_ARN=%s\n' "$EXEC_ARN"
    printf 'CLUSTER_ID=%s\n' "$CLUSTER_ID"
    printf 'RUN_WINDOW_START_UTC=%s\n' "$START_UTC"
    printf 'RUN_WINDOW_END_UTC=%s\n' "$END_UTC"
    printf 'METRIC_WINDOW_FROM_UTC=%s\n' "$WINDOW_FROM"
    printf 'METRIC_WINDOW_TO_UTC=%s\n' "$WINDOW_TO"
    printf 'METRIC_PERIOD_SECONDS=300\n'
    printf 'COLLECTED_AT_UTC=%s\n' "$(utc_now)"
    printf 'AWS_CALLS=describe-execution get-execution-history describe-cluster list-instance-groups list-instances get-managed-scaling-policy list-steps list-metrics get-metric-data\n'
    printf 'CALL_STATUS=see CALL_STATUS.txt (a FAILED optional call leaves its file absent)\n'
  } > "$STAGE/COLLECTED.txt"
)

(
  cd "$STAGE"
  files=(*)
  if type -P sha256sum >/dev/null 2>&1; then
    sha256sum -- "${files[@]}" > "$STAGE/SHA256SUMS.new"
  else
    shasum -a 256 -- "${files[@]}" > "$STAGE/SHA256SUMS.new"
  fi
  mv "$STAGE/SHA256SUMS.new" "$STAGE/SHA256SUMS"
)
chmod a-w "$STAGE"/*
mv "$STAGE" "$OUT"
STAGE_DONE=1
chmod a-w "$OUT"
printf 'COLLECTED_DIR=%s\n' "$OUT"
printf 'COLLECT_COMPLETE cluster=%s window=%s..%s\n' "$CLUSTER_ID" "$WINDOW_FROM" "$WINDOW_TO"
