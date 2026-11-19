# Service limits and Region availability

Written 2026-11-19 from repository files. Documentation-engineer has no documentation-MCP or AWS access, so no AWS limit below was re-read from AWS on that date. Status vocabulary:

- VERIFIED (source, date): a repository record states that someone read the source on that date, or the value was read from code in this repository on 2026-11-19.
- OBSERVED (record): seen working in a release or run record.
- UNVERIFIED: carried from a file that marks it unverified, or from a file that gives no source.

AWS values can change. Every AWS row marked VERIFIED was read from the cited page by the orchestrator on the stated date, or checked by the human on the command line where the row says so (rows for Neptune `db.t4g.medium` and EC2 `m5.xlarge`, `checks-3.md`), and was not re-read for this document. Re-verify before any live run (the Phase 7 record says the same).

Region: `us-east-2` only. The account is locked to it (Phase 14 decision D1; `var.region` refuses another value at plan time; `infra/terraform/README.md`, "Account facts and cost limits").

Related: [threat model](threat-model.md), [runbook](runbook.md), [architecture](architecture.md).

## 1. Region availability used by the implementation

Source file: `docs/agent-runs/phase-14/evidence-availability-us-east-2.md` (collected 2026-11-13 by the orchestrator from the AWS documentation MCP server), plus the later human read-only checks in `docs/agent-runs/phase-14/checks-3.md` as cited in `infra/terraform/README.md`.

| Item | Status | Source and date | Note |
|---|---|---|---|
| S3 Vectors in us-east-2 | VERIFIED (S3 Vectors regions and quotas page, 2026-11-13); OBSERVED (vector ingest finished in run 8, `docs/releases/phase-14/README.md`) | endpoint `s3vectors.us-east-2.api.aws` | The supported-services page for the new account type does not name S3 Vectors; real use settled it |
| S3 Vectors interface-endpoint service name in us-east-2 | UNVERIFIED in the Terraform README (name built from the Region-neutral pattern) | `infra/terraform/README.md` "Egress" | Session plans listed the `s3vectors` endpoint among the nine; the first-plan check outcome is not stated in the files read |
| Neptune endpoint in us-east-2 | VERIFIED (2026-11-13) | `rds.us-east-2.amazonaws.com` | |
| Neptune `db.t4g.medium`, engine 1.4.5.1 | VERIFIED: orderable (human read-only CLI check, 2026-11-13, `checks-3.md`) | Terraform README | Orderable is not the same as sufficient: run 10 failed with Neptune `OUT_OF_MEMORY_ERROR` on this class (`docs/releases/phase-14/README.md`). The graph loads used `db.r6g.xlarge` (OBSERVED, run 11 and Phase 15 A1), then the writer was modified to `db.t4g.medium` for serving |
| Neptune `db.r6g.xlarge` in us-east-2 | OBSERVED (deploy 15 and Phase 15 A1 reached `DEPLOY_COMPLETE`) | `docs/releases/phase-14/README.md`, `docs/releases/phase-15/README.md` | No separate documentation read recorded |
| Neptune zone coverage for `db.t4g.medium` | UNVERIFIED: the check did not list Availability Zones | Terraform README "Unverified items" | |
| EMR endpoint in us-east-2 | VERIFIED (2026-11-13) | `elasticmapreduce.us-east-2.amazonaws.com` | |
| EC2 `m5.xlarge` offered in us-east-2 | VERIFIED at Region level (human CLI, 2026-11-13, `checks-3.md`) | Terraform README | EMR support for the instance type: the first cluster launch confirmed it in practice (S runs 3 and later ran EMR), no documentation read recorded |
| EMR release `emr-7.10.0`, Hadoop-only application list | OBSERVED (pipeline runs reached the EMR step); documentation read: NOT RECORDED | Terraform README "EMR design B" | |
| Titan Text Embeddings V2 in us-east-2 | VERIFIED: in-Region supported (2026-11-13); OBSERVED: 256-dimension vector returned (Phase 14 D5, relayed by the orchestrator) | evidence file; Terraform README | Titan V2 offers dimensions 256, 512 and 1024 |
| Claude Haiku 4.5 in us-east-2 | VERIFIED: only through a cross-Region profile; in-Region is not supported (2026-11-13) | evidence file | Profile `us.anthropic.claude-haiku-4-5-20251001-v1:0` routes from us-east-2 to us-east-1, us-east-2 and us-west-2. OBSERVED: Haiku calls returned `outcome=ok` in SMOKE2 (`docs/releases/phase-15/smoke-tn.md`). IAM lists the profile ARN plus a foundation-model ARN for each of the three destination Regions |
| Neptune bulk loader S3 path | OBSERVED (run 11, 12 jobs `LOAD_COMPLETED`, 0 errors) | `docs/releases/phase-14/README.md` | Requires the S3 gateway endpoint and an IAM role |
| Services used are in the new-account supported list (DynamoDB, S3, ECS, ECR, SQS, Step Functions, Secrets Manager, KMS, CloudWatch, CloudTrail, Budgets, PrivateLink, VPC, EventBridge, Bedrock) | VERIFIED (2026-11-13) | evidence file | Neptune and EMR are in the "Paid Plan" list; the project account is on the Paid plan |
| Neptune Free Tier applicability | UNVERIFIED | evidence file | Billing line settles it |
| Neptune and Haiku availability in other Regions | Not used. Another Region is locked out because it needs "Activate advanced features", which is irreversible and removes the spend limit | Terraform README | |

