#!/usr/bin/env bash
# check.sh: the local gates, in one command. No AWS, no credentials, no approval
# record. Reads the repository, runs sbt once, and runs Terraform only for fmt
# -check, init -backend=false and validate. It never plans, applies or destroys.
#
# Usage (from a clean checkout, nothing else is needed but the prerequisites):
#   bash scripts/check.sh
#   bash scripts/check.sh --dry-run
#   bash scripts/check.sh [--keep-going] [--sbt check|quick] [--skip-sbt]
#                         [--skip-terraform] [--allow-missing-shellcheck]
#
# Prerequisites: bash 3.2 or later, git, python3 (already a project prerequisite),
# the shellcheck program, JDK 21 and sbt (the first sbt run needs the public
# Maven repositories), terraform (the AWS provider download needs registry access).
#
# Gates, in this order. Each prints STEP[n] name: start, then ok, FAIL or skip.
#   1 constitution-json  .claude/hooks/validate-agent-files.sh if it exists (read
#                        only), then every *.json under .claude/ and .mcp.json parsed
#                        with python3 -I
#   2 hook-syntax        bash -n on .claude/hooks/*.sh; compile() with no output file
#                        on .claude/hooks/*.py (no __pycache__ is written)
#   3 shell-syntax       bash -n on scripts/*.sh
#   4 shellcheck         shellcheck -x on scripts/*.sh. When shellcheck is absent it
#                        prints SHELLCHECK=absent and the gate FAILS, unless
#                        --allow-missing-shellcheck is given (then it is skipped
#                        and said so)
#   5 sbt                one sbt process: "sbt check" (scalafmt check, sbt
#                        Test/compile, sbt test, then the marker) or, with --sbt
#                        quick, "sbt quick" (the same but without the slowSuites).
#                        The contract and schema tests run inside it; the mapping
#                        is printed. --skip-sbt skips it, and the summary says so
#   6 terraform          terraform fmt -check -recursive infra/terraform, then init
#                        -backend=false -input=false and validate in
#                        infra/terraform/environments/course. --skip-terraform
#                        skips it
# The default is to stop at the first failed gate. --keep-going runs the rest. The
# exit code is nonzero when any gate failed. The last line is
# CHECK_RESULT=PASS|PASS_WITH_SKIPS|FAIL. PASS means every gate ran and passed. With
# any skipped gate the result is PASS_WITH_SKIPS (exit 0 when nothing failed), and the
# skipped gates are listed above it and on the same line.
# --dry-run prints the gate list and what each gate would run, and runs nothing.
# Exit codes are in scripts/README.md.
SC_SCRIPT="${BASH_SOURCE[0]}"
# shellcheck source=scripts/lib.sh
source "$(dirname "$SC_SCRIPT")/lib.sh"

KEEP_GOING=0
ALLOW_NO_SHELLCHECK=0
SBT_MODE="check"
SKIP_SBT=0
SKIP_TF=0
GATES="constitution-json hook-syntax shell-syntax shellcheck sbt terraform"
RESULTS=""
SKIPPED=""
FAILED=0
STOPPED=0
SBT_RAN="no"
TF_ENV_DIR="$SC_TF_ROOT/environments/course"

JSON_PY='import json, os, sys
root = sys.argv[1]
paths = []
for d, _, names in os.walk(os.path.join(root, ".claude")):
    for n in names:
        if n.endswith(".json"):
            paths.append(os.path.join(d, n))
mcp = os.path.join(root, ".mcp.json")
if os.path.isfile(mcp):
    paths.append(mcp)
bad = 0
for p in sorted(paths):
    rel = os.path.relpath(p, root)
    try:
        with open(p, "rb") as f:
            json.loads(f.read().decode("utf-8"))
    except Exception as e:
        bad += 1
        print("INVALID_JSON %s (%s)" % (rel, type(e).__name__), file=sys.stderr)
    else:
        print("JSON_OK %s" % rel)
print("JSON_FILES=%d INVALID=%d" % (len(paths), bad))
sys.exit(1 if bad else 0)'

COMPILE_PY='import sys
for p in sys.argv[1:]:
    with open(p, "rb") as f:
        compile(f.read(), p, "exec")'

