#!/usr/bin/env bash
# rollback.sh: return to a previous image tag, or to a previous Terraform state
# version. Same approval record contract as deploy.sh.
#
# Mode A, previous image tag (needs a plan made by plan.sh --image-tag <TAG>):
#   bash scripts/rollback.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 25 --approval-record /abs/approval.env \
#     --image-tag <40-hex git sha> --plan-file /abs/course-<sha12>.tfplan
#   The old tag must still exist in both ECR repositories (five images are kept).
#   Only the saved plan is applied. The revision is verified afterwards.
#
# Mode B, previous state version (S3 backend with bucket versioning only):
#   bash scripts/rollback.sh ... --state-bucket B --state-key K --state-version-id V
#   Backs up the current state, checks lineage and that V is older, pushes V with
#   terraform state push -force, then prints a full drift plan. State push does
#   not change real resources, so the printed plan shows what Terraform would then
#   do. Provisional: the remote backend does not exist until Phase 14.
#
# Refuses: a dirty tree (mode A), a plan or record that does not name the exact
# tag or version, another account or region, and a tag not present in ECR.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

TARGET_TAG=""
PLAN_FILE=""
STATE_BUCKET=""
STATE_KEY=""
STATE_VERSION=""

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --image-tag) need_arg "$@"; TARGET_TAG="$2"; shift 2 ;;
    --plan-file) need_arg "$@"; PLAN_FILE="$2"; shift 2 ;;
    --state-bucket) need_arg "$@"; STATE_BUCKET="$2"; shift 2 ;;
    --state-key) need_arg "$@"; STATE_KEY="$2"; shift 2 ;;
    --state-version-id) need_arg "$@"; STATE_VERSION="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

step validate-params
init_common yes
require_cmd terraform aws git awk tee grep
if [[ -n "$TARGET_TAG" && -z "$STATE_VERSION" ]]; then
  MODE="image"
  require_image_tag "$TARGET_TAG"
  [[ -n "$PLAN_FILE" ]] || die "--plan-file is required with --image-tag"
  [[ -z "$STATE_BUCKET$STATE_KEY" ]] || die "state parameters do not belong with --image-tag"
  EXTRA="mode=image;image_tag=$TARGET_TAG"
