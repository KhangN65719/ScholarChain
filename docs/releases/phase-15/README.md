# Phase 15 release records: session 2 "tonight" (2026-11-17 to 2026-11-18)

Author: deploy-engineer (P15-46 step 2, work order P15-46). Outcome index for the release records of the evening session. Records only, no command run to write them: no AWS, terraform, docker or deploy script, no commit, read-only git. Account masked (`000000000000`); no secret value was logged, read or copied. Sources are the human's logs under `scholarchain-data/logs/phase15-tn-*` (cited by file name; they are outside the repository), `docs/agent-runs/phase-15/session-tonight/evidence.md`, decisions P15-43 to P15-46 in `docs/agent-runs/phase-15/decisions.md`, and `session-tonight-runbook.md` revision 4. Times are UTC unless marked local (UTC-6).

Marked UNVERIFIED anywhere in these records means: not seen in a log or file I read. The records separate what a log shows from what the evidence file or runbook states.

## Records

| Record | Action | Plan digest | Args digest | Counts (plan, apply) | Result |
|---|---|---|---|---|---|
| `deploy-tn-s1-a1.md` | A1: Neptune `db.r6g.xlarge`, nine interface endpoints, loader | `99faef30` | `b4d3fff9` | 19/6/3, 19/6/3 | `DEPLOY_COMPLETE`; reload `SUCCEEDED`, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788` |
| `deploy-tn-s2-a2.md` | A2: services stack, worker role, Bedrock answer model, Neptune downsize | `055b7855` | `b4d3fff9` | 34/3/6, 34/3/6 | `DEPLOY_COMPLETE`; query failed to start on empty auth secrets, human fill and redeploy (deviation) |
| `deploy-tn-s2b-a2b.md` | A2 re-plan at `38421c6` | `98a13413` | `9586ec21` | 4/3/4, 4/2/4 | `DEPLOY_COMPLETE` |
| `deploy-tn-s3-a3.md` | A3: answer model to stub | `a24588eb` | `9586ec21` | 1/2/1, 1/2/1 | `DEPLOY_COMPLETE` |
| `deploy-tn-s4-down.md` | A4: session-down | `aefd76b8` | `9586ec21` | 2/7/31, 2/7/31 | `DEPLOY_COMPLETE`; H.4 reduced read clean |
| `smoke-tn.md` | SMOKE1, SMOKE2, STUB1 | n/a | n/a | n/a | SMOKE1 FAIL, SMOKE2 FAIL, STUB1 PASS |

Common to all five deploy records: environment `course`, region `us-east-2`, account `000000000000`, cost bound 20 USD, deploy script digest `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` (the same at both git revisions, `32e401a05a221dae081e3c35d4ada7aa787f1175` for A1 and A2, `38421c6f986ddd09cc4c1e5ac309a530f760f2a9` for `tn-s2b`, `tn-s3`, `tn-s4`), approved by `winnguyen`, 8 h expiry, single use. Approved-at values: A1 and A2 are derived from the logged expiry (the record logs for `tn-s1` and `tn-s2` are not in the logs directory); `tn-s2b`, `tn-s3` and `tn-s4` are read from their record logs (23:52:41Z, 00:10:55Z, 02:14:21Z).

Not written, pending: a release record for the afternoon B-only session (run `exp-B-20261117T185928Z-5c69ba90`, down apply plan `dc000094...`). The decision whether such a record is wanted is still open with the orchestrator (runbook H.6, "PENDING decision"). Experiment results (D, E, F, C) are the experiment-engineer's (P15-46 step 3); the ledger below only cites them.

## Session outcome (from evidence.md; deploy and smoke figures cross-checked to logs in the records)

| Item | Result |
|---|---|
| Window | A1 apply 2026-11-17T22:34Z (16:34 local) to A4 apply 2026-11-18T02:14Z (20:14 local); the 01:15 local hard stop was not reached |
| Reload (graph-only, A's export) | SUCCEEDED, 5.2 min, `NEPTUNE_LOAD=LOADED vertices=32225 edges=135788`, estimated 0.08 USD |
| Smoke | SMOKE1 FAIL (question set, finding 2); SMOKE2 FAIL (Haiku synthesis rejected, finding 3); STUB1 PASS |
| D | `exp-D-20261118T002157Z-38421c6f` COMPLETED, 2,896 s, estimated 0.44 USD (ceiling 0.65) |
| E | `exp-E-20261118T012347Z-38421c6f` COMPLETED, 673 s, estimated 0.08 USD (ceiling 0.45); mixed provenance (P15-44 D1 option 2) |
| F (first) | `exp-F-20261118T011431Z-38421c6f` COMPLETED by the harness, 245 s; INVALID as a recovery result (finding 4); kept |
| F2 | `exp-F-20261118T013740Z-38421c6f` COMPLETED, 246 s; the F result (P15-45), derive verdict PASS 9, FAIL 0 per evidence.md |
| C | `exp-C-20261118T014553Z-38421c6f` COMPLETED, 509 s, estimated 0.06 USD (ceiling 0.60); valid per P15-45 |
| Teardown | `tn-s4` `DEPLOY_COMPLETE`; H.4 reduced read: no EMR cluster, no running task, no Neptune, only Gateway endpoints, no NAT, Elastic IP, load balancer, unattached volume or running instance |
| Billing gate | 6.18 billed + 2.27 LAG + 4.50 HIGH = 12.95 against 14: GO (P15-43, `phase15-tn-billing-gate-220035.log`) |
| Billed cost | PENDING: read from Billing the next day against the gate. Estimated spend (burn x time, not a bill): experiments 0.64 USD, reload 0.08 USD |

## Approval ledger (cited from evidence.md, with the deploy rows cross-checked)

All records: environment `course`, region `us-east-2`, approved by `winnguyen`, 8 h expiry, single use. Script digests: deploy `961ce19b...`, pipeline `4b6ca5d7...`, experiment `e3787c42...` (the last from evidence.md only).

| Record | Action | Plan | Args | Cost bound | GIT_SHA | State ack | IAM line |
|---|---|---|---|---|---|---|---|
| tn-s1 | deploy | `99faef30` | `b4d3fff9` | 20 | `32e401a` | no | typed, wording not seen |
| reload | pipeline | n/a | `2a942e58` | 20 (spend ceiling 1.70, estimated 0.08) | n/a | no | none |
| tn-s2 | deploy | `055b7855` | `b4d3fff9` | 20 | `32e401a` | no | typed, wording not seen |
| tn-s2b | deploy | `98a13413` | `9586ec21` | 20 | `38421c6` | no | none |
| tn-s3 | deploy | `a24588eb` | `9586ec21` | 20 | `38421c6` | no | none |
| d, f, e, f2, c | experiment | n/a | `4513b718`, `e35cab71`, `9cb63983`, `e35cab71`, `56873bc3` | 0.65, 0.25, 0.45, 0.25, 0.60 | `38421c6` | no | none |
| tn-s4 | deploy | `aefd76b8` | `9586ec21` | 20 | `38421c6` | yes | D5 line not typed (deviation 9, accepted P15-45) |

The experiment rows are not re-verified here: only the D run log header was read (approval verified, `RUN_ID=exp-D-20261118T002157Z-38421c6f`, `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=2896`).

## Rollback gate (runbook, all applies)

There is no reversal of an apply. The way back is a new plan with a new record (runbook H, executed as `tn-s4`); pushed image tags are immutable. A half-applied plan is re-planned under a `-2` name; no second apply without a new plan, record and decision. A1 detail: Neptune data is destroyed at teardown with no snapshot. Teardown (A4) has no reversal either; a new session needs a new session-up plan, record and graph load. No rollback was exercised. The rollback gate was not triggered.

## Findings (evidence.md) and where each is routed

1. Empty auth secrets (A2): the query service failed to start. Fixed in session by the human, outside the reviewed scripts (deviation 1). P15-45: Phase 15 docs fix and a limitation; owners infra-engineer and deploy-engineer for a documented, scripted fill step.
2. `builtin:windows` questions abstain before retrieval: fixed in `38421c6` (load-client question wording only). SMOKE1 evidence in `smoke-tn.md`.
3. Haiku synthesis output rejected (SMOKE2): Phase 15 limitation, fix in Phase 16; diagnosis UNVERIFIED (stop reason not logged).
4. F's first run hit `ChangedVectorContent`: F (first) is invalid as a recovery result and kept; F2 is the F result (P15-45).
5. The load client does not capture `abstentionReason` and the smoke check does not test evidence or abstention (SMOKE1 passed the script check): Phase 16 with finding 3.

## Deviations, with decision IDs where one exists

| # | Deviation | Decision or disposition |
|---|---|---|
| 1 | Secrets filled by hand with `put-secret-value`, query force-redeployed, outside the Phase 13 scripts (`phase15-tn-secret-fix.log`) | P15-45 (finding 1): recorded as a deviation and a limitation. `deploy-tn-s2-a2.md` |
| 2 | Commit `38421c6` during the session (P15-31 forbids repository writes during a session); load-client image built and pushed by hand at `38421c6`; services and loader images rebuilt by `deploy.sh` from the same contexts | Human ruling per evidence.md; no decision ID in P15-43 to P15-46. `deploy-tn-s2b-a2b.md` |
| 3 | A2 re-planned as `tn-s2b`, not in the runbook; pin file moved to `38421c6`, old pin kept as `...-32e401a.txt` (both files seen) | No decision ID in my sources. `deploy-tn-s2b-a2b.md` |
| 4 | Reader R output not shown for `tn-s2b`, `tn-s3`, `tn-s4`; the plan-read logs for those three are absent | No decision ID. Plan summaries from the plan logs were used |
| 5 | Order changed from P15-10 (D, E, F, C) to A3, D, F, E, F2, C | No decision ID in my sources |
| 6 | D's first start failed (`caffeinate` took `AWS_PROFILE=...` as the program, exit 127, record not consumed); restarted at 18:21 local | Recorded in evidence.md only |
| 7 | Experiment digests recomputed into `-2` logs; values equal the evening ones | Recorded in evidence.md only |
| 8 | Vectors deleted in six batches and the embedding DLQ purged twice outside the runbook | P15-45: C's 2,000 keys removed from three indexes, a limitation |
| 9 | `tn-s4`: the D5 IAM line not typed before the record | Accepted, P15-45. `deploy-tn-s4-down.md` |
| 10 | C pre-check and F2 and C outcome tallies not shown before the next step | evidence.md only; later derived per the Pending list there |
| 11 | H.4 run in a reduced form | No recorded acceptance in my sources. `deploy-tn-s4-down.md` |
| 12 | Earlier read checks and the B-only HEAD gate, recorded in the summary | evidence.md only |

## Secret values present gate (P15-45 finding 1, amended 2026-11-18)

Reason: tonight's query start failure (`deploy-tn-s2-a2.md`, deviation 1). After the A2 apply the query service could not start because `scholarchain-course/auth-query-token`, `auth-read-token` and `auth-cursor-secret` had no `AWSCURRENT` version; the human filled them by hand, outside the reviewed scripts.

Amendment: `session-tonight-runbook.md`, section "Template amendment (P15-45)" at the end of the file (SV.1 read-only gate with a `SECRETS_PRESENT=yes|NO` verdict that exits non-zero on `NO`, SV.2 fill per absent secret, each placed before the A2 apply and before any `run-task`). The commands and the value rules live only in `infra/terraform/SESSION2.md` section 8 ("Secret values present", "Secret fill"), the canonical source; the amendment points to them and copies none. No generic template exists under `docs/releases/`, so that runbook is the template. Requirement: every future session runbook for this project (any session that applies the services stack or runs a task) must carry the gate and fill placements and the STOP rule, and point to SESSION2.md section 8 for the commands. Revision 4 itself is not rewritten.

Pending, human ruling: restarting a failed query service after a late fill (a forced new deployment) is a service mutation outside the Phase 13 scripts. It is not a routine step: it needs a human decision and a deviation record.

Pending, human decision: the secret values left in Secrets Manager persist across session-down (idle items). Rotation or deletion is not decided; until it is, a later session finds them present.

## Gaps I could not fill from evidence

- Typed IAM approval lines for `tn-s1`, `tn-s2`: the wording is in the chat only; the ledger says they were typed.
- A1 and A2 approved-at times and the record logs (`phase15-tn-s1-deploy-record.log`, `phase15-tn-s2-deploy-record.log` are not in the logs directory); expiry-derived values are used.
- Apply end times for every apply: no timestamps in the apply logs.
- The `session2.tfvars` change of `load_client_image_tag` from `32e401a0` to `38421c6f`: the file now holds the new value, but no log records the edit.
- Plan-read logs for `tn-s2b`, `tn-s3`, `tn-s4` (deviation 4) and a gate G log for `38421c6`.
- The S.5b `describe-images` log for the load-client image (`phase15-tn-load-client-image.log`).
- Whether the post-A3 query-service `COMPLETED` read was done as a separate step.
- The exact `put-secret-value` and redeploy commands and their times.
- The human's explicit decision to continue to A3, D and E after the SMOKE2 failure (runbook S.6 requires one).
- The cause of the A2b image manifest digests differing from the A1 digests under equal build contexts; the cause of A2b's plan-versus-apply change count (3 versus 2); the Neptune instance class after the A2 modification.
- H.4 sections not in the log (24 h EMR list, Step Functions, DLQ depths).
- Billed cost for the session (next-day reading).

## Next gate

Independent review of these records (P15-46 step 5, after the results summary and docs page). No AWS or deploy action is pending from this directory. Manual action for the human: decide on the secret values left in Secrets Manager, on the reduced H.4 read, and on whether a release record is wanted for the afternoon B-only session.
