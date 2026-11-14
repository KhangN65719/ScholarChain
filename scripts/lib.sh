# shellcheck shell=bash
# Shared functions for the ScholarChain operational scripts. Source it, never
# run it. The calling script sets SC_SCRIPT (its own path) before sourcing.
#
# Contract of every script that sources this file:
#   - bash strict mode, no prompts, greppable markers STEP[n] name: start|ok|skip|FAIL
#   - region is us-east-2 or the script refuses (Phase 14 decision D1: the account is
#     locked to us-east-2; another Region needs an irreversible account change)
#   - account id, environment and cost ceiling are explicit parameters
#   - the approval record (see verify_approval) is verified, never bypassed
#   - no secret value is read, printed or placed in an argument list, except in
#     smoke-test.sh, which reads one bearer token into a private temp file
#   - --dry-run is the only way to a dry run (no environment variable, and no flag
#     turns a dry run real). A dry run calls no aws, terraform, docker, curl or sbt:
#     those names are shell functions below that refuse first (dry_guard), so no call
#     site can reach the real binary. It writes no plan, marker or output directory.
#   - every aws call carries a connect and a read timeout, and only describe-,
#     list-, get- and head- operations are retried (3 attempts); docker calls run
#     under run_bounded. Terraform is not wrapped in a timeout: a killed apply is
#     worse than a slow one (see scripts/README.md, "Timeouts and retries").
#
# What the approval checks do NOT stop (they are consistency checks, not
# authorization; the real control is the human signing the digest. A ratified
# launcher is not part of Phase 13 (decision D3, an open constitutional item), so
# running these scripts stays a human-controlled step):
#   - a caller who can write a record: every digest is printable, so a new record
#     with a fresh APPROVED_AT is a new single-use marker
#   - SCHOLARCHAIN_OPS_STATE_DIR pointing at an empty marker directory: a consumed
#     record is then reusable, because the markers are only files in that directory.
#     check_state_dir refuses a relative path, . or .. segments, and a location
#     inside the repository or --out-dir, and the override is printed as
#     STATE_DIR_OVERRIDE so it shows in the log. An empty directory elsewhere (for
#     example a fresh directory under HOME) is NOT refused and cannot be detected.
#   - the smoke-test.sh --out-dir repository check is a comparison of physical,
#     lower-cased paths (physical_path below). It resolves symlinks in the
#     existing part of the path, squeezes doubled slashes and ignores letter case,
#     but it is a check at one moment: a symlink swapped after the check, a hard
#     link, or a bind mount gets past it.
#
# Compatible with bash 3.2 (macOS): no associative arrays, no mapfile.

set -euo pipefail

SC_LIB_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
SC_REPO_ROOT="$(cd "$(dirname "$SC_LIB_FILE")/.." && pwd)"
SC_TF_ROOT="$SC_REPO_ROOT/infra/terraform"
SC_PROJECT="scholarchain"
SC_APPROVED_REGION="us-east-2"
# Shown with every Region refusal. Names decision D1 so the reader does not look for
# a way around it.
SC_REGION_LOCK_NOTE="Phase 14 decision D1: the AWS account is locked to us-east-2 (Ohio); another Region needs an irreversible account change that removes the spend limit and is never suggested"
# Phase 14 decision D6: the code ceiling is 20 USD, matching the AWS-enforced project
# spend limit. The AWS Budget of 25 (alerts at 10, 15, 20) is the outer alert and the
# human's operating stop near 15 is tighter still; neither is enforced here.
SC_PROJECT_CEILING_USD="20"
# An approval record may be valid for at most this long (24 hours).
SC_APPROVAL_MAX_SECONDS=86400
# Where single-use markers live. Outside the repository and outside --out-dir,
# so a changed --out-dir does not forget a consumed record.
SC_STATE_DIR="${SCHOLARCHAIN_OPS_STATE_DIR:-${HOME:-}/.scholarchain-ops}"

SC_ENV=""
SC_ACCOUNT=""
SC_REGION="$SC_APPROVED_REGION"
SC_COST=""
SC_APPROVAL_FILE=""
SC_OUT_DIR=""
SC_PRINT_DIGEST=0
SC_DRY_RUN=0
SC_SHIFT=0
SC_ENV_DIR=""
SC_TFVARS=""
SC_NAME_PREFIX=""

SC_STEP_N=0
SC_STEP_NAME="init"
SC_STEP_OPEN=0
SC_CLEANUP_PATHS=""

### Markers and failure ------------------------------------------------------

step() {
  SC_STEP_N=$((SC_STEP_N + 1))
  SC_STEP_NAME="$1"
  SC_STEP_OPEN=1
  printf 'STEP[%s] %s: start\n' "$SC_STEP_N" "$SC_STEP_NAME"
}

step_ok() {
  printf 'STEP[%s] %s: ok\n' "$SC_STEP_N" "$SC_STEP_NAME"
  SC_STEP_OPEN=0
}

step_skip() {
  printf 'STEP[%s] %s: skip (%s)\n' "$SC_STEP_N" "$SC_STEP_NAME" "$1"
  SC_STEP_OPEN=0
}

note() {
  printf 'NOTE: %s\n' "$*"
}

die() {
  printf 'EVIDENCE: %s\n' "$*" >&2
  exit 1
}

register_cleanup() {
  SC_CLEANUP_PATHS="$SC_CLEANUP_PATHS $1"
}

sc_on_exit() {
  local rc=$?
  local p
  for p in $SC_CLEANUP_PATHS; do
    rm -f "$p" 2>/dev/null || true
  done
  if [[ $rc -ne 0 && $SC_STEP_OPEN -eq 1 ]]; then
    printf 'STEP[%s] %s: FAIL (exit %s)\n' "$SC_STEP_N" "$SC_STEP_NAME" "$rc"
  fi
}
trap sc_on_exit EXIT

### Argument handling --------------------------------------------------------

