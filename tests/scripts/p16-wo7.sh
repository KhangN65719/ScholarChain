#!/usr/bin/env bash
# tests/scripts/p16-wo7.sh: Phase 16 WO-7 harness cases for WO-5 and WO-6.
#
#   bash tests/scripts/p16-wo7.sh
#
# Part 1 (WO-6, scripts/smoke-test.sh): the bounded-query decision is cut out of the real
# script and run in a child shell over scripted response bodies, so no curl, aws or approval
# record is needed. An abstained answer, an answer with no evidence, a missing or non-boolean
# `abstained`, and a body that is not JSON must all fail before the replay and
# SMOKE_COMPLETE; an answered query with evidence passes. Static cases pin the dry-run text
# and the step order. RB1-B (P16-11): the answered line is `CHECK bounded-query-answer
# query_id=<id|unreadable> abstained=.. evidence_items=.. abstention_reason=..`, the non-200
# branch prints `CHECK bounded-query-error error_code=.. trace_id=..` and fails, and a scripted
# body holding a distinctive answer string never reaches stdout or stderr on any path.
# Part 2 (WO-5): python3 -I tests/scripts/p16/test_load_derive.py (load_client.py records
# abstention_reason; no unresolved WO marker remains; derive_latency.py bootstrap option).
#
# Prints "CASE <id> PASS|FAIL <reason>" and "RESULT pass=N fail=M"; exits 0 only when fail=0.
# Needs bash, awk, grep, python3. No AWS, no network except loopback in part 2.
# Owner: test-engineer. A failing case is a finding for the script's owner; never loosen it.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SMOKE="$ROOT/scripts/smoke-test.sh"
PASS=0
FAIL=0
WORK="$(mktemp -d "${TMPDIR:-/tmp}/p16-wo7.XXXXXX")" || exit 2
trap 'rm -rf "$WORK"' EXIT

