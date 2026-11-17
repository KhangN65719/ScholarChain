# shellcheck shell=bash
# Shared read-only collector helpers for Experiments C, D, E and F. Source it, never run it.
# Same rules as experiments/b-burst/collect.sh:
#   - every AWS call is one of an exact allowlist of (service, verb) pairs in cc_aws (11 pairs, all
#     reads; any other pair is refused, never secretsmanager or ssm), Region us-east-2 only;
#   - calls write into <run dir>/collected.staging/, which becomes <run dir>/collected/ by ONE
#     rename after every REQUIRED call succeeded and the checksums are written, so collected/
#     never exists half-filled;
#   - a failed REQUIRED call stops the collector; the staging directory is renamed to
#     collect-failed.<UTC stamp>/ and kept (never deleted or reused);
#   - a failed OPTIONAL call is recorded as FAILED in CALL_STATUS.txt and the collection goes on;
#   - collected/ is written once; an existing one is refused.
# Bash 3.2 compatible (macOS). The caller sets AWS_PROFILE.
#
# Usage in an experiment's collect.sh:
#   source ".../load-tests/lib/collect-common.sh"
#   cc_parse_args "$@"            # --run-dir /abs/<run dir> [--pad-minutes N]
#   cc_init
#   cc_save NAME aws-service verb args...
#   cc_save_optional NAME aws-service verb args...
#   cc_metrics TEMPLATE_FILE KEY=VALUE ...
#   cc_finish "extra line" ...

set -euo pipefail

CC_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CC_REGION="us-east-2"
CC_RUN_DIR=""
CC_PAD_MINUTES=10
CC_STAGE=""
CC_OUT=""
CC_DONE=0
CC_START=""
CC_END=""
CC_FROM=""
CC_TO=""

cc_die() {
  printf 'COLLECT_REFUSED: %s\n' "$*" >&2
  exit 1
}

cc_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

cc_py() {
  python3 -I "$CC_LIB_DIR/metrics_derive.py" "$@"
}

cc_keep_failed() {
  local status=$?
  if [[ -n "$CC_STAGE" && "$CC_DONE" -eq 0 && -d "$CC_STAGE" ]]; then
    local failed
    failed="$CC_RUN_DIR/collect-failed.$(date -u +%Y%m%dT%H%M%SZ)"
    if mv "$CC_STAGE" "$failed" 2>/dev/null; then
      printf 'COLLECT_FAILED: partial files kept in %s (collected/ was not created; repeat the command after fixing the cause)\n' "$failed" >&2
    else
      printf 'COLLECT_FAILED: could not rename %s; move it aside by hand before repeating\n' "$CC_STAGE" >&2
    fi
  fi
  return "$status"
}

