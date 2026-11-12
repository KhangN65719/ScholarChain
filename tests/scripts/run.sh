#!/usr/bin/env bash
# tests/scripts/run.sh: local harness for the ten operational scripts under scripts/.
#
#   bash tests/scripts/run.sh
#
# Prints one line per case, "CASE <id> PASS|FAIL <reason>" (SKIPPED where a case
# cannot be exercised), then "RESULT pass=N fail=M". Exits 0 only when fail=0.
#
# Safe on the real repository and on a laptop with no AWS credentials and no
# network. It works in a fresh mktemp -d directory: it copies scripts/ and the files
# the scripts read into a temporary git repository with one commit, runs the copy,
# and removes only the directory it made. It never writes to the real repository
# and never to $HOME. aws, terraform, docker, curl, sbt and java are logging stubs
# that append to a tripwire file and exit 1, and the real binaries are not on PATH.
#
# Owner: test-engineer (tests/**). Never edit scripts/** from here: a failing case
# is a finding for the script's owner, not a case to loosen.
#
# Environment (optional): SC_HARNESS_KEEP=1 keeps the temp directory and prints its
# path on stderr; SC_HARNESS_RUN_TIMEOUT=N changes the per-run limit (default 120 s).
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_ROOT="$(cd "$HERE/../.." && pwd)"
KEEP="${SC_HARNESS_KEEP:-0}"
RUN_TIMEOUT="${SC_HARNESS_RUN_TIMEOUT:-120}"
PASS=0
FAIL=0
SKIP=0
WORK=""
TMP_PHYS=""

prereq_fail() {
  printf 'PREREQ %s\n' "$*" >&2
  exit 2
}

cleanup() {
  if [ -n "$WORK" ] && [ -n "$TMP_PHYS" ]; then
    if [ "$KEEP" = "1" ]; then
      printf 'KEPT temp directory: %s\n' "$WORK" >&2
    else
      case "$WORK" in
        "$TMP_PHYS"/scholarchain-scripts-harness.?*)
          chmod -R u+w "$WORK" 2>/dev/null
          rm -rf "$WORK"
          ;;
      esac
    fi
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

[ -f "$REAL_ROOT/scripts/lib.sh" ] || prereq_fail "scripts/lib.sh not found under $REAL_ROOT"

REAL_STATUS_BEFORE="$(git -C "$REAL_ROOT" status --porcelain 2>/dev/null)"

### Temp directory and isolated tool path -------------------------------------

TMP_ROOT="${TMPDIR:-/tmp}"
TMP_ROOT="${TMP_ROOT%/}"
[ -n "$TMP_ROOT" ] || TMP_ROOT="/tmp"
[ -d "$TMP_ROOT" ] || prereq_fail "temp root is not a directory: $TMP_ROOT"
TMP_PHYS="$(cd "$TMP_ROOT" && pwd -P)" || prereq_fail "temp root cannot be entered: $TMP_ROOT"
WORK="$(mktemp -d "$TMP_PHYS/scholarchain-scripts-harness.XXXXXX")" || prereq_fail "mktemp -d failed under $TMP_PHYS"
WORK="$(cd "$WORK" && pwd -P)"
case "$WORK" in
  "$TMP_PHYS"/scholarchain-scripts-harness.?*) ;;
  *) prereq_fail "unexpected temp path: $WORK" ;;
esac
case "$WORK" in
  *[[:space:]]*) prereq_fail "the temp path holds a space, which the manifest values cannot: $WORK" ;;
esac

REPO="$WORK/repo"
STATE="$WORK/state"
OUT_BASE="$WORK/out"
BIN="$WORK/bin"
BIN2="$WORK/bin2"
TOOLDIR="$WORK/tools"
REC="$WORK/rec"
LOGDIR="$WORK/log"
EXP_OUT="$WORK/exp-out"
CWD="$WORK/cwd"
TRIP="$WORK/tripwire.log"
TRIP_ALL="$WORK/tripwire-all.log"
ALL_OUT="$WORK/all-output.txt"
mkdir -p "$REPO" "$STATE" "$OUT_BASE" "$BIN" "$BIN2" "$TOOLDIR" "$REC" "$LOGDIR" "$CWD" "$WORK/tmp" "$WORK/xdg" "$REC/plan"
: > "$TRIP"
: > "$TRIP_ALL"
: > "$ALL_OUT"

# Wrappers (not symlinks) for the tools the scripts and this harness need, so the
# PATH holds no aws, terraform, docker, curl, sbt, java or shellcheck of the machine.
TOOLS="awk basename bash cat chmod cmp cp cut date diff dirname env expr find git grep head id ln ls mkdir mktemp mv od python3 readlink rm sed sh shasum sha256sum sleep sort stat tail tee touch tr uname wc xargs"
for t in $TOOLS; do
  p="$(type -P "$t" 2>/dev/null)" || p=""
  [ -n "$p" ] || continue
  printf '#!/bin/sh\nexec "%s" "$@"\n' "$p" > "$TOOLDIR/$t"
  chmod 755 "$TOOLDIR/$t"
done
for t in awk basename bash cat chmod cp cut date dirname env find git grep head ln mkdir mktemp mv python3 rm sed sh sort tail tee touch tr wc; do
  [ -x "$TOOLDIR/$t" ] || prereq_fail "required tool not found on PATH: $t"
done
[ -x "$TOOLDIR/shasum" ] || [ -x "$TOOLDIR/sha256sum" ] || prereq_fail "neither shasum nor sha256sum found"

make_stub() {
  printf '#!/bin/sh\nprintf "%%s %%s\\n" "%s" "$*" >> "%s"\nexit 1\n' "$2" "$TRIP" > "$1/$2"
  chmod 755 "$1/$2"
}
for t in aws terraform docker curl sbt java; do
  make_stub "$BIN" "$t"
done
for t in aws docker curl sbt; do
  make_stub "$BIN2" "$t"
done
PATH1="$BIN:$TOOLDIR"
PATH2="$BIN2:$TOOLDIR"
export PATH="$PATH1"

export TMPDIR="$WORK/tmp"
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1
export XDG_CONFIG_HOME="$WORK/xdg"
export AWS_CONFIG_FILE=/dev/null
export AWS_SHARED_CREDENTIALS_FILE=/dev/null
export SCHOLARCHAIN_OPS_STATE_DIR="$STATE"
unset SCHOLARCHAIN_AGENT_RUN CLAUDECODE CLAUDE_CODE_ENTRYPOINT
unset AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
cd "$CWD" || prereq_fail "cannot enter $CWD"

ACCT="123456789012"
NOW="$(date +%s)"
ZERO64="$(printf '%064d' 0)"
ZERO40="$(printf '%040d' 0)"
CANARY="CANARY$NOW$$"
FAKE_AKIA="AKIA""IOSFODNN7EXAMPLE"
FAKE_PEM="-----BEGIN RSA PRIV""ATE KEY-----"
FAKE_VAR="aws_secret_""access_key"
FAKE_BEARER="Authorization: Bear""er abc"
FAKE_GHP="ghp_""abcdefghijklmnopqrstuvwxyz0123456789"
FAKE_GHPAT="github_pat_""abcdefghijklmnopqrstuvwxyz0123"

### Helpers --------------------------------------------------------------------

iso_from_epoch() {
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}

stamp_from_epoch() {
  date -u -d "@$1" +%Y%m%dT%H%M%SZ 2>/dev/null || date -u -r "$1" +%Y%m%dT%H%M%SZ
}

