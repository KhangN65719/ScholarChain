# ScholarChain Terraform (Phase 12, local authoring only)

Status: on 2026-11-09 the human ran `terraform fmt -check -recursive -diff` (exit 0, no diff) and `terraform validate` in `environments/course` ("Success! The configuration is valid.", exit 0) on the files as they stood after repair round 1 (`terraform-checks.md`, Run 3; provider 6.26.0 from Run 2's `init -upgrade`). Repair round 2 (work order 15) edited files again without running any Terraform, so **`fmt -check` and `validate` have not been run on the repair round 2 edits** (the per-tier step timeout variable, the descriptions and the overlays) and must be re-run (see "Checks to run"). Never planned. Nothing here has touched AWS.

## Layout

```
infra/terraform/
  modules/
    foundation     budgets (optional), cost-allocation tags (optional)
    network        VPC, 2 AZs, subnets, endpoints, security groups by role
    identity       roles, policies, Secrets Manager entries (no values)
    data_lake      data and artifacts buckets
    dynamodb       six on-demand tables, three indexes
    s3vectors      vector bucket, five indexes
    neptune        optional cluster (provisioned default, or serverless), bulk-load role
    emr            security configuration and bounded cluster definition
    runtime        ECR, ECS cluster, Fargate task definitions, optional Cloud Map
    workflow       Step Functions pipeline, SQS queues and dead-letter queues
    observability  one dashboard, a few alarms
  environments/course/
    versions.tf    Terraform and provider pins
    main.tf        wiring; default_tags carry the five required tags
    variables.tf, outputs.tf
    course.tfvars, cleanup.tfvars, persistent.example.tfvars   (no secrets)
    experiment-size-s.tfvars, -m, -l, elasticity-example.tfvars (overlays, see below)
    session-down.tfvars   (overlay: stop hourly billing, keep storage; see "Session up and session down")
```

## Pins

- Terraform `>= 1.10.0, < 2.0.0`. The lower bound is the first release with native S3 state locking. The installed version could not be read (`terraform version` was refused), so the exact CLI version is unrecorded.
- AWS provider `= 6.26.0`, released 2025-12-10 (decision D5), declared once in `environments/course/versions.tf`; the modules declare the source only. The pin replaced `6.20.0`, whose `validate` failed because it has no `aws_s3vectors_vector_bucket` or `aws_s3vectors_index`. Per the provider changelog the resources first appear in 6.24.0 (2025-12-02) and 6.26.0 adds the index `encryption_configuration` and `metadata_configuration` blocks; no later entry changes them. 6.26.0 is the smallest version with every argument used. It is over 11 months old; the latest 6.x at fetch time was 6.72.0 (2026-11-04). The argument schema was read from the provider docs on GitHub `main`, not the 6.26.0 tag, so `validate` is the check. The human ran `terraform init -upgrade -backend=false` on 2026-11-09 (Run 2: provider 6.26.0 installed, exit 0), which regenerated `.terraform.lock.hcl`; that file must be committed.

## Defaults, and why (all chosen for the $25 ceiling, decision D2)

| Choice | Default | Reason |
|---|---|---|
| Region, account | us-east-1, one account | D4 |
| Public exposure | none: no public subnet, IGW, load balancer or public IP | brief |
| Egress | `endpoints` mode (the human's choice, D2c): gateway endpoints for S3 and DynamoDB, 8 interface endpoints in one AZ (9 with `bedrock-runtime` when Bedrock is on), no NAT | NAT is about $0.045 an hour plus data. See egress below and `COST_ESTIMATE.md`, which prices both |
| Buckets | 2 (data, artifacts) instead of 7 | prefixes follow `S3Prefix` in modules/storage; lifecycle works per prefix; versioning is the only bucket-wide setting and only artifacts-vs-data needed separating |
| Encryption | AWS-owned or AWS-managed keys everywhere, no customer KMS key | a KMS key is about $1 a month each; the course does not require one; no `kms:*` permission is needed |
| DynamoDB | on-demand, PITR off, deletion protection off | no idle cost; PITR bills by size and is on in a persistent environment |
| EMR | transient EMR on EC2, created and terminated by the state machine; one `m5.xlarge` node, 0 core, 0 Spot task nodes, 900 s idle auto-termination, managed scaling capped at 3 units through `emr_managed_scaling_max_units` (applies only with core nodes). The cap must cover every fixed node: 1 primary + core + Spot task nodes (task nodes count only with a core node), so the largest legal shape is 1 + 4 + 2 = 7 units, and the variable validates the sum (the earlier cap of 6 ignored task nodes). The elasticity run uses 4 core nodes and a cap of 5 | the Hadoop stage is MapReduce, which EMR Serverless cannot run (Spark and Hive only), so EMR on EC2 is forced; a transient cluster costs nothing between runs. Serverless is not carried because it would still need EC2 for Hadoop |
| Spot | off; allowed only for task nodes and only with a core node | HDFS data lives on core nodes; a lost task node costs a retried task, a lost single node would fail the run |
| Neptune | not created (`enable_neptune = false`). If enabled, `neptune_mode = "provisioned"`: one `db.t4g.medium` writer, no replica. `serverless` is selectable: 1 NCU minimum, 2 NCU maximum (was 2.5) | D2c. Serverless has no scale to zero and is the likeliest budget threat; both modes need the Paid plan and are destroyed after each run. Deletion protection and the final snapshot follow `persistent_environment` in both modes. Whether `db.t4g.medium` is orderable for engine 1.4.5.1 is unverified |
| S3 Vectors | 1 bucket, 5 indexes, 256 dimensions, cosine | names from `VectorIndexes`. Dimension is open (D2a); 256 is cheapest. **Changing the dimension destroys the vectors** (next section) |
| Runtime | 1 ECR image for 2 task definitions (fast, slow) plus a loader image; 256 CPU, 512 MiB; desired count 0; Fargate on-demand | see "Consolidation" |
| Bedrock | off; when on, `bedrock:InvokeModel` on exact ARNs only | D2a |
| Logs | 3 days retention | cost |
| Cloud Map | not created (`enable_service_discovery = false`) | no ingress and nothing resolves the names; saves about $0.50 a month. With it off the ECS services carry no registry and are reached by private IP |
| Budgets | not created (`create_budgets = false`); limit and thresholds are variables, defaults 25 and [10, 15, 20] USD | see "Budget mismatch" |

### Egress

Default (`endpoints`): nothing in the VPC can reach the internet. S3 and DynamoDB go through gateway endpoints (free). ECR (`ecr.api`, `ecr.dkr`), CloudWatch Logs, Secrets Manager, STS, EMR, **S3 Vectors (`s3vectors`) and SQS (`sqs`)** go through 8 interface endpoints, which bill hourly per endpoint per AZ. Bedrock adds a `bedrock-runtime` endpoint (a ninth) when enabled.

Why `s3vectors` and `sqs` are in the list (review finding H2): the runtime role may query S3 Vectors and send SQS messages, and the loader role may put vectors and consume SQS, and both run in the VPC. Their security groups allow egress only to the endpoint security group and the S3 and DynamoDB prefix lists, so without these two endpoints the calls cannot leave the VPC. The security-group rules are unchanged: the existing `to_endpoints` egress rule (services, loader, client, EMR nodes to the endpoint group on 443) and the endpoint group's ingress from each role already cover the new endpoints, and the services and loader get no other egress in this mode. The Step Functions service calls its targets itself and the VPC makes no Step Functions API call, so no `states` endpoint is needed.

Facts and their status. S3 Vectors supports an interface endpoint, service `com.amazonaws.us-east-1.s3vectors`, private DNS name `s3vectors.us-east-1.api.aws`: VERIFIED, https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-privatelink.html. The SQS interface endpoint `com.amazonaws.us-east-1.sqs` is the standard one: not read from the docs this round, so UNVERIFIED. Whether both are offered in the AZ of the single endpoint subnet (`interface_endpoint_az_count = 1`): UNVERIFIED until a Phase 14 plan. The S3 Vectors private DNS name also has to be the name the SDK resolves, which depends on the application's endpoint configuration: UNVERIFIED.

`nat` mode builds one NAT gateway in one public subnet (public subnets exist only for it). `none` keeps only the gateway endpoints and will not run Fargate tasks (no ECR or Logs path); it is the egress setting of the session-down overlay.

### S3 Vectors: changing the dimension destroys the vectors (review finding M7)

The five index names (`*-v1`) do not include the dimension. Changing `vector_dimension` (for example 256 to 384 for the hash-embedder fallback, anticipated below) makes the provider replace all five indexes in place, and a replaced index loses its vectors. There is no second environment to absorb it. What exists:

- Guard added: `modules/s3vectors` keeps a `terraform_data.dimension_record` holding the dimension the indexes were first created with (`ignore_changes` on its input), and each index has a `precondition` that fails at plan time when the variable differs from the record, unless `acknowledge_vector_loss = true`. This does not rely on a variable in `prevent_destroy`, which Terraform does not allow. Chosen over renaming the indexes, which belongs to the storage and rag code. How `ignore_changes` on `terraform_data` and a precondition on a value unknown at plan time behave is UNVERIFIED until a Phase 14 plan.
- **Known gap in that guard (review 2, finding L-a).** Flipping `acknowledge_vector_loss` from true back to false is harmless only when the dimension is unchanged. If the flip lands in the same apply as another dimension change, the record is replaced, its output is unknown at plan time, the precondition waits until apply, the record is recreated with the new dimension first, and the indexes are replaced without a plan-time refusal. A precondition cannot refuse this reliably, because it sees only current variable values and not the previous acknowledge value. The guard is a first layer, not a proof. Operating rule: flip it back in an apply of its own with the dimension unchanged. This reading comes from the code and is UNVERIFIED until a Phase 14 plan.
- Second layer, already in the scripts, which still catches the gap: `plan.sh` lists an `aws_s3vectors_index` replacement under `STATEFUL_DESTROY_OR_REPLACE`, and `deploy.sh` then requires `STATEFUL_CHANGE_ACKNOWLEDGED=yes` in the approval record. It depends on a human reading the plan summary.
- A 384-dimension fallback run needs its own environment (a copy of the environment with a different `environment` value, so different buckets and index storage) or a state-aware plan. The infra module does not provide the second environment. Flagged for the human.

### Consolidation (runtime)

`modules/services` has two independently bounded servers, fast (evidence, graph, explain, ops) and slow (query, jobs), each with its own pool and executor. Retrieval, ranking, synthesis and verification run in-process behind the slow server and have no network seam, so they are not separate ECS services. One image, two task definitions, independent desired counts. No ingress is defined. Approving any public ingress would be a human decision and a new module.

### Idle-cost table (resources that bill while they exist and nothing runs)

Rates are mostly from memory of the public pricing pages and are **not yet verified** (see `COST_ESTIMATE.md`, which marks each rate verified or unverified). Treat the numbers as order of magnitude.

| Resource | Default | Idle rate (approx, us-east-1) | Note |
|---|---|---|---|
| Interface endpoints (8; 9 with Bedrock) in 1 AZ | on | $0.010 to $0.015 an hour each (U6, unverified): 8 endpoints $0.08 to $0.12 an hour, $1.92 to $2.88 a day, about $58 to $86 a month; 9 endpoints $2.16 to $3.24 a day | the largest default idle item. Remove between sessions with `session-down.tfvars` (egress mode `none`) |
| NAT gateway | off | $0.045 an hour plus data | only in `nat` mode |
| Neptune provisioned `db.t4g.medium` (default mode) | off | about $0.06 to $0.10 an hour (COST_ESTIMATE U3, unverified), about $1.4 to $2.4 a day while it exists | needs Paid plan. Free Tier trial eligibility unverified |
| Neptune Serverless (`neptune_mode = "serverless"`) | off | 1 NCU minimum, about $0.14 to $0.25 per NCU-hour (COST_ESTIMATE U2, unverified), about $3.4 to $6.0 a day while it exists | needs Paid plan. Does not scale to zero |
| Neptune storage and backup | off | per GB-month | both modes |
| Secrets Manager (3 secrets) | on | $0.40 each a month | created without a value |
| Cloud Map private DNS namespace | off | none while off; about $0.50 a month if `enable_service_discovery = true` | |
| CloudWatch alarms (2 dead-letter plus pipeline) | on | $0.10 each a month | 3 alarms. A fourth (Neptune at maximum capacity) exists only with Neptune in `serverless` mode, not in the default provisioned mode |
| CloudWatch dashboard | on | free for the first 3 in an account | |
| S3, DynamoDB, ECR, S3 Vectors storage | on | per GB-month, near zero when empty | |
| DynamoDB on-demand | on | none idle | |
| ECS services | on at count 0 | none at 0 tasks | at 1 task: Fargate vCPU and GB hourly |
| EMR cluster | not created | none | billed per run only |
| Step Functions, SQS, ECR scanning | on | none idle | |
| VPC, subnets, route tables, security groups | on | free | |

### Variables added or changed in the D2c round

| Variable | Default | Purpose |
|---|---|---|
| `neptune_mode` | `provisioned` | `provisioned` or `serverless` |
| `neptune_instance_class` | `db.t4g.medium` | provisioned mode only |
| `neptune_max_ncu` | `2` (was 2.5), range 1 to 4 | serverless mode only |
| `enable_service_discovery` | `false` | Cloud Map namespace and service registration |
| `budget_total_usd` | `25`, range above 0 to 25 | used only when `create_budgets` is true |
| `budget_alert_thresholds_usd` | `[10, 15, 20]`, 1 to 5 values, each at most the total | used only when `create_budgets` is true |
| `emr_core_instance_count` | `0`, range 0 to 4 (was 0 to 2) | the elasticity run needs 4 |
| `emr_managed_scaling_max_units` | `3`, range 1 to 7, and at least 1 + core + Spot task nodes (task nodes only with a core node) | its own variable now; the default is not raised. Validated in the environment and in the module |
| `egress_mode` | `endpoints` (unchanged) | `nat` and `none` stay selectable |

The budget variables and `create_budgets = false` already existed; the D2c round added the range checks.

### Variables added in repair round 1

| Variable | Default | Purpose |
|---|---|---|
| `interface_endpoint_services` | 8 names, now including `s3vectors` and `sqs` | finding H2 |
| `pipeline_max_run_seconds` (module `max_run_seconds`) | `10800`, range 1800 to 10800 | finding M1, see "Pipeline bounds" |
| `acknowledge_vector_loss` | `false` | finding M7, guard on a dimension change |
| `deployer_require_mfa` | `false` | finding L3, optional MFA condition in the deployer trust policy |
| `emr_task_spot_instance_count` | `0`, range 0 to 2 (the environment now validates it too) | finding L2 |

### Variable added in repair round 2

| Variable | Default | Purpose |
|---|---|---|
| `pipeline_step_timeout_seconds` (module `step_timeout_seconds`) | `3600`, range 300 to 7200 | finding L-b. Set per tier in the overlays: S 1800, M 2700, L 3600. See "Pipeline bounds" |

### Pipeline bounds (review finding M1)

The state machine has a per-step timeout (`step_timeout_seconds`, module default 3600, range 300 to 7200; the environment variable is `pipeline_step_timeout_seconds` and each size overlay sets it: S 1800, M 2700, L 3600) and an execution timeout (`max_run_seconds`, now default 10800 seconds = 180 minutes, validated 1800 to 10800 and not below one step timeout). The default was 14400. 10800 is the 150-minute tier L script cap in `scripts/run-bounded-pipeline.sh` plus 30 minutes of margin. The bound goes in per run through the experiment tfvars: S 5400 (60-minute cap plus 30), M 7200 (90 plus 30), L 10800 (150 plus 30).

What the bound does not do. When the execution-level timeout fires, the execution ends as `TIMED_OUT` and `CleanupOnFailure` does **not** run. The same is true of `StopExecution`. `CleanupOnFailure` runs only after a caught error in the Hadoop or Spark step (including a step timeout). So if the script that watches the run dies, the state machine can keep going for up to `max_run_seconds`, and if the execution is then timed out, a running EMR cluster is left to two things only: the cluster's own idle auto-termination (900 s, which starts only once no step is running, so a step still running keeps the cluster up until it ends) and the human's cleanup. Nothing here stops the cluster sooner. Whether a timed-out step also stops the EMR step on the cluster is UNVERIFIED.

Per-tier step timeout (review 2, finding L-b). With one uniform step timeout of 3600 s, a long Hadoop step followed by a hung Spark step reached the execution timeout first on tier S (cluster start plus 2 x 3600 is over 5400) and on tier M (over 7200), and that path skips `CleanupOnFailure`. The overlays now set the step timeout to half the script wall-clock cap: S 1800 with an execution timeout of 5400, M 2700 with 7200, L 3600 with 10800. The hung second step is then cut by its own step timeout, which routes to `CleanupOnFailure`, as long as cluster start stays under 1800 s on S and M (3600 s on L). The estimate's launch overhead is 0.25 to 0.4 hours (900 to 1440 s), so this holds on the estimate's assumptions, which are not measurements. The price: a single legitimate step longer than the step timeout is stopped and cleaned up, so if S or M measure a longer step, raise `pipeline_step_timeout_seconds` and `pipeline_max_run_seconds` together. Reachable gap that remains: a hang in `CreateCluster` itself, a hang that outlasts the margin above, and a dead client that never lets a step fail still end in the execution timeout, which skips cleanup; the idle auto-termination (900 s, only after no step is running) and the human's cleanup are then the only stops. Behavior of the state machine under these timeouts is not proven by `validate`; it needs a Phase 14 test.

### Example tfvars (values only, no secrets)

Apply `course.tfvars` first, then overlays; a later `-var-file` wins. Sizes S, M and L are the PROPOSED tiers (5,000, 25,000 and 100,000 works) from `COST_ESTIMATE.md`; decision D2c has not confirmed them.

| File | Use |
|---|---|
| `experiment-size-s.tfvars` | tier S: Neptune provisioned on, one EMR node, no standing service tasks |
| `experiment-size-m.tfvars` | tier M: same, one task per service |
| `experiment-size-l.tfvars` | tier L: same, two tasks per service for the load test. Runs last, behind the human spend gate |
| `elasticity-example.tfvars` | Phase 15 elasticity run on M: master plus 4 core nodes, scaling cap 5. Repeat with `emr_core_instance_count` 1, 2 and 4. Check the On-Demand Standard vCPU quota first (5 x m5.xlarge is 20 vCPUs) |
| `session-down.tfvars` | between sessions: `egress_mode = "none"`, Neptune off, service tasks 0. Keeps all storage. Never combine with an `experiment-size-*` overlay. See "Session up and session down" |

Each size overlay sets `pipeline_max_run_seconds` (S 5400, M 7200, L 10800) and `pipeline_step_timeout_seconds` (S 1800, M 2700, L 3600). Each size overlay also sets `enable_neptune = true`, so a session brought up with an overlay bills Neptune for the whole session and runs the overlay's service tasks (S none, M one per service, L two per service); the estimate counts those hours (repair round 2, finding N1). A pipeline-only overlay with Neptune off is a proposal only (`COST_ESTIMATE.md`, section 3a), not built.

### Session up and session down (review finding M3)

The estimate assumes compute and network are removed after each session while storage stays. `cleanup.sh` cannot do that: it only does a full `force_destroy` teardown that deletes buckets, tables and vectors. The keep-storage flow uses only the existing scripts and approval records and adds no new destructive capability.

Session down (stop the hourly billing, keep storage):

1. At the same commit that was deployed (see the caveat below), run `plan.sh` with the overlay: `bash scripts/plan.sh --environment course --account-id <id> --cost-ceiling-usd <n> --extra-var-file <abs>/infra/terraform/environments/course/session-down.tfvars`. Do not add a size overlay.
2. Read the plan. It should destroy the interface endpoints and, if Neptune is up, the Neptune cluster, instance, subnet group, parameter groups and bulk-load role, and change the ECS service desired counts. It must not destroy a bucket, table, vector bucket, index, repository or secret. The Neptune destroys appear under `STATEFUL_DESTROY_OR_REPLACE`.
3. Run `deploy.sh --print-digest`, write the approval record with `STATEFUL_CHANGE_ACKNOWLEDGED=yes` (a Neptune destroy counts as stateful), and run `deploy.sh` with the saved plan. `deploy.sh` also takes the two build-context directories and digests them; because the image tag for HEAD already exists in ECR, nothing is rebuilt, but the directories must still exist.
4. Neptune keeps no snapshot in the course environment (`skip_final_snapshot` is true unless `persistent_environment`), so its data is gone. The next session reloads the graph from the data bucket's `graph/` prefix, which the estimate counts in the Neptune session hours.

Session up: run `plan.sh` and `deploy.sh` with `course.tfvars` plus one `experiment-size-*.tfvars` overlay (the default `egress_mode` is `endpoints`), at the commit you intend to run.

Caveats. `deploy.sh` requires the plan's image tag to equal HEAD, so a session-down plan made after HEAD moved would tag the task definitions with a tag that has no image yet and make `deploy.sh` build and push one. Plan session down at the commit that is deployed. Both flows cost one approval record per apply, and a record is single use. `cleanup.sh` stays the end-of-project full teardown. UNVERIFIED until a Phase 14 plan: that this overlay produces exactly the changes in step 2.

### Smoke-test network path (review finding M9, a Phase 14 decision)

There is currently no way for `scripts/smoke-test.sh` to reach the services. The services have no ingress. No host is a member of the client security group. `enable_execute_command` is false and there is no `ssm` or `ssmmessages` endpoint, so ECS Exec cannot be used. Cloud Map is off by default, so no `*.internal` name resolves, and the service URLs are the tasks' private IPs. Options for Phase 14, none implemented:

| Option | Change | Cost and risk |
|---|---|---|
| A. A short-lived client task in the VPC | one more Fargate task definition in the client security group that runs the smoke test; needs the task to reach Secrets Manager (the `secretsmanager` endpoint exists) | a few cents; a new task definition and role (an IAM change for the human to approve) |
| B. ECS Exec | set `enable_execute_command`, add `ssm`, `ssmmessages` and `ec2messages` endpoints and the task-role SSM permissions | three more endpoint-hours, an IAM change, a wider exec path into running tasks |
| C. A tunnel the human opens (an EC2 or SSM bastion) | a bastion host in the client security group | an instance bill while it exists, and a human-managed entry point |
| D. Enable Cloud Map | `enable_service_discovery = true` | about $0.50 a month; fixes name resolution but not reach |

The orchestrator and human choose in Phase 14. Until then the smoke test cannot run from anywhere, and the example URLs in the script use private IPs rather than a name that would not resolve.

### Budget mismatch

Instructor alerts are $10, $15 and $20 of a $25 cumulative ceiling. The existing AWS budgets are $25 and $50. `create_budgets` is false so nothing duplicates or contradicts them. When enabled, the module creates one budget named `<prefix>-total` with those thresholds on actual spend (credits excluded, so the figure is gross usage), with period `ANNUALLY` as the nearest fit to a cumulative project total. Enabling it, and the notification address, are Phase 14 human actions. AWS does not enforce a budget: it only alerts.

## IAM (roles and their limits)

No IAM user, access key or login profile is created. The roles:

| Role | Assumed by | Allowed actions | Resources |
|---|---|---|---|
| `emr-service` | EMR service | AWS-managed `AmazonEMRServicePolicy_v2` (cannot be narrowed here) | acts only on resources tagged `for-use-with-amazon-emr-managed-policies` (set on VPC, app subnets, EMR security groups) |
| `emr-ec2` | EMR nodes | S3 list, get, put, delete, abort-multipart | data and artifacts buckets by prefix (raw, curated, graph in; curated, graph, semantic-context, reports, tmp, emr-logs out) |
| `ecs-execution` | ECS agent | ECR pull; log stream writes; read three secrets | ECR repos `<prefix>-*`; log groups `/<prefix>/*`; the three secret ARNs. `ecr:GetAuthorizationToken` needs `*` |
| `runtime` | fast and slow tasks | S3 read; DynamoDB get and query; PutItem on query-session and evidence-bundle; S3 Vectors get, query; SQS send; Neptune read-only; Bedrock invoke (flag) | prefixes, table and index ARNs, index ARNs, run-requests queue, Neptune cluster resource ID, exact model ARNs |
| `loader` | load task | S3 read and write to logs and reports; DynamoDB put, get, query on ledger, registry, metric cache; S3 Vectors put, get; SQS consume; Neptune loader actions; Titan invoke (flag) | as above. No `CreateIndex`: Terraform owns the indexes |
| `sfn` | Step Functions | EMR create, manage, terminate; ECS run and stop task; PassRole; managed-rule events (3 rules: EMR RunJobFlow, EMR AddJobFlowSteps, ECS `StepFunctionsGetEventsForECSTaskRule`); log delivery | `RunJobFlow` and the log delivery APIs need `*` (no resource-level support); everything else is ARN-scoped, and PassRole is limited by `iam:PassedToService`. EMR actions are on `cluster/*` because the cluster ID does not exist until RunJobFlow runs (finding L3): the actions are describe, terminate, add-steps, cancel-steps, list-steps and add-tags on any EMR cluster in this dedicated account and region |
| `deployer` (optional) | the human principals in `deployer_trusted_principal_arns`, none by default; MFA required only if `deployer_require_mfa` is true (default false: the IAM users are programmatic-only, D4) | ECR push; ECS register task definition and update services; start and inspect the pipeline; publish jars | repos, services, state machine and execution ARNs, jars and bootstrap prefixes. Cannot run Terraform apply and has no data read or write. Does NOT separate deployment from apply: see "Who runs what" |
| Neptune bulk load (in the neptune module) | RDS service | S3 read | `graph/*` of the data bucket |

Separation: the runtime role cannot write ledger, registry or cache tables or S3 Vectors and cannot start loads. The deployer holds no data permissions. Neptune and Bedrock statements exist only when their flags are on. No `bedrock:*`, no Bedrock `*` resource. The Bedrock ARN variables now reject a `*`, a value that is not an `arn:aws:bedrock:` ARN, and an empty list while Bedrock is on (finding L4). Between roles, runtime and deployment are separate in what each role may call, not in who runs `terraform apply` (next section).

### Who runs what (review finding M8)

The deployer role is not the identity that deploys. `scripts/deploy.sh` runs `terraform apply` of a saved plan, and that apply creates and changes IAM roles, VPC resources, Neptune, S3, DynamoDB and more, none of which the deployer role may do. So:

| Script | Runs under | Needs |
|---|---|---|
| `plan.sh` | the human's broad credentials (a profile that can read everything Terraform manages and `sts:GetCallerIdentity`) | read access to all managed resource types, plus the state backend. No write |
| `deploy.sh` | the human's broad credentials | everything `terraform apply` needs for the plan (create, change, delete across the resource types in the plan), plus ECR login, push and describe, and ECS list and describe calls for the revision check. The deployer role would cover only the ECR push and `DescribeImages` and `DescribeServices` parts; it lacks `ecs:ListTaskDefinitionFamilies` and `ecs:ListServices`, which the revision check calls |
| `rollback.sh` | the human's broad credentials | same as `deploy.sh` for image mode; S3 read of the state bucket versions and `terraform state push` for state mode |
| `cleanup.sh` | the human's broad credentials | `terraform destroy` rights over everything in state, S3 list calls, and the S3 Vectors read call (UNVERIFIED) |
| `run-bounded-pipeline.sh` | the human's credentials | `states:ListStateMachines`, `StartExecution`, `DescribeExecution`, `StopExecution`, `ListExecutions`, EMR `ListClusters` and `TerminateJobFlows`, SQS `GetQueueUrl` and `GetQueueAttributes`. The deployer role has `StartExecution`, `ListExecutions`, `DescribeExecution` and `StopExecution` on the state machine and its executions but lacks `ListStateMachines`, the EMR calls and the SQS calls, so it cannot run this script until a work order extends it |
| `smoke-test.sh` | the human's credentials | Secrets Manager `GetSecretValue` on the query token, Bedrock invoke when `--bedrock on`, ECR and ECS read calls for the revision check. The deployer role has none of these |

The deployer role is therefore an optional narrowing for the image push, service roll, pipeline start and jar publish calls. It is not a separation of deployment from apply, and no script runs under it today. The earlier claim that it separated them was wrong.

The runtime `PutItem` on evidence-bundle and query-session is an assumption (answers store their evidence and sessions store turns). The service owner should confirm it.

## Verification status (be precise about what proves what)

| Gate item | Check | Status |
|---|---|---|
| Formatting | `terraform fmt -check -recursive -diff` | exit 0, no diff (human, 2026-11-09, Run 3, after repair round 1). Proves only that the files are formatted as `terraform fmt` would write them. **Not re-run** on the repair round 2 edits (work order 15); alignment of the new lines was done by hand |
| Syntax, types, references | `terraform validate` in `environments/course` | exit 0, "Success! The configuration is valid." (human, 2026-11-09, Run 3, after repair round 1; provider 6.26.0). Proves HCL syntax, types, and that every argument and attribute reference exists in the provider schema, and that variable validations and dynamic blocks parse. It does not prove IAM scope, cost, exposure, or that the defaults produce the intended resources. **Not re-run** on the repair round 2 edits (one new variable and its wiring, description text, overlay lines) |
| Script syntax | `bash -n` on the seven scripts | exit 0 (human, 2026-11-09, Run 3, repair round 1 files; first time these ran). Proves syntax only. **Not re-run** on the repair round 2 edits to `scripts/lib.sh` and `scripts/smoke-test.sh`. `shellcheck`: not installed, so not run |
| IAM scoped to actual resources and actions | read-only review of `modules/identity/main.tf` against this table | independent review rounds 1 and 2 done; round 2 verified H1, H2, M8 and the identity scope as fixed. `validate` does not prove IAM correctness |
| Not public, bounded capacity | read-only review; variable validations | rounds 1 and 2: no public exposure found. Range checks exist for the pipeline timeouts and the scaling cap |
| Cost | `COST_ESTIMATE.md` | restated for endpoint egress, provisioned Neptune and absolute thresholds in repair round 1; Neptune session hours and service-task hours restated in repair round 2 (finding N1); mostly unverified rates; S + M at the HIGH bound does not fit under $25 |
| No unexplained stateful replacement, plan counts, plan-signed gate | `terraform plan` | deferred to Phase 14 (D3) |

## Checks to run

```
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/environments/course init -upgrade -backend=false
terraform -chdir=infra/terraform/environments/course validate
```

`init -upgrade` is not needed: no provider or version changed (the lock file already records 6.26.0; `terraform_data` is built in and needs no provider). Run `fmt -check` and `validate` again after repair round 2. The last results, on the repair round 1 files (2026-11-09, `terraform-checks.md` Run 3), were `fmt -check -recursive -diff` exit 0 with no diff and `validate` exit 0 ("Success! The configuration is valid."). If `validate` fails now, the new variable `pipeline_step_timeout_seconds` and its wiring in `environments/course/main.tf` are the first places to look. `validate` proves syntax, types and references only; it does not prove IAM correctness, cost or exposure.

## Remote state design (documented only, not created in Phase 12)

The local backend stays until Phase 14. The design to adopt then: one S3 bucket named `<prefix>-<account>-tfstate`, versioning on, SSE-S3, public access blocked, TLS-only policy, a 90-day noncurrent expiry, created by a separate one-off configuration (a bootstrap cannot store its own state in itself) and kept after the course teardown. Locking uses the S3 native lock file (`use_lockfile = true`, Terraform 1.10 or later), which avoids a DynamoDB lock table. The backend block goes in a new `backend.tf` in the environment. A state file holds resource attributes, so the bucket is private, and `*.tfstate*` stays out of git (the sandbox already denies reads of it).

## OpenSearch

Not deployed. There is no implemented and evaluated hot or hybrid tier, so by the work order there is nothing to define. No stub resource exists. If a tier lands, it needs its own module, VPC-only domain, encryption, logging and capacity cap.

## Glue

Not used, so no Glue catalog resources. Data stays under S3 prefixes read by the application.

## Observability coverage

Created: pipeline failure alarm, dead-letter alarms, one dashboard (pipeline executions, dead-letter depth, ECS CPU, and when Neptune is on either serverless capacity or provisioned CPU), and a Neptune at-max-capacity alarm that exists only in serverless mode. Not created: EMR, DynamoDB, S3 Vectors client, API latency and LLM usage metrics. The course text lists them. They need application-emitted metrics or paid custom metrics, so this is a partial cover and is deferred.

## Cleanup mode

`cleanup_mode = true` (with `cleanup.tfvars`) turns on `force_destroy` for buckets, the vector bucket and ECR repositories, turns off deletion protection and the Neptune final snapshot, and sets the secret recovery window to zero. It is a variable, not a default, and only a human-approved `cleanup.sh` should apply it and then destroy. It is the end-of-project full teardown and deletes storage; use the session-down flow above to stop billing between sessions while keeping storage. `force_destroy` follows `cleanup_mode` alone, for the data and artifacts buckets, the vector bucket and the ECR repositories. (The Neptune final snapshot is separate: it is skipped whenever the environment is not persistent, not only in cleanup mode.) `lifecycle.prevent_destroy` cannot take a variable, so stateful safeguards are `force_destroy = false` (a non-empty bucket cannot be deleted), versioning, DynamoDB and Neptune deletion protection, and the persistent variable.

## Paid-plan items

Neptune and EMR need the Paid plan (D4). Terraform creates the free parts (roles, security groups, state machine, task definitions) on the Free plan, but a pipeline run (EMR) and the Neptune cluster do not run without the upgrade. Nothing here suggests or scripts "activate advanced features". The upgrade is a Phase 14 human decision.

## Unverified items to settle before the first plan

- The S3 Vectors resource arguments against the 6.26.0 schema (read from `main`; `validate` settles it).
- Neptune engine version `1.4.5.1` and family `neptune1.4` (must support Serverless), and `db.t4g.medium` orderable for it.
- EMR release label `emr-7.10.0` and its Hadoop and Spark versions against the built jars.
- Bedrock Haiku 4.5 profile ID, model ID and routing regions (D2a: account model access is also unverified).
- EMR service-role v2 tag requirement. Step Functions managed rules: the policy grants three (EMR `StepFunctionsGetEventForEMRRunJobFlowRule`, EMR `StepFunctionsGetEventForEMRAddJobFlowStepsRule`, ECS `StepFunctionsGetEventsForECSTaskRule`). Only the ECS name, with "Events" in the plural, is VERIFIED (https://docs.aws.amazon.com/step-functions/latest/dg/connect-ecs.html); the two EMR names are carried over and UNVERIFIED. The state machine uses four `.sync` integrations (createCluster, addStep, terminateCluster, runTask). Whether `terminateCluster.sync` uses an EMR managed rule is UNVERIFIED and no rule name is invented. The documented terminateCluster policy has `TerminateJobFlows` and `DescribeCluster` only, and the documentation says missing events permissions on a `.sync` task cause a delay before Step Functions notices completion, not a denial (https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html and https://docs.aws.amazon.com/step-functions/latest/dg/service-integration-iam-templates.html). The documentation also shows a polling fallback for this case: UNVERIFIED here beyond that statement, and this repo does not rely on it.
- Neptune bulk-loader egress (finding L1). The Neptune security group has no egress rule (Terraform removes the default allow-all), and the bulk loader reads from S3 on behalf of the cluster. A rule to the S3 prefix list is NOT added: the documented requirement for the loader (a gateway endpoint for S3 and the IAM role, versus a security-group egress rule) was not read this round, so adding it would be a guess. UNVERIFIED, flagged for the Phase 14 plan and first load.
- S3 Vectors and SQS interface endpoints: see the Egress section for what is verified.
- `terraform_data` `ignore_changes` and precondition behavior for the dimension guard: see the S3 Vectors section.
- Container environment variable names, ports 8080 and 8081 and the jar key `jars/scholarchain-hadoop.jar` are proposals. No production entrypoint, AWS adapter or packaging step exists yet.
- The hash embedder is 384 dimensions, so a fallback run needs `vector_dimension = 384` in its own deployment (its own environment: changing the dimension on the existing one destroys the vectors, see above). Titan V2 offers only 256, 512 and 1024.