## 2. AWS service limits the adapters assume

Source: `docs/agent-runs/phase-07/service-limits.md`, captured 2026-11-08 by the orchestrator through the AWS Documentation MCP server (public pages). Not re-read since: NOT RECORDED. Adapters take these as named constants or configuration and validate before sending.

### S3 Vectors (VERIFIED, 2026-11-08, `s3-vectors-limitations.html`)

| Limit | Value |
|---|---|
| Vector buckets per Region per account / indexes per bucket / vectors per index | 10,000 / 10,000 / up to 2 billion |
| Dimension per vector | 1 to 4,096 (the project uses 256, cosine) |
| Total metadata per vector / keys per vector | 40 KB / 50 |
| Filterable metadata per vector | 2 KB |
| Non-filterable metadata keys per index | 10 |
| PutVectors and DeleteVectors | 1,000 combined requests a second per index; 2,500 vectors a second per index |
| Request payload; vectors per PutVectors; per DeleteVectors; per GetVectors | 20 MiB; 500; 500; 100 |
| QueryVectors top-K; results per page | up to 10,000; up to 100 |
| ListVectors page; segment count | up to 1,000; up to 16 |
| Vector key | up to 1,024 characters, case-sensitive, UTF-8 (`s3-vectors-vectors.html`) |
| Element type | float32 (`s3-vectors-best-practices.html`) |

Design consequence (same file): batch size at most 500; filterable metadata at most 2 KB and references only; stable key at most 1,024 characters. Observed in practice: PutVectors rejected empty arrays in run 7 (`docs/releases/phase-14/README.md`).

### Neptune (VERIFIED, 2026-11-08, `limits.html`)

| Limit | Value |
|---|---|
| Bulk loader queued jobs / tracked jobs / error details per job | 64 / most recent 1,024 / last 10,000 |
| Loader semantics | non-ACID, so loads must be idempotent by id |
| Individual property or label | 55 MB (long text stays in S3) |
| Gremlin and SPARQL HTTP payload | under 150 MB |
| Null characters in strings | unsupported |
| Connectivity | VPC only, TLS 1.2 HTTPS only |
| Cluster volume | 128 TiB |

Offline-loader all-or-nothing semantics and Neptune Analytics quotas: located, not read. Open.

### DynamoDB (VERIFIED, 2026-11-08, `Constraints.html`)

| Limit | Value |
|---|---|
| Item size | 400 KB |
| Partition key / sort key value | 1 to 2,048 bytes / 1 to 1,024 bytes |
| BatchWriteItem / BatchGetItem | 25 requests, 16 MB / 100 items, 16 MB |
| Query and Scan result | 1 MB a call |
| Conditional idempotent writes | `PutItem` or `UpdateItem` with a `ConditionExpression` (batch writes cannot carry conditions) |

### Not captured

Ordinary S3 object and key limits, Neptune per-account shared limits, ECS and Fargate quotas, ALB limits, Step Functions limits, SQS limits, CloudWatch limits: not read. Mark each UNVERIFIED.

## 3. Account quotas and rates

