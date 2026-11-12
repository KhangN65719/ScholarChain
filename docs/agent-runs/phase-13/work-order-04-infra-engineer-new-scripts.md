# Work order 04: infra-engineer, the four new scripts

Owner: infra-engineer. Local only: no AWS calls, no credentials, no `terraform plan/apply/destroy`, no docker build or push, and no sbt run by you inside this order (you may write the sbt calls; the human or the test gate runs them). Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Do not commit. Build on `scripts/lib.sh` and the conventions of the existing scripts (strict mode, `STEP[n] name: start|ok|skip|FAIL`, `EVIDENCE:`, `--help`, `--print-digest`, `--dry-run` from work order 03).

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/{prompt,decisions}.md (D4 to D9), reports 01 and 03 in docs/agent-runs/phase-13/, scripts/README.md, scripts/lib.sh and one or two existing scripts for style. Also read build.sbt (aliases `check`, `quick`, `packageJobs`, `dockerLocal`), the Dockerfile and packaging/**, `.claude/hooks/*.sh` and `.claude/settings.json` (read only), tests/check_test_marker.py, and infra/terraform/environments/course.

## 0. First: repair the five shellcheck findings (human run, docs/agent-runs/phase-13/checks-1.md)

`bash -n` passed on all seven existing scripts and `shellcheck` reported exactly these. Fix them before building on `lib.sh`, without changing real-path behavior: deploy.sh:150 and :215 (SC1010, quote the literal `done` step name; keep the printed step name `done`); lib.sh:133 `SC_SHIFT`, :740 `SC_PLAN_EXIT`, :791 `SC_PLAN_META` (SC2034: check whether any script reads each one; if read, add `# shellcheck disable=SC2034` with the reason; if not, remove it); rollback.sh:196 (SC2015, rewrite the `A && B && C || D` chain as an `if` with identical semantics). Say in the report how you verified each is behavior-preserving. Also note the human's earlier answers: `bash -n` passed, `terraform fmt/init/validate` passed.

## 1. scripts/check.sh (no AWS, no approval record)

Order of gates; every gate prints `STEP[n] name: start|ok|FAIL`; the script keeps going after a failure only when `--keep-going` is given, and always exits nonzero if any gate failed. Default is stop at first failure.
1. Agent constitution and JSON: run the existing validator `.claude/hooks/validate-agent-files.sh` if it exists (read only; do not edit it) and parse every `*.json` under `.claude/` and `.mcp.json` with `python3 -I -c` (python3 is already a project prerequisite; no other tool).
2. Hook syntax: `bash -n` on every `.claude/hooks/*.sh` and `python3 -I -m py_compile`-equivalent check for hook `.py` files if any (do not write `__pycache__` into the repo: use a temp dir or `compile()` with no output file).
3. Shell syntax and shellcheck: `bash -n` and `shellcheck -x` on `scripts/*.sh`. If `shellcheck` is absent, print `SHELLCHECK=absent` and treat it as a failed gate unless `--allow-missing-shellcheck` is given (then `STEP skip` with the reason).
4. sbt gates: one `sbt check` (which already includes scalafmtCheck, `Test/compile`, tests and the marker; document that it covers "scalafmt check, sbt Test/compile, sbt test") unless `--sbt quick` is given (then `sbt quick`). Contract and schema validation: say which tests in the sbt run are the contract and schema tests (read the test names under tests/contracts and modules/*/src/test) and print that mapping; do not invent a separate runner. Use the exact bare two-token sbt forms. Never run two sbt commands. Honor `--skip-sbt` (prints `STEP skip` and the reason, and the final summary says sbt was skipped, so a green result never implies sbt ran).
5. Terraform: `terraform fmt -check -recursive infra/terraform` and `terraform -chdir=infra/terraform/environments/course init -backend=false -input=false` then `validate`, only these. `--skip-terraform` skips with a printed reason. Never run plan, apply or destroy.
6. Final summary line: `CHECK_RESULT=PASS|FAIL`, each gate's status, and which gates were skipped.
`--dry-run` prints the gate list and what each would run; runs nothing.
Document prerequisites and a clean-checkout invocation. Exit codes per the table from work order 03.

## 2. scripts/package.sh (no AWS, no push)

