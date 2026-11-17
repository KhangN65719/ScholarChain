# shellcheck shell=bash
# Helpers shared by the Phase 15 experiment entry points. Source it, never run it.
# An entry point is started once by scripts/run-experiment.sh, which sets the
# SCHOLARCHAIN_* environment named below. Nothing here tunes a run, retries a run or
# calls a mutating AWS verb. The only workload it starts is one pipeline execution
# through scripts/run-bounded-pipeline.sh, which has its own human approval record.
#
# Bash 3.2 compatible (macOS).

set -euo pipefail

EXP_LIB_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/exp-lib.sh"
EXP_REPO_ROOT="$(cd "$(dirname "$EXP_LIB_FILE")/../.." && pwd)"
EXP_RAW=""
EXP_EVENTS=""

exp_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

exp_die() {
  printf 'EXPERIMENT_REFUSED: %s\n' "$*" >&2
  exit 1
}

# exp_sha256 FILE: print the sha256 of a file (sha256sum on Linux, shasum on macOS).
exp_sha256() {
  if type -P sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

# exp_event NAME [DETAIL]: append one JSON line with a UTC ISO-8601 time to
# raw/events.jsonl. The detail may not hold a double quote or a backslash, so no
# escaping is needed.
exp_event() {
  local name="$1" detail="${2:-}"
  case "$detail" in
    *\"* | *\\*) exp_die "event detail must not hold a quote or a backslash" ;;
  esac
  printf '{"ts_utc":"%s","event":"%s","detail":"%s"}\n' "$(exp_utc_now)" "$name" "$detail" >> "$EXP_EVENTS"
}

# exp_init EXPECTED_ID: validate the environment that run-experiment.sh sets and open
# the event log. The event log is created once and never overwritten.
exp_init() {
  local expected="$1" v
  for v in SCHOLARCHAIN_EXPERIMENT_ID SCHOLARCHAIN_RUN_ID SCHOLARCHAIN_RUN_DIR \
    SCHOLARCHAIN_MAX_MINUTES SCHOLARCHAIN_MAX_CONCURRENCY SCHOLARCHAIN_MAX_TASKS \
    SCHOLARCHAIN_REGION SCHOLARCHAIN_ACCOUNT_ID SCHOLARCHAIN_DATASET_SNAPSHOT; do
    [[ -n "${!v:-}" ]] || exp_die "$v is not set: this entry point is started by scripts/run-experiment.sh only"
  done
  [[ "$SCHOLARCHAIN_EXPERIMENT_ID" == "$expected" ]] || exp_die "this entry point is for experiment $expected, got $SCHOLARCHAIN_EXPERIMENT_ID"
  [[ "$SCHOLARCHAIN_REGION" == "us-east-2" ]] || exp_die "Region must be us-east-2"
  [[ "$SCHOLARCHAIN_RUN_DIR" == /* && -d "$SCHOLARCHAIN_RUN_DIR" ]] || exp_die "SCHOLARCHAIN_RUN_DIR must be an existing absolute directory"
  EXP_RAW="$SCHOLARCHAIN_RUN_DIR"
  EXP_EVENTS="$EXP_RAW/events.jsonl"
  (set -C; : > "$EXP_EVENTS") || exp_die "cannot create $EXP_EVENTS (a run writes it once)"
  exp_event ENTRY_START "experiment=$expected run_id=$SCHOLARCHAIN_RUN_ID"
}

# exp_run_pipeline SIZE MAX_MINUTES SPEND_CEILING_USD HOURLY_BURN_USD
# Start ONE pipeline execution through scripts/run-bounded-pipeline.sh and wait for it.
# The numbers are fixed by the calling entry point (committed code), never read from
# the environment, so the pipeline approval record binds exactly what is committed.
# The human supplies two paths in the environment of the run-experiment.sh command:
#   SCHOLARCHAIN_PIPELINE_INPUT_FILE        absolute path of the execution input JSON
#   SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD   absolute path of the pipeline approval record
# Returns the pipeline script's exit status. There is no retry.
exp_run_pipeline() {
  local size="$1" max_min="$2" spend="$3" burn="$4" rc=0 input record_dir
  input="${SCHOLARCHAIN_PIPELINE_INPUT_FILE:-}"
  [[ "$input" == /* && -f "$input" ]] || exp_die "SCHOLARCHAIN_PIPELINE_INPUT_FILE must be an existing absolute path"
  [[ "${SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD:-}" == /* && -f "${SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD:-}" ]] || exp_die "SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD must be an existing absolute path"
  [[ "$max_min" -lt "$SCHOLARCHAIN_MAX_MINUTES" ]] || exp_die "the pipeline limit $max_min min must be below the experiment limit $SCHOLARCHAIN_MAX_MINUTES min"
  cp "$input" "$EXP_RAW/pipeline-input.json"
  printf '%s  pipeline-input.json\n' "$(exp_sha256 "$EXP_RAW/pipeline-input.json")" > "$EXP_RAW/pipeline-input.sha256"
  record_dir="$EXP_RAW/pipeline"
  mkdir "$record_dir" || exp_die "cannot create $record_dir"
  exp_event PIPELINE_START "size=$size max_minutes=$max_min spend_ceiling_usd=$spend hourly_burn_usd=$burn"
  bash "$EXP_REPO_ROOT/scripts/run-bounded-pipeline.sh" \
    --environment course \
    --account-id "$SCHOLARCHAIN_ACCOUNT_ID" \
    --region "$SCHOLARCHAIN_REGION" \
    --cost-ceiling-usd 20 \
    --approval-record "$SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD" \
    --size "$size" \
    --max-minutes "$max_min" \
    --spend-ceiling-usd "$spend" \
    --hourly-burn-usd "$burn" \
    --stop-on first-failure,dlq-nonempty \
    --input-file "$input" \
    --out-dir "$record_dir" > "$EXP_RAW/pipeline-run.log" 2>&1 || rc=$?
  exp_event PIPELINE_END "exit=$rc"
  exp_write_window "$record_dir" "$rc"
  return "$rc"
}

# exp_record_value FILE KEY: first value of KEY=VALUE in a run record.
exp_record_value() {
  awk -F= -v k="$2" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$1"
}

# exp_write_window RECORD_DIR EXIT: write raw/window.json from the pipeline run record.
# It holds the values the post-run collector needs. It is derived from the record, so
# the record stays the source of truth.
exp_write_window() {
  local record_dir="$1" rc="$2" rec="" f n=0
  for f in "$record_dir"/pipeline-*.record.env; do
    if [[ -f "$f" ]]; then
      rec="$f"
      n=$((n + 1))
    fi
  done
  if [[ "$n" -ne 1 ]]; then
    exp_event WINDOW_UNAVAILABLE "pipeline record files found: $n"
    return 0
  fi
  printf '{\n  "pipeline_record": "%s",\n  "execution_arn": "%s",\n  "start_utc": "%s",\n  "end_utc": "%s",\n  "final_status": "%s",\n  "pipeline_exit": %s\n}\n' \
    "$rec" \
    "$(exp_record_value "$rec" EXECUTION_ARN)" \
    "$(exp_record_value "$rec" START_UTC)" \
    "$(exp_record_value "$rec" END_UTC)" \
    "$(exp_record_value "$rec" FINAL_STATUS)" \
    "$rc" > "$EXP_RAW/window.json"
}
