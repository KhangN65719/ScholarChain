#!/usr/bin/env bash
# smoke-test.sh: checks of a deployed environment. Health and readiness of both
# services, one bounded query, an optional Bedrock access check, and an optional
# deployed-revision check. It creates no infrastructure and changes none, but it
# is not free of side effects: the query may write a query-session row and an
# evidence-bundle row to DynamoDB (the runtime role has PutItem on both, an
# unconfirmed assumption), the query may call Bedrock when Bedrock is on, and
# --bedrock on makes two billed calls. It reads one Secrets Manager value.
#
# NETWORK REQUIREMENT (unresolved, a Phase 14 decision). The services have no
# ingress, and today there is no path to them: no instance or task is a member of
# the client security group, ECS execute-command is off (enable_execute_command is
# false and there is no ssm or ssmmessages endpoint), and Cloud Map is off by
# default, so no *.internal name resolves. The service URLs are the private IPs of
# the running tasks. Run this from a host inside the VPC that is in the client
# security group, or over a tunnel the human opens. The script opens neither. See
# infra/terraform/README.md, "Smoke-test network path", for the options.
#
# --out-dir guard: refused when its physical path (symlinks resolved, doubled
# slashes squeezed, letter case ignored) is inside the repository. It does not stop
# a symlink swapped after the check, a hard link or a bind mount. A relative
# --out-dir and . or .. segments are refused. See the lib.sh header.
#
# Usage:
#   bash scripts/smoke-test.sh --environment course --account-id 123456789012 \
#     --cost-ceiling-usd 25 --approval-record /abs/approval.env \
#     --fast-url http://10.20.0.10:8080 \
#     --slow-url http://10.20.0.11:8081 \
#     --query-secret-arn arn:aws:secretsmanager:us-east-1:123456789012:secret:... \
#     --bedrock off [--expected-image-tag <40-hex git sha>] [--question "text"]
#
#   --bedrock on|off  off skips the Bedrock checks. on makes two single-request
#                     calls (Titan embedding of "ping", Haiku with max 5 tokens),
#                     the only non-free calls here, a fraction of a cent.
#   --query-secret-arn  the Secrets Manager ARN of the query bearer token. The
#                     value is read into a private temp file, passed to curl by
#                     file, and never printed or placed in an argument list.
#   --titan-model-id, --haiku-profile-id, --vector-dimension  Bedrock overrides,
#                     defaults match infra/terraform (UNVERIFIED until Phase 14).
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

FAST_URL=""
SLOW_URL=""
SECRET_ARN=""
BEDROCK=""
EXPECTED_TAG=""
QUESTION="Which authors collaborated most between 2015 and 2020?"
TITAN_MODEL="amazon.titan-embed-text-v2:0"
HAIKU_PROFILE="us.anthropic.claude-haiku-4-5-20251001-v1:0"
VECTOR_DIM="256"

usage() {
  print_header
  common_usage
}

while [[ $# -gt 0 ]]; do
  if parse_common_flag "$@"; then shift "$SC_SHIFT"; continue; fi
  case "$1" in
    --fast-url) need_arg "$@"; FAST_URL="$2"; shift 2 ;;
    --slow-url) need_arg "$@"; SLOW_URL="$2"; shift 2 ;;
    --query-secret-arn) need_arg "$@"; SECRET_ARN="$2"; shift 2 ;;
    --bedrock) need_arg "$@"; BEDROCK="$2"; shift 2 ;;
    --expected-image-tag) need_arg "$@"; EXPECTED_TAG="$2"; shift 2 ;;
    --question) need_arg "$@"; QUESTION="$2"; shift 2 ;;
    --titan-model-id) need_arg "$@"; TITAN_MODEL="$2"; shift 2 ;;
    --haiku-profile-id) need_arg "$@"; HAIKU_PROFILE="$2"; shift 2 ;;
    --vector-dimension) need_arg "$@"; VECTOR_DIM="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