Builds Hadoop and Spark artifacts and the two service images, records checksums and image tags derived from the Git SHA. Build on what `deploy.sh` expects (the `lib/` directories from sbt packaging for `--services-context` and `--loader-context`, and the Dockerfile). Rules:
- Refuse a dirty tree and a detached HEAD unless `--allow-dirty` is given; a dirty build tags `<sha>-dirty` and says so, and `deploy.sh` must still refuse it (check that it does, and report).
- Local only: `sbt packageJobs`/`dockerLocal` as defined in build.sbt, `docker build` for the two images with `--platform` taken from the `cpu_architecture` the same way plan metadata does it or an explicit `--platform` argument. No `docker push`, no ECR login. If Docker is absent, `--skip-images` records that.
- Record in `--out-dir` (absolute, outside the repository, same rule as plan.sh): `package-manifest.json` or `.env`-style manifest with commit SHA, image tags, artifact paths, sha256 of each artifact, platform, tool versions (`sbt`, `java`, `docker`), start and end times. A checksum file in `sha256sum`-compatible form.
- Check what the sbt aliases actually do first; if `packageJobs` or `dockerLocal` do not produce what `deploy.sh` needs, report the gap as `BLOCKED-ON build-engineer` with evidence rather than editing build.sbt.
- `--dry-run` as above. Never two sbt commands at once.

## 3. scripts/run-experiment.sh (approval-gated, manifest-driven)

Executes only an approved experiment manifest. Define the manifest contract here (scripts/README.md section "Experiment manifest contract") as a flat `KEY=VALUE` file parsed, never sourced, like the approval record, so no YAML or JSON tool is needed. Minimum keys: manifest version, experiment id (A to F from the course), description, environment, region, account id, dataset snapshot, size tier, resource limits (max minutes, max concurrency, max tasks, spend ceiling), stop conditions, metric names and CloudWatch namespaces to export, output location (outside the repository by default), and the sbt or script entry point the experiment runs. Rules:
- Requires `--manifest`, `--approval-record`, `--environment`, `--account-id`, `--region`, `--cost-ceiling-usd`. The approval record action is `experiment`; add it to the record's allowed actions and to `lib.sh` verification without weakening any existing check. The record must bind the manifest by sha256 (add the field to the digested arguments, not a new unchecked field).
- Refuses a manifest with a missing or out-of-range limit, a limit above the tier cap or the cost ceiling, a region other than us-east-1, a dirty tree, an unknown experiment id, an output path inside the repository, or one that scales beyond what the manifest's tier allows. No unbounded load: concurrency and duration are required and capped.
- Records commit SHA, resource limits, start and end times (UTC), Region, account, run ID and metric locations in `run-record.env` in the output directory. Raw output files are written once and the script refuses to overwrite an existing run directory (immutability).
- It runs no production algorithm change and tunes nothing. A real run needs live AWS; in this phase only `--help`, `--print-digest`, `--dry-run` and refusals are exercisable. In dry-run it validates the manifest and record fully and prints the steps.
- Timeouts on every cloud operation; retry only idempotent reads.
- Experiment manifests themselves are authored by experiment-engineer in the next work order; you only define and document the contract and a minimal valid example inside scripts/README.md.

## 4. scripts/archive-evidence.sh (no destructive action; no approval record for local archive)

Gathers into a versioned evidence directory: test reports and the `.claude/.last-test-run` marker facts (existence and mtime only, never contents of anything sensitive), plan checksum and plan `.meta` if given, image digests from `deploy` records if given, the pipeline and experiment run manifests and records, selected CloudWatch metric exports if a file path is given (this script does not call CloudWatch; the metrics come from `run-experiment.sh` output paths), and release metadata (commit SHA, tool versions, script digests). Rules:
- Versioned directory `<out-dir>/evidence-<UTC timestamp>-<short sha>/`; refuses to reuse a directory; writes a `MANIFEST.sha256` of every file copied and a `MANIFEST.txt` listing sources.
- Copies files only from explicitly given paths (`--include` repeated) plus a fixed small allowlist of repository files; no globbing from home, no copying anything named like credentials, `.env`, `*.pem`, `*.key`, `*.tfstate*`; refuses and names the file. Scan the copied tree for key material patterns the way plan.sh scans its log and fail if found.
- Optional `--s3-prefix` upload is a real cloud write: it requires `--approval-record` with action `archive`, bound like the others, and `--dry-run` never uploads. Without `--s3-prefix` it is local only and needs no record. Keep the upload path small, use `aws s3 cp` of the verified directory only (no `sync --delete`), with a timeout. Add the `archive` action to the record verification without weakening existing checks.
- `--dry-run` lists what would be gathered and what would be refused.

## Cross-cutting

- Update `lib.sh` only by adding; run `shellcheck -x` and `bash -n` over everything in scripts/ afterwards. Existing scripts' behavior must not change except for the two new record actions.
- Update scripts/README.md: a table of all ten scripts, required environment variable names (names only, never values), human approval points, generated evidence per script, rollback behavior (what rolls back and what does not), the exit code table, and a "from a clean checkout" quick path (`bash scripts/check.sh`, `bash scripts/package.sh --dry-run ...`, and so on) that is exactly what the README reproduction section and the demonstration will use.
- If the Phase 1 guard refuses `bash -n`, `shellcheck` or `bash scripts/...` for an agent, report `BLOCKED-ON` with the refusal text and the exact commands the human must run; do not work around it.
- Before editing, list which check proves each item of your primary gate.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
