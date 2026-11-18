# Release record: deploy-tn-s3-a3 (session 2 "tonight", A3: answer model back to stub)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only, written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No AWS, terraform, docker or script was run to write it. Account masked (`000000000000`).

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4, section S.7. Purpose: D, E, F and C run on the stub answer model, with the query service at the same task-definition tag.

| Field | Value | Source |
|---|---|---|
| Result | `DEPLOY_COMPLETE`, steps 1 to 12 all `ok` | `phase15-tn-s3-deploy.log` |
| Environment, region | course, us-east-2 | same |
| Account | `000000000000` | same |
| Script revision (git SHA) | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9`, clean tree | `phase15-tn-s3-plan.log`, deploy log |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` | `phase15-tn-s3-deploy-digest.log` |
| Plan name, file | `p15-tn-s3`, `course-38421c6f986d.tfplan` | plan log |
| Plan digest | `a24588eb796ac877d7d8c8369e2dce33c170340107a32007dfb336d32389c092` (`PLAN_BOUND ... stateful_change=no`) | deploy log |
| Args digest | `9586ec2175b8069f01173abd20806554ffe3d4a7cbbd288029e3a423cb2ddfe2` (equal to `tn-s2b`; the plan digest is what separates the two records) | digest log |
| Cost bound | 20 USD | record log |
| Approval record | `approvals/p15-tn-s3-deploy.env`: `APPROVED_BY=winnguyen`, `APPROVED_AT=2026-11-18T00:10:55Z`, `EXPIRES_AT=2026-11-18T08:10:55Z`, `GIT_SHA=38421c6f...`, `STATEFUL_CHANGE_ACKNOWLEDGED=no`, `COST_CEILING_USD=20` (`phase15-tn-s3-deploy-record.log`); consumed at apply | record log, deploy log |
| Apply start | 2026-11-18T00:11:11Z (apply log name `course-20261118T001111Z.apply.log`) | deploy log |
| Plan summary | 1 to add, 2 to change, 1 to destroy; `IAM_CHANGES: none`; `HIGH_COST_CREATIONS: none`; `STATEFUL_CHANGE=no` | plan log |
| Apply result | "1 added, 2 changed, 1 destroyed" (equals the plan and the runbook prediction) | deploy log |

## Prerequisites verified

- Plan counts equal the S.7 prediction `1 to add, 2 to change, 1 to destroy`, and `IAM_CHANGES` reads `none` as the runbook required.
- DD dry run (`phase15-tn-s3-deploy-dry.log`): `CLEAN_TREE`, `APPROVAL_VERIFIED`, `APPROVAL_SKIPPED_FIELDS=none`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`.
- Immutable images reused, not rebuilt: `IMAGE_EXISTS` services `sha256:32358714c35a...` and loader `sha256:c38bb57aab32...` at `38421c6f...`.
- Reader R output for `tn-s3` was not shown (evidence.md deviation 4); `phase15-tn-s3-plan-read.log` is not in the logs directory. The plan log summary was used.

## What changed

The `query` task definition replaced (1 destroyed, 1 added; stub answer model, per the runbook the slow-deadline environment removed), the `query` ECS service modified in place, and its autoscaling target modified in place (max 1 to 4 per the runbook). The apply log shows exactly those three modification groups. No IAM, stateful or network change. The task-definition environment values (stub setting, removed variable) are runbook statements: I did not read the plan's task-definition diff, UNVERIFIED.

## Smoke evidence

Deploy log verify step: `IMAGE_PRESENT` both; `REVISION_OK` for `load-client`, `loader`, `query`, `worker`; `SERVICE_REVISION_OK` for `query` and `worker`; `DEPLOYED_REVISION=38421c6f986ddd09cc4c1e5ac309a530f760f2a9`; `DEPLOY_COMPLETE ... plan_digest=a24588eb...`.

STUB1 (`smoke-tn.md`) ran after this apply, from 2026-11-18T00:14:35Z: 4 of 4 HTTP 200, `abstained: false`, evidence items 4, 3, 3, 8, task exit 0. That is the functional check of the stub configuration: PASS.

The runbook also required waiting for the query service to be `COMPLETED` with desired = running = 1 after A3. The logs read do not include that read for this step: UNVERIFIED as a separate line (STUB1 answering is indirect evidence that the service was serving).

## IAM

None. No IAM approval line required.

## Rollback gate (runbook)

No reversal of an apply. Going back to the Bedrock answer model needs a new plan with a new record. Experiments D, F, E, F2 and C ran on this configuration. Not exercised.

## Deviations and risks

- Deviation 4: reader R output not shown. Deviation 5: experiment order changed from the runbook's P15-10 order (D, E, F, C) to A3, D, F, E, F2, C. See `README.md` for the full list and decision IDs.
- Because A3 moved the answer model to the stub, none of the D, E, F or C results measure Haiku synthesis. This follows from SMOKE2 (`smoke-tn.md`, finding 3).
- Billed cost: PENDING.
