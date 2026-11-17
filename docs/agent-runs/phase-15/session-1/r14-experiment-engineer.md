# R14 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Cause: the installed AWS CLI has no `emr list-instance-groups` (`ParamValidation: Found invalid choice`), so the B collector's required call failed (exit 252) after `describe-execution`, `get-execution-history` and `describe-cluster` succeeded (partial files kept in `collect-failed.20261117T071225Z/`).

Fix (only `experiments/b-burst/**`; not executed, guard):
- `collect.sh`: the `list-instance-groups` call removed (8 calls); header and `AWS_CALLS=` updated; read-only verb rule, staging, `keep_failed_stage`, `rm`/`mv` lines unchanged.
- `derive-capacity.py`: instance groups read from `emr-describe-cluster.json` `Cluster.InstanceGroups`; an unlisted group id or a cluster without `InstanceGroups` gives role and group `NOT RECORDED` (was `UNKNOWN`); new summary key `instances_role_not_recorded`.
- Fixtures: groups moved under `Cluster.InstanceGroups` in the four `emr-describe-cluster.json`; four `emr-list-instance-groups.json` fixtures now unused (human removes them).
- `rehearse.sh` +3 checks (expected 62); `rehearse-collect.sh` stub fails `list-instance-groups` with exit 252 and the real text, +2 checks (expected 27). No check loosened.
- `README.md`: table row and the live-watch command (used the same missing subcommand) now use `describe-cluster --query 'Cluster.InstanceGroups[]...'`.
- CLI verbs: observed OK `stepfunctions describe-execution`, `get-execution-history --include-execution-data`, `emr describe-cluster`; UNVERIFIED `emr list-instances --instance-states ...`, `get-managed-scaling-policy`, `list-steps`, `cloudwatch list-metrics`, `get-metric-data ... --scan-by TimestampAscending`.
- Harness cases that pin the call count, `AWS_CALLS=` or the `emr-list-instance-groups.json` file may break (test-engineer).

Orchestrator note: the agent repeats the claim that `echo "exit: $?"` reports `tail`'s status; under `set -o pipefail` it does not (see `wave-1/reports/r6-dataset-engineer.md`).
