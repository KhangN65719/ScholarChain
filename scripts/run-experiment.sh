#!/usr/bin/env bash
# run-experiment.sh: run one approved experiment manifest, inside its stated limits,
# and keep an immutable record of the run. Approval-gated: the record names the
# action "experiment" and binds the manifest by its sha256. A human-controlled
# execution path runs this; agent shell calls to it stay blocked until a launcher is
# ratified. It runs no production algorithm change and tunes nothing: the entry
# point named in the manifest is a committed script or sbt task, started once.
#
# Usage:
#   bash scripts/run-experiment.sh --manifest /abs/experiment.manifest \
#     --environment course --account-id 123456789012 --region us-east-2 \
#     --cost-ceiling-usd 20 --approval-record /abs/approval.env [--dry-run]
#   Add --print-digest (no record needed) to print the digests the record must hold.
#   --region is required here and must be us-east-2 (decision D1). --out-dir, when given, must
#   equal the manifest's OUTPUT_ROOT; the manifest is the one place that names it.
#
# The manifest is a flat KEY=VALUE file, parsed and never sourced. The contract,
# every key, the tier caps and a minimal valid example are in scripts/README.md,
# "Experiment manifest contract". The caps below are PROVISIONAL, proposed by the
# infra-engineer and not confirmed by the human: minutes 60, 90, 150 (the pipeline
# caps), concurrency 4, 16, 32 and tasks 1000, 5000, 20000 for tiers S, M and L.
#
# Refuses: a missing, unknown or duplicated key, a limit missing, out of range or
# above the tier cap, a spend ceiling above --cost-ceiling-usd or a projected spend
# (burn x minutes) above the spend ceiling, a Region other than us-east-2, an
# unknown experiment id, a tier with no overlay file, an output path inside the
# repository, a dirty tree, an entry point that is not a tracked file, a stop
# condition list without wall-clock, an approval record that does not bind this
# manifest, and an existing run directory (a run is written once).
#
# Output, in <OUTPUT_ROOT>/<run id>/: run-record.env (commit SHA, limits, start and
# end UTC, Region, account, run id, metric locations), raw/entrypoint.log,
# metrics/*.list.json, SHA256SUMS. raw/ and metrics/ are made read-only at the end.
# This script lists metric identities with one read call per namespace and name. It
# does not pull metric series; the record holds the namespaces, names, Region and
# the UTC window so the series can be pulled afterwards.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

# --region is required for this script, so the library default is cleared here.
SC_REGION=""

MANIFEST_FILE=""
OUT_DIR_ARG=""
MF_SHA=""
MF_KEYS="MANIFEST_VERSION EXPERIMENT_ID DESCRIPTION ENVIRONMENT REGION ACCOUNT_ID DATASET_SNAPSHOT SIZE_TIER MAX_MINUTES MAX_CONCURRENCY MAX_TASKS SPEND_CEILING_USD HOURLY_BURN_USD STOP_CONDITIONS METRIC_NAMES METRIC_NAMESPACES OUTPUT_ROOT ENTRY_POINT"
CAP_MINUTES=0
CAP_CONC=0
CAP_TASKS=0
REC=""

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --manifest) need_arg "$@"; MANIFEST_FILE="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

### Manifest parsing ---------------------------------------------------------

mf_value() {
  awk -v k="$1" '
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1) }
    key == k { print substr($0, i + 1); exit }
  ' "$MANIFEST_FILE"
}

mf_count() {
  awk -v k="$1" '
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1) }
    key == k { n++ }
    END { print n + 0 }
  ' "$MANIFEST_FILE"
}

tier_caps() {
  case "$1" in
    S) CAP_MINUTES=60; CAP_CONC=4; CAP_TASKS=1000 ;;
    M) CAP_MINUTES=90; CAP_CONC=16; CAP_TASKS=5000 ;;
    L) CAP_MINUTES=150; CAP_CONC=32; CAP_TASKS=20000 ;;
    *) die "SIZE_TIER must be S, M or L, got: $1" ;;
  esac
}

# require_int NAME VALUE CAP: a whole number from 1 to CAP.
require_int() {
  local name="$1" v="$2" cap="$3"
  [[ "$v" =~ ^[1-9][0-9]{0,8}$ ]] || die "$name must be a whole number from 1 to $cap (the cap for tier $TIER), got: $v"
  [[ "$v" -le "$cap" ]] || die "$name $v is above the cap $cap for tier $TIER"
}

