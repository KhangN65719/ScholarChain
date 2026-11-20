# ScholarChain threat model

Scope: the build-time pipeline (datasets, Hadoop, entity resolution, Spark, export, loaders), the runtime (services, RAG, stores, Bedrock), the Terraform and scripts, and the agentic factory that authored them. Written 2026-11-19 from repository files. Nothing here was replayed by the author: documentation-engineer has no shell, and every command or test named below is marked with what the cited record observed, or NOT REPLAYED. The human's H-8 records (`docs/agent-runs/phase-17/human-commands.md`) cover only `validate-agent-files.sh`, `sbt check` on `dc15299` and the Phase 7 agent replay.

How to read the tables. "Control" says what the repository does. "Evidence" names the file, test, hook, IAM statement or script, and says whether a record observed it. "Gap" is what remains. A control that exists in code but was never exercised live is stated as such. Account ids are masked everywhere (`000000000000`).

Related documents: [architecture](architecture.md), [data lineage](data-lineage.md), [API reference](api.md), [service limits](service-limits.md), [runbook](runbook.md), [evaluation](evaluation.md), [Terraform README](../infra/terraform/README.md), [scripts README](../scripts/README.md).

## 1. Assets and trust boundaries

| Asset | Where | Boundary |
|---|---|---|
| Scholarly source records and derived graph | S3 data bucket prefixes (`raw/`, `curated/`, `graph/`, `semantic-context/`), Neptune, DynamoDB, S3 Vectors | VPC-only runtime, no public subnet or load balancer (infra README, "Defaults, and why") |
| Query and read tokens, cursor secret | Secrets Manager (`auth-query-token`, `auth-read-token`, `auth-cursor-secret`), created empty by Terraform | Resolved by the ECS task at start (`infra/terraform/SESSION2.md` section 8) |
| Model input and output | Bedrock (Titan V2 embeddings, Claude Haiku 4.5 through a cross-Region profile) | Model replies are untrusted until schema-validated (`modules/rag/.../BedrockAnswerModel.scala:109`) |
| Approval records and Terraform state | The human's machine, outside the repository | Single-use records, digests printable by any caller (see 6) |
| The constitution (`CLAUDE.md`, `docs/agents.md`, `.claude/**`, `.mcp.json`) | Repository | One writer, enforced by the guard hook and the sandbox deny list |

## 2. Prompt injection

Threat: instruction-like text in a question, a researcher name, a work title or a stored abstract steers the planner, the synthesis model or the verifier.

| Control | Evidence | Gap |
|---|---|---|
| Question, known gaps, names, titles and snippets reach the model only under `untrusted`, `untrustedText` or `untrustedData` keys, and the prompt says that text is data, never instructions | `modules/rag/.../Model.scala:70, 83`; `Synthesis.scala:72-94`; `Planner.scala:178` | The prompt is a request, not an enforcement. The structural controls below carry the weight |
| `InjectionScreen` scans the question, stored names and retrieved snippets with seven patterns (ignore-instructions, role-override, prompt-leak, role-marker, control-token, database-code, claim-forcing). A hit flags the evidence item and the audit record and refuses nothing | `Model.scala:511-535`; callers `Planner.scala:82`, `EvidenceBuilder.scala:73, 527`, `Workflow.scala:211` | Pattern list only. Paraphrase, other languages and encoded text are not claimed to be caught. No false-negative rate is recorded |
| The model's reply must pass its JSON schema; malformed replies get a bounded repair (2 retries, 12,000 input tokens, 65,536 output characters) | `Model.scala:544-548`; `BedrockAnswerModel.scala:109` | A schema-valid reply can still be wrong in content |
| Claims are verified against the evidence bundle after synthesis; an unsupported claim is dropped or the answer abstains | the verifier stage in `modules/rag` (not read line by line for this document); `docs/evaluation.md` section 2.4 (audit found no claim citing invalid evidence, 2 contradicted claims, 3 numbers matching nothing) | The audit shares the export with the labels and reads only extracted numbers and ids, so it does not judge prose (`docs/evaluation.md` 2.4) |
| Database-shaped text in filters or ids is a 400/404/422, never code; Neptune access is parameterised openCypher | `tests/adversarial/Phase10SecuritySuite.scala:157-196`, `Phase10GraphSuite.scala:196, 253`, `Phase7NeptuneSuite.scala:323` | Tests ran under sbt in earlier phases; not replayed in this work order |
| Injection tests at the model layer: retrieved snippet, display name, work title, question and stored summary change no plan, tool or verdict | `tests/adversarial/Phase9ModelSuite.scala:479-607`, `Phase9RepairSuite.scala:349-445`, `Phase11FaultSuite.scala:356-358` | The stub model, not Haiku, ran most of these. Haiku behaviour under injection was not measured as its own experiment: NOT RECORDED |
| Runtime IAM lets the model path read only; no tool the model can call writes | IAM table in `infra/terraform/README.md`, role `runtime` | Read of the whole loaded export is allowed to the runtime |

