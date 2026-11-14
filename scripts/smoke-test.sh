#!/usr/bin/env bash
# smoke-test.sh: checks of a deployed environment. Health and readiness of both
# services, one bounded query, an optional Bedrock access check, an optional
# deployed-revision check, then three checks against the services' contract:
# idempotency (the same Idempotency-Key and body twice returns the same query id,
# a different body under that key is 422 IDEMPOTENCY_KEY_REUSED), one expected
# error path (a request without a token is 401 UNAUTHORIZED with a trace id), and,
# only when --read-secret-arn is given, an evidence fetch for the first evidence id
# the query cited (GET /v1/evidence/{id} on the fast service). It creates no
# infrastructure and changes none, but it is not free of side effects: the query
# may write a query-session row and an evidence-bundle row to DynamoDB (the runtime
# role has PutItem on both, an unconfirmed assumption), the query may call Bedrock
# when Bedrock is on, and --bedrock on makes two billed calls. It reads one Secrets
# Manager value, or two with --read-secret-arn. The bounded query carries an
# Idempotency-Key header (the one addition to that request); the replay and the
# different-body request do no workflow work and no Bedrock call. Every check here
# is written from modules/services and UNVERIFIED against a deployment until Phase 14.
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
#     --cost-ceiling-usd 20 --approval-record /abs/approval.env \
#     --fast-url http://10.20.0.10:8080 \
#     --slow-url http://10.20.0.11:8081 \
#     --query-secret-arn arn:aws:secretsmanager:us-east-2:123456789012:secret:... \
#     --bedrock off [--expected-image-tag <40-hex git sha>] [--question "text"] \
#     [--read-secret-arn arn:aws:secretsmanager:us-east-2:123456789012:secret:...]
#
#   --read-secret-arn  optional. The Secrets Manager ARN of a bearer token with the
#                     read scope. The evidence route needs it: the query token has
#                     the query scope only, and the service answers 403 FORBIDDEN
#                     for a token of the wrong scope. Without it the evidence-fetch
#                     step is skipped and SMOKE_COVERAGE says so. Handled like the
#                     query token (private temp file, never printed). Its secret
#                     layout is assumed to match the query secret (UNVERIFIED).
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
READ_SECRET_ARN=""
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
    --read-secret-arn) need_arg "$@"; READ_SECRET_ARN="$2"; shift 2 ;;
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
dry_announce

step validate-params
init_common yes
require_cmd aws curl awk grep
# python3 reads the JSON answers (no jq is assumed). A dry run reads none.
if ! is_dry_run; then require_cmd python3; fi
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
if [[ -n "$READ_SECRET_ARN" ]]; then
  [[ "$READ_SECRET_ARN" =~ ^arn:aws:secretsmanager:${SC_REGION}:${SC_ACCOUNT}:secret:[A-Za-z0-9/_+=.@-]+$ ]] || die "--read-secret-arn must be a Secrets Manager ARN in account $SC_ACCOUNT, $SC_REGION"
