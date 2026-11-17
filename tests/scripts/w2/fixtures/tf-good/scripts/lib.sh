#!/usr/bin/env bash
# Test fixture for tests/scripts/w2/tfcheck.py (check verify-revision). A verbatim copy of verify_revision from
# scripts/lib.sh: every ACTIVE task definition family with the environment prefix, the load-client family
# included, must use only :TAG images, and a mismatch fails the deploy. Never run.

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
