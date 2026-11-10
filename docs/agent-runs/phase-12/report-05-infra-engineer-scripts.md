# Report 05: infra-engineer (work order 05, scripts), as received (condensed)

STATUS: COMPLETE for authoring. No syntax check ran: the guard refused `bash -n` on all seven files ("Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked."). shellcheck was not tried. No sbt, terraform, aws or script execution.
Files created (scripts/): lib.sh, plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh, README.md. Nothing else touched.

## Validation
No test results. Refusal paths, approval logic and AWS calls are unexercised. Checked by reading only. Written for bash 3.2 (macOS); a syntax error is possible until the human runs `bash -n`. No execute bits are set; run as `bash scripts/x.sh`.
Human command: `bash -n scripts/lib.sh scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh`, and `shellcheck -x scripts/*.sh` only if already installed.

## Gate item to proof
Strict mode and no prompts: visible in source, review confirms. Refusing a dirty tree, wrong account or region, missing or mismatched record: only a Phase 14 run proves it; today only a read of lib.sh (`verify_approval`, `init_common`, `require_clean_tree`). Plan never hidden: `tf_plan_to` uses `tee`, review only. SHA tags and revision check: `verify_revision`, exercised at the Phase 14 deploy. No secret printed: review of smoke-test.sh and `scan_for_secrets`.

## Approval record (KEY=VALUE, parsed never sourced; each key exactly once; unknown keys refused)
APPROVAL_VERSION=1; ACTION (deploy, rollback, pipeline, cleanup, smoke-test); ENVIRONMENT; ACCOUNT_ID; REGION; COST_CEILING_USD; PLAN_DIGEST (sha256 of the saved plan or n/a); SCRIPT_DIGEST (script plus lib.sh); ARGS_DIGEST (exact canonical arguments); GIT_SHA; STATEFUL_CHANGE_ACKNOWLEDGED; APPROVED_BY (agent or role-like names refused, a weak check); APPROVED_AT; EXPIRES_AT (UTC ISO 8601). `--print-digest` prints SCRIPT_DIGEST, ARGS_CANONICAL and ARGS_DIGEST and exits before any cloud call; `plan.sh` prints PLAN_DIGEST.

## Parameters
All: `--environment`, `--account-id`, `--region` (only us-east-1), `--cost-ceiling-usd` (0 < N <= 25), `--out-dir`, `--print-digest`; all but plan.sh take `--approval-record`. Plans and logs default to `${TMPDIR:-/tmp}/scholarchain-ops/<env>`.
plan: `--image-tag`, `--extra-var-file`. deploy: `--plan-file`, `--services-context`, `--loader-context`. rollback: `--image-tag` with `--plan-file`, or `--state-bucket`, `--state-key`, `--state-version-id`. smoke-test: `--fast-url`, `--slow-url`, `--query-secret-arn`, `--bedrock on|off`, optional `--expected-image-tag`, `--question`, `--titan-model-id`, `--haiku-profile-id`, `--vector-dimension`. run-bounded-pipeline: `--size`, `--max-minutes`, `--spend-ceiling-usd`, `--hourly-burn-usd`, `--stop-on`, `--input-file` (all required). cleanup: `--mode`, `--i-am-the-human`, `--image-tag`, `--plan-file`, `--acknowledge-data-loss`.

## Refusals
All: other region; non-12-digit or mismatched account; ceiling above 25; missing, expired, future-dated or malformed record; any digest mismatch (script, args, plan, Git SHA).
plan: refuses a supplied record; dirty tree marks plan DIRTY=yes. deploy: dirty tree, plan not from clean HEAD, plan digest mismatch, stateful destroy or replace without acknowledgement; applies only the saved plan, never -auto-approve; reuses an existing immutable ECR tag. rollback: tag absent from ECR, plan tag mismatch, dirty tree (image mode), backend not the named bucket and key, lineage mismatch, state version not older than current. smoke-test: URLs with path or credentials, secret ARN outside the account, question over 200 characters or with control characters. pipeline: any missing parameter, `--max-minutes` over the provisional cap (30, 60, 120 for small, medium, large), projected spend over ceiling, spend ceiling over cost ceiling, running execution or active cluster; stops the execution and terminates its EMR clusters on timeout, dead-letter hit or signal. cleanup: no `--i-am-the-human`, `SCHOLARCHAIN_AGENT_RUN` set, no terminal in an agent environment, any environment but course, a flip plan that adds or destroys, a destroy plan that adds or changes, `apply-destroy` without `--acknowledge-data-loss`; lists state and the first 20 keys of each bucket before deleting; no wildcard, no -target, no `aws s3 rm`.

## Assumptions and risks
- Read from modules/services: /healthz, /readyz, POST /v1/query, bearer token. Inferred and unverified: state machine and cluster name `<prefix>-pipeline`, ECR repos `<prefix>-services` and `<prefix>-loader`, queue names, the sbt packaging output that the Dockerfile expects in lib/, the aws CLI `converse` form.
- Size caps are provisional placeholders (work order 06 did not exist). AWS enforces no spend limit; scripts enforce only time, the dead-letter stop and pre-start arithmetic, and report an estimate, not a bill.
- State-version rollback uses `terraform state push -force` after a backup; provisional until the remote backend exists.
- `smoke-test.sh` reads one secret value (the query token) into a mode 600 temp file passed to curl by file and does not print it. This is a read of a secret value; the code-reviewer must confirm it is acceptable.
- A caller who edits both the script and the record defeats the checks; that needs the launcher.

## Blocked on
`bash -n`: refused by the guard. Owner: factory-engineer for a ratified entry, or the human runs the command.

## Phase 13 items
Ratify a launcher that verifies record, script digest, arguments, environment and bounds before the guard opens deploy, rollback, pipeline and cleanup. Decide how a human runs cleanup.sh (a `!` command inside Claude Code is refused by design). Allow a syntax check (`bash -n` or shellcheck) as a ratified entry.

## Phase 14 human actions
Run `bash -n` and shellcheck; create the remote state bucket; decide the Paid-plan upgrade (never "activate advanced features"); provide scoped credentials; confirm the three-size plan, replace provisional caps and burn values, write approval records by hand; run plan.sh, review, sign the digest; set up packaging contexts; open any tunnel the smoke test needs (no public ingress); verify Bedrock access then smoke-test; run cleanup.sh in a terminal after each run and record actual spend.

Next gate: the human runs the syntax checks, then an independent code-reviewer reads scripts/ (at least `verify_approval`, the cleanup guard, secret handling in smoke-test.sh).
