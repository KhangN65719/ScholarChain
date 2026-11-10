#!/usr/bin/env bash
# cleanup.sh: tear down what Terraform created in one environment, in four
# explicit modes. Requires explicit human invocation (--i-am-the-human). It
# deletes only through terraform destroy with cleanup.tfvars. No wildcard, no
# -target, no aws s3 rm, no console steps.
#
# --i-am-the-human is an agreement that a person chose to run this. It is not a
# security control: any process can pass it. The script also refuses when
# SCHOLARCHAIN_AGENT_RUN is set, or when stdin is not a terminal and an agent
# environment (CLAUDECODE, CLAUDE_CODE_ENTRYPOINT) is present. Run it in your own
# terminal. A "!" command inside Claude Code is refused on purpose.
#
# Usage (run the modes in this order, one approval record for each apply mode):
#   bash scripts/cleanup.sh --mode plan-flip    COMMON --image-tag <deployed tag> --i-am-the-human
#   bash scripts/cleanup.sh --mode apply-flip   COMMON --plan-file F --approval-record R --i-am-the-human
#   bash scripts/cleanup.sh --mode plan-destroy COMMON --i-am-the-human
#   bash scripts/cleanup.sh --mode apply-destroy COMMON --plan-file F --approval-record R \
#       --acknowledge-data-loss --i-am-the-human
# COMMON = --environment NAME --account-id ID --cost-ceiling-usd N [--out-dir DIR]
#
#   plan-flip     plans cleanup_mode=true (force_destroy on, deletion protection and
#                 final snapshot off, secret recovery window 0). Must show 0 to add and
#                 0 to destroy; pass the tag that is deployed or the plan will add.
#   apply-flip    applies that saved plan. Terraform needs these flags in state first.
#   plan-destroy  plans terraform destroy and lists every resource, bucket and the
#                 first keys of each bucket BEFORE anything is deleted.
#   apply-destroy lists again, then applies the saved destroy plan, then checks the
#                 state is empty and the buckets are gone. The two general-purpose
#                 buckets are checked with head-bucket. The S3 Vectors bucket check
#                 (aws s3vectors get-vector-bucket) is UNVERIFIED: it reports gone only
#                 on a not-found message, and fails the run with "could not verify"
#                 on anything else, including access denied.
#
# This script only does the full teardown. To stop billing between sessions and
# KEEP storage, use the session-down flow (plan.sh and deploy.sh with
# session-down.tfvars), not this script. See scripts/README.md.
#
# Not removed: anything Terraform did not create (the account budgets, a remote
# state bucket). Everything inside Terraform's own buckets IS removed with them,
# including objects Terraform did not write, because force_destroy empties the
# bucket. That is why --acknowledge-data-loss exists.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

MODE=""
PLAN_FILE=""
IMAGE_TAG=""
HUMAN="no"
ACK_LOSS="no"

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --mode) need_arg "$@"; MODE="$2"; shift 2 ;;
    --plan-file) need_arg "$@"; PLAN_FILE="$2"; shift 2 ;;
    --image-tag) need_arg "$@"; IMAGE_TAG="$2"; shift 2 ;;
    --i-am-the-human) HUMAN="yes"; shift ;;
    --acknowledge-data-loss) ACK_LOSS="yes"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

step human-invocation
require_human "$HUMAN"
step_ok

step validate-params
case "$MODE" in
  plan-flip | plan-destroy) NEEDS_APPROVAL="no" ;;
  apply-flip | apply-destroy) NEEDS_APPROVAL="yes" ;;
  *) die "--mode must be plan-flip, apply-flip, plan-destroy or apply-destroy" ;;
esac
init_common "$NEEDS_APPROVAL"
require_cmd terraform aws git awk tee grep
[[ "$SC_ENV" == "course" ]] || die "cleanup.sh is written for the course environment only; $SC_ENV needs its own reviewed runbook"
[[ -f "$SC_ENV_DIR/cleanup.tfvars" ]] || die "missing $SC_ENV_DIR/cleanup.tfvars"
HEAD_SHA="$(git_head)"
if [[ -z "$IMAGE_TAG" ]]; then IMAGE_TAG="$HEAD_SHA"; fi
require_image_tag "$IMAGE_TAG"
if [[ "$NEEDS_APPROVAL" == "yes" ]]; then
  [[ -n "$PLAN_FILE" ]] || die "--plan-file is required for $MODE"
  [[ "$MODE" != "apply-destroy" || "$ACK_LOSS" == "yes" ]] || die "apply-destroy needs --acknowledge-data-loss: force_destroy deletes bucket contents, including any not written by Terraform"
fi
EXTRA="mode=$MODE;image_tag=$IMAGE_TAG"
maybe_print_digest cleanup "$EXTRA"
step_ok