sha_of() {
  if [ -x "$TOOLDIR/shasum" ]; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

RUN_N=0
OUT=""
ERR=""
RC=0

# run CMD...: runs with stdin from /dev/null under a watchdog. Sets OUT, ERR, RC and
# truncates the tripwire first, so a_trip_empty speaks for this run only.
run() {
  local pid wd
  RUN_N=$((RUN_N + 1))
  OUT="$LOGDIR/run-$RUN_N.out"
  ERR="$LOGDIR/run-$RUN_N.err"
  : > "$TRIP"
  "$@" > "$OUT" 2> "$ERR" < /dev/null &
  pid=$!
  ( sp=""; trap 'kill "$sp" 2>/dev/null; exit 0' TERM; sleep "$RUN_TIMEOUT" & sp=$!; wait "$sp"; kill -TERM "$pid" 2>/dev/null ) > /dev/null 2>&1 &
  wd=$!
  wait "$pid"
  RC=$?
  kill "$wd" 2>/dev/null
  wait "$wd" 2>/dev/null
  cat "$TRIP" >> "$TRIP_ALL"
  cat "$OUT" "$ERR" >> "$ALL_OUT"
}

SC() {
  local name="$1"
  shift
  run bash "$REPO/scripts/$name.sh" "$@"
}

run_build() {
  run bash "$REPO/scripts/$SN.sh" "${A[@]}"
}

run_build_env() {
  run env "$1" bash "$REPO/scripts/$SN.sh" "${A[@]}"
}

C_ID=""
C_WHY=""

case_begin() {
  C_ID="$1"
  C_WHY=""
}

note_fail() {
  C_WHY="${C_WHY}${C_WHY:+; }$1"
}

case_end() {
  local d
  if [ -z "$C_WHY" ]; then
    printf 'CASE %s PASS ok\n' "$C_ID"
    PASS=$((PASS + 1))
  else
    d="rc=$RC"
    if [ -s "$ERR" ]; then
      d="$d err1=$(head -n 1 "$ERR" | cut -c1-140)"
    fi
    printf 'CASE %s FAIL %s [%s]\n' "$C_ID" "$C_WHY" "$d"
    FAIL=$((FAIL + 1))
  fi
}

case_skipped() {
  printf 'CASE %s SKIPPED %s\n' "$1" "$2"
  SKIP=$((SKIP + 1))
}

a_rc() {
  [ "$RC" -eq "$1" ] || note_fail "exit $RC, expected $1"
}

a_rc_nonzero() {
  [ "$RC" -ne 0 ] || note_fail "exit 0, expected a refusal"
}

a_out() {
  grep -qF -- "$1" "$OUT" || note_fail "stdout lacks: $1"
}

a_out_re() {
  grep -qE -- "$1" "$OUT" || note_fail "stdout lacks match: $1"
}

a_not_out() {
  if grep -qF -- "$1" "$OUT"; then note_fail "stdout holds: $1"; fi
}

a_not_out_re() {
  if grep -qE -- "$1" "$OUT"; then note_fail "stdout matches: $1"; fi
}

a_err() {
  grep -qF -- "$1" "$ERR" || note_fail "stderr lacks: $1"
}

a_err_evidence() {
  grep -q '^EVIDENCE:' "$ERR" || note_fail "no EVIDENCE: line on stderr"
}

a_first_line() {
  [ "$(head -n 1 "$OUT")" = "$1" ] || note_fail "first stdout line is not $1"
}

# a_order FRAGMENT...: every fragment is on stdout and the first line of each comes
# after the first line of the one before it.
a_order() {
  local prev=0 n frag
  for frag in "$@"; do
    n="$(grep -nF -- "$frag" "$OUT" | head -n 1 | cut -d: -f1)"
    if [ -z "$n" ]; then
      note_fail "stdout lacks (order check): $frag"
      return 0
    fi
    if [ "$n" -le "$prev" ]; then note_fail "out of order: $frag"; fi
    prev="$n"
  done
}

a_trip_empty() {
  if [ -s "$TRIP" ]; then note_fail "stub called: $(head -n 1 "$TRIP" | cut -c1-120)"; fi
}

a_state_empty() {
  if [ -n "$(find "$STATE" -mindepth 1 2>/dev/null | head -n 1)" ]; then note_fail "the default marker directory is not empty"; fi
}

a_refused() {
  a_rc_nonzero
  a_err_evidence
  if [ -n "${1:-}" ]; then a_err "$1"; fi
  a_not_out "APPROVAL_VERIFIED"
  a_not_out "DRY_RUN_COMPLETE"
  a_trip_empty
}

a_dry_common() {
  a_rc 0
  a_first_line "DRY_RUN=yes"
  a_out_re '^STEP\[[0-9]+\] .*: dry-run$'
  a_out "DRY_RUN_COMPLETE"
  a_trip_empty
}

a_targets() {
  a_out "TARGET_ACCOUNT=$ACCT"
  a_out "TARGET_REGION=us-east-1"
  a_out "TARGET_ENVIRONMENT=course"
}

is_hex64() {
  printf '%s' "$1" | grep -qE '^[0-9a-f]{64}$'
}

dirty_on() {
  printf 'x\n' > "$REPO/dirty-marker.txt"
}

dirty_off() {
  rm -f "$REPO/dirty-marker.txt"
}

### Approval record writer -----------------------------------------------------

R_ACTION=""
R_SDIG=""
R_ADIG=""
R_PDIG=""
R_GSHA=""
R_VERSION=1
R_ENV=course
R_ACCT="$ACCT"
R_REGION=us-east-1
R_COST=25
R_STATEFUL=no
R_BY="harness-operator"
R_AT=""
R_EXP=""
R_EXTRA_LINES=""
R_OMIT=""
R_DUP=""

rec_base() {
  R_ACTION="$1"
  R_SDIG="$2"
  R_ADIG="$3"
  R_PDIG="$4"
  R_GSHA="$5"
  R_VERSION=1
  R_ENV=course
  R_ACCT="$ACCT"
  R_REGION=us-east-1
  R_COST=25
  R_STATEFUL=no
  R_BY="harness-operator"
  R_AT="$(iso_from_epoch $((NOW - 120)))"
  R_EXP="$(iso_from_epoch $((NOW + 72000)))"
  R_EXTRA_LINES=""
  R_OMIT=""
  R_DUP=""
}

rec_write() {
  local f="$1" k v
  {
    printf '# harness approval record, canary %s\n' "$CANARY"
    for k in APPROVAL_VERSION ACTION ENVIRONMENT ACCOUNT_ID REGION COST_CEILING_USD PLAN_DIGEST SCRIPT_DIGEST ARGS_DIGEST GIT_SHA STATEFUL_CHANGE_ACKNOWLEDGED APPROVED_BY APPROVED_AT EXPIRES_AT; do
      case "$k" in
        APPROVAL_VERSION) v="$R_VERSION" ;;
        ACTION) v="$R_ACTION" ;;
        ENVIRONMENT) v="$R_ENV" ;;
        ACCOUNT_ID) v="$R_ACCT" ;;
        REGION) v="$R_REGION" ;;
        COST_CEILING_USD) v="$R_COST" ;;
        PLAN_DIGEST) v="$R_PDIG" ;;
        SCRIPT_DIGEST) v="$R_SDIG" ;;
        ARGS_DIGEST) v="$R_ADIG" ;;
        GIT_SHA) v="$R_GSHA" ;;
        STATEFUL_CHANGE_ACKNOWLEDGED) v="$R_STATEFUL" ;;
        APPROVED_BY) v="$R_BY" ;;
        APPROVED_AT) v="$R_AT" ;;
        EXPIRES_AT) v="$R_EXP" ;;
      esac
      if [ "$k" = "$R_OMIT" ]; then continue; fi
      printf '%s=%s\n' "$k" "$v"
      if [ "$k" = "$R_DUP" ]; then printf '%s=%s\n' "$k" "$v"; fi
    done
    if [ -n "$R_EXTRA_LINES" ]; then printf '%s\n' "$R_EXTRA_LINES"; fi
  } > "$f"
}

### Fixtures -------------------------------------------------------------------

# make_plan FILE MODE TAG [STATEFUL]: STATEFUL is yes or no (default no) and becomes the
# STATEFUL_CHANGE line of the plan metadata.
make_plan() {
  local f="$1" mode="$2" tag="$3" stateful="${4:-no}" d
  printf 'harness fake plan %s\n' "$mode" > "$f"
  if [ "$stateful" = "yes" ]; then printf 'harness fake plan: stateful change\n' >> "$f"; fi
  d="$(sha_of "$f")"
  {
    printf 'META_VERSION=1\nMODE=%s\nENVIRONMENT=course\nACCOUNT_ID=%s\nREGION=us-east-1\n' "$mode" "$ACCT"
    printf 'GIT_SHA=%s\nDIRTY=no\nIMAGE_TAG=%s\nCPU_ARCHITECTURE=X86_64\nCOST_CEILING_USD=25\n' "$HEAD_SHA" "$tag"
    printf 'PLAN_EXIT=2\nSTATEFUL_CHANGE=%s\nPLAN_DIGEST=%s\nCREATED_AT=%s\n' "$stateful" "$d" "$(iso_from_epoch "$NOW")"
  } > "$f.meta"
  printf '%s' "$d"
}

