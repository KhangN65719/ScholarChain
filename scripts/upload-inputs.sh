#!/usr/bin/env bash
# upload-inputs.sh: put the pipeline inputs in S3, once, under a human approval record
# (action "upload"). A cloud write, so it needs --approval-record. Copies files one by
# one with "aws s3 cp": no sync, no delete, no overwrite of anything else, the bucket's
# default encryption only (never --sse-c), and each object is checked afterwards with
# head-object. Written, not run.
#
# Usage:
#   bash scripts/upload-inputs.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 20 --approval-record /abs/approval.env \
#     --snapshot phase5-dblp-subset --data-bucket NAME --artifacts-bucket NAME \
#     --spark-dir /abs/outside/repo/spark-4.0.5 --max-objects 120 --max-bytes 1500000000 \
#     [--dry-run | --print-digest]
#
# What is uploaded (bucket names come from the Terraform outputs; the human gives them):
#   data bucket       raw/<snapshot>/{dblp-subset.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}
#                     from datasets/subset/
#   artifacts bucket  jars/<module>/<main>.jar and jars/<module>/lib/*.jar for hadoop,
#                     entity-resolution and spark, from modules/<module>/target/job/
#                     (built by sbt packageJobs, through scripts/package.sh)
#                     bootstrap/*.sh and bootstrap/spark-yarn.conf from scripts/emr/
#                     jars/spark-4.0.5/{spark-4.0.5-bin-hadoop3.tgz,spark-4.0.5-jars.zip,
#                     spark-4.0.5.sha256} from --spark-dir (outside the repository; the
#                     human builds it: scripts/README.md, "Spark 4.0.5 archive")
#   --max-objects     refuse when the file list is longer (required)
#   --max-bytes       refuse when the total size is larger (required)
# The record binds the exact file list: every destination with its sha256, in the
# digested arguments. A changed file, an added file or a different bucket needs a new
# record. A re-upload of an existing key replaces that object (the S3 versioning of the
# bucket decides whether the old one is kept: off in the course environment).
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

SNAPSHOT=""
DATA_BUCKET=""
ARTIFACTS_BUCKET=""
SPARK_DIR=""
MAX_OBJECTS=""
MAX_BYTES=""
PLAN=""
MISSING=""
TOTAL_BYTES=0
OBJECT_COUNT=0

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --snapshot) need_arg "$@"; SNAPSHOT="$2"; shift 2 ;;
    --data-bucket) need_arg "$@"; DATA_BUCKET="$2"; shift 2 ;;
    --artifacts-bucket) need_arg "$@"; ARTIFACTS_BUCKET="$2"; shift 2 ;;
    --spark-dir) need_arg "$@"; SPARK_DIR="$2"; shift 2 ;;
    --max-objects) need_arg "$@"; MAX_OBJECTS="$2"; shift 2 ;;
    --max-bytes) need_arg "$@"; MAX_BYTES="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

refused_name() {
  local base lower
  base="${1##*/}"
  lower="$(printf '%s' "$base" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    .env | .env.* | *credential* | *secret* | *.pem | *.key | *.p12 | *.pfx | *.tfstate* | *.tfplan | .netrc | .npmrc | id_rsa* | id_ed25519*) return 0 ;;
  esac
  return 1
}

# add_file SRC BUCKET KEY SCAN(yes|no): queue one upload as SRC|BUCKET|KEY|BYTES|SHA256.
add_file() {
  local src="$1" bucket="$2" key="$3" scan="$4" bytes sha
  if [[ ! -f "$src" || -L "$src" ]]; then
    MISSING="${MISSING}${MISSING:+; }not a regular file (or a symlink): $src"
    return 0
  fi
  if refused_name "$src"; then
    die "refusing a file named like a credential or secret: $src"
  fi
  if [[ "$scan" == "yes" ]]; then
    scan_for_secrets "$src"
  fi
  bytes="$(wc -c < "$src" | tr -d ' ')"
  sha="$(sha256_file "$src")"
  PLAN="${PLAN}${src}|${bucket}|${key}|${bytes}|${sha}"$'\n'
  OBJECT_COUNT=$((OBJECT_COUNT + 1))
  TOTAL_BYTES=$((TOTAL_BYTES + bytes))
}

