#!/usr/bin/env bash
# tests/scripts/hooks-guard.sh: hook command form, R03 guard refusals, the --list-categories interface, D3 ownership row.
#
#   bash tests/scripts/hooks-guard.sh
#
# Prints "CASE <id> PASS|FAIL <reason>" per case, then "RESULT pass=N fail=M"; exits 0 only when
# fail=0. Read-only: it never writes under the repository, runs only two hook entry points, the
# guard's decision function `policy.py guard` and `guard-dangerous.sh` itself (with
# `--list-categories`, and with a made-up payload on stdin), on made-up Bash payloads (nothing in
# those payloads is executed), and uses placeholder names only. Needs bash, python3 and git.
#
# Owner: test-engineer (tests/**). A failing case is a finding for the owner of the file under
# test (factory-engineer for .claude/** and docs/agents.md), not a case to loosen.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
HOOKS="$ROOT/.claude/hooks"
SETTINGS="$ROOT/.claude/settings.json"
GUARD_SH="$HOOKS/guard-dangerous.sh"
POLICY="$HOOKS/policy.py"
AGENTS_MD="$ROOT/docs/agents.md"
PASS=0
FAIL=0

for f in "$SETTINGS" "$GUARD_SH" "$POLICY" "$AGENTS_MD"; do
  [ -f "$f" ] || { printf 'PREREQ missing %s\n' "$f" >&2; exit 2; }
done
command -v python3 >/dev/null || { printf 'PREREQ python3\n' >&2; exit 2; }

TMP="$(mktemp -d "${TMPDIR:-/tmp}/hooks-guard.XXXXXX")" || exit 2
trap 'rm -rf "$TMP"' EXIT

