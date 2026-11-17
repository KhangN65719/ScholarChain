#!/usr/bin/env bash
# Rehearsal of the Experiment B collector's failure handling (review 1, m5) with a STUB `aws`
# on PATH. No AWS, no network, no credential: the stub is a shell script that prints a tiny JSON
# document per verb, or fails for the verb named in STUB_FAIL_VERB. It checks that
#   - a clean run makes collected/ in one step, with CALL_STATUS.txt and SHA256SUMS;
#   - an OPTIONAL call that fails (get-managed-scaling-policy) is recorded and the run completes;
#   - a REQUIRED call that fails leaves NO collected/ and NO collected.staging, keeps the partial
#     files as collect-failed.<stamp>/, and the same command can then be repeated and succeeds;
#   - an existing collected/ is refused;
#   - the stub saw only describe-, list- and get- verbs.
# Exit 0 and "REHEARSAL pass=<n> fail=0" mean the collector behaves as specified; nothing here
# says that the real AWS calls succeed.
#
# Usage: bash experiments/b-burst/rehearse-collect.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sc-collect-rehearse.XXXXXX")"
PASS=0
FAIL=0
mkdir -p "$TMP_ROOT/bin"

cat > "$TMP_ROOT/bin/aws" <<'STUB'
#!/usr/bin/env bash
# STUB aws for the collector rehearsal. Prints a tiny JSON document, or fails for STUB_FAIL_VERB.
printf '%s %s\n' "$1" "$2" >> "$STUB_LOG"
if [[ "$2" == "${STUB_FAIL_VERB:-}" ]]; then
  echo "An error occurred (AccessDenied) when calling the $2 operation: stub failure" >&2
  exit 254
fi
case "$2" in
  list-instance-groups)
    echo "aws: [ERROR]: An error occurred (ParamValidation): argument operation: Found invalid choice 'list-instance-groups'" >&2
    exit 252
    ;;
  get-execution-history)
    printf '%s\n' '{"events":[{"type":"TaskSucceeded","id":1,"taskSucceededEventDetails":{"output":"{\"ClusterId\":\"j-STUB00000001\"}"}}]}'
    ;;
  *)
    printf '{"stub":"%s"}\n' "$2"
    ;;
esac
STUB
chmod +x "$TMP_ROOT/bin/aws"

ok() {
  PASS=$((PASS + 1))
  printf 'PASS %s\n' "$1"
}

bad() {
  FAIL=$((FAIL + 1))
  printf 'FAIL %s\n' "$1"
}

exists() {
  if [[ -e "$1" ]]; then ok "$2"; else bad "$2 (missing: $1)"; fi
}

missing() {
  if [[ ! -e "$1" ]]; then ok "$2"; else bad "$2 (must not exist: $1)"; fi
}

has() {
  if [[ -f "$1" ]] && grep -q -F -- "$2" "$1"; then ok "$3"; else bad "$3 (expected to find: $2 in $1)"; fi
}

# new_run NAME: a run directory with the two files the collector needs.
new_run() {
  mkdir -p "$TMP_ROOT/$1/raw"
  printf 'RUN_ID=stub\n' > "$TMP_ROOT/$1/run-record.env"
  printf '{\n  "execution_arn": "arn:aws:states:us-east-2:000000000000:stateMachine:stub",\n  "start_utc": "2026-11-18T15:00:00Z",\n  "end_utc": "2026-11-18T16:00:00Z"\n}\n' > "$TMP_ROOT/$1/raw/window.json"
}

# collect NAME [FAIL_VERB]: run the real collector against the stub; the exit status is left in RC.
RC=0
collect() {
  RC=0
  STUB_LOG="$TMP_ROOT/$1.stub.log" STUB_FAIL_VERB="${2:-}" AWS_PROFILE=stub PATH="$TMP_ROOT/bin:$PATH" \
    bash "$HERE/collect.sh" --run-dir "$TMP_ROOT/$1" > "$TMP_ROOT/$1.out" 2>&1 || RC=$?
}

