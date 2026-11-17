#!/usr/bin/env bash
# Fails when a committed JSON copy of an experiment manifest differs from its KEY=VALUE
# file (decision P15-17). No AWS, no network. Read-only unless --generate is given.
#
# Usage:
#   bash experiments/check-manifest-json.sh                  check all six (exit 0 only if every pair matches)
#   bash experiments/check-manifest-json.sh --generate       rewrite every JSON copy from its manifest
#   bash experiments/check-manifest-json.sh --manifests-dir DIR --json-dir DIR   (used by the rehearsal)
#
# Checks, in this order, and all are reported before the exit status is decided:
#   - every DIR/*.manifest has JSON-DIR/<name>.json whose content equals it
#   - no JSON-DIR/*.json lacks a manifest (an orphan copy)
#   - at least one manifest exists
# The edit rule: change the KEY=VALUE file, run --generate, commit both. The approval record
# binds the KEY=VALUE file only; the JSON copy is a generated, checked convenience.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFESTS="$HERE/manifests"
JSONS="$HERE/manifests/json"
GENERATE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --generate) GENERATE=1; shift ;;
    --manifests-dir) [[ $# -ge 2 ]] || { echo "CHECK_REFUSED: --manifests-dir needs a value" >&2; exit 2; }; MANIFESTS="$2"; shift 2 ;;
    --json-dir) [[ $# -ge 2 ]] || { echo "CHECK_REFUSED: --json-dir needs a value" >&2; exit 2; }; JSONS="$2"; shift 2 ;;
    *) echo "CHECK_REFUSED: unknown argument: $1" >&2; exit 2 ;;
  esac
done

type -P python3 >/dev/null 2>&1 || { echo "CHECK_REFUSED: python3 is required" >&2; exit 2; }
[[ -d "$MANIFESTS" ]] || { echo "CHECK_REFUSED: no manifests directory: $MANIFESTS" >&2; exit 2; }

BAD=0
COUNT=0
shopt -s nullglob
if [[ "$GENERATE" -eq 1 ]]; then
  mkdir -p "$JSONS"
fi
for m in "$MANIFESTS"/*.manifest; do
  COUNT=$((COUNT + 1))
  name="$(basename "$m" .manifest)"
  if [[ "$GENERATE" -eq 1 ]]; then
    python3 -I "$HERE/lib/manifest-json.py" generate "$m" "$JSONS/$name.json" || BAD=$((BAD + 1))
  else
    if [[ ! -f "$JSONS/$name.json" ]]; then
      printf 'MISMATCH %s.manifest: no JSON copy %s\n' "$name" "$JSONS/$name.json"
      BAD=$((BAD + 1))
    else
      python3 -I "$HERE/lib/manifest-json.py" check "$m" "$JSONS/$name.json" || BAD=$((BAD + 1))
    fi
  fi
done
if [[ "$GENERATE" -eq 0 && -d "$JSONS" ]]; then
  for j in "$JSONS"/*.json; do
    if [[ ! -f "$MANIFESTS/$(basename "$j" .json).manifest" ]]; then
      printf 'MISMATCH orphan JSON copy with no manifest: %s\n' "$j"
      BAD=$((BAD + 1))
    fi
  done
fi
if [[ "$COUNT" -eq 0 ]]; then
  echo "CHECK_FAILED: no *.manifest file in $MANIFESTS"
  exit 1
fi
if [[ "$BAD" -ne 0 ]]; then
  printf 'MANIFEST_JSON_CHECK FAILED manifests=%s problems=%s\n' "$COUNT" "$BAD"
  exit 1
fi
printf 'MANIFEST_JSON_CHECK OK manifests=%s\n' "$COUNT"