need_arg() {
  [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || die "$1 needs a value"
}

# Returns 0 and sets SC_SHIFT when $1 is a flag every script shares.
# SC_SHIFT is read by the calling scripts (shift "$SC_SHIFT"), which shellcheck
# does not follow when it checks this file on its own.
# shellcheck disable=SC2034
parse_common_flag() {
  SC_SHIFT=0
  case "$1" in
    --environment) need_arg "$@"; SC_ENV="$2"; SC_SHIFT=2 ;;
    --account-id) need_arg "$@"; SC_ACCOUNT="$2"; SC_SHIFT=2 ;;
    --region) need_arg "$@"; SC_REGION="$2"; SC_SHIFT=2 ;;
    --cost-ceiling-usd) need_arg "$@"; SC_COST="$2"; SC_SHIFT=2 ;;
    --approval-record) need_arg "$@"; SC_APPROVAL_FILE="$2"; SC_SHIFT=2 ;;
    --out-dir) need_arg "$@"; SC_OUT_DIR="$2"; SC_SHIFT=2 ;;
    --print-digest) SC_PRINT_DIGEST=1; SC_SHIFT=1 ;;
    --dry-run) SC_DRY_RUN=1; SC_SHIFT=1 ;;
    *) return 1 ;;
  esac
  return 0
}

print_header() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SC_SCRIPT"
}

common_usage() {
  cat <<'EOF'
Common parameters (all required unless marked):
  --environment NAME        environment directory under infra/terraform/environments (course)
  --account-id 123456789012 the approved AWS account; checked against the caller identity
  --region us-east-2        optional; any other value is refused (decision D1)
  --cost-ceiling-usd N      project cost ceiling, 0 < N <= 20 (decision D6); must equal the approval record
  --approval-record PATH    human approval record (see scripts/README.md); not used by plan.sh
  --out-dir ABS_PATH        optional; default ${TMPDIR:-/tmp}/scholarchain-ops/<environment>
  --print-digest            print SCRIPT_DIGEST and ARGS_DIGEST for the record, then exit
  --dry-run                 check arguments and the approval record, print the steps, call no
                            cloud, Terraform, docker or network command, consume no record
EOF
}

# type -P looks only at executables on PATH. command -v would find the wrapper
# functions below and report a missing aws, terraform or docker as present.
# A dry run does not need the cloud tools, so they are not required there.
require_cmd() {
  local c
  for c in "$@"; do
    if [[ "$SC_DRY_RUN" -eq 1 ]]; then
      case "$c" in
        aws | terraform | docker | curl | sbt) continue ;;
      esac
    fi
    type -P "$c" >/dev/null 2>&1 || die "required command not found: $c"
  done
}

validate_number() {
  [[ "$1" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "not a plain positive number: $1"
}

num_le() {
  awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 <= b + 0) }'
}

num_eq() {
  awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 == b + 0) }'
}

num_gt0() {
  awk -v a="$1" 'BEGIN { exit !(a + 0 > 0) }'
}

