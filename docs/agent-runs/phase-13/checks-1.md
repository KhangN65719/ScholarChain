# Human check run 1 (2026-11-11), after work orders 01 to 05

Command: `bash -n` on 7 scripts; `shellcheck -x scripts/*.sh`; `terraform fmt -check -recursive -diff infra/terraform`; `terraform -chdir=infra/terraform/environments/course init -backend=false -input=false`; `validate`; `grep -rn "step_timeout_seconds" infra scripts docs tests modules`. Log: /Users/winnguyen/scholarchain-data/logs/phase-13-checks-1.log. Overall exit 1 (shellcheck).

| Check | Result |
|---|---|
| `bash -n` on lib, plan, deploy, rollback, smoke-test, run-bounded-pipeline, cleanup | no output, passed |
| `terraform fmt -check -recursive -diff` | no diff, passed |
| `init -backend=false` | success (aws 6.26.0 from the lock file) |
| `validate` | "The configuration is valid." |
| stale-reference grep | no stale reference; hits are historical rows (infra README:130, reports, Phase 12 records) and substring matches of `emr_step_timeout_seconds` |
| `shellcheck -x scripts/*.sh` | 5 findings, below |

## shellcheck findings (route to infra-engineer, folded into work order 04 step 0)
- deploy.sh:150 `dry_step done` and deploy.sh:215 `step done`: SC1010 (warning), the literal `done` as an argument. Quote it.
- lib.sh:133 `SC_SHIFT`, lib.sh:740 `SC_PLAN_EXIT`, lib.sh:791 `SC_PLAN_META`: SC2034 (warning), appear unused. If scripts read them, add a `# shellcheck disable=SC2034` with the reason; if nothing reads them, remove them.
- rollback.sh:196: SC2015 (info), `A && B && C || D` is not if-then-else. Rewrite as an `if`.
Fixes must not change real-path behavior.

Still pending, not run: the dry-run tripwire test, the refusal cases, and a real `docker login --password-stdin` through `run_bounded`.
