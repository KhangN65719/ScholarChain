# WO-1 report, infra-engineer (received 2026-11-17T00:52:10Z; recovered from the subagent transcript because the stop hook replaced its final message; content preserved as written; author: infra-engineer subagent)

Status: BLOCKED-ON the human for validation. All edits are done. No check ran: the guard refused every shell wrapper and terraform call. No AWS command, plan, apply, commit or sbt.

## Tests that prove each gate item
| Gate item | Proving test | Status |
|---|---|---|
| S overlay is `db.t4g.medium`; line 1 says 3,472 | `terraform fmt`/`validate`; WO-12a harness case | Not run |
| `graph-load.tfvars` and `elasticity-s.tfvars` pass validation | `check.sh` terraform gate (fmt -check, init -backend=false, validate) | Not run |
| Wait state absent at 0, bounded when set | `terraform validate`; WO-12a case on the module text | Not run |
| Rule A with the wait | New second validation on `max_run_seconds` (validate plus WO-12a case) | Not run |
| S caps 90 / 64 / 20,000; M and L unchanged | `tier_caps` in `run-experiment.sh`; WO-12a case via `--print-digest` or `--dry-run` | Not run |
| Pre-session commands have no mutating AWS verb | WO-12a text case; by reading, every command is `get-` or `list-` | Reading only |

## Files changed
- `infra/terraform/environments/course/experiment-size-s.tfvars`: `neptune_instance_class = "db.t4g.medium"` (xlarge line removed); line 1 says 3,472 works; the "660 s" creation figure marked unsourced (run 6 derives about 374 s for create plus fetch); 660 kept in the sums so existing harness arithmetic does not move.
- `infra/terraform/environments/course/graph-load.tfvars` (new): only `db.r6g.xlarge`; documents the session 2 apply order (S + graph-load up, loader graph-only, then S alone, each with its own plan and approval record); Neptune modify behaviour marked UNVERIFIED.
- `infra/terraform/environments/course/elasticity-s.tfvars` (new): core 1, scaling cap 4, no task nodes, Neptune off, Bedrock off, services 0, loader 512 CPU / 1024 MiB; wait 900 s, idle timeout 1800 s; timeouts max run 7200, create 1440, step 4200, loader 600; Rule A 1440 + 4200 + 900 + 600 = 7140 <= 7200 (a 4800 step gives 7740 and is refused).
- `infra/terraform/environments/course/elasticity-example.tfvars`: header note only (min = max = 5 cannot scale); values unchanged.
- `infra/terraform/modules/workflow/variables.tf`: new `wait_before_terminate_seconds` (0 to 1800, default 0); second Rule A validation counting the wait; old validation untouched.
- `infra/terraform/modules/workflow/main.tf`: both step states go to a `WaitBeforeTerminate` Wait state only when the wait is above 0; at 0 the state does not exist; failure paths never wait.
- `infra/terraform/environments/course/variables.tf`: new `pipeline_wait_before_terminate_seconds`; validation that `emr_idle_timeout_seconds` exceeds it.
- `infra/terraform/environments/course/main.tf`: wires the new variable into the workflow module.
- `infra/terraform/modules/emr/main.tf` (not in the work order, inside infra territory): `MaximumCoreCapacityUnits` was `max(core, 1)`, so core 1 with cap 4 could never scale out; now `max(core, cap - 1 - tasks, 1)`. Fixed-size shapes give the same value as before.
- `scripts/run-experiment.sh`: S caps 90 / 64 / 20,000 (P15-1), header comment updated; M and L unchanged.
- `scripts/README.md`: cap table updated, D12 marked decided by P15-1 for tier S; new section "Phase 15 pre-session checks".