describe_targets() {
  local name b out
  printf 'AFFECTED_RESOURCES_BEGIN (terraform state list, environment %s)\n' "$SC_ENV"
  tf state list || die "terraform state list failed"
  printf 'AFFECTED_RESOURCES_END\n'
  for name in data_bucket artifacts_bucket vector_bucket; do
    b="$(tf output -raw "$name" 2>/dev/null || true)"
    [[ -n "$b" ]] || { printf 'BUCKET %s: no output (not created or already gone)\n' "$name"; continue; }
    printf 'BUCKET %s=%s\n' "$name" "$b"
    if [[ "$name" != "vector_bucket" ]]; then
      out="$(aws s3api list-objects-v2 --bucket "$b" --max-items 20 --query 'Contents[].Key' --output text 2>/dev/null || true)"
      printf 'FIRST_KEYS (max 20) %s: %s\n' "$b" "${out:-none or not listable}"
    fi
  done
}

step identity-check
verify_identity
step_ok

# dirty_flag: yes when the working tree has uncommitted changes, else no. Written
# into the plan metadata as it is (cleanup does not require a clean tree, but the
# record of it must be true).
dirty_flag() {
  if [[ -n "$(tree_status)" ]]; then printf 'yes'; else printf 'no'; fi
}

# vector_bucket_check NAME: prints EXISTS, GONE or UNVERIFIED:<reason>. The S3
# Vectors bucket is not a general-purpose bucket, so s3api head-bucket does not
# apply. The command used, `aws s3vectors get-vector-bucket --vector-bucket-name`,
# and the not-found error text matched below are UNVERIFIED: they were written
# from the service's naming, not read from the CLI reference or run. The check
# fails closed. GONE needs a not-found message. Access denied, an unknown
# command, a network or credential problem and any unrecognized error are
# UNVERIFIED, never GONE.
vector_bucket_check() {
  local b="$1" err rc=0 first
  err="$(aws s3vectors get-vector-bucket --vector-bucket-name "$b" 2>&1 >/dev/null)" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    printf 'EXISTS'
    return 0
  fi
  first="$(printf '%s' "$err" | head -n 1 | cut -c1-160)"
  if printf '%s' "$err" | grep -qiE 'AccessDenied|Forbidden|Unauthorized|invalid choice|Unknown options|Could not connect|Unable to locate credentials|ExpiredToken'; then
    printf 'UNVERIFIED:%s' "$first"
    return 0
  fi
  if printf '%s' "$err" | grep -qiE 'NotFoundException|NoSuchVectorBucket|does not exist'; then
    printf 'GONE'
    return 0
  fi
  printf 'UNVERIFIED:%s' "$first"
}

tf_init() {
  step terraform-init
  tf init -input=false -no-color
  step_ok
}

make_out_dir