Residual: the injection test strings are documented in `tests/scripts/README.md` as adversarial inputs. They are test data, not instructions to any reader of this repository.

## 3. Data poisoning

Threat: a malicious or corrupt source record, a wrong entity merge, a tampered export file or a poisoned vector changes what the graph says.

| Control | Evidence | Gap |
|---|---|---|
| Hadoop jobs reject malformed records and count them; rejected records are kept, not dropped silently | Reject handling exists in `modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/TaskSink.scala`, `DblpParser.scala` and the job classes (a text search found reject or quarantine terms in 21 files; counter values were not read for this document: NOT RECORDED here) | Sources are public datasets; this author did not read the licence or checksum records under `datasets/`. No publisher signature check is known |
| Entity resolution owns match decisions; an LLM adjudicator may only accept, reject or abstain on a candidate pair, and a stub that always abstains exists | `modules/entity-resolution/.../llm/Adjudication.scala:26, 60, 110` | Threshold trade-offs and conflicting evidence are in `docs/evaluation.md` 2.1. A deliberately poisoned source pair was not tested: NOT RECORDED |
| Spark consumes canonical, versioned inputs; the export carries a manifest with per-file size, SHA-256 and row count, and `_SUCCESS` is required | `modules/storage/.../JsonlExportSource.scala:168, 275, 312`; `AwsReadModels.scala:123-129` | The manifest is in the same bucket as the files. Anyone who can write both can make them agree (see 7) |
| The runtime refuses an export whose manifest `sourceRunId` differs from the configured `graph_run_id`, and bounds the export (1,000 objects, 128 MiB, 250,000 rows per table, 1,000,000 rows) | `AwsReadModels.scala:20-37, 127` | Bounds stop an oversized export, not a well-formed false one |
| Vector writes are idempotent by text hash and model; a changed text under an existing key is refused with `ChangedVectorContent`, not overwritten | `modules/storage/.../EmbeddingIngest.scala:291`, `EmbeddingWorker.scala:359, 408`; observed on AWS in experiment F run 1 (`experiments/RESULTS.md` section 3.F, `docs/releases/phase-15/README.md` finding 4) | Mixed vector provenance and 2,000 deleted vector keys are recorded limitations (`docs/evaluation.md` 3.7) |
| A poison queue message (unsupported schema version) is received three times, never written, and lands in the dead-letter queue | Experiment F2: PASS 9, FAIL 0, INFO 3 (`experiments/RESULTS.md` 3.F) | One fault class was injected. Others (corrupt body, oversized text) are in unit tests only |
| Loader escapes CSV and Cypher input | `tests/adversarial/Phase7NeptuneSuite.scala:323` | Not replayed here |

## 4. Credential exposure

Threat: a key, token or secret lands in the repository, a log, an agent transcript or a release record.

