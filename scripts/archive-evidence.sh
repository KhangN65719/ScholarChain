#!/usr/bin/env bash
# archive-evidence.sh: gather the evidence of a release or an experiment into a
# versioned, checksummed directory. It deletes nothing, overwrites nothing and
# calls no cloud service unless an upload is asked for. Two modes.
#
# Gather (local only, no approval record, no AWS):
#   bash scripts/archive-evidence.sh [--out-dir /abs/dir] [--include /abs/file-or-dir]...
#     [--plan-file /abs/course-<sha12>.tfplan] [--deploy-log /abs/deploy.log] [--dry-run]
#   Writes <out-dir>/evidence-<UTC timestamp>-<sha12>/. The default out-dir is
#   ${TMPDIR:-/tmp}/scholarchain-ops/evidence, outside the repository (the plan.sh
#   rule). A directory that exists is never reused.
#   What goes in: only the paths named by --include (a file, or a directory whose
#   files are copied one by one; no glob is expanded, a glob character is refused);
#   a fixed allowlist of repository files (build.sbt, project/DependencyVersions.scala,
#   project/build.properties, Dockerfile, the Terraform provider lock file);
#   the sbt JUnit XML reports under target/test-reports of the root and of each
#   module, when they exist; and generated files: the facts of
#   .claude/.last-test-run (whether it exists and its modification time, never its
#   content), release/metadata.env (commit SHA, tool versions, script digests), the
#   sha256 of --plan-file with its .meta copied (the plan itself is never copied),
#   and, from --deploy-log, the copied log and release/image-digests.txt (the
#   IMAGE_ and revision marker lines). Pipeline and experiment run records, package
#   manifests and CloudWatch metric listings come in through --include; this script
#   calls no CloudWatch.
#   Refused, and named: a symlink, anything named like credentials or secrets, .env
#   or .env.*, *.pem, *.key, *.p12, *.pfx, *.jks, *.keystore, *.tfstate*, *.tfplan,
#   .netrc, .npmrc, .git-credentials, an ssh key, anything under .aws, .ssh, .gnupg
#   or .kube, a path with a glob character, and any file in which the key material
#   patterns of plan.sh match (checked on each source before it is copied and again
#   on the copied tree). The matched text is never printed.
#   Writes MANIFEST.txt (every source, destination and size) and MANIFEST.sha256
#   (sha256sum-compatible, every file but itself).
#   --dry-run lists what would be gathered, generated and refused, copies nothing,
#   and exits 1 when anything would be refused.
#
# Upload (a cloud write: needs an approval record with action "archive"):
#   bash scripts/archive-evidence.sh --upload-dir /abs/evidence-<...> \
#     --s3-prefix s3://bucket/prefix --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 20 --approval-record /abs/approval.env [--dry-run]
#   The directory must be one this script made. Its MANIFEST.sha256 is re-checked
#   file by file and the key material scan runs again. The record binds the S3
#   prefix, the directory name and the sha256 of MANIFEST.sha256, so the human
#   approves the exact content: gather first, review, then approve and upload. One
#   invocation cannot gather and upload, because the content digest does not exist
#   before the gather. The upload is "aws s3 cp --recursive" of that directory to
#   <prefix>/<directory name>/, no sync and no delete, then an object count. The
#   aws wrapper in lib.sh gives it connect and read timeouts and runs it once.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

INCLUDES=""
PLAN_FILE=""
DEPLOY_LOG=""
UPLOAD_DIR=""
S3_PREFIX=""
OUT_DIR_ARG=""
PLAN_LINES=""
REFUSALS=""
GEN_LINES=""
REFUSE_N=0
GATHER_N=0
EV=""
export CHECKPOINT_DISABLE=1

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --include) need_arg "$@"; INCLUDES="${INCLUDES}$2"$'\n'; shift 2 ;;
    --plan-file) need_arg "$@"; PLAN_FILE="$2"; shift 2 ;;
    --deploy-log) need_arg "$@"; DEPLOY_LOG="$2"; shift 2 ;;
    --upload-dir) need_arg "$@"; UPLOAD_DIR="$2"; shift 2 ;;
    --s3-prefix) need_arg "$@"; S3_PREFIX="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