fi
[[ "$BEDROCK" == "on" || "$BEDROCK" == "off" ]] || die "--bedrock must be on or off"
[[ "$VECTOR_DIM" =~ ^(256|384|512|1024)$ ]] || die "--vector-dimension must be 256, 384, 512 or 1024"
[[ ${#QUESTION} -le 200 ]] || die "the smoke question is limited to 200 characters"
if [[ -n "$EXPECTED_TAG" ]]; then require_image_tag "$EXPECTED_TAG"; fi
QUESTION_DIGEST="$(printf '%s' "$QUESTION" | sha256_stdin)"
EXTRA="fast=$FAST_URL;slow=$SLOW_URL;secret=$SECRET_ARN;bedrock=$BEDROCK;tag=${EXPECTED_TAG:-none};question=$QUESTION_DIGEST;dim=$VECTOR_DIM;titan=$TITAN_MODEL;haiku=$HAIKU_PROFILE"
# Added only when given, so the canonical arguments of an invocation without it are unchanged.
if [[ -n "$READ_SECRET_ARN" ]]; then EXTRA="$EXTRA;read_secret=$READ_SECRET_ARN"; fi
maybe_print_digest smoke-test "$EXTRA"
step_ok

if is_dry_run; then
  dry_banner smoke-test "$EXTRA"
fi

step approval-record
verify_approval smoke-test "$EXTRA" "n/a" "n/a"
step_ok

if is_dry_run; then
  dry_approval_unconsumed
  dry_step identity-check
  dry_would "aws sts get-caller-identity (skipped)"
  dry_step health
  dry_would "consume the approval record; GET $FAST_URL/healthz and $SLOW_URL/healthz (curl, 10 s limit each)"
  dry_step readiness
  dry_would "GET $FAST_URL/readyz and $SLOW_URL/readyz"
  dry_step bounded-query
  dry_would "read the query token from $SECRET_ARN into a mode 600 file under $SC_OUT_DIR; POST $SLOW_URL/v1/query (60 s limit)"
  dry_step bedrock-access
  if [[ "$BEDROCK" == "on" ]]; then
    dry_would "bedrock is on: two single-request billed calls (Titan embedding, Haiku converse)"
  else
    dry_would "bedrock is off: the real run skips this step"
  fi
  dry_step deployed-revision
  if [[ -n "$EXPECTED_TAG" ]]; then
    dry_would "verify the deployed revision is :$EXPECTED_TAG"
  else
    dry_would "no --expected-image-tag: the real run skips this step"
  fi
  dry_step idempotency
  dry_would "POST $SLOW_URL/v1/query again with the same Idempotency-Key and the identical body (60 s limit): expect 200 and the same queryId as the bounded query; then POST a different body (one trailing space) under that key: expect 422 IDEMPOTENCY_KEY_REUSED with an error.traceId"
  dry_step error-path
  dry_would "POST $SLOW_URL/v1/query with no Authorization header (10 s limit): expect 401 UNAUTHORIZED, an error.traceId, and the same value in the X-Trace-Id response header"
  dry_step evidence-fetch
  if [[ -n "$READ_SECRET_ARN" ]]; then
    dry_would "read the read-scope token from $READ_SECRET_ARN into a mode 600 file under $SC_OUT_DIR; GET $FAST_URL/v1/evidence/<first evidence id cited by the bounded query> (10 s limit): expect 200 and the same evidenceId"
  else
    dry_would "no --read-secret-arn: the real run skips this step (the query token cannot read evidence)"
  fi
  dry_finish
fi

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

# write_bearer_file FILE ARN LABEL: read one token from Secrets Manager into a mode
# 600 file as an Authorization header line. The value is never printed and never
# placed in an argument list. The caller registers FILE for cleanup first.
write_bearer_file() {
  local file="$1" arn="$2" label="$3" tok
  (umask 077 && : > "$file")
  tok="$(aws secretsmanager get-secret-value --secret-id "$arn" --query SecretString --output text)" \
    || die "could not read the $label token secret (value is never printed)"
  [[ -n "$tok" && "$tok" != "None" ]] || die "the $label token secret is empty"
  printf 'Authorization: Bearer %s\n' "$tok" > "$file"
}

# json_field FILE WHAT: print one identifier from a JSON answer, or nothing and
# status 1. python3 -I, no file is written, and only a value that matches
# [A-Za-z0-9:_.=-]{1,300} (the services' own identifier pattern) is printed, so a
# response can never put other text on the console through this function.
# WHAT: query-id (queryId), evidence-id (the first id in claims[].evidenceIds, else
# evidence.items[].evidenceId), evidence-id-of (evidenceId of an evidence view),
# error-code (error.code), trace-id (error.traceId).
JSON_FIELD_PY='import json, re, sys
try:
    with open(sys.argv[1], "rb") as f:
        d = json.loads(f.read().decode("utf-8"))
    what = sys.argv[2]
    v = None
    if what == "query-id":
        v = d["queryId"]
    elif what == "evidence-id":
        for c in d.get("claims") or []:
            ids = c.get("evidenceIds") or []
            if ids:
                v = ids[0]
                break
        if v is None:
            for i in (d.get("evidence") or {}).get("items") or []:
                if i.get("evidenceId"):
                    v = i["evidenceId"]
                    break
    elif what == "evidence-id-of":
        v = d["evidenceId"]
    elif what == "error-code":
        v = d["error"]["code"]
    elif what == "trace-id":
        v = d["error"]["traceId"]
    if isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9:_.=-]{1,300}", v):
        print(v)
        sys.exit(0)
except Exception:
    pass
sys.exit(1)'

json_field() {
  python3 -I -B -c "$JSON_FIELD_PY" "$1" "$2"
}

# header_value FILE NAME: the first response header NAME (case-insensitive) from a
# curl -D dump, without the carriage return.
header_value() {
  awk -v n="$2" 'BEGIN { n = tolower(n) } { k = $0; sub(/:.*/, "", k) } tolower(k) == n { v = $0; sub(/^[^:]*:[[:space:]]*/, "", v); sub(/\r$/, "", v); print v; exit }' "$1"
}

step bounded-query
make_out_dir
HDR="$SC_OUT_DIR/.smoke-auth.$$"
BODY_OUT="$SC_OUT_DIR/.smoke-body.$$"
register_cleanup "$HDR"
register_cleanup "$BODY_OUT"
write_bearer_file "$HDR" "$SECRET_ARN" query
# JSON-escape the question: backslash and double quote only; control characters are refused.
if printf '%s' "$QUESTION" | grep -q '[[:cntrl:]]'; then die "the question holds control characters"; fi
ESCAPED="$(printf '%s' "$QUESTION" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
QUERY_JSON="{\"question\":\"$ESCAPED\",\"mode\":\"sync\"}"
IDEM_KEY="smoke-$(utc_stamp)-$$"
CODE="$(curl -sS --max-time 60 --max-filesize 1048576 -o "$BODY_OUT" -w '%{http_code}' \
  -X POST -H "@$HDR" -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM_KEY" \
  --data "$QUERY_JSON" "$SLOW_URL/v1/query")" || CODE="curl-failed"
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

step idempotency
QUERY_ID="$(json_field "$BODY_OUT" query-id)" || die "the bounded query answer has no usable queryId, so a replay cannot be compared"
REPLAY_OUT="$SC_OUT_DIR/.smoke-replay.$$"
REUSE_OUT="$SC_OUT_DIR/.smoke-reuse.$$"
register_cleanup "$REPLAY_OUT"
register_cleanup "$REUSE_OUT"
# The same key and the identical body bytes: the service replays the stored answer
# (the fingerprint is the sha256 of the raw body and the mode), so no workflow run.
CODE="$(curl -sS --max-time 60 --max-filesize 1048576 -o "$REPLAY_OUT" -w '%{http_code}' \
  -X POST -H "@$HDR" -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM_KEY" \
  --data "$QUERY_JSON" "$SLOW_URL/v1/query")" || CODE="curl-failed"
