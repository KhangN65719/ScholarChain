# ScholarChain runbook

Written 2026-11-19 from `scripts/README.md`, `infra/terraform/README.md`, `infra/terraform/SESSION2.md`, `docs/releases/phase-14/` and `docs/releases/phase-15/`. Documentation-engineer has no shell and replayed nothing. Every command below is quoted from those files and is marked NOT REPLAYED until the human's replay (H-8) is archived. The phase records say whether a command ran on a real account in an earlier phase; that is cited as OBSERVED (record), and is not a replay by this document.

Rules that apply to every section:

- Agents never run deploy, rollback, pipeline, cleanup, apply or destroy. The human runs them with the `!` prefix. The guard hook refuses them for agents (`.claude/hooks/policy.py`, `.claude/settings.json`; see [threat model](threat-model.md) section 7).
- Every apply-class script needs its own single-use approval record (action, script digest, argument digest, plan digest, account, Region us-east-2, cost ceiling at most 20, expiry at most 24 hours). Write each record yourself after `--print-digest`.
- Region is us-east-2 only. Account ids are masked in records as `000000000000`; replace `<id>` with the approved 12-digit account in your shell, never in a file in the repository.
- Cost gate: AWS spend limit 20 USD; AWS Budget 25 USD (alerts only); code ceiling 20 USD; the operating stop is near 15 USD (the Phase 17 plan uses 14 USD). Read Billing before any session. See [service limits](service-limits.md) section 3.
- No credential value goes into a file, a chat, a log or a record.

Related: [architecture](architecture.md), [API reference](api.md), [data lineage](data-lineage.md), [Terraform README](../infra/terraform/README.md), [scripts README](../scripts/README.md).

## 1. Local checks (no AWS)

Source: `scripts/README.md`, "From a clean checkout". Prerequisites: bash 3.2 or later, git, python3, shellcheck, JDK 21, sbt, Terraform; Docker with a running engine for `package.sh` unless `--skip-images`.

```
git clone <repository-url> ScholarChain && cd ScholarChain
bash scripts/check.sh --dry-run
bash scripts/check.sh
bash scripts/package.sh --dry-run
bash scripts/package.sh --out-dir /abs/outside/repo/package-1
bash scripts/archive-evidence.sh --dry-run --include /abs/outside/repo/package-1
bash scripts/archive-evidence.sh --include /abs/outside/repo/package-1
bash scripts/plan.sh --dry-run --environment course --account-id <id> --cost-ceiling-usd 20
bash scripts/run-experiment.sh --help
```

NOT REPLAYED until H-8. Earlier observed (record): the dry-run, refusal and `--help` paths of the scripts under a stubbed harness, `RESULT pass=570 fail=0` (`scripts/README.md`, `docs/agent-runs/phase-13/checks-5.md`). That run did not exercise any real AWS call. Later script edits changed digests. The full script harness was last recorded at `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0` (human run, 2026-11-19; `docs/agent-runs/phase-17/human-commands.md`; `docs/agent-runs/phase-16/ratification.md`, Gates). The hook harness `hooks-guard.sh` was recorded at `RESULT pass=114 fail=0` (Claude in chat on the final Wave A2 files, `docs/agent-runs/phase-17/human-commands.md`). Both are recorded results, not replayed by this document's author.

`check.sh --skip-sbt --skip-terraform` is the fast form; its last line is `CHECK_RESULT=PASS_WITH_SKIPS`, never `PASS`. `CHECK_RESULT=PASS` means every gate ran and passed.

Terraform static checks (`infra/terraform/README.md`, "Checks to run"):

```
terraform fmt -check -recursive -diff infra/terraform
terraform -chdir=infra/terraform/environments/course init -backend=false
terraform -chdir=infra/terraform/environments/course validate
```

OBSERVED (record): passed on 2026-11-11 (`docs/agent-runs/phase-13/checks-1.md`). NOT REPLAYED here.

## 2. Deployment

A session is "up" when compute, network and Neptune exist and "down" when only storage remains. The sequence below follows the Phase 15 session 2 apply order (`SESSION2.md` section 5) and the two scripts' contracts (`scripts/README.md`).

### 2.1 Before any apply

