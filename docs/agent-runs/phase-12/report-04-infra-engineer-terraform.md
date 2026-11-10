# Report 04: infra-engineer (work order 04, Terraform), as received (condensed)

STATUS: BLOCKED-ON, partial. The Terraform is written. No tool has checked it. `terraform init -backend=false` and `terraform fmt` were refused by the guard. `fmt -check` and `validate` ran only vacuously from the repo root (no .tf files there).
Owner: infra-engineer. Work order: work-order-04-infra-engineer-terraform.md.

## Files created (all under infra/terraform/)
README.md; environments/course/{versions,main,variables,outputs}.tf, course.tfvars, cleanup.tfvars, persistent.example.tfvars; modules/{foundation,network,identity,data_lake,dynamodb,s3vectors,neptune,emr,runtime,workflow,observability}/{main,variables,outputs}.tf. Files modified: none outside infra/terraform.

## Commands (exact)
Refused by the guard ("outside the role's permitted commands"): `terraform version`; `terraform -chdir=.../environments/course init -backend=false`; `terraform fmt -check -recursive <path>`; bare `terraform init -backend=false`; `terraform fmt`; `cd <environments/course>`.
Ran, vacuously (cwd is the repo root): `terraform fmt -check` exit 0 with no output (twice); `terraform validate` printed "Success! The configuration is valid." with no module to check.
No sbt, plan, apply, AWS credentials or AWS calls. The guard was not worked around.

## Validation
None exists for this code. Hand-aligned `=` signs likely fail `fmt -check`. The first `validate` is likely to fail on the provider pin and the S3 Vectors arguments. `validate` proves syntax, types and references only, not IAM correctness, cost or public exposure.

## Defaults and reasons
- No public subnet, internet gateway, load balancer or public IP. Ingress none.
- Egress mode `endpoints`: S3 and DynamoDB gateway endpoints (free) plus six interface endpoints in one AZ (ecr.api, ecr.dkr, logs, secretsmanager, sts, elasticmapreduce). No NAT. `nat` and `none` are variable options.
- Two buckets (data, artifacts), prefixes from `S3Prefix` in modules/storage. AWS-owned or AWS-managed encryption, no customer KMS key (about $1 a month each).
- DynamoDB on-demand: six tables and three indexes copied from DynamoDb.scala; TTL where `TtlPolicy.Required`. PITR, versioning and deletion protection follow `persistent_environment` (off for course).
- EMR on EC2, transient: Step Functions creates and terminates the cluster. EMR Serverless not carried because the Hadoop stage is MapReduce, which Serverless cannot run. Default one m5.xlarge node, 0 core, 0 Spot, 900 s idle termination, scaling cap 3 units. Logs to emr-logs/ with 14-day expiry.
- Neptune off by default. If on: Serverless 1 to 2.5 NCU (1 is the lowest). Deletion protection and final snapshot follow `persistent_environment`.
- S3 Vectors: one bucket, five indexes (names, cosine, float32 from Vectors.scala). Dimension variable, default 256, allowed 256, 384, 512, 1024.
- Runtime: ECR immutable, scan on push, keep 5 images. Fargate 256 CPU and 512 MiB, desired count 0. One image with fast and slow task definitions, plus a loader task definition and image.
- Workflow: one Standard state machine (EMR create, Hadoop step, Spark step, terminate, then ECS loader task; failure path terminates the cluster; 4 h cap, 1 h per step). Two SQS queues with dead-letter queues. No EventBridge.
- Observability: one dashboard, three or four alarms. Metrics for EMR, DynamoDB, S3 Vectors client, API latency and LLM usage are not covered (README says so).
- `cleanup_mode` (cleanup.tfvars): force_destroy everywhere, no deletion protection or final snapshot, secret recovery window 0. `prevent_destroy` cannot take a variable.
- `enable_bedrock` default false; when on, `bedrock:InvokeModel` on exact ARNs (Haiku 4.5 inference profile, its foundation-model ARNs, Titan V2). No `bedrock:*`.

