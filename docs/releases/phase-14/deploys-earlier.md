# Earlier Phase 14 deploys (brief records)

Record author: deploy-engineer (WO 115). Records only, written afterwards from `scholarchain-data/`. Each entry is built from three sources: the plan's `.tfplan.meta` file (HEAD, plan digest, STATEFUL_CHANGE), the approval record (approval and expiry times) and the last lines of the deploy log (`DEPLOY_COMPLETE`). I did not extract apply resource counts, smoke detail or IAM summaries for these. Read the named log for that.

Conventions: all in environment course, us-east-2, account `<account-id>`, cost ceiling 20 USD. Plan digests are shown as the first 10 hex characters; the full value is in the approval record and the `DEPLOY_COMPLETE` line of the log. HEAD is shown as 12 characters. Times are UTC. Every deploy log ends with `DEPLOY_COMPLETE` and the idle-cost note. All were approved by the human operator.

## Up deploys

| Deploy | HEAD | Plan | Plan digest | Stateful | Approved (UTC) | Package record | Log |
|---|---|---|---|---|---|---|---|
| deploy-3 | `3c0ad1398a30` | plan-3 | `7aedb32c51` | no | 2026-11-14T03:52:24Z | none found | `phase14-deploy-3.log` (597 lines) |
| deploy-s-session-5 | `9359cfb56812` | plan-5 | `c7d4048a19` | no | 2026-11-14T20:44:30Z | package-5 | `phase14-deploy-5.log` |
| deploy-s-session-6 | `0399c4b31890` | plan-6 (`s-slr`) | `ef83673361` | no | 2026-11-14T21:29:49Z | none found | `phase14-deploy-6.log` |
| deploy-s-session-7 | `d5d28405d387` | plan-7 (`emr-tags`) | `7aab78f2f4` | no | 2026-11-14T21:45:58Z | none found | `phase14-deploy-7.log` |
| deploy-s-session-8 | `4cfe070563c4` | plan-8 | `7dd2c57786` | no | 2026-11-15T04:28:49Z | none found | `phase14-deploy-8.log` |
| deploy-s-session-9 | `5228e787db9c` | plan-9 | `b838525af1` | no | 2026-11-15T07:05:36Z | none found | `phase14-deploy-9.log` |
| deploy-s-session-10 | `7d9de2255c2d` | plan-10 | `deff292928` | no | 2026-11-15T19:02:10Z | none found | `phase14-deploy-10.log` |
| deploy-s-session-11 | `2cc20a8616e6` | plan-11 | `35540332af` | no | 2026-11-16T02:29:16Z | package-6 | `phase14-deploy-11.log` |

Notes:
- deploy-3 has a plan directory named `phase14-plan-3` and an approval named `deploy-plan3.env`; it is the earliest deploy with a deploy log. `phase14-plan-1.log` and `phase14-plan-2.log` exist as plan logs only: no approval record and no deploy log for them were found.
- The plan directory names for 6 and 7 (`s-slr`, `emr-tags`) suggest a service-linked-role fix and EMR tagging; I did not read the diffs to confirm.
- Packages: only package-5 (`9359cfb5...`), package-6 (`2cc20a86...`) and package-7 to 10 (sessions 12 to 15, recorded in their own files) exist under `packages/`. No package record was found for deploy-3 or sessions 6 to 10 (the table says "none found"). I did not look for another record of how those images were built, so that remains a gap in the evidence for those deploys.
- Session-10 is the deploy that run 6 used. Session-11 is the deploy that run 7 used. See `runs-s-6-to-11.md`.

## Teardowns (session-down)

| Deploy | HEAD | Plan | Plan digest | Stateful | Approved (UTC) | Log |
|---|---|---|---|---|---|---|
| deploy-4-session-down | `3c0ad1398a30` | `phase14-plan-4-session-down` | `0ab7fca145` | no | 2026-11-14T04:00:21Z | `phase14-deploy-4-session-down.log` (235 lines) |
| deploy-down-1 | `4cfe070563c4` | plan-down-1 | `be1c710e00` | yes (acknowledged) | 2026-11-15T03:53:46Z | `phase14-deploy-down-1.log`; plan log `plan-down-1.log` |
| deploy-down-2 | `4cfe070563c4` | plan-down-2 | `24839142ac` | yes (acknowledged) | 2026-11-15T05:50:17Z | `phase14-deploy-down-2.log` |
| deploy-down-3 | `5228e787db9c` | plan-down-3 | `4565828761` | yes (acknowledged) | 2026-11-15T08:31:24Z | `phase14-deploy-down-3.log` |
| deploy-down-4 | `7d9de2255c2d` | plan-down-4 | `3a888bcc25` | yes (acknowledged) | 2026-11-15T21:20:37Z | `phase14-deploy-down-4.log`; confirm log `phase14-teardown-4-confirm.log` |

Notes:
- A teardown's HEAD is the HEAD at the time of the teardown, not necessarily the HEAD of the session it tears down. Down-1 and down-2 share a HEAD with session-8 (`4cfe0705...`) although down-1 ran before session-8.
- `deploy-4-session-down` is recorded with STATEFUL_CHANGE=no, unlike down-1 to down-5. I did not read its plan to say what it removed.
- `phase14-teardown-4-confirm.log` lists no Neptune cluster, only the S3 and DynamoDB gateway endpoints (available), and empty EMR, ECS task and NAT sections. No confirm log named for down-1 to down-3 was found (`phase14-teardown-4-confirm.log` and `phase14-teardown-5-confirm.log` are the only two).

## Timeline of the session (UTC, from approval times and run records)

2026-11-14: deploy-3, deploy-4-session-down, sessions 5, 6, 7.
2026-11-15: down-1, session 8, down-2, session 9, down-3, session 10, run 6, down-4.
2026-11-16: session 11, run 7, session 12, run 8, session 13, run 9, session 14, run 10, session 15, run 11, down-5.

Runs 1 to 5 and capability runs 1 to 3 exist under `runs/` and `approvals/` and are outside this record.

## Rollback gate for these deploys

Each rollback is a fresh plan, a new human approval and the human-controlled execution path. The earlier approvals and plans are consumed or stale. Detail per deploy was not worked out in this record.