ok() { PASS=$((PASS + 1)); printf 'CASE %s PASS\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf 'CASE %s FAIL %s\n' "$1" "$2"; }
check() { # id, condition-result (0 pass), reason
  if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1" "$3"; fi
}

### Hook command form (F05) ------------------------------------------------------------------

# One line per hook: <event> <TAB> <command>, read from settings.json.
python3 -I - "$SETTINGS" > "$TMP/hooks.tsv" <<'PYEOF'
import json, sys
doc = json.load(open(sys.argv[1]))
for event, groups in doc["hooks"].items():
    for group in groups:
        for hook in group["hooks"]:
            print(event + "\t" + hook["command"])
PYEOF
check "1.hooks.settings-json-parses-and-lists-hooks" "$([ -s "$TMP/hooks.tsv" ]; echo $?)" "no hook command read from settings.json"

N="$(wc -l < "$TMP/hooks.tsv" | tr -d ' ')"
check "1.hooks.exactly-four-hook-commands" "$([ "$N" = "4" ]; echo $?)" "found $N hook commands, expected 4"

PROJ="$TMP/proj dir"
mkdir -p "$PROJ"
ln -s "$ROOT/.claude" "$PROJ/.claude"

while IFS="$(printf '\t')" read -r event command; do
  name="$(printf '%s' "$command" | sed -n 's|.*/hooks/\([A-Za-z0-9_-]*\)\.sh$|\1|p')"
  id="1.hooks.$event.${name:-unparsed}"
  if [ -z "$name" ]; then
    bad "$id.command-ends-with-hook-sh" "command does not end with /hooks/<name>.sh: $command"
    continue
  fi
  case "$command" in
    'bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/'*) ok "$id.uses-the-unquoted-tail-form" ;;
    *) bad "$id.uses-the-unquoted-tail-form" "command is not bash \"\$CLAUDE_PROJECT_DIR\"/.claude/hooks/<name>.sh: $command" ;;
  esac
  last="${command##* }"
  case "$last" in
    *'"') bad "$id.path-token-has-no-trailing-quote" "last token ends with a quote: $last" ;;
    *) ok "$id.path-token-has-no-trailing-quote" ;;
  esac
  case "$command" in
    *'$CLAUDE_PROJECT_DIR/.claude'*) bad "$id.old-quoted-whole-path-form-is-gone" "old form still present: $command" ;;
    *) ok "$id.old-quoted-whole-path-form-is-gone" ;;
  esac
  # Resolve the path words the way the shell does, with a project directory that holds a space.
  resolved="$(CLAUDE_PROJECT_DIR="$PROJ" bash -c "printf '%s\n' ${command#bash }" 2>/dev/null)"
  check "$id.resolves-to-one-word-under-a-project-dir-with-a-space" \
    "$([ "$resolved" = "$PROJ/.claude/hooks/$name.sh" ]; echo $?)" "resolved to: $resolved"
  check "$id.resolved-file-exists" "$([ -f "$PROJ/.claude/hooks/$name.sh" ]; echo $?)" "missing $PROJ/.claude/hooks/$name.sh"
  check "$id.resolved-file-is-executable" "$([ -x "$HOOKS/$name.sh" ]; echo $?)" "execute bit not set on $name.sh"
  bash -n "$HOOKS/$name.sh" 2>"$TMP/syntax.err"
  check "$id.resolved-file-passes-bash-n" "$?" "bash -n failed: $(head -n 1 "$TMP/syntax.err")"
done < "$TMP/hooks.tsv"

for f in guard-dangerous format-scala verify-tests-ran; do
  mode="$(git -C "$ROOT" ls-files -s -- ".claude/hooks/$f.sh" | cut -c1-6)"
  check "1.hooks.index-mode-100755.$f" "$([ "$mode" = "100755" ]; echo $?)" "index mode is '$mode'"
done

### R03 guard header ---------------------------------------------------------------------------

check "2.header.guard-dangerous-is-executable" "$([ -x "$GUARD_SH" ]; echo $?)" "execute bit not set"
bash -n "$GUARD_SH" 2>"$TMP/syntax.err"
check "2.header.guard-dangerous-passes-bash-n" "$?" "bash -n failed: $(head -n 1 "$TMP/syntax.err")"

total="$(wc -l < "$GUARD_SH" | tr -d ' ')"
grep -qxF -- 'set -euo pipefail' "$GUARD_SH"
check "2.header.set-euo-pipefail-line-present" "$?" "line 'set -euo pipefail' not found"
grep -qxF -- 'command -v python3 >/dev/null || { echo "ScholarChain guard requires Python 3." >&2; exit 2; }' "$GUARD_SH"
check "2.header.python-check-line-present" "$?" "python3 check line not found unchanged"
last="$(sed -n "${total}p" "$GUARD_SH")"
check "2.header.last-line-is-the-exec-of-policy-guard" \
  "$([ "$last" = 'exec python3 -B "$(dirname "$0")/policy.py" guard' ]; echo $?)" "got: $last"
check "2.header.shebang-is-first-line" "$([ "$(head -n 1 "$GUARD_SH")" = '#!/usr/bin/env bash' ]; echo $?)" "first line changed"


### R03 refusals through policy.py guard (one or more per category) --------------------------------

# guard_case <id> <expect: refuse|allow> <command text>. The payload is made up and never executed.
guard_case() {
  local id="$1" expect="$2" cmd="$3" agent="${4:-test-engineer}" rc
  python3 -I -c '
import json, sys
print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}, "cwd": sys.argv[2], "agent_type": sys.argv[3]}))
' "$cmd" "$ROOT" "$agent" | python3 -B "$POLICY" guard >"$TMP/guard.out" 2>"$TMP/guard.err"
  rc=$?
  case "$expect" in
    refuse)
      if [ "$rc" -eq 2 ] && grep -q '^ScholarChain policy:' "$TMP/guard.err"; then ok "$id"; else bad "$id" "expected exit 2 with a policy message, got exit $rc: $(head -c 200 "$TMP/guard.err")"; fi ;;
    allow)
      if [ "$rc" -eq 0 ]; then ok "$id"; else bad "$id" "expected exit 0, got exit $rc: $(head -c 200 "$TMP/guard.err")"; fi ;;
  esac
}

