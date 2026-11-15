# ScholarChain Terraform (Phase 12, local authoring only)

Status: on 2026-11-09 the human ran `terraform fmt -check -recursive -diff` (exit 0, no diff) and `terraform validate` in `environments/course` ("Success! The configuration is valid.", exit 0) on the files as they stood after repair round 1 (`terraform-checks.md`, Run 3; provider 6.26.0 from Run 2's `init -upgrade`). Repair round 2 (work order 15) edited files again without running any Terraform. Phase 13 work order 01 then replaced the step timeout variable with three (see "Variables replacing the single step timeout"), and work order 05 removed the Rule B `check` block (human decision O1). On 2026-11-11 the human ran `fmt -check -recursive -diff`, `init -backend=false` and `validate` after those edits (Phase 13 `checks-1.md`): all three passed (see "Checks to run"). **`terraform plan` has still not been run** (Phase 14). Nothing here has touched AWS.

## Layout

```
infra/terraform/
  modules/
    foundation     budgets (optional), cost-allocation tags (optional)
    network        VPC, 2 AZs, subnets, endpoints, security groups by role
    identity       roles, policies, the EMR service-linked role, Secrets Manager entries (no values)
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

## Account facts and cost limits (Phase 14 decision D1, replaces the Phase 12 account notes)

| Fact | Value |
|---|---|
| Account | Project account "ScholarChain", a new AWS sign-up (identity kept out of the repository). The earlier account is not used |
| Plan and credits | Paid plan with USD 20 of credits |
| AWS-enforced project spend limit | USD 20, with "pause idle resources" on |
| AWS Budget | USD 25 with alerts at 10, 15 and 20. Created outside Terraform. It only alerts; it is the outer alert |
| Code ceiling | USD 20 (decision D6): `SC_PROJECT_CEILING_USD` in `scripts/lib.sh` and the `budget_total_usd` validation. It matches the AWS spend limit |
| Operating stop | The human stops near USD 15 of actual spend |
| Region | LOCKED to us-east-2 (Ohio). Another Region needs "Activate advanced features", which is irreversible and removes the spend limit. Never use it |

The earlier text that said "$25 and $50 budgets already exist" is withdrawn: it described the previous account. `create_budgets` stays false because a USD 25 AWS Budget already exists; if it were set to true, Terraform would add a second budget capped at 20.

## Defaults, and why (all chosen for the $20 code ceiling, decisions D2 and D6)

| Choice | Default | Reason |
|---|---|---|
| Region, account | us-east-2 (Ohio), one account. LOCKED: `var.region` refuses any other value at plan time, with a message naming Phase 14 D1 (`docs/agent-runs/phase-14/decisions.md`). `var.availability_zones` must sit inside it | Phase 14 D1 (replaces D4's us-east-1) |
| Public exposure | none: no public subnet, IGW, load balancer or public IP | brief |
| Egress | `endpoints` mode (the human's choice, D2c): gateway endpoints for S3 and DynamoDB, 8 interface endpoints in one AZ (9 with `bedrock-runtime` when Bedrock is on), no NAT | NAT is about $0.045 an hour plus data. See egress below and `COST_ESTIMATE.md`, which prices both |
| Buckets | 2 (data, artifacts) instead of 7 | prefixes follow `S3Prefix` in modules/storage; lifecycle works per prefix; versioning is the only bucket-wide setting and only artifacts-vs-data needed separating |
| Encryption | AWS-owned or AWS-managed keys everywhere, no customer KMS key | a KMS key is about $1 a month each; the course does not require one; no `kms:*` permission is needed. The data and artifacts buckets state `blocked_encryption_types = ["SSE-C"]` (AES256, bucket key on), which is what AWS applies by default; leaving it unset made the provider plan `[]` and remove the block (Phase 14 D14, plan 4) |
| DynamoDB | on-demand, PITR off, deletion protection off | no idle cost; PITR bills by size and is on in a persistent environment |
| EMR | transient EMR on EC2, created and terminated by the state machine; one `m5.xlarge` node, 0 core, 0 Spot task nodes, 900 s idle auto-termination, managed scaling capped at 3 units through `emr_managed_scaling_max_units` (applies only with core nodes). The cap must cover every fixed node: 1 primary + core + Spot task nodes (task nodes count only with a core node), so the largest legal shape is 1 + 4 + 2 = 7 units, and the variable validates the sum (the earlier cap of 6 ignored task nodes). The elasticity run uses 4 core nodes and a cap of 5 | the Hadoop stage is MapReduce, which EMR Serverless cannot run (Spark and Hive only), so EMR on EC2 is forced; a transient cluster costs nothing between runs. Serverless is not carried because it would still need EC2 for Hadoop |
| Spot | off; allowed only for task nodes and only with a core node | HDFS data lives on core nodes; a lost task node costs a retried task, a lost single node would fail the run |
| Neptune | not created (`enable_neptune = false`). If enabled, `neptune_mode = "provisioned"`: one `db.t4g.medium` writer, no replica. `serverless` is selectable: 1 NCU minimum, 2 NCU maximum (was 2.5) | D2c. Serverless has no scale to zero and is the likeliest budget threat; both modes need the Paid plan and are destroyed after each run. Deletion protection and the final snapshot follow `persistent_environment` in both modes. `db.t4g.medium` for engine 1.4.5.1 is confirmed orderable in us-east-2 by a human read-only CLI check on 2026-11-13 (`docs/agent-runs/phase-14/checks-3.md`) |
| S3 Vectors | 1 bucket, 5 indexes, 256 dimensions, cosine | names from `VectorIndexes`. Dimension is open (D2a); 256 is cheapest. **Changing the dimension destroys the vectors** (next section) |
| Runtime | 1 ECR image for 2 task definitions (fast, slow) plus a loader image; 256 CPU, 512 MiB; desired count 0; Fargate on-demand | see "Consolidation" |
| Bedrock | off in `course.tfvars`; when on, `bedrock:InvokeModel` on exact ARNs only and the `bedrock-runtime` interface endpoint. `experiment-size-s.tfvars` sets it true (work order 31: the loader embeds with Titan V2) | D2a, D17 |
| Logs | 3 days retention | cost |
| Cloud Map | not created (`enable_service_discovery = false`) | no ingress and nothing resolves the names; saves about $0.50 a month. With it off the ECS services carry no registry and are reached by private IP |
| Budgets | not created (`create_budgets = false`); limit and thresholds are variables, defaults 20 and [10, 15, 20] USD (the limit is validated to at most 20, decision D6) | see "Budgets" |

### Egress

Default (`endpoints`): nothing in the VPC can reach the internet. S3 and DynamoDB go through gateway endpoints (free). ECR (`ecr.api`, `ecr.dkr`), CloudWatch Logs, Secrets Manager, STS, EMR, **S3 Vectors (`s3vectors`) and SQS (`sqs`)** go through 8 interface endpoints, which bill hourly per endpoint per AZ. Bedrock adds a `bedrock-runtime` endpoint (a ninth) when enabled.

Why `s3vectors` and `sqs` are in the list (review finding H2): the runtime role may query S3 Vectors and send SQS messages, and the loader role may put vectors (it no longer holds any SQS permission, work order 37), and both run in the VPC; the `sqs` endpoint stays for the runtime role's send. Their security groups allow egress only to the endpoint security group and the S3 and DynamoDB prefix lists, so without these two endpoints the calls cannot leave the VPC. The security-group rules are unchanged: the existing `to_endpoints` egress rule (services, loader, client, EMR nodes to the endpoint group on 443) and the endpoint group's ingress from each role already cover the new endpoints, and the services and loader get no other egress in this mode. The Step Functions service calls its targets itself and the VPC makes no Step Functions API call, so no `states` endpoint is needed.

Facts and their status. S3 Vectors supports an interface endpoint, service `com.amazonaws.<region>.s3vectors`, private DNS name `s3vectors.<region>.api.aws` (read for the Region-neutral pattern, https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-privatelink.html; the service itself is confirmed in us-east-2, endpoint `s3vectors.us-east-2.api.aws`, https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html, but the PrivateLink service name in us-east-2 is UNVERIFIED until the first plan). The SQS interface endpoint `com.amazonaws.<region>.sqs` is the standard one: not read from the docs, so UNVERIFIED. Terraform builds every service name from `var.region` (us-east-2). Whether both are offered in the AZ of the single endpoint subnet (`interface_endpoint_az_count = 1`): UNVERIFIED until a Phase 14 plan. The S3 Vectors private DNS name also has to be the name the SDK resolves, which depends on the application's endpoint configuration: UNVERIFIED.

`nat` mode builds one NAT gateway in one public subnet (public subnets exist only for it). `none` keeps only the gateway endpoints and will not run Fargate tasks (no ECR or Logs path); it is the egress setting of the session-down overlay.

### S3 Vectors: changing the dimension destroys the vectors (review finding M7)

The five index names (`*-v1`) do not include the dimension. Changing `vector_dimension` (for example 256 to 384 for the hash-embedder fallback, anticipated below) makes the provider replace all five indexes in place, and a replaced index loses its vectors. There is no second environment to absorb it. What exists:

- Guard added: `modules/s3vectors` keeps a `terraform_data.dimension_record` holding the dimension the indexes were first created with (`ignore_changes` on its input), and each index has a `precondition` that fails at plan time when the variable differs from the record, unless `acknowledge_vector_loss = true`. This does not rely on a variable in `prevent_destroy`, which Terraform does not allow. Chosen over renaming the indexes, which belongs to the storage and rag code. How `ignore_changes` on `terraform_data` and a precondition on a value unknown at plan time behave is UNVERIFIED until a Phase 14 plan.
- **Known gap in that guard (review 2, finding L-a).** Flipping `acknowledge_vector_loss` from true back to false is harmless only when the dimension is unchanged. If the flip lands in the same apply as another dimension change, the record is replaced, its output is unknown at plan time, the precondition waits until apply, the record is recreated with the new dimension first, and the indexes are replaced without a plan-time refusal. A precondition cannot refuse this reliably, because it sees only current variable values and not the previous acknowledge value. The guard is a first layer, not a proof. Operating rule: flip it back in an apply of its own with the dimension unchanged. This reading comes from the code and is UNVERIFIED until a Phase 14 plan.
- Second layer, already in the scripts, which still catches the gap: `plan.sh` lists an `aws_s3vectors_index` replacement under `STATEFUL_DESTROY_OR_REPLACE`, and `deploy.sh` then requires `STATEFUL_CHANGE_ACKNOWLEDGED=yes` in the approval record. It depends on a human reading the plan summary.
- A 384-dimension fallback run needs its own environment (a copy of the environment with a different `environment` value, so different buckets and index storage) or a state-aware plan. The infra module does not provide the second environment. Flagged for the human.

### Consolidation (runtime)

`modules/services` has two independently bounded servers, fast (evidence, graph, explain, ops) and slow (query, jobs), each with its own pool and executor. Retrieval, ranking, synthesis and verification run in-process behind the slow server and have no network seam, so they are not separate ECS services. One image, two task definitions, independent desired counts. No ingress is defined. Approving any public ingress would be a human decision and a new module.

### EMR design B (work order 20, Phase 14 decision D21; authored, not planned, not validated)

Nothing here has been run: no `fmt`, `init`, `validate` or `plan` has seen these edits (the author could not run Terraform), and no cluster has launched.

- Release `emr-7.10.0`, Applications `Hadoop` only. EMR's Spark 3.5.5 (Scala 2.12) is not installed, so `/usr/bin/spark-submit`, `/etc/spark/conf` and `SPARK_HOME` do not exist. Spark 4.0.5 comes from the Apache tarball, staged by `scripts/emr/` from `jars/spark-4.0.5/` in the artifacts bucket, and runs with `spark-submit --master yarn` against the cluster's YARN. m5.xlarge, the security configuration and the 900 s idle timeout are unchanged. No IAM, security-group or endpoint change: IAM diff expected none (the `emr_ec2` role already reads `jars/*`, `bootstrap/*` and `raw/*` and writes `curated/*`, `graph/*`, `semantic-context/*` and `reports/*`).
- Optional bootstrap action (`emr_install_java_bootstrap`, default false; `emr_bootstrap_java_major` 17 or 21): `scripts/emr/install-java-bootstrap.sh` installs Corretto and writes `/etc/scholarchain/java.env`. Whether emr-7.10.0 defaults to JDK 17 is UNVERIFIED; the capability check decides. With no NAT the package install depends on the S3-backed Amazon Linux repositories being reachable through the S3 gateway endpoint (UNVERIFIED), and a failing bootstrap fails the launch. Whether the job jars need Java 21 bytecode is unknown (build-engineer); if so use major 21.
- Workflow module: `hadoop_jar_key` and the S3-jar Hadoop step are gone; `data_bucket` is a new required variable (wired in `environments/course/main.tf`). The input contract, state machine and key layout are in `scripts/README.md`, "EMR design B". Rule A over-covered at first (a run has one long step plus a fixed 300 s fetch step, against the 2 x that Rule A counted); work order 49 corrected it to one step plus 600 (see "Pipeline bounds"). The S overlay's 1800 s step bound proved too short for the one script step in S run 3 (staging the Spark tarball, Hadoop, entity resolution, Spark, export and publish together) and was raised to 3600 s by human decision (work order 49). S run 4 then ran into the on-node Spark limit of 1500 s in `scripts/emr/lib-emr.sh` (about 2,100 to 2,400 s needed), so the S step cap went to 4800 s and the on-node Spark and entity-resolution limits to 3000 s (human decision D32, work order 52).
- Single-node scope: Spark, ER and the export use local java.nio paths, so everything lives on the primary node's disk. Report results as "data-size scaling on a single-node EMR cluster (Spark and MapReduce on YARN)". Experiment B (node elasticity) is not demonstrated; the multi-node options (EFS, Spark-only `s3a://` I/O, an HDFS or S3A rewrite) are a human decision after the S run.

Unverified items of this design (each is checked by the capability check or the first S run, or needs a read of the cluster): the release label and Hadoop-only application list accepted by `RunJobFlow`; the default JDK; AWS CLI on the node; `command-runner.jar bash <script>`; Choice rules with `IsPresent` inside `And` (Step Functions evaluates them in order, UNVERIFIED) and `States.Array` with JSON paths in `Args.$`; `mapreduce.framework.name=yarn` as the cluster default; HDFS on the single node; `spark.yarn.archive` from HDFS; Spark 4 client against EMR's Hadoop configuration (the LZO codec override is a precaution); `spark.yarn.populateHadoopClasspath` false versus true; the container user for `file:` output; `-libjars` reaching tasks; Hadoop 3.5.0 client classes against 3.4.1; the scripts' own syntax (no `bash -n` or `shellcheck` was run).

### EMR service-linked role (work order 39; authored, not planned, not validated)

Capability-check launch 1 (2026-11-14) failed at CreateCluster: the cluster went `TERMINATED_WITH_ERRORS`, `VALIDATION_ERROR`, "Service-linked role 'AWSServiceRoleForEMRCleanup' for EMR is required". The new account had never run EMR. `aws_iam_service_linked_role.emr` (`aws_service_name = "elasticmapreduce.amazonaws.com"`) in `modules/identity` creates it. Identity, not `modules/emr`, because it is account-level IAM and the identity module is where the privilege diff is reviewed in one place; it takes no inputs and sits outside every flag the session-down overlay flips. Expected plan from the deployed state: 1 to add, 0 to change, 0 to destroy for this role, plus the image-tag effect (the tag moves to the new HEAD, so the loader and the two services task definitions are replaced with new revisions again; expected). Apply uses the human's AdministratorAccess credentials, which can create it; the `deployer` role does not need `iam:CreateServiceLinkedRole` because it never runs this apply and no IAM change was made.

- If the role already exists in an account, the apply fails with an `InvalidInput` error that the role name has been taken. Recover with `terraform import 'module.identity.aws_iam_service_linked_role.emr' arn:aws:iam::<account>:role/aws-service-role/elasticmapreduce.amazonaws.com/AWSServiceRoleForEMRCleanup` and re-plan. In this account it does not exist.
- Teardown: it is in state, so `cleanup.sh apply-destroy` deletes it (`DeleteServiceLinkedRole`), and the state-empty check needs that. The account is dedicated to the project and the role is free and recreated by this configuration, so deleting it is acceptable. The delete fails if EMR still uses the role (a live cluster); none exists at teardown. If the account were shared with other EMR users, remove it from state first (`terraform state rm`) instead.
- Other first-use service-linked roles for the on-demand, no-Spot, managed-scaling path on emr-7.10.0: none found, none added. `AWSServiceRoleForEC2Spot` matters only for Spot (`emr_task_spot_instance_count` above 0 adds a SPOT task group), and EMR managed scaling is run by the EMR service with the existing service role. Both statements come from memory of the EMR and EC2 documentation, not read this round (the documentation tool was unavailable): UNVERIFIED. Enabling Spot later should be preceded by a check for `AWSServiceRoleForEC2Spot`.
- UNVERIFIED until a plan: whether the provider sends the `default_tags` on this role without error. Administrator credentials allow `iam:TagRole`.

### EMR service role: request tag and PassRole (work order 40; authored, not planned, not validated)

Capability-check launch 2 (2026-11-14, execution `run-20261114T213414Z-small`): the service-linked role was found, the cluster lived about 46 s, then `TERMINATED_WITH_ERRORS`, `VALIDATION_ERROR`, "Service role ... scholarchain-course-emr-service has insufficient EC2 permissions". The request had empty `Tags`. Two causes, from the orchestrator's reading of the `AmazonEMRServicePolicy_v2` page (v6), not re-read by the author:

- `CreateEMRTaggedInstancesAndVolumes` and `CreateNetworkInterfaceNeededForPrivateSubnet` need `aws:RequestTag/for-use-with-amazon-emr-managed-policies = "true"`. The `CreateCluster` state in `modules/workflow` now sends `Tags = [{Key = "for-use-with-amazon-emr-managed-policies", Value = "true"}]` (`merge(var.cluster_definition, { Tags = ... })`; the emr module's definition has no `Tags`, so nothing is overridden).
- The managed policy's `PassRoleForEC2` names only `EMR_EC2_DefaultRole`. `modules/identity` adds the inline policy `pass-node-role` on `emr-service`: `iam:PassRole` on `aws_iam_role.emr_ec2.arn` only, with `iam:PassedToService` `StringLike` `ec2.amazonaws.com*`.
- Network tags: the source at HEAD already tags both app subnets and the three EMR security groups (and the VPC), via `local.emr_tag` in `modules/network`. No network edit was made. The VPC tag is not needed per the docs (only when EMR creates default security groups) and was left in place; removing it is a separate decision.

Expected plan from the deployed state: 1 to add (`module.identity.aws_iam_role_policy.emr_service_pass_node`), the state machine updated in place, the network tags unchanged if they were applied before launch 1 (check the plan), no destroy.

### Loader (work order 31; authored, not planned, not validated, not run)

Nothing here has been run: no `fmt`, `init`, `validate`, `plan` or harness saw these edits, and the author could not run Terraform, `bash -n` or `shellcheck` (guard D11). The loader is `scholarchain.rag.AwsIngestMain` (work order 30), started as the ECS `loader` task by the workflow's `RunLoader` state with `Command = loader_command`. That class was not in the tree when this was written, so every name below comes from the work order and is UNVERIFIED against code.

Environment of the loader task (the only container that reads these; credentials come from the task role through the default provider chain, never from the environment):

| Name | Value | Source |
|---|---|---|
| `AWS_REGION` | `var.region` (us-east-2) | root `main.tf` |
| `SCHOLARCHAIN_DATA_BUCKET` | data bucket name | `module.data_lake.data_bucket` |
| `SCHOLARCHAIN_VECTOR_BUCKET` | vector bucket name | `module.s3vectors.bucket_name` |
| `SCHOLARCHAIN_TABLE_PREFIX` | `<project>-<environment>`, so tables are `<prefix>-run-ledger` and so on | `local.name_prefix`; whether the code appends the dash is UNVERIFIED |
| `SCHOLARCHAIN_NEPTUNE_HOST` | Neptune writer endpoint host, only when `enable_neptune` | `module.neptune[0].endpoint`; absent otherwise (absent versus empty is UNVERIFIED against the loader). The port is not passed: the loader defaults to 8182, the cluster's default |
| `SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN` | the bulk-load role, only when `enable_neptune` | `module.neptune[0].bulk_load_role_arn` (an existing output) |

The older proposed names the loader no longer receives (`SCHOLARCHAIN_VECTOR_DIM`, `SCHOLARCHAIN_BEDROCK`, and `SCHOLARCHAIN_NEPTUNE_HOST` with an empty value when Neptune is off) stay only on the fast and slow services, in `container_environment`, unchanged. Nothing on the loader reads the dimension or the Bedrock switch (the Titan adapter fixes 256 dimensions in code, and the model ARN is in IAM). Whether the current services code reads any of the proposals could not be searched (no shell), so the services map is not changed.

S3 layout of the loader, all in the data bucket:

| Prefix | Who | Use |
|---|---|---|
| `semantic-context/runs/<run_id>/export/` | EMR writes, loader reads | the export (JSONL tables) |
| `graph/neptune/...` | loader writes, loader and Neptune read | openCypher load CSV files, one load job per file |
| `graph/neptune-manifest/...` | loader writes and reads | the load manifest (sha256 per file). Not readable by the Neptune bulk-load role |
| `reports/runs/<run_id>/loader/` | loader writes | loader report |
| `logs/` | loader writes | rejects and run logs (the `expire-run-logs` lifecycle rule expires it after `ops_log_expiry_days`) |

S3 permission detail that matters. The adapter's `head` returns `None` only for a 404. S3 returns 404 for a missing key only to a caller that holds `s3:ListBucket` on the bucket, and 403 otherwise, which the adapter raises as `AwsAccessDenied`. `HeadObject` is authorized by `s3:GetObject`, and it carries no `s3:prefix` context key. So the loader's `ListBucket` statement uses `StringLikeIfExists` on `s3:prefix`: absent key passes (HEAD), a sent prefix must be one of the five. The cost: a list call with no prefix at all is also allowed, showing key names under every prefix of the data bucket (contents stay behind `GetObject`). A plain `StringLike` would have turned every missing-key HEAD into a 403. UNVERIFIED until a live run.

Network of the loader (checked in `modules/network/main.tf`): egress to the endpoint group on 443 covers every interface endpoint, including `s3vectors` (in the default list, so it is in the session-up plan; its DNS name and zone coverage stay UNVERIFIED until the first plan) and `bedrock-runtime` (added when `enable_bedrock`); egress to the S3 and DynamoDB gateway prefix lists on 443; egress to the Neptune group on 8182; Neptune ingress from the loader on 8182. All existed already. New: Neptune group egress to the S3 prefix list (finding L1, above). No new endpoint other than `bedrock-runtime`, which is the S overlay's `enable_bedrock = true`. The Neptune data API needs no endpoint: the client connects to the cluster host in the data subnets.

Does the S session need Neptune for the loader to finish? Not by design. Per work order 30, task 4, the loader skips the graph step loudly when the export lacks the graph tables, and the Neptune names are simply absent when `enable_neptune` is false. So the loader can finish with `enable_neptune = false` after the S overlay (a later `-var enable_neptune=false` wins over the overlay), loading vectors only. The decision D17 instructor requirement includes a Neptune load, so the full S session needs Neptune; the Neptune-off form is a fallback or a rehearsal.

| Session-up plan (S overlay, endpoints egress, 1 AZ) | Interface endpoints | Neptune | EMR node | Burn while the EMR cluster is up | Burn while only the loader runs |
|---|---|---|---|---|---|
| With Neptune and Bedrock (the S overlay as now) | 9 | on | 1 | 0.37 to 0.585 USD an hour | 0.18 to 0.27 |
| Neptune off, Bedrock on | 9 | off | 1 | 0.31 to 0.485 | 0.12 to 0.17 |
| Before this work order (Neptune on, Bedrock off) | 8 | on | 1 | 0.36 to 0.57 | 0.17 to 0.25 |

Arithmetic: endpoints 0.010 to 0.015 USD per endpoint-hour in one AZ (U6, unverified) times 9 is 0.09 to 0.135 (8 is 0.08 to 0.12); Neptune `db.t4g.medium` 0.06 to 0.10 (U3, unverified); EMR `m5.xlarge` 0.22 to 0.35 (U1, unverified) only while the cluster exists, and the state machine terminates it before `RunLoader`; the loader Fargate task about 0.03 an hour for 0.5 vCPU and 1 GB (from memory of the Fargate rate, unverified). So the Bedrock endpoint adds 0.010 to 0.015 USD an hour, and Neptune adds 0.06 to 0.10. The orchestrator's rule-of-thumb figure of about 0.60 USD an hour is the top of the first row. The Titan token cost is small (2,000 vectors at S) and priced in `COST_ESTIMATE.md`, not hourly. Neptune creation time and the bulk-load duration are not measured, so Neptune hours in a session are an assumption.

Session-down interaction: `session-down.tfvars` does not set `enable_bedrock`, so a session-down plan made after an S session flips the Bedrock IAM statements off (an IAM-only diff, no resource churn; the endpoint goes with `egress_mode = "none"` anyway) and the next S session flips them on. Read that diff each time, or set `enable_bedrock = true` in the session-down overlay to hold the IAM steady at the price of keeping the permissions between sessions. Not changed here (no work order for it).

Expected plan effect of work order 31 on the foundation (session-down state, plan 5): in-place IAM policy updates on `loader` (and `neptune-bulk-load` only when Neptune exists, so none while Neptune is off), one new security group rule (`to_s3["neptune"]`), and the loader task definition replaced with a new revision (the container environment changed). No resource is destroyed. UNVERIFIED until a plan.

### Idle-cost table (resources that bill while they exist and nothing runs)

Rates are mostly from memory of the public pricing pages and are **not yet verified** (see `COST_ESTIMATE.md`, which marks each rate verified or unverified). Treat the numbers as order of magnitude.

| Resource | Default | Idle rate (approx; rates not verified for us-east-2, see `COST_ESTIMATE.md`, "Region migration") | Note |
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
| `budget_total_usd` | `20`, range above 0 to 20 (was 25, lowered by decision D6) | used only when `create_budgets` is true |
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
| `pipeline_step_timeout_seconds` (module `step_timeout_seconds`) | `3600`, range 300 to 7200 | finding L-b. REPLACED in Phase 13 (work order 01, finding F1) by the three variables below |

### Variables replacing the single step timeout (Phase 13, finding F1)

| Variable (module name) | Default | Range | Bounds | On timeout |
|---|---|---|---|---|
| `pipeline_create_cluster_timeout_seconds` (`create_cluster_timeout_seconds`) | 1440, all tiers | 300 to 3600 | CreateCluster | Failed, no cleanup state |
| `pipeline_emr_step_timeout_seconds` (`emr_step_timeout_seconds`) | 3600; S 4800 (work order 52, D32; 3600 under work order 49, 1800 before), M 2700, L 3600 | 300 to 7200 | the one pipeline script step (Hadoop, ER, Spark, export, publish) | CleanupOnFailure, then Failed |
| `pipeline_loader_timeout_seconds` (`loader_timeout_seconds`) | 3600; S 1800, M 2700, L 3600 | 300 to 7200 | RunLoader | Failed, no cleanup state |

TerminateCluster and CleanupOnFailure are not variables: each keeps a fixed 900 s (`locals` in `modules/workflow/main.tf`). The overlay values for the EMR step and the loader are unchanged from repair round 2; only CreateCluster moved (from 1800, 2700 and 3600 to 1440). The EMR step and loader values do not rise to the estimate's HIGH figures: the human decided to keep the caps (O1, Phase 13 decisions D10; see "Pipeline bounds").

### Pipeline bounds (review finding M1)

The state machine has a timeout on each task state and an execution timeout. Task states: CreateCluster (`pipeline_create_cluster_timeout_seconds`, 1440 s), the one pipeline script step (`pipeline_emr_step_timeout_seconds`; S 4800 since work order 52, M 2700, L 3600; there is no separate Hadoop or Spark task state; inside the step the script has its own `timeout` limits, see "On-node time limits" in `scripts/README.md`: Hadoop 1500 s, entity resolution and each Spark application 3000 s, so the step cap is the outer bound), FetchScripts (fixed 300 s), RunLoader (`pipeline_loader_timeout_seconds`; S 1800, M 2700, L 3600), TerminateCluster and CleanupOnFailure (fixed 900 s each). Execution: `max_run_seconds`, default 10800 seconds = 180 minutes, validated 1800 to 10800 and by Rule A below. The default was 14400. 10800 is the 150-minute tier L script cap in `scripts/run-bounded-pipeline.sh` plus 30 minutes of margin. The bound goes in per run through the experiment tfvars: S 7200 (work order 49, human decision 2026-11-15, after S run 3 hit the old 1800 s step cap; run it with the script's `--size medium`, 90-minute cap, which stops the execution at `--max-minutes` before 7200 s), M 7200 (90 plus 30), L 10800 (150 plus 30).

Where each failure goes (Phase 13, finding F1; read from `modules/workflow/main.tf`, not observed on AWS):

| State | Catch target | Cleanup runs? |
|---|---|---|
| CreateCluster | `Failed` | No |
| FetchScripts, PipelineStep or CapabilityStep (design B, work order 20; replaced HadoopStep and SparkStep) | `CleanupOnFailure`, then `Failed` | Yes |
| ChooseStep with no matching mode | `CleanupOnFailure`, then `Failed` | Yes |
| ValidateInput with a bad input | `InvalidInput` (a Fail state, before any cluster) | Not needed |
| TerminateCluster | `Failed` | No |
| RunLoader | `Failed` | No |
| CleanupOnFailure | `Failed` (error kept in `$.cleanup_error`) | it is the cleanup |

What that means. A hung CreateCluster ends at its own timeout (1440 s), not in the execution timeout, and goes straight to `Failed`. No state terminates a cluster on that path. Whether Step Functions terminates the half-created cluster when the task times out is UNVERIFIED, and so is whether idle auto-termination applies to a cluster that never reached `WAITING`. The stops that exist are the script's: `run-bounded-pipeline.sh` with `--stop-on first-failure` terminates leftover clusters named `<prefix>-pipeline` created after the start, and the human's cleanup check. Likewise a failed or timed-out TerminateCluster goes to `Failed` and leaves the cluster running (a retry of the termination is not modelled), and RunLoader runs after the cluster is already terminated, so its failure leaves no cluster. Routing TerminateCluster's catch to a cleanup state is a state-machine change this work order did not make.

What the bound does not do. When the execution-level timeout fires, the execution ends as `TIMED_OUT` and `CleanupOnFailure` does **not** run. The same is true of `StopExecution`. `CleanupOnFailure` runs only after a caught error in the Hadoop or Spark step (including a step timeout). So if the script that watches the run dies, the state machine can keep going for up to `max_run_seconds`, and if the execution is then timed out, a running EMR cluster is left to two things only: the cluster's own idle auto-termination (900 s, which starts only once no step is running, so a step still running keeps the cluster up until it ends) and the human's cleanup. Nothing here stops the cluster sooner. Whether a timed-out step also stops the EMR step on the cluster is UNVERIFIED.

One rule is enforced and one is a known limitation (Phase 13, finding F1, human decision O1).

- Rule A, enforced as a variable validation in `modules/workflow` (CHANGED in work order 49; it used to read `create + 2 x emr_step + 300`, which counted two long steps although the design B state machine has one): `create_cluster_timeout + emr_step_timeout + 600 <= max_run_seconds`. The one EMR step that runs to its limit is then cut by its own timeout and reaches `CleanupOnFailure` before the execution timeout. The 600 s is the fixed 300 s FetchScripts step plus 300 s of margin for the cleanup state to issue the terminate call; the 900 s cleanup bound does not have to fit, because the call is made when the state starts. Holds today: S 1440 + 4800 + 600 = 6840 <= 7200 (360 s of slack; work order 52, was 5640 with a 3600 s step); M 1440 + 2700 + 600 = 4740 <= 7200; L 1440 + 3600 + 600 = 5640 <= 10800. Under the old rule the S values of work order 49 would have been refused (1440 + 2 x 3600 + 300 = 8940 > 7200).
- Rule B, a KNOWN LIMITATION, not enforced and not warned about: `create + 300 (fetch) + emr_step + 900 (TerminateCluster) + loader_timeout <= max_run_seconds`, the worst-case sum of the sequential timeouts (the earlier text counted 2 x emr_step and no fetch step). It does NOT hold on S and M, by design: S 1440 + 300 + 4800 + 900 + 1800 = 9240 > 7200 (2040 s over; it was 8040, 840 s over, with a 3600 s step); M 1440 + 300 + 2700 + 900 + 2700 = 8040 > 7200; L 1440 + 300 + 3600 + 900 + 3600 = 9840 <= 10800 holds. In practice the script cap (`--max-minutes`, 90 for `--size medium`) ends a run first. The human accepted this outcome by keeping the caps (decision O1, `docs/agent-runs/phase-13/decisions.md` D10). The earlier plan-time `check` block was removed in work order 05 because a warning that fires on every plan teaches readers to ignore warnings; this paragraph is where the limitation is recorded. A Rule B breach cuts the loader or terminate segment with `TIMED_OUT`; it cannot skip the Hadoop or Spark cleanup, which Rule A covers.

Plain statements of what the caps mean (human decision O1):

- At the estimate's HIGH bound a run may time out (S 7200 s against 7200 s since work order 49, with no room for the terminate allowance, and the S worst case of the segment timeouts is 9240 s since work order 52; M 12240 s against 7200 s, L 23040 s against 10800 s, before the terminate allowance).
- The estimate's HIGH hours are not a promise that the caps cover. The caps are ceilings chosen under the cost ceiling (25 when they were set, 20 since decision D6; no cap was changed), not a forecast of the run time.
- A timed-out run still wastes the spend it has accrued and needs a re-run with a new approval record (a record is single use).
- A cap is a ceiling, not a cost. No cost figure changes with this decision.

Recorded human step (Phase 14, before M runs). After the S run, the human sets the M and L values from the measured S runtime and records them as a human decision in a Phase 14 decisions file (`docs/agent-runs/phase-14/decisions.md`) before M runs. Formula, for each of the script `--max-minutes` cap, `pipeline_emr_step_timeout_seconds` and `pipeline_loader_timeout_seconds`: `value(tier) = measured S value x (works(tier) / 5,000) x 1.5`, with 25,000 works for M (factor 5) and 100,000 for L (factor 20), so the factors with margin are 7.5 and 30. The measured S value is the longest single Hadoop or Spark step for the EMR step timeout, the load time for the loader timeout, and the whole run for the cap. `pipeline_create_cluster_timeout_seconds` does not scale with works: use the measured S cluster start time x 1.5 (this is a proposal in this work order, the human may choose otherwise). `pipeline_max_run_seconds` follows the cap plus 30 minutes as today. Every result stays inside the existing validation ranges (execution 1800 to 10800 s, EMR step 300 to 7200 s, loader 300 to 7200 s, create 300 to 3600 s), Rule A must still hold, and the projected spend must stay under the $20 code ceiling (and, by the human's operating rule, stop near $15 of actual spend); a result outside a range needs a human decision to change the range, it is not clamped silently. The linear-in-works scaling is an assumption that S, M and L were not measured to satisfy.

Arithmetic from the estimate's HIGH figures (`COST_ESTIMATE.md` section 1.2 and the Fargate rebuild; the launch overhead is inside the node-hours; the estimate has no terminate time, so 900 s is an allowance):

| | S | M | L |
|---|---|---|---|
| Cluster start, HIGH (0.4 h) | 1440 s | 1440 s | 1440 s |
| Pipeline node-hours, HIGH | 1.0 h | 1.4 h | 2.4 h |
| Hadoop + Spark together (node-hours minus 0.4 h) | 0.6 h = 2160 s | 1.0 h = 3600 s | 2.0 h = 7200 s |
| TerminateCluster allowance | 900 s | 900 s | 900 s |
| Loader, HIGH | 1.0 h = 3600 s | 2.0 h = 7200 s | 4.0 h = 14400 s |
| One sequential HIGH run (start + Hadoop and Spark + terminate + loader) | 8100 s | 13140 s | 23940 s |
| Same without the terminate allowance | 7200 s = 120 min | 12240 s = 204 min | 23040 s = 384 min |
| Execution timeout today | 7200 s (work order 49; was 5400) | 7200 s | 10800 s |
| Script `--max-minutes` cap today | 60 min = 3600 s (the S run uses `--size medium`, 90 min = 5400 s) | 90 min = 5400 s | 150 min = 9000 s |
| Overlay EMR step and loader timeout today | step 4800 s (work order 52; 3600 s under work order 49, 1800 s before), loader 1800 s | 2700 s | 3600 s |

The estimate's own HIGH run does not fit the current execution timeout or the script caps on any tier. Even at the estimate's LOW figures, M (0.6 h + 1.0 h = 96 min) and L (0.9 h + 2.0 h = 174 min) exceed the caps of 90 and 150 minutes. The human decided (O1) not to raise the caps; the options and their dollar change are in `docs/agent-runs/phase-13/` (work order 01 report). The EMR step and loader timeouts keep their repair round 2 values, each bounded, documented and inside `pipeline_max_run_seconds` (Rule A holds on every tier). F1 is therefore closed in structure and accepted in value: RunLoader, or a run as a whole, can time out when the work takes what the estimate's HIGH bound assumes.

CreateCluster no longer shares a window with the steps: it is cut at 1440 s on every tier. The price of any timeout: a single legitimate step longer than its timeout is stopped, so if S or M measure a longer step or load, raise the matching timeout and `pipeline_max_run_seconds` together through the recorded human step above (Rule A will refuse an inconsistent set at plan time). Reachable gap that remains: a hung `CreateCluster` goes to `Failed` at 1440 s with no cleanup (see the table above), a dead client that never lets a step fail still ends in the execution timeout, which skips cleanup, and a failed TerminateCluster leaves the cluster; the idle auto-termination (900 s, only after no step is running), the script's leftover-cluster teardown and the human's cleanup are then the only stops. Behavior of the state machine under these timeouts is not proven by `validate`; it needs a Phase 14 test.

### Example tfvars (values only, no secrets)

Apply `course.tfvars` first, then overlays; a later `-var-file` wins. Sizes S, M and L are the PROPOSED tiers (5,000, 25,000 and 100,000 works) from `COST_ESTIMATE.md`; decision D2c has not confirmed them.

| File | Use |
|---|---|
| `experiment-size-s.tfvars` | tier S: Neptune provisioned on, one EMR node, no standing service tasks |
| `experiment-size-m.tfvars` | tier M: same, one task per service |
| `experiment-size-l.tfvars` | tier L: same, two tasks per service for the load test. Runs last, behind the human spend gate |
| `elasticity-example.tfvars` | Phase 15 elasticity run on M: master plus 4 core nodes, scaling cap 5. Repeat with `emr_core_instance_count` 1, 2 and 4. Check the On-Demand Standard vCPU quota first (5 x m5.xlarge is 20 vCPUs) |
| `session-down.tfvars` | between sessions: `egress_mode = "none"`, Neptune off, service tasks 0. Keeps all storage. Never combine with an `experiment-size-*` overlay. See "Session up and session down" |

Each size overlay sets `pipeline_max_run_seconds` (S 7200 since work order 49, M 7200, L 10800), `pipeline_create_cluster_timeout_seconds` (1440 on every tier), `pipeline_emr_step_timeout_seconds` (S 4800 since work order 52, M 2700, L 3600) and `pipeline_loader_timeout_seconds` (S 1800, M 2700, L 3600), with the arithmetic in the overlay comments. The S overlay also sets `enable_bedrock = true` (work order 31; M and L do not yet). Each size overlay also sets `enable_neptune = true`, so a session brought up with an overlay bills Neptune for the whole session and runs the overlay's service tasks (S none, M one per service, L two per service); the estimate counts those hours (repair round 2, finding N1). A pipeline-only overlay with Neptune off is a proposal only (`COST_ESTIMATE.md`, section 3a), not built.

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

### Budgets

Three separate limits exist, and only one is enforced by AWS (see "Account facts and cost limits"). The AWS-enforced project spend limit is USD 20 (pause idle resources on). The AWS Budget of USD 25 with alerts at 10, 15 and 20 is the outer alert and only notifies. The code ceiling (USD 20, decision D6) is what the scripts and the `budget_total_usd` validation enforce before any run. The human's operating stop is near USD 15 of actual spend. `create_budgets` is false so nothing duplicates the existing budget. When enabled, the module creates one more budget named `<prefix>-total` with the thresholds on actual spend (credits excluded, so the figure is gross usage), capped at 20, with period `ANNUALLY` as the nearest fit to a cumulative project total. It cannot represent the existing USD 25 budget, because the validation now stops at 20. Enabling it, and the notification address, are human actions. AWS does not enforce a budget: it only alerts.

## IAM (roles and their limits)

No IAM user, access key or login profile is created. The roles:

| Role | Assumed by | Allowed actions | Resources |
|---|---|---|---|
| `emr-service` | EMR service | AWS-managed `AmazonEMRServicePolicy_v2` (cannot be narrowed here), plus inline `pass-node-role` (work order 40): `iam:PassRole` on the `emr-ec2` role ARN only, `iam:PassedToService` `StringLike` `ec2.amazonaws.com*` | the managed policy acts only on resources tagged `for-use-with-amazon-emr-managed-policies` (set on VPC, both app subnets, the three EMR security groups) and on requests carrying that tag (the `CreateCluster` state sends it as a cluster tag). The managed policy's own PassRole covers only a role named `EMR_EC2_DefaultRole`, so the inline grant is what lets the service pass our node role |
| `AWSServiceRoleForEMRCleanup` (service-linked, work order 39) | EMR (`elasticmapreduce.amazonaws.com`) | AWS-defined policy, not editable here | created by `aws_iam_service_linked_role.emr` in the identity module, unconditional (not tied to `enable_pipeline`, EMR counts or Neptune), so session-down keeps it. A new account has none, and the first `RunJobFlow` fails with `VALIDATION_ERROR` until it exists. Costs nothing. Full teardown (`cleanup.sh apply-destroy`) deletes it with the rest of state |
| `emr-ec2` | EMR nodes | S3 list, get, put, delete, abort-multipart | data and artifacts buckets by prefix (raw, curated, graph in; curated, graph, semantic-context, reports, tmp, emr-logs out) |
| `ecs-execution` | ECS agent | ECR pull; log stream writes; read three secrets | ECR repos `<prefix>-*`; log groups `/<prefix>/*`; the three secret ARNs. `ecr:GetAuthorizationToken` needs `*` |
| `runtime` | fast and slow tasks | S3 read; DynamoDB get and query; PutItem on query-session and evidence-bundle; S3 Vectors get, query; SQS send; Neptune read-only; Bedrock invoke (flag) | prefixes, table and index ARNs, index ARNs, run-requests queue, Neptune cluster resource ID, exact model ARNs |
| `loader` | load task | S3 list, get and put on `semantic-context`, `graph/neptune`, `graph/neptune-manifest`, `reports` and `logs` (work order 31); DynamoDB put, get, query on ledger, registry, metric cache; S3 Vectors get-index, put, get, list, query on the five index ARNs; no SQS (statement removed in work order 37, no adapter uses SQS); Neptune loader actions (flag); Titan invoke (flag) | as above. No `CreateIndex` and no `ListIndexes`: Terraform owns the indexes. S3 list uses `StringLikeIfExists` on `s3:prefix` so a HEAD on a missing key sees 404 and not 403 (see "Loader (work order 31)") |
| `sfn` | Step Functions | EMR create, manage, terminate; ECS run and stop task; PassRole; managed-rule events (3 rules: EMR RunJobFlow, EMR AddJobFlowSteps, ECS `StepFunctionsGetEventsForECSTaskRule`); log delivery | `RunJobFlow` and the log delivery APIs need `*` (no resource-level support); everything else is ARN-scoped, and PassRole is limited by `iam:PassedToService`. EMR actions are on `cluster/*` because the cluster ID does not exist until RunJobFlow runs (finding L3): the actions are describe, terminate, add-steps, cancel-steps, list-steps and add-tags on any EMR cluster in this dedicated account and region. Work order 41 added statement `EmrTagClusterForManagedPolicy`: `elasticmapreduce:AddTags` on `cluster/*` (this account and Region) only when `aws:RequestTag/for-use-with-amazon-emr-managed-policies` is `true` and `aws:TagKeys` is `ForAllValues:StringEquals` that one key. It is redundant in effect, because `EmrManageClusters` already allows AddTags on `cluster/*` without a condition |
| `deployer` (optional) | the human principals in `deployer_trusted_principal_arns`, none by default; MFA required only if `deployer_require_mfa` is true (default false: the IAM users are programmatic-only, D4) | ECR push; ECS register task definition and update services; start and inspect the pipeline; publish jars | repos, services, state machine and execution ARNs, jars and bootstrap prefixes. Cannot run Terraform apply and has no data read or write. Does NOT separate deployment from apply: see "Who runs what" |
| Neptune bulk load (in the neptune module) | RDS service (`rds.amazonaws.com`, `aws:SourceAccount` condition), attached to the cluster through `iam_roles` | S3 list and get | `graph/neptune/*` of the data bucket (narrowed from `graph/*` in work order 31; the manifest under `graph/neptune-manifest/` is not a load source) |

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
| Formatting | `terraform fmt -check -recursive -diff` | exit 0, no diff (human, 2026-11-09, Run 3, after repair round 1). Proves only that the files are formatted as `terraform fmt` would write them. Re-run 2026-11-11 (human, `checks-1.md`, after work orders 01 to 05): passed, no diff |
| Syntax, types, references | `terraform validate` in `environments/course` | exit 0, "Success! The configuration is valid." (human, 2026-11-09, Run 3, after repair round 1; provider 6.26.0). Proves HCL syntax, types, and that every argument and attribute reference exists in the provider schema, and that variable validations and dynamic blocks parse. It does not prove IAM scope, cost, exposure, or that the defaults produce the intended resources. Re-run 2026-11-11 (human, `checks-1.md`, after work orders 01 to 05, with `init -backend=false`, provider 6.26.0 from the lock file): "The configuration is valid." |
| Script syntax | `bash -n` on the seven scripts | exit 0 (human, 2026-11-09, Run 3, repair round 1 files; first time these ran). Proves syntax only. Later runs on 2026-11-11 (human): `bash -n` and `shellcheck -x` clean on the ten scripts and the harness (`checks-3.md`); the harness gave `RESULT pass=546 fail=0` with no AWS reachable. See `scripts/README.md` |
| IAM scoped to actual resources and actions | read-only review of `modules/identity/main.tf` against this table | independent review rounds 1 and 2 done; round 2 verified H1, H2, M8 and the identity scope as fixed. `validate` does not prove IAM correctness |
| Not public, bounded capacity | read-only review; variable validations | rounds 1 and 2: no public exposure found. Range checks exist for the pipeline timeouts and the scaling cap |
| Cost | `COST_ESTIMATE.md` | restated for endpoint egress, provisioned Neptune and absolute thresholds in repair round 1; Neptune session hours and service-task hours restated in repair round 2 (finding N1); mostly unverified rates; S + M at the HIGH bound does not fit under $25, and so not under the $20 code ceiling either (`COST_ESTIMATE.md`, "Cost ceiling lowered to 20 USD") |
| No unexplained stateful replacement, plan counts, plan-signed gate | `terraform plan` | deferred to Phase 14 (D3) |

## Checks to run

```
terraform fmt -check -recursive -diff infra/terraform
terraform -chdir=infra/terraform/environments/course init -backend=false
terraform -chdir=infra/terraform/environments/course validate
```

`init -upgrade` is not used: no provider or version changed, the committed `.terraform.lock.hcl` already records 6.26.0, and `terraform_data` is built in and needs no provider. Run `fmt -check` and `validate` after any Terraform edit. Observed results. Latest, 2026-11-11 (human, `docs/agent-runs/phase-13/checks-1.md`, after Phase 13 work orders 01 to 05, which include the F1 timeout files): `fmt -check -recursive -diff infra/terraform` passed with no diff, `init -backend=false -input=false` succeeded (aws 6.26.0 from the lock file) and `validate` printed "The configuration is valid." `checks-3.md` did not rerun Terraform; no Terraform file is known to have changed since. Earlier, on the repair round 1 files (2026-11-09, `terraform-checks.md` Run 3): `fmt -check -recursive -diff` exit 0 with no diff and `validate` exit 0 ("Success! The configuration is valid."). **`terraform plan` has still not been run** and is a Phase 14 item (D3), so plan counts, IAM scope, cost and stateful replacements remain unobserved. The Rule B `check` block was removed in Phase 13 work order 05, so `plan` no longer prints a Rule B warning. If `validate` fails now, the three pipeline timeout variables (`pipeline_create_cluster_timeout_seconds`, `pipeline_emr_step_timeout_seconds`, `pipeline_loader_timeout_seconds`) and their wiring in `environments/course/main.tf` are the first places to look. `validate` proves syntax, types and references only; it does not prove IAM correctness, cost or exposure.

## Availability checks for the human (us-east-2)

Phase 14 decision D7. Read-only calls, run by the human with the `scholarchain` profile (agents never read `~/.aws` and do not run these). Each lists and describes only. They settle the two items that were marked NOT CONFIRMED, before the first plan. Status: the human ran the Neptune and EC2 checks on 2026-11-13 (`docs/agent-runs/phase-14/checks-3.md`); both passed at region level.

Neptune `db.t4g.medium` for engine `1.4.5.1`, with the zones it can be placed in (expect the class and both `us-east-2a` and `us-east-2b` in the output; an empty list means the class or version is not orderable here and `neptune_instance_class` or `neptune_engine_version` must change before any plan):

```
aws neptune describe-orderable-db-instance-options --engine neptune --engine-version 1.4.5.1 --db-instance-class db.t4g.medium --region us-east-2 --profile scholarchain --query 'OrderableDBInstanceOptions[].{Class:DBInstanceClass,Version:EngineVersion,Zones:AvailabilityZones[].Name}' --output json
```

If that is empty, list what is orderable for the version, and the versions the engine offers:

```
aws neptune describe-orderable-db-instance-options --engine neptune --engine-version 1.4.5.1 --region us-east-2 --profile scholarchain --query 'OrderableDBInstanceOptions[].DBInstanceClass' --output text
aws neptune describe-db-engine-versions --engine neptune --region us-east-2 --profile scholarchain --query 'DBEngineVersions[].EngineVersion' --output text
```

EMR primary node `m5.xlarge` (EC2 offering by zone; expect `us-east-2a` and `us-east-2b`, because the cluster uses the first app subnet and that zone must offer it):

```
aws ec2 describe-instance-type-offerings --location-type availability-zone --filters Name=instance-type,Values=m5.xlarge --region us-east-2 --profile scholarchain --query 'InstanceTypeOfferings[].Location' --output text
```

An EC2 offering shows the instance type exists in the zone. It does not prove that EMR release `emr-7.10.0` supports it there; the EMR release label stays in "Unverified items" until a plan and the first cluster launch. Record the pasted output in the Phase 14 evidence directory before the first plan.

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
- Neptune engine version `1.4.5.1` and family `neptune1.4` (must support Serverless): `1.4.5.1` is in the orderable list for `db.t4g.medium` in us-east-2 (human read-only CLI check, 2026-11-13, `docs/agent-runs/phase-14/checks-3.md`) and the family matches. Settled; the check did not list Availability Zones.
- EMR release label `emr-7.10.0` and its Hadoop and Spark versions against the built jars.
- Bedrock Haiku 4.5 in us-east-2: reachable only through a cross-Region profile (In-Region is not supported). The Geo US profile `us.anthropic.claude-haiku-4-5-20251001-v1:0` is documented for source us-east-2, routing to us-east-1, us-east-2 and us-west-2 (the us-east-1 entry in `bedrock_haiku_regions` is a routing destination only); `global.anthropic.claude-haiku-4-5-20251001-v1:0` is the Region-neutral alternative. Source: `docs/agent-runs/phase-14/evidence-availability-us-east-2.md`. The human confirmed live in us-east-2 (Phase 14 D5, relayed by the orchestrator) that this profile works and that Titan V2 returns a 256-length embedding at dimensions 256, which closes risk R1. IAM: the runtime and loader policies list the profile ARN in `var.region` plus a foundation-model ARN for each of the three destination Regions (`bedrock_haiku_regions`); all three are needed because a cross-Region call is authorized against the destination model ARN. The us-east-2-only Region guard applies to the provider and Terraform; the profile's routing to us-east-1 and us-west-2 is Bedrock-side.
- Neptune `db.t4g.medium` in us-east-2: confirmed orderable by a human read-only CLI check on 2026-11-13 (`docs/agent-runs/phase-14/checks-3.md`, decision D7), for engine versions 1.1.1.0 up to 1.5.0.1, which includes 1.4.5.1. Exact commands in "Availability checks for the human (us-east-2)" below. Neptune and EMR are Paid-plan services, acceptable under D1.
- EMR instance type `m5.xlarge` in us-east-2: EC2 offers it in us-east-2 (human read-only CLI check, 2026-11-13, `checks-3.md`; region-level, not per zone). The EMR per-Region page was not read, so EMR support is not confirmed; the first CreateCluster confirms it (residual risk low).
- EMR service-role v2 tag requirement. Step Functions managed rules: the policy grants three (EMR `StepFunctionsGetEventForEMRRunJobFlowRule`, EMR `StepFunctionsGetEventForEMRAddJobFlowStepsRule`, ECS `StepFunctionsGetEventsForECSTaskRule`). Only the ECS name, with "Events" in the plural, is VERIFIED (https://docs.aws.amazon.com/step-functions/latest/dg/connect-ecs.html); the two EMR names are carried over and UNVERIFIED. The state machine uses four `.sync` integrations (createCluster, addStep, terminateCluster, runTask). Whether `terminateCluster.sync` uses an EMR managed rule is UNVERIFIED and no rule name is invented. The documented terminateCluster policy has `TerminateJobFlows` and `DescribeCluster` only, and the documentation says missing events permissions on a `.sync` task cause a delay before Step Functions notices completion, not a denial (https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html and https://docs.aws.amazon.com/step-functions/latest/dg/service-integration-iam-templates.html). The documentation also shows a polling fallback for this case: UNVERIFIED here beyond that statement, and this repo does not rely on it.
- Neptune bulk-loader egress (finding L1). The Neptune security group had no egress rule (Terraform removes the default allow-all), and the bulk loader reads from S3 on behalf of the cluster. Work order 31 added `neptune` to the `to_s3` egress rule (443 to the S3 gateway prefix list only) and confirmed the S3 gateway endpoint is attached to the data route table, where the Neptune subnets sit. Whether AWS requires the security-group rule was not read from the documentation: the rule is a narrow safe-side addition. UNVERIFIED until a real load (the loader's first `StartLoaderJob`).
- S3 Vectors and SQS interface endpoints: see the Egress section for what is verified.
- `terraform_data` `ignore_changes` and precondition behavior for the dimension guard: see the S3 Vectors section.
- The services' container environment variable names and ports 8080 and 8081 are proposals (whether the current services code reads any of them is UNVERIFIED). The loader's six names are no longer proposals: they come from work order 30 (`scholarchain.rag.AwsIngestMain`, not present in the tree when work order 31 ran, so UNVERIFIED against code). See "Loader (work order 31)".
- The hash embedder is 384 dimensions, so a fallback run needs `vector_dimension = 384` in its own deployment (its own environment: changing the dimension on the existing one destroys the vectors, see above). Titan V2 offers only 256, 512 and 1024.
