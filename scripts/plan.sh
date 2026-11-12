#!/usr/bin/env bash
# plan.sh: terraform fmt -check, init, validate and a saved plan, with the plan
# printed in full and teed to a file. Never applies. Credentials come from the
# caller's environment only. Reads no secret value.
#
# Usage:
#   bash scripts/plan.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 25 [--image-tag <40-hex git sha>] \
#     [--extra-var-file /abs/path.tfvars] [--out-dir /abs/dir]
#
# Output (in --out-dir): <env>-<sha12>.tfplan, .tfplan.meta, .plan.log
# The printed PLAN_DIGEST is what the human names in the approval record.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

IMAGE_TAG=""
EXTRA_VAR_FILES=""

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --image-tag) need_arg "$@"; IMAGE_TAG="$2"; shift 2 ;;
    --extra-var-file) need_arg "$@"; EXTRA_VAR_FILES="$EXTRA_VAR_FILES $2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

step validate-params
init_common no
[[ -z "$SC_APPROVAL_FILE" ]] || die "plan.sh takes no approval record: it produces the digest the record names"
require_cmd terraform aws git awk tee grep
HEAD_SHA="$(git_head)"
[[ -n "$IMAGE_TAG" ]] || IMAGE_TAG="$HEAD_SHA"
require_image_tag "$IMAGE_TAG"
for f in $EXTRA_VAR_FILES; do
  [[ "$f" == /* && -f "$f" ]] || die "--extra-var-file must be an existing absolute path: $f"
done
maybe_print_digest plan "image_tag=$IMAGE_TAG"
step_ok

# Dry run: print the target and the commands as text, run none of them. Nothing
# below this block is reached in a dry run.
if is_dry_run; then
  dry_banner plan "image_tag=$IMAGE_TAG"
  dry_plan_file="$SC_OUT_DIR/$SC_ENV-${HEAD_SHA:0:12}.tfplan"
  dry_plan_args="-var-file=$SC_TFVARS -var=image_tag=$IMAGE_TAG"
  for f in $EXTRA_VAR_FILES; do dry_plan_args="$dry_plan_args -var-file=$f"; done
  dry_step identity-check
  dry_would "aws sts get-caller-identity --query Account --output text"
  dry_step source-tree
  dry_would "git status --porcelain (sets DIRTY in the plan metadata)"
  dry_step terraform-init
  dry_would "terraform -chdir=$SC_ENV_DIR init -input=false -no-color"
  dry_step terraform-fmt-check
  dry_would "terraform fmt -check -recursive -no-color $SC_TF_ROOT"
  dry_step terraform-validate
  dry_would "terraform -chdir=$SC_ENV_DIR validate -no-color"
  dry_step terraform-plan
  dry_would "mkdir $SC_OUT_DIR (mode 700) and terraform -chdir=$SC_ENV_DIR plan -input=false -lock-timeout=60s -detailed-exitcode -no-color -out=$dry_plan_file $dry_plan_args"
  dry_step plan-scan
  dry_would "scan the plan log for key material"
  dry_step plan-summary
  dry_would "print plan counts, IAM changes, stateful changes; write $dry_plan_file.meta"
  dry_finish
fi

step identity-check
verify_identity
step_ok

step source-tree
if [[ -n "$(tree_status)" ]]; then
  DIRTY="yes"
  note "the tree is dirty: this plan is for review only and deploy.sh will refuse it"
else
  DIRTY="no"
fi
printf 'GIT_SHA=%s DIRTY=%s IMAGE_TAG=%s\n' "$HEAD_SHA" "$DIRTY" "$IMAGE_TAG"
step_ok

tf_prepare

step terraform-plan
make_out_dir
PLAN_FILE="$SC_OUT_DIR/$SC_ENV-${HEAD_SHA:0:12}.tfplan"
PLAN_LOG="$SC_OUT_DIR/$SC_ENV-${HEAD_SHA:0:12}.plan.log"
PLAN_ARGS="-var-file=$SC_TFVARS -var=image_tag=$IMAGE_TAG"
for f in $EXTRA_VAR_FILES; do PLAN_ARGS="$PLAN_ARGS -var-file=$f"; done
# PLAN_ARGS holds only paths and a SHA, never a secret, so word splitting is safe.
# shellcheck disable=SC2086
tf_plan_to "$PLAN_FILE" "$PLAN_LOG" $PLAN_ARGS
step_ok

step plan-scan
scan_for_secrets "$PLAN_LOG"
step_ok

step plan-summary
plan_summary "$PLAN_LOG"
PLAN_DIGEST="$(sha256_file "$PLAN_FILE")"
# Effective cpu_architecture: the variable default (X86_64), then each var file in
# the order given to terraform, the last assignment winning. deploy.sh derives the
# docker platform from this and re-checks it against the applied output.
CPU_ARCH="X86_64"
for f in "$SC_TFVARS" $EXTRA_VAR_FILES; do
  v="$(tfvar_value "$f" cpu_architecture)"
  if [[ -n "$v" ]]; then CPU_ARCH="$v"; fi
done
[[ "$CPU_ARCH" == "X86_64" || "$CPU_ARCH" == "ARM64" ]] || die "cpu_architecture must be X86_64 or ARM64, got: $CPU_ARCH"
write_meta "$PLAN_FILE.meta" \
  "META_VERSION=1" \
  "MODE=apply" \
  "ENVIRONMENT=$SC_ENV" \
  "ACCOUNT_ID=$SC_ACCOUNT" \
  "REGION=$SC_REGION" \
  "GIT_SHA=$HEAD_SHA" \
  "DIRTY=$DIRTY" \
  "IMAGE_TAG=$IMAGE_TAG" \
  "CPU_ARCHITECTURE=$CPU_ARCH" \
  "COST_CEILING_USD=$SC_COST" \
  "PLAN_EXIT=$SC_PLAN_EXIT" \
  "STATEFUL_CHANGE=$SC_STATEFUL_CHANGE" \
  "PLAN_DIGEST=$PLAN_DIGEST" \
  "CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'PLAN_FILE=%s\n' "$PLAN_FILE"
printf 'PLAN_LOG=%s\n' "$PLAN_LOG"
printf 'PLAN_META=%s\n' "$PLAN_FILE.meta"
printf 'PLAN_DIGEST=%s\n' "$PLAN_DIGEST"
printf 'PLAN_EXIT=%s (0 no changes, 2 changes)\n' "$SC_PLAN_EXIT"
note "nothing was applied. Cost bound $SC_COST USD is checked against COST_ESTIMATE.md by the human reviewer; the plan does not price itself."
step_ok
