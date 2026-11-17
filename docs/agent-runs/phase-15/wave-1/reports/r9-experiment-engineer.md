# R9 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE (rows 2 and 8 of `repair-batch-3.md`); not executed (guard).

- `experiments/b-burst/collect.sh:69` (SC2155): `local failed="$RUN_DIR/collect-failed.$(date -u +%Y%m%dT%H%M%SZ)"` became `local failed` + `failed="$RUN_DIR/collect-failed.$(date -u +%Y%m%dT%H%M%SZ)"` (lines 69-70). `local status=$?` stays the first statement of `keep_failed_stage`.
- `experiments/b-burst/run.sh:31` comment: `(exp_run_pipeline refuses otherwise)` became `(the entry helper refuses otherwise)`. The token now appears only on the call at line 44. No call or constant changed.
- `experiments/a-baseline/run.sh`: one `exp_run_pipeline` (the call, line 35), none in comments; unchanged.

Expected: shellcheck clean on `collect.sh` and `run.sh`; `rehearse-collect.sh` pass=25; `rehearse.sh` pass=59 (the agent could not grep the rehearsals for the old text; check that first if a count differs).

Human commands (logs `phase15-r9-1..3.log`): shellcheck -x on both files; `rehearse-collect.sh`; `rehearse.sh`.
