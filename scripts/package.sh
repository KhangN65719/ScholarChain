#!/usr/bin/env bash
# package.sh: build the Hadoop, entity resolution and Spark job artifacts and the
# two service images locally, and record checksums and image tags derived from the
# Git SHA. No AWS call, no credential, no docker login, no docker push. The images
# are local only: deploy.sh builds and pushes its own from the two context
# directories, using the full Git SHA of a clean HEAD as the tag.
#
# Usage:
#   bash scripts/package.sh [--environment course] [--platform linux/amd64|linux/arm64]
#     [--extra-var-file /abs/experiment-size-s.tfvars] [--out-dir /abs/dir]
#     [--allow-dirty] [--skip-images] [--dry-run]
#
#   --platform        docker --platform for both images. Without it, the platform
#                     follows cpu_architecture the way plan.sh records it: the
#                     variable default X86_64, then <environment>.tfvars, then each
#                     --extra-var-file in order, the last assignment winning.
#                     X86_64 is linux/amd64 and ARM64 is linux/arm64.
#   --out-dir         absolute, outside the repository, and not yet existing (or
#                     empty). Default ${TMPDIR:-/tmp}/scholarchain-ops/package/<sha12>-<UTC>.
#                     Receives package-manifest.env and SHA256SUMS, written once.
#   --allow-dirty     build a dirty tree or a detached HEAD. The tag then ends in
#                     -dirty (or -detached for a clean detached HEAD). deploy.sh
#                     still refuses such a tree: it needs a clean tree, and its
#                     image tag must be the 40 character SHA of HEAD.
#   --skip-images     run only sbt packageJobs; build no image and stage no context.
#                     The manifest records IMAGES=skipped. deploy.sh needs the
#                     context, so a --skip-images run cannot be deployed from.
#
# What it runs: one sbt process, "sbt packageJobs dockerLocal" (the two aliases in
# build.sbt, in that order), then "docker build" twice. dockerLocal stages
# target/docker-local (the Dockerfile and lib/ with the services runtime classpath)
# and also builds scholarchain-local:phase-2 for the host architecture, a build this
# script does not use. That staging is the only way build.sbt provides the context,
# so Docker must be running for it even though the tag is not used.
# deploy.sh --services-context and --loader-context both take
# <repo>/target/docker-local: build.sbt stages one context and the Dockerfile is
# the same for both images. Whether the loader entry point is on the services
# runtime classpath is UNVERIFIED here (see scripts/README.md).
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

ENV_NAME="course"
PLATFORM_ARG=""
EXTRA_VAR_FILES=""
OUT_DIR_ARG=""
ALLOW_DIRTY=0
SKIP_IMAGES=0
MANIFEST_BODY=""
SUMS_BODY=""

usage() {
  print_header
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) need_arg "$@"; ENV_NAME="$2"; shift 2 ;;
    --platform) need_arg "$@"; PLATFORM_ARG="$2"; shift 2 ;;
    --extra-var-file) need_arg "$@"; EXTRA_VAR_FILES="$EXTRA_VAR_FILES $2"; shift 2 ;;
    --out-dir) need_arg "$@"; OUT_DIR_ARG="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --skip-images) SKIP_IMAGES=1; shift ;;
    --dry-run) SC_DRY_RUN=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

add_kv() {
  MANIFEST_BODY="${MANIFEST_BODY}$1=$2"$'\n'
}

# clean TEXT: tool output made safe for a KEY=VALUE line.
clean() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._:/+-' '_'
}

# add_sums DIR: one sha256sum-compatible line per file, paths relative to the
# repository root, so "cd <repo> && shasum -a 256 -c SHA256SUMS" verifies them.
add_sums() {
  local d="$1" f
  while IFS= read -r f; do
    SUMS_BODY="${SUMS_BODY}$(sha256_file "$f")  ${f#"$SC_REPO_ROOT"/}"$'\n'
  done < <(find "$d" -type f -print | LC_ALL=C sort)
}