step validate-params
init_common yes
require_cmd git awk grep find wc
[[ -n "$SNAPSHOT" && -n "$DATA_BUCKET" && -n "$ARTIFACTS_BUCKET" && -n "$SPARK_DIR" ]] || die "--snapshot, --data-bucket, --artifacts-bucket and --spark-dir are required"
[[ -n "$MAX_OBJECTS" && -n "$MAX_BYTES" ]] || die "--max-objects and --max-bytes are required"
[[ "$SNAPSHOT" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "--snapshot must be 1 to 64 characters of A-Za-z0-9._-"
[[ "$SNAPSHOT" != "." && "$SNAPSHOT" != ".." ]] || die "--snapshot must not be . or .."
for b in "$DATA_BUCKET" "$ARTIFACTS_BUCKET"; do
  [[ "$b" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || die "not a bucket name: $b"
done
[[ "$DATA_BUCKET" != "$ARTIFACTS_BUCKET" ]] || die "the data and artifacts buckets must differ"
[[ "$MAX_OBJECTS" =~ ^[0-9]+$ && "$MAX_OBJECTS" -ge 1 ]] || die "--max-objects must be a whole number of at least 1"
[[ "$MAX_BYTES" =~ ^[0-9]+$ && "$MAX_BYTES" -ge 1 ]] || die "--max-bytes must be a whole number of at least 1"
require_external_dir "$SPARK_DIR" "--spark-dir"
step_ok

step plan-files
for f in dblp-subset.xml openalex.jsonl crossref.jsonl orcid.jsonl; do
  add_file "$SC_REPO_ROOT/datasets/subset/$f" "$DATA_BUCKET" "raw/$SNAPSHOT/$f" yes
done
for m in hadoop entity-resolution spark; do
  jd="$SC_REPO_ROOT/modules/$m/target/job"
  if [[ ! -d "$jd/lib" ]]; then
    MISSING="${MISSING}${MISSING:+; }no $jd/lib (run sbt packageJobs through scripts/package.sh)"
    continue
  fi
  jar_n=0
  for j in "$jd"/*.jar; do
    [[ -f "$j" ]] || continue
    jar_n=$((jar_n + 1))
    add_file "$j" "$ARTIFACTS_BUCKET" "jars/$m/${j##*/}" yes
  done
  [[ "$jar_n" -eq 1 ]] || MISSING="${MISSING}${MISSING:+; }expected exactly one main jar in $jd, found $jar_n"
  for j in "$jd"/lib/*.jar; do
    [[ -f "$j" ]] || continue
    add_file "$j" "$ARTIFACTS_BUCKET" "jars/$m/lib/${j##*/}" yes
  done
done
for f in "$SC_REPO_ROOT"/scripts/emr/*.sh "$SC_REPO_ROOT"/scripts/emr/spark-yarn.conf; do
  add_file "$f" "$ARTIFACTS_BUCKET" "bootstrap/${f##*/}" yes
done
# The Spark files come from outside the repository and are large binaries: not
# scanned for key material. Their checksum file is verified against the files below.
for f in spark-4.0.5-bin-hadoop3.tgz spark-4.0.5-jars.zip spark-4.0.5.sha256; do
  add_file "$SPARK_DIR/$f" "$ARTIFACTS_BUCKET" "jars/spark-4.0.5/$f" no
done
if [[ -z "$MISSING" ]]; then
  for f in spark-4.0.5-bin-hadoop3.tgz spark-4.0.5-jars.zip; do
    want="$(awk -v n="$f" '$2 == n { print $1; exit }' "$SPARK_DIR/spark-4.0.5.sha256")"
    [[ "$want" =~ ^[0-9a-f]{64}$ ]] || die "spark-4.0.5.sha256 has no sha256 line for $f"
    [[ "$(sha256_file "$SPARK_DIR/$f")" == "$want" ]] || die "$f does not match spark-4.0.5.sha256"
  done
  printf 'SPARK_CHECKSUMS_VERIFIED file=%s/spark-4.0.5.sha256\n' "$SPARK_DIR"
fi
if [[ -n "$MISSING" ]]; then
  if is_dry_run; then
    dry_skipped file-list "$MISSING"
  else
    die "cannot build the file list: $MISSING"
  fi
else
  [[ "$OBJECT_COUNT" -le "$MAX_OBJECTS" ]] || die "$OBJECT_COUNT objects exceed --max-objects $MAX_OBJECTS"
  [[ "$TOTAL_BYTES" -le "$MAX_BYTES" ]] || die "$TOTAL_BYTES bytes exceed --max-bytes $MAX_BYTES"
  printf 'UPLOAD_PLAN objects=%s bytes=%s max_objects=%s max_bytes=%s\n' "$OBJECT_COUNT" "$TOTAL_BYTES" "$MAX_OBJECTS" "$MAX_BYTES"
  while IFS='|' read -r src bucket key bytes sha; do
    [[ -n "$src" ]] || continue
    printf 'WOULD_UPLOAD s3://%s/%s bytes=%s sha256=%s\n' "$bucket" "$key" "$bytes" "$sha"
  done <<EOF
$PLAN
EOF
fi
step_ok

if [[ -n "$MISSING" ]]; then
  EXTRA="@skip:$MISSING"
else
  LIST_DIGEST="$(while IFS='|' read -r _ bucket key bytes sha; do
    [[ -n "$bucket" ]] || continue
    printf '%s  %s/%s  %s\n' "$sha" "$bucket" "$key" "$bytes"
  done <<EOF | LC_ALL=C sort | sha256_stdin
$PLAN
EOF
  )"
  EXTRA="snapshot=$SNAPSHOT;data_bucket=$DATA_BUCKET;artifacts_bucket=$ARTIFACTS_BUCKET;objects=$OBJECT_COUNT;bytes=$TOTAL_BYTES;max_objects=$MAX_OBJECTS;max_bytes=$MAX_BYTES;files=$LIST_DIGEST"
