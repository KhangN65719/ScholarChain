# Work order 03: infra-engineer, dry-run and refusal foundation for the existing scripts

Owner: infra-engineer. Local only: no AWS calls, no credentials, no `terraform plan/apply/destroy`, no docker, no sbt. Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Do not commit. Build on scripts/; do not rewrite them.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/{prompt,decisions}.md (D3, D4, D5, D6), docs/agent-runs/phase-13/report-01-infra-engineer-f1-f4.md (what changed in step 1), scripts/README.md, scripts/lib.sh and the six existing scripts (plan, deploy, rollback, smoke-test, run-bounded-pipeline, cleanup).

## Goal

Every existing script gains a `--dry-run` mode that can be exercised on a laptop with no AWS credentials, no network and no Terraform state, and that proves the refusal paths. Behavior of the real (non-dry-run) paths must not change except where this order says so.

## Requirements

1. `lib.sh`: shared dry-run helpers. A dry run is explicit (`--dry-run` flag only; no environment-variable trigger that could silently turn a real run into a fake one, and no flag that turns a dry run real). Guard every call site that reaches `aws`, `terraform` (except nothing: dry-run runs no terraform at all), `docker`, `curl` to a service, `sbt` or any state-changing call behind one function, so a dry run provably cannot reach them. Show how you proved that: list every external-command call site in each script and the guard in front of it.
2. Every script in dry-run prints, on stdout and greppable:
   - `DRY_RUN=yes`
   - `TARGET_ACCOUNT=<12 digits from --account-id>` and `TARGET_REGION=<region>` and `TARGET_ENVIRONMENT=<env>` taken from arguments (`sts` is not called in a dry run). Say in the output that the identity check is skipped.
   - `STEP[n] name: dry-run` for each step it would run, in order, using the same step names as the real run.
   - `SCRIPT_DIGEST` and `ARGS_DIGEST` as `--print-digest` does.
3. Approval-gated scripts (deploy, rollback, pipeline, cleanup, smoke-test): in dry-run they still require `--approval-record` and run the full record verification (fields, digests, expiry, 24 h window, cost ceiling, account and Region match, stateful acknowledgement). They must NOT call `consume_approval` in a dry run (a dry run must leave the record usable), and must say so (`APPROVAL_VERIFIED` then `APPROVAL_NOT_CONSUMED dry-run`). A missing, expired, mismatched or already-consumed record is refused exactly as in a real run, nonzero exit, `EVIDENCE:` on stderr. Where a real run needs a plan file or context directories that a dry run cannot have, state in the output which checks were skipped and why; never claim a skipped check passed.
4. `plan.sh` takes no approval record (unchanged). Dry run prints the target, the terraform commands it would run (as text) and exits 0 without calling terraform or aws.
5. `cleanup.sh`: the dry run lists resources before any deletion, from local files only: the modules and resource types in `infra/terraform` that `cleanup.tfvars` would flip, and the names of the S3 buckets, DynamoDB tables, vector bucket, ECR repositories and Neptune resources the Terraform declares, labelled `SOURCE=terraform-source (not live)`. It says the live listing runs at real execution. Its existing human-only refusals (`--i-am-the-human`, `SCHOLARCHAIN_AGENT_RUN`, terminal check, `--acknowledge-data-loss`) must apply in dry-run too, except that a dry run may be invoked by an agent solely to prove the refusals; it must still never perform any deletion. Make the order of checks explicit and refuse before anything is listed when the arguments are wrong.
6. Exit codes: document a small, fixed table in `scripts/README.md` (0 ok, 2 usage, 3 refused, 4 failed gate or check, and any others already used). Existing nonzero codes stay as they are; map them in the table rather than renumbering.
7. Cloud operations timeouts and retries: confirm every real AWS or docker call in the six scripts has a bounded timeout and that only idempotent reads are retried. Fix gaps you find within the requirement; list each in the report.
8. Secrets: confirm no script prints a secret, key material or the contents of an approval record beyond the named fields. Keep the existing scan.

## Checks

- `bash -n` and `shellcheck -x` on every file under scripts/ (shellcheck is installed). Fix every shellcheck finding in files you touch, or justify an inline `# shellcheck disable=` with the reason. Do not add other tools.
- If the Phase 1 guard refuses an agent the `bash -n`, `shellcheck`, or `bash scripts/...` form, do not work around it: report `BLOCKED-ON` with the refusal text, and list exactly which commands the human must run. Do not switch interpreters to dodge the guard.
- Before editing, list which check proves each item of your primary gate.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