elif [[ -n "$STATE_VERSION" && -z "$TARGET_TAG" ]]; then
  MODE="state"
  [[ -n "$STATE_BUCKET" && -n "$STATE_KEY" ]] || die "--state-bucket and --state-key are required with --state-version-id"
  [[ -z "$PLAN_FILE" ]] || die "--plan-file does not belong with --state-version-id"
  [[ "$STATE_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || die "state bucket name is not valid"
  [[ "$STATE_KEY" =~ ^[A-Za-z0-9._/-]+$ ]] || die "state key is not valid"
  [[ "$STATE_VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || die "state version id is not valid"
  EXTRA="mode=state;bucket=$STATE_BUCKET;key=$STATE_KEY;version=$STATE_VERSION"
else
  die "give exactly one of --image-tag or --state-version-id"
fi
maybe_print_digest rollback "$EXTRA"
step_ok

if [[ "$MODE" == "image" ]]; then
  step clean-tree
  require_clean_tree
  step_ok

  step plan-binding
  check_plan_binding "$PLAN_FILE" apply yes
  [[ "$(meta_get "$SC_PLAN_META" IMAGE_TAG)" == "$TARGET_TAG" ]] || die "plan image tag $(meta_get "$SC_PLAN_META" IMAGE_TAG) is not the rollback target $TARGET_TAG"
  step_ok

  step approval-record
  verify_approval rollback "$EXTRA" "$SC_PLAN_DIGEST" "$(git_head)"
  if [[ "$(meta_get "$SC_PLAN_META" STATEFUL_CHANGE)" == "yes" ]]; then
    [[ "$(approval_value STATEFUL_CHANGE_ACKNOWLEDGED)" == "yes" ]] || die "the plan changes stateful resources and the record does not acknowledge it"
  fi
  step_ok

  step identity-check
  verify_identity
  step_ok

  step target-exists
  for role in services loader; do
    aws ecr describe-images --repository-name "${SC_NAME_PREFIX}-${role}" --image-ids "imageTag=$TARGET_TAG" \
      --query 'imageDetails[0].imageDigest' --output text >/dev/null \
      || die "rollback target $TARGET_TAG is not in ${SC_NAME_PREFIX}-${role}"
    printf 'TARGET_PRESENT repository=%s tag=%s\n' "${SC_NAME_PREFIX}-${role}" "$TARGET_TAG"
  done
  step_ok

  step terraform-apply
  consume_approval rollback
  tf init -input=false -no-color
  make_out_dir
  APPLY_LOG="$SC_OUT_DIR/$SC_ENV-rollback-$(date -u +%Y%m%dT%H%M%SZ).apply.log"
  tf_apply_saved "$PLAN_FILE" "$APPLY_LOG"
  printf 'APPLY_LOG=%s\n' "$APPLY_LOG"
  step_ok

  step verify-revision
  verify_revision "$TARGET_TAG"
  step_ok

  printf 'ROLLBACK_COMPLETE mode=image environment=%s tag=%s\n' "$SC_ENV" "$TARGET_TAG"
  exit 0
fi

step approval-record
verify_approval rollback "$EXTRA" "n/a" "n/a"
step_ok

step identity-check
verify_identity
step_ok

step state-fetch
consume_approval rollback
make_out_dir
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
CURRENT="$SC_OUT_DIR/$SC_ENV-state-before-$STAMP.json"
OLD="$SC_OUT_DIR/$SC_ENV-state-version-$STAMP.json"
tf init -input=false -no-color
(umask 077 && tf state pull > "$CURRENT")
aws s3api get-object --bucket "$STATE_BUCKET" --key "$STATE_KEY" --version-id "$STATE_VERSION" "$OLD" >/dev/null \
  || die "could not fetch version $STATE_VERSION of s3://$STATE_BUCKET/$STATE_KEY"
chmod 600 "$OLD"
printf 'STATE_BACKUP=%s\nSTATE_CANDIDATE=%s\n' "$CURRENT" "$OLD"
step_ok

step state-compare
lineage_of() { grep -o '"lineage": *"[^"]*"' "$1" | head -n 1 || true; }
serial_of() { grep -o '"serial": *[0-9]*' "$1" | head -n 1 | grep -o '[0-9]*$' || true; }
BACKEND_FILE="$SC_ENV_DIR/.terraform/terraform.tfstate"
[[ -f "$BACKEND_FILE" ]] && grep -q "\"bucket\": *\"$STATE_BUCKET\"" "$BACKEND_FILE" && grep -q "\"key\": *\"$STATE_KEY\"" "$BACKEND_FILE" \
  || die "the initialized backend is not s3://$STATE_BUCKET/$STATE_KEY: refusing to push state to another backend"
[[ -n "$(lineage_of "$CURRENT")" && "$(lineage_of "$CURRENT")" == "$(lineage_of "$OLD")" ]] || die "lineage differs or is missing: refusing to push another state's history"
CUR_SERIAL="$(serial_of "$CURRENT")"
OLD_SERIAL="$(serial_of "$OLD")"
[[ -n "$CUR_SERIAL" && -n "$OLD_SERIAL" ]] || die "could not read serials"
[[ "$OLD_SERIAL" -lt "$CUR_SERIAL" ]] || die "candidate serial $OLD_SERIAL is not older than current $CUR_SERIAL"
printf 'STATE_SERIALS current=%s candidate=%s\n' "$CUR_SERIAL" "$OLD_SERIAL"
step_ok

step state-push
tf state push -force "$OLD"
step_ok

step drift-plan
DRIFT_LOG="$SC_OUT_DIR/$SC_ENV-rollback-drift-$STAMP.plan.log"
DRIFT_PLAN="$SC_OUT_DIR/$SC_ENV-rollback-drift-$STAMP.tfplan"
tf_plan_to "$DRIFT_PLAN" "$DRIFT_LOG" "-var-file=$SC_TFVARS" "-var=image_tag=$(git_head)"
scan_for_secrets "$DRIFT_LOG"
plan_summary "$DRIFT_LOG"
note "nothing was applied. Real resources are unchanged. The restore point is $CURRENT (terraform state push -force it to undo this)."
step_ok

printf 'ROLLBACK_COMPLETE mode=state environment=%s version=%s\n' "$SC_ENV" "$STATE_VERSION"
