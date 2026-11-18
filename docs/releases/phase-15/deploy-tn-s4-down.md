# Release record: deploy-tn-s4-down (session 2 "tonight", A4: session-down, teardown)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only, written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No AWS, terraform, docker or script was run to write it. Account masked (`000000000000`).

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4, section H (H.1 main case row: last successful apply A2 or A3, `NAME=tn-s4`, `STATE_ACK=yes`).

| Field | Value | Source |
|---|---|---|
| Result | `DEPLOY_COMPLETE`, steps 1 to 12 all `ok` | `phase15-tn-s4-deploy.log` |
| Environment, region | course, us-east-2 | same |
| Account | `000000000000` | same |
| Script revision (git SHA) | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9`, clean tree | `phase15-tn-s4-plan.log`, deploy log |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` | `phase15-tn-s4-deploy-digest.log` |
| Plan name, file | `p15-tn-s4`, `course-38421c6f986d.tfplan` (var files per H.1: `session-down.tfvars`, `session-down-services.tfvars`, `session2.tfvars`; not re-read here) | plan log |
| Plan digest | `aefd76b868e96291a3bf43e21ad3c42179a09dc0a6283b87001b3a1c70c92f24` (`PLAN_BOUND ... stateful_change=yes`) | deploy log |
| Args digest | `9586ec2175b8069f01173abd20806554ffe3d4a7cbbd288029e3a423cb2ddfe2` (equal to `tn-s2b` and `tn-s3`) | digest log |
| Cost bound | 20 USD | record log |
| Approval record | `approvals/p15-tn-s4-deploy.env`: `APPROVED_BY=winnguyen`, `APPROVED_AT=2026-11-18T02:14:21Z`, `EXPIRES_AT=2026-11-18T10:14:21Z`, `GIT_SHA=38421c6f...`, `STATEFUL_CHANGE_ACKNOWLEDGED=yes`, `COST_CEILING_USD=20` (`phase15-tn-s4-deploy-record.log`); the deploy log prints `NOTE: stateful change acknowledged in the approval record`; consumed at apply | record log, deploy log |
| Apply start | 2026-11-18T02:14:47Z (apply log name `course-20261118T021447Z.apply.log`); evidence.md: 20:14 local, so the 01:15 local hard stop (runbook D4) was not reached | deploy log, evidence.md |
| Apply end | UNVERIFIED (no timestamp). Neptune cluster destruction took 50 s after the writer | deploy log |
| Plan summary | 2 to add, 7 to change, 31 to destroy; `STATEFUL_CHANGE=yes` (Neptune cluster, writer instance and cluster parameter group destroyed) | plan log |
| Apply result | "2 added, 7 changed, 31 destroyed" (equals the plan and the runbook main-case prediction; not the 3/7/32 variant) | deploy log |

## Prerequisites verified