setup_repo() {
  local f
  mkdir -p "$REPO/scripts"
  cp "$REAL_ROOT"/scripts/*.sh "$REPO/scripts/" || return 1
  (cd "$REAL_ROOT" && find infra/terraform -name .terraform -prune -o -type f \( -name '*.tf' -o -name '*.tfvars' -o -name '.terraform.lock.hcl' \) -print) | while IFS= read -r f; do
    mkdir -p "$REPO/$(dirname "$f")"
    cp "$REAL_ROOT/$f" "$REPO/$f"
  done
  [ -f "$REPO/infra/terraform/environments/course/course.tfvars" ] || return 1
  mkdir -p "$REPO/infra/terraform/environments/staging"
  cp "$REPO/infra/terraform/environments/course/course.tfvars" "$REPO/infra/terraform/environments/staging/staging.tfvars"
  printf 'FROM scratch\n' > "$REPO/Dockerfile"
  mkdir -p "$REPO/project"
  printf 'sbt.version=1.10.0\n' > "$REPO/project/build.properties"
  mkdir -p "$REPO/.claude/hooks"
  printf '{}\n' > "$REPO/.claude/settings.json"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$REPO/.claude/hooks/ok.sh"
  printf '{}\n' > "$REPO/.mcp.json"
  mkdir -p "$REPO/experiments/manifests" "$REPO/experiments/harness"
  cp "$REAL_ROOT"/experiments/manifests/*.manifest "$REPO/experiments/manifests/" 2>/dev/null
  printf '#!/usr/bin/env bash\nexit 0\n' > "$REPO/experiments/harness/run.sh"
  git -C "$REPO" init -q || return 1
  git -C "$REPO" symbolic-ref HEAD refs/heads/main || return 1
  git -C "$REPO" add -A || return 1
  git -C "$REPO" -c user.name=harness -c user.email=harness@example.invalid -c commit.gpgsign=false commit -q -m harness || return 1
  HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
  [ -z "$(git -C "$REPO" status --porcelain)" ] || return 1
  return 0
}

HEAD_SHA=""
setup_repo || prereq_fail "could not build the temporary repository under $REPO"
[ -n "$HEAD_SHA" ] || prereq_fail "no HEAD in the temporary repository"

PLAN_F="$REC/plan/course-${HEAD_SHA:0:12}.tfplan"
PLAN_DIGEST="$(make_plan "$PLAN_F" apply "$HEAD_SHA")"
PLAN_FLIP="$REC/plan/course-cleanup-flip.tfplan"
PLAN_FLIP_DIGEST="$(make_plan "$PLAN_FLIP" cleanup-flip "$HEAD_SHA")"
PLAN_DESTROY="$REC/plan/course-cleanup-destroy.tfplan"
PLAN_DESTROY_DIGEST="$(make_plan "$PLAN_DESTROY" cleanup-destroy "$HEAD_SHA")"
PLAN_STATEFUL="$REC/plan/course-stateful.tfplan"
PLAN_STATEFUL_DIGEST="$(make_plan "$PLAN_STATEFUL" apply "$HEAD_SHA" yes)"
PLAN_MISSING="$REC/plan/missing.tfplan"
PLAN_OVERRIDE=""
CTX_S="$REC/ctx-services"
CTX_L="$REC/ctx-loader"
mkdir -p "$CTX_S/lib" "$CTX_L/lib"
printf 'services\n' > "$CTX_S/lib/a.jar"
printf 'loader\n' > "$CTX_L/lib/b.jar"
PIPE_IN="$REC/pipeline-input.json"
printf '{"hadoop_args":[],"spark_args":[],"loader_command":[]}\n' > "$PIPE_IN"
INCL_DIR="$REC/incl"
mkdir -p "$INCL_DIR"
printf 'RUN_ID=harness\n' > "$INCL_DIR/run-record.env"
VREC="$REC/variant.env"
MF_BASE="$REC/base.manifest"
cat > "$MF_BASE" <<EOF
MANIFEST_VERSION=1
EXPERIMENT_ID=A
DESCRIPTION=harness-tier-s-manifest
ENVIRONMENT=course
REGION=us-east-1
ACCOUNT_ID=$ACCT
DATASET_SNAPSHOT=harness-slice
SIZE_TIER=S
MAX_MINUTES=30
MAX_CONCURRENCY=2
MAX_TASKS=200
SPEND_CEILING_USD=0.50
HOURLY_BURN_USD=0.60
STOP_CONDITIONS=wall-clock,first-failure
METRIC_NAMES=ExecutionTime,ExecutionsFailed
METRIC_NAMESPACES=AWS/States
OUTPUT_ROOT=$EXP_OUT
ENTRY_POINT=script:experiments/harness/run.sh
EOF

common_args() {
  CA=(--environment course --account-id "$1" --region "$2" --cost-ceiling-usd "$3")
}

A=()
CA=()
SN=""
AN=""
GS=""
PD=""
ST=""
KEY=""
D_SCRIPT=""
D_ARGS=""
EVDIR=""

# build KEY MODE RECORD [ACCT] [COST] [REGION]: fills A with a valid argument list for
# one script (MODE dry or digest; RECORD is "-" to leave --approval-record out) and sets
# SN (script name), AN (approval action), GS (record GIT_SHA), PD (record PLAN_DIGEST)
# and ST (record STATEFUL_CHANGE_ACKNOWLEDGED).
build() {
  local key="$1" mode="$2" rec="$3" acct="${4:-$ACCT}" cost="${5:-25}" region="${6:-us-east-1}"
  local flag="--dry-run" pf stop_on
  if [ "$mode" = "digest" ]; then flag="--print-digest"; fi
  common_args "$acct" "$region" "$cost"
  ST=no
  GS=n/a
  PD=n/a
  case "$key" in
    plan)
      SN=plan; AN=plan
      A=("$flag" "${CA[@]}" --out-dir "$OUT_BASE/plan") ;;
    deploy)
      SN=deploy; AN=deploy; GS="$HEAD_SHA"; PD="$PLAN_DIGEST"
      pf="${PLAN_OVERRIDE:-$PLAN_F}"
      A=("$flag" "${CA[@]}" --plan-file "$pf" --services-context "$CTX_S" --loader-context "$CTX_L") ;;
    rollback-image)
      SN=rollback; AN=rollback; GS="$HEAD_SHA"; PD="$PLAN_DIGEST"
      pf="${PLAN_OVERRIDE:-$PLAN_F}"
      A=("$flag" "${CA[@]}" --image-tag "$HEAD_SHA" --plan-file "$pf") ;;
    rollback-state)
      SN=rollback; AN=rollback
      A=("$flag" "${CA[@]}" --state-bucket scholarchain-harness-state --state-key env/course/terraform.tfstate --state-version-id v1.2-abc) ;;
    smoke)
      SN=smoke-test; AN=smoke-test
      A=("$flag" "${CA[@]}" --out-dir "$OUT_BASE/smoke" --fast-url http://10.20.0.10:8080 --slow-url http://10.20.0.11:8081 --query-secret-arn "arn:aws:secretsmanager:us-east-1:${acct}:secret:scholarchain/query-token-AbCdEf" --bedrock off) ;;
    pipeline)
      SN=run-bounded-pipeline; AN=pipeline
      stop_on="first-failure,dlq-nonempty"
      A=("$flag" "${CA[@]}" --size small --max-minutes 60 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on "$stop_on" --input-file "$PIPE_IN") ;;
    cleanup-flip)
      SN=cleanup; AN=cleanup; PD="$PLAN_FLIP_DIGEST"
      pf="${PLAN_OVERRIDE:-$PLAN_FLIP}"
      A=("$flag" "${CA[@]}" --mode apply-flip --plan-file "$pf" --i-am-the-human) ;;
    cleanup-destroy)
      SN=cleanup; AN=cleanup; PD="$PLAN_DESTROY_DIGEST"; ST=yes
      pf="${PLAN_OVERRIDE:-$PLAN_DESTROY}"
      A=("$flag" "${CA[@]}" --mode apply-destroy --plan-file "$pf" --acknowledge-data-loss --i-am-the-human) ;;
    experiment)
      SN=run-experiment; AN=experiment; GS="$HEAD_SHA"
      A=("$flag" --manifest "$MF_BASE" "${CA[@]}") ;;
    archive-upload)
      SN=archive-evidence; AN=archive
      A=("$flag" --upload-dir "$EVDIR" --s3-prefix s3://harness-evidence-bucket/prefix "${CA[@]}") ;;
    *)
      printf 'harness bug: unknown key %s\n' "$key" >&2
      exit 2 ;;
  esac
  if [ "$mode" = "dry" ] && [ "$rec" != "-" ]; then
    A=("${A[@]}" --approval-record "$rec")
  fi
}

# prep_key KEY: runs --print-digest and keeps the digests the script itself prints.
prep_key() {
  build "$1" digest -
  run_build
  D_SCRIPT="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
  D_ARGS="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
}

fresh() {
  rec_base "$AN" "$D_SCRIPT" "$D_ARGS" "$PD" "$GS"
  R_STATEFUL="$ST"
}

consume_with_lib() {
  (
    export SCHOLARCHAIN_OPS_STATE_DIR="$2"
    # SC_SCRIPT is read by script_digest in the sourced lib.sh, which shellcheck does not follow here.
    # shellcheck disable=SC2034
    SC_SCRIPT="$REPO/scripts/rollback.sh"
    # shellcheck source=/dev/null
    source "$REPO/scripts/lib.sh"
    # SC_APPROVAL_FILE is read by consumed_dir and approval_value in the sourced lib.sh.
    # shellcheck disable=SC2034
    SC_APPROVAL_FILE="$1"
    # SC_ENV is read by consume_approval in the sourced lib.sh.
    # shellcheck disable=SC2034
    SC_ENV=course
    consume_approval "$3"
  ) > "$LOGDIR/consume.out" 2> "$LOGDIR/consume.err" < /dev/null
}

KEYS="deploy rollback-image rollback-state smoke pipeline cleanup-flip cleanup-destroy experiment archive-upload"
SCRIPT_NAMES="plan deploy rollback smoke-test run-bounded-pipeline cleanup check package run-experiment archive-evidence"

### Controls: the harness itself -----------------------------------------------

case_begin "0.stubs-first-on-path"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0; : > "$OUT"; : > "$ERR"
for t in aws terraform docker curl sbt; do
  [ "$(type -P "$t")" = "$BIN/$t" ] || note_fail "$t does not resolve to the stub"
done
case_end

case_begin "0.shellcheck-hidden"
if type -P shellcheck > /dev/null 2>&1; then note_fail "shellcheck is on the harness PATH"; fi
case_end

case_begin "0.tripwire-armed"
run aws --version
a_rc 1
[ -s "$TRIP" ] || note_fail "the aws stub logged nothing"
grep -q '^aws ' "$TRIP" || note_fail "the stub log has no aws line"
case_end
: > "$TRIP_ALL"

case_begin "0.temp-repo-clean"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ -z "$(git -C "$REPO" status --porcelain)" ] || note_fail "the temporary repository is dirty before any case"
case_end

### Gather once (used by case 9 and the archive upload cases) ------------------

run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$OUT_BASE/evidence" --include "$INCL_DIR"
G_OUT="$OUT"
G_ERR="$ERR"
G_RC=$RC
G_TRIP_FAIL=""
if [ -s "$TRIP" ]; then G_TRIP_FAIL="stub called during the gather"; fi
EVDIR="$(sed -n 's/^EVIDENCE_DIR=//p' "$G_OUT" | head -n 1)"

### Case 1: --help --------------------------------------------------------------

for n in $SCRIPT_NAMES; do
  SC "$n" --help
  case_begin "1.help.$n"
  a_rc 0
  a_out "$n.sh"
  [ "$(wc -l < "$OUT" | tr -d ' ')" -ge 3 ] || note_fail "help text shorter than 3 lines"
  a_trip_empty
  case_end
done

### Case 2: --print-digest ------------------------------------------------------

build plan digest -
run_build
case_begin "2.print-digest.plan"
a_rc 0
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_out "ACTION=plan"
a_trip_empty
case_end

for key in $KEYS; do
  prep_key "$key"
  case_begin "2.print-digest.$key"
  a_rc 0
  a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
  a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
  a_out "ACTION=$AN"
  is_hex64 "$D_SCRIPT" || note_fail "SCRIPT_DIGEST is not 64 hex"
  is_hex64 "$D_ARGS" || note_fail "ARGS_DIGEST is not 64 hex"
  a_not_out "DRY_RUN=yes"
  a_trip_empty
  case_end
done

for n in check package; do
  SC "$n" --print-digest
  case_begin "2.print-digest.unsupported.$n"
  a_rc_nonzero
  a_err_evidence
  a_err "unknown argument: --print-digest"
  a_trip_empty
  case_end
done

prep_key rollback-state
S1="$D_SCRIPT"
A1="$D_ARGS"
prep_key rollback-state
S2="$D_SCRIPT"
A2="$D_ARGS"
build rollback-state digest - "$ACCT" 20
run_build
S3="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
A3="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "2.print-digest.deterministic"
[ "$S1" = "$S2" ] && [ "$A1" = "$A2" ] || note_fail "two runs with the same arguments printed different digests"
[ "$S1" = "$S3" ] || note_fail "SCRIPT_DIGEST changed with the cost ceiling"
[ "$A1" != "$A3" ] || note_fail "ARGS_DIGEST did not change with the cost ceiling"
case_end

### Case 3 and 4: dry runs and approval-gated dry runs ---------------------------

build plan dry -
run_build
case_begin "3.dry-run.plan"
a_dry_common
a_targets
a_out "IDENTITY_CHECK=skipped"
case_end

SC check --dry-run
case_begin "3.dry-run.check"
a_dry_common
a_out "CHECK_RESULT=DRY-RUN"
case_end

SC package --dry-run --out-dir "$OUT_BASE/package-1"
case_begin "3.dry-run.package"
a_dry_common
a_out "SOURCE git_sha=$HEAD_SHA"
case_end

SC archive-evidence --dry-run --out-dir "$OUT_BASE/evidence-dry" --include "$INCL_DIR"
case_begin "3.dry-run.archive-gather"
a_dry_common
a_out "WOULD_GATHER"
a_out "WOULD_GENERATE"
[ ! -e "$OUT_BASE/evidence-dry" ] || note_fail "the dry run created the out directory"
case_end

for key in $KEYS; do
  prep_key "$key"
  fresh
  rec_write "$REC/$key.env"
  build "$key" dry "$REC/$key.env"
  run_build
  case_begin "3.dry-run.$key"
  a_dry_common
  a_targets
  a_out "IDENTITY_CHECK=skipped"
  a_out "ACTION=$AN"
  case "$key" in
    experiment)
      a_out "MANIFEST_VALID experiment=A size=S"
      a_out "LIMITS max_minutes=30" ;;
    pipeline)
      a_out "PROJECTED_SPEND_USD=0.60" ;;
    cleanup-flip | cleanup-destroy)
      a_out "SOURCE=terraform-source (not live)" ;;
  esac
  case_end
  case_begin "4.approval.$key.verified"
  a_rc 0
  a_out "APPROVAL_VERIFIED action=$AN"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out "APPROVAL_SKIPPED_FIELDS=none"
  a_not_out "APPROVAL_CONSUMED"
  a_trip_empty
  case_end
  run_build
  case_begin "4.approval.$key.reusable"
  a_rc 0
  a_out "APPROVAL_VERIFIED action=$AN"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_state_empty
  a_trip_empty
  case_end
done

# Dry runs that legitimately skip a check say so and claim no pass for it.
for key in deploy rollback-image cleanup-flip cleanup-destroy; do
  prep_key "$key"
  fresh
  if [ "$key" = "deploy" ]; then
    R_ADIG="$ZERO64"
  fi
  R_PDIG="$ZERO64"
  rec_write "$REC/$key-skip.env"
  PLAN_OVERRIDE="$PLAN_MISSING"
  build "$key" dry "$REC/$key-skip.env"
  PLAN_OVERRIDE=""
  run_build
  case_begin "4.skipped-check.$key"
  a_rc 0
  a_out "CHECK_SKIPPED plan-binding"
  a_out "(not done, so not passed)"
  a_out "APPROVAL_CHECK_SKIPPED field=PLAN_DIGEST"
  a_not_out "PLAN_BOUND"
  a_not_out "plan-binding: ok"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  if [ "$key" = "deploy" ]; then
    a_out "APPROVAL_CHECK_SKIPPED field=ARGS_DIGEST"
    a_out "APPROVAL_SKIPPED_FIELDS=ARGS_DIGEST,PLAN_DIGEST"
  else
    a_out "APPROVAL_SKIPPED_FIELDS=PLAN_DIGEST"
  fi
  a_trip_empty
  case_end
done

### Case 5: refusals --------------------------------------------------------------

rv() {
  local id="$1" frag="$2"
  rec_write "$VREC"
  build "$KEY" dry "$VREC"
  run_build
  case_begin "5.$KEY.$id"
  a_refused "$frag"
  case_end
  fresh
}

rv_ok() {
  local id="$1"
  rec_write "$VREC"
  build "$KEY" dry "$VREC"
  run_build
  case_begin "5.$KEY.$id"
  a_rc 0
  a_out "APPROVAL_VERIFIED"
  a_trip_empty
  case_end
  fresh
}

arg_variant() {
  local id="$1" frag="$2" acct="$3" cost="$4" region="$5"
  build "$KEY" dry "$VREC" "$acct" "$cost" "$region"
  run_build
  case_begin "5.$KEY.$id"
  a_refused "$frag"
  case_end
}

for key in $KEYS; do
  KEY="$key"
  prep_key "$key"
  fresh
  OTHER=archive
  if [ "$AN" = "archive" ]; then OTHER=deploy; fi

  build "$key" dry -
  run_build
  case_begin "5.$key.no-record"
  a_refused "--approval-record is required"
  case_end

  build "$key" dry "$REC/does-not-exist.env"
  run_build
  case_begin "5.$key.nonexistent-record"
  a_refused "approval record is missing"
  case_end

  R_AT="$(iso_from_epoch $((NOW - 7200)))"; R_EXP="$(iso_from_epoch $((NOW - 3600)))"
  rv expired "approval expired"
  R_ACCT=210987654321
  rv account-differs "record ACCOUNT_ID differs"
  R_ACTION="$OTHER"
  rv action-differs "record ACTION"
  R_AT="$(iso_from_epoch $((NOW - 120)))"; R_EXP="$(iso_from_epoch $((NOW - 120 + 86401)))"
  rv window-over-24h "longer than 24 hours"
  R_AT="$(iso_from_epoch $((NOW - 120)))"; R_EXP="$(iso_from_epoch $((NOW - 120 + 86400)))"
  rv_ok window-exactly-24h
  R_EXTRA_LINES="EXTRA_KEY=1"
  rv extra-key "unknown key"
  R_OMIT=GIT_SHA
  rv missing-key "must hold GIT_SHA exactly once"
  R_DUP=APPROVED_BY
  rv duplicate-key "must hold APPROVED_BY exactly once"
  R_BY=""
  rv empty-value "has an empty APPROVED_BY"
  R_EXTRA_LINES="NOT A RECORD LINE"
  rv malformed-line "malformed line"
  R_SDIG="$ZERO64"
  rv script-digest "SCRIPT_DIGEST does not match"
  R_ADIG="$ZERO64"
  rv args-digest "ARGS_DIGEST does not match"
  R_PDIG="$ZERO64"
  rv plan-digest "PLAN_DIGEST does not match"
  R_GSHA="$ZERO40"
  rv git-sha "GIT_SHA does not match"
  R_REGION=eu-west-1
  rv record-region "record REGION differs"
  R_ENV=staging
  rv record-environment "record ENVIRONMENT differs"
  R_COST=20
  rv record-cost-differs "differs from --cost-ceiling-usd"
  R_BY=claude-agent
  rv approver-agent "APPROVED_BY must name the human"
  R_BY=infra-engineer
  rv approver-role "APPROVED_BY must name the human"
  R_AT="$(iso_from_epoch $((NOW + 20000)))"; R_EXP="$(iso_from_epoch $((NOW + 30000)))"
  rv approved-in-future "in the future"
  R_VERSION=2
  rv version "unsupported APPROVAL_VERSION"
  R_STATEFUL=maybe
  rv stateful-value "must be yes or no"
  R_EXP=2099-01-01
  rv bad-time-format "UTC ISO 8601"
  if [ "$key" = "cleanup-destroy" ]; then
    R_STATEFUL=no
    rv destroy-without-stateful-ack "STATEFUL_CHANGE_ACKNOWLEDGED=yes"
  fi

  rec_write "$REC/sym-target.env"
  ln -sf "$REC/sym-target.env" "$REC/sym-link.env"
  build "$key" dry "$REC/sym-link.env"
  run_build
  case_begin "5.$key.symlink-record"
  a_refused "not a regular file, or a symlink"
  case_end

  fresh
  rec_write "$VREC"
  arg_variant account-10-digits "account id must be 12 digits" 1234567890 25 us-east-1
  arg_variant account-13-digits "account id must be 12 digits" 1234567890123 25 us-east-1
  arg_variant account-not-numeric "account id must be 12 digits" abcdefghijkl 25 us-east-1
  arg_variant region-not-us-east-1 "region must be us-east-1" "$ACCT" 25 eu-west-1
  arg_variant cost-zero "cost ceiling must be greater than zero" "$ACCT" 0 us-east-1
  arg_variant cost-negative "not a plain positive number" "$ACCT" -1 us-east-1
  arg_variant cost-non-numeric "not a plain positive number" "$ACCT" abc us-east-1
  arg_variant cost-exponent "not a plain positive number" "$ACCT" 1e1 us-east-1
  arg_variant cost-above-25 "exceeds the project ceiling" "$ACCT" 26 us-east-1
  arg_variant cost-just-above-25 "exceeds the project ceiling" "$ACCT" 25.01 us-east-1
done

for key in deploy rollback-image; do
  KEY="$key"
  prep_key "$key"
  fresh
  rec_write "$REC/$key-dirty.env"
  build "$key" dry "$REC/$key-dirty.env"
  dirty_on
  run_build
  dirty_off
  case_begin "5.$key.dirty-tree"
  a_refused "working tree is dirty"
  case_end
done

# A plan whose metadata says STATEFUL_CHANGE=yes needs a record with
# STATEFUL_CHANGE_ACKNOWLEDGED=yes, and the refusal comes before APPROVAL_VERIFIED.
for key in deploy rollback-image; do
  KEY="$key"
  prep_key "$key"
  fresh
  R_PDIG="$PLAN_STATEFUL_DIGEST"
  R_STATEFUL=no
  rec_write "$REC/$key-stateful-no.env"
  PLAN_OVERRIDE="$PLAN_STATEFUL"
  build "$key" dry "$REC/$key-stateful-no.env"
  run_build
  case_begin "5.$key.stateful-plan-unacknowledged"
  a_refused "destroy removes stateful resources: the record must set STATEFUL_CHANGE_ACKNOWLEDGED=yes"
  a_not_out "stateful change acknowledged"
  case_end
  R_STATEFUL=yes
  rec_write "$REC/$key-stateful-yes.env"
  build "$key" dry "$REC/$key-stateful-yes.env"
  run_build
  PLAN_OVERRIDE=""
  case_begin "4.approval.$key.stateful-plan-acknowledged"
  a_rc 0
  a_out "APPROVAL_VERIFIED action=$AN"
  a_out "stateful change acknowledged in the approval record"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out "DRY_RUN_COMPLETE"
  a_trip_empty
  case_end
done

# Plan takes no record; a state directory inside the repository, relative or with ..
# segments is refused; a state directory inside --out-dir is refused.
build plan dry -
A=("${A[@]}" --approval-record "$REC/deploy.env")
run_build
case_begin "5.plan.rejects-approval-record"
a_refused "plan.sh takes no approval record"
case_end

KEY=rollback-state
prep_key rollback-state
build rollback-state dry "$REC/rollback-state.env"
run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=$REPO/inside-state"
case_begin "5.state-dir.inside-repository"
a_refused "must not be inside the repository"
case_end
run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=relative-state"
case_begin "5.state-dir.relative"
a_refused "must be an absolute path"
case_end
run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=$WORK/state/../elsewhere"
case_begin "5.state-dir.dot-dot-segment"
a_refused "must not contain . or .. segments"
case_end
build smoke dry "$REC/smoke.env"
run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=$OUT_BASE/smoke/state"
case_begin "5.state-dir.inside-out-dir"
a_refused "must not be inside --out-dir"
case_end

# smoke-test.sh --out-dir guard: physical path, letter case ignored.
LINK_DIR="$WORK/lnk"
ln -s "$REPO/scripts" "$LINK_DIR"
UPPER_REPO="$(printf '%s' "$REPO" | tr '[:lower:]' '[:upper:]')"
for variant in "inside|$REPO/smoke-out" "doubled-slash|$REPO//smoke-out" "symlink|$LINK_DIR/smoke-out" "case-changed|$UPPER_REPO/smoke-out"; do
  vid="${variant%%|*}"
  vdir="${variant#*|}"
  build smoke dry "$REC/smoke.env"
  A=("${A[@]}" --out-dir "$vdir")
  run_build
  case_begin "5.smoke.out-dir-$vid"
  a_refused "must not be inside the repository"
  case_end
done
build smoke dry "$REC/smoke.env"
A=("${A[@]}" --out-dir "relative/out")
run_build
case_begin "5.smoke.out-dir-relative"
a_refused "must be an absolute path"
case_end
build smoke dry "$REC/smoke.env"
A=("${A[@]}" --out-dir "$OUT_BASE/a/../b")
run_build
case_begin "5.smoke.out-dir-dot-dot"
a_refused "must not contain . or .. segments"
case_end
LONGQ="$(awk 'BEGIN { for (i = 0; i < 201; i++) printf "a" }')"
for variant in "url-with-path|--fast-url|http://10.20.0.10:8080/x|must look like http://host:port" \
  "secret-other-account|--query-secret-arn|arn:aws:secretsmanager:us-east-1:210987654321:secret:abc|must be a Secrets Manager ARN" \
  "bedrock-maybe|--bedrock|maybe|--bedrock must be on or off" \
  "question-201-chars|--question|$LONGQ|limited to 200 characters"; do
  vid="${variant%%|*}"
  rest="${variant#*|}"
  vflag="${rest%%|*}"
  rest="${rest#*|}"
  vval="${rest%%|*}"
  vfrag="${rest#*|}"
  build smoke dry "$REC/smoke.env"
  A=("${A[@]}" "$vflag" "$vval")
  run_build
  case_begin "5.smoke.$vid"
  a_refused "$vfrag"
  case_end
done

# smoke-test.sh: the three added steps follow deployed-revision, and --read-secret-arn
# is in the digested arguments only when it is given.
build smoke dry "$REC/smoke.env"
run_build
case_begin "3.dry-run.smoke.new-steps-follow-deployed-revision"
a_dry_common
a_out_re '^STEP\[[0-9]+\] deployed-revision: dry-run$'
a_out_re '^STEP\[[0-9]+\] idempotency: dry-run$'
a_out_re '^STEP\[[0-9]+\] error-path: dry-run$'
a_out_re '^STEP\[[0-9]+\] evidence-fetch: dry-run$'
a_order "deployed-revision: dry-run" "idempotency: dry-run" "error-path: dry-run" "evidence-fetch: dry-run" "DRY_RUN_COMPLETE"
a_out "expect 422 IDEMPOTENCY_KEY_REUSED"
a_out "expect 401 UNAUTHORIZED"
a_out "no --read-secret-arn: the real run skips this step"
a_not_out "read the read-scope token"
case_end

SM_ARN_OK="arn:aws:secretsmanager:us-east-1:${ACCT}:secret:x"
build smoke digest -
run_build
SM_CANON_NO="$(sed -n 's/^ARGS_CANONICAL=//p' "$OUT" | head -n 1)"
SM_ADIG_NO="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
SM_NO_HAS="no"
case "$SM_CANON_NO" in *";read_secret="*) SM_NO_HAS="yes" ;; esac
run_build
SM_CANON_NO2="$(sed -n 's/^ARGS_CANONICAL=//p' "$OUT" | head -n 1)"
SM_ADIG_NO2="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
build smoke digest -
A=("${A[@]}" --read-secret-arn "$SM_ARN_OK")
run_build
SM_RC=$RC
SM_CANON_YES="$(sed -n 's/^ARGS_CANONICAL=//p' "$OUT" | head -n 1)"
SM_ADIG_YES="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
SM_SDIG_YES="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
SM_YES_HAS="no"
case "$SM_CANON_YES" in *";read_secret=$SM_ARN_OK") SM_YES_HAS="yes" ;; esac
case_begin "2.print-digest.smoke.read-secret-arn-only-when-given"
a_rc 0
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_trip_empty
[ "$SM_RC" -eq 0 ] || note_fail "--print-digest with --read-secret-arn exited $SM_RC"
[ -n "$SM_CANON_NO" ] && [ -n "$SM_CANON_YES" ] || note_fail "an ARGS_CANONICAL line is missing"
[ "$SM_NO_HAS" = "no" ] || note_fail "the digest without --read-secret-arn holds ;read_secret="
[ "$SM_YES_HAS" = "yes" ] || note_fail "the digest with --read-secret-arn does not end in ;read_secret=<arn>"
[ "$SM_CANON_NO" = "$SM_CANON_NO2" ] && [ "$SM_ADIG_NO" = "$SM_ADIG_NO2" ] || note_fail "two runs without the flag printed different digests"
[ "$SM_CANON_YES" = "${SM_CANON_NO};read_secret=$SM_ARN_OK" ] || note_fail "the canonical arguments with the flag are not the canonical arguments without it plus ;read_secret=<arn>"
[ "$SM_ADIG_YES" != "$SM_ADIG_NO" ] || note_fail "ARGS_DIGEST did not change when --read-secret-arn was given"
case_end

rec_base smoke-test "$SM_SDIG_YES" "$SM_ADIG_YES" n/a n/a
rec_write "$REC/smoke-read.env"
build smoke dry "$REC/smoke-read.env"
A=("${A[@]}" --read-secret-arn "$SM_ARN_OK")
run_build
case_begin "4.approval.smoke.read-secret-arn-dry-run"
a_dry_common
a_out "APPROVAL_VERIFIED action=smoke-test"
a_out "APPROVAL_NOT_CONSUMED dry-run"
a_out ";read_secret=$SM_ARN_OK"
a_out "read the read-scope token from $SM_ARN_OK into a mode 600 file under"
a_not_out "no --read-secret-arn"
case_end

build smoke dry "$REC/smoke.env"
A=("${A[@]}" --read-secret-arn "arn:aws:secretsmanager:us-east-1:210987654321:secret:x")
run_build
case_begin "5.smoke.read-secret-arn-other-account"
a_refused "--read-secret-arn must be a Secrets Manager ARN"
case_end
build smoke dry "$REC/smoke.env"
A=("${A[@]}" --read-secret-arn "not-an-arn")
run_build
case_begin "5.smoke.read-secret-arn-not-an-arn"
a_refused "--read-secret-arn must be a Secrets Manager ARN"
case_end
build smoke dry "$REC/smoke.env"
A=("${A[@]}" --read-secret-arn "arn:aws:secretsmanager:eu-west-1:${ACCT}:secret:x")
run_build
case_begin "5.smoke.read-secret-arn-other-region"
a_refused "--read-secret-arn must be a Secrets Manager ARN"
case_end

# run-bounded-pipeline.sh limits.
pipe_case() {
  local id="$1" frag="$2" size="$3" minutes="$4" spend="$5" burn="$6" stop="$7" input="$8"
  common_args "$ACCT" us-east-1 25
  SN=run-bounded-pipeline
  A=(--dry-run "${CA[@]}" --size "$size" --max-minutes "$minutes" --spend-ceiling-usd "$spend" --hourly-burn-usd "$burn" --stop-on "$stop" --input-file "$input" --approval-record "$REC/pipeline.env")
  run_build
  case_begin "5.pipeline.$id"
  a_refused "$frag"
  case_end
}
pipe_case small-over-60 "must be 1 to 60" small 61 0.75 0.60 first-failure "$PIPE_IN"
pipe_case medium-over-90 "must be 1 to 90" medium 91 5.00 0.60 first-failure "$PIPE_IN"
pipe_case large-over-150 "must be 1 to 150" large 151 5.00 0.60 first-failure "$PIPE_IN"
pipe_case minutes-zero "must be 1 to 60" small 0 0.75 0.60 first-failure "$PIPE_IN"
pipe_case unknown-size "size must be small, medium or large" huge 60 0.75 0.60 first-failure "$PIPE_IN"
pipe_case projected-over-spend "projected spend" small 60 0.75 2.00 first-failure "$PIPE_IN"
pipe_case spend-over-cost "exceeds the cost ceiling" small 60 26 0.60 first-failure "$PIPE_IN"
pipe_case unknown-stop-condition "unknown stop condition" small 60 0.75 0.60 first-failure,bogus "$PIPE_IN"
pipe_case input-missing "must be an existing absolute path" small 60 0.75 0.60 first-failure "$REC/no-such-input.json"
printf '{"x":"%s"}\n' "$FAKE_AKIA" > "$REC/pipeline-secret-input.json"
pipe_case input-key-material "possible credential material" small 60 0.75 0.60 first-failure "$REC/pipeline-secret-input.json"

# run-bounded-pipeline.sh: the input, limits and output markers of validate-params
# (printed in a dry run too). The digest and size are computed here, not read back.
build pipeline dry "$REC/pipeline.env"
run_build
PIPE_SHA="$(sha_of "$PIPE_IN")"
PIPE_BYTES="$(wc -c < "$PIPE_IN" | tr -d ' ')"
case_begin "3.dry-run.pipeline.input-limits-and-output-markers"
a_dry_common
a_out "INPUT_FILE=$PIPE_IN"
a_out "INPUT_SHA256=$PIPE_SHA"
a_out "INPUT_BYTES=$PIPE_BYTES"
a_out "LIMITS size=small max_minutes=60 spend_ceiling_usd=0.75 hourly_burn_usd=0.60 stop_on=first-failure,dlq-nonempty cost_ceiling_usd=25"
a_out "OUTPUT_LOCATIONS=none-named"
a_not_out "OUTPUT_LOCATION="
a_out "DATASET_SNAPSHOT=not-recorded"
a_not_out "PIPELINE_RECORD="
a_not_out "EXECUTION_ARN="
a_not_out "START_UTC="
case_end

PIPE_IN_S3="$REC/pipeline-input-s3.json"
printf '{"hadoop_args":["--output","s3://harness-out-bucket/run-1/hadoop"],"spark_args":[],"loader_command":[]}\n' > "$PIPE_IN_S3"
common_args "$ACCT" us-east-1 25
SN=run-bounded-pipeline
stop_on="first-failure,dlq-nonempty"
A=(--print-digest "${CA[@]}" --size small --max-minutes 60 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on "$stop_on" --input-file "$PIPE_IN_S3")
run_build
rec_base pipeline "$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)" "$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)" n/a n/a
rec_write "$REC/pipeline-s3.env"
A=(--dry-run "${CA[@]}" --size small --max-minutes 60 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on "$stop_on" --input-file "$PIPE_IN_S3" --approval-record "$REC/pipeline-s3.env")
run_build
case_begin "3.dry-run.pipeline.s3-uri-in-input-is-an-output-location"
a_dry_common
a_out "INPUT_FILE=$PIPE_IN_S3"
a_out "INPUT_SHA256=$(sha_of "$PIPE_IN_S3")"
a_out "OUTPUT_LOCATION=s3://harness-out-bucket/run-1/hadoop"
a_not_out "OUTPUT_LOCATIONS=none-named"
a_out "DATASET_SNAPSHOT=not-recorded"
a_out "APPROVAL_VERIFIED action=pipeline"
case_end

### Case 6: consumed record --------------------------------------------------------

for key in $KEYS; do
  KEY="$key"
  prep_key "$key"
  fresh
  rec_write "$REC/$key-consumed.env"
  SDIR="$WORK/state-consumed-$key"
  FRESH_DIR="$WORK/state-fresh-$key"
  mkdir -p "$FRESH_DIR"
  consume_with_lib "$REC/$key-consumed.env" "$SDIR" "$AN"
  CRC=$?
  case_begin "6.$key.marker-written-by-lib"
  OUT="$LOGDIR/consume.out"; ERR="$LOGDIR/consume.err"; RC=$CRC
  a_rc 0
  a_out "APPROVAL_CONSUMED action=$AN"
  [ -n "$(find "$SDIR/consumed" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -n 1)" ] || note_fail "no marker directory under $SDIR/consumed"
  case_end
  build "$key" dry "$REC/$key-consumed.env"
  run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=$SDIR"
  case_begin "6.$key.dry-run-refuses-consumed"
  a_refused "already used"
  case_end
  run_build_env "SCHOLARCHAIN_OPS_STATE_DIR=$FRESH_DIR"
  case_begin "6.$key.same-record-passes-with-fresh-marker-dir"
  a_rc 0
  a_out "APPROVAL_VERIFIED"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  [ -z "$(find "$FRESH_DIR" -mindepth 1 2>/dev/null | head -n 1)" ] || note_fail "the dry run wrote into the marker directory"
  case_end
done

### Case 7: cleanup.sh -------------------------------------------------------------

common_args "$ACCT" us-east-1 25

a_cleanup_refused() {
  a_refused "$1"
  a_not_out "SOURCE=terraform-source"
  a_not_out "AFFECTED_RESOURCES_BEGIN"
  a_not_out "CLEANUP_OVERLAY"
}

a_human_refused() {
  a_cleanup_refused "$1"
  a_not_out "validate-params"
}

SC cleanup --dry-run --mode plan-flip "${CA[@]}"
case_begin "7.refuse.no-human-flag-dry"
a_human_refused "requires --i-am-the-human"
case_end

SC cleanup --mode plan-flip "${CA[@]}"
case_begin "7.refuse.no-human-flag-real"
a_human_refused "requires --i-am-the-human"
case_end

run env SCHOLARCHAIN_AGENT_RUN=1 bash "$REPO/scripts/cleanup.sh" --dry-run --mode plan-flip "${CA[@]}" --i-am-the-human
case_begin "7.refuse.agent-run-set-dry"
a_human_refused "SCHOLARCHAIN_AGENT_RUN is set"
case_end

run env SCHOLARCHAIN_AGENT_RUN=1 bash "$REPO/scripts/cleanup.sh" --mode plan-destroy "${CA[@]}" --i-am-the-human
case_begin "7.refuse.agent-run-set-real"
a_human_refused "SCHOLARCHAIN_AGENT_RUN is set"
case_end

run env CLAUDECODE=1 bash "$REPO/scripts/cleanup.sh" --dry-run --mode plan-flip "${CA[@]}" --i-am-the-human
case_begin "7.refuse.agent-environment-without-terminal"
a_human_refused "agent environment is detected"
case_end

common_args "$ACCT" us-east-1 25
SC cleanup --dry-run --mode plan-flip --environment staging --account-id "$ACCT" --region us-east-1 --cost-ceiling-usd 25 --i-am-the-human
case_begin "7.refuse.environment-not-course"
a_cleanup_refused "course environment only"
case_end

SC cleanup --dry-run --mode apply-destroy "${CA[@]}" --plan-file "$PLAN_DESTROY" --approval-record "$REC/cleanup-destroy.env" --i-am-the-human
case_begin "7.refuse.apply-destroy-without-acknowledge-dry"
a_cleanup_refused "--acknowledge-data-loss"
case_end

SC cleanup --mode apply-destroy "${CA[@]}" --plan-file "$PLAN_DESTROY" --approval-record "$REC/cleanup-destroy.env" --i-am-the-human
case_begin "7.refuse.apply-destroy-without-acknowledge-real"
a_cleanup_refused "--acknowledge-data-loss"
case_end

SC cleanup --dry-run --mode wipe-everything "${CA[@]}" --i-am-the-human
case_begin "7.refuse.unknown-mode"
a_cleanup_refused "--mode must be"
case_end

SC cleanup --dry-run --mode apply-flip "${CA[@]}" --approval-record "$REC/cleanup-flip.env" --i-am-the-human
case_begin "7.refuse.apply-flip-without-plan-file"
a_cleanup_refused "--plan-file is required"
case_end

SC cleanup --dry-run --mode plan-flip "${CA[@]}" --image-tag not-a-sha --i-am-the-human
case_begin "7.refuse.bad-image-tag"
a_cleanup_refused "image tag must be a full 40-character Git SHA"
case_end

for m in plan-flip plan-destroy; do
  SC cleanup --dry-run --mode "$m" "${CA[@]}" --i-am-the-human
  case_begin "7.dry-run.$m.lists-source-and-deletes-nothing"
  a_dry_common
  a_targets
  a_out "SOURCE=terraform-source (not live)"
  a_out "LIVE_LISTING=not done in a dry run"
  a_out "DECLARED_NAMES_ARE_SOURCE_EXPRESSIONS"
  a_not_out "AFFECTED_RESOURCES_BEGIN"
  a_not_out "BUCKET_GONE"
  a_not_out "STATE_EMPTY"
  case_end
done
SC cleanup --dry-run --mode plan-flip "${CA[@]}" --i-am-the-human
case_begin "7.dry-run.plan-flip.declares-stateful-resources"
a_out "DECLARED module="
case_end

### Case 8: run-experiment.sh --------------------------------------------------------

exp_args() {
  local flag="--dry-run"
  if [ "$1" = "digest" ]; then flag="--print-digest"; fi
  common_args "$ACCT" us-east-1 25
  SN=run-experiment
  A=("$flag" --manifest "$2" "${CA[@]}")
  if [ "$1" = "dry" ] && [ "$3" != "-" ]; then
    A=("${A[@]}" --approval-record "$3")
  fi
}

mf_edit() {
  local src="$1" dst="$2" mode="$3" key="$4" val="$5"
  if [ "$mode" = "add" ]; then
    cp "$src" "$dst"
    printf '%s=%s\n' "$key" "$val" >> "$dst"
    return 0
  fi
  awk -v m="$mode" -v k="$key" -v v="$val" '
    { i = index($0, "="); key = (i > 0) ? substr($0, 1, i - 1) : "" }
    key == k && m == "set" { print k "=" v; next }
    key == k && m == "del" { next }
    key == k && m == "dup" { print; print k "=" v; next }
    { print }
  ' "$src" > "$dst"
}

exp_refuse() {
  local id="$1" mode="$2" key="$3" val="$4" frag="$5" m="$REC/mf-$1.manifest"
  mf_edit "$MF_BASE" "$m" "$mode" "$key" "$val"
  exp_args dry "$m" "$REC/does-not-exist.env"
  run_build
  case_begin "8.refuse.$id"
  a_refused "$frag"
  case_end
}

exp_refuse minutes-over-tier-cap set MAX_MINUTES 61 "above the cap 60"
exp_refuse concurrency-over-tier-cap set MAX_CONCURRENCY 5 "above the cap 4"
exp_refuse tasks-over-tier-cap set MAX_TASKS 1001 "above the cap 1000"
exp_refuse minutes-zero set MAX_MINUTES 0 "must be a whole number from 1 to 60"
exp_refuse region-not-us-east-1 set REGION eu-west-1 "manifest REGION must be us-east-1"
exp_refuse unknown-experiment-id set EXPERIMENT_ID G "unknown EXPERIMENT_ID"
exp_refuse tier-unknown set SIZE_TIER XL "SIZE_TIER must be S, M or L"
exp_refuse output-root-inside-repository set OUTPUT_ROOT "$REPO/exp-out" "must not be inside the repository"
exp_refuse output-root-relative set OUTPUT_ROOT relative/exp-out "must be an absolute path"
exp_refuse output-root-dot-dot set OUTPUT_ROOT "$WORK/a/../exp-out" "must not contain . or .. segments"
exp_refuse stop-condition-dlq-nonempty set STOP_CONDITIONS wall-clock,dlq-nonempty "not allowed: dlq-nonempty"
exp_refuse stop-conditions-without-wall-clock set STOP_CONDITIONS first-failure "must include wall-clock"
exp_refuse missing-key del MAX_TASKS "" "must hold MAX_TASKS exactly once"
exp_refuse duplicate-key dup MAX_TASKS 300 "must hold MAX_TASKS exactly once"
exp_refuse unknown-key add EXTRA_KEY 1 "unknown key"
exp_refuse space-in-value set DESCRIPTION "two words" "malformed line"
exp_refuse entry-point-outside-experiments set ENTRY_POINT script:scripts/check.sh "must be a .sh file under experiments/ or load-tests/"
exp_refuse entry-point-dot-dot set ENTRY_POINT script:experiments/../scripts/check.sh "must not contain . or .. segments"
exp_refuse entry-point-not-script-or-sbt set ENTRY_POINT bash:experiments/harness/run.sh "must start with script: or sbt:"
exp_refuse spend-above-cost-ceiling set SPEND_CEILING_USD 26 "exceeds --cost-ceiling-usd"
exp_refuse projected-spend-above-ceiling set HOURLY_BURN_USD 5.00 "projected spend"
exp_refuse account-differs set ACCOUNT_ID 210987654321 "manifest ACCOUNT_ID differs"
exp_refuse environment-differs set ENVIRONMENT staging "manifest ENVIRONMENT differs"
exp_refuse key-material set DESCRIPTION "$FAKE_AKIA" "possible credential material"
case_begin "8.refuse.key-material.value-not-printed"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

exp_args dry "$MF_BASE" "$REC/experiment.env"
A=("${A[@]}" --out-dir "$WORK/somewhere-else")
run_build
case_begin "8.refuse.out-dir-differs-from-output-root"
a_refused "differs from the manifest OUTPUT_ROOT"
case_end

common_args "$ACCT" us-east-1 25
SN=run-experiment
A=(--dry-run --manifest "$MF_BASE" --environment course --account-id "$ACCT" --cost-ceiling-usd 25 --approval-record "$REC/experiment.env")
run_build
case_begin "8.refuse.region-argument-missing"
a_refused "--region is required"
case_end

SN=run-experiment
A=(--dry-run --manifest "$MF_BASE" --environment course --account-id "$ACCT" --region eu-west-1 --cost-ceiling-usd 25 --approval-record "$REC/experiment.env")
run_build
case_begin "8.refuse.region-argument-not-us-east-1"
a_refused "region must be us-east-1"
case_end

exp_args dry "$MF_BASE" "$REC/experiment.env"
dirty_on
run_build
dirty_off
case_begin "8.refuse.dirty-tree"
a_refused "working tree is dirty"
a_out "MANIFEST_VALID"
case_end

for mf in "$REPO"/experiments/manifests/*.manifest; do
  [ -f "$mf" ] || continue
  bn="${mf##*/}"
  letter="$(printf '%s' "${bn:11:1}" | tr '[:lower:]' '[:upper:]')"
  exp_args digest "$mf" -
  run_build
  MD_RC=$RC
  MD_S="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
  MD_A="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
  rec_base experiment "$MD_S" "$MD_A" n/a "$HEAD_SHA"
  rec_write "$REC/committed-$letter.env"
  exp_args dry "$mf" "$REC/committed-$letter.env"
  run_build
  case_begin "8.committed-manifest.$letter"
  [ "$MD_RC" -eq 0 ] || note_fail "--print-digest exited $MD_RC"
  a_rc 0
  a_out "MANIFEST_VALID experiment=$letter"
  a_out "LIMITS max_minutes="
  a_out "CHECK_SKIPPED entry-point"
  a_out "(not done, so not passed)"
  a_out "APPROVAL_VERIFIED action=experiment"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out "DRY_RUN_COMPLETE"
  a_trip_empty
  case_end
