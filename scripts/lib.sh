# shellcheck shell=bash
# Shared functions for the ScholarChain operational scripts. Source it, never
# run it. The calling script sets SC_SCRIPT (its own path) before sourcing.
#
# Contract of every script that sources this file:
#   - bash strict mode, no prompts, greppable markers STEP[n] name: start|ok|skip|FAIL
#   - region is us-east-1 or the script refuses
#   - account id, environment and cost ceiling are explicit parameters
#   - the approval record (see verify_approval) is verified, never bypassed
#   - no secret value is read, printed or placed in an argument list, except in
#     smoke-test.sh, which reads one bearer token into a private temp file
#
# What the approval checks do NOT stop (they are consistency checks, not
# authorization; the real control is the human signing the digest and, in Phase
# 13, a ratified launcher):
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
SC_APPROVED_REGION="us-east-1"
SC_PROJECT_CEILING_USD="25"
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
  --region us-east-1        optional; any other value is refused
  --cost-ceiling-usd N      project cost ceiling, 0 < N <= 25; must equal the approval record
  --approval-record PATH    human approval record (see scripts/README.md); not used by plan.sh
  --out-dir ABS_PATH        optional; default ${TMPDIR:-/tmp}/scholarchain-ops/<environment>
  --print-digest            print SCRIPT_DIGEST and ARGS_DIGEST for the record, then exit
EOF
}

require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "required command not found: $c"
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
  [[ "$SC_REGION" == "$SC_APPROVED_REGION" ]] || die "region must be $SC_APPROVED_REGION, got: $SC_REGION"
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
verify_approval() {
  local action="$1" extra="$2" plan="$3" sha="$4"
  local key v now lower_by

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
  [[ "$(approval_value REGION)" == "$SC_APPROVED_REGION" ]] || die "record REGION is not $SC_APPROVED_REGION"

  v="$(approval_value COST_CEILING_USD)"
  validate_number "$v"
  num_eq "$v" "$SC_COST" || die "record COST_CEILING_USD $v differs from --cost-ceiling-usd $SC_COST"
  num_le "$v" "$SC_PROJECT_CEILING_USD" || die "record COST_CEILING_USD exceeds the project ceiling"

  [[ "$(approval_value SCRIPT_DIGEST)" == "$(script_digest)" ]] || die "record SCRIPT_DIGEST does not match this script plus lib.sh: the script changed after approval"
  [[ "$(approval_value ARGS_DIGEST)" == "$(args_digest "$action" "$extra")" ]] || die "record ARGS_DIGEST does not match these exact arguments"
  [[ "$(approval_value PLAN_DIGEST)" == "$plan" ]] || die "record PLAN_DIGEST does not match: expected $plan"
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

  printf 'APPROVAL_VERIFIED action=%s environment=%s account=%s region=%s cost_ceiling_usd=%s approved_by=%s expires_at=%s\n' \
    "$action" "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION" "$SC_COST" "$(approval_value APPROVED_BY)" "$(approval_value EXPIRES_AT)"
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

scan_for_secrets() {
  local f="$1"
  if grep -qE 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|aws_secret_access_key' "$f"; then
    printf 'EVIDENCE: possible credential material in %s on line(s): %s\n' "$f" \
      "$(grep -nE 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|aws_secret_access_key' "$f" | cut -d: -f1 | tr '\n' ' ')" >&2
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