## IAM roles (no IAM user, access key or login profile anywhere)
emr-service (AWS-managed AmazonEMRServicePolicy_v2; tag `for-use-with-amazon-emr-managed-policies` set on VPC, app subnets, EMR security groups); emr-ec2 (S3 only); ecs-execution (ECR pull, log writes, GetSecretValue on three ARNs; `ecr:GetAuthorizationToken` needs `*`); runtime (S3 read, DynamoDB Get and Query, PutItem on query-session and evidence-bundle only (assumption for the service owner), S3 Vectors Get/Query on five index ARNs, SQS send, Neptune read behind a flag, Bedrock behind a flag); loader (S3, DynamoDB on run-ledger, artifact-registry, researcher-metric-cache, S3 Vectors Put/Get/GetIndex, no CreateIndex, SQS consume, Neptune loader and Titan behind flags); sfn (EMR manage and terminate on cluster/*, ecs:RunTask conditioned, PassRole by iam:PassedToService; `RunJobFlow` and log-delivery APIs need `*`); deployer (only if `deployer_trusted_principal_arns` is set, default empty; no Terraform apply, no data-plane access); neptune bulk-load role (S3 read on graph/* only).

## Idle billing (rates from the agent's MEMORY; work order 06 must verify and cite)
Six interface endpoints, one AZ (on): about $0.01 an hour each, roughly $44 a month, the biggest default idle item. Neptune Serverless (off): about $0.16 per NCU-hour, about $117 a month at 1 NCU. NAT (off): about $0.045 an hour plus data. Secrets Manager (on): 3 secrets at $0.40 a month. Cloud Map hosted zone (on): about $0.50 a month. Alarms $0.10 a month each. Storage near zero when empty.

## Paid-plan items
Neptune and EMR (D4). The state machine, roles, security groups and task definitions can exist on the Free plan, but a pipeline run and the Neptune cluster cannot run without the upgrade. Nothing suggests or scripts "activate advanced features".

## Assumptions and unverified items (all in the README)
- AWS provider `= 6.20.0` is from memory; release date unknown. `aws_s3vectors_vector_bucket` and `aws_s3vectors_index` and their arguments are unconfirmed. Terraform pinned `>= 1.10.0, < 2.0.0` (installed version unreadable).
- Neptune engine 1.4.5.1, family neptune1.4; EMR release emr-7.10.0; Bedrock Haiku 4.5 profile ID, model ID and routing regions; EMR v2 tag requirement; three Step Functions managed-rule names. Account model access unverified (D2a).
- Proposed contracts not read by current code: env var names (SCHOLARCHAIN_TABLE_PREFIX, SCHOLARCHAIN_DATA_BUCKET), ports 8080 and 8081, jar key jars/scholarchain-hadoop.jar, fast/slow selection. No production entrypoint, AWS adapter or packaging step exists yet. Secret env names follow `ServiceConfig.envName`.
- The hash embedder is 384 dimensions and Titan V2 offers only 256, 512 and 1024, so a fallback run needs `vector_dimension = 384`.
- `plan` calls `sts:GetCallerIdentity`, so it needs credentials (Phase 14).

## Risks and manual actions
- `.gitignore` belongs to build-engineer; `.terraform/` and tfstate patterns must be added. `.tfvars` files are meant to be committed and hold no secrets.
- `.terraform.lock.hcl` is missing until a human-run `init` creates it.
- Budget mismatch: instructor $10, $15, $20 alerts vs existing $25 and $50 budgets; budget resource optional and off.
- Remote state documented only. OpenSearch and Glue documented as not deployed. tflint, tfsec and checkov not run (new tools, need approval).

## Phase 14 human actions
Run and fix fmt, init, validate; confirm provider pin and date; decide the Paid-plan upgrade; decide on the budgets and supply notification emails; scoped-credential `terraform plan` and signed plan; verify Bedrock model access, Neptune and EMR versions, and the account; put the three secret values in outside Terraform; choose the vector dimension; choose interface endpoints or NAT per run; approve any ingress; approve `cleanup_mode` through the reviewed cleanup script.

Next gate (agent's words): the human runs the check command, then independent code-reviewer review of infra/terraform, then work orders 05 and 06.