# 1. AWS delete and terminate
guard_case "3.r03.aws-delete-terminate.ec2-terminate-instances" refuse 'aws ec2 terminate-instances --instance-ids i-example'
guard_case "3.r03.aws-delete-terminate.emr-terminate-clusters" refuse 'aws emr terminate-clusters --cluster-ids j-example'
guard_case "3.r03.aws-delete-terminate.ecr-delete-repository" refuse 'aws ecr delete-repository --repository-name example-placeholder'
guard_case "3.r03.aws-delete-terminate.kms-schedule-key-deletion" refuse 'aws kms schedule-key-deletion --key-id example-placeholder'
# 2. Terraform
guard_case "3.r03.terraform.apply" refuse 'terraform apply'
guard_case "3.r03.terraform.chdir-destroy" refuse 'terraform -chdir=infra/example destroy'
guard_case "3.r03.terraform.tofu-apply" refuse 'tofu apply'
guard_case "3.r03.terraform.tofu-destroy" refuse 'tofu destroy'
# 3. Git
guard_case "3.r03.git.push-force" refuse 'git push --force'
guard_case "3.r03.git.push-short-f" refuse 'git push -f origin main'
guard_case "3.r03.git.push-plus-refspec" refuse 'git push origin +main'
guard_case "3.r03.git.push-mirror" refuse 'git push --mirror'
guard_case "3.r03.git.push-delete" refuse 'git push origin --delete example-branch'
guard_case "3.r03.git.push-colon-ref" refuse 'git push origin :example-branch'
guard_case "3.r03.git.branch-D" refuse 'git branch -D example-branch'
guard_case "3.r03.git.reset-hard" refuse 'git reset --hard'
guard_case "3.r03.git.rebase" refuse 'git rebase main'
# 4. Database
guard_case "3.r03.database.drop-database" refuse 'psql -c "DROP DATABASE example_db"'
guard_case "3.r03.database.drop-table-lower-case" refuse 'psql -c "drop table example_table"'
guard_case "3.r03.database.truncate-table" refuse 'psql -c "TRUNCATE TABLE example_table"'
guard_case "3.r03.database.delete-from" refuse 'psql -c "DELETE FROM example_table"'
# 5. S3
guard_case "3.r03.s3.rm" refuse 'aws s3 rm s3://example-placeholder/x'
guard_case "3.r03.s3.rb" refuse 'aws s3 rb s3://example-placeholder'
guard_case "3.r03.s3.api-delete-object" refuse 'aws s3api delete-object --bucket example-placeholder --key x'
guard_case "3.r03.s3.sync-delete" refuse 'aws s3 sync ./example s3://example-placeholder/x --delete'
# 6. Neptune
guard_case "3.r03.neptune.delete-db-cluster" refuse 'aws neptune delete-db-cluster --db-cluster-identifier example-placeholder'
guard_case "3.r03.neptune.neptunedata-delete" refuse 'aws neptunedata delete-sparql-statistics --endpoint-url https://example.invalid'
guard_case "3.r03.neptune.neptunedata-cancel" refuse 'aws neptunedata cancel-gremlin-query --query-id example-placeholder'
# 7. DynamoDB
guard_case "3.r03.dynamodb.delete-table" refuse 'aws dynamodb delete-table --table-name example-placeholder'
guard_case "3.r03.dynamodb.delete-item" refuse 'aws dynamodb delete-item --table-name example-placeholder --key x'
# 8. Vector index
guard_case "3.r03.vector-index.delete-index" refuse 'aws s3vectors delete-index --vector-bucket-name example-placeholder --index-name x'
guard_case "3.r03.vector-index.delete-vector-bucket" refuse 'aws s3vectors delete-vector-bucket --vector-bucket-name example-placeholder'
guard_case "3.r03.vector-index.delete-vectors" refuse 'aws s3vectors delete-vectors --index-name x'

# Allowed forms stay allowed (the header says so).
# The three-token aws metadata forms pass only for infra-engineer (policy.py:152); every other role
# is refused at the final fall-through (policy.py:158).
guard_case "4.allowed.infra-aws-s3-ls" allow 'aws s3 ls' infra-engineer
guard_case "4.allowed.infra-aws-neptune-describe-db-clusters" allow 'aws neptune describe-db-clusters' infra-engineer
guard_case "4.allowed.infra-aws-dynamodb-list-tables" allow 'aws dynamodb list-tables' infra-engineer
guard_case "4.allowed.infra-aws-sts-get-caller-identity" allow 'aws sts get-caller-identity' infra-engineer
guard_case "4.non-infra-refused.test-engineer-aws-s3-ls" refuse 'aws s3 ls' test-engineer
guard_case "4.non-infra-refused.test-engineer-aws-neptune-describe-db-clusters" refuse 'aws neptune describe-db-clusters' test-engineer
guard_case "4.non-infra-refused.documentation-engineer-aws-s3-ls" refuse 'aws s3 ls' documentation-engineer
guard_case "4.infra-still-refused.aws-s3-rm" refuse 'aws s3 rm s3://example-placeholder/x' infra-engineer
guard_case "4.infra-still-refused.aws-secretsmanager-list" refuse 'aws secretsmanager list-secrets' infra-engineer
guard_case "4.infra-still-refused.terraform-apply" refuse 'terraform apply' infra-engineer
guard_case "4.allowed.git-status" allow 'git status'
guard_case "4.allowed.git-diff-stat" allow 'git diff --stat'
guard_case "4.allowed.git-log-oneline" allow 'git log --oneline'

### Interface: guard-dangerous.sh --list-categories ----------------------------------------------

IDS='aws-delete-terminate terraform git database s3 neptune dynamodb vector-index'
TAB="$(printf '\t')"

bash "$GUARD_SH" --list-categories </dev/null >"$TMP/cats.out" 2>"$TMP/cats.err"
check "6.list.exits-0" "$?" "--list-categories did not exit 0: $(head -c 200 "$TMP/cats.err")"
bash "$GUARD_SH" --list-categories <&- >/dev/null 2>&1
check "6.list.exits-0-with-stdin-closed" "$?" "--list-categories needs stdin"
check "6.list.stderr-is-empty" "$([ ! -s "$TMP/cats.err" ]; echo $?)" "stderr: $(head -c 200 "$TMP/cats.err")"

