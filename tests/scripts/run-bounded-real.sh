#!/usr/bin/env bash
# run_bounded with real processes (Phase 13 required item S1, Phase 14 work order 07).
#
# Sources the real scripts/lib.sh and calls the real run_bounded with local processes
# only (sh, sleep, cat, true, false). No aws, terraform, docker, curl or sbt, no
# network, no credentials. Everything lives in one mktemp directory under $TMPDIR,
# never inside the repository. Total runtime is about 30 to 40 seconds.
#
#   bash tests/scripts/run-bounded-real.sh
#
# Output: one "CASE <name> PASS|FAIL <reason>" line per case, then
# "RESULT pass=N fail=M". Exit 0 only when fail=0, 2 when a prerequisite is missing.
#
# What the real run_bounded does (scripts/lib.sh): one limit SECS, a watchdog that sends
# TERM to the command's process group at the limit and KILL 5 seconds later, a
# marker file that makes the return value 124 whenever it exists, and a sweep that
# stops group members still alive after the command returned (SC_RB_STRAY, SC_RB_LEFT).
# The grace in this code is therefore 5 s between TERM and KILL (polled every 0.5 s),
# not 500 ms; the timing bounds below follow the code.
#
# The STOPPED_WALL_CLOCK / COMPLETED decision is made by run-experiment.sh from the
# return value, not by run_bounded. It cannot be sourced, so the test mirrors the
# three-line mapping and asserts the mapping is still present in that script.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/../../scripts/lib.sh"
RUN_EXPERIMENT="$HERE/../../scripts/run-experiment.sh"

if [[ ! -f "$LIB" ]]; then
  printf 'PREREQ missing %s\n' "$LIB" >&2
  exit 2
fi
for tool in sh sleep cat true false mktemp kill; do
  type -P "$tool" >/dev/null 2>&1 || { printf 'PREREQ missing tool: %s\n' "$tool" >&2; exit 2; }
done
SH="$(type -P sh)"
SLEEP="$(type -P sleep)"
TRUE="$(type -P true)"
FALSE="$(type -P false)"

TMP_ROOT="${TMPDIR:-/tmp}"
T="$(mktemp -d "${TMP_ROOT%/}/sc-rb-real.XXXXXX")" || { printf 'PREREQ cannot create a temp directory\n' >&2; exit 2; }
T="$(cd "$T" && pwd -P)"
case "$T" in
  */sc-rb-real.*) ;;
  *) printf 'PREREQ unexpected temp directory %s\n' "$T" >&2; exit 2 ;;
esac

# lib.sh reads SC_SCRIPT only in print_header, which this file never calls.
# shellcheck disable=SC2034
SC_SCRIPT="$0"
# shellcheck disable=SC1090,SC1091
source "$LIB"
# lib.sh turned on -e and installed its own EXIT trap. The test checks return values
# itself, and installs its own cleanup below.
set +e

# The markers of run_bounded are created with mktemp "${TMPDIR:-/tmp}/sc-rb.XXXXXX":
# pointing TMPDIR into the temp directory makes them observable and keeps them
# out of the shared temp root.
export TMPDIR="$T"

PASS=0
FAIL=0
CASE=""
FAILS=""
RB_RC=0
RB_ERR=""
RB_ELAPSED=0
RB_IN=""
POLLER=""
GROUPS_SEEN=""

cleanup() {
  local rc=$? p g
  trap - EXIT
  if [[ -n "$POLLER" ]]; then kill "$POLLER" 2>/dev/null; fi
  # Only recorded processes, still alive, and only ours (kill fails for other users).
  while read -r p || [[ -n "$p" ]]; do
    if [[ "$p" =~ ^[0-9]+$ ]] && kill -0 "$p" 2>/dev/null; then kill -KILL "$p" 2>/dev/null; fi
  done < <(cat "$T"/pids.* 2>/dev/null)
  for g in $GROUPS_SEEN; do
    if [[ "$g" =~ ^[0-9]+$ ]] && kill -0 -- "-$g" 2>/dev/null; then kill -KILL -- "-$g" 2>/dev/null; fi
  done
  case "$T" in
    "${TMP_ROOT%/}"/sc-rb-real.*) rm -rf "$T" ;;
  esac
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