step validate-params
require_cmd git awk
if ! is_dry_run; then
  require_cmd java sbt
  if [[ "$SKIP_IMAGES" -eq 0 ]]; then
    require_cmd docker
  fi
fi
[[ "$ENV_NAME" =~ ^[a-z][a-z0-9-]{1,19}$ ]] || die "environment name is not valid: $ENV_NAME"
ENV_DIR="$SC_TF_ROOT/environments/$ENV_NAME"
[[ -d "$ENV_DIR" ]] || die "no Terraform environment directory for: $ENV_NAME"
TFVARS="$ENV_DIR/$ENV_NAME.tfvars"
[[ -f "$TFVARS" ]] || die "missing variable file: $TFVARS"
for f in $EXTRA_VAR_FILES; do
  [[ "$f" == /* && -f "$f" ]] || die "--extra-var-file must be an existing absolute path: $f"
done
[[ -f "$SC_REPO_ROOT/Dockerfile" ]] || die "Dockerfile not found at the repository root"
if [[ -n "$PLATFORM_ARG" ]]; then
  case "$PLATFORM_ARG" in
    linux/amd64) CPU_ARCH="X86_64"; PLATFORM="linux/amd64" ;;
    linux/arm64) CPU_ARCH="ARM64"; PLATFORM="linux/arm64" ;;
    *) die "--platform must be linux/amd64 or linux/arm64, got: $PLATFORM_ARG" ;;
  esac
  PLATFORM_SOURCE="--platform argument"
else
  CPU_ARCH="X86_64"
  for f in "$TFVARS" $EXTRA_VAR_FILES; do
    v="$(tfvar_value "$f" cpu_architecture)"
    if [[ -n "$v" ]]; then CPU_ARCH="$v"; fi
  done
  case "$CPU_ARCH" in
    X86_64) PLATFORM="linux/amd64" ;;
    ARM64) PLATFORM="linux/arm64" ;;
    *) die "cpu_architecture must be X86_64 or ARM64, got: $CPU_ARCH" ;;
  esac
  PLATFORM_SOURCE="cpu_architecture in the variable files"
fi
HEAD_SHA="$(git_head)"
require_image_tag "$HEAD_SHA"
STAMP="$(utc_stamp)"
if [[ -n "$OUT_DIR_ARG" ]]; then
  OUT_DIR="$OUT_DIR_ARG"
else
  OUT_DIR="${TMPDIR:-/tmp}"
  OUT_DIR="${OUT_DIR%/}/scholarchain-ops/package/${HEAD_SHA:0:12}-$STAMP"
fi
require_external_dir "$OUT_DIR" "--out-dir"
if [[ -e "$OUT_DIR" ]]; then
  [[ -d "$OUT_DIR" && -z "$(find "$OUT_DIR" -mindepth 1 | head -n 1)" ]] || die "--out-dir already exists and is not empty: $OUT_DIR (package records are written once)"
fi
step_ok

step source-tree
TREE_BEFORE="$(tree_status)"
DIRTY="no"
DETACHED="no"
if [[ -n "$TREE_BEFORE" ]]; then DIRTY="yes"; fi
if ! git -C "$SC_REPO_ROOT" symbolic-ref -q HEAD >/dev/null; then DETACHED="yes"; fi
TAG_SUFFIX=""
if [[ "$DETACHED" == "yes" ]]; then TAG_SUFFIX="-detached"; fi
if [[ "$DIRTY" == "yes" ]]; then TAG_SUFFIX="-dirty"; fi
IMAGE_TAG="${HEAD_SHA}${TAG_SUFFIX}"
if [[ "$DIRTY" == "yes" || "$DETACHED" == "yes" ]]; then
  if [[ "$ALLOW_DIRTY" -eq 0 ]]; then
    printf 'EVIDENCE: refusing: dirty=%s detached_head=%s. Commit or stash and check out a branch, or pass --allow-dirty for a build tagged %s. git status --porcelain:\n%s\n' \
      "$DIRTY" "$DETACHED" "$IMAGE_TAG" "$TREE_BEFORE" >&2
    exit 1
  fi
  note "building with --allow-dirty: dirty=$DIRTY detached_head=$DETACHED, so the tag is $IMAGE_TAG and deploy.sh will refuse this tree"
fi
printf 'SOURCE git_sha=%s dirty=%s detached_head=%s image_tag=%s\n' "$HEAD_SHA" "$DIRTY" "$DETACHED" "$IMAGE_TAG"
printf 'PLATFORM=%s (from %s) CPU_ARCHITECTURE=%s\n' "$PLATFORM" "$PLATFORM_SOURCE" "$CPU_ARCH"
step_ok

CONTEXT_DIR="$SC_REPO_ROOT/target/docker-local"
JOB_MODULES="hadoop entity-resolution spark"

if is_dry_run; then
  printf 'OUT_DIR=%s\n' "$OUT_DIR"
  dry_step tool-versions
  dry_would "read sbt.version from project/build.properties; java -version; docker version (skipped with --skip-images)"
  dry_step sbt-package
  if [[ "$SKIP_IMAGES" -eq 1 ]]; then
    dry_would "sbt packageJobs (one process, 3000 s limit)"
  else
    dry_would "sbt packageJobs dockerLocal (one process, 3000 s limit; stages $CONTEXT_DIR and builds scholarchain-local:phase-2)"
  fi
  dry_step artifacts
  dry_would "check modules/{hadoop,entity-resolution,spark}/target/job (one main jar and lib/), digest each, sha256 every file; check $CONTEXT_DIR/{Dockerfile,lib}"
  dry_step images
  if [[ "$SKIP_IMAGES" -eq 1 ]]; then
    dry_would "skipped: --skip-images (the manifest would record IMAGES=skipped)"
  else
    dry_would "docker build --platform $PLATFORM -f Dockerfile -t scholarchain-services:$IMAGE_TAG $CONTEXT_DIR; the same for scholarchain-loader:$IMAGE_TAG (no push, no login)"
  fi
  dry_step record
  dry_would "write package-manifest.env and SHA256SUMS to $OUT_DIR (created once, mode 700; refuses an existing non-empty directory)"
  dry_finish
fi

step tool-versions
SBT_VERSION="unknown"
if [[ -f "$SC_REPO_ROOT/project/build.properties" ]]; then
  SBT_VERSION="$(awk -F= '/^sbt\.version[[:space:]]*=/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "$SC_REPO_ROOT/project/build.properties")"
fi
JAVA_VERSION="$(java -version 2>&1)" || die "java -version failed"
JAVA_VERSION="${JAVA_VERSION%%$'\n'*}"
DOCKER_VERSION="skipped"
if [[ "$SKIP_IMAGES" -eq 0 ]]; then
  DOCKER_VERSION="$(docker version --format '{{.Client.Version}}')" || die "docker version failed"
  docker version --format '{{.Server.Version}}' >/dev/null || die "the Docker engine is not reachable: start it, or pass --skip-images"
fi
printf 'TOOLS sbt=%s java=%s docker=%s\n' "$SBT_VERSION" "$(clean "$JAVA_VERSION")" "$DOCKER_VERSION"
step_ok

START_UTC="$(utc_now)"

step sbt-package
if [[ "$SKIP_IMAGES" -eq 1 ]]; then
  SBT_ARGS_TEXT="packageJobs"
  sbt_rc=0
  sbt_run 3000 packageJobs || sbt_rc=$?
else
  SBT_ARGS_TEXT="packageJobs dockerLocal"
  sbt_rc=0
  sbt_run 3000 packageJobs dockerLocal || sbt_rc=$?
fi
[[ "$sbt_rc" -ne 124 ]] || die "sbt $SBT_ARGS_TEXT exceeded its 3000 second limit and was stopped"
[[ "$sbt_rc" -eq 0 ]] || die "sbt $SBT_ARGS_TEXT failed with exit $sbt_rc"
[[ "$(git_head)" == "$HEAD_SHA" ]] || die "HEAD moved during the build"
[[ "$(tree_status)" == "$TREE_BEFORE" ]] || die "the build changed the working tree status: refusing to record a package from a tree that moved"
step_ok

step artifacts
for m in $JOB_MODULES; do
  jd="$SC_REPO_ROOT/modules/$m/target/job"
  [[ -d "$jd/lib" ]] || die "sbt packageJobs did not stage $jd/lib"
  jar_count=0
  main_jar=""
  for j in "$jd"/*.jar; do
    [[ -f "$j" ]] || continue
    jar_count=$((jar_count + 1))
    main_jar="$j"
  done
  [[ "$jar_count" -eq 1 ]] || die "expected exactly one main jar in $jd, found $jar_count"
  lib_count=0
  for j in "$jd"/lib/*.jar; do
    [[ -f "$j" ]] || continue
    lib_count=$((lib_count + 1))
  done
  [[ "$lib_count" -gt 0 ]] || die "no runtime jars in $jd/lib"
  key="$(printf '%s' "$m" | tr 'a-z-' 'A-Z_')"
  add_kv "JOB_${key}_DIR" "${jd#"$SC_REPO_ROOT"/}"
  add_kv "JOB_${key}_MAIN_JAR" "${main_jar#"$SC_REPO_ROOT"/}"
  add_kv "JOB_${key}_MAIN_JAR_SHA256" "$(sha256_file "$main_jar")"
  add_kv "JOB_${key}_LIB_COUNT" "$lib_count"
  add_kv "JOB_${key}_DIR_DIGEST" "$(dir_digest "$jd")"
  add_sums "$jd"
  printf 'ARTIFACT module=%s main_jar=%s lib_jars=%s\n' "$m" "${main_jar##*/}" "$lib_count"
done
if [[ "$SKIP_IMAGES" -eq 0 ]]; then
  [[ -f "$CONTEXT_DIR/Dockerfile" && -d "$CONTEXT_DIR/lib" ]] || die "sbt dockerLocal did not stage $CONTEXT_DIR/Dockerfile and lib/"
  ctx_jars=0
  for j in "$CONTEXT_DIR"/lib/*.jar; do
    [[ -f "$j" ]] || continue
    ctx_jars=$((ctx_jars + 1))
  done
  [[ "$ctx_jars" -gt 0 ]] || die "no runtime jars in $CONTEXT_DIR/lib"
  CONTEXT_DIGEST="$(dir_digest "$CONTEXT_DIR")"
  add_kv "SERVICES_CONTEXT" "$CONTEXT_DIR"
  add_kv "LOADER_CONTEXT" "$CONTEXT_DIR"
  add_kv "CONTEXT_DIGEST" "$CONTEXT_DIGEST"
  add_kv "CONTEXT_LIB_COUNT" "$ctx_jars"
  add_sums "$CONTEXT_DIR"
  printf 'CONTEXT dir=%s lib_jars=%s digest=%s\n' "$CONTEXT_DIR" "$ctx_jars" "$CONTEXT_DIGEST"
fi
step_ok

step images
if [[ "$SKIP_IMAGES" -eq 1 ]]; then
  step_skip "--skip-images: no image was built and no deploy context was staged"
  add_kv "IMAGES" "skipped"
else
  for role in services loader; do
    ref="scholarchain-${role}:${IMAGE_TAG}"
    docker build --platform "$PLATFORM" \
      --label "org.opencontainers.image.revision=$HEAD_SHA" \
      --label "scholarchain.dirty=$DIRTY" \
      -f "$SC_REPO_ROOT/Dockerfile" -t "$ref" "$CONTEXT_DIR"
    image_id="$(docker image inspect --format '{{.Id}}' "$ref")" || die "cannot read the image id of $ref"
    ROLE_KEY="$(printf '%s' "$role" | tr '[:lower:]' '[:upper:]')"
    add_kv "${ROLE_KEY}_IMAGE_REF" "$ref"
    add_kv "${ROLE_KEY}_IMAGE_ID" "$image_id"
    printf 'IMAGE_BUILT ref=%s id=%s platform=%s (local only, not pushed)\n' "$ref" "$image_id" "$PLATFORM"
  done
  add_kv "IMAGES" "built"
  step_ok
fi

step record
END_UTC="$(utc_now)"
[[ "$(git_head)" == "$HEAD_SHA" ]] || die "HEAD moved during the build"
(umask 077 && mkdir -p "$(dirname "$OUT_DIR")")
if [[ ! -d "$OUT_DIR" ]]; then
  (umask 077 && mkdir "$OUT_DIR") || die "cannot create --out-dir: $OUT_DIR"
fi
[[ -z "$(find "$OUT_DIR" -mindepth 1 | head -n 1)" ]] || die "--out-dir is no longer empty (it changed during the build): $OUT_DIR"
HEADER_BODY="MANIFEST_VERSION=1"$'\n'
HEADER_BODY="${HEADER_BODY}GIT_SHA=${HEAD_SHA}"$'\n'
HEADER_BODY="${HEADER_BODY}DIRTY=${DIRTY}"$'\n'
HEADER_BODY="${HEADER_BODY}DETACHED_HEAD=${DETACHED}"$'\n'
HEADER_BODY="${HEADER_BODY}IMAGE_TAG=${IMAGE_TAG}"$'\n'
HEADER_BODY="${HEADER_BODY}ENVIRONMENT=${ENV_NAME}"$'\n'
HEADER_BODY="${HEADER_BODY}CPU_ARCHITECTURE=${CPU_ARCH}"$'\n'
HEADER_BODY="${HEADER_BODY}PLATFORM=${PLATFORM}"$'\n'
HEADER_BODY="${HEADER_BODY}SBT_VERSION=${SBT_VERSION}"$'\n'
HEADER_BODY="${HEADER_BODY}JAVA_VERSION=$(clean "$JAVA_VERSION")"$'\n'
HEADER_BODY="${HEADER_BODY}DOCKER_VERSION=${DOCKER_VERSION}"$'\n'
HEADER_BODY="${HEADER_BODY}SBT_COMMAND=sbt_${SBT_ARGS_TEXT// /_}"$'\n'
HEADER_BODY="${HEADER_BODY}START_UTC=${START_UTC}"$'\n'
HEADER_BODY="${HEADER_BODY}END_UTC=${END_UTC}"$'\n'
(set -C; printf '%s%s' "$HEADER_BODY" "$MANIFEST_BODY" > "$OUT_DIR/package-manifest.env")
(set -C; printf '%s' "$SUMS_BODY" > "$OUT_DIR/SHA256SUMS")
chmod 400 "$OUT_DIR/package-manifest.env" "$OUT_DIR/SHA256SUMS"
printf 'PACKAGE_MANIFEST=%s\n' "$OUT_DIR/package-manifest.env"
printf 'PACKAGE_SHA256SUMS=%s\n' "$OUT_DIR/SHA256SUMS"
printf 'PACKAGE_COMPLETE git_sha=%s image_tag=%s platform=%s\n' "$HEAD_SHA" "$IMAGE_TAG" "$PLATFORM"
if [[ "$SKIP_IMAGES" -eq 0 && "$DIRTY" == "no" && "$DETACHED" == "no" ]]; then
  note "deploy.sh takes --services-context $CONTEXT_DIR --loader-context $CONTEXT_DIR; it builds and pushes its own images from them"
fi
step_ok
