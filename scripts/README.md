# Operational scripts (Phase 12, authored and never executed)

Status: written and **never executed**; the scripts must not run in Phase 12. On 2026-11-09 the human ran `bash -n` on all seven scripts after repair round 1 (exit 0; `terraform-checks.md`, Run 3). That proves they parse and nothing else. `shellcheck` is not installed, so it was not run. Repair round 2 (work order 15) then edited `scripts/lib.sh` and `scripts/smoke-test.sh`, so **`bash -n` has not been run on those two as they now stand** and must be run again (see the end). Nothing here touched AWS.

Run each as `bash scripts/<name>.sh ...` (no execute bit is set). Every script has `--help`, `--print-digest` and uses `set -euo pipefail`, no prompts, and greppable markers: `grep 'STEP\[' log` shows `STEP[n] name: start|ok|skip|FAIL`. Other markers: `EVIDENCE:` (stderr, the reason for a refusal), `PLAN_DIGEST=`, `APPROVAL_VERIFIED`, `DEPLOYED_REVISION=`.

Execution stays with the human-controlled path. Agent shell calls to deploy, rollback, pipeline and cleanup are blocked by the Phase 1 guard until a launcher is ratified (Phase 13 item below). These scripts are the reviewed artifact that launcher would verify by digest.

## Shared parameters