done
case_begin "8.committed-manifest.count"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
n_mf="$(find "$REPO/experiments/manifests" -name '*.manifest' -type f | wc -l | tr -d ' ')"
[ "$n_mf" -eq 6 ] || note_fail "expected 6 committed manifests, found $n_mf"
case_end

# An entry point that is not a tracked file: a real run refuses it at validation,
# before the clean-tree check, the approval record and any aws call; --print-digest and
# --dry-run accept the manifest and say the entry-point check was not done. The file
# exists but is untracked, so existence alone must not pass; it is removed afterwards.
UNTRACKED_REL="experiments/harness/untracked.sh"
MF_UNTRACKED="$REC/mf-untracked.manifest"
mf_edit "$MF_BASE" "$MF_UNTRACKED" set ENTRY_POINT "script:$UNTRACKED_REL"
printf '#!/usr/bin/env bash\nexit 0\n' > "$REPO/$UNTRACKED_REL"
common_args "$ACCT" us-east-1 25
SN=run-experiment
A=(--manifest "$MF_UNTRACKED" "${CA[@]}" --approval-record "$REC/does-not-exist.env")
run_build
case_begin "8.real-run.untracked-entry-point-refused"
a_rc_nonzero
a_err_evidence
a_err "ENTRY_POINT $UNTRACKED_REL is not a tracked file in this commit"
a_not_out "DRY_RUN"
a_not_out "MANIFEST_VALID"
a_not_out "CLEAN_TREE"
a_not_out "APPROVAL_VERIFIED"
a_not_out "identity-check"
a_not_out "RUN_ID="
a_trip_empty
[ ! -e "$EXP_OUT" ] || note_fail "the refused run created the output root"
case_end

