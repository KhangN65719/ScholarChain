# shellcheck shell=bash
# Helpers shared by the Phase 15 entry points of C, D, E and F. Source AFTER
# experiments/lib/exp-lib.sh (it uses exp_utc_now, exp_event, exp_die, EXP_RAW). Never run it.
#
#   ec_begin            records the start time and arms the EXIT trap that writes raw/window.json
#   ec_require_env VAR  exp_die when an environment variable is empty
#   ec_set_status WORD  the final status word written into window.json (default "interrupted",
#                       so a TERM or an unexpected exit leaves a visible record)
#   ec_add KEY VALUE    an extra string field for window.json (no quote or backslash in a value)
# window.json is written once, at exit, never overwritten. The collectors read start_utc and
# end_utc from it. Bash 3.2 compatible.

EC_START=""
EC_STATUS="interrupted"
EC_WINDOW_DONE=0
EC_EXTRA=()

ec_require_env() {
  local v
  for v in "$@"; do
    [[ -n "${!v:-}" ]] || exp_die "$v is not set (the paste-ready command for this experiment sets it)"
  done
}

ec_set_status() {
  EC_STATUS="$1"
}

ec_add() {
  EC_EXTRA+=("$1=$2")
}

ec_finish() {
  local rc=$?
  if [[ "$EC_WINDOW_DONE" -eq 0 && -n "${EXP_RAW:-}" && -d "${EXP_RAW:-/nonexistent}" ]]; then
    EC_WINDOW_DONE=1
    local end extra="" kv
    end="$(exp_utc_now)"
    for kv in ${EC_EXTRA[@]+"${EC_EXTRA[@]}"}; do
      extra="${extra},\n  \"${kv%%=*}\": \"${kv#*=}\""
    done
    (
      set -C
      printf '{\n  "start_utc": "%s",\n  "end_utc": "%s",\n  "final_status": "%s",\n  "entry_exit": %s%b\n}\n' \
        "$EC_START" "$end" "$EC_STATUS" "$rc" "$extra" > "$EXP_RAW/window.json"
    ) || true
    exp_event ENTRY_END "exit=$rc status=$EC_STATUS"
  fi
  return "$rc"
}

ec_begin() {
  EC_START="$(exp_utc_now)"
  trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; exit 143' TERM INT
  trap ec_finish EXIT
}