### Helpers ------------------------------------------------------------------

begin() {
  CASE="$1"
  FAILS=""
}

fail() {
  FAILS="${FAILS}${FAILS:+; }$1"
}

finish() {
  if [[ -z "$FAILS" ]]; then
    printf 'CASE %s PASS ok\n' "$CASE"
    PASS=$((PASS + 1))
  else
    printf 'CASE %s FAIL %s\n' "$CASE" "$FAILS"
    FAIL=$((FAIL + 1))
  fi
}

# run_rb SECS CMD ARGS... : the real run_bounded in this shell (so SC_RB_* are visible).
# Sets RB_RC, RB_ERR, RB_ELAPSED. Standard input is RB_IN or /dev/null. A set -e
# caller sees the same value, because the return is consumed by ||.
run_rb() {
  local t0=$SECONDS
  RB_RC=0
  run_bounded "$@" <"${RB_IN:-/dev/null}" >"$T/out" 2>"$T/err" || RB_RC=$?
  RB_ELAPSED=$((SECONDS - t0))
  RB_ERR="$(cat "$T/err")"
  GROUPS_SEEN="$GROUPS_SEEN $SC_RB_PID"
  # run_bounded clears the INT and TERM traps it sets; put the test's trap back.
  trap 'exit 130' INT TERM
}

expect_rc() {
  [[ "$RB_RC" -eq "$1" ]] || fail "return code $RB_RC, expected $1"
}

expect_elapsed_between() {
  if [[ "$RB_ELAPSED" -lt "$1" || "$RB_ELAPSED" -gt "$2" ]]; then
    fail "elapsed ${RB_ELAPSED}s, expected $1 to $2 s"
  fi
}

has_evidence() {
  case "$RB_ERR" in *"exceeded its"*"second limit and was stopped"*) return 0 ;; esac
  return 1
}

expect_no_marker_left() {
  local f
  for f in "$T"/sc-rb.*; do
    if [[ -e "$f" ]]; then fail "marker file left behind: ${f##*/}"; rm -f "$f"; fi
  done
}

# wait_dead PID SECONDS: poll with a deadline until PID is gone (a killed orphan can
# be a zombie for a moment until init reaps it).
wait_dead() {
  local pid="$1" i max=$(($2 * 5))
  for ((i = 0; i < max; i++)); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.2
  done
  return 1
}

# expect_pids_dead FILE: every PID recorded in FILE is gone within 5 s, and the file
# is not empty (a child that never started would make the case meaningless).
expect_pids_dead() {
  local f="$1" p n=0
  while read -r p || [[ -n "$p" ]]; do
    n=$((n + 1))
    wait_dead "$p" 5 || fail "pid $p still alive after run_bounded returned"
  done < <(cat "$f" 2>/dev/null)
  [[ "$n" -gt 0 ]] || fail "no pid was recorded in ${f##*/}: the child did not start"
}

expect_group_gone() {
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sc_group_alive "$SC_RB_PID" || return 0
    sleep 0.5
  done
  fail "process group $SC_RB_PID still has members"
}

# start_poller TAG: records in $T/seen.TAG that a run_bounded marker file existed
# while the command was still running. Stopped with stop_poller.
start_poller() {
  (
    exec >/dev/null 2>&1 </dev/null
    n=0
    while [[ $n -lt 400 ]]; do
      for f in "$T"/sc-rb.*; do
        if [[ -e "$f" ]]; then : >"$T/seen.$1"; exit 0; fi
      done
      sleep 0.1
      n=$((n + 1))
    done
  ) &
  POLLER=$!
}

stop_poller() {
  if [[ -n "$POLLER" ]]; then
    kill "$POLLER" 2>/dev/null
    wait "$POLLER" 2>/dev/null
    POLLER=""
  fi
}

# The mapping run-experiment.sh applies to the return value of run_bounded.
final_state() {
  case "$1" in
    0) printf 'COMPLETED' ;;
    124) printf 'STOPPED_WALL_CLOCK' ;;
    *) printf 'FAILED' ;;
  esac
}