exp_args digest "$MF_UNTRACKED" -
run_build
UT_RC=$RC
UT_S="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
UT_A="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "8.untracked-entry-point.print-digest-accepted"
a_rc 0
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_trip_empty
case_end
rm -f "$REPO/$UNTRACKED_REL"
rec_base experiment "$UT_S" "$UT_A" n/a "$HEAD_SHA"
rec_write "$REC/untracked.env"
exp_args dry "$MF_UNTRACKED" "$REC/untracked.env"
run_build
case_begin "8.untracked-entry-point.dry-run-accepted-and-says-not-checked"
[ "$UT_RC" -eq 0 ] || note_fail "--print-digest exited $UT_RC"
a_rc 0
a_out "MANIFEST_VALID experiment=A"
a_out "CHECK_SKIPPED entry-point: $UNTRACKED_REL is not a tracked file in this commit; a real run refuses (not done, so not passed)"
a_out "APPROVAL_VERIFIED action=experiment"
a_out "DRY_RUN_COMPLETE"
a_trip_empty
case_end

# The dry-run text for the entry point's process group (work order 15, R2).
exp_args dry "$MF_BASE" "$REC/experiment.env"
run_build
case_begin "8.dry-run.would-run-names-the-process-group"
a_rc 0
a_out "WOULD_RUN: script entry point experiments/harness/run.sh, started once, limit 1800 s (TERM, then KILL 5 s later, sent to the entry point's whole process group; any member still alive when it returns is stopped before finalize), output to raw/entrypoint.log"
a_out "SCHOLARCHAIN_EXPERIMENT_ID, SCHOLARCHAIN_RUN_ID, SCHOLARCHAIN_RUN_DIR"
a_trip_empty
case_end

