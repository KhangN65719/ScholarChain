#!/usr/bin/env bash
# deploy.sh: apply an approved saved plan, build and push images tagged by the
# Git SHA, then verify the deployed revision. A human-controlled execution path
# runs this; agent shell calls to it stay blocked until a launcher is ratified.
#
# Usage:
#   bash scripts/deploy.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 25 --approval-record /abs/approval.env \
#     --plan-file /abs/course-<sha12>.tfplan \
#     --services-context /abs/dir-with-lib --loader-context /abs/dir-with-lib
#
# Refuses: a dirty tree, a plan not made from HEAD, a plan whose digest is not
# the one in the approval record, an expired or mismatching record, another
# account or region, and a plan that destroys or replaces stateful resources
# unless the record sets STATEFUL_CHANGE_ACKNOWLEDGED=yes. The digested arguments
# hold the image tag, the docker platform (from the plan's cpu_architecture) and a
# content digest of each build context, so the approval names the image bytes. An
# existing immutable tag is reused, not rebuilt: in that case the contexts are
# still digested but nothing is built from them.
# Principals: terraform apply needs the human's broad credentials, not the
# deployer role (see infra/terraform/README.md, "Who runs what").
# Never passes -auto-approve and never applies anything but the saved plan.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

PLAN_FILE=""
SERVICES_CONTEXT=""
LOADER_CONTEXT=""

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --plan-file) need_arg "$@"; PLAN_FILE="$2"; shift 2 ;;
    --services-context) need_arg "$@"; SERVICES_CONTEXT="$2"; shift 2 ;;
    --loader-context) need_arg "$@"; LOADER_CONTEXT="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

# DRY_PLAN and DRY_CTX are "yes" in every real run. A dry run may lack the saved
# plan file (it needs terraform) or the two build contexts (sbt output): the
# arguments are still required, and the checks that need the files are skipped
# and said so.
DRY_PLAN="yes"
DRY_CTX="yes"