- Plan counts equal the H.1 main-case `EXP` `2 to add, 7 to change, 31 to destroy`; the plan summary lists `STATEFUL_CHANGE=yes` with exactly the three stateful resources named above. The variant (a) row does not apply (A2 and A3 had applied).
- I did not enumerate all 31 destroyed resources from the 3,064-line plan log or the apply log. The apply log lines read show destruction of the Neptune writer, cluster, parameter groups, subnet group and bulk-load role and policy, the interface endpoints (six named in the first lines read), the query scaling policies and the worker and query alarms; the remainder is the runbook H.1 prediction.
- Plan `IAM_CHANGES` (plan log, exact list): `aws_iam_role_policy.loader`, `aws_iam_role_policy.runtime` and `aws_iam_role_policy.worker[0]` updated in place; `module.neptune[0].aws_iam_role.bulk_load` and `aws_iam_role_policy.bulk_load` destroyed. Nothing else. The apply log shows `loader` and `runtime` policies modified and the `bulk_load` policy and role destroyed; the `worker[0]` policy modification line is not in the lines I read (UNVERIFIED in the apply log, present in the plan).
- DD dry run (`phase15-tn-s4-deploy-dry.log`): `CLEAN_TREE`, `APPROVAL_VERIFIED`, `APPROVAL_SKIPPED_FIELDS=none`, stateful note, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`.
- All collectors were to run before A4 (runbook C4). Collector logs exist for D, E, F2 and C (`phase15-tn-d-collect.log`, `-e-`, `-f2-`, `-c-`); F (first)'s collector was not run before the first purge (evidence.md deviation 8), and no collector log for F1 exists. Their contents were not reviewed for this record.
- Reader R output for `tn-s4` was not shown (evidence.md deviation 4); `phase15-tn-s4-plan-read.log` is not in the logs directory. The plan log summary above was used.

## What changed

Destroyed (31, per the runbook H.1 prediction; the lines read confirm the Neptune module resources, the bulk-load role and policy, several interface endpoints, query scaling policies and alarms). Per the runbook the full set is: nine interface endpoints, seven Neptune-module resources, the worker service, the ALB (three resources) and the p95 alarm, two scaling targets, four scaling policies, two worker alarms. Added 2: the `query` and `loader` task definitions (Bedrock off and Neptune host cleared per the runbook; the apply log shows both created). Changed 7: the `loader`, `runtime` and `worker[0]` IAM policies (the plan lists all three; the apply log lines read show `loader` and `runtime`), the `query` service, the pipeline state machine, and the overview and scaling dashboards (all four seen in the apply log). Outputs: `egress_mode` back to `none`; `alb_dns_name`, `neptune_endpoint` and `worker_service_name` removed. The runbook keeps the worker role, load-client repository, log groups, security groups and dashboards by design; the H.4 read does not list them.

## IAM approval (decision, deviation 9)

Runbook D5 line: `IAM DIFF APPROVED tn-s4: three narrowing policy updates; bulk_load role and policy destroyed with Neptune; worker role kept`. This line was not typed in the chat before the record (evidence.md deviation 9). P15-45 accepts deviation 9: "Claude read the plan in chat (3 narrowings, bulk_load role and policy destroyed, worker role kept)". This record therefore cites the plan log's `IAM_CHANGES` list as the evidence of what was approved, and P15-45 as the human's acceptance; the D5 line itself is absent. The runbook gate (typed D5 line before the record) was not met as written; the exception is the human's, recorded in P15-45.

## Smoke evidence

Deploy log verify step: `IMAGE_PRESENT` services (`sha256:32358714...`) and loader (`sha256:c38bb57a...`); `REVISION_OK` for families `load-client`, `loader`, `query`, `worker`; `SERVICE_REVISION_OK` for `query` only (the `worker` service is destroyed); `DEPLOYED_REVISION=38421c6f986ddd09cc4c1e5ac309a530f760f2a9`; `DEPLOY_COMPLETE ... plan_digest=aefd76b8...`.

H.4 post-teardown read (`phase15-tn-post-teardown.log`, no timestamps, reduced form): empty sections for active EMR clusters, running ECS tasks, Neptune, NAT gateways, Elastic IPs, load balancers, unattached EBS volumes and pending or running EC2 instances; ECS services `scholarchain-course-query 0 0 ACTIVE` and `scholarchain-course-worker 0 0 INACTIVE`; VPC endpoints only `Gateway ... dynamodb available` and `Gateway ... s3 available`. Result: no billable session resource found by this read. It is a reduced form of runbook H.4 (evidence.md deviation 11; P15-45 names only deviation 9 as accepted, so no human acceptance of the reduced form is recorded in my sources): the 24 h EMR list, the Step Functions running-execution read and the DLQ depth lines are not in the log. The log has one `--neptune` section, so whether it is the cluster call, the instance call, or both is UNVERIFIED. DLQ depth after the session is unread (the embedding DLQ was purged twice, deviation 8).

## Rollback gate (runbook H.3, H.1)

There is no reversal of a destroy. Neptune was destroyed with `skip_final_snapshot = true` and 1-day backup retention (set in the A1 plan log's cluster resource; the A4 plan log was not re-read for them), so no final snapshot is expected and the graph is gone; the graph export under the data bucket prefix is not touched by this plan and a reload is possible from it (runbook H.1; the bucket was not listed for this record). A failed teardown with the stack up bills about 0.26 to 0.37 USD an hour plus Neptune (runbook H.3 estimate, `COST_ESTIMATE.md:118`); the runbook re-plans under `tn-s4-2` and decides within 15 minutes. Not exercised: the apply completed. A new session needs a new session-up plan, a new record and a new graph load.

## Idle items that remain (by design, not read in H.4)

S3 buckets, DynamoDB tables, S3 Vectors bucket and indexes (the human deleted vectors in six batches, evidence.md deviation 8, and C's 2,000 keys were removed from three indexes, P15-45), ECR repositories and images, CloudWatch log groups, DLQ messages, and the three Secrets Manager secrets with the values the human wrote by hand (see `deploy-tn-s2-a2.md`).

## Deviations, risks, open items

- Deviation 9 accepted (P15-45). Deviation 11 (reduced H.4) has no recorded acceptance; manual action: the human decides whether the reduced read stands as the limitation it is (P15-46 says no AWS from here on, so the missing sections, 24 h EMR list, Step Functions and DLQ depth, would need an explicit exception). No AWS command is run by this record.
- Billed cost for the whole session: PENDING, read from Billing the next day against the section 2 gate (6.18 + LAG 2.27 + 4.50 HIGH). Estimated experiment spend 0.64 USD plus 0.08 for the reload is a burn estimate, not a bill.
- Next gate: independent review of these records (P15-46 step 5), no AWS action pending from this record.