## Paste-ready pre-session commands (verbatim, as in `scripts/README.md`)
EC2 Running On-Demand Standard vCPU quota:
```
set -o pipefail; AWS_PROFILE=scholarchain aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --region us-east-2 --query 'Quota.[QuotaCode,QuotaName,Value,Unit,Adjustable]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-ec2-quota.log | tail -n 60; echo "exit: $?"
```
Fargate On-Demand vCPU quota:
```
set -o pipefail; AWS_PROFILE=scholarchain aws service-quotas get-service-quota --service-code fargate --quota-code L-3032A538 --region us-east-2 --query 'Quota.[QuotaCode,QuotaName,Value,Unit,Adjustable]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-fargate-quota.log | tail -n 60; echo "exit: $?"
```
Fargate fallback, if the code is rejected:
```
set -o pipefail; AWS_PROFILE=scholarchain aws service-quotas list-service-quotas --service-code fargate --region us-east-2 --query 'Quotas[].[QuotaCode,QuotaName,Value]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-fargate-quota-list.log | tail -n 60; echo "exit: $?"
```
Bedrock model availability (3a):
```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock get-foundation-model-availability --model-id anthropic.claude-haiku-4-5-20251001-v1:0 --region us-east-2 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-bedrock-availability.log | tail -n 60; echo "exit: $?"
```
Bedrock inference profile (3b):
```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock get-inference-profile --inference-profile-identifier us.anthropic.claude-haiku-4-5-20251001-v1:0 --region us-east-2 --query '[inferenceProfileId,status,type]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-bedrock-profile.log | tail -n 60; echo "exit: $?"
```
Bedrock profile list, if 3b says unknown (3c):
```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock list-inference-profiles --type-equals SYSTEM_DEFINED --region us-east-2 --query 'inferenceProfileSummaries[].[inferenceProfileId,status]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-bedrock-profiles.log | tail -n 60; echo "exit: $?"
```
If model access is missing, the human enables it in the Bedrock console for us-east-2. None of these commands invokes the model.

URLs listed in the README for verification; none was read (the documentation MCP was not available in the agent's session):
- https://docs.aws.amazon.com/cli/latest/reference/service-quotas/get-service-quota.html
- https://docs.aws.amazon.com/general/latest/gr/ec2-service.html
- https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-quotas.html
- https://docs.aws.amazon.com/cli/latest/reference/bedrock/get-foundation-model-availability.html
- https://docs.aws.amazon.com/cli/latest/reference/bedrock/list-inference-profiles.html

## Exact guard refusals
- `bash scripts/check.sh --skip-sbt`: `PreToolUse:Bash hook error: [bash "$CLAUDE_PROJECT_DIR/.claude/hooks/guard-dangerous.sh"]: ScholarChain policy: Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked.`
- `terraform fmt -check -recursive -no-color /Users/winnguyen/ScholarChain/infra/terraform`: `ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.`
- Shell `grep` and an `awk` column check were also refused; pipelines and chaining got "Use explicit commands and paths. Shell expansion, pipelines, chaining and redirection require a reviewed script."; plain `grep` got "outside the role's permitted commands."

## Human gate to run
```
set -o pipefail; AWS_PROFILE=scholarchain bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo1-check.log | tail -n 60; echo "exit: $?"
```
Then `bash tests/scripts/run.sh`.

## Open items and risks
- Existing harness cases will likely fail: they pin old text in the S overlay (the 660 sums, "5,000 works", the xlarge value), the old S caps 60 / 4 / 1000, `Next = "TerminateCluster"` and `MaximumCoreCapacityUnits`. WO-12a owns the update. Nothing weakened.
- Experiment B's loader step will probably end Failed: in pipeline mode the loader runs after `TerminateCluster`, and with Neptune and Bedrock off it cannot do its normal work. Capped at 600 s; the measurements are taken before it starts. WO-3 should choose a harmless `loader_command` or accept a FAILED final status.
- Wait and idle timeout must change together: the default idle timeout (900) equals the wait, so the new validation refuses 900 / 900. The overlay sets 1800.
- Every script digest changed (`run-experiment.sh` edited), so any approval record for it must be rewritten.
- The S overlay still carries the work order 114 history comment saying a later S session-up creates Neptune at xlarge; now false, left as history with a note above it.

## UNVERIFIED
- Every quota code and Bedrock CLI command above (from memory): `L-1216C47A`, `L-3032A538`; that `get-foundation-model-availability` exists and its response fields.
- Neptune modify downtime; orderability of `db.r6g.xlarge` for engine 1.4.5.1 in the writer's AZ.
- EMR managed scaling: whether it accepts these limits on an instance-group cluster; whether it counts the primary node as a unit, as the module assumes; whether it scales in within 900 s of idle. If not, P15-11 applies.
- `terraform fmt` cleanliness (checked by eye only); whether the new validations and the Wait state pass `terraform validate`.

Hook: sbt not applicable; marker stale; validation pending human.
