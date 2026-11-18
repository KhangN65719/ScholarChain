# Release record: deploy-tn-s1-a1 (session 2 "tonight", A1: Neptune xlarge, nine interface endpoints, loader)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only. Executed by the human through the approval-gated deploy flow; written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No command was run to write it, and no AWS, terraform, docker or script was invoked. The account id is masked everywhere (`000000000000`). No secret value appears here or was read.

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4, section S.1 (A1), generic commands DP, DC, DW, DD, DR and reader R. Log names are in `scholarchain-data/logs/` and are cited by file name only.

| Field | Value | Source |
|---|---|---|
| Result | `DEPLOY_COMPLETE`, steps 1 to 12 all `ok` | `phase15-tn-s1-deploy.log` |
| Environment, region | course, us-east-2 | same |
| Account | `000000000000` (`IDENTITY_OK` and `APPROVAL_VERIFIED` matched at the identity check) | same |
| Script revision (git SHA) | `32e401a05a221dae081e3c35d4ada7aa787f1175`, `DIRTY=no`, `CLEAN_TREE` | `phase15-tn-s1-plan.log`, `phase15-tn-s1-deploy.log` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` | `phase15-tn-s1-deploy-digest.log` |
| Plan name, file | `p15-tn-s1`, `course-32e401a05a22.tfplan` | plan log |
| Plan digest | `99faef301ab131f464417d8a57bd35407f3e9f3a773024937a781a11fb616280` (`PLAN_BOUND ... mode=apply stateful_change=no`) | deploy log |
| Args digest | `b4d3fff938b8ed29b361e6d32db2844873fa14ee25520c51a7702e0fc4176587` (image tag `32e401a0...`, platform linux/amd64, services and loader contexts both `a70d0d1152f7...`) | digest log |
| Cost bound | 20 USD (`--cost-ceiling-usd 20`, `cost_ceiling_usd=20` in `APPROVAL_VERIFIED`) | deploy log |
| Approval record | `approvals/p15-tn-s1-deploy.env` (name only; not read, it carries the account id). `approved_by=winnguyen`, `expires_at=2026-11-18T06:34:10Z`, single use, consumed at apply (`APPROVAL_CONSUMED action=deploy`), `STATEFUL_CHANGE_ACKNOWLEDGED=no` (evidence ledger) | deploy log, evidence.md |
| Approved at | 2026-11-17T22:34:10Z, derived from the 8 h expiry fixed by runbook section 3. UNVERIFIED directly: `phase15-tn-s1-deploy-record.log` is not in the logs directory | derivation |
| Apply start | 2026-11-17T22:34:28Z (apply log name `course-20261117T223428Z.apply.log`); evidence.md gives 22:34:24Z from the DR clock print (16:34 local) | deploy log, evidence.md |
| Apply end | UNVERIFIED (no timestamp in the log). Bound: the writer created in 9m40s, and the reload started at 22:51:05Z | deploy log, reload log |
| Plan summary | 19 to add, 6 to change, 3 to destroy; `STATEFUL_CHANGE=no`; `STATEFUL_DESTROY_OR_REPLACE: none` | plan log |
| Apply result | "19 added, 6 changed, 3 destroyed" (equals the plan) | deploy log |

## Prerequisites verified before the apply

- Billing gate (P15-43, `phase15-tn-billing-gate-220035.log`, 22:00:35Z): `billed=6.18 lag=2.27 effective=8.45 limit=14.00`, `high_s2=4.50`, `total=12.95 -> GO`, no drops.
- State read from the down state (`phase15-tn-state-20261117T215817Z.log`, 21:58:17Z): both DLQs `0 0`, no active EMR cluster, no running Step Functions execution, no pending or running EC2 instance, only the S3 and DynamoDB Gateway endpoints, `POST_TEARDOWN_CLEAN=yes`.
- Code gate G (`phase15-tn-gate-223044.log`, 22:30:44Z): `HEAD=32e401a0...`, no paths outside the allowlist, no non-comment tfvars lines, `STATUS_LINES=0`, `GATE=PASS`. Pin file `phase15-tn-commit-sha-32e401a.txt` holds the same SHA.
- Service memory: `phase15-tn-heap-measure-check.log` `HEAP_MEASURE_ROWS_CHECK=PASS`; `phase15-tn-session2-memory.log` shows `service_cpu = 512`, `service_memory = 2048` appended to `session2.tfvars`.
- Reader R (`phase15-tn-s1-plan-read.log`): `Plan: 19 to add, 6 to change, 3 to destroy.` and `PLAN_READ_CHECK=PASS` (counts, `STATEFUL_CHANGE=no`, `DIRTY=no`, plan `GIT_SHA` equals the pin).
- DC (`phase15-tn-s1-contexts.log`): `CONTEXTS_EQUAL_AB=yes`. Services and loader contexts equal the A + B build, `a70d0d1152f7620ab5a93a03680b457c9f0cc744a305d2c437b66cc9cdb3082c`.
- DD dry run (`phase15-tn-s1-deploy-dry.log`): `CLEAN_TREE`, `APPROVAL_VERIFIED`, `APPROVAL_SKIPPED_FIELDS=none`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`.
- Immutable image: the apply built and pushed `scholarchain-course-services` and `scholarchain-course-loader` at the new tag (`IMAGE_PUSHED` for both). The tag is the 40-hex SHA.
- Not found in the logs directory: the runbook S.0 identity log `phase15-tn-identity.log`. Identity is covered by `IDENTITY_OK` in the plan and deploy logs.

## What changed