### Case 9: archive-evidence.sh ----------------------------------------------------

case_begin "9.gather.local-run"
OUT="$G_OUT"; ERR="$G_ERR"; RC=$G_RC
a_rc 0
a_out "ARCHIVE_COMPLETE"
a_out "MANIFEST_SHA256="
a_out "local only: nothing was uploaded"
[ -z "$G_TRIP_FAIL" ] || note_fail "$G_TRIP_FAIL"
[ -n "$EVDIR" ] && [ -d "$EVDIR" ] || note_fail "no evidence directory was created"
case_end

case_begin "9.gather.manifest-sha256-matches-copied-files"
OUT="$G_OUT"; ERR="$G_ERR"; RC=$G_RC
if [ -z "$EVDIR" ] || [ ! -f "$EVDIR/MANIFEST.sha256" ]; then
  note_fail "MANIFEST.sha256 is missing"
else
  vm_n=0
  vm_bad=0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    h="${line%%  *}"
    p="${line#*  }"
    vm_n=$((vm_n + 1))
    if [ ! -f "$EVDIR/$p" ] || [ "$(sha_of "$EVDIR/$p")" != "$h" ]; then vm_bad=$((vm_bad + 1)); fi
  done < "$EVDIR/MANIFEST.sha256"
  [ "$vm_n" -gt 0 ] || note_fail "MANIFEST.sha256 lists nothing"
  [ "$vm_bad" -eq 0 ] || note_fail "$vm_bad manifest line(s) do not match the copied file"
  grep -qF "include/1/incl/run-record.env" "$EVDIR/MANIFEST.sha256" || note_fail "the included file is not listed"
  grep -qF "repo/Dockerfile" "$EVDIR/MANIFEST.sha256" || note_fail "the allowlisted Dockerfile is not listed"
  grep -qF "release/metadata.env" "$EVDIR/MANIFEST.sha256" || note_fail "release/metadata.env is not listed"
  listed="$(awk 'END { print NR }' "$EVDIR/MANIFEST.sha256")"
  found="$(find "$EVDIR" -type f ! -name MANIFEST.sha256 | wc -l | tr -d ' ')"
  [ "$listed" -eq "$found" ] || note_fail "manifest lists $listed file(s), directory holds $found"
  grep -qF "SOURCE kind=include" "$EVDIR/MANIFEST.txt" || note_fail "MANIFEST.txt has no SOURCE line for the include"
