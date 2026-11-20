# Repair 05: review A9 F3 (test-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE (authoring only; not executed).
FILES MODIFIED: `tests/scripts/hooks-guard.sh`, header comment lines 7-10 only. The comment now says the harness runs two entry points: `policy.py guard` and `guard-dangerous.sh` itself (via `--list-categories`, and with a made-up payload on stdin).
Recount: test-engineer confirms the reviewer's total of **114** (the old file had 97; D3 has 3 cases, not 4). The new total is 97 - 12 + 3 + 26 = 114.
MANUAL ACTION: `bash -n tests/scripts/hooks-guard.sh`, then `bash tests/scripts/hooks-guard.sh`, expecting `RESULT pass=114 fail=0`.