usage() {
  print_header
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-going) KEEP_GOING=1; shift ;;
    --allow-missing-shellcheck) ALLOW_NO_SHELLCHECK=1; shift ;;
    --sbt) need_arg "$@"; SBT_MODE="$2"; shift 2 ;;
    --skip-sbt) SKIP_SBT=1; shift ;;
    --skip-terraform) SKIP_TF=1; shift ;;
    --dry-run) SC_DRY_RUN=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
dry_announce

case "$SBT_MODE" in
  check | quick) ;;
  *) die "--sbt must be check or quick, got: $SBT_MODE" ;;
esac

### Gates --------------------------------------------------------------------
# Each gate runs in a subshell with errexit on and stops at its own first
# failing command, or collects failures and dies once at the end. Output
# names files and error classes only, never file contents.

print_contract_mapping() {
  printf 'CONTRACT_AND_SCHEMA_TESTS (they run inside the sbt command below; there is no separate runner):\n'
  printf '  core contracts, config, schema and serialization: modules/core/src/test/scala/scholarchain/core/{ContractSuite,ConfigSuite,MatchCandidateSuite,MentionCoauthorSuite}.scala\n'
  printf '  frozen wire examples of the REST contract: tests/contracts/Phase10WireSuite.scala\n'
  printf '  request validation of the services: modules/services/src/test/scala/scholarchain/services/{QueryRequestSuite,GraphRequestSuite}.scala\n'
  printf '  independent API contract checks: tests/adversarial/Phase10ApiContractSuite.scala\n'
  printf '  none of these is in slowSuites (build.sbt), so both "sbt check" and "sbt quick" run them. The list was read from the tree and is not generated: a new contract suite is covered by sbt without a change here.\n'
}

gate_constitution_json() {
  local v="$SC_REPO_ROOT/.claude/hooks/validate-agent-files.sh"
  type -P python3 >/dev/null 2>&1 || die "python3 is required"
  if [[ -f "$v" ]]; then
    printf 'RUN: bash .claude/hooks/validate-agent-files.sh\n'
    bash "$v" || die "the agent constitution validator failed"
  else
    note ".claude/hooks/validate-agent-files.sh does not exist: the constitution validator was not run"
  fi
  printf 'RUN: python3 -I -B -c <json parse of .claude/**/*.json and .mcp.json>\n'
  python3 -I -B -c "$JSON_PY" "$SC_REPO_ROOT" || die "a JSON file under .claude/ or .mcp.json does not parse"
}

gate_hook_syntax() {
  local hooks="$SC_REPO_ROOT/.claude/hooks" f n=0 bad=0
  [[ -d "$hooks" ]] || die "no hooks directory at $hooks"
  for f in "$hooks"/*.sh; do
    [[ -e "$f" ]] || continue
    n=$((n + 1))
    if bash -n "$f"; then
      printf 'HOOK_SH_OK %s\n' "${f#"$SC_REPO_ROOT"/}"
    else
      printf 'EVIDENCE: bash -n failed on %s\n' "${f#"$SC_REPO_ROOT"/}" >&2
      bad=1
    fi
  done
  for f in "$hooks"/*.py; do
    [[ -e "$f" ]] || continue
    n=$((n + 1))
    if python3 -I -B -c "$COMPILE_PY" "$f"; then
      printf 'HOOK_PY_OK %s\n' "${f#"$SC_REPO_ROOT"/}"
    else
      printf 'EVIDENCE: compile failed on %s\n' "${f#"$SC_REPO_ROOT"/}" >&2
      bad=1
    fi
  done
  [[ "$n" -gt 0 ]] || die "found no hook scripts under $hooks"
  printf 'HOOK_FILES=%s\n' "$n"
  [[ "$bad" -eq 0 ]] || die "a hook script does not parse"
}

gate_shell_syntax() {
  local f n=0 bad=0
  for f in "$SC_REPO_ROOT"/scripts/*.sh; do
    [[ -e "$f" ]] || continue
    n=$((n + 1))
    if bash -n "$f"; then
      printf 'SYNTAX_OK scripts/%s\n' "${f##*/}"
    else
      printf 'EVIDENCE: bash -n failed on scripts/%s\n' "${f##*/}" >&2
      bad=1
    fi
  done
  [[ "$n" -gt 0 ]] || die "found no scripts under scripts/"
  printf 'SCRIPT_FILES=%s\n' "$n"
  [[ "$bad" -eq 0 ]] || die "a script does not parse"
}