fi
case_end

case_begin "9.gather.marker-facts-hold-no-content"
OUT="$G_OUT"; ERR="$G_ERR"; RC=$G_RC
if [ -f "$EVDIR/test/last-test-run.facts" ]; then
  grep -qF "CONTENT_ARCHIVED=no" "$EVDIR/test/last-test-run.facts" || note_fail "facts file does not say content is not archived"
else
  note_fail "test/last-test-run.facts is missing"
fi
case_end

BADDIR="$REC/bad"
mkdir -p "$BADDIR"
bi=0
for bn in .env .env.local x.pem x.key terraform.tfstate my-credentials.txt id_ed25519 x.tfplan .npmrc .smoke-auth.123; do
  bi=$((bi + 1))
  printf 'x\n' > "$BADDIR/$bn"
  SC archive-evidence --dry-run --out-dir "$OUT_BASE/ev-bad-dry-$bi" --include "$BADDIR/$bn"
  case_begin "9.refuse.dry-run.$bn"
  a_rc 1
  a_out "WOULD_REFUSE file=$BADDIR/$bn"
  a_err_evidence
  a_not_out "DRY_RUN_COMPLETE"
  a_trip_empty
  [ ! -e "$OUT_BASE/ev-bad-dry-$bi" ] || note_fail "the dry run created the out directory"
  case_end
  run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$OUT_BASE/ev-bad-real-$bi" --include "$BADDIR/$bn"
  case_begin "9.refuse.real-run.$bn"
  a_rc_nonzero
  a_err_evidence
  a_err "REFUSED file=$BADDIR/$bn"
  a_not_out "ARCHIVE_COMPLETE"
  a_trip_empty
  [ ! -e "$OUT_BASE/ev-bad-real-$bi" ] || note_fail "the refused run created the out directory"
  case_end
done

SMOKE_WHY="a smoke-test temporary file (.smoke-auth.* holds a bearer token)"
SC archive-evidence --dry-run --out-dir "$OUT_BASE/ev-smoke-dry" --include "$BADDIR/.smoke-auth.123"
case_begin "9.refuse.smoke-auth-by-name.reason-dry-run"
a_rc 1
a_out "WOULD_REFUSE file=$BADDIR/.smoke-auth.123 reason=$SMOKE_WHY"
a_err_evidence
a_trip_empty
case_end
run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$OUT_BASE/ev-smoke-real" --include "$BADDIR/.smoke-auth.123"
case_begin "9.refuse.smoke-auth-by-name.reason-real-run"
a_rc_nonzero
a_err "REFUSED file=$BADDIR/.smoke-auth.123 reason=$SMOKE_WHY"
a_not_out "ARCHIVE_COMPLETE"
a_trip_empty
case_end

ki=0
for kind in akia pem var bearer ghp ghpat; do
  ki=$((ki + 1))
  case "$kind" in
    akia) printf 'note %s\n' "$FAKE_AKIA" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_AKIA" ;;
    pem) printf '%s\nabc\n' "$FAKE_PEM" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_PEM" ;;
    var) printf '%s = abc\n' "$FAKE_VAR" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_VAR" ;;
    bearer) printf 'header %s\n' "$FAKE_BEARER" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_BEARER" ;;
    ghp) printf 'token %s\n' "$FAKE_GHP" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_GHP" ;;
    ghpat) printf 'token %s\n' "$FAKE_GHPAT" > "$BADDIR/notes-$kind.txt"; planted="$FAKE_GHPAT" ;;
  esac
  run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$OUT_BASE/ev-key-$ki" --include "$BADDIR/notes-$kind.txt"
  case_begin "9.refuse.key-material.$kind"
  a_rc_nonzero
  a_err "possible credential material"
  a_not_out "ARCHIVE_COMPLETE"
  a_trip_empty
  if grep -qF -- "$planted" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
  [ ! -e "$OUT_BASE/ev-key-$ki" ] || note_fail "the refused run created the out directory"
  case_end
done

mkdir -p "$REC/control"
printf 'release notes: ordinary text, no token, no header and no key\n' > "$REC/control/notes-control.txt"
run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$OUT_BASE/ev-control" --include "$REC/control/notes-control.txt"
case_begin "9.gather.ordinary-text-control-is-still-gathered"
a_rc 0
a_out "ARCHIVE_COMPLETE"
a_trip_empty
CTRL_EV="$(sed -n 's/^EVIDENCE_DIR=//p' "$OUT" | head -n 1)"
if [ -z "$CTRL_EV" ] || [ ! -f "$CTRL_EV/MANIFEST.sha256" ]; then
  note_fail "no evidence directory with a MANIFEST.sha256 was made"
else
  grep -qF "include/1/notes-control.txt" "$CTRL_EV/MANIFEST.sha256" || note_fail "the control file is not listed in MANIFEST.sha256"
  [ -f "$CTRL_EV/include/1/notes-control.txt" ] || note_fail "the control file was not copied"
fi
case_end

# A destination directory that already exists is never reused. The evidence name holds a
# UTC second, so directories are made for the seconds around the run.
EX_OUT="$OUT_BASE/ev-exists"
N2="$(date +%s)"
i=-2
while [ "$i" -le 8 ]; do
  mkdir -p "$EX_OUT/evidence-$(stamp_from_epoch $((N2 + i)))-${HEAD_SHA:0:12}"
  i=$((i + 1))
done
run env PATH="$PATH2" bash "$REPO/scripts/archive-evidence.sh" --out-dir "$EX_OUT" --include "$INCL_DIR"
case_begin "9.refuse.destination-exists"
a_rc_nonzero
a_err "already exists and is never reused"
a_not_out "ARCHIVE_COMPLETE"
a_trip_empty
case_end