# needs_approval: yes or no. Validates the shared parameters.
init_common() {
  local needs_approval="$1"
  [[ -n "$SC_ENV" ]] || die "--environment is required"
  [[ -n "$SC_ACCOUNT" ]] || die "--account-id is required"
  [[ -n "$SC_COST" ]] || die "--cost-ceiling-usd is required"
  [[ "$SC_ENV" =~ ^[a-z][a-z0-9-]{1,19}$ ]] || die "environment name is not valid: $SC_ENV"
  [[ -d "$SC_TF_ROOT/environments/$SC_ENV" ]] || die "no Terraform environment directory for: $SC_ENV"
  [[ "$SC_ACCOUNT" =~ ^[0-9]{12}$ ]] || die "account id must be 12 digits"
  [[ "$SC_REGION" == "$SC_APPROVED_REGION" ]] || die "region must be $SC_APPROVED_REGION, got: $SC_REGION ($SC_REGION_LOCK_NOTE)"
  validate_number "$SC_COST"
  num_gt0 "$SC_COST" || die "cost ceiling must be greater than zero"
  num_le "$SC_COST" "$SC_PROJECT_CEILING_USD" || die "cost ceiling $SC_COST exceeds the project ceiling $SC_PROJECT_CEILING_USD USD"
  SC_ENV_DIR="$SC_TF_ROOT/environments/$SC_ENV"
  SC_TFVARS="$SC_ENV_DIR/$SC_ENV.tfvars"
  [[ -f "$SC_TFVARS" ]] || die "missing variable file: $SC_TFVARS"
  SC_NAME_PREFIX="${SC_PROJECT}-${SC_ENV}"
  if [[ "$needs_approval" == "yes" && "$SC_PRINT_DIGEST" -eq 0 ]]; then
    [[ -n "$SC_APPROVAL_FILE" ]] || die "--approval-record is required: no human approval, no run"
  fi
  if [[ -z "$SC_OUT_DIR" ]]; then
    SC_OUT_DIR="${TMPDIR:-/tmp}"
    SC_OUT_DIR="${SC_OUT_DIR%/}/scholarchain-ops/$SC_ENV"
  fi
  [[ "$SC_OUT_DIR" == /* ]] || die "--out-dir must be an absolute path"
  export AWS_DEFAULT_REGION="$SC_REGION"
  export AWS_REGION="$SC_REGION"
  export AWS_PAGER=""
}

make_out_dir() {
  dry_guard "make_out_dir"
  (umask 077 && mkdir -p "$SC_OUT_DIR")
}

# physical_path P: the absolute path P with doubled slashes squeezed and with
# symlinks resolved in its deepest existing directory; components that do not
# exist yet are appended unchanged. Callers refuse . and .. segments first.
# Prints nothing and fails when no ancestor can be entered.
physical_path() {
  local p rest="" base head
  p="$(printf '%s' "$1" | tr -s '/')"
  p="${p%/}"
  [[ -n "$p" ]] || p="/"
  while [[ "$p" != "/" && ! -d "$p" ]]; do
    base="${p##*/}"
    head="${p%/*}"
    rest="/$base$rest"
    p="${head:-/}"
  done
  p="$(cd "$p" 2>/dev/null && pwd -P)" || return 1
  if [[ "$p" == "/" ]]; then
    p=""
  fi
  if [[ -z "$p$rest" ]]; then
    printf '/'
  else
    printf '%s%s' "$p" "$rest"
  fi
}

# path_within CHILD PARENT: success when the physical path of CHILD is PARENT or
# below it. Letter case is ignored, so a macOS case-insensitive spelling of the
# same directory matches (a case-sensitive volume with two directories that differ
# only in case is over-refused, which is the safe side).
path_within() {
  local c p
  c="$(physical_path "$1")" || return 1
  p="$(physical_path "$2")" || return 1
  c="$(printf '%s/' "$c" | tr '[:upper:]' '[:lower:]')"
  p="$(printf '%s' "$p" | tr '[:upper:]' '[:lower:]')"
  p="${p%/}"
  case "$c" in
    "$p"/*) return 0 ;;
  esac
  return 1
}

# check_state_dir: the single-use marker directory must be absolute, free of . and
# .. segments, and outside the repository and --out-dir. It does not make the
# markers tamper-proof: any other empty directory still forgets consumed records.
check_state_dir() {
  [[ "$SC_STATE_DIR" == /* ]] || die "the single-use marker directory must be an absolute path: $SC_STATE_DIR"
  case "$SC_STATE_DIR/" in
    */../* | */./*) die "the single-use marker directory must not contain . or .. segments: $SC_STATE_DIR" ;;
  esac
  if path_within "$SC_STATE_DIR" "$SC_REPO_ROOT"; then
    die "the single-use marker directory must not be inside the repository: $SC_STATE_DIR"
  fi
  if [[ -n "$SC_OUT_DIR" ]] && path_within "$SC_STATE_DIR" "$SC_OUT_DIR"; then
    die "the single-use marker directory must not be inside --out-dir: $SC_STATE_DIR"
  fi
  if [[ -n "${SCHOLARCHAIN_OPS_STATE_DIR:-}" ]]; then
    printf 'STATE_DIR_OVERRIDE=%s (consumed-record markers are read from and written to this directory only)\n' "$SC_STATE_DIR"
  fi
}

### Digests ------------------------------------------------------------------

sha256_stdin() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    die "neither shasum nor sha256sum is installed"
  fi
}

sha256_file() {
  [[ -f "$1" ]] || die "not a file: $1"
  sha256_stdin < "$1"
}

# Digest of the running script followed by this library.
script_digest() {
  cat "$SC_SCRIPT" "$SC_LIB_FILE" | sha256_stdin
}

# dir_digest DIR: sha256 over the sorted relative paths and file digests of a
# directory tree. Refuses symlinks, which a digest of regular files would skip.
dir_digest() {
  local d="$1" f
  [[ -d "$d" ]] || die "not a directory: $d"
  [[ -z "$(find "$d" -type l | head -n 1)" ]] || die "directory holds a symlink, which the context digest cannot bind: $d"
  (
    cd "$d"
    find . -type f -print | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s  %s\n' "$(sha256_file "$f")" "$f"
    done
  ) | sha256_stdin
}

# tfvar_value FILE NAME: the last quoted string assigned to NAME in a .tfvars
# file, or nothing. Comments after # are ignored. Not a general HCL parser.
tfvar_value() {
  awk -v n="$2" '
    { line = $0; sub(/#.*/, "", line) }
    match(line, "^[[:space:]]*" n "[[:space:]]*=[[:space:]]*\"[^\"]*\"") {
      v = substr(line, RSTART, RLENGTH); sub(/^[^"]*"/, "", v); sub(/"$/, "", v); out = v
    }
    END { if (out != "") print out }
  ' "$1"
}

# check_tfvars_region FILE: refuse a var file that assigns a quoted region other than
# the approved one, or an availability_zones list with a zone outside it. Not an HCL
# parser (same limits as tfvar_value). Terraform's own validation on var.region is the
# second layer.
check_tfvars_region() {
  local f="$1" v bad
  v="$(tfvar_value "$f" region)"
  if [[ -n "$v" && "$v" != "$SC_APPROVED_REGION" ]]; then
    die "$f sets region = \"$v\"; region must be $SC_APPROVED_REGION ($SC_REGION_LOCK_NOTE)"
  fi
  bad="$(awk -v ok="$SC_APPROVED_REGION" '
    { line = $0; sub(/#.*/, "", line) }
    line ~ /^[[:space:]]*availability_zones[[:space:]]*=/ {
      n = split(line, parts, "\"")
      for (i = 2; i <= n; i += 2) if (index(parts[i], ok) != 1) { print parts[i]; exit }
    }
  ' "$f")"
  if [[ -n "$bad" ]]; then
    die "$f sets an availability zone outside $SC_APPROVED_REGION: $bad ($SC_REGION_LOCK_NOTE)"
  fi
}

args_canonical() {
  printf 'action=%s;environment=%s;account=%s;region=%s;cost=%s;extra=%s' \
    "$1" "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION" "$SC_COST" "$2"
}

args_digest() {
  args_canonical "$1" "$2" | sha256_stdin
}

# For the human who writes the approval record. Touches no cloud and no state.
maybe_print_digest() {
  local action="$1" extra="$2"
  if [[ "$SC_PRINT_DIGEST" -eq 1 ]]; then
    [[ "$extra" != @skip:* ]] || die "cannot print the digests: ${extra#@skip:}"
    printf 'ACTION=%s\n' "$action"
    printf 'SCRIPT_DIGEST=%s\n' "$(script_digest)"
    printf 'ARGS_CANONICAL=%s\n' "$(args_canonical "$action" "$extra")"
    printf 'ARGS_DIGEST=%s\n' "$(args_digest "$action" "$extra")"
    SC_STEP_OPEN=0
    exit 0
  fi
}

### Approval record ----------------------------------------------------------
# KEY=VALUE lines, no spaces in values, # comments. Never sourced or evaluated.

SC_APPROVAL_KEYS="APPROVAL_VERSION ACTION ENVIRONMENT ACCOUNT_ID REGION COST_CEILING_USD PLAN_DIGEST SCRIPT_DIGEST ARGS_DIGEST GIT_SHA STATEFUL_CHANGE_ACKNOWLEDGED APPROVED_BY APPROVED_AT EXPIRES_AT"

approval_value() {
  awk -v k="$1" '
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1) }
    key == k { print substr($0, i + 1); exit }
  ' "$SC_APPROVAL_FILE"
}

approval_count() {
  awk -v k="$1" '
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1) }
    key == k { n++ }
    END { print n + 0 }
  ' "$SC_APPROVAL_FILE"
}

# iso_epoch 2026-11-20T15:04:05Z: seconds since the epoch. GNU date first, then BSD (macOS).
iso_epoch() {
  local s="$1" e
  if e="$(date -u -d "$s" +%s 2>/dev/null)"; then
    printf '%s' "$e"
    return 0
  fi
  e="$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$s" +%s 2>/dev/null)" || die "cannot parse the time: $s"
  printf '%s' "$e"
}

