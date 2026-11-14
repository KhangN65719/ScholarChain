# Checks 1: static checks after the us-east-2 migration (human run, observed by the orchestrator from the pasted output)

Date: 2026-11-13. Run by the human with `!`. Log: /Users/winnguyen/scholarchain-data/logs/phase14-static-checks.log.

Commands, in one pipefail block: `terraform fmt -check -recursive -diff infra/terraform`, `terraform -chdir=infra/terraform/environments/course init -backend=false`, `terraform ... validate`, then `bash -n` and `shellcheck -x` on scripts/*.sh (11 files: archive-evidence, check, cleanup, deploy, lib, package, plan, rollback, run-bounded-pipeline, run-experiment, smoke-test) and tests/scripts/run.sh.

Result: exit 0.
- fmt: no diff output (clean).
- init -backend=false: success; reused hashicorp/aws 6.26.0 from the lock file.
- validate: "The configuration is valid."
- bash -n and shellcheck -x: no output for all 12 files.

Not yet observed: the script harness (expected 682 cases, 0 failures) and the Neptune and EMR availability check.