gate_shellcheck() {
  local f n=0 bad=0
  type -P shellcheck >/dev/null 2>&1 || die "SHELLCHECK=absent: install shellcheck, or pass --allow-missing-shellcheck to skip this gate (the scripts are then not linted)"
  for f in "$SC_REPO_ROOT"/scripts/*.sh; do
    [[ -e "$f" ]] || continue
    n=$((n + 1))
    if (cd "$SC_REPO_ROOT" && shellcheck -x "scripts/${f##*/}"); then
      printf 'SHELLCHECK_OK scripts/%s\n' "${f##*/}"
    else
      printf 'EVIDENCE: shellcheck reported findings on scripts/%s\n' "${f##*/}" >&2
      bad=1
    fi
  done
  [[ "$n" -gt 0 ]] || die "found no scripts under scripts/"
  [[ "$bad" -eq 0 ]] || die "shellcheck reported findings"
}

gate_sbt() {
  local rc=0
  print_contract_mapping
  printf 'RUN: sbt %s (one sbt process, 3600 s limit; covers scalafmt check, sbt Test/compile, tests and the marker)\n' "$SBT_MODE"
  if [[ "$SBT_MODE" == "quick" ]]; then
    note "sbt quick skips the slowSuites named in build.sbt; its marker means the quick set passed, not every test"
  fi
  sbt_run 3600 "$SBT_MODE" || rc=$?
  [[ "$rc" -ne 124 ]] || die "sbt $SBT_MODE exceeded its 3600 second limit and was stopped"
  [[ "$rc" -eq 0 ]] || die "sbt $SBT_MODE failed with exit $rc"
}

gate_terraform() {
  type -P terraform >/dev/null 2>&1 || die "terraform is not installed"
  [[ -d "$TF_ENV_DIR" ]] || die "no Terraform environment directory at $TF_ENV_DIR"
  printf 'RUN: terraform fmt -check -recursive -no-color infra/terraform\n'
  terraform fmt -check -recursive -no-color "$SC_TF_ROOT" || die "terraform fmt -check found unformatted files"
  printf 'RUN: terraform -chdir=infra/terraform/environments/course init -backend=false -input=false\n'
  terraform -chdir="$TF_ENV_DIR" init -backend=false -input=false -no-color || die "terraform init -backend=false failed"
  printf 'RUN: terraform -chdir=infra/terraform/environments/course validate\n'
  terraform -chdir="$TF_ENV_DIR" validate -no-color || die "terraform validate failed"
}

### Runner -------------------------------------------------------------------

record() {
  RESULTS="${RESULTS}GATE $1=$2"$'\n'
}

gate_body() {
  case "$1" in
    constitution-json) gate_constitution_json ;;
    hook-syntax) gate_hook_syntax ;;
    shell-syntax) gate_shell_syntax ;;
    shellcheck) gate_shellcheck ;;
    sbt) gate_sbt ;;
    terraform) gate_terraform ;;
    *) die "internal error: unknown gate $1" ;;
  esac
}

run_one() {
  local g="$1" reason="" rc=0
  step "$g"
  case "$g" in
    shellcheck)
      if ! type -P shellcheck >/dev/null 2>&1; then
        printf 'SHELLCHECK=absent\n'
        if [[ "$ALLOW_NO_SHELLCHECK" -eq 1 ]]; then
          reason="shellcheck is not installed and --allow-missing-shellcheck was given, so the scripts were NOT linted"
        fi
      fi ;;
    sbt)
      if [[ "$SKIP_SBT" -eq 1 ]]; then
        reason="--skip-sbt: sbt did not run, so formatting, compilation and tests were NOT checked"
      fi ;;
    terraform)
      if [[ "$SKIP_TF" -eq 1 ]]; then
        reason="--skip-terraform: fmt -check, init -backend=false and validate did NOT run"
      fi ;;
  esac
  if [[ -n "$reason" ]]; then
    step_skip "$reason"
    record "$g" "SKIP"
    SKIPPED="$SKIPPED $g"
    return 0
  fi
  set +e
  ( trap - EXIT; set -e; gate_body "$g" )
  rc=$?
  set -e
  if [[ "$g" == "sbt" ]]; then
    SBT_RAN="attempted"
  fi
  if [[ "$rc" -eq 0 ]]; then
    step_ok
    record "$g" "PASS"
    if [[ "$g" == "sbt" ]]; then
      SBT_RAN="yes ($SBT_MODE)"
    fi
  else
    printf 'STEP[%s] %s: FAIL (exit %s)\n' "$SC_STEP_N" "$g" "$rc"
    SC_STEP_OPEN=0
    FAILED=1
    record "$g" "FAIL"
    if [[ "$KEEP_GOING" -eq 0 ]]; then
      STOPPED=1
    fi
  fi
}