# consumed_dir ACTION: the marker directory for the approval in $SC_APPROVAL_FILE.
# The key covers the action, the arguments digest, the plan digest and APPROVED_AT.
# It is not a security control: a caller who can write a record can also write a
# different APPROVED_AT. It stops an accidental or scripted second run of the
# same record.
consumed_dir() {
  local key
  key="$(printf 'action=%s;args=%s;plan=%s;approved_at=%s' "$1" "$(approval_value ARGS_DIGEST)" \
    "$(approval_value PLAN_DIGEST)" "$(approval_value APPROVED_AT)" | sha256_stdin)"
  printf '%s/consumed/%s' "$SC_STATE_DIR" "$key"
}

# consume_approval ACTION: call after verify_approval and verify_identity, right
# before the first billable or state-changing call. mkdir is the atomic step: the
# second of two racing runs fails. A failed action still consumes the record, so
# a retry needs a new record (conservative, because a retry can re-spend).
consume_approval() {
  local action="$1" d
  dry_guard "consume_approval"
  [[ -n "${HOME:-}" || -n "${SCHOLARCHAIN_OPS_STATE_DIR:-}" ]] || die "HOME is not set and SCHOLARCHAIN_OPS_STATE_DIR is not given: nowhere to keep the single-use marker"
  check_state_dir
  d="$(consumed_dir "$action")"
  (umask 077 && mkdir -p "$(dirname "$d")")
  mkdir "$d" 2>/dev/null || die "approval record already consumed (marker $d)"
  printf 'action=%s consumed_at=%s environment=%s\n' "$action" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SC_ENV" > "$d/info"
  printf 'APPROVAL_CONSUMED action=%s marker=%s\n' "$action" "$d"
}