### Child programs (plain sh scripts, written into the temp directory) ---------

# Ignores TERM, records its pid, runs until killed.
cat >"$T/ignore-term.sh" <<'EOF'
trap '' TERM
echo $$ >> "$PIDS"
if [ -n "${READY:-}" ]; then : > "$READY"; fi
while :; do sleep 1; done
EOF

# The leader exits 0 at once and leaves a grandchild (sleep 300) in the group.
cat >"$T/leave-grandchild.sh" <<'EOF'
sleep 300 &
echo $! >> "$PIDS"
echo $$ >> "$PIDS"
exit 0
EOF

# The leader exits 0 at once and leaves a grandchild that ignores TERM.
cat >"$T/leave-stubborn-grandchild.sh" <<'EOF'
echo $$ >> "$PIDS"
export READY="$PIDS.ready"
sh "$TESTDIR/ignore-term.sh" &
n=0
while [ ! -e "$READY" ] && [ "$n" -lt 100 ]; do sleep 0.1; n=$((n + 1)); done
exit 0
EOF

# Runs past the limit with a grandchild; default TERM disposition.
cat >"$T/run-long-with-grandchild.sh" <<'EOF'
sleep 300 &
echo $! >> "$PIDS"
echo $$ >> "$PIDS"
wait
EOF

# S1 core: on TERM it looks for the marker (it exists before TERM is sent), records
# that, then exits 0.
cat >"$T/term-exit0.sh" <<'EOF'
echo $$ >> "$PIDS"
trap 'for f in "$TMPDIR"/sc-rb.*; do if [ -e "$f" ]; then echo yes > "$SEEN"; fi; done; exit 0' TERM
sleep 30 &
echo $! >> "$PIDS"
wait
EOF

# Ignores TERM and exits 0 two seconds in: after the marker (limit 1 s), before KILL.
cat >"$T/exit0-after-limit.sh" <<'EOF'
trap '' TERM
echo $$ >> "$PIDS"
sleep 2
exit 0
EOF

# Takes one second to react to TERM, which keeps the marker visible to the poller.
cat >"$T/slow-term.sh" <<'EOF'
echo $$ >> "$PIDS"
trap 'sleep 1; exit 143' TERM
sleep 30 &
echo $! >> "$PIDS"
wait
EOF

export TESTDIR="$T"

### Cases ----------------------------------------------------------------------

# 1. Finishes in time with 0: returns 0, no marker.
begin "1.finishes-in-time-rc0"
export PIDS="$T/pids.1"
run_rb 10 "$TRUE"
expect_rc 0
has_evidence && fail "stderr reports a time limit although the command finished"
expect_no_marker_left
expect_elapsed_between 0 3
[[ "$SC_RB_STRAY" -eq 0 && "$SC_RB_LEFT" -eq 0 ]] || fail "SC_RB_STRAY=$SC_RB_STRAY SC_RB_LEFT=$SC_RB_LEFT for a clean exit"
[[ "$(final_state "$RB_RC")" == "COMPLETED" ]] || fail "final state not COMPLETED for rc 0"
finish

begin "1b.finishes-in-time-rc0-reads-stdin"
printf 'hi\n' >"$T/stdin.txt"
RB_IN="$T/stdin.txt"
# shellcheck disable=SC2016
run_rb 10 "$SH" -c 'read l; [ "$l" = hi ]'
RB_IN=""
expect_rc 0
expect_no_marker_left
finish

# 2. Exits nonzero in time: returns that code, no marker.
begin "2.nonzero-exit-passes-through"
run_rb 10 "$FALSE"
expect_rc 1
has_evidence && fail "stderr reports a time limit although the command failed on its own"
expect_no_marker_left
[[ "$(final_state "$RB_RC")" == "FAILED" ]] || fail "final state not FAILED for rc 1"
finish

begin "2b.nonzero-exit-7-passes-through"
run_rb 10 "$SH" -c 'exit 7'
expect_rc 7
has_evidence && fail "stderr reports a time limit although the command failed on its own"
expect_no_marker_left
finish

