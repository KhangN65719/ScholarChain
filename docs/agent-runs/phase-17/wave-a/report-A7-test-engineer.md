# WO-A7 report (test-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE for authoring. The harness has not been executed and `bash -n` has not been run on it. FILES MODIFIED: `tests/scripts/hooks-guard.sh` only.
- **Removed (12):** the 8 heading cases, `no-executable-line-before-the-last-three`, and 3 positional last-three-lines cases.
- **Changed (3):** `set -euo pipefail` present anywhere; the python3 check line present; the last line is exactly the `exec ... policy.py guard` line.
- **Added (26), section 6 (`--list-categories`):**
  - exits 0, including with stdin closed; stderr empty
  - every line is `id<TAB>example`
  - the eight ids appear in order
  - at least two examples per id (8 cases)
  - placeholders only (no 12-digit number, `arn:`, `AKIA`, `amazonaws.com`; every `s3://` bucket starts with `example` or equals `x`)
  - every example piped to `policy.py guard` as test-engineer exits 2 with `ScholarChain policy:` (one case per category, 8 cases)
- **Added, section 7 (hook payload through `guard-dangerous.sh` itself):** terraform apply, s3 rm and git push --force are refused (exit 2); git status is allowed (exit 0).
- **Kept (86):** the 33 R03 refusals, 13 role cases, 33 hook-form cases, the D3 cases, executable, `bash -n`, and the shebang.
- **Expected total, as stated by the agent:** 115 (98 - 12 + 3 + 26).

## Orchestrator note
The agent counts the old file as 98, but the human's run of the old file printed `RESULT pass=97 fail=0` (`human-commands.md`). The baseline count is therefore off by one, and the true new total is probably 114. The human's run is authoritative; the reviewer is asked to recount.
