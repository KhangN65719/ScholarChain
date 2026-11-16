# Release record: deploy-s-session-14

Record author: deploy-engineer (WO 115). Records only. Executed through the human-controlled approval-gated deploy flow; written afterwards from logs and records in `scholarchain-data/`. No command was run to write it.

| Field | Value |
|---|---|
| Result | DEPLOY_COMPLETE logged (steps 1 to 12 all `ok`) |
| Environment, region | course, us-east-2 |
| Account | `<account-id>` (matched the approval record at the identity check) |
| Git HEAD | `c4ebc1fae676cd146a92fe32ec219fb00ee6972d`, clean tree |
| Package | package-9 (manifest GIT_SHA matches HEAD, DIRTY=no) |
| Plan | plan-14 (`plans/plan-14-s-graph-errors`) |
| Plan digest | `4c197bb553cbee4336d9a51342622c3067b1fb1c03f5e01cb87e88b838b791e5` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` |
| Args digest | `ea6726639e173d95d3bd4e9eed48fbf0ea81f77ec406d824ed6be41defd10057` |
| Approval record | `approvals/deploy-s-session-14.env`, approved 2026-11-16T20:10:48Z, expires 2026-11-17T04:10:48Z, cost ceiling 20 USD, STATEFUL_CHANGE_ACKNOWLEDGED=no |
| Apply start | about 2026-11-16T20:13:15Z (from the apply log name) |
| Plan summary | 3 to add, 3 to change, 3 to destroy; IAM changes none; high-cost creations none; STATEFUL_CHANGE=no |
| Apply result | "3 added, 2 changed, 3 destroyed" |

## What changed

- Image tag change only. The three task definitions were replaced for the new tag (loader revision 14 to 15) and the two ECS services were modified in place. The loader diff in the plan shows only the image field (plus the usual provider noise: empty lists and tags). CPU and memory stay 2048 and 8192.
- Images pushed to the immutable tag `c4ebc1fae676cd146a92fe32ec219fb00ee6972d`: services `sha256:03596ac03bd7a89b6e997204fefb0dd703661f842dacad26122eb8c313873f9f`, loader `sha256:276fbbeeba000a4fa161e8963fbdf5334c88649ef2c054575cd92249eaeb46f6`.
- Previous tag was `8cefc14dd02406c6d2a16b4624e08d85162e2792` (session-13).
- Purpose: name Neptune bulk-load errors and add the bounded tolerate-errors mode after run 9.
- Same plan-versus-apply count gap as sessions 12 and 13 (plan 3 changes, apply 2). I read the loader block and the summary of the plan log; I did not read every line of the state machine diff.

## Smoke evidence (read-only, produced by the deploy script's verify step)

- `IMAGE_PRESENT` for both repositories with the digests above, `REVISION_OK` for the three families, `SERVICE_REVISION_OK` for both services, `DEPLOYED_REVISION=c4ebc1fa...`.
- Limit: services at desired count 0, so configuration only.

## Rollback gate

- Reverts to: tag `8cefc14d...` and the session-13 plan.
- That target reports bulk-load failures without naming the cause. A rollback would lose the error naming that exposed the out-of-memory cause in run 10.
- Needs a fresh plan, a new human approval record and the human-controlled execution path.

## Open items

- Run 10 followed two minutes after the apply began and failed with Neptune `OUT_OF_MEMORY_ERROR` on the `db.t4g.medium` writer. That led to session-15.
- Plan-versus-apply count gap, as above: cause not established. HYPOTHESIS only; see `README.md`, discrepancy 1.
- Process gap: in `logs/phase14-deploy-14.log` the apply is lines 35 to 93 and the image pushes are lines 97 to 222. See `README.md`, open items.
- Run 10's `--tolerate-load-errors` flag is evidenced only by its input file; see `README.md`.