### Refusal rules ------------------------------------------------------------

# refusal_reason PATH: prints why PATH must not be archived, or nothing.
refusal_reason() {
  local f="$1" lower base
  lower="$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')"
  base="${lower##*/}"
  case "$f" in
    *'|'*) printf 'the path holds a | character'; return 0 ;;
  esac
  if [[ -L "$f" ]]; then
    printf 'a symlink'
    return 0
  fi
  case "$base" in
    .env | .env.*) printf 'an environment file'; return 0 ;;
    .smoke-*) printf 'a smoke-test temporary file (.smoke-auth.* holds a bearer token)'; return 0 ;;
    *credential* | *secret*) printf 'named like credentials or a secret'; return 0 ;;
    *.pem | *.key | *.p12 | *.pfx | *.jks | *.keystore) printf 'key material by its name'; return 0 ;;
    *.tfstate | *.tfstate.*) printf 'Terraform state'; return 0 ;;
    *.tfplan | tfplan) printf 'a Terraform plan file (it can hold resource attributes): use --plan-file for its checksum and .meta'; return 0 ;;
    .netrc | .npmrc | id_rsa*| id_ed25519* | id_ecdsa* | id_dsa*) printf 'a credential file'; return 0 ;;
  esac
  case "$lower/" in
    */.aws/* | */.ssh/* | */.gnupg/* | */.kube/*) printf 'under a credential directory'; return 0 ;;
    */secrets/* | */secret/* | */credentials/* | */credential/*) printf 'under a secrets directory'; return 0 ;;
  esac
  return 0
}

# add_item KIND SRC DEST: queue one copy, or one refusal.
add_item() {
  local kind="$1" src="$2" dest="$3" why
  why="$(refusal_reason "$src")"
  if [[ -n "$why" ]]; then
    REFUSALS="${REFUSALS}${src}|${why}"$'\n'
    REFUSE_N=$((REFUSE_N + 1))
    return 0
  fi
  PLAN_LINES="${PLAN_LINES}${kind}|${src}|${dest}"$'\n'
  GATHER_N=$((GATHER_N + 1))
}

refuse_path() {
  REFUSALS="${REFUSALS}$1|$2"$'\n'
  REFUSE_N=$((REFUSE_N + 1))
}

# path_part TEXT: TEXT with every character outside A-Za-z0-9._/- replaced by _.
path_part() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._/-' '_'
}

add_include() {
  local n="$1" p="$2" f rel base
  case "$p" in
    *'*'* | *'?'* | *'['*) refuse_path "$p" "contains a glob character: pass explicit paths, no pattern is expanded"; return 0 ;;
  esac
  if [[ "$p" != /* ]]; then
    refuse_path "$p" "not an absolute path"
    return 0
  fi
  case "$p/" in
    */../* | */./*) refuse_path "$p" "contains . or .. segments"; return 0 ;;
  esac
  if [[ -L "$p" ]]; then
    refuse_path "$p" "a symlink"
  elif [[ -f "$p" ]]; then
    add_item include "$p" "include/$n/$(safe_name "${p##*/}")"
  elif [[ -d "$p" ]]; then
    base="$(safe_name "${p##*/}")"
    while IFS= read -r f; do
      [[ -n "$f" ]] || continue
      rel="${f#"$p"/}"
      add_item include "$f" "include/$n/$base/$(path_part "$rel")"
    done < <(find "$p" \( -type f -o -type l \) -print | LC_ALL=C sort)
  else
    refuse_path "$p" "does not exist or is neither a regular file nor a directory"
  fi
}

### Upload mode --------------------------------------------------------------