1. Read Billing; check the gate rule (billed + lag reserve + the option's high estimate against the stop). NOT RECORDED as a script; it is a human reading.
2. Confirm the three auth secrets hold a value. Terraform creates `scholarchain-course/auth-query-token`, `auth-read-token` and `auth-cursor-secret` empty, and a task that starts without them fails with `ResourceInitializationError` (OBSERVED, session 2, `docs/releases/phase-15/README.md` finding 1). The canonical check and fill commands are in `infra/terraform/SESSION2.md` section 8, "Secret values present" and "Secret fill". They are not copied here, so the one copy stays correct. Do not proceed unless that check prints `SECRETS_PRESENT=yes`.
3. Work from a clean tree at the commit you intend to run. `deploy.sh` refuses a dirty tree and requires the image tag to equal the 40-character HEAD SHA.
4. Package: `bash scripts/package.sh --out-dir /abs/outside/repo/package-N` (builds the jars and the two images; no push).

### 2.2 Plan, record, apply

Plan (read only, applies nothing):

```
bash scripts/plan.sh --environment course --account-id <id> --cost-ceiling-usd <n> --extra-var-file <abs>/infra/terraform/environments/course/<overlay>.tfvars
```

Session up uses `course.tfvars` plus one overlay (for example `experiment-size-s.tfvars`); session down uses `session-down.tfvars` (never together with a size overlay). `plan.sh` prints `PLAN_DIGEST=`, counts, IAM changes, high-cost creations and `STATEFUL_DESTROY_OR_REPLACE`. Read it. Anything outside the expected counts is a stop. Expected counts per apply are in `SESSION2.md` sections 5, 5a and 5b (read from code, not planned, until a real plan settles them).

Record and apply:

1. `bash scripts/deploy.sh --print-digest ...` with the saved plan. The full argument line (plan file, `--services-context`, `--loader-context`, `--approval-record`, image tag, shared parameters) is not reproduced in the files read here as one line; run `bash scripts/deploy.sh --help` for it. NOT RECORDED.
2. Write the approval record (`ACTION=deploy`, plus `STATEFUL_CHANGE_ACKNOWLEDGED=yes` when the plan destroys or replaces a stateful resource, and a typed IAM approval line when IAM changes).
3. `bash scripts/deploy.sh ...` with the same arguments. It applies the saved plan (never `-auto-approve`), logs in to ECR, pushes the services and loader images under the Git SHA, then verifies the deployed revision. Markers: `DEPLOY_COMPLETE`, `IMAGE_*`, `DEPLOYED_REVISION=`.

OBSERVED (record): five deploys reached `DEPLOY_COMPLETE` in the Phase 15 evening session (A1 to A4, `docs/releases/phase-15/README.md`) and, in Phase 14, four deploys (12 to 15) and one session-down (`docs/releases/phase-14/README.md`, "Outcome in one table"). NOT REPLAYED here.

Notes from the records:

- `deploy.sh` applies first and pushes images afterwards, so for a short time task definitions point at a tag not yet in ECR (`docs/releases/phase-14/README.md`, "Open items").
- The load-client image is not pushed by `deploy.sh`; a human push under the HEAD SHA is needed after A2 and before the first `run-task` (`SESSION2.md` section 6a and section 8).
- Do not commit between the first apply of a session and the teardown: the image tag is the Git SHA (`SESSION2.md` section 5). Phase 15 broke this once, with a recorded ruling (`docs/releases/phase-15/README.md`, deviation 2).

### 2.3 Data pipeline run

`scripts/run-bounded-pipeline.sh` starts one Step Functions execution (EMR for Hadoop, entity resolution, Spark, export, then the Fargate loader), with a record `ACTION=pipeline`, `--size`, `--max-minutes`, `--spend-ceiling-usd` and `--hourly-burn-usd`. The execution input contract is in `scripts/README.md` ("Execution input contract"). Loader-only mode re-runs the loader from an export already in S3 without creating a cluster (`infra/terraform/README.md`, "Loader-only mode"). The S run 5 command line, quoted from `scripts/README.md`: `--size large --max-minutes 110 --spend-ceiling-usd 1.20 --hourly-burn-usd 0.62`. NOT REPLAYED. Observed results: [experiments/RESULTS.md](../experiments/RESULTS.md) and `docs/releases/phase-14/runs-s-6-to-11.md`.

## 3. Smoke tests

Source: `scripts/README.md`, "`smoke-test.sh` coverage" and "Smoke-test network requirement"; `docs/releases/phase-15/smoke-tn.md`.

`smoke-test.sh` needs a record `ACTION=smoke-test` and a required `--question` (at most 200 characters, no default; the wording is the human's). It checks, in order: identity, health, readiness, a bounded `POST /v1/query` (200, `abstained` false, at least one evidence item), optional Bedrock single-request checks, deployed revision, idempotency (replay returns the same `queryId`; a changed body returns 422 `IDEMPOTENCY_KEY_REUSED`), the unauthorized error path (401 `UNAUTHORIZED`) and, with `--read-secret-arn`, an evidence fetch. Success is `SMOKE_COMPLETE`; coverage is printed on a `SMOKE_COVERAGE` line, where `skipped` is not `ran`. Example question quoted from the README: "Who are the top 3 researchers by average coauthors per paper between 2015 and 2019?". Output carries identifiers only; no answer text.

Network path. The services have no ingress. In the Phase 15 session the smoke ran as a short-lived load-client Fargate task against the internal ALB (`smoke-tn.md`, "Runbook"). From a laptop the script cannot reach the services unless a tunnel exists (`infra/terraform/README.md`, "Smoke-test network path"). Whether `smoke-test.sh` itself was run against a deployment: NOT RECORDED in the files read; the Phase 15 smokes used the load-client task, not `smoke-test.sh`.

Observed (record, Phase 15): SMOKE1 FAIL (all four questions abstained, zero evidence), SMOKE2 FAIL (Haiku synthesis rejected), STUB1 PASS (stub answer model, 4 of 4 answered). The smoke script was strengthened afterwards so that an abstained answer fails the step (Phase 16 WO-6, `scripts/README.md`); that change is not observed on a deployment.

Local services smoke (no AWS): the local runtime and its API tests run under sbt; see [API reference](api.md). NOT REPLAYED here.

## 4. Common failures

Each row names the record that observed it. "Fix" is what the record says was done or decided.

| Symptom | Cause (per record) | Fix or decision | Source |
|---|---|---|---|
| `CreateCluster` `VALIDATION_ERROR`: service-linked role `AWSServiceRoleForEMRCleanup` required | New account had never run EMR | `aws_iam_service_linked_role.emr` in the identity module (work order 39) | Terraform README, "EMR service-linked role" |
| `CreateCluster` `VALIDATION_ERROR`: service role has insufficient EC2 permissions | Missing `for-use-with-amazon-emr-managed-policies` request tag and `PassRole` of the node role | Tag sent by the state machine; inline `pass-node-role` policy (work order 40) | Terraform README |
| EMR step cut by its timeout (S run 3, S run 4) | Step cap or on-node Spark limit too short | S step cap raised to 3,600 then 4,800 s; on-node limits 3,000 s (work orders 49, 52, decision D32) | Terraform README "Pipeline bounds" |
| Run 6: `States.Timeout` at `RunLoader` | Serial embedding at about 9 vectors a second hit the 1,800 s loader cap | Loader-only mode; S loader cap 5,400 s (work order 86) | `docs/releases/phase-14/README.md` |
| Run 7: loader exit 1, PutVectors rejected empty arrays | Empty vector arrays | Run 8 then completed the vector ingest (the fix itself is not described in the files read) | same |
| Run 8: `OutOfMemoryError` in the Neptune graph phase | 0.5 vCPU and 1 GiB loader | Loader 2 vCPU, 8 GiB in the S overlay (work order 108) | same; Terraform README "Loader task size" |
| Run 9: `edges-COAUTHORED_WITH.csv` `LOAD_FAILED`, 3 insert errors | Bulk-load row errors | Run 10 (tolerance flag) failed on Neptune memory; run 11 (graph-only, `db.r6g.xlarge`) loaded 12 jobs with 0 errors. What cleared the 3 insert errors of run 9 is not established in the files read | same |
| Run 10: Neptune `OUT_OF_MEMORY_ERROR`, 153,760 errors on `db.t4g.medium` | Writer too small for the bulk load | Load on `db.r6g.xlarge`, then modify to `db.t4g.medium` for serving (the plan must say "updated in-place") | same; `SESSION2.md` section 5 |
| Query or worker task fails to start: `ResourceInitializationError ... AWSCURRENT` | The three auth secrets were empty | `SECRETS_PRESENT` gate and fill step added (P15-45); the redeploy after a late fill is a deviation, not a routine step | `docs/releases/phase-15/README.md` findings 1 and "Secret values present gate" |
| Smoke abstains with zero evidence | A question that names no researcher and no rankable metric abstains with `researcher-count` before retrieval | Question wording fixed in `38421c6` | `smoke-tn.md` SMOKE1 |
| Smoke answers abstain after synthesis; the eleventh Bedrock call is refused (`cap-exceeded maxCalls=10`) | Haiku replies rejected three times per request; per-process call cap | Phase 15 limitation; Phase 16 fixes (claim cap, stop reason logging); P16-34 slow deadline still required | `smoke-tn.md` SMOKE2; [evaluation](evaluation.md) section 6 |
| `ChangedVectorContent` on an embedding rerun | A vector key exists with different content | Run F1 is invalid as a recovery result; F2 is the result | `docs/releases/phase-15/README.md` finding 4 |
| `RUN_ID_MATCH=false` in a loader report | Pipeline slug `run-v1-...` and Spark `sourceRunId` `run:v1:...` differ by design | Expected; the Neptune graph is named for the Spark run | `docs/releases/phase-14/README.md` |
| Plan counts differ from the apply counts (state machine in-place update) | New task definition ARN unknown at plan time | Hypothesis only; cause not established | `docs/releases/phase-14/README.md` discrepancy 1 |
| `terraform plan` shows an S3 Vectors index replacement | `vector_dimension` changed | Stop. A replaced index loses its vectors. Needs `acknowledge_vector_loss`, a separate apply, and a human decision | Terraform README, finding M7 |
| `deploy.sh` ends in `REVISION_MISMATCH` | An active task definition image tag is not HEAD (for example a mistyped `load_client_image_tag`) | Fix the tfvars value to the HEAD SHA and re-plan | `SESSION2.md` section 6a |
| A hung `CreateCluster` or an execution timeout left a cluster running | `CreateCluster` failure and the execution timeout skip the cleanup state | Idle auto-termination (900 s), the script's leftover-cluster teardown, or the human's cleanup. Read EMR clusters (read-only) | Terraform README "Pipeline bounds" |
| Scale-in has not returned to 1 task | Target-tracking scale-in is slow | Wait at least 25 minutes after the client ends; record it if it does not return | `SESSION2.md` section 8 |
| An approval record is refused as already consumed | Single use; a failed run still consumes it | Write a new record | `scripts/README.md` "Approval record" |

## 5. Rollback

There is no reversal of an apply (`docs/releases/phase-15/README.md`, "Rollback gate"). The way back is a new plan and a new record. No rollback was exercised in Phase 14 or Phase 15 (same file).

`rollback.sh` (`scripts/README.md`):

- Image mode: applies a saved plan made with `plan.sh --image-tag <old>`, then verifies. Pushed ECR tags are immutable, so the old tag must still exist. Needs a record `ACTION=rollback`, a clean tree, and the stateful acknowledgement when the plan says `STATEFUL_CHANGE=yes`.
- State mode: backs up state, checks lineage and serial, `terraform state push -force` of the chosen S3 version, then prints a drift plan. It prints its restore point as `STATE_BACKUP`. Real resources are untouched by a state push.

A half-applied plan is re-planned under a new name; no second apply without a new plan, record and decision (`docs/releases/phase-15/README.md`). A killed `terraform apply` can leave a partial apply and a held lock; the operator interrupts by hand (`scripts/README.md`). Neptune data is destroyed at teardown with no snapshot; recovery is a reload from the `graph/` prefix of the data bucket, which is a session-up plus a loader-only run.

Full command lines for `rollback.sh`: run `bash scripts/rollback.sh --help`. NOT RECORDED as a single line in the files read; NOT REPLAYED.

## 6. Incident escalation

Roles (`docs/agents.md`): `incident-responder` owns `docs/incidents/**`, preserves evidence, documents the blast radius and verifies recovery with smoke checks; the human approves any recovery script, target and bounds. `deploy-engineer` owns release records. Destructive actions, data deletion and irreversible recovery are the human's, through a reviewed runbook.

No incident report exists: `docs/incidents/` holds only `.gitkeep`. Phase 14 and 15 failures were recorded in release records and decision files, not as incident reports.

Escalate to the human immediately, without further action, on any of:

- Billed spend at or above the operating stop, or a spend-limit or Budget alert.
- Any resource found running after session-down (EMR cluster, Neptune, interface endpoints, load balancer, NAT, running tasks).
- Any secret value shown in a log, a chat, a record or the repository. Rotate by `put-secret-value` (a human step) and treat the exposure as a security event.
- Any IAM, security group or public-exposure change in a plan that the expected plan did not list.
- Any plan that destroys or replaces a stateful resource unexpectedly.
- Any evidence a guard or approval control was bypassed.

First steps for the responder, read only: record the time and the last command, preserve logs (`archive-evidence.sh` gather mode refuses secret-shaped files), list resources with the read-only checks in section 7.3, and stop. Do not delete anything to "clean up" during an incident.

## 7. Session down and cleanup

### 7.1 Session down (stop hourly billing, keep storage)

Source: `scripts/README.md` and `infra/terraform/README.md`, "Session up and session down".

1. At the deployed commit, plan with the overlay: `bash scripts/plan.sh --environment course --account-id <id> --cost-ceiling-usd <n> --extra-var-file <abs>/infra/terraform/environments/course/session-down.tfvars`. Do not add a size overlay. After a services session (A2 or later applied), also add `session-down-services.tfvars` and the `session2.tfvars` values, as in `SESSION2.md` section 5 and 8; after an early stop with the services stack never applied, use `session-down.tfvars` only (`SESSION2.md`, "Early-stop teardowns").
2. Read the plan. It should destroy interface endpoints and Neptune (cluster, instance, subnet group, parameter groups, bulk-load role) and set service desired counts to 0. It must not destroy a bucket, table, vector bucket, index, repository or secret.
3. `bash scripts/deploy.sh --print-digest ...`, write the record with `STATEFUL_CHANGE_ACKNOWLEDGED=yes`, then `bash scripts/deploy.sh ...`. Nothing is rebuilt (the tag for HEAD exists) but the context directories must still exist.
4. Confirm with read-only checks (7.3).

OBSERVED (record): `tn-s4` reached `DEPLOY_COMPLETE` and the reduced read showed no EMR cluster, no running task, no Neptune, only gateway endpoints, no NAT, Elastic IP, load balancer, unattached volume or running instance (`docs/releases/phase-15/README.md`). The read was run in a reduced form (deviation 11; the 24 h EMR list, Step Functions and dead-letter depths were not in the log). NOT REPLAYED here.

Idle items that keep billing after session down (`infra/terraform/README.md`, "Idle-cost table"; rates mostly UNVERIFIED): Secrets Manager secrets (about 0.40 USD each a month, three secrets), CloudWatch alarms, and storage (S3, DynamoDB, ECR, S3 Vectors). The secret values persist across sessions.

### 7.2 Full cleanup (end of project, deletes storage)

Human only. Decision P17-7: after grades are released (mid-December 2026). Quoted from `scripts/README.md`: `cleanup.sh` has four modes (`plan-flip`, `apply-flip`, `plan-destroy`, `apply-destroy`); requires `--i-am-the-human`, `--acknowledge-data-loss`, an approval record (`ACTION=cleanup`, `STATEFUL_CHANGE_ACKNOWLEDGED=yes` for destroy) and a saved plan built with `course.tfvars` plus `cleanup.tfvars`; lists state resources and the first 20 keys of each bucket before any deletion; deletes only through `terraform destroy` of the saved plan. It refuses an agent environment. Two phases are needed because `force_destroy` must be in state before the destroy empties a bucket. Argument line: run `bash scripts/cleanup.sh --help`. NOT RECORDED as one line; NOT REPLAYED; never run during any session that is still needed.

After the destroy the script checks that each bucket is gone. The S3 Vectors check uses `aws s3vectors get-vector-bucket`; its not-found message is UNVERIFIED and fails closed with `VECTOR_BUCKET_COULD_NOT_VERIFY`, so confirm the vector bucket by hand. The EMR service-linked role is in state and is deleted with it; it is free and recreated by the configuration. The AWS Budget created outside Terraform is not destroyed by `cleanup.sh` (the module's `create_budgets` is false); removing it is a separate human action. Terraform state and the approval-record markers are kept outside the repository (`*.tfstate*` is denied to agents; markers sit under `SCHOLARCHAIN_OPS_STATE_DIR` or `$HOME/.scholarchain-ops`); keeping or removing them is the human's decision.

### 7.3 Read-only inventory (before and after)

Source: the `aws ... describe-*` forms in `infra/terraform/README.md` ("Availability checks") are the documented examples of the style; the exact inventory commands the human used for the Phase 15 reduced read are in the human's logs outside the repository (`phase15-tn-*`) and are not reproduced in the files read: NOT RECORDED. The checks to make are: no EMR cluster in a non-terminated state, no running ECS task, no Neptune cluster, no interface endpoint, no NAT gateway, no load balancer, no unattached volume, no running EC2 instance; ECS services at desired and running 0; DLQ depth. Phase 17 deliverable D20 (resource inventory before and after cleanup) belongs to the human and the release records.

## 8. Replay status summary

| Section | Status |
|---|---|
| 1 local checks | NOT REPLAYED until H-8 |
| 2 deployment | NOT REPLAYED; deploys OBSERVED in Phase 14 and 15 records |
| 3 smoke | NOT REPLAYED; Phase 15 smokes OBSERVED (two FAIL, one PASS) |
| 4 failures | records only |
| 5 rollback | NOT REPLAYED; never exercised |
| 6 escalation | procedure, not a command |
| 7 down and cleanup | NOT REPLAYED; `tn-s4` OBSERVED; cleanup never run |