# 3. Exceeds its own step timeout: stopped within timeout plus grace, rc 124.
# The command honours TERM, so the grace period (5 s before KILL) is not needed:
# the bound is the limit plus a small margin, well under limit plus grace.
begin "3.over-limit-killed-within-timeout-plus-grace"
export PIDS="$T/pids.3"
# shellcheck disable=SC2016
run_rb 1 "$SH" -c 'echo $$ >> "$PIDS"; exec sleep 30'
expect_rc 124
expect_elapsed_between 1 4
has_evidence || fail "stderr lacks the EVIDENCE line naming the exceeded limit"
expect_no_marker_left
expect_pids_dead "$PIDS"
finish

# 4. Wall-clock cutoff while the command is running: 124, marker exists, the final
# state is STOPPED_WALL_CLOCK and never COMPLETED. Marker existence is observed from
# outside (poller) while the command is still shutting down, and from the EVIDENCE
# line that run_bounded prints only when the marker exists.
begin "4.wall-clock-cutoff-124-marker-stopped"
export PIDS="$T/pids.4"
rm -f "$T/seen.4"
start_poller 4
run_rb 1 "$SH" "$T/slow-term.sh"
stop_poller
expect_rc 124
[[ -e "$T/seen.4" ]] || fail "the marker file was never observed while the command ran"
has_evidence || fail "stderr lacks the EVIDENCE line (it is printed only when the marker exists)"
expect_no_marker_left
expect_pids_dead "$PIDS"
[[ "$(final_state "$RB_RC")" == "STOPPED_WALL_CLOCK" ]] || fail "final state is $(final_state "$RB_RC"), not STOPPED_WALL_CLOCK"
[[ "$(final_state "$RB_RC")" != "COMPLETED" ]] || fail "final state is COMPLETED after a cutoff"
finish

begin "4b.run-experiment-keeps-the-state-mapping"
if grep -Fq '0) FINAL="COMPLETED"' "$RUN_EXPERIMENT" \
  && grep -Fq '124) FINAL="STOPPED_WALL_CLOCK"' "$RUN_EXPERIMENT" \
  && grep -Fq '*) FINAL="FAILED"' "$RUN_EXPERIMENT"; then
  :
else
  fail "scripts/run-experiment.sh no longer maps 0, 124 and other to COMPLETED, STOPPED_WALL_CLOCK and FAILED; the mirror in this test is stale"
fi
finish

# 5. S1. The command exits 0 after the marker exists; the result must be 124.
begin "5a.S1-exit0-on-TERM-after-marker"
export PIDS="$T/pids.5a" SEEN="$T/seen.5a"
rm -f "$SEEN"
run_rb 1 "$SH" "$T/term-exit0.sh"
expect_rc 124
[[ -e "$SEEN" ]] || fail "the command did not see the marker while exiting (the exit 0 came before the marker, so the case did not test S1)"
has_evidence || fail "stderr lacks the EVIDENCE line"
[[ "$RB_RC" -ne 0 ]] || fail "S1 regression: rc 0 although the marker existed"
[[ "$(final_state "$RB_RC")" == "STOPPED_WALL_CLOCK" ]] || fail "final state is $(final_state "$RB_RC"), not STOPPED_WALL_CLOCK"
expect_no_marker_left
expect_pids_dead "$PIDS"
finish

begin "5b.S1-ignores-TERM-exits-0-just-after-limit"
export PIDS="$T/pids.5b"
run_rb 1 "$SH" "$T/exit0-after-limit.sh"
expect_rc 124
expect_elapsed_between 2 4
has_evidence || fail "stderr lacks the EVIDENCE line"
[[ "$(final_state "$RB_RC")" == "STOPPED_WALL_CLOCK" ]] || fail "final state is $(final_state "$RB_RC"), not STOPPED_WALL_CLOCK"
expect_no_marker_left
expect_pids_dead "$PIDS"
finish

