# Terraform local checks (human-run with the `!` prefix, 2026-11-09; the guard refuses these for agents)

Environment: infra/terraform/environments/course. No plan, no apply, no AWS credentials.

## Run 1 (before work order 08)
- `terraform -chdir=infra/terraform fmt -check -recursive`: no output (clean).
- `terraform -chdir=infra/terraform/environments/course init -backend=false`: success, `hashicorp/aws v6.20.0` installed, lock file created.
- `terraform validate`: FAILED, two errors: `aws_s3vectors_vector_bucket` and `aws_s3vectors_index` are not supported by the provider at v6.20.0 (modules/s3vectors/main.tf lines 26 and 35). Led to decision D5 and work order 08.

## Run 2 (after work order 08), log: /Users/winnguyen/scholarchain-data/logs/phase-12-terraform-checks-2.log
- `init -upgrade -backend=false`: exit 0. `hashicorp/aws v6.26.0` installed (signed by HashiCorp). The lock file `.terraform.lock.hcl` was changed and must be committed.
- `validate`: exit 0, "Success! The configuration is valid."
- `fmt -check -recursive -diff`: exit 0, no diff.

## Run 3 (after repair round 1, work order 11), log: /Users/winnguyen/scholarchain-data/logs/phase-12-checks-3.log
- `terraform validate` (environments/course): exit 0, "Success! The configuration is valid." (no `init -upgrade`; no provider changed).
- `terraform fmt -check -recursive -diff` on infra/terraform: exit 0, no diff.
- `bash -n` on scripts/lib.sh, plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh: exit 0 (syntax only; first time these ran).
- `shellcheck`: not run. `command -v shellcheck` printed no path and the group exited 1, so it is not installed. No new tool was installed (needs human approval).

## Run 4 (after repair round 2, work order 15), log: /Users/winnguyen/scholarchain-data/logs/phase-12-checks-4.log
- `terraform validate` (environments/course): exit 0, "Success! The configuration is valid."
- `terraform fmt -check -recursive -diff` on infra/terraform: exit 0, no diff.
- `bash -n` on the seven scripts (lib.sh, plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh): exit 0. This covers the repair-round-2 edits to lib.sh and smoke-test.sh (syntax only).
- shellcheck: not installed, not run.

## What this proves, and what it does not
Proves: formatting; provider v6.26.0 resolves; the S3 Vectors resources and every argument and attribute reference in the configuration type-check against v6.26.0; the dynamic blocks, conditional resources and variable validations parse.
Does not prove: IAM scope, cost, that nothing is public, that the defaults produce the intended resource set (that is a Phase 14 plan), whether `db.t4g.medium` is orderable for the chosen Neptune engine, or that the Bedrock, EMR and Neptune identifiers exist. Those rest on review and Phase 14.
Terraform CLI version: not shown in the output; still unconfirmed against the `>= 1.10.0, < 2.0.0` constraint (validate passed, so the installed version satisfies it).