printf 'CHECK idempotency-replay url=%s/v1/query http=%s\n' "$SLOW_URL" "$CODE"
[[ "$CODE" == "200" ]] || die "idempotent replay expected HTTP 200, got $CODE"
REPLAY_ID="$(json_field "$REPLAY_OUT" query-id)" || die "the replayed answer has no usable queryId"
printf 'CHECK idempotency-query-id first=%s replay=%s\n' "$QUERY_ID" "$REPLAY_ID"
[[ "$REPLAY_ID" == "$QUERY_ID" ]] || die "the replay returned a different queryId: the Idempotency-Key was not honored"
# A different body (one trailing space changes the bytes, not the meaning) under the
# same key is refused before any work: 422 IDEMPOTENCY_KEY_REUSED.
CODE="$(curl -sS --max-time 60 --max-filesize 1048576 -o "$REUSE_OUT" -w '%{http_code}' \
  -X POST -H "@$HDR" -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM_KEY" \
  --data "$QUERY_JSON " "$SLOW_URL/v1/query")" || CODE="curl-failed"
REUSE_CODE="$(json_field "$REUSE_OUT" error-code)" || REUSE_CODE="none"
REUSE_TRACE="$(json_field "$REUSE_OUT" trace-id)" || REUSE_TRACE="none"
printf 'CHECK idempotency-different-body http=%s error_code=%s trace_id=%s\n' "$CODE" "$REUSE_CODE" "$REUSE_TRACE"
[[ "$CODE" == "422" ]] || die "a different body under the same Idempotency-Key expected HTTP 422, got $CODE"
[[ "$REUSE_CODE" == "IDEMPOTENCY_KEY_REUSED" ]] || die "expected error code IDEMPOTENCY_KEY_REUSED, got $REUSE_CODE"
[[ "$REUSE_TRACE" != "none" ]] || die "the typed error carries no traceId"
step_ok

