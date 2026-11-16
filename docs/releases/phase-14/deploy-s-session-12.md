# Release record: deploy-s-session-12

Record author: deploy-engineer (WO 115). Records only. The deploy was executed through the human-controlled approval-gated deploy flow; this record was written afterwards from the logs and records in the operator data directory (`scholarchain-data/`, outside the repository). No command was run to write it.

| Field | Value |
|---|---|
| Result | DEPLOY_COMPLETE logged (steps 1 to 12 all `ok`) |
| Environment, region | course, us-east-2 |
| Account | `<account-id>` (matched the approval record at the identity check) |
| Git HEAD | `02437a90ba7d4b98098f7a408fc82510f31137c0`, clean tree |
| Package | package-7 (manifest GIT_SHA matches HEAD, DIRTY=no) |
| Plan | plan-12 (`plans/plan-12-s-session-up`) |
| Plan digest | `34128683a6bcbe5bc8987a6720577b048ec6b463835dfbd79742f07caf06fcee` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` |
| Args digest | `3c679c6ab0b2fbc462628eadd1c318d3b8f2d3d5d0bb2fd54bca36cb3871c53d` |
| Approval record | `approvals/deploy-s-session-12.env`, approved 2026-11-16T08:32:51Z, expires 2026-11-16T16:32:51Z, cost ceiling 20 USD, STATEFUL_CHANGE_ACKNOWLEDGED=no |
| Apply start | about 2026-11-16T08:33:06Z (from the apply log name) |
| Plan summary | 3 to add, 3 to change, 3 to destroy; IAM changes none; high-cost creations none; STATEFUL_CHANGE=no |
| Apply result | "3 added, 2 changed, 3 destroyed" (see discrepancy below) |

## What changed

- Three ECS task definitions replaced (loader, services fast, services slow) for the new image tag. Loader task definition revision 12 to 13. The two ECS services were modified in place to point at the new revisions.
- Images pushed to the immutable tag `02437a90ba7d4b98098f7a408fc82510f31137c0`: services image digest `sha256:617ebba773f6d255e83c2ca47fee62781bb9532f439558d1abe4e3ed4fcb426e`, loader image digest `sha256:7798a86f53a7df6aabe8e161ff2ca00a6f69f5bcf6daed8fc3aa631ce0c8702d` (manifest-list digests as printed by the push).
- Previous tag was `2cc20a8616e6eea533143da133ee919b1453e783` (the session-11 deploy).
- The plan also listed an in-place update of the Step Functions state machine. The apply log has no "Modifying" line for it and reports 2 changes, not 3. Cause not established from the logs.

## Smoke evidence (read-only, produced by the deploy script's verify step)

- `IMAGE_PRESENT` for both repositories with the digests above.
- `REVISION_OK` for the three task definition families and `SERVICE_REVISION_OK` for both services, all naming the new tag.
- `DEPLOYED_REVISION=02437a90...`.
- Limit: services run at desired count 0, so this is a configuration check. No request was served and no task was started by this deploy.

## Rollback gate

- Reverts to: image tag `2cc20a86...` and the session-11 plan (digest `35540332af...` prefix; see `deploys-earlier.md`).
- Approvals are single-use (`APPROVAL_CONSUMED`) and plan files are bound to state, so rollback needs a fresh plan at that SHA, a new human approval record, and the human-controlled execution path. The old approval and plan are not reusable.
- Whether the old image still exists in ECR depends on the ECR lifecycle policy, which was not read for this record.
- Rolling back does not undo run side effects (vector writes from runs 7 and 8).

## Open items

- Run 8 (loader-only) started at 08:33:33Z, about 27 seconds after the apply log's start stamp, and failed in the graph phase with `OutOfMemoryError` on the 1 GB loader task. See `runs-s-6-to-11.md`. The deploy log carries no timestamps, so the order of its image steps against the run start is not provable. The run's ECS task record does show the new tag and digest `7798a86f...`, so the image was present when the task started.
- Apply "2 changed" versus plan "3 to change": cause not established. HYPOTHESIS only; see `README.md`, discrepancy 1.
- Process gap: in `logs/phase14-deploy-12.log` the apply is lines 35 to 93 and the image pushes are lines 97 to 214, so the task definitions named the new tag before it was pushed. See `README.md`, open items.
- Idle-cost items bill until a session-down runs. Teardown happened in `deploy-down-5`.