case "$MODE" in
  plan-flip)
    tf_init
    step plan-flip
    PF="$SC_OUT_DIR/$SC_ENV-cleanup-flip.tfplan"
    PL="$SC_OUT_DIR/$SC_ENV-cleanup-flip.plan.log"
    tf_plan_to "$PF" "$PL" "-var-file=$SC_TFVARS" "-var-file=$SC_ENV_DIR/cleanup.tfvars" "-var=image_tag=$IMAGE_TAG"
    scan_for_secrets "$PL"
    plan_summary "$PL"
    grep -qE '^(Plan: 0 to add, [0-9]+ to change, 0 to destroy\.|No changes\.)' "$PL" || die "the flip plan must be in-place only (0 to add, 0 to destroy); pass --image-tag as the deployed tag"
    D="$(sha256_file "$PF")"
    write_meta "$PF.meta" "META_VERSION=1" "MODE=cleanup-flip" "ENVIRONMENT=$SC_ENV" "ACCOUNT_ID=$SC_ACCOUNT" \
      "REGION=$SC_REGION" "GIT_SHA=$HEAD_SHA" "DIRTY=$(dirty_flag)" "IMAGE_TAG=$IMAGE_TAG" "STATEFUL_CHANGE=$SC_STATEFUL_CHANGE" "PLAN_DIGEST=$D"
    printf 'PLAN_FILE=%s\nPLAN_DIGEST=%s\n' "$PF" "$D"
    step_ok
    ;;
  apply-flip)
    step plan-binding
    check_plan_binding "$PLAN_FILE" cleanup-flip no
    [[ "$(meta_get "$SC_PLAN_META" IMAGE_TAG)" == "$IMAGE_TAG" ]] || die "plan image tag differs from --image-tag"
    step_ok
    step approval-record
    verify_approval cleanup "$EXTRA" "$SC_PLAN_DIGEST" "n/a"
    step_ok
    tf_init
    step apply-flip
    describe_targets
    consume_approval cleanup
    tf_apply_saved "$PLAN_FILE" "$SC_OUT_DIR/$SC_ENV-cleanup-flip.apply.log"
    note "flags are in state. Next: --mode plan-destroy, review, approve, --mode apply-destroy."
    step_ok
    ;;
  plan-destroy)
    tf_init
    step list-before-deletion
    describe_targets
    step_ok
    step plan-destroy
    PF="$SC_OUT_DIR/$SC_ENV-cleanup-destroy.tfplan"
    PL="$SC_OUT_DIR/$SC_ENV-cleanup-destroy.plan.log"
    tf_plan_to "$PF" "$PL" -destroy "-var-file=$SC_TFVARS" "-var-file=$SC_ENV_DIR/cleanup.tfvars" "-var=image_tag=$IMAGE_TAG"
    scan_for_secrets "$PL"
    plan_summary "$PL"
    grep -qE '^Plan: 0 to add, 0 to change, [0-9]+ to destroy\.' "$PL" || die "the destroy plan must be 0 to add, 0 to change; got something else (did apply-flip run?)"
    D="$(sha256_file "$PF")"
    write_meta "$PF.meta" "META_VERSION=1" "MODE=cleanup-destroy" "ENVIRONMENT=$SC_ENV" "ACCOUNT_ID=$SC_ACCOUNT" \
      "REGION=$SC_REGION" "GIT_SHA=$HEAD_SHA" "DIRTY=$(dirty_flag)" "IMAGE_TAG=$IMAGE_TAG" "STATEFUL_CHANGE=$SC_STATEFUL_CHANGE" "PLAN_DIGEST=$D"
    printf 'PLAN_FILE=%s\nPLAN_DIGEST=%s\n' "$PF" "$D"
    note "stateful destroys are expected here; the human approves the listed targets by naming PLAN_DIGEST in the record."
    step_ok
    ;;
  apply-destroy)
    step plan-binding
    check_plan_binding "$PLAN_FILE" cleanup-destroy no
    [[ "$(meta_get "$SC_PLAN_META" IMAGE_TAG)" == "$IMAGE_TAG" ]] || die "plan image tag differs from --image-tag"
    step_ok
    step approval-record
    verify_approval cleanup "$EXTRA" "$SC_PLAN_DIGEST" "n/a"
    [[ "$(approval_value STATEFUL_CHANGE_ACKNOWLEDGED)" == "yes" ]] || die "destroy removes stateful resources: the record must set STATEFUL_CHANGE_ACKNOWLEDGED=yes"
    step_ok
    tf_init
    step list-before-deletion
    describe_targets
    tf show -no-color "$PLAN_FILE"
    step_ok
    step apply-destroy
    DATA_B="$(tf output -raw data_bucket 2>/dev/null || true)"
    ART_B="$(tf output -raw artifacts_bucket 2>/dev/null || true)"
    VEC_B="$(tf output -raw vector_bucket 2>/dev/null || true)"
    consume_approval cleanup
    tf_apply_saved "$PLAN_FILE" "$SC_OUT_DIR/$SC_ENV-cleanup-destroy.apply.log"
    step_ok
    step verify-gone
    REMAIN="$(tf state list || true)"
    [[ -z "$REMAIN" ]] || die "state still lists resources after destroy: $REMAIN"
    for b in $DATA_B $ART_B; do
      if aws s3api head-bucket --bucket "$b" 2>/dev/null; then die "bucket still exists after destroy: $b"; fi
      printf 'BUCKET_GONE %s\n' "$b"
    done
    UNVERIFIED_COUNT=0
    if [[ -n "$VEC_B" ]]; then
      VEC_STATE="$(vector_bucket_check "$VEC_B")"
      case "$VEC_STATE" in
        GONE) printf 'VECTOR_BUCKET_GONE %s\n' "$VEC_B" ;;
        EXISTS) die "vector bucket still exists after destroy: $VEC_B" ;;
        *)
          UNVERIFIED_COUNT=$((UNVERIFIED_COUNT + 1))
          printf 'VECTOR_BUCKET_COULD_NOT_VERIFY %s (%s). Check it by hand; the check is UNVERIFIED and never reports gone on an error.\n' "$VEC_B" "$VEC_STATE" ;;
      esac
    fi
    printf 'STATE_EMPTY environment=%s\n' "$SC_ENV"
    note "not touched: account budgets, any remote state bucket, resources Terraform never created. Check billing the next day for stragglers and record actual spend."
    [[ "$UNVERIFIED_COUNT" -eq 0 ]] || die "destroy applied and state is empty, but $UNVERIFIED_COUNT bucket(s) could not be verified gone: confirm the S3 Vectors bucket by hand"
    step_ok
    ;;
esac
