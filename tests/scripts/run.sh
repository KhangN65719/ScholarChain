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
  a_out "TARGET_REGION=us-east-2"
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
R_REGION=us-east-2
R_COST=""
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
  R_REGION=us-east-2
  R_COST="$CEIL"
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
    printf 'META_VERSION=1\nMODE=%s\nENVIRONMENT=course\nACCOUNT_ID=%s\nREGION=us-east-2\n' "$mode" "$ACCT"
    printf 'GIT_SHA=%s\nDIRTY=no\nIMAGE_TAG=%s\nCPU_ARCHITECTURE=X86_64\nCOST_CEILING_USD=%s\n' "$HEAD_SHA" "$tag" "$CEIL"
    printf 'PLAN_EXIT=2\nSTATEFUL_CHANGE=%s\nPLAN_DIGEST=%s\nCREATED_AT=%s\n' "$stateful" "$d" "$(iso_from_epoch "$NOW")"
  } > "$f.meta"
  printf '%s' "$d"
}

# Fixtures for scripts/upload-inputs.sh (Phase 14 work order 24). Small stand-ins that are
# committed with the temporary repository, so the tree stays clean. The EMR file names
# mirror the real scripts/emr directory but the content is a stand-in: the upload plan
# cases then do not depend on the real files, which get their own secret-scan cases
# (14.upload.real-emr-file.*). The Spark files live outside the repository, as the script
# requires.
UP_SNAP="harness-snap"
UP_DATA_BUCKET="harness-data-bucket"
UP_ART_BUCKET="harness-artifacts-bucket"
UP_SPARK="$WORK/spark-dir"
UP_MAXO=500
UP_MAXB=100000000

make_upload_fixtures_repo() {
  local f m
  mkdir -p "$REPO/datasets/subset" "$REPO/scripts/emr"
  for f in dblp-subset.xml openalex.jsonl crossref.jsonl orcid.jsonl; do
    printf 'harness dataset %s\n' "$f" > "$REPO/datasets/subset/$f"
  done
  for m in hadoop entity-resolution spark; do
    mkdir -p "$REPO/modules/$m/target/job/lib"
    printf 'harness main jar %s\n' "$m" > "$REPO/modules/$m/target/job/$m-job.jar"
    printf 'harness lib jar %s\n' "$m" > "$REPO/modules/$m/target/job/lib/dep-$m.jar"
  done
  for f in "$REAL_ROOT"/scripts/emr/*.sh "$REAL_ROOT"/scripts/emr/spark-yarn.conf; do
    [ -f "$f" ] || continue
    printf '# harness stand-in for %s\n' "${f##*/}" > "$REPO/scripts/emr/${f##*/}"
  done
  return 0
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
  make_upload_fixtures_repo || return 1
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

# The Spark 4.0.5 files for upload-inputs.sh: stand-in content, a real sha256 file.
up_write_sums() {
  printf '%s  spark-4.0.5-bin-hadoop3.tgz\n%s  spark-4.0.5-jars.zip\n' \
    "$(sha_of "$UP_SPARK/spark-4.0.5-bin-hadoop3.tgz")" "$(sha_of "$UP_SPARK/spark-4.0.5-jars.zip")" > "$UP_SPARK/spark-4.0.5.sha256"
}
up_write_spark() {
  mkdir -p "$UP_SPARK"
  printf 'harness spark tarball\n' > "$UP_SPARK/spark-4.0.5-bin-hadoop3.tgz"
  printf 'harness spark jars archive\n' > "$UP_SPARK/spark-4.0.5-jars.zip"
  up_write_sums
}
up_write_spark

# The file count and total size the upload plan must report for the fixtures above.
UP_N=0
UP_BYTES=0
while IFS= read -r up_f; do
  [ -n "$up_f" ] || continue
  UP_N=$((UP_N + 1))
  UP_BYTES=$((UP_BYTES + $(wc -c < "$up_f" | tr -d ' ')))
done < <(find "$REPO/datasets/subset" "$REPO/modules" "$REPO/scripts/emr" "$UP_SPARK" -type f | sort)
[ "$UP_N" -ge 18 ] || prereq_fail "the upload fixtures hold only $UP_N files"

# The upload-inputs.sh argument list is built from these settings, so a case changes one
# value (an empty value leaves the flag out).
UPV_SNAP=""
UPV_DB=""
UPV_AB=""
UPV_SPARK=""
UPV_MAXO=""
UPV_MAXB=""
UD_S=""
UD_A=""

up_reset() {
  UPV_SNAP="$UP_SNAP"
  UPV_DB="$UP_DATA_BUCKET"
  UPV_AB="$UP_ART_BUCKET"
  UPV_SPARK="$UP_SPARK"
  UPV_MAXO="$UP_MAXO"
  UPV_MAXB="$UP_MAXB"
}

# up_args FLAG RECORD: sets SN, AN and A. FLAG may be empty (a real run); RECORD is "-" to
# leave --approval-record out. The common arguments must be in CA already.
up_args() {
  local flag="$1" rec="$2"
  SN=upload-inputs
  AN=upload
  A=()
  if [ -n "$flag" ]; then A+=("$flag"); fi
  A+=("${CA[@]}")
  if [ -n "$UPV_SNAP" ]; then A+=(--snapshot "$UPV_SNAP"); fi
  if [ -n "$UPV_DB" ]; then A+=(--data-bucket "$UPV_DB"); fi
  if [ -n "$UPV_AB" ]; then A+=(--artifacts-bucket "$UPV_AB"); fi
  if [ -n "$UPV_SPARK" ]; then A+=(--spark-dir "$UPV_SPARK"); fi
  if [ -n "$UPV_MAXO" ]; then A+=(--max-objects "$UPV_MAXO"); fi
  if [ -n "$UPV_MAXB" ]; then A+=(--max-bytes "$UPV_MAXB"); fi
  if [ "$rec" != "-" ]; then A+=(--approval-record "$rec"); fi
}

# up_digest: --print-digest with the current settings; sets UD_S and UD_A.
up_digest() {
  common_args "$ACCT" us-east-2 "$CEIL"
  up_args --print-digest -
  run_build
  UD_S="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
  UD_A="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
}

# up_dry_record FILE: writes a record for the current settings and runs the dry run with it.
up_dry_record() {
  up_digest
  rec_base upload "$UD_S" "$UD_A" n/a n/a
  rec_write "$1"
  common_args "$ACCT" us-east-2 "$CEIL"
  up_args --dry-run "$1"
  run_build
}

# Puts the fixtures back after a case changed them.
up_restore() {
  git -C "$REPO" checkout -q -- .
  git -C "$REPO" clean -q -f -d
  up_write_spark
  up_reset
}

# The project cost ceiling is read from the copied lib.sh, not hard-coded: Phase 14 D6
# moves it from 25 to 20 in a parallel work order. Case 0.ceiling-pinned-to-D6 pins the
# expected value separately, so a lib.sh still at 25 fails that one case only.
CEIL="$(sed -n 's/^SC_PROJECT_CEILING_USD="\{0,1\}\([0-9][0-9]*\)"\{0,1\}$/\1/p' "$REPO/scripts/lib.sh" | head -n 1)"
printf '%s' "$CEIL" | grep -qE '^[0-9]+$' || prereq_fail "cannot read SC_PROJECT_CEILING_USD from scripts/lib.sh"
[ "$CEIL" -gt 5 ] || prereq_fail "SC_PROJECT_CEILING_USD is too small for the harness arithmetic: $CEIL"
CEIL_PLUS1=$((CEIL + 1))
CEIL_PLUS_CENT="$CEIL.01"
COST_OTHER=$((CEIL - 5))

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
# The execution input contract of design B (scripts/README.md, "Execution input contract"):
# {"mode":"capability-check"} or mode pipeline with snapshot, snapshots, run_id, loader_command.
PI_SNAP="harness-snap"
PI_SNAPS="dblp=harness-snap,openalex=harness-snap,crossref=harness-snap,orcid=harness-snap"
# Work order 46 (run id shape): in pipeline mode run_id must be exactly run-v1- plus a
# lower-case canonical UUID (43 characters), the path-safe slug of the stable run id
# run:v1:<uuid>. PI_RUN is the valid example id; PI_RUN_OTHER is a second valid id.
PI_UUID="3f2b8c1e-5a47-4d0e-9b6a-1c2d3e4f5a6b"
PI_RUN="run-v1-$PI_UUID"
PI_RUN_OTHER="run-v1-9d8c7b6a-1f2e-4a3b-8c5d-0e1f2a3b4c5d"
printf '{"mode":"pipeline","snapshot":"%s","snapshots":"%s","run_id":"%s","loader_command":["loader","--run","%s"]}\n' "$PI_SNAP" "$PI_SNAPS" "$PI_RUN" "$PI_RUN" > "$PIPE_IN"
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
REGION=us-east-2
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
  local key="$1" mode="$2" rec="$3" acct="${4:-$ACCT}" cost="${5:-$CEIL}" region="${6:-us-east-2}"
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
      A=("$flag" "${CA[@]}" --out-dir "$OUT_BASE/smoke" --fast-url http://10.20.0.10:8080 --slow-url http://10.20.0.11:8081 --query-secret-arn "arn:aws:secretsmanager:us-east-2:${acct}:secret:scholarchain/query-token-AbCdEf" --bedrock off) ;;
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
    upload)
      up_reset
      up_args "$flag" - ;;
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

KEYS="deploy rollback-image rollback-state smoke pipeline cleanup-flip cleanup-destroy experiment archive-upload upload"
SCRIPT_NAMES="plan deploy rollback smoke-test run-bounded-pipeline cleanup check package run-experiment archive-evidence upload-inputs"

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
build rollback-state digest - "$ACCT" "$COST_OTHER"
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
    upload)
      a_out "UPLOAD_PLAN objects=$UP_N bytes=$UP_BYTES max_objects=$UP_MAXO max_bytes=$UP_MAXB" ;;
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
  R_COST="$COST_OTHER"
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
  arg_variant account-10-digits "account id must be 12 digits" 1234567890 "$CEIL" us-east-2
  arg_variant account-13-digits "account id must be 12 digits" 1234567890123 "$CEIL" us-east-2
  arg_variant account-not-numeric "account id must be 12 digits" abcdefghijkl "$CEIL" us-east-2
  # Flipped from "region must be us-east-1" (Phase 14 D1: the approved Region is us-east-2).
  arg_variant region-not-us-east-2 "region must be us-east-2" "$ACCT" "$CEIL" eu-west-1
  arg_variant cost-zero "cost ceiling must be greater than zero" "$ACCT" 0 us-east-2
  arg_variant cost-negative "not a plain positive number" "$ACCT" -1 us-east-2
  arg_variant cost-non-numeric "not a plain positive number" "$ACCT" abc us-east-2
  arg_variant cost-exponent "not a plain positive number" "$ACCT" 1e1 us-east-2
  arg_variant cost-above-ceiling "exceeds the project ceiling" "$ACCT" "$CEIL_PLUS1" us-east-2
  arg_variant cost-just-above-ceiling "exceeds the project ceiling" "$ACCT" "$CEIL_PLUS_CENT" us-east-2
done

# Phase 14 D1: the account is locked to us-east-2. Every approval-gated form refuses
# us-east-1 and any other Region, with the D1 note, before any command is called.
# region_variant ID REGION: --region REGION against a record that is otherwise valid.
region_variant() {
  local id="$1" r="$2"
  build "$KEY" dry "$VREC" "$ACCT" "$CEIL" "$r"
  run_build
  case_begin "5.$KEY.$id"
  a_refused "region must be us-east-2, got: $r"
  a_err "Phase 14 decision D1"
  a_err "locked to us-east-2"
  a_err "never suggested"
  case_end
}

for key in $KEYS; do
  KEY="$key"
  prep_key "$key"
  fresh
  rec_write "$VREC"
  region_variant region-us-east-1-refused-with-d1-note us-east-1
  region_variant region-us-west-2-refused-with-d1-note us-west-2
  region_variant region-us-east-2a-zone-is-not-a-region us-east-2a
  region_variant region-letter-case-refused US-EAST-2

  build "$key" digest - "$ACCT" "$CEIL" us-east-1
  run_build
  case_begin "5.$key.region-us-east-1-print-digest-refused"
  a_rc_nonzero
  a_err_evidence
  a_err "region must be us-east-2, got: us-east-1"
  a_not_out "SCRIPT_DIGEST="
  a_not_out "ARGS_DIGEST="
  a_trip_empty
  case_end

  # Approval record whose REGION is us-east-1 while --region is us-east-2.
  R_REGION=us-east-1
  rv record-region-us-east-1 "record REGION differs from --region"
  R_REGION=us-west-2
  rv record-region-us-west-2 "record REGION differs from --region"
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
  "secret-other-account|--query-secret-arn|arn:aws:secretsmanager:us-east-2:210987654321:secret:abc|must be a Secrets Manager ARN" \
  "secret-us-east-1|--query-secret-arn|arn:aws:secretsmanager:us-east-1:${ACCT}:secret:abc|must be a Secrets Manager ARN in account $ACCT, us-east-2" \
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

SM_ARN_OK="arn:aws:secretsmanager:us-east-2:${ACCT}:secret:x"
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
A=("${A[@]}" --read-secret-arn "arn:aws:secretsmanager:us-east-2:210987654321:secret:x")
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
build smoke dry "$REC/smoke.env"
A=("${A[@]}" --read-secret-arn "arn:aws:secretsmanager:us-east-1:${ACCT}:secret:x")
run_build
case_begin "5.smoke.read-secret-arn-us-east-1"
a_refused "--read-secret-arn must be a Secrets Manager ARN in account $ACCT, us-east-2"
case_end

# run-bounded-pipeline.sh limits.
pipe_case() {
  local id="$1" frag="$2" size="$3" minutes="$4" spend="$5" burn="$6" stop="$7" input="$8"
  common_args "$ACCT" us-east-2 "$CEIL"
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
pipe_case spend-over-cost "exceeds the cost ceiling" small 60 "$CEIL_PLUS1" 0.60 first-failure "$PIPE_IN"
pipe_case unknown-stop-condition "unknown stop condition" small 60 0.75 0.60 first-failure,bogus "$PIPE_IN"
pipe_case input-missing "must be an existing absolute path" small 60 0.75 0.60 first-failure "$REC/no-such-input.json"
printf '{"mode":"pipeline","snapshot":"%s","snapshots":"%s","run_id":"%s","loader_command":["loader","%s"]}\n' "$PI_SNAP" "$PI_SNAPS" "$PI_RUN" "$FAKE_AKIA" > "$REC/pipeline-secret-input.json"
pipe_case input-key-material "possible credential material" small 60 0.75 0.60 first-failure "$REC/pipeline-secret-input.json"

# Work order 59 (follows infra-engineer work order 58): --max-minutes is validated with
# ^(0|[1-9][0-9]*)$, so a leading zero is refused with its own text (bash would read 08 as a
# bad octal number and 010 as 8) and never reaches the range message; 0 is still a whole
# number and still gets the range message (the minutes-zero case above).
PIPE_MIN_TEXT="--max-minutes must be a whole number (no leading zero, which bash would read as octal)"
pipe_minutes_refuse() {
  local id="$1" minutes="$2"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  A=(--dry-run "${CA[@]}" --size large --max-minutes "$minutes" --spend-ceiling-usd 5.00 --hourly-burn-usd 0.60 --stop-on first-failure --input-file "$PIPE_IN" --approval-record "$REC/pipeline.env")
  run_build
  case_begin "5.pipeline.$id"
  a_refused "$PIPE_MIN_TEXT"
  if grep -qF -- "must be 1 to" "$ERR"; then note_fail "the range message was printed for a value that is not a whole number"; fi
  a_not_out "PROJECTED_SPEND_USD"
  a_not_out "LIMITS "
  case_end
}
pipe_minutes_refuse minutes-leading-zero-08-refused-as-not-a-whole-number 08
pipe_minutes_refuse minutes-leading-zero-010-refused-not-read-as-octal-8 010
pipe_minutes_refuse minutes-double-zero-refused-as-not-a-whole-number 00

pipe_case minutes-zero-is-still-a-whole-number-and-gets-the-range-message "must be 1 to 150 for size large" large 0 5.00 0.60 first-failure "$PIPE_IN"
case_begin "5.pipeline.minutes-zero-does-not-get-the-whole-number-message"
if grep -qF -- "whole number" "$ERR"; then note_fail "the whole-number message was printed for 0"; fi
case_end

# pipe_minutes_accept ID SIZE MINUTES SPEND BURN PROJECTED: --print-digest, a record for it,
# then the dry run with the record. A value with no leading zero is accepted as before.
pipe_minutes_accept() {
  local id="$1" size="$2" minutes="$3" spend="$4" burn="$5" proj="$6" f="$REC/pipeline-min-$1.env"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  A=(--print-digest "${CA[@]}" --size "$size" --max-minutes "$minutes" --spend-ceiling-usd "$spend" --hourly-burn-usd "$burn" --stop-on first-failure --input-file "$PIPE_IN")
  run_build
  rec_base pipeline "$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)" "$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)" n/a n/a
  rec_write "$f"
  A=(--dry-run "${CA[@]}" --size "$size" --max-minutes "$minutes" --spend-ceiling-usd "$spend" --hourly-burn-usd "$burn" --stop-on first-failure --input-file "$PIPE_IN" --approval-record "$f")
  run_build
  case_begin "5.pipeline.$id"
  a_dry_common
  a_out "LIMITS size=$size max_minutes=$minutes spend_ceiling_usd=$spend hourly_burn_usd=$burn stop_on=first-failure cost_ceiling_usd=$CEIL"
  a_out "PROJECTED_SPEND_USD=$proj CEILING_USD=$spend"
  a_out "APPROVAL_VERIFIED action=pipeline"
  if grep -qF -- "whole number" "$ERR"; then note_fail "the whole-number message was printed for a valid value"; fi
  case_end
}
pipe_minutes_accept minutes-90-large-still-accepted large 90 1.00 0.62 0.93
pipe_minutes_accept minutes-110-large-the-s-run-5-command-still-accepted large 110 1.20 0.62 1.14

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
a_out "LIMITS size=small max_minutes=60 spend_ceiling_usd=0.75 hourly_burn_usd=0.60 stop_on=first-failure,dlq-nonempty cost_ceiling_usd=$CEIL"
a_out "OUTPUT_LOCATIONS=none-named"
a_not_out "OUTPUT_LOCATION="
a_out "INPUT_MODE=pipeline"
a_out "DATASET_SNAPSHOT=$PI_SNAP snapshots="
a_out_re " run_id=$PI_RUN\$"
a_not_out "DATASET_SNAPSHOT=not-recorded"
a_not_out "PIPELINE_RECORD="
a_not_out "EXECUTION_ARN="
a_not_out "START_UTC="
case_end

PIPE_IN_S3="$REC/pipeline-input-s3.json"
printf '{"mode":"pipeline","snapshot":"%s","snapshots":"%s","run_id":"%s","loader_command":["loader","--out","s3://harness-out-bucket/run-1/hadoop"]}\n' "$PI_SNAP" "$PI_SNAPS" "$PI_RUN" > "$PIPE_IN_S3"
common_args "$ACCT" us-east-2 "$CEIL"
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
a_out "INPUT_MODE=pipeline"
a_out "DATASET_SNAPSHOT=$PI_SNAP snapshots="
a_not_out "DATASET_SNAPSHOT=not-recorded"
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

common_args "$ACCT" us-east-2 "$CEIL"

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

common_args "$ACCT" us-east-2 "$CEIL"
SC cleanup --dry-run --mode plan-flip --environment staging --account-id "$ACCT" --region us-east-2 --cost-ceiling-usd "$CEIL" --i-am-the-human
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
  common_args "$ACCT" us-east-2 "$CEIL"
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
# Flipped from "manifest REGION must be us-east-1" (Phase 14 D1: the approved Region is us-east-2).
exp_refuse region-not-us-east-2 set REGION eu-west-1 "manifest REGION must be us-east-2"
exp_refuse region-us-east-1 set REGION us-east-1 "manifest REGION must be us-east-2, got: us-east-1"
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
exp_refuse spend-above-cost-ceiling set SPEND_CEILING_USD "$CEIL_PLUS1" "exceeds --cost-ceiling-usd"
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

common_args "$ACCT" us-east-2 "$CEIL"
SN=run-experiment
A=(--dry-run --manifest "$MF_BASE" --environment course --account-id "$ACCT" --cost-ceiling-usd "$CEIL" --approval-record "$REC/experiment.env")
run_build
case_begin "8.refuse.region-argument-missing"
a_refused "--region is required"
case_end

SN=run-experiment
A=(--dry-run --manifest "$MF_BASE" --environment course --account-id "$ACCT" --region eu-west-1 --cost-ceiling-usd "$CEIL" --approval-record "$REC/experiment.env")
run_build
# Flipped from "region must be us-east-1" (Phase 14 D1: the approved Region is us-east-2).
case_begin "8.refuse.region-argument-not-us-east-2"
a_refused "region must be us-east-2"
case_end

SN=run-experiment
A=(--dry-run --manifest "$MF_BASE" --environment course --account-id "$ACCT" --region us-east-1 --cost-ceiling-usd "$CEIL" --approval-record "$REC/experiment.env")
run_build
case_begin "8.refuse.region-argument-us-east-1"
a_refused "region must be us-east-2, got: us-east-1"
a_err "Phase 14 decision D1"
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
common_args "$ACCT" us-east-2 "$CEIL"
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
common_args "$ACCT" us-east-2 "$CEIL"
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

### Case 13: Phase 14 Region lock (decision D1) ---------------------------------------
# Work order 03 (phase 14). Placed before case 12 so that the outputs of these cases
# are covered by the secret scan and the tripwire check.

case_begin "13.ceiling-pinned-to-D6"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$CEIL" = "20" ] || note_fail "scripts/lib.sh SC_PROJECT_CEILING_USD is $CEIL; Phase 14 D6 sets 20 (work order 02 changes it)"
case_end

# plan.sh: --region other than us-east-2, dry run, digest and real run.
for r in us-east-1 us-west-2 us-east-2a US-EAST-2; do
  build plan dry - "$ACCT" "$CEIL" "$r"
  run_build
  case_begin "13.plan.region-$r-refused-with-d1-note"
  a_refused "region must be us-east-2, got: $r"
  a_err "Phase 14 decision D1"
  a_err "locked to us-east-2"
  a_not_out "TARGET_REGION="
  case_end
done

build plan digest - "$ACCT" "$CEIL" us-east-1
run_build
case_begin "13.plan.print-digest-region-us-east-1-refused"
a_rc_nonzero
a_err_evidence
a_err "region must be us-east-2, got: us-east-1"
a_not_out "SCRIPT_DIGEST="
a_trip_empty
case_end

# Real runs (no --dry-run) are refused in the shared validation, before the identity
# check and before any stubbed command.
build plan dry - "$ACCT" "$CEIL" us-east-1
A=("${A[@]:1}")
run_build
case_begin "13.plan.real-run-region-us-east-1-refused"
a_refused "region must be us-east-2, got: us-east-1"
a_err "Phase 14 decision D1"
a_not_out "IDENTITY_OK"
a_not_out "identity-check"
case_end

build deploy dry "$REC/deploy.env" "$ACCT" "$CEIL" us-east-1
A=("${A[@]:1}")
run_build
case_begin "13.deploy.real-run-region-us-east-1-refused"
a_refused "region must be us-east-2, got: us-east-1"
a_err "Phase 14 decision D1"
a_not_out "IDENTITY_OK"
a_not_out "identity-check"
case_end

build smoke dry "$REC/smoke.env" "$ACCT" "$CEIL" us-east-1
A=("${A[@]:1}")
run_build
case_begin "13.smoke.real-run-region-us-east-1-refused"
a_refused "region must be us-east-2, got: us-east-1"
a_err "Phase 14 decision D1"
a_not_out "IDENTITY_OK"
a_not_out "identity-check"
case_end

build experiment dry "$REC/experiment.env" "$ACCT" "$CEIL" us-east-1
A=("${A[@]:1}")
run_build
case_begin "13.experiment.real-run-region-us-east-1-refused"
a_refused "region must be us-east-2, got: us-east-1"
a_err "Phase 14 decision D1"
a_not_out "IDENTITY_OK"
a_not_out "identity-check"
case_end

# check_tfvars_region through plan.sh (course.tfvars is checked on every plan run;
# --extra-var-file files are checked too). Files live outside the repository.
TFV_DIR="$REC/tfv"
mkdir -p "$TFV_DIR"
tfv_write() {
  local name="$1"
  shift
  : > "$TFV_DIR/$name.tfvars"
  while [ "$#" -gt 0 ]; do
    printf '%s\n' "$1" >> "$TFV_DIR/$name.tfvars"
    shift
  done
}

# plan_tfv ID MODE FRAGMENT: the var file TFV_DIR/ID.tfvars is given as --extra-var-file.
# MODE is refuse (the D1 note and FRAGMENT on stderr) or accept (the dry run completes).
plan_tfv() {
  local id="$1" mode="$2" frag="${3:-}"
  build plan dry -
  A=("${A[@]}" --extra-var-file "$TFV_DIR/$id.tfvars")
  run_build
  case_begin "13.plan.tfvars.$id.$mode"
  if [ "$mode" = "refuse" ]; then
    a_refused "$frag"
    a_err "Phase 14 decision D1"
    a_err "locked to us-east-2"
  else
    a_dry_common
    a_out "TARGET_REGION=us-east-2"
    a_not_out "EVIDENCE"
  fi
  case_end
}

tfv_write region-east1 'region = "us-east-1"'
plan_tfv region-east1 refuse 'sets region = "us-east-1"; region must be us-east-2'
tfv_write region-west2 'region = "us-west-2"'
plan_tfv region-west2 refuse 'sets region = "us-west-2"; region must be us-east-2'
tfv_write region-no-spaces 'region="us-east-1"'
plan_tfv region-no-spaces refuse 'sets region = "us-east-1"'
tfv_write region-last-assignment-wins-bad 'region = "us-east-2"' 'region = "us-east-1"'
plan_tfv region-last-assignment-wins-bad refuse 'sets region = "us-east-1"'
tfv_write region-east2 'region = "us-east-2"'
plan_tfv region-east2 accept
tfv_write region-comments-ignored '# region = "us-east-1"' 'region = "us-east-2" # not "us-east-1"'
plan_tfv region-comments-ignored accept
tfv_write az-east1 'availability_zones = ["us-east-1a", "us-east-1b"]'
plan_tfv az-east1 refuse 'sets an availability zone outside us-east-2: us-east-1a'
tfv_write az-mixed 'availability_zones = ["us-east-2a", "us-east-1b"]'
plan_tfv az-mixed refuse 'sets an availability zone outside us-east-2: us-east-1b'
tfv_write az-west2 'availability_zones = ["us-west-2a", "us-west-2b"]'
plan_tfv az-west2 refuse 'sets an availability zone outside us-east-2: us-west-2a'
tfv_write az-prefix-trap 'availability_zones = ["eu-us-east-2a", "us-east-2b"]'
plan_tfv az-prefix-trap refuse 'sets an availability zone outside us-east-2: eu-us-east-2a'
tfv_write az-east2 'availability_zones = ["us-east-2a", "us-east-2b"]'
plan_tfv az-east2 accept
tfv_write region-and-az-east2 'region = "us-east-2"' 'availability_zones = ["us-east-2a", "us-east-2b"]'
plan_tfv region-and-az-east2 accept
tfv_write az-comment-ignored '# availability_zones = ["us-east-1a"]' 'availability_zones = ["us-east-2a", "us-east-2b"] # not us-east-1a'
plan_tfv az-comment-ignored accept
tfv_write empty-file
plan_tfv empty-file accept
tfv_write region-and-az-both-wrong 'region = "us-east-1"' 'availability_zones = ["us-east-1a", "us-east-1b"]'
plan_tfv region-and-az-both-wrong refuse 'sets region = "us-east-1"; region must be us-east-2'

# A good first file does not excuse a bad second one.
build plan dry -
A=("${A[@]}" --extra-var-file "$TFV_DIR/region-east2.tfvars" --extra-var-file "$TFV_DIR/az-east1.tfvars")
run_build
case_begin "13.plan.tfvars.second-extra-var-file-bad.refuse"
a_refused "sets an availability zone outside us-east-2: us-east-1a"
a_err "Phase 14 decision D1"
case_end

# check_tfvars_region called directly (the library function, as case 6 does for
# consume_approval).
check_with_lib() {
  RC=0
  (
    # SC_SCRIPT is read by script_digest in the sourced lib.sh, which shellcheck does not follow here.
    # shellcheck disable=SC2034
    SC_SCRIPT="$REPO/scripts/plan.sh"
    # shellcheck source=/dev/null
    source "$REPO/scripts/lib.sh"
    check_tfvars_region "$1"
  ) > "$LOGDIR/chk.out" 2> "$LOGDIR/chk.err" < /dev/null || RC=$?
  OUT="$LOGDIR/chk.out"
  ERR="$LOGDIR/chk.err"
}

check_with_lib "$TFV_DIR/region-east1.tfvars"
case_begin "13.lib.check_tfvars_region.region-east1-refused"
a_rc 1
a_err_evidence
a_err 'sets region = "us-east-1"; region must be us-east-2 (Phase 14 decision D1'
case_end
check_with_lib "$TFV_DIR/az-east1.tfvars"
case_begin "13.lib.check_tfvars_region.az-east1-refused"
a_rc 1
a_err_evidence
a_err "sets an availability zone outside us-east-2: us-east-1a (Phase 14 decision D1"
case_end
check_with_lib "$TFV_DIR/az-east2.tfvars"
case_begin "13.lib.check_tfvars_region.az-east2a-east2b-accepted"
a_rc 0
[ ! -s "$ERR" ] || note_fail "stderr is not empty"
[ ! -s "$OUT" ] || note_fail "stdout is not empty"
case_end
check_with_lib "$TFV_DIR/region-east2.tfvars"
case_begin "13.lib.check_tfvars_region.region-east2-accepted"
a_rc 0
[ ! -s "$ERR" ] || note_fail "stderr is not empty"
case_end
check_with_lib "$TFV_DIR/empty-file.tfvars"
case_begin "13.lib.check_tfvars_region.no-region-no-zones-accepted"
a_rc 0
[ ! -s "$ERR" ] || note_fail "stderr is not empty"
case_end

# Every committed var file (and the staging copy the harness makes) passes.
TFV_N=0
while IFS= read -r tf_file; do
  [ -n "$tf_file" ] || continue
  TFV_N=$((TFV_N + 1))
  check_with_lib "$tf_file"
  tf_rel="${tf_file#"$REPO"/}"
  case_begin "13.lib.check_tfvars_region.committed.$(printf '%s' "$tf_rel" | tr '/' '.')"
  a_rc 0
  [ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
  case_end
done < <(find "$REPO/infra/terraform" -name '*.tfvars' -type f | sort)
case_begin "13.lib.check_tfvars_region.committed.found-at-least-eight"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$TFV_N" -ge 8 ] || note_fail "only $TFV_N var files were found under infra/terraform"
case_end

# Terraform defaults, read as plain text from the copied files (no terraform binary).
TF_VARS_FILE="$REPO/infra/terraform/environments/course/variables.tf"
tf_default() {
  awk -v v="$1" '
    $0 ~ "^variable \"" v "\" " { f = 1; next }
    f && /^}/ { exit }
    f && /^[[:space:]]*default[[:space:]]*=/ { sub(/^[^=]*=[[:space:]]*/, ""); print; exit }
  ' "$TF_VARS_FILE"
}

case_begin "13.terraform.region-default-and-validation-are-us-east-2"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$(tf_default region)" = '"us-east-2"' ] || note_fail "region default is $(tf_default region), not \"us-east-2\""
grep -qF 'condition     = var.region == "us-east-2"' "$TF_VARS_FILE" || note_fail "no validation that var.region equals us-east-2"
grep -qF 'Phase 14 decision D1' "$TF_VARS_FILE" || note_fail "the region validation message does not name decision D1"
case_end

case_begin "13.terraform.availability-zone-defaults-are-us-east-2a-and-b"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$(tf_default availability_zones)" = '["us-east-2a", "us-east-2b"]' ] || note_fail "availability_zones default is $(tf_default availability_zones)"
case_end

case_begin "13.terraform.haiku-profile-default-is-the-us-geo-profile"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ "$(tf_default bedrock_haiku_profile_id)" = '"us.anthropic.claude-haiku-4-5-20251001-v1:0"' ] || note_fail "bedrock_haiku_profile_id default is $(tf_default bedrock_haiku_profile_id)"
case_end

# The smoke script's Bedrock defaults agree with the Terraform defaults (Phase 14 D5).
TF_HAIKU="$(tf_default bedrock_haiku_profile_id | tr -d '"')"
TF_DIM="$(tf_default vector_dimension)"
case_begin "13.smoke-defaults-agree-with-terraform-defaults"
OUT="$LOGDIR/none.out"; ERR="$LOGDIR/none.err"; RC=0
[ -n "$TF_HAIKU" ] && [ -n "$TF_DIM" ] || note_fail "a Terraform default could not be read"
case "$SM_CANON_NO" in
  *";haiku=$TF_HAIKU") ;;
  *) note_fail "smoke-test.sh default Haiku profile differs from the Terraform default $TF_HAIKU" ;;
esac
case "$SM_CANON_NO" in
  *";dim=$TF_DIM;"*) ;;
  *) note_fail "smoke-test.sh default vector dimension differs from the Terraform default $TF_DIM" ;;
esac
case_end

### Case 14: Phase 14 work order 24 -------------------------------------------------------
# The pipeline execution input contract of design B (run-bounded-pipeline.sh), the new
# scripts/upload-inputs.sh (its approval-gated cases are in the KEYS loops above, key
# "upload"), and the static content of scripts/emr/*. Placed before case 12 so that the
# outputs of these cases are covered by the secret scan and the tripwire check. A case
# named in the work order report as "predicted to fail" documents a defect found by
# reading the script; it is not loosened here.

static_begin() {
  case_begin "$1"
  OUT="$LOGDIR/none.out"
  ERR="$LOGDIR/none.err"
  RC=0
}

CODE_DIR="$WORK/code"
mkdir -p "$CODE_DIR"

# code_of FILE: prints the path of a copy of FILE without comment-only lines, so a rule
# written in a comment ("no sync, no delete") is not mistaken for code. The copy lives in a
# directory named after the whole path of FILE and keeps FILE's own name (so messages still
# read "main.tf lacks ..."). Work order 99: the copy used to be $CODE_DIR/<basename>, so two
# files with the same name (the three main.tf of infra/terraform) shared one path and the
# later call silently replaced the earlier copy under a path the case still held.
code_of() {
  local d="$CODE_DIR/code-of/${1//\//_}"
  mkdir -p "$d"
  grep -v '^[[:space:]]*#' "$1" > "$d/${1##*/}" 2> /dev/null
  printf '%s' "$d/${1##*/}"
}

# extract_fn FILE NAME OUT: the function NAME (from "NAME() {" in column 1 to the first later line that
# is exactly "}"). OUT is always written, empty when NAME is not found. Work order 99: defined here, before
# the first case that calls it; it used to be defined 650 lines below its first call, and bash then
# reports "command not found" for a function it has not read yet, so OUT was never created.
extract_fn() {
  awk -v start="$2() {" 'index($0, start) == 1 { f = 1 } f { print } f && $0 == "}" { exit }' "$1" > "$3"
}

# f_min_lines FILE N WHAT: the cut-out FILE exists and holds at least N lines. A missing file counts as
# 0 lines. The older guards of the form [ "$(wc -l < FILE ...)" -lt N ] print an error and pass silently
# when FILE does not exist, which is how a failed cut-out hides behind the absence checks that follow it.
f_min_lines() {
  local n=0
  if [ -f "$1" ]; then n="$(wc -l < "$1" | tr -d ' ')"; fi
  [ "$n" -ge "$2" ] || note_fail "$3 has $n line(s), expected at least $2"
}

f_has() {
  grep -qF -- "$2" "$1" || note_fail "${1##*/} lacks: $2"
}

f_has_not() {
  if grep -qF -- "$2" "$1"; then note_fail "${1##*/} holds: $2"; fi
}

f_has_not_re() {
  if grep -qE -- "$2" "$1"; then note_fail "${1##*/} matches: $2"; fi
}

f_count_is() {
  local n
  n="$(grep -cF -- "$2" "$1")"
  [ "$n" = "$3" ] || note_fail "${1##*/} holds '$2' $n time(s), expected $3"
}

line_of() {
  grep -nF -- "$2" "$1" | head -n 1 | cut -d: -f1
}

f_before() {
  local a b
  a="$(line_of "$1" "$2")"
  b="$(line_of "$1" "$3")"
  if [ -z "$a" ] || [ -z "$b" ]; then
    note_fail "${1##*/}: '$2' or '$3' not found"
    return 0
  fi
  [ "$a" -lt "$b" ] || note_fail "${1##*/}: '$2' (line $a) is not before '$3' (line $b)"
}

### 14.pipeline: execution input contract -------------------------------------------------

PI_TAIL=(--size small --max-minutes 45 --spend-ceiling-usd 0.50 --hourly-burn-usd 0.47 --stop-on first-failure)
J_SNAP="\"$PI_SNAP\""
J_SNAPS="\"$PI_SNAPS\""
J_RUN="\"$PI_RUN\""
# The last loader element carries the run id, as the real loader argument does: it starts
# with run- and equals run_id, so every accepted case here also exercises the "equal is
# accepted" side of the loader_command check (work order 46).
J_LOAD="[\"loader\",\"--run\",\"$PI_RUN\"]"
# Work order 110 (infra work order 108): a trailing option element of loader_command. The script skips every
# element that starts with two hyphens when it finds the element to compare with run_id, and prints and
# records LOADER_GRAPH_ONLY=yes when "--graph-only" is among the elements.
LO_CLS="scholarchain.rag.AwsIngestMain"
J_LOAD_GO="[\"loader\",\"--run\",\"$PI_RUN\",\"--graph-only\"]"
J_LOAD_AIM_GO="[\"$LO_CLS\",\"$PI_RUN\",\"--graph-only\"]"
# shellcheck disable=SC2016
J_CMDSUB='"$(id)"'

pi_json() {
  printf '{"mode":"pipeline","snapshot":%s,"snapshots":%s,"run_id":%s,"loader_command":%s}' "$1" "$2" "$3" "$4"
}

pi_rep() {
  awk -v n="$1" 'BEGIN { for (i = 0; i < n; i++) printf "a" }'
}

# pi_refuse ID JSON FRAGMENT: a dry run with this input is refused by the input validation,
# before anything is printed from the input and before the approval record is read.
pi_refuse() {
  local id="$1" json="$2" frag="$3" f="$REC/pi-$1.json"
  printf '%s' "$json" > "$f"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$REC/pipeline.env")
  run_build
  case_begin "14.pipeline.input.$id"
  a_refused "execution input refused"
  a_err "$frag"
  a_not_out "INPUT_MODE="
  a_not_out "DATASET_SNAPSHOT="
  case_end
}

# pi_dry_with FILE RECORD: --print-digest, a record for it, then the dry run with the record.
pi_dry_with() {
  local f="$1" r="$2"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f")
  run_build
  rec_base pipeline "$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)" "$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)" n/a n/a
  rec_write "$r"
  A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$r")
  run_build
}

# Accepted: capability-check.
printf '{"mode":"capability-check"}\n' > "$REC/pi-capability.json"
pi_dry_with "$REC/pi-capability.json" "$REC/pi-capability.env"
case_begin "14.pipeline.capability-check.accepted-and-records-no-dataset"
a_dry_common
a_out "INPUT_MODE=capability-check"
a_out "DATASET_SNAPSHOT=n/a (capability-check reads no dataset)"
a_not_out "DATASET_SNAPSHOT=not-recorded"
a_out "OUTPUT_PREFIX=reports/capability/ (key prefix in the data bucket)"
a_not_out "curated/runs/"
a_not_out "run_id="
a_out "OUTPUT_LOCATIONS=none-named"
a_out "PROJECTED_SPEND_USD=0.35 CEILING_USD=0.50"
a_out "LIMITS size=small max_minutes=45 spend_ceiling_usd=0.50 hourly_burn_usd=0.47 stop_on=first-failure cost_ceiling_usd=$CEIL"
a_out "APPROVAL_VERIFIED action=pipeline"
a_out "APPROVAL_NOT_CONSUMED dry-run"
case_end
# Work order 87: the teardown-check text of capability-check is unchanged by the loader-only mode
# (infra work order 86 branched the line on INPUT_MODE); the loader-only text must not leak into it.
case_begin "14.pipeline.capability-check.dry-run-teardown-check-text-is-unchanged"
a_out "WOULD_RUN: up to 10 checks for active EMR clusters created after the start"
a_not_out "one check that no EMR cluster named"
a_not_out "loader-only"
a_order "teardown-check: dry-run" "WOULD_RUN: up to 10 checks for active EMR clusters created after the start" "report: dry-run"
case_end

# Accepted: pipeline mode. One dry run feeds the next two cases.
pi_dry_with "$PIPE_IN" "$REC/pi-pipeline.env"
case_begin "14.pipeline.pipeline-mode.output-prefixes-follow-the-run-id"
a_dry_common
a_out "INPUT_MODE=pipeline"
a_out "OUTPUT_PREFIX=curated/runs/$PI_RUN/ (key prefix in the data bucket)"
a_out "OUTPUT_PREFIX=graph/runs/$PI_RUN/ (key prefix in the data bucket)"
a_out "OUTPUT_PREFIX=semantic-context/runs/$PI_RUN/ (key prefix in the data bucket)"
a_out "OUTPUT_PREFIX=reports/runs/$PI_RUN/ (key prefix in the data bucket)"
a_not_out "reports/capability/"
a_out "APPROVAL_VERIFIED action=pipeline"
case_end
case_begin "14.pipeline.pipeline-mode.snapshots-spec-printed-whole"
a_dry_common
a_out "DATASET_SNAPSHOT=$PI_SNAP snapshots=$PI_SNAPS run_id=$PI_RUN"
case_end
# Work order 87: the same for pipeline mode (the output prefixes and the dry-run teardown text did
# not change with the loader-only mode).
case_begin "14.pipeline.pipeline-mode.dry-run-teardown-check-text-and-markers-are-unchanged"
a_out "WOULD_RUN: up to 10 checks for active EMR clusters created after the start"
a_not_out "one check that no EMR cluster named"
a_not_out "loader-only"
a_order "teardown-check: dry-run" "WOULD_RUN: up to 10 checks for active EMR clusters created after the start" "report: dry-run"
a_not_out "DATASET_SNAPSHOT=n/a"
[ "$(grep -c '^OUTPUT_PREFIX=' "$OUT")" = "4" ] || note_fail "pipeline mode prints $(grep -c '^OUTPUT_PREFIX=' "$OUT") OUTPUT_PREFIX lines, expected 4"
a_not_out "/loader/ (key prefix"
case_end

# Boundaries: 300 characters for the snapshots spec are accepted. A 64-character run id was
# accepted before work order 46; it passes the old character and length rule and is now
# refused by the shape rule (the new text, naming the required shape).
RUN64="$(pi_rep 64)"
pi_refuse run-id-64-chars-refused-with-the-shape-text "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$RUN64\"" "$J_LOAD")" "run_id must be exactly run-v1- followed by a lower-case canonical UUID"
SNAPS300="$(pi_rep 300)"
printf '%s\n' "$(pi_json "$J_SNAP" "\"$SNAPS300\"" "$J_RUN" "$J_LOAD")" > "$REC/pi-snaps300.json"
pi_dry_with "$REC/pi-snaps300.json" "$REC/pi-snaps300.env"
case_begin "14.pipeline.input.snapshots-300-chars-accepted"
a_dry_common
a_out "snapshots=$SNAPS300 run_id=$PI_RUN"
case_end

# Refused: mode.
pi_refuse unknown-mode '{"mode":"batch"}' "mode must be capability-check or pipeline"
pi_refuse missing-mode '{}' "mode must be capability-check or pipeline"
pi_refuse mode-not-a-string '{"mode":1}' "mode must be capability-check or pipeline"
pi_refuse mode-wrong-case "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/"pipeline"/"Pipeline"/')" "mode must be capability-check or pipeline"
pi_refuse mode-trailing-space '{"mode":"capability-check "}' "mode must be capability-check or pipeline"

# Refused: keys.
pi_refuse capability-unknown-key '{"mode":"capability-check","run_id":"x"}' "unknown input key(s) for mode capability-check: run_id"
pi_refuse capability-with-all-pipeline-fields "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/"pipeline"/"capability-check"/')" "unknown input key(s) for mode capability-check: loader_command, run_id, snapshot, snapshots"
pi_refuse pipeline-unknown-key "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/}$/,"extra":1}/')" "unknown input key(s) for mode pipeline: extra"
pi_refuse pipeline-old-hadoop-args-key "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/}$/,"hadoop_args":[]}/')" "unknown input key(s) for mode pipeline: hadoop_args"
pi_refuse pipeline-old-spark-args-key "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/}$/,"spark_args":[]}/')" "unknown input key(s) for mode pipeline: spark_args"
pi_refuse old-shape-without-mode '{"hadoop_args":[],"spark_args":[],"loader_command":[]}' "mode must be capability-check or pipeline"
pi_refuse old-shape-with-mode-pipeline '{"mode":"pipeline","hadoop_args":[],"spark_args":[],"loader_command":[]}' "unknown input key(s) for mode pipeline: hadoop_args, spark_args"

# Refused: snapshot.
pi_refuse snapshot-missing '{"mode":"pipeline","snapshots":"dblp=a","run_id":"r1","loader_command":["l"]}' "snapshot is missing or not valid"
pi_refuse snapshot-not-a-string "$(pi_json 123 "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshot-with-slash "$(pi_json '"a/b"' "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshot-with-space "$(pi_json '"a b"' "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshot-empty "$(pi_json '""' "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshot-65-chars "$(pi_json "\"$(pi_rep 65)\"" "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshot-non-ascii "$(pi_json "\"sn$(printf '\303\251')p\"" "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"

# Refused: snapshots.
pi_refuse snapshots-missing '{"mode":"pipeline","snapshot":"s1","run_id":"r1","loader_command":["l"]}' "snapshots is missing or not valid"
pi_refuse snapshots-with-space "$(pi_json "$J_SNAP" '"dblp=a openalex=b"' "$J_RUN" "$J_LOAD")" "snapshots is missing or not valid"
pi_refuse snapshots-with-semicolon "$(pi_json "$J_SNAP" '"dblp=a;rm -rf x"' "$J_RUN" "$J_LOAD")" "snapshots is missing or not valid"
pi_refuse snapshots-command-substitution "$(pi_json "$J_SNAP" "$J_CMDSUB" "$J_RUN" "$J_LOAD")" "snapshots is missing or not valid"
pi_refuse snapshots-301-chars "$(pi_json "$J_SNAP" "\"$(pi_rep 301)\"" "$J_RUN" "$J_LOAD")" "snapshots is missing or not valid"

# Refused: run id.
pi_refuse run-id-missing '{"mode":"pipeline","snapshot":"s1","snapshots":"dblp=a","loader_command":["l"]}' "run_id is missing or not valid"
pi_refuse run-id-with-slash "$(pi_json "$J_SNAP" "$J_SNAPS" '"a/b"' "$J_LOAD")" "run_id is missing or not valid"
pi_refuse run-id-empty "$(pi_json "$J_SNAP" "$J_SNAPS" '""' "$J_LOAD")" "run_id is missing or not valid"
pi_refuse run-id-65-chars "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$(pi_rep 65)\"" "$J_LOAD")" "run_id is missing or not valid"
pi_refuse run-id-with-space "$(pi_json "$J_SNAP" "$J_SNAPS" '"run 1"' "$J_LOAD")" "run_id is missing or not valid"
pi_refuse run-id-not-a-string "$(pi_json "$J_SNAP" "$J_SNAPS" '["x"]' "$J_LOAD")" "run_id is missing or not valid"

# Refused: loader_command.
pi_refuse loader-command-missing '{"mode":"pipeline","snapshot":"s1","snapshots":"dblp=a","run_id":"r1"}' "loader_command must be a non-empty list of non-empty strings"
pi_refuse loader-command-empty-list "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '[]')" "loader_command must be a non-empty list of non-empty strings"
pi_refuse loader-command-not-a-list "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '"loader"')" "loader_command must be a non-empty list of non-empty strings"
pi_refuse loader-command-empty-string-element "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '[""]')" "loader_command must be a non-empty list of non-empty strings"
pi_refuse loader-command-non-string-element "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '["loader",1]')" "loader_command must be a non-empty list of non-empty strings"

# Refused: not an object.
pi_refuse not-json '{not json' "input is not valid JSON"
pi_refuse empty-file '' "input is not valid JSON"
pi_refuse json-array '[]' "input must be a JSON object"
pi_refuse json-null 'null' "input must be a JSON object"
pi_refuse json-string '"capability-check"' "input must be a JSON object"

# Adversarial (report 24): "." and ".." would make "curated/runs/../", and a final newline
# must not slip past the id patterns. The script now refuses "." and ".." with its own
# message ("must not be . or .."); the newline cases are refused as not valid.
pi_refuse run-id-dot-dot "$(pi_json "$J_SNAP" "$J_SNAPS" '".."' "$J_LOAD")" "run_id must not be . or .."
pi_refuse snapshot-dot-dot "$(pi_json '".."' "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot must not be . or .."
pi_refuse run-id-trailing-newline "$(pi_json "$J_SNAP" "$J_SNAPS" '"run1\n"' "$J_LOAD")" "run_id is missing or not valid"
pi_refuse snapshot-trailing-newline "$(pi_json '"snap1\n"' "$J_SNAPS" "$J_RUN" "$J_LOAD")" "snapshot is missing or not valid"
pi_refuse snapshots-trailing-newline "$(pi_json "$J_SNAP" '"dblp=a\n"' "$J_RUN" "$J_LOAD")" "snapshots is missing or not valid"

# Work order 46: the run id shape. In pipeline mode run_id must be exactly run-v1- plus a
# lower-case canonical UUID (8-4-4-4-12 hex, 43 characters). Order of the checks in the
# script's Python block: the old character and length rule (run_id is missing or not valid),
# then the loader_command shape, then the shape rule (the text below), then the loader
# mismatch rule. Each refusal comes before the approval record is read, in a real run
# (before identity-check), in a dry run and under --print-digest, and calls no stub.
PI_SHAPE_TEXT="run_id must be exactly run-v1- followed by a lower-case canonical UUID"
PI_LOADER_TEXT="the last element of loader_command starts with run- but is not equal to run_id"

# pi_shape_refuse ID JSON FRAGMENT [NOPRINT]: three cases, one per mode. NOPRINT, when
# given, is text that must not appear on stdout or stderr.
pi_shape_refuse() {
  local id="$1" json="$2" frag="$3" noprint="${4:-}" f="$REC/pis-$1.json" mode
  printf '%s' "$json" > "$f"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  for mode in real dry digest; do
    case "$mode" in
      real) A=("${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$REC/pipeline.env") ;;
      dry) A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$REC/pipeline.env") ;;
      digest) A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f") ;;
    esac
    run_build
    case_begin "14.pipeline.run-id-shape.$id.$mode"
    a_refused "$frag"
    a_err "execution input refused"
    a_not_out "INPUT_MODE="
    a_not_out "DATASET_SNAPSHOT="
    a_not_out "SCRIPT_DIGEST="
    a_not_out "ARGS_DIGEST="
    a_not_out "identity-check"
    a_not_out "IDENTITY_OK"
    a_not_out "EXECUTION_ARN="
    a_not_out "LOADER_GRAPH_ONLY"
    if [ -n "$noprint" ] && grep -qF -- "$noprint" "$OUT" "$ERR"; then note_fail "the text that must not be printed was printed"; fi
    if [ "$frag" = "$PI_SHAPE_TEXT" ]; then
      a_err "characters; the value is not printed)"
      if grep -qF -- "(got: " "$ERR"; then note_fail "stderr holds the old (got: <value>) echo"; fi
    fi
    if [ -n "${5:-}" ]; then a_err "$5"; fi
    case_end
  done
}

# pi_accept ID JSON RUNID: a dry run with a record accepts this input and prints RUNID.
pi_accept() {
  local id="$1" json="$2" want="$3" f="$REC/pia-$1.json"
  printf '%s\n' "$json" > "$f"
  pi_dry_with "$f" "$REC/pia-$id.env"
  case_begin "14.pipeline.run-id-shape.$id"
  a_dry_common
  a_out "INPUT_MODE=pipeline"
  a_out "APPROVAL_VERIFIED action=pipeline"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out_re " run_id=$want\$"
  a_not_out "LOADER_GRAPH_ONLY"
  case_end
}

PI_UUID_UPPER="$(printf '%s' "$PI_UUID" | tr 'a-f' 'A-F')"
# Refused with the new text: path-safe by the old rule, wrong by the shape rule.
pi_shape_refuse path-safe-wrong-shape "$(pi_json "$J_SNAP" "$J_SNAPS" '"s-20261114-2"' "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse name-64-chars "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$(pi_rep 64)\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse uppercase-uuid "$(pi_json "$J_SNAP" "$J_SNAPS" "\"run-v1-$PI_UUID_UPPER\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse uppercase-prefix "$(pi_json "$J_SNAP" "$J_SNAPS" "\"RUN-V1-$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse wrong-version-tag "$(pi_json "$J_SNAP" "$J_SNAPS" "\"run-v2-$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse uuid-last-group-one-short-42-chars "$(pi_json "$J_SNAP" "$J_SNAPS" "\"${PI_RUN%?}\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse uuid-last-group-one-long-44-chars "$(pi_json "$J_SNAP" "$J_SNAPS" "\"${PI_RUN}a\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
pi_shape_refuse bare-uuid-without-the-prefix "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
# Key material as the run id: AKIA plus 16 upper-case characters is path-safe by the old
# rule (20 characters), so it reaches the shape rule, which must not echo it (the
# key-material scan has not run yet). FAKE_AKIA is built from two halves, as elsewhere.
pi_shape_refuse akia-shaped-run-id-is-never-printed "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$FAKE_AKIA\"" "$J_LOAD")" "$PI_SHAPE_TEXT" "$FAKE_AKIA" "(got 20 characters; the value is not printed)"
# Refused by the old character rule, which runs first: a final newline (the JSON escape
# \n) and the stable colon form itself (a colon is not path-safe).
pi_shape_refuse valid-looking-id-with-trailing-newline "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$PI_RUN\\n\"" "$J_LOAD")" "run_id is missing or not valid"
pi_shape_refuse stable-colon-form-is-not-the-slug "$(pi_json "$J_SNAP" "$J_SNAPS" "\"run:v1:$PI_UUID\"" "$J_LOAD")" "run_id is missing or not valid"

# loader_command: a last element that starts with run- must equal run_id. The element is
# never printed (the key-material scan has not run yet).
pi_shape_refuse loader-last-element-is-another-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"--run\",\"$PI_RUN_OTHER\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
pi_shape_refuse loader-last-element-is-bare-run-dash "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '["loader","run-"]')" "$PI_LOADER_TEXT"
pi_shape_refuse loader-last-element-run-dash-with-key-material "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"run-$FAKE_AKIA\"]")" "$PI_LOADER_TEXT" "$FAKE_AKIA"

# Accepted: the last element equals run_id (J_LOAD), is not a run- value, differs only in
# the case of the prefix, or a different run id sits in an earlier position.
pi_accept loader-last-element-equals-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD")" "$PI_RUN"
pi_accept loader-last-element-not-a-run-value "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" '["loader","--run","harness-run-1"]')" "$PI_RUN"
pi_accept loader-last-element-differs-only-in-prefix-case "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"Run-v1-${PI_RUN_OTHER#run-v1-}\"]")" "$PI_RUN"
# Work order 110: this case used to end in a bare "--flag" after the other run id. Since infra work order 108
# the check skips elements that start with two hyphens, so that command's last remaining element is the other
# run id and it is refused (pi_shape_refuse cases below). The case keeps its subject, a different run id in an
# EARLIER position, and now ends with the real run id after a --run option.
pi_accept loader-earlier-element-is-another-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"$PI_RUN_OTHER\",\"--run\",\"$PI_RUN\"]")" "$PI_RUN"
pi_accept loader-single-element-equals-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"$PI_RUN\"]")" "$PI_RUN"
# Work order 110: an option element after another run id must not hide it (pipeline mode shares the check).
pi_shape_refuse loader-other-run-id-then-trailing-graph-only "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"--run\",\"$PI_RUN_OTHER\",\"--graph-only\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
pi_shape_refuse loader-other-run-id-then-trailing-unknown-option "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"$PI_RUN_OTHER\",\"--flag\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"

# Boundary: exactly 43 characters of the right shape is accepted (all f, a different valid id).
RUN_ALLF="run-v1-ffffffff-ffff-ffff-ffff-ffffffffffff"
pi_accept valid-id-43-chars-all-f-accepted "$(pi_json "$J_SNAP" "$J_SNAPS" "\"$RUN_ALLF\"" "[\"loader\",\"--run\",\"$RUN_ALLF\"]")" "$RUN_ALLF"

# --print-digest accepts a valid id, and the digest follows the id (the input file hash is
# in the digested arguments).
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$PIPE_IN")
run_build
PV_RC=$RC
PV_S1="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
PV_A1="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "14.pipeline.run-id-shape.valid-id-print-digest-accepted"
a_rc 0
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_trip_empty
case_end
pi_json "$J_SNAP" "$J_SNAPS" "\"$PI_RUN_OTHER\"" "[\"loader\",\"--run\",\"$PI_RUN_OTHER\"]" > "$REC/pi-other-valid.json"
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/pi-other-valid.json")
run_build
PV_S2="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
PV_A2="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "14.pipeline.run-id-shape.print-digest-follows-the-run-id"
a_rc 0
[ "$PV_RC" -eq 0 ] || note_fail "the first --print-digest exited $PV_RC"
if ! is_hex64 "$PV_A1" || ! is_hex64 "$PV_A2"; then note_fail "an ARGS_DIGEST is not 64 hex"; fi
[ "$PV_A1" != "$PV_A2" ] || note_fail "ARGS_DIGEST did not change with the run id"
[ "$PV_S1" = "$PV_S2" ] || note_fail "SCRIPT_DIGEST changed with the run id"
a_trip_empty
case_end

# capability-check mode has no run_id and is still accepted, also under --print-digest.
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/pi-capability.json")
run_build
case_begin "14.pipeline.run-id-shape.capability-check-needs-no-run-id-print-digest"
a_rc 0
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_not_out "run_id="
if grep -qF -- "$PI_SHAPE_TEXT" "$ERR"; then note_fail "the run id shape rule fired for capability-check"; fi
a_trip_empty
case_end

# The old shape is refused in a real run and with --print-digest too, before any call.
printf '{"hadoop_args":[],"spark_args":[],"loader_command":[]}\n' > "$REC/pi-old-shape.json"
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=("${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/pi-old-shape.json" --approval-record "$REC/pipeline.env")
run_build
case_begin "14.pipeline.input.old-shape-real-run-refused-before-any-call"
a_refused "execution input refused"
a_err "mode must be capability-check or pipeline"
a_not_out "identity-check"
a_not_out "IDENTITY_OK"
a_not_out "EXECUTION_ARN="
case_end
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/pi-old-shape.json")
run_build
case_begin "14.pipeline.input.old-shape-print-digest-refused"
a_rc_nonzero
a_err_evidence
a_err "execution input refused"
a_not_out "SCRIPT_DIGEST="
a_not_out "ARGS_DIGEST="
a_trip_empty
case_end

# The key-material scan still covers the loader command, and never prints what matched.
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--dry-run "${CA[@]}" --size small --max-minutes 60 --spend-ceiling-usd 0.75 --hourly-burn-usd 0.60 --stop-on first-failure --input-file "$REC/pipeline-secret-input.json" --approval-record "$REC/pipeline.env")
run_build
case_begin "5.pipeline.input-key-material.value-not-printed"
a_refused "possible credential material"
a_out "INPUT_MODE=pipeline"
a_not_out_re '^STEP\[[0-9]+\] input-scan: ok$'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

# Static content of scripts/run-bounded-pipeline.sh (the run record is written only in a
# real run, which a stubbed harness cannot reach; its lines are read as text, and the dry
# run above prints the same variables).
# shellcheck disable=SC2016
static_pipeline_cases() {
  local c
  c="$(code_of "$REPO/scripts/run-bounded-pipeline.sh")"
  static_begin "14.pipeline.static.input-validated-with-isolated-python-and-no-eval"
  f_has "$c" 'python3 -I - "$INPUT_FILE"'
  f_has_not_re "$c" '(^|[[:space:]])eval[[:space:]]'
  case_end
  static_begin "14.pipeline.static.record-holds-the-input-mode"
  f_has "$c" 'rec INPUT_MODE "$INPUT_MODE"'
  case_end
  static_begin "14.pipeline.static.record-holds-snapshot-snapshots-and-run-id-in-pipeline-mode"
  f_has "$c" 'rec DATASET_SNAPSHOT "$INPUT_SNAPSHOT"'
  f_has "$c" 'rec RUN_SNAPSHOTS "$INPUT_SNAPSHOTS"'
  f_has "$c" 'rec RUN_ID "$INPUT_RUN_ID"'
  case_end
  static_begin "14.pipeline.static.record-says-n-a-for-capability-check"
  f_has "$c" 'rec DATASET_SNAPSHOT n/a'
  case_end
  static_begin "14.pipeline.static.no-not-recorded-placeholder-left"
  f_has_not "$c" 'not-recorded'
  case_end
  static_begin "14.pipeline.static.record-holds-the-output-prefixes"
  f_has "$c" 'rec OUTPUT_PREFIX "$u"'
  f_has "$c" 'curated/runs/$INPUT_RUN_ID/'
  f_has "$c" 'graph/runs/$INPUT_RUN_ID/'
  f_has "$c" 'semantic-context/runs/$INPUT_RUN_ID/'
  f_has "$c" 'reports/runs/$INPUT_RUN_ID/'
  f_has "$c" 'reports/capability/'
  case_end
  static_begin "14.pipeline.static.input-checked-before-approval-and-the-same-file-is-started"
  f_before "$c" 'INPUT_FIELDS="$(python3' 'verify_approval pipeline "$EXTRA" "n/a" "n/a"'
  f_before "$c" 'verify_approval pipeline "$EXTRA" "n/a" "n/a"' 'consume_approval pipeline'
  f_before "$c" 'consume_approval pipeline' 'start-execution --state-machine-arn'
  f_has "$c" '--input "file://$INPUT_FILE"'
  case_end
  static_begin "14.pipeline.static.old-free-form-lists-are-not-accepted"
  f_has_not "$c" 'hadoop_args'
  f_has_not "$c" 'spark_args'
  case_end
  static_begin "14.pipeline.static.run-id-shape-is-a-lower-case-fullmatch-checked-before-the-approval-record"
  f_has "$c" 'run_slug = re.compile(r"run-v1-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")'
  f_has "$c" 'run_slug.fullmatch(run_id)'
  f_has_not_re "$c" '[.](match|search)[(]'
  f_has_not_re "$c" 'IGNORECASE|re[.]I[^a-z]|[(][?]i[)]'
  f_before "$c" 'run_slug.fullmatch(run_id)' 'verify_approval pipeline "$EXTRA" "n/a" "n/a"'
  f_before "$c" 'run_slug.fullmatch(run_id)' 'verify_identity'
  f_has "$c" 'the last element of loader_command starts with run- but is not equal to run_id'
  case_end
}
static_pipeline_cases

### 14.pipeline.loader-only: mode loader-only (work order 87, follows infra work order 86) ---
# Mode loader-only reruns only the Fargate loader: {"mode":"loader-only","run_id":ID,"loader_command":[...]},
# exactly those three keys. The script side is read from run-bounded-pipeline.sh: the input is refused
# in the same Python block as the pipeline input, in the same order (character rule, loader_command shape,
# run id shape, last loader element), before the approval record, in a real run, a dry run and under
# --print-digest. A dry run prints INPUT_MODE=loader-only, one OUTPUT_PREFIX (the loader report) and
# DATASET_SNAPSHOT=n/a with the run id, and the teardown-check step would make one unfiltered check
# that terminates nothing. Nothing here runs a real run: the run record and stop_everything are read
# as text.

PI_CMD_TEXT="loader_command must be a non-empty list of non-empty strings"
PI_CHAR_TEXT="run_id is missing or not valid"
LO_MODE_TEXT="mode must be capability-check or pipeline, or loader-only (run_id and loader_command only)"

lo_json() {
  printf '{"mode":"loader-only","run_id":%s,"loader_command":%s}' "$1" "$2"
}

# lo_refuse ID JSON FRAGMENT [NOPRINT] [EXTRA_ERR] [ABSENT_ERR]: three cases (real, dry, digest). Each is
# refused by the input validation, before the approval record, the identity check and any stub, and prints
# nothing of the input. NOPRINT is text that must not appear on stdout or stderr, EXTRA_ERR text that must
# be on stderr, ABSENT_ERR text that must not be (it proves which check fired first).
lo_refuse() {
  local id="$1" json="$2" frag="$3" noprint="${4:-}" extra="${5:-}" absent="${6:-}" f="$REC/lo-$1.json" mode
  printf '%s' "$json" > "$f"
  common_args "$ACCT" us-east-2 "$CEIL"
  SN=run-bounded-pipeline
  for mode in real dry digest; do
    case "$mode" in
      real) A=("${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$REC/pipeline.env") ;;
      dry) A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f" --approval-record "$REC/pipeline.env") ;;
      digest) A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$f") ;;
    esac
    run_build
    case_begin "14.pipeline.loader-only.$id.$mode"
    a_refused "$frag"
    a_err "execution input refused"
    a_not_out "INPUT_MODE="
    a_not_out "DATASET_SNAPSHOT="
    a_not_out "OUTPUT_PREFIX="
    a_not_out "SCRIPT_DIGEST="
    a_not_out "ARGS_DIGEST="
    a_not_out "identity-check"
    a_not_out "IDENTITY_OK"
    a_not_out "EXECUTION_ARN="
    a_not_out "LOADER_GRAPH_ONLY"
    if [ -n "$noprint" ] && grep -qF -- "$noprint" "$OUT" "$ERR"; then note_fail "the text that must not be printed was printed"; fi
    if [ "$frag" = "$PI_SHAPE_TEXT" ]; then
      a_err "characters; the value is not printed)"
      if grep -qF -- "(got: " "$ERR"; then note_fail "stderr holds the old (got: <value>) echo"; fi
    fi
    if [ -n "$extra" ]; then a_err "$extra"; fi
    if [ -n "$absent" ] && grep -qF -- "$absent" "$ERR"; then note_fail "stderr holds '$absent', so another check fired first"; fi
    case_end
  done
}

# lo_accept ID JSON RUNID: a dry run with a record accepts this loader-only input and prints RUNID.
lo_accept() {
  local id="$1" json="$2" want="$3" f="$REC/loa-$1.json"
  printf '%s\n' "$json" > "$f"
  pi_dry_with "$f" "$REC/loa-$id.env"
  case_begin "14.pipeline.loader-only.$id"
  a_dry_common
  a_out "INPUT_MODE=loader-only"
  a_out "APPROVAL_VERIFIED action=pipeline"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out "OUTPUT_PREFIX=reports/runs/$want/loader/ (key prefix in the data bucket)"
  a_out "DATASET_SNAPSHOT=n/a (loader-only reads no dataset) run_id=$want"
  a_not_out "LOADER_GRAPH_ONLY"
  case_end
}

# lo_accept_opt ID JSON RUNID GO: like lo_accept, for a loader_command that carries option elements (work
# order 110). GO is yes when the dry run must print exactly one LOADER_GRAPH_ONLY=yes line (after the
# INPUT_MODE line and before the OUTPUT_PREFIX line) and no when it must print no LOADER_GRAPH_ONLY text.
lo_accept_opt() {
  local id="$1" json="$2" want="$3" go="$4" f="$REC/loo-$1.json"
  printf '%s\n' "$json" > "$f"
  pi_dry_with "$f" "$REC/loo-$id.env"
  case_begin "14.pipeline.loader-only.$id"
  a_dry_common
  a_out "INPUT_MODE=loader-only"
  a_out "APPROVAL_VERIFIED action=pipeline"
  a_out "APPROVAL_NOT_CONSUMED dry-run"
  a_out "OUTPUT_PREFIX=reports/runs/$want/loader/ (key prefix in the data bucket)"
  a_out "DATASET_SNAPSHOT=n/a (loader-only reads no dataset) run_id=$want"
  if [ "$go" = yes ]; then
    a_out "LOADER_GRAPH_ONLY=yes (loader_command carries --graph-only; it takes effect only with a loader image built from code that implements the flag)"
    a_out_re '^LOADER_GRAPH_ONLY=yes \('
    [ "$(grep -c 'LOADER_GRAPH_ONLY' "$OUT")" = "1" ] || note_fail "expected exactly one LOADER_GRAPH_ONLY line, found $(grep -c 'LOADER_GRAPH_ONLY' "$OUT")"
    a_order "INPUT_MODE=loader-only" "LOADER_GRAPH_ONLY=yes" "OUTPUT_PREFIX=reports/runs/$want/loader/"
  else
    a_not_out "LOADER_GRAPH_ONLY"
  fi
  case_end
}

# Accepted: the dry run with a record. The loader-only markers, and none of the pipeline ones.
printf '%s\n' "$(lo_json "$J_RUN" "$J_LOAD")" > "$REC/lo-ok.json"
pi_dry_with "$REC/lo-ok.json" "$REC/lo-ok.env"
case_begin "14.pipeline.loader-only.dry-run-prints-the-loader-only-markers-and-none-of-the-pipeline-ones"
a_dry_common
a_out "INPUT_MODE=loader-only"
a_out "OUTPUT_PREFIX=reports/runs/$PI_RUN/loader/ (key prefix in the data bucket)"
[ "$(grep -c '^OUTPUT_PREFIX=' "$OUT")" = "1" ] || note_fail "expected exactly one OUTPUT_PREFIX line, found $(grep -c '^OUTPUT_PREFIX=' "$OUT")"
a_not_out "OUTPUT_PREFIX=reports/runs/$PI_RUN/ "
a_out "DATASET_SNAPSHOT=n/a (loader-only reads no dataset) run_id=$PI_RUN"
a_not_out "curated/runs/"
a_not_out "graph/runs/"
a_not_out "semantic-context/runs/"
a_not_out "snapshots="
a_not_out "reports/capability/"
a_not_out "DATASET_SNAPSHOT=not-recorded"
a_not_out "DATASET_SNAPSHOT=$PI_SNAP"
a_not_out "capability-check reads no dataset"
# Work order 110: a loader_command without a trailing option behaves as before and prints no graph-only line.
a_not_out "LOADER_GRAPH_ONLY"
a_out "OUTPUT_LOCATIONS=none-named"
a_out "LIMITS size=small max_minutes=45 spend_ceiling_usd=0.50 hourly_burn_usd=0.47 stop_on=first-failure cost_ceiling_usd=$CEIL"
a_out "APPROVAL_VERIFIED action=pipeline"
a_out "APPROVAL_NOT_CONSUMED dry-run"
if ! grep -qF -- "loader-only: the execution creates no EMR cluster, so give an --hourly-burn-usd without the EMR node" "$OUT" "$ERR"; then note_fail "the no-cluster burn note is missing"; fi
case_end

# The teardown-check step of the dry run: one unfiltered check, nothing terminated, and not the
# ten checks of the other modes.
case_begin "14.pipeline.loader-only.dry-run-teardown-check-would-run-one-unfiltered-check-and-terminates-nothing"
a_out_re 'WOULD_RUN: one check that no EMR cluster named [A-Za-z0-9._-]+-pipeline is active \(loader-only creates none, so none is terminated and a missing cluster is not an error\)'
a_not_out "up to 10 checks for active EMR clusters created after the start"
a_order "teardown-check: dry-run" "WOULD_RUN: one check that no EMR cluster named" "report: dry-run"
a_not_out "terminate-job-flows"
case_end

# --print-digest accepts the input, and the digest follows the input file (its sha256 is in the
# digested arguments), so the mode is bound by the file.
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-ok.json")
run_build
LO_RC=$RC
LO_S="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
LO_A="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "14.pipeline.loader-only.print-digest-accepted-and-the-digest-follows-the-input-file"
a_rc 0
[ "$LO_RC" -eq 0 ] || note_fail "--print-digest exited $LO_RC"
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
a_out "ACTION=pipeline"
a_not_out "DRY_RUN=yes"
[ "$LO_S" = "$PV_S1" ] || note_fail "SCRIPT_DIGEST differs from the one printed for a pipeline input"
[ "$LO_A" != "$PV_A1" ] || note_fail "ARGS_DIGEST of a loader-only input equals that of a pipeline input with the same run id"
a_trip_empty
case_end

# Refused: the pipeline keys. snapshot and snapshots are unknown keys in this mode.
lo_refuse snapshot-key "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/}$/,"snapshot":"s1"}/')" "unknown input key(s) for mode loader-only: snapshot"
lo_refuse snapshots-key "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/}$/,"snapshots":"dblp=a"}/')" "unknown input key(s) for mode loader-only: snapshots"
lo_refuse snapshot-and-snapshots-keys "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/}$/,"snapshot":"s1","snapshots":"dblp=a"}/')" "unknown input key(s) for mode loader-only: snapshot, snapshots"
lo_refuse a-pipeline-input-with-the-mode-changed "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD" | sed 's/"pipeline"/"loader-only"/')" "unknown input key(s) for mode loader-only: snapshot, snapshots"
lo_refuse unknown-extra-key "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/}$/,"extra":1}/')" "unknown input key(s) for mode loader-only: extra"
lo_refuse old-hadoop-args-key "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/}$/,"hadoop_args":[]}/')" "unknown input key(s) for mode loader-only: hadoop_args"

# Refused: the mode itself, and the wording that names loader-only.
lo_refuse unknown-mode '{"mode":"batch"}' "$LO_MODE_TEXT"
lo_refuse mode-with-underscore "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/"loader-only"/"loader_only"/')" "$LO_MODE_TEXT"
lo_refuse mode-wrong-case "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/"loader-only"/"Loader-Only"/')" "$LO_MODE_TEXT"
lo_refuse mode-trailing-space "$(lo_json "$J_RUN" "$J_LOAD" | sed 's/"loader-only"/"loader-only "/')" "$LO_MODE_TEXT"
lo_refuse missing-mode "{\"run_id\":$J_RUN,\"loader_command\":$J_LOAD}" "$LO_MODE_TEXT"

# Refused: the run id, by the character rule first (it runs before the loader_command check).
lo_refuse run-id-missing "{\"mode\":\"loader-only\",\"loader_command\":$J_LOAD}" "$PI_CHAR_TEXT"
lo_refuse run-id-not-a-string "$(lo_json '["x"]' "$J_LOAD")" "$PI_CHAR_TEXT"
lo_refuse run-id-with-slash "$(lo_json '"a/b"' "$J_LOAD")" "$PI_CHAR_TEXT"
lo_refuse run-id-empty "$(lo_json '""' "$J_LOAD")" "$PI_CHAR_TEXT"
lo_refuse run-id-dot-dot "$(lo_json '".."' "$J_LOAD")" "run_id must not be . or .."
lo_refuse run-id-trailing-newline "$(lo_json "\"$PI_RUN\\n\"" "$J_LOAD")" "$PI_CHAR_TEXT"
lo_refuse run-id-stable-colon-form "$(lo_json "\"run:v1:$PI_UUID\"" "$J_LOAD")" "$PI_CHAR_TEXT"
lo_refuse only-mode-given '{"mode":"loader-only"}' "$PI_CHAR_TEXT"

# Refused: the run id shape (the same rule as pipeline mode, the shape text and no echo of the value).
lo_refuse run-id-path-safe-wrong-shape "$(lo_json '"s-20261114-2"' "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-uppercase-uuid "$(lo_json "\"run-v1-$PI_UUID_UPPER\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-uppercase-prefix "$(lo_json "\"RUN-V1-$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-wrong-version-tag "$(lo_json "\"run-v2-$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-42-chars "$(lo_json "\"${PI_RUN%?}\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-44-chars "$(lo_json "\"${PI_RUN}a\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-bare-uuid "$(lo_json "\"$PI_UUID\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-64-chars "$(lo_json "\"$(pi_rep 64)\"" "$J_LOAD")" "$PI_SHAPE_TEXT"
lo_refuse run-id-key-material-is-never-printed "$(lo_json "\"$FAKE_AKIA\"" "$J_LOAD")" "$PI_SHAPE_TEXT" "$FAKE_AKIA" "(got 20 characters; the value is not printed)"

# Refused: loader_command, in the shape the pipeline mode demands.
lo_refuse loader-command-missing "{\"mode\":\"loader-only\",\"run_id\":$J_RUN}" "$PI_CMD_TEXT"
lo_refuse loader-command-empty-list "$(lo_json "$J_RUN" '[]')" "$PI_CMD_TEXT"
lo_refuse loader-command-not-a-list "$(lo_json "$J_RUN" '"loader"')" "$PI_CMD_TEXT"
lo_refuse loader-command-empty-string-element "$(lo_json "$J_RUN" '[""]')" "$PI_CMD_TEXT"
lo_refuse loader-command-non-string-element "$(lo_json "$J_RUN" '["loader",1]')" "$PI_CMD_TEXT"

# Order of the checks: the character rule, then the loader_command shape, then the run id shape.
lo_refuse order-bad-shape-and-empty-command-gives-the-command-text "$(lo_json '"s-20261114-2"' '[]')" "$PI_CMD_TEXT" "" "" "$PI_SHAPE_TEXT"
lo_refuse order-bad-characters-and-empty-command-gives-the-character-text "$(lo_json '"a/b"' '[]')" "$PI_CHAR_TEXT" "" "" "$PI_CMD_TEXT"
lo_refuse order-bad-shape-and-another-last-element-gives-the-shape-text "$(lo_json '"s-20261114-2"' "[\"loader\",\"$PI_RUN_OTHER\"]")" "$PI_SHAPE_TEXT" "$PI_RUN_OTHER" "" "$PI_LOADER_TEXT"

# Refused: a last loader element that starts with run- must equal run_id. It is never printed.
lo_refuse last-element-is-another-run-id "$(lo_json "$J_RUN" "[\"loader\",\"--run\",\"$PI_RUN_OTHER\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
lo_refuse last-element-is-bare-run-dash "$(lo_json "$J_RUN" '["loader","run-"]')" "$PI_LOADER_TEXT"
lo_refuse last-element-run-dash-with-key-material "$(lo_json "$J_RUN" "[\"loader\",\"run-$FAKE_AKIA\"]")" "$PI_LOADER_TEXT" "$FAKE_AKIA"

# Accepted: the last element equals run_id (the dry run above), is not a run- value, differs only in
# the case of the prefix, or a different run id sits in an earlier position; a single element; the
# all-f id.
lo_accept last-element-is-not-a-run-value "$(lo_json "$J_RUN" '["loader","--run","harness-run-1"]')" "$PI_RUN"
lo_accept last-element-differs-only-in-prefix-case "$(lo_json "$J_RUN" "[\"loader\",\"Run-v1-${PI_RUN_OTHER#run-v1-}\"]")" "$PI_RUN"
# Work order 110: as in pipeline mode, the old command ended in a bare "--flag" after the other run id, which
# the option-skipping check (infra work order 108) now refuses (lo_refuse cases below). The subject is kept:
# a different run id in an EARLIER position, the real run id last.
lo_accept earlier-element-is-another-run-id "$(lo_json "$J_RUN" "[\"loader\",\"$PI_RUN_OTHER\",\"--run\",\"$PI_RUN\"]")" "$PI_RUN"
lo_accept single-element-equals-run-id "$(lo_json "$J_RUN" "[\"$PI_RUN\"]")" "$PI_RUN"
lo_accept all-f-run-id-of-43-chars "$(lo_json "\"$RUN_ALLF\"" "[\"loader\",\"--run\",\"$RUN_ALLF\"]")" "$RUN_ALLF"

# Work order 110 (infra work order 108): a trailing --graph-only element of loader_command.
# Read from run-bounded-pipeline.sh, and mirrored here exactly:
#  - the run-id check compares the LAST element that does not start with two hyphens with run_id, so an
#    option element, "--graph-only" or any other "--" element, can never hide a different run id;
#  - the script prints LOADER_GRAPH_ONLY=yes (and records LOADER_GRAPH_ONLY yes) when the exact element
#    "--graph-only" is among the elements, wherever it stands; nothing is printed otherwise;
#  - the script does NOT reject any other option-like element: every "--" element is skipped, and its
#    spelling is not checked (scripts/README.md says so). A single-hyphen element is not an option to the
#    check: it is compared like any other element and, not starting with run-, is accepted.
# Refused: an option after another run id does not hide the mismatch. The id is never printed.
lo_refuse graph-only-trailing-does-not-hide-another-run-id "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN_OTHER\",\"--graph-only\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
lo_refuse graph-only-trailing-does-not-hide-bare-run-dash "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"run-\",\"--graph-only\"]")" "$PI_LOADER_TEXT"
lo_refuse graph-only-trailing-does-not-hide-run-dash-with-key-material "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"run-$FAKE_AKIA\",\"--graph-only\"]")" "$PI_LOADER_TEXT" "$FAKE_AKIA"
lo_refuse unknown-trailing-option-does-not-hide-another-run-id "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN_OTHER\",\"--other-option\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
lo_refuse two-trailing-options-do-not-hide-another-run-id "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN_OTHER\",\"--graph-only\",\"--other-option\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
lo_refuse graph-only-before-another-run-id-is-still-refused "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"--graph-only\",\"$PI_RUN_OTHER\"]")" "$PI_LOADER_TEXT" "$PI_RUN_OTHER"
# The shape rule still runs before the loader rule, with the option present.
lo_refuse graph-only-with-a-bad-run-id-shape-gives-the-shape-text "$(lo_json '"s-20261114-2"' "[\"$LO_CLS\",\"$PI_RUN_OTHER\",\"--graph-only\"]")" "$PI_SHAPE_TEXT" "$PI_RUN_OTHER" "" "$PI_LOADER_TEXT"

# Accepted: the option is accepted in a dry run and is printed once, between INPUT_MODE and OUTPUT_PREFIX.
lo_accept_opt graph-only-trailing-option-is-accepted-and-printed "$(lo_json "$J_RUN" "$J_LOAD_AIM_GO")" "$PI_RUN" yes
lo_accept_opt graph-only-after-the-run-option-is-accepted-and-printed "$(lo_json "$J_RUN" "$J_LOAD_GO")" "$PI_RUN" yes
lo_accept_opt graph-only-before-the-run-id-is-accepted-and-printed "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"--graph-only\",\"$PI_RUN\"]")" "$PI_RUN" yes
lo_accept_opt graph-only-after-another-run-id-in-an-earlier-position-is-accepted-and-printed "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN_OTHER\",\"--run\",\"$PI_RUN\",\"--graph-only\"]")" "$PI_RUN" yes
# Accepted, and no graph-only line: another option is skipped by the check and is neither refused nor
# recorded as the graph-only flag. Only the exact element --graph-only counts.
lo_accept_opt unknown-trailing-option-is-accepted-and-prints-no-graph-only-line "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"--other-option\"]")" "$PI_RUN" no
lo_accept_opt misspelt-graph-onlyx-is-accepted-and-is-not-the-flag "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"--graph-onlyx\"]")" "$PI_RUN" no
lo_accept_opt wrong-case-graph-only-is-accepted-and-is-not-the-flag "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"--Graph-Only\"]")" "$PI_RUN" no
lo_accept_opt graph-only-with-a-value-is-accepted-and-is-not-the-flag "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"--graph-only=true\"]")" "$PI_RUN" no
lo_accept_opt single-hyphen-graph-only-is-accepted-and-is-not-the-flag "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"-graph-only\"]")" "$PI_RUN" no

# The option is part of what the human approves: the digest of the input file follows it, and a record made
# for the same command without the option does not verify the command with it. The script digest is the
# one of every other input (work order 110 adds no input key and no script code outside the block).
printf '%s\n' "$(lo_json "$J_RUN" "$J_LOAD_GO")" > "$REC/lo-go.json"
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--print-digest "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-go.json")
run_build
LOG_RC=$RC
LOG_S="$(sed -n 's/^SCRIPT_DIGEST=//p' "$OUT" | head -n 1)"
LOG_A="$(sed -n 's/^ARGS_DIGEST=//p' "$OUT" | head -n 1)"
case_begin "14.pipeline.loader-only.graph-only-print-digest-accepted-and-the-digest-differs-from-the-same-command-without-the-option"
a_rc 0
[ "$LOG_RC" -eq 0 ] || note_fail "--print-digest exited $LOG_RC"
a_out_re '^SCRIPT_DIGEST=[0-9a-f]{64}$'
a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
[ "$LOG_S" = "$LO_S" ] || note_fail "SCRIPT_DIGEST differs from the one printed for the same script and another loader-only input"
[ "$LOG_A" != "$LO_A" ] || note_fail "ARGS_DIGEST of the command with --graph-only equals that of the same command without it"
a_trip_empty
case_end

# Control: the record made for the command without the option still verifies that command.
A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-ok.json" --approval-record "$REC/lo-ok.env")
run_build
case_begin "14.pipeline.loader-only.graph-only-control-the-record-of-the-command-without-the-option-verifies-that-command"
a_dry_common
a_out "APPROVAL_VERIFIED action=pipeline"
a_not_out "LOADER_GRAPH_ONLY"
case_end
# The same record, the command with the option: refused by the approval check, after the input was
# accepted and printed (the input check does not read the record). Nothing is consumed or started.
A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-go.json" --approval-record "$REC/lo-ok.env")
run_build
case_begin "14.pipeline.loader-only.graph-only-is-refused-under-a-record-made-for-the-command-without-the-option"
a_refused ""
a_out "INPUT_MODE=loader-only"
a_out "LOADER_GRAPH_ONLY=yes ("
a_not_out "APPROVAL_NOT_CONSUMED"
a_not_out "EXECUTION_ARN="
case_end

# The key-material scan still covers every element, also with the option present, and never prints the match.
printf '%s\n' "$(lo_json "$J_RUN" "[\"$LO_CLS\",\"$PI_RUN\",\"--graph-only\",\"--note=$FAKE_AKIA\"]")" > "$REC/lo-go-secret.json"
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-go-secret.json" --approval-record "$REC/pipeline.env")
run_build
case_begin "14.pipeline.loader-only.graph-only-does-not-bypass-the-key-material-scan-and-the-match-is-not-printed"
a_refused "possible credential material"
a_out "INPUT_MODE=loader-only"
a_out "LOADER_GRAPH_ONLY=yes ("
a_not_out_re '^STEP\[[0-9]+\] input-scan: ok$'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

# Pipeline mode shares the Python block, so it prints the line too (infra work order 108 names loader-only
# only; this case pins what the script does and is a finding to confirm, not a policy of this harness).
printf '%s\n' "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "$J_LOAD_GO")" > "$REC/pia-go.json"
pi_dry_with "$REC/pia-go.json" "$REC/pia-go.env"
case_begin "14.pipeline.pipeline-mode.trailing-graph-only-is-accepted-and-printed-by-the-shared-input-check"
a_dry_common
a_out "INPUT_MODE=pipeline"
a_out "APPROVAL_VERIFIED action=pipeline"
a_out_re '^LOADER_GRAPH_ONLY=yes \('
a_out_re " run_id=$PI_RUN\$"
case_end

# The key-material scan still covers the loader command in this mode and never prints the match.
printf '%s\n' "$(lo_json "$J_RUN" "[\"loader\",\"$FAKE_AKIA\"]")" > "$REC/lo-secret.json"
common_args "$ACCT" us-east-2 "$CEIL"
SN=run-bounded-pipeline
A=(--dry-run "${CA[@]}" "${PI_TAIL[@]}" --input-file "$REC/lo-secret.json" --approval-record "$REC/pipeline.env")
run_build
case_begin "14.pipeline.loader-only.key-material-in-the-loader-command-is-refused-and-not-printed"
a_refused "possible credential material"
a_out "INPUT_MODE=loader-only"
a_not_out_re '^STEP\[[0-9]+\] input-scan: ok$'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

# Static content of scripts/run-bounded-pipeline.sh for mode loader-only: the Python rules, the record,
# the teardown check and stop_everything. The run record and the stop path are not reachable by a
# stubbed harness, so they are read as text.
# shellcheck disable=SC2016
static_loader_only_script_cases() {
  local c fn lb tb pb
  c="$(code_of "$REPO/scripts/run-bounded-pipeline.sh")"
  fn="$CODE_DIR/lo-stop-everything-fn.sh"
  lb="$CODE_DIR/lo-stop-loader-only-block.sh"
  tb="$CODE_DIR/lo-teardown-loader-only-branch.sh"
  pb="$CODE_DIR/lo-teardown-pipeline-branch.sh"
  extract_fn "$c" stop_everything "$fn"
  awk 'index($0, "  if [[ \"$INPUT_MODE\" == \"loader-only\" ]]; then") == 1 { g = 1; next } g == 1 && $0 == "  fi" { exit } g == 1 { print }' "$fn" > "$lb"
  awk 'index($0, "step teardown-check") == 1 { s = 1 } s == 1 && index($0, "if [[ \"$INPUT_MODE\" == \"loader-only\" ]]; then") == 1 { g = 1; next } g == 1 && $0 == "else" { exit } g == 1 { print }' "$c" > "$tb"
  awk 'index($0, "step teardown-check") == 1 { s = 1 } s == 1 && $0 == "else" { g = 1; next } g == 1 && $0 == "fi" { exit } g == 1 { print }' "$c" > "$pb"

  static_begin "14.pipeline.static.loader-only-python-rules-allow-exactly-mode-run-id-and-loader-command"
  f_has "$c" 'elif mode == "loader-only":'
  f_has "$c" 'allowed = {"mode", "run_id", "loader_command"}'
  f_count_is "$c" 'allowed = {' 3
  f_has "$c" 'sys.exit("mode must be capability-check or pipeline, or loader-only (run_id and loader_command only)")'
  f_has "$c" 'if mode in ("pipeline", "loader-only"):'
  # Four leading spaces: the elif line above holds the text "if mode == ..." too, but at column 0.
  f_count_is "$c" '    if mode == "loader-only":' 1
  f_has "$c" 'fields = (("run_id", ident),)'
  f_before "$c" 'if mode in ("pipeline", "loader-only"):' 'run_slug = re.compile('
  f_before "$c" 'fields = (("run_id", ident),)' 'run_slug.fullmatch(run_id)'
  f_before "$c" 'run_slug.fullmatch(run_id)' 'verify_approval pipeline "$EXTRA" "n/a" "n/a"'
  # Work order 110: infra work order 108 replaced the old test on cmd[-1] by one on the last element that does
  # not start with two hyphens. The old needle is gone from the script and is asserted absent.
  f_has "$c" 'positional = [x for x in cmd if not x.startswith("--")]'
  f_has "$c" 'if positional and positional[-1].startswith("run-") and positional[-1] != run_id:'
  f_has_not "$c" 'cmd[-1]'
  f_before "$c" 'positional[-1].startswith("run-") and positional[-1] != run_id' 'verify_approval pipeline "$EXTRA" "n/a" "n/a"'
  case_end

  static_begin "14.pipeline.static.loader-only-graph-only-is-printed-and-recorded-only-when-the-input-carries-the-element-and-adds-no-input-key"
  # The record is written only in a real run, which a stubbed harness cannot reach, so it is read as text.
  # Four code lines name LOADER_GRAPH_ONLY: the Python append, the awk that reads it, the printf and the rec.
  f_has "$c" 'if "--graph-only" in cmd:'
  f_has "$c" 'out.append("LOADER_GRAPH_ONLY=yes")'
  f_has "$c" '$1 == "LOADER_GRAPH_ONLY" { print $2; exit }'
  f_has "$c" "printf 'LOADER_GRAPH_ONLY=yes (loader_command carries --graph-only; it takes effect only with a loader image built from code that implements the flag)\\n'"
  f_count_is "$c" 'rec LOADER_GRAPH_ONLY yes' 1
  f_count_is "$c" 'LOADER_GRAPH_ONLY' 4
  f_count_is "$c" 'if [[ "$INPUT_GRAPH_ONLY" == "yes" ]]; then' 2
  f_count_is "$c" '--graph-only' 2
  f_has_not "$c" 'graph_only'
  f_has "$c" 'allowed = {"mode", "run_id", "loader_command"}'
  f_count_is "$c" 'allowed = {' 3
  f_before "$c" 'out.append("LOADER_GRAPH_ONLY=yes")' 'verify_approval pipeline "$EXTRA" "n/a" "n/a"'
  f_before "$c" "printf 'INPUT_MODE=%s\\n' \"\$INPUT_MODE\"" "printf 'LOADER_GRAPH_ONLY=yes ("
  f_before "$c" "printf 'LOADER_GRAPH_ONLY=yes (" "printf 'OUTPUT_PREFIX=%s (key prefix in the data bucket)"
  f_before "$c" 'rec INPUT_MODE "$INPUT_MODE"' 'rec LOADER_GRAPH_ONLY yes'
  f_before "$c" 'rec LOADER_GRAPH_ONLY yes' 'rec OUTPUT_PREFIX "$u"'
  case_end

  static_begin "14.pipeline.static.loader-only-output-prefix-is-the-loader-report-only-and-the-record-says-n-a-and-the-run-id"
  f_has "$c" 'OUTPUT_PREFIXES="reports/runs/$INPUT_RUN_ID/loader/"'
  f_count_is "$c" 'OUTPUT_PREFIXES=' 4
  f_count_is "$c" 'curated/runs/$INPUT_RUN_ID/' 1
  f_count_is "$c" 'rec DATASET_SNAPSHOT n/a' 2
  f_count_is "$c" 'rec RUN_ID "$INPUT_RUN_ID"' 2
  f_count_is "$c" 'rec DATASET_SNAPSHOT "$INPUT_SNAPSHOT"' 1
  f_count_is "$c" 'rec RUN_SNAPSHOTS "$INPUT_SNAPSHOTS"' 1
  f_has "$c" "printf 'DATASET_SNAPSHOT=n/a (loader-only reads no dataset) run_id=%s\\n' \"\$INPUT_RUN_ID\""
  case_end

  static_begin "14.pipeline.static.loader-only-teardown-check-is-one-unfiltered-check-that-terminates-nothing-and-the-other-modes-keep-their-ten"
  if [ "$(wc -l < "$tb" | tr -d ' ')" -lt 4 ]; then note_fail "the loader-only teardown branch cut out of the script has fewer than 4 lines"; fi
  f_has "$tb" "LEFT=\"\$(aws emr list-clusters --active --query \"Clusters[?Name=='\${EMR_NAME}'].Id\" --output text)\""
  f_count_is "$tb" 'aws emr list-clusters' 1
  f_has_not "$tb" '--created-after'
  f_has_not "$tb" 'terminate-job-flows'
  f_has_not "$tb" 'sleep'
  f_has_not "$tb" 'for _ in'
  f_has "$tb" 'die "an EMR cluster named $EMR_NAME is active after a loader-only run, which creates none: $LEFT.'
  f_has "$tb" "printf 'NO_ACTIVE_CLUSTER name=%s (loader-only: this run creates no cluster)\\n' \"\$EMR_NAME\""
  if [ "$(wc -l < "$pb" | tr -d ' ')" -lt 8 ]; then note_fail "the pipeline teardown branch cut out of the script has fewer than 8 lines"; fi
  f_has "$pb" 'for _ in 1 2 3 4 5 6 7 8 9 10; do'
  f_has "$pb" "LEFT=\"\$(aws emr list-clusters --active --created-after \"\$START_ISO\" --query \"Clusters[?Name=='\${EMR_NAME}'].Id\" --output text)\""
  f_has "$pb" 'aws emr terminate-job-flows --job-flow-ids $LEFT || true'
  f_has "$pb" 'sleep 30'
  f_has "$pb" 'die "EMR cluster still active after the run: $LEFT. Terminate it now; it bills hourly."'
  f_has "$pb" "printf 'NO_ACTIVE_CLUSTER name=%s\\n' \"\$EMR_NAME\""
  f_has_not "$pb" 'loader-only'
  f_count_is "$c" 'dry_would "one check that no EMR cluster named ${SC_NAME_PREFIX}-pipeline is active (loader-only creates none, so none is terminated and a missing cluster is not an error)"' 1
  f_count_is "$c" 'dry_would "up to 10 checks for active EMR clusters created after the start"' 1
  case_end

  static_begin "14.pipeline.static.loader-only-stop-everything-stops-the-execution-terminates-no-cluster-and-says-the-fargate-task-may-outlive-it"
  # Work order 99: the function is cut out of the comment-free copy of the script by extract_fn, from the
  # line "stop_everything() {" to the first later line that is exactly "}" (lines 374 to 391 of the real
  # script; the comment lines 384 and 388 are not in the copy). The needles are fixed strings, each a
  # substring of one line: 378 holds the stop-execution call, 380 the mode test with its two leading
  # spaces, 381 the note, 382 "return 0", 383 the closing "  fi", 385 the list-clusters call, 389 the
  # terminate-job-flows call. Mutation: dropping the stop-execution call, moving the loader-only test below
  # the cluster lookup, removing its return 0, or letting the loader-only branch call terminate-job-flows
  # or list-clusters, each fails one of the needles below. A failed cut-out fails the next two lines.
  f_min_lines "$fn" 10 "stop_everything cut out of the script"
  f_min_lines "$lb" 2 "the loader-only branch of stop_everything"
  f_has "$fn" 'aws stepfunctions stop-execution --execution-arn "$EXEC_ARN"'
  f_has "$fn" 'if [[ "$INPUT_MODE" == "loader-only" ]]; then'
  f_before "$fn" 'aws stepfunctions stop-execution' 'if [[ "$INPUT_MODE" == "loader-only" ]]; then'
  f_before "$fn" 'if [[ "$INPUT_MODE" == "loader-only" ]]; then' 'aws emr list-clusters --active --created-after'
  f_before "$fn" 'if [[ "$INPUT_MODE" == "loader-only" ]]; then' 'terminate-job-flows'
  f_has "$fn" 'aws emr list-clusters --active --created-after "$START_ISO"'
  f_has "$fn" 'terminate-job-flows'
  f_has "$lb" 'no EMR cluster'
  f_has "$lb" 'may outlive stop-execution (UNVERIFIED)'
  f_has "$lb" 'return 0'
  f_has_not "$lb" 'terminate-job-flows'
  f_has_not "$lb" 'list-clusters'
  case_end
}
static_loader_only_script_cases

### 14.upload: scripts/upload-inputs.sh (the KEYS loops above cover the approval matrix) ---

# up_refuse ID FRAGMENT [MODE]: the current UPV_* settings are refused. MODE is dry
# (default), digest or real (no --dry-run, a record that exists). UP_PLANTED, when set, is
# text that must not appear in any output.
UP_PLANTED=""
up_refuse() {
  local id="$1" frag="$2" mode="${3:-dry}"
  common_args "$ACCT" us-east-2 "$CEIL"
  case "$mode" in
    dry) up_args --dry-run "$REC/upload.env" ;;
    digest) up_args --print-digest - ;;
    real) up_args "" "$REC/upload.env" ;;
  esac
  run_build
  case_begin "14.upload.$id"
  a_refused "$frag"
  a_not_out "WOULD_UPLOAD"
  a_not_out "UPLOADED"
  a_not_out "IDENTITY_OK"
  if [ "$mode" = "digest" ]; then a_not_out "ARGS_DIGEST="; fi
  if [ -n "$UP_PLANTED" ] && grep -qF -- "$UP_PLANTED" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
  case_end
  UP_PLANTED=""
}

up_reset
up_digest
UP_BASE_S="$UD_S"
UP_BASE_A="$UD_A"

# The plan: every file, with its bucket, key, size and sha256, in one dry run.
up_reset
up_dry_record "$REC/upload-plan.env"
case_begin "14.upload.dry-run.plan-lists-every-file"
a_dry_common
a_out "UPLOAD_PLAN objects=$UP_N bytes=$UP_BYTES max_objects=$UP_MAXO max_bytes=$UP_MAXB"
[ "$(grep -c '^WOULD_UPLOAD ' "$OUT")" = "$UP_N" ] || note_fail "the WOULD_UPLOAD lines are not $UP_N"
[ "$(grep -c "^WOULD_UPLOAD s3://$UP_DATA_BUCKET/raw/$UP_SNAP/" "$OUT")" = "4" ] || note_fail "the data bucket does not get exactly the four raw files"
for up_f in dblp-subset.xml openalex.jsonl crossref.jsonl orcid.jsonl; do
  a_out "WOULD_UPLOAD s3://$UP_DATA_BUCKET/raw/$UP_SNAP/$up_f bytes=$(wc -c < "$REPO/datasets/subset/$up_f" | tr -d ' ') sha256=$(sha_of "$REPO/datasets/subset/$up_f")"
done
for up_m in hadoop entity-resolution spark; do
  a_out "WOULD_UPLOAD s3://$UP_ART_BUCKET/jars/$up_m/$up_m-job.jar bytes="
  a_out "WOULD_UPLOAD s3://$UP_ART_BUCKET/jars/$up_m/lib/dep-$up_m.jar bytes="
done
for up_f in "$REPO"/scripts/emr/*; do
  a_out "WOULD_UPLOAD s3://$UP_ART_BUCKET/bootstrap/${up_f##*/} bytes="
done
for up_f in spark-4.0.5-bin-hadoop3.tgz spark-4.0.5-jars.zip spark-4.0.5.sha256; do
  a_out "WOULD_UPLOAD s3://$UP_ART_BUCKET/jars/spark-4.0.5/$up_f bytes=$(wc -c < "$UP_SPARK/$up_f" | tr -d ' ') sha256=$(sha_of "$UP_SPARK/$up_f")"
done
a_not_out_re "^WOULD_UPLOAD s3://$UP_DATA_BUCKET/(jars|bootstrap)/"
a_not_out_re "^WOULD_UPLOAD s3://$UP_ART_BUCKET/raw/"
a_not_out "UPLOADED"
case_end
case_begin "14.upload.dry-run.spark-checksums-verified"
a_out "SPARK_CHECKSUMS_VERIFIED file=$UP_SPARK/spark-4.0.5.sha256"
case_end
case_begin "14.upload.dry-run.says-what-the-real-run-would-do"
a_out "one aws s3 cp per file"
a_out "no sync, no delete"
a_out "aws s3api head-object for every key: ContentLength must equal the local size"
a_not_out "--sse-c"
a_not_out "s3 sync"
a_not_out "--delete"
a_out "APPROVAL_NOT_CONSUMED dry-run"
a_not_out "APPROVAL_CONSUMED"
a_state_empty
case_end
case_begin "14.upload.dry-run.step-order"
a_order "validate-params: ok" "plan-files: ok" "approval-record: ok" "identity-check: dry-run" "upload: dry-run" "verify-upload: dry-run" "DRY_RUN_COMPLETE"
case_end

# The digest binds the exact file list, the buckets, the snapshot and both limits.
up_reset
common_args "$ACCT" us-east-2 "$CEIL"
up_args --print-digest -
run_build
case_begin "14.upload.print-digest.canonical-binds-file-list"
a_rc 0
a_out "ACTION=upload"
a_out_re '^ARGS_CANONICAL=action=upload;environment=course;account='"$ACCT"';region=us-east-2;cost='"$CEIL"';extra=snapshot='"$UP_SNAP"';data_bucket='"$UP_DATA_BUCKET"';artifacts_bucket='"$UP_ART_BUCKET"';objects='"$UP_N"';bytes='"$UP_BYTES"';max_objects='"$UP_MAXO"';max_bytes='"$UP_MAXB"';files=[0-9a-f]{64}$'
a_trip_empty
case_end

up_digest_differs() {
  up_digest
  case_begin "14.upload.digest-changes-with.$1"
  a_rc 0
  a_out_re '^ARGS_DIGEST=[0-9a-f]{64}$'
  [ "$UD_A" != "$UP_BASE_A" ] || note_fail "ARGS_DIGEST did not change"
  [ "$UD_S" = "$UP_BASE_S" ] || note_fail "SCRIPT_DIGEST changed"
  a_trip_empty
  case_end
}

up_reset
printf 'harness dataset openalex.jsonX\n' > "$REPO/datasets/subset/openalex.jsonl"
up_digest_differs changed-file-content-of-the-same-size
up_restore

up_reset
printf 'extra\n' > "$REPO/modules/hadoop/target/job/lib/extra.jar"
up_digest
case_begin "14.upload.digest-changes-with.added-file"
a_rc 0
a_out "objects=$((UP_N + 1));"
[ "$UD_A" != "$UP_BASE_A" ] || note_fail "ARGS_DIGEST did not change"
a_trip_empty
case_end
up_restore

up_reset
UPV_DB="harness-other-bucket"
up_digest_differs other-data-bucket
up_reset
UPV_SNAP="harness-snap2"
up_digest_differs other-snapshot
up_reset
UPV_MAXO=$((UP_MAXO + 1))
up_digest_differs other-max-objects

# A record written for one file list does not cover a changed file.
up_reset
up_dry_record "$REC/upload-old.env"
printf 'harness dataset openalex.jsonX\n' > "$REPO/datasets/subset/openalex.jsonl"
common_args "$ACCT" us-east-2 "$CEIL"
up_args --dry-run "$REC/upload-old.env"
run_build
case_begin "14.upload.changed-file-invalidates-an-existing-record"
a_refused "record ARGS_DIGEST does not match these exact arguments"
case_end
up_restore

# Limits: the count and the size are inclusive, one below is refused, and both are required.
up_reset
UPV_MAXO="$UP_N"
up_dry_record "$REC/upload-maxo.env"
case_begin "14.upload.max-objects-exactly-the-count-accepted"
a_dry_common
a_out "UPLOAD_PLAN objects=$UP_N bytes=$UP_BYTES max_objects=$UP_N "
case_end
up_reset
UPV_MAXO=$((UP_N - 1))
up_refuse max-objects-one-below-refused "$UP_N objects exceed --max-objects $((UP_N - 1))"
up_reset
UPV_MAXB="$UP_BYTES"
up_dry_record "$REC/upload-maxb.env"
case_begin "14.upload.max-bytes-exactly-the-total-accepted"
a_dry_common
a_out "UPLOAD_PLAN objects=$UP_N bytes=$UP_BYTES max_objects=$UP_MAXO max_bytes=$UP_BYTES"
case_end
up_reset
UPV_MAXB=$((UP_BYTES - 1))
up_refuse max-bytes-one-below-refused "$UP_BYTES bytes exceed --max-bytes $((UP_BYTES - 1))"
up_reset
UPV_MAXO=""
up_refuse max-objects-missing "--max-objects and --max-bytes are required"
up_reset
UPV_MAXB=""
up_refuse max-bytes-missing "--max-objects and --max-bytes are required"
up_reset
UPV_MAXO=0
up_refuse max-objects-zero "--max-objects must be a whole number of at least 1"
up_reset
UPV_MAXB=0
up_refuse max-bytes-zero "--max-bytes must be a whole number of at least 1"
up_reset
UPV_MAXO=abc
up_refuse max-objects-non-numeric "--max-objects must be a whole number of at least 1"
up_reset
UPV_MAXB=-5
up_refuse max-bytes-negative "--max-bytes must be a whole number of at least 1"
up_reset
UPV_MAXB=1.5
up_refuse max-bytes-decimal "--max-bytes must be a whole number of at least 1"
up_reset
UPV_MAXO=$((UP_N - 1))
up_refuse max-objects-real-run-refused-before-any-call "$UP_N objects exceed --max-objects" real
up_reset
UPV_MAXB=$((UP_BYTES - 1))
up_refuse max-bytes-print-digest-refused-with-no-digest "$UP_BYTES bytes exceed --max-bytes" digest

# Snapshot, buckets and the Spark directory.
up_reset
UPV_SNAP="a/b"
up_refuse snapshot-with-slash "--snapshot must be 1 to 64 characters"
up_reset
UPV_SNAP="$(pi_rep 65)"
up_refuse snapshot-65-chars "--snapshot must be 1 to 64 characters"
up_reset
UPV_SNAP="a b"
up_refuse snapshot-with-space "--snapshot must be 1 to 64 characters"
up_reset
UPV_SNAP=""
up_refuse snapshot-missing "--snapshot, --data-bucket, --artifacts-bucket and --spark-dir are required"
up_reset
UPV_SNAP=".."
up_refuse snapshot-dot-dot "--snapshot must not be . or .."
up_reset
UPV_DB="Harness-Data"
up_refuse data-bucket-uppercase "not a bucket name: Harness-Data"
up_reset
UPV_DB="harness_data"
up_refuse data-bucket-underscore "not a bucket name: harness_data"
up_reset
UPV_AB="ab"
up_refuse artifacts-bucket-too-short "not a bucket name: ab"
up_reset
UPV_AB="$UP_DATA_BUCKET"
up_refuse same-bucket-twice "the data and artifacts buckets must differ"
up_reset
UPV_AB=""
up_refuse artifacts-bucket-missing "--snapshot, --data-bucket, --artifacts-bucket and --spark-dir are required"
up_reset
UPV_SPARK=""
up_refuse spark-dir-missing "--snapshot, --data-bucket, --artifacts-bucket and --spark-dir are required"
up_reset
UPV_SPARK="$REPO/spark-dir"
up_refuse spark-dir-inside-repository "--spark-dir must not be inside the repository"
up_reset
UPV_SPARK="relative/spark-dir"
up_refuse spark-dir-relative "--spark-dir must be an absolute path"
up_reset
UPV_SPARK="$WORK/a/../spark-dir"
up_refuse spark-dir-dot-dot "--spark-dir must not contain . or .. segments"

# Files: a symlink, a missing file, a wrong number of main jars, names that look like
# credentials, key material in the content, and Spark files that do not match their sums.
up_reset
rm -f "$REPO/datasets/subset/orcid.jsonl"
ln -s "$REPO/datasets/subset/openalex.jsonl" "$REPO/datasets/subset/orcid.jsonl"
rec_base upload "$UP_BASE_S" "$ZERO64" n/a n/a
rec_write "$REC/upload-skip.env"
common_args "$ACCT" us-east-2 "$CEIL"
up_args --dry-run "$REC/upload-skip.env"
run_build
case_begin "14.upload.symlinked-dataset.dry-run-skips-the-file-list-and-says-so"
a_dry_common
a_out "CHECK_SKIPPED file-list: not a regular file (or a symlink): $REPO/datasets/subset/orcid.jsonl (not done, so not passed)"
a_out "APPROVAL_CHECK_SKIPPED field=ARGS_DIGEST"
a_out "APPROVAL_SKIPPED_FIELDS=ARGS_DIGEST"
a_not_out "UPLOAD_PLAN"
a_not_out "WOULD_UPLOAD"
case_end
up_refuse symlinked-dataset-real-run-refused "cannot build the file list: not a regular file (or a symlink): $REPO/datasets/subset/orcid.jsonl" real
up_refuse symlinked-dataset-print-digest-refused "cannot build the file list: not a regular file (or a symlink): $REPO/datasets/subset/orcid.jsonl" digest
up_restore

rm -f "$REPO/datasets/subset/orcid.jsonl"
up_refuse missing-dataset-file-real-run-refused "cannot build the file list: not a regular file (or a symlink): $REPO/datasets/subset/orcid.jsonl" real
up_restore

rm -rf "$REPO/modules/hadoop/target/job/lib"
up_refuse missing-lib-directory-real-run-refused "cannot build the file list: no $REPO/modules/hadoop/target/job/lib (run sbt packageJobs through scripts/package.sh)" real
up_restore

printf 'x\n' > "$REPO/modules/spark/target/job/second-job.jar"
up_refuse two-main-jars-real-run-refused "expected exactly one main jar in $REPO/modules/spark/target/job, found 2" real
up_restore

rm -f "$REPO/modules/entity-resolution/target/job/entity-resolution-job.jar"
up_refuse no-main-jar-real-run-refused "expected exactly one main jar in $REPO/modules/entity-resolution/target/job, found 0" real
up_restore

rm -f "$REPO/modules/hadoop/target/job/lib/dep-hadoop.jar"
ln -s "$REPO/modules/hadoop/target/job/hadoop-job.jar" "$REPO/modules/hadoop/target/job/lib/dep-hadoop.jar"
up_refuse symlinked-lib-jar-real-run-refused "cannot build the file list: not a regular file (or a symlink): $REPO/modules/hadoop/target/job/lib/dep-hadoop.jar" real
up_restore

printf 'x\n' > "$REPO/modules/hadoop/target/job/lib/my-credentials.jar"
up_refuse credential-named-jar-refused-in-a-dry-run "refusing a file named like a credential or secret: $REPO/modules/hadoop/target/job/lib/my-credentials.jar"
up_restore

printf 'x\n' > "$REPO/modules/spark/target/job/lib/SECRET-Lib.jar"
up_refuse secret-named-jar-refused-in-a-real-run "refusing a file named like a credential or secret: $REPO/modules/spark/target/job/lib/SECRET-Lib.jar" real
up_restore

printf '# x\n' > "$REPO/scripts/emr/my-credentials.sh"
up_refuse credential-named-emr-script-refused-in-a-dry-run "refusing a file named like a credential or secret: $REPO/scripts/emr/my-credentials.sh"
up_restore

printf 'note %s\n' "$FAKE_AKIA" > "$REPO/datasets/subset/dblp-subset.xml"
UP_PLANTED="$FAKE_AKIA"
up_refuse key-material-in-a-dataset-real-run-refused "possible credential material" real
up_restore

printf 'note %s\n' "$FAKE_AKIA" > "$REPO/modules/entity-resolution/target/job/lib/dep-entity-resolution.jar"
UP_PLANTED="$FAKE_AKIA"
up_refuse key-material-in-a-lib-jar-dry-run-refused "possible credential material"
up_restore

printf '%s\n' "$FAKE_PEM" >> "$REPO/scripts/emr/capability-check.sh"
UP_PLANTED="$FAKE_PEM"
up_refuse key-material-in-an-emr-script-real-run-refused "possible credential material" real
up_restore

printf 'tampered\n' > "$UP_SPARK/spark-4.0.5-jars.zip"
up_refuse spark-archive-not-matching-the-sums-refused "spark-4.0.5-jars.zip does not match spark-4.0.5.sha256"
up_restore

printf '%s  spark-4.0.5-bin-hadoop3.tgz\n' "$(sha_of "$UP_SPARK/spark-4.0.5-bin-hadoop3.tgz")" > "$UP_SPARK/spark-4.0.5.sha256"
up_refuse spark-sums-file-without-a-line-for-the-archive-refused "spark-4.0.5.sha256 has no sha256 line for spark-4.0.5-jars.zip"
up_restore

rm -f "$UP_SPARK/spark-4.0.5-jars.zip"
up_refuse spark-archive-missing-real-run-refused "cannot build the file list: not a regular file (or a symlink): $UP_SPARK/spark-4.0.5-jars.zip" real
up_restore

# The real scripts/emr files go through the same secret scan upload-inputs.sh applies
# (scan_for_secrets from lib.sh). Predicted to fail for lib-emr.sh: it holds the key-material
# pattern list itself, so the scan matches its own pattern line and the upload would stop.
scan_with_lib() {
  RC=0
  (
    # SC_SCRIPT is read by script_digest in the sourced lib.sh, which shellcheck does not follow here.
    # shellcheck disable=SC2034
    SC_SCRIPT="$REPO/scripts/upload-inputs.sh"
    # shellcheck source=/dev/null
    source "$REPO/scripts/lib.sh"
    scan_for_secrets "$1"
  ) > "$LOGDIR/scan.out" 2> "$LOGDIR/scan.err" < /dev/null || RC=$?
  OUT="$LOGDIR/scan.out"
  ERR="$LOGDIR/scan.err"
}
for up_f in "$REAL_ROOT"/scripts/emr/*; do
  [ -f "$up_f" ] || continue
  scan_with_lib "$up_f"
  case_begin "14.upload.real-emr-file.${up_f##*/}.passes-the-upload-secret-scan"
  a_rc 0
  [ ! -s "$ERR" ] || note_fail "scan stderr: $(head -n 1 "$ERR" | cut -c1-140)"
  case_end
done

# Static content of scripts/upload-inputs.sh.
# shellcheck disable=SC2016
static_upload_cases() {
  local c p
  c="$(code_of "$REPO/scripts/upload-inputs.sh")"
  static_begin "14.upload.static.one-aws-s3-cp-per-file-with-errors-only"
  f_count_is "$c" 'aws s3 cp "$src" "s3://$bucket/$key" --only-show-errors' 1
  case_end
  static_begin "14.upload.static.no-sse-c-no-sync-no-delete-no-overwrite-options"
  for p in '--sse-c' '--delete' 's3 sync' 's3 rm' 's3 mv' 'delete-object' 'put-object' '--acl' '--recursive' '--storage-class'; do
    f_has_not "$c" "$p"
  done
  case_end
  static_begin "14.upload.static.head-object-checks-the-size-and-reports-the-encryption"
  f_has "$c" 'aws s3api head-object --bucket "$bucket" --key "$key" --query ContentLength'
  f_has "$c" '[[ "$got" == "$bytes" ]] || die "size mismatch'
  f_has "$c" '--query ServerSideEncryption'
  case_end
  static_begin "14.upload.static.record-checked-then-identity-then-consume-then-copy-then-verify"
  f_before "$c" 'step plan-files' 'verify_approval upload'
  f_before "$c" 'verify_approval upload' 'verify_identity'
  f_before "$c" 'verify_identity' 'consume_approval upload'
  f_before "$c" 'consume_approval upload' 'aws s3 cp "$src"'
  f_before "$c" 'aws s3 cp "$src"' 'aws s3api head-object --bucket'
  case_end
  static_begin "14.upload.static.credential-name-list-is-complete"
  for p in '.env | .env.*' '*credential*' '*secret*' '*.pem' '*.key' '*.p12' '*.pfx' '*.tfstate*' '*.tfplan' '.netrc' '.npmrc' 'id_rsa*' 'id_ed25519*'; do
    f_has "$c" "$p"
  done
  case_end
  static_begin "14.upload.static.one-action-name-everywhere"
  f_has "$c" 'maybe_print_digest upload "$EXTRA"'
  f_has "$c" 'dry_banner upload "$EXTRA"'
  f_has "$c" 'verify_approval upload "$EXTRA" "n/a" "n/a"'
  f_has "$c" 'consume_approval upload'
  case_end
}
static_upload_cases
up_restore

### 14.emr: static content of scripts/emr/* (read as text from the real repository) ---------

EMR_DIR="$REAL_ROOT/scripts/emr"
EMR_SH="lib-emr.sh capability-check.sh run-s-pipeline.sh install-java-bootstrap.sh"
# shellcheck disable=SC2016
CRED_PATTERNS=(
  '^[[:space:]]*(printenv|export -p|declare -p|declare -x)([[:space:]]|$)'
  '^[[:space:]]*(env|set)[[:space:]]*($|\||>)'
  '^[[:space:]]*set[[:space:]]+(-[a-zA-Z]*x|-o[[:space:]]+xtrace)'
  '(^|[[:space:]])(ba)?sh[[:space:]]+-[a-zA-Z]*x'
  'AWS_(SECRET|SESSION|ACCESS)'
  '(echo|printf)[^|]*\$\{?AWS_'
  '/proc/[^ ]*/environ'
  'get-secret-value|get-parameter|169\.254\.169\.254|security-credentials|aws configure|sts get-session-token|assume-role'
  '\.aws/(credentials|config)|credential_process'
)
EMR_KEY_RE='AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIV''ATE KEY-----|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|[Aa]uthorization:[[:space:]]*[Bb]earer[[:space:]]+[A-Za-z0-9]'

# shellcheck disable=SC2016
for emr_n in $EMR_SH; do
  emr_p="$EMR_DIR/$emr_n"
  static_begin "14.emr.$emr_n.strict-mode"
  if [ ! -f "$emr_p" ]; then
    note_fail "missing $emr_p"
  else
    emr_c="$(code_of "$emr_p")"
    emr_lib="$(code_of "$EMR_DIR/lib-emr.sh")"
    if grep -qE '^[[:space:]]*set -euo pipefail[[:space:]]*$' "$emr_c"; then
      :
    elif grep -qF 'source "$SC_SCRIPT_DIR/lib-emr.sh"' "$emr_c" && grep -qE '^set -euo pipefail[[:space:]]*$' "$emr_lib"; then
      :
    else
      note_fail "$emr_n has no set -euo pipefail and does not source lib-emr.sh, which has it"
    fi
  fi
  case_end

  static_begin "14.emr.$emr_n.no-credential-printing"
  if [ ! -f "$emr_p" ]; then
    note_fail "missing $emr_p"
  else
    emr_c="$(code_of "$emr_p")"
    for emr_pat in "${CRED_PATTERNS[@]}"; do
      f_has_not_re "$emr_c" "$emr_pat"
    done
  fi
  case_end

  static_begin "14.emr.$emr_n.no-sse-c-no-sync-no-delete"
  if [ ! -f "$emr_p" ]; then
    note_fail "missing $emr_p"
  else
    emr_c="$(code_of "$emr_p")"
    for emr_pat in '--sse-c' '--sse-customer' 's3 sync' 's3 rm' 's3 mv' '--delete' 'delete-object'; do
      f_has_not "$emr_c" "$emr_pat"
    done
  fi
  case_end

  static_begin "14.emr.$emr_n.no-key-material-literal"
  if [ ! -f "$emr_p" ]; then
    note_fail "missing $emr_p"
  else
    f_has_not_re "$emr_p" "$EMR_KEY_RE"
  fi
  case_end
done

emr_cap="$(code_of "$EMR_DIR/capability-check.sh")"
emr_run="$(code_of "$EMR_DIR/run-s-pipeline.sh")"
emr_lib="$(code_of "$EMR_DIR/lib-emr.sh")"
emr_boot="$(code_of "$EMR_DIR/install-java-bootstrap.sh")"

static_begin "14.emr.capability-check.local-mode-wording"
f_has "$emr_cap" 'MapReduce in local mode on an EMR node'
f_has "$emr_cap" 'mr="local"'
case_end

static_begin "14.emr.run-s-pipeline.local-mode-wording"
f_has "$emr_run" 'MapReduce in local mode on an EMR node'
f_has "$emr_run" 'mr="local"'
case_end

static_begin "14.emr.no-distributed-mapreduce-claim"
emr_claim="$(grep -hiE 'distributed[[:space:]]+(mapreduce|execution|processing|computing)' "$EMR_DIR"/* | grep -viE '(never|not|no)[[:space:]]+distributed' | head -n 1)"
[ -z "$emr_claim" ] || note_fail "a line claims distributed execution without a negation: $(printf '%s' "$emr_claim" | cut -c1-120)"
case_end

static_begin "14.emr.run-s-pipeline.single-node-scope-wording"
f_has "$emr_run" 'data-size scaling on a single-node EMR cluster (Spark and MapReduce on YARN)'
case_end

# Work order 46: the on-node side of the run id shape. The slug (run-v1-<uuid>) stays the
# work directory, the S3 prefixes and the loader argument; only -Dscholarchain.run.id gets
# the stable form run:v1:<uuid> that core's StableIds requires.
# SC2016: every single-quoted string below is a literal fragment of a node script that
# f_has, f_before, f_has_not and f_count_is search for as text; the $ must not expand.
# A function, so that one directive covers the whole block (as static_pipeline_cases does).
# shellcheck disable=SC2016
static_run_id_emr_cases() {
static_begin "14.emr.run-s-pipeline.validates-the-run-id-shape-before-any-stage-work"
f_has "$emr_run" 'validate_run_id "$run_id"'
f_before "$emr_run" 'validate_run_id "$run_id"' 'work="$SC_HOME/run/$run_id"'
f_before "$emr_run" 'validate_run_id "$run_id"' 'mkdir -p "$raw" "$rep"'
f_before "$emr_run" 'validate_run_id "$run_id"' 'register_failure_logs'
f_before "$emr_run" 'validate_run_id "$run_id"' 'find_java'
f_before "$emr_run" 'validate_run_id "$run_id"' 'aws s3 cp'
case_end

static_begin "14.emr.run-s-pipeline.run-id-option-takes-the-stable-form"
f_has "$emr_run" 'local stable_run_id="$SC_STABLE_RUN_ID"'
f_has "$emr_run" '"-Dscholarchain.run.id=$stable_run_id"'
f_count_is "$emr_run" '-Dscholarchain.run.id=' 1
f_has_not_re "$emr_run" 'run[.]id=[^[:space:]]*[$][{]?run_id'
case_end

static_begin "14.emr.run-s-pipeline.prints-run-id-stable-next-to-the-slug"
f_has "$emr_run" 'kv RUN_ID "$run_id"'
f_has "$emr_run" 'kv RUN_ID_STABLE "$stable_run_id"'
f_before "$emr_run" 'kv RUN_ID "$run_id"' 'kv RUN_ID_STABLE "$stable_run_id"'
case_end

static_begin "14.emr.run-s-pipeline.slug-stays-the-work-directory-and-the-s3-prefix"
f_has "$emr_run" 'work="$SC_HOME/run/$run_id"'
f_has "$emr_run" 's3://$data_bucket/curated/runs/$run_id/hadoop/'
f_has "$emr_run" 's3://$data_bucket/graph/runs/$run_id/'
f_has "$emr_run" 's3://$data_bucket/semantic-context/runs/$run_id/'
f_has "$emr_run" 's3://$data_bucket/reports/runs/$run_id/'
f_has_not "$emr_run" 'runs/$stable_run_id'
f_has_not "$emr_run" 'run/$stable_run_id'
case_end

static_begin "14.emr.no-scholarchain-run-id-option-takes-the-slug-in-any-script"
emr_slug_line="$(grep -h 'scholarchain[.]run[.]id' "$emr_cap" "$emr_run" "$emr_lib" "$emr_boot" | grep -vF 'stable_run_id' | head -n 1)"
[ -z "$emr_slug_line" ] || note_fail "a code line names scholarchain.run.id without the stable form: $(printf '%s' "$emr_slug_line" | cut -c1-120)"
case_end

static_begin "14.emr.lib-emr.validate-run-id-is-the-lower-case-slug-pattern-and-derives-the-stable-id"
f_has "$emr_lib" '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
f_has "$emr_lib" '^run-v1-('
f_has "$emr_lib" 'SC_STABLE_RUN_ID="run:v1:${BASH_REMATCH[1]}"'
f_has "$emr_lib" 'run-id must be exactly run-v1- followed by a lower-case canonical UUID'
f_has_not "$emr_lib" 'nocasematch'
f_has_not "$emr_lib" 'A-F'
case_end
}
static_run_id_emr_cases

# validate_run_id from the real lib-emr.sh, sourced in a child shell (sourcing reads and
# defines only; no stub is called). SC_STABLE_RUN_ID is printed on success.
# emr_vr ID VALUE [EXPECTED_STABLE]: no EXPECTED_STABLE means a refusal is expected.
# SC2016: the bash -c program is single-quoted on purpose; the child shell expands $1 and $2.
# shellcheck disable=SC2016
emr_vr() {
  local id="$1" val="$2" want="${3:-}"
  run bash -c 'source "$1" && validate_run_id "$2" && printf "%s" "$SC_STABLE_RUN_ID"' _ "$EMR_DIR/lib-emr.sh" "$val"
  case_begin "14.emr.lib-emr.validate_run_id.$id"
  if [ -n "$want" ]; then
    a_rc 0
    [ "$(cat "$OUT")" = "$want" ] || note_fail "SC_STABLE_RUN_ID is not $want"
  else
    a_rc_nonzero
    a_err_evidence
    a_err "run-id must be exactly run-v1- followed by a lower-case canonical UUID"
    [ ! -s "$OUT" ] || note_fail "stdout is not empty on a refusal"
  fi
  a_trip_empty
  case_end
}
emr_vr valid-id-gives-the-stable-form "$PI_RUN" "run:v1:$PI_UUID"
emr_vr valid-all-f-id-gives-the-stable-form "$RUN_ALLF" "run:v1:ffffffff-ffff-ffff-ffff-ffffffffffff"
emr_vr path-safe-wrong-shape-refused "s-20261114-2"
emr_vr empty-refused ""
emr_vr name-64-chars-refused "$(pi_rep 64)"
emr_vr uppercase-uuid-refused "run-v1-$PI_UUID_UPPER"
emr_vr wrong-version-tag-refused "run-v2-$PI_UUID"
emr_vr last-group-one-short-refused "${PI_RUN%?}"
emr_vr last-group-one-long-refused "${PI_RUN}a"
emr_vr trailing-newline-refused "$PI_RUN"$'\n'
emr_vr stable-colon-form-refused "run:v1:$PI_UUID"

static_begin "14.emr.capability-check.scope-lines-say-not-distributed"
f_has "$emr_cap" 'kv MAPREDUCE_SCOPE "single node: MapReduce on YARN on one node is not distributed execution"'
f_has "$emr_cap" 'kv SPARK_SCOPE "single node: Spark on YARN on one node is not distributed execution"'
case_end

static_begin "14.emr.region-is-us-east-2-only"
f_has "$emr_lib" 'SC_REGION="us-east-2"'
emr_region="$(grep -hE '(^|[^a-z0-9])(us-east-1|us-west-[12]|eu-[a-z]+-[0-9]|ap-[a-z]+-[0-9])' "$EMR_DIR"/* | head -n 1)"
[ -z "$emr_region" ] || note_fail "another Region is named: $(printf '%s' "$emr_region" | cut -c1-120)"
case_end

static_begin "14.emr.key-material-pattern-text-only-on-its-own-line"
emr_pat_lines="$(grep -hF 'aws_secret_access_key' "$EMR_DIR"/*.sh | grep -vF 'SC_SECRET_RE=' | head -n 1)"
[ -z "$emr_pat_lines" ] || note_fail "aws_secret_access_key appears outside the SC_SECRET_RE line: $(printf '%s' "$emr_pat_lines" | cut -c1-120)"
case_end

static_begin "14.emr.install-java-bootstrap.sh.only-java-17-or-21"
f_has "$emr_boot" '17 | 21) ;;'
# shellcheck disable=SC2016
f_has "$emr_boot" 'major="${1:-17}"'
case_end

static_begin "14.emr.install-java-bootstrap.sh.no-download-and-no-gpg-bypass"
f_has_not_re "$emr_boot" '(^|[^a-z])(curl|wget)([^a-z]|$)'
f_has_not_re "$emr_boot" 'nogpgcheck|no-gpgcheck|gpgcheck=0'
case_end

static_begin "14.emr.spark-yarn-conf.holds-only-key-value-settings"
emr_bad="$(grep -vE '^[[:space:]]*(#.*)?$' "$EMR_DIR/spark-yarn.conf" | grep -vE '^[A-Z_]+=[A-Za-z0-9._-]*$' | head -n 1)"
[ -z "$emr_bad" ] || note_fail "a line is not KEY=VALUE: $emr_bad"
[ -f "$EMR_DIR/spark-yarn.conf" ] || note_fail "scripts/emr/spark-yarn.conf is missing"
case_end

static_begin "14.emr.every-file-in-the-directory-is-on-the-upload-list"
for emr_f in "$EMR_DIR"/*; do
  case "${emr_f##*/}" in
    *.sh | spark-yarn.conf) ;;
    *) note_fail "upload-inputs.sh uploads only scripts/emr/*.sh and spark-yarn.conf, not ${emr_f##*/}" ;;
  esac
done
case_end

### 14.emr and 14.terraform: work orders 48 and 49 ----------------------------------------------
# The MapReduce verdict from the ResourceManager application count (D30, D31), the job id
# sources, STAGE_SECONDS_ lines, the progress checkpoints, the publish count, the two Spark
# executors and Rule A. Static cases read the scripts as text. Dynamic cases source the real
# lib-emr.sh in a child shell (a bash program under $CODE_DIR) with shell functions standing in
# for timeout, yarn, mapred and aws, so no stub on PATH is reached and the tripwire stays
# empty. The verdict itself is an inline block of run-s-pipeline.sh, which cannot be sourced
# (it runs pipeline_main); it is cut out of the real file by markers and run with the real
# lib-emr.sh helpers that feed it. No loosened expectation: the old verdict cases of work
# order 43 do not exist in this file (it asserted only the "local mode" wording, which stays).

EMR_FX="$WORK/emr-fx"
mkdir -p "$EMR_FX"
MR_TS=1794701635353
MR_TS_OTHER=1700000000000
MR_BLOCK="$CODE_DIR/mr-verdict-block.sh"
PG_FN="$CODE_DIR/progress-fn.sh"

# extract_verdict_block: the lines of run-s-pipeline.sh from "mrkind=..." up to, not including,
# the first later line that holds kv MAPREDUCE "$mr" (the verdict decision itself).
# SC2016: the awk markers are single-quoted on purpose, the $ is text of the node script.
# shellcheck disable=SC2016
extract_verdict_block() {
  awk -v s='mrkind=$((SC_MR_YARN + mrapps_n))' -v e='kv MAPREDUCE "$mr"' 'f == 0 && index($0, s) { f = 1 } f == 1 && index($0, e) { exit } f == 1 { print }' "$emr_run" > "$MR_BLOCK"
}
extract_verdict_block

# extract_fn (FILE NAME OUT) is defined with the other helpers near the top of case 14 (work order 99).
extract_fn "$emr_lib" sc_publish_progress "$PG_FN"

# Child program 1: the helpers that feed the verdict, then the real verdict block.
cat > "$CODE_DIR/mr-chain.sh" <<'EOF'
LIB="$1"; BLOCK="$2"; FIX="$3"; EXPECTED="$4"
source "$LIB"
timeout() { shift; "$@"; }
yarn() { if [ -f "$FIX/yarn.txt" ]; then cat "$FIX/yarn.txt"; fi; return 0; }
mapred() {
  if [ "${3:-}" = "all" ]; then
    if [ -f "$FIX/mapred-all.txt" ]; then cat "$FIX/mapred-all.txt"; fi
  else
    if [ -f "$FIX/mapred-list.txt" ]; then cat "$FIX/mapred-list.txt"; fi
  fi
  return 0
}
rep="$FIX/reports"
mkdir -p "$rep"
classify_job_ids "$FIX/hadoop.log"
mrapps="$(yarn_mapreduce_apps "$rep/mapreduce-yarn-list.log")"
mrapps_n="$(words_in "$mrapps")"
choose_mapreduce_job_ids "$EXPECTED" "$mrapps" "$rep/mapreduce-job-list.log"
mr="unknown"; mrdetail=""; mrattn="yes"; mrproof="none"; mrexpected="$EXPECTED"
eval "$(cat "$BLOCK")"
printf 'MR=%s\n' "$mr"
printf 'PROOF=%s\n' "$mrproof"
printf 'ATTN=%s\n' "$mrattn"
printf 'SRC=%s\n' "$SC_MRF_SOURCE"
printf 'N=%s\n' "$SC_MRF_N"
printf 'IDS=%s\n' "$SC_MRF_IDS"
printf 'APPS_N=%s\n' "$mrapps_n"
printf 'LOCAL=%s\n' "$SC_MR_LOCAL"
printf 'YARN=%s\n' "$SC_MR_YARN"
printf 'DETAIL=%s\n' "$mrdetail"
EOF

# Child program 2: the small pure helpers.
cat > "$CODE_DIR/fn-child.sh" <<'EOF'
LIB="$1"; FIX="$2"
source "$LIB"
printf 'DERIVE1=%s\n' "$(derive_job_ids "application_1794701635353_0001 application_1794701635353_0002")"
printf 'DERIVE2=%s\n' "$(derive_job_ids "")"
printf 'DERIVE3=%s\n' "$(derive_job_ids "application_x_1 foo application_17_9")"
printf 'WORDS0=%s\n' "$(words_in "")"
printf 'WORDS3=%s\n' "$(words_in "a b c")"
printf 'SORTED=%s\n' "$(job_ids_in_file "$FIX/jobs.txt")"
classify_job_ids "$FIX/classify.log"
printf 'CLS_N=%s\n' "$SC_MR_N"
printf 'CLS_YARN=%s\n' "$SC_MR_YARN"
printf 'CLS_LOCAL=%s\n' "$SC_MR_LOCAL"
printf 'CLS_IDS=%s\n' "$SC_MR_IDS"
EOF

# Child program 3: stage and the exit trap with a controlled SECONDS counter.
cat > "$CODE_DIR/stage-child.sh" <<'EOF'
LIB="$1"; MODE="$2"
source "$LIB"
SC_T0=0
if [ "$MODE" = "ok" ]; then
  SECONDS=1000
  stage alpha-beta
  SECONDS=1042
  stage_ok
  record_total_seconds STAGE_SECONDS_TOTAL
else
  install_trap
  SECONDS=500
  stage boom-stage
  SECONDS=507
  false
fi
EOF

# Child program 4: sc_publish_progress and stage_ok with aws and timeout replaced by functions
# that log every call (CALLS) and copy every uploaded file (UPLOADS). MODE: ok, fail (aws
# exits 1), unregistered, nodir (reports directory missing) or stages (stage and stage_ok).
# CAP and BUDGET (environment) lower SC_PROGRESS_MAX_BYTES and SC_PROGRESS_BUDGET_SECONDS.
cat > "$CODE_DIR/progress-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; MODE="$3"
source "$LIB"
UPLOADS="$FIX/uploads"
CALLS="$FIX/calls.log"
mkdir -p "$UPLOADS"
: > "$CALLS"
timeout() { printf 'TIMEOUT %s\n' "$1" >> "$CALLS"; shift; "$@"; }
aws() {
  if [ "$MODE" = "fail" ]; then
    printf 'AWS-FAILED %s\n' "$*" >> "$CALLS"
    return 1
  fi
  printf 'AWS %s\n' "$*" >> "$CALLS"
  if [ "${1:-}" = "s3" ] && [ "${2:-}" = "cp" ]; then
    cp "$3" "$UPLOADS/${4##*/}"
  fi
  return 0
}
if [ -n "${CAP:-}" ]; then SC_PROGRESS_MAX_BYTES="$CAP"; fi
if [ -n "${BUDGET:-}" ]; then SC_PROGRESS_BUDGET_SECONDS="$BUDGET"; fi
SC_KV=$'RUN_ID=abc\nMAPREDUCE=yarn\n'
case "$MODE" in
  unregistered) ;;
  nodir) register_failure_logs "$FIX/no-such-dir" data-bucket run-v1-x ;;
  *) register_failure_logs "$FIX/reports" data-bucket run-v1-x ;;
esac
if [ "$MODE" = "stages" ]; then
  stage one-a
  stage_ok
  stage publish
  stage_ok
else
  SC_STAGE_N=3
  SC_STAGE_NAME=hadoop
  rc=0
  sc_publish_progress "checkpoint-1" || rc=$?
  printf 'RC=%s\n' "$rc"
fi
EOF

# Child program 5: publish with an aws function that answers s3 ls from a fixture.
cat > "$CODE_DIR/publish-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; DIR="$3"; URI="$4"
source "$LIB"
aws() {
  if [ "${1:-}" = "s3" ] && [ "${2:-}" = "ls" ]; then
    cat "$FIX/ls.txt"
    return 0
  fi
  printf 'AWS %s\n' "$*" >> "$FIX/calls.log"
  return 0
}
publish "$DIR" "$URI"
printf 'RC=%s\n' "$?"
EOF

# Child program 6: write_spark_conf under a temporary SC_HOME.
cat > "$CODE_DIR/sparkconf-child.sh" <<'EOF'
SC_HOME="$2"
source "$1"
SC_ARCHIVE_URI="hdfs://harness/spark-jars.zip"
write_spark_conf false
cat "$SC_SPARK_CONF_DIR/spark-defaults.conf"
EOF

### Static cases: run-s-pipeline.sh and lib-emr.sh as text.
# SC2016: every single-quoted string below is a literal fragment of a node script that
# f_has, f_before and f_count_is search for as text; the $ must not expand.
# shellcheck disable=SC2016
static_wo50_cases() {
  local k tl pl cl blk_n
  static_begin "14.emr.verdict.block-extracted-and-never-stops-the-run"
  blk_n="$(wc -l < "$MR_BLOCK" | tr -d ' ')"
  [ "${blk_n:-0}" -ge 15 ] || note_fail "the verdict block cut out of run-s-pipeline.sh has ${blk_n:-0} line(s)"
  f_has "$MR_BLOCK" 'mr="mixed"'
  f_has "$MR_BLOCK" 'mr="local"'
  f_has "$MR_BLOCK" 'mr="yarn"'
  f_has_not_re "$MR_BLOCK" '(^|[^a-z_])(die|exit|return)([^a-z_]|$)'
  case_end

  static_begin "14.emr.verdict.expected-stage-count-is-datasets-plus-six-and-the-order-is-recorded"
  f_has "$emr_run" 'local dsets=(crossref openalex orcid) dargs=()'
  f_has "$emr_run" 'mrexpected=$((${#dargs[@]} + 6))'
  f_has "$emr_run" 'mrorder="dblp-extraction"'
  f_has "$emr_run" 'mrorder="$mrorder source-normalization/$f"'
  f_has "$emr_run" 'mrorder="$mrorder identifier-join blocking candidate-features team-expansion graph-export"'
  case_end

  static_begin "14.emr.verdict.call-order-hadoop-then-classify-then-list-then-choose-then-verdict"
  f_before "$emr_run" 'hadoop jar "$hjar"' 'classify_job_ids "$rep/hadoop.log"'
  f_before "$emr_run" '[[ "$rc" -eq 0 ]] || die "PipelineTool exited $rc' 'classify_job_ids "$rep/hadoop.log"'
  f_before "$emr_run" 'classify_job_ids "$rep/hadoop.log"' 'mrapps="$(yarn_mapreduce_apps "$rep/mapreduce-yarn-list.log")"'
  f_before "$emr_run" 'mrapps="$(yarn_mapreduce_apps' 'choose_mapreduce_job_ids "$mrexpected" "$mrapps" "$rep/mapreduce-job-list.log"'
  f_before "$emr_run" 'choose_mapreduce_job_ids "$mrexpected"' 'mrkind=$((SC_MR_YARN + mrapps_n))'
  f_before "$emr_run" 'mrkind=$((SC_MR_YARN + mrapps_n))' 'kv MAPREDUCE "$mr"'
  case_end

  static_begin "14.emr.verdict.local-and-mixed-come-first-and-yarn-needs-the-application-count"
  f_has "$emr_run" 'local mr="unknown" mrdetail="" mrattn="yes" mrproof="none"'
  f_before "$emr_run" 'mr="mixed"' 'mr="local"'
  f_before "$emr_run" 'mr="local"' 'mr="yarn"'
  f_has "$emr_run" 'if [[ "$SC_MR_LOCAL" -gt 0 && "$mrkind" -gt 0 ]]; then'
  f_has "$emr_run" 'elif [[ "$mrapps_n" -eq "$mrexpected" ]]; then'
  f_before "$emr_run" 'elif [[ "$mrapps_n" -eq "$mrexpected" ]]; then' 'mr="yarn"'
  f_count_is "$emr_run" 'mrattn="no"' 1
  f_before "$emr_run" 'mr="yarn"' 'mrattn="no"'
  f_has "$emr_run" 'mrproof="resourcemanager-application-ids"'
  f_has "$emr_run" 'if [[ "$SC_MR_YARN" -eq "$mrexpected" ]]; then'
  f_has "$emr_run" 'mrproof="job-ids-in-log"'
  case_end

  static_begin "14.emr.verdict.record-keys-name-the-proof-the-source-and-the-attention"
  for k in MAPREDUCE MAPREDUCE_PROOF MAPREDUCE_DETAIL MAPREDUCE_STAGES_EXPECTED MAPREDUCE_STAGE_MAPPING MAPREDUCE_JOB_COUNT MAPREDUCE_JOBS_YARN MAPREDUCE_JOBS_LOCAL MAPREDUCE_JOB_IDS_SOURCE MAPREDUCE_JOB_IDS MAPREDUCE_JOB_IDS_FILE MAPREDUCE_APPS_YARN_LIST MAPREDUCE_ATTENTION; do
    f_has "$emr_run" "kv $k \""
  done
  f_has "$emr_run" 'kv MAPREDUCE_PROOF "$mrproof"'
  f_has "$emr_run" 'kv MAPREDUCE_ATTENTION "$mrattn"'
  f_has "$emr_run" 'kv MAPREDUCE_JOB_IDS_SOURCE "$SC_MRF_SOURCE"'
  f_has "$emr_run" 'kv MAPREDUCE_JOB_IDS "${SC_MRF_IDS:-none}"'
  f_has "$emr_run" 'positional and inferred, not read from any log'
  case_end

  static_begin "14.emr.job-ids.source-labels-and-the-derivation-note-in-run-s-pipeline"
  f_has "$emr_run" 'hadoop-log) printf'
  f_has "$emr_run" 'mapred-job-list) printf'
  f_has "$emr_run" 'derived-from-application-ids) printf'
  f_has "$emr_run" 'The ids are DERIVED, not observed: job_<ts>_<n> is the ResourceManager application id application_<ts>_<n>'
  f_count_is "$emr_run" 'kv MAPREDUCE_JOB_IDS_DERIVATION' 1
  f_before "$emr_run" 'if [[ "$SC_MRF_SOURCE" == "derived-from-application-ids" ]]; then' 'kv MAPREDUCE_JOB_IDS_DERIVATION'
  f_has "$emr_run" 'a Hadoop naming property, not an observation in the log'
  case_end

  static_begin "14.emr.verdict.attention-note-whenever-the-verdict-is-not-yarn"
  f_count_is "$emr_run" 'if [[ "$mrattn" == "yes" ]]; then' 2
  f_has "$emr_run" 'note "ATTENTION: MAPREDUCE=$mr, not yarn for every stage: $mrdetail.'
  f_has "$emr_run" 'note "ATTENTION: MAPREDUCE=$mr, not yarn for every stage (see MAPREDUCE_DETAIL in run-record.env)"'
  f_has "$emr_run" 'if [[ "$mr" == "local" || "$mr" == "mixed" ]]; then'
  f_has "$emr_run" 'MapReduce in local mode on an EMR node ($SC_MR_LOCAL job_local... id(s) of $SC_MR_N found)'
  case_end

  static_begin "14.emr.progress.checkpoint-right-after-the-classification-and-before-the-chmod"
  f_count_is "$emr_run" 'sc_publish_progress "after-hadoop-classification" || true' 1
  f_before "$emr_run" 'kv MAPREDUCE_ATTENTION "$mrattn"' 'sc_publish_progress "after-hadoop-classification" || true'
  f_before "$emr_run" 'sc_publish_progress "after-hadoop-classification" || true' 'sc_chmod_readable_tree "$hout"'
  case_end

  static_begin "14.emr.progress.failure-logs-are-registered-before-the-first-stage-ok"
  f_before "$emr_run" 'register_failure_logs "$rep" "$data_bucket" "$run_id"' 'stage_ok'
  f_before "$emr_run" 'mkdir -p "$raw" "$rep"' 'register_failure_logs "$rep" "$data_bucket" "$run_id"'
  case_end

  static_begin "14.emr.stage-seconds.run-s-pipeline-records-the-total-before-and-after-publish"
  f_count_is "$emr_run" 'record_total_seconds STAGE_SECONDS_TOTAL' 2
  f_count_is "$emr_run" 'record_total_seconds STAGE_SECONDS_TOTAL_BEFORE_PUBLISH' 1
  f_before "$emr_run" 'record_total_seconds STAGE_SECONDS_TOTAL_BEFORE_PUBLISH' 'write_json "$rep/run-record.json"'
  f_before "$emr_run" 'write_json "$rep/run-record.json"' 'publish "$rep" "s3://$data_bucket/reports/runs/$run_id/"'
  tl="$(grep -nF -- 'record_total_seconds STAGE_SECONDS_TOTAL' "$emr_run" | tail -n 1 | cut -d: -f1)"
  pl="$(line_of "$emr_run" 'publish "$rep" "s3://$data_bucket/reports/runs/$run_id/"')"
  cl="$(line_of "$emr_run" 'PIPELINE_COMPLETE')"
  if [ -z "$tl" ] || [ -z "$pl" ] || [ -z "$cl" ]; then
    note_fail "a line was not found (total $tl, publish $pl, complete $cl)"
  else
    [ "$tl" -gt "$pl" ] || note_fail "the final total (line $tl) is not after the last publish (line $pl)"
    [ "$tl" -lt "$cl" ] || note_fail "the final total (line $tl) is not before PIPELINE_COMPLETE (line $cl)"
  fi
  case_end

  static_begin "14.emr.pipeline-complete.line-carries-the-proof-the-source-and-total-seconds"
  f_count_is "$emr_run" 'PIPELINE_COMPLETE' 1
  f_has "$emr_run" 'PIPELINE_COMPLETE run_id=%s MAPREDUCE=%s mr_jobs_found=%s mr_jobs_yarn=%s mr_jobs_local=%s mr_jobs_expected=%s mr_yarn_list_apps=%s SPARK_ON_YARN=%s:yes mr_proof=%s mr_job_ids_source=%s total_seconds=%s'
  f_has "$emr_run" '"$run_id" "$mr" "$SC_MRF_N" "$SC_MR_YARN" "$SC_MR_LOCAL" "$mrexpected" "$mrapps_n" "$mode" "$mrproof" "$SC_MRF_SOURCE" "$((SECONDS - SC_T0))"'
  case_end

  static_begin "14.emr.lib-emr.stage-ok-keeps-the-marker-and-adds-the-stage-seconds"
  f_has "$emr_lib" 'SC_STAGE_T0=$SECONDS'
  f_has "$emr_lib" "printf 'STAGE[%s] %s: ok\\n' \"\$SC_STAGE_N\" \"\$SC_STAGE_NAME\""
  f_has "$emr_lib" 'kv "STAGE_SECONDS_$(stage_key)" "$elapsed"'
  f_before "$emr_lib" "printf 'STAGE[%s] %s: ok" 'kv "STAGE_SECONDS_$(stage_key)" "$elapsed"'
  f_has "$emr_lib" "tr 'a-z-' 'A-Z_'"
  f_has "$emr_lib" 'if [[ "$SC_STAGE_NAME" != "publish" ]]; then'
  f_has "$emr_lib" 'sc_publish_progress "after-stage-$SC_STAGE_N-$SC_STAGE_NAME" || true'
  f_has "$emr_lib" 'kv "STAGE_SECONDS_$(stage_key)" "$((SECONDS - SC_STAGE_T0)) (stage failed)"'
  f_has "$emr_lib" 'record_total_seconds STAGE_SECONDS_TOTAL_AT_FAILURE'
  case_end

  static_begin "14.emr.lib-emr.job-id-sources-in-order-with-their-labels-and-the-tie-rule"
  f_has "$emr_lib" 'SC_MRF_SOURCE="hadoop-log"'
  f_has "$emr_lib" 'SC_MRF_SOURCE="mapred-job-list"'
  f_has "$emr_lib" 'SC_MRF_SOURCE="derived-from-application-ids"'
  f_has "$emr_lib" 'SC_MRF_SOURCE="none"'
  f_before "$emr_lib" 'SC_MRF_SOURCE="hadoop-log"' 'SC_MRF_SOURCE="mapred-job-list"'
  f_before "$emr_lib" 'SC_MRF_SOURCE="mapred-job-list"' 'SC_MRF_SOURCE="derived-from-application-ids"'
  f_count_is "$emr_lib" 'if [[ "$SC_MRF_N" -lt "$expected" ]]; then' 2
  f_count_is "$emr_lib" 'if [[ "$n" -gt "$SC_MRF_N" ]]; then' 2
  f_has "$emr_lib" "sed -n 's/^application_\\([0-9][0-9]*_[0-9][0-9]*\\)\$/job_\\1/p'"
  case_end

  static_begin "14.emr.lib-emr.sc_publish_progress.is-a-best-effort-scanned-per-file-copy"
  f_has "$PG_FN" 'dest="s3://$bucket/reports/runs/$run/progress/"'
  f_has "$PG_FN" '[[ -n "$rep" && -n "$bucket" && -n "$run" && -d "$rep" ]] || return 0'
  f_count_is "$PG_FN" 'aws s3 cp' 1
  f_has "$PG_FN" 'timeout "$remaining" aws s3 cp "$src" "$dest$name" --only-show-errors'
  f_before "$PG_FN" 'grep -qE "$SC_SECRET_RE" "$src"' 'timeout "$remaining" aws s3 cp'
  f_has "$PG_FN" 'deadline=$((SECONDS + SC_PROGRESS_BUDGET_SECONDS))'
  for k in '--sse-c' '--delete' 's3 sync' 's3 rm' 's3 mv' '--recursive' 'delete-object'; do
    f_has_not "$PG_FN" "$k"
  done
  f_has_not_re "$PG_FN" '(^|[^a-z_])(die|exit)([^a-z_]|$)'
  f_count_is "$emr_lib" '[[ -n "$rep" && -n "$bucket" && -n "$run" && -d "$rep" ]] || return 0' 2
  case_end

  static_begin "14.emr.lib-emr.publish.count-leaves-out-the-progress-and-failed-keys-of-the-reports-prefix"
  f_has "$emr_lib" 'remote_n="$(aws s3 ls "$uri" --recursive | awk'
  f_has "$emr_lib" '$0 ~ " reports/runs/[^/]+/(progress|failed)/" { next } { n++ } END { print n + 0 }'
  f_has "$emr_lib" "aws s3 cp \"\$dir\" \"\$uri\" --recursive --exclude '_work/*' --only-show-errors"
  f_has "$emr_lib" '[[ "$local_n" -eq "$remote_n" ]] || die "object count differs for $uri: local $local_n, listed $remote_n"'
  case_end
}
static_wo50_cases

### Dynamic cases: the helpers that feed the verdict.

mr_new() {
  MR_FIX="$EMR_FX/mr-$1"
  rm -rf "$MR_FIX"
  mkdir -p "$MR_FIX"
  : > "$MR_FIX/hadoop.log"
}

# mr_log_yarn N REPEAT: N job_<ts>_<seq> ids in hadoop.log, each REPEAT times.
mr_log_yarn() {
  local i=1 r
  while [ "$i" -le "$1" ]; do
    r=0
    while [ "$r" -lt "$2" ]; do
      printf 'INFO mapreduce.Job: Running job: job_%s_%04d\n' "$MR_TS" "$i" >> "$MR_FIX/hadoop.log"
      r=$((r + 1))
    done
    i=$((i + 1))
  done
}

mr_log_local() {
  local i=1
  while [ "$i" -le "$1" ]; do
    printf 'INFO mapreduce.Job: Running job: job_local1517040254_%04d\n' "$i" >> "$MR_FIX/hadoop.log"
    i=$((i + 1))
  done
}

# mr_yarn_list N: the output of the ResourceManager list: a header and N application lines.
mr_yarn_list() {
  local i=1
  printf 'Total number of applications (application-types: [MAPREDUCE], states: [NEW, SUBMITTED, RUNNING, FINISHED, FAILED, KILLED] and tags: []):%s\n' "$1" > "$MR_FIX/yarn.txt"
  while [ "$i" -le "$1" ]; do
    printf 'application_%s_%04d\tPipelineTool-step\thadoop\tMAPREDUCE\tdefault\tFINISHED\tSUCCEEDED\t100%%\tN/A\n' "$MR_TS" "$i" >> "$MR_FIX/yarn.txt"
    i=$((i + 1))
  done
}

# mr_mapred FILE N TS: a mapred job -list style file (mapred-all.txt or mapred-list.txt).
mr_mapred() {
  local i=1
  printf 'Total jobs:%s\n' "$2" > "$MR_FIX/$1"
  while [ "$i" -le "$2" ]; do
    printf 'job_%s_%04d\tSUCCEEDED\t1794701635999\thadoop\tDEFAULT\n' "$3" "$i" >> "$MR_FIX/$1"
    i=$((i + 1))
  done
}

# mr_ids TS N: "job_<ts>_0001 job_<ts>_0002 ..." as the record prints them.
mr_ids() {
  local i=1 out=""
  while [ "$i" -le "$2" ]; do
    out="${out}${out:+ }$(printf 'job_%s_%04d' "$1" "$i")"
    i=$((i + 1))
  done
  printf '%s' "$out"
}

mr_run() {
  run bash "$CODE_DIR/mr-chain.sh" "$EMR_DIR/lib-emr.sh" "$MR_BLOCK" "$MR_FIX" "$1"
}

mr_ok() {
  a_rc 0
  a_trip_empty
  [ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
}

# mr_expect MR PROOF ATTN SRC N
mr_expect() {
  a_out_re "^MR=$1\$"
  a_out_re "^PROOF=$2\$"
  a_out_re "^ATTN=$3\$"
  a_out_re "^SRC=$4\$"
  a_out_re "^N=$5\$"
}

# yarn: the ResourceManager lists exactly the expected number of applications, nothing local.
mr_new yarn-rm-count-no-job-ids
mr_yarn_list 9
mr_run 9
case_begin "14.emr.verdict.yarn-by-resourcemanager-application-count-with-no-job-id-in-the-log"
mr_ok
mr_expect yarn resourcemanager-application-ids no derived-from-application-ids 9
a_out "IDS=$(mr_ids "$MR_TS" 9)"
a_out_re '^APPS_N=9$'
a_out_re '^YARN=0$'
a_out_re '^LOCAL=0$'
a_out "DETAIL=9 MAPREDUCE applications on the ResourceManager for 9 expected stages, 0 job_local id in the log, 0 job id(s) in the log"
grep -qF "application_${MR_TS}_0009" "$MR_FIX/reports/mapreduce-yarn-list.log" || note_fail "the full list output was not kept in mapreduce-yarn-list.log"
case_end

mr_new yarn-job-ids-in-log
mr_log_yarn 9 2
mr_yarn_list 9
mr_run 9
case_begin "14.emr.verdict.yarn-proof-is-job-ids-in-log-when-the-log-holds-all-nine-ids"
mr_ok
mr_expect yarn job-ids-in-log no hadoop-log 9
a_out "IDS=$(mr_ids "$MR_TS" 9)"
a_out_re '^YARN=9$'
a_out "DETAIL=9 MAPREDUCE applications on the ResourceManager for 9 expected stages, 0 job_local id in the log, 9 job id(s) in the log"
case_end

mr_new log-wins-over-the-other-sources
mr_log_yarn 9 1
mr_mapred mapred-all.txt 9 "$MR_TS_OTHER"
mr_yarn_list 9
mr_run 9
case_begin "14.emr.job-ids.the-hadoop-log-wins-when-it-holds-all-expected-ids"
mr_ok
mr_expect yarn job-ids-in-log no hadoop-log 9
a_out "IDS=$(mr_ids "$MR_TS" 9)"
a_not_out "$MR_TS_OTHER"
case_end

mr_new mapred-list-all
mr_mapred mapred-all.txt 9 "$MR_TS_OTHER"
mr_yarn_list 9
mr_run 9
case_begin "14.emr.job-ids.source-mapred-job-list-all-when-the-log-holds-none"
mr_ok
mr_expect yarn resourcemanager-application-ids no mapred-job-list 9
a_out "IDS=$(mr_ids "$MR_TS_OTHER" 9)"
case_end

mr_new mapred-list-fallback
mr_mapred mapred-list.txt 9 "$MR_TS_OTHER"
mr_yarn_list 9
mr_run 9
case_begin "14.emr.job-ids.source-mapred-job-list-without-all-when-the-first-call-gives-no-id"
mr_ok
mr_expect yarn resourcemanager-application-ids no mapred-job-list 9
a_out "IDS=$(mr_ids "$MR_TS_OTHER" 9)"
grep -qF 'mapred job -list all gave no job id; trying mapred job -list' "$MR_FIX/reports/mapreduce-job-list.log" || note_fail "mapreduce-job-list.log does not record the fallback to mapred job -list"
case_end

mr_new partial-log-derives
mr_log_yarn 3 1
mr_yarn_list 9
mr_run 9
case_begin "14.emr.job-ids.source-derived-from-application-ids-when-the-log-holds-only-some"
mr_ok
mr_expect yarn resourcemanager-application-ids no derived-from-application-ids 9
a_out "IDS=$(mr_ids "$MR_TS" 9)"
a_out_re '^YARN=3$'
a_out "DETAIL=9 MAPREDUCE applications on the ResourceManager for 9 expected stages, 0 job_local id in the log, 3 job id(s) in the log"
case_end

mr_new tie-keeps-the-earlier-source
mr_log_yarn 3 1
mr_mapred mapred-all.txt 3 "$MR_TS_OTHER"
mr_run 9
case_begin "14.emr.job-ids.a-tie-keeps-the-earlier-source-and-nine-ids-in-the-log-alone-do-not-prove-yarn"
mr_ok
mr_expect unknown none yes hadoop-log 3
a_out "IDS=$(mr_ids "$MR_TS" 3)"
a_not_out "$MR_TS_OTHER"
case_end

# local, mixed and unknown.
mr_new local-only
mr_log_local 9
mr_run 9
case_begin "14.emr.verdict.local-when-only-job-local-ids-appear"
mr_ok
mr_expect local none yes hadoop-log 9
a_out_re '^LOCAL=9$'
a_out "DETAIL=9 local job id(s) job_local... found, no YARN job or application seen, 9 expected"
case_end

mr_new mixed-local-and-applications
mr_log_local 1
mr_yarn_list 9
mr_run 9
case_begin "14.emr.verdict.mixed-when-a-local-id-meets-nine-resourcemanager-applications-never-yarn"
mr_ok
mr_expect mixed none yes derived-from-application-ids 9
a_out "DETAIL=1 local job id(s) job_local... and YARN evidence together (0 YARN job id(s) in the log, 9 MAPREDUCE application(s) on the ResourceManager), 9 expected"
case_end

mr_new mixed-local-and-yarn-log-ids
mr_log_local 1
mr_log_yarn 2 1
mr_run 9
case_begin "14.emr.verdict.mixed-when-a-local-id-meets-yarn-job-ids-in-the-log-without-a-list"
mr_ok
mr_expect mixed none yes hadoop-log 3
a_out "DETAIL=1 local job id(s) job_local... and YARN evidence together (2 YARN job id(s) in the log, 0 MAPREDUCE application(s) on the ResourceManager), 9 expected"
case_end

mr_new unknown-empty-list
printf 'Total number of applications (application-types: [MAPREDUCE], states: [NEW, SUBMITTED, RUNNING, FINISHED, FAILED, KILLED] and tags: []):0\n' > "$MR_FIX/yarn.txt"
mr_run 9
case_begin "14.emr.verdict.unknown-for-an-empty-list-with-the-counts-in-the-detail"
mr_ok
mr_expect unknown none yes none 0
a_out_re '^APPS_N=0$'
a_out "DETAIL=the ResourceManager lists 0 MAPREDUCE application(s) against 9 expected (empty or a different count), 0 job id(s) and 0 local id(s) in the log: not proven"
case_end

mr_new unknown-eight
mr_log_yarn 2 1
mr_yarn_list 8
mr_run 9
case_begin "14.emr.verdict.unknown-for-eight-applications-against-nine-expected"
mr_ok
mr_expect unknown none yes derived-from-application-ids 8
a_out_re '^APPS_N=8$'
a_out "DETAIL=the ResourceManager lists 8 MAPREDUCE application(s) against 9 expected (empty or a different count), 2 job id(s) and 0 local id(s) in the log: not proven"
case_end

mr_new unknown-ten
mr_yarn_list 10
mr_run 9
case_begin "14.emr.verdict.unknown-for-ten-applications-against-nine-expected"
mr_ok
mr_expect unknown none yes derived-from-application-ids 10
a_out "DETAIL=the ResourceManager lists 10 MAPREDUCE application(s) against 9 expected (empty or a different count), 0 job id(s) and 0 local id(s) in the log: not proven"
case_end

mr_new unknown-log-ids-without-a-list
mr_log_yarn 9 1
mr_run 9
case_begin "14.emr.verdict.unknown-when-nine-yarn-job-ids-are-in-the-log-but-the-list-is-empty"
mr_ok
mr_expect unknown none yes hadoop-log 9
a_out "DETAIL=the ResourceManager lists 0 MAPREDUCE application(s) against 9 expected (empty or a different count), 9 job id(s) and 0 local id(s) in the log: not proven"
case_end

mr_new expected-three-yarn
mr_yarn_list 3
mr_run 3
case_begin "14.emr.verdict.the-expected-count-is-the-argument-not-a-literal-nine-yarn-for-three-of-three"
mr_ok
mr_expect yarn resourcemanager-application-ids no derived-from-application-ids 3
a_out "DETAIL=3 MAPREDUCE applications on the ResourceManager for 3 expected stages, 0 job_local id in the log, 0 job id(s) in the log"
case_end

mr_new expected-three-unknown
mr_yarn_list 9
mr_run 3
case_begin "14.emr.verdict.the-expected-count-is-the-argument-not-a-literal-nine-unknown-for-nine-of-three"
mr_ok
mr_expect unknown none yes derived-from-application-ids 9
a_out "DETAIL=the ResourceManager lists 9 MAPREDUCE application(s) against 3 expected (empty or a different count), 0 job id(s) and 0 local id(s) in the log: not proven"
case_end

# The small pure helpers, one child run, four cases.
mkdir -p "$EMR_FX/fn"
printf 'job_1794701635353_10\njob_1794701635353_2\njob_1794701635352_100\njob_1794701635353_2\njob_12_3\nnot a job id\n' > "$EMR_FX/fn/jobs.txt"
{
  printf 'INFO Running job: job_local1517040254_0001\n'
  printf 'INFO Running job: job_local1517040254_0001\n'
  printf 'INFO Running job: job_local1517040254_0001\n'
  printf 'INFO Running job: job_1794701635353_0001\n'
  printf 'INFO Job job_1794701635353_0001 completed\n'
  printf 'INFO Running job: job_1794701635353_0002\n'
  printf 'INFO short id job_123_1 is not a job id of a cluster\n'
} > "$EMR_FX/fn/classify.log"
run bash "$CODE_DIR/fn-child.sh" "$EMR_DIR/lib-emr.sh" "$EMR_FX/fn"
case_begin "14.emr.job-ids.derive-job-ids-replaces-the-prefix-and-drops-anything-else"
mr_ok
a_out "DERIVE1=job_1794701635353_0001 job_1794701635353_0002"
a_out_re '^DERIVE2=$'
a_out "DERIVE3=job_17_9"
case_end
case_begin "14.emr.job-ids.words-in-counts-space-separated-words"
a_out_re '^WORDS0=0$'
a_out_re '^WORDS3=3$'
case_end
case_begin "14.emr.job-ids.job-ids-in-file-sorts-numerically-drops-duplicates-and-short-ids"
a_out "SORTED=job_1794701635352_100 job_1794701635353_2 job_1794701635353_10"
case_end
case_begin "14.emr.job-ids.classify-job-ids-counts-distinct-ids-by-kind-in-order-of-first-appearance"
a_out_re '^CLS_N=3$'
a_out_re '^CLS_YARN=2$'
a_out_re '^CLS_LOCAL=1$'
a_out "CLS_IDS=job_local1517040254_0001 job_1794701635353_0001 job_1794701635353_0002"
case_end

### Dynamic cases: stage seconds and the exit trap.

run bash "$CODE_DIR/stage-child.sh" "$EMR_DIR/lib-emr.sh" ok
case_begin "14.emr.stage-seconds.stage-ok-prints-the-unchanged-marker-then-the-seconds-and-no-checkpoint-unregistered"
a_rc 0
a_trip_empty
a_order 'STAGE[1] alpha-beta: start' 'STAGE[1] alpha-beta: ok' 'STAGE_SECONDS_ALPHA_BETA='
a_out_re '^STAGE_SECONDS_ALPHA_BETA=4[1-3]$'
a_out_re '^STAGE_SECONDS_TOTAL=104[1-4]$'
a_not_out 'progress checkpoint'
a_not_out 'PROGRESS_'
case_end

run bash "$CODE_DIR/stage-child.sh" "$EMR_DIR/lib-emr.sh" fail
case_begin "14.emr.stage-seconds.a-failed-stage-records-its-seconds-and-the-total-at-failure-and-keeps-the-exit-code"
a_rc 1
a_trip_empty
a_out 'STAGE[1] boom-stage: FAIL (exit 1)'
a_out_re '^STAGE_SECONDS_BOOM_STAGE=[6-9] \(stage failed\)$'
a_out_re '^STAGE_SECONDS_TOTAL_AT_FAILURE=50[7-9]$'
a_not_out 'FAILED_STAGE='
case_end

### Dynamic cases: sc_publish_progress and stage_ok checkpoints.

pg_new() {
  PG_FIX="$EMR_FX/pg-$1"
  rm -rf "$PG_FIX"
  mkdir -p "$PG_FIX/reports/sub"
  printf 'hadoop line\n' > "$PG_FIX/reports/hadoop.log"
  printf 'er line\n' > "$PG_FIX/reports/er.log"
  printf 'inner\n' > "$PG_FIX/reports/sub/inner.log"
  printf 'note %s\n' "$FAKE_AKIA" > "$PG_FIX/reports/secret.log"
  awk 'BEGIN { for (i = 1; i <= 40; i++) printf "0123456789abcdef line %02d\n", i }' > "$PG_FIX/reports/big.log"
  {
    awk 'BEGIN { for (i = 1; i <= 40; i++) printf "filler filler filler line %02d\n", i }'
    printf 'tail %s\n' "$FAKE_AKIA"
  } > "$PG_FIX/reports/bigsecret.log"
}

# pg_run MODE [NAME=VALUE ...]
pg_run() {
  local mode="$1"
  shift
  run env "$@" bash "$CODE_DIR/progress-child.sh" "$EMR_DIR/lib-emr.sh" "$PG_FIX" "$mode"
}

pg_new ok
pg_run ok CAP=400
case_begin "14.emr.progress.checkpoint-reports-what-it-uploaded-skipped-and-never-prints-key-material"
a_rc 0
a_trip_empty
a_out_re '^RC=0$'
a_out "PROGRESS_PUBLISHED=s3://data-bucket/reports/runs/run-v1-x/progress/ checkpoint=checkpoint-1"
a_out "NOTE: progress checkpoint checkpoint-1: 5 file(s) uploaded, 2 skipped, 0 failed"
a_out "PROGRESS_SKIPPED file=secret.log reason=key-material-pattern (content not printed)"
a_out "PROGRESS_SKIPPED file=bigsecret.log.tail reason=key-material-pattern (content not printed)"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

case_begin "14.emr.progress.uploads-only-the-small-files-the-tail-of-a-large-one-and-never-a-secret-or-a-directory"
PG_UP="$PG_FIX/uploads"
PG_NAMES="$(find "$PG_UP" -type f | sed 's|.*/||' | sort | tr '\n' ' ')"
[ "$PG_NAMES" = "big.log.tail er.log hadoop.log progress.env run-record.env " ] || note_fail "uploaded names: $PG_NAMES"
grep -qx 'RUN_ID=abc' "$PG_UP/run-record.env" || note_fail "run-record.env lacks the key=value lines so far"
grep -qx 'CHECKPOINT=checkpoint-1' "$PG_UP/progress.env" || note_fail "progress.env lacks CHECKPOINT=checkpoint-1"
grep -qx 'STAGE=3 hadoop open=0' "$PG_UP/progress.env" || note_fail "progress.env lacks the stage line"
grep -qE '^UPDATED_UTC=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$PG_UP/progress.env" || note_fail "progress.env lacks a UTC time"
grep -qE '^ELAPSED_SECONDS=[0-9]+$' "$PG_UP/progress.env" || note_fail "progress.env lacks ELAPSED_SECONDS"
grep -qF 'NOTE=latest checkpoint only' "$PG_UP/progress.env" || note_fail "progress.env lacks the latest-only note"
if ! tail -c 400 "$PG_FIX/reports/big.log" | cmp -s - "$PG_UP/big.log.tail"; then note_fail "big.log.tail is not the last 400 bytes of big.log"; fi
if grep -rqF -- "$FAKE_AKIA" "$PG_UP"; then note_fail "key material was uploaded"; fi
case_end

case_begin "14.emr.progress.every-call-is-one-timed-aws-s3-cp-of-one-file-to-the-progress-prefix"
PG_CALLS="$PG_FIX/calls.log"
[ "$(grep -c '^AWS ' "$PG_CALLS")" = "5" ] || note_fail "expected 5 aws calls, found $(grep -c '^AWS ' "$PG_CALLS")"
[ "$(grep -c '^TIMEOUT ' "$PG_CALLS")" = "5" ] || note_fail "expected 5 timeout wrappers, found $(grep -c '^TIMEOUT ' "$PG_CALLS")"
PG_BAD="$(grep '^AWS ' "$PG_CALLS" | grep -vE '^AWS s3 cp [^ ]+ s3://data-bucket/reports/runs/run-v1-x/progress/[A-Za-z0-9._-]+ --only-show-errors$' | head -n 1)"
[ -z "$PG_BAD" ] || note_fail "an aws call has another shape: $PG_BAD"
PG_TO="$(awk '$1 == "TIMEOUT" && ($2 < 1 || $2 > 60) { print }' "$PG_CALLS" | head -n 1)"
[ -z "$PG_TO" ] || note_fail "a timeout is outside 1 to 60 seconds: $PG_TO"
case_end

pg_new fail
pg_run fail CAP=400
case_begin "14.emr.progress.aws-failures-are-counted-and-swallowed-the-checkpoint-still-returns-zero"
a_rc 0
a_trip_empty
a_out_re '^RC=0$'
a_out "NOTE: progress checkpoint checkpoint-1: 0 file(s) uploaded, 2 skipped, 5 failed"
a_not_out "PROGRESS_PUBLISHED="
[ "$(grep -c '^AWS-FAILED ' "$PG_FIX/calls.log")" = "5" ] || note_fail "expected 5 failed aws calls"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

pg_new budget
pg_run ok CAP=400 BUDGET=0
case_begin "14.emr.progress.an-exhausted-time-budget-calls-no-aws-and-still-returns-zero"
a_rc 0
a_trip_empty
a_out_re '^RC=0$'
a_out "NOTE: progress checkpoint checkpoint-1: 0 file(s) uploaded, 2 skipped, 5 failed"
[ "$(grep -c '^AWS' "$PG_FIX/calls.log")" = "0" ] || note_fail "aws was called with no time budget left"
case_end

pg_new unregistered
pg_run unregistered
case_begin "14.emr.progress.does-nothing-before-register-failure-logs-ran"
a_rc 0
a_trip_empty
[ "$(cat "$OUT")" = "RC=0" ] || note_fail "stdout is not exactly RC=0"
[ ! -s "$PG_FIX/calls.log" ] || note_fail "a call was made before register_failure_logs"
case_end

pg_new nodir
pg_run nodir
case_begin "14.emr.progress.does-nothing-when-the-reports-directory-is-missing"
a_rc 0
a_trip_empty
[ "$(cat "$OUT")" = "RC=0" ] || note_fail "stdout is not exactly RC=0"
[ ! -s "$PG_FIX/calls.log" ] || note_fail "a call was made with no reports directory"
case_end

pg_new stages
pg_run stages
case_begin "14.emr.progress.stage-ok-checkpoints-after-every-stage-but-the-publish-stage"
a_rc 0
a_trip_empty
a_order 'STAGE[1] one-a: start' 'STAGE[1] one-a: ok' 'STAGE_SECONDS_ONE_A=' 'PROGRESS_PUBLISHED=' 'STAGE[2] publish: start' 'STAGE[2] publish: ok' 'STAGE_SECONDS_PUBLISH='
a_out_re '^STAGE_SECONDS_ONE_A=[0-9]+$'
a_out 'checkpoint=after-stage-1-one-a'
a_not_out 'after-stage-2-publish'
[ "$(grep -c '^NOTE: progress checkpoint' "$OUT")" = "1" ] || note_fail "expected exactly one checkpoint note"
grep -q '^STAGE_SECONDS_ONE_A=' "$PG_FIX/uploads/run-record.env" || note_fail "the checkpoint's run-record.env lacks the stage seconds just recorded"
case_end

### Dynamic cases: the publish count.

pb_new() {
  PB_FIX="$EMR_FX/pb-$1"
  rm -rf "$PB_FIX"
  mkdir -p "$PB_FIX/out"
  : > "$PB_FIX/ls.txt"
  printf 'a\n' > "$PB_FIX/out/a.txt"
  printf 'b\n' > "$PB_FIX/out/b.txt"
}
pb_ls() {
  printf '2026-11-15 00:00:00 %9d %s\n' 10 "$1" >> "$PB_FIX/ls.txt"
}
pb_run() {
  run bash "$CODE_DIR/publish-child.sh" "$EMR_DIR/lib-emr.sh" "$PB_FIX" "$PB_FIX/out" "$1"
}
PB_REP="s3://harness-bucket/reports/runs/r1/"

pb_new ok
pb_ls reports/runs/r1/a.txt
pb_ls reports/runs/r1/b.txt
pb_ls reports/runs/r1/progress/run-record.env
pb_ls reports/runs/r1/progress/progress.env
pb_ls reports/runs/r1/progress/hadoop.log
pb_ls reports/runs/r1/failed/20261115T000000Z/hadoop.log
pb_ls reports/runs/r1/failed/20261115T000000Z/run-record.env
pb_run "$PB_REP"
case_begin "14.emr.publish-count.progress-and-failed-keys-of-the-reports-prefix-are-not-counted"
mr_ok
a_out "PUBLISHED uri=${PB_REP} files_local=2 objects_listed=2"
a_out_re '^RC=0$'
case_end
case_begin "14.emr.publish-count.the-copy-is-recursive-skips-work-and-uses-no-sync-or-delete"
grep -qF -- "AWS s3 cp $PB_FIX/out $PB_REP --recursive --exclude _work/* --only-show-errors" "$PB_FIX/calls.log" || note_fail "the aws copy call has another shape: $(head -n 1 "$PB_FIX/calls.log")"
[ "$(grep -c '^AWS ' "$PB_FIX/calls.log")" = "1" ] || note_fail "publish made more than one aws call besides the listing"
case_end

pb_new extra
pb_ls reports/runs/r1/a.txt
pb_ls reports/runs/r1/b.txt
pb_ls reports/runs/r1/c.txt
pb_ls reports/runs/r1/progress/progress.env
pb_run "$PB_REP"
case_begin "14.emr.publish-count.an-extra-object-outside-progress-and-failed-is-refused"
a_rc 1
a_err_evidence
a_err "object count differs for ${PB_REP}: local 2, listed 3"
a_not_out "RC=0"
a_trip_empty
case_end

pb_new progressive
pb_ls reports/runs/r1/a.txt
pb_ls reports/runs/r1/b.txt
pb_ls reports/runs/r1/progressive/x.log
pb_run "$PB_REP"
case_begin "14.emr.publish-count.a-prefix-that-only-starts-with-progress-is-counted"
a_rc 1
a_err "object count differs for ${PB_REP}: local 2, listed 3"
a_trip_empty
case_end

pb_new missing
pb_ls reports/runs/r1/a.txt
pb_ls reports/runs/r1/progress/run-record.env
pb_ls reports/runs/r1/progress/progress.env
pb_ls reports/runs/r1/failed/20261115T000000Z/hadoop.log
pb_run "$PB_REP"
case_begin "14.emr.publish-count.progress-keys-do-not-hide-a-missing-object"
a_rc 1
a_err "object count differs for ${PB_REP}: local 2, listed 1"
a_trip_empty
case_end

pb_new other-prefix
mkdir -p "$PB_FIX/out/failed"
printf 'c\n' > "$PB_FIX/out/failed/c.txt"
pb_ls curated/runs/r1/hadoop/a.txt
pb_ls curated/runs/r1/hadoop/b.txt
pb_ls curated/runs/r1/hadoop/failed/c.txt
pb_run "s3://harness-bucket/curated/runs/r1/hadoop/"
case_begin "14.emr.publish-count.outside-the-reports-prefix-a-failed-directory-is-counted"
mr_ok
a_out "PUBLISHED uri=s3://harness-bucket/curated/runs/r1/hadoop/ files_local=3 objects_listed=3"
case_end

### Dynamic cases: the Spark executor settings.

sp_new() {
  SP_HOME="$EMR_FX/sp-$1"
  rm -rf "$SP_HOME"
  mkdir -p "$SP_HOME"
}
sp_run() {
  run bash "$CODE_DIR/sparkconf-child.sh" "$EMR_DIR/lib-emr.sh" "$SP_HOME"
}
# conf_val FILE KEY: the value of the one KEY=VALUE line (comment lines never start with KEY).
conf_val() {
  sed -n "s/^$2=\\(.*\\)\$/\\1/p" "$1" | head -n 1
}

sp_new defaults
sp_run
cp "$OUT" "$EMR_FX/spark-defaults-from-code.conf"
case_begin "14.emr.spark-conf.defaults-are-two-executors-of-two-cores-and-3g-with-dynamic-allocation-off"
mr_ok
a_out_re '^spark\.executor\.instances 2$'
a_out_re '^spark\.executor\.cores 2$'
a_out_re '^spark\.executor\.memory 3g$'
a_out_re '^spark\.driver\.memory 2g$'
a_out_re '^spark\.yarn\.am\.memory 1g$'
a_out_re '^spark\.dynamicAllocation\.enabled false$'
a_out_re '^spark\.yarn\.populateHadoopClasspath false$'
a_out_re '^spark\.ui\.enabled false$'
a_out_re '^spark\.hadoop\.fs\.permissions\.umask-mode 000$'
[ "$(grep -c '^spark\.executor\.instances ' "$OUT")" = "1" ] || note_fail "spark.executor.instances is not set exactly once"
a_not_out "spark.dynamicAllocation.enabled true"
case_end

sp_new real-file
cp "$EMR_DIR/spark-yarn.conf" "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.the-real-spark-yarn-conf-and-the-defaults-in-the-code-agree"
mr_ok
cmp -s "$OUT" "$EMR_FX/spark-defaults-from-code.conf" || note_fail "spark-defaults.conf from scripts/emr/spark-yarn.conf differs from the one the code defaults give"
a_out_re '^spark\.executor\.instances 2$'
a_out_re '^spark\.executor\.memory 3g$'
case_end

# The container arithmetic of work order 49, computed from the file: a heap in g or m plus
# max(384 MB, 10 percent) as overhead, per executor, plus the application master.
mb_of() {
  case "$1" in
    *g) printf '%s' $((${1%g} * 1024)) ;;
    *m) printf '%s' "${1%m}" ;;
    *) printf '%s' 0 ;;
  esac
}
container_mb() {
  local heap ov
  heap="$(mb_of "$1")"
  ov=$((heap / 10))
  if [ "$ov" -lt 384 ]; then ov=384; fi
  printf '%s' $((heap + ov))
}
static_begin "14.emr.spark-conf.container-arithmetic-from-the-file-fits-the-node-and-the-comment-states-it"
SP_CONF="$EMR_DIR/spark-yarn.conf"
SP_EI="$(conf_val "$SP_CONF" EXECUTOR_INSTANCES)"
SP_EC="$(conf_val "$SP_CONF" EXECUTOR_CORES)"
SP_EM="$(conf_val "$SP_CONF" EXECUTOR_MEMORY)"
SP_AM="$(conf_val "$SP_CONF" AM_MEMORY)"
[ "$SP_EI" = "2" ] || note_fail "EXECUTOR_INSTANCES is '$SP_EI', expected 2"
[ "$SP_EC" = "2" ] || note_fail "EXECUTOR_CORES is '$SP_EC', expected 2"
[ "$SP_EM" = "3g" ] || note_fail "EXECUTOR_MEMORY is '$SP_EM', expected 3g"
if [ -n "$SP_EI" ] && [ -n "$SP_EC" ] && [ -n "$SP_EM" ] && [ -n "$SP_AM" ]; then
  SP_TOTAL=$((SP_EI * $(container_mb "$SP_EM") + $(container_mb "$SP_AM")))
  [ "$SP_TOTAL" -le 12288 ] || note_fail "the containers ask $SP_TOTAL MB, above the believed node manager limit of 12288 MB"
  [ $((SP_EI * SP_EC)) -le 4 ] || note_fail "$SP_EI executors of $SP_EC cores ask more than the 4 vCPUs of an m5.xlarge"
  f_has "$SP_CONF" "total asked of the node manager $SP_TOTAL MB"
else
  note_fail "a key is missing from spark-yarn.conf"
fi
case_end

sp_new override
printf 'EXECUTOR_INSTANCES=3\n' > "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.a-value-in-the-conf-file-overrides-the-default"
mr_ok
a_out_re '^spark\.executor\.instances 3$'
a_out_re '^spark\.executor\.cores 2$'
case_end

sp_new bad-instances
printf 'EXECUTOR_INSTANCES=two\n' > "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.a-non-numeric-instance-count-is-refused"
a_rc 1
a_err "spark-yarn.conf: EXECUTOR_INSTANCES has an invalid value"
[ ! -s "$OUT" ] || note_fail "stdout is not empty on a refusal"
a_trip_empty
case_end

sp_new bad-memory
printf 'EXECUTOR_MEMORY=3\n' > "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.executor-memory-without-a-unit-is-refused"
a_rc 1
a_err "spark-yarn.conf: EXECUTOR_MEMORY has an invalid value"
a_trip_empty
case_end

sp_new bad-cores
printf 'EXECUTOR_CORES=2.5\n' > "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.a-decimal-core-count-is-refused"
a_rc 1
a_err "spark-yarn.conf: EXECUTOR_CORES has an invalid value"
a_trip_empty
case_end

sp_new comments
printf '# EXECUTOR_INSTANCES=9\n\n   # EXECUTOR_CORES=9\n' > "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.comment-and-blank-lines-never-set-a-value"
mr_ok
a_out_re '^spark\.executor\.instances 2$'
a_out_re '^spark\.executor\.cores 2$'
case_end

### Terraform: Rule A and the S overlay (work order 49), read as text.

TF_DIR="$REAL_ROOT/infra/terraform"
WF_VARS="$TF_DIR/modules/workflow/variables.tf"
CRS_VARS="$TF_DIR/environments/course/variables.tf"
TF_S="$TF_DIR/environments/course/experiment-size-s.tfvars"
TF_M="$TF_DIR/environments/course/experiment-size-m.tfvars"
TF_L="$TF_DIR/environments/course/experiment-size-l.tfvars"

# tf_num FILE NAME: the last numeric assignment "NAME = N" outside comment lines.
tf_num() {
  grep -v '^[[:space:]]*#' "$1" | sed -n "s/^$2[[:space:]]*=[[:space:]]*\\([0-9][0-9]*\\)[[:space:]]*\$/\\1/p" | tail -n 1
}
tf_count() {
  grep -cE "^$2[[:space:]]*=" "$1"
}
# tf_def_in FILE VARIABLE: the default of a variable block, as the text after the "=".
tf_def_in() {
  awk -v v="$2" '
    $0 ~ "^variable \"" v "\" " { f = 1; next }
    f && /^}/ { exit }
    f && /^[[:space:]]*default[[:space:]]*=/ { sub(/^[^=]*=[[:space:]]*/, ""); print; exit }
  ' "$1"
}
tf_is_num() {
  case "$1" in
    "" | *[!0-9]*) return 1 ;;
  esac
  return 0
}
# tf_rule_a MAX CREATE STEP: succeeds when MAX >= CREATE + STEP + 600 (all whole numbers).
tf_rule_a() {
  tf_is_num "$1" || return 1
  tf_is_num "$2" || return 1
  tf_is_num "$3" || return 1
  [ "$1" -ge $((${2} + ${3} + 600)) ]
}
# tf_overlay FILE: sets TF_MAX, TF_CREATE, TF_STEP and TF_LOAD from the overlay.
tf_overlay() {
  TF_MAX="$(tf_num "$1" pipeline_max_run_seconds)"
  TF_CREATE="$(tf_num "$1" pipeline_create_cluster_timeout_seconds)"
  TF_STEP="$(tf_num "$1" pipeline_emr_step_timeout_seconds)"
  TF_LOAD="$(tf_num "$1" pipeline_loader_timeout_seconds)"
}

static_begin "14.terraform.rule-a.the-workflow-module-counts-the-one-step-once-plus-600"
f_has "$WF_VARS" 'condition     = var.max_run_seconds >= var.create_cluster_timeout_seconds + var.emr_step_timeout_seconds + 600'
f_has "$WF_VARS" 'Rule A: max_run_seconds must be at least create_cluster_timeout_seconds + emr_step_timeout_seconds + 600'
f_has_not_re "$WF_VARS" '2[[:space:]]*\*[[:space:]]*var[.]emr_step_timeout_seconds|var[.]emr_step_timeout_seconds[[:space:]]*\*[[:space:]]*2'
f_has_not "$WF_VARS" 'var.emr_step_timeout_seconds + 300'
f_has "$WF_VARS" 'condition     = var.max_run_seconds >= 1800 && var.max_run_seconds <= 10800'
case_end

static_begin "14.terraform.rule-a.module-defaults-pass-it-and-agree-with-the-course-variables"
TF_WD_MAX="$(tf_def_in "$WF_VARS" max_run_seconds)"
TF_WD_CREATE="$(tf_def_in "$WF_VARS" create_cluster_timeout_seconds)"
TF_WD_STEP="$(tf_def_in "$WF_VARS" emr_step_timeout_seconds)"
TF_CD_MAX="$(tf_def_in "$CRS_VARS" pipeline_max_run_seconds)"
TF_CD_CREATE="$(tf_def_in "$CRS_VARS" pipeline_create_cluster_timeout_seconds)"
TF_CD_STEP="$(tf_def_in "$CRS_VARS" pipeline_emr_step_timeout_seconds)"
[ "$TF_WD_MAX" = "$TF_CD_MAX" ] || note_fail "max run default differs: module $TF_WD_MAX, course $TF_CD_MAX"
[ "$TF_WD_CREATE" = "$TF_CD_CREATE" ] || note_fail "create default differs: module $TF_WD_CREATE, course $TF_CD_CREATE"
[ "$TF_WD_STEP" = "$TF_CD_STEP" ] || note_fail "step default differs: module $TF_WD_STEP, course $TF_CD_STEP"
tf_rule_a "$TF_WD_MAX" "$TF_WD_CREATE" "$TF_WD_STEP" || note_fail "the module defaults $TF_WD_MAX, $TF_WD_CREATE, $TF_WD_STEP fail Rule A"
case_end

# Work order 87: the S loader cap is 5400 since infra work order 86 (it was 1800); the case id and
# the expected value follow, the other three values are unchanged.
static_begin "14.terraform.s-overlay.timeouts-are-7200-1440-4800-5400"
tf_overlay "$TF_S"
[ "$TF_MAX" = "7200" ] || note_fail "pipeline_max_run_seconds is '$TF_MAX', expected 7200"
[ "$TF_CREATE" = "1440" ] || note_fail "pipeline_create_cluster_timeout_seconds is '$TF_CREATE', expected 1440"
[ "$TF_STEP" = "4800" ] || note_fail "pipeline_emr_step_timeout_seconds is '$TF_STEP', expected 4800"
[ "$TF_LOAD" = "5400" ] || note_fail "pipeline_loader_timeout_seconds is '$TF_LOAD', expected 5400 (work order 86; it was 1800)"
case_end

static_begin "14.terraform.s-overlay.passes-rule-a-from-its-own-values"
tf_overlay "$TF_S"
tf_rule_a "$TF_MAX" "$TF_CREATE" "$TF_STEP" || note_fail "S fails Rule A: $TF_MAX < $TF_CREATE + $TF_STEP + 600"
case_end

static_begin "14.terraform.m-overlay.passes-rule-a-from-its-own-values"
tf_overlay "$TF_M"
tf_rule_a "$TF_MAX" "$TF_CREATE" "$TF_STEP" || note_fail "M fails Rule A: '$TF_MAX' < '$TF_CREATE' + '$TF_STEP' + 600"
case_end

static_begin "14.terraform.l-overlay.passes-rule-a-from-its-own-values"
tf_overlay "$TF_L"
tf_rule_a "$TF_MAX" "$TF_CREATE" "$TF_STEP" || note_fail "L fails Rule A: '$TF_MAX' < '$TF_CREATE' + '$TF_STEP' + 600"
case_end

static_begin "14.terraform.overlays.set-each-pipeline-variable-exactly-once"
for tf_o in "$TF_S" "$TF_M" "$TF_L"; do
  for tf_v in pipeline_max_run_seconds pipeline_create_cluster_timeout_seconds pipeline_emr_step_timeout_seconds pipeline_loader_timeout_seconds; do
    [ "$(tf_count "$tf_o" "$tf_v")" = "1" ] || note_fail "${tf_o##*/} sets $tf_v $(tf_count "$tf_o" "$tf_v") times"
  done
done
case_end

static_begin "14.terraform.overlays.stay-inside-the-variable-ranges"
f_has "$CRS_VARS" 'condition     = var.pipeline_max_run_seconds >= 1800 && var.pipeline_max_run_seconds <= 10800'
f_has "$CRS_VARS" 'condition     = var.pipeline_create_cluster_timeout_seconds >= 300 && var.pipeline_create_cluster_timeout_seconds <= 3600'
f_has "$CRS_VARS" 'condition     = var.pipeline_emr_step_timeout_seconds >= 300 && var.pipeline_emr_step_timeout_seconds <= 7200'
f_has "$CRS_VARS" 'condition     = var.pipeline_loader_timeout_seconds >= 300 && var.pipeline_loader_timeout_seconds <= 7200'
for tf_o in "$TF_S" "$TF_M" "$TF_L"; do
  tf_overlay "$tf_o"
  if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
    { [ "$TF_MAX" -ge 1800 ] && [ "$TF_MAX" -le 10800 ]; } || note_fail "${tf_o##*/}: max run $TF_MAX is outside 1800 to 10800"
    { [ "$TF_CREATE" -ge 300 ] && [ "$TF_CREATE" -le 3600 ]; } || note_fail "${tf_o##*/}: create $TF_CREATE is outside 300 to 3600"
    { [ "$TF_STEP" -ge 300 ] && [ "$TF_STEP" -le 7200 ]; } || note_fail "${tf_o##*/}: step $TF_STEP is outside 300 to 7200"
    { [ "$TF_LOAD" -ge 300 ] && [ "$TF_LOAD" -le 7200 ]; } || note_fail "${tf_o##*/}: loader $TF_LOAD is outside 300 to 7200"
  else
    note_fail "${tf_o##*/}: a pipeline value could not be read as a whole number"
  fi
done
case_end

static_begin "14.terraform.rule-a.the-evaluator-in-this-harness-is-exact-at-the-boundary"
tf_rule_a 5640 1440 3600 || note_fail "the exact boundary 5640 = 1440 + 3600 + 600 was refused"
if tf_rule_a 5639 1440 3600; then note_fail "one below the boundary was accepted"; fi
if tf_rule_a "" 1440 3600; then note_fail "an empty maximum was accepted"; fi
if tf_rule_a 7200 1440 "3600s"; then note_fail "a non-numeric step was accepted"; fi
case_end

static_begin "14.terraform.s-overlay.comment-arithmetic-and-the-module-worst-case-figure-match-the-values"
tf_overlay "$TF_S"
if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
  f_has "$TF_S" "$TF_CREATE + $TF_STEP + 600 = $((TF_CREATE + TF_STEP + 600)) <= $TF_MAX"
  f_has "$TF_S" "$TF_CREATE + 300 + $TF_STEP + 900 + $TF_LOAD = $((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD)) > $TF_MAX"
  f_has "$WF_VARS" "S overlay: $((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD)) against $TF_MAX"
else
  note_fail "a pipeline value of the S overlay could not be read as a whole number"
fi
case_end

# Work order 59 (D33): the S run 5 sequential totals in the S overlay comment, each computed
# here from the overlay values and the run's own inputs (creation observed at 660 s, the fetch
# step 300 s, the step estimate 3300 and 3900 s, terminate 900 s; the loader and the step cap
# are read from the file) and never written twice. The O1 worst case (creation at its timeout)
# is read from the file as before and must differ from the third total by exactly the
# difference between the creation timeout and the 660 s.
static_begin "14.terraform.s-overlay.s-run-5-sequential-totals-are-computed-from-the-values-and-the-o1-worst-case-is-read-from-the-file"
tf_overlay "$TF_S"
if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
  s5_low=$((660 + 300 + 3300 + 900 + TF_LOAD))
  s5_high=$((660 + 300 + 3900 + 900 + TF_LOAD))
  s5_cap=$((660 + 300 + TF_STEP + 900 + TF_LOAD))
  s5_o1=$((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD))
  f_has "$TF_S" "660 + 300 + 3300 + 900 + $TF_LOAD = $s5_low s at the low step estimate, $s5_high s"
  f_has "$TF_S" "at 3900 s, and 660 + 300 + $TF_STEP + 900 + $TF_LOAD = $s5_cap s with the step at its $TF_STEP s cap"
  f_has "$TF_S" "the O1 worst case below, $s5_o1 s)"
  f_has "$TF_S" "the sequential worst case $TF_CREATE + 300 + $TF_STEP + 900 + $TF_LOAD = $s5_o1 > $TF_MAX"
  # Work order 87: with the 1800 s loader of D33 the third total was 8460; the 5400 s loader of infra
  # work order 86 gives 660 + 300 + 4800 + 900 + 5400 = 12060 (the README's "10,560, 11,160 and 12,060").
  [ "$s5_cap" = "12060" ] || note_fail "the third total from the values is $s5_cap, work order 86 says 12060 (D33 said 8460 with a 1800 s loader)"
  [ "$s5_low" = "10560" ] || note_fail "the first total from the values is $s5_low, work order 86 says 10560"
  [ "$s5_high" = "11160" ] || note_fail "the second total from the values is $s5_high, work order 86 says 11160"
  [ "$s5_o1" = "12840" ] || note_fail "the O1 worst case from the values is $s5_o1, work order 86 says 12840"
  [ "$s5_o1" -gt "$s5_cap" ] || note_fail "the O1 worst case $s5_o1 is not above the third total $s5_cap"
  [ $((s5_o1 - s5_cap)) -eq $((TF_CREATE - 660)) ] || note_fail "the O1 worst case and the third total differ by $((s5_o1 - s5_cap)), not by the creation timeout minus 660 s ($((TF_CREATE - 660)))"
  { [ "$s5_low" -lt "$s5_high" ] && [ "$s5_high" -lt "$s5_cap" ]; } || note_fail "the totals are not in rising order: $s5_low, $s5_high, $s5_cap"
  [ "$s5_cap" -gt "$TF_MAX" ] || note_fail "the third total $s5_cap no longer exceeds the execution timeout $TF_MAX, so the accepted limitation text would be stale"
else
  note_fail "a pipeline value of the S overlay could not be read as a whole number"
fi
case_end

# Work order 59: the infra README sentence about the repair round 2 values is true for M, L
# and the S loader and says so for the S step, with the S values read from the overlay.
static_begin "14.terraform.readme-says-which-timeouts-keep-their-repair-round-2-values"
tf_overlay "$TF_S"
TF_README="$TF_DIR/README.md"
if tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
  # Work order 87: infra work order 86 changed the S loader too, so the sentence now says that
  # neither S value keeps its repair round 2 value. The two older wordings must be gone.
  f_has "$TF_README" "The M and L EMR step and loader timeouts keep their repair round 2 values; the S EMR step does not (1800 s, then 3600 s, now $TF_STEP s), and neither does the S loader (1800 s, now $TF_LOAD s, work order 86)."
  f_has "$TF_README" "the S loader changed once (1800 s, then $TF_LOAD s under work order 86, for the loader-only mode below)"
  f_has_not "$TF_README" "and so does the S loader"
  f_has_not "$TF_README" "and so is the S loader value (1800 s)"
else
  note_fail "a pipeline value of the S overlay could not be read as a whole number"
fi
f_has_not "$TF_README" "The EMR step and loader timeouts keep their repair round 2 values"
case_end

# tf_ml_comment FILE LABEL SIGN: the M or L overlay comment states Rule A, its slack and the
# sequential worst case (create + 300 + step + 900 + loader) as the values give them. SIGN is
# over (the worst case exceeds the maximum, so the comment must say O1 DOES apply) or within
# (it does not, so the comment must say O1 does NOT apply). The figures are computed from the
# values read, never written twice.
tf_ml_comment() {
  local ra wc
  tf_overlay "$1"
  if ! { tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; }; then
    note_fail "a pipeline value of the $2 overlay could not be read as a whole number"
    return 0
  fi
  ra=$((TF_CREATE + TF_STEP + 600))
  wc=$((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD))
  f_has "$1" "$TF_CREATE + $TF_STEP + 600 = $ra <= $TF_MAX"
  f_has "$1" "$((TF_MAX - ra)) s of slack"
  if [ "$wc" -gt "$TF_MAX" ]; then
    [ "$3" = "over" ] || note_fail "$2: the worst case $wc exceeds $TF_MAX, expected it to stay within"
    f_has "$1" "$TF_CREATE + 300 + $TF_STEP + 900 + $TF_LOAD = $wc > $TF_MAX ($((wc - TF_MAX)) s over)"
    f_has "$1" "DOES apply to $2"
  else
    [ "$3" = "within" ] || note_fail "$2: the worst case $wc stays within $TF_MAX, expected it to exceed it"
    f_has "$1" "$TF_CREATE + 300 + $TF_STEP + 900 + $TF_LOAD = $wc <= $TF_MAX ($((TF_MAX - wc)) s of slack)"
    f_has "$1" "does NOT apply to $2"
  fi
}

static_begin "14.terraform.m-and-l-overlays.comment-arithmetic-matches-the-values"
tf_ml_comment "$TF_M" M over
tf_ml_comment "$TF_L" L within
case_end

### Work order 54: the on-node time limits and the S step cap (D32) -------------------------
# Static readings only. The Spark and ER defaults and the S overlay figures are read from the
# files and compared with each other; the only literals are the decision's own numbers (D32)
# and the 874 s that S run 4 spent before Spark started (checkpoint after stage 6).
# SC2016: every single-quoted string below is a literal fragment of a node script that
# f_has, f_has_not and the case patterns search for as text; the $ must not expand.
# shellcheck disable=SC2016
static_wo54_cases() {
  local er_line spark_t er_t ra_fig ra_max wc_fig wc_max wf_fig wf_max ra_calc wc_calc
  # Work order 87: the S worst case is 12840 since infra work order 86 raised the S loader to 5400 s
  # (9240 under D32 with a 1800 s loader); Rule A, 6840, is unchanged.
  local pre_spark=874 want_ra=6840 want_wc=12840

  static_begin "14.emr.timeouts.spark-limit-default-is-3000-overridable-with-sc-spark-timeout-and-status-124-is-documented"
  f_has "$emr_lib" 'timeout -k 30 "${SC_SPARK_TIMEOUT:-3000}" "${cmd[@]}"'
  f_has_not "$emr_lib" 'SC_SPARK_TIMEOUT:-1500'
  f_has "$EMR_DIR/lib-emr.sh" 'Returns the exit status (124 on the time limit).'
  f_has "$EMR_DIR/lib-emr.sh" 'The time limit is SC_SPARK_TIMEOUT seconds, default 3000 (was 1500).'
  case_end

  static_begin "14.emr.timeouts.er-limit-is-sc-er-timeout-default-3000-and-the-old-1500-literal-is-gone-from-the-er-line"
  er_line="$(grep -F 'scholarchain.er.ErBatch' "$emr_run" | head -n 1)"
  [ -n "$er_line" ] || note_fail "no code line of run-s-pipeline.sh runs scholarchain.er.ErBatch"
  case "$er_line" in
    *'timeout -k 30 "${SC_ER_TIMEOUT:-3000}" '*) ;;
    *) note_fail "the ErBatch line does not use timeout -k 30 with the SC_ER_TIMEOUT default 3000: $er_line" ;;
  esac
  case "$er_line" in
    *'timeout 1500'* | *'timeout -k 30 1500'*) note_fail "the ErBatch line still carries the literal 1500 limit: $er_line" ;;
    *) ;;
  esac
  f_has_not "$emr_run" 'SC_ER_TIMEOUT:-1500'
  f_has "$EMR_DIR/run-s-pipeline.sh" 'Override with SC_ER_TIMEOUT.'
  case_end

  static_begin "14.emr.timeouts.hadoop-stage-limit-stays-1500-and-comes-before-the-er-line"
  f_has "$emr_run" 'HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout -k 30 1500'
  f_before "$emr_run" 'timeout -k 30 1500' 'timeout -k 30 "${SC_ER_TIMEOUT:-3000}"'
  case_end

  static_begin "14.terraform.s-overlay.rule-a-and-worst-case-figures-in-the-text-are-read-from-the-files-and-match-d32"
  tf_overlay "$TF_S"
  read -r ra_fig ra_max <<< "$(sed -n 's/.*+ 600 = \([0-9][0-9]*\) <= \([0-9][0-9]*\).*/\1 \2/p' "$TF_S" | head -n 1)"
  read -r wc_fig wc_max <<< "$(sed -n 's/.*+ 900 + [0-9][0-9]* = \([0-9][0-9]*\) > \([0-9][0-9]*\).*/\1 \2/p' "$TF_S" | head -n 1)"
  read -r wf_fig wf_max <<< "$(sed -n 's/.*S overlay: \([0-9][0-9]*\) against \([0-9][0-9]*\).*/\1 \2/p' "$WF_VARS" | head -n 1)"
  if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD" \
    && tf_is_num "$ra_fig" && tf_is_num "$ra_max" && tf_is_num "$wc_fig" && tf_is_num "$wc_max" \
    && tf_is_num "$wf_fig" && tf_is_num "$wf_max"; then
    ra_calc=$((TF_CREATE + TF_STEP + 600))
    wc_calc=$((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD))
    [ "$ra_calc" = "$want_ra" ] || note_fail "Rule A for S from the overlay values is $ra_calc, D32 says $want_ra"
    [ "$wc_calc" = "$want_wc" ] || note_fail "the worst case for S from the overlay values is $wc_calc, D32 says $want_wc"
    [ "$ra_fig" = "$ra_calc" ] || note_fail "the overlay comment states Rule A as $ra_fig, the values give $ra_calc"
    [ "$ra_max" = "$TF_MAX" ] || note_fail "the overlay comment compares Rule A with $ra_max, the maximum is $TF_MAX"
    [ "$wc_fig" = "$wc_calc" ] || note_fail "the overlay comment states the worst case as $wc_fig, the values give $wc_calc"
    [ "$wc_max" = "$TF_MAX" ] || note_fail "the overlay comment compares the worst case with $wc_max, the maximum is $TF_MAX"
    [ "$wf_fig" = "$wc_calc" ] || note_fail "the module description states the S worst case as $wf_fig, the values give $wc_calc"
    [ "$wf_max" = "$TF_MAX" ] || note_fail "the module description compares the S worst case with $wf_max, the maximum is $TF_MAX"
    [ "$ra_calc" -le "$TF_MAX" ] || note_fail "Rule A $ra_calc exceeds the maximum $TF_MAX"
    [ "$wc_calc" -gt "$TF_MAX" ] || note_fail "the worst case $wc_calc no longer exceeds $TF_MAX, so the accepted limitation text would be stale"
  else
    note_fail "a figure could not be read as a whole number (overlay values, overlay comment or module description)"
  fi
  case_end

  static_begin "14.emr.timeouts.spark-limit-plus-the-run-4-pre-spark-time-stays-under-the-s-step-cap"
  tf_overlay "$TF_S"
  spark_t="$(sed -n 's/.*SC_SPARK_TIMEOUT:-\([0-9][0-9]*\)}.*/\1/p' "$emr_lib" | head -n 1)"
  er_t="$(sed -n 's/.*SC_ER_TIMEOUT:-\([0-9][0-9]*\)}.*/\1/p' "$emr_run" | head -n 1)"
  if tf_is_num "$spark_t" && tf_is_num "$er_t" && tf_is_num "$TF_STEP"; then
    [ $((spark_t + pre_spark)) -lt "$TF_STEP" ] || note_fail "Spark limit $spark_t plus $pre_spark s before Spark is not under the S step cap $TF_STEP"
    [ "$spark_t" -gt 2400 ] || note_fail "the Spark limit $spark_t is not above the 2400 s upper estimate of the Spark stage (D32)"
    [ "$er_t" -lt "$TF_STEP" ] || note_fail "the ER limit $er_t is not under the S step cap $TF_STEP"
  else
    note_fail "a limit could not be read: Spark '$spark_t', ER '$er_t', S step cap '$TF_STEP'"
  fi
  case_end
}
static_wo54_cases

### Work order 56: the review repairs of work order 55 (D33) ----------------------------------
# -k 30 on the three long timeouts, yarn_final_state under timeout 120, and the failure-path
# publish (sc_publish_failed_logs, sc_on_exit) with its shared time budget. Static cases read
# the scripts and scripts/README.md as text. Dynamic cases source the real lib-emr.sh in a
# child shell (a bash program under $CODE_DIR) with shell functions standing in for timeout,
# aws and yarn, as the progress cases above do, so no stub on PATH is reached and the tripwire
# stays empty. run_spark_submit reaches timeout through env, which a function cannot stand in
# for, so that child puts a one-line timeout script first on its PATH. Nothing here runs a real
# timeout, so what GNU timeout does with -k (exit 124, or 137 after a KILL) stays a documented
# claim, as the README says.

# yarn_final_state and spark_verdict with timeout and yarn replaced by functions. MODE:
# succeeded, failed, yarn-fails (yarn exits 1), timeout (timeout exits 124 and never runs
# yarn) or timeout-late (yarn prints SUCCEEDED, then timeout exits 124).
cat > "$CODE_DIR/yarn-state-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; MODE="$3"
source "$LIB"
CALLS="$FIX/calls.log"
: > "$CALLS"
timeout() {
  local r
  printf 'TIMEOUT %s\n' "$1" >> "$CALLS"
  if [ "$MODE" = "timeout" ]; then return 124; fi
  shift
  r=0
  "$@" || r=$?
  if [ "$MODE" = "timeout-late" ]; then return 124; fi
  return "$r"
}
yarn() {
  printf 'YARN %s\n' "$*" >> "$CALLS"
  case "$MODE" in
    succeeded | timeout-late) printf 'Application Report : \n\tFinal-State : SUCCEEDED\n' ;;
    failed) printf 'Application Report : \n\tFinal-State : FAILED\n' ;;
    yarn-fails) return 1 ;;
  esac
  return 0
}
printf 'STATE=%s\n' "$(yarn_final_state application_1794701635353_0001)"
spark_verdict "$FIX/spark.log" 0
printf 'VERDICT=%s\n' "$SC_LAST_VERDICT"
printf 'VSTATE=%s\n' "$SC_LAST_STATE"
EOF

# sc_publish_failed_logs, directly (MODE direct) or through the exit trap of a failed stage
# (MODE exit, exit code EXITCODE), with timeout and aws replaced by functions that log every
# call (CALLS) and copy every uploaded file (UPLOADS). Environment: AWSFAIL=1 makes aws exit 1;
# TICK adds that many seconds to the SECONDS counter on every aws call (a stub clock, so a
# time budget can be spent without waiting); SC_FAILED_PUBLISH_BUDGET_SECONDS is read by
# lib-emr.sh when it is sourced. MODE unregistered and nodir never reach a reports directory.
cat > "$CODE_DIR/failed-publish-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; MODE="$3"
source "$LIB"
UPLOADS="$FIX/uploads"
CALLS="$FIX/calls.log"
mkdir -p "$UPLOADS"
: > "$CALLS"
timeout() { printf 'TIMEOUT %s\n' "$1" >> "$CALLS"; shift; "$@"; }
aws() {
  if [ "${AWSFAIL:-0}" = "1" ]; then
    printf 'AWS-FAILED %s\n' "$*" >> "$CALLS"
    SECONDS=$((SECONDS + ${TICK:-0}))
    return 1
  fi
  printf 'AWS %s\n' "$*" >> "$CALLS"
  if [ "${1:-}" = "s3" ] && [ "${2:-}" = "cp" ]; then
    cp "$3" "$UPLOADS/${4##*/}"
  fi
  SECONDS=$((SECONDS + ${TICK:-0}))
  return 0
}
SC_KV=$'RUN_ID=abc\nMAPREDUCE=yarn\n'
case "$MODE" in
  unregistered) ;;
  nodir) register_failure_logs "$FIX/no-such-dir" data-bucket run-v1-x ;;
  *) register_failure_logs "$FIX/reports" data-bucket run-v1-x ;;
esac
if [ "$MODE" = "exit" ]; then
  install_trap
  SC_T0=0
  SECONDS=500
  stage boom-stage
  SECONDS=507
  exit "${EXITCODE:-7}"
fi
rc=0
sc_publish_failed_logs || rc=$?
printf 'RC=%s\n' "$rc"
EOF

# run_spark_submit with a spark-submit script of its own and a timeout script that logs its
# arguments, drops the -k option and the duration and runs the rest (or exits 124 when a file
# named limit sits beside the calls log).
SS_TPL="$EMR_FX/ss-tpl"
mkdir -p "$SS_TPL"
cat > "$SS_TPL/timeout" <<'EOF'
#!/bin/sh
printf 'TIMEOUT-ARGS %s\n' "$*" >> "${0%/*}/../calls.log"
if [ -f "${0%/*}/../limit" ]; then exit 124; fi
if [ "$1" = "-k" ]; then shift 2; fi
shift
exec "$@"
EOF
cat > "$SS_TPL/spark-submit" <<'EOF'
#!/bin/sh
printf 'SPARK-SUBMIT %s\n' "$*" >> "${0%/*}/../../calls.log"
printf 'INFO Client: Submitted application application_1794701635353_0001\n'
exit 0
EOF
chmod 755 "$SS_TPL/timeout" "$SS_TPL/spark-submit"
cat > "$CODE_DIR/spark-submit-child.sh" <<'EOF'
LIB="$1"; FIX="$2"
source "$LIB"
PATH="$FIX/bin:$PATH"
export PATH
SPARK_HOME="$FIX/spark"
rc=0
run_spark_submit "$FIX/spark.log" client scholarchain.spark.CollaborationPipeline /app.jar "/a.jar,/b.jar" --out-root /out || rc=$?
printf 'RC=%s\n' "$rc"
EOF

# shellcheck disable=SC2016
static_wo56_cases() {
  local fp_fn="$CODE_DIR/failed-publish-fn.sh" rd="$REAL_ROOT/scripts/README.md" k
  extract_fn "$emr_lib" sc_publish_failed_logs "$fp_fn"

  static_begin "14.emr.timeouts.k-30-is-on-the-spark-hadoop-and-er-calls-and-no-bare-form-is-left"
  f_has "$emr_lib" 'timeout -k 30 "${SC_SPARK_TIMEOUT:-3000}" "${cmd[@]}"'
  f_has "$emr_run" 'JAVA_HOME="$SC_JAVA_HOME" timeout -k 30 1500'
  f_has "$emr_run" 'timeout -k 30 "${SC_ER_TIMEOUT:-3000}" "$SC_JAVA_HOME/bin/java"'
  f_has_not "$emr_lib" 'timeout "${SC_SPARK_TIMEOUT'
  f_has_not "$emr_run" 'timeout 1500'
  f_has_not "$emr_run" 'timeout "${SC_ER_TIMEOUT'
  # Work order 65: lib-emr.sh now holds two bare-free timeout -k 30 calls, the Spark one and the
  # on-node preflight's (run_preflight). The run script's count of 2 is unchanged.
  f_count_is "$emr_lib" 'timeout -k 30 ' 2
  f_has "$emr_lib" 'timeout -k 30 "$remaining" "$SPARK_HOME/bin/spark-shell"'
  f_count_is "$emr_run" 'timeout -k 30 ' 2
  case_end

  static_begin "14.emr.timeouts.readme-documents-k-30-status-124-and-the-137-claim-as-not-observed"
  f_has "$rd" '`-k 30` sends KILL 30 s after the TERM that the time limit sends'
  f_has "$rd" 'Every stage under `timeout` catches the status (`|| rc=$?`) and calls `die`, and `die` always exits 1, so `sc_on_exit` records `FAILED_EXIT_CODE=1` for a timed-out stage'
  f_has "$rd" 'The 124 appears only in the text that `die` writes to stderr, which is the EMR step log'
  f_has "$rd" '`STAGE_SECONDS_<STAGE>=<n> (stage failed)` with n equal to the stage limit'
  f_has "$rd" 'From the GNU coreutils manual, not observed on the node'
  f_has "$rd" 'makes `timeout -k` exit 137, so that text would read 137 instead of 124; the recorded `FAILED_EXIT_CODE` stays 1 either way.'
  f_has_not "$rd" 'A timeout exits 124, which the stage reports as a failure'
  f_has_not "$rd" 'would report `FAILED_EXIT_CODE=137`'
  case_end

  static_begin "14.emr.timeouts.readme-limit-table-names-the-same-commands-as-the-code"
  f_has "$rd" '| Hadoop | `timeout -k 30 1500` |'
  f_has "$rd" '`timeout -k 30 "${SC_ER_TIMEOUT:-3000}"`'
  f_has "$rd" '`timeout -k 30 "${SC_SPARK_TIMEOUT:-3000}"`'
  f_has "$rd" '`timeout 120` per `yarn application -status`; a timeout gives `unknown`, which is not `SUCCEEDED`'
  f_has "$rd" '`SC_FAILED_PUBLISH_BUDGET_SECONDS` (a whole number with no leading zero, else 600)'
  f_has_not "$rd" '(digits, else 600)'
  f_has "$rd" 'FAILED_LOGS_SKIPPED file=<name> reason=time-budget-spent'
  case_end

  static_begin "14.emr.lib-emr.yarn_final_state.is-wrapped-in-timeout-120-and-resets-to-unknown-on-failure"
  f_has "$emr_lib" 'timeout 120 yarn application -status "$1"'
  f_has "$emr_lib" '|| s=""'
  case_end

  static_begin "14.emr.lib-emr.sc_publish_failed_logs.is-a-budgeted-best-effort-scanned-per-file-copy-under-the-failed-stamp"
  f_has "$emr_lib" 'SC_FAILED_PUBLISH_BUDGET_SECONDS="${SC_FAILED_PUBLISH_BUDGET_SECONDS:-600}"'
  f_has "$fp_fn" 'dest="s3://$bucket/reports/runs/$run/failed/$stamp/"'
  f_has "$fp_fn" '[[ -n "$rep" && -n "$bucket" && -n "$run" && -d "$rep" ]] || return 0'
  f_count_is "$fp_fn" 'aws s3 cp' 1
  f_has "$fp_fn" 'timeout "$per" aws s3 cp "$f" "$dest$name" --only-show-errors'
  f_has "$fp_fn" '[[ "$budget" =~ ^(0|[1-9][0-9]*)$ ]] || budget=600'
  f_has_not "$fp_fn" '=~ ^[0-9]+$'
  f_has "$fp_fn" 'deadline=$((SECONDS + budget))'
  f_has "$fp_fn" 'per=300'
  f_has "$fp_fn" 'if [[ "$remaining" -lt "$per" ]]; then'
  f_has "$fp_fn" 'per="$remaining"'
  f_has "$fp_fn" 'reason=time-budget-spent (budget %s s)'
  f_has "$fp_fn" 'reason=key-material-pattern (content not printed)'
  f_has "$fp_fn" 'FAILED_LOGS_PUBLISHED=%s'
  f_before "$fp_fn" 'files=("$rep/run-record.env")' 'for f in "$rep"/*; do'
  f_before "$fp_fn" 'grep -qE "$SC_SECRET_RE" "$f"' 'remaining=$((deadline - SECONDS))'
  f_before "$fp_fn" 'remaining=$((deadline - SECONDS))' 'timeout "$per" aws s3 cp'
  for k in '--sse-c' '--delete' 's3 sync' 's3 rm' 's3 mv' '--recursive' 'delete-object'; do
    f_has_not "$fp_fn" "$k"
  done
  f_has_not_re "$fp_fn" '(^|[^a-z_])(die|exit)([^a-z_]|$)'
  case_end

  static_begin "14.emr.lib-emr.sc_on_exit.records-the-failed-stage-and-exit-code-before-the-publish-and-exits-with-the-original-code"
  f_has "$emr_lib" 'kv FAILED_STAGE "$SC_STAGE_N $SC_STAGE_NAME"'
  f_has "$emr_lib" 'kv FAILED_EXIT_CODE "$rc"'
  f_has "$emr_lib" 'sc_publish_failed_logs || true'
  f_before "$emr_lib" 'if [[ -n "$SC_FAIL_REP" ]]; then' 'kv FAILED_STAGE'
  f_before "$emr_lib" 'kv FAILED_STAGE' 'kv FAILED_EXIT_CODE'
  f_before "$emr_lib" 'kv FAILED_EXIT_CODE' 'sc_publish_failed_logs || true'
  f_before "$emr_lib" 'sc_publish_failed_logs || true' 'exit "$rc"'
  case_end
}
static_wo56_cases

### Dynamic cases: yarn_final_state and the Spark verdict under a limit.

yn_new() {
  YN_FIX="$EMR_FX/yn-$1"
  rm -rf "$YN_FIX"
  mkdir -p "$YN_FIX"
  printf 'INFO Client: Submitted application application_1794701635353_0001\n' > "$YN_FIX/spark.log"
}
yn_run() {
  run bash "$CODE_DIR/yarn-state-child.sh" "$EMR_DIR/lib-emr.sh" "$YN_FIX" "$1"
}
# yn_expect STATE VERDICT YARN_CALLS: yarn_final_state and spark_verdict each ran under
# timeout 120 (two TIMEOUT lines), and yarn was called YARN_CALLS times.
yn_expect() {
  mr_ok
  a_out_re "^STATE=$1\$"
  a_out_re "^VERDICT=$2\$"
  a_out_re "^VSTATE=$1\$"
  [ "$(grep -c '^TIMEOUT 120$' "$YN_FIX/calls.log")" = "2" ] || note_fail "expected two timeout 120 wrappers, found $(grep -c '^TIMEOUT 120$' "$YN_FIX/calls.log")"
  [ "$(grep -c '^TIMEOUT ' "$YN_FIX/calls.log")" = "2" ] || note_fail "a timeout wrapper other than 120 was used"
  [ "$(grep -c '^YARN application -status application_1794701635353_0001$' "$YN_FIX/calls.log")" = "$3" ] || note_fail "yarn was not called $3 time(s) with application -status and the application id"
}

yn_new succeeded
yn_run succeeded
case_begin "14.emr.yarn-final-state.succeeded-gives-succeeded-and-the-verdict-yes-under-timeout-120"
yn_expect SUCCEEDED yes 2
case_end

yn_new failed
yn_run failed
case_begin "14.emr.yarn-final-state.a-failed-final-state-is-passed-through-and-the-verdict-is-no"
yn_expect FAILED no 2
case_end

yn_new yarn-fails
yn_run yarn-fails
case_begin "14.emr.yarn-final-state.a-failing-yarn-gives-unknown-and-the-verdict-is-no"
yn_expect unknown no 2
case_end

yn_new timeout
yn_run timeout
case_begin "14.emr.yarn-final-state.a-timeout-exit-124-gives-unknown-and-the-verdict-is-no"
yn_expect unknown no 0
case_end

yn_new timeout-late
yn_run timeout-late
case_begin "14.emr.yarn-final-state.a-timeout-after-partial-output-still-gives-unknown-never-succeeded"
yn_expect unknown no 2
case_end

### Dynamic cases: run_spark_submit under timeout -k 30.

ss_new() {
  SS_FIX="$EMR_FX/ss-$1"
  rm -rf "$SS_FIX"
  mkdir -p "$SS_FIX/bin" "$SS_FIX/spark/bin"
  cp "$SS_TPL/timeout" "$SS_FIX/bin/timeout"
  cp "$SS_TPL/spark-submit" "$SS_FIX/spark/bin/spark-submit"
  chmod 755 "$SS_FIX/bin/timeout" "$SS_FIX/spark/bin/spark-submit"
  : > "$SS_FIX/calls.log"
}

ss_new default
run env -u SC_SPARK_TIMEOUT bash "$CODE_DIR/spark-submit-child.sh" "$EMR_DIR/lib-emr.sh" "$SS_FIX"
case_begin "14.emr.timeouts.run-spark-submit-runs-the-command-under-timeout-k-30-3000-by-default"
mr_ok
a_out_re '^RC=0$'
grep -qF -- "TIMEOUT-ARGS -k 30 3000 $SS_FIX/spark/bin/spark-submit --master yarn --deploy-mode client --class scholarchain.spark.CollaborationPipeline --jars /a.jar,/b.jar /app.jar --out-root /out" "$SS_FIX/calls.log" || note_fail "timeout was not called with -k 30 3000 and the spark-submit command: $(head -n 1 "$SS_FIX/calls.log")"
grep -qF -- "SPARK-SUBMIT --master yarn --deploy-mode client --class scholarchain.spark.CollaborationPipeline" "$SS_FIX/calls.log" || note_fail "spark-submit was not run behind the timeout"
grep -qF -- "application_1794701635353_0001" "$SS_FIX/spark.log" || note_fail "the spark-submit output did not reach the log file"
case_end

ss_new override
run env SC_SPARK_TIMEOUT=77 bash "$CODE_DIR/spark-submit-child.sh" "$EMR_DIR/lib-emr.sh" "$SS_FIX"
case_begin "14.emr.timeouts.run-spark-submit-limit-follows-sc-spark-timeout-and-keeps-k-30"
mr_ok
a_out_re '^RC=0$'
grep -qF -- "TIMEOUT-ARGS -k 30 77 $SS_FIX/spark/bin/spark-submit " "$SS_FIX/calls.log" || note_fail "SC_SPARK_TIMEOUT=77 did not give -k 30 77: $(head -n 1 "$SS_FIX/calls.log")"
case_end

ss_new limit
: > "$SS_FIX/limit"
run env -u SC_SPARK_TIMEOUT bash "$CODE_DIR/spark-submit-child.sh" "$EMR_DIR/lib-emr.sh" "$SS_FIX"
case_begin "14.emr.timeouts.run-spark-submit-returns-the-timeout-status-124-when-the-limit-is-reached"
mr_ok
a_out_re '^RC=124$'
grep -qF -- "TIMEOUT-ARGS -k 30 3000 " "$SS_FIX/calls.log" || note_fail "timeout was not called with -k 30 3000"
if grep -qF -- "SPARK-SUBMIT" "$SS_FIX/calls.log"; then note_fail "spark-submit ran although the limit was reached first"; fi
case_end

### Dynamic cases: the failure-path publish and the exit trap.

fp_new() {
  FP_FIX="$EMR_FX/fp-$1"
  FP_CALLS="$FP_FIX/calls.log"
  rm -rf "$FP_FIX"
  mkdir -p "$FP_FIX/reports/sub"
  printf 'hadoop line\n' > "$FP_FIX/reports/hadoop.log"
  printf 'er line\n' > "$FP_FIX/reports/er.log"
  printf 'zz line\n' > "$FP_FIX/reports/zz.log"
  printf 'inner\n' > "$FP_FIX/reports/sub/inner.log"
  printf 'note %s\n' "$FAKE_AKIA" > "$FP_FIX/reports/secret.log"
}
# fp_run MODE [NAME=VALUE ...]
fp_run() {
  local mode="$1"
  shift
  run env "$@" bash "$CODE_DIR/failed-publish-child.sh" "$EMR_DIR/lib-emr.sh" "$FP_FIX" "$mode"
}
fp_names() {
  find "$FP_FIX/uploads" -type f 2> /dev/null | sed 's|.*/||' | sort | tr '\n' ' '
}
FP_STAMP_URI_RE='^FAILED_LOGS_PUBLISHED=s3://data-bucket/reports/runs/run-v1-x/failed/[0-9]{8}T[0-9]{6}Z/$'

fp_new exit-ok
fp_run exit EXITCODE=7
case_begin "14.emr.failed-publish.a-failed-registered-stage-records-the-stage-and-exit-code-then-publishes-and-the-exit-code-stands"
a_rc 7
a_trip_empty
a_order 'STAGE[1] boom-stage: FAIL (exit 7)' 'STAGE_SECONDS_TOTAL_AT_FAILURE=' 'FAILED_STAGE=1 boom-stage' 'FAILED_EXIT_CODE=7' 'FAILED_LOGS_PUBLISHED=' 'NOTE: failed-log publish:'
a_out_re "$FP_STAMP_URI_RE"
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
grep -qx 'FAILED_STAGE=1 boom-stage' "$FP_FIX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks FAILED_STAGE=1 boom-stage"
grep -qx 'FAILED_EXIT_CODE=7' "$FP_FIX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks FAILED_EXIT_CODE=7"
grep -qx 'RUN_ID=abc' "$FP_FIX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks the key=value lines recorded before"
case_end

case_begin "14.emr.failed-publish.uploads-only-under-the-failed-stamp-prefix-run-record-first-one-cp-per-file-and-no-sync-delete-or-sse-c"
[ "$(grep -c '^AWS ' "$FP_CALLS")" = "4" ] || note_fail "expected 4 aws calls, found $(grep -c '^AWS ' "$FP_CALLS")"
FP_BAD="$(grep '^AWS ' "$FP_CALLS" | grep -vE '^AWS s3 cp [^ ]+ s3://data-bucket/reports/runs/run-v1-x/failed/[0-9]{8}T[0-9]{6}Z/[A-Za-z0-9._-]+ --only-show-errors$' | head -n 1)"
[ -z "$FP_BAD" ] || note_fail "an aws call has another shape: $FP_BAD"
grep '^AWS ' "$FP_CALLS" | head -n 1 | grep -qE '/failed/[0-9]{8}T[0-9]{6}Z/run-record\.env --only-show-errors$' || note_fail "the first upload is not run-record.env"
[ "$(grep '^AWS ' "$FP_CALLS" | awk '{ print $5 }' | sed 's|[^/]*$||' | sort -u | wc -l | tr -d ' ')" = "1" ] || note_fail "the uploads do not share one failed/<stamp>/ prefix"
[ "$(grep -c 'progress' "$FP_CALLS")" = "0" ] || note_fail "a call names the progress prefix"
if grep -qE -- '--sse-c|--delete|sync|--recursive|s3 rm|s3 mv' "$FP_CALLS"; then note_fail "a call uses sync, delete, recursive or sse-c"; fi
[ "$(fp_names)" = "er.log hadoop.log run-record.env zz.log " ] || note_fail "uploaded names: $(fp_names)"
case_end

case_begin "14.emr.failed-publish.a-file-with-key-material-is-skipped-and-its-text-is-never-printed-or-uploaded"
a_out "FAILED_LOGS_SKIPPED file=secret.log reason=key-material-pattern (content not printed)"
a_out "NOTE: failed-log publish: 4 file(s) uploaded, 1 skipped (key-material pattern), 0 failed"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
if grep -rqF -- "$FAKE_AKIA" "$FP_FIX/uploads"; then note_fail "key material was uploaded"; fi
if grep -qF -- "secret.log" "$FP_CALLS"; then note_fail "an aws call names secret.log"; fi
case_end

case_begin "14.emr.failed-publish.every-upload-is-timed-and-the-per-call-limit-is-300-while-the-budget-is-untouched"
[ "$(grep -c '^TIMEOUT ' "$FP_CALLS")" = "4" ] || note_fail "expected 4 timeout wrappers, found $(grep -c '^TIMEOUT ' "$FP_CALLS")"
FP_TO="$(awk '$1 == "TIMEOUT" && $2 != 300 { print }' "$FP_CALLS" | head -n 1)"
[ -z "$FP_TO" ] || note_fail "a per-call limit is not 300 with the whole budget left: $FP_TO"
case_end

fp_new direct-fail
fp_run direct AWSFAIL=1
case_begin "14.emr.failed-publish.aws-failures-are-counted-and-swallowed-the-function-still-returns-zero"
a_rc 0
a_trip_empty
a_out_re '^RC=0$'
a_out "NOTE: failed-log publish: 0 file(s) uploaded, 1 skipped (key-material pattern), 4 failed"
a_not_out "FAILED_LOGS_PUBLISHED="
[ "$(grep -c '^AWS-FAILED ' "$FP_CALLS")" = "4" ] || note_fail "expected 4 failed aws calls, found $(grep -c '^AWS-FAILED ' "$FP_CALLS")"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

# This is a unit test of sc_on_exit: the child ends with a direct "exit 124", so the trap sees
# 124 and must keep it whatever the publish does. It does not model a timed-out stage. In the
# real script a stage under timeout catches the status (|| rc=$?) and calls die, which always
# exits 1, so a timed-out stage records FAILED_EXIT_CODE=1 (scripts/README.md, "On-node time
# limits"; S run 4, D32). The 124 here is only a value the trap must pass through unchanged.
fp_new exit-fail
fp_run exit AWSFAIL=1 EXITCODE=124
case_begin "14.emr.failed-publish.a-failing-aws-in-the-exit-trap-does-not-change-the-original-exit-code-124"
a_rc 124
a_trip_empty
a_out 'FAILED_STAGE=1 boom-stage'
a_out 'FAILED_EXIT_CODE=124'
a_out "NOTE: failed-log publish: 0 file(s) uploaded, 1 skipped (key-material pattern), 4 failed"
a_not_out "FAILED_LOGS_PUBLISHED="
case_end

# fp_clock_expect: the stub clock (TICK=250 on every upload) spends a 600 s budget. The limits
# of the three calls are 300 (350 left, so the limit stays 300), 300 and the time left (about
# 100, a second or so less when the clock ticks over), and the fourth upload candidate, zz.log,
# is skipped with the budget text and the NOTE with the count.
fp_clock_expect() {
  local t1="" t2="" t3="" t4=""
  a_rc 0
  a_trip_empty
  a_out_re '^RC=0$'
  read -r t1 t2 t3 t4 <<< "$(awk '$1 == "TIMEOUT" { printf "%s ", $2 }' "$FP_CALLS")"
  [ "$t1" = "300" ] || note_fail "the first per-call limit is '$t1', expected 300"
  [ "$t2" = "300" ] || note_fail "the second per-call limit is '$t2', expected 300 (350 s were left)"
  { tf_is_num "$t3" && [ "$t3" -ge 95 ] && [ "$t3" -le 100 ]; } || note_fail "the third per-call limit is '$t3', expected the time left (95 to 100)"
  [ -z "$t4" ] || note_fail "a fourth upload was timed ($t4) after the budget was spent"
  a_out "FAILED_LOGS_SKIPPED file=zz.log reason=time-budget-spent (budget 600 s)"
  a_out "NOTE: failed-log publish: 1 file(s) not uploaded, time budget of 600 s spent"
  a_out "NOTE: failed-log publish: 3 file(s) uploaded, 1 skipped (key-material pattern), 0 failed"
  a_out_re "$FP_STAMP_URI_RE"
  grep '^AWS ' "$FP_CALLS" | head -n 1 | grep -qE '/failed/[0-9]{8}T[0-9]{6}Z/run-record\.env --only-show-errors$' || note_fail "the first upload is not run-record.env"
  [ "$(fp_names)" = "er.log hadoop.log run-record.env " ] || note_fail "uploaded names: $(fp_names)"
  if grep -qF -- "zz.log" "$FP_CALLS"; then note_fail "zz.log was uploaded with no budget left"; fi
}

fp_new clock
fp_run direct TICK=250
case_begin "14.emr.failed-publish.each-call-gets-the-smaller-of-300-and-the-time-left-and-a-spent-budget-skips-the-rest-with-a-note"
fp_clock_expect
case_end

fp_new budget-abc
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=abc TICK=250
case_begin "14.emr.failed-publish.a-non-numeric-budget-abc-falls-back-to-600"
fp_clock_expect
a_not_out "budget abc"
case_end

fp_new budget-negative
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=-5 TICK=250
case_begin "14.emr.failed-publish.a-negative-budget-minus-5-falls-back-to-600"
fp_clock_expect
a_not_out "budget -5"
case_end

# Work order 59: a leading zero is not a whole number here (bash would read 08 and 09 as bad
# octal, "value too great for base", and 0600 as 384 s), so it falls back to 600 like abc and
# -5. The same clock expectations as the abc case hold, stderr stays empty, and the budget
# text never shows the rejected value.
fp_new budget-08
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=08 TICK=250
case_begin "14.emr.failed-publish.a-budget-with-a-leading-zero-08-falls-back-to-600"
fp_clock_expect
a_not_out "budget 08"
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
case_end

fp_new budget-09
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=09 TICK=250
case_begin "14.emr.failed-publish.a-budget-with-a-leading-zero-09-falls-back-to-600"
fp_clock_expect
a_not_out "budget 09"
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
case_end

fp_new budget-0600
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=0600 TICK=250
case_begin "14.emr.failed-publish.a-budget-of-0600-falls-back-to-600-not-the-octal-384"
fp_clock_expect
a_not_out "budget 0600"
a_not_out "budget 384"
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
case_end

fp_new budget-zero
fp_run direct SC_FAILED_PUBLISH_BUDGET_SECONDS=0
case_begin "14.emr.failed-publish.a-budget-of-zero-skips-every-file-calls-no-aws-prints-the-note-and-returns-zero"
a_rc 0
a_trip_empty
a_out_re '^RC=0$'
a_out "FAILED_LOGS_SKIPPED file=run-record.env reason=time-budget-spent (budget 0 s)"
a_out "FAILED_LOGS_SKIPPED file=er.log reason=time-budget-spent (budget 0 s)"
a_out "FAILED_LOGS_SKIPPED file=hadoop.log reason=time-budget-spent (budget 0 s)"
a_out "FAILED_LOGS_SKIPPED file=zz.log reason=time-budget-spent (budget 0 s)"
a_out "FAILED_LOGS_SKIPPED file=secret.log reason=key-material-pattern (content not printed)"
a_out "NOTE: failed-log publish: 4 file(s) not uploaded, time budget of 0 s spent"
a_out "NOTE: failed-log publish: 0 file(s) uploaded, 1 skipped (key-material pattern), 0 failed"
a_not_out "FAILED_LOGS_PUBLISHED="
[ ! -s "$FP_CALLS" ] || note_fail "aws or timeout was called with no budget: $(head -n 1 "$FP_CALLS")"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

fp_new unregistered
fp_run unregistered
case_begin "14.emr.failed-publish.does-nothing-before-register-failure-logs-ran"
a_rc 0
a_trip_empty
[ "$(cat "$OUT")" = "RC=0" ] || note_fail "stdout is not exactly RC=0"
[ ! -s "$FP_CALLS" ] || note_fail "a call was made before register_failure_logs"
case_end

fp_new nodir
fp_run nodir
case_begin "14.emr.failed-publish.does-nothing-when-the-reports-directory-is-missing"
a_rc 0
a_trip_empty
[ "$(cat "$OUT")" = "RC=0" ] || note_fail "stdout is not exactly RC=0"
[ ! -s "$FP_CALLS" ] || note_fail "a call was made with no reports directory"
case_end

### Work order 61: the export output committer (follows infra-engineer work order 60, S run 5) ---
# S run 5 failed in the export stage with ClassNotFoundException for EMR's
# org.apache.hadoop.mapred.DirectFileOutputCommitter, named by the node's mapred-site key
# mapred.output.committer.class (UNVERIFIED: the node file was not read). write_spark_conf now
# writes the Hadoop default for that key. Nothing here runs a Spark job: the generated
# spark-defaults.conf, the scripts as text and the README paragraph are read. No case of work
# orders 50 to 59 was changed.

WO61_LINE='spark.hadoop.mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitter'
WO61_KEY_RE='^spark\.hadoop\.mapred\.output\.committer\.class '

# Child program: write_spark_conf with the populate flag (argument 3) and SC_JAVA_HOME (argument 4,
# may be empty) chosen by the caller. The capability check calls it with true, the pipeline with
# the value of POPULATE_HADOOP_CLASSPATH.
cat > "$CODE_DIR/sparkconf-variant-child.sh" <<'EOF'
SC_HOME="$2"
source "$1"
SC_ARCHIVE_URI="hdfs://harness/spark-jars.zip"
SC_JAVA_HOME="$4"
write_spark_conf "$3"
cat "$SC_SPARK_CONF_DIR/spark-defaults.conf"
EOF

# Child program: conf_xml_value for the old-API committer key, with HADOOP_CONF_DIR set by the caller.
cat > "$CODE_DIR/conf-xml-child.sh" <<'EOF'
LIB="$1"
export HADOOP_CONF_DIR="$2"
source "$LIB"
printf 'V=%s\n' "$(conf_xml_value mapred.output.committer.class)"
EOF

# wo61_conf_expect: the spark-defaults.conf on stdout holds the committer line exactly once, no
# other line sets that key, nothing names the EMR class, and the line follows the umask line.
# SC2016: the single-quoted strings are literal text that grep and f_before search for.
# shellcheck disable=SC2016
wo61_conf_expect() {
  mr_ok
  [ "$(grep -cxF -- "$WO61_LINE" "$OUT")" = "1" ] || note_fail "the committer line is not present exactly once"
  [ "$(grep -c -- "$WO61_KEY_RE" "$OUT")" = "1" ] || note_fail "the committer key is not set by exactly one line"
  if grep -qF -- 'DirectFileOutputCommitter' "$OUT"; then note_fail "spark-defaults.conf names DirectFileOutputCommitter"; fi
  f_before "$OUT" 'spark.hadoop.fs.permissions.umask-mode 000' "$WO61_LINE"
}

# Dynamic: the generated spark-defaults.conf (item a).
sp_new wo61-defaults
sp_run
case_begin "14.emr.spark-conf.export-committer-line-is-in-the-defaults-exactly-once-after-the-umask-line"
wo61_conf_expect
case_end

sp_new wo61-real-file
cp "$EMR_DIR/spark-yarn.conf" "$SP_HOME/spark-yarn.conf"
sp_run
case_begin "14.emr.spark-conf.export-committer-line-is-in-the-real-spark-yarn-conf-output-exactly-once"
wo61_conf_expect
case_end

sp_new wo61-populate-true
run bash "$CODE_DIR/sparkconf-variant-child.sh" "$EMR_DIR/lib-emr.sh" "$SP_HOME" true ""
case_begin "14.emr.spark-conf.export-committer-line-is-written-with-populate-true-as-the-capability-check-does"
wo61_conf_expect
a_out_re '^spark\.yarn\.populateHadoopClasspath true$'
case_end

sp_new wo61-java-home
run bash "$CODE_DIR/sparkconf-variant-child.sh" "$EMR_DIR/lib-emr.sh" "$SP_HOME" false /opt/harness-jdk
case_begin "14.emr.spark-conf.export-committer-line-is-written-once-when-a-java-home-is-set-too"
wo61_conf_expect
a_out_re '^spark\.yarn\.appMasterEnv\.JAVA_HOME /opt/harness-jdk$'
a_out_re '^spark\.executorEnv\.JAVA_HOME /opt/harness-jdk$'
case_end

# Dynamic: conf_xml_value, the reader behind the evidence line (item c). Present, absent, and a
# key that only resembles it (a different character in place of a dot, a longer key).
mkdir -p "$EMR_FX/cx-present" "$EMR_FX/cx-lookalike" "$EMR_FX/cx-empty"
cat > "$EMR_FX/cx-present/mapred-site.xml" <<'EOF'
<configuration>
  <property>
    <name>mapred.output.committer.class</name>
    <value>org.apache.hadoop.mapred.DirectFileOutputCommitter</value>
  </property>
</configuration>
EOF
cat > "$EMR_FX/cx-lookalike/mapred-site.xml" <<'EOF'
<configuration>
  <property>
    <name>mapredXoutput.committer.class</name>
    <value>org.apache.hadoop.mapred.DirectFileOutputCommitter</value>
  </property>
  <property>
    <name>mapred.output.committer.class.extra</name>
    <value>org.apache.hadoop.mapred.DirectFileOutputCommitter</value>
  </property>
</configuration>
EOF
run bash "$CODE_DIR/conf-xml-child.sh" "$EMR_DIR/lib-emr.sh" "$EMR_FX/cx-present"
case_begin "14.emr.conf-xml-value.reads-the-old-api-committer-class-from-mapred-site-xml"
mr_ok
a_out_re '^V=org\.apache\.hadoop\.mapred\.DirectFileOutputCommitter$'
case_end
run bash "$CODE_DIR/conf-xml-child.sh" "$EMR_DIR/lib-emr.sh" "$EMR_FX/cx-lookalike"
case_begin "14.emr.conf-xml-value.a-lookalike-key-is-not-the-committer-key-and-gives-unset"
mr_ok
a_out_re '^V=unset$'
case_end
run bash "$CODE_DIR/conf-xml-child.sh" "$EMR_DIR/lib-emr.sh" "$EMR_FX/cx-empty"
case_begin "14.emr.conf-xml-value.no-site-files-gives-unset"
mr_ok
a_out_re '^V=unset$'
case_end

# Static: scripts/emr/*.sh and scripts/README.md as text (items b, c and d).
# SC2016: the single-quoted strings are literal fragments of node scripts and of the README that
# f_has, f_before and f_count_is search for as text; the $ and the backticks must not expand.
# shellcheck disable=SC2016
static_wo61_cases() {
  local f n c tok rd="$REAL_ROOT/scripts/README.md" para="$CODE_DIR/readme-wo60-paragraph.txt"
  local sl kl ol hl stages_between

  # b: no code line of any scripts/emr/*.sh names an EMR-only class or file system, or the LZO
  # codec package. Read in full: no code line names any of the four, so the allowed set is empty.
  # The existing codec override lines (SC_CODECS in lib-emr.sh, its use in write_spark_conf and the
  # -Dio.compression.codecs option of run-s-pipeline.sh) name only org.apache.hadoop.io.compress
  # classes. com.hadoop.compression appears in comments only (lib-emr.sh and run-s-pipeline.sh), and
  # the only com.amazon-like text is java-<n>-amazon-corretto-devel in install-java-bootstrap.sh,
  # which is not com.amazon. Comment-only lines are excluded as code_of defines them.
  for f in "$EMR_DIR"/*.sh; do
    [ -f "$f" ] || continue
    n="${f##*/}"
    c="$(code_of "$f")"
    static_begin "14.emr.$n.no-emr-only-class-on-any-code-line"
    if [ "$n" = "rehearse-preflight.sh" ]; then
      # The local rehearsal (never uploaded, never run on a node) writes minimal fake node site
      # files shaped like the EMR findings. Its EMR-only names are data of one helper: they may
      # appear only as the value argument of an xml_prop call, never in any other code line.
      # Work order 79: the scenario nooverride (infra work order 78) must recognise the EMR committer in the
      # evaluate result, so two more code lines name DirectFileOutputCommitter: the verdict grep of the
      # no-override branch and the REASON text of that branch (the why= line). Nothing else may. These two
      # are the whole allowance and each is counted once, so a third name is still refused.
      tok="$(grep -E 'DirectFileOutputCommitter|EmrFileSystem|com\.amazon|com\.hadoop\.compression' "$c" | grep -vE '^[[:space:]]*xml_prop ' | grep -vF -e ".*DirectFileOutputCommitter' \"\$result\"" -e 'why="expected a failed preflight with ')"
      [ -z "$tok" ] || note_fail "an EMR-only name is on a code line that is not an xml_prop call or the nooverride verdict: $tok"
      [ "$(grep -cF -- ".*DirectFileOutputCommitter' \"\$result\"" "$c")" = "1" ] || note_fail "the nooverride verdict grep that names DirectFileOutputCommitter is not on exactly one code line"
      [ "$(grep -cF -- 'why="expected a failed preflight with ' "$c")" = "1" ] || note_fail "the nooverride REASON line is not on exactly one code line"
      [ "$(awk '/DirectFileOutputCommitter|EmrFileSystem|com\.amazon|com\.hadoop\.compression/ { k++ } END { print k + 0 }' "$c")" -ge 1 ] || note_fail "no EMR-only name found: the fake node file shape changed, review this case"
      # Work order 83 (review advisory A-i): the two allowed lines may name DirectFileOutputCommitter and
      # nothing else EMR-only. The first filter above lets any line that holds the verdict-grep text or
      # the why= text through, so a file system or a codec package on those same lines would pass it.
      tok="$(grep -F -e ".*DirectFileOutputCommitter' \"\$result\"" -e 'why="expected a failed preflight with ' "$c" | grep -E 'EmrFileSystem|com\.amazon|com\.hadoop\.compression' || true)"
      [ -z "$tok" ] || note_fail "an allowed nooverride line names more than DirectFileOutputCommitter: $tok"
      [ "$(grep -F -e ".*DirectFileOutputCommitter' \"\$result\"" -e 'why="expected a failed preflight with ' "$c" | grep -o 'DirectFileOutputCommitter' | wc -l | tr -d ' ')" = "2" ] || note_fail "the two allowed lines do not name DirectFileOutputCommitter exactly once each"
    else
      for tok in DirectFileOutputCommitter EmrFileSystem com.amazon com.hadoop.compression; do
        f_has_not "$c" "$tok"
      done
    fi
    case_end
  done

  # Work order 71: the local preflight rehearsal (text only; the harness never runs it).
  static_begin "14.emr.rehearse-preflight.refuses-bash-3-and-a-root-in-the-repo-and-reaches-no-aws"
  f="$EMR_DIR/rehearse-preflight.sh"
  if [ -f "$f" ]; then
    c="$(code_of "$f")"
    f_has "$f" '#!/usr/bin/env bash'
    f_has "$f" 'if [ -z "${BASH_VERSINFO:-}" ] || [ "${BASH_VERSINFO[0]}" -lt 4 ]; then'
    f_has "$f" 'REFUSED: this is bash %s.'
    f_has "$f" 'does not re-exec itself into a shell it has not been given.'
    f_before "$f" '"${BASH_VERSINFO[0]}" -lt 4' 'set -euo pipefail'
    f_before "$f" 'set -euo pipefail' 'source "$LIB"'
    f_before "$f" '"${BASH_VERSINFO[0]}" -lt 4' 'source "$LIB"'
    f_has "$f" '"$REPO"/*) refuse "the rehearsal root $ROOT is inside the repository $REPO; choose a directory outside it" ;;'
    f_before "$f" '"$REPO"/*) refuse' 'mkdir -p "$RUN"'
    f_has "$f" 'export AWS_EC2_METADATA_DISABLED=true'
    f_has_not_re "$c" '(^|[[:space:];|&(`])(aws|terraform|curl|wget|ssh|scp|sudo|rsync)[[:space:]]'
    f_has_not "$c" '$HOME'
    # shellcheck disable=SC2088 # literal needle: the script must not contain the text "~/", not an expansion
    f_has_not "$c" '~/'
    f_has_not "$c" '.aws/'
    f_has_not "$c" 's3://'
    # Work order 79: infra work order 78 removes AWS_CONFIG_FILE, AWS_SHARED_CREDENTIALS_FILE,
    # AWS_WEB_IDENTITY_TOKEN_FILE and AWS_ROLE_ARN from the spark-shell environment, so their names are
    # now on one code line (the env -u line). They must never be assigned or expanded; the
    # work order 79 case "unsets-the-aws-file-and-role-variables" pins the -u form and the count.
    f_has_not_re "$c" 'AWS_(CONFIG_FILE|SHARED_CREDENTIALS_FILE|WEB_IDENTITY_TOKEN_FILE|ROLE_ARN)='
    f_has_not_re "$c" '[$][{]?AWS_'
    f_has "$f" '-u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  static_begin "14.emr.lib-emr.the-codec-and-committer-lines-name-only-hadoop-core-classes"
  f_has "$emr_lib" 'SC_CODECS="org.apache.hadoop.io.compress.DefaultCodec,org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.BZip2Codec"'
  f_has "$emr_lib" "printf 'spark.hadoop.mapred.output.committer.class %s\\n' \"org.apache.hadoop.mapred.FileOutputCommitter\""
  f_count_is "$emr_lib" 'org.apache.hadoop.mapred.FileOutputCommitter' 1
  f_count_is "$emr_lib" 'spark.hadoop.mapred.output.committer.class' 1
  case_end

  # c: the evidence line sits in the spark-stage stage, after the stage_spark call and before that
  # stage's own stage_ok (the first stage_ok of the file is the validate-arguments one, so the
  # stage_ok is searched for after the stage_spark line), and before the next stage starts.
  static_begin "14.emr.run-s-pipeline.node-committer-class-is-recorded-in-the-spark-stage-after-stage-spark-before-its-stage-ok"
  f_has "$emr_run" 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS "$(conf_xml_value mapred.output.committer.class)"'
  f_count_is "$emr_run" 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS' 1
  f_count_is "$emr_run" 'mapred.output.committer.class' 1
  f_before "$emr_run" 'stage spark-stage' 'stage_spark "$artifacts_bucket"'
  f_before "$emr_run" 'stage_spark "$artifacts_bucket"' 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS'
  f_before "$emr_run" 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS' 'stage hadoop'
  sl="$(line_of "$emr_run" 'stage_spark "$artifacts_bucket"')"
  kl="$(line_of "$emr_run" 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS')"
  hl="$(line_of "$emr_run" 'stage hadoop')"
  if [ -z "$sl" ] || [ -z "$kl" ] || [ -z "$hl" ]; then
    note_fail "a line was not found (stage_spark $sl, kv $kl, stage hadoop $hl)"
  else
    ol="$(awk -v s="$sl" 'NR > s && $0 ~ /^[[:space:]]*stage_ok[[:space:]]*$/ { print NR; exit }' "$emr_run")"
    if [ -z "$ol" ]; then
      note_fail "no stage_ok after the stage_spark call"
    else
      [ "$kl" -lt "$ol" ] || note_fail "the kv line (line $kl) is not before the stage's first stage_ok (line $ol)"
      [ "$ol" -lt "$hl" ] || note_fail "the spark-stage stage_ok (line $ol) is not before the hadoop stage (line $hl)"
      stages_between="$(awk -v a="$sl" -v b="$ol" 'NR > a && NR < b && $0 ~ /^[[:space:]]*stage[[:space:]]/ { n++ } END { print n + 0 }' "$emr_run")"
      [ "$stages_between" = "0" ] || note_fail "another stage starts between stage_spark and its stage_ok"
    fi
  fi
  case_end

  # d: the README paragraph. It is one line that starts with the heading text.
  awk 'index($0, "Export output committer (work order 60") == 1' "$rd" > "$para"

  static_begin "14.emr.readme-export-committer-paragraph-states-the-failed-stage-the-cause-and-the-fix"
  [ "$(wc -l < "$para" | tr -d ' ')" = "1" ] || note_fail "scripts/README.md does not hold exactly one 'Export output committer (work order 60' paragraph"
  f_before "$rd" 'Export output committer (work order 60' '### S3 key layout'
  f_has "$para" 'then failed at stage 8, `export`, after 35 s (`FAILED_STAGE=8 export`, `FAILED_EXIT_CODE=1`, `EXPORT_ON_YARN=client:no`)'
  f_has "$para" 'Nothing was published, because `publish` comes after `export`'
  f_has "$para" 'ClassNotFoundException: Class org.apache.hadoop.mapred.DirectFileOutputCommitter not found'
  f_has "$para" 'the old mapred API, which builds its output committer from `mapred.output.committer.class`'
  f_has "$para" 'our Spark 4.0.5 jars (the same set as the archive) do not carry it'
  f_has "$para" '`write_spark_conf` now adds `spark.hadoop.mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitter` to the one `spark-defaults.conf`'
  f_has "$para" "the value is Hadoop's own default for the key"
  f_has "$para" 'so it applies to CollaborationPipeline, JsonlExport and the capability check alike'
  case_end

  static_begin "14.emr.readme-export-committer-paragraph-does-not-claim-the-node-value-was-observed"
  f_has "$para" "The node's \`mapred-site.xml\` was not read"
  f_has "$para" 'so that the file sets the key is inferred, not observed'
  f_has "$para" 'The stage records `NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS`'
  f_has "$para" 'so the next run shows what the node names'
  f_has "$para" 'Whether the override clears the export is UNVERIFIED until S run 6 records `EXPORT_ON_YARN=client:yes`.'
  f_has "$para" 'Export output committer (work order 60, S run 5; written, not run on a node)'
  f_has "$para" 'The human reported (not an agent run) that `bash -n` on `scripts/emr/lib-emr.sh`, `scripts/emr/run-s-pipeline.sh` and `tests/scripts/run.sh` and `shellcheck -x` on all three exit 0 (log phase14-static-checks-20.log), and that the harness ended `RESULT pass=1084 fail=0` with `SKIPPED_CASES 0` (log phase14-harness-10.log).'
  f_has "$para" "Nothing about the node, Spark's application of the key, or the class's presence in the 4.0.5 jars has been observed."
  f_has_not "$para" 'pending the human'
  f_has_not "$para" 'the Spark 4 archive does not carry it'
  f_has_not "$para" 'the Spark 4.0.5 archive does not carry it'
  f_has_not_re "$para" "node.s (value|key) (is|was) (observed|seen)"
  f_has_not_re "$para" 'observed (on|in|from) the node'
  f_has_not_re "$para" '(fix|change|committer line) (is|was) (verified|confirmed)'
  case_end
}
static_wo61_cases

### Work order 65: the on-node preflight (follows infra-engineer work order 64, S run 5) -----
# run_preflight (lib-emr.sh) runs inside the spark-stage stage of run-s-pipeline.sh: one
# spark-shell on local[2] that loads every class-valued configuration value and the four job
# main classes and does one old-API and one Parquet write. Nothing here runs Spark: the real
# lib-emr.sh is read as text, or sourced in a child shell (a bash program under $CODE_DIR) with
# a fake spark-shell, a fake hadoop and a fake timeout script first on the child's PATH, because
# env execs timeout and a shell function cannot stand in for it. The fake timeout logs its
# arguments, never waits and exits 124 or 137 on request; the fake spark-shell logs its
# arguments, its environment and its directory and copies a prepared result file to
# SC_PREFLIGHT_RESULT. No stub on PATH is reached, so the tripwire stays empty. What a real
# timeout does at the KILL, whether the Scala script compiles, and what a node answers, stay
# unobserved: the cases below read the shell around the Scala text, not the Scala's behaviour.
# No case of work orders 50 to 61 was loosened; one count was updated (the k-30 case above).

EMR_RUN_RAW="$EMR_DIR/run-s-pipeline.sh"
PF_SCALA="$CODE_DIR/preflight-scala.txt"
PF_RUN_FN="$CODE_DIR/preflight-run-fn.sh"
PF_SS_FN="$CODE_DIR/preflight-ss-fn.sh"
PF_TG_FN="$CODE_DIR/preflight-targets-fn.sh"
PF_EV_FN="$CODE_DIR/preflight-evaluate-fn.sh"
PF_README_SEC="$CODE_DIR/readme-wo64-section.txt"
# The here-document of preflight_write_script, from the line after its opener to the terminator.
awk '/<<.SC_PREFLIGHT_SCALA.$/ { f = 1; next } $0 == "SC_PREFLIGHT_SCALA" { f = 0 } f { print }' "$emr_lib" > "$PF_SCALA"
extract_fn "$emr_lib" run_preflight "$PF_RUN_FN"
extract_fn "$emr_lib" run_spark_submit "$PF_SS_FN"
extract_fn "$emr_lib" preflight_write_targets "$PF_TG_FN"
extract_fn "$emr_lib" preflight_evaluate "$PF_EV_FN"
# The README section: from its heading up to, not including, the next ### heading.
awk 'index($0, "### On-node preflight (work order 64") == 1 { f = 1; print; next } f && index($0, "### ") == 1 { exit } f { print }' "$REAL_ROOT/scripts/README.md" > "$PF_README_SEC"

# commas N: a whole number up to 999999 as the README writes it (1,440).
commas() {
  if [ "$1" -ge 1000 ]; then
    printf '%s,%03d' "$(($1 / 1000))" "$(($1 % 1000))"
  else
    printf '%s' "$1"
  fi
}

# SC2016: the single-quoted strings are literal fragments of node scripts, of the Scala text and
# of the README that f_has, f_has_not, f_before and f_count_is search for as text; the $ and the
# backticks must not expand.
# shellcheck disable=SC2016
static_wo65_cases() {
  local rd="$REAL_ROOT/scripts/README.md" k val needle fn envs tg
  local sl wl kl pl ol hl names n between lim grace spark_t rows total ra lo hi spare floor
  local pre_spark=874

  lim="$(sed -n 's/^SC_PREFLIGHT_LIMIT_SECONDS=\([0-9][0-9]*\)$/\1/p' "$emr_lib" | head -n 1)"
  floor="$(sed -n 's/^SC_PREFLIGHT_FLOOR_SECONDS=\([0-9][0-9]*\)$/\1/p' "$emr_lib" | head -n 1)"
  grace="$(sed -n 's/.*timeout -k \([0-9][0-9]*\) "\$remaining".*/\1/p' "$emr_lib" | head -n 1)"
  spark_t="$(sed -n 's/.*SC_SPARK_TIMEOUT:-\([0-9][0-9]*\)}.*/\1/p' "$emr_lib" | head -n 1)"
  rows="$(sed -n 's/^SC_PREFLIGHT_ROWS=\([0-9][0-9]*\)$/\1/p' "$emr_lib" | head -n 1)"

  static_begin "14.emr.preflight.runs-inside-spark-stage-and-adds-no-stage"
  f_count_is "$emr_run" 'run_preflight' 1
  f_has "$emr_run" 'run_preflight "$work" "$rep"'
  f_count_is "$emr_lib" 'run_preflight' 1
  f_has "$EMR_RUN_RAW" 'sub-step of the spark-stage stage'
  sl="$(line_of "$emr_run" 'stage spark-stage')"
  wl="$(line_of "$emr_run" 'write_spark_conf "')"
  kl="$(line_of "$emr_run" 'kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS')"
  pl="$(line_of "$emr_run" 'run_preflight "$work" "$rep"')"
  hl="$(line_of "$emr_run" 'stage hadoop')"
  if [ -z "$sl" ] || [ -z "$wl" ] || [ -z "$kl" ] || [ -z "$pl" ] || [ -z "$hl" ]; then
    note_fail "a line was not found (stage spark-stage $sl, write_spark_conf $wl, kv $kl, run_preflight $pl, stage hadoop $hl)"
  else
    ol="$(awk -v s="$pl" 'NR > s && $0 ~ /^[[:space:]]*stage_ok[[:space:]]*$/ { print NR; exit }' "$emr_run")"
    if [ -z "$ol" ]; then
      note_fail "no stage_ok after the run_preflight call"
    else
      [ "$sl" -lt "$wl" ] || note_fail "write_spark_conf (line $wl) is not after stage spark-stage (line $sl)"
      [ "$wl" -lt "$pl" ] || note_fail "run_preflight (line $pl) is not after write_spark_conf (line $wl)"
      [ "$kl" -lt "$pl" ] || note_fail "run_preflight (line $pl) is not after the NODE_CONF record (line $kl)"
      [ "$pl" -lt "$ol" ] || note_fail "the stage_ok (line $ol) is not after run_preflight (line $pl)"
      [ "$ol" -lt "$hl" ] || note_fail "the stage_ok (line $ol) is not before stage hadoop (line $hl)"
      between="$(awk -v a="$sl" -v b="$ol" 'NR > a && NR < b && $0 ~ /^[[:space:]]*stage[[:space:]]/ { n++ } END { print n + 0 }' "$emr_run")"
      [ "$between" = "0" ] || note_fail "another stage starts between stage spark-stage and its stage_ok"
    fi
  fi
  names="$(awk '$1 == "stage" { printf "%s%s", (c++ ? " " : ""), $2 }' "$emr_run")"
  [ "$names" = "validate-arguments java fetch-inputs spark-stage hadoop entity-resolution spark export publish" ] || note_fail "the stage list changed: $names"
  n="$(awk '$1 == "stage" { c++; if ($2 == "export") { print c; exit } }' "$emr_run")"
  [ "$n" = "8" ] || note_fail "the export is stage '$n', not 8 (FAILED_STAGE=8 export must keep its meaning)"
  n="$(awk '$1 == "stage" { c++; if ($2 == "spark-stage") { print c; exit } }' "$emr_run")"
  [ "$n" = "4" ] || note_fail "spark-stage is stage '$n', not 4 (a preflight failure reads FAILED_STAGE=4 spark-stage)"
  case_end

  static_begin "14.emr.preflight.limit-and-kill-grace-total-at-most-90"
  if tf_is_num "$lim" && tf_is_num "$grace"; then
    [ "$lim" -le 60 ] || note_fail "SC_PREFLIGHT_LIMIT_SECONDS is $lim, above 60"
    [ "$lim" -ge 10 ] || note_fail "SC_PREFLIGHT_LIMIT_SECONDS is $lim, below the 10 s the JVM start needs"
    [ $((lim + grace)) -le 90 ] || note_fail "the limit $lim plus the kill grace $grace is above 90 s"
  else
    note_fail "the limit '$lim' or the kill grace '$grace' could not be read"
  fi
  f_count_is "$emr_lib" 'timeout -k 30 "$remaining"' 1
  f_count_is "$emr_lib" 'timeout -k 30 ' 2
  f_count_is "$emr_lib" '${SC_PREFLIGHT_TIMEOUT' 1
  f_has "$emr_lib" 'limit="${SC_PREFLIGHT_TIMEOUT:-$SC_PREFLIGHT_LIMIT_SECONDS}"'
  # Work order 67 (F2, F3): the floor joins the validation, and the class path command has a KILL grace.
  if tf_is_num "$floor"; then
    [ "$floor" -ge 10 ] || note_fail "SC_PREFLIGHT_FLOOR_SECONDS is $floor, below the 10 s the JVM start needs"
    if tf_is_num "$lim"; then [ "$floor" -le "$lim" ] || note_fail "the floor $floor is above the limit $lim"; fi
  else
    note_fail "SC_PREFLIGHT_FLOOR_SECONDS could not be read: '$floor'"
  fi
  f_has "$emr_lib" '[[ "$limit" =~ ^[1-9][0-9]?$ && "$limit" -ge "$SC_PREFLIGHT_FLOOR_SECONDS" && "$limit" -le "$SC_PREFLIGHT_LIMIT_SECONDS" ]]'
  f_has "$emr_lib" 'SC_PREFLIGHT_TIMEOUT must be a whole number from $SC_PREFLIGHT_FLOOR_SECONDS to $SC_PREFLIGHT_LIMIT_SECONDS with no leading zero'
  f_has_not "$emr_lib" 'SC_PREFLIGHT_TIMEOUT must be a whole number from 1 to'
  f_has "$emr_lib" 'remaining=$((limit - (SECONDS - t0)))'
  f_has "$emr_lib" '[[ "$remaining" -ge 10 ]]'
  f_has "$emr_lib" 'timeout -k 5 20 hadoop classpath --glob'
  f_has_not "$emr_lib" 'timeout 20 hadoop'
  f_before "$PF_RUN_FN" 'SC_PREFLIGHT_LIMIT_SECONDS" ]]' 'mkdir -p "$pf/data"'
  f_before "$PF_RUN_FN" 'mkdir -p "$pf/data"' 'remaining=$((limit'
  f_before "$PF_RUN_FN" 'preflight_write_targets "$pf/targets.tsv" "$work"' 'remaining=$((limit'
  case_end

  static_begin "14.emr.preflight.main-classes-match-the-run-script"
  for k in HADOOP ER SPARK EXPORT; do
    val="$(sed -n 's/^SC_PREFLIGHT_MAIN_'"$k"'="\(.*\)"$/\1/p' "$emr_lib" | head -n 1)"
    case "$k" in
      HADOOP) needle='hadoop jar "$hjar" '"$val" ;;
      ER) needle='-cp "$ejar:$elibs/*" '"$val" ;;
      SPARK) needle='run_spark_submit "$rep/spark.log" "$mode" '"$val" ;;
      *) needle='run_spark_submit "$rep/export.log" "$mode" '"$val" ;;
    esac
    if printf '%s' "$val" | grep -qE '^scholarchain(\.[A-Za-z0-9_]+)+$'; then
      f_has "$emr_run" "$needle"
      f_count_is "$emr_run" "$val" 1
      f_count_is "$emr_lib" "$val" 1
    else
      note_fail "SC_PREFLIGHT_MAIN_$k is '$val', not a scholarchain class name"
    fi
  done
  case_end

  static_begin "14.emr.preflight.exclusion-constants-are-what-the-stages-use"
  f_has "$emr_lib" 'SC_HADOOP_LIB_EXCLUDE="^hadoop-"'
  f_has "$emr_lib" 'SC_SPARK_LIB_EXCLUDE="^(spark-|hadoop-)"'
  f_count_is "$emr_lib" 'SC_HADOOP_LIB_EXCLUDE=' 1
  f_count_is "$emr_lib" 'SC_SPARK_LIB_EXCLUDE=' 1
  f_has "$emr_run" 'hexcl="$SC_HADOOP_LIB_EXCLUDE"'
  f_has "$emr_run" 'sexcl="$SC_SPARK_LIB_EXCLUDE"'
  f_has "$emr_run" 'lib_jars_csv "$work/jars/hadoop/lib" "$hexcl"'
  f_has "$emr_run" 'lib_jars_excluded "$work/jars/hadoop/lib" "$hexcl"'
  f_has "$emr_run" 'lib_jars_csv "$work/jars/spark/lib" "$sexcl"'
  f_has "$emr_run" 'lib_jars_excluded "$work/jars/spark/lib" "$sexcl"'
  f_has_not "$emr_run" '^hadoop-'
  f_has_not "$emr_run" '^(spark-'
  f_has "$PF_TG_FN" 'lib_jars_csv "$work/jars/hadoop/lib" "$SC_HADOOP_LIB_EXCLUDE"'
  f_has "$PF_TG_FN" 'lib_jars_csv "$work/jars/spark/lib" "$SC_SPARK_LIB_EXCLUDE"'
  f_has "$PF_TG_FN" "lib_jars_csv \"\$work/jars/entity-resolution/lib\" '^\$'"
  f_has "$PF_TG_FN" 'lib_jars_csv "$SPARK_HOME/jars" '"'^\$'"
  f_has_not "$PF_TG_FN" '^hadoop-'
  f_has_not "$PF_TG_FN" '^(spark-'
  case_end

  static_begin "14.emr.preflight.env-matches-run-spark-submit"
  for k in 'env -u HADOOP_CLASSPATH -u SPARK_DIST_CLASSPATH -u SPARK_EXTRA_CLASSPATH' 'SPARK_HOME="$SPARK_HOME" SPARK_CONF_DIR="$SC_SPARK_CONF_DIR"' 'HADOOP_CONF_DIR=/etc/hadoop/conf YARN_CONF_DIR=/etc/hadoop/conf' 'JAVA_HOME="$SC_JAVA_HOME"'; do
    f_has "$PF_SS_FN" "$k"
    f_has "$PF_RUN_FN" "$k"
  done
  f_has_not "$PF_RUN_FN" 'HADOOP_CLASSPATH='
  f_has_not "$PF_RUN_FN" 'SPARK_DIST_CLASSPATH='
  f_has_not "$PF_RUN_FN" 'SPARK_EXTRA_CLASSPATH='
  f_has "$PF_RUN_FN" '--master "local[2]"'
  # Work order 75: no -i; the script is the here-string ':load <script>' on stdin.
  f_has "$PF_RUN_FN" '--conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"'
  f_has_not "$PF_RUN_FN" '-i "$scala"'
  f_has_not "$PF_RUN_FN" '--master yarn'
  f_has_not "$PF_RUN_FN" 'spark-submit'
  f_has "$PF_RUN_FN" 'cd "$pf" && env -u HADOOP_CLASSPATH'
  case_end

  static_begin "14.emr.preflight.script-keeps-its-controls"
  f_has "$emr_lib" "<<'SC_PREFLIGHT_SCALA'"
  if [ "$(wc -l < "$PF_SCALA" | tr -d ' ')" -lt 100 ]; then note_fail "the Scala here-document cut out of lib-emr.sh has fewer than 100 lines"; fi
  for k in 'emit("CONTROL positive ' 'emit("CONTROL negative ' 'emit("DONE")' 'Class.forName(name, false' 'getMethod("main"' 'Modifier.isStatic' \
    'loads("org.apache.hadoop.fs.FileSystem", sparkLoader).isEmpty' 'loads("scholarchain.preflight.NoSuchClassForTheControl", sparkLoader).isDefined' \
    'new URLClassLoader(urls, ClassLoader.getPlatformClassLoader)' 'if (own) None else exemptReason(key)' \
    'private val usedSchemes = Set("file", "hdfs", "viewfs")' 'emit("SITE_FILES ' 'emit("OVERLAY ' 'emit("OVERRIDDEN ' 'emit("EXEMPT ' \
    'emit("FAIL config ' 'emit("FAIL target ' 'emit("ERROR ' 'emit("TARGET ' 'emit("SUMMARY keys=' 'saveAsTextFile(oldUri)' 'write.parquet(pqUri)' \
    'PreflightRun.run()' 'sys.exit(0)'; do
    f_has "$PF_SCALA" "$k"
  done
  f_count_is "$PF_SCALA" 'emit("DONE")' 1
  f_count_is "$PF_SCALA" 'Class.forName(' 2
  f_count_is "$PF_SCALA" ', false, loader)' 2
  f_has_not "$PF_SCALA" 'Class.forName(name)'
  f_has_not "$PF_SCALA" 'Class.forName(name, true'
  f_has_not "$PF_SCALA" 'Class.forName(f(1))'
  f_has_not "$PF_SCALA" 'Class.forName(f(1), true'
  # f_before reads the first line that holds a needle, and in the Scala text 'checkTargets()' and
  # 'smoke()' first occur in their own definitions ("private def checkTargets(): Unit"), which
  # sit above run(). The order is therefore read at the call sites, with needles a definition
  # line cannot hold: "try { " opens only the two guarded calls in run(), and the smoke call is
  # the one line indented six spaces (every definition is indented two after "private def").
  # Each needle must be on exactly one line, so the first match is that call and nothing else.
  f_count_is "$PF_SCALA" 'try { checkConfig(sparkLoader) }' 1
  f_count_is "$PF_SCALA" 'try { checkTargets() }' 1
  f_count_is "$PF_SCALA" '      smoke()' 1
  f_count_is "$PF_SCALA" '    finally {' 1
  f_has_not "$PF_SCALA" 'def checkConfig(sparkLoader'
  f_has "$PF_SCALA" 'private def checkTargets(): Unit'
  f_has "$PF_SCALA" 'private def smoke(): Unit'
  f_before "$PF_SCALA" 'emit("CONTROL positive ' 'try { checkConfig(sparkLoader) }'
  f_before "$PF_SCALA" 'emit("CONTROL negative ' 'try { checkConfig(sparkLoader) }'
  f_before "$PF_SCALA" 'try { checkConfig(sparkLoader) }' 'try { checkTargets() }'
  f_before "$PF_SCALA" 'try { checkTargets() }' '      smoke()'
  f_before "$PF_SCALA" '      smoke()' 'emit("SUMMARY keys='
  f_before "$PF_SCALA" 'emit("SUMMARY keys=' '    finally {'
  f_before "$PF_SCALA" '    finally {' 'emit("DONE")'
  f_before "$PF_SCALA" 'emit("DONE")' 'PreflightRun.run()'
  f_before "$PF_SCALA" 'private def checkTargets(): Unit' 'private def smoke(): Unit'
  f_before "$PF_SCALA" 'private def smoke(): Unit' 'def run(): Unit'
  case_end

  static_begin "14.emr.preflight.script-reads-only-its-own-environment-and-holds-no-secret-pattern-and-no-s3-uri"
  envs="$(grep -oE 'sys\.env(\.getOrElse)?\("[A-Z_]+"' "$PF_SCALA" | sed 's/.*("\([A-Z_]*\)"/\1/' | sort -u | tr '\n' ' ')"
  [ "$envs" = "HADOOP_CONF_DIR SC_PREFLIGHT_DATA SC_PREFLIGHT_DIR SC_PREFLIGHT_RESULT SC_PREFLIGHT_ROWS " ] || note_fail "the Scala reads these environment variables: $envs"
  n="$(grep -c 'sys\.env' "$PF_SCALA")"
  k="$(grep -cE 'sys\.env(\.getOrElse)?\("[A-Z_]+"' "$PF_SCALA")"
  [ "$n" = "$k" ] || note_fail "$n line(s) use sys.env, $k of them in the one-variable forms: the environment may be read whole"
  f_has_not "$PF_SCALA" 'System.getenv'
  f_has_not "$PF_SCALA" 'sys.props'
  f_has_not_re "$PF_SCALA" "$EMR_KEY_RE"
  f_has_not_re "$PF_SCALA" 'AWS_|[Ss]ecret|[Pp]assword|[Cc]redential'
  for k in 's3://' 's3a://' 's3n://' 'hdfs://'; do
    f_has_not "$PF_SCALA" "$k"
    f_has_not "$PF_RUN_FN" "$k"
    f_has_not "$PF_TG_FN" "$k"
    f_has_not "$PF_EV_FN" "$k"
  done
  case_end

  static_begin "14.emr.preflight.script-writes-only-the-result-and-the-smoke-data"
  f_count_is "$PF_SCALA" 'new PrintWriter(' 1
  f_count_is "$PF_SCALA" 'Files.newBufferedWriter(' 1
  f_count_is "$PF_SCALA" 'saveAsTextFile(' 1
  f_count_is "$PF_SCALA" '.write.parquet(' 1
  f_has "$PF_SCALA" 'Files.newBufferedWriter(Paths.get(sys.env("SC_PREFLIGHT_RESULT")))'
  f_has "$PF_SCALA" 'private val data = sys.env("SC_PREFLIGHT_DATA")'
  f_has "$PF_SCALA" 'val base = data.stripSuffix("/")'
  f_has "$PF_SCALA" 'val oldUri = "file://" + base + "/old"'
  f_has "$PF_SCALA" 'val pqUri = "file://" + base + "/pq"'
  for k in 'Files.write(' 'Files.delete' 'Files.createFile' 'Files.copy' 'Files.move' 'FileWriter' 'FileOutputStream' 'FileUtils' 'ProcessBuilder' 'Runtime.getRuntime' 'sys.process' '.delete(' 'deleteIfExists' 'saveAsHadoopFile' 'saveAsObjectFile'; do
    f_has_not "$PF_SCALA" "$k"
  done
  case_end

  static_begin "14.emr.preflight.shell-functions-write-only-under-work-preflight-and-reports"
  f_has "$PF_RUN_FN" 'pf="$1/preflight"'
  f_has "$PF_RUN_FN" 'scala="$rep/preflight.scala" result="$rep/preflight-result.txt" plog="$rep/preflight.log"'
  f_has "$PF_RUN_FN" 'mkdir -p "$pf/data"'
  f_has "$PF_RUN_FN" 'rm -rf "${pf:?}/data"'
  f_count_is "$PF_RUN_FN" 'mkdir' 1
  f_count_is "$PF_RUN_FN" 'rm -' 1
  f_has "$PF_RUN_FN" 'preflight_write_script "$scala"'
  f_has "$PF_RUN_FN" 'preflight_write_targets "$pf/targets.tsv" "$work"'
  f_has "$PF_RUN_FN" 'SC_PREFLIGHT_DIR="$pf" SC_PREFLIGHT_DATA="$pf/data" SC_PREFLIGHT_RESULT="$result" SC_PREFLIGHT_ROWS="$SC_PREFLIGHT_ROWS"'
  tg="$(grep -oE '>[[:space:]]*"[^"]*"' "$PF_RUN_FN" | tr '\n' ' ')"
  [ "$tg" = '> "$plog" ' ] || note_fail "run_preflight redirects to: $tg"
  tg="$(grep -oE '>[[:space:]]*"[^"]*"' "$PF_TG_FN" | tr '\n' ' ')"
  [ "$tg" = '> "$file" ' ] || note_fail "preflight_write_targets redirects to: $tg"
  f_has "$emr_lib" 'cat > "$1" <<'"'SC_PREFLIGHT_SCALA'"
  f_count_is "$emr_lib" 'cat > "$1" <<' 1
  for fn in "$PF_RUN_FN" "$PF_TG_FN" "$PF_EV_FN"; do
    f_has_not_re "$fn" '(^|[[:space:];|&(])(cp|mv|tee|chmod|chown|sudo|curl|wget|aws|hdfs|yarn)[[:space:]]'
  done
  f_has_not "$PF_TG_FN" 'mkdir'
  f_has_not "$PF_EV_FN" 'mkdir'
  f_has_not "$PF_TG_FN" 'rm -'
  f_has_not "$PF_EV_FN" 'rm -'
  case_end

  static_begin "14.emr.preflight.evaluate-checks-in-order-and-never-reads-the-exit-status"
  f_has "$PF_EV_FN" '[[ -s "$result" ]] || die "preflight wrote no result'
  f_has "$PF_EV_FN" "grep -qx 'DONE' \"\$result\" || die \"preflight did not reach DONE"
  f_has "$PF_EV_FN" "fails=\"\$(grep -E '^(FAIL|ERROR) ' \"\$result\" || true)\""
  f_has "$PF_EV_FN" "grep -qx 'CONTROL positive OK' \"\$result\""
  f_has "$PF_EV_FN" "grep -qx 'CONTROL negative OK' \"\$result\""
  f_has "$PF_EV_FN" '[[ " $line " == *" core-site.xml "* ]]'
  f_has "$PF_EV_FN" 'grep -qxF -- "OVERLAY ${k#spark.hadoop.} $v" "$result"'
  f_has "$PF_EV_FN" '[[ "$overlay_n" -ge 1 ]]'
  f_has "$PF_EV_FN" 'for label in hadoop er spark export; do'
  f_has "$PF_EV_FN" 'grep -qx "TARGET $label OK" "$result"'
  f_has "$PF_EV_FN" 'for label in old-api parquet; do'
  f_has "$PF_EV_FN" 'grep -qx "SMOKE $label OK rows=$rows" "$result"'
  f_has "$PF_EV_FN" '[[ "${keys:-0}" -ge 1 && "${classes:-0}" -ge 1 ]]'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_RESULT failed'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_FAILED_COUNT "$nfail"'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_FIRST_FAILURE "$first"'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_RESULT ok'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_FIRST_FAILURE "$first"' 'die "preflight: $nfail check(s) failed'
  f_before "$PF_EV_FN" '[[ -s "$result" ]]' "grep -qx 'DONE'"
  f_before "$PF_EV_FN" "grep -qx 'DONE'" 'grep -E '"'^(FAIL|ERROR) '"
  f_before "$PF_EV_FN" 'grep -E '"'^(FAIL|ERROR) '" "grep -qx 'CONTROL positive OK'"
  f_before "$PF_EV_FN" "grep -qx 'CONTROL positive OK'" "grep -qx 'CONTROL negative OK'"
  f_before "$PF_EV_FN" "grep -qx 'CONTROL negative OK'" 'core-site.xml'
  f_before "$PF_EV_FN" 'core-site.xml' 'OVERLAY ${k#spark.hadoop.}'
  f_before "$PF_EV_FN" 'OVERLAY ${k#spark.hadoop.}' 'for label in hadoop er spark export; do'
  f_before "$PF_EV_FN" 'for label in hadoop er spark export; do' 'for label in old-api parquet; do'
  f_before "$PF_EV_FN" 'for label in old-api parquet; do' 'checked no class-valued key'
  f_before "$PF_EV_FN" 'checked no class-valued key' 'kv PREFLIGHT_RESULT ok'
  f_has_not_re "$PF_EV_FN" '"\$rc" -(eq|ne|gt|lt)'
  f_has_not_re "$PF_EV_FN" '"\$3" -(eq|ne|gt|lt)'
  case_end

  static_begin "14.emr.preflight.run-preflight-judges-only-the-timeout-statuses-and-removes-the-data-first"
  f_has "$PF_RUN_FN" ') > "$plog" 2>&1 || rc=$?'
  f_has_not "$PF_RUN_FN" '2>&1 < /dev/null || rc=$?'
  f_has_not "$PF_RUN_FN" '< /dev/null'
  f_has "$PF_RUN_FN" '[[ "$rc" -eq 124 || "$rc" -eq 137 ]]'
  f_has "$PF_RUN_FN" 'kv PREFLIGHT_RESULT "timed out (exit $rc, JVM limit $remaining s)"'
  f_has "$PF_RUN_FN" 'die "preflight timed out (exit $rc) after $((SECONDS - t0)) s'
  f_has "$PF_RUN_FN" 'preflight_evaluate "$result" "$plog" "$rc"'
  f_has "$PF_RUN_FN" 'kv PREFLIGHT_LIMIT_SECONDS "$limit"'
  f_has "$PF_RUN_FN" 'kv PREFLIGHT_SECONDS "$((SECONDS - t0))"'
  f_has_not_re "$PF_RUN_FN" '"\$rc" -(ne|gt|lt)'
  f_has_not_re "$PF_RUN_FN" '"\$rc" -eq [0-9]+ \]\] *\|\| *(die|exit)'
  f_before "$PF_RUN_FN" 'mkdir -p "$pf/data"' 'preflight_write_script "$scala"'
  f_before "$PF_RUN_FN" 'preflight_write_script "$scala"' 'preflight_write_targets'
  f_before "$PF_RUN_FN" 'preflight_write_targets' 'kv PREFLIGHT_CLASSPATH_ENTRIES'
  f_before "$PF_RUN_FN" 'rm -rf "${pf:?}/data"' '[[ "$rc" -eq 124'
  f_before "$PF_RUN_FN" '[[ "$rc" -eq 124' 'preflight_evaluate "$result"'
  f_before "$PF_RUN_FN" 'preflight_evaluate "$result"' 'kv PREFLIGHT_LIMIT_SECONDS'
  case_end

  static_begin "14.emr.capability-check.does-not-call-the-preflight"
  for k in 'run_preflight' 'preflight_write' 'preflight_evaluate' 'PREFLIGHT_' 'SC_PREFLIGHT'; do
    f_has_not "$emr_cap" "$k"
  done
  f_has "$emr_cap" 'write_spark_conf'
  case_end

  static_begin "14.terraform.preflight.fits-the-s-step-cap"
  tf_overlay "$TF_S"
  if tf_is_num "$lim" && tf_is_num "$grace" && tf_is_num "$spark_t" && tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
    total=$((lim + grace + pre_spark + spark_t))
    [ "$total" -lt "$TF_STEP" ] || note_fail "the preflight limit $lim + $grace + $pre_spark s before Spark + the Spark limit $spark_t = $total is not under the S step cap $TF_STEP"
    ra=$((TF_CREATE + TF_STEP + 600))
    [ "$ra" -le "$TF_MAX" ] || note_fail "Rule A $TF_CREATE + $TF_STEP + 600 = $ra exceeds the maximum $TF_MAX"
    [ "$ra" = "6840" ] || note_fail "Rule A for S is $ra, D32 says 6840"
    f_has "$TF_S" "$TF_CREATE + $TF_STEP + 600 = $ra <= $TF_MAX"
    f_has "$PF_README_SEC" "Rule A (create $(commas "$TF_CREATE") + step $(commas "$TF_STEP") + 600 = $(commas "$ra") <= $(commas "$TF_MAX"))"
    # Work order 92 (repair of work order 87, finding F6): the README sentence names the four S overlay
    # values, all read from the overlay, the loader included (5,400 since infra work order 86), and the
    # stale 1,800 is no longer accepted. The sentence then says which figure moved and why, so a reader
    # of the preflight section is not told the loader is unchanged.
    f_has "$PF_README_SEC" "the S overlay values ($(commas "$TF_MAX"), $(commas "$TF_CREATE"), $(commas "$TF_STEP"), $(commas "$TF_LOAD")) are unchanged by the preflight"
    f_has_not "$PF_README_SEC" "the S overlay values ($(commas "$TF_MAX"), $(commas "$TF_CREATE"), $(commas "$TF_STEP"), 1,800)"
    f_has_not_re "$PF_README_SEC" 'S overlay values \([0-9,]+, [0-9,]+, [0-9,]+, 1,800\)'
    f_has "$PF_README_SEC" "(the last is the S loader value, which work order 86 raised from 1,800 to $(commas "$TF_LOAD") s for mode \`loader-only\`; the other three did not change)"
    lo=$((3300 + lim + grace))
    hi=$((3900 + lim + grace))
    spare=$((TF_STEP - hi))
    f_has "$PF_README_SEC" "The step is expected at 3,300 to 3,900 s and is now $(commas "$lo") to $(commas "$hi") s at most, under the $(commas "$TF_STEP") s cap with $spare s to spare"
    [ "$hi" -lt "$TF_STEP" ] || note_fail "the step at its upper estimate plus the preflight, $hi, is not under the step cap $TF_STEP"
  else
    note_fail "a figure could not be read: limit '$lim', grace '$grace', Spark '$spark_t', overlay '$TF_MAX' '$TF_CREATE' '$TF_STEP' '$TF_LOAD'"
  fi
  case_end

  static_begin "14.emr.preflight.readme-section-exists-before-s3-key-layout-and-says-not-run-on-a-node"
  f_count_is "$rd" '### On-node preflight' 1
  # Work order 79: the heading now says what rehearsal 3 did (compiled and ran locally with stand-ins)
  # and still that nothing about a node is observed.
  f_has "$rd" '### On-node preflight (work order 64, S run 5; compiled and run locally in rehearsal 3 with stand-ins, not run on a node, nothing about a node is observed)'
  f_before "$rd" 'Export output committer (work order 60' '### On-node preflight (work order 64'
  f_before "$rd" '### On-node preflight (work order 64' '### S3 key layout'
  if [ "$(wc -l < "$PF_README_SEC" | tr -d ' ')" -lt 10 ]; then note_fail "the README section has fewer than 10 lines"; fi
  f_has "$PF_README_SEC" 'A preflight failure reads `FAILED_STAGE=4 spark-stage`'
  f_has "$PF_README_SEC" '`PREFLIGHT_RESULT=failed`'
  f_has "$PF_README_SEC" '`PREFLIGHT_FIRST_FAILURE=`'
  f_has "$PF_README_SEC" 'It is a sub-step, not a numbered stage: the stage numbers do not change, so the export stays stage 8 and `FAILED_STAGE=8 export` still means what it meant.'
  f_has "$PF_README_SEC" '`capability-check.sh` is not changed and does not call it'
  # Work order 75: the sentence 'The script has never been compiled by anything.' is gone from the
  # README (work order 74 rewrote the paragraph); the same claim is now 'nothing has compiled it yet'.
  f_has "$PF_README_SEC" 'nothing has compiled it yet'
  f_has "$PF_README_SEC" "SMOKE old-api OK rows=$rows"
  f_has "$PF_README_SEC" "SMOKE parquet OK rows=$rows"
  f_has "$PF_README_SEC" 'The exit status of `spark-shell` is not used'
  f_has "$PF_README_SEC" 'only 124 and 137 (the time limit) are read'
  f_has "$PF_README_SEC" '`STAGE_SECONDS_SPARK_STAGE` now includes the preflight.'
  for k in 'preflight.log' 'preflight-result.txt' 'preflight.scala'; do
    f_has "$PF_README_SEC" "\`$k\`"
  done
  f_has "$PF_README_SEC" 'The harness cannot reach any of it'
  case_end

  static_begin "14.emr.preflight.readme-claims-nothing-observed"
  # Work order 79: 'UNVERIFIED: no part of it has run.' became 'UNVERIFIED on a node: no part of it has run
  # there (the local rehearsal's seconds are a laptop's, not the node's)'.
  f_has "$PF_README_SEC" 'UNVERIFIED on a node: no part of it has run there'
  f_has_not "$PF_README_SEC" 'UNVERIFIED: no part of it has run.'
  f_has "$PF_README_SEC" 'This list is a judgement from Hadoop'"'"'s key names, not from the node'"'"'s files'
  f_has "$PF_README_SEC" 'that proves the Scala compiles and runs on the Scala shell, not that the node passes'
  f_has_not_re "$PF_README_SEC" '[Pp]reflight (passed|passes|was observed|was verified|has run|ran on)'
  f_has_not_re "$PF_README_SEC" '(is|was|were|are) (observed|verified|confirmed|measured) (on|in|from) (the|a|an|S run 6) (node|cluster|run)'
  f_has_not_re "$PF_README_SEC" 'observed (on|in|from) the node'
  f_has_not_re "$PF_README_SEC" '(measured|took|ran in) [0-9]+ s'
  f_has_not "$PF_README_SEC" 'pending the human'
  case_end

  static_begin "14.emr.preflight.readme-limit-table-row-and-figures-follow-the-code"
  if tf_is_num "$lim" && tf_is_num "$grace" && tf_is_num "$floor"; then
    f_has "$rd" '| On-node preflight (work order 64, see "On-node preflight") | `timeout -k '"$grace"' "$remaining"` with a '"$lim"' s limit shared with the class path build, so at most '"$((lim + grace))"' s with the KILL grace | `lib-emr.sh`, `run_preflight` | `SC_PREFLIGHT_TIMEOUT` ('"$floor"' to '"$lim"') | n/a |'
    f_has "$PF_README_SEC" 'A hard ceiling of '"$((lim + grace))"' s: `timeout -k '"$grace"'` with a limit of '"$lim"' s counted from the start of the sub-step'
    f_has "$PF_README_SEC" '`SC_PREFLIGHT_TIMEOUT` lowers it, from '"$floor"' to '"$lim"' with no leading zero (nothing in the state machine sets it)'
    f_has "$PF_README_SEC" 'a smaller value is refused up front with `SC_PREFLIGHT_TIMEOUT must be a whole number from '"$floor"' to '"$lim"'`'
  else
    note_fail "the limit '$lim', the kill grace '$grace' or the floor '$floor' could not be read"
  fi
  case_end
}
static_wo65_cases

### Work order 87: the loader-only mode of infra work order 86 in the workflow module, the S overlay and the README (static) --
# The state machine is not run (no terraform binary on the harness PATH). main.tf is read as text with
# comment-only lines removed, and the states are cut out by their indented headers. The Choice routing,
# the RunLoader Catch and the Default of ValidateInput are what the cases below pin; whether Step Functions
# evaluates the IsPresent rules inside And as intended stays UNVERIFIED until a plan and a run (the README
# says so too).

f_has_re() {
  grep -qE -- "$2" "$1" || note_fail "${1##*/} lacks a match: $2"
}

# SC2016: the single-quoted strings are literal fragments of Terraform text that f_has, f_has_not and
# f_count_is search for; the $ must not expand.
# shellcheck disable=SC2016
static_wo87_terraform_cases() {
  local main vi rl at lf pfp ln lp lc ra wc cap low high
  main="$(code_of "$TF_DIR/modules/workflow/main.tf")"
  vi="$CODE_DIR/wf-validate-input.txt"
  rl="$CODE_DIR/wf-run-loader.txt"
  at="$CODE_DIR/wf-after-terminate.txt"
  lf="$CODE_DIR/wf-loader-only-fields.txt"
  pfp="$CODE_DIR/wf-pipeline-fields.txt"
  awk 'index($0, "      ValidateInput = {") == 1 { f = 1 } f == 1 && index($0, "      InvalidInput = {") == 1 { exit } f == 1 { print }' "$main" > "$vi"
  awk 'index($0, "      RunLoader = {") == 1 { f = 1 } f == 1 && index($0, "      CleanupOnFailure = {") == 1 { exit } f == 1 { print }' "$main" > "$rl"
  awk 'index($0, "      AfterTerminate = {") == 1 { f = 1 } f == 1 && index($0, "      RunLoader = {") == 1 { exit } f == 1 { print }' "$main" > "$at"
  awk 'index($0, "  loader_only_fields_present = [") == 1 { f = 1 } f == 1 { print } f == 1 && $0 == "  ]" { exit }' "$main" > "$lf"
  awk 'index($0, "  pipeline_fields_present = [") == 1 { f = 1 } f == 1 { print } f == 1 && $0 == "  ]" { exit }' "$main" > "$pfp"

  static_begin "14.terraform.workflow.loader-only-mode-is-in-main-tf-with-its-own-rule-and-the-invalid-input-text-names-it"
  f_count_is "$main" 'StringEquals = "loader-only"' 1
  f_has "$main" 'mode_is_loader_only = ['
  f_has "$main" '{ Variable = "$.mode", StringEquals = "loader-only" },'
  f_has "$main" 'mode loader-only with run_id and loader_command. No cluster was created.'
  f_has "$main" 'Mode loader-only: no cluster; run the loader once against an export already in S3, then end.'
  f_has "$main" 'The execution input needs mode capability-check, or mode pipeline with snapshot, snapshots, run_id and loader_command, or mode loader-only'
  case_end

  static_begin "14.terraform.workflow.loader-only-choice-needs-run-id-and-loader-command-only-and-never-a-snapshot-or-a-cluster"
  if [ "$(wc -l < "$lf" | tr -d ' ')" != "4" ]; then note_fail "loader_only_fields_present has $(wc -l < "$lf" | tr -d ' ') lines, expected 4 (header, two rules, close)"; fi
  f_has "$lf" '{ Variable = "$.run_id", IsPresent = true },'
  f_has "$lf" '{ Variable = "$.loader_command", IsPresent = true },'
  f_has_not "$lf" '$.snapshot'
  f_has_not "$lf" '$.cluster'
  f_has_not "$lf" 'StringEquals'
  if [ "$(wc -l < "$pfp" | tr -d ' ')" != "6" ]; then note_fail "pipeline_fields_present has $(wc -l < "$pfp" | tr -d ' ') lines, expected 6 (header, four rules, close)"; fi
  f_has "$pfp" '{ Variable = "$.snapshot", IsPresent = true },'
  f_has "$pfp" '{ Variable = "$.snapshots", IsPresent = true },'
  f_has "$pfp" '{ Variable = "$.run_id", IsPresent = true },'
  f_has "$pfp" '{ Variable = "$.loader_command", IsPresent = true },'
  case_end

  static_begin "14.terraform.workflow.validate-input-routes-loader-only-to-run-loader-and-not-to-create-cluster"
  if [ "$(wc -l < "$vi" | tr -d ' ')" -lt 14 ]; then note_fail "the ValidateInput state cut out of main.tf has fewer than 14 lines"; fi
  f_has "$vi" 'And  = concat(local.mode_is_loader_only, local.loader_only_fields_present)'
  f_count_is "$main" 'local.mode_is_loader_only' 1
  ln="$(awk 'index($0, "local.mode_is_loader_only") > 0 { getline; print; exit }' "$vi")"
  case "$ln" in
    *'Next = "RunLoader"'*) ;;
    *) note_fail "the loader-only rule of ValidateInput does not go to RunLoader: '$ln'" ;;
  esac
  lp="$(awk 'index($0, "local.mode_is_pipeline") > 0 { getline; print; exit }' "$vi")"
  case "$lp" in
    *'Next = "CreateCluster"'*) ;;
    *) note_fail "the pipeline rule of ValidateInput does not go to CreateCluster: '$lp'" ;;
  esac
  lc="$(awk 'index($0, "And  = local.mode_is_capability") > 0 { getline; print; exit }' "$vi")"
  case "$lc" in
    *'Next = "CreateCluster"'*) ;;
    *) note_fail "the capability-check rule of ValidateInput does not go to CreateCluster: '$lc'" ;;
  esac
  f_count_is "$vi" 'Next = "CreateCluster"' 2
  f_count_is "$vi" 'Next = "RunLoader"' 1
  f_count_is "$vi" 'Next' 3
  f_has_not "$vi" 'CleanupOnFailure'
  f_has_not "$vi" 'FetchScripts'
  f_has_not "$vi" 'TerminateCluster'
  case_end

  static_begin "14.terraform.workflow.validate-input-still-defaults-to-invalid-input-and-nothing-else-defaults"
  f_count_is "$vi" 'Default = "InvalidInput"' 1
  f_count_is "$vi" 'Default' 1
  f_has_not_re "$vi" 'Default[[:space:]]*=[[:space:]]*"(RunLoader|CreateCluster|Done|Failed|CleanupOnFailure)"'
  f_has "$main" 'StartAt        = "ValidateInput"'
  f_has_re "$main" '^      InvalidInput = \{$'
  f_has "$main" 'Type  = "Fail"'
  f_has "$main" 'Error = "InvalidInput"'
  case_end

  static_begin "14.terraform.workflow.run-loader-still-catches-everything-to-failed-and-reads-only-the-loader-command"
  if [ "$(wc -l < "$rl" | tr -d ' ')" -lt 20 ]; then note_fail "the RunLoader state cut out of main.tf has fewer than 20 lines"; fi
  f_has "$rl" 'Resource = "arn:aws:states:::ecs:runTask.sync"'
  f_has "$rl" '"Command.$" = "$.loader_command"'
  f_has_re "$rl" 'ResultPath[[:space:]]*=[[:space:]]*"\$\.load"'
  f_has_re "$rl" 'TimeoutSeconds[[:space:]]*=[[:space:]]*var\.loader_timeout_seconds'
  f_has_re "$rl" 'Next[[:space:]]*=[[:space:]]*"Done"'
  f_has_re "$rl" 'ErrorEquals[[:space:]]*=[[:space:]]*\["States\.ALL"\]'
  f_has_re "$rl" 'Next[[:space:]]*=[[:space:]]*"Failed"'
  if [ "$(grep -cE 'Next[[:space:]]*=' "$rl")" != "2" ]; then note_fail "RunLoader has $(grep -cE 'Next[[:space:]]*=' "$rl") Next targets, expected 2 (Done and the Catch to Failed)"; fi
  f_has_not "$rl" 'CleanupOnFailure'
  f_has_not "$rl" 'local.on_failure'
  f_has_not "$rl" '$.cluster'
  f_has_not "$rl" 'CreateCluster'
  f_count_is "$main" 'Next = "RunLoader"' 2
  f_has "$at" 'And  = local.mode_is_pipeline'
  f_has "$at" 'Next = "RunLoader"'
  f_has "$at" 'Default = "Done"'
  f_has_not "$at" 'loader_only'
  case_end

  # The S overlay: the loader cap fits alone in the execution timeout, Rule A is still the one-step rule
  # and does not count the loader, and the pipeline worst case still exceeds the maximum (so the accepted
  # limitation text stays true). M and L keep their loader values.
  static_begin "14.terraform.s-overlay.loader-5400-fits-the-execution-timeout-alone-and-rule-a-is-unchanged"
  tf_overlay "$TF_S"
  if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
    ra=$((TF_CREATE + TF_STEP + 600))
    wc=$((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD))
    [ "$TF_LOAD" = "5400" ] || note_fail "the S loader is $TF_LOAD, work order 86 says 5400"
    [ "$TF_LOAD" -le "$TF_MAX" ] || note_fail "the S loader $TF_LOAD does not fit alone in the execution timeout $TF_MAX"
    [ "$TF_MAX" = "7200" ] || note_fail "the S execution timeout is $TF_MAX, expected 7200 (unchanged)"
    [ "$ra" = "6840" ] || note_fail "Rule A for S is $ra, expected 6840 (1440 + 4800 + 600, unchanged)"
    tf_rule_a "$TF_MAX" "$TF_CREATE" "$TF_STEP" || note_fail "S fails Rule A: $TF_MAX < $ra"
    [ "$wc" = "12840" ] || note_fail "the S pipeline worst case is $wc, expected 12840"
    [ "$wc" -gt "$TF_MAX" ] || note_fail "the S worst case $wc no longer exceeds $TF_MAX"
    f_has "$TF_S" "$TF_LOAD <= $TF_MAX"
  else
    note_fail "a pipeline value of the S overlay could not be read as a whole number"
  fi
  f_has "$WF_VARS" 'condition     = var.max_run_seconds >= var.create_cluster_timeout_seconds + var.emr_step_timeout_seconds + 600'
  f_has_not_re "$WF_VARS" 'condition[[:space:]]*=.*loader_timeout_seconds[[:space:]]*[+]'
  f_has_not_re "$CRS_VARS" 'condition[[:space:]]*=.*pipeline_loader_timeout_seconds[[:space:]]*[+]'
  tf_overlay "$TF_M"
  [ "$TF_LOAD" = "2700" ] || note_fail "the M loader is '$TF_LOAD', expected 2700 (unchanged)"
  if tf_is_num "$TF_LOAD" && tf_is_num "$TF_MAX"; then
    [ "$TF_LOAD" -le "$TF_MAX" ] || note_fail "the M loader $TF_LOAD is above the M execution timeout $TF_MAX"
  fi
  tf_overlay "$TF_L"
  [ "$TF_LOAD" = "3600" ] || note_fail "the L loader is '$TF_LOAD', expected 3600 (unchanged)"
  if tf_is_num "$TF_LOAD" && tf_is_num "$TF_MAX"; then
    [ "$TF_LOAD" -le "$TF_MAX" ] || note_fail "the L loader $TF_LOAD is above the L execution timeout $TF_MAX"
  fi
  case_end

  # The infra README: the figures it gives for the S loader follow the overlay.
  static_begin "14.terraform.readme-gives-the-s-loader-figures-of-work-order-86-from-the-overlay-values"
  tf_overlay "$TF_S"
  if tf_is_num "$TF_MAX" && tf_is_num "$TF_CREATE" && tf_is_num "$TF_STEP" && tf_is_num "$TF_LOAD"; then
    wc=$((TF_CREATE + 300 + TF_STEP + 900 + TF_LOAD))
    low=$((660 + 300 + 3300 + 900 + TF_LOAD))
    high=$((660 + 300 + 3900 + 900 + TF_LOAD))
    cap=$((660 + 300 + TF_STEP + 900 + TF_LOAD))
    f_has "$TF_README" "S $TF_LOAD (work order 86; 1800 before), M 2700, L 3600"
    f_has "$TF_README" "RunLoader (\`pipeline_loader_timeout_seconds\`; S $TF_LOAD since work order 86 and 1800 before, M 2700, L 3600)"
    f_has "$TF_README" "S $TF_CREATE + 300 + $TF_STEP + 900 + $TF_LOAD = $wc > $TF_MAX ($((wc - TF_MAX)) s over; it was 9240, 2040 s over, with a 1800 s loader before work order 86"
    f_has "$TF_README" "the S worst case of the segment timeouts is $wc s since work order 86 (9240 s under work order 52)"
    f_has "$TF_README" "with $TF_LOAD the three totals become $(commas "$low"), $(commas "$high") and $(commas "$cap") s"
    f_has "$TF_README" "$TF_LOAD s is for loader-only runs"
  else
    note_fail "a pipeline value of the S overlay could not be read as a whole number"
  fi
  f_has "$TF_README" '### Loader-only mode (work order 86; authored, not planned, not validated, not run)'
  f_count_is "$TF_README" '### Loader-only mode (work order 86' 1
  f_has "$TF_README" 'Path: `ValidateInput` -> `RunLoader` -> `Done`, with `RunLoader`'"'"'s Catch going to `Failed`.'
  f_has "$TF_README" 'UNVERIFIED until a plan and a run: the Choice rules with `IsPresent` inside `And`'
  f_has_not "$TF_README" 'S 1440 + 300 + 4800 + 900 + 1800 = 9240 > 7200 (2040 s over;'
  case_end
}
static_wo87_terraform_cases

### Work order 92: the prose of infra work order 90 (loader-only section, log-tail command, loader figures) --
# Infra work order 90 corrected three claims in infra/terraform/README.md and added the operator command
# that tails the loader log group. The cases below pin the corrected facts as text, and each fact that
# the code or the Terraform decides is also read from that code or Terraform, so a later change to either
# makes the prose stale loudly instead of silently:
#   1. A rerun does NOT skip finished batches (the ledger execution label is fresh on every loader run);
#      it skips per vector by text hash and model.
#   2. ValidateInput does not reject extra keys; the script is the only exact-key gate.
#   3. The loader log group is the one Terraform names, /scholarchain-course/loader, derived here from
#      the Terraform text (module runtime, name_prefix of the course environment), not typed twice.
#   4. The three log markers, and that two of them exist only after the loader image is rebuilt.
#   5. The loader figures the READMEs give for S, M and L follow the overlays.
# Nothing is run: the files are read as text. Whether Step Functions evaluates the Choice rule as the
# README says stays UNVERIFIED until a plan and a run, as the README itself says.
# SC2016: the single-quoted strings are literal README, Scala and Terraform fragments that f_has,
# f_has_not and f_has_re search for as text; the $ and the backticks must not expand.
# shellcheck disable=SC2016
static_wo92_cases() {
  local tfr="$TF_DIR/README.md" srd="$REAL_ROOT/scripts/README.md"
  local aim="$REAL_ROOT/modules/rag/src/main/scala/scholarchain/rag/AwsIngestMain.scala"
  local emb="$REAL_ROOT/modules/storage/src/main/scala/scholarchain/storage/EmbeddingIngest.scala"
  local rtm crm ctf rt_code rt_blk crm_code rb main vi
  local proj envn region lg bad f p ov para
  local s_max s_create s_step s_load m_load l_load ra
  rtm="$TF_DIR/modules/runtime/main.tf"
  crm="$TF_DIR/environments/course/main.tf"
  ctf="$TF_DIR/environments/course/course.tfvars"

  # 1. How a rerun skips work.
  static_begin "14.docs.loader-only.readme-says-a-rerun-skips-per-vector-and-never-the-finished-batches"
  f_has "$tfr" 'A rerun does NOT resume by batch: every loader run gets a fresh ledger execution label (`AwsIngestMain`, `executionLabel = compactStamp(clock())`), so the ledger'"'"'s finished-batch skip never applies.'
  f_has "$tfr" 'Skipping is per vector, by text hash and embedding model (`EmbeddingIngest.runBatch`), and only for vectors that are actually stored in S3 Vectors; for each such vector the semantic document is still re-checked, one S3 call per item.'
  f_has "$tfr" 'If a previous run'"'"'s vectors were not stored (for example the batch that was running at the timeout), the rerun embeds them again.'
  for f in "$tfr" "$srd" "$TF_S"; do
    f_has_not "$f" 'skips the batches already finished'
    f_has_not "$f" 'skip the batches already finished'
    f_has_not_re "$f" '[Ss]kips? (the )?(batches|work) (that )?(are |were |have been |it has )?already (finished|done|completed)'
    f_has_not_re "$f" 'already[- ](finished|completed|done) batches'
  done
  # The code the sentence describes: a fresh label from the clock, never a fixed literal; the per-vector
  # skip needs both the text hash and the model to match; the document is put back for a skipped vector;
  # the ledger skip of a FINISHED batch still exists, which is why the label must stay fresh.
  f_has "$aim" 'wiring.backend.copy(executionLabel = compactStamp(clock()))'
  f_has_not_re "$aim" 'executionLabel[[:space:]]*=[[:space:]]*"'
  f_has "$emb" 'storedHash(old) == p.item.textHash &&'
  f_has "$emb" 'storedModel(old) == p.item.embeddingModel =>'
  f_has "$emb" 'if !differing.contains("textRef") then putDocument(p)'
  f_has "$emb" 'if prior.exists(_._1 == "FINISHED") then t(2) += items.size'
  case_end

  # 2. What ValidateInput checks.
  main="$(code_of "$TF_DIR/modules/workflow/main.tf")"
  vi="$CODE_DIR/wf-validate-input-92.txt"
  awk 'index($0, "      ValidateInput = {") == 1 { f = 1 } f == 1 && index($0, "      InvalidInput = {") == 1 { exit } f == 1 { print }' "$main" > "$vi"
  static_begin "14.docs.loader-only.readme-says-validate-input-does-not-reject-extra-keys-and-the-script-is-the-only-exact-key-gate"
  f_has "$tfr" 'Input (an exact shape, checked by `scripts/run-bounded-pipeline.sh` before anything starts; `ValidateInput` checks less, see below):'
  f_has "$tfr" '`ValidateInput` checks only that `run_id` and `loader_command` are present; their shape is the script'"'"'s job'
  f_has "$tfr" '`ValidateInput` does not reject extra keys, in this mode or in mode pipeline'
  f_has "$tfr" 'a loader-only input that also carries `snapshot` or `snapshots` still reaches `RunLoader`, and the script is the only exact-key gate.'
  f_has "$tfr" 'gets no exact-key check, no `run_id` shape check and no check that the last `loader_command` element matches `run_id`'
  f_has_not_re "$tfr" 'ValidateInput`?[[:space:]]+(also[[:space:]]+)?(rejects|refuses|enforces)'
  f_has_not_re "$tfr" 'ValidateInput`?[[:space:]]+(is|also is)[[:space:]]+(an?|the)[[:space:]]+(exact|key)'
  # Work order 94 (follows infra work order 93): the scripts/README.md sentence now says who refuses the keys
  # ("refused by the script as unknown keys") and adds that the state machine does not reject them. The old
  # needle ended at the closing parenthesis and a full stop, so it would fail on the new text; it is replaced
  # by the new sentence, and the old wording is asserted absent.
  f_has "$srd" 'The keys are exactly `mode`, `run_id` and `loader_command`; `snapshot` and `snapshots` are refused by the script as unknown keys (`unknown input key(s) for mode loader-only: ...`); the state machine does not reject them.'
  f_has_not "$srd" 'are refused as unknown keys (`unknown input key(s) for mode loader-only: ...`).'
  f_has_not_re "$srd" 'state machine[^.]*(refuses|rejects)[^.]*(unknown|extra) key'
  # The Terraform the sentence describes: the loader-only rule is the mode test plus two IsPresent tests,
  # and the ValidateInput state holds no negated or absent-key test that would reject an extra key.
  if [ "$(wc -l < "$vi" | tr -d ' ')" -lt 14 ]; then note_fail "the ValidateInput state cut out of main.tf has fewer than 14 lines"; fi
  f_has "$vi" 'And  = concat(local.mode_is_loader_only, local.loader_only_fields_present)'
  f_has_not_re "$vi" 'IsPresent[[:space:]]*=[[:space:]]*false'
  f_has_not_re "$vi" '(^|[^A-Za-z_])Not[[:space:]]*='
  case_end

  # 3. The loader log group.
  rt_code="$(code_of "$rtm")"
  crm_code="$(code_of "$crm")"
  rt_blk="$CODE_DIR/runtime-log-group-block-92.txt"
  rb="$CODE_DIR/course-module-runtime-92.txt"
  awk 'index($0, "resource \"aws_cloudwatch_log_group\" \"this\" {") == 1 { f = 1 } f == 1 { print } f == 1 && $0 == "}" { exit }' "$rt_code" > "$rt_blk"
  awk 'index($0, "module \"runtime\" {") == 1 { f = 1 } f == 1 { print } f == 1 && $0 == "}" { exit }' "$crm_code" > "$rb"
  proj="$(tf_def_in "$CRS_VARS" project)"
  proj="${proj//\"/}"
  proj="${proj%% *}"
  envn="$(sed -n 's/^environment[[:space:]]*=[[:space:]]*"\([A-Za-z0-9-]*\)".*/\1/p' "$ctf" | head -n 1)"
  region="$(tf_def_in "$CRS_VARS" region)"
  region="${region//\"/}"
  region="${region%% *}"
  lg="/${proj}-${envn}/loader"
  static_begin "14.docs.loader-only.log-group-in-both-readmes-is-the-one-terraform-names-and-the-tail-command-follows-it"
  case "$proj" in
    "" | *[!a-z0-9-]*) note_fail "the project default could not be read from the course variables: '$proj'" ;;
  esac
  case "$envn" in
    "" | *[!a-z0-9-]*) note_fail "the environment could not be read from course.tfvars: '$envn'" ;;
  esac
  case "$region" in
    "" | *[!a-z0-9-]*) note_fail "the region default could not be read from the course variables: '$region'" ;;
  esac
  [ "$lg" = "/scholarchain-course/loader" ] || note_fail "the loader log group derived from the Terraform is '$lg', not /scholarchain-course/loader"
  for ov in "$TF_S" "$TF_M" "$TF_L"; do
    [ "$(tf_count "$ov" environment)" = "0" ] || note_fail "${ov##*/} sets environment, so the name prefix of that overlay differs from course.tfvars"
    [ "$(tf_count "$ov" project)" = "0" ] || note_fail "${ov##*/} sets project, so the name prefix of that overlay differs from the variable default"
  done
  # Terraform: name_prefix = project-environment, handed to the runtime module, which names the group
  # /<name_prefix>/<each.value> for loader too, and the loader task definition writes to that group.
  f_has_re "$crm_code" '^[[:space:]]*name_prefix[[:space:]]*=[[:space:]]*"\$\{var\.project\}-\$\{var\.environment\}"$'
  # Work order 99: rt_code and crm_code are now two different files (code_of names its copy after the whole
  # path; both used to be $CODE_DIR/main.tf, so rt_code held the course main.tf and the cut below was empty).
  # Each cut runs from its exact header line (runtime main.tf line 93 and course main.tf line 179) to the first
  # later line that is exactly "}" in column 1. Mutation: renaming the resource label, splitting the toset list
  # over lines, changing the name pattern to /${var.name_prefix}-loader, pointing the loader task's awslogs-group
  # at another group, or passing another name_prefix to the module each fails one needle below.
  f_count_is "$rt_code" 'resource "aws_cloudwatch_log_group" "this" {' 1
  f_count_is "$crm_code" 'module "runtime" {' 1
  f_min_lines "$rb" 5 "the runtime module call cut out of the course main.tf"
  f_has_re "$rb" '^[[:space:]]*name_prefix[[:space:]]*=[[:space:]]*local\.name_prefix$'
  f_min_lines "$rt_blk" 4 "the log group resource cut out of the runtime main.tf"
  f_has_re "$rt_blk" '^[[:space:]]*for_each[[:space:]]*=[[:space:]]*toset\(\["fast", "slow", "loader"\]\)$'
  f_has_re "$rt_blk" '^[[:space:]]*name[[:space:]]*=[[:space:]]*"/\$\{var\.name_prefix\}/\$\{each\.value\}"$'
  f_has_re "$rt_code" '"awslogs-group"[[:space:]]*=[[:space:]]*aws_cloudwatch_log_group\.this\["loader"\]\.name'
  f_has_re "$rt_code" '"awslogs-stream-prefix"[[:space:]]*=[[:space:]]*"ecs"'
  # The READMEs: the name, the exact command, the stream prefix, the Region, and no other loader tail.
  f_has "$tfr" 'goes to the CloudWatch log group `/<name_prefix>/loader` (`aws_cloudwatch_log_group.this["loader"]` in `modules/runtime/main.tf`, stream prefix `ecs`;'
  f_has "$tfr" "so \`$lg\` for the course environment)"
  f_has "$srd" "The loader's log group is \`/<name_prefix>/loader\` (\`modules/runtime/main.tf\`; \`$lg\` in the course environment)."
  for f in "$tfr" "$srd"; do
    f_has "$f" "AWS_PROFILE=scholarchain aws logs tail $lg --follow --format short"
    f_has "$f" "Add \`--region $region\` if the profile has no default Region (not checked)."
    bad="$( (grep -oE 'aws logs tail [^ ]*loader[^ ]*' "$f" || true) | (grep -vxF "aws logs tail $lg" || true))"
    [ -z "$bad" ] || note_fail "${f##*/} tails a loader log group other than $lg: $bad"
  done
  case_end

  # 4. The markers and the rebuild note, against the code that prints them.
  static_begin "14.docs.loader-only.readme-lists-the-three-log-markers-says-they-need-the-rebuilt-image-and-the-code-prints-them"
  f_has "$tfr" 'Markers to look for: `LOADER_PHASE name=<download|ingest|graph|report> state=<start|done> elapsed_s=<n>`, `LOADER_PROGRESS` (one line at the start, one per batch and one per 100 embeds inside a batch), and `LOADER_RESULT=SUCCESS` or `LOADER_RESULT=FAILED reason=...` as the last line.'
  f_has "$tfr" '`LOADER_PROGRESS` and `LOADER_PHASE` exist only after the loader image is rebuilt from the repaired sources (work orders 85 and 88)'
  f_has "$tfr" 'the image of S run 6 prints neither, only `LOADER_INPUT`'
  f_has "$srd" 'Look for `LOADER_PHASE name=... state=... elapsed_s=...`, `LOADER_PROGRESS` (a start line, one line per batch, one per 100 embeds within a batch) and the last line `LOADER_RESULT=SUCCESS` or `LOADER_RESULT=FAILED reason=...`.'
  f_has "$srd" '`LOADER_PROGRESS` and `LOADER_PHASE` exist only after the loader image is rebuilt from the repaired sources (work orders 85 and 88)'
  f_has "$srd" 'the S run 6 image printed `LOADER_INPUT` and nothing else until the timeout.'
  f_has "$aim" 's"LOADER_PHASE name=$name state=$state "'
  for p in download ingest graph report; do
    f_has "$aim" "phase(\"$p\", \"start\")"
    f_has "$aim" "phase(\"$p\", \"done\")"
  done
  f_has "$aim" 'if success then "LOADER_RESULT=SUCCESS"'
  f_has "$aim" 's"LOADER_RESULT=FAILED reason='
  f_has "$emb" 'emit(s"LOADER_PROGRESS start items=$totalItems batches=${batches.size}")'
  f_has "$emb" 's"LOADER_PROGRESS index=${work.index} "'
  f_has "$emb" 's"LOADER_PROGRESS index=${spec.name} "'
  f_has "$emb" 'val BatchProgressEvery = 100'
  case_end

  # The spend note: the READMEs say what the script's ceiling does not count, and quote no price.
  static_begin "14.docs.loader-only.spend-note-says-per-request-charges-are-not-counted-and-quotes-no-price"
  para="$CODE_DIR/readme-spend-para-92.txt"
  awk 'index($0, "Spend the script does not see.") == 1' "$tfr" > "$para"
  [ "$(wc -l < "$para" | tr -d ' ')" = "1" ] || note_fail "infra/terraform/README.md does not hold exactly one 'Spend the script does not see.' paragraph"
  f_has "$para" 'projects spend as the caller'"'"'s hourly burn times the time cap and nothing else'
  f_has "$para" 'These counts are unmeasured estimates, and no price is quoted here because none was verified.'
  f_has_not_re "$para" 'USD|\$[0-9]'
  para="$CODE_DIR/readme-spend-para-92b.txt"
  awk 'index($0, "Spend. The script") == 1' "$srd" > "$para"
  [ "$(wc -l < "$para" | tr -d ' ')" = "1" ] || note_fail "scripts/README.md does not hold exactly one 'Spend. The script' paragraph"
  f_has "$para" 'counts the caller'"'"'s hourly burn times time only, not per-request charges'
  f_has "$para" '(all unmeasured estimates; no price is quoted because none was verified)'
  f_has_not_re "$para" 'USD|\$[0-9]'
  case_end

  # 5. The loader figures follow the overlays.
  static_begin "14.docs.loader-only.loader-timeout-figures-in-the-readmes-follow-the-overlays"
  tf_overlay "$TF_S"
  s_max="$TF_MAX"
  s_create="$TF_CREATE"
  s_step="$TF_STEP"
  s_load="$TF_LOAD"
  tf_overlay "$TF_M"
  m_load="$TF_LOAD"
  tf_overlay "$TF_L"
  l_load="$TF_LOAD"
  if tf_is_num "$s_max" && tf_is_num "$s_create" && tf_is_num "$s_step" && tf_is_num "$s_load" && tf_is_num "$m_load" && tf_is_num "$l_load"; then
    ra=$((s_create + s_step + 600))
    f_has "$tfr" "\`pipeline_loader_timeout_seconds\` (S $s_load since work order 86, M $m_load, L $l_load), with the arithmetic in the overlay comments."
    # Work order 97 (infra work order 95): the sentence no longer says the run ends at the cap "plus the
    # Fargate start". The RunLoader state timeout starts when the state is entered, so the Fargate start
    # and the image pull are inside the cap. The old needle ("The execution ends at ... plus the Fargate
    # start, inside ...") is replaced by the new sentence, and the old addition is asserted absent.
    f_has "$tfr" "Bounds. The loader run ends at \`pipeline_loader_timeout_seconds\` (S $s_load s), inside \`pipeline_max_run_seconds\` (S $s_max s)."
    f_has "$tfr" "\`RunLoader\` is an \`ecs:runTask.sync\` Task state whose \`TimeoutSeconds\` starts when the state is entered, so the Fargate start and the image pull count against the $s_load s and do not come on top of it"
    f_has_not "$tfr" "plus the Fargate start"
    f_has_not_re "$tfr" '[Pp]lus the Fargate start'
    f_has_not_re "$tfr" 'Bounds\. The execution ends at'
    f_has "$tfr" "it still holds for mode pipeline (S $s_create + $s_step + 600 = $ra <= $s_max)"
    f_has "$srd" "(\`pipeline_loader_timeout_seconds\`: S $s_load since work order 86 and 1800 s in the S run 5 figures above, M $m_load, L $l_load s;"
    f_has "$srd" "the loader timeout of the S overlay is $s_load s ($((s_load / 60)) minutes)"
  else
    note_fail "a loader or S pipeline value could not be read from the overlays as a whole number"
  fi
  case_end
}
static_wo92_cases

### Work order 94: the input contract wording of infra work order 93 (static) --------------------------
# Infra work order 93 corrected three places that said the state machine refuses a wrong key set: the
# "Execution input contract" paragraph of scripts/README.md, the loader-only paragraph of the same file, and
# the comment above INPUT_FIELDS in run-bounded-pipeline.sh. The truth, read from main.tf (the ValidateInput
# case of work order 92 pins it): the first Choice state routes by mode and by the presence of the required
# keys, sends a missing key or an unknown mode to InvalidInput, and does not reject extra keys, so the script
# is the only exact-key gate. This case pins that wording and refuses the old claim. The README may still say
# that ValidateInput sends a missing key or an unknown mode to InvalidInput; that is not forbidden here.
# SC2016: the single-quoted strings are literal README and script fragments (backticks must not expand).
# shellcheck disable=SC2016
static_wo94_cases() {
  local srd="$REAL_ROOT/scripts/README.md" scr="$REAL_ROOT/scripts/run-bounded-pipeline.sh"

  static_begin "14.docs.input-contract.readme-and-script-say-the-script-is-the-only-exact-key-gate-and-the-state-machine-does-not-refuse-extra-or-unknown-keys"
  f_has "$srd" '`run-bounded-pipeline.sh` refuses every other shape before it starts anything: the key set must be exactly the keys of the mode, so a missing key, an unknown key and an unknown mode are all refused.'
  f_has "$srd" 'but it does not reject extra keys (for example `snapshot` in a loader-only input still reaches `RunLoader`), so the script is the only exact-key gate.'
  f_has "$scr" 'not reject extra keys, so the check below is the only exact-key gate:'
  f_has_not "$srd" "and by the state machine's first Choice state"
  f_has_not "$srd" 'Any other shape is refused by `run-bounded-pipeline.sh` before it starts anything'
  f_has_not "$scr" 'design B):'
  # No sentence may give the state machine the exact-key check: an active claim (the state machine,
  # ValidateInput or the Choice state refuses, rejects or enforces extra, unknown or other keys) or a passive
  # one (extra, unknown or other keys refused or rejected by the state machine). The negated forms the README
  # now uses ("does not reject extra keys", "does not reject them") hold the bare verb and never match.
  f_has_not_re "$srd" '(state machine|ValidateInput`?|Choice state)[^.]{0,80}[[:space:]](rejects|refuses|enforces)[^.]{0,40}[[:space:]](extra|unknown|other)[[:space:]]+(input[[:space:]]+)?keys?'
  f_has_not_re "$srd" '(extra|unknown|other)[[:space:]]+(input[[:space:]]+)?keys?[^.]{0,60}[[:space:]](refused|rejected)[[:space:]]+by[^.]{0,40}(state machine|ValidateInput|Choice state)'
  f_has_not_re "$scr" '(state machine|ValidateInput|Choice state)[^.]{0,80}[[:space:]](rejects|refuses|enforces)[^.]{0,40}[[:space:]](extra|unknown|other)[[:space:]]+(input[[:space:]]+)?keys?'
  case_end
}
static_wo94_cases

### Work order 97: the doc text of infra work order 95 (static) ------------------------------------------
# Infra work order 95 changed text only, in four places, and this block pins the new truths and refuses
# the old claims. Nothing here is run against AWS; the files are read as text.
#   1. infra/terraform/README.md, "Bounds": the loader run ends at the loader cap, and because the
#      RunLoader state timeout starts when the state is entered, the Fargate start and the image pull are
#      inside the cap. They are not added on top ("plus the Fargate start" is gone). The case of work order
#      92 that read the old sentence is repaired in place (14.docs.loader-only.loader-timeout-figures-...).
#   2. The same README, "Why": about 47 minutes (about 2,800 s), no longer "about 52 more minutes". The figure
#      is recomputed here from the 25.7k embeddings and 9.2 items a second the sentence itself states.
#      infra/terraform/environments/course/experiment-size-s.tfvars still says "about 52 more minutes" (it
#      was outside work order 95); this block does not read it, and the report names it as a finding.
#   3. Both READMEs, the spend notes: the old "about 100k HEAD/GET checks" is replaced by the supported
#      counts (about 42k HEADs, about 25.7k PUTs, about 420 GetVectors calls, two listings of about 42 pages),
#      still unmeasured estimates and no price. The counts are re-derived from the 41.9k total the notes state.
#   4. modules/workflow/main.tf, the comment above mode_is_capability: ValidateInput checks run_id AND
#      loader_command. main.tf is read raw here (the comment is dropped by code_of, which the other
#      main.tf cases use), and the claim is tied to the code: the loader-only rule holds both IsPresent
#      tests, and RunLoader never reads run_id.
# SC2016: the single-quoted strings are literal README and Terraform fragments (backticks and $ must not
# expand).
# shellcheck disable=SC2016
static_wo97_cases() {
  local tfr="$TF_DIR/README.md" srd="$REAL_ROOT/scripts/README.md" rawmain="$TF_DIR/modules/workflow/main.tf"
  local main rl lf cm para s_load n r mins items heads pages calls

  # 1. The Fargate start is inside the loader cap.
  tf_overlay "$TF_S"
  s_load="$TF_LOAD"
  main="$(code_of "$rawmain")"
  rl="$CODE_DIR/wf-run-loader-97.txt"
  awk 'index($0, "      RunLoader = {") == 1 { f = 1 } f == 1 && index($0, "      CleanupOnFailure = {") == 1 { exit } f == 1 { print }' "$main" > "$rl"
  static_begin "14.docs.loader-only.readme-says-the-fargate-start-counts-against-the-loader-cap-and-the-cap-is-on-the-whole-run-loader-state"
  if tf_is_num "$s_load"; then
    # Mutation: restoring "plus the Fargate start" (or dropping "count against") fails the first needles
    # and the absence checks; an overlay change of the S loader fails the figure inside them.
    f_has "$tfr" "\`RunLoader\` is an \`ecs:runTask.sync\` Task state whose \`TimeoutSeconds\` starts when the state is entered, so the Fargate start and the image pull count against the $s_load s and do not come on top of it; the loader process therefore gets less than the cap."
    f_has "$tfr" 'The cap is on the whole `RunLoader` state including the Fargate start'
    f_has_not "$tfr" 'plus the Fargate start'
    f_has_not_re "$tfr" '[Pp]lus the Fargate start'
  else
    note_fail "the S loader value could not be read from the overlay as a whole number: '$s_load'"
  fi
  # The sentence is true only if the timeout is on the Task state itself: a Task state on runTask.sync whose
  # own TimeoutSeconds is the loader variable (the Catch to Failed is pinned by the work order 87 case).
  # Mutation: moving the timeout into a nested Parameters block, or changing the resource.
  f_has_re "$rl" '^        Type[[:space:]]*=[[:space:]]*"Task"$'
  f_has_re "$rl" '^        Resource[[:space:]]*=[[:space:]]*"arn:aws:states:::ecs:runTask\.sync"$'
  f_has_re "$rl" '^        TimeoutSeconds[[:space:]]*=[[:space:]]*var\.loader_timeout_seconds$'
  case_end

  # The fit estimate sentence: its ranges, its basis, and that the 9.2 a second figure matches the minutes.
  static_begin "14.docs.loader-only.readme-gives-47-minutes-and-the-fit-estimate-ranges-and-never-52-more-minutes"
  # 25.7k embeddings at 9.2 a second: 25700 / 9.2 = 2793 s, which the README rounds to 2,800 s and 47
  # minutes. Mutation: a changed rate or count without the matching second and minute figure fails here.
  n=$((257000 / 92))
  r=$(( (n + 50) / 100 * 100 ))
  mins=$(( (r + 30) / 60 ))
  [ "$r" = "2800" ] || note_fail "25.7k embeddings at 9.2 a second round to $r s, the README says about 2,800 s"
  [ "$mins" = "47" ] || note_fail "$r s is about $mins minutes, the README says about 47"
  f_has "$tfr" "about 25.7k embeddings were left (about $mins minutes: about $(commas "$r") s at about 9.2 items per second)"
  f_has_not "$tfr" 'about 52 more minutes'
  f_has_not_re "$tfr" '(^|[^0-9,.])52 more minutes'
  f_has "$tfr" 'about 3,400 to 4,600 s if the earlier vectors were stored and about 4,700 to 5,950 s if not, unmeasured'
  f_has "$tfr" 'the S run 6 numbers (about 9.2 embeds per second including the document write) are the basis of the fit estimate'
  case_end

  # 3. The spend notes: the supported counts, derived from the 41.9k total; no "about 100k"; no price.
  static_begin "14.docs.loader-only.spend-notes-in-both-readmes-give-the-supported-counts-and-not-about-100k-head-get-checks"
  items=41900
  heads=$(( (items + 500) / 1000 ))
  pages=$(( (items + 999) / 1000 ))
  calls=$(( ( (items + 99) / 100 + 5) / 10 * 10 ))
  [ "$heads" = "42" ] || note_fail "41.9k items are about ${heads}k HEADs, the READMEs say about 42k"
  [ "$pages" = "42" ] || note_fail "41.9k keys are $pages listing pages of 1000, the READMEs say about 42"
  [ "$calls" = "420" ] || note_fail "41.9k keys at 100 a call are about $calls GetVectors calls, the READMEs say about 420"
  # Whole-file absence of the old claim in both READMEs. Mutation: restoring "about 100k HEAD/GET checks".
  f_has_not "$tfr" 'about 100k HEAD/GET checks'
  f_has_not "$srd" 'about 100k HEAD/GET checks'
  para="$CODE_DIR/readme-spend-para-97.txt"
  awk 'index($0, "Spend the script does not see.") == 1' "$tfr" > "$para"
  [ "$(wc -l < "$para" | tr -d ' ')" = "1" ] || note_fail "infra/terraform/README.md does not hold exactly one 'Spend the script does not see.' paragraph"
  f_has "$para" "about ${heads}k S3 HEADs (\`putImmutable\` makes one HEAD per planned item, whether it is skipped or newly embedded)"
  f_has "$para" 'about 25.7k document PUTs if the earlier vectors were stored (all 41.9k if not)'
  f_has "$para" "about $calls \`GetVectors\` calls"
  f_has "$para" "two bounded \`ListObjectsV2\` listings of about $pages pages each"
  f_has "$para" 'These counts are unmeasured estimates, and no price is quoted here because none was verified.'
  f_has_not "$para" 'about 100k'
  f_has_not_re "$para" '100k|HEAD/GET|HEAD or GET'
  f_has_not_re "$para" 'USD|\$[0-9]'
  para="$CODE_DIR/readme-spend-para-97b.txt"
  awk 'index($0, "Spend. The script") == 1' "$srd" > "$para"
  [ "$(wc -l < "$para" | tr -d ' ')" = "1" ] || note_fail "scripts/README.md does not hold exactly one 'Spend. The script' paragraph"
  f_has "$para" "about ${heads}k S3 HEADs (one per planned item, skipped or newly embedded)"
  f_has "$para" 'about 25.7k document PUTs if the earlier vectors were stored (all 41.9k if not)'
  f_has "$para" "about $calls \`GetVectors\` calls"
  f_has "$para" "two bounded \`ListObjectsV2\` listings of about $pages pages each"
  f_has "$para" '(all unmeasured estimates; no price is quoted because none was verified)'
  f_has_not "$para" 'about 100k'
  f_has_not_re "$para" '100k|HEAD/GET|HEAD or GET'
  f_has_not_re "$para" 'USD|\$[0-9]'
  case_end

  # 4. The main.tf comment, read raw. The comment lines of the "Execution input contract" block are joined
  # into paragraphs (each line loses its "#", runs of blanks become one space, an empty comment line ends
  # a paragraph), so a sentence wrapped over two lines can be pinned whole.
  lf="$CODE_DIR/wf-loader-only-fields-97.txt"
  cm="$CODE_DIR/wf-comment-paragraphs-97.txt"
  awk 'index($0, "  loader_only_fields_present = [") == 1 { f = 1 } f == 1 { print } f == 1 && $0 == "  ]" { exit }' "$main" > "$lf"
  awk '/^[[:space:]]*#/ { t = $0; sub(/^[[:space:]]*#/, "", t); gsub(/[[:space:]]+/, " ", t); sub(/^ /, "", t); sub(/ $/, "", t); if (t == "") { if (b != "") print b; b = ""; next } b = (b == "" ? t : b " " t); next } { if (b != "") print b; b = "" } END { if (b != "") print b }' "$rawmain" > "$cm"
  static_begin "14.docs.loader-only.workflow-comment-says-validate-input-checks-run-id-and-loader-command-and-the-code-requires-both"
  if [ "$(wc -l < "$cm" | tr -d ' ')" -lt 3 ]; then note_fail "the comment paragraphs cut out of main.tf have fewer than 3 lines"; fi
  # Mutation: dropping "and loader_command" (the old wording "checks that run_id is present") fails the
  # whole-sentence needle and the two absence checks.
  f_has "$cm" 'ValidateInput only checks that run_id and loader_command are present in that mode; no state uses the run_id value, and the loader takes the run id from loader_command.'
  f_has_not "$cm" 'only checks that run_id is present'
  f_has_not_re "$cm" 'checks that run_id (is|alone is|only is) present'
  f_has_not_re "$cm" 'ValidateInput (only )?(checks|requires|needs) (that )?(only )?run_id[[:space:]]+(is|alone)'
  # The code the comment describes. Mutation: removing either IsPresent test from the loader-only rule
  # makes the comment overstate the check; a RunLoader that reads $.run_id makes "no state uses the
  # run_id value" false.
  if [ "$(wc -l < "$lf" | tr -d ' ')" != "4" ]; then note_fail "loader_only_fields_present has $(wc -l < "$lf" | tr -d ' ') lines, expected 4 (header, two rules, close)"; fi
  f_has "$lf" '{ Variable = "$.run_id", IsPresent = true },'
  f_has "$lf" '{ Variable = "$.loader_command", IsPresent = true },'
  if [ "$(wc -l < "$rl" | tr -d ' ')" -lt 20 ]; then note_fail "the RunLoader state cut out of main.tf has fewer than 20 lines"; fi
  f_has_not "$rl" 'run_id'
  f_has "$rl" '"Command.$" = "$.loader_command"'
  case_end
}
static_wo97_cases

### Work order 68: the harness impact of infra-engineer work order 67 (static cases) ---------
# F2: the floor of 15 s for SC_PREFLIGHT_TIMEOUT. F3: timeout -k 5 20 hadoop classpath --glob.
# F4: the first failure is cut by parameter expansion, not by a pipe that closes early. F7: a
# class that only a node site file names is a WARN (report only), a key that our spark-defaults
# sets stays a hard FAIL. F5, F6, A3: comments, README text and the lettered header of
# run-s-pipeline.sh. Each needle is anchored to a call site or a whole line, and each is counted
# or tested for absence where a definition or a comment could hold the same words (the first
# occurrence of a name in a file is often its definition, as work order 66 found).
# SC2016: the single-quoted strings are literal fragments of node scripts, of the Scala text and
# of the README that f_has, f_has_not, f_before and f_count_is search for as text.
# shellcheck disable=SC2016
static_wo68_cases() {
  local rd="$REAL_ROOT/scripts/README.md" lib_raw="$EMR_DIR/lib-emr.sh" hdr="$CODE_DIR/run-s-header.txt"
  local k lim floor grace kg ch fl wl lp pf_first

  lim="$(sed -n 's/^SC_PREFLIGHT_LIMIT_SECONDS=\([0-9][0-9]*\)$/\1/p' "$emr_lib" | head -n 1)"
  floor="$(sed -n 's/^SC_PREFLIGHT_FLOOR_SECONDS=\([0-9][0-9]*\)$/\1/p' "$emr_lib" | head -n 1)"
  grace="$(sed -n 's/.*timeout -k \([0-9][0-9]*\) "\$remaining".*/\1/p' "$emr_lib" | head -n 1)"

  static_begin "14.emr.preflight.floor-is-15-and-the-refusal-text-says-why"
  if tf_is_num "$floor"; then
    [ "$floor" = "15" ] || note_fail "SC_PREFLIGHT_FLOOR_SECONDS is $floor, work order 67 and the README say 15"
  else
    note_fail "SC_PREFLIGHT_FLOOR_SECONDS could not be read: '$floor'"
  fi
  f_has "$emr_lib" 'SC_PREFLIGHT_FLOOR_SECONDS=15'
  f_count_is "$emr_lib" 'SC_PREFLIGHT_FLOOR_SECONDS=' 1
  f_has "$PF_RUN_FN" '"$limit" -ge "$SC_PREFLIGHT_FLOOR_SECONDS"'
  f_has "$PF_RUN_FN" 'the floor of 15 is chosen because the JVM compile and run is estimated at 15 to 25 s, and below it a pass is not plausible'
  f_has_not "$PF_RUN_FN" 'a smaller limit cannot leave 10 s for the JVM'
  f_has "$REAL_ROOT/scripts/README.md" 'because the floor of 15 is chosen on the estimate that the JVM compile and run takes 15 to 25 s, and below it a pass is not plausible'
  f_has_not "$REAL_ROOT/scripts/README.md" 'because under 10 s would be left for the JVM after the class paths'
  f_has_not "$lib_raw" 'a smaller limit cannot leave 10 s for the JVM'
  f_has_not "$PF_RUN_FN" 'whole number from 1 to'
  f_has_not "$lib_raw" 'whole number from 1 to'
  case_end

  static_begin "14.emr.preflight.hadoop-classpath-has-its-own-kill-grace-and-stays-inside-the-sub-step-budget"
  f_has "$PF_TG_FN" 'timeout -k 5 20 hadoop classpath --glob'
  f_count_is "$PF_TG_FN" 'timeout ' 1
  f_has_not_re "$PF_TG_FN" 'timeout [0-9]'
  f_has "$PF_TG_FN" 'die "preflight: hadoop classpath --glob failed or took over 20 s"'
  kg="$(sed -n 's/.*timeout -k \([0-9][0-9]*\) [0-9][0-9]* hadoop classpath --glob.*/\1/p' "$PF_TG_FN" | head -n 1)"
  ch="$(sed -n 's/.*timeout -k [0-9][0-9]* \([0-9][0-9]*\) hadoop classpath --glob.*/\1/p' "$PF_TG_FN" | head -n 1)"
  if tf_is_num "$kg" && tf_is_num "$ch" && tf_is_num "$lim" && tf_is_num "$grace"; then
    [ "$kg" -ge 1 ] || note_fail "the class path KILL grace is $kg"
    [ $((ch + kg)) -le $((lim - 10)) ] || note_fail "the class path command can take $((ch + kg)) s, which leaves under 10 s of the $lim s limit"
    [ $((lim + grace)) -le 90 ] || note_fail "the limit $lim plus the JVM kill grace $grace is above 90 s"
    f_has "$PF_README_SEC" 'The class path command has its own KILL grace (`-k '"$kg"'`), so it ends at most '"$((ch + kg))"' s after it starts even if `hadoop` ignores TERM'
    f_has "$PF_README_SEC" 'so the worst case stays '"$lim"' + '"$grace"' = '"$((lim + grace))"' s'
  else
    note_fail "a figure could not be read: class path grace '$kg', class path limit '$ch', limit '$lim', JVM grace '$grace'"
  fi
  case_end

  # F7: the Scala text. checkValue gets a hard flag. The FAIL emit is guarded by it, the WARN
  # emit is not and is the last branch, the node key loop passes the flag own (our key), and the
  # class-like spark.* loop passes true. Counted, so a second emit elsewhere cannot hide.
  static_begin "14.emr.preflight.scala-fails-hard-only-for-keys-our-spark-defaults-sets-and-warns-for-node-only-keys"
  f_has "$PF_SCALA" 'private def checkValue(loader: ClassLoader, key: String, value: String, reason: Option[String], hard: Boolean): Unit'
  f_count_is "$PF_SCALA" 'emit("FAIL config ' 1
  f_count_is "$PF_SCALA" 'emit("WARN config ' 1
  f_count_is "$PF_SCALA" 'emit("EXEMPT ' 1
  fl="$(grep -F 'emit("FAIL config ' "$PF_SCALA" | head -n 1)"
  wl="$(grep -F 'emit("WARN config ' "$PF_SCALA" | head -n 1)"
  case "$fl" in
    *'if (hard)'*) ;;
    *) note_fail "the FAIL config emit is not guarded by hard: $fl" ;;
  esac
  case "$wl" in
    *hard*) note_fail "the WARN config emit is guarded by hard: $wl" ;;
    *'warnKeys += key'*) ;;
    *) note_fail "the WARN config emit does not record the key in warnKeys: $wl" ;;
  esac
  f_has "$PF_SCALA" 'else if (hard) emit("FAIL config '
  f_has "$PF_SCALA" 'else { warnKeys += key; emit("WARN config '
  f_before "$PF_SCALA" 'emit("EXEMPT ' 'emit("FAIL config '
  f_before "$PF_SCALA" 'emit("FAIL config ' 'emit("WARN config '
  f_count_is "$PF_SCALA" 'checkValue(loader, key, value, ' 2
  f_has "$PF_SCALA" 'val own = ours.contains(key)'
  f_has "$PF_SCALA" 'checkValue(loader, key, value, if (own) None else exemptReason(key), own)'
  f_has_not "$PF_SCALA" 'if (ours.contains(key)) None else exemptReason(key)'
  lp="$(awk 'index($0, "for ((key, value) <- sparkConf") > 0 { getline; print; exit }' "$PF_SCALA")"
  case "$lp" in
    *'checkValue(loader, key, value, None, true)'*) ;;
    *) note_fail "the class-like spark.* loop does not pass true: '$lp'" ;;
  esac
  f_count_is "$PF_SCALA" 'checkValue(loader, key, value, None, true)' 1
  f_has "$PF_SCALA" 'if (!hard) nodeChecked += 1'
  f_has "$PF_SCALA" 'private val warnKeys = scala.collection.mutable.LinkedHashSet[String]()'
  f_has "$PF_SCALA" 'if (line.startsWith("WARN ")) println(line)'
  f_count_is "$PF_SCALA" 'emit("SUMMARY keys=" + checkedKeys + " classes=" + seen.size + " exempt=" + exemptCount + " nodechecked=" + nodeChecked + " nodewarn=" + warnKeys.size)' 1
  f_has "$PF_SCALA" '" nodechecked=" + nodeChecked'
  f_has "$PF_SCALA" '" nodewarn=" + warnKeys.size'
  case_end

  # F4 and F7 in preflight_evaluate: the record keys, their order, the notes after the record,
  # no pass rule on the node key count, and the first failure by parameter expansion.
  static_begin "14.emr.preflight.evaluate-records-the-node-key-counts-in-order-notes-the-warn-lines-after-them-and-cuts-the-first-failure-without-a-pipe"
  pf_first="first=\"\${fails%%\$'\\n'*}\""
  f_has "$PF_EV_FN" "$pf_first"
  f_count_is "$PF_EV_FN" 'first=' 1
  f_has_not "$PF_EV_FN" '"$fails" | head'
  f_has "$PF_EV_FN" 's/.*nodechecked=\([0-9][0-9]*\).*/\1/p'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_NODE_KEYS_CHECKED "${nodechecked:-unknown}"'
  f_has "$PF_EV_FN" 'kv PREFLIGHT_NODE_KEYS_WARN "$warn_n: $warn_keys"'
  f_count_is "$PF_EV_FN" '$1 == "WARN" && !seen[$3]++' 2
  f_has "$PF_EV_FN" "grep '^WARN ' \"\$result\" | head -n 20"
  f_has "$PF_EV_FN" 'note "preflight ${w:0:300} (report only, node site file key not set by spark-defaults.conf; see preflight-result.txt)"'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_UNLOADABLE_EXEMPT' 'kv PREFLIGHT_NODE_KEYS_CHECKED'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_NODE_KEYS_CHECKED' 'kv PREFLIGHT_NODE_KEYS_WARN'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_NODE_KEYS_WARN' 'kv PREFLIGHT_OVERLAY_KEYS_PROVEN'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_OVERLAY_KEYS_PROVEN' 'kv PREFLIGHT_TARGETS'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_TARGETS' 'kv PREFLIGHT_SMOKE'
  f_before "$PF_EV_FN" 'kv PREFLIGHT_SMOKE' 'note "preflight '
  f_before "$PF_EV_FN" 'checked no class-valued key' 'kv PREFLIGHT_NODE_KEYS_CHECKED'
  f_has_not_re "$PF_EV_FN" 'nodechecked[^"]*" -(eq|ne|ge|gt|le|lt)'
  f_has_not_re "$PF_EV_FN" 'warn_n"? -(eq|ne|ge|gt|le|lt)'
  case_end

  awk 'index($0, "SC_SCRIPT_DIR=") == 1 { exit } { print }' "$EMR_RUN_RAW" > "$hdr"
  static_begin "14.emr.run-s-pipeline.header-letters-the-steps-a-to-f-and-has-no-numbered-lines"
  if [ "$(wc -l < "$hdr" | tr -d ' ')" -lt 10 ]; then note_fail "the header cut out of run-s-pipeline.sh has fewer than 10 lines"; fi
  f_has_not_re "$hdr" '^#   [0-9]+\.'
  for k in 'a. preflight' 'b. Hadoop ' 'c. ER ' 'd. Spark ' 'e. Export ' 'f. publish '; do
    f_count_is "$hdr" "#   $k" 1
  done
  f_before "$hdr" '#   a. preflight' '#   b. Hadoop '
  f_before "$hdr" '#   b. Hadoop ' '#   c. ER '
  f_before "$hdr" '#   c. ER ' '#   d. Spark '
  f_before "$hdr" '#   d. Spark ' '#   e. Export '
  f_before "$hdr" '#   e. Export ' '#   f. publish '
  f_has "$hdr" 'The letters are not the STAGE[n] numbers'
  f_has "$hdr" '(the export is stage 8):'
  f_has "$hdr" 'a sub-step of the spark-stage stage'
  case_end

  static_begin "14.emr.preflight.readme-and-lib-text-state-the-floor-the-class-path-grace-the-report-only-rule-and-what-it-gives-up"
  f_has "$rd" '`SC_PREFLIGHT_TIMEOUT` (15 to 60)'
  f_has "$PF_README_SEC" 'timeout -k 5 20 hadoop classpath --glob'
  f_has "$PF_README_SEC" 'with no leading zero'
  f_has "$PF_README_SEC" 'What report only gives up.'
  f_has "$PF_README_SEC" 'is recorded as `WARN config <key> <class> <why>` and does NOT fail the run'
  f_has "$PF_README_SEC" 'is a hard failure (`FAIL config <key> <class> <why>`)'
  f_has "$PF_README_SEC" 'A `WARN` line is not a failure: a run with WARN lines still records `PREFLIGHT_RESULT=ok`.'
  f_has "$PF_README_SEC" 'Every other class that fails to load for a node-only key is a `WARN`'
  f_has "$PF_README_SEC" '`PREFLIGHT_NODE_KEYS_CHECKED`'
  f_has "$PF_README_SEC" '`PREFLIGHT_NODE_KEYS_WARN`'
  f_has "$PF_README_SEC" "does not prove that the node's keys were examined"
  f_has "$PF_README_SEC" 'the `NOTE: preflight WARN ...` lines on the step log'
  f_has "$PF_README_SEC" 'a new upload record, a new deploy of the S session, a new run id and a new pipeline record'
  f_has "$PF_README_SEC" 'the REPL compiles the preflight script on the node'
  f_has "$PF_README_SEC" 'inferred, not observed'
  f_has "$PF_README_SEC" 'ServiceLoader'
  f_has_not "$PF_README_SEC" 'no compile step'
  f_has_not "$PF_README_SEC" ' 1 to 60'
  f_has_not "$PF_README_SEC" 'timeout 20 hadoop'
  f_has_not "$rd" '`SC_PREFLIGHT_TIMEOUT` (1 to 60)'
  f_has "$lib_raw" 'the REPL compiles the script on the node'
  # Work order 83: infra work order 82 rewrote the evaluate comment to the rehearsal 4 observation, so the
  # old 'INFERRED, not observed, that the REPL keeps going after an error in the script and exits 0' needle
  # is stale. It is replaced by the two comment lines that now state it (the wrap is after 'Java'), and the
  # old inferred wording must be gone.
  f_has "$lib_raw" 'observed locally (rehearsal 4, scenario nocompile: stand-ins, Spark 4.0.5, Scala 2.13.16, Java'
  f_has "$lib_raw" '21, not on a node) that the REPL keeps going after a compile error in the script and exits 0'
  f_has_not "$lib_raw" 'INFERRED, not observed, that the REPL keeps going'
  f_has_not "$lib_raw" 'no compile step'
  case_end
}
static_wo68_cases

### Work order 75: the harness impact of infra-engineer work order 74 (static cases) ----------
# The defect, observed locally (phase14-rehearse-probe-1.log, phase14-rehearse-preflight-1.log): with
# stdin from /dev/null, `spark-shell -i FILE` and `-I FILE` never interpret FILE (the REPL reads EOF
# and quits first), so the old launch was a silent no-op. `:load FILE` fed on stdin works. The launch
# of run_preflight (lib-emr.sh) and of the rehearsal (rehearse-preflight.sh) is now
#   timeout -k 30 N spark-shell --master local[2] --conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"
# These cases read the text of the launch (the line with the spark-shell path and the two after it)
# and refuse the old forms; the dynamic cases further down run run_preflight against a fake
# spark-shell that, like the real REPL, takes the script only from a one-line ':load' stdin.
# SC2016: the single-quoted strings are literal fragments of node scripts and of the README that
# f_has, f_has_not, f_before and f_count_is search for as text.
# shellcheck disable=SC2016
static_wo75_cases() {
  local rd="$REAL_ROOT/scripts/README.md" lib_raw="$EMR_DIR/lib-emr.sh" reh_raw="$EMR_DIR/rehearse-preflight.sh"
  local lib_launch="$CODE_DIR/launch-lib.txt" reh_launch="$CODE_DIR/launch-rehearse.txt" reh_code=""

  # The launch text: the line that starts the spark-shell command, then the next two lines.
  awk 'index($0, "bin/spark-shell\" --master") > 0 { print; getline; print; getline; print; exit }' "$emr_lib" > "$lib_launch"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    awk 'index($0, "bin/spark-shell\" --master") > 0 { print; getline; print; getline; print; exit }' "$reh_code" > "$reh_launch"
  else
    : > "$reh_launch"
  fi

  static_begin "14.emr.preflight.run-preflight-launches-spark-shell-with-the-load-here-string-and-no-i-or-dev-null"
  f_count_is "$emr_lib" 'bin/spark-shell" --master' 1
  if [ "$(wc -l < "$lib_launch" | tr -d ' ')" != "3" ]; then note_fail "the launch text cut out of lib-emr.sh is not 3 lines"; fi
  f_has "$lib_launch" 'timeout -k 30 "$remaining" "$SPARK_HOME/bin/spark-shell" --master "local[2]"'
  f_has "$lib_launch" '--conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"'
  f_has "$lib_launch" ') > "$plog" 2>&1 || rc=$?'
  f_count_is "$lib_launch" '<<< ":load $scala"' 1
  f_count_is "$PF_RUN_FN" '<<< ":load $scala"' 1
  f_count_is "$lib_launch" '$scala' 1
  f_has_not_re "$lib_launch" '(^|[[:space:]])-[iI]([[:space:]]|$)'
  f_has_not_re "$lib_launch" '(^|[[:space:]])-[iI]"'
  f_has_not "$lib_launch" '/dev/null'
  f_has_not_re "$lib_launch" '(^|[^<])<([^<]|$)'
  f_has_not_re "$PF_RUN_FN" '(^|[[:space:]])-[iI]([[:space:]]|$)'
  f_has_not "$PF_RUN_FN" '/dev/null'
  f_has_not "$emr_lib" '-i "$scala"'
  f_has_not "$emr_lib" '-I "$scala"'
  f_has_not "$emr_lib" '2>&1 < /dev/null || rc=$?'
  f_count_is "$emr_lib" 'timeout -k 30 "$remaining"' 1
  f_count_is "$emr_lib" 'timeout -k 30 ' 2
  f_has_not_re "$PF_RUN_FN" '"\$rc" -(ne|gt|lt)'
  f_has "$PF_RUN_FN" '[[ "$rc" -eq 124 || "$rc" -eq 137 ]]'
  f_before "$PF_RUN_FN" '<<< ":load $scala"' '[[ "$rc" -eq 124'
  f_has "$lib_raw" "':load'"
  case_end

  static_begin "14.emr.rehearse-preflight.launches-spark-shell-with-the-load-here-string-and-no-i-or-dev-null"
  if [ -f "$reh_raw" ]; then
    f_count_is "$reh_code" 'bin/spark-shell" --master' 1
    if [ "$(wc -l < "$reh_launch" | tr -d ' ')" != "3" ]; then note_fail "the launch text cut out of rehearse-preflight.sh is not 3 lines"; fi
    f_has "$reh_launch" 'timeout -k 30 "$LIMIT" "$SPARK_HOME/bin/spark-shell" --master "local[2]"'
    f_has "$reh_launch" '--conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"'
    f_has "$reh_launch" ') > "$plog" 2>&1'
    f_count_is "$reh_launch" '<<< ":load $scala"' 1
    f_count_is "$reh_code" '<<< ":load $scala"' 1
    f_count_is "$reh_launch" '$scala' 1
    f_has_not_re "$reh_launch" '(^|[[:space:]])-[iI]([[:space:]]|$)'
    f_has_not_re "$reh_launch" '(^|[[:space:]])-[iI]"'
    f_has_not "$reh_launch" '/dev/null'
    f_has_not_re "$reh_launch" '(^|[^<])<([^<]|$)'
    f_has_not "$reh_code" '-i "$scala"'
    f_has_not "$reh_code" '-I "$scala"'
    f_has_not "$reh_code" '2>&1 < /dev/null'
    f_has "$reh_raw" 'phase14-rehearse-probe-1.log'
    f_has "$reh_raw" "loaded with ':load FILE' fed on stdin (a here-string;"
    f_has "$reh_raw" "with ':load \$scala' on stdin"
    f_has "$reh_raw" "is bound under ':load' on stdin"
    # Work order 77 (infra-engineer work order 76): the stand-in jars. make_jars gives the stand-in
    # ER target a lib directory holding every Spark distribution jar (the real ER class path is the
    # jar plus entity-resolution/lib/*), so the stand-in main class finds the classes its signatures
    # name. preflight_write_targets itself is unchanged. Text only: the harness never runs the script.
    f_count_is "$reh_code" 'make_jars() {' 1
    f_has "$reh_code" 'mkdir -p "$work/jars/hadoop/lib" "$work/jars/entity-resolution/lib" "$work/jars/spark"'
    f_count_is "$reh_code" 'mkdir -p "$work/jars/hadoop/lib" "$work/jars/entity-resolution/lib" "$work/jars/spark"' 1
    f_count_is "$reh_code" 'for j in "$SPARK_HOME"/jars/*.jar; do' 1
    f_has "$reh_code" 'ln -sf "$j" "$work/jars/entity-resolution/lib/${j##*/}"'
    f_count_is "$reh_code" 'ln -sf "$j" "$work/jars/entity-resolution/lib/${j##*/}"' 1
    f_has "$reh_code" 'ln -sf "$CORE_JAR" "$work/jars/entity-resolution/job-er.jar"'
    f_before "$reh_code" 'mkdir -p "$work/jars/hadoop/lib" "$work/jars/entity-resolution/lib"' 'for j in "$SPARK_HOME"/jars/*.jar; do'
    f_before "$reh_code" 'for j in "$SPARK_HOME"/jars/*.jar; do' 'ln -sf "$j" "$work/jars/entity-resolution/lib/${j##*/}"'
    f_before "$reh_code" 'ln -sf "$j" "$work/jars/entity-resolution/lib/${j##*/}"' 'ln -sf "$CORE_JAR" "$work/jars/spark/job-spark.jar"'
    f_has_not "$reh_code" 'preflight_write_targets() {'
    # say_targets RESULT: the four verdicts on one line, then each FAIL target line, called once,
    # straight after the evaluate output and before the scenario verdict is judged.
    f_count_is "$reh_code" 'say_targets() {' 1
    f_count_is "$reh_code" 'say_targets "$result"' 1
    f_count_is "$reh_code" 'say "TARGETS: $line"' 1
    f_has "$reh_code" 'for t in hadoop er spark export; do'
    f_count_is "$reh_code" 'st="MISSING"' 1
    f_count_is "$reh_code" 'st="OK"' 1
    f_count_is "$reh_code" 'st="FAIL"' 1
    f_has "$reh_code" 'grep -qxF "TARGET $t OK" "$result"'
    f_has "$reh_code" 'grep -qE "^FAIL target $t " "$result"'
    f_has "$reh_code" "grep -aE '^FAIL target ' \"\$result\""
    f_before "$reh_code" 'say "preflight_evaluate exit $erc"' 'say_targets "$result"'
    f_before "$reh_code" "grep -aE '^(PREFLIGHT_|NOTE: preflight|EVIDENCE:)' \"\$evlog\"" 'say_targets "$result"'
    f_before "$reh_code" 'say_targets "$result"' 'observed="evaluate-failed"'
    f_before "$reh_code" 'say_targets() {' 'run_scenario() {'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  static_begin "14.emr.preflight.evaluate-no-result-text-names-the-load-form-and-the-repl-that-never-interpreted-the-script"
  # Work order 79: infra work order 78 added the compile-failure cause and the evidence helper to the
  # same die ("... exits 0 with no output): a script that failed to compile under ':load' does the same
  # ... Evidence from preflight.log (the first compiler diagnostic, else the last error line): " then
  # $(preflight_compile_evidence "$plog")). The prefix needle 'wrote no result' is kept as the line before.
  f_has "$PF_EV_FN" '[[ -s "$result" ]] || die "preflight wrote no result'
  f_has "$PF_EV_FN" "[[ -s \"\$result\" ]] || die \"preflight wrote no result (spark-shell exit \$rc; the script is loaded with ':load' from stdin, and a REPL that never interpreted it exits 0 with no output): a script that failed to compile under ':load' does the same, because the object never exists and no result file is created, so read preflight.log. Evidence from preflight.log (the first compiler diagnostic, else the last error line): \$(preflight_compile_evidence \"\$plog\")\""
  f_has "$PF_EV_FN" "grep -qx 'DONE' \"\$result\" || die \"preflight did not reach DONE (spark-shell exit \$rc;"
  f_has_not "$PF_EV_FN" 'preflight wrote no result (spark-shell exit $rc):'
  case_end

  static_begin "14.emr.preflight.readme-names-the-probe-log-the-load-here-string-and-says-i-and-big-i-are-not-interpreted-with-stdin-from-dev-null"
  f_has "$PF_README_SEC" '`phase14-rehearse-probe-1.log`'
  f_has "$PF_README_SEC" '`phase14-rehearse-preflight-1.log`'
  # Work order 77: the README writes `spark-shell -i FILE` (the command name sits inside the same
  # backticks), so the needle starts at the command name, not at the backtick before -i.
  f_has "$PF_README_SEC" '`spark-shell -i FILE` and `-I FILE` with standard input from `/dev/null` do NOT interpret the file at all'
  f_has "$PF_README_SEC" 'How it is launched (work order 74): `spark-shell` gets no `-i`, and its standard input is the here-string `:load <reports>/preflight.scala`'
  f_has "$PF_README_SEC" '`:load` on standard input was observed to load and run a multi-line file'
  f_has "$PF_README_SEC" 'fails `preflight wrote no result`'
  f_has "$PF_README_SEC" 'the `die` text names the `:load` form'
  # Work order 79: the paragraph now records rehearsal 3 (human-run, relayed): the `:load` form was
  # confirmed locally, and what is still unrun is the two scenarios of work order 78. The two old
  # needles ('... has to be rerun).' and 'The `:load` form below has not been run through the rehearsal
  # script yet.') are replaced by the new sentence and by the rehearsal 3 facts below; they are stale,
  # not weakened.
  # Work order 83: infra work order 82 recorded rehearsal 4 (human-run, relayed): the heading sentence now
  # says rehearsal 4 ran the two scenarios of work order 78; the rehearsal 3 sentence continues into the
  # rehearsal 4 sentence after a semicolon (so it no longer ends at '=2`.'); the NOT proven list gained
  # 'a path with whitespace beyond the refusal'. Each needle below is the README's current text, and the
  # superseded sentence ('what is still unrun is the two scenarios') must be gone.
  f_has "$PF_README_SEC" 'Before S run 6 (recommended; the first local rehearsal found the `-i` defect fixed in work order 74, and rehearsal 3 then confirmed the `:load` form locally, and rehearsal 4 ran the two scenarios of work order 78, see below).'
  f_has_not "$PF_README_SEC" 'what is still unrun is the two scenarios of work order 78'
  f_has_not "$PF_README_SEC" 'has to be rerun).'
  f_has_not "$PF_README_SEC" 'has not been run through the rehearsal script yet'
  f_has "$PF_README_SEC" 'Observed, human-run and relayed, not an agent run: rehearsal 3 (`phase14-rehearse-preflight-3.log`, 2026-11-15) compiled and ran the script locally with stand-ins on Spark 4.0.5, Scala 2.13.16 and Java 21; scenarios `clean`, `hard` and `warn` each ended PASS and `hard` recorded `PREFLIGHT_FAILED_COUNT=2`'
  f_has "$PF_README_SEC" 'rehearsal 4 (`phase14-rehearse-preflight-4.log`, same day, same versions, stand-ins) ran all five scenarios, each PASS, including `nocompile` and `nooverride`.'
  f_has "$PF_README_SEC" "NOT proven by it: anything on the node (EMR's jars and site files, timing, Linux), our real job jars, YARN, the KILL path of \`timeout -k 30\`, a path with whitespace beyond the refusal, and that the committer override clears the export on EMR (pending until S run 6)."
  f_has "$PF_README_SEC" "<<< ':load /abs/work/preflight.scala'"
  f_has "$PF_README_SEC" '(not `-i FILE < /dev/null`: observed in `phase14-rehearse-probe-1.log` not to interpret the file)'
  f_has "$PF_README_SEC" 'with `:load <script>` on standard input (a here-string, no `-i`) under `timeout -k 30`'
  f_has "$PF_README_SEC" 'that `spark` is bound under `:load` on standard input'
  f_has "$PF_README_SEC" 'After a change to `lib-emr.sh` the rehearsal has to be run again.'
  f_has_not "$PF_README_SEC" 'in-memory -i'
  f_has_not "$PF_README_SEC" 'in-memory -I'
  f_has_not "$PF_README_SEC" '--conf spark.sql.catalogImplementation=in-memory -i'
  f_has_not "$PF_README_SEC" 'with the Scala file'
  f_count_is "$rd" 'Before S run 6 (recommended;' 1
  f_before "$rd" 'Before S run 6 (recommended;' '### S3 key layout'
  # Work order 79: the old negative needle '[Rr]ehearsal (passed|passes|was run|has run|ran)' forbade any
  # rehearsal claim, which is no longer true (the README now states rehearsal 3, human-run). It is
  # retargeted to what must stay unclaimed: a pass on a node or EMR, and a working committer override.
  f_has_not_re "$PF_README_SEC" '(the node|a node|EMR) (passed|has passed|ran it|has run it)'
  f_has_not_re "$PF_README_SEC" '(override|fix) (worked|has worked|cleared the export|was shown|was proven)'
  f_has_not_re "$PF_README_SEC" '[Rr]ehearsal [0-9]+ (ran|passed|was run) on (the|a) (node|cluster|EMR)'
  case_end
}
static_wo75_cases

### Work order 79: the harness impact of infra-engineer work order 78 (static cases) ----------
# Work order 78 repaired review findings in lib-emr.sh (preflight_compile_evidence, the PREFLIGHT_RESULT=failed
# record before the DONE refusal, the whitespace refusal in run_preflight), in rehearse-preflight.sh (two new
# scenarios nocompile and nooverride, four more unset AWS variables, a filter on every printed line, an
# existing-run-directory and a whitespace refusal) and in scripts/README.md. Static cases read the text; the
# dynamic cases for the same changes sit after the preflight_evaluate and run_preflight cases below. The
# harness never runs rehearse-preflight.sh past its refusals (it needs Spark), so the two new scenarios are
# proved only by the human's rehearsal 4 (each PASS, relayed). Work order 83 follows infra work order 82
# (one-line compile evidence, the say_diagnostic helper, the root whitespace refusal before mkdir) and the
# cases of this block were updated to it.
# SC2016: the single-quoted strings are literal fragments of node scripts and of the README that f_has,
# f_has_not, f_before and f_count_is search for as text.
# shellcheck disable=SC2016
static_wo79_cases() {
  local reh_raw="$EMR_DIR/rehearse-preflight.sh"
  local reh_code="" ce_fn="$CODE_DIR/preflight-compile-evidence-fn.sh" res_fn="$CODE_DIR/rehearse-resolve-new-dir-fn.sh"
  local sd_fn="$CODE_DIR/rehearse-say-diagnostic-fn.sh" lib_raw="$EMR_DIR/lib-emr.sh"
  local k n bad fn

  extract_fn "$emr_lib" preflight_compile_evidence "$ce_fn"

  static_begin "14.emr.preflight.evaluate-records-failed-before-each-no-result-and-no-done-refusal-and-the-no-result-text-has-no-lowercase-error-colon"
  f_count_is "$PF_EV_FN" 'kv PREFLIGHT_RESULT failed' 3
  f_count_is "$PF_EV_FN" '[[ -s "$result" ]] || kv PREFLIGHT_RESULT failed' 1
  f_count_is "$PF_EV_FN" "grep -qx 'DONE' \"\$result\" || kv PREFLIGHT_RESULT failed" 1
  f_before "$PF_EV_FN" '[[ -s "$result" ]] || kv PREFLIGHT_RESULT failed' '[[ -s "$result" ]] || die "preflight wrote no result'
  f_before "$PF_EV_FN" "grep -qx 'DONE' \"\$result\" || kv PREFLIGHT_RESULT failed" "grep -qx 'DONE' \"\$result\" || die \"preflight did not reach DONE"
  f_before "$PF_EV_FN" '[[ -s "$result" ]] || die "preflight wrote no result' "grep -qx 'DONE' \"\$result\" || kv PREFLIGHT_RESULT failed"
  # The rehearsal scenario nocompile accepts a match of the lowercase 'error:' only as the REPL's own text,
  # so no static text of the evaluate function may hold it (an uppercase ERROR in a grep pattern is not it).
  f_has_not "$PF_EV_FN" 'error:'
  f_count_is "$PF_EV_FN" 'preflight_compile_evidence "$plog"' 1
  f_count_is "$PF_EV_FN" 'last_error_line' 1
  f_has "$PF_EV_FN" 'die "preflight did not reach DONE (spark-shell exit $rc; the script compiled and started, since the result file exists, but it stopped before its last line; a compile error would have left no result file at all): $(last_error_line "$plog")"'
  f_count_is "$emr_lib" 'preflight_compile_evidence' 2
  case_end

  # Work order 83: infra work order 82 reduced the function to ONE line, the first diagnostic. The locals
  # l, l1, l2 and t, the cut at 200, the next-line reads, the warning test and the caret test are gone
  # (the REPL prints the source and caret lines BEFORE the error line, observed in rehearsal 4, so the
  # lines after it were only consequences). Their old needles are replaced by absence needles.
  static_begin "14.emr.preflight.compile-evidence-returns-only-the-first-diagnostic-line-then-the-first-error-colon-line-then-last-error-line-and-hides-key-material"
  if [ "$(wc -l < "$ce_fn" | tr -d ' ')" -lt 15 ]; then note_fail "the function cut out of lib-emr.sh has fewer than 15 lines"; fi
  f_has "$ce_fn" "local f=\"\$1\" ex='UnsatisfiedLinkError|buildSupportsOpenssl' pat n=\"\" out=\"\""
  f_has "$ce_fn" "for pat in ':[0-9]+: error:' 'error:'; do"
  f_has "$ce_fn" "n=\"\$(grep -a -n -i -E \"\$pat\" \"\$f\" 2>/dev/null | grep -a -v -E \"\$ex\" | sed -n '1p' | cut -d: -f1)\" || n=\"\""
  f_has "$ce_fn" '[[ "$n" =~ ^[0-9]+$ ]] && break'
  f_has "$ce_fn" 'out="$(sed -n "${n}p" "$f" | cut -c1-300)"'
  f_count_is "$ce_fn" 'cut -c1-300' 1
  f_count_is "$ce_fn" 'sed -n "${n}p" "$f"' 1
  f_count_is "$ce_fn" 'sed -n' 2
  f_has_not "$ce_fn" 'cut -c1-200'
  f_has_not "$ce_fn" 'l1'
  f_has_not "$ce_fn" 'l2'
  f_has_not "$ce_fn" 'n + 1'
  f_has_not "$ce_fn" 'n + 2'
  f_has_not "$ce_fn" 'warning:'
  f_has_not "$ce_fn" '\^'
  f_has_not "$ce_fn" ' / '
  f_has "$ce_fn" 'if [[ -n "$out" ]] && grep -qE "$SC_SECRET_RE" <<< "$out"; then'
  f_has "$ce_fn" 'out="a log line matches a key-material pattern: not printed"'
  f_count_is "$ce_fn" 'last_error_line "$f"' 1
  f_count_is "$ce_fn" 'clean_text "$out"' 1
  # The comment above the function (read from the raw file) says what was observed and that the line
  # number is the REPL's count (review advisory A-ii).
  f_has "$lib_raw" 'observed locally in rehearsal 4, scenario nocompile, Spark 4.0.5, Scala 2.13.16, Java 21,'
  f_has "$lib_raw" 'PreflightRun"), so only the FIRST diagnostic line is returned, alone. The <line> number in it'
  f_has "$lib_raw" "is the REPL's own count of the loaded file, not a line of preflight.scala (observed: 142 for a"
  f_has_not "$lib_raw" 'with at most two following lines'
  f_before "$ce_fn" "for pat in ':[0-9]+: error:' 'error:'; do" 'out="$(sed -n "${n}p" "$f"'
  f_before "$ce_fn" 'if [[ -n "$out" ]] && grep -qE "$SC_SECRET_RE" <<< "$out"; then' 'last_error_line "$f"'
  f_before "$ce_fn" 'if [[ -n "$out" ]] && grep -qE "$SC_SECRET_RE" <<< "$out"; then' 'clean_text "$out"'
  # The grep results are read through sed, never head, so a closed pipe cannot fail the pipeline.
  f_has_not_re "$ce_fn" '[|][[:space:]]*head([[:space:]]|$)'
  f_has_not "$ce_fn" 'mkdir'
  f_has_not "$ce_fn" 'rm -'
  bad="$(grep -oE '>[[:space:]]*"[^"]*"' "$ce_fn" | tr '\n' ' ')"
  [ -z "$bad" ] || note_fail "preflight_compile_evidence redirects to: $bad"
  f_has_not_re "$ce_fn" '(^|[[:space:];|&(])(cp|mv|tee|chmod|chown|sudo|curl|wget|aws|hdfs|yarn)[[:space:]]'
  for k in 's3://' 's3a://' 's3n://' 'hdfs://'; do
    f_has_not "$ce_fn" "$k"
  done
  case_end

  static_begin "14.emr.preflight.run-preflight-refuses-whitespace-in-the-script-path-before-it-makes-anything-and-keeps-its-single-mkdir-and-rm"
  f_has "$PF_RUN_FN" "[[ \"\$scala\" != *[[:space:]]* ]] || die \"preflight: the script path '\$scala' holds whitespace and ':load' splits its argument on whitespace (inferred, not observed), so the reports directory path must hold none\""
  f_count_is "$PF_RUN_FN" '[[:space:]]' 1
  f_before "$PF_RUN_FN" '"$scala" != *[[:space:]]*' 'mkdir -p "$pf/data"'
  f_before "$PF_RUN_FN" '"$scala" != *[[:space:]]*' 'preflight_write_script "$scala"'
  f_before "$PF_RUN_FN" '"$scala" != *[[:space:]]*' 'preflight_write_targets'
  f_before "$PF_RUN_FN" '[[ -x "$SPARK_HOME/bin/spark-shell" ]]' '"$scala" != *[[:space:]]*'
  f_before "$PF_RUN_FN" 'SC_PREFLIGHT_LIMIT_SECONDS" ]]' '"$scala" != *[[:space:]]*'
  f_count_is "$PF_RUN_FN" 'mkdir' 1
  f_count_is "$PF_RUN_FN" 'rm -' 1
  bad="$(grep -oE '>[[:space:]]*"[^"]*"' "$PF_RUN_FN" | tr '\n' ' ')"
  [ "$bad" = '> "$plog" ' ] || note_fail "run_preflight redirects to: $bad"
  case_end

  static_begin "14.emr.rehearse-preflight.runs-five-scenarios-in-order-and-has-the-nocompile-and-nooverride-helpers"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    for k in 'run_scenario clean no "" ok' 'run_scenario hard no "scholarchain.rehearsal.MissingCommitter" hard-fail' 'run_scenario warn yes "" warn' 'run_scenario nocompile no "" no-compile' 'run_scenario nooverride no DROP no-override'; do
      f_count_is "$reh_code" "$k" 1
    done
    n="$(awk '/^run_scenario / { c++ } END { print c + 0 }' "$reh_code")"
    [ "$n" = "5" ] || note_fail "the file calls run_scenario $n times, expected 5"
    f_before "$reh_code" 'run_scenario() {' 'run_scenario clean no "" ok'
    f_before "$reh_code" 'run_scenario clean no "" ok' 'run_scenario hard no'
    f_before "$reh_code" 'run_scenario hard no' 'run_scenario warn yes "" warn'
    f_before "$reh_code" 'run_scenario warn yes "" warn' 'run_scenario nocompile no "" no-compile'
    f_before "$reh_code" 'run_scenario nocompile no "" no-compile' 'run_scenario nooverride no DROP no-override'
    f_before "$reh_code" 'run_scenario nooverride no DROP no-override' 'REHEARSAL_RESULT=PASS'
    f_has "$reh_raw" 'EXPECT(ok|hard-fail|warn|no-compile|no-override)'
    f_has "$reh_raw" '# Five runs of the real spark-shell, each judged by the real preflight_evaluate:'
    f_has_not "$reh_raw" 'Three runs'
    f_has "$reh_raw" '#   nocompile   the script with a deliberate compile error inside the object: expect shell exit'
    f_has "$reh_raw" '#   nooverride  clean, but our committer line is removed and the fake mapred-site names'
    # Work order 83: infra work order 82 replaced the sentence 'Scenarios nocompile and nooverride were
    # written afterwards and have not run.' by the rehearsal 3 and rehearsal 4 facts (human-run, relayed).
    # Each needle is one header line, as the header wraps; the superseded wording must be gone.
    f_has "$reh_raw" '# Observed (human-run, relayed, 2026-11-15, Spark 4.0.5, Scala 2.13.16, Java 21, stand-ins, not'
    f_has "$reh_raw" 'rehearsal 3 (phase14-rehearse-preflight-3.log) ran clean, hard and warn; rehearsal 4'
    f_has "$reh_raw" '# (phase14-rehearse-preflight-4.log) ran all five, each PASS, hard with PREFLIGHT_FAILED_COUNT=2.'
    f_has "$reh_raw" '# In nocompile the spark-shell exit was 0, no result file was written, PREFLIGHT_RESULT=failed,'
    f_has "$reh_raw" "# and the REPL's first diagnostic line (\"<file>:<line>: error: not found: value ...\", its own"
    f_has "$reh_raw" "# line count, not the file's) was the evidence; in nooverride the old-API smoke failed with"
    f_has "$reh_raw" '# ClassNotFoundException for DirectFileOutputCommitter.'
    f_has "$reh_raw" 'line (lower case) as the evidence, printed as a DIAGNOSTIC: line'
    f_has_not "$reh_raw" 'written afterwards'
    f_has_not "$reh_raw" 'have not run'
    f_count_is "$reh_code" 'drop_committer() {' 1
    f_count_is "$reh_code" 'make_nocompile_script() {' 1
    f_count_is "$reh_code" 'NOCOMPILE_MARKER="scholarchain_rehearsal_undefined"' 1
    f_count_is "$reh_code" 'drop_committer "$SC_SPARK_CONF_DIR/spark-defaults.conf"' 1
    f_count_is "$reh_code" 'make_nocompile_script "$scala"' 1
    f_has "$reh_code" 'if [[ "$committer" == DROP ]]; then'
    f_has "$reh_code" 'if [[ "$expect" == no-compile ]]; then'
    f_has "$reh_code" '$1 != "spark.hadoop.mapred.output.committer.class" { print }'
    f_has "$reh_code" 'refuse "could not remove the committer line from $f"'
    f_has "$reh_code" 'print "  private val rehearsalNoCompile: Int = " m'
    f_has "$reh_code" 'refuse "the compile error is not in $f"'
    f_before "$reh_code" 'cp "$scala" "$scala.clean"' 'make_nocompile_script "$scala"'
    # The nocompile verdict: shell exit 0, no result file, record failed, the evidence names the REPL's own
    # lowercase error: text and the marker (the first diagnostic), and carries our die text.
    f_has "$reh_code" 'if [[ "$rc" -eq 0 && "$erc" -ne 0 && ! -s "$result" ]]'
    f_has "$reh_code" "grep -qF 'preflight wrote no result' <<< \"\$evidence\""
    f_has "$reh_code" "grep -qF 'error:' <<< \"\$evidence\""
    f_has "$reh_code" 'grep -qF "$NOCOMPILE_MARKER" <<< "$evidence"'
    # The nooverride verdict: failed, a FAIL line naming the EMR committer, both controls and DONE.
    f_has "$reh_code" "grep -qE '^FAIL (smoke old-api|config [^ ]+) .*DirectFileOutputCommitter' \"\$result\""
    # A stale result file of an earlier run must not be read: removed before the targets and the launch.
    f_count_is "$reh_code" 'rm -f "$result"' 1
    f_before "$reh_code" 'make_nocompile_script "$scala"' 'rm -f "$result"'
    f_before "$reh_code" 'rm -f "$result"' 'preflight_write_targets "$pf/targets.tsv" "$work"'
    f_before "$reh_code" 'rm -f "$result"' 'bin/spark-shell" --master'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  static_begin "14.emr.rehearse-preflight.unsets-the-aws-file-and-role-variables-and-filters-every-printed-line-for-key-material"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    f_has "$reh_code" '-u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE'
    f_has "$reh_code" '-u AWS_CONFIG_FILE -u AWS_SHARED_CREDENTIALS_FILE -u AWS_WEB_IDENTITY_TOKEN_FILE -u AWS_ROLE_ARN'
    for k in AWS_CONFIG_FILE AWS_SHARED_CREDENTIALS_FILE AWS_WEB_IDENTITY_TOKEN_FILE AWS_ROLE_ARN; do
      f_count_is "$reh_code" "$k" 1
      f_count_is "$reh_code" " -u $k" 1
      f_before "$reh_code" " -u $k" 'bin/spark-shell" --master'
    done
    f_has "$reh_raw" '# AWS_SESSION_TOKEN, AWS_PROFILE, AWS_CONFIG_FILE, AWS_SHARED_CREDENTIALS_FILE,'
    f_has "$reh_raw" '# AWS_WEB_IDENTITY_TOKEN_FILE, AWS_ROLE_ARN) from the spark-shell environment.'
    # Every line that goes through tee into the summary and the screen passes the key-material filter first:
    # the shell output errors, the FAIL and ERROR lines, the target lines, the targets log and the evaluate output.
    f_count_is "$reh_code" 'tee -a "$SUMMARY"' 5
    f_count_is "$reh_code" 'grep -avE "$SC_SECRET_RE"' 5
    bad="$(awk 'index($0, "tee -a \"$SUMMARY\"") > 0 { t = index($0, "tee -a \"$SUMMARY\""); f = index($0, "grep -avE \"$SC_SECRET_RE\""); if (f == 0 || f > t) print }' "$reh_code")"
    [ -z "$bad" ] || note_fail "a line is tee'd to the summary without the key-material filter before it: $bad"
    f_has "$reh_code" "grep -a 'EVIDENCE' \"\$d/targets.log\" | grep -avE \"\$SC_SECRET_RE\""
    f_has "$reh_code" "grep -aE '^FAIL target ' \"\$result\" 2>/dev/null | grep -avE \"\$SC_SECRET_RE\""
    f_has "$reh_code" "grep -aE '^(PREFLIGHT_|NOTE: preflight|EVIDENCE:)' \"\$evlog\" | grep -avE \"\$SC_SECRET_RE\""
    f_count_is "$reh_code" 'show_errors() {' 1
    f_count_is "$reh_code" 'show_errors "$plog" "$result"' 2
    # The filter variable is the lib's, sourced before any call.
    f_before "$reh_code" 'source "$LIB"' 'grep -avE "$SC_SECRET_RE"'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  static_begin "14.emr.rehearse-preflight.refuses-a-relative-root-dot-segments-a-root-in-the-repo-whitespace-and-an-existing-run-directory-before-it-makes-a-directory"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    f_has "$reh_code" '[[ "$ROOT_IN" == /* && "$SPARK_SRC" == /* ]] || refuse "SC_REHEARSE_ROOT and SC_REHEARSE_SPARK_DIR must be absolute paths"'
    f_has "$reh_code" 'case "/$ROOT_IN/" in'
    f_has "$reh_code" '*/./* | */../*) refuse "SC_REHEARSE_ROOT must not contain . or .. segments: $ROOT_IN" ;;'
    f_has "$reh_code" 'ROOT="$(resolve_new_dir "${ROOT_IN%/}")"'
    f_has "$reh_code" '"$REPO"/*) refuse "the rehearsal root $ROOT is inside the repository $REPO; choose a directory outside it" ;;'
    f_count_is "$reh_code" 'mkdir -p "$ROOT"' 1
    f_count_is "$reh_code" 'mkdir -p "$RUN" "$SHIM"' 1
    # A6: the in-repo root refusal comes before the root is made, not only before the run directory; the
    # dot-segment and absolute-path refusals come before the root is resolved or made.
    f_before "$reh_code" '[[ "$ROOT_IN" == /* && "$SPARK_SRC" == /* ]]' '*/./* | */../*) refuse'
    f_before "$reh_code" '*/./* | */../*) refuse' 'ROOT="$(resolve_new_dir'
    f_before "$reh_code" '*/./* | */../*) refuse' 'mkdir -p "$ROOT"'
    f_before "$reh_code" 'ROOT="$(resolve_new_dir' '"$REPO"/*) refuse'
    f_before "$reh_code" '"$REPO"/*) refuse' 'mkdir -p "$ROOT"'
    f_before "$reh_code" '"$REPO"/*) refuse' 'mkdir -p "$RUN"'
    # Work order 83: infra work order 82 moved the run directory name and its whitespace refusal ABOVE the
    # root's mkdir, so a root whose own path holds whitespace is refused before it is made. The old order
    # (mkdir -p "$ROOT" before RUN=) is flipped here, not loosened; the new order is also pinned in the
    # whitespace-root case below.
    f_before "$reh_code" 'RUN="$ROOT/preflight-$STAMP"' 'mkdir -p "$ROOT"'
    extract_fn "$reh_code" resolve_new_dir "$res_fn"
    if [ "$(wc -l < "$res_fn" | tr -d ' ')" -lt 8 ]; then note_fail "the resolve_new_dir text cut out of rehearse-preflight.sh has fewer than 8 lines"; fi
    f_has_not "$res_fn" 'mkdir'
    f_has "$res_fn" 'printf '"'"'%s%s'"'"' "$(cd "$p" && pwd -P)" "$rest"'
    # The run directory: whitespace, then an existing directory, then the one mkdir of it.
    f_has "$reh_code" 'RUN="$ROOT/preflight-$STAMP"'
    f_has "$reh_code" 'case "$RUN" in'
    f_has "$reh_code" "*[[:space:]]*) refuse \"the run directory \$RUN holds whitespace, and ':load' splits its argument on whitespace (inferred); choose SC_REHEARSE_ROOT without any\" ;;"
    f_has "$reh_code" '[[ ! -e "$RUN" ]] || refuse "the run directory $RUN already exists (the stamp has 1-second resolution); wait a second and run again"'
    f_before "$reh_code" 'RUN="$ROOT/preflight-$STAMP"' 'case "$RUN" in'
    f_before "$reh_code" 'case "$RUN" in' '[[ ! -e "$RUN" ]]'
    f_before "$reh_code" '[[ ! -e "$RUN" ]]' 'mkdir -p "$RUN" "$SHIM"'
    f_before "$reh_code" 'mkdir -p "$RUN" "$SHIM"' 'ln -sfn "$RUN" "$ROOT/latest"'
    # Work order 83: the whitespace refusal now comes before the root is made (infra work order 82), so a
    # root with whitespace outside the repository is no longer created and then refused. The old pin
    # (mkdir -p "$ROOT" before case "$RUN" in) is flipped. The existing-run-directory test stays after the
    # root's mkdir, because it needs the root to exist.
    f_before "$reh_code" 'case "$RUN" in' 'mkdir -p "$ROOT"'
    f_before "$reh_code" 'mkdir -p "$ROOT"' '[[ ! -e "$RUN" ]]'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  # Work order 83: the README now records rehearsal 4 (human-run, relayed): nocompile and nooverride each
  # PASS, the compile evidence is the first diagnostic LINE alone (no source or caret lines), and the line
  # number is the REPL's count. The case keeps its old name's subject but not its 'written-not-run' claim,
  # so it is renamed; every superseded sentence is asserted absent.
  static_begin "14.emr.preflight.readme-states-the-one-line-no-result-evidence-the-five-scenarios-and-that-nocompile-and-nooverride-passed-in-rehearsal-4"
  f_has "$PF_README_SEC" 'A script that does not compile under `:load` leaves no result file at all, not a result file without `DONE`:'
  f_has "$PF_README_SEC" 'gives as evidence the first compiler diagnostic LINE alone (`preflight_compile_evidence`: the first line of the shape `<file>:<line>: error: <text>`, else the first line holding `error:` in any case, else the last error line; cut to 300 characters; no source or caret lines, because the REPL prints them before the error line, and the diagnostics after the first are consequences such as `not found: value PreflightRun`).'
  f_has "$PF_README_SEC" "The \`<line>\` number in it is the REPL's own count of the loaded file, not a line of \`preflight.scala\` (observed: 142 for a marker on file line 148); \`evaluate.log\` and \`preflight.log\` hold the full text."
  f_has_not "$PF_README_SEC" 'plus the source and caret lines that follow it'
  f_has_not "$PF_README_SEC" 'falling back to the last error line.'
  f_has "$PF_README_SEC" '`PREFLIGHT_RESULT=failed` is recorded in both no-result cases and when `DONE` is missing.'
  f_has "$PF_README_SEC" 'A result file that exists but has no `DONE` means the script compiled and started and then stopped early.'
  f_has "$PF_README_SEC" 'Observed locally in rehearsal 4 (scenario `nocompile`, `phase14-rehearse-preflight-4.log`, human-run and relayed, 2026-11-15; Spark 4.0.5, Scala 2.13.16, Java 21, stand-ins, not a node): a lowercase `<file>:<line>: error: <text>` diagnostic, printed after its source and caret lines, followed by a blank line and then the same for the follow-on `not found: value PreflightRun`'
  f_has "$PF_README_SEC" "Whether a node's shell prints the same is not observed."
  f_has "$PF_README_SEC" 'Observed locally in rehearsal 4 (scenario `nocompile`; stand-ins, Spark 4.0.5, Scala 2.13.16, Java 21, not a node): exit 0, no result file, `PREFLIGHT_RESULT=failed`.'
  f_has_not "$PF_README_SEC" 'not observed until the rehearsal scenario `nocompile` is run'
  f_has "$PF_README_SEC" 'were run by the human as rehearsal 3 on 2026-11-15, and all five, including `nocompile` and `nooverride` of work order 78, as rehearsal 4 the same day, each PASS; human-run and relayed, local, stand-ins).'
  f_has_not "$PF_README_SEC" 'are written, not run, until the human reports them'
  f_has "$PF_README_SEC" 'It runs five times (a run directory that already exists is refused, since its name has 1-second resolution, and so is a path with whitespace, because `:load` splits its argument on whitespace, inferred)'
  f_has "$PF_README_SEC" '- `nocompile` (work order 78; PASS in rehearsal 4, human-run and relayed, local, stand-ins):'
  f_has "$PF_README_SEC" '- `nooverride` (work order 78; PASS in rehearsal 4, human-run and relayed, local, stand-ins):'
  f_has_not "$PF_README_SEC" '(work order 78; written, not run)'
  f_has "$PF_README_SEC" "Expected, and observed: \`spark-shell\` exits 0, there is no result file, \`PREFLIGHT_RESULT=failed\` is recorded, and the evidence is the REPL's own lowercase \`error:\` line that names the undefined value (the first diagnostic), not the follow-on \`not found: value PreflightRun\`."
  f_has "$PF_README_SEC" 'The summary prints it on its own `DIAGNOSTIC:` line (the `EVIDENCE:` line is cut to 400 characters and its die text comes first, so the diagnostic does not survive the cut there).'
  f_has "$PF_README_SEC" "The line number in the diagnostic is the REPL's count (142), the marker is on line 148 of the file."
  f_has "$PF_README_SEC" 'is printed whole, up to 600 characters, on its own `DIAGNOSTIC:` line'
  f_has "$PF_README_SEC" 'the `die` text itself holds no `error:`, so a match cannot come from our own wording'
  f_has "$PF_README_SEC" 'The negative control is `nooverride` below, which passed in rehearsal 4.'
  f_has_not "$PF_README_SEC" 'which is written and not yet run'
  f_has "$PF_README_SEC" 'The whitespace refusal runs on the root path before the root directory is created (after the in-repo refusal), so a root with whitespace outside the repository is not created and then refused.'
  f_has "$PF_README_SEC" '`spark-shell` also gets `AWS_CONFIG_FILE`, `AWS_SHARED_CREDENTIALS_FILE`, `AWS_WEB_IDENTITY_TOKEN_FILE` and `AWS_ROLE_ARN` removed from its environment, next to the key, token and profile variables.'
  f_has "$PF_README_SEC" 'every printed line from the shell output, the evaluate output, the targets log and the result file passes the key-material filter, and a line that matches one of those patterns is dropped.'
  f_has "$PF_README_SEC" 'Exit status 0 when all five behave as expected.'
  f_has "$PF_README_SEC" 'After a change to `lib-emr.sh` the rehearsal has to be run again.'
  for fn in 'three scenarios' 'runs three times' 'all three behave' 'Exit status 0 when all three'; do
    f_has_not "$PF_README_SEC" "$fn"
  done
  case_end

  # Work order 83 (infra work order 82): say_diagnostic RESULT-LOG prints the text after the evidence
  # introducer on its own DIAGNOSTIC: line, because the EVIDENCE: line is cut and its die text comes first.
  # It does not tee, so the tee and grep -avE counts of the key-material case above (5 and 5) are unchanged;
  # it has its own check with grep -qE, replacing a matching text by a fixed one.
  static_begin "14.emr.rehearse-preflight.say-diagnostic-prints-the-text-after-the-evidence-introducer-on-its-own-line-before-the-targets-and-hides-key-material"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    extract_fn "$reh_code" say_diagnostic "$sd_fn"
    if [ "$(wc -l < "$sd_fn" | tr -d ' ')" -lt 8 ]; then note_fail "the say_diagnostic text cut out of rehearse-preflight.sh has fewer than 8 lines"; fi
    f_count_is "$reh_code" 'say_diagnostic() {' 1
    f_count_is "$reh_code" 'say_diagnostic "$evlog"' 1
    f_count_is "$reh_code" 'say "DIAGNOSTIC: ${diag:0:600}"' 1
    f_has "$sd_fn" "diag=\"\$(grep -a '^EVIDENCE: ' \"\$evlog\" 2>/dev/null | sed -n 's/^EVIDENCE: .*Evidence from preflight\\.log ([^)]*): //p' | sed -n '1p')\" || diag=\"\""
    f_has "$sd_fn" '[[ -n "$diag" ]] || return 0'
    f_has "$sd_fn" 'if grep -qE "$SC_SECRET_RE" <<< "$diag"; then'
    f_has "$sd_fn" 'diag="a log line matches a key-material pattern: not printed"'
    f_has "$sd_fn" 'say "DIAGNOSTIC: ${diag:0:600}"'
    f_count_is "$sd_fn" 'SC_SECRET_RE' 1
    f_has_not "$sd_fn" 'tee'
    f_has_not "$sd_fn" '| head'
    f_before "$sd_fn" '[[ -n "$diag" ]] || return 0' 'if grep -qE "$SC_SECRET_RE"'
    f_before "$sd_fn" 'if grep -qE "$SC_SECRET_RE"' 'say "DIAGNOSTIC:'
    # Order in the file: defined before run_scenario; called once, after the evaluate output lines and
    # before the targets line.
    f_before "$reh_code" 'say_diagnostic() {' 'run_scenario() {'
    f_before "$reh_code" "grep -aE '^(PREFLIGHT_|NOTE: preflight|EVIDENCE:)' \"\$evlog\"" 'say_diagnostic "$evlog"'
    f_before "$reh_code" 'say_diagnostic "$evlog"' 'say_targets "$result"'
    # The EVIDENCE: line of the targets log is cut at 600 characters now (it was 400).
    f_has "$reh_code" "grep -a 'EVIDENCE' \"\$d/targets.log\" | grep -avE \"\$SC_SECRET_RE\" | cut -c1-600"
    # The printed diagnostic and the lib's die text must agree: the sed pattern ends at ') ' of the
    # introducer 'Evidence from preflight.log (...): ', which the lib prints once.
    f_count_is "$emr_lib" 'Evidence from preflight.log (' 1
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end

  # Work order 83 (infra work order 82, review): the run directory name and its whitespace refusal sit
  # before the root's mkdir, so a root with whitespace is refused before anything is made. The dynamic
  # case after the run_preflight whitespace cases runs the script to its refusal.
  static_begin "14.emr.rehearse-preflight.refuses-whitespace-in-the-root-path-after-the-in-repo-refusal-and-before-the-root-directory-is-made"
  if [ -f "$reh_raw" ]; then
    reh_code="$(code_of "$reh_raw")"
    f_count_is "$reh_code" 'STAMP="$(date -u +%Y%m%dT%H%M%SZ)"' 1
    f_count_is "$reh_code" 'RUN="$ROOT/preflight-$STAMP"' 1
    f_count_is "$reh_code" 'case "$RUN" in' 1
    f_count_is "$reh_code" 'holds whitespace' 1
    f_has_not "$reh_code" 'mkdir -p "$ROOT_IN"'
    f_before "$reh_code" '"$REPO"/*) refuse' 'STAMP="$(date -u +%Y%m%dT%H%M%SZ)"'
    f_before "$reh_code" 'STAMP="$(date -u +%Y%m%dT%H%M%SZ)"' 'RUN="$ROOT/preflight-$STAMP"'
    f_before "$reh_code" 'RUN="$ROOT/preflight-$STAMP"' 'case "$RUN" in'
    f_before "$reh_code" 'case "$RUN" in' 'mkdir -p "$ROOT"'
    f_before "$reh_code" 'mkdir -p "$ROOT"' '[[ ! -e "$RUN" ]]'
    f_before "$reh_code" '[[ ! -e "$RUN" ]]' 'mkdir -p "$RUN" "$SHIM"'
    f_has "$PF_README_SEC" 'The whitespace refusal runs on the root path before the root directory is created'
  else
    note_fail "scripts/emr/rehearse-preflight.sh does not exist"
  fi
  case_end
}
static_wo79_cases

### Dynamic cases: preflight_evaluate, preflight_write_targets, run_preflight and the exit trap.

# Fake commands for the child's PATH. The timeout logs "TIMEOUT-ARGS <arguments>" to calls.log,
# exits with the number in the file timeout-rc when it is called with -k 30 (the spark-shell
# call, not "timeout -k 5 20 hadoop classpath --glob", which since work order 67 also has a -k
# and so is told apart by its grace of 5), otherwise drops "-k N" and the duration and runs the
# rest. The spark-shell logs its arguments, directory and the interesting environment (an unset
# variable has no ENV line) and keeps a copy of targets.tsv. Work order 75: like the real REPL
# of Spark 4.0.5 (phase14-rehearse-probe-1.log) it takes the script ONLY from its standard input,
# which must be exactly one line ':load <absolute path>'. It keeps the stdin in seen-stdin.txt and
# a copy of the script in seen-script.scala. A launch with -i or -I, with the script as an
# argument, or with any other stdin (empty, /dev/null, two lines) is REJECTED: it logs
# 'FAKE-REJECT <why>' to calls.log, prints the reason, exits 0 as the real REPL does when it never
# interprets the file, and writes no script copy, no data and no result, so the run_preflight case
# fails with 'preflight wrote no result'. An accepted launch creates data under SC_PREFLIGHT_DATA,
# copies spark/result-src to SC_PREFLIGHT_RESULT when that file exists and exits with the number
# in the file shell-rc (default 0).
PF_TPL="$EMR_FX/pf-tpl"
mkdir -p "$PF_TPL"
cat > "$PF_TPL/timeout" <<'EOF'
#!/bin/sh
printf 'TIMEOUT-ARGS %s\n' "$*" >> "${0%/*}/../calls.log"
if [ "$1" = "-k" ] && [ "$2" = "30" ] && [ -f "${0%/*}/../timeout-rc" ]; then
  exit "$(cat "${0%/*}/../timeout-rc")"
fi
if [ "$1" = "-k" ]; then shift 2; fi
shift
exec "$@"
EOF
cat > "$PF_TPL/hadoop" <<'EOF'
#!/bin/sh
printf 'HADOOP-FAKE %s\n' "$*" >> "${0%/*}/../calls.log"
printf '/stub/a.jar:/stub/b.jar'
EOF
cat > "$PF_TPL/spark-shell" <<'EOF'
#!/bin/sh
F="${0%/*}/../.."
C="$F/calls.log"
printf 'SPARK-SHELL %s\n' "$*" >> "$C"
printf 'PWD=%s\n' "$(pwd)" >> "$C"
env | grep -E '^(SPARK_HOME|SPARK_CONF_DIR|HADOOP_CONF_DIR|YARN_CONF_DIR|JAVA_HOME|SC_PREFLIGHT_[A-Z]+|HADOOP_CLASSPATH|SPARK_DIST_CLASSPATH|SPARK_EXTRA_CLASSPATH)=' | sed 's/^/ENV /' >> "$C"
if [ -d "$SC_PREFLIGHT_DATA" ]; then printf 'DATA-DIR present\n' >> "$C"; fi
cp "$SC_PREFLIGHT_DIR/targets.tsv" "$F/seen-targets.tsv"
cat > "$F/seen-stdin.txt"
nlines="$(grep -c '' "$F/seen-stdin.txt")"
line="$(head -n 1 "$F/seen-stdin.txt")"
bad=""
for a in "$@"; do
  case "$a" in
    -i* | -I* | *.scala) [ -n "$bad" ] || bad="the script or -i/-I arrived as an argument: $a" ;;
  esac
done
if [ -z "$bad" ] && [ "$nlines" != "1" ]; then bad="stdin held $nlines line(s), expected exactly one ':load FILE' line"; fi
script=""
if [ -z "$bad" ]; then
  case "$line" in
    ':load /'*) script="${line#:load }" ;;
    *) bad="the stdin line is not ':load <absolute path>': $line" ;;
  esac
fi
if [ -z "$bad" ] && [ ! -f "$script" ]; then bad="the :load file does not exist: $script"; fi
if [ -n "$bad" ]; then
  printf 'FAKE-REJECT %s\n' "$bad" >> "$C"
  printf 'fake spark-shell rejected the launch: %s\n' "$bad"
  exit 0
fi
printf 'LOAD-LINE %s\n' "$line" >> "$C"
cp "$script" "$F/seen-script.scala"
mkdir -p "$SC_PREFLIGHT_DATA/old"
: > "$SC_PREFLIGHT_DATA/old/part-00000"
printf 'fake spark-shell output line\n'
if [ -f "$F/spark/extra-log" ]; then cat "$F/spark/extra-log"; fi
if [ -f "$F/spark/result-src" ]; then cp "$F/spark/result-src" "$SC_PREFLIGHT_RESULT"; fi
if [ -f "$F/shell-rc" ]; then exit "$(cat "$F/shell-rc")"; fi
exit 0
EOF
chmod 755 "$PF_TPL/timeout" "$PF_TPL/hadoop" "$PF_TPL/spark-shell"

# preflight_evaluate over a result file. No function is called with ||, so set -e stays on as
# it does in the real script; a refusal is die's exit 1.
cat > "$CODE_DIR/preflight-eval-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; RES="$3"; CONFDIR="$4"
SC_HOME="$FIX/home"
source "$LIB"
SC_SPARK_CONF_DIR="$CONFDIR"
preflight_evaluate "$RES" "$FIX/preflight.log" 7
printf 'EVAL=ok\n'
EOF

# preflight_write_targets with functions standing in for timeout and hadoop. The stub logs what
# it was called with and what HADOOP_CLASSPATH and JAVA_HOME it saw; the files hadoop-fails and
# hadoop-empty in the fixture make it exit 1 or print nothing.
cat > "$CODE_DIR/preflight-targets-child.sh" <<'EOF'
LIB="$1"; FIX="$2"
SC_HOME="$FIX/home"
source "$LIB"
SPARK_HOME="$FIX/spark"
SC_JAVA_HOME="/opt/harness-jdk"
CALLS="$FIX/calls.log"
timeout() {
  if [ "$1" = "-k" ]; then
    printf 'TIMEOUT %s %s %s\n' "$1" "$2" "$3" >> "$CALLS"
    shift 3
  else
    printf 'TIMEOUT %s\n' "$1" >> "$CALLS"
    shift
  fi
  "$@"
}
hadoop() {
  printf 'HADOOP %s\n' "$*" >> "$CALLS"
  printf 'HADOOP_CLASSPATH=%s\n' "${HADOOP_CLASSPATH-<unset>}" >> "$CALLS"
  printf 'JAVA_HOME=%s\n' "${JAVA_HOME-<unset>}" >> "$CALLS"
  if [ -f "$FIX/hadoop-fails" ]; then return 1; fi
  if [ -f "$FIX/hadoop-empty" ]; then return 0; fi
  printf '/stub/a.jar:/stub/b.jar'
}
printf 'MAIN_HADOOP=%s\n' "$SC_PREFLIGHT_MAIN_HADOOP"
printf 'MAIN_ER=%s\n' "$SC_PREFLIGHT_MAIN_ER"
printf 'MAIN_SPARK=%s\n' "$SC_PREFLIGHT_MAIN_SPARK"
printf 'MAIN_EXPORT=%s\n' "$SC_PREFLIGHT_MAIN_EXPORT"
preflight_write_targets "$FIX/targets.tsv" "$FIX/work"
printf 'WRITTEN=yes\n'
EOF

# run_preflight against the fake spark-shell, directly (not through ||, so set -e stays on).
cat > "$CODE_DIR/preflight-run-child.sh" <<'EOF'
LIB="$1"; FIX="$2"; REP="${3:-$FIX/rep}"
SC_HOME="$FIX/home"
source "$LIB"
PATH="$FIX/bin:$PATH"
export PATH
SPARK_HOME="$FIX/spark"
SC_JAVA_HOME="/opt/harness-jdk"
mkdir -p "$SC_SPARK_CONF_DIR"
cp "$FIX/conf/spark-defaults.conf" "$SC_SPARK_CONF_DIR/spark-defaults.conf"
run_preflight "$FIX/work" "$REP"
printf 'RC=0\n'
EOF

# The same inside the stages and the exit trap, with the number the real script gives the
# spark-stage stage (four). timeout, hadoop and aws are functions for the direct calls (the
# failed-log publish and the checkpoint call "timeout N aws ..."); the spark-shell still runs
# behind the fake timeout script, which env finds on PATH. Every upload is copied to uploads/.
cat > "$CODE_DIR/preflight-trap-child.sh" <<'EOF'
LIB="$1"; FIX="$2"
SC_HOME="$FIX/home"
source "$LIB"
PATH="$FIX/bin:$PATH"
export PATH
SPARK_HOME="$FIX/spark"
SC_JAVA_HOME="/opt/harness-jdk"
UPLOADS="$FIX/uploads"
mkdir -p "$UPLOADS" "$SC_SPARK_CONF_DIR"
cp "$FIX/conf/spark-defaults.conf" "$SC_SPARK_CONF_DIR/spark-defaults.conf"
timeout() { if [ "$1" = "-k" ]; then shift 2; fi; shift; "$@"; }
hadoop() { printf '/stub/a.jar:/stub/b.jar'; }
aws() {
  if [ "${1:-}" = "s3" ] && [ "${2:-}" = "cp" ]; then
    printf 'AWS %s\n' "$*" >> "$FIX/aws-calls.log"
    cp "$3" "$UPLOADS/${4##*/}"
  fi
  return 0
}
install_trap
stage validate-arguments
stage_ok
stage java
stage_ok
stage fetch-inputs
stage_ok
stage spark-stage
register_failure_logs "$FIX/rep" data-bucket run-v1-x
run_preflight "$FIX/work" "$FIX/rep"
stage_ok
printf 'ALL-DONE\n'
EOF

# The spark-defaults.conf that the real write_spark_conf writes, once: the result fixtures take
# their OVERLAY lines from it, so a change of the spark.hadoop.* lines follows into the cases.
mkdir -p "$EMR_FX/pf-conf-home"
run bash "$CODE_DIR/sparkconf-child.sh" "$EMR_DIR/lib-emr.sh" "$EMR_FX/pf-conf-home"
PF_CONF="$EMR_FX/pf-spark-defaults.conf"
cp "$OUT" "$PF_CONF"
PF_OVERLAY_N="$(grep -c '^spark\.hadoop\.' "$PF_CONF")"

# The SUMMARY line of the base result, in the format of work order 67 (nodechecked= and nodewarn=
# are new). The cases that edit it substitute this exact text.
PF_SUMMARY_BASE='SUMMARY keys=7 classes=9 exempt=1 nodechecked=3 nodewarn=0'

# pf_result_base FILE: a result that preflight_evaluate accepts.
pf_result_base() {
  {
    printf 'CONTROL positive OK\n'
    printf 'CONTROL negative OK\n'
    printf 'SITE_FILES core-site.xml mapred-site.xml yarn-site.xml hdfs-site.xml\n'
    awk '$1 ~ /^spark\.hadoop\./ { k = $1; sub(/^spark\.hadoop\./, "", k); v = $0; sub(/^[^ ]* /, "", v); printf "OVERLAY %s %s\n", k, v }' "$PF_CONF"
    printf 'OVERRIDDEN mapred.output.committer.class node=org.apache.hadoop.mapred.DirectFileOutputCommitter ours=org.apache.hadoop.mapred.FileOutputCommitter\n'
    printf 'CLASS OK io.compression.codecs org.apache.hadoop.io.compress.DefaultCodec\n'
    printf 'CLASS OK mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitter\n'
    printf 'EXEMPT fs.s3.impl com.example.S3Impl scheme s3 is not used by the jobs\n'
    printf 'TARGET hadoop OK\n'
    printf 'TARGET er OK\n'
    printf 'TARGET spark OK\n'
    printf 'TARGET export OK\n'
    printf 'SMOKE old-api OK rows=25\n'
    printf 'SMOKE parquet OK rows=25\n'
    printf '%s\n' "$PF_SUMMARY_BASE"
    printf 'DONE\n'
  } > "$1"
}
pf_result_base "$EMR_FX/pf-base-result.txt"
PF_CODECS_LINE="$(grep '^OVERLAY io\.compression\.codecs ' "$EMR_FX/pf-base-result.txt" | head -n 1)"
PF_COMMITTER_LINE='OVERLAY mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitter'

# Result file edits, on $PF_RES: drop an exact line, drop by pattern, replace an exact line, or
# add a line before the DONE line.
pf_drop() {
  { grep -vxF -- "$1" "$PF_RES" || true; } > "$PF_RES.tmp"
  mv "$PF_RES.tmp" "$PF_RES"
}
pf_drop_re() {
  { grep -vE -- "$1" "$PF_RES" || true; } > "$PF_RES.tmp"
  mv "$PF_RES.tmp" "$PF_RES"
}
pf_sub() {
  awk -v f="$1" -v t="$2" '$0 == f { print t; next } { print }' "$PF_RES" > "$PF_RES.tmp"
  mv "$PF_RES.tmp" "$PF_RES"
}
pf_add() {
  { grep -vx 'DONE' "$PF_RES" || true; printf '%s\n' "$1"; printf 'DONE\n'; } > "$PF_RES.tmp"
  mv "$PF_RES.tmp" "$PF_RES"
}
# pf_add_gen N FORMAT: add N lines before the DONE line, each FORMAT with its one %d (or %02d)
# replaced by 1 to N. One awk call, so a few thousand lines cost no more than one.
pf_add_gen() {
  { grep -vx 'DONE' "$PF_RES" || true; awk -v n="$1" -v f="$2" 'BEGIN { s = f "\n"; for (i = 1; i <= n; i++) printf s, i }'; printf 'DONE\n'; } > "$PF_RES.tmp"
  mv "$PF_RES.tmp" "$PF_RES"
}

# pfx_new NAME: a fixture directory PFX with the job jars (all four lib directories), the
# Spark distribution jars, the spark-defaults.conf above, the fake commands and the base result
# as spark/result-src (PF_RES). PF_FIX_FILES lists the fixture files under work/.
pfx_new() {
  local f
  PFX="$EMR_FX/pf-$1"
  PF_RES="$PFX/spark/result-src"
  rm -rf "$PFX"
  mkdir -p "$PFX/bin" "$PFX/spark/bin" "$PFX/spark/jars" "$PFX/home" "$PFX/conf" "$PFX/rep" \
    "$PFX/work/jars/hadoop/lib" "$PFX/work/jars/entity-resolution/lib" "$PFX/work/jars/spark/lib"
  PF_FIX_FILES="work/jars/hadoop/hadoop-job.jar work/jars/hadoop/lib/dep-a.jar work/jars/hadoop/lib/dep-b.jar work/jars/hadoop/lib/hadoop-common-3.jar work/jars/entity-resolution/er-job.jar work/jars/entity-resolution/lib/er-a.jar work/jars/entity-resolution/lib/er-b.jar work/jars/entity-resolution/lib/hadoop-er.jar work/jars/entity-resolution/lib/spark-er.jar work/jars/spark/spark-job.jar work/jars/spark/lib/s-a.jar work/jars/spark/lib/s-b.jar work/jars/spark/lib/spark-core-4.jar work/jars/spark/lib/hadoop-client-3.jar"
  for f in $PF_FIX_FILES; do
    : > "$PFX/$f"
  done
  : > "$PFX/spark/jars/spark-core.jar"
  : > "$PFX/spark/jars/hadoop-client-api.jar"
  : > "$PFX/spark/jars/z.jar"
  cp "$PF_TPL/timeout" "$PF_TPL/hadoop" "$PFX/bin/"
  cp "$PF_TPL/spark-shell" "$PFX/spark/bin/spark-shell"
  chmod 755 "$PFX/bin/timeout" "$PFX/bin/hadoop" "$PFX/spark/bin/spark-shell"
  cp "$PF_CONF" "$PFX/conf/spark-defaults.conf"
  : > "$PFX/calls.log"
  printf 'java.lang.IllegalStateException: harness shell log line\n' > "$PFX/preflight.log"
  pf_result_base "$PF_RES"
}

# pf_entries LIST: the colon separated class path as sorted lines joined by a space.
pf_entries() {
  printf '%s' "$1" | tr ':' '\n' | sort | tr '\n' ' '
}
# pf_sorted_words WORD...: the words as sorted lines joined by a space.
pf_sorted_words() {
  printf '%s\n' "$@" | sort | tr '\n' ' '
}
# pf_col FILE LABEL N: field N of the targets.tsv line for LABEL.
pf_col() {
  awk -F'\t' -v l="$2" -v n="$3" '$1 == l { print $n }' "$1"
}

pf_ev_run() {
  run bash "$CODE_DIR/preflight-eval-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX" "$PF_RES" "${1:-$PFX/conf}"
}
pf_ev_refused() {
  a_rc 1
  a_err_evidence
  a_err "$1"
  a_not_out 'PREFLIGHT_RESULT=ok'
  a_not_out 'EVAL=ok'
  a_trip_empty
}
pf_ev_ok() {
  a_rc 0
  a_out 'EVAL=ok'
  a_out_re '^PREFLIGHT_RESULT=ok$'
  [ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
  a_trip_empty
}

### preflight_evaluate: a good result, then one defect per case.

pfx_new ev-pass
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-complete-result-passes-and-records-every-key"
pf_ev_ok
a_out_re '^PREFLIGHT_SITE_FILES=core-site\.xml mapred-site\.xml yarn-site\.xml hdfs-site\.xml$'
a_out_re '^PREFLIGHT_CONFIG_KEYS_CHECKED=7$'
a_out_re '^PREFLIGHT_CLASS_NAMES_CHECKED=9$'
a_out_re '^PREFLIGHT_KEYS_OVERRIDDEN=mapred\.output\.committer\.class$'
a_out_re '^PREFLIGHT_UNLOADABLE_EXEMPT=1: fs\.s3\.impl$'
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=3$'
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=0:[[:space:]]*$'
a_out_re "^PREFLIGHT_OVERLAY_KEYS_PROVEN=$PF_OVERLAY_N\$"
a_out_re '^PREFLIGHT_TARGETS=hadoop:ok er:ok spark:ok export:ok$'
a_out_re '^PREFLIGHT_SMOKE=old-api:ok parquet:ok rows=25$'
a_order 'PREFLIGHT_UNLOADABLE_EXEMPT=' 'PREFLIGHT_NODE_KEYS_CHECKED=' 'PREFLIGHT_NODE_KEYS_WARN=' 'PREFLIGHT_OVERLAY_KEYS_PROVEN=' 'PREFLIGHT_TARGETS=' 'PREFLIGHT_SMOKE='
a_not_out 'NOTE: preflight WARN'
a_not_out 'PREFLIGHT_FIRST_FAILURE'
[ "$PF_OVERLAY_N" -ge 3 ] || note_fail "the real spark-defaults.conf has only $PF_OVERLAY_N spark.hadoop.* lines"
case_end

pfx_new ev-nofile
rm -f "$PF_RES"
pf_ev_run
# Work order 75: the die text for no result now says how the script is loaded and what a REPL that
# never interpreted it does ('-i' with stdin from /dev/null did exactly that, observed in
# phase14-rehearse-probe-1.log), so the next reader of the step log is not sent to the Scala.
pf_nores_text() {
  a_err "preflight wrote no result (spark-shell exit $1"
  a_err "':load'"
  a_err 'from stdin, and a REPL that never interpreted it exits 0 with no output'
}
case_begin "14.emr.preflight.evaluate.no-result-file-is-refused-with-the-shell-exit-status-the-load-text-and-the-log-tail"
pf_ev_refused 'preflight wrote no result (spark-shell exit 7'
pf_nores_text 7
# Work order 79: the text after the colon now also names the compile-failure cause and the evidence
# source (infra work order 78), so the whole die text up to the evidence is pinned.
a_err "preflight wrote no result (spark-shell exit 7; the script is loaded with ':load' from stdin, and a REPL that never interpreted it exits 0 with no output): a script that failed to compile under ':load' does the same, because the object never exists and no result file is created, so read preflight.log. Evidence from preflight.log (the first compiler diagnostic, else the last error line): "
a_err 'harness shell log line'
a_out_re '^PREFLIGHT_RESULT=failed$'
case_end

pfx_new ev-empty
: > "$PF_RES"
pf_ev_run
case_begin "14.emr.preflight.evaluate.an-empty-result-file-is-refused-with-the-load-text"
pf_ev_refused 'preflight wrote no result (spark-shell exit 7'
pf_nores_text 7
a_out_re '^PREFLIGHT_RESULT=failed$'
case_end

pfx_new ev-nodone
pf_drop DONE
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-result-without-done-is-refused-with-the-log-tail"
pf_ev_refused 'preflight did not reach DONE'
a_err 'spark-shell exit 7'
a_err 'harness shell log line'
# Work order 79 (infra work order 78, finding: the DONE-missing refusal did not record the result):
# the record now says failed before the die, as the no-result and the FAIL-line refusals do.
a_out_re '^PREFLIGHT_RESULT=failed$'
case_end

pfx_new ev-notdone
pf_sub DONE 'NOT DONE'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-line-that-only-contains-done-is-not-the-done-line"
pf_ev_refused 'preflight did not reach DONE'
a_out_re '^PREFLIGHT_RESULT=failed$'
case_end

pfx_new ev-fail
pf_add 'FAIL config mapreduce.fake.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-fail-line-is-refused-and-the-record-names-the-key-and-the-class"
pf_ev_refused 'preflight: 1 check(s) failed, first: FAIL config mapreduce.fake.class com.example.Missing'
a_out_re '^PREFLIGHT_RESULT=failed$'
a_out_re '^PREFLIGHT_FAILED_COUNT=1$'
a_out 'PREFLIGHT_FIRST_FAILURE=FAIL config mapreduce.fake.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
a_order 'PREFLIGHT_RESULT=failed' 'PREFLIGHT_FAILED_COUNT=1' 'PREFLIGHT_FIRST_FAILURE='
case_end

pfx_new ev-fail-two
pf_add 'FAIL target hadoop scholarchain.hadoop.jobs.PipelineTool java.lang.NoClassDefFoundError: x'
pf_add 'FAIL smoke old-api java.lang.ClassNotFoundException: Class org.apache.hadoop.mapred.DirectFileOutputCommitter not found'
pf_ev_run
case_begin "14.emr.preflight.evaluate.two-failures-are-counted-and-the-first-in-file-order-is-recorded"
pf_ev_refused 'preflight: 2 check(s) failed, first: FAIL target hadoop'
a_out_re '^PREFLIGHT_FAILED_COUNT=2$'
a_out 'PREFLIGHT_FIRST_FAILURE=FAIL target hadoop scholarchain.hadoop.jobs.PipelineTool'
a_not_out 'PREFLIGHT_FIRST_FAILURE=FAIL smoke'
case_end

pfx_new ev-error
pf_add 'ERROR config java.lang.RuntimeException: boom'
pf_ev_run
case_begin "14.emr.preflight.evaluate.an-error-line-is-refused-like-a-fail-line"
pf_ev_refused 'preflight: 1 check(s) failed, first: ERROR config java.lang.RuntimeException: boom'
a_out 'PREFLIGHT_FIRST_FAILURE=ERROR config java.lang.RuntimeException: boom'
case_end

pfx_new ev-ctl-pos-missing
pf_drop 'CONTROL positive OK'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-missing-positive-control-is-refused"
pf_ev_refused 'preflight control failed: a class that must load did not'
case_end

pfx_new ev-ctl-pos-failed
pf_sub 'CONTROL positive OK' 'CONTROL positive FAILED'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-failed-positive-control-is-refused-even-though-it-is-not-a-fail-line"
pf_ev_refused 'preflight control failed: a class that must load did not'
case_end

pfx_new ev-ctl-neg-missing
pf_drop 'CONTROL negative OK'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-missing-negative-control-is-refused"
pf_ev_refused 'preflight control failed: a class that cannot exist loaded'
case_end

pfx_new ev-ctl-neg-failed
pf_sub 'CONTROL negative OK' 'CONTROL negative FAILED'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-failed-negative-control-is-refused-so-the-check-cannot-pass-anything"
pf_ev_refused 'preflight control failed: a class that cannot exist loaded'
case_end

pfx_new ev-site-nocore
pf_sub 'SITE_FILES core-site.xml mapred-site.xml yarn-site.xml hdfs-site.xml' 'SITE_FILES mapred-site.xml yarn-site.xml hdfs-site.xml'
pf_ev_run
case_begin "14.emr.preflight.evaluate.site-files-without-core-site-xml-are-refused"
pf_ev_refused 'preflight read no core-site.xml under /etc/hadoop/conf'
case_end

pfx_new ev-site-none
pf_sub 'SITE_FILES core-site.xml mapred-site.xml yarn-site.xml hdfs-site.xml' 'SITE_FILES none'
pf_ev_run
case_begin "14.emr.preflight.evaluate.site-files-none-is-refused"
pf_ev_refused 'preflight read no core-site.xml under /etc/hadoop/conf (SITE_FILES none)'
case_end

pfx_new ev-site-lookalike
pf_sub 'SITE_FILES core-site.xml mapred-site.xml yarn-site.xml hdfs-site.xml' 'SITE_FILES my-core-site.xml mapred-site.xml'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-file-that-only-ends-in-core-site-xml-is-not-core-site-xml"
pf_ev_refused 'preflight read no core-site.xml under /etc/hadoop/conf'
case_end

pfx_new ev-site-missing-line
pf_drop_re '^SITE_FILES '
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-site-files-line-is-refused"
pf_ev_refused 'preflight read no core-site.xml under /etc/hadoop/conf'
case_end

pfx_new ev-site-core-only
pf_sub 'SITE_FILES core-site.xml mapred-site.xml yarn-site.xml hdfs-site.xml' 'SITE_FILES core-site.xml'
pf_ev_run
case_begin "14.emr.preflight.evaluate.core-site-xml-alone-is-enough"
pf_ev_ok
a_out_re '^PREFLIGHT_SITE_FILES=core-site\.xml$'
case_end

pfx_new ev-overlay-committer-missing
pf_drop "$PF_COMMITTER_LINE"
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-missing-committer-overlay-line-is-refused"
pf_ev_refused "preflight: spark.hadoop.mapred.output.committer.class of spark-defaults.conf is not what the Spark driver"
case_end

pfx_new ev-overlay-committer-node
pf_sub "$PF_COMMITTER_LINE" 'OVERLAY mapred.output.committer.class org.apache.hadoop.mapred.DirectFileOutputCommitter'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-driver-that-still-holds-the-node-committer-is-refused-the-s-run-5-case"
pf_ev_refused "preflight: spark.hadoop.mapred.output.committer.class of spark-defaults.conf is not what the Spark driver"
case_end

pfx_new ev-overlay-codecs-node
pf_sub "$PF_CODECS_LINE" 'OVERLAY io.compression.codecs org.apache.hadoop.io.compress.DefaultCodec,com.hadoop.compression.lzo.LzoCodec'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-driver-that-holds-another-codec-list-is-refused"
pf_ev_refused "preflight: spark.hadoop.io.compression.codecs of spark-defaults.conf is not what the Spark driver"
case_end

pfx_new ev-overlay-truncated
pf_sub "$PF_COMMITTER_LINE" 'OVERLAY mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitte'
pf_ev_run
case_begin "14.emr.preflight.evaluate.an-overlay-value-that-is-a-prefix-of-ours-is-refused"
pf_ev_refused "preflight: spark.hadoop.mapred.output.committer.class of spark-defaults.conf is not what the Spark driver"
case_end

pfx_new ev-overlay-none
pf_drop_re '^OVERLAY '
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-overlay-lines-at-all-are-refused-at-the-first-spark-hadoop-key"
pf_ev_refused "preflight: spark.hadoop.io.compression.codecs of spark-defaults.conf is not what the Spark driver"
case_end

pfx_new ev-conf-plain
mkdir -p "$PFX/conf-plain"
printf 'spark.yarn.archive hdfs://harness/spark-jars.zip\nspark.ui.enabled false\n' > "$PFX/conf-plain/spark-defaults.conf"
pf_ev_run "$PFX/conf-plain"
case_begin "14.emr.preflight.evaluate.a-spark-defaults-conf-with-no-spark-hadoop-line-is-refused"
pf_ev_refused 'preflight: spark-defaults.conf has no spark.hadoop.* line to prove the overrides with'
case_end

for pf_l in hadoop er spark export; do
  pfx_new "ev-target-$pf_l"
  pf_drop "TARGET $pf_l OK"
  pf_ev_run
  case_begin "14.emr.preflight.evaluate.a-missing-target-$pf_l-line-is-refused"
  pf_ev_refused "preflight: no 'TARGET $pf_l OK' line, so the $pf_l main class was not proven"
  case_end
done

pfx_new ev-smoke-old-missing
pf_drop 'SMOKE old-api OK rows=25'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-missing-old-api-smoke-line-is-refused"
pf_ev_refused "preflight: no 'SMOKE old-api OK rows=25' line, so the old-api write was not proven"
case_end

pfx_new ev-smoke-pq-missing
pf_drop 'SMOKE parquet OK rows=25'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-missing-parquet-smoke-line-is-refused"
pf_ev_refused "preflight: no 'SMOKE parquet OK rows=25' line, so the parquet write was not proven"
case_end

pfx_new ev-smoke-old-rows
pf_sub 'SMOKE old-api OK rows=25' 'SMOKE old-api OK rows=24'
pf_ev_run
case_begin "14.emr.preflight.evaluate.an-old-api-smoke-line-with-the-wrong-row-count-is-refused"
pf_ev_refused "preflight: no 'SMOKE old-api OK rows=25' line"
case_end

pfx_new ev-smoke-pq-rows
pf_sub 'SMOKE parquet OK rows=25' 'SMOKE parquet OK rows=250'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-parquet-smoke-line-whose-count-only-starts-with-25-is-refused"
pf_ev_refused "preflight: no 'SMOKE parquet OK rows=25' line"
case_end

pfx_new ev-keys-zero
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=0 classes=0 exempt=0 nodechecked=0 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-summary-with-no-class-valued-key-is-refused"
pf_ev_refused 'preflight checked no class-valued key (keys=0 classes=0 exempt=0 nodechecked=0 nodewarn=0)'
case_end

pfx_new ev-keys-zero-node-checked
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=0 classes=0 exempt=0 nodechecked=5 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.node-keys-checked-do-not-excuse-a-summary-with-no-class-valued-key"
pf_ev_refused 'preflight checked no class-valued key (keys=0 classes=0 exempt=0 nodechecked=5 nodewarn=0)'
case_end

pfx_new ev-classes-zero
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=3 classes=0 exempt=0 nodechecked=0 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-summary-with-keys-but-no-class-name-is-refused"
pf_ev_refused 'preflight checked no class-valued key (keys=3 classes=0 exempt=0 nodechecked=0 nodewarn=0)'
case_end

pfx_new ev-summary-missing
pf_drop_re '^SUMMARY '
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-result-without-a-summary-line-is-refused"
pf_ev_refused 'preflight checked no class-valued key'
case_end

pfx_new ev-exempt-only
pf_drop_re '^CLASS OK '
pf_drop_re '^OVERRIDDEN '
pf_add 'EXEMPT fs.s3a.impl com.example.S3A scheme s3a is not used by the jobs'
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=2 classes=2 exempt=2 nodechecked=2 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-result-with-only-exempt-lines-for-the-unloadable-classes-passes-and-lists-the-keys"
pf_ev_ok
a_out_re '^PREFLIGHT_UNLOADABLE_EXEMPT=2: fs\.s3\.impl fs\.s3a\.impl$'
a_out_re '^PREFLIGHT_KEYS_OVERRIDDEN=none$'
a_out_re '^PREFLIGHT_CONFIG_KEYS_CHECKED=2$'
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=2$'
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=0:[[:space:]]*$'
case_end

### preflight_evaluate: WARN lines (work order 67, report only for node-only keys), the node key
### counts and the first failure of a long FAIL list (F4).
# A WARN line is a class that only a node site file names and that does not load. It is recorded
# and noted, never a failure. note prints to stdout (printf 'NOTE: ...'), so pf_ev_ok's empty
# stderr check still holds for a WARN result.

PF_WARN_LINE='WARN config yarn.ipc.rpc.class com.example.MissingRpc java.lang.ClassNotFoundException: com.example.MissingRpc'
PF_WARN_NOTE_TAIL='(report only, node site file key not set by spark-defaults.conf; see preflight-result.txt)'

pfx_new ev-warn-one
pf_add "$PF_WARN_LINE"
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=7 classes=9 exempt=1 nodechecked=3 nodewarn=1'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-warn-line-passes-with-result-ok-records-the-key-and-notes-it-and-records-no-failure"
pf_ev_ok
a_out_re '^PREFLIGHT_RESULT=ok$'
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=1: yarn\.ipc\.rpc\.class$'
a_out "NOTE: preflight $PF_WARN_LINE $PF_WARN_NOTE_TAIL"
a_not_out 'PREFLIGHT_FIRST_FAILURE'
a_not_out 'PREFLIGHT_FAILED_COUNT'
a_not_out 'PREFLIGHT_RESULT=failed'
a_order 'PREFLIGHT_NODE_KEYS_WARN=' 'PREFLIGHT_SMOKE=' 'NOTE: preflight WARN config yarn.ipc.rpc.class'
case_end

pfx_new ev-warn-dedupe
pf_add 'WARN config yarn.ipc.rpc.class com.example.MissingRpc java.lang.ClassNotFoundException: com.example.MissingRpc'
pf_add 'WARN config mapreduce.fake.class com.example.MissingFake java.lang.ClassNotFoundException: com.example.MissingFake'
pf_add 'WARN config yarn.ipc.rpc.class com.example.MissingRpcTwo java.lang.ClassNotFoundException: com.example.MissingRpcTwo'
pf_ev_run
case_begin "14.emr.preflight.evaluate.two-warn-lines-for-one-key-and-one-for-another-count-distinct-keys-in-file-order"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=2: yarn\.ipc\.rpc\.class mapreduce\.fake\.class$'
[ "$(grep -c '^NOTE: preflight WARN config ' "$OUT")" = "3" ] || note_fail "expected one NOTE per WARN line (3), found $(grep -c '^NOTE: preflight WARN config ' "$OUT")"
a_out 'NOTE: preflight WARN config yarn.ipc.rpc.class com.example.MissingRpcTwo '
case_end

pfx_new ev-warn-and-fail
pf_add "$PF_WARN_LINE"
pf_add 'FAIL config mapreduce.fake.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-warn-line-and-a-fail-line-is-refused-and-the-first-failure-is-the-fail-line"
pf_ev_refused 'preflight: 1 check(s) failed, first: FAIL config mapreduce.fake.class com.example.Missing'
a_out_re '^PREFLIGHT_RESULT=failed$'
a_out_re '^PREFLIGHT_FAILED_COUNT=1$'
a_out 'PREFLIGHT_FIRST_FAILURE=FAIL config mapreduce.fake.class com.example.Missing'
a_not_out 'PREFLIGHT_FIRST_FAILURE=WARN'
a_not_out 'PREFLIGHT_NODE_KEYS_WARN'
a_not_out 'NOTE: preflight WARN'
case_end

pfx_new ev-warn-no-excuse
pf_add "$PF_WARN_LINE"
pf_drop 'SMOKE parquet OK rows=25'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-warn-line-does-not-excuse-a-missing-smoke-line"
pf_ev_refused "preflight: no 'SMOKE parquet OK rows=25' line, so the parquet write was not proven"
a_not_out 'PREFLIGHT_NODE_KEYS_WARN'
case_end

pfx_new ev-warning-lookalike
pf_add 'WARNING config lookalike.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-line-that-only-starts-with-warn-is-not-a-warn-line"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=0:[[:space:]]*$'
a_not_out 'NOTE: preflight WARN'
case_end

pfx_new ev-nodechecked-3-1
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=7 classes=9 exempt=1 nodechecked=3 nodewarn=1'
pf_ev_run
case_begin "14.emr.preflight.evaluate.nodechecked-3-nodewarn-1-gives-node-keys-checked-3"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=3$'
case_end

pfx_new ev-nodechecked-12
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=7 classes=9 exempt=1 nodechecked=12 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-two-digit-nodechecked-is-read-whole"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=12$'
a_out_re '^PREFLIGHT_CONFIG_KEYS_CHECKED=7$'
case_end

pfx_new ev-nodechecked-0
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=7 classes=9 exempt=1 nodechecked=0 nodewarn=0'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-node-key-examined-is-recorded-as-0-and-is-not-a-failure"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=0$'
case_end

pfx_new ev-nodechecked-unknown
pf_sub "$PF_SUMMARY_BASE" 'SUMMARY keys=7 classes=9 exempt=1'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-summary-without-nodechecked-is-recorded-as-unknown-and-passes"
pf_ev_ok
a_out_re '^PREFLIGHT_NODE_KEYS_CHECKED=unknown$'
a_out_re '^PREFLIGHT_CONFIG_KEYS_CHECKED=7$'
case_end

pfx_new ev-warn-cap
pf_add_gen 25 'WARN config n%02d.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_ev_run
case_begin "14.emr.preflight.evaluate.25-warn-keys-are-all-recorded-and-only-20-notes-are-printed"
pf_ev_ok
pf_want="$(awk 'BEGIN { for (i = 1; i <= 25; i++) printf "%sn%02d.class", (i > 1 ? " " : ""), i }')"
grep -qxF "PREFLIGHT_NODE_KEYS_WARN=25: $pf_want" "$OUT" || note_fail "the record does not list the 25 keys in order: $(grep '^PREFLIGHT_NODE_KEYS_WARN=' "$OUT" | cut -c1-120)"
[ "$(grep -c '^NOTE: preflight WARN config ' "$OUT")" = "20" ] || note_fail "expected 20 NOTE lines, found $(grep -c '^NOTE: preflight WARN config ' "$OUT")"
a_out 'NOTE: preflight WARN config n01.class '
a_out 'NOTE: preflight WARN config n20.class '
a_not_out 'NOTE: preflight WARN config n21.class '
case_end

pfx_new ev-warn-long
pf_w="WARN config long.class com.example.Missing $(pi_rep 500)"
pf_add "$pf_w"
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-warn-note-keeps-the-first-300-characters-of-the-line"
pf_ev_ok
a_out "NOTE: preflight ${pf_w:0:300} $PF_WARN_NOTE_TAIL"
a_not_out "${pf_w:0:301}"
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=1: long\.class$'
case_end

pfx_new ev-fail-single-whole
pf_add 'FAIL config single.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_ev_run
case_begin "14.emr.preflight.evaluate.a-single-fail-line-is-kept-whole-as-the-first-failure"
pf_ev_refused 'preflight: 1 check(s) failed, first: FAIL config single.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing (every result line is in preflight-result.txt)'
grep -qxF 'PREFLIGHT_FIRST_FAILURE=FAIL config single.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing' "$OUT" || note_fail "the first failure was cut or changed: $(grep '^PREFLIGHT_FIRST_FAILURE=' "$OUT" | cut -c1-160)"
case_end

pfx_new ev-fail-3000
pf_add_gen 3000 'FAIL config many.class com.example.Missing #%d'
pf_ev_run
case_begin "14.emr.preflight.evaluate.3000-fail-lines-exit-1-not-141-record-failed-and-keep-the-first-failure"
pf_ev_refused 'preflight: 3000 check(s) failed, first: FAIL config many.class com.example.Missing #1 (every result line is in preflight-result.txt)'
a_out_re '^PREFLIGHT_RESULT=failed$'
a_out_re '^PREFLIGHT_FAILED_COUNT=3000$'
grep -qxF 'PREFLIGHT_FIRST_FAILURE=FAIL config many.class com.example.Missing #1' "$OUT" || note_fail "PREFLIGHT_FIRST_FAILURE is not the first line: $(grep '^PREFLIGHT_FIRST_FAILURE=' "$OUT" | cut -c1-120)"
[ "$RC" != "141" ] || note_fail "exit 141: the first failure was cut with a pipe that closed early"
case_end

### preflight_evaluate: the evidence of a run that left no result file (work order 79, after infra
### work order 78; updated in work order 83 after infra work order 82). A script that does not compile
### under ':load' leaves no result file; the die then carries preflight_compile_evidence of preflight.log:
### the FIRST diagnostic LINE alone, of the shape '<file>:<line>: error: <text>' (no source or caret
### line, no following line), else the first line holding 'error:' in any case, else last_error_line;
### cut to 300 characters. The non-fatal openssl UnsatisfiedLinkError is skipped; a matching line is
### replaced by a fixed text, and a key-material pattern on a line the function does not read cannot
### reach the output. The record says PREFLIGHT_RESULT=failed before the die in every no-result case.
### The REPL layout (observed in rehearsal 4, human-run and relayed: the source and caret lines come
### BEFORE the error line, then a blank line, then the follow-on 'not found: value PreflightRun') is
### the fixture ev-ce-observed-layout; the other fixtures are shaped text for the shell code.

# pf_log LINE...: the shell output the evaluate reads (PFX/preflight.log), one argument per line.
pf_log() {
  printf '%s\n' "$@" > "$PFX/preflight.log"
}
# pf_rep CHAR N: N copies of CHAR, no newline.
pf_rep() {
  awk -v c="$1" -v n="$2" 'BEGIN { for (i = 0; i < n; i++) printf "%s", c }'
}
# pf_nores_ev: the refusal for no result file with the evidence introducer, and the record says failed.
pf_nores_ev() {
  pf_ev_refused 'preflight wrote no result (spark-shell exit 7'
  a_out_re '^PREFLIGHT_RESULT=failed$'
  a_err 'Evidence from preflight.log (the first compiler diagnostic, else the last error line): '
}
# pf_ev_absent TEXT: the text is not in the refusal.
pf_ev_absent() {
  if grep -qF -- "$1" "$ERR"; then note_fail "the evidence holds '$1'"; fi
}

pfx_new ev-ce-first
rm -f "$PF_RES"
pf_log 'Spark context available as sc' \
  'x.scala:12: error: not found: value foo' \
  '  val y = foo' \
  '          ^' \
  'x.scala:99: error: not found: value PreflightRun' \
  'PreflightRun.run()' \
  '^'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-is-the-first-compiler-diagnostic-line-alone-without-the-source-and-caret-lines-after-it-and-not-the-follow-on"
pf_nores_ev
a_err ': x.scala:12: error: not found: value foo'
grep -qE 'x[.]scala:12: error: not found: value foo$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'val y = foo'
pf_ev_absent '/ ^'
pf_ev_absent 'PreflightRun'
pf_ev_absent 'x.scala:99'
pf_ev_absent 'Spark context available'
case_end

pfx_new ev-ce-two-errors
rm -f "$PF_RES"
pf_log 'x.scala:5: error: first thing' 'x.scala:6: error: not found: value PreflightRun'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-is-the-first-diagnostic-line-alone-when-the-next-line-is-another-error"
pf_nores_ev
a_err ': x.scala:5: error: first thing'
grep -qE 'x[.]scala:5: error: first thing$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'PreflightRun'
pf_ev_absent ' / x.scala:6'
case_end

pfx_new ev-ce-warning-next
rm -f "$PF_RES"
pf_log 'x.scala:4: error: bad' 'x.scala:5: warning: meh' '  ^'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-is-the-diagnostic-line-alone-and-leaves-out-a-warning-line-after-it"
pf_nores_ev
a_err ': x.scala:4: error: bad'
grep -qE 'x[.]scala:4: error: bad$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'warning'
pf_ev_absent 'meh'
case_end

pfx_new ev-ce-last-line
rm -f "$PF_RES"
pf_log 'info line' 'x.scala:7: error: dangling'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-is-the-diagnostic-alone-when-it-is-the-last-line-of-the-log"
pf_nores_ev
a_err ': x.scala:7: error: dangling'
grep -qE 'x[.]scala:7: error: dangling$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
case_end

pfx_new ev-ce-long-diagnostic
rm -f "$PF_RES"
pf_log "x.scala:1: error: $(pf_rep d 382)"
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-cuts-the-diagnostic-line-to-300-characters"
pf_nores_ev
a_err "x.scala:1: error: $(pf_rep d 282)"
pf_ev_absent "$(pf_rep d 283)"
case_end

pfx_new ev-ce-long-source
rm -f "$PF_RES"
pf_log 'x.scala:1: error: e' "$(pf_rep s 250)"
pf_ev_run
# Work order 83: the 200 character cut of a source line is gone with the source line itself (infra work
# order 82), so this fixture now proves that a long line after the diagnostic is not in the evidence at all.
case_begin "14.emr.preflight.evaluate.no-result-evidence-leaves-out-a-long-source-line-after-the-diagnostic"
pf_nores_ev
a_err ': x.scala:1: error: e'
grep -qE 'x[.]scala:1: error: e$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent "$(pf_rep s 20)"
case_end

# The openssl UnsatisfiedLinkError that Spark logs is not the failure. Here it carries the diagnostic
# shape, so without the skip it would be the first match and win.
pfx_new ev-ce-openssl-first
rm -f "$PF_RES"
pf_log 'WARN NativeCodeLoader: java.lang.UnsatisfiedLinkError: Native.java:5: error: buildSupportsOpenssl missing' \
  'x.scala:12: error: not found: value foo' \
  '  val y = foo' \
  '          ^'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-skips-an-openssl-line-that-has-the-diagnostic-shape-the-real-diagnostic-line-wins"
pf_nores_ev
a_err ': x.scala:12: error: not found: value foo'
grep -qE 'x[.]scala:12: error: not found: value foo$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'val y = foo'
pf_ev_absent 'buildSupportsOpenssl'
pf_ev_absent 'Native.java'
case_end

pfx_new ev-ce-openssl-tier-two
rm -f "$PF_RES"
pf_log 'WARN NativeCodeLoader: java.lang.UnsatisfiedLinkError: buildSupportsOpenssl error: skip me' 'ERROR: boom'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-second-tier-skips-an-openssl-line-too"
pf_nores_ev
a_err ': ERROR: boom'
pf_ev_absent 'skip me'
pf_ev_absent 'buildSupportsOpenssl'
case_end

# Second tier: no diagnostic shape, so the FIRST line holding error: in any case (not the last error
# line, which last_error_line would give: the RuntimeException below).
pfx_new ev-ce-tier-two
rm -f "$PF_RES"
pf_log 'Welcome' 'ERROR: first problem' 'detail line' 'java.lang.RuntimeException: later'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-second-tier-is-the-first-error-colon-line-alone-in-any-case-not-the-last-error-line"
pf_nores_ev
a_err ': ERROR: first problem'
grep -qE 'ERROR: first problem$' "$ERR" || note_fail "the evidence does not end at the error line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'detail line'
pf_ev_absent 'later'
pf_ev_absent 'Welcome'
case_end

# A planted secret never reaches the output: the chosen lines are replaced by the fixed text.
pfx_new ev-ce-secret-diagnostic
rm -f "$PF_RES"
pf_log "x.scala:3: error: unexpected token $FAKE_AKIA" '  val k = 1' '  ^'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-with-key-material-in-the-diagnostic-line-is-the-fixed-text"
pf_nores_ev
a_err ': a log line matches a key-material pattern: not printed'
pf_ev_absent 'unexpected token'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

pfx_new ev-ce-secret-source
rm -f "$PF_RES"
pf_log 'x.scala:3: error: bad value' "  val k = \"$FAKE_AKIA\"" '  ^'
pf_ev_run
# Work order 83: the function no longer reads the source line, so a key-material pattern there is not read
# and not printed; the evidence is the diagnostic line, which holds no pattern. The secret still must not
# reach any output.
case_begin "14.emr.preflight.evaluate.no-result-evidence-does-not-read-a-key-material-source-line-after-the-diagnostic-and-never-prints-it"
pf_nores_ev
a_err ': x.scala:3: error: bad value'
grep -qE 'x[.]scala:3: error: bad value$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'key-material'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

# No diagnostic and no error: colon: last_error_line decides, as before.
pfx_new ev-ce-fallback-last
rm -f "$PF_RES"
pf_log 'info line' 'java.lang.RuntimeException: boom one' 'java.lang.RuntimeException: boom two' 'trailing info'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-falls-back-to-the-last-error-line-when-no-diagnostic-matches"
pf_nores_ev
a_err ': java.lang.RuntimeException: boom two'
pf_ev_absent 'boom one'
case_end

pfx_new ev-ce-fallback-secret
rm -f "$PF_RES"
pf_log "java.lang.RuntimeException: leak $FAKE_AKIA"
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-fallback-with-key-material-is-the-fixed-text"
pf_nores_ev
a_err ': a log line matches a key-material pattern: not printed'
pf_ev_absent 'leak'
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
case_end

pfx_new ev-ce-empty-log
rm -f "$PF_RES"
: > "$PFX/preflight.log"
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-for-an-empty-log-says-so"
pf_nores_ev
a_err ': no error line found and the log is empty or unreadable'
case_end

pfx_new ev-ce-missing-log
rm -f "$PF_RES" "$PFX/preflight.log"
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-for-a-missing-log-says-so"
pf_nores_ev
a_err ': no error line found and the log is empty or unreadable'
case_end

# Work order 83: the layout observed in rehearsal 4 (human-run and relayed; scenario nocompile, Spark 4.0.5,
# Scala 2.13.16, Java 21, stand-ins): the REPL echoes the source line and a caret line, THEN the error line
# (its number is the REPL's own count, 142 for a marker on file line 148), a blank line, then the same for
# the follow-on 'not found: value PreflightRun'. The evidence is exactly the first error line.
pfx_new ev-ce-observed-layout
rm -f "$PF_RES"
pf_log 'Spark context available as sc' \
  '  val y = scholarchain_rehearsal_undefined' \
  '                       ^' \
  'x.scala:142: error: not found: value scholarchain_rehearsal_undefined' \
  '' \
  '  PreflightRun.run()' \
  '  ^' \
  'x.scala:143: error: not found: value PreflightRun'
pf_ev_run
{ cat "$OUT"; cat "$ERR"; } > "$PFX/evaluate-merged.log"
case_begin "14.emr.preflight.evaluate.no-result-evidence-for-the-observed-repl-layout-is-exactly-the-first-error-line"
pf_nores_ev
a_err ': x.scala:142: error: not found: value scholarchain_rehearsal_undefined'
grep -qE 'x[.]scala:142: error: not found: value scholarchain_rehearsal_undefined$' "$ERR" || note_fail "the evidence does not end at the first error line: $(tail -n 1 "$ERR" | cut -c1-200)"
if grep -qE ' / $' "$ERR"; then note_fail "the evidence ends with ' / ': a joined line is left over"; fi
pf_ev_absent 'PreflightRun'
pf_ev_absent 'val y ='
pf_ev_absent 'x.scala:143'
pf_ev_absent 'Spark context available'
case_end

# Work order 83 (review advisory A-iii): a whitespace-only line right after the diagnostic gives the same
# evidence as no line at all.
pfx_new ev-ce-blank-after
rm -f "$PF_RES"
pf_log 'x.scala:5: error: first thing' '   ' '  PreflightRun.run()' 'x.scala:6: error: not found: value PreflightRun'
pf_ev_run
case_begin "14.emr.preflight.evaluate.no-result-evidence-is-the-same-when-a-whitespace-only-line-follows-the-diagnostic"
pf_nores_ev
a_err ': x.scala:5: error: first thing'
grep -qE 'x[.]scala:5: error: first thing$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
pf_ev_absent 'PreflightRun'
pf_ev_absent 'x.scala:6'
case_end

# Work order 83: the rehearsal's say_diagnostic (infra work order 82), cut out of the real
# rehearse-preflight.sh and run in a child shell against evaluate output, because the helper cannot be
# reached any other way (the script needs Spark past its refusals). The fixture is the evaluate output of
# the observed layout above, so the sed pattern of the helper is checked against the die text of the real
# lib-emr.sh, not against a hand-written copy of it. say is a printf stand-in; SC_SECRET_RE is the lib's.
cat > "$CODE_DIR/rehearse-say-diagnostic-child.sh" <<'EOF'
LIB="$1"; FN="$2"; EV="$3"
SC_HOME="${EV%/*}/home"
source "$LIB"
say() { printf '%s\n' "$*"; }
source "$FN"
say_diagnostic "$EV"
printf 'RC=0\n'
EOF

pf_sd_fn="$CODE_DIR/rehearse-say-diagnostic-run-fn.sh"
extract_fn "$(code_of "$EMR_DIR/rehearse-preflight.sh")" say_diagnostic "$pf_sd_fn"

pfx_new sd-observed
cp "$EMR_FX/pf-ev-ce-observed-layout/evaluate-merged.log" "$PFX/evlog.txt"
run bash "$CODE_DIR/rehearse-say-diagnostic-child.sh" "$EMR_DIR/lib-emr.sh" "$pf_sd_fn" "$PFX/evlog.txt"
case_begin "14.emr.rehearse-preflight.say-diagnostic-prints-the-first-error-line-of-the-real-die-text-on-its-own-line"
[ -s "$pf_sd_fn" ] || note_fail "say_diagnostic was not cut out of rehearse-preflight.sh (the static case reports why)"
a_rc 0
a_out_re '^RC=0$'
[ "$(grep -c '^DIAGNOSTIC: ' "$OUT")" = "1" ] || note_fail "expected exactly one DIAGNOSTIC: line, found $(grep -c '^DIAGNOSTIC: ' "$OUT")"
grep -qxF 'DIAGNOSTIC: x.scala:142: error: not found: value scholarchain_rehearsal_undefined' "$OUT" || note_fail "the DIAGNOSTIC: line is not exactly the first error line: $(grep '^DIAGNOSTIC: ' "$OUT" | cut -c1-200)"
if grep -qF 'preflight wrote no result' "$OUT"; then note_fail "the die text before the introducer was printed"; fi
a_trip_empty
case_end

pfx_new sd-secret
printf 'EVIDENCE: preflight wrote no result (spark-shell exit 0): x. Evidence from preflight.log (the first compiler diagnostic, else the last error line): %s\n' "x.scala:3: error: leak $FAKE_AKIA" > "$PFX/evlog.txt"
run bash "$CODE_DIR/rehearse-say-diagnostic-child.sh" "$EMR_DIR/lib-emr.sh" "$pf_sd_fn" "$PFX/evlog.txt"
case_begin "14.emr.rehearse-preflight.say-diagnostic-replaces-a-diagnostic-with-key-material-by-the-fixed-text-and-never-prints-it"
a_rc 0
grep -qxF 'DIAGNOSTIC: a log line matches a key-material pattern: not printed' "$OUT" || note_fail "the fixed text was not printed: $(grep '^DIAGNOSTIC: ' "$OUT" | cut -c1-120)"
if grep -qF -- "$FAKE_AKIA" "$OUT" "$ERR"; then note_fail "the matched text was printed"; fi
a_trip_empty
case_end

pfx_new sd-long
printf 'EVIDENCE: preflight wrote no result (spark-shell exit 0): x. Evidence from preflight.log (the first compiler diagnostic, else the last error line): %s\n' "$(pf_rep d 700)" > "$PFX/evlog.txt"
run bash "$CODE_DIR/rehearse-say-diagnostic-child.sh" "$EMR_DIR/lib-emr.sh" "$pf_sd_fn" "$PFX/evlog.txt"
case_begin "14.emr.rehearse-preflight.say-diagnostic-cuts-the-text-at-600-characters"
a_rc 0
grep -qxF "DIAGNOSTIC: $(pf_rep d 600)" "$OUT" || note_fail "the DIAGNOSTIC: line is not 600 characters of the text: $(grep '^DIAGNOSTIC: ' "$OUT" | cut -c1-40) ($(grep '^DIAGNOSTIC: ' "$OUT" | wc -c | tr -d ' ') bytes)"
if grep -qF "$(pf_rep d 601)" "$OUT"; then note_fail "more than 600 characters were printed"; fi
a_trip_empty
case_end

pfx_new sd-none
printf 'PREFLIGHT_RESULT=ok\nNOTE: preflight something\n' > "$PFX/evlog.txt"
run bash "$CODE_DIR/rehearse-say-diagnostic-child.sh" "$EMR_DIR/lib-emr.sh" "$pf_sd_fn" "$PFX/evlog.txt"
case_begin "14.emr.rehearse-preflight.say-diagnostic-prints-nothing-and-does-not-fail-when-there-is-no-evidence-line"
a_rc 0
a_out_re '^RC=0$'
a_not_out 'DIAGNOSTIC:'
a_trip_empty
case_end

### preflight_write_targets: four lines, the class paths and the dies.

pfx_new tg
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.four-lines-in-order-with-three-fields-and-the-main-classes"
mr_ok
a_out 'WRITTEN=yes'
[ "$(wc -l < "$PFX/targets.tsv" | tr -d ' ')" = "4" ] || note_fail "targets.tsv does not have exactly four lines"
[ "$(awk -F'\t' '{ printf "%s%s:%s", (NR > 1 ? " " : ""), $1, NF }' "$PFX/targets.tsv")" = "hadoop:3 er:3 spark:3 export:3" ] || note_fail "labels or field counts: $(awk -F'\t' '{ printf "%s:%s ", $1, NF }' "$PFX/targets.tsv")"
for pf_l in hadoop er spark export; do
  pf_u="$(printf '%s' "$pf_l" | tr '[:lower:]' '[:upper:]')"
  pf_want="$(sed -n "s/^MAIN_$pf_u=//p" "$OUT" | head -n 1)"
  [ -n "$pf_want" ] || note_fail "the child printed no main class for $pf_l"
  [ "$(pf_col "$PFX/targets.tsv" "$pf_l" 2)" = "$pf_want" ] || note_fail "the $pf_l line names '$(pf_col "$PFX/targets.tsv" "$pf_l" 2)', not '$pf_want'"
done
case_end

case_begin "14.emr.preflight.write-targets.hadoop-class-path-is-the-command-output-then-the-thin-jar"
[ "$(pf_col "$PFX/targets.tsv" hadoop 3)" = "/stub/a.jar:/stub/b.jar:$PFX/work/jars/hadoop/hadoop-job.jar" ] || note_fail "the hadoop class path is $(pf_col "$PFX/targets.tsv" hadoop 3)"
case_end

case_begin "14.emr.preflight.write-targets.hadoop-classpath-reaches-the-stub-under-timeout-k-5-20-with-the-lib-jars-and-the-java-home"
grep -qxF 'TIMEOUT -k 5 20' "$PFX/calls.log" || note_fail "hadoop classpath did not run under timeout -k 5 20: $(grep '^TIMEOUT' "$PFX/calls.log" | head -n 1)"
[ "$(grep -c '^TIMEOUT ' "$PFX/calls.log")" = "1" ] || note_fail "expected exactly one timeout call"
if grep -qxF 'TIMEOUT 20' "$PFX/calls.log"; then note_fail "hadoop classpath ran under the old form timeout 20, without a KILL grace"; fi
grep -qxF 'HADOOP classpath --glob' "$PFX/calls.log" || note_fail "the stub was not called as hadoop classpath --glob"
pf_seen="$(sed -n 's/^HADOOP_CLASSPATH=//p' "$PFX/calls.log" | head -n 1)"
[ "$(pf_entries "$pf_seen")" = "$(pf_sorted_words "$PFX/work/jars/hadoop/lib/dep-a.jar" "$PFX/work/jars/hadoop/lib/dep-b.jar")" ] || note_fail "HADOOP_CLASSPATH for the stub was: $pf_seen"
grep -qxF 'JAVA_HOME=/opt/harness-jdk' "$PFX/calls.log" || note_fail "the stub did not see JAVA_HOME=/opt/harness-jdk"
if grep -qF 'hadoop-common-3.jar' "$PFX/calls.log"; then note_fail "an excluded hadoop-* lib jar reached HADOOP_CLASSPATH"; fi
case_end

case_begin "14.emr.preflight.write-targets.er-class-path-is-the-jar-then-every-lib-jar-with-no-exclusion"
pf_seen="$(pf_col "$PFX/targets.tsv" er 3)"
case "$pf_seen" in
  "$PFX/work/jars/entity-resolution/er-job.jar":*) ;;
  *) note_fail "the ER class path does not start with the thin jar: $pf_seen" ;;
esac
[ "$(pf_entries "$pf_seen")" = "$(pf_sorted_words "$PFX/work/jars/entity-resolution/er-job.jar" "$PFX/work/jars/entity-resolution/lib/er-a.jar" "$PFX/work/jars/entity-resolution/lib/er-b.jar" "$PFX/work/jars/entity-resolution/lib/hadoop-er.jar" "$PFX/work/jars/entity-resolution/lib/spark-er.jar")" ] || note_fail "the ER class path is $pf_seen"
case_end

case_begin "14.emr.preflight.write-targets.spark-class-path-has-the-distribution-jars-and-the-job-lib-minus-spark-and-hadoop-and-ends-with-the-job-jar"
pf_seen="$(pf_col "$PFX/targets.tsv" spark 3)"
case "$pf_seen" in
  *":$PFX/work/jars/spark/spark-job.jar") ;;
  *) note_fail "the Spark class path does not end with the job jar: $pf_seen" ;;
esac
[ "$(pf_entries "$pf_seen")" = "$(pf_sorted_words "$PFX/spark/jars/spark-core.jar" "$PFX/spark/jars/hadoop-client-api.jar" "$PFX/spark/jars/z.jar" "$PFX/work/jars/spark/lib/s-a.jar" "$PFX/work/jars/spark/lib/s-b.jar" "$PFX/work/jars/spark/spark-job.jar")" ] || note_fail "the Spark class path is $pf_seen"
if printf '%s' "$pf_seen" | grep -qE 'spark-core-4\.jar|hadoop-client-3\.jar'; then note_fail "an excluded spark-* or hadoop-* lib jar is on the Spark class path"; fi
case_end

case_begin "14.emr.preflight.write-targets.export-uses-the-spark-class-path"
[ "$(pf_col "$PFX/targets.tsv" export 3)" = "$(pf_col "$PFX/targets.tsv" spark 3)" ] || note_fail "the export class path differs from the Spark one"
case_end

pfx_new tg-hadoop-fails
: > "$PFX/hadoop-fails"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.a-failing-hadoop-classpath-dies-and-writes-nothing"
a_rc 1
a_err_evidence
a_err 'preflight: hadoop classpath --glob failed or took over 20 s'
a_not_out 'WRITTEN=yes'
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written although hadoop classpath failed"
a_trip_empty
case_end

pfx_new tg-hadoop-empty
: > "$PFX/hadoop-empty"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.an-empty-hadoop-classpath-dies"
a_rc 1
a_err_evidence
a_err 'preflight: hadoop classpath --glob printed nothing'
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written although hadoop classpath printed nothing"
a_trip_empty
case_end

pfx_new tg-hadoop-lib-excluded
rm -f "$PFX/work/jars/hadoop/lib/dep-a.jar" "$PFX/work/jars/hadoop/lib/dep-b.jar"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.a-hadoop-lib-with-only-excluded-jars-dies"
a_rc 1
a_err_evidence
a_err 'preflight: no library jars under jars/hadoop/lib after exclusions'
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written with no hadoop lib jar"
a_trip_empty
case_end

pfx_new tg-spark-dir-missing
rm -rf "$PFX/work/jars/spark"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.a-missing-spark-jar-directory-dies"
a_rc 1
a_err_evidence
a_err "expected exactly one jar in $PFX/work/jars/spark, found 0"
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written with no Spark job jar"
a_trip_empty
case_end

pfx_new tg-er-two-jars
: > "$PFX/work/jars/entity-resolution/second-job.jar"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.two-er-main-jars-die"
a_rc 1
a_err_evidence
a_err "expected exactly one jar in $PFX/work/jars/entity-resolution, found 2"
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written with two ER main jars"
a_trip_empty
case_end

pfx_new tg-no-distribution-jars
rm -f "$PFX/spark/jars/spark-core.jar" "$PFX/spark/jars/hadoop-client-api.jar" "$PFX/spark/jars/z.jar"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.an-empty-spark-distribution-jars-directory-dies"
a_rc 1
a_err_evidence
a_err "preflight: no jars under $PFX/spark/jars"
[ ! -e "$PFX/targets.tsv" ] || note_fail "targets.tsv was written with no distribution jar"
a_trip_empty
case_end

pfx_new tg-no-er-lib
rm -rf "$PFX/work/jars/entity-resolution/lib"
run bash "$CODE_DIR/preflight-targets-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
case_begin "14.emr.preflight.write-targets.no-er-lib-directory-gives-the-thin-jar-alone"
mr_ok
a_out 'WRITTEN=yes'
[ "$(pf_col "$PFX/targets.tsv" er 3)" = "$PFX/work/jars/entity-resolution/er-job.jar" ] || note_fail "the ER class path is $(pf_col "$PFX/targets.tsv" er 3)"
case_end

### run_preflight against the fake spark-shell.

# pf_rp_run [NAME=VALUE ...]: SC_PREFLIGHT_TIMEOUT is removed first, so only a NAME=VALUE given
# here sets it. The three EMR class path variables are poisoned to prove env -u removes them.
pf_rp_run() {
  run env -u SC_PREFLIGHT_TIMEOUT "$@" HADOOP_CLASSPATH=/emr/poison/hadoop.jar SPARK_DIST_CLASSPATH=/emr/poison/dist.jar SPARK_EXTRA_CLASSPATH=/emr/poison/extra.jar bash "$CODE_DIR/preflight-run-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
}
# pf_timeout_call LOW HIGH: the one spark-shell timeout call (-k 30), with a remaining of LOW to
# HIGH s. The hadoop classpath call has a -k too since work order 67 (-k 5 20), so the count is
# anchored on the 30.
pf_timeout_call() {
  local r="$1" found=0
  while [ "$r" -le "$2" ]; do
    if grep -qxF -- "TIMEOUT-ARGS -k 30 $r $PFX/spark/bin/spark-shell --master local[2] --conf spark.sql.catalogImplementation=in-memory" "$PFX/calls.log"; then found=1; fi
    r=$((r + 1))
  done
  [ "$found" = "1" ] || note_fail "no 'timeout -k 30 N spark-shell --master local[2] --conf spark.sql.catalogImplementation=in-memory' call (and nothing after it) with N from $1 to $2: $(grep 'TIMEOUT-ARGS -k 30 ' "$PFX/calls.log" | tail -n 1)"
  [ "$(grep -c '^TIMEOUT-ARGS -k 30 ' "$PFX/calls.log")" = "1" ] || note_fail "expected exactly one timeout call with -k 30"
}
# pf_rp_refused LIMIT_TEXT: the value was refused before anything ran or was written.
pf_rp_refused() {
  a_rc 1
  a_err_evidence
  a_err 'SC_PREFLIGHT_TIMEOUT must be a whole number from 15 to 60 with no leading zero (the floor of 15 is chosen because the JVM compile and run is estimated at 15 to 25 s, and below it a pass is not plausible)'
  a_not_out 'RC=0'
  [ ! -s "$PFX/calls.log" ] || note_fail "a command ran although SC_PREFLIGHT_TIMEOUT=$1 was refused: $(head -n 1 "$PFX/calls.log")"
  [ ! -e "$PFX/rep/preflight.scala" ] || note_fail "the script was written although SC_PREFLIGHT_TIMEOUT=$1 was refused"
  [ ! -d "$PFX/work/preflight" ] || note_fail "the preflight directory was made although SC_PREFLIGHT_TIMEOUT=$1 was refused"
  a_trip_empty
}

pfx_new rp-pass
pf_rp_run
case_begin "14.emr.preflight.run-preflight.the-command-is-timeout-k-30-remaining-spark-shell-on-local-2-with-no-script-argument-and-the-note-says-so"
mr_ok
a_out 'RC=0'
pf_timeout_call 55 60
a_out_re '^NOTE: preflight: class names, job main classes and two small file: writes in one spark-shell \(limit 60 s, (5[5-9]|60) s left for the JVM\)$'
grep -qxF 'TIMEOUT-ARGS -k 5 20 hadoop classpath --glob' "$PFX/calls.log" || note_fail "the class path command was not run as timeout -k 5 20 hadoop classpath --glob: $(grep 'hadoop classpath' "$PFX/calls.log" | head -n 1)"
grep -qxF "SPARK-SHELL --master local[2] --conf spark.sql.catalogImplementation=in-memory" "$PFX/calls.log" || note_fail "spark-shell got other arguments (the argv must end at the in-memory --conf, no -i, no script path): $(grep '^SPARK-SHELL' "$PFX/calls.log" | head -n 1)"
grep -qxF "PWD=$PFX/work/preflight" "$PFX/calls.log" || note_fail "spark-shell did not start in work/preflight: $(grep '^PWD=' "$PFX/calls.log" | head -n 1)"
grep -qxF 'DATA-DIR present' "$PFX/calls.log" || note_fail "the data directory did not exist when spark-shell started"
case_end

# Work order 75: the script reaches the REPL only as one ':load <reports>/preflight.scala' line on
# stdin (the child's own stdin is /dev/null, so a launch without the here-string reads no line and
# the fake rejects it). A script path in argv, -i or -I, or a second stdin line, fails here.
case_begin "14.emr.preflight.run-preflight.the-script-reaches-the-shell-as-one-load-line-on-stdin-and-never-as-an-argument-or-i-or-big-i"
[ -f "$PFX/seen-stdin.txt" ] || note_fail "the fake spark-shell recorded no stdin (seen-stdin.txt)"
[ "$(grep -c '' "$PFX/seen-stdin.txt")" = "1" ] || note_fail "stdin held $(grep -c '' "$PFX/seen-stdin.txt") line(s), expected exactly one"
[ "$(head -n 1 "$PFX/seen-stdin.txt")" = ":load $PFX/rep/preflight.scala" ] || note_fail "the stdin line is '$(head -n 1 "$PFX/seen-stdin.txt")', expected ':load $PFX/rep/preflight.scala'"
grep -qxF "LOAD-LINE :load $PFX/rep/preflight.scala" "$PFX/calls.log" || note_fail "the fake did not accept the :load line"
if grep -q '^FAKE-REJECT ' "$PFX/calls.log"; then note_fail "the fake rejected the launch: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"; fi
pf_argv="$(sed -n 's/^SPARK-SHELL //p' "$PFX/calls.log" | head -n 1)"
[ -n "$pf_argv" ] || note_fail "no SPARK-SHELL argv line was logged"
if printf '%s' "$pf_argv" | grep -qE '(^| )-[iI]'; then note_fail "spark-shell got -i or -I: $pf_argv"; fi
case "$pf_argv" in
  *.scala*) note_fail "the script path is in the spark-shell argv: $pf_argv" ;;
esac
pf_targs="$(grep '^TIMEOUT-ARGS -k 30 ' "$PFX/calls.log" | head -n 1)"
[ -n "$pf_targs" ] || note_fail "no timeout -k 30 call was logged"
if printf '%s' "$pf_targs" | grep -qE ' -[iI]( |$)'; then note_fail "the timeout call carries -i or -I: $pf_targs"; fi
case "$pf_targs" in
  *.scala*) note_fail "the timeout call carries the script path: $pf_targs" ;;
esac
cmp -s "$PFX/rep/preflight.scala" "$PFX/seen-script.scala" || note_fail "the file named on stdin differs from rep/preflight.scala"
case_end

case_begin "14.emr.preflight.run-preflight.two-timeout-calls-the-class-path-under-k-5-20-first-and-spark-shell-under-k-30"
[ "$(grep -c '^TIMEOUT-ARGS ' "$PFX/calls.log")" = "2" ] || note_fail "expected exactly two timeout calls, found $(grep -c '^TIMEOUT-ARGS ' "$PFX/calls.log")"
[ "$(grep -c '^TIMEOUT-ARGS -k 5 20 ' "$PFX/calls.log")" = "1" ] || note_fail "expected exactly one timeout -k 5 20 call"
if grep -qxF 'TIMEOUT-ARGS 20 hadoop classpath --glob' "$PFX/calls.log"; then note_fail "the class path command ran under the old form timeout 20, without a KILL grace"; fi
pf_l1="$(line_of "$PFX/calls.log" 'TIMEOUT-ARGS -k 5 20 hadoop classpath --glob')"
pf_l2="$(line_of "$PFX/calls.log" 'TIMEOUT-ARGS -k 30 ')"
if [ -z "$pf_l1" ] || [ -z "$pf_l2" ]; then
  note_fail "a timeout call was not found (-k 5 20 at '$pf_l1', -k 30 at '$pf_l2')"
else
  [ "$pf_l1" -lt "$pf_l2" ] || note_fail "the -k 30 call (line $pf_l2) is not after the class path call (line $pf_l1)"
fi
case_end

case_begin "14.emr.preflight.run-preflight.the-environment-is-the-run-spark-submit-one-and-the-emr-class-path-variables-are-gone"
for pf_e in "SPARK_HOME=$PFX/spark" "SPARK_CONF_DIR=$PFX/home/spark-conf" 'HADOOP_CONF_DIR=/etc/hadoop/conf' 'YARN_CONF_DIR=/etc/hadoop/conf' 'JAVA_HOME=/opt/harness-jdk' "SC_PREFLIGHT_DIR=$PFX/work/preflight" "SC_PREFLIGHT_DATA=$PFX/work/preflight/data" "SC_PREFLIGHT_RESULT=$PFX/rep/preflight-result.txt" 'SC_PREFLIGHT_ROWS=25'; do
  grep -qxF "ENV $pf_e" "$PFX/calls.log" || note_fail "spark-shell did not see $pf_e"
done
for pf_e in HADOOP_CLASSPATH SPARK_DIST_CLASSPATH SPARK_EXTRA_CLASSPATH; do
  if grep -q "^ENV $pf_e=" "$PFX/calls.log"; then note_fail "spark-shell still saw $pf_e: $(grep "^ENV $pf_e=" "$PFX/calls.log" | head -n 1)"; fi
done
case_end

case_begin "14.emr.preflight.run-preflight.evidence-files-the-targets-the-script-and-the-record-keys"
[ -s "$PFX/rep/preflight.scala" ] || note_fail "rep/preflight.scala is missing or empty"
grep -qF 'object PreflightRun' "$PFX/rep/preflight.scala" || note_fail "preflight.scala is not the preflight script"
cmp -s "$PFX/rep/preflight.scala" "$PFX/seen-script.scala" || note_fail "the file spark-shell loaded differs from rep/preflight.scala"
grep -qxF 'fake spark-shell output line' "$PFX/rep/preflight.log" || note_fail "rep/preflight.log does not hold the shell output"
cmp -s "$PFX/rep/preflight-result.txt" "$PF_RES" || note_fail "rep/preflight-result.txt is not the result the shell wrote"
[ "$(wc -l < "$PFX/seen-targets.tsv" | tr -d ' ')" = "4" ] || note_fail "targets.tsv was not in place with four lines when spark-shell started"
a_out_re '^PREFLIGHT_CLASSPATH_ENTRIES=hadoop=3 er=5 spark=6 export=6$'
a_out_re '^PREFLIGHT_RESULT=ok$'
a_out_re '^PREFLIGHT_LIMIT_SECONDS=60$'
a_out_re '^PREFLIGHT_SECONDS=[0-9]+$'
a_order 'PREFLIGHT_CLASSPATH_ENTRIES=' 'NOTE: preflight:' 'PREFLIGHT_RESULT=ok' 'PREFLIGHT_LIMIT_SECONDS=60' 'PREFLIGHT_SECONDS='
case_end

case_begin "14.emr.preflight.run-preflight.the-data-directory-is-removed-and-only-the-expected-files-are-left-under-work-and-reports"
[ ! -d "$PFX/work/preflight/data" ] || note_fail "work/preflight/data was not removed"
pf_have="$(cd "$PFX" && find work rep -type f | sort | tr '\n' ' ')"
pf_want="$( { for pf_f in $PF_FIX_FILES; do printf '%s\n' "$pf_f"; done; printf '%s\n' work/preflight/targets.tsv rep/preflight.log rep/preflight-result.txt rep/preflight.scala; } | sort | tr '\n' ' ')"
[ "$pf_have" = "$pf_want" ] || note_fail "files under work and rep: $pf_have (expected $pf_want)"
case_end

for pf_v in 0 61 abc 08 -5 1.5 100 1 9 14 015; do
  pfx_new "rp-bad-$pf_v"
  pf_rp_run "SC_PREFLIGHT_TIMEOUT=$pf_v"
  case_begin "14.emr.preflight.run-preflight.sc-preflight-timeout-$pf_v-is-refused-before-anything-runs"
  pf_rp_refused "$pf_v"
  case_end
done

pfx_new rp-limit-25
pf_rp_run SC_PREFLIGHT_TIMEOUT=25
case_begin "14.emr.preflight.run-preflight.sc-preflight-timeout-25-sets-the-limit-and-the-remaining-time"
mr_ok
a_out 'RC=0'
pf_timeout_call 20 25
a_out_re '^NOTE: preflight: .*\(limit 25 s, (2[0-5]) s left for the JVM\)$'
a_out_re '^PREFLIGHT_LIMIT_SECONDS=25$'
case_end

pfx_new rp-limit-60
pf_rp_run SC_PREFLIGHT_TIMEOUT=60
case_begin "14.emr.preflight.run-preflight.sc-preflight-timeout-60-is-accepted"
mr_ok
a_out 'RC=0'
pf_timeout_call 55 60
a_out_re '^PREFLIGHT_LIMIT_SECONDS=60$'
case_end

pfx_new rp-limit-empty
pf_rp_run SC_PREFLIGHT_TIMEOUT=
case_begin "14.emr.preflight.run-preflight.an-empty-sc-preflight-timeout-falls-back-to-the-default-60"
mr_ok
a_out 'RC=0'
pf_timeout_call 55 60
a_out_re '^PREFLIGHT_LIMIT_SECONDS=60$'
case_end

# Work order 67 (F2): a limit below 15 is refused up front (the floor, SC_PREFLIGHT_FLOOR_SECONDS),
# because under 10 s would be left for the JVM after the class paths. 1, 9 and 14 are in the
# refused loop above, which replaced the two cases that pinned the old "passes the validation
# and dies for lack of time" behaviour of 1 and 9. 15 is the lowest accepted value; the stub
# builds the class paths instantly, so at most a few seconds are spent and 10 to 15 s remain.
pfx_new rp-limit-15
pf_rp_run SC_PREFLIGHT_TIMEOUT=15
case_begin "14.emr.preflight.run-preflight.sc-preflight-timeout-15-is-the-lowest-accepted-value"
mr_ok
a_out 'RC=0'
pf_timeout_call 10 15
a_out_re '^NOTE: preflight: .*\(limit 15 s, (1[0-5]) s left for the JVM\)$'
a_out_re '^PREFLIGHT_LIMIT_SECONDS=15$'
if grep -qF 'must be a whole number' "$ERR"; then note_fail "the validation refused 15, which is the floor and must be accepted"; fi
case_end

pfx_new rp-warn
pf_add "$PF_WARN_LINE"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-result-with-a-warn-line-passes-and-prints-the-note-after-the-record-and-before-the-limit-keys"
mr_ok
a_out 'RC=0'
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
a_out_re '^PREFLIGHT_RESULT=ok$'
a_out_re '^PREFLIGHT_NODE_KEYS_WARN=1: yarn\.ipc\.rpc\.class$'
a_out "NOTE: preflight $PF_WARN_LINE $PF_WARN_NOTE_TAIL"
a_order 'PREFLIGHT_RESULT=ok' 'PREFLIGHT_SMOKE=' 'NOTE: preflight WARN config' 'PREFLIGHT_LIMIT_SECONDS=' 'PREFLIGHT_SECONDS='
cmp -s "$PFX/rep/preflight-result.txt" "$PF_RES" || note_fail "rep/preflight-result.txt is not the result the shell wrote"
case_end

pfx_new rp-no-shell
rm -f "$PFX/spark/bin/spark-shell"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-distribution-without-spark-shell-dies-before-anything-is-written"
a_rc 1
a_err_evidence
a_err "no spark-shell in the extracted distribution: $PFX/spark"
[ ! -s "$PFX/calls.log" ] || note_fail "a command ran: $(head -n 1 "$PFX/calls.log")"
[ ! -d "$PFX/work/preflight" ] || note_fail "the preflight directory was made"
a_trip_empty
case_end

pfx_new rp-timeout-124
printf '124' > "$PFX/timeout-rc"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.exit-124-from-timeout-records-timed-out-and-dies-without-reading-a-result"
a_rc 1
a_err_evidence
a_err 'preflight timed out (exit 124) after'
a_out_re '^PREFLIGHT_RESULT=timed out \(exit 124, JVM limit (5[5-9]|60) s\)$'
a_not_out 'PREFLIGHT_RESULT=ok'
a_not_out 'PREFLIGHT_RESULT=failed'
a_not_out 'RC=0'
if grep -q '^SPARK-SHELL ' "$PFX/calls.log"; then note_fail "the fake timeout ran spark-shell although it was set to time out"; fi
[ ! -d "$PFX/work/preflight/data" ] || note_fail "work/preflight/data was not removed after the timeout"
a_trip_empty
case_end

pfx_new rp-timeout-137
printf '137' > "$PFX/timeout-rc"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.exit-137-after-the-kill-is-a-timeout-too"
a_rc 1
a_err 'preflight timed out (exit 137) after'
a_out_re '^PREFLIGHT_RESULT=timed out \(exit 137, JVM limit (5[5-9]|60) s\)$'
a_not_out 'RC=0'
[ ! -d "$PFX/work/preflight/data" ] || note_fail "work/preflight/data was not removed after the kill"
case_end

pfx_new rp-shell-rc-1-ok
printf '1' > "$PFX/shell-rc"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-nonzero-shell-exit-with-a-complete-result-still-passes-the-status-is-not-the-verdict"
mr_ok
a_out 'RC=0'
a_out_re '^PREFLIGHT_RESULT=ok$'
case_end

pfx_new rp-shell-rc-2-noresult
rm -f "$PF_RES"
printf '2' > "$PFX/shell-rc"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-nonzero-shell-exit-with-no-result-dies-with-the-status-and-the-log-tail"
a_rc 1
a_err_evidence
a_err 'preflight wrote no result (spark-shell exit 2'
a_err "':load'"
a_err 'fake spark-shell output line'
a_not_out 'PREFLIGHT_RESULT=ok'
[ ! -d "$PFX/work/preflight/data" ] || note_fail "work/preflight/data was not removed"
a_trip_empty
case_end

pfx_new rp-shell-rc-0-noresult
rm -f "$PF_RES"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-zero-shell-exit-with-no-result-still-dies"
a_rc 1
a_err 'preflight wrote no result (spark-shell exit 0'
a_err "':load'"
a_not_out 'PREFLIGHT_RESULT=ok'
a_not_out 'RC=0'
a_out_re '^PREFLIGHT_RESULT=failed$'
case_end

# Work order 79, updated in work order 83: a script that does not compile. The fake shell prints the
# layout that rehearsal 4 observed (human-run and relayed): the source and caret lines first, then the
# error line, a blank line, and the follow-on diagnostic; it writes no result. The refusal carries the
# FIRST diagnostic LINE alone, not its source and caret lines and not the follow-on
# 'not found: value PreflightRun', and the record says failed.
pfx_new rp-compile-error
rm -f "$PF_RES"
printf '%s\n' '  val y = foo' '          ^' 'x.scala:12: error: not found: value foo' '' '  PreflightRun.run()' '  ^' 'x.scala:99: error: not found: value PreflightRun' > "$PFX/spark/extra-log"
pf_rp_run
case_begin "14.emr.preflight.run-preflight.a-shell-log-with-a-compiler-diagnostic-and-no-result-dies-with-the-first-diagnostic-line-alone-and-records-failed"
a_rc 1
a_err_evidence
a_err 'preflight wrote no result (spark-shell exit 0'
a_err ': x.scala:12: error: not found: value foo'
grep -qE 'x[.]scala:12: error: not found: value foo$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
if grep -qF 'val y = foo' "$ERR"; then note_fail "the source line is in the evidence"; fi
if grep -qF 'PreflightRun' "$ERR"; then note_fail "the follow-on diagnostic is in the evidence"; fi
a_out_re '^PREFLIGHT_RESULT=failed$'
a_not_out 'PREFLIGHT_RESULT=ok'
a_not_out 'RC=0'
[ ! -d "$PFX/work/preflight/data" ] || note_fail "work/preflight/data was not removed"
grep -qF 'x.scala:99: error: not found: value PreflightRun' "$PFX/rep/preflight.log" || note_fail "preflight.log does not hold the whole shell output"
a_trip_empty
case_end

# Work order 79 (infra work order 78): the script path is passed to ':load', which splits its argument
# on whitespace (inferred, not observed), so a reports directory with whitespace is refused before the
# preflight directory is made and before any command runs. Only the reports path is checked.
pf_ws_run() {
  run env -u SC_PREFLIGHT_TIMEOUT bash "$CODE_DIR/preflight-run-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX" "$1"
}
pf_ws_refused() {
  a_rc 1
  a_err_evidence
  a_err "preflight: the script path '$1/preflight.scala' holds whitespace"
  a_err "and ':load' splits its argument on whitespace (inferred, not observed), so the reports directory path must hold none"
  a_not_out 'RC=0'
  [ ! -s "$PFX/calls.log" ] || note_fail "a command ran: $(head -n 1 "$PFX/calls.log")"
  [ ! -e "$PFX/work/preflight" ] || note_fail "the preflight directory was made"
  [ ! -e "$1" ] || note_fail "the reports directory with whitespace was made"
  [ ! -e "$PFX/rep/preflight.scala" ] || note_fail "a script was written"
  a_trip_empty
}

pfx_new rp-ws-space
pf_ws_run "$PFX/rep with space"
case_begin "14.emr.preflight.run-preflight.a-reports-path-with-a-space-is-refused-before-the-preflight-directory-and-any-command"
pf_ws_refused "$PFX/rep with space"
a_err 'holds whitespace'
case_end

pfx_new rp-ws-tab
pf_ws_run "$PFX/rep"$'\t'"tab"
case_begin "14.emr.preflight.run-preflight.a-reports-path-with-a-tab-is-refused-too"
pf_ws_refused "$PFX/rep"$'\t'"tab"
case_end

# Work order 83 (infra work order 82): rehearse-preflight.sh is run here only as far as its refusals, which
# come before anything is made, before Java, Spark or any command is looked for. The rehearsal root is a
# directory with a space under the harness's own temporary directory (outside the repository, which the
# script's in-repo refusal would otherwise report first). Expected: exit 2, REFUSED: naming the run
# directory and the whitespace, no stdout, and neither the root nor any directory made. The script is run
# by the bash that runs the harness ($BASH), because the script itself refuses a bash older than 4 with
# exit 2; on such a bash the case checks that refusal instead and does not claim the whitespace one.
pfx_new rh-ws-root
RH_ROOT="$PFX/root with space"
run env SC_REHEARSE_ROOT="$RH_ROOT" SC_REHEARSE_DATA_ROOT="$PFX/data-not-made" "$BASH" "$EMR_DIR/rehearse-preflight.sh"
case_begin "14.emr.rehearse-preflight.a-root-with-whitespace-outside-the-repository-is-refused-before-any-directory-is-made"
a_rc 2
a_trip_empty
[ ! -s "$OUT" ] || note_fail "stdout is not empty: $(head -n 1 "$OUT" | cut -c1-140)"
if [ "${BASH_VERSINFO[0]}" -ge 4 ]; then
  a_err "REFUSED: the run directory $RH_ROOT/preflight-"
  a_err " holds whitespace, and ':load' splits its argument on whitespace (inferred); choose SC_REHEARSE_ROOT without any"
else
  a_err 'REFUSED: this is bash '
fi
[ ! -e "$RH_ROOT" ] || note_fail "the root with whitespace was made: $RH_ROOT"
[ ! -e "$PFX/data-not-made" ] || note_fail "the data root was made"
case_end

### The fake spark-shell itself (work order 75). A fake that accepted the old launch would let
### the run_preflight cases above pass on the defect they exist to catch, so the fake is run
### directly here with each wrong launch and must refuse it, and with the right one and accept it.

# pf-fake-shell-child.sh FIX STDIN ARGS...: runs the fake spark-shell of the fixture FIX with the
# preflight environment, stdin from the file STDIN (or /dev/null when STDIN is the word none).
cat > "$CODE_DIR/pf-fake-shell-child.sh" <<'EOF'
FIX="$1"; IN="$2"; shift 2
export SC_PREFLIGHT_DIR="$FIX/work/preflight" SC_PREFLIGHT_DATA="$FIX/work/preflight/data" SC_PREFLIGHT_RESULT="$FIX/rep/preflight-result.txt"
mkdir -p "$SC_PREFLIGHT_DIR"
printf 'hadoop\tMain\tcp\n' > "$SC_PREFLIGHT_DIR/targets.tsv"
if [ "$IN" = none ]; then
  "$FIX/spark/bin/spark-shell" "$@" < /dev/null
else
  "$FIX/spark/bin/spark-shell" "$@" < "$IN"
fi
EOF

# pf_fake_new NAME: a fixture with the script file in place.
pf_fake_new() {
  pfx_new "$1"
  printf 'object PreflightRun { def run(): Unit = () }\n' > "$PFX/rep/preflight.scala"
}

# pf_fake_rejected: the launch was refused: a reason is logged, and no script copy, no data and
# no result exist, so a run_preflight that launched this way would die with "wrote no result".
pf_fake_rejected() {
  a_rc 0
  grep -q '^FAKE-REJECT ' "$PFX/calls.log" || note_fail "the fake did not log FAKE-REJECT"
  a_out 'fake spark-shell rejected the launch: '
  [ ! -e "$PFX/seen-script.scala" ] || note_fail "the fake copied a script although the launch was wrong"
  [ ! -e "$PFX/rep/preflight-result.txt" ] || note_fail "the fake wrote a result although the launch was wrong"
  [ ! -d "$PFX/work/preflight/data" ] || note_fail "the fake made the data directory although the launch was wrong"
  a_trip_empty
}

pf_fake_new fk-ok
printf ':load %s\n' "$PFX/rep/preflight.scala" > "$PFX/stdin.txt"
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" "$PFX/stdin.txt" --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory
case_begin "14.emr.preflight.fake-spark-shell.accepts-exactly-one-load-line-on-stdin-and-copies-the-script-and-the-result"
a_rc 0
grep -q '^FAKE-REJECT ' "$PFX/calls.log" && note_fail "the fake rejected the right launch: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"
cmp -s "$PFX/rep/preflight.scala" "$PFX/seen-script.scala" || note_fail "the script named on stdin was not copied"
grep -qxF "LOAD-LINE :load $PFX/rep/preflight.scala" "$PFX/calls.log" || note_fail "no LOAD-LINE in the call log"
cmp -s "$PFX/rep/preflight-result.txt" "$PF_RES" || note_fail "the prepared result was not copied"
a_out 'fake spark-shell output line'
a_trip_empty
case_end

pf_fake_new fk-dash-i
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" none --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory -i "$PFX/rep/preflight.scala"
case_begin "14.emr.preflight.fake-spark-shell.rejects-i-with-stdin-from-dev-null-the-defect-of-the-probe"
pf_fake_rejected
grep -q '^FAKE-REJECT the script or -i/-I arrived as an argument: -i$' "$PFX/calls.log" || note_fail "the reason is not the -i argument: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"
case_end

pf_fake_new fk-dash-big-i
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" none --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory -I "$PFX/rep/preflight.scala"
case_begin "14.emr.preflight.fake-spark-shell.rejects-big-i-with-stdin-from-dev-null"
pf_fake_rejected
grep -q '^FAKE-REJECT the script or -i/-I arrived as an argument: -I$' "$PFX/calls.log" || note_fail "the reason is not the -I argument: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"
case_end

pf_fake_new fk-arg-and-load
printf ':load %s\n' "$PFX/rep/preflight.scala" > "$PFX/stdin.txt"
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" "$PFX/stdin.txt" --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory "$PFX/rep/preflight.scala"
case_begin "14.emr.preflight.fake-spark-shell.rejects-the-script-path-as-an-argument-even-with-the-load-line-on-stdin"
pf_fake_rejected
case_end

pf_fake_new fk-empty-stdin
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" none --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory
case_begin "14.emr.preflight.fake-spark-shell.rejects-a-launch-with-no-stdin-line-at-all"
pf_fake_rejected
grep -q '^FAKE-REJECT stdin held 0 line(s)' "$PFX/calls.log" || note_fail "the reason is not an empty stdin: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"
case_end

pf_fake_new fk-two-lines
printf ':load %s\n:quit\n' "$PFX/rep/preflight.scala" > "$PFX/stdin.txt"
run bash "$CODE_DIR/pf-fake-shell-child.sh" "$PFX" "$PFX/stdin.txt" --master 'local[2]' --conf spark.sql.catalogImplementation=in-memory
case_begin "14.emr.preflight.fake-spark-shell.rejects-a-stdin-of-more-than-one-line"
pf_fake_rejected
grep -q '^FAKE-REJECT stdin held 2 line(s)' "$PFX/calls.log" || note_fail "the reason is not a two line stdin: $(grep '^FAKE-REJECT ' "$PFX/calls.log" | head -n 1)"
case_end

### run_preflight inside the stages and the exit trap.

# pf_trap_run: the poisoned class path variables again.
pf_trap_run() {
  run env HADOOP_CLASSPATH=/emr/poison/hadoop.jar SPARK_DIST_CLASSPATH=/emr/poison/dist.jar SPARK_EXTRA_CLASSPATH=/emr/poison/extra.jar bash "$CODE_DIR/preflight-trap-child.sh" "$EMR_DIR/lib-emr.sh" "$PFX"
}
# pf_up_names: the names of the uploaded files, sorted, joined by a space.
pf_up_names() {
  find "$PFX/uploads" -type f 2> /dev/null | sed 's|.*/||' | LC_ALL=C sort | tr '\n' ' '
}

pfx_new tr-fail
pf_add 'FAIL config mapreduce.fake.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing'
pf_trap_run
case_begin "14.emr.preflight.exit-trap.a-failing-preflight-records-failed-stage-4-spark-stage-with-exit-code-1-and-the-first-failure"
a_rc 1
a_trip_empty
a_order 'STAGE[4] spark-stage: start' 'PREFLIGHT_RESULT=failed' 'PREFLIGHT_FIRST_FAILURE=' 'STAGE[4] spark-stage: FAIL (exit 1)' 'STAGE_SECONDS_SPARK_STAGE=' 'STAGE_SECONDS_TOTAL_AT_FAILURE=' 'FAILED_STAGE=4 spark-stage' 'FAILED_EXIT_CODE=1' 'FAILED_LOGS_PUBLISHED='
a_not_out 'STAGE[4] spark-stage: ok'
a_not_out 'ALL-DONE'
a_err 'preflight: 1 check(s) failed, first: FAIL config mapreduce.fake.class com.example.Missing'
a_out_re '^STAGE_SECONDS_SPARK_STAGE=[0-9]+ \(stage failed\)$'
grep -qxF 'FAILED_STAGE=4 spark-stage' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks FAILED_STAGE=4 spark-stage"
grep -qxF 'FAILED_EXIT_CODE=1' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks FAILED_EXIT_CODE=1"
grep -qxF 'PREFLIGHT_RESULT=failed' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks PREFLIGHT_RESULT=failed"
grep -qxF 'PREFLIGHT_FIRST_FAILURE=FAIL config mapreduce.fake.class com.example.Missing java.lang.ClassNotFoundException: com.example.Missing' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks the key and the class of the first failure"
case_end

case_begin "14.emr.preflight.exit-trap.a-failing-preflight-publishes-its-three-evidence-files-with-the-run-record"
[ "$(pf_up_names)" = "preflight-result.txt preflight.log preflight.scala run-record.env " ] || note_fail "uploaded names: $(pf_up_names)"
grep -qF 'FAIL config mapreduce.fake.class com.example.Missing' "$PFX/uploads/preflight-result.txt" || note_fail "the uploaded preflight-result.txt lacks the FAIL line"
grep -qF 'object PreflightRun' "$PFX/uploads/preflight.scala" || note_fail "the uploaded preflight.scala is not the script"
grep -qxF 'fake spark-shell output line' "$PFX/uploads/preflight.log" || note_fail "the uploaded preflight.log lacks the shell output"
if grep -qE '/progress/' "$PFX/aws-calls.log"; then note_fail "a failed run uploaded to the progress prefix"; fi
if ! grep -qE ' s3://data-bucket/reports/runs/run-v1-x/failed/[0-9]{8}T[0-9]{6}Z/preflight-result\.txt --only-show-errors$' "$PFX/aws-calls.log"; then note_fail "preflight-result.txt did not go under the failed/<stamp>/ prefix"; fi
case_end

pfx_new tr-timeout
printf '124' > "$PFX/timeout-rc"
pf_trap_run
case_begin "14.emr.preflight.exit-trap.a-timed-out-preflight-records-failed-stage-4-spark-stage-with-the-timed-out-result"
a_rc 1
a_trip_empty
a_order 'STAGE[4] spark-stage: start' 'PREFLIGHT_RESULT=timed out' 'STAGE[4] spark-stage: FAIL (exit 1)' 'FAILED_STAGE=4 spark-stage' 'FAILED_EXIT_CODE=1' 'FAILED_LOGS_PUBLISHED='
grep -qE '^PREFLIGHT_RESULT=timed out \(exit 124, JVM limit (5[5-9]|60) s\)$' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks the timed-out result"
[ "$(pf_up_names)" = "preflight.log preflight.scala run-record.env " ] || note_fail "uploaded names: $(pf_up_names)"
case_end

pfx_new tr-pass
pf_trap_run
case_begin "14.emr.preflight.exit-trap.a-passing-preflight-lets-the-stage-end-ok-and-the-checkpoint-carries-the-evidence-files"
a_rc 0
a_trip_empty
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
a_out 'ALL-DONE'
a_order 'STAGE[4] spark-stage: start' 'PREFLIGHT_RESULT=ok' 'PREFLIGHT_SECONDS=' 'STAGE[4] spark-stage: ok' 'STAGE_SECONDS_SPARK_STAGE=' 'checkpoint=after-stage-4-spark-stage'
a_not_out 'FAILED_STAGE='
[ "$(pf_up_names)" = "preflight-result.txt preflight.log preflight.scala progress.env run-record.env " ] || note_fail "uploaded names: $(pf_up_names)"
grep -qxF 'PREFLIGHT_RESULT=ok' "$PFX/uploads/run-record.env" || note_fail "the checkpoint's run-record.env lacks PREFLIGHT_RESULT=ok"
grep -qxF 'PREFLIGHT_SMOKE=old-api:ok parquet:ok rows=25' "$PFX/uploads/run-record.env" || note_fail "the checkpoint's run-record.env lacks the smoke record"
grep -qxF 'STAGE=4 spark-stage open=0' "$PFX/uploads/progress.env" || note_fail "progress.env lacks the stage line for stage 4"
case_end

pfx_new tr-warn
pf_add "$PF_WARN_LINE"
pf_trap_run
case_begin "14.emr.preflight.exit-trap.a-passing-preflight-with-a-warn-line-ends-the-stage-ok-and-the-checkpoint-records-the-warn-key"
a_rc 0
a_trip_empty
[ ! -s "$ERR" ] || note_fail "stderr is not empty: $(head -n 1 "$ERR" | cut -c1-140)"
a_out 'ALL-DONE'
a_order 'STAGE[4] spark-stage: start' 'PREFLIGHT_NODE_KEYS_WARN=1: yarn.ipc.rpc.class' 'NOTE: preflight WARN config' 'STAGE[4] spark-stage: ok' 'checkpoint=after-stage-4-spark-stage'
a_not_out 'FAILED_STAGE='
grep -qxF 'PREFLIGHT_RESULT=ok' "$PFX/uploads/run-record.env" || note_fail "the checkpoint's run-record.env lacks PREFLIGHT_RESULT=ok"
grep -qxF 'PREFLIGHT_NODE_KEYS_WARN=1: yarn.ipc.rpc.class' "$PFX/uploads/run-record.env" || note_fail "the checkpoint's run-record.env lacks the WARN key record"
case_end

# Work order 79: a script that does not compile leaves no result file. The failed run still records
# PREFLIGHT_RESULT=failed in the run record that the exit trap publishes, with the first diagnostic in
# the die text, and publishes the log and the script (there is no result file to publish).
pfx_new tr-compile-error
rm -f "$PF_RES"
printf '%s\n' 'x.scala:12: error: not found: value foo' '  val y = foo' '          ^' 'x.scala:99: error: not found: value PreflightRun' > "$PFX/spark/extra-log"
pf_trap_run
case_begin "14.emr.preflight.exit-trap.a-script-that-does-not-compile-records-failed-stage-4-spark-stage-with-result-failed-and-publishes-the-log-and-the-script-only"
a_rc 1
a_trip_empty
a_order 'STAGE[4] spark-stage: start' 'PREFLIGHT_RESULT=failed' 'STAGE[4] spark-stage: FAIL (exit 1)' 'FAILED_STAGE=4 spark-stage' 'FAILED_EXIT_CODE=1' 'FAILED_LOGS_PUBLISHED='
a_not_out 'STAGE[4] spark-stage: ok'
a_not_out 'ALL-DONE'
a_not_out 'PREFLIGHT_RESULT=ok'
a_err 'preflight wrote no result (spark-shell exit 0'
a_err ': x.scala:12: error: not found: value foo'
grep -qE 'x[.]scala:12: error: not found: value foo$' "$ERR" || note_fail "the evidence does not end at the diagnostic line: $(tail -n 1 "$ERR" | cut -c1-200)"
if grep -qF 'val y = foo' "$ERR"; then note_fail "the source line is in the evidence"; fi
if grep -qF 'PreflightRun' "$ERR"; then note_fail "the follow-on diagnostic is in the evidence"; fi
grep -qxF 'PREFLIGHT_RESULT=failed' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks PREFLIGHT_RESULT=failed"
grep -qxF 'FAILED_STAGE=4 spark-stage' "$PFX/uploads/run-record.env" || note_fail "the uploaded run-record.env lacks FAILED_STAGE=4 spark-stage"
[ "$(pf_up_names)" = "preflight.log preflight.scala run-record.env " ] || note_fail "uploaded names: $(pf_up_names)"
grep -qF 'x.scala:12: error: not found: value foo' "$PFX/uploads/preflight.log" || note_fail "the uploaded preflight.log lacks the diagnostic"
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