fi
maybe_print_digest upload "$EXTRA"

if is_dry_run; then
  dry_banner upload "$EXTRA"
fi

step approval-record
verify_approval upload "$EXTRA" "n/a" "n/a"
step_ok

if is_dry_run; then
  dry_approval_unconsumed
  dry_step identity-check
  dry_would "aws sts get-caller-identity (skipped)"
  dry_step upload
  dry_would "consume the approval record; one aws s3 cp per file, in the order listed above (no sync, no delete, default bucket encryption, run once each)"
  dry_step verify-upload
  dry_would "aws s3api head-object for every key: ContentLength must equal the local size; the reported ServerSideEncryption is printed"
  dry_finish
fi

step identity-check
verify_identity
step_ok

step upload
consume_approval upload
n=0
while IFS='|' read -r src bucket key bytes sha; do
  [[ -n "$src" ]] || continue
  n=$((n + 1))
  aws s3 cp "$src" "s3://$bucket/$key" --only-show-errors
  printf 'UPLOADED %s/%s s3://%s/%s\n' "$n" "$OBJECT_COUNT" "$bucket" "$key"
done <<EOF
$PLAN
EOF
step_ok

step verify-upload
while IFS='|' read -r src bucket key bytes sha; do
  [[ -n "$src" ]] || continue
  got="$(aws s3api head-object --bucket "$bucket" --key "$key" --query ContentLength --output text)" || die "head-object failed for s3://$bucket/$key"
  [[ "$got" == "$bytes" ]] || die "size mismatch for s3://$bucket/$key: remote $got, local $bytes"
  enc="$(aws s3api head-object --bucket "$bucket" --key "$key" --query ServerSideEncryption --output text)" || enc="unknown"
  printf 'VERIFIED s3://%s/%s bytes=%s sse=%s sha256=%s\n' "$bucket" "$key" "$got" "$enc" "$sha"
done <<EOF
$PLAN
EOF
printf 'UPLOAD_COMPLETE objects=%s bytes=%s snapshot=%s\n' "$OBJECT_COUNT" "$TOTAL_BYTES" "$SNAPSHOT"
step_ok