step validate-params
init_common yes
require_cmd aws curl awk grep
# The bearer token header file is written under --out-dir for a moment. Never
# under the repository, where it could be committed or indexed.
case "$SC_OUT_DIR/" in
  */../* | */./*) die "--out-dir must not contain . or .. segments" ;;
esac
# Compare physical, case-folded paths: a symlink into the repository, a doubled
# slash, or a different letter case on macOS must not get past a prefix match.
# This is a check at one moment (see the lib.sh header for what it does not stop).
if path_within "$SC_OUT_DIR" "$SC_REPO_ROOT"; then
  die "--out-dir must not be inside the repository ($SC_REPO_ROOT), by physical path and ignoring letter case: the token header file would be written there"
fi
[[ -n "$(physical_path "$SC_OUT_DIR")" ]] || die "--out-dir cannot be resolved to a physical path: $SC_OUT_DIR"
URL_RE='^https?://[A-Za-z0-9.-]+:[0-9]{2,5}$'
[[ "$FAST_URL" =~ $URL_RE ]] || die "--fast-url must look like http://host:port with no path or credentials"
[[ "$SLOW_URL" =~ $URL_RE ]] || die "--slow-url must look like http://host:port with no path or credentials"
[[ "$SECRET_ARN" =~ ^arn:aws:secretsmanager:${SC_REGION}:${SC_ACCOUNT}:secret:[A-Za-z0-9/_+=.@-]+$ ]] || die "--query-secret-arn must be a Secrets Manager ARN in account $SC_ACCOUNT, $SC_REGION"
[[ "$BEDROCK" == "on" || "$BEDROCK" == "off" ]] || die "--bedrock must be on or off"
[[ "$VECTOR_DIM" =~ ^(256|384|512|1024)$ ]] || die "--vector-dimension must be 256, 384, 512 or 1024"
[[ ${#QUESTION} -le 200 ]] || die "the smoke question is limited to 200 characters"
if [[ -n "$EXPECTED_TAG" ]]; then require_image_tag "$EXPECTED_TAG"; fi
QUESTION_DIGEST="$(printf '%s' "$QUESTION" | sha256_stdin)"
EXTRA="fast=$FAST_URL;slow=$SLOW_URL;secret=$SECRET_ARN;bedrock=$BEDROCK;tag=${EXPECTED_TAG:-none};question=$QUESTION_DIGEST;dim=$VECTOR_DIM;titan=$TITAN_MODEL;haiku=$HAIKU_PROFILE"
maybe_print_digest smoke-test "$EXTRA"
step_ok

step approval-record
verify_approval smoke-test "$EXTRA" "n/a" "n/a"
step_ok

step identity-check
verify_identity
step_ok

http_code() {
  curl -sS --max-time 10 --max-filesize 1048576 -o /dev/null -w '%{http_code}' "$1" || true
}

expect_200() {
  local label="$1" url="$2" code
  code="$(http_code "$url")"
  printf 'CHECK %s url=%s http=%s\n' "$label" "$url" "$code"
  [[ "$code" == "200" ]] || die "$label expected HTTP 200, got $code"
}

step health
consume_approval smoke-test
expect_200 fast-healthz "$FAST_URL/healthz"
expect_200 slow-healthz "$SLOW_URL/healthz"
step_ok

step readiness
expect_200 fast-readyz "$FAST_URL/readyz"
expect_200 slow-readyz "$SLOW_URL/readyz"
step_ok

step bounded-query
make_out_dir
HDR="$SC_OUT_DIR/.smoke-auth.$$"
BODY_OUT="$SC_OUT_DIR/.smoke-body.$$"
register_cleanup "$HDR"
register_cleanup "$BODY_OUT"
(umask 077 && : > "$HDR")
TOKEN="$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --query SecretString --output text)" \
  || die "could not read the query token secret (value is never printed)"
[[ -n "$TOKEN" && "$TOKEN" != "None" ]] || die "the query token secret is empty"
printf 'Authorization: Bearer %s\n' "$TOKEN" > "$HDR"
unset TOKEN
# JSON-escape the question: backslash and double quote only; control characters are refused.
if printf '%s' "$QUESTION" | grep -q '[[:cntrl:]]'; then die "the question holds control characters"; fi
ESCAPED="$(printf '%s' "$QUESTION" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
CODE="$(curl -sS --max-time 60 --max-filesize 1048576 -o "$BODY_OUT" -w '%{http_code}' \
  -X POST -H "@$HDR" -H 'Content-Type: application/json' \
  --data "{\"question\":\"$ESCAPED\",\"mode\":\"sync\"}" "$SLOW_URL/v1/query")" || CODE="curl-failed"
printf 'CHECK bounded-query url=%s/v1/query http=%s bytes=%s\n' "$SLOW_URL" "$CODE" "$(wc -c < "$BODY_OUT" | tr -d ' ')"
printf 'RESPONSE_HEAD %s\n' "$(head -c 300 "$BODY_OUT" | tr '\n' ' ')"
[[ "$CODE" == "200" ]] || die "bounded query expected HTTP 200, got $CODE"
step_ok

step bedrock-access
if [[ "$BEDROCK" == "off" ]]; then
  step_skip "bedrock is off; the hash embedder and offline stub are in use"
else
  EMB_OUT="$SC_OUT_DIR/.smoke-emb.$$"
  CONV_OUT="$SC_OUT_DIR/.smoke-conv.$$"
  register_cleanup "$EMB_OUT"
  register_cleanup "$CONV_OUT"
  aws bedrock-runtime invoke-model --model-id "$TITAN_MODEL" \
    --cli-binary-format raw-in-base64-out \
    --body "{\"inputText\":\"ping\",\"dimensions\":$VECTOR_DIM,\"normalize\":true}" \
    --content-type application/json --accept application/json "$EMB_OUT" >/dev/null \
    || die "Titan embedding call failed: model access or quota is not usable"
  printf 'CHECK bedrock-titan model=%s dimensions=%s bytes=%s\n' "$TITAN_MODEL" "$VECTOR_DIM" "$(wc -c < "$EMB_OUT" | tr -d ' ')"
  aws bedrock-runtime converse --model-id "$HAIKU_PROFILE" \
    --messages '[{"role":"user","content":[{"text":"ping"}]}]' \
    --inference-config '{"maxTokens":5}' --query 'usage' --output json >"$CONV_OUT" \
    || die "Haiku converse call failed: model access, inference profile or quota is not usable"
  printf 'CHECK bedrock-haiku profile=%s usage=%s\n' "$HAIKU_PROFILE" "$(tr -d '\n ' < "$CONV_OUT")"
  step_ok
fi

step deployed-revision
if [[ -n "$EXPECTED_TAG" ]]; then
  verify_revision "$EXPECTED_TAG"
  step_ok
else
  step_skip "no --expected-image-tag given"
fi

printf 'SMOKE_COMPLETE environment=%s account=%s region=%s\n' "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION"
