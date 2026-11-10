# Work order 04: infra-engineer, Terraform under infra/terraform

Owner: infra-engineer. Scope: `infra/terraform/**` only. The scripts under `scripts/` are work order 05, and the cost estimate is work order 06. Do not touch Scala, `build.sbt`, `.gitignore`, `.claude/**`, `docs/agents.md` or CLAUDE.md. Do not run sbt. Local only, no commit.

Read first: docs/agent-runs/phase-12/prompt.md (the course section and the scope note), decisions.md D2, D2a, D2b, D3 and D4, CLAUDE.md, docs/agents.md and your role file.

## Binding constraints
- Allowed commands: `terraform fmt -check` (and `terraform fmt` to fix formatting), `terraform init -backend=false`, `terraform validate`. Nothing else from terraform. No plan, no apply, no AWS credentials, no AWS calls, no `aws` CLI. If the guard blocks one of them, stop and report `BLOCKED-ON` with the exact refusal. The orchestrator gives the human a `!` command.
- Total project budget is $25 (decision D2). Every default is the cheapest viable choice. Nothing public. No secrets or secret values in source. Secrets Manager entries are referenced by ARN or created without a value.
- Region us-east-1, one AWS account. Pin the Terraform and provider versions exactly (use the approved current versions and state their release dates; ask the orchestrator if unsure). No new tool.
- Account facts: Free plan with credits, no root user, IAM users are programmatic-only. Neptune and EMR need the Paid plan, a Phase 14 human decision. Never suggest or script "activate advanced features".

## What to build
Follow the 13 numbered areas of the course text in prompt.md, as modules under infra/terraform/modules with one environment under infra/terraform/environments (for example `course`), and variable files without secrets. Required design points:
1. Foundation: pinned providers, remote-state design documented (S3 plus lock, written in a README only and not created in Phase 12; the local backend stays until Phase 14), required tags (project, phase, owner, environment, cost-center), budgets as variables with thresholds. The instructor alerts are $10, $15 and $20 of a $25 total (D2). The existing AWS budgets are $25 and $50. Do not guess: make the budget resources optional and flag the mismatch.
2. Network: two AZs. To save money, default to no NAT gateway (about $0.045 an hour each, plus data). Use gateway endpoints for S3 and DynamoDB, and interface endpoints only where justified and priced. Say what the default egress is, and what reaches the internet. Security groups chained by role.
3. Identity and encryption: separate roles for EMR, ECS tasks, Step Functions, deployment execution, loaders and runtime readers. No `*` on resources where an ARN is known. No IAM user or access-key resources. Bedrock permissions are scoped to the Haiku 4.5 inference profile and the Titan Text Embeddings V2 model ARNs behind a variable that defaults to off (D2a). No `bedrock:*` on `*`. Prefer AWS-managed keys over customer KMS keys where the course allows (a KMS key costs about $1 a month each). Justify each choice.
4. Data lake: raw, curated, graph, rag, artifacts, logs and reports buckets, or prefixes in fewer buckets (cheaper; justify). Versioning where needed, encryption, public-access block, lifecycle rules with short expiry for scratch data, deletion safeguards. `force_destroy` is a variable, off by default and true only in the explicit course cleanup mode.
5. EMR: prefer the cheapest viable shape. Decide between EMR on EC2 with minimal instances (including Spot for task nodes) and EMR Serverless. Implement the choice as a variable if both are cheap to carry, and state the default and why. Bounded default capacity, a managed scaling cap, auto-termination (idle timeout), logs to the logs bucket.
6. DynamoDB: on-demand, with the required tables and indexes taken from the code (read modules/storage for the table and key design, and do not invent it). Point-in-time recovery is off by default in the course environment and on in a persistent one, behind a variable.
7. Neptune: compare Neptune Serverless at the minimum capacity with the alternatives, and make the cluster optional behind a variable, because it is the likeliest threat to the budget. Cost it in work order 06. Subnet and parameter groups, encryption, deletion protection as a variable (off for the ephemeral course environment), loader role and read-only runtime role.
8. S3 Vectors: vector bucket and one index per required index. Read the index names, dimensions and distance metric from modules/storage and modules/rag. The dimension is a variable (1024 or 256, D2a). If the pinned provider has no S3 Vectors resource, report that as BLOCKED-ON with evidence and document the exact gap. Do not fake it with a null_resource.
9. OpenSearch: not deployed (no implemented and evaluated hot tier). Leave a documented, disabled stub or a README note only.
10. Runtime compute: ECR repositories (immutable tags, scan on push), an ECS cluster and Fargate task definitions. Read which services exist in modules/services before choosing the consolidation, and justify any. Smallest CPU and memory sizes, desired count 0 or 1 by variable. Internal service discovery. Ingress: default none public. If an approved ingress is needed, make it a variable and flag it for the human.
11. Workflow and messaging: Step Functions state machines for the bounded pipeline (Hadoop, then Spark, then load), SQS queues with dead-letter queues. EventBridge only if used.
12. Observability: log groups with short retention, a small dashboard, and alarms for the budget-relevant signals. Keep the metric set modest, because dashboards and alarms cost money.
13. Cost and safety: bounded defaults, variable files without secrets, deletion protection for stateful resources in a persistent environment, and an explicit cleanup mode for the course environment (a single variable such as `cleanup_mode` that turns on force_destroy and turns off deletion protection). Do not create anything that bills while idle without flagging it.

## Procedure
1. Before writing, list which check proves each item of the primary gate (fmt, validate, a read-only review). `terraform validate` proves syntax and types only, not IAM correctness or cost. Say so in the report and do not overclaim.
2. Write the modules. Run `terraform fmt -check`, then `terraform init -backend=false` and `terraform validate` in each root module you create. Report the exact output and exit status of each.
3. Provide an idle-cost table in the README: every resource that bills while idle, with its rate source and its default (on or off).
4. Do not hide failures. Anything not done is listed with its reason.

## Report (contract fields, as plain text in your final message)
STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN (exact), VALIDATION RESULTS, then: the module list, the default for each cost-relevant choice with its reason, the IAM role list with the actions and resources each is limited to, resources that bill while idle, items that need the Paid plan, anything BLOCKED-ON, and the Phase 14 human actions.
