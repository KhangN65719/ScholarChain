# Release record: deploy-tn-s2b-a2b (session 2 "tonight", A2 re-plan at `38421c6` after the mid-session commit)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only, written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No AWS, terraform, docker or script was run to write it. Account masked (`000000000000`). No secret value appears here.

This apply is not in the runbook. The runbook names A1, A2, A3 and A4; `tn-s2b` was added during the session (evidence.md deviation 3). It follows the runbook's re-plan rule in form (new `NAME`, one plan per out-dir, new record, no reuse of the old plan) but is a new apply at a new HEAD, not a repeat of a failed one.

| Field | Value | Source |
|---|---|---|
| Result | `DEPLOY_COMPLETE`, steps 1 to 12 all `ok` | `phase15-tn-s2b-deploy.log` |
| Environment, region | course, us-east-2 | same |
| Account | `000000000000` | same |
| Script revision (git SHA) | `38421c6f986ddd09cc4c1e5ac309a530f760f2a9`, `DIRTY=no`, `CLEAN_TREE` | `phase15-tn-s2b-plan.log`, deploy log |
| Deploy script digest | `961ce19b54e5bdfef3c5297e7998f0590b9abbe2a86c9c0bc4e453192820d504` (identical to A1 and A2 at `32e401a0`) | `phase15-tn-s2b-deploy-digest.log` |
| Plan name, file | `p15-tn-s2b`, `course-38421c6f986d.tfplan` | plan log |
| Plan digest | `98a134131f4e9a87400f1a41a192ed0e2a7569b107e61d5e2df7f4430ccd0f43` (`PLAN_BOUND ... stateful_change=no`) | deploy log |
| Args digest | `9586ec2175b8069f01173abd20806554ffe3d4a7cbbd288029e3a423cb2ddfe2` (image tag `38421c6f...`; services and loader contexts `a70d0d1152f7620ab5a93a03680b457c9f0cc744a305d2c437b66cc9cdb3082c`, equal to A2's) | digest log |
| Cost bound | 20 USD | record log |
| Approval record | `approvals/p15-tn-s2b-deploy.env`: `APPROVED_BY=winnguyen`, `APPROVED_AT=2026-11-17T23:52:41Z`, `EXPIRES_AT=2026-11-18T07:52:41Z`, `GIT_SHA=38421c6f...`, `STATEFUL_CHANGE_ACKNOWLEDGED=no`, `COST_CEILING_USD=20`, account `<masked>` (`phase15-tn-s2b-deploy-record.log`); consumed at apply | record log, deploy log |
| Apply start | 2026-11-17T23:52:58Z (apply log name `course-20261117T235258Z.apply.log`) | deploy log |
| Plan summary | 4 to add, 3 to change, 4 to destroy; `IAM_CHANGES: none`; `HIGH_COST_CREATIONS: none`; `STATEFUL_CHANGE=no` | plan log |
| Apply result | "4 added, 2 changed, 4 destroyed" | deploy log |

## Why it was needed

The human committed `38421c6` during the session (finding 2: the `builtin:windows` load-client question wording; P15-45 says "load-client question wording only"). `deploy.sh` binds a plan to a clean tree and the HEAD (`PLAN_BOUND ... git_sha=`, `CLEAN_TREE`), so no old plan could be applied at the new HEAD. The purpose given in the work order is to move every task definition to `38421c6`; the load-client question change needed a load-client image at the new SHA (my inference from the finding, not a logged statement). Repository commits during a session are against P15-31; this one was a human ruling (evidence.md deviation 2). The runbook's pin file was moved: `phase15-tn-commit-sha-32e401a.txt` holds the old SHA and `phase15-tn-commit-sha.txt` holds `38421c6f986ddd09cc4c1e5ac309a530f760f2a9` (both read for this record).

## Prerequisites verified

- Plan log: `GIT_SHA=38421c6f... DIRTY=no IMAGE_TAG=38421c6f...`; `IAM_CHANGES: none`.
- DD dry run (`phase15-tn-s2b-deploy-dry.log`): `CLEAN_TREE`, `APPROVAL_VERIFIED`, `APPROVAL_SKIPPED_FIELDS=none`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`.
- Immutable images: rebuilt by `deploy.sh` at the new tag from contexts identical to A2's (`a70d0d11...`, per the args canonical string). `IMAGE_PUSHED` services `sha256:32358714c35aab3520f8e3ac974a5e1ee9520da06e72868a34273d0ac7fd9ed3`; `IMAGE_PUSHED` loader `sha256:c38bb57aab322fc725363817fe9ca5a705927bc0dfedcd7708d012cc846c2e8b`. The image manifest digests differ from the `32e401a0` builds (`e1236153...`, `b6839af3...`) although the context digest is equal, and one small layer was pushed in each repository. The cause was not examined: UNVERIFIED (the same layers of the base and `lib/` were reused from cache).
- Load-client image: built and pushed by hand at `38421c6f...` under the runbook S.5 step form (`phase15-tn-load-client-build-2.log`), digest `sha256:ec96e9b6cc743f4125152c876a1c7aa313f8fc32083763d2f98ee1409e998e57`, base `python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df`. The build log shows a Dockerfile warning `InvalidDefaultArgInFrom` for the default `PYTHON_BASE` argument; the build used the digest-pinned base passed on the command line and the pinned-digest check step was cached.
- `session2.tfvars` now carries `load_client_image_tag = "38421c6f..."` (read for this record; the file holds run ids, CPU, memory and the tag, no secret). The edit from `32e401a0` to `38421c6f` is not in any log: when and how it was changed is UNVERIFIED. It is consistent with the `REVISION_OK family=scholarchain-course-load-client ... :38421c6f` line below.
- Reader R output for `tn-s2b` was not shown (evidence.md deviation 4), and `phase15-tn-s2b-plan-read.log` is not in the logs directory. The plan log's own summary was used for the counts and IAM above. A gate G log for the new HEAD is also absent (only `phase15-tn-gate-223044.log` for `32e401a0`): UNVERIFIED that G was rerun.

## What changed

Added 4 and destroyed 4: the task definitions `load-client`, `worker`, `query` and `loader`, each replaced with a revision naming `38421c6f...`. Services `worker` and `query` modified in place to the new revisions. The plan also listed the pipeline state machine as a third in-place change; the apply log shows no state-machine modification (apply "2 changed"). The runbook calls this the known state-machine plan-versus-apply gap; I did not verify that known-gap claim, and whether the live state machine references the new loader revision is UNVERIFIED. (The A4 apply did modify the state machine.) No IAM change, no stateful resource, no endpoint, no Neptune change.

## Smoke evidence (read-only, deploy log)

`IMAGE_PRESENT` for services and loader at `38421c6f...`; `REVISION_OK` for families `load-client`, `loader`, `query`, `worker`; `SERVICE_REVISION_OK` for `query` and `worker`; `DEPLOYED_REVISION=38421c6f986ddd09cc4c1e5ac309a530f760f2a9`; `DEPLOY_COMPLETE ... plan_digest=98a13413...`. Service health after this apply is covered by SMOKE2 (`smoke-tn.md`), which ran at 00:02 UTC and returned HTTP 200 on all four requests.

## IAM

None (plan summary `IAM_CHANGES: none`). No IAM approval line was required.

## Rollback gate (runbook)

No reversal of an apply; the way back is a new plan with a new record (the A4 teardown). Pushed tags are immutable, so `32e401a0` images stay in ECR. Not exercised.

## Deviations and risks

- Deviation 2 (commit during the session, human ruling) and deviation 3 (this extra apply, pin file move): evidence.md; the P15-45 ruling does not re-examine them individually and neither does this record.
- Deviation 4 (reader R output not shown for `tn-s2b`): the plan-read log is absent.
- Billed cost: PENDING. No new billable resource was created; the session clock and idle items were unchanged.