# verify_approval ACTION EXTRA EXPECTED_PLAN_DIGEST EXPECTED_GIT_SHA
# EXPECTED_PLAN_DIGEST and EXPECTED_GIT_SHA are "n/a" when the action has none.
# Optional fifth argument "yes": the record must also set STATEFUL_CHANGE_ACKNOWLEDGED=yes.
# That check runs after every other one and before APPROVAL_VERIFIED is printed, so a
# record that fails it is never reported as verified.
verify_approval() {
  local action="$1" extra="$2" plan="$3" sha="$4" require_ack="${5:-no}"
  local key v now lower_by skipped=""

  check_state_dir
  [[ -n "$SC_APPROVAL_FILE" ]] || die "no approval record given"
  [[ -f "$SC_APPROVAL_FILE" && ! -L "$SC_APPROVAL_FILE" ]] || die "approval record is missing, not a regular file, or a symlink: $SC_APPROVAL_FILE"
  [[ -r "$SC_APPROVAL_FILE" ]] || die "approval record is not readable"

  if awk '!/^[[:space:]]*(#|$)/ && !/^[A-Z_]+=[^[:space:]]*$/ { bad = 1 } END { exit !bad }' "$SC_APPROVAL_FILE"; then
    die "approval record has a malformed line (expected KEY=VALUE with no spaces)"
  fi

  if awk -v list="$SC_APPROVAL_KEYS" '
    BEGIN { n = split(list, a, " "); for (j = 1; j <= n; j++) ok[a[j]] = 1 }
    /^[[:space:]]*(#|$)/ { next }
    { i = index($0, "="); key = substr($0, 1, i - 1); if (!(key in ok)) bad = 1 }
    END { exit !bad }
  ' "$SC_APPROVAL_FILE"; then
    die "approval record has an unknown key; allowed: $SC_APPROVAL_KEYS"
  fi

  for key in $SC_APPROVAL_KEYS; do
    [[ "$(approval_count "$key")" == "1" ]] || die "approval record must hold $key exactly once"
    [[ -n "$(approval_value "$key")" ]] || die "approval record has an empty $key"
  done

  [[ "$(approval_value APPROVAL_VERSION)" == "1" ]] || die "unsupported APPROVAL_VERSION"
  [[ "$(approval_value ACTION)" == "$action" ]] || die "record ACTION '$(approval_value ACTION)' is not '$action'"
  [[ "$(approval_value ENVIRONMENT)" == "$SC_ENV" ]] || die "record ENVIRONMENT differs from --environment"
  [[ "$(approval_value ACCOUNT_ID)" == "$SC_ACCOUNT" ]] || die "record ACCOUNT_ID differs from --account-id"
  [[ "$(approval_value REGION)" == "$SC_REGION" ]] || die "record REGION differs from --region"
  [[ "$(approval_value REGION)" == "$SC_APPROVED_REGION" ]] || die "record REGION is not $SC_APPROVED_REGION ($SC_REGION_LOCK_NOTE)"

  v="$(approval_value COST_CEILING_USD)"
  validate_number "$v"
  num_eq "$v" "$SC_COST" || die "record COST_CEILING_USD $v differs from --cost-ceiling-usd $SC_COST"
  num_le "$v" "$SC_PROJECT_CEILING_USD" || die "record COST_CEILING_USD exceeds the project ceiling"

  [[ "$(approval_value SCRIPT_DIGEST)" == "$(script_digest)" ]] || die "record SCRIPT_DIGEST does not match this script plus lib.sh: the script changed after approval"
  # The @skip: sentinel exists for dry runs that lack the plan file or the build
  # contexts. The check is then not done, said so on stdout and listed in
  # skipped=; it is never reported as passed. A real run refuses the sentinel.
  if [[ "$extra" == @skip:* || "$plan" == @skip:* ]]; then
    [[ "$SC_DRY_RUN" -eq 1 ]] || die "internal error: a skipped approval check outside a dry run"
  fi
  if [[ "$extra" == @skip:* ]]; then
    skipped="${skipped}${skipped:+,}ARGS_DIGEST"
    printf 'APPROVAL_CHECK_SKIPPED field=ARGS_DIGEST reason=%s\n' "${extra#@skip:}"
  else
    [[ "$(approval_value ARGS_DIGEST)" == "$(args_digest "$action" "$extra")" ]] || die "record ARGS_DIGEST does not match these exact arguments"
  fi
  if [[ "$plan" == @skip:* ]]; then
    skipped="${skipped}${skipped:+,}PLAN_DIGEST"
    printf 'APPROVAL_CHECK_SKIPPED field=PLAN_DIGEST reason=%s\n' "${plan#@skip:}"
  else
    [[ "$(approval_value PLAN_DIGEST)" == "$plan" ]] || die "record PLAN_DIGEST does not match: expected $plan"
  fi
  [[ "$(approval_value GIT_SHA)" == "$sha" ]] || die "record GIT_SHA does not match: expected $sha"

  v="$(approval_value STATEFUL_CHANGE_ACKNOWLEDGED)"
  [[ "$v" == "yes" || "$v" == "no" ]] || die "STATEFUL_CHANGE_ACKNOWLEDGED must be yes or no"

  lower_by="$(approval_value APPROVED_BY | tr '[:upper:]' '[:lower:]')"
  case "$lower_by" in
    claude* | agent* | orchestrator* | *subagent* | *-engineer | auto* | none | n/a)
      die "APPROVED_BY must name the human approver, not an agent or role" ;;
  esac

  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for key in APPROVED_AT EXPIRES_AT; do
    [[ "$(approval_value "$key")" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die "$key must be UTC ISO 8601 like 2026-11-20T15:04:05Z"
  done
  [[ ! "$(approval_value APPROVED_AT)" > "$now" ]] || die "APPROVED_AT is in the future"
  [[ "$(approval_value EXPIRES_AT)" > "$now" ]] || die "approval expired at $(approval_value EXPIRES_AT)"

  # Validity window cap: EXPIRES_AT may not be more than 24 hours after APPROVED_AT.
  local t_from t_to
  t_from="$(iso_epoch "$(approval_value APPROVED_AT)")"
  t_to="$(iso_epoch "$(approval_value EXPIRES_AT)")"
  [[ "$t_to" -gt "$t_from" ]] || die "EXPIRES_AT must be after APPROVED_AT"
  [[ $((t_to - t_from)) -le "$SC_APPROVAL_MAX_SECONDS" ]] || die "approval window is longer than $((SC_APPROVAL_MAX_SECONDS / 3600)) hours (APPROVED_AT to EXPIRES_AT)"

  # Single use: a record that was already consumed for this action and these
  # arguments is refused. consume_approval writes the marker just before the
  # first billable or state-changing call.
  [[ ! -d "$(consumed_dir "$action")" ]] || die "this approval record was already used for $action with these arguments (marker $(consumed_dir "$action")); the human must write a new record"

  if [[ "$require_ack" == "yes" ]]; then
    [[ "$(approval_value STATEFUL_CHANGE_ACKNOWLEDGED)" == "yes" ]] || die "destroy removes stateful resources: the record must set STATEFUL_CHANGE_ACKNOWLEDGED=yes"
  fi

  printf 'APPROVAL_VERIFIED action=%s environment=%s account=%s region=%s cost_ceiling_usd=%s approved_by=%s expires_at=%s\n' \
    "$action" "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION" "$SC_COST" "$(approval_value APPROVED_BY)" "$(approval_value EXPIRES_AT)"
  if [[ "$SC_DRY_RUN" -eq 1 ]]; then
    printf 'APPROVAL_SKIPPED_FIELDS=%s\n' "${skipped:-none}"
  fi
}

### Dry run and external commands -------------------------------------------
# aws, terraform, docker, curl and sbt are functions here, so that every call site
# in every script goes through dry_guard first. A function wins over the binary
# for a plain command name, also inside $( ) and pipelines. The real binary is
# found with type -P and run by path. Local reads (git, awk, find, sha256) and the
# approval-record reads are not wrapped: they change nothing.

dry_guard() {
  if [[ "$SC_DRY_RUN" -eq 1 ]]; then
    die "DRY_RUN_GUARD: a dry run reached $1, which must never run in a dry run. This is a script defect; nothing was changed."
  fi
}

ext_bin() {
  local b
  b="$(type -P "$1")" || die "required command not found: $1"
  printf '%s' "$b"
}

# sc_group_alive PGID: success while any process of the group exists.
sc_group_alive() {
  kill -0 -- "-$1" 2>/dev/null
}

# sc_group_stop PGID: TERM the whole process group, wait up to 5 seconds, then KILL
# it and wait up to 5 seconds more. Returns 0 when the group is gone, 1 when a
# member is still there.
sc_group_stop() {
  local g="$1"
  sc_group_alive "$g" || return 0
  kill -TERM -- "-$g" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sc_group_alive "$g" || return 0
    sleep 0.5
  done
  kill -KILL -- "-$g" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sc_group_alive "$g" || return 0
    sleep 0.5
  done
  return 1
}

# run_bounded SECONDS PATH_TO_BINARY ARGS... Runs the command in its own process
# group (bash job control, set -m, so the command is the group leader and its
# children inherit the group). A watchdog sends TERM to the whole group at the limit
# and KILL 5 seconds later. When the command returns, any group member still alive
# is stopped the same way before run_bounded returns, so nothing the command started
# keeps running or writing afterwards. Standard input is kept (docker login
# --password-stdin needs it). Returns the command's status, or 124 on a timeout. A
# Ctrl-C or TERM is forwarded to the group.
# After each call: SC_RB_STRAY=1 when members were still alive after the leader
# returned (and were signalled), SC_RB_LEFT=1 when a member survived even KILL. A
# member that starts its own session or group (setsid, set -m in the entry point)
# is outside the group and is not reached. UNVERIFIED until run with real processes.
SC_RB_PID=""
# Read by run-experiment.sh, which shellcheck does not follow when it checks this
# file on its own.
# shellcheck disable=SC2034
SC_RB_STRAY=0
# shellcheck disable=SC2034
SC_RB_LEFT=0

run_bounded() {
  local secs="$1" rc=0 wd was_m=0 mark
  shift
  SC_RB_STRAY=0
  SC_RB_LEFT=0
  mark="$(mktemp "${TMPDIR:-/tmp}/sc-rb.XXXXXX")" || die "cannot create a temporary file for the time limit marker"
  rm -f "$mark"
  register_cleanup "$mark"
  case "$-" in *m*) was_m=1 ;; esac
  set -m
  "$@" <&0 &
  SC_RB_PID=$!
  (
    exec >/dev/null 2>&1 </dev/null
    sleep "$secs"
    : > "$mark"
    kill -TERM -- "-$SC_RB_PID" 2>/dev/null || true
    sleep 5
    kill -KILL -- "-$SC_RB_PID" 2>/dev/null || true
  ) &
  wd=$!
  trap 'kill -TERM -- "-$SC_RB_PID" 2>/dev/null || true' INT TERM
  wait "$SC_RB_PID" || rc=$?
  trap - INT TERM
  kill -- "-$wd" 2>/dev/null || kill "$wd" 2>/dev/null || true
  wait "$wd" 2>/dev/null || true
  if sc_group_alive "$SC_RB_PID"; then
    # Read by run-experiment.sh (lines 371, 417), outside what shellcheck follows here.
    # shellcheck disable=SC2034
    SC_RB_STRAY=1
    printf 'EVIDENCE: process group %s still had members after the command returned; stopping them\n' "$SC_RB_PID" >&2
    if ! sc_group_stop "$SC_RB_PID"; then
      # Read by run-experiment.sh (lines 374, 418), outside what shellcheck follows here.
      # shellcheck disable=SC2034
      SC_RB_LEFT=1
      printf 'EVIDENCE: PROCESS_GROUP_REMAINING pgid=%s: a member survived KILL; stop it by hand\n' "$SC_RB_PID" >&2
    fi
  fi
  if [[ "$was_m" -eq 0 ]]; then set +m; fi
  if [[ -e "$mark" ]]; then
    rm -f "$mark"
    printf 'EVIDENCE: a command exceeded its %s second limit and was stopped\n' "$secs" >&2
    return 124
  fi
  return "$rc"
}

# Every aws call: connect and read timeouts, and retries only for reads
# (describe-, list-, get- and head- operations, 3 attempts). Anything else, such as
# start-execution, stop-execution, terminate-job-flows and the billed Bedrock
# calls, runs once.
aws() {
  dry_guard "aws ${1:-} ${2:-}"
  local bin attempts=1
  bin="$(ext_bin aws)" || exit 1
  case "${2:-}" in
    describe-* | list-* | get-* | head-*) attempts=3 ;;
  esac
  env AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS="$attempts" "$bin" "$@" \
    --cli-connect-timeout 10 --cli-read-timeout 60
}