| Item | Value | Status |
|---|---|---|
| Titan Text Embeddings V2 on demand | 300,000 tokens a minute | OBSERVED as a human-checked figure in `docs/agent-runs/phase-14/decisions.md:11` ("checked by the human"); the date of the check is not stated there |
| Claude Haiku 4.5 cross-Region and global | 5,000,000 tokens a minute | same file and caveat |
| Fargate vCPU quota | 30 vCPU assumed in `SESSION2.md` section 3 | UNVERIFIED. Decision P15-8 asked the human for the EC2 and Fargate vCPU quota check; the result is NOT RECORDED in the files read |
| EC2 On-Demand Standard vCPU quota | the elasticity overlay needs 20 vCPUs (5 x `m5.xlarge`) | UNVERIFIED, same reason |
| Hourly rates (interface endpoints, Neptune, EMR, Fargate, ALB) | in `infra/terraform/COST_ESTIMATE.md` | Mostly UNVERIFIED (the file marks each rate). Phase 15 session 2 billed about 2.7 USD per the Phase 17 plan; billing readings are the human's |
| Project spend limit (AWS-enforced) | 20 USD, pause idle resources on | Terraform README, Phase 14 D1 |
| AWS Budget | 25 USD, alerts at 10, 15, 20; alert only | same |
| Code ceiling | 20 USD (`SC_PROJECT_CEILING_USD` in `scripts/lib.sh`, `budget_total_usd` validation) | read in the README; not re-read in the script for this document |
| Operating stop | near 15 USD of actual spend (the Phase 17 plan uses 14 USD) | human decision |

## 4. Code defaults (VERIFIED, read from source 2026-11-19)

### Services: `modules/services/src/main/scala/scholarchain/services/ServiceConfig.scala:124-152`

Key, default, allowed range.

| Key | Default | Range |
|---|---|---|
| `http.bodyLimitBytes` | 65,536 | 256 to 1,048,576 |
| `http.maxUriChars` | 4,096 | 64 to 65,536 |
| `http.threads` | 8 | 1 to 64 |
| `http.queueCapacity` | 64 | 1 to 10,000 |
| `query.maxQuestionChars` | 1,000 | 1 to `RagRuntime.MaxQuestionChars` |
| `query.asyncEnabled` | true | |
| `query.maxResultLimit` | 20 | 1 to 100 |
| `fast.deadlineMillis` | 2,000 | 20 to 60,000 |
| `slow.deadlineMillis` | 10,000 | 20 to 300,000 |
| `ready.deadlineMillis` | 1,000 | 20 to 30,000 |
| `retry.max` / `retry.backoffMillis` | 2 / 20 | 0 to 2 / 0 to 1,000 |
| `breaker.failureThreshold` / `breaker.openMillis` | 5 / 5,000 | 1 to 100; 10 to 600,000 |
| `calls.poolSize` / `calls.queueCapacity` | 16 / 32 | 1 to 128 / 1 to 10,000 |
| `graph.defaultWindow` | all | |
| `graph.maxDepth` / `graph.maxFanout` / `graph.maxExpansions` | 2 / 100 / 25 | 1 to 4 / 1 to `CypherQuery.MaxLimit` / 1 to 200 |
| `graph.defaultLimit` / `graph.maxLimit` | 20 / 50 | 1 to maxLimit / 1 to `ReadLimits.MaxPage` |
| `evidence.maxContentChars` | 500 | 16 to 5,000 |
| `jobs.executorSize` / `jobs.queueCapacity` / `jobs.retained` | 2 / 16 / 1,000 | 1 to 16 / 1 to 1,000 / 1 to 100,000 |
| `idempotency.entries` | 1,000 | 1 to 100,000 |
| `records.retained` | 500 | 1 to 100,000 |
| `metrics.enabled` | false | |

An unknown key is a configuration error (`ServiceConfig.from`). Tokens: 16 to 256 printable characters, the query and read tokens must differ; the cursor secret is at least 16 bytes (`ServiceConfig.scala:93-118` as cited in `SESSION2.md` section 8; the `tokenOk` range was cited, not re-read line by line).

One task can therefore hold 16 + 32 = 48 concurrent calls; beyond that a request is rejected (SESSION2.md section 3). Experiment D at 48 clients saw 14 errors in 3,324 requests (`experiments/RESULTS.md` 3.D).