ok() { printf 'CASE %s PASS ok\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf 'CASE %s FAIL %s\n' "$1" "$2"; FAIL=$((FAIL + 1)); }

# Cut the real json_field helper and the real bounded-query decision out of smoke-test.sh.
awk '/^JSON_FIELD_PY=/ { p = 1 } p { print } /^json_field\(\) \{/ { q = 1 } q && /^}/ { exit }' "$SMOKE" > "$WORK/helper.sh"
awk '/^ABSTAINED=/ { p = 1 } p { print } /^\[\[ "\$EVIDENCE_COUNT"/ { exit }' "$SMOKE" > "$WORK/decision.sh"

if [ ! -s "$WORK/helper.sh" ] || [ ! -s "$WORK/decision.sh" ]; then
  bad "0.extract.bounded-query-blocks" "could not cut json_field or the decision block out of scripts/smoke-test.sh"
  printf 'RESULT pass=%s fail=%s\n' "$PASS" "$FAIL"
  exit 1
fi
ok "0.extract.bounded-query-blocks"
grep -q 'abstention-reason' "$WORK/helper.sh" && ok "0.extract.helper-has-abstention-reason" || bad "0.extract.helper-has-abstention-reason" "json_field lacks abstention-reason"

# smoke_case NAME BODY EXPECT_RC [stdout-needle ...]
# Runs the decision block over BODY. REACHED_REPLAY is printed only if the block fell through,
# which stands for the idempotency step (and SMOKE_COMPLETE) being reached.
OUT_F="$WORK/out.txt"
ERR_F="$WORK/err.txt"
run_decision() {
  printf '%s' "$1" > "$WORK/body.json"
  bash -c '
    die() { printf "DIE %s\n" "$*" >&2; exit 1; }
    BODY_OUT="$1"
    source "$2"
    source "$3"
    printf "REACHED_REPLAY\n"
  ' _ "$WORK/body.json" "$WORK/helper.sh" "$WORK/decision.sh" > "$OUT_F" 2> "$ERR_F" < /dev/null
  return $?
}

expect_fail() { # id body err-needle
  local rc
  run_decision "$2"; rc=$?
  if [ "$rc" -eq 0 ]; then bad "$1" "expected a failure, exit 0"; return; fi
  if grep -q REACHED_REPLAY "$OUT_F"; then bad "$1" "replay step reached after a failure"; return; fi
  if ! grep -qF -- "$3" "$ERR_F"; then bad "$1" "stderr lacks: $3 (got: $(head -c 200 "$ERR_F"))"; return; fi
  ok "$1"
}

expect_pass() { # id body stdout-needle
  local rc
  run_decision "$2"; rc=$?
  if [ "$rc" -ne 0 ]; then bad "$1" "expected pass, exit $rc: $(head -c 200 "$ERR_F")"; return; fi
  grep -q REACHED_REPLAY "$OUT_F" || { bad "$1" "did not fall through"; return; }
  grep -qF -- "$3" "$OUT_F" || { bad "$1" "stdout lacks: $3 (got: $(head -c 200 "$OUT_F"))"; return; }
  ok "$1"
}

expect_fail "6.smoke.abstained-true-with-evidence-fails-and-prints-the-reason" \
  '{"abstained":true,"abstentionReason":"NO_EVIDENCE","evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "abstentionReason=NO_EVIDENCE"
expect_fail "6.smoke.abstained-true-says-an-abstained-answer-is-not-a-pass" \
  '{"abstained":true,"abstentionReason":"NO_EVIDENCE","evidence":{"items":[]}}' \
  "an abstained answer is not a pass"
expect_fail "6.smoke.abstained-true-with-no-reason-field-prints-none" \
  '{"abstained":true,"evidence":{"items":[]}}' \
  "abstentionReason=none"
expect_fail "6.smoke.abstained-false-with-empty-items-fails" \
  '{"abstained":false,"evidence":{"items":[]}}' \
  "0 evidence items"
expect_fail "6.smoke.abstained-false-with-no-evidence-key-fails" \
  '{"abstained":false}' \
  "0 evidence items"
expect_fail "6.smoke.abstained-false-with-null-evidence-fails" \
  '{"abstained":false,"evidence":null}' \
  "0 evidence items"
expect_fail "6.smoke.missing-abstained-fails-closed" \
  '{"evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "no boolean abstained"
expect_fail "6.smoke.string-false-is-not-a-boolean-and-fails-closed" \
  '{"abstained":"false","evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "no boolean abstained"
expect_fail "6.smoke.null-abstained-fails-closed" \
  '{"abstained":null,"evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "no boolean abstained"
expect_fail "6.smoke.numeric-zero-abstained-fails-closed" \
  '{"abstained":0,"evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "no boolean abstained"
expect_fail "6.smoke.body-that-is-not-json-fails" \
  'this is not json' \
  "no boolean abstained"
expect_fail "6.smoke.empty-body-fails" \
  '' \
  "no boolean abstained"
expect_fail "6.smoke.a-reason-with-spaces-is-unreadable-and-still-fails" \
  '{"abstained":true,"abstentionReason":"free text with spaces","evidence":{"items":[]}}' \
  "abstentionReason=unreadable"
expect_pass "6.smoke.abstained-false-with-one-item-passes-and-an-absent-queryId-reads-unreadable" \
  '{"abstained":false,"evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "CHECK bounded-query-answer query_id=unreadable abstained=false evidence_items=1 abstention_reason=none"
expect_pass "6.smoke.abstained-false-with-one-item-prints-the-queryId" \
  '{"queryId":"q-123","abstained":false,"evidence":{"items":[{"evidenceId":"e1"}]}}' \
  "CHECK bounded-query-answer query_id=q-123 abstained=false evidence_items=1 abstention_reason=none"
expect_pass "6.smoke.abstained-false-with-three-items-passes" \
  '{"abstained":false,"abstentionReason":null,"evidence":{"items":[{},{},{}]}}' \
  "evidence_items=3"

# The failing case must not print the answer text or the question: only codes and counts.
run_decision '{"abstained":true,"abstentionReason":"NO_EVIDENCE","answer":"SECRETANSWERTEXT","evidence":{"items":[]}}'
if grep -q SECRETANSWERTEXT "$OUT_F" "$ERR_F"; then bad "6.smoke.the-decision-never-prints-answer-text" "answer text on the console"; else ok "6.smoke.the-decision-never-prints-answer-text"; fi

# P16-11 (review 1 finding 17): the smoke output carries identifier fields only. A scripted
# body holds a distinctive answer string in the answer, in a claim, in the evidence and in an
# abstention message; none of it may reach stdout or stderr, on a pass, on an abstention, or
# on a non-200 reply.
DISTINCT="ZEBRA-QUOKKA-ANSWER-7731"
no_leak() { # id
  if grep -q "$DISTINCT" "$OUT_F" "$ERR_F"; then bad "$1" "the answer string reached the console"; else ok "$1"; fi
}
ANSWERED_BODY='{"queryId":"q-9","answer":"'"$DISTINCT"'","claims":[{"text":"'"$DISTINCT"' claim","evidenceIds":["e1"]}],"abstained":false,"abstentionReason":null,"evidence":{"items":[{"evidenceId":"e1","text":"'"$DISTINCT"' evidence"}]}}'
run_decision "$ANSWERED_BODY"; RC_ANS=$?
if [ "$RC_ANS" -eq 0 ] && grep -qF "CHECK bounded-query-answer query_id=q-9 abstained=false evidence_items=1 abstention_reason=none" "$OUT_F"; then ok "6.smoke.p16-11.answered-body-prints-identifier-fields-only"; else bad "6.smoke.p16-11.answered-body-prints-identifier-fields-only" "rc=$RC_ANS out=$(head -c 200 "$OUT_F")"; fi
no_leak "6.smoke.p16-11.answered-body-never-prints-the-answer-string"
run_decision '{"queryId":"q-8","answer":"'"$DISTINCT"'","abstained":true,"abstentionReason":"NO_EVIDENCE","message":"'"$DISTINCT"'","evidence":{"items":[]}}'
no_leak "6.smoke.p16-11.abstained-body-never-prints-the-answer-string"
run_decision '{"queryId":"free text with spaces '"$DISTINCT"'","abstained":false,"evidence":{"items":[{}]}}'
no_leak "6.smoke.p16-11.a-queryId-that-is-not-an-identifier-is-never-printed"
if grep -qF "query_id=unreadable" "$OUT_F"; then ok "6.smoke.p16-11.a-queryId-that-is-not-an-identifier-reads-unreadable"; else bad "6.smoke.p16-11.a-queryId-that-is-not-an-identifier-reads-unreadable" "out=$(head -c 200 "$OUT_F")"; fi

# The non-200 branch is cut out of the real script and run over scripted error bodies.
awk '/^if \[\[ "\$CODE" != "200" \]\]; then/ { p = 1 } p { print } p && /^fi$/ { exit }' "$SMOKE" > "$WORK/errblock.sh"
if [ -s "$WORK/errblock.sh" ]; then
  ok "0.extract.non-200-block"
  run_errblock() { # code body
    printf '%s' "$2" > "$WORK/body.json"
    bash -c '
      die() { printf "DIE %s\n" "$*" >&2; exit 1; }
      BODY_OUT="$1"
      CODE="$4"
      source "$2"
      source "$3"
      printf "REACHED_REPLAY\n"
    ' _ "$WORK/body.json" "$WORK/helper.sh" "$WORK/errblock.sh" "$1" > "$OUT_F" 2> "$ERR_F" < /dev/null
    return $?
  }
  run_errblock 500 '{"error":{"code":"INTERNAL","traceId":"trace-1","message":"'"$DISTINCT"'"},"answer":"'"$DISTINCT"'"}'; RC_E=$?
  if [ "$RC_E" -ne 0 ] && grep -qF "CHECK bounded-query-error error_code=INTERNAL trace_id=trace-1" "$OUT_F" && grep -qF "expected HTTP 200, got 500" "$ERR_F" && ! grep -q REACHED_REPLAY "$OUT_F"; then ok "6.smoke.p16-11.non-200-prints-error-code-and-trace-id-then-fails"; else bad "6.smoke.p16-11.non-200-prints-error-code-and-trace-id-then-fails" "rc=$RC_E out=$(head -c 200 "$OUT_F") err=$(head -c 200 "$ERR_F")"; fi
  no_leak "6.smoke.p16-11.non-200-never-prints-the-body-text"
  run_errblock 502 'this is not json '"$DISTINCT"
  if [ "$?" -ne 0 ] && grep -qF "CHECK bounded-query-error error_code=unreadable trace_id=unreadable" "$OUT_F"; then ok "6.smoke.p16-11.non-200-with-a-non-json-body-reads-unreadable"; else bad "6.smoke.p16-11.non-200-with-a-non-json-body-reads-unreadable" "out=$(head -c 200 "$OUT_F")"; fi
  no_leak "6.smoke.p16-11.non-200-non-json-body-never-printed"
  run_errblock 500 '{"error":{"code":"free text with spaces '"$DISTINCT"'","traceId":"t 1"}}'
  if grep -qF "error_code=unreadable trace_id=unreadable" "$OUT_F"; then ok "6.smoke.p16-11.non-200-fields-that-are-not-identifiers-read-unreadable"; else bad "6.smoke.p16-11.non-200-fields-that-are-not-identifiers-read-unreadable" "out=$(head -c 200 "$OUT_F")"; fi
  no_leak "6.smoke.p16-11.non-200-free-text-fields-never-printed"
  run_errblock 200 '{"abstained":false}'
  if [ "$?" -eq 0 ] && grep -q REACHED_REPLAY "$OUT_F" && ! grep -q 'bounded-query-error' "$OUT_F"; then ok "6.smoke.p16-11.http-200-skips-the-error-branch"; else bad "6.smoke.p16-11.http-200-skips-the-error-branch" "out=$(head -c 200 "$OUT_F")"; fi
else
  bad "0.extract.non-200-block" "could not cut the non-200 branch out of scripts/smoke-test.sh"
fi

# Static: the body head is gone.
if grep -qF 'RESPONSE_HEAD' "$SMOKE"; then bad "6.smoke.static.p16-11.no-RESPONSE_HEAD" "RESPONSE_HEAD is still in scripts/smoke-test.sh"; else ok "6.smoke.static.p16-11.no-RESPONSE_HEAD"; fi

# Static: the body file is only written by curl -o, measured by wc -c, or read by json_field
# (which prints an identifier or nothing). The variable's own assignment and its cleanup
# registration are the two other lines that must name it. Any other use (cat, echo, printf,
# head, a redirect, ${BODY_OUT}) is refused. A mutated copy with a `cat` line proves the audit.
body_out_stray() { # file: prints the lines that still name BODY_OUT after the allowed forms are removed
  sed -E \
    -e 's/^BODY_OUT="\$SC_OUT_DIR\/\.smoke-body\.\$\$"$//' \
    -e 's/register_cleanup "\$BODY_OUT"//g' \
    -e 's/json_field "\$BODY_OUT"//g' \
    -e 's/wc -c < "\$BODY_OUT"//g' \
    -e '/^CODE="\$\(curl /s/ -o "\$BODY_OUT"//' "$1" | grep -n 'BODY_OUT'
  # the literal path of the body file (its only legal line is the definition of BODY_OUT)
  grep -nF '.smoke-body' "$1" | grep -vE '^[0-9]+:BODY_OUT="\$SC_OUT_DIR/\.smoke-body\.\$\$"$'
}
STRAY="$(body_out_stray "$SMOKE")"
DEF_COUNT="$(grep -cxF 'BODY_OUT="$SC_OUT_DIR/.smoke-body.$$"' "$SMOKE")"
if [ -z "$STRAY" ] && [ "$DEF_COUNT" = "1" ] && grep -qF 'json_field "$BODY_OUT"' "$SMOKE" && grep -qF -- '-o "$BODY_OUT"' "$SMOKE" && grep -qF 'wc -c < "$BODY_OUT"' "$SMOKE"; then
  ok "6.smoke.static.p16-11.body-file-is-used-only-by-json-field-wc-and-curl-o"
else
  bad "6.smoke.static.p16-11.body-file-is-used-only-by-json-field-wc-and-curl-o" "other uses: ${STRAY:-none}; definition lines: $DEF_COUNT (must be exactly 1)"
fi
# Review 4 N3: the definition is anchored to its exact line, so a reassignment is refused.
{ cat "$SMOKE"; printf '%s\n' 'BODY_OUT="/dev/stdout"'; } > "$WORK/smoke-mutated-def.sh"
if [ "$(body_out_stray "$WORK/smoke-mutated-def.sh" | wc -l | tr -d ' ')" -ge 1 ]; then
  ok "6.smoke.static.p16-11.body-file-audit-refuses-a-reassignment-of-the-body-variable"
else
  bad "6.smoke.static.p16-11.body-file-audit-refuses-a-reassignment-of-the-body-variable" "the audit accepted BODY_OUT=\"/dev/stdout\""
fi
{ cat "$SMOKE"; printf '%s\n' 'cat "$BODY_OUT"' 'echo "${BODY_OUT}"' 'head -c 99 "$BODY_OUT"'; } > "$WORK/smoke-mutated.sh"
if [ "$(body_out_stray "$WORK/smoke-mutated.sh" | wc -l | tr -d ' ')" -ge 3 ]; then
  ok "6.smoke.static.p16-11.body-file-audit-refuses-cat-echo-and-head-of-it"
else
  bad "6.smoke.static.p16-11.body-file-audit-refuses-cat-echo-and-head-of-it" "the audit missed an added use of BODY_OUT"
fi
# Finding 5 (review 3): the literal path is audited too, and -o is allowed only on the curl line.
{ cat "$SMOKE"; printf '%s\n' 'cat "$SC_OUT_DIR/.smoke-body.$$"' 'tail -c 9 .smoke-body.1'; } > "$WORK/smoke-mutated-literal.sh"
if [ "$(body_out_stray "$WORK/smoke-mutated-literal.sh" | wc -l | tr -d ' ')" -ge 2 ]; then
  ok "6.smoke.static.p16-11.body-file-audit-refuses-a-read-through-the-literal-smoke-body-path"
else
  bad "6.smoke.static.p16-11.body-file-audit-refuses-a-read-through-the-literal-smoke-body-path" "the audit missed a read of .smoke-body"
fi
{ cat "$SMOKE"; printf '%s\n' 'cp -o "$BODY_OUT" /dev/stdout'; } > "$WORK/smoke-mutated-o.sh"
if [ "$(body_out_stray "$WORK/smoke-mutated-o.sh" | wc -l | tr -d ' ')" -ge 1 ]; then
  ok "6.smoke.static.p16-11.body-file-audit-allows-dash-o-only-on-the-curl-line"
else
  bad "6.smoke.static.p16-11.body-file-audit-allows-dash-o-only-on-the-curl-line" "the audit accepted -o \"\$BODY_OUT\" on a line that is not the curl call"
fi

# Static: the decision sits between the bounded query and the idempotency step, and the
# 200 check and the dry-run text are still there.
line_of() { grep -nF -- "$1" "$SMOKE" | head -n 1 | cut -d: -f1; }
line_x() { grep -nxF -- "$1" "$SMOKE" | head -n 1 | cut -d: -f1; }
L_STEP_BQ="$(line_x 'step bounded-query')"
L_200="$(line_of 'die "bounded query expected HTTP 200')"
L_ABS="$(line_of 'ABSTAINED="$(json_field')"
L_IDEM="$(line_x 'step idempotency')"
L_DONE="$(line_of "printf 'SMOKE_COMPLETE")"
if [ -n "$L_STEP_BQ" ] && [ -n "$L_200" ] && [ -n "$L_ABS" ] && [ -n "$L_IDEM" ] && [ -n "$L_DONE" ] \
  && [ "$L_STEP_BQ" -lt "$L_200" ] && [ "$L_200" -lt "$L_ABS" ] && [ "$L_ABS" -lt "$L_IDEM" ] && [ "$L_IDEM" -lt "$L_DONE" ]; then
  ok "6.smoke.static.order-http-200-then-answer-check-then-idempotency-then-complete"
else
  bad "6.smoke.static.order-http-200-then-answer-check-then-idempotency-then-complete" "lines: step=$L_STEP_BQ 200=$L_200 abstained=$L_ABS idem=$L_IDEM complete=$L_DONE"
fi
DRY="$(grep -F 'dry_would "read the query token' "$SMOKE")"
case "$DRY" in
  *"abstained=false"*"evidence"*"abstentionReason"*) ok "6.smoke.static.dry-run-text-names-abstained-evidence-and-the-reason" ;;
  *) bad "6.smoke.static.dry-run-text-names-abstained-evidence-and-the-reason" "WOULD_RUN text does not name abstained=false, evidence and abstentionReason" ;;