terraform() {
  dry_guard "terraform"
  local bin
  bin="$(ext_bin terraform)" || exit 1
  "$bin" "$@"
}

docker() {
  dry_guard "docker ${1:-}"
  local bin secs
  bin="$(ext_bin docker)" || exit 1
  case "${1:-}" in
    login) secs=120 ;;
    build | push) secs=1800 ;;
    *) secs=300 ;;
  esac
  run_bounded "$secs" "$bin" "$@"
}

curl() {
  dry_guard "curl"
  local bin
  bin="$(ext_bin curl)" || exit 1
  "$bin" "$@"
}

sbt() {
  dry_guard "sbt"
  die "these scripts never run sbt"
}

is_dry_run() {
  [[ "$SC_DRY_RUN" -eq 1 ]]
}

# dry_announce: the first stdout line of a dry run, before any check can refuse.
dry_announce() {
  if [[ "$SC_DRY_RUN" -eq 1 ]]; then
    printf 'DRY_RUN=yes\n'
  fi
}

# dry_banner ACTION EXTRA: the target as given in the arguments (sts is not
# called) and the digests, as --print-digest prints them. EXTRA may be an @skip:
# sentinel when the plan file or the build contexts are not available.
dry_banner() {
  local action="$1" extra="$2"
  printf 'TARGET_ACCOUNT=%s\n' "$SC_ACCOUNT"
  printf 'TARGET_REGION=%s\n' "$SC_REGION"
  printf 'TARGET_ENVIRONMENT=%s\n' "$SC_ENV"
  printf 'IDENTITY_CHECK=skipped (dry run: sts is not called, so the account above is the --account-id argument and not a verified caller identity)\n'
  printf 'ACTION=%s\n' "$action"
  printf 'SCRIPT_DIGEST=%s\n' "$(script_digest)"
  if [[ "$extra" == @skip:* ]]; then
    printf 'ARGS_DIGEST=unavailable (%s)\n' "${extra#@skip:}"
  else
    printf 'ARGS_CANONICAL=%s\n' "$(args_canonical "$action" "$extra")"
    printf 'ARGS_DIGEST=%s\n' "$(args_digest "$action" "$extra")"
  fi
}

# dry_step NAME: same numbering and name as the real step, marked not executed.
dry_step() {
  SC_STEP_N=$((SC_STEP_N + 1))
  SC_STEP_NAME="$1"
  printf 'STEP[%s] %s: dry-run\n' "$SC_STEP_N" "$1"
}

dry_would() {
  printf 'WOULD_RUN: %s\n' "$*"
}

dry_skipped() {
  printf 'CHECK_SKIPPED %s: %s (not done, so not passed)\n' "$1" "$2"
}

dry_finish() {
  SC_STEP_OPEN=0
  printf 'DRY_RUN_COMPLETE nothing was called or written: no aws, terraform, docker, curl or sbt, no plan, marker or output directory\n'
  exit 0
}

# dry_approval_unconsumed: after verify_approval in a dry run.
dry_approval_unconsumed() {
  printf 'APPROVAL_NOT_CONSUMED dry-run\n'
}

### Cloud identity and git ---------------------------------------------------

verify_identity() {
  local got
  got="$(aws sts get-caller-identity --query Account --output text)" || die "sts get-caller-identity failed: no usable credentials in the caller's environment"
  [[ "$got" == "$SC_ACCOUNT" ]] || die "caller account $got differs from approved account $SC_ACCOUNT"
  printf 'IDENTITY_OK account=%s region=%s\n' "$got" "$SC_REGION"
}

git_head() {
  git -C "$SC_REPO_ROOT" rev-parse --verify HEAD
}

tree_status() {
  git -C "$SC_REPO_ROOT" status --porcelain --untracked-files=normal
}

require_clean_tree() {
  local s
  s="$(tree_status)"
  if [[ -n "$s" ]]; then
    printf 'EVIDENCE: working tree is dirty; commit or stash first. git status --porcelain:\n%s\n' "$s" >&2
    exit 1
  fi
}

require_image_tag() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || die "image tag must be a full 40-character Git SHA, got: $1"
}

### Terraform ----------------------------------------------------------------

tf() {
  terraform -chdir="$SC_ENV_DIR" "$@"
}

tf_prepare() {
  step terraform-init
  tf init -input=false -no-color
  step_ok
  step terraform-fmt-check
  terraform fmt -check -recursive -no-color "$SC_TF_ROOT"
  step_ok
  step terraform-validate
  tf validate -no-color
  step_ok
}

