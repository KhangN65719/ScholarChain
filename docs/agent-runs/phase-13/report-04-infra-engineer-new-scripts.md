# Report 04: infra-engineer, shellcheck repair and four new scripts (as received, condensed)

STATUS: COMPLETE for the edits. Nothing parsed, linted or run (guard, D11). Every check pending the human.

Files created: scripts/check.sh, package.sh, run-experiment.sh, archive-evidence.sh.
Files modified: lib.sh (two SC2034 disables with reasons plus additions only: `utc_now`, `utc_stamp`, `sbt_run`, `require_external_dir`, `file_mtime_iso`, `safe_name`, `csv_words`); deploy.sh (quoted `'done'` x2); rollback.sh (SC2015 chain rewritten as `if ! -f || ! grep || ! grep`, same branches); scripts/README.md (ten-script table, approval points, env var names, rollback table, clean-checkout path, exit codes, manifest contract with example, per-script sections, pending checks).

Step 0 by reading: SC_SHIFT is read by five scripts (`shift "$SC_SHIFT"`); SC_PLAN_EXIT read by plan.sh; SC_PLAN_META read by deploy.sh and rollback.sh. Disables added, no code change.

## Design
- check.sh: six gates (constitution-json, hook-syntax, shell-syntax, shellcheck, sbt, terraform), each in an errexit subshell; missing shellcheck fails the gate unless `--allow-missing-shellcheck`; `--skip-sbt` is listed in the summary as not covered; sbt gate prints the contract/schema suite mapping (core suites, Phase10WireSuite, services request suites, Phase10ApiContractSuite; none in slowSuites).
- package.sh: one sbt process `sbt packageJobs dockerLocal`, then two local `docker build` calls; platform from `--platform` or `cpu_architecture`; out-dir outside repo and empty, written only at the end; `package-manifest.env` and `SHA256SUMS`. deploy.sh refuses a `-dirty` build twice (clean tree check; image tag must be the 40-hex HEAD SHA) and rebuilds from contexts.
- run-experiment.sh: manifest parsed by awk, never sourced; record's ARGS_DIGEST binds manifest sha256, experiment id, tier, limits; GIT_SHA binds the commit; entry points limited to a tracked `.sh` under experiments/ or load-tests/, or `sbt:<task>`; run directory created once; raw/ and metrics/ made read-only.
- archive-evidence.sh: gather needs no record; upload needs `--upload-dir`, `--s3-prefix` and a record with action `archive` binding prefix, directory name and sha256 of MANIFEST.sha256; gather and upload are separate invocations.
- New actions `experiment` and `archive` needed no lib.sh allow-list change.

## Assumptions (agent)
Tier caps are PROPOSALS, not human-confirmed: minutes 60/90/150, concurrency 4/16/32, tasks 1000/5000/20000 (`tier_caps`). Supported stop conditions: wall-clock, first-failure, spend-estimate (`dlq-nonempty` refused). Test reports expected under target/test-reports (unverified).

## Risks
None of the new code has run. `run_bounded` never run (TERM may reach the sbt launcher, not the JVM). The experiment entry point can exceed MAX_CONCURRENCY or MAX_TASKS; the script enforces only wall-clock and pre-start spend arithmetic and does not pull CloudWatch series. A tree with untracked files (e.g. docs/.DS_Store) is dirty, so package, deploy and run-experiment refuse, including dry runs. Build gaps for deploy.sh: `dockerLocal` is the only context staging (Docker must run); no separate loader context; loader entry point on services classpath UNVERIFIED; nothing uploads packageJobs jars. Archive refuses names matching *secret* and *credential* even if harmless.

Human checks pending: `bash -n scripts/*.sh`; `shellcheck -x scripts/*.sh`; `bash scripts/check.sh --skip-sbt --skip-terraform`; dry-run tripwire with stubs; `check.sh`/`package.sh`/`archive-evidence.sh --dry-run`; refusal cases (limit above cap, region, output path in repo, dirty tree, `.env` to archive, package on dirty tree); `package.sh --skip-images` and a full run; confirm tier caps.