| Control | Evidence | Gap |
|---|---|---|
| Runtime secrets come from Secrets Manager; Terraform creates them with no value; no IAM user, access key or login profile is created | `infra/terraform/README.md` "IAM"; `SESSION2.md` section 8 | A human fills them with `put-secret-value`. The value is briefly visible in that local command's process arguments (stated in `SESSION2.md`). Values persist across session-down |
| The agent sandbox denies reads of `.env*`, `.aws`, `.ssh`, `.gnupg`, `.kube`, `secret(s)`, `credential(s)`, `*.pem`, `*.key`, `*.tfstate*` and similar; the permission deny list repeats them | `.claude/settings.json` `permissions.deny` and `sandbox.filesystem.denyRead` | Hooks do not analyse the implementation of approved executables (`CLAUDE.md`, "Hook operation"). Verify on the actual runtime |
| The guard hook refuses a shell argument that names a sensitive path, and refuses `aws secretsmanager` and `aws ssm` commands outright | `.claude/hooks/policy.py` (`SENSITIVE`, `guard_shell`) | Direct probe results exist (`probe_policy.py`); native interception in a fresh session is tracked in `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` |
| Scripts scan plans, inputs, manifests and archives for key material (AKIA/ASIA ids, PEM headers, `aws_secret_access_key`, bearer headers, GitHub tokens) and refuse | `scripts/lib.sh:819` (`SC_SECRET_RE`); `archive-evidence.sh` refuses secret-named files | Pattern list, not a proof of absence |
| `smoke-test.sh` reads a token into a mode-600 temp file, passes it to curl by file, deletes it on exit, never prints it, and prints identifier-only fields from the response | `scripts/README.md` "`smoke-test.sh` coverage" | A SIGKILL can leave `.smoke-auth.<pid>`; the archive script refuses such files |
| Tests that need fake keys build them at runtime so no secret-shaped literal sits in the tree | Phase 17 Wave A order K01 (`docs/agent-runs/phase-17/wave-a/work-orders.md`) | Earlier commits contain a fake AWS key id in a test fixture (not a credential). History is not rewritten |
| `.mcp.json` sets the GitHub `Authorization` header to the reference `"${SCHOLARCHAIN_GITHUB_AUTH_HEADER}"`, not a value; the variable holds `Bearer <token>` and is set outside the repository; MCP servers stay disabled until individually approved | `.mcp.json:7`; `CLAUDE.md:37` (MCP trust table, GitHub row); `.claude/settings.json` `enableAllProjectMcpServers: false` | Token handling outside the repository is the human's |
| Account id masking in records | Release records use `000000000000` | The Phase 16 sbt fork line echoed an account id into raw run files outside the repository (`docs/evaluation.md` section 6, item 3); not fixed |
| ALB listener is HTTP 80, so the bearer token crosses the VPC unencrypted | `infra/terraform/SESSION2.md` section 1 ("accepted risk for the human to confirm") | Open. No certificate on an internal ALB name |

## 5. Over-permission

Threat: a role, tool or agent can do more than its job.

| Control | Evidence | Gap |
|---|---|---|
| One role per function with ARN-scoped actions; no `bedrock:*`; Bedrock ARN variables reject `*`; the runtime cannot write the ledger, registry or vector indexes | IAM table in `infra/terraform/README.md` | `ecr:GetAuthorizationToken` and `RunJobFlow` need `*` (stated there). EMR actions are on `cluster/*` of this dedicated account |
| Neptune read for the runtime is limited to openCypher by a condition key | `SESSION2.md` section 6 ("RUNTIME ROLE CHANGE") | UNVERIFIED until live: whether the condition key is populated for the openCypher HTTPS endpoint. Graph retrieval ran on every request in experiment D (`experiments/RESULTS.md` 3.D), which suggests the condition did not block queries; this author did not read a log that shows the IAM decision, so it stays UNVERIFIED |
| The deployer role is an optional narrowing; it does not separate deployment from apply. `plan.sh`, `deploy.sh`, `rollback.sh`, `cleanup.sh` run under the human's broad credentials | Terraform README "Who runs what" | The human credential is broad. The control is the approval record, not IAM |
| Agents: one writer per artifact class, tool lists declared per role, `code-reviewer` has no shell or write tool; the guard's structured-write fence covers only Git metadata, the test marker and the constitution, refuses all `code-reviewer` writes, and confines `factory-engineer`, `test-engineer`, `deploy-engineer` and `service-engineer` to their paths. It is not a general per-owner path fence | `docs/agents.md`; `.claude/agents/*.md`; `policy.py:179-197`; `guard-dangerous.sh:75-77` | The main session can do a specialist's work inline and nothing in the runtime stops it. `docs/agents.md` records this as an open, behaviour-only control |
| Constitutional files cannot be edited by shell or by non-factory agents | `.claude/settings.json` `sandbox.filesystem.denyWrite`; `policy.py:184-189` | Hook enforcement in a fresh session is evidenced by probes, not by this document |
| Budget: AWS-enforced spend limit 20 USD, AWS Budget 25 USD (alert only), code ceiling 20 USD, operating stop near 15 USD | Terraform README "Account facts and cost limits" | AWS enforces only the project spend limit. Billing lags; the gate rule adds a lag reserve (Phase 17 plan) |
| Region locked to us-east-2; `var.region` refuses any other value at plan time | Terraform README | Haiku inference profile routes to us-east-1, us-east-2 and us-west-2 on the Bedrock side (README "Unverified items") |

