# Release record: deploy-s-session-13

Record author: deploy-engineer (WO 115). Records only. Executed through the human-controlled approval-gated deploy flow; written afterwards from logs and records in `scholarchain-data/`. No command was run to write it.

| Field | Value |
|---|---|
| Result | DEPLOY_COMPLETE logged (steps 1 to 12 all `ok`) |
| Environment, region | course, us-east-2 |
| Account | `<account-id>` (matched the approval record at the identity check) |
| Git HEAD | `8cefc14dd02406c6d2a16b4624e08d85162e2792`, clean tree |
| Package | package-8 (manifest GIT_SHA matches HEAD, DIRTY=no) |
| Plan | plan-13 (`plans/plan-13-s-graph-memory`) |
| Plan digest | `c37b0af0b12b53b7d050143d5981d5c0fbd117fe54d62e78fe8838d3c2de75b0` |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` |
| Args digest | `8418cb903a9aef5576044cf9f35fc1d4c9bb516065cb740bdc1c73eeed1d5b79` |
| Approval record | `approvals/deploy-s-session-13.env`, approved 2026-11-16T17:11:43Z, expires 2026-11-17T01:11:43Z, cost ceiling 20 USD, STATEFUL_CHANGE_ACKNOWLEDGED=no |
| Apply start | about 2026-11-16T17:12:23Z (from the apply log name) |
| Plan summary | 3 to add, 3 to change, 3 to destroy; IAM changes none; high-cost creations none; STATEFUL_CHANGE=no |
| Apply result | "3 added, 2 changed, 3 destroyed" |

## What changed

- Loader task definition: CPU 512 to 2048 and memory 1024 to 8192 (both force replacement), plus the new image tag. Revision 13 to 14 (the next plan shows revision 14 as current).
- Services fast and slow task definitions replaced for the new image tag only. The two ECS services were modified in place.
- Images pushed to the immutable tag `8cefc14dd02406c6d2a16b4624e08d85162e2792`: services `sha256:e33dcd026a86392b48d7bf6b4e7b9cc676ee34d09afe12b5f7bc6e029c5061b9`, loader `sha256:9eddf4f56e3a1eac4c9a0c0368686fb65f11f0e8079742e30d40e7cb7cc3eba2`.
- Previous tag was `02437a90ba7d4b98098f7a408fc82510f31137c0` (session-12).
- Purpose: the 1 GB loader task ran out of heap in the graph phase of run 8.
- The plan listed the state machine as an in-place update. The apply log shows the services modified and no state machine line, and reports 2 changes. The brief said "state machine in place"; the plan says so, the apply log does not confirm it.

## Smoke evidence (read-only, produced by the deploy script's verify step)

- `IMAGE_PRESENT` for both repositories with the digests above.
- `REVISION_OK` for the three families and `SERVICE_REVISION_OK` for both services, naming the new tag. `DEPLOYED_REVISION=8cefc14d...`.
- Limit: services at desired count 0, so configuration only. The first behavioural evidence for the new size is run 9, which still failed (Neptune insert errors) but got through download, projection and export on the larger task. See `runs-s-6-to-11.md`.

## Rollback gate

- Reverts to: tag `02437a90...`, the session-12 plan, loader 512 CPU and 1024 MiB.
- That target is known to fail the graph phase (run 8 `OutOfMemoryError`), so a rollback would restore a loader that cannot finish the graph load.
- Needs a fresh plan, a new human approval record and the human-controlled execution path. Old approvals are single-use.

## Open items

- Apply "2 changed" versus plan "3 to change": cause not established. HYPOTHESIS only; see `README.md`, discrepancy 1.
- Process gap: in `logs/phase14-deploy-13.log` the apply is lines 35 to 93 and the image pushes are lines 97 to 217. See `README.md`, open items.
- Run 9 started under three minutes after the apply began and failed on the Neptune side (insert errors), not on loader memory. The ECS task image for run 9 was not read; that it used this tag is inferred from the timing and the deploy order.
