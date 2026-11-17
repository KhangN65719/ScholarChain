# Wave 2 repair batch 2, experiment-engineer m5 (received 2026-11-17; author: experiment-engineer subagent; the stop hook replaced the detailed report twice; summary as received plus orchestrator reading)

Status: COMPLETE (edits), not executed; no sbt (work order). Four files under `experiments/b-burst/` and `load-tests/lib/`.
- `load-tests/lib/collect-common.sh:78-88` `cc_aws` (read by orchestrator): exact pairs cloudwatch get-metric-data / list-metrics / describe-alarms, sqs get-queue-attributes, ecs describe-services, logs filter-log-events, application-autoscaling describe-scaling-activities / describe-scaling-policies / describe-scalable-targets, dynamodb get-item, s3vectors get-vectors; anything else `cc_die`.
- `experiments/b-burst/collect.sh` exact allowlist likewise.
Expected human rehearsals: `load-tests/lib/rehearse_shell.py` pass=27; `experiments/b-burst/rehearse-collect.sh` pass=35; `experiments/b-burst/rehearse.sh` fail=0 (counts unverified).
Open: `tests/scripts/w2/expcheck.py:331` pins the old `describe-* | list-* | get-* | filter-log-events` text; possible `run.sh` pin not located. Routed to test-engineer (follow-up).

## test-engineer follow-up (received 2026-11-17; summary as received)
COMPLETE, not executed (no sbt per work order). Files: `tests/scripts/run.sh`, `tests/scripts/w2/expcheck.py`. Expected harness `RESULT pass=1780 fail=0` (1764 + 16 new cases), once the services read `SCHOLARCHAIN_EXPORT_RUN_ID`.