Terraform sets the slow deadline through `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` (`environments/course/main.tf:91`); `0` leaves the code default of 10 s (`variables.tf:492`). Haiku synthesis takes 13.9 to 17.1 s per call at the 4,096-token limit (`docs/evaluation.md` section 6, item 1, P16-34). The smoke overlay `infra/terraform/environments/course/services-smoke.tfvars:26` sets `slow_deadline_millis = 60000` (the file's comment: 60 s is above the 40 s Bedrock call deadline and under the ALB idle timeout of 120 s), and `variables.tf:492` says "The Bedrock smoke raises it". The default (0) and `services-session.tfvars` do not set it, so a Haiku deploy without the smoke overlay keeps the 10 s code default. `variables.tf:497-498` accepts 0 or 10000 to 120000, a narrower range than the 20 to 300000 the code allows.

### Export bounds: `modules/storage/src/main/scala/scholarchain/storage/AwsReadModels.scala:37`

1,000 objects, 128 MiB (134,217,728 bytes), 250,000 rows per table, 1,000,000 rows in all. The Phase 15 export measured 100.9 MiB in 34 objects (`SESSION2.md` section 10).

### Model call bounds

- `ModelRetryPolicy` defaults: 2 retries (range 0 to 5), 12,000 input tokens, 65,536 output characters (`modules/rag/.../Model.scala:544-548`).
- `bedrock_max_calls`: 1 to 10 per process, 10 in the session tfvars (`variables.tf:480-487`, `services-session.tfvars:50`). The planner and the synthesis call both draw on it; "4 queries" is not "4 model calls" (`smoke-tn.md`).
- Bedrock call deadline: 40 s (`variables.tf:492`, description text).

### Task sizing (PROVISIONAL, `SESSION2.md` sections 2 and 7)

Query service 512 CPU and 4096 MiB (heap 75 percent); worker 512 CPU and 1024 MiB (a guess); load client 1024 CPU and 2048 MiB; loader 2048 CPU and 8192 MiB in the S overlay (512 and 1024 default). The heap measure that would decide 2048 or 4096 MiB was not run (`SESSION2.md` section 10, item 1). Health-check grace period 300 s, provisional.

### Scaling (`SESSION2.md` section 3; observed in experiment D)

Query service 1 to 4 tasks; targets 300 requests per target per minute and 60 percent CPU; cooldowns 60 s out, 300 s in. Observed: 1 to 3 and back to 1 (`experiments/RESULTS.md` 3.D). Embedding worker 1 to 4 on backlog per task; scale-out of the worker was not the subject of a separate derived table in the sections read.

### Pipeline timeouts (`infra/terraform/README.md` "Variables replacing the single step timeout", "Pipeline bounds")

| Variable | Default | Range |
|---|---|---|
| `pipeline_create_cluster_timeout_seconds` | 1,440 | 300 to 3,600 |
| `pipeline_emr_step_timeout_seconds` | 3,600 (S 4,800) | 300 to 7,200 |
| `pipeline_loader_timeout_seconds` | 3,600 (S 5,400) | 300 to 7,200 |
| `pipeline_max_run_seconds` | 10,800 (S 7,200) | 1,800 to 10,800 |
| TerminateCluster and CleanupOnFailure | fixed 900 s | not variables |

Rule A is enforced at plan time; Rule B is a recorded limitation. Experiment tier caps in `run-experiment.sh`: S 90 minutes, 64 clients, 20,000 tasks; M 90, 16, 5,000; L 150, 32, 20,000 (M and L provisional).

### Script AWS call limits (`scripts/README.md` "Timeouts and retries")

AWS CLI connect timeout 10 s, read timeout 60 s; three attempts only for `describe-*`, `list-*`, `get-*`, `head-*`. Approval record lifetime at most 24 hours (`SC_APPROVAL_MAX_SECONDS`). Docker login 120 s; build and push 1,800 s each.

## 5. What this document does not settle

- No limit above was re-read from AWS on 2026-11-19.
- Fargate and EC2 vCPU quotas, ALB, Step Functions, SQS and CloudWatch limits: NOT RECORDED.
- The cost rates are mostly UNVERIFIED; billed figures come from the human.
- Replay of any limit-dependent behaviour (for example a PutVectors batch of 500) is NOT REPLAYED until H-8.