step error-path
ERR_OUT="$SC_OUT_DIR/.smoke-error.$$"
ERR_HDR="$SC_OUT_DIR/.smoke-errhdr.$$"
register_cleanup "$ERR_OUT"
register_cleanup "$ERR_HDR"
# No Authorization header: the router answers 401 before it reads the body or runs
# any handler, so this check does no work and writes nothing.
CODE="$(curl -sS --max-time 10 --max-filesize 1048576 -o "$ERR_OUT" -D "$ERR_HDR" -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' --data '{}' "$SLOW_URL/v1/query")" || CODE="curl-failed"
ERR_CODE="$(json_field "$ERR_OUT" error-code)" || ERR_CODE="none"
ERR_TRACE="$(json_field "$ERR_OUT" trace-id)" || ERR_TRACE="none"
ERR_HDR_TRACE="$(header_value "$ERR_HDR" X-Trace-Id)"
printf 'CHECK error-path-unauthorized http=%s error_code=%s trace_id=%s\n' "$CODE" "$ERR_CODE" "$ERR_TRACE"
[[ "$CODE" == "401" ]] || die "a request without a token expected HTTP 401, got $CODE"
[[ "$ERR_CODE" == "UNAUTHORIZED" ]] || die "expected error code UNAUTHORIZED, got $ERR_CODE"
[[ "$ERR_TRACE" != "none" ]] || die "the typed error carries no traceId"
[[ "$ERR_HDR_TRACE" == "$ERR_TRACE" ]] || die "the X-Trace-Id header differs from error.traceId"
step_ok

step evidence-fetch
if [[ -z "$READ_SECRET_ARN" ]]; then
  step_skip "no --read-secret-arn: the query token has the query scope only and cannot read evidence"
  EVIDENCE_FETCH="skipped"
else
  READ_HDR="$SC_OUT_DIR/.smoke-auth.read.$$"
  EVID_OUT="$SC_OUT_DIR/.smoke-evidence.$$"
  register_cleanup "$READ_HDR"
  register_cleanup "$EVID_OUT"
  write_bearer_file "$READ_HDR" "$READ_SECRET_ARN" read
  EVID_ID="$(json_field "$BODY_OUT" evidence-id)" || die "the bounded query answer cites no evidence id (it may have abstained), so there is nothing to fetch"
  CODE="$(curl -sS --max-time 10 --max-filesize 1048576 -o "$EVID_OUT" -w '%{http_code}' \
    -H "@$READ_HDR" "$FAST_URL/v1/evidence/$EVID_ID")" || CODE="curl-failed"
  GOT_ID="$(json_field "$EVID_OUT" evidence-id-of)" || GOT_ID="none"
  printf 'CHECK evidence-fetch url=%s/v1/evidence/<id> http=%s evidence_id=%s returned_id=%s\n' "$FAST_URL" "$CODE" "$EVID_ID" "$GOT_ID"
  [[ "$CODE" == "200" ]] || die "evidence fetch expected HTTP 200, got $CODE"
  [[ "$GOT_ID" == "$EVID_ID" ]] || die "the evidence view carries a different evidenceId"
  step_ok
  EVIDENCE_FETCH="ran"
fi

COV_BEDROCK="skipped"
if [[ "$BEDROCK" == "on" ]]; then COV_BEDROCK="ran"; fi
COV_REVISION="skipped"
if [[ -n "$EXPECTED_TAG" ]]; then COV_REVISION="ran"; fi
printf 'SMOKE_COVERAGE health=ran readiness=ran bounded-query=ran idempotency=ran error-path=ran evidence-fetch=%s bedrock=%s deployed-revision=%s\n' \
  "$EVIDENCE_FETCH" "$COV_BEDROCK" "$COV_REVISION"
printf 'SMOKE_COMPLETE environment=%s account=%s region=%s\n' "$SC_ENV" "$SC_ACCOUNT" "$SC_REGION"