# 1: clean run
new_run clean
collect clean
if [[ "$RC" -eq 0 ]]; then ok "clean run exits 0"; else bad "clean run exit $RC"; cat "$TMP_ROOT/clean.out"; fi
exists "$TMP_ROOT/clean/collected/emr-list-instances.json" "clean: collected/ holds list-instances"
exists "$TMP_ROOT/clean/collected/SHA256SUMS" "clean: SHA256SUMS written"
has "$TMP_ROOT/clean/collected/CALL_STATUS.txt" 'emr-managed-scaling-policy.json OK' "clean: CALL_STATUS records the policy call as OK"
missing "$TMP_ROOT/clean/collected.staging" "clean: no staging directory is left"
has "$TMP_ROOT/clean/collected/SHA256SUMS" 'cloudwatch-metric-data.json' "clean: SHA256SUMS lists the metric data"
has "$TMP_ROOT/clean/collected/metric-queries.json" '"MetricName": "ContainerPendingRatio"' "clean: the query file asks for ContainerPendingRatio"

# 2: an optional call fails
new_run optional
collect optional get-managed-scaling-policy
if [[ "$RC" -eq 0 ]]; then ok "optional failure: the run still exits 0"; else bad "optional failure exit $RC"; cat "$TMP_ROOT/optional.out"; fi
exists "$TMP_ROOT/optional/collected/emr-list-instances.json" "optional failure: collected/ is complete"
missing "$TMP_ROOT/optional/collected/emr-managed-scaling-policy.json" "optional failure: the failed call leaves no output file"
has "$TMP_ROOT/optional/collected/CALL_STATUS.txt" 'emr-managed-scaling-policy.json FAILED exit=254' "optional failure: CALL_STATUS records FAILED with the exit code"
exists "$TMP_ROOT/optional/collected/emr-managed-scaling-policy.json.error.txt" "optional failure: the error text is kept"

# 3: a required call fails, then the same command is repeated
new_run required
collect required list-instances
if [[ "$RC" -ne 0 ]]; then ok "required failure: the run exits nonzero"; else bad "required failure exited 0"; fi
missing "$TMP_ROOT/required/collected" "required failure: no collected/ directory"
missing "$TMP_ROOT/required/collected.staging" "required failure: no staging directory left behind"
has "$TMP_ROOT/required.out" 'COLLECT_FAILED: partial files kept in' "required failure: the partial files are reported as kept"
kept=0
for d in "$TMP_ROOT"/required/collect-failed.*; do
  if [[ -d "$d" ]]; then
    kept=$((kept + 1))
    has "$d/CALL_STATUS.txt" 'emr-list-instances.json FAILED exit=254' "required failure: the kept stage records the failed call"
    exists "$d/emr-describe-cluster.json" "required failure: calls made before the failure are kept"
  fi
done
if [[ "$kept" -eq 1 ]]; then ok "required failure: exactly one collect-failed directory"; else bad "required failure: $kept collect-failed directories"; fi
collect required
if [[ "$RC" -eq 0 ]]; then ok "repeat after a required failure exits 0"; else bad "repeat exit $RC"; cat "$TMP_ROOT/required.out"; fi
exists "$TMP_ROOT/required/collected/emr-list-instances.json" "repeat: collected/ is complete"
kept=0
for d in "$TMP_ROOT"/required/collect-failed.*; do
  if [[ -d "$d" ]]; then kept=$((kept + 1)); fi
done
if [[ "$kept" -eq 1 ]]; then ok "repeat: the failed attempt's files are still kept"; else bad "repeat: $kept collect-failed directories"; fi

# 4: an existing collected/ is refused
collect clean
if [[ "$RC" -ne 0 ]]; then ok "existing collected/ is refused"; else bad "existing collected/ was accepted"; fi
has "$TMP_ROOT/clean.out" 'already exists' "existing collected/: the refusal says why"

