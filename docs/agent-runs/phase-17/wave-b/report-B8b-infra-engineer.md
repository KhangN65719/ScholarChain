# WO-B8b report (infra-engineer, read-only), as received 2026-11-19 (condensed by the orchestrator; citations kept)

STATUS: COMPLETE. No Terraform change is needed for path (i) without live Haiku.

## 1. A2-stub overlay set
The A2-stub set is `course` + `experiment-size-s` + `services-session` + `session2` (off-repository), with no `services-smoke`. This is the Phase 15 A3 set (`SESSION2.md:71, 230`).
- The answer model is stub: `services-session.tfvars:49`, with the same default at `variables.tf:467`, reaching the task through `main.tf:84`.
- `enable_bedrock` is not required for the stub (`variables.tf:474-477`, `AwsSettings.scala:260-261`). Keep it, though: dropping it saves about 0.03 USD but needs a new overlay, gives untested plan shapes, and removes the loader's Titan path.
- No Haiku call is made, so P17-26 holds and R1 is resolved.
- Unverified: whether the off-repository `session2.tfvars` sets only run ids, image tag, CPU and memory, with no `answer_model` or `slow_deadline_millis`.

## 2. Slow deadline
The default 0 means 10 s (`variables.tf:492, 494`; `main.tf:91`). P16-34 applies only to Haiku (`phase-16/decisions.md:39`), and stub queries take milliseconds. No change is needed. P17-25 stays valid only for a Haiku session.

## 3. Sequence for path (i)
0. **Gate:** Billing; secrets present; export listing; load-client ECR repository exists. Skip the Haiku checks.
1. **A1:** `course` + S + `graph-load` + `session-down-services` + `session2` (the last two keep the stack and combined layout, `session-down-services.tfvars:19-20`), then `deploy.sh`, with an approval record and IAM line.
   - Expected IAM: 2 creates (the bulk-load role, principal `rds.amazonaws.com`).
   - Stop on any role, ECR, log-group or security-group destroy, or a Neptune replace.
2. **Reload:** `run-bounded-pipeline.sh` loader-only (`AwsIngestMain RUNSLUG --graph-only`), `--size small --max-minutes 15 --spend-ceiling-usd 0.25`, approval record action `pipeline`.
3. **A2-stub:** the set above, then `deploy.sh`, with an approval record. Expect no IAM change; the writer goes from xlarge to t4g in place.
4. **Image push:** the load-client image at the HEAD SHA (this can precede A1).
5. **Queries:** `ecs run-task --limit-queries 4`.
6. **A4 teardown:** `session-down` + `session-down-services` + `session2`, with `STATEFUL_CHANGE_ACKNOWLEDGED=yes`. Expect 2 IAM destroys.
7. **Post-teardown read.**

`--cost-ceiling-usd 2.30` (decimal) is unverified, because `lib.sh` was not read.

## 4. Projected HIGH cost, no Haiku (UNVERIFIED rates)
| Item | Arithmetic | USD |
|---|---|---|
| xlarge window | 40 min x 0.97 | 0.65 |
| Services | 60 min x 0.37 | 0.37 |
| Teardown | | 0.09 |
| Other | | 0.10 |
| **Total** | | **1.21** |

- Margin to the 2.30 ceiling: 1.09.
- Sensitivities: 60 min xlarge gives 1.53; 90 min gives 2.02; 90 min xlarge plus 120 min of services gives 2.39, over the ceiling.
- Keep the services window to 60 minutes or less.

## Risks
- SMOKE2 abstained live and the Phase 16 fix is unproven on AWS, so a stub query may abstain too.
- Evidence and explain are not reachable live (port 8080).
- The load client records no answer text.