# check_tokens NAME LIST REGEX: a non-empty comma list, no empty element, every
# element matching REGEX, at most 20 elements.
check_tokens() {
  local name="$1" list="$2" re="$3" words w n=0
  case ",$list," in
    *,,*) die "$name has an empty element: $list" ;;
  esac
  words="$(csv_words "$list")"
  for w in $words; do
    [[ "$w" =~ $re ]] || die "$name has an element that is not allowed: $w"
    n=$((n + 1))
  done
  [[ "$n" -ge 1 && "$n" -le 20 ]] || die "$name must hold 1 to 20 elements, got $n"
}

validate_manifest() {
  local key v re
  [[ "$MANIFEST_FILE" == /* ]] || die "--manifest must be an absolute path"
  [[ -f "$MANIFEST_FILE" && ! -L "$MANIFEST_FILE" ]] || die "manifest is missing, not a regular file, or a symlink: $MANIFEST_FILE"
  [[ -r "$MANIFEST_FILE" ]] || die "manifest is not readable"
  if awk '!/^[[:space:]]*(#|$)/ && !/^[A-Z_]+=[^[:space:]]*$/ { bad = 1 } END { exit !bad }' "$MANIFEST_FILE"; then
    die "manifest has a malformed line (expected KEY=VALUE with no spaces)"
  fi
  if awk -v list="$MF_KEYS" '
    BEGIN { n = split(list, a, " "); for (j = 1; j <= n; j++) ok[a[j]] = 1 }
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1); if (!(key in ok)) bad = 1 }
    END { exit !bad }
  ' "$MANIFEST_FILE"; then
    die "manifest has an unknown key; allowed: $MF_KEYS"
  fi
  for key in $MF_KEYS; do
    [[ "$(mf_count "$key")" == "1" ]] || die "manifest must hold $key exactly once"
    [[ -n "$(mf_value "$key")" ]] || die "manifest has an empty $key"
  done
  scan_for_secrets "$MANIFEST_FILE"

  [[ "$(mf_value MANIFEST_VERSION)" == "1" ]] || die "unsupported MANIFEST_VERSION"
  EXP_ID="$(mf_value EXPERIMENT_ID)"
  case "$EXP_ID" in
    A | B | C | D | E | F) ;;
    *) die "unknown EXPERIMENT_ID '$EXP_ID': the course experiments are A to F" ;;
  esac
  DESCRIPTION="$(mf_value DESCRIPTION)"
  re='^[A-Za-z0-9._,:/-]{1,200}$'
  [[ "$DESCRIPTION" =~ $re ]] || die "DESCRIPTION must be 1 to 200 characters of A-Za-z0-9._,:/- (no spaces)"
  [[ "$(mf_value ENVIRONMENT)" == "$SC_ENV" ]] || die "manifest ENVIRONMENT differs from --environment"
  [[ "$(mf_value REGION)" == "$SC_APPROVED_REGION" ]] || die "manifest REGION must be $SC_APPROVED_REGION, got: $(mf_value REGION)"
  [[ "$(mf_value REGION)" == "$SC_REGION" ]] || die "manifest REGION differs from --region"
  [[ "$(mf_value ACCOUNT_ID)" == "$SC_ACCOUNT" ]] || die "manifest ACCOUNT_ID differs from --account-id"
  DATASET="$(mf_value DATASET_SNAPSHOT)"
  re='^[A-Za-z0-9._-]{1,64}$'
  [[ "$DATASET" =~ $re ]] || die "DATASET_SNAPSHOT must be 1 to 64 characters of A-Za-z0-9._-"

  TIER="$(mf_value SIZE_TIER)"
  tier_caps "$TIER"
  TIER_LOWER="$(printf '%s' "$TIER" | tr 'SML' 'sml')"
  [[ -f "$SC_ENV_DIR/experiment-size-$TIER_LOWER.tfvars" ]] || die "no size overlay for tier $TIER at $SC_ENV_DIR/experiment-size-$TIER_LOWER.tfvars"
  MAX_MINUTES="$(mf_value MAX_MINUTES)"
  MAX_CONC="$(mf_value MAX_CONCURRENCY)"
  MAX_TASKS="$(mf_value MAX_TASKS)"
  require_int MAX_MINUTES "$MAX_MINUTES" "$CAP_MINUTES"
  require_int MAX_CONCURRENCY "$MAX_CONC" "$CAP_CONC"
  require_int MAX_TASKS "$MAX_TASKS" "$CAP_TASKS"
  SPEND="$(mf_value SPEND_CEILING_USD)"
  BURN="$(mf_value HOURLY_BURN_USD)"
  validate_number "$SPEND"
  validate_number "$BURN"
  num_gt0 "$SPEND" || die "SPEND_CEILING_USD must be greater than zero"
  num_gt0 "$BURN" || die "HOURLY_BURN_USD must be greater than zero"
  num_le "$SPEND" "$SC_COST" || die "SPEND_CEILING_USD $SPEND exceeds --cost-ceiling-usd $SC_COST"
  PROJECTED="$(awk -v b="$BURN" -v m="$MAX_MINUTES" 'BEGIN { printf "%.2f", b * m / 60 }')"
  num_le "$PROJECTED" "$SPEND" || die "projected spend $PROJECTED USD (burn $BURN x $MAX_MINUTES min) exceeds SPEND_CEILING_USD $SPEND"

  STOPS="$(mf_value STOP_CONDITIONS)"
  check_tokens STOP_CONDITIONS "$STOPS" '^(wall-clock|first-failure|spend-estimate)$'
  case ",$STOPS," in
    *,wall-clock,*) ;;
    *) die "STOP_CONDITIONS must include wall-clock: no run is unbounded in time" ;;
  esac
  METRIC_NAMES_LIST="$(mf_value METRIC_NAMES)"
  METRIC_NS_LIST="$(mf_value METRIC_NAMESPACES)"
  check_tokens METRIC_NAMES "$METRIC_NAMES_LIST" '^[A-Za-z0-9_.-]{1,255}$'
  check_tokens METRIC_NAMESPACES "$METRIC_NS_LIST" '^[A-Za-z0-9_./#:-]{1,255}$'

  OUTPUT_ROOT="$(mf_value OUTPUT_ROOT)"
  require_external_dir "$OUTPUT_ROOT" "OUTPUT_ROOT"
  if [[ -n "$OUT_DIR_ARG" && "$OUT_DIR_ARG" != "$OUTPUT_ROOT" ]]; then
    die "--out-dir differs from the manifest OUTPUT_ROOT: the manifest is the one place that names the output location"
  fi

  ENTRY="$(mf_value ENTRY_POINT)"
  ENTRY_OK="yes"
  case "$ENTRY" in
    script:*)
      ENTRY_KIND="script"
      ENTRY_REL="${ENTRY#script:}"
      re='^(experiments|load-tests)/[A-Za-z0-9._/-]+\.sh$'
      [[ "$ENTRY_REL" =~ $re ]] || die "ENTRY_POINT script must be a .sh file under experiments/ or load-tests/, got: $ENTRY_REL"
      case "/$ENTRY_REL/" in
        */../* | */./* | *//*) die "ENTRY_POINT must not contain . or .. segments or doubled slashes: $ENTRY_REL" ;;
      esac
      if git -C "$SC_REPO_ROOT" ls-files --error-unmatch -- "$ENTRY_REL" >/dev/null 2>&1; then
        ENTRY_OK="yes"
      elif is_dry_run || [[ "$SC_PRINT_DIGEST" -eq 1 ]]; then
        ENTRY_OK="no"
      else
        die "ENTRY_POINT $ENTRY_REL is not a tracked file in this commit"
      fi ;;
    sbt:*)
      ENTRY_KIND="sbt"
      ENTRY_REL="${ENTRY#sbt:}"
      re='^[A-Za-z][A-Za-z0-9/:_.-]{0,100}$'
      [[ "$ENTRY_REL" =~ $re ]] || die "ENTRY_POINT sbt task is not valid: $ENTRY_REL"
      ;;
    *) die "ENTRY_POINT must start with script: or sbt:, got: $ENTRY" ;;
  esac
}

# rec KEY VALUE: append one line to the run record. Keys are never rewritten.
rec() {
  printf '%s=%s\n' "$1" "$2" >> "$REC"
}

on_signal() {
  printf 'SIGNAL received\n' >&2
  SC_STEP_OPEN=0
  if [[ -n "$REC" ]]; then
    printf 'END_UTC=%s\nFINAL_STATUS=INTERRUPTED\n' "$(utc_now)" >> "$REC" || true
  fi
  exit 130
}

### Validation ---------------------------------------------------------------

step validate-params
OUT_DIR_ARG="$SC_OUT_DIR"
[[ -n "$SC_REGION" ]] || die "--region is required (the only allowed value is $SC_APPROVED_REGION)"
[[ -n "$MANIFEST_FILE" ]] || die "--manifest is required"
init_common yes
require_cmd aws git awk grep date
validate_manifest
MF_SHA="$(sha256_file "$MANIFEST_FILE")"
SC_OUT_DIR="$OUTPUT_ROOT"
HEAD_SHA="$(git_head)"
EXTRA="manifest=$MF_SHA;experiment=$EXP_ID;size=$TIER;max_minutes=$MAX_MINUTES;max_concurrency=$MAX_CONC;max_tasks=$MAX_TASKS;spend_ceiling=$SPEND"
maybe_print_digest experiment "$EXTRA"
printf 'MANIFEST_VALID experiment=%s size=%s sha256=%s\n' "$EXP_ID" "$TIER" "$MF_SHA"
printf 'LIMITS max_minutes=%s max_concurrency=%s max_tasks=%s spend_ceiling_usd=%s projected_spend_usd=%s (tier caps %s min, %s concurrent, %s tasks)\n' \
  "$MAX_MINUTES" "$MAX_CONC" "$MAX_TASKS" "$SPEND" "$PROJECTED" "$CAP_MINUTES" "$CAP_CONC" "$CAP_TASKS"
step_ok

if is_dry_run; then
  dry_banner experiment "$EXTRA"
fi

step clean-tree
require_clean_tree
printf 'CLEAN_TREE git_sha=%s\n' "$HEAD_SHA"
step_ok

step approval-record
verify_approval experiment "$EXTRA" "n/a" "$HEAD_SHA"
step_ok

if is_dry_run; then
  dry_approval_unconsumed
  if [[ "$ENTRY_OK" == "no" ]]; then
    dry_skipped entry-point "$ENTRY_REL is not a tracked file in this commit; a real run refuses"
  fi
  printf 'OUTPUT_ROOT=%s\n' "$OUTPUT_ROOT"
  dry_step identity-check
  dry_would "aws sts get-caller-identity (skipped)"
  dry_step preflight
  dry_would "re-check the clean tree, HEAD and the manifest digest; choose run id exp-$EXP_ID-<UTC>-${HEAD_SHA:0:8}; refuse if its directory exists"
  dry_step start-run
  dry_would "consume the approval record; mkdir $OUTPUT_ROOT/<run id> (mode 700, never overwritten) with raw/ and metrics/; write run-record.env"
  dry_step run-entry
  dry_would "$ENTRY_KIND entry point $ENTRY_REL, started once, limit $((MAX_MINUTES * 60)) s (TERM, then KILL 5 s later, sent to the entry point's whole process group; any member still alive when it returns is stopped before finalize), output to raw/entrypoint.log; environment SCHOLARCHAIN_EXPERIMENT_ID, SCHOLARCHAIN_RUN_ID, SCHOLARCHAIN_RUN_DIR, SCHOLARCHAIN_MAX_MINUTES, SCHOLARCHAIN_MAX_CONCURRENCY, SCHOLARCHAIN_MAX_TASKS, SCHOLARCHAIN_REGION, SCHOLARCHAIN_ACCOUNT_ID, SCHOLARCHAIN_DATASET_SNAPSHOT"
  dry_step metric-export
  dry_would "aws cloudwatch list-metrics (a read, retried up to 3 times) for namespaces $METRIC_NS_LIST and names $METRIC_NAMES_LIST, one file each under metrics/"
  dry_step finalize
  dry_would "append end time, exit status and estimated spend (burn x elapsed) to run-record.env; make raw/ and metrics/ read-only; write SHA256SUMS"
  dry_finish
fi

### Run ----------------------------------------------------------------------

step identity-check
verify_identity
step_ok

step preflight
require_clean_tree
[[ "$(git_head)" == "$HEAD_SHA" ]] || die "HEAD moved during the run"
[[ "$(sha256_file "$MANIFEST_FILE")" == "$MF_SHA" ]] || die "the manifest changed after it was digested"
RUN_ID="exp-${EXP_ID}-$(utc_stamp)-${HEAD_SHA:0:8}"
RUN_DIR="$OUTPUT_ROOT/$RUN_ID"
[[ ! -e "$RUN_DIR" ]] || die "run directory already exists: $RUN_DIR (a run is written once and never overwritten)"
printf 'RUN_ID=%s\nRUN_DIR=%s\n' "$RUN_ID" "$RUN_DIR"
step_ok

step start-run
consume_approval experiment
make_out_dir
(umask 077 && mkdir "$RUN_DIR") || die "cannot create the run directory: $RUN_DIR"
RAW_DIR="$RUN_DIR/raw"
METRICS_DIR="$RUN_DIR/metrics"
(umask 077 && mkdir "$RAW_DIR" "$METRICS_DIR")
REC="$RUN_DIR/run-record.env"
(umask 077; set -C; : > "$REC") || die "cannot create $REC"
trap on_signal INT TERM
START_UTC="$(utc_now)"
START_EPOCH="$(date +%s)"
rec RECORD_VERSION 1
rec RUN_ID "$RUN_ID"
rec EXPERIMENT_ID "$EXP_ID"
rec DESCRIPTION "$DESCRIPTION"
rec GIT_SHA "$HEAD_SHA"
rec MANIFEST_SHA256 "$MF_SHA"
rec ENVIRONMENT "$SC_ENV"
rec REGION "$SC_REGION"
rec ACCOUNT_ID "$SC_ACCOUNT"
rec DATASET_SNAPSHOT "$DATASET"
rec SIZE_TIER "$TIER"
rec MAX_MINUTES "$MAX_MINUTES"
rec MAX_CONCURRENCY "$MAX_CONC"
rec MAX_TASKS "$MAX_TASKS"
rec SPEND_CEILING_USD "$SPEND"
rec HOURLY_BURN_USD "$BURN"
rec STOP_CONDITIONS "$STOPS"
rec ENTRY_POINT "$ENTRY"
rec APPROVED_BY "$(approval_value APPROVED_BY)"
rec APPROVED_AT "$(approval_value APPROVED_AT)"
rec START_UTC "$START_UTC"
rec METRIC_REGION "$SC_REGION"
rec METRIC_NAMESPACES "$METRIC_NS_LIST"
rec METRIC_NAMES "$METRIC_NAMES_LIST"
rec METRIC_WINDOW_START_UTC "$START_UTC"
rec RAW_DIR "$RAW_DIR"
rec METRICS_DIR "$METRICS_DIR"
step_ok

step run-entry
SECS=$((MAX_MINUTES * 60))
ENV_BIN="$(type -P env)" || die "required command not found: env"
if [[ "$ENTRY_KIND" == "script" ]]; then
  CMD_BIN="$(type -P bash)" || die "required command not found: bash"
  CMD_ARG="$SC_REPO_ROOT/$ENTRY_REL"
else
  CMD_BIN="$(ext_bin sbt)" || exit 1
  CMD_ARG="$ENTRY_REL"
fi
ENTRY_RC=0
run_bounded "$SECS" "$ENV_BIN" \
  SCHOLARCHAIN_EXPERIMENT_ID="$EXP_ID" \
  SCHOLARCHAIN_RUN_ID="$RUN_ID" \
  SCHOLARCHAIN_RUN_DIR="$RAW_DIR" \
  SCHOLARCHAIN_MAX_MINUTES="$MAX_MINUTES" \
  SCHOLARCHAIN_MAX_CONCURRENCY="$MAX_CONC" \
  SCHOLARCHAIN_MAX_TASKS="$MAX_TASKS" \
  SCHOLARCHAIN_REGION="$SC_REGION" \
  SCHOLARCHAIN_ACCOUNT_ID="$SC_ACCOUNT" \
  SCHOLARCHAIN_DATASET_SNAPSHOT="$DATASET" \
  "$CMD_BIN" "$CMD_ARG" </dev/null >"$RAW_DIR/entrypoint.log" 2>&1 || ENTRY_RC=$?
trap on_signal INT TERM
# run_bounded has stopped every member of the entry point's process group before it
# returned (TERM, then KILL), so nothing it started writes to raw/ after this point.
if [[ "$SC_RB_STRAY" -eq 1 ]]; then
  printf 'ENTRY_PROCESSES_STRAY pgid=%s: members were still running after the entry point returned and were stopped\n' "$SC_RB_PID"
fi
if [[ "$SC_RB_LEFT" -eq 1 ]]; then
  printf 'ENTRY_PROCESSES_REMAINING pgid=%s: a member survived KILL; raw/ may still change and SHA256SUMS may stop matching\n' "$SC_RB_PID" >&2
fi
END_EPOCH="$(date +%s)"
ELAPSED=$((END_EPOCH - START_EPOCH))
case "$ENTRY_RC" in
  0) FINAL="COMPLETED" ;;
  124) FINAL="STOPPED_WALL_CLOCK" ;;
  *) FINAL="FAILED" ;;
esac
printf 'ENTRY_EXIT=%s FINAL_STATUS=%s elapsed_s=%s\n' "$ENTRY_RC" "$FINAL" "$ELAPSED"
step_ok

step metric-export
EXPORT_FAIL=0
NS_WORDS="$(csv_words "$METRIC_NS_LIST")"
NAME_WORDS="$(csv_words "$METRIC_NAMES_LIST")"
for ns in $NS_WORDS; do
  for name in $NAME_WORDS; do
    out="$METRICS_DIR/$(safe_name "$ns")__$(safe_name "$name").list.json"
    if (set -C; aws cloudwatch list-metrics --namespace "$ns" --metric-name "$name" --output json > "$out"); then
      printf 'METRIC_LISTING namespace=%s metric=%s file=%s\n' "$ns" "$name" "$out"
    else
      printf 'WARN: the metric listing failed for %s %s\n' "$ns" "$name" >&2
      EXPORT_FAIL=$((EXPORT_FAIL + 1))
    fi
  done
done
step_ok

step finalize
END_UTC="$(utc_now)"
EST="$(awk -v b="$BURN" -v s="$ELAPSED" 'BEGIN { printf "%.2f", b * s / 3600 }')"
OVER="no"
if ! num_le "$EST" "$SPEND"; then OVER="yes"; fi
rec END_UTC "$END_UTC"
rec METRIC_WINDOW_END_UTC "$END_UTC"
rec ELAPSED_SECONDS "$ELAPSED"
rec EXIT_STATUS "$ENTRY_RC"
rec FINAL_STATUS "$FINAL"
rec ESTIMATED_SPEND_USD "$EST"
rec SPEND_ESTIMATE_EXCEEDED "$OVER"
rec METRIC_EXPORT_FAILURES "$EXPORT_FAIL"
if [[ "$SC_RB_STRAY" -eq 1 ]]; then STRAY="yes"; else STRAY="no"; fi
if [[ "$SC_RB_LEFT" -eq 1 ]]; then LEFT="yes"; else LEFT="no"; fi
rec ENTRY_PROCESSES_STRAY "$STRAY"
rec ENTRY_PROCESSES_REMAINING "$LEFT"
rec RECORD_COMPLETE yes
chmod -R a-w "$RAW_DIR" "$METRICS_DIR"
chmod a-w "$REC"
(
  cd "$RUN_DIR"
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r f; do
    printf '%s  %s\n' "$(sha256_file "$f")" "${f#./}"
  done
) > "$RUN_DIR/SHA256SUMS"
chmod a-w "$RUN_DIR/SHA256SUMS"
printf 'RUN_RECORD=%s\nRUN_SHA256SUMS=%s\n' "$REC" "$RUN_DIR/SHA256SUMS"
printf 'EXPERIMENT_RUN experiment=%s run_id=%s final=%s elapsed_s=%s estimated_spend_usd=%s ceiling_usd=%s\n' \
  "$EXP_ID" "$RUN_ID" "$FINAL" "$ELAPSED" "$EST" "$SPEND"
note "estimated spend is burn x time, not a bill. Record actual spend from AWS billing."
step_ok

[[ "$LEFT" == "no" ]] || die "a process of the entry point survived KILL (ENTRY_PROCESSES_REMAINING=yes): the checksums of raw/ may not match; stop it by hand and treat this run as unreliable"
[[ "$FINAL" == "COMPLETED" ]] || die "the experiment did not complete: $FINAL (exit $ENTRY_RC); the record is at $REC"
[[ "$OVER" == "no" ]] || die "the estimated spend $EST USD exceeds the spend ceiling $SPEND USD"
[[ "$EXPORT_FAIL" -eq 0 ]] || die "$EXPORT_FAIL metric listing(s) failed; see the warnings above"
