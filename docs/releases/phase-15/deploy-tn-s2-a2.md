# Release record: deploy-tn-s2-a2 (session 2 "tonight", A2: services stack, worker role, Bedrock answer model, Neptune downsize)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only, written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No AWS, terraform, docker or script was run to write it. Account masked (`000000000000`). No secret value appears here; the auth secret values were never logged and were not looked for.

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4, sections S.3 to S.6 (A2 and its verification).

| Field | Value | Source |
|---|---|---|
| Result | `DEPLOY_COMPLETE`, steps 1 to 12 all `ok` | `phase15-tn-s2-deploy.log` |
| Environment, region | course, us-east-2 | same |
| Account | `000000000000` (`IDENTITY_OK`, `APPROVAL_VERIFIED`) | same |
| Script revision (git SHA) | `32e401a05a221dae081e3c35d4ada7aa787f1175`, clean tree | `phase15-tn-s2-plan.log`, deploy log |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` | `phase15-tn-s2-deploy-digest.log` |
| Plan name, file | `p15-tn-s2`, `course-32e401a05a22.tfplan` | plan log |
| Plan digest | `055b7855c2dbea4850d8dd7406dc54ef174f26a69bdd92655c3c9d41b808bdd4` (`PLAN_BOUND ... stateful_change=no`) | deploy log |
| Args digest | `b4d3fff938b8ed29b361e6d32db2844873fa14ee25520c51a7702e0fc4176587` (equal to A1's: same tag `32e401a0...` and contexts `a70d0d11...`) | digest log |
| Cost bound | 20 USD | deploy log |
| Approval record | `approvals/p15-tn-s2-deploy.env` (name only). `approved_by=winnguyen`, `expires_at=2026-11-18T07:00:03Z`, single use, `APPROVAL_CONSUMED`, `STATEFUL_CHANGE_ACKNOWLEDGED=no` (ledger) | deploy log, evidence.md |
| Approved at | 2026-11-17T23:00:03Z, derived from the 8 h expiry. UNVERIFIED directly: `phase15-tn-s2-deploy-record.log` is not in the logs directory | derivation |
| Apply start | 2026-11-17T23:00:34Z (apply log name `course-20261117T230034Z.apply.log`) | deploy log |
| Apply end | UNVERIFIED (no timestamp). The Neptune writer modification took 8m48s | deploy log |
| Plan summary | 34 to add, 3 to change, 6 to destroy; `STATEFUL_CHANGE=no` | plan log |
| Apply result | "34 added, 3 changed, 6 destroyed" (equals the plan and the runbook prediction) | deploy log |

## Prerequisites verified

- Reader R (`phase15-tn-s2-plan-read.log`): `Plan: 34 to add, 3 to change, 6 to destroy.`, `PLAN_READ_CHECK=PASS`; the IAM section lists only the deployer policy document read, and creation of `aws_iam_role.worker[0]` and `aws_iam_role_policy.worker[0]`.
- DD dry run (`phase15-tn-s2-deploy-dry.log`): `DRY_RUN_COMPLETE`, `APPROVAL_NOT_CONSUMED dry-run`, `APPROVAL_SKIPPED_FIELDS=none`.
- Pre-checks from A1 hold (billing GO, state, gate G, memory 2048); see `deploy-tn-s1-a1.md`. The graph reload had already succeeded (22:56:18Z).
- Immutable images: `IMAGE_EXISTS ... (immutable tag, not rebuilt)` for services (`sha256:e12361538ccb...`) and loader (`sha256:b6839af3469f...`) at the A1 tag.

## What changed

The counts equal the plan and the runbook prediction. The resource list below is the runbook S.4 prediction; I did not enumerate every resource from the 2,223-line plan log. Lines seen in the apply log confirm: the Neptune writer modified in place (8m48s, no replacement), the scaling dashboard created, the overview dashboard modified, the `load-client` ECR repository and the `query` and `worker` services present afterwards (`ecr_repository_urls`, `REVISION_OK`).

Added (34, predicted): the worker IAM role and policy, the `load-client` ECR repository and its lifecycle, log groups (query, worker, load-client), task definitions (query, worker, load-client), security groups and rules, the internal ALB, the `query` and `worker` ECS services, scaling targets, policies and alarms, the scaling dashboard. Changed (3, predicted): the Neptune writer in place, cluster Container Insights, the overview dashboard (the third was not identified in the lines read). Destroyed (6, predicted): the `fast` and `slow` task definitions, services and log groups. `query_service_name` changed from `scholarchain-course-slow` to `scholarchain-course-query`. The Neptune instance class value after the modification is not printed in the lines read for this record: UNVERIFIED (the runbook expected `db.t4g.medium`).

IAM: two creates, `aws_iam_role.worker[0]` (role `scholarchain-course-worker`) and `aws_iam_role_policy.worker[0]` (SQS embedding queue, run-ledger table, five vector indexes, documents prefix, Titan V2 invoke, per runbook S.4). Runtime and loader policies unchanged by A2. `role_arns.deployer` stays null.

IAM approval line, runbook text: `IAM DIFF APPROVED tn-s2: new role scholarchain-course-worker with worker-embed policy as in SESSION2.md section 6; blast radius SQS embedding-work queue, run-ledger table, five vector indexes, documents prefix, Titan V2 invoke`. The ledger records a typed line `IAM DIFF APPROVED tn-s2 ...`; its exact wording is in the human's chat, not in a log I read: UNVERIFIED.

## Smoke evidence

Deploy log verify step: `IMAGE_PRESENT` for services and loader; `REVISION_OK` for families `load-client`, `loader`, `query`, `worker` (all `:32e401a0...`); `SERVICE_REVISION_OK` for `query` and `worker`; `DEPLOYED_REVISION=32e401a05a221dae081e3c35d4ada7aa787f1175`; `DEPLOY_COMPLETE ... plan_digest=055b7855...`. As the runbook warns, this verify does not prove the load-client image is in ECR.

Load-client image: pushed by hand after A2 under the runbook S.5 step (`phase15-tn-load-client-push.log`), tag `32e401a0...`, digest `sha256:bb41073b74da9263c31d671af7eadca159686f27e8f4f5a3e1ab3efc371335d5`. The S.5b `describe-images` check log (`phase15-tn-load-client-image.log`) is not in the logs directory, so `LOAD_CLIENT_IMAGE_AT_HEAD=yes` is UNVERIFIED as a line; indirect evidence is that SMOKE1 ran from that image (see `smoke-tn.md`).

Read-only checks (`phase15-tn-s2-checks.log`, no timestamps): query desired 1 running 0 `IN_PROGRESS`; worker 1/1 `COMPLETED`; scalable targets worker 1 to 4 and query 1 to 1; alarms `OK` for both DLQs, pipeline-failed, query p95 and worker-backlog-high; `scholarchain-course-worker-backlog-low` in `ALARM`. The runbook expected `OK` or `INSUFFICIENT_DATA`; an `ALARM` on the low-backlog alarm is consistent with an empty queue but that reading was not verified. The query service was not running at that read; see the deviation below.

## Deviation 1: query service failed to start on empty auth secrets; secrets filled by hand and query force-redeployed

Source: `phase15-tn-query-diag.log`, `phase15-tn-secret-fix.log`, `phase15-tn-query-stable.log`; evidence.md finding 1 and deviation 1; decision P15-45.

- Failure: after the A2 apply the `query` service did not reach a steady state. The diagnosis log shows `deployment failed: tasks failed to start` and three task start attempts, each stopped with `ResourceInitializationError: unable to pull secrets or registry auth ... failed to fetch secret ... auth-cursor-secret ... ResourceNotFoundException: Secrets Manager can't find the specified secret value for staging label: AWSCURRENT`. Cause: `modules/identity/main.tf:55-68` creates `auth-query-token`, `auth-read-token` and `auth-cursor-secret` with no version by design, and points at a "Phase 14 runbook" step that does not exist in the repository (evidence.md finding 1). This describes the file at the deployed revision 38421c6 (main.tf:55-68 then); the comment now points to infra/terraform/SESSION2.md section 8 (uncommitted change, P15-45).
- Fix: the human wrote a value into each of the three secrets with `put-secret-value` (random 96-hex values, never logged) and force-redeployed the query service, outside the reviewed Phase 13 scripts and with no approval record, plan digest or deploy-engineer step. The log shows only `auth-query-token=SET`, `auth-read-token=SET`, `auth-cursor-secret=SET`, `REDEPLOY_STARTED`. The exact commands are not in the log: UNVERIFIED (the redeploy is inferred to be a forced new deployment).
- Result: `phase15-tn-query-stable.log` shows `QUERY_STABLE`, `1 1 COMPLETED`, `deployment completed`. No timestamp in the logs. Upper bound: the query service served SMOKE1's first request at 2026-11-17T23:21:28Z, so the fix and redeploy finished before then.
- Disposition: P15-45 records finding 1 as a Phase 15 documentation fix (a "secret values present" check and the put-secret-value step in the session runbook template and `SESSION2.md`), a recorded deviation and a limitation. Follow-up owners (evidence.md): infra-engineer and deploy-engineer, for a documented, scripted fill step.
- This record does not claim the fill step was reviewed or scripted. It was a direct Secrets Manager mutation by the human operator.

## Rollback gate (runbook)

No reversal of an apply; the way back is the session-down plan with a new record (`tn-s4`, see `deploy-tn-s4-down.md`). Pushed tags are immutable. Not exercised: the apply completed. Secret versions written by hand persist after the teardown (the teardown does not touch them). Whether to rotate or remove them is a human decision that is not recorded: manual action required.

## Risks and open items

- Billed cost: PENDING (next-day billing read). The runbook estimates services time at 1.58 USD for the session (`COST_ESTIMATE.md:127`), an estimate.
- A2 was superseded for task-definition revisions by `tn-s2b` after the mid-session commit: see `deploy-tn-s2b-a2b.md`.
