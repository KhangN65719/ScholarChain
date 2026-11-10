# Work order 05: infra-engineer, operational scripts under scripts/

Owner: infra-engineer. Scope: `scripts/**` only (new files). Do not edit infra/terraform in this work order (the cost estimate is work order 06, running separately). No sbt. No terraform commands, no AWS CLI calls, no AWS credentials. Do not execute the scripts.

Read first: prompt.md (the course text, "Required scripts" and "Script requirements"), decisions.md, CLAUDE.md (hook operation: the guard refuses live deployment, rollback, pipeline and load script execution until a launcher is ratified), report-04, infra/terraform/README.md.

## Build
`plan.sh`, `deploy.sh`, `rollback.sh`, `smoke-test.sh`, `run-bounded-pipeline.sh`, `cleanup.sh`, plus one shared `lib.sh` for the common functions.

Requirements:
- `set -euo pipefail`. No interactive prompts. Greppable step markers (for example `STEP[n] name: start|ok|FAIL`).
- Refuse a dirty source tree before `deploy.sh`. Tag images by the Git SHA. Verify the deployed revision afterwards. Fail loudly with evidence. Never hide the terraform plan output (print it in full, and also tee it to a file).
- Every script needs explicit parameters: environment name, AWS account id, region (default us-east-1, refuse any other), and the cost bound. The scripts refuse to run when the account id or region differs from the approved values, and when a required human approval record is missing. The approval record is a file whose path is a parameter. It names the plan digest, script digest, account, region and cost ceiling (the contract in CLAUDE.md "Hook operation"). The scripts verify that record. Do not weaken it or create a bypass.
- `plan.sh` runs `terraform plan -out` with credentials from the caller's environment only. It reads no secret value and prints none.
- `run-bounded-pipeline.sh` takes explicit size (small, medium or large, from the three-size plan in work order 06 once it exists; until then accept `small|medium|large` and document it as provisional), a wall-clock limit, a spend ceiling and a stop condition. It refuses to start without all of them.
- `cleanup.sh` requires explicit human invocation (refuse when stdin or an agent flag shows it is non-interactive-agent-run, with an explicit `--i-am-the-human` flag documented as an agreement, not a security control), lists the affected resources BEFORE deletion, and then deletes only what Terraform created in that environment, through `terraform destroy` with `cleanup.tfvars`. It never deletes by wildcard and never runs `aws s3 rm --recursive`.
- `rollback.sh`: roll back to a previous image tag or Terraform state version given as a parameter, with the same approval record check.
- `smoke-test.sh`: read-only checks (health and readiness endpoints, a single bounded query, and a Bedrock access check that is skipped when Bedrock is off).
- No secret in source. Secrets come from AWS Secrets Manager ARNs, and the scripts never print them.

## Procedure
1. List which check proves each gate item. A syntax check (`bash -n`, and `shellcheck` only if it is installed already; do not install it) proves syntax only. Try `bash -n` on each script. If the guard blocks it, report the exact refusal and give the command. Do not work around the guard. The scripts must never be executed in this phase.
2. Write a short `scripts/README.md` with the parameters, the approval record format, what each script touches, and the idle/cost note.
3. Do not weaken the policy in CLAUDE.md. Report anything the scripts need from the guard or launcher as a Phase 13 or Phase 14 item.

## Report (contract fields, as plain text in your final message)
Files created, exact commands run with results, the approval-record fields, the parameters per script, what each script refuses, anything blocked, and the Phase 14 human actions.
