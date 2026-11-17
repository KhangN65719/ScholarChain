#!/usr/bin/env bash
# Rehearsal of experiments/check-manifest-json.sh. No AWS, no network. It works on COPIES of the
# manifests in a temporary directory, changes one thing at a time and checks that the check
# script notices. Exit 0 and "REHEARSAL pass=<n> fail=0" mean the check behaves as specified.
#
# Usage: bash experiments/rehearse-manifest-json.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/check-manifest-json.sh"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sc-manifest-json.XXXXXX")"
PASS=0
FAIL=0

ok() {
  PASS=$((PASS + 1))
  printf 'PASS %s\n' "$1"
}

bad() {
  FAIL=$((FAIL + 1))
  printf 'FAIL %s\n' "$1"
}

# fresh NAME: new copies of the six manifests and their JSON copies under $TMP_ROOT/NAME.
fresh() {
  mkdir -p "$TMP_ROOT/$1/manifests" "$TMP_ROOT/$1/json"
  cp "$HERE"/manifests/*.manifest "$TMP_ROOT/$1/manifests/"
  cp "$HERE"/manifests/json/*.json "$TMP_ROOT/$1/json/"
}

# run_check NAME: run the check script on $TMP_ROOT/NAME, output to NAME.out, status to RC.
RC=0
run_check() {
  RC=0
  bash "$CHECK" --manifests-dir "$TMP_ROOT/$1/manifests" --json-dir "$TMP_ROOT/$1/json" > "$TMP_ROOT/$1.out" 2>&1 || RC=$?
}

expect_fail() {
  local name="$1" pattern="$2"
  if [[ "$RC" -ne 0 ]] && grep -q -F -- "$pattern" "$TMP_ROOT/$name.out"; then
    ok "$name fails and says: $pattern"
  else
    bad "$name (exit $RC, expected failure with: $pattern)"
    cat "$TMP_ROOT/$name.out"
  fi
}

# 1: the committed pairs match
RC=0
bash "$CHECK" > "$TMP_ROOT/real.out" 2>&1 || RC=$?
if [[ "$RC" -eq 0 ]] && grep -q -F 'MANIFEST_JSON_CHECK OK manifests=6' "$TMP_ROOT/real.out"; then
  ok "committed manifests and JSON copies match (6 pairs)"
else
  bad "committed manifests and JSON copies (exit $RC)"
  cat "$TMP_ROOT/real.out"
fi

# 2: an untouched copy passes
fresh clean
run_check clean
if [[ "$RC" -eq 0 ]] && grep -q -F 'MANIFEST_JSON_CHECK OK manifests=6' "$TMP_ROOT/clean.out"; then
  ok "untouched copy passes"
else
  bad "untouched copy (exit $RC)"
  cat "$TMP_ROOT/clean.out"
fi

# 3: a number in the KEY=VALUE file changed
fresh edit-number
sed 's/^MAX_MINUTES=90$/MAX_MINUTES=91/' "$TMP_ROOT/edit-number/manifests/experiment-b-burst.manifest" > "$TMP_ROOT/edit-number/b.tmp"
mv "$TMP_ROOT/edit-number/b.tmp" "$TMP_ROOT/edit-number/manifests/experiment-b-burst.manifest"
run_check edit-number
expect_fail edit-number 'key MAX_MINUTES differs'

# 4: a number in the JSON copy changed
fresh edit-json
sed 's/"SPEND_CEILING_USD": "2.40"/"SPEND_CEILING_USD": "9.99"/' "$TMP_ROOT/edit-json/json/experiment-b-burst.json" > "$TMP_ROOT/edit-json/b.tmp"
mv "$TMP_ROOT/edit-json/b.tmp" "$TMP_ROOT/edit-json/json/experiment-b-burst.json"
run_check edit-json
expect_fail edit-json 'key SPEND_CEILING_USD differs'

# 5: a list value in the KEY=VALUE file changed (one more metric)
fresh edit-list
sed 's/^METRIC_NAMESPACES=AWS\/States,AWS\/ElasticMapReduce$/METRIC_NAMESPACES=AWS\/States,AWS\/ElasticMapReduce,AWS\/EC2/' "$TMP_ROOT/edit-list/manifests/experiment-a-baseline.manifest" > "$TMP_ROOT/edit-list/a.tmp"
mv "$TMP_ROOT/edit-list/a.tmp" "$TMP_ROOT/edit-list/manifests/experiment-a-baseline.manifest"
run_check edit-list
expect_fail edit-list 'key METRIC_NAMESPACES differs'

# 6: a JSON copy is missing
fresh missing-json
rm "$TMP_ROOT/missing-json/json/experiment-c-embedding-backlog.json"
run_check missing-json
expect_fail missing-json 'no JSON copy'

# 7: a JSON copy with no manifest
fresh orphan
cp "$TMP_ROOT/orphan/json/experiment-f-failure-recovery.json" "$TMP_ROOT/orphan/json/experiment-g-extra.json"
run_check orphan
expect_fail orphan 'orphan JSON copy'

# 8: a key added to the KEY=VALUE file only
fresh new-key
printf 'NEW_KEY=1\n' >> "$TMP_ROOT/new-key/manifests/experiment-e-retrieval-modes.manifest"
run_check new-key
expect_fail new-key 'key NEW_KEY is missing from the JSON'

# 9: --generate repairs a mismatch, and the repaired pair passes
fresh regenerate
sed 's/^MAX_MINUTES=90$/MAX_MINUTES=91/' "$TMP_ROOT/regenerate/manifests/experiment-b-burst.manifest" > "$TMP_ROOT/regenerate/b.tmp"
mv "$TMP_ROOT/regenerate/b.tmp" "$TMP_ROOT/regenerate/manifests/experiment-b-burst.manifest"
RC=0
bash "$CHECK" --generate --manifests-dir "$TMP_ROOT/regenerate/manifests" --json-dir "$TMP_ROOT/regenerate/json" > "$TMP_ROOT/regenerate-gen.out" 2>&1 || RC=$?
run_check regenerate
if [[ "$RC" -eq 0 ]] && grep -q -F '"MAX_MINUTES": "91"' "$TMP_ROOT/regenerate/json/experiment-b-burst.json"; then
  ok "--generate rewrites the JSON copy and the pair matches again"
else
  bad "--generate round trip (exit $RC)"
  cat "$TMP_ROOT/regenerate.out"
fi

# 10: a duplicate key in the KEY=VALUE file is refused
fresh duplicate
printf 'MAX_TASKS=2\n' >> "$TMP_ROOT/duplicate/manifests/experiment-d-query-load.manifest"
run_check duplicate
expect_fail duplicate 'duplicate key MAX_TASKS'

printf 'OUTPUT_DIR=%s\n' "$TMP_ROOT"
printf 'REHEARSAL pass=%s fail=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