## 6. Denial of service

Threat: request floods, slow clients, oversized inputs, or runaway cost exhaust the services or the budget.

| Control | Evidence | Gap |
|---|---|---|
| Services have bounded HTTP pools (8 threads, queue 64), bounded call pools (16, queue 32), a body limit (65,536 bytes), URI limit (4,096), question limit (1,000 characters), deadlines (fast 2 s, slow 10 s), retries (max 2) and a circuit breaker (5 failures, 5 s open). Overflow returns a typed error, not a hang | `modules/services/.../ServiceConfig.scala:124-152`; full table in [service limits](service-limits.md) | The limits are per task. There is no per-client rate limit in the code read (`rateLimit` has no match in `modules/services/src/main`) |
| Auto scaling on request count per target (300 per minute) and CPU (60 percent), 1 to 4 tasks | `SESSION2.md` section 3; observed scale 1 to 3 and back in experiment D (`experiments/RESULTS.md` 3.D) | D showed p95 7,990 ms at 48 clients and 14 errors (0.4212 percent); cause of the errors is not in a derived file. Vector retrieval was the slow stage. Stub answer model only |
| Bedrock call cap per process (`bedrock_max_calls` 1 to 10) and a model retry bound | `infra/terraform/environments/course/variables.tf:480-487`; observed in SMOKE2 (eleventh call refused with `cap-exceeded`, `docs/releases/phase-15/smoke-tn.md`) | A cap that also stops legitimate answers (SMOKE2 request 4). The default slow deadline (10 s) is shorter than a Haiku call (13.9 to 17.1 s, P16-34). The smoke overlay `services-smoke.tfvars:26` sets 60000; the default (0) and `services-session.tfvars` do not, so a Haiku deploy without that overlay keeps 10 s |
| The embedding worker handles one message at a time; queue `maxReceiveCount` 3 and a dead-letter queue; Titan on-demand 300,000 tokens a minute | `SESSION2.md` section 3; `docs/agent-runs/phase-14/decisions.md:11` | Worker scale-out not shown in C beyond what `experiments/RESULTS.md` 3.C records |
| Pipeline bounds: per-state timeouts and an execution timeout; EMR idle auto-termination 900 s; scripts stop on wall clock, spend estimate and dead-letter depth | Terraform README "Pipeline bounds"; `scripts/README.md` | A timed-out execution skips the cleanup state; a failed TerminateCluster leaves the cluster; `StopExecution` may not stop the loader task (UNVERIFIED). Rule B is a known limitation |
| No public ingress: no public subnet, IGW or public IP; ALB is internal | Terraform README "Defaults" | Reachability from outside the VPC cannot be tested from this repository; a public endpoint would be a new module and a human approval |
| Export size bound prevents an oversized export from exhausting task memory | `AwsReadModels.scala:37`; export 100.9 MiB in 34 objects (`SESSION2.md` section 10, item 2) | Task memory 4096 MiB is provisional: the heap measure was not run (`SESSION2.md` section 10, item 1) |

## 7. Destructive commands

Threat: an agent or a script deletes data, destroys infrastructure, rewrites history or mutates the cloud outside review.

Guard sources. The refusals below come from `.claude/hooks/policy.py` (rule names are the function and regex names there) and from `.claude/settings.json` (`permissions.deny`, sandbox). The header of `.claude/hooks/guard-dangerous.sh` (lines 1-81, ratified and committed at `5157e0e`) names eight guarded categories with example refused forms and the `policy.py` rule for each; `bash .claude/hooks/guard-dangerous.sh --list-categories` prints the example array. The array decides nothing: `policy.py` stays the enforcer. The hook harness `hooks-guard.sh` was recorded at `RESULT pass=114 fail=0` on the final files (Claude in chat, `docs/agent-runs/phase-17/human-commands.md`); this author did not replay it.