# 5c. The command ends at the limit itself (a true race): either result is allowed,
# but 0 must come with no EVIDENCE line and 124 with one, and nothing may be left.
begin "5c.S1-race-at-the-limit-is-consistent"
for i in 1 2 3; do
  run_rb 1 "$SLEEP" 1
  case "$RB_RC" in
    0) has_evidence && fail "run $i: rc 0 together with a limit EVIDENCE line" ;;
    124) has_evidence || fail "run $i: rc 124 without the EVIDENCE line" ;;
    *) fail "run $i: unexpected rc $RB_RC" ;;
  esac
  expect_no_marker_left
done
finish

# 6. Process groups.
# 6a. A child that ignores SIGTERM: the watchdog needs KILL, 5 s after TERM.
begin "6a.sigterm-ignored-group-gone-after-grace"
export PIDS="$T/pids.6a"
run_rb 1 "$SH" "$T/ignore-term.sh"
expect_rc 124
expect_elapsed_between 5 15
has_evidence || fail "stderr lacks the EVIDENCE line"
[[ "$SC_RB_LEFT" -eq 0 ]] || fail "SC_RB_LEFT=1: a member survived KILL"
expect_pids_dead "$PIDS"
expect_group_gone
expect_no_marker_left
finish

# 6b. The leader exits 0 and leaves a grandchild: run_bounded stops it, reports stray.
begin "6b.grandchild-left-behind-is-stopped"
export PIDS="$T/pids.6b"
run_rb 20 "$SH" "$T/leave-grandchild.sh"
expect_rc 0
[[ "$SC_RB_STRAY" -eq 1 ]] || fail "SC_RB_STRAY=$SC_RB_STRAY, expected 1 (the grandchild outlived the leader)"
[[ "$SC_RB_LEFT" -eq 0 ]] || fail "SC_RB_LEFT=1: the grandchild survived"
case "$RB_ERR" in *"still had members after the command returned"*) ;; *) fail "stderr lacks the stray-members EVIDENCE line" ;; esac
expect_pids_dead "$PIDS"
expect_group_gone
expect_no_marker_left
finish

# 6c. The leader exits 0 and leaves a grandchild that ignores TERM: TERM, 5 s, KILL.
begin "6c.stubborn-grandchild-killed-after-grace"
export PIDS="$T/pids.6c"
run_rb 30 "$SH" "$T/leave-stubborn-grandchild.sh"
expect_rc 0
[[ "$SC_RB_STRAY" -eq 1 ]] || fail "SC_RB_STRAY=$SC_RB_STRAY, expected 1"
[[ "$SC_RB_LEFT" -eq 0 ]] || fail "SC_RB_LEFT=1: a member survived KILL"
expect_elapsed_between 4 15
expect_pids_dead "$PIDS"
expect_group_gone
expect_no_marker_left
finish

# 6d. A cutoff with a grandchild: the whole group goes, the result is 124.
begin "6d.cutoff-takes-the-grandchild-too"
export PIDS="$T/pids.6d"
run_rb 1 "$SH" "$T/run-long-with-grandchild.sh"
expect_rc 124
expect_elapsed_between 1 6
has_evidence || fail "stderr lacks the EVIDENCE line"
expect_pids_dead "$PIDS"
expect_group_gone
expect_no_marker_left
finish

# 7. Nothing from this test is left at exit: every recorded pid and process group is
# gone, no marker file remains, and no process of the user still runs a test program.
begin "7.no-stray-processes-at-exit"
n=0
while read -r p || [[ -n "$p" ]]; do
  n=$((n + 1))
  wait_dead "$p" 5 || fail "recorded pid $p is still alive"
done < <(cat "$T"/pids.* 2>/dev/null)
[[ "$n" -gt 0 ]] || fail "no pid was recorded by any case"
for g in $GROUPS_SEEN; do
  if sc_group_alive "$g"; then fail "process group $g still has members"; fi
done
expect_no_marker_left
if type -P pgrep >/dev/null 2>&1; then
  left="$(pgrep -f "$T/" 2>/dev/null | tr '\n' ' ')"
  [[ -z "$left" ]] || fail "processes still run test programs: $left"
fi
finish

printf 'RESULT pass=%s fail=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
