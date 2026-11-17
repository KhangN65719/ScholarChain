# Tonight runbook PENDING: infra-engineer report (received 2026-11-17; summary as received)

Docs-only edits to `infra/terraform/SESSION2.md` (new section 5b, five-line `session2.tfvars`, section 8 A4 IAM and destroy wording, an `ecr describe-images` check before D). Nothing run; counts predicted from code.

1. Counts from the B state (split layout, 8 endpoints, loader 512/1024, tag at HEAD):

| Plan | Add | Change | Destroy |
|---|---|---|---|
| A1 | 11 | 6 | 3 |
| A2 | 34 | 3 | 6 |
| A3 | 1 | 2 | 1 |
| A4 | 2 | 7 | 31 |

A1: 7 Neptune-module resources, `bedrock-runtime` endpoint, 3 replaced task definitions; changes runtime and loader policies, fast and slow services, state machine, overview dashboard. A2: worker role and policy, ECR repo and lifecycle, 3 log groups, 3 task definitions, 2 SGs + 7 rules, ALB (3), query and worker services, 6 scaling, 3 alarms, scaling dashboard; Neptune writer in place xlarge -> t4g.medium; `containerInsights`; overview dashboard; destroys fast and slow task definitions, services, log groups. A3: query task definition replaced (stub, slow-deadline env removed), query service and autoscaling target (max 1 -> 4) in place. A4: destroys 9 endpoints, 7 Neptune, worker service, ALB (3) and p95 alarm, 2 targets, 4 policies, 2 worker alarms; query and loader task definitions replaced.
IAM correction: `modules/neptune/main.tf:58-67` creates `aws_iam_role.bulk_load` (trust `rds.amazonaws.com`) and its policy. A1 = 2 policy updates + 2 creates; A2 = 2 creates (worker role and policy); A3 none; A4 = 3 narrowings + 2 destroys (bulk-load role and policy). D5 approval names the new principal. Possible provider replacement of the query service in A4 (load_balancer removal): +1/+1, not a stop, unverified.
2. A4 `service_cpu`: confirmed defaults 256/512 (`environments/course/variables.tf:414-424`); 4096 without CPU fails (`modules/runtime/variables.tf:119-131`); 2048 without CPU plans CPU 512 -> 256. Appending `service_cpu = 512` and `service_memory = <2048|4096>` to `session2.tfvars` passes and gives the same pair in A2/A3/A4. A4 query task definition replaced either way (`SCHOLARCHAIN_BEDROCK` on -> off, `SCHOLARCHAIN_NEPTUNE_HOST` -> ""; `main.tf:80-81`). E.5 approach stands.
3. `verify_revision`: no `REVISION_MISMATCH` at A2; `deploy.sh:206-212` pushes services and loader only, `verify_revision` (`:214-216`; `lib.sh:934-976`) checks image existence only for those two and compares strings for every family, so load-client prints `REVISION_OK` with its image absent. Order: A2 deploy (creates repo), manual `docker push` at HEAD, then first `run-task`. A passing verify is not evidence of the push; `ecr describe-images` check added before D.