| Parameter | Rule |
|---|---|
| `--environment` | a directory under `infra/terraform/environments/` (only `course` exists) |
| `--account-id` | 12 digits. Compared with `sts get-caller-identity` and the approval record |
| `--region` | optional, default and only value `us-east-1` |
| `--cost-ceiling-usd` | 0 < N <= 25 (the instructor's cumulative ceiling). Must equal the record |
| `--approval-record` | path to the record below. Not used by `plan.sh` |
| `--out-dir` | absolute path. Default `${TMPDIR:-/tmp}/scholarchain-ops/<env>`, outside the repository so plans (which can hold resource attributes) are never committed |
| `--print-digest` | prints `SCRIPT_DIGEST` and `ARGS_DIGEST` for the record and exits before any cloud or Terraform call |

## Approval record

A plain `KEY=VALUE` file, `#` comments allowed, no spaces in values, every key exactly once, no other keys. It is parsed, never sourced.

| Field | Meaning |
|---|---|
| `APPROVAL_VERSION` | `1` |
| `ACTION` | `deploy`, `rollback`, `pipeline`, `cleanup` or `smoke-test` |
| `ENVIRONMENT`, `ACCOUNT_ID`, `REGION` | must equal the parameters; region must be `us-east-1` |
| `COST_CEILING_USD` | must equal `--cost-ceiling-usd` and be <= 25 |
| `PLAN_DIGEST` | sha256 of the saved plan file (printed by `plan.sh`), or `n/a` for `pipeline` and `smoke-test` and for state-version rollback |
| `SCRIPT_DIGEST` | sha256 of the script file followed by `lib.sh`. Any edit to either invalidates the record |
| `ARGS_DIGEST` | sha256 of the exact canonical arguments (`--print-digest` shows the string). For `deploy` the arguments include the image tag, the docker platform and a content digest of each build context directory |
| `GIT_SHA` | the commit the plan was made from (`deploy`, image-tag `rollback`), else `n/a` |
| `STATEFUL_CHANGE_ACKNOWLEDGED` | `yes` or `no`. A plan that destroys or replaces a stateful resource needs `yes`, as does `cleanup` destroy |
| `APPROVED_BY` | the human. Names that look like an agent or role are refused (a weak check; the real control is that a human writes the file) |
| `APPROVED_AT`, `EXPIRES_AT` | UTC like `2026-11-20T15:04:05Z`. `APPROVED_AT` not in the future, `EXPIRES_AT` not past, and **`EXPIRES_AT` at most 24 hours after `APPROVED_AT`** (`SC_APPROVAL_MAX_SECONDS` in `lib.sh`) |

Single use. Each apply-class script calls `consume_approval` after the record, plan binding and identity checks pass and just before its first billable or state-changing call. It makes a marker directory `${SCHOLARCHAIN_OPS_STATE_DIR:-$HOME/.scholarchain-ops}/consumed/<sha256 of action, ARGS_DIGEST, PLAN_DIGEST and APPROVED_AT>`; `mkdir` is atomic, so a second use of the same record, or a racing second run, is refused with "already consumed". A run that fails after the marker is written still consumes the record: a retry needs a new record (conservative, because a retry can re-spend). `smoke-test` and `pipeline` consume at their first call as well. `plan.sh` takes no record.

What this does and does not stop. It stops an accidental or scripted second run of the same record, a stale record lying around for weeks, and a record reused after a failed run. It does **not** stop a caller who can write a record: every digest is printable with `--print-digest`, so a caller can write a new record with a fresh `APPROVED_AT`, which is a new marker. The real control is the human signing the digest, and, in Phase 13, a ratified launcher that verifies the approval record itself. The checks in these scripts are consistency checks, not authorization.

Two more things it does **not** stop (review 2, finding L-e):

- `SCHOLARCHAIN_OPS_STATE_DIR` pointing at an empty marker directory. The single-use markers are only directories under that path, so any caller who sets the variable to a fresh directory makes a consumed record usable again. A small hardening was added: `check_state_dir` (called by `verify_approval` and `consume_approval`) refuses a relative path, `.` or `..` segments, and a location inside the repository or `--out-dir`, and prints `STATE_DIR_OVERRIDE=<path>` when the variable is used so the override is visible in the log. It cannot refuse an empty directory elsewhere (for example a new directory under `HOME`), because the script has no way to know which directory held the earlier markers.
- The `smoke-test.sh` `--out-dir` repository check. It was a plain string-prefix match, which a symlink into the repository, a doubled slash or a different letter case on macOS (a case-insensitive file system) got past. It now compares physical paths with letter case ignored (`physical_path` and `path_within` in `lib.sh`): symlinks in the existing part of the path are resolved, doubled slashes are squeezed, and `.` and `..` segments and relative paths are refused. It is still a check at one moment: a symlink swapped after the check, a hard link or a bind mount gets past it. The check was written without being run (only `bash -n` can be run on it, by the human), so it is UNVERIFIED beyond syntax; a Phase 14 test with a symlink, a `//` path and a case-changed path should confirm it.

To make one: run the script with `--print-digest` and the intended parameters, copy `SCRIPT_DIGEST` and `ARGS_DIGEST`, add the plan digest from `plan.sh`, review the printed plan, and write the file yourself.

## Scripts

| Script | Touches | Refuses |
|---|---|---|
| `plan.sh` | `terraform init`, `fmt -check`, `validate`, `plan -out`. Reads state; writes plan, `.meta` (which now includes `CPU_ARCHITECTURE`, read from the var files, last assignment wins, default X86_64) and log under `--out-dir`. Needs credentials in the caller's environment (`sts:GetCallerIdentity` is called at plan time). Applies nothing. Prints the plan in full and tees it, then lists counts, IAM changes, high-cost creations and stateful destroy or replace, and scans the log for key material | another account or region, a cost ceiling over 25, a supplied approval record |
| `deploy.sh` | `terraform apply <saved plan>` (never `-auto-approve`, never an unsaved plan), `docker build/push` to the two ECR repositories with the Git SHA as the tag, read-only ECS and ECR describes to verify. Needs `--services-context` and `--loader-context` directories holding `lib/` from `sbt` packaging. Those directories are not in Git, so their content digests (sorted file paths and sha256s; a symlink is refused) go into the digested arguments, together with the image tag and the docker platform. The platform is `linux/amd64` for `cpu_architecture` X86_64 and `linux/arm64` for ARM64, taken from the plan's `.meta` and re-checked against the `cpu_architecture` Terraform output right after the apply, before any image is built. The context digests are re-checked just before each build. `--print-digest` now needs `--plan-file` and its `.meta` | a dirty tree, a plan not made from HEAD or from a dirty tree, a plan digest not in the record, an expired or mismatched record, a stateful change without acknowledgement. An existing immutable tag is reused, not rebuilt |
| `rollback.sh` | image mode: applies a saved plan made with `plan.sh --image-tag <old>`, then verifies. State mode: backs up state, checks lineage and serial, `terraform state push -force` of the chosen S3 version, prints a drift plan | a tag absent from ECR, a plan whose tag differs, a dirty tree (image mode), a backend that is not the named bucket and key, a lineage or serial mismatch |
| `smoke-test.sh` | HTTP GET `/healthz`, `/readyz` on both services, one `POST /v1/query` (sync, 60 s cap, 200 character question), optional Bedrock single-request checks, optional revision check. Reads one Secrets Manager value into a mode 600 temp file passed to curl by file, deleted on exit, never printed. It is not side-effect free: the query may write a query-session row and an evidence-bundle row to DynamoDB (an unconfirmed assumption about the services) and may call Bedrock, and `--bedrock on` makes two billed calls. **It cannot reach the services today**: see "Smoke-test network requirement" below | URLs with a path or credentials, a secret ARN outside the account, a missing record, an `--out-dir` inside the repository (physical path, letter case ignored) or with `.` or `..` segments. `--bedrock off` skips Bedrock |
| `run-bounded-pipeline.sh` | starts one Step Functions execution, polls it, may stop it, lists and terminates EMR clusters named `<prefix>-pipeline` created after the start. Reads SQS dead-letter depth | a missing size, wall-clock limit, spend ceiling, hourly burn, stop condition, input file or record; a limit over the size cap; projected spend (burn x minutes) over the spend ceiling or a spend ceiling over the cost ceiling; a running execution or active cluster already present |
| `cleanup.sh` | four modes (`plan-flip`, `apply-flip`, `plan-destroy`, `apply-destroy`). Lists state resources and the first 20 keys of each bucket before any deletion. Deletes only through `terraform destroy` of a saved plan built with `course.tfvars` plus `cleanup.tfvars` | no `--i-am-the-human`, `SCHOLARCHAIN_AGENT_RUN` set, no terminal with an agent environment, any environment but `course`, a flip plan that adds or destroys, a destroy plan that adds or changes, missing `--acknowledge-data-loss` |

`cleanup.sh` never uses a wildcard, `-target`, `aws s3 rm` or a console step. `--i-am-the-human` is an agreement, not a security control. Two phases are needed because `force_destroy` must be in state before a destroy empties a bucket. `cleanup.sh` is the end-of-project full teardown; it deletes storage. It is not the between-session step (next section).

Gone-checks after the destroy. The two general-purpose buckets use `aws s3api head-bucket`. The S3 Vectors bucket is not a general-purpose bucket, so `head-bucket` does not apply to it; the script runs `aws s3vectors get-vector-bucket --vector-bucket-name`. That command, and the not-found message it matches (`NotFoundException`, `NoSuchVectorBucket`, "does not exist"), are **UNVERIFIED**: written from the service's naming, not read from the CLI reference or run. The check fails closed: `GONE` only on a not-found message; access denied, an unknown command, a network or credential problem and any unrecognized error print `VECTOR_BUCKET_COULD_NOT_VERIFY` and fail the run after the destroy has applied, so the human confirms the bucket by hand. A 403 is never read as gone. The plan metadata `DIRTY` field now records the real tree state instead of a constant `no`.

### Session up and session down (keep storage, stop the hourly billing)

Added for review finding M3. It uses only the existing scripts and approval records and adds no new destructive capability. Details and the caveats are in `infra/terraform/README.md`, "Session up and session down".

1. Session down: `bash scripts/plan.sh --environment course --account-id <id> --cost-ceiling-usd <n> --extra-var-file <abs path>/infra/terraform/environments/course/session-down.tfvars`, at the commit that is deployed. Read the plan: it removes the interface endpoints, the Neptune cluster and instance and sets service tasks to 0; it must not destroy a bucket, table, vector bucket, index, repository or secret.
2. `bash scripts/deploy.sh --print-digest ...` with that plan, write the approval record (`STATEFUL_CHANGE_ACKNOWLEDGED=yes`, because Neptune is a stateful destroy), then `bash scripts/deploy.sh ...`. Nothing is rebuilt (the image tag for HEAD exists), but the two context directories are required and digested.
3. Session up: the same two scripts with `course.tfvars` plus one `experiment-size-*.tfvars` overlay passed as `--extra-var-file`. Never combine `session-down.tfvars` with a size overlay.
4. Neptune keeps no snapshot in the course environment, so each session reloads its graph from S3.

Every apply needs its own record, and each record is single use.

### Smoke-test network requirement (finding M9, a Phase 14 decision)

No host or task is in the client security group, ECS execute-command is off (no `ssm` or `ssmmessages` endpoint), and Cloud Map is off by default, so no `*.internal` name resolves; the URLs are task private IPs (the script's example uses IPs now). The script opens no tunnel. Options (a short-lived in-VPC client task, ECS Exec, a human-opened tunnel or bastion, enabling Cloud Map) and their costs are in `infra/terraform/README.md`, "Smoke-test network path". Until Phase 14 picks one, the smoke test cannot be run from anywhere.

### Principals

The scripts do not run under the Terraform `deployer` role. `plan.sh`, `deploy.sh`, `rollback.sh`, `cleanup.sh` and `smoke-test.sh` run under the human's broad credentials. `run-bounded-pipeline.sh` also needs the human's credentials, because the deployer role lacks `states:ListStateMachines`, EMR list and terminate, and SQS calls. `deploy.sh` runs `terraform apply`, which the deployer role cannot do, so the claim that the deployer role separates deployment from apply was wrong and is withdrawn. Permissions per script are in `infra/terraform/README.md`, "Who runs what".

### Pipeline sizes (provisional)

`--size small|medium|large` is tier S, M or L of the three-size plan in `infra/terraform/COST_ESTIMATE.md` (5,000, 25,000 and 100,000 works; decision D2c). It caps `--max-minutes` at 60, 90 and 150. The sizes are proposed and not yet confirmed by the human, so the caps stay provisional. They come from the estimate's HIGH pipeline node-hours on one node (S 1.0, M 1.4, L 2.4) rounded up. The previous placeholders were 30, 60 and 120, and the S and M caps were raised because the estimate's HIGH hours exceed them. Reference hourly burn for the whole session while the run is up, at HIGH rates (all unverified), default endpoint egress and Neptune on (the size overlays turn it on): one m5.xlarge node 0.35 + loader task 0.0247 (0.5 vCPU, 1 GB, the Terraform default) + 8 interface endpoints at 0.015 each, 0.12 + Neptune `db.t4g.medium` 0.10 = 0.59 USD an hour; with the overlay's runtime tasks (0.0123 each) about 0.60 for S (none), 0.62 for M (2 tasks) and 0.65 for L (4 tasks). Master plus 4 core nodes on M: 5 x 0.35 + 0.0247 + 0.12 + 0.10 + 0.0247 = about 2.02 USD. Add 0.015 an hour when `bedrock-runtime` is on. The earlier 0.41 and 1.81 left out the endpoints and Neptune and priced the loader at 1 vCPU, 4 GB (finding L7). Example: `--size small --max-minutes 60 --hourly-burn-usd 0.60 --spend-ceiling-usd 0.75` projects 0.60 USD. The Terraform execution timeout for each size (S 5400 s, M 7200 s, L 10800 s, `pipeline_max_run_seconds`) is the cap plus 30 minutes; it skips the cleanup state when it fires (infra README, "Pipeline bounds"). The per-step timeout (`pipeline_step_timeout_seconds`: S 1800, M 2700, L 3600 s) is set so that a hung second step is cut by the step timeout, which does run the cleanup state, before the execution timeout on S and M. Tier L runs last and needs the human's spend gate (G-L) before the approval record is written; the script does not check it. AWS enforces no spend limit. The script enforces the wall-clock limit, the dead-letter stop and the pre-start arithmetic, and reports an estimate (burn x time), not a bill. Actual spend comes from billing, recorded by the human.

## Idle and cost note

Nothing in these scripts creates a resource that bills while idle except through Terraform. After `deploy.sh`, the default idle items are the eight interface endpoints (nine with Bedrock; $1.92 to $2.88 a day for eight, about $58 to $86 a month, the largest), three secrets and three alarms (the Cloud Map hosted zone is off by default) (see `infra/terraform/README.md`; the rates are mostly UNVERIFIED, each one is marked in `infra/terraform/COST_ESTIMATE.md`). A transient EMR cluster and Neptune cost money only while they exist. After every session run the session-down flow above, which keeps storage; `cleanup.sh` is the end-of-project teardown. Neptune and EMR need the Paid plan (Phase 14 human decision); nothing here recommends or scripts "activate advanced features".

## Checks the human runs

```
bash -n scripts/lib.sh scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh
shellcheck -x scripts/*.sh        # only if already installed
```

Result so far: `bash -n` exit 0 on all seven scripts (human, 2026-11-09, `terraform-checks.md` Run 3, on the repair round 1 files). `shellcheck` is not installed (`command -v shellcheck` printed nothing), so it has not run, and no tool was installed. Repair round 2 changed `lib.sh` and `smoke-test.sh`: run `bash -n` again.

`bash -n` proves syntax only. It does not prove any refusal path, the approval logic, the new path checks or the AWS calls, which are unexercised.

## Open items

- Phase 13 (launcher and guard): a ratified launcher must verify the approval record, script digest, arguments, environment and bounds before the guard opens `deploy`, `rollback`, `run-bounded-pipeline` and `cleanup`. These scripts verify the same fields themselves, but a caller does not need to edit the script to defeat them: any caller can write a record, because every digest is printable with `--print-digest`, and can pick a fresh `APPROVED_AT` to get a new single-use marker. The 24-hour cap and the consumed marker stop stale or repeated use of one record, nothing more. The real control is the human signing the digest and the Phase 13 launcher. The `!` path inside Claude Code is refused by `cleanup.sh` by design.
- Phase 13: the guard blocks `bash -n` for agents ("Shell and interpreter wrappers are blocked"), so a syntax check needs a ratified entry or a human run. The human ran it on 2026-11-09 (exit 0).
- Phase 14: UNVERIFIED items introduced or kept in repair round 1: the `aws s3vectors get-vector-bucket` command and its not-found text; the ECS retry behavior for a first apply with desired count above 0 before the image push; whether `date -d` or `date -j -f` (the two forms `lib.sh` tries) both parse on the operator's machine; the Neptune bulk-loader egress rule (infra README, finding L1).
- Phase 14: unverified assumptions. State machine name `<prefix>-pipeline`, EMR cluster name `<prefix>-pipeline`, ECR repositories `<prefix>-services` and `<prefix>-loader`, queue names, ports 8080 and 8081, routes `/healthz`, `/readyz`, `/v1/query` (read from `modules/services`), the sbt packaging output that `Dockerfile` expects in `lib/`, Bedrock model and profile IDs, and the `aws` CLI `converse` and `--query` forms. State-version rollback needs the remote backend that does not exist yet.
- Phase 14: the remote state bucket, Paid-plan upgrade, budget alerts and scoped credentials are human actions, as listed in `infra/terraform/README.md`.
