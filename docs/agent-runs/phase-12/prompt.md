# Phase 12 prompt

## Scope note (human, 2026-11-09; this overrides the course text where they differ)
- Phase 11 is accepted at fa21c2c.
- Step 1 comes before any Phase 12 infrastructure work: hadoop-engineer fixes the four P-4 `.fail` defects, test-engineer flips the twins, then test-engineer handles P-10 F1 and F2 (F3 to F6 if small). See decisions.md D1 and work orders 01 to 03.
- **Phase 12 is local only.** Write Terraform. Run `terraform fmt`, `terraform validate` and local static checks. No `terraform plan` or `apply` against AWS, no AWS credentials, no AWS calls. The course text asks for "a non-destructive plan" and plan summaries. Those items are deferred to Phase 14 and recorded as open gate items.
- AWS facts to design for: one account from the new AWS sign-up (owned by an AWS Builder ID, Free plan with credits, no root user, IAM users programmatic-only). Region us-east-1. Budgets at $25 and $50 exist. Neptune and EMR need the Paid plan per AWS documentation, so the upgrade is a Phase 14 human decision. Never recommend "activate advanced features" (irreversible, removes the spend limit).
- Design constraints: bounded default capacity, nothing public, no secrets in source.
- Extra deliverables: a written per-service cost estimate (hourly and per bounded run) citing AWS pricing pages. It must show the whole remaining project (Phases 14 and 15, with tear-down after each run) under the instructor's $25 cumulative ceiling, with the cheapest viable choice per service and a flag plus cheaper alternative for any service whose minimum cost threatens the ceiling. Instead of the README minimums (100,000 works, 1,000,000 edges, 10,000 chunks), a multi-size plan (for example three sizes) that fits the budget. See decisions.md D2 for the instructor's constraints, quoted. Bedrock is optional and the hash embedder and offline stub stay the default fallback.
- No new dependency or tool without asking the human. No edits to CLAUDE.md, docs/agents.md or .claude/** (that needs manual mode for factory-engineer). Read-only git. No commit.
- Closing sequence: fresh read-only code-reviewer (Opus) on the Phase 12 gate, at most 2 batched repair rounds with re-review of changes only, then the human runs `sbt clean check` and accepts.

## Course text, Phase 12 (verbatim from /Users/winnguyen/scholarchain-data/course-README.md, lines 1748 to 1871)

## Phase 12: create the AWS infrastructure in Terraform

Purpose:

Define the complete ScholarChain cloud environment as reviewed, reproducible code.

Why:

Manual cloud assembly creates drift, missing security controls, and a deployment that graders cannot reproduce. Terraform plans make intended changes visible before resources are created.

Use this work order:

```text
Use the infra-engineer agent to create the ScholarChain AWS infrastructure under infra/terraform and the approved operational scripts under scripts.

Required Terraform modules or equivalent organization:

1. Foundation
- Provider versions pinned.
- Remote state design documented.
- Required tags.
- Budget and cost-allocation tags where course accounts allow.

2. Network
- VPC in at least two Availability Zones.
- Public subnets only for approved ingress components.
- Private application and data subnets.
- Route tables, NAT or controlled egress, and VPC endpoints where justified.
- Security groups chained by service role rather than broad CIDR access.

3. Identity and encryption
- Least-privilege roles for EMR, ECS tasks, Step Functions, deployment execution, loaders, and runtime readers.
- Separate runtime and deployment permissions.
- KMS keys or approved AWS-managed encryption choices.
- Secrets Manager records referenced by ARN, never secret values in Terraform source or plans.

4. Data lake
- Raw, curated, graph, RAG, artifact, log, and report S3 buckets or prefixes.
- Versioning where needed.
- Encryption, public-access blocking, lifecycle rules, and deletion safeguards.
- Glue catalog resources when used.

5. EMR
- Hadoop and Spark applications.
- Managed scaling or instance-fleet policy.
- Log and bootstrap configuration.
- Bounded default capacity.
- Spot task capacity only when failure behavior is handled.

6. DynamoDB
- Required tables and indexes.
- On-demand or autoscaled provisioned capacity with justification.
- Point-in-time recovery where appropriate.

7. Neptune
- Cluster or serverless configuration.
- Subnet and parameter groups.
- Encryption, backups, deletion protection for non-ephemeral environments.
- Loader role and read-only runtime role.

8. S3 Vectors
- Vector bucket resources.
- Index resources for every required index.
- Encryption, tags, and access policies.
- Use current Terraform provider resources supported at implementation time and pin the provider version.

9. Optional OpenSearch
- Deploy only when used by an implemented and evaluated hot or hybrid tier.
- Define access, encryption, logging, and capacity policy.

10. Runtime compute
- ECR repositories with immutable Git SHA tags and scan-on-push.
- ECS cluster and Fargate task definitions.
- Services for query, graph retrieval, vector retrieval, ranking, answer synthesis, verification, and workers, or a justified consolidation that preserves independent scaling where needed.
- Internal service discovery and approved ingress.

11. Workflow and messaging
- Step Functions state machines.
- SQS queues and dead-letter queues.
- EventBridge rules when used.

12. Observability
- CloudWatch log groups.
- Dashboards and alarms.
- Metrics for EMR, ECS, SQS, Neptune, DynamoDB, S3 Vectors clients, API latency, LLM usage, and errors.

13. Cost and safety
- Bounded defaults.
- Environment-specific variable files without secrets.
- Deletion protection for stateful resources in persistent environments.
- Explicit cleanup mode for the course environment.

Required scripts:
- plan.sh
- deploy.sh
- rollback.sh
- smoke-test.sh
- run-bounded-pipeline.sh
- cleanup.sh

Script requirements:
- bash strict mode.
- No interactive prompts inside agent-run scripts.
- Refuse dirty source trees before deployment.
- Tag images by Git SHA.
- Print greppable step markers.
- Verify the deployed revision.
- Fail loudly with evidence.
- Never hide terraform plan output.
- Cleanup requires explicit human invocation and lists affected resources before deletion.

Run terraform fmt -check, terraform validate, a non-destructive plan, shell syntax checks, and security scans selected by the team.
Present the complete plan summary, add/change/destroy counts, estimated high-cost resources, stateful replacements, IAM changes, and required human actions. Do not apply.
```

Gate:

- Terraform plan contains no unexplained destroy or replacement of stateful resources.
- IAM policies are scoped to actual resources and actions.
- Buckets and databases are not public.
- Default capacity is bounded.
- Secrets do not appear in source or plan output.
- Human reviews and signs the plan before any apply.
- Code reviewer approves the infrastructure diff.