# The key material patterns, shared by every scan (plan logs, inputs, manifests, the
# evidence archive): AWS access key ids, PEM private keys, aws_secret_access_key, an
# Authorization: Bearer header, and GitHub tokens (ghp_ and github_pat_ followed by
# at least 20 token characters).
SC_SECRET_RE='AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|aws_secret_access_key|[Aa]uthorization:[[:space:]]*[Bb]earer|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'

scan_for_secrets() {
  local f="$1"
  if grep -qE "$SC_SECRET_RE" "$f"; then
    printf 'EVIDENCE: possible credential material in %s on line(s): %s\n' "$f" \
      "$(grep -nE "$SC_SECRET_RE" "$f" | cut -d: -f1 | tr '\n' ' ')" >&2
    die "stopping: a secret may have reached the output (matched text is not printed)"
  fi
}

# tf_plan_to PLAN_FILE LOG_FILE ARGS...  Prints the full plan and tees it to LOG_FILE.
# Sets SC_PLAN_EXIT to 0 (no changes) or 2 (changes).
tf_plan_to() {
  local plan="$1" log="$2" rc=0
  shift 2
  set +e
  tf plan -input=false -lock-timeout=60s -detailed-exitcode -no-color -out="$plan" "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e
  if [[ "$rc" -ne 0 && "$rc" -ne 2 ]]; then
    die "terraform plan failed with exit $rc; output above and in $log"
  fi
  # Read by plan.sh (PLAN_EXIT in the plan metadata), which shellcheck does not
  # follow when it checks this file on its own.
  # shellcheck disable=SC2034
  SC_PLAN_EXIT="$rc"
}

STATEFUL_TYPES='aws_dynamodb_table|aws_s3_bucket|aws_s3vectors_vector_bucket|aws_s3vectors_index|aws_neptune_cluster|aws_neptune_cluster_instance|aws_secretsmanager_secret|aws_ecr_repository'

# plan_summary LOG_FILE. Prints counts, IAM changes, stateful destroys and
# replacements, and high-cost creations. Sets SC_STATEFUL_CHANGE to yes or no.
plan_summary() {
  local log="$1"
  printf 'PLAN_SUMMARY_BEGIN\n'
  grep -E '^(Plan:|No changes\.)' "$log" || printf 'PLAN_COUNTS: no "Plan:" line found\n'
  printf 'IAM_CHANGES:\n'
  grep -E '^[[:space:]]*# .*aws_iam_.* (will be|must be)' "$log" || printf '  none\n'
  printf 'HIGH_COST_CREATIONS (verify against infra/terraform/COST_ESTIMATE.md):\n'
  grep -E '^[[:space:]]*# .*(aws_nat_gateway|aws_vpc_endpoint|aws_neptune_cluster|aws_neptune_cluster_instance|aws_emr_|aws_ecs_service|aws_s3vectors_index)[^ ]* will be created' "$log" || printf '  none\n'
  printf 'STATEFUL_DESTROY_OR_REPLACE:\n'
  if grep -E "^[[:space:]]*# .*(${STATEFUL_TYPES})[^ ]* (will be destroyed|must be replaced|will be replaced)" "$log"; then
    SC_STATEFUL_CHANGE="yes"
  else
    printf '  none\n'
    SC_STATEFUL_CHANGE="no"
  fi
  printf 'STATEFUL_CHANGE=%s\n' "$SC_STATEFUL_CHANGE"
  printf 'PLAN_SUMMARY_END\n'
}

write_meta() {
  local f="$1"
  dry_guard "write_meta"
  shift
  (umask 077 && : > "$f")
  local kv
  for kv in "$@"; do
    printf '%s\n' "$kv" >> "$f"
  done
}

meta_get() {
  awk -v k="$2" '{ i = index($0, "="); if (substr($0, 1, i - 1) == k) { print substr($0, i + 1); exit } }' "$1"
}

