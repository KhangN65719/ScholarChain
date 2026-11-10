# Work order 07: build-engineer, .gitignore patterns for Terraform

Owner: build-engineer. Scope: `.gitignore` only. No sbt (no Scala or sbt file changes). Local only, no commit.

Add ignore patterns for local Terraform artifacts, so state and provider downloads never reach git:
- `.terraform/` (provider downloads from `terraform init`)
- `*.tfstate`, `*.tfstate.*` and `*.tfstate.backup`
- `*.tfplan` and `tfplan` (saved plans can contain sensitive values)
- `crash.log` and `crash.*.log`
- `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`

Do NOT ignore: `.terraform.lock.hcl` (it is committed on purpose), `*.tf`, or `*.tfvars` (the course tfvars files are committed on purpose and hold no secrets). Note that `.gitignore` already ignores `/.claude/.last-test-run`; leave existing lines unchanged.

Report: the exact lines added, and the result of a read-only check that `infra/terraform/**/*.tf` and `*.tfvars` are still not ignored (for example `git check-ignore -v` on one path of each kind, if the guard allows it; if not, say so).
