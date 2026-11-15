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
# written in a comment ("no sync, no delete") is not mistaken for code.
code_of() {
  grep -v '^[[:space:]]*#' "$1" > "$CODE_DIR/${1##*/}" 2> /dev/null
  printf '%s' "$CODE_DIR/${1##*/}"
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
pi_accept loader-earlier-element-is-another-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"loader\",\"$PI_RUN_OTHER\",\"--flag\"]")" "$PI_RUN"
pi_accept loader-single-element-equals-run-id "$(pi_json "$J_SNAP" "$J_SNAPS" "$J_RUN" "[\"$PI_RUN\"]")" "$PI_RUN"

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

# extract_fn FILE NAME OUT: the function NAME (from "NAME() {" in column 1 to the "}" in column 1).
extract_fn() {
  awk -v start="$2() {" 'index($0, start) == 1 { f = 1 } f { print } f && $0 == "}" { exit }' "$1" > "$3"
}
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

static_begin "14.terraform.s-overlay.timeouts-are-7200-1440-4800-1800"
tf_overlay "$TF_S"
[ "$TF_MAX" = "7200" ] || note_fail "pipeline_max_run_seconds is '$TF_MAX', expected 7200"
[ "$TF_CREATE" = "1440" ] || note_fail "pipeline_create_cluster_timeout_seconds is '$TF_CREATE', expected 1440"
[ "$TF_STEP" = "4800" ] || note_fail "pipeline_emr_step_timeout_seconds is '$TF_STEP', expected 4800"
[ "$TF_LOAD" = "1800" ] || note_fail "pipeline_loader_timeout_seconds is '$TF_LOAD', expected 1800"
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
  [ "$s5_cap" = "8460" ] || note_fail "the third total from the values is $s5_cap, D33 says 8460"
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
  f_has "$TF_README" "The M and L EMR step and loader timeouts keep their repair round 2 values, and so does the S loader ($TF_LOAD s); the S EMR step does not (1800 s, then 3600 s, now $TF_STEP s)."
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
  local pre_spark=874 want_ra=6840 want_wc=9240

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
  f_count_is "$emr_lib" 'timeout -k 30 ' 1
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