UP_NAME="evidence-20260101T000000Z-aaaaaaaaaaaa"
common_args "$ACCT" us-east-1 25
SC archive-evidence --dry-run --upload-dir "$OUT_BASE/$UP_NAME" --s3-prefix s3://harness-evidence-bucket/prefix "${CA[@]}"
case_begin "9.upload.dry-run-without-record-refused"
a_refused "--approval-record is required"
case_end
SC archive-evidence --upload-dir "$OUT_BASE/$UP_NAME" --s3-prefix s3://harness-evidence-bucket/prefix "${CA[@]}"
case_begin "9.upload.real-run-without-record-refused-nothing-uploaded"
a_refused "--approval-record is required"
a_not_out "UPLOADED"
case_end
SC archive-evidence --dry-run --upload-dir "$OUT_BASE/$UP_NAME" --s3-prefix not-an-s3-url "${CA[@]}" --approval-record "$REC/archive-upload.env"
case_begin "9.upload.bad-prefix-refused"
a_refused "--s3-prefix must look like"
case_end
SC archive-evidence --dry-run --upload-dir "$INCL_DIR" --s3-prefix s3://harness-evidence-bucket/prefix "${CA[@]}" --approval-record "$REC/archive-upload.env"
case_begin "9.upload.directory-not-named-by-the-script-refused"
a_refused "--upload-dir must be named evidence-"
case_end

# An evidence directory changed after it was gathered is refused at upload time.
TAMPER="$WORK/tamper"
mkdir -p "$TAMPER"
if [ -n "$EVDIR" ] && [ -d "$EVDIR" ]; then
  cp -R "$EVDIR" "$TAMPER/${EVDIR##*/}"
  printf 'extra\n' > "$TAMPER/${EVDIR##*/}/unlisted.txt"
  SC archive-evidence --dry-run --upload-dir "$TAMPER/${EVDIR##*/}" --s3-prefix s3://harness-evidence-bucket/prefix "${CA[@]}" --approval-record "$REC/archive-upload.env"
  case_begin "9.upload.unlisted-file-refused"
  a_refused "unlisted file"
  case_end
else
  case_skipped "9.upload.unlisted-file-refused" "no evidence directory was gathered"
fi

### Case 10: check.sh --------------------------------------------------------------------

SC check --dry-run
case_begin "10.dry-run.prints-six-gates-and-runs-nothing"
a_rc 0
for g in constitution-json hook-syntax shell-syntax shellcheck sbt terraform; do
  a_out_re '^STEP\[[0-9]+\] '"$g"': dry-run$'
done
a_out "CHECK_RESULT=DRY-RUN"
a_not_out_re '^(HOOK_SH_OK|SYNTAX_OK|SHELLCHECK_OK|JSON_OK|RUN:)'
a_not_out "GATE "
a_trip_empty
case_end

SC check --dry-run --skip-sbt --skip-terraform
case_begin "10.dry-run.skip-flags-are-announced"
a_rc 0
a_out "SKIP: --skip-sbt"
a_out "SKIP: --skip-terraform"
a_out "FAIL: shellcheck is absent (SHELLCHECK=absent)"
a_trip_empty
case_end

SC check --dry-run --allow-missing-shellcheck
case_begin "10.dry-run.allow-missing-shellcheck-is-announced"
a_rc 0
a_out "SKIP: shellcheck is absent and --allow-missing-shellcheck was given"
a_trip_empty
case_end

SC check --skip-sbt --skip-terraform --allow-missing-shellcheck
case_begin "10.skip-sbt.summary-never-passes-sbt"
a_rc 0
a_out "GATE constitution-json=PASS"
a_out "GATE hook-syntax=PASS"
a_out "GATE shell-syntax=PASS"
a_out "GATE shellcheck=SKIP"
a_out "GATE sbt=SKIP"
a_out "GATE terraform=SKIP"
a_not_out "GATE sbt=PASS"
a_out "SBT_RAN=no"
a_not_out "SBT_RAN=yes"
a_out_re '^SKIPPED_GATES=.*sbt'
a_out "NOTE: sbt was skipped, so CHECK_RESULT does not cover scalafmt, compilation or any test"
a_out_re '^STEP\[[0-9]+\] sbt: skip'
a_out_re '^CHECK_RESULT=PASS_WITH_SKIPS'
a_out_re '^CHECK_RESULT=PASS_WITH_SKIPS .*skipped: shellcheck sbt terraform'
a_out_re '^CHECK_RESULT=PASS_WITH_SKIPS .*this is not a full pass'
a_not_out_re '^CHECK_RESULT=PASS$'
a_not_out_re '^CHECK_RESULT=(FAIL|DRY-RUN)'
a_trip_empty
case_end

SC check --skip-sbt --skip-terraform
case_begin "10.shellcheck-absent.gate-fails-without-allow-flag"
a_rc 1
a_out "SHELLCHECK=absent"
a_out "GATE shellcheck=FAIL"
a_out "CHECK_RESULT=FAIL"
a_not_out "CHECK_RESULT=PASS"
a_not_out "GATE sbt=PASS"
a_out "SBT_RAN=no"
a_err "SHELLCHECK=absent"
a_trip_empty
case_end

SC check --keep-going --skip-sbt --skip-terraform
case_begin "10.shellcheck-absent.keep-going-still-fails-and-lists-skips"
a_rc 1
a_out "GATE shellcheck=FAIL"
a_out "GATE sbt=SKIP"
a_out "GATE terraform=SKIP"
a_out "CHECK_RESULT=FAIL"
a_trip_empty
case_end

SC check --sbt bogus
case_begin "10.refuse.unknown-sbt-mode"
a_rc_nonzero
a_err_evidence
a_err "--sbt must be check or quick"
a_trip_empty
case_end

SC check --no-such-flag
case_begin "10.refuse.unknown-flag"
a_rc_nonzero
a_err "unknown argument: --no-such-flag"
a_trip_empty
case_end

### Case 11: package.sh ---------------------------------------------------------------------

SC package --dry-run --out-dir "$OUT_BASE/package-2"
case_begin "11.dry-run.platform-and-tag-from-head"
a_dry_common
a_out "SOURCE git_sha=$HEAD_SHA dirty=no detached_head=no image_tag=$HEAD_SHA"
a_out_re '^PLATFORM=linux/(amd64|arm64) \(from cpu_architecture in the variable files\)'
a_out "WOULD_RUN: sbt packageJobs dockerLocal"
a_out "scholarchain-services:$HEAD_SHA"
a_out "scholarchain-loader:$HEAD_SHA"
[ ! -e "$OUT_BASE/package-2" ] || note_fail "the dry run created the out directory"
case_end

SC package --dry-run --platform linux/arm64 --out-dir "$OUT_BASE/package-3"
case_begin "11.dry-run.platform-argument"
a_dry_common
a_out "PLATFORM=linux/arm64 (from --platform argument) CPU_ARCHITECTURE=ARM64"
case_end

SC package --dry-run --platform linux/s390x --out-dir "$OUT_BASE/package-4"
case_begin "11.refuse.unknown-platform"
a_rc_nonzero
a_err_evidence
a_err "--platform must be linux/amd64 or linux/arm64"
a_trip_empty
case_end

SC package --dry-run --skip-images --out-dir "$OUT_BASE/package-5"
case_begin "11.dry-run.skip-images"
a_dry_common
a_out "WOULD_RUN: sbt packageJobs "
a_out "skipped: --skip-images"
case_end

dirty_on
SC package --dry-run --out-dir "$OUT_BASE/package-6"
case_begin "11.refuse.dirty-tree-dry-run"
a_rc_nonzero
a_err_evidence
a_err "refusing: dirty=yes"
a_not_out "DRY_RUN_COMPLETE"
a_trip_empty
case_end
SC package --out-dir "$OUT_BASE/package-7"
case_begin "11.refuse.dirty-tree-real-run-before-any-build"
a_rc_nonzero
a_err "refusing: dirty=yes"
a_not_out "PACKAGE_COMPLETE"
a_trip_empty
[ ! -e "$OUT_BASE/package-7" ] || note_fail "the refused run created the out directory"
case_end
SC package --dry-run --allow-dirty --out-dir "$OUT_BASE/package-8"
case_begin "11.dry-run.allow-dirty-tags-the-image-dirty"
a_dry_common
a_out "dirty=yes detached_head=no image_tag=${HEAD_SHA}-dirty"
a_out "building with --allow-dirty"
case_end
dirty_off

mkdir -p "$OUT_BASE/package-exists"
printf 'x\n' > "$OUT_BASE/package-exists/old-file"
SC package --dry-run --out-dir "$OUT_BASE/package-exists"
case_begin "11.refuse.out-dir-exists-and-not-empty"
a_rc_nonzero
a_err "already exists and is not empty"
a_trip_empty
case_end

SC package --dry-run --out-dir "$REPO/package-out"
case_begin "11.refuse.out-dir-inside-repository"
a_rc_nonzero
a_err "must not be inside the repository"
a_trip_empty
case_end

SC package --dry-run --out-dir relative/package-out
case_begin "11.refuse.out-dir-relative"
a_rc_nonzero
a_err "must be an absolute path"
a_trip_empty
case_end

### Case 12: secrets, the tripwire and the real repository ---------------------------------

SECRET_RE='AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIV''ATE KEY-----|aws_secret_''access_key'
case_begin "12.no-key-material-in-any-output"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
if grep -qE -- "$SECRET_RE" "$ALL_OUT"; then note_fail "an output line matches the key material patterns"; fi
for planted in "$FAKE_AKIA" "$FAKE_PEM" "$FAKE_VAR" "$FAKE_BEARER" "$FAKE_GHP" "$FAKE_GHPAT"; do
  if grep -qF -- "$planted" "$ALL_OUT"; then note_fail "a planted secret reached the output"; fi
done
case_end

case_begin "12.approval-record-contents-stay-out-of-output"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
if grep -qF -- "$CANARY" "$ALL_OUT"; then note_fail "a record comment (canary) was echoed"; fi
leak="$(grep -F -- "harness-operator" "$ALL_OUT" | grep -v '^APPROVAL_VERIFIED ' | head -n 1)"
[ -z "$leak" ] || note_fail "APPROVED_BY printed outside the APPROVAL_VERIFIED line: $(printf '%s' "$leak" | cut -c1-100)"
case_end

case_begin "12.output-was-collected"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$(wc -l < "$ALL_OUT" | tr -d ' ')" -gt 500 ] || note_fail "fewer than 500 output lines were collected: the scan covers too little"
case_end

case_begin "0.tripwire-never-reached-in-any-case"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ ! -s "$TRIP_ALL" ] || note_fail "a stub was called: $(head -n 1 "$TRIP_ALL" | cut -c1-120)"
case_end

case_begin "0.default-marker-directory-never-written"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
a_state_empty
case_end

case_begin "0.temp-repo-clean-at-end"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ -z "$(git -C "$REPO" status --porcelain)" ] || note_fail "a case left the temporary repository dirty"
case_end

case_begin "0.real-repository-untouched"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
REAL_STATUS_AFTER="$(git -C "$REAL_ROOT" status --porcelain 2>/dev/null)"
[ "$REAL_STATUS_BEFORE" = "$REAL_STATUS_AFTER" ] || note_fail "git status of the real repository changed during the run"
case_end

printf 'SKIPPED_CASES %s\n' "$SKIP"
printf 'RESULT pass=%s fail=%s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
