# WO-10 report, infra-engineer (received 2026-11-17; summary of the report as received; author: infra-engineer subagent)

Status: COMPLETE for authoring; nothing formatted, validated, planned or applied (guard refused `terraform fmt`/`validate`; formatting hand-aligned, `check.sh` may report fmt). No AWS documentation read (tools unavailable to the agent). Full design and every human command: `infra/terraform/SESSION2.md`; costs: `infra/terraform/COST_ESTIMATE.md`.

## Files
Created `infra/terraform/SESSION2.md`, `modules/observability/scaling_dashboard.tf`, `environments/course/services-session.tfvars`, `services-smoke.tfvars`. Modified `COST_ESTIMATE.md`, `modules/runtime/{main,variables,outputs}.tf`, `modules/identity/{main,outputs}.tf`, `modules/network/{main,outputs}.tf`, `modules/observability/variables.tf`, `environments/course/{main,variables,outputs}.tf`, `course.tfvars`, `session-down.tfvars`. Nothing under `scripts/`.

## Decisions
- ALB: internal, HTTP 80 to target group HTTP 8081 (slow port serves `POST /v1/query` with role `both`), target type `ip`, `/healthz`; one `query` service (`service_layout = combined`) because query records are in-process. Bearer token crosses the VPC in clear (no certificate on an internal DNS name): needs the human's acceptance.
- Health-check grace 300 s (PROVISIONAL). Memory/CPU 512 / 4096 MiB (PROVISIONAL; arithmetic 128 MiB x 6 parsed + headroom gives about 2.7 GiB); variables `service_memory`, `service_cpu`, `service_jvm_options`; decision rule in SESSION2.md section 7.
- Query scaling: min 1, max 4 (2 vCPU; worst case 5 vCPU of 30); `ALBRequestCountPerTarget` 300/min and CPU 60 %; cooldown out 60 s, in 300 s. D rate = clients / (L + 3.0): 8 clients 137/min (no scale-out), 24 clients 411/min (crosses), 48 clients 823/min (about 3 tasks). One task has 16 calls + 32 queued: early 429/503 at the 48 level possible, record not tune. Scale-in about 15 one-minute datapoints (UNVERIFIED): keep services up at least 25 min after the client ends.
- Worker: step scaling on backlog per task (metric math), min 1, max 4; out at 150/task (+1; +2 at 450), in below 20 for 5 min; max 4 bounded by the Titan TPM quota. Worker visibility/lease 60/50 s. 512 / 1024 MiB (guess).
- `desired_count` validation 0-2 to 0-4. Log retention 7 days. Second dashboard `${prefix}-scaling` (EMR, ECS tasks, CPU/memory, ALB p50/p95/p99, requests, 5xx, SQS and DLQ, DynamoDB throttles, Neptune).
- Session 2 HIGH about 4.5 USD (plan 4.3; LOW about 1.8; 4.0 with a 60-min graph load); P15-10 then needs billed at most 9.5 USD at session 2 start. Provisional manifest burns: D 0.55, E 0.42, F 0.40, C 0.45 USD/h.
- Applies: A1 `experiment-size-s` + `graph-load`; A2 `experiment-size-s` + `services-session` + `services-smoke` (t4g, services, worker, ALB, Bedrock answer model); A3 `experiment-size-s` + `services-session` (stub; D, E, F, C); A4 `session-down`. No commit between A1 and A3.

## Names for WO-11
Container `load-client`; log group `/scholarchain-course/load-client`; stream prefix `ecs`; family `scholarchain-course-load-client`; cluster `scholarchain-course-cluster`; ECR `scholarchain-course-load-client`, tag `p15-lc-1`; base URL `http://<alb_dns_name>`; outputs `load_client`, `app_subnet_ids`, `ecs_cluster_name`, `embedding_queue_url`.

## IAM diff
New role `scholarchain-course-worker` (trust `ecs-tasks.amazonaws.com` with `aws:SourceAccount`): SQS receive/delete/change-visibility/get-attributes on `embedding-work` only; DynamoDB Get/Put on `run-ledger`; s3vectors GetIndex/GetVectors/PutVectors on the five indexes; S3 ListBucket (prefix `semantic-context/documents/*`) and Get/Put on that prefix; `bedrock:InvokeModel` on Titan V2 when Bedrock is on. Deployer PassRole list gains the worker role (if a deployer role exists). Runtime role unchanged. Load client has no task role. Human's `ecs:RunTask`/`iam:PassRole` UNVERIFIED (SESSION2.md has a read-only `iam simulate-principal-policy` check). `neptune-db:QueryLanguage` condition NOT added (unverified by the agent; orchestrator verified it afterwards, `../doc-verification.md`).

## Human commands
In SESSION2.md section 8: `check.sh --skip-sbt`; Bedrock availability and profiles; DLQ depth; export size and run ID (at most 134,217,728 bytes, 1,000 objects, `_SUCCESS` and manifest present); plans A1-A4; local export size and docker memory/start measurement; after-A2 checks; scale-in desired-count check; `iam simulate-principal-policy`.

## Harness cases likely to change
`env-read` flags `SCHOLARCHAIN_QUERY_TOKEN` on the load client (needs an exemption); ALB must stay in the runtime module for `alb-sg`; existing cases on fast/slow services, Container Insights disabled, `desired_count` 0-2, retention 3 days, runtime header "no load balancer", `session-down` contents, IAM role count, `container_environment` shape and SG lists.

## Open items
- Memory, CPU, grace PROVISIONAL.
- **The S overlay comment says the S export is 324 MB, above the 128 MiB export limit the code enforces: if so the runtime exits 3 at start.** BLOCKED-ON storage-engineer or service-engineer; the export-size check settles it.
- `deploy.sh` pushes only the services and loader images; the load-client image needs a human push or a script change (human decision).
- `graph_run_id` set to `run:v1:192de0fd-1442-4508-9065-09c44775643b` (run 6), UNVERIFIED as the run whose graph Neptune holds; check against the S run 11 loader command before A2.
- Worker S3 prefix `semantic-context/documents/` from the WO-7 report, not from code.
- **The first plan after this change includes the one-time Wave 2 resources** (worker role and policy, 2 SGs, 7 rules, ECR repo, log groups, `query` service at desired 0, Container Insights on, retention 3 to 7 days): that plan is session 1's if session 1 runs on this tree.
- No `ignore_changes` on `desired_count`; no apply during an experiment.
- UNVERIFIED AWS behaviour: target-tracking scale-in timing and no-traffic handling, metric-math in worker alarms, Container Insights and EMR metric names, ALB percentiles in a dashboard, `bedrock:InvokeModel` for Converse, Neptune downsize, SQS one-minute metrics, a service starting before its image exists.