esac
case "$DRY" in
  *"expect 200"*) ok "6.smoke.static.dry-run-text-still-expects-http-200" ;;
  *) bad "6.smoke.static.dry-run-text-still-expects-http-200" "the 200 expectation is gone from the text" ;;
esac
grep -qF 'die "bounded query abstained' "$SMOKE" && ok "6.smoke.static.an-abstention-is-a-die-not-a-warning" || bad "6.smoke.static.an-abstention-is-a-die-not-a-warning" "no die on abstention"
if grep -nE 'abstained.*(\|\| true|\|\| :)' "$SMOKE" | grep -v 'ABSTENTION_REASON=' > /dev/null 2>&1; then
  bad "6.smoke.static.no-swallowed-abstained-check" "an abstained check is followed by || true"
else
  ok "6.smoke.static.no-swallowed-abstained-check"
fi

# Part 2: Python cases for WO-5.
printf '# part 2: python3 -I -B tests/scripts/p16/test_load_derive.py\n'
PYLOG="$WORK/py.log"
python3 -I -B "$HERE/p16/test_load_derive.py" > "$PYLOG" 2>&1
PYRC=$?
tail -n 5 "$PYLOG"
if [ "$PYRC" -eq 0 ]; then ok "5.python.test_load_derive.all-cases"; else bad "5.python.test_load_derive.all-cases" "exit $PYRC; see the FAIL lines above (full log: rerun the file alone)"; grep -E '^(FAIL|ERROR):' "$PYLOG" | head -n 30; fi

printf 'RESULT pass=%s fail=%s\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