# check_plan_binding PLAN_FILE EXPECTED_MODE REQUIRE_CLEAN(yes|no)
# Cross-checks the plan.meta written next to the plan. The approval record's
# PLAN_DIGEST is the binding that matters; this catches mix-ups early.
# Sets SC_PLAN_DIGEST and SC_PLAN_META.
check_plan_binding() {
  local plan="$1" mode="$2" clean="$3" meta
  [[ "$plan" == /* ]] || die "plan file must be an absolute path"
  [[ -f "$plan" ]] || die "plan file not found: $plan"
  meta="$plan.meta"
  [[ -f "$meta" ]] || die "plan metadata not found: $meta (plan.sh writes it)"
  # Read by deploy.sh and rollback.sh, which shellcheck does not follow when it
  # checks this file on its own.
  # shellcheck disable=SC2034
  SC_PLAN_META="$meta"
  SC_PLAN_DIGEST="$(sha256_file "$plan")"
  [[ "$(meta_get "$meta" META_VERSION)" == "1" ]] || die "unsupported plan metadata version"
  [[ "$(meta_get "$meta" MODE)" == "$mode" ]] || die "plan MODE is '$(meta_get "$meta" MODE)', expected '$mode'"
  [[ "$(meta_get "$meta" ENVIRONMENT)" == "$SC_ENV" ]] || die "plan was made for another environment"
  [[ "$(meta_get "$meta" ACCOUNT_ID)" == "$SC_ACCOUNT" ]] || die "plan was made for another account"
  [[ "$(meta_get "$meta" REGION)" == "$SC_REGION" ]] || die "plan was made for another region"
  [[ "$(meta_get "$meta" PLAN_DIGEST)" == "$SC_PLAN_DIGEST" ]] || die "plan file digest differs from plan.meta: the plan file changed after it was made"
  if [[ "$clean" == "yes" ]]; then
    [[ "$(meta_get "$meta" DIRTY)" == "no" ]] || die "the plan was made from a dirty tree; remake it from a clean commit"
    [[ "$(meta_get "$meta" GIT_SHA)" == "$(git_head)" ]] || die "plan GIT_SHA differs from HEAD $(git_head)"
  fi
  printf 'PLAN_BOUND file=%s digest=%s mode=%s git_sha=%s stateful_change=%s\n' \
    "$plan" "$SC_PLAN_DIGEST" "$mode" "$(meta_get "$meta" GIT_SHA)" "$(meta_get "$meta" STATEFUL_CHANGE)"
}

# tf_apply_saved PLAN_FILE LOG_FILE. A saved plan applies without a prompt and
# fails if the state moved since the plan was made.
tf_apply_saved() {
  local plan="$1" log="$2" rc=0
  set +e
  tf apply -input=false -lock-timeout=60s -no-color "$plan" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e
  [[ "$rc" -eq 0 ]] || die "terraform apply of the saved plan failed with exit $rc; output in $log"
  scan_for_secrets "$log"
}

### Deployed revision --------------------------------------------------------

# verify_revision TAG. Every ACTIVE task definition family of the environment,
# and every running service's task definition, must use only :TAG images, and
# both ECR repositories must hold TAG.
verify_revision() {
  local tag="$1" repo digest fams fam imgs img svcs svc td bad=0 seen=0 cluster
  for repo in services loader; do
    digest="$(aws ecr describe-images --repository-name "${SC_NAME_PREFIX}-${repo}" \
      --image-ids "imageTag=${tag}" --query 'imageDetails[0].imageDigest' --output text)" \
      || die "ECR repository ${SC_NAME_PREFIX}-${repo} has no image tagged ${tag}"
    printf 'IMAGE_PRESENT repository=%s tag=%s digest=%s\n' "${SC_NAME_PREFIX}-${repo}" "$tag" "$digest"
  done

  fams="$(aws ecs list-task-definition-families --family-prefix "$SC_NAME_PREFIX" \
    --status ACTIVE --query 'families[]' --output text)"
  [[ -n "$fams" && "$fams" != "None" ]] || die "no ACTIVE task definition family with prefix $SC_NAME_PREFIX"
  for fam in $fams; do
    imgs="$(aws ecs describe-task-definition --task-definition "$fam" \
      --query 'taskDefinition.containerDefinitions[].image' --output text)"
    for img in $imgs; do
      seen=$((seen + 1))
      if [[ "$img" == *":${tag}" ]]; then
        printf 'REVISION_OK family=%s image=%s\n' "$fam" "$img"
      else
        printf 'REVISION_MISMATCH family=%s image=%s expected_tag=%s\n' "$fam" "$img" "$tag" >&2
        bad=1
      fi
    done
  done

  cluster="${SC_NAME_PREFIX}-cluster"
  svcs="$(aws ecs list-services --cluster "$cluster" --query 'serviceArns[]' --output text)"
  if [[ -n "$svcs" && "$svcs" != "None" ]]; then
    for svc in $svcs; do
      td="$(aws ecs describe-services --cluster "$cluster" --services "$svc" \
        --query 'services[0].taskDefinition' --output text)"
      imgs="$(aws ecs describe-task-definition --task-definition "$td" \
        --query 'taskDefinition.containerDefinitions[].image' --output text)"
      for img in $imgs; do
        seen=$((seen + 1))
        if [[ "$img" == *":${tag}" ]]; then
          printf 'SERVICE_REVISION_OK service=%s image=%s\n' "${svc##*/}" "$img"
        else
          printf 'SERVICE_REVISION_MISMATCH service=%s image=%s expected_tag=%s\n' "${svc##*/}" "$img" "$tag" >&2
          bad=1
        fi
      done
    done
  fi
  [[ "$seen" -gt 0 ]] || die "no container image found to verify"
  [[ "$bad" -eq 0 ]] || die "deployed revision does not match $tag"
  printf 'DEPLOYED_REVISION=%s\n' "$tag"
}

### Added in Phase 13 for check.sh, package.sh, run-experiment.sh and ---------
### archive-evidence.sh. Work order 15 later edited run_bounded and scan_for_secrets above. --

utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

utc_stamp() {
  date -u +%Y%m%dT%H%M%SZ
}

# sbt_run SECONDS ARGS... The one way a script runs sbt. The sbt function above
# still refuses, so the six older scripts cannot reach it. Refused in a dry run,
# bounded by run_bounded, and standard input is /dev/null so sbt never waits for a
# prompt. Returns sbt's status, or 124 on a timeout.
sbt_run() {
  local secs="$1" bin
  shift
  dry_guard "sbt_run"
  bin="$(ext_bin sbt)" || exit 1
  run_bounded "$secs" "$bin" "$@" </dev/null
}

# require_external_dir PATH WHAT: PATH must be absolute, free of . and .. segments,
# resolvable, and outside the repository by physical path (letter case ignored).
# A check at one moment, with the limits listed in the header of this file.
require_external_dir() {
  local p="$1" what="$2"
  [[ "$p" == /* ]] || die "$what must be an absolute path: $p"
  case "$p/" in
    */../* | */./*) die "$what must not contain . or .. segments: $p" ;;
  esac
  if path_within "$p" "$SC_REPO_ROOT"; then
    die "$what must not be inside the repository ($SC_REPO_ROOT): $p"
  fi
  [[ -n "$(physical_path "$p")" ]] || die "$what cannot be resolved to a physical path: $p"
}

# file_mtime_iso FILE: the modification time as UTC ISO 8601. GNU stat first, then
# BSD (macOS). Reads metadata only, never the content.
file_mtime_iso() {
  local f="$1" e
  e="$(stat -c %Y "$f" 2>/dev/null)" || e="$(stat -f %m "$f" 2>/dev/null)" || return 1
  [[ "$e" =~ ^[0-9]+$ ]] || return 1
  date -u -d "@$e" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$e" +%Y-%m-%dT%H:%M:%SZ
}

# safe_name TEXT: TEXT with every character outside A-Za-z0-9._- replaced by _.
safe_name() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'
}

# csv_words TEXT: a comma list as space separated words, for a for loop.
csv_words() {
  printf '%s' "$1" | tr ',' ' '
}

### Human invocation guard (cleanup) -----------------------------------------
# An agreement, not a security control: any process can pass the flag.

require_human() {
  local flag="$1"
  [[ "$flag" == "yes" ]] || die "this script requires --i-am-the-human: an explicit human invocation"
  if [[ -n "${SCHOLARCHAIN_AGENT_RUN:-}" ]]; then
    die "SCHOLARCHAIN_AGENT_RUN is set: agent-run invocation refused"
  fi
  if [[ ! -t 0 ]] && { [[ -n "${CLAUDECODE:-}" ]] || [[ -n "${CLAUDE_CODE_ENTRYPOINT:-}" ]]; }; then
    die "stdin is not a terminal and an agent environment is detected: run this in your own terminal"
  fi
}