bad_lines="$(awk -F'\t' 'NF != 2 || $1 == "" || $2 == ""' "$TMP/cats.out" | wc -l | tr -d ' ')"
nlines="$(wc -l < "$TMP/cats.out" | tr -d ' ')"
check "6.list.every-line-is-id-tab-example" "$([ "$bad_lines" = "0" ] && [ "$nlines" -gt 0 ]; echo $?)" "$bad_lines malformed lines of $nlines"

order="$(cut -f1 "$TMP/cats.out" | uniq | tr '\n' ' ' | sed 's/ $//')"
check "6.list.ids-are-exactly-the-eight-in-order" "$([ "$order" = "$IDS" ]; echo $?)" "ids: $order"

for id in $IDS; do
  n="$(cut -f1 "$TMP/cats.out" | grep -cxF -- "$id")"
  check "6.list.at-least-two-examples.$id" "$([ "$n" -ge 2 ]; echo $?)" "$n examples"
done

leak="$(cut -f2 "$TMP/cats.out" | grep -E '[0-9]{12}|arn:|AKIA|amazonaws\.com' | head -n 1)"
bucket="$(cut -f2 "$TMP/cats.out" | grep -oE 's3://[^/ ]+' | grep -vE '^s3://(example|x$)' | head -n 1)"
check "6.list.examples-use-placeholders-only" "$([ -z "$leak" ] && [ -z "$bucket" ]; echo $?)" "suspect text: $leak $bucket"

# Each example must be refused by the decision function for test-engineer.
refused() { # command -> 0 when policy.py guard exits 2 with a policy message
  python3 -I -c '
import json, sys
print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}, "cwd": sys.argv[2], "agent_type": "test-engineer"}))
' "$1" "$ROOT" | python3 -B "$POLICY" guard >"$TMP/guard.out" 2>"$TMP/guard.err"
  [ "$?" -eq 2 ] && grep -q '^ScholarChain policy:' "$TMP/guard.err"
}
for id in $IDS; do
  misses=""
  while IFS="$TAB" read -r cid ex; do
    [ "$cid" = "$id" ] || continue
    refused "$ex" || misses="$misses [$ex]"
  done < "$TMP/cats.out"
  check "6.list.every-example-refused.$id" "$([ -z "$misses" ]; echo $?)" "not refused:$misses"
done

### Hook payload through guard-dangerous.sh itself (no arguments, JSON on stdin) -------------------

hook_case() { # id, expect refuse|allow, command
  local rc
  python3 -I -c '
import json, sys
print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}, "cwd": sys.argv[2], "agent_type": "test-engineer"}))
' "$3" "$ROOT" | bash "$GUARD_SH" >"$TMP/hook.out" 2>"$TMP/hook.err"
  rc=$?
  if [ "$2" = refuse ]; then
    if [ "$rc" -eq 2 ] && grep -q '^ScholarChain policy:' "$TMP/hook.err"; then ok "$1"; else bad "$1" "expected exit 2 with a policy message, got $rc: $(head -c 200 "$TMP/hook.err")"; fi
  else
    if [ "$rc" -eq 0 ]; then ok "$1"; else bad "$1" "expected exit 0, got $rc: $(head -c 200 "$TMP/hook.err")"; fi
  fi
}
hook_case "7.hook-payload.terraform-apply-refused" refuse 'terraform apply'
hook_case "7.hook-payload.s3-rm-refused" refuse 'aws s3 rm s3://example-placeholder/x'
hook_case "7.hook-payload.git-push-force-refused" refuse 'git push --force'
hook_case "7.hook-payload.git-status-allowed" allow 'git status'

### D3: reports/** has exactly one ownership row ---------------------------------------------

rows="$(grep -c '^|.*reports/\*\*' "$AGENTS_MD")"
check "5.d3.reports-appears-in-exactly-one-matrix-row" "$([ "$rows" = "1" ]; echo $?)" "found $rows rows naming reports/**"
row="$(grep '^|.*reports/\*\*' "$AGENTS_MD" | head -n 1)"
case "$row" in
  '| `documentation-engineer` |'*) ok "5.d3.the-row-belongs-to-documentation-engineer" ;;
  *) bad "5.d3.the-row-belongs-to-documentation-engineer" "row starts: $(printf '%s' "$row" | cut -c1-60)" ;;
esac
owned="$(printf '%s' "$row" | cut -d'|' -f3)"
case "$owned" in
  *'README.md'*'reports/**'*'docs/**'*) ok "5.d3.README-reports-docs-all-in-the-owned-outputs-column" ;;
  *) bad "5.d3.README-reports-docs-all-in-the-owned-outputs-column" "owned column: $owned" ;;
esac

printf 'RESULT pass=%d fail=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