cc_parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --run-dir) [[ $# -ge 2 ]] || cc_die "--run-dir needs a value"; CC_RUN_DIR="$2"; shift 2 ;;
      --pad-minutes) [[ $# -ge 2 ]] || cc_die "--pad-minutes needs a value"; CC_PAD_MINUTES="$2"; shift 2 ;;
      *) cc_die "unknown argument: $1" ;;
    esac
  done
  [[ "$CC_RUN_DIR" == /* && -d "$CC_RUN_DIR" ]] || cc_die "--run-dir must be an existing absolute directory"
  [[ "$CC_PAD_MINUTES" =~ ^[0-9]{1,3}$ ]] || cc_die "--pad-minutes must be a whole number"
}

# cc_aws SERVICE VERB ARGS...
cc_aws() {
  case "$1 $2" in
    "cloudwatch get-metric-data" | "cloudwatch list-metrics" | "cloudwatch describe-alarms" | \
    "sqs get-queue-attributes" | "ecs describe-services" | "logs filter-log-events" | \
    "application-autoscaling describe-scaling-activities" | "application-autoscaling describe-scaling-policies" | \
    "application-autoscaling describe-scalable-targets" | \
    "dynamodb get-item" | "s3vectors get-vectors") ;;
    *) cc_die "not an allowed read-only AWS call: $1 $2" ;;
  esac
  ${CC_AWS_BIN:-aws} "$@" --region "$CC_REGION" --cli-connect-timeout 10 --cli-read-timeout 60 --output json
}

cc_status() {
  printf '%s %s exit=%s at=%s\n' "$1" "$2" "$3" "$(cc_utc_now)" >> "$CC_STAGE/CALL_STATUS.txt"
}

cc_init() {
  [[ -n "${AWS_PROFILE:-}" ]] || cc_die "AWS_PROFILE must be set"
  type -P python3 >/dev/null 2>&1 || cc_die "python3 is required"
  [[ -f "$CC_RUN_DIR/run-record.env" ]] || cc_die "$CC_RUN_DIR has no run-record.env: it is not an experiment run directory"
  [[ -f "$CC_RUN_DIR/raw/window.json" ]] || cc_die "$CC_RUN_DIR/raw/window.json is missing: the entry point did not write its window"
  CC_OUT="$CC_RUN_DIR/collected"
  [[ ! -e "$CC_OUT" ]] || cc_die "$CC_OUT already exists: collected files are written once and never overwritten"
  CC_STAGE="$CC_RUN_DIR/collected.staging"
  [[ ! -e "$CC_STAGE" ]] || cc_die "$CC_STAGE already exists: a previous attempt was interrupted; move it aside (do not delete it) and repeat"
  CC_START="$(cc_py window-field --window "$CC_RUN_DIR/raw/window.json" --field start_utc)"
  CC_END="$(cc_py window-field --window "$CC_RUN_DIR/raw/window.json" --field end_utc)"
  [[ -n "$CC_START" ]] || cc_die "window.json holds no start_utc"
  if [[ -z "$CC_END" ]]; then
    CC_END="$(cc_utc_now)"
    printf 'NOTE: window.json has no end_utc; using the collection time %s\n' "$CC_END"
  fi
  CC_FROM="$(cc_py shift-time --time "$CC_START" --minutes "-$CC_PAD_MINUTES")"
  CC_TO="$(cc_py shift-time --time "$CC_END" --minutes "$CC_PAD_MINUTES")"
  mkdir "$CC_STAGE" || cc_die "cannot create $CC_STAGE"
  trap cc_keep_failed EXIT
  trap 'exit 143' TERM INT
}

# cc_save NAME SERVICE VERB ARGS...: REQUIRED call, output in a new file (no clobber).
cc_save() {
  local name="$1" rc=0
  shift
  printf 'CALL %s: %s\n' "$name" "$*"
  (set -C; cc_aws "$@" > "$CC_STAGE/$name" 2> "$CC_STAGE/$name.error.txt") || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    cc_status "$name" FAILED "$rc"
    cc_die "required call $name failed (exit $rc); error text in $name.error.txt of the kept stage"
  fi
  rm -f "$CC_STAGE/$name.error.txt"
  cc_status "$name" OK 0
}

cc_save_optional() {
  local name="$1" rc=0
  shift
  printf 'CALL %s (optional): %s\n' "$name" "$*"
  (set -C; cc_aws "$@" > "$CC_STAGE/$name" 2> "$CC_STAGE/$name.error.txt") || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    rm -f "$CC_STAGE/$name"
    cc_status "$name" FAILED "$rc"
    printf 'NOTE: optional call %s failed (exit %s); recorded in CALL_STATUS.txt, error in %s.error.txt\n' "$name" "$rc" "$name"
    return 0
  fi
  rm -f "$CC_STAGE/$name.error.txt"
  cc_status "$name" OK 0
}

# cc_metrics TEMPLATE KEY=VALUE...: render the queries and call get-metric-data (REQUIRED).
cc_metrics() {
  local template="$1" args=() kv
  shift
  for kv in "$@"; do
    args+=(--set "$kv")
  done
  (set -C; cc_py render-queries --template "$template" ${args[@]+"${args[@]}"} > "$CC_STAGE/metric-queries.json")
  cc_save cloudwatch-metric-data.json cloudwatch get-metric-data \
    --metric-data-queries "file://$CC_STAGE/metric-queries.json" \
    --start-time "$CC_FROM" --end-time "$CC_TO" --scan-by TimestampAscending
}

# cc_finish EXTRA_LINE...: COLLECTED.txt, SHA256SUMS, read-only, one rename.
cc_finish() {
  (
    set -C
    {
      printf 'RUN_DIR=%s\n' "$CC_RUN_DIR"
      printf 'REGION=%s\n' "$CC_REGION"
      printf 'RUN_WINDOW_START_UTC=%s\n' "$CC_START"
      printf 'RUN_WINDOW_END_UTC=%s\n' "$CC_END"
      printf 'METRIC_WINDOW_FROM_UTC=%s\n' "$CC_FROM"
      printf 'METRIC_WINDOW_TO_UTC=%s\n' "$CC_TO"
      printf 'COLLECTED_AT_UTC=%s\n' "$(cc_utc_now)"
      printf 'CALL_STATUS=see CALL_STATUS.txt (a FAILED optional call leaves its file absent)\n'
      local line
      for line in "$@"; do
        printf '%s\n' "$line"
      done
    } > "$CC_STAGE/COLLECTED.txt"
  )
  (
    cd "$CC_STAGE"
    files=(*)
    if type -P sha256sum >/dev/null 2>&1; then
      sha256sum -- "${files[@]}" > "$CC_STAGE/SHA256SUMS.new"
    else
      shasum -a 256 -- "${files[@]}" > "$CC_STAGE/SHA256SUMS.new"
    fi
    mv "$CC_STAGE/SHA256SUMS.new" "$CC_STAGE/SHA256SUMS"
  )
  chmod a-w "$CC_STAGE"/*
  mv "$CC_STAGE" "$CC_OUT"
  CC_DONE=1
  chmod a-w "$CC_OUT"
  printf 'COLLECTED_DIR=%s\n' "$CC_OUT"
  printf 'COLLECT_COMPLETE window=%s..%s\n' "$CC_FROM" "$CC_TO"
}