| Category | Typical refused forms | Enforced by | Observed |
|---|---|---|---|
| Terraform apply and destroy | `terraform apply`, `terraform -chdir=x destroy`, `tofu apply` | `policy.py` `DIRECT_DANGER` (first alternative); `settings.json` deny `Bash(terraform apply *)`, `terraform destroy *`, `tofu apply *`, `tofu destroy *`. Only infra-engineer may run `terraform validate`, `plan` and `fmt -check` (`policy.py:149-151`) | Direct probes in `probe_policy.py`; `hooks-guard.sh` `RESULT pass=114 fail=0` (recorded, one refusal case per category, not replayed here); live interception per restart probes |
| Git force push, mirror, ref deletion | `git push --force`, `-f`, `+ref`, `--mirror`, `--delete`, `:ref`, `--prune` | `policy.py` `DIRECT_DANGER` second alternative and the ref-deletion check (`policy.py:100`); `settings.json` deny `git push *--force*`, `-f`, `--mirror` | same |
| Other history rewrite or working-tree changes (`reset`, `rebase`, `clean`, `filter-branch`, `checkout`) | any `git` verb not in the read set | `policy.py` read-set `READ_GIT` plus the default deny at the end of `guard_shell` (line 158). No rule names these verbs; they are refused because they are not allowed | Default-deny reading from code, NOT REPLAYED here |
| Database destructive SQL | `drop database`, `drop table`, `truncate table`, `delete from` | `policy.py` `DIRECT_DANGER` third alternative | direct probes |
| S3 | `aws s3 rm`, `aws s3 rb`, `aws s3api delete-*` | `policy.py` AWS rule (`aws` commands must be exactly three tokens and `describe-*`, `list-*`, `get-caller-identity` or `ls`); `settings.json` deny `aws s3 rm *`, `rb *`, `s3api delete-* *` | direct probes |
| DynamoDB | `aws dynamodb delete-table`, `delete-item` | same AWS rule; deny `aws dynamodb delete-* *` | same |
| Neptune | `aws neptune delete-db-cluster`, `aws neptunedata delete-*`, `cancel-*` | same AWS rule; deny `aws neptune delete-* *`, `neptunedata delete-* *`, `neptunedata cancel-* *` | same |
| Vector index | `aws s3vectors delete-index`, `delete-vector-bucket` | same AWS rule; deny `aws s3vectors delete-* *` | same |
| Other cloud mutation | `aws ecr delete-*`, `aws kms schedule-key-deletion`, `aws emr terminate-*` and `modify-*` | AWS rule; deny entries for each | same |
| Secrets reads | `aws secretsmanager ...`, `aws ssm ...` | `policy.py:107` | same |
| Chaining, pipes, redirection, substitution, shell wrappers | `a && b`, `sh -c`, `python -c`, `eval`, `sudo` | `policy.py:86-94` | same |

| Control | Evidence | Gap |
|---|---|---|
| The deployment, rollback, pipeline and cleanup scripts are not in any agent's allowed script root (infra-engineer may run only `scripts/validation/`), so agents cannot run them | `policy.py:61-64, 154-157`; `CLAUDE.md` "Hook operation" | The human running a script with `!` is outside the hook. The control there is the script design below |
| Each apply-class script needs an approval record bound to the script digest, exact arguments, plan digest, account, Region, cost ceiling and expiry (at most 24 hours), single use | `scripts/README.md` "Approval record" | Digests are printable, so a caller who can write a record can write a new one. The real control is the human signing (stated in the README). Consistency check, not authorization |
| `cleanup.sh` needs `--i-am-the-human`, `--acknowledge-data-loss`, refuses an agent environment, lists resources before deleting, and uses only `terraform destroy` of a saved plan (no wildcard, no `-target`, no `aws s3 rm`) | `scripts/README.md` "Scripts" | `--i-am-the-human` is an agreement, not a security control (README) |
| Stateful destroy or replace needs `STATEFUL_CHANGE_ACKNOWLEDGED=yes` | `plan.sh` lists `STATEFUL_DESTROY_OR_REPLACE`; `deploy.sh` requires the acknowledgement | Changing the S3 Vectors dimension destroys vectors; a guard exists but has a documented gap (Terraform README, M7) |
| Buckets, tables and Neptune have `force_destroy` and deletion protection off by default for the course environment, on only through `cleanup_mode` or `persistent_environment` | Terraform README "Cleanup mode" | Neptune keeps no snapshot in the course environment, so a session-down loses its data by design (reloaded from S3) |
| Live scripts were exercised on AWS only under the human's approvals; no rollback was exercised | `docs/releases/phase-14/README.md`, `docs/releases/phase-15/README.md` | The Phase 15 first secret fill and a service redeploy were done outside the reviewed scripts (deviation 1, accepted P15-45) |