describe_gate() {
  case "$1" in
    constitution-json) dry_would "bash .claude/hooks/validate-agent-files.sh (if it exists); python3 -I -B -c <parse every .claude/**/*.json and .mcp.json>" ;;
    hook-syntax) dry_would "bash -n on each .claude/hooks/*.sh; python3 -I -B compile() with no output file on each .claude/hooks/*.py" ;;
    shell-syntax) dry_would "bash -n on each scripts/*.sh" ;;
    shellcheck)
      if type -P shellcheck >/dev/null 2>&1; then
        dry_would "shellcheck -x on each scripts/*.sh (shellcheck is installed)"
      elif [[ "$ALLOW_NO_SHELLCHECK" -eq 1 ]]; then
        dry_would "SKIP: shellcheck is absent and --allow-missing-shellcheck was given"
      else
        dry_would "FAIL: shellcheck is absent (SHELLCHECK=absent) and --allow-missing-shellcheck was not given"
      fi ;;
    sbt)
      if [[ "$SKIP_SBT" -eq 1 ]]; then
        dry_would "SKIP: --skip-sbt (the summary would say sbt did not run)"
      else
        dry_would "sbt $SBT_MODE (one process, 3600 s limit); prints the contract and schema test mapping first"
      fi ;;
    terraform)
      if [[ "$SKIP_TF" -eq 1 ]]; then
        dry_would "SKIP: --skip-terraform"
      else
        dry_would "terraform fmt -check -recursive -no-color $SC_TF_ROOT; terraform -chdir=$TF_ENV_DIR init -backend=false -input=false -no-color; terraform -chdir=$TF_ENV_DIR validate -no-color"
      fi ;;
  esac
}

if is_dry_run; then
  if [[ "$KEEP_GOING" -eq 1 ]]; then
    printf 'STOP_POLICY=keep-going\n'
  else
    printf 'STOP_POLICY=stop at the first failed gate\n'
  fi
  for g in $GATES; do
    dry_step "$g"
    describe_gate "$g"
  done
  printf 'CHECK_RESULT=DRY-RUN (no gate was run, so this is neither a pass nor a fail)\n'
  dry_finish
fi

for g in $GATES; do
  if [[ "$STOPPED" -eq 1 ]]; then
    record "$g" "NOT-RUN (stopped at the first failed gate; use --keep-going)"
    continue
  fi
  run_one "$g"
done

printf '%s' "$RESULTS"
printf 'SBT_RAN=%s\n' "$SBT_RAN"
if [[ -n "$SKIPPED" ]]; then
  printf 'SKIPPED_GATES=%s (a skipped gate is not a pass)\n' "${SKIPPED# }"
else
  printf 'SKIPPED_GATES=none\n'
fi
if [[ "$SKIP_SBT" -eq 1 ]]; then
  printf 'NOTE: sbt was skipped, so CHECK_RESULT does not cover scalafmt, compilation or any test\n'
fi
if [[ "$FAILED" -eq 0 ]]; then
  if [[ -n "$SKIPPED" ]]; then
    printf 'CHECK_RESULT=PASS_WITH_SKIPS (every gate that ran passed; skipped:%s; this is not a full pass)\n' "$SKIPPED"
  else
    printf 'CHECK_RESULT=PASS\n'
  fi
  exit 0
fi
printf 'CHECK_RESULT=FAIL\n'
exit 1
