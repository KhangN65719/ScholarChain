# Report 01: infra-engineer, F1 and F4 (as received, condensed)

STATUS: BLOCKED-ON human decision (F1 loader windows). F4 done. F1 structure done. No check ran: the Phase 1 guard refused every one.

## Files modified
- infra/terraform/modules/workflow/variables.tf: `step_timeout_seconds` replaced by `create_cluster_timeout_seconds` (300 to 3600, default 1440), `emr_step_timeout_seconds` (300 to 7200, default 3600), `loader_timeout_seconds` (300 to 7200, default 3600). Rule A validation on `max_run_seconds`: create + 2 x EMR step + 300 <= max_run.
- modules/workflow/main.tf: four timeouts rewired; TerminateCluster and CleanupOnFailure stay 900 s as locals; new `check "execution_timeout_covers_every_step"` (Rule B, plan warning): create + 2 x EMR step + 900 + loader vs max_run.
- environments/course/variables.tf, main.tf: three `pipeline_*_timeout_seconds` variables; hand-aligned `=` in the workflow block.
- environments/course/experiment-size-{s,m,l}.tfvars: four values each, arithmetic in comments. CreateCluster 1440 on every tier; EMR step and loader values unchanged (S 1800, M 2700, L 3600); max_run unchanged (5400, 7200, 10800).
- infra/terraform/README.md: variable tables, failure-routing table, "Pipeline bounds" rewritten (Rules A and B, HIGH arithmetic), CreateCluster-hang sentence corrected, F4 check block now `fmt -check -recursive -diff`, `init -backend=false`, `validate` (no `-upgrade`).
- COST_ESTIMATE.md threat row 11; scripts/README.md "Pipeline sizes" sentence. No dollar figure changed. scripts/*.sh untouched (SCRIPT_DIGEST unchanged).

## Failure routing (from code, not observed)
Hadoop and Spark failures -> CleanupOnFailure -> Failed. CreateCluster, TerminateCluster, RunLoader failures -> Failed, no cleanup. A hung CreateCluster ends at its own 1440 s timeout. Whether Step Functions terminates a half-created cluster is UNVERIFIED.

## Commands
None succeeded as checks. Refused by the guard: `terraform fmt -check -recursive -diff`, `terraform -chdir=... validate`, `shellcheck -x scripts/lib.sh`, several `grep` calls. Refusal text: "ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner."

## Human checks pending
```
terraform fmt -check -recursive -diff infra/terraform
terraform -chdir=infra/terraform/environments/course init -backend=false
terraform -chdir=infra/terraform/environments/course validate
```

## F1 stop condition: loader windows cannot match the estimate without a raise
HIGH sequential run per the estimate (+900 s terminate allowance is the agent's):
| | S | M | L |
|---|---|---|---|
| HIGH run | 7200 s (120 min) | 12240 s (204 min) | 23040 s (384 min) |
| with 900 s terminate | 8100 | 13140 | 23940 |
| execution timeout today | 5400 | 7200 | 10800 |
| `--max-minutes` cap today | 60 | 90 | 150 |
Even LOW overshoots M (96 vs 90 min) and L (174 vs 150).

Options (flat HIGH burn S 0.60, M 0.62, L 0.65 USD/h; overstates the loader phase). Today's projections 0.60 / 0.93 / 1.63.
- O1 keep caps and max_run, restate the loader assumption as shorter: caps 60/90/150, $0 change; loader room about 0, 6, 6 min, so the assumption is not credible; a failed run costs a re-run and a new approval record.
- O2 raise caps to one HIGH run: caps 120/204/384; projected 1.20 / 2.11 / 4.16 (+0.60 / +1.18 / +2.53); max_run 9000/14040/24840 s, so validation ranges must rise; estimate's M session hours would add about $0.22, S+M HIGH about $25.64 (agent arithmetic, not applied).
- O3 HIGH pipeline plus LOW loader: caps 90/144/264; 0.90 / 1.49 / 2.86; L max_run still exceeds the range; loader can still time out at HIGH.
Agent recommendation: O2 only if S+M HIGH about $25.64 is accepted; otherwise O1.

## Assumptions and risks
Launch overhead is inside the estimate's node-hours; estimate has no Hadoop/Spark split; Rule B and the `check` block are untested; Rule B warns on all tiers until decided; `plan.sh` handling of Terraform warnings unchecked; CreateCluster now 1440 s on every tier (was 1800/2700/3600); a stale reference to `pipeline_step_timeout_seconds` in docs/** or tests could be missed (documentation-engineer sweep needed).