Added (19): nine interface VPC endpoints (`bedrock-runtime`, `ecr.api`, `ecr.dkr`, `elasticmapreduce`, `logs`, `s3vectors`, `secretsmanager`, `sqs`, `sts`); the seven resources of the Neptune module (cluster, writer instance `db.r6g.xlarge` in the plan, cluster and instance parameter groups, subnet group, bulk-load IAM role and its policy); three replaced task definitions (loader, fast, slow). Neptune cluster created in 32 s, writer in 9m40s.

Changed in place (6): `runtime` and `loader` inline IAM policies, services `fast` and `slow`, the pipeline state machine, the overview dashboard. Destroyed (3): the three superseded task definitions. No services-stack resource (no ALB, no `query` service, no worker, no load-client repository). `egress_mode` went from `none` to `endpoints`.

IAM (matches runbook S.1 and `SESSION2.md` 5b; `IAM_CHANGES` in the plan log lists exactly these): `aws_iam_role_policy.runtime` and `aws_iam_role_policy.loader` updated in place (Neptune and Bedrock statements added: `NeptuneReadOpenCypher`, `NeptuneEngineStatus`, `BedrockInvokeExactModels` for runtime; `NeptuneBulkLoad`, `BedrockEmbeddingsOnly` for loader); `aws_iam_role.bulk_load` (trust `rds.amazonaws.com`, condition on the source account) and its policy `read-graph-prefix` created. No other principal changed.

## IAM approval line

Runbook text (D5, S.1): `IAM DIFF APPROVED tn-s1: runtime and loader inline policies updated (Neptune and Bedrock statements); new role bulk_load (trust rds.amazonaws.com) with its policy; no other principal`. The evidence ledger records that a line `IAM DIFF APPROVED tn-s1 ...` was typed before the record. The exact typed wording is in the human's chat, not in any log I read: UNVERIFIED.

## Smoke evidence (read-only, from the deploy log)

`IMAGE_PRESENT` for services (`sha256:e12361538ccb3d7476d8da3b2935fb3c9c394aaf0ecc63ffca600393502fbcab`) and loader (`sha256:b6839af3469fc7fb2947cd1b66fd5c8c496bc0fd91051092b3700a5fe964431f`); `REVISION_OK` for families `fast`, `loader`, `slow`; `SERVICE_REVISION_OK` for `slow` and `fast`; `DEPLOYED_REVISION=32e401a05a221dae081e3c35d4ada7aa787f1175`; `DEPLOY_COMPLETE ... plan_digest=99faef30...`. Services run at desired count 0 in this plan, so the verify step proves image and task-definition revision only, not runtime health. Neptune availability beyond the created-resource lines is not read here; the reload below is the first use of it.

## Follow-on action: graph-only reload of A's export (action `pipeline`, not a deploy)

Runbook S.2, loader-only, `--tolerate-load-errors` off. Recorded here because it ran inside the A1 window and needs the A1 graph.

| Field | Value |
|---|---|
| Script | `scripts/run-bounded-pipeline.sh`, digest `4b6ca5d7fa02a30d1c875d9aba5d1936feb96b8977f8f90dafd9fc9e0ba74af4` (`phase15-tn-reload-pipeline-digest.log`) |
| Args digest | `2a942e5856d85ac51d1747018a4f003f89e24a164e3e4aa49aab04357342b44c` (size large, 100 min, spend ceiling 1.70, hourly burn 0.97, stop on first-failure and dlq-nonempty, input sha256 `cced15591358d51d06b9da935077bb154e33ef25e8420f66343781a4acad2ce5`) |
| Cost bound | `cost_ceiling_usd=20`; projected spend 1.62 USD against a 1.70 USD limit |
| Approval | `approved_by=winnguyen`, `expires_at=2026-11-18T06:50:45Z` (approved about 22:50:45Z, derived), `GIT_SHA=n/a`, `PLAN_DIGEST=n/a`; dry run `DRY_RUN_COMPLETE` (`phase15-tn-reload-dry.log`) |
| Run | Started 2026-11-17T22:51:05Z, `FINAL_STATUS=SUCCEEDED` at poll 312 s, ended 22:56:18Z, `elapsed_min=5.2 estimated_spend_usd=0.08`, no EMR cluster (`NO_ACTIVE_CLUSTER`) |
| Load result | `phase15-tn-reload-check-2.log`: `graph-report.json` and `graph-run.json` present, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788 files=12 jobs=12`, `BOUND_EXCEEDED_EVENTS=0 FAILED_EVENTS=0`, `RELOAD_CHECK=PASS` |

The first check (`phase15-tn-reload-check.log`) printed `RELOAD_CHECK=PASS` with an empty `NEPTUNE_LOAD` section; the repeat printed the line above. Why the first read was empty is not shown (UNVERIFIED). The runbook required the human to read the `NEPTUNE_LOAD` line, which only the repeat shows. A's graph run id `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`; the export run id and the mixed vector provenance are in decisions P15-44 (D1).

## Rollback gate (runbook)

There is no reversal of an apply. The way back is the session-down plan with a new record (runbook H, executed as `tn-s4`; see `deploy-tn-s4-down.md`). Neptune data is destroyed there with no snapshot (`skip_final_snapshot`, `session-down.tfvars:5-8`). Pushed image tags are immutable. A half-applied plan would be re-planned under a `-2` name; no second apply without a new plan, record and decision. Not exercised: the apply completed.

## Deviations and risks

- No deviation specific to A1. The session-wide deviations (P15-45) are listed in `README.md`.
- Billed cost of A1: PENDING (billing is read the next day against the section 2 gate). The runbook estimate of A1's share is 1.67 USD (`COST_ESTIMATE.md:126`, an estimate not a bill).
- Idle-cost items billed from this apply until the A4 teardown (Neptune xlarge then medium, nine interface endpoints).
