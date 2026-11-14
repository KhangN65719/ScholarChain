# Report 01: infra-engineer, us-east-2 migration (condensed by the orchestrator; author: infra-engineer)

STATUS: BLOCKED-ON. Edits made, nothing checked (the guard refused terraform, bash -n, grep, find, awk, git grep; only `ls` ran).

## Coverage limits
- Found `us-east-1` by reading files, not searching. Read in full: Terraform except module outputs/variables files, Terraform READMEs, COST_ESTIMATE.md, lib.sh, plan.sh, deploy.sh, rollback.sh, cleanup.sh, smoke-test.sh, run-bounded-pipeline.sh, check.sh, run-experiment.sh, scripts/README.md.
- Partly read: package.sh (lines 1 to 150). Not opened: archive-evidence.sh. "No us-east-1 remains" is NOT proven.

## Files modified
- infra/terraform/environments/course/variables.tf: `region` default us-east-2 with validation refusing others (message names D1); AZ defaults us-east-2a/b; new validation requires exactly two zones inside var.region (needs Terraform >= 1.9; pin is >= 1.10); Haiku descriptions per D5.
- infra/terraform/modules/neptune/variables.tf: db.t4g.medium noted NOT CONFIRMED for us-east-2.
- scripts/lib.sh: SC_APPROVED_REGION=us-east-2, SC_REGION_LOCK_NOTE (names D1), both Region refusals append it, new `check_tfvars_region`.
- scripts/plan.sh: calls check_tfvars_region on course.tfvars and every --extra-var-file.
- scripts/smoke-test.sh, scripts/run-experiment.sh, scripts/README.md: help text, example ARNs.
- infra/terraform/README.md, infra/terraform/COST_ESTIMATE.md (new section "Region migration to us-east-2": verified V9 and the LOW half of U4; six lines Region-neutral; the rest unverified; totals and caps unchanged).

## Findings
- main.tf and modules build ARNs and endpoints from var.region; no hard-coded AZs, no use1, no Region in tfvars.
- D5: IAM already has Haiku foundation-model ARNs for us-east-1, us-east-2, us-west-2 plus the us-east-2 profile ARN (bedrock_model_arns local); no IAM change. vector_dimension default 256, 256 allowed.
- Every SCRIPT_DIGEST changed; any approval record is invalid.
- Cross-Region profile may route to us-east-1/us-west-2; cost of that unmodelled.

## Blocked-on
1. EMR m5.xlarge in us-east-2 (agent has no docs tool): orchestrator to supply.
2. Human: Neptune db.t4g.medium in us-east-2 unconfirmed (describe-orderable-db-instance-options, let first plan decide, or switch class).
3. Human: cost ceiling. $25 in lib.sh, `budget_total_usd` validation and cost docs is looser than D1 (USD 20 limit, stop near 15).
4. Stale text "$25 and $50 budgets already exist" in variables.tf and infra README (not a Region change).

## Handoffs not done
- test-engineer: tests/scripts/run.sh likely asserts old Region.
- documentation-engineer: root README and docs/** not searched.
- package.sh (after line 150) and archive-evidence.sh unread.

## Human `!` checks required
terraform fmt -check -recursive -diff infra/terraform; terraform -chdir=infra/terraform/environments/course init -backend=false then validate; bash -n and shellcheck -x on lib.sh, plan.sh, smoke-test.sh, run-experiment.sh.
