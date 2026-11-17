# Phase 15 B-only session (afternoon), record (orchestrator, 2026-11-17; from the human's pasted outputs)

Context: the human stopped all agents and repository writes, stashed the uncommitted work (`p15-b-now-leftover`) and ran B immediately at HEAD `5c69ba9` (clean tree), with commands the orchestrator adapted from the committed A + B runbook (the fallback runbook was in the stash). Times UTC; local is UTC-6. Logs `/Users/winnguyen/scholarchain-data/logs/phase15-bn-*`.

## Outcome
- B: run `exp-B-20261117T185928Z-5c69ba90`, `final=COMPLETED`, `elapsed_s=4858`, `estimated_spend_usd=2.09` (ceiling 2.40), exit 0. Cluster `j-0091484IPP1V1PIP0UK`.
- Elasticity (human polls of `describe-cluster` instance groups): 19:31:37Z CORE requested 1, running 3; 19:33:25Z requested 1, running 3; 19:44:36Z requested 1, running 1. Scale-out to 3 core nodes and scale-in to 1 observed (the P15-23 goal). Collector and derive not yet run.
- Teardown: `DEPLOY_COMPLETE` plan `dc000094...`; EMR active list empty before the apply.

- Collector (P.2): `COLLECT_COMPLETE cluster=j-0091484IPP1V1PIP0UK window=2026-11-17T18:49:32Z..2026-11-17T20:30:26Z`, exit 0 (all required and optional calls succeeded after the teardown; `collected/` in the run dir; log `phase15-tn-b-collect.log`).
- Derive (P.3): `scale_out_observed=yes scale_in_observed=yes`, exit 0 (`derived/` in the run dir; log `phase15-tn-b-derive.log`). The scaling gate of Phase 15 has its B evidence (P15-11 fallback not needed for scale-out or scale-in).

## Steps
| Step | Result |
|---|---|
| Billing | `billed=5.33 lag=1.10 high_b=2.93 total=9.36 -> GO` (OD-7 1.15 not added: the 5.33 reading includes session 1; with it 10.51, GO) |
| State | DLQs `0 0`, no EMR cluster, no running EC2 (short form; no Step Functions read) |
| HEAD gate | not shown |
| Up plan | `8 to add, 1 to change, 0 to destroy`, `IAM_CHANGES: none`, `STATEFUL_CHANGE=no`, 8 interface endpoints, `PLAN_DIGEST=bcc6833a...`; action-line read not shown |
| Deploy P/W/D/R | `ARGS_DIGEST=b3014947...` (contexts `a70d0d11...` unchanged); record 18:55:17Z; dry run `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`; apply from 18:55:41Z (T0), `DEPLOY_COMPLETE` plan `bcc6833a...`, `DEPLOYED_REVISION=5c69ba9...` |
| Definition | `ComputeLimits` 1/3/3/3 Instances, `IdleTimeout` 1800, `Seconds` 900, two `TimeoutSeconds` 4200, `b-burst`, no `a-baseline` |
| B records | the unused A + B records `p15-ab-b-pipeline.env` and `p15-ab-b-experiment.env` (17:28:48Z, expiring 01:28:48Z, experiment bound to `5c69ba9`) reused (human's choice; P15-35 had planned new records); dry runs `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, part exits 0 |
| B run | input `p15-ab-b.json` (`run-v1-730f95f0-ff71-4763-bba6-a0ddbc39e87f`); see Outcome; human-reported: pipeline `FINAL_STATUS=SUCCEEDED`, `RECORD_COMPLETE=yes`, `NO_ACTIVE_CLUSTER` |
| Down plan | `IAM_CHANGES: none`, `STATEFUL_CHANGE=no`, no high-cost creation, `PLAN_DIGEST=dc000094...`; counts line and action lines not shown |
| Down P/W/D/R | `ARGS_DIGEST=b3014947...`; record written before the counts read (deviation); dry run `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`; apply `DEPLOY_COMPLETE` plan `dc000094...`, exit 0 |
| Post-teardown | P.1 at 2026-11-17T21:58:17Z: DLQs `0 0` and `0 0`; no EMR cluster, running execution or EC2 instance; only Gateway s3 and dynamodb endpoints; `POST_TEARDOWN_CLEAN=yes`, exit 0 |
| Stash pop | `p15-b-now-leftover` restored (`Dropped refs/stash@{0}`), 9 modified and 24 untracked paths back |

Billable window: 18:55:41Z to the down apply (about 20:25Z to 20:30Z).

## Deviations
1. HEAD gate not shown. 2. Up-plan action-line read not shown. 3. A + B B records reused instead of new ones (human's choice). 4. Down-plan counts not shown before the record and apply. 5. Billing gate used 5.33 + 1.10 (A + B lag) without the OD-7 1.15 (human's choice). 6. Commands adapted in chat from the committed A + B runbook, not from a reviewed B-only runbook (the reviewed fallback was stashed).

## Open
Post-teardown check; `collect.sh` and `derive-capacity.py derive` for the B run; actual billed cost; the combined session-2 runbook (`session-tonight-runbook.md`, FINAL) now runs without its Part B.