# verify_evidence_dir DIR: every line of MANIFEST.sha256 matches its file, no file
# is missing from it, no symlink, no refused name, no key material.
verify_evidence_dir() {
  local d="$1" line h p n=0 files f why
  [[ -d "$d" && ! -L "$d" ]] || die "not a directory: $d"
  [[ -f "$d/MANIFEST.sha256" && -f "$d/MANIFEST.txt" ]] || die "$d is not an evidence directory made by this script (no MANIFEST.sha256 and MANIFEST.txt)"
  [[ -z "$(find "$d" -type l | head -n 1)" ]] || die "the evidence directory holds a symlink"
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    h="${line%%  *}"
    p="${line#*  }"
    [[ "$h" =~ ^[0-9a-f]{64}$ ]] || die "malformed line in MANIFEST.sha256"
    [[ "$p" != /* ]] || die "MANIFEST.sha256 names an absolute path: $p"
    case "/$p/" in
      */../* | */./*) die "MANIFEST.sha256 names a path with . or .. segments: $p" ;;
    esac
    [[ -f "$d/$p" ]] || die "MANIFEST.sha256 names a file that is missing: $p"
    [[ "$(sha256_file "$d/$p")" == "$h" ]] || die "the file changed after it was archived: $p"
    n=$((n + 1))
  done < "$d/MANIFEST.sha256"
  files="$(find "$d" -type f ! -name MANIFEST.sha256 -print | awk 'END { print NR }')"
  [[ "$files" -eq "$n" ]] || die "the directory holds $files file(s) besides MANIFEST.sha256 but MANIFEST.sha256 lists $n: refusing to upload an unlisted file"
  while IFS= read -r f; do
    why="$(refusal_reason "$f")"
    if [[ -n "$why" ]]; then
      die "refusing to archive or upload $f: $why"
    fi
    scan_for_secrets "$f"
  done < <(find "$d" -type f -print | LC_ALL=C sort)
  UPLOAD_FILE_COUNT="$((n + 1))"
}

run_upload_mode() {
  local ev_name re
  [[ -n "$UPLOAD_DIR" && -n "$S3_PREFIX" ]] || die "an upload needs both --upload-dir and --s3-prefix"
  [[ -z "$INCLUDES$PLAN_FILE$DEPLOY_LOG" ]] || die "--include, --plan-file and --deploy-log belong to a gather run; an upload takes an existing evidence directory"
  [[ -z "$OUT_DIR_ARG" ]] || die "--out-dir is not used when uploading"
  step validate-params
  init_common yes
  require_cmd git awk grep find
  [[ "$UPLOAD_DIR" == /* ]] || die "--upload-dir must be an absolute path"
  case "$UPLOAD_DIR/" in
    */../* | */./*) die "--upload-dir must not contain . or .. segments" ;;
  esac
  ev_name="${UPLOAD_DIR%/}"
  ev_name="${ev_name##*/}"
  re='^evidence-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$'
  [[ "$ev_name" =~ $re ]] || die "--upload-dir must be named evidence-<UTC timestamp>-<sha12>, as this script names it: $ev_name"
  re='^s3://[a-z0-9][a-z0-9.-]{2,62}(/[A-Za-z0-9._/-]*)?$'
  [[ "$S3_PREFIX" =~ $re ]] || die "--s3-prefix must look like s3://bucket/prefix"
  case "${S3_PREFIX#s3://}" in
    *..* | *//*) die "--s3-prefix must not contain .. or doubled slashes" ;;
  esac
  S3_PREFIX="${S3_PREFIX%/}"
  verify_evidence_dir "${UPLOAD_DIR%/}"
  MANIFEST_DIGEST="$(sha256_file "${UPLOAD_DIR%/}/MANIFEST.sha256")"
  EXTRA="s3_prefix=$S3_PREFIX;evidence_dir=$ev_name;manifest_sha256=$MANIFEST_DIGEST"
  maybe_print_digest archive "$EXTRA"
  printf 'EVIDENCE_DIR_VERIFIED dir=%s files=%s manifest_sha256=%s\n' "$ev_name" "$UPLOAD_FILE_COUNT" "$MANIFEST_DIGEST"
  step_ok

  if is_dry_run; then
    dry_banner archive "$EXTRA"
  fi

  step approval-record
  verify_approval archive "$EXTRA" "n/a" "n/a"
  step_ok

  if is_dry_run; then
    dry_approval_unconsumed
    dry_step identity-check
    dry_would "aws sts get-caller-identity (skipped)"
    dry_step upload
    dry_would "consume the approval record; aws s3 cp ${UPLOAD_DIR%/} $S3_PREFIX/$ev_name/ --recursive --no-follow-symlinks --only-show-errors (connect 10 s and read 60 s timeouts, run once, no sync, no delete)"
    dry_step verify-upload
    dry_would "aws s3 ls $S3_PREFIX/$ev_name/ --recursive: expect $UPLOAD_FILE_COUNT objects"
    dry_finish
  fi

  step identity-check
  verify_identity
  step_ok

  step upload
  consume_approval archive
  aws s3 cp "${UPLOAD_DIR%/}" "$S3_PREFIX/$ev_name/" --recursive --no-follow-symlinks --only-show-errors
  step_ok

  step verify-upload
  local listed
  listed="$(aws s3 ls "$S3_PREFIX/$ev_name/" --recursive | awk 'END { print NR }')"
  printf 'UPLOADED prefix=%s/%s/ objects_listed=%s objects_expected=%s\n' "$S3_PREFIX" "$ev_name" "$listed" "$UPLOAD_FILE_COUNT"
  [[ "$listed" -eq "$UPLOAD_FILE_COUNT" ]] || die "the listing shows $listed object(s), expected $UPLOAD_FILE_COUNT: check the prefix by hand"
  step_ok
}

if [[ -n "$UPLOAD_DIR" || -n "$S3_PREFIX" ]]; then
  OUT_DIR_ARG="$SC_OUT_DIR"
  run_upload_mode
  exit 0
fi

### Gather mode --------------------------------------------------------------

step validate-params
[[ -z "$SC_APPROVAL_FILE$SC_ENV$SC_ACCOUNT$SC_COST" ]] || die "--approval-record, --environment, --account-id and --cost-ceiling-usd belong to an upload (--upload-dir with --s3-prefix); a local gather takes none of them"
[[ "$SC_PRINT_DIGEST" -eq 0 ]] || die "--print-digest belongs to an upload: a local gather has no approval record"
require_cmd git awk grep find
HEAD_SHA="$(git_head)"
require_image_tag "$HEAD_SHA"
SHORT_SHA="${HEAD_SHA:0:12}"
if [[ -z "$SC_OUT_DIR" ]]; then
  SC_OUT_DIR="${TMPDIR:-/tmp}"
  SC_OUT_DIR="${SC_OUT_DIR%/}/scholarchain-ops/evidence"
fi
require_external_dir "$SC_OUT_DIR" "--out-dir"
STAMP="$(utc_stamp)"
EV_NAME="evidence-${STAMP}-${SHORT_SHA}"
EV="$SC_OUT_DIR/$EV_NAME"
[[ ! -e "$EV" ]] || die "the evidence directory already exists and is never reused: $EV"
for opt in "$PLAN_FILE" "$DEPLOY_LOG"; do
  if [[ -n "$opt" && "$opt" != /* ]]; then die "--plan-file and --deploy-log must be absolute paths: $opt"; fi
done
step_ok

step plan-sources
n=0
while IFS= read -r inc; do
  [[ -n "$inc" ]] || continue
  n=$((n + 1))
  add_include "$n" "$inc"
done <<EOF
$INCLUDES
EOF

for rel in build.sbt project/DependencyVersions.scala project/build.properties Dockerfile infra/terraform/environments/course/.terraform.lock.hcl; do
  if [[ -f "$SC_REPO_ROOT/$rel" ]]; then
    add_item repo "$SC_REPO_ROOT/$rel" "repo/$rel"
  else
    note "allowlisted repository file not present, nothing to archive: $rel"
  fi
done

REPORT_COUNT=0
for m in . modules/core modules/hadoop modules/entity-resolution modules/spark modules/storage modules/rag modules/services; do
  rd="$SC_REPO_ROOT/$m/target/test-reports"
  [[ -d "$rd" ]] || continue
  label="$(safe_name "$m")"
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    add_item test-report "$f" "test-reports/$label/$(safe_name "${f##*/}")"
    REPORT_COUNT=$((REPORT_COUNT + 1))
  done < <(find "$rd" -maxdepth 1 -type f -name '*.xml' -print | LC_ALL=C sort)
done
if [[ "$REPORT_COUNT" -eq 0 ]]; then
  note "no JUnit XML test reports found under target/test-reports of the root or the modules: none archived"
fi

if [[ -n "$PLAN_FILE" ]]; then
  if [[ ! -f "$PLAN_FILE" ]]; then
    refuse_path "$PLAN_FILE" "--plan-file does not exist"
  elif [[ ! -f "$PLAN_FILE.meta" ]]; then
    refuse_path "$PLAN_FILE.meta" "the plan metadata next to --plan-file does not exist (plan.sh writes it)"
  else
    add_item plan-meta "$PLAN_FILE.meta" "plan/$(safe_name "${PLAN_FILE##*/}").meta"
    GEN_LINES="${GEN_LINES}plan/plan.sha256|the sha256 of the plan file, which itself is not copied"$'\n'
  fi
fi
if [[ -n "$DEPLOY_LOG" ]]; then
  if [[ ! -f "$DEPLOY_LOG" ]]; then
    refuse_path "$DEPLOY_LOG" "--deploy-log does not exist"
  else
    add_item deploy-log "$DEPLOY_LOG" "release/$(safe_name "${DEPLOY_LOG##*/}")"
    GEN_LINES="${GEN_LINES}release/image-digests.txt|the IMAGE_ and revision marker lines of the deploy log"$'\n'
  fi
fi
GEN_LINES="${GEN_LINES}test/last-test-run.facts|existence and modification time of .claude/.last-test-run, never its content"$'\n'
GEN_LINES="${GEN_LINES}release/metadata.env|commit SHA, tool versions, script digests"$'\n'
printf 'PLANNED sources=%s generated=%s refused=%s\n' "$GATHER_N" "$(printf '%s' "$GEN_LINES" | awk 'END { print NR }')" "$REFUSE_N"
step_ok

if is_dry_run; then
  printf 'EVIDENCE_DIR=%s (would be created once, mode 700; not created now)\n' "$EV"
  while IFS='|' read -r kind src dest; do
    [[ -n "$kind" ]] || continue
    printf 'WOULD_GATHER kind=%s source=%s dest=%s\n' "$kind" "$src" "$dest"
  done <<EOF
$PLAN_LINES
EOF
  while IFS='|' read -r dest what; do
    [[ -n "$dest" ]] || continue
    printf 'WOULD_GENERATE dest=%s (%s)\n' "$dest" "$what"
  done <<EOF
$GEN_LINES
EOF
  while IFS='|' read -r src why; do
    [[ -n "$src" ]] || continue
    printf 'WOULD_REFUSE file=%s reason=%s\n' "$src" "$why"
  done <<EOF
$REFUSALS
EOF
  dry_step scan-sources
  dry_would "scan each source for key material before it is copied"
  dry_step copy
  dry_would "copy each source once into $EV, refusing a destination that exists"
  dry_step generate
  dry_would "write the generated files listed above"
  dry_step manifest
  dry_would "write MANIFEST.txt and MANIFEST.sha256, rescan the copied tree, re-hash every file"
  if [[ "$REFUSE_N" -gt 0 ]]; then
    SC_STEP_OPEN=0
    printf 'DRY_RUN_RESULT=WOULD_REFUSE count=%s (nothing was called or written)\n' "$REFUSE_N"
    printf 'EVIDENCE: a real run would refuse %s path(s), listed above as WOULD_REFUSE\n' "$REFUSE_N" >&2
    exit 1
  fi
  dry_finish
fi

if [[ "$REFUSE_N" -gt 0 ]]; then
  while IFS='|' read -r src why; do
    [[ -n "$src" ]] || continue
    printf 'REFUSED file=%s reason=%s\n' "$src" "$why" >&2
  done <<EOF
$REFUSALS
EOF
  die "refusing to archive: $REFUSE_N path(s) are not allowed, named above. Nothing was copied."
fi

step scan-sources
while IFS='|' read -r kind src _; do
  [[ -n "$kind" ]] || continue
  scan_for_secrets "$src"
done <<EOF
$PLAN_LINES
EOF
step_ok

step create-directory
make_out_dir
(umask 077 && mkdir "$EV") || die "cannot create the evidence directory: $EV"
printf 'EVIDENCE_DIR=%s\n' "$EV"
step_ok

copy_item() {
  local src="$1" dest="$2" d
  d="$EV/$dest"
  [[ ! -e "$d" ]] || die "destination already exists (two sources map to one name): $dest"
  (umask 077 && mkdir -p "$(dirname "$d")")
  cp -- "$src" "$d"
}

gen_file() {
  local d="$EV/$1"
  [[ ! -e "$d" ]] || die "generated file collides with a copied file: $1"
  (umask 077 && mkdir -p "$(dirname "$d")")
  printf '%s' "$2" > "$d"
}

step copy
SOURCE_LINES=""
while IFS='|' read -r kind src dest; do
  [[ -n "$kind" ]] || continue
  copy_item "$src" "$dest"
  size="$(wc -c < "$src")"
  SOURCE_LINES="${SOURCE_LINES}SOURCE kind=${kind} path=${src} dest=${dest} bytes=${size// /}"$'\n'
done <<EOF
$PLAN_LINES
EOF
printf 'COPIED files=%s\n' "$GATHER_N"
step_ok

step generate
MARKER="$SC_REPO_ROOT/.claude/.last-test-run"
if [[ -f "$MARKER" ]]; then
  MARKER_MTIME="$(file_mtime_iso "$MARKER")" || MARKER_MTIME="unreadable"
  FACTS="EXISTS=yes"$'\n'"MTIME_UTC=${MARKER_MTIME}"$'\n'
else
  FACTS="EXISTS=no"$'\n'
fi
FACTS="${FACTS}CONTENT_ARCHIVED=no"$'\n'
FACTS="${FACTS}MEANING=a passing sbt check or sbt quick wrote it; the file does not say which, and a quick run skips the slowSuites"$'\n'
gen_file test/last-test-run.facts "$FACTS"

DIRTY="no"
if [[ -n "$(tree_status)" ]]; then DIRTY="yes"; fi
BRANCH="detached"
if git -C "$SC_REPO_ROOT" symbolic-ref -q HEAD >/dev/null; then
  BRANCH="$(git -C "$SC_REPO_ROOT" symbolic-ref --short HEAD)"
fi
SBT_VERSION="unknown"
if [[ -f "$SC_REPO_ROOT/project/build.properties" ]]; then
  SBT_VERSION="$(awk -F= '/^sbt\.version[[:space:]]*=/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "$SC_REPO_ROOT/project/build.properties")"
fi
GIT_V="$(git --version)"
PY_V="absent"
if type -P python3 >/dev/null 2>&1; then PY_V="$(python3 --version 2>&1)" || PY_V="unavailable"; fi
JAVA_V="absent"
if type -P java >/dev/null 2>&1; then
  JAVA_V="$(java -version 2>&1)" || JAVA_V="unavailable"
  JAVA_V="${JAVA_V%%$'\n'*}"
fi
TF_V="absent"
if type -P terraform >/dev/null 2>&1; then
  TF_V="$(terraform version 2>&1)" || TF_V="unavailable"
  TF_V="${TF_V%%$'\n'*}"
fi
META="ARCHIVE_VERSION=1"$'\n'
META="${META}CREATED_UTC=$(utc_now)"$'\n'
META="${META}GIT_SHA=${HEAD_SHA}"$'\n'
META="${META}GIT_BRANCH=$(safe_name "$BRANCH")"$'\n'
META="${META}DIRTY=${DIRTY}"$'\n'
META="${META}TOOL_GIT=$(safe_name "$GIT_V")"$'\n'
META="${META}TOOL_BASH=$(safe_name "$BASH_VERSION")"$'\n'
META="${META}TOOL_PYTHON3=$(safe_name "$PY_V")"$'\n'
META="${META}TOOL_JAVA=$(safe_name "$JAVA_V")"$'\n'
META="${META}TOOL_TERRAFORM=$(safe_name "$TF_V")"$'\n'
META="${META}TOOL_SBT_VERSION=${SBT_VERSION}"$'\n'
META="${META}ARCHIVE_SCRIPT_DIGEST=$(script_digest)"$'\n'
for f in "$SC_REPO_ROOT"/scripts/*.sh; do
  [[ -f "$f" ]] || continue
  key="$(printf '%s' "${f##*/}" | tr 'a-z.-' 'A-Z__')"
  META="${META}SCRIPT_SHA256_${key}=$(sha256_file "$f")"$'\n'
done
gen_file release/metadata.env "$META"

if [[ -n "$PLAN_FILE" ]]; then
  gen_file plan/plan.sha256 "$(sha256_file "$PLAN_FILE")  ${PLAN_FILE##*/}"$'\n'
fi
if [[ -n "$DEPLOY_LOG" ]]; then
  DIGESTS="$(grep -E '^(IMAGE_EXISTS|IMAGE_PRESENT|IMAGE_PUSHED|REVISION_OK|SERVICE_REVISION_OK|DEPLOYED_REVISION|DEPLOY_COMPLETE)[ =]' "$DEPLOY_LOG" || true)"
  gen_file release/image-digests.txt "${DIGESTS}"$'\n'
fi
step_ok

step manifest
{
  printf 'ARCHIVE_VERSION=1\n'
  printf 'CREATED_UTC=%s\n' "$(utc_now)"
  printf 'GIT_SHA=%s\n' "$HEAD_SHA"
  printf 'EVIDENCE_NAME=%s\n' "$EV_NAME"
  printf '%s' "$SOURCE_LINES"
  while IFS='|' read -r dest what; do
    [[ -n "$dest" ]] || continue
    printf 'GENERATED dest=%s (%s)\n' "$dest" "$what"
  done <<EOF
$GEN_LINES
EOF
} > "$EV/MANIFEST.txt"
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  scan_for_secrets "$f"
done < <(find "$EV" -type f -print | LC_ALL=C sort)
(
  cd "$EV"
  find . -type f ! -name MANIFEST.sha256 -print | LC_ALL=C sort | while IFS= read -r f; do
    printf '%s  %s\n' "$(sha256_file "$f")" "${f#./}"
  done
) > "$EV/MANIFEST.sha256"
verify_evidence_dir "$EV"
printf 'EVIDENCE_DIR=%s\n' "$EV"
printf 'MANIFEST_SHA256=%s\n' "$(sha256_file "$EV/MANIFEST.sha256")"
printf 'ARCHIVE_COMPLETE files=%s\n' "$UPLOAD_FILE_COUNT"
note "local only: nothing was uploaded. To upload, review the directory, write an approval record with action archive (--print-digest), then run this script with --upload-dir and --s3-prefix."
step_ok
