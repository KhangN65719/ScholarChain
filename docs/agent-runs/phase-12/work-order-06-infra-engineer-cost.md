# Work order 06: infra-engineer, cost estimate and multi-size scale plan

Owner: infra-engineer. Output: `infra/terraform/COST_ESTIMATE.md` (new file; infra-owned). Do not edit other files except to report conflicts. No sbt. No terraform command is needed. No AWS calls.

Read first: decisions.md D2, D2a, D2b, D3 and D4; pricing-evidence.md (the only price source; do not use memory); report-04 (your Terraform defaults); infra/terraform/README.md.

## Constraints (instructor, quoted in D2)
- At most $25 cumulative for the WHOLE project, including Bedrock, compute, storage and transfer, not enforced by AWS. Alerts at $10, $15 and $20.
- The estimate must show the whole remaining project fitting under $25: Phase 14 (deploy, bounded runs, smoke) and Phase 15 (experiments, elasticity), with tear-down after each run.
- Use the cheapest viable choice per service. For any service whose minimum cost threatens the budget, flag it and propose a documented cheaper alternative. An alternative that drops a course-required service needs human approval, so label it as a proposal.
- Replace the 100k works, 1M edges and 10k chunks targets with a multi-size plan (three sizes). It must fit the budget, state selection criteria, expected record counts and limitations, and show scalability across sizes.
- Grading is on correctness, experiment quality and analysis, not AWS usage.

## Evidence rule
Every rate is marked VERIFIED (cite the page and the quoted label, from pricing-evidence.md) or UNVERIFIED (state the source of the number, for example "agent memory", and a low and high bound). Never present an unverified rate as a fact. Check every conclusion at the HIGH bound. List every missing rate that matters, ranked by how much it changes the total. Do not invent a rate to close a gap.

## Contents
1. Assumptions: region, hours per run, number of runs per phase, data volumes per size (derive from the repo, for example the dataset slice sizes and the pair expansion in modules/hadoop, and say how).
2. A per-service table: hourly rate, idle rate, cost per bounded run, with VERIFIED or UNVERIFIED on every cell. Services: EMR on EC2 (EC2 plus EMR surcharge, Spot task nodes), Neptune (Serverless at 1 NCU; the smallest provisioned instance; the Free Tier trial; and the alternative of not running Neptune), S3 and S3 Vectors (price both 256 and 1024 dimensions), DynamoDB, Bedrock (Haiku 4.5 tokens per bounded query run; Titan V2 embedding tokens per size; the offline stub and hash embedder as the zero-cost fallback), Fargate, VPC (interface endpoints vs NAT for a short run: compare them with numbers, because six interface endpoints at the hourly rate may cost more per hour than one NAT gateway), CloudWatch, Step Functions, SQS, Secrets Manager, ECR.
3. A timeline for Phases 14 and 15: each run, its duration, what is up, what is torn down, a cost per run and a running total against the $10, $15 and $20 alerts.
4. The three-size plan (for example small, medium, large), with the cost of each size and a total.
5. The budget threats: services whose minimum cost threatens the $25, each with the cheaper alternative and the trade-off.
6. A Terraform-defaults review: say which defaults in report-04 the estimate recommends changing (for example, the endpoint mode, instance counts, which services default off). Do not edit the Terraform here. List the changes as proposals for the orchestrator.
7. A section that states how actual AWS expenses will be recorded in Phases 14 and 15 (source: the Billing and Cost Explorer pages read by the human, because agents have no credentials), for the final report.
8. Open items for the human, including the paste-ready ways to close the missing rates.

## Report
Contract fields, as plain text in your final message: the headline total at the LOW and HIGH bound, the three largest cost lines, the threats, the proposed Terraform default changes, and the list of unverified rates.
