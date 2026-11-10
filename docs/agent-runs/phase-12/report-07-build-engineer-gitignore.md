# Report 07: build-engineer (work order 07, .gitignore), as received (condensed)

STATUS: COMPLETE for the edit. The read-only ignore check could not run, so it is not done.
File modified: .gitignore. Existing lines unchanged, including `/.claude/.last-test-run`.

Lines added after `/.claude/.last-test-run`: `.terraform/`, `*.tfstate`, `*.tfstate.*`, `*.tfstate.backup`, `*.tfplan`, `tfplan`, `crash.log`, `crash.*.log`, `override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`. None match `.terraform.lock.hcl`, `*.tf` (except files named override.tf or *_override.tf) or `*.tfvars`.

Commands: only the file edit succeeded. No sbt, no commit. Every `git check-ignore -v` attempt was blocked by the guard (shell expansion or chaining; "outside the role's permitted commands"; and a sensitive-path flag, probably from `tfstate`). The patterns were checked by reading them only.

Risk: ignore behavior is unverified by a command. `override.tf` is ignored at any depth; no such file is expected under infra/terraform.
Manual action (optional, human `!`): `git check-ignore -v infra/terraform/environments/course/main.tf infra/terraform/environments/course/course.tfvars infra/terraform/environments/course/a.tfplan` should print only a match for the third path.
