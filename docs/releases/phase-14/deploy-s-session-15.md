# Release record: deploy-s-session-15

Record author: deploy-engineer (WO 115). Records only. Executed through the human-controlled approval-gated deploy flow; written afterwards from logs and records in `scholarchain-data/`. No command was run to write it.

| Field | Value |
|---|---|
| Result | DEPLOY_COMPLETE logged (steps 1 to 12 all `ok`) |
| Environment, region | course, us-east-2 |
| Account | `<account-id>` (matched the approval record at the identity check) |
| Git HEAD | `b456cd3fddeb337ebd9c1e81d0d599bbc487aa1e`, clean tree |
| Package | package-10 (manifest GIT_SHA matches HEAD, DIRTY=no) |
| Plan | plan-15 (`plans/plan-15-s-neptune-xlarge`) |
| Plan digest | `a5d43b8fb6c8fb37ee5c6d8cef1e616a0e980bbec0d3b1fab0fa801d0f08da35` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` |
| Args digest | `2d837b99f2c479c59d836ed379c99fa89c423a7b4b484f66b0470739f9fe4fd7` |
| Approval record | `approvals/deploy-s-session-15.env`, approved 2026-11-16T21:05:53Z, expires 2026-11-17T05:05:53Z, cost ceiling 20 USD, STATEFUL_CHANGE_ACKNOWLEDGED=no |
| Apply start | about 2026-11-16T21:06:27Z (from the apply log name) |
| Plan summary | 3 to add, 4 to change, 3 to destroy; IAM changes none; high-cost creations none; STATEFUL_CHANGE=no |
| Apply result | "3 added, 3 changed, 3 destroyed" |

## What changed

- Neptune writer instance class `db.t4g.medium` to `db.r6g.xlarge`, an in-place modification of the existing instance. The apply waited 11m1s for it. The plan log does not show an `apply_immediately` attribute on the instance (it is among the hidden unchanged attributes). The committed module sets `apply_immediately = true` on the writer instance (`infra/terraform/modules/neptune/main.tf:139`) and on the cluster (`:118`), and `experiment-size-s.tfvars:46-47` says the change is applied now. That is the source for this point; it is committed source, not plan-log evidence.
- Three task definitions replaced for the new image tag (loader revision 15 to 16) and the two ECS services modified in place.
- Images pushed to the immutable tag `b456cd3fddeb337ebd9c1e81d0d599bbc487aa1e`: services `sha256:6bded15f96ac5e860961530cf39a7f72584b42960d385ebe3752ba34ad9521e0`, loader `sha256:c027725857ddc09c77287edbeca679f0c1fa3c5e268fb1ff9385f33ce659855d`.
- Previous tag was `c4ebc1fae676cd146a92fe32ec219fb00ee6972d` (session-14).
- The plan's fourth change is the state machine, which the apply log again does not show as modified.
- Cost effect: the recorded hourly burn for run 11 is 0.97 USD/h against 0.37 USD/h for runs 9 and 10. Those figures were supplied at run time and are not a bill.

## Smoke evidence (read-only)

- Deploy script verify step: `IMAGE_PRESENT` for both repositories, `REVISION_OK` for the three families, `SERVICE_REVISION_OK` for both services, `DEPLOYED_REVISION=b456cd3f...`.
- Follow-up read-only check, `logs/phase14-neptune-check-15.log`: writer `db.r6g.xlarge`, status `available`, pending modification `None`, plus a second line `available` (the command is not recorded; `docs/agent-runs/phase-14/decisions-d38.md:27` reads it as the cluster status).
- Behavioural evidence: run 11 completed the graph load on this writer (`runs-s-6-to-11.md`). That evidence comes from the loader's own report, because Neptune cannot be queried from outside the VPC.

## Rollback gate

- Reverts to: tag `c4ebc1fa...` and writer `db.t4g.medium` (session-14 plan). Another in-place instance modification; the forward change took 11m1s and the reverse is expected to be similar (not measured).
- That writer failed the bulk load with `OUT_OF_MEMORY_ERROR` in run 10, so it is a known-bad target for the graph load.
- This state was superseded by `deploy-down-5`, which destroyed the writer. There is nothing left to roll back to except a fresh session-up.
- Needs a fresh plan, a new human approval record and the human-controlled execution path.

## Open items

- Plan versus apply: the plan's fourth change (state machine) is not logged as applied. HYPOTHESIS only; see `README.md`, discrepancy 1.
- The `db.r6g.xlarge` writer incurred higher idle burn from about 21:17Z until down-5 (about one hour). See `idle-time-and-spend.md`.
- Process gap: in `logs/phase14-deploy-15.log` the apply is lines 35 to 161 and the image pushes are lines 165 to 289, so the task definitions named the new tag before it was pushed. The log carries no timestamps. See `README.md`, open items.