step validate-params
init_common yes
require_cmd terraform aws git docker awk tee grep
[[ -n "$PLAN_FILE" ]] || die "--plan-file is required"
if is_dry_run; then
  [[ -n "$SERVICES_CONTEXT" && -n "$LOADER_CONTEXT" ]] || die "--services-context and --loader-context are required (a dry run needs the arguments, not the directories)"
  [[ "$PLAN_FILE" == /* ]] || die "--plan-file must be an absolute path"
  [[ -f "$PLAN_FILE" && -f "$PLAN_FILE.meta" ]] || DRY_PLAN="no"
  [[ -d "$SERVICES_CONTEXT/lib" && -d "$LOADER_CONTEXT/lib" ]] || DRY_CTX="no"
else
  [[ -n "$SERVICES_CONTEXT" && -d "$SERVICES_CONTEXT/lib" ]] || die "--services-context must be a directory holding lib/ (sbt packaging output)"
  [[ -n "$LOADER_CONTEXT" && -d "$LOADER_CONTEXT/lib" ]] || die "--loader-context must be a directory holding lib/ (sbt packaging output)"
fi
[[ -f "$SC_REPO_ROOT/Dockerfile" ]] || die "Dockerfile not found at the repository root"
HEAD_SHA="$(git_head)"
IMAGE_TAG="$HEAD_SHA"
require_image_tag "$IMAGE_TAG"
# The image platform follows the cpu_architecture the plan was made with. plan.sh
# records it in the plan metadata; it is re-checked against the applied output
# before any image is built.
CPU_ARCH=""
PLATFORM=""
if [[ "$DRY_PLAN" == "yes" ]]; then
  [[ "$PLAN_FILE" == /* && -f "$PLAN_FILE.meta" ]] || die "plan metadata not found next to the plan file: $PLAN_FILE.meta (plan.sh writes it)"
  CPU_ARCH="$(meta_get "$PLAN_FILE.meta" CPU_ARCHITECTURE)"
  case "$CPU_ARCH" in
    X86_64) PLATFORM="linux/amd64" ;;
    ARM64) PLATFORM="linux/arm64" ;;
    *) die "plan metadata has no valid CPU_ARCHITECTURE (got '${CPU_ARCH}'); remake the plan with the current plan.sh" ;;
  esac
fi
# The images are built from the two context directories, which are not in Git
# (sbt packaging output). Their content digests go into the digested arguments, so
# an approval names the exact bytes that become the image.
SERVICES_CONTEXT_DIGEST=""
LOADER_CONTEXT_DIGEST=""
if [[ "$DRY_PLAN" == "yes" && "$DRY_CTX" == "yes" ]]; then
  SERVICES_CONTEXT_DIGEST="$(dir_digest "$SERVICES_CONTEXT")"
  LOADER_CONTEXT_DIGEST="$(dir_digest "$LOADER_CONTEXT")"
  EXTRA="image_tag=$IMAGE_TAG;platform=$PLATFORM;services_context=$SERVICES_CONTEXT_DIGEST;loader_context=$LOADER_CONTEXT_DIGEST"
else
  EXTRA="@skip:the plan file or a build context directory is missing in this dry run, so platform and context digests are unknown"
fi
maybe_print_digest deploy "$EXTRA"
step_ok

if is_dry_run; then
  dry_banner deploy "$EXTRA"
fi

step clean-tree
require_clean_tree
printf 'CLEAN_TREE git_sha=%s\n' "$HEAD_SHA"
step_ok

if [[ "$DRY_PLAN" == "yes" ]]; then
  step plan-binding
  check_plan_binding "$PLAN_FILE" apply yes
  [[ "$(meta_get "$SC_PLAN_META" IMAGE_TAG)" == "$IMAGE_TAG" ]] || die "plan image tag differs from HEAD; remake the plan with plan.sh"
  step_ok
else
  dry_step plan-binding
  dry_skipped plan-binding "no plan file and metadata at --plan-file in this dry run"
  SC_PLAN_DIGEST="@skip:no plan file in this dry run"
  SC_PLAN_META=""
fi

step approval-record
REQUIRE_ACK="no"
if [[ -n "$SC_PLAN_META" && "$(meta_get "$SC_PLAN_META" STATEFUL_CHANGE)" == "yes" ]]; then
  REQUIRE_ACK="yes"
fi
verify_approval deploy "$EXTRA" "$SC_PLAN_DIGEST" "$HEAD_SHA" "$REQUIRE_ACK"
if [[ -n "$SC_PLAN_META" ]]; then
  if [[ "$REQUIRE_ACK" == "yes" ]]; then
    note "stateful change acknowledged in the approval record"
  fi
else
  dry_skipped stateful-change-acknowledgement "the plan metadata that says whether the plan changes stateful resources is not available; the record says STATEFUL_CHANGE_ACKNOWLEDGED=$(approval_value STATEFUL_CHANGE_ACKNOWLEDGED)"
fi
step_ok

if is_dry_run; then
  dry_approval_unconsumed
  dry_step identity-check
  dry_would "aws sts get-caller-identity (skipped)"
  dry_step terraform-init
  dry_would "terraform -chdir=$SC_ENV_DIR init -input=false -no-color"
  dry_step terraform-apply
  dry_would "re-check clean tree, HEAD and both context digests; consume the approval record; terraform apply -input=false -lock-timeout=60s of the saved plan $PLAN_FILE (never -auto-approve); compare output account_id and cpu_architecture"
  dry_step ecr-login
  dry_would "aws ecr get-login-password | docker login $SC_ACCOUNT.dkr.ecr.$SC_REGION.amazonaws.com"
  dry_step image-services
  dry_would "reuse the immutable tag $IMAGE_TAG if present in ${SC_NAME_PREFIX}-services, else docker build and push (platform ${PLATFORM:-unknown in this dry run})"
  dry_step image-loader
  dry_would "reuse the immutable tag $IMAGE_TAG if present in ${SC_NAME_PREFIX}-loader, else docker build and push"
  dry_step verify-revision
  dry_would "read-only ECR and ECS describes: every task definition and service must use :$IMAGE_TAG"
  dry_step 'done'
  dry_finish
fi

step identity-check
verify_identity
step_ok

step terraform-init
tf init -input=false -no-color
step_ok

step terraform-apply
require_clean_tree
[[ "$(git_head)" == "$HEAD_SHA" ]] || die "HEAD moved during the run"
[[ "$(dir_digest "$SERVICES_CONTEXT")" == "$SERVICES_CONTEXT_DIGEST" ]] || die "the services context changed during the run"
[[ "$(dir_digest "$LOADER_CONTEXT")" == "$LOADER_CONTEXT_DIGEST" ]] || die "the loader context changed during the run"
consume_approval deploy
make_out_dir
APPLY_LOG="$SC_OUT_DIR/$SC_ENV-$(date -u +%Y%m%dT%H%M%SZ).apply.log"
tf_apply_saved "$PLAN_FILE" "$APPLY_LOG"
[[ "$(tf output -raw account_id)" == "$SC_ACCOUNT" ]] || die "applied account differs from the approved account"
printf 'APPLY_LOG=%s\n' "$APPLY_LOG"
[[ "$(tf output -raw cpu_architecture)" == "$CPU_ARCH" ]] || die "applied cpu_architecture differs from the plan metadata ($CPU_ARCH): no image was built"
step_ok

# The ECR repositories exist only after the first apply. Task definitions point at
# the SHA tag. Services start at desired count 0 only under course.tfvars and the
# tier S overlay; the tier M and L overlays set 1 or 2, so on a first apply those
# tasks try to pull a tag that does not exist until the push below finishes. How
# long ECS retries a failed pull is UNVERIFIED here.
REGISTRY="${SC_ACCOUNT}.dkr.ecr.${SC_REGION}.amazonaws.com"

step ecr-login
aws ecr get-login-password --region "$SC_REGION" | docker login --username AWS --password-stdin "$REGISTRY"
step_ok

push_image() {
  local role="$1" context="$2" expected_digest="$3" repo existing
  repo="${SC_NAME_PREFIX}-${role}"
  if existing="$(aws ecr describe-images --repository-name "$repo" --image-ids "imageTag=$IMAGE_TAG" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null)"; then
    printf 'IMAGE_EXISTS repository=%s tag=%s digest=%s (immutable tag, not rebuilt)\n' "$repo" "$IMAGE_TAG" "$existing"
    return 0
  fi
  [[ "$(dir_digest "$context")" == "$expected_digest" ]] || die "the $role build context changed after it was digested; no image was built"
  docker build --platform "$PLATFORM" \
    --label "org.opencontainers.image.revision=$IMAGE_TAG" \
    -f "$SC_REPO_ROOT/Dockerfile" -t "$REGISTRY/$repo:$IMAGE_TAG" "$context"
  docker push "$REGISTRY/$repo:$IMAGE_TAG"
  printf 'IMAGE_PUSHED repository=%s tag=%s\n' "$repo" "$IMAGE_TAG"
}

step image-services
push_image services "$SERVICES_CONTEXT" "$SERVICES_CONTEXT_DIGEST"
step_ok

step image-loader
push_image loader "$LOADER_CONTEXT" "$LOADER_CONTEXT_DIGEST"
step_ok

step verify-revision
verify_revision "$IMAGE_TAG"
step_ok

step 'done'
printf 'DEPLOY_COMPLETE environment=%s account=%s region=%s git_sha=%s plan_digest=%s\n' \
  "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION" "$IMAGE_TAG" "$SC_PLAN_DIGEST"
note "services run at desired count 0 only under course.tfvars and the tier S overlay. Idle-cost items bill until the session-down flow or cleanup.sh runs; see scripts/README.md."
step_ok