# 5: the stub saw only read verbs
bad_verbs=0
for log in "$TMP_ROOT"/*.stub.log; do
  while read -r svc verb; do
    case "$svc $verb" in
      "stepfunctions describe-execution" | "stepfunctions get-execution-history" | \
      "emr describe-cluster" | "emr list-instances" | "emr get-managed-scaling-policy" | "emr list-steps" | \
      "cloudwatch list-metrics" | "cloudwatch get-metric-data") ;;
      *) bad_verbs=$((bad_verbs + 1)); printf 'call outside the allowlist: %s %s\n' "$svc" "$verb" ;;
    esac
  done < "$log"
done
if [[ "$bad_verbs" -eq 0 ]]; then ok "every AWS call was one of the 8 allowlisted (service, verb) pairs"; else bad "$bad_verbs calls outside the allowlist"; fi

# 6: R14, the collector must not call a subcommand the installed CLI lacks (the stub fails it
# with exit 252 as the real CLI did on 2026-11-17), and a clean run makes exactly the 8 expected calls.
if grep -q -F -- 'list-instance-groups' "$TMP_ROOT/clean.stub.log"; then bad "the collector called emr list-instance-groups"; else ok "clean: no emr list-instance-groups call"; fi
calls=0
while read -r _svc _verb; do calls=$((calls + 1)); done < "$TMP_ROOT/clean.stub.log"
if [[ "$calls" -eq 8 ]]; then ok "clean: the stub saw exactly the 8 expected calls"; else bad "clean: unexpected call count $calls"; fi

# 7: m5, the read-only guard is an exact (service, verb) allowlist. Extract aws_read() from the real
# collector and call it with pairs it must refuse; the stub must never be reached for them.
sed -n '/^aws_read() {$/,/^}$/p' "$HERE/collect.sh" > "$TMP_ROOT/aws_read.func.sh"
if [[ -s "$TMP_ROOT/aws_read.func.sh" ]]; then ok "guard: aws_read() extracted from collect.sh"; else bad "guard: aws_read() not found in collect.sh"; fi
guard() {
  RC=0
  (
    set +e
    die() { printf 'COLLECT_REFUSED: %s\n' "$*" >&2; exit 1; }
    REGION=us-east-2
    # shellcheck disable=SC1090
    source "$TMP_ROOT/aws_read.func.sh"
    STUB_LOG="$TMP_ROOT/guard.stub.log" PATH="$TMP_ROOT/bin:$PATH" aws_read "$@"
  ) > "$TMP_ROOT/guard.out" 2>&1 || RC=$?
}
rm -f "$TMP_ROOT/guard.stub.log"
guard secretsmanager get-secret-value --secret-id x
if [[ "$RC" -ne 0 ]]; then ok "guard: secretsmanager get-secret-value is refused"; else bad "guard: secretsmanager get-secret-value was accepted"; fi
has "$TMP_ROOT/guard.out" 'COLLECT_REFUSED: not an allowed read-only AWS call: secretsmanager get-secret-value' "guard: the refusal names the call"
guard ssm get-parameter --name x
if [[ "$RC" -ne 0 ]]; then ok "guard: ssm get-parameter is refused"; else bad "guard: ssm get-parameter was accepted"; fi
guard emr list-instance-groups --cluster-id j-X
if [[ "$RC" -ne 0 ]]; then ok "guard: emr list-instance-groups (not in the allowlist) is refused"; else bad "guard: emr list-instance-groups was accepted"; fi
guard emr terminate-job-flows --cluster-ids j-X
if [[ "$RC" -ne 0 ]]; then ok "guard: a write verb is refused"; else bad "guard: a write verb was accepted"; fi
guard emr describe-cluster --cluster-id j-X
if [[ "$RC" -eq 0 ]]; then ok "guard: an allowlisted pair (emr describe-cluster) passes"; else bad "guard: emr describe-cluster refused"; fi
if [[ "$(wc -l < "$TMP_ROOT/guard.stub.log")" -eq 1 ]]; then ok "guard: the stub was reached only by the allowlisted call"; else bad "guard: stub saw refused calls"; fi

printf 'OUTPUT_DIR=%s\n' "$TMP_ROOT"
printf 'REHEARSAL pass=%s fail=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
