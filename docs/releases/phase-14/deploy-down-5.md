# Release record: deploy-down-5 (session-down, teardown)

Record author: deploy-engineer (WO 115). Records only. Executed through the human-controlled approval-gated deploy flow with a recorded stateful-change acknowledgement; written afterwards from logs and records in `scholarchain-data/`. No command was run to write it.

| Field | Value |
|---|---|
| Result | DEPLOY_COMPLETE logged (steps 1 to 12 all `ok`) |
| Environment, region | course, us-east-2 |
| Account | `<account-id>` (matched the approval record at the identity check) |
| Git HEAD | `b456cd3fddeb337ebd9c1e81d0d599bbc487aa1e`, clean tree (same SHA as session-15) |
| Plan | plan-down-5 (`plans/plan-down-5`) |
| Plan digest | `90b559872f25ea9fb1b9f76c77e8a24ebbe3afdee2a643e45cf5dff4e756f579` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` |
| Args digest | `2d837b99f2c479c59d836ed379c99fa89c423a7b4b484f66b0470739f9fe4fd7` (identical to session-15's) |
| Approval record | `approvals/deploy-down-5.env`, approved 2026-11-16T22:17:52Z, expires 2026-11-17T06:17:52Z, cost ceiling 20 USD, STATEFUL_CHANGE_ACKNOWLEDGED=yes |
| Apply start | about 2026-11-16T22:18:08Z (from the apply log name) |
| Plan summary | 3 to add, 6 to change, 19 to destroy; STATEFUL_CHANGE=yes |
| Apply result | "3 added, 6 changed, 19 destroyed" (matches the plan) |

## What changed

Destroyed (16 resources): Neptune cluster, Neptune writer instance (8m7s), cluster and instance parameter groups, subnet group, the Neptune bulk-load IAM role and its inline policy, and nine interface VPC endpoints (sqs, bedrock-runtime, s3vectors, secretsmanager, ecr.api, ecr.dkr, logs, elasticmapreduce, sts). Three task definitions were also replaced (3 destroyed, 3 added).

Changed in place (6): both ECS services (new task definition revisions), the Step Functions state machine, the CloudWatch dashboard (Neptune CPU widget removed), and two IAM role policies.

Task definitions (loader revision 16 to a new revision, expected 17; the apply log does not print it):
- Loader: CPU and memory revert from 2048 and 8192 to 512 and 1024; the Neptune host and bulk-load role environment variables are removed.
- Services fast and slow: `SCHOLARCHAIN_BEDROCK` goes from `on` to `off` and `SCHOLARCHAIN_NEPTUNE_HOST` is cleared.
- Same image tag as session-15 (`b456cd3f...`), reused because tags are immutable: the script logged `IMAGE_EXISTS ... not rebuilt` for both repositories, digests unchanged from session-15.

Outputs: `egress_mode` goes from `endpoints` to `none`; `neptune_endpoint` removed.

IAM changes (the brief said Neptune-only; the plan summary and diff show more, all removals of access):
- `loader-write` policy: the `NeptuneBulkLoad` statement and the `BedrockEmbeddingsOnly` statement (Titan embed invoke) are removed.
- `runtime-read` policy: the `NeptuneReadOnly` statement and the `BedrockInvokeExactModels` statement (Haiku and Titan invoke) are removed.
- Neptune bulk-load role and its inline policy destroyed.
- No IAM permission was added.

## Smoke evidence (read-only)

- Deploy script verify step: `IMAGE_PRESENT`, `REVISION_OK` (three families), `SERVICE_REVISION_OK` (both services), `DEPLOYED_REVISION=b456cd3f...`.
- Confirmation log `logs/phase14-teardown-5-confirm.log`: empty Neptune section; VPC endpoints listed are only the S3 and DynamoDB gateway endpoints, both `available`; empty EMR, ECS and NAT sections. The log has section labels (`--neptune`, `--endpoints`, `--emr`, `--ecs`, `--nat`). It has no timestamps and does not record the commands used. I read the empty `--ecs` section as "no ECS tasks" on that basis. (An earlier version of this record wrongly said the log had no section labels.)
- Not checked in the record: other idle-cost items outside the confirm log's five categories.

## Rollback gate

- There is no reversal of a destroy. Rollback means a new session-up plan with the Neptune module, a new human approval (with the stateful acknowledgement where the plan requires it) and a new graph load.
- The Neptune cluster was destroyed with `skip_final_snapshot = true` and a 1-day backup retention, so no final snapshot was taken. The graph data in Neptune is gone. The graph CSVs under the `graph/neptune/` prefix of `<data-bucket>` are not part of the teardown, so a reload is possible from them (the destroy plan did not touch the bucket; the bucket contents were not listed for this record).
- The previous service-side state (Bedrock on, Neptune host set, 2048/8192 loader) existed only while the session was up.

## Open items

- Billing: actual spend for the session is owed by the human from AWS billing. See the README for the arithmetic caveat.
- Per-run estimates in `runs-s-6-to-11.md` do not include the idle hours between runs. The window from the session-11 deploy approval (2026-11-16T02:29:16Z) to this approval (22:17:52Z) is 19.81 hours with no teardown in between. The phase-wide arithmetic is in `idle-time-and-spend.md`. The Neptune cluster and nine interface endpoints are inferred to have been up for that window from the deploy order (down-4 was the last earlier teardown) and from plan-12's refresh, which shows the Neptune cluster and the interface endpoints present.
- Items outside the teardown and not covered by the confirm log (S3 buckets, DynamoDB tables, S3 Vectors bucket and indexes, ECR repositories and images, CloudWatch log groups) remain by design. Any cleanup of them is a separate human-approved operation.