## 8. Provenance tampering

Threat: a record's source or transformation history is altered, or an answer cites evidence that was changed after the fact.

| Control | Evidence | Gap |
|---|---|---|
| Every data transformation records source and transformation provenance; rows carry `sourceRunId`, run id and transform version | `modules/core` contracts; `modules/storage/.../EmbeddingWork.scala:244-273` (`spark-run:<id>`) | Provenance is data in the same stores it describes. It is not signed |
| Export files verified against the manifest (size, SHA-256, rows); manifest SHA-256 is recorded in the start-up summary | `JsonlExportSource.scala:168, 312`; `AwsReadModels.scala:43-48` | Manifest and files share a bucket and writer |
| Run identity pinned: manifest `sourceRunId` must equal `graph_run_id` | `AwsReadModels.scala:127` | Two ids exist (`run-v1-...` slug and `run:v1:...` Spark id) and differ by design. `RUN_ID_MATCH=false` in runs 9 to 11 is expected (`docs/releases/phase-14/README.md`, "Open items"). The graph in Neptune is named for the Spark run, not the pipeline slug |
| Material runtime claims cite an evidence bundle: source ids, graph or retrieval evidence, uncertainty. Evidence and explain endpoints return it | `modules/core` `EvidenceBundle`, `AnswerClaim` (`Contracts.scala:425-489`); `docs/api.md` | Query records, jobs and idempotency are in-process, so `/v1/evidence` and `/v1/explain` work only in the task that served the query (`SESSION2.md` section 1). The evidence bundle table holds the record; its integrity is not hash-chained |
| Idempotency: the same key with a different body is 422 `IDEMPOTENCY_KEY_REUSED` (fingerprint is a SHA-256 of body and mode) | `scripts/README.md` smoke coverage; `QueryService.scala` | A replay is observable only as the same `queryId` |
| Vectors are never overwritten with different content (`ChangedVectorContent`) | see 3 | Deleted keys in Phase 15 are recorded |
| Release evidence: plan digest, script digest and image tags (immutable) are recorded per deploy; `package.sh` writes `SHA256SUMS`; `archive-evidence.sh` writes `MANIFEST.sha256` | `docs/releases/phase-15/README.md`; `scripts/README.md` | `DEPLOY_COMPLETE` is unstamped (Phase 14 open items). `package.sh` checksums: NOT REPLAYED |
| Agent evidence is archived as received with its author identified; a reviewer never approves its own work | `docs/agents.md` boundary rules; `docs/agent-runs/README.md` | Phase 17 `human-gate.md` and `agent-report.md` files are orchestrator-compiled indexes, labelled as such. Git history is the only tamper-evidence for the archive |

## 9. Open items carried to the human

1. HTTP between client and ALB is unencrypted inside the VPC (accepted risk to confirm).
2. Haiku prompt-injection behaviour is not measured separately from the stub: NOT RECORDED.
3. No per-client rate limit in the services code read.
4. The Neptune openCypher condition key behaviour on a live query path: UNVERIFIED.
5. Secret values left in Secrets Manager across sessions: human decision pending (`docs/releases/phase-15/README.md`).
6. The hook header (R03) is ratified and committed at `5157e0e` (`guard-dangerous.sh:1-81`, `--list-categories`). The 114/0 `hooks-guard.sh` run is recorded, not replayed here; native interception in a fresh session stays tracked in the restart probes.
7. Every test and script named here: NOT REPLAYED by this document's author.