# Report 06: infra-engineer (work order 06, cost estimate and size plan), as received (condensed)

STATUS: COMPLETE (estimate only). Output: infra/terraform/COST_ESTIMATE.md (no other file touched). Only command: `ls` on datasets/slice. No sbt, terraform or AWS call; the guard refused cat, grep and python, so file tools were used.

## Headline total
| Case | LOW | HIGH (all upper bounds at once) |
|---|---|---|
| Plan R (recommended), tiers S+M+L | $9.2 | $35.4 (timeline allocation $34.7) |
| Plan R, tiers S+M only | about $6.8 | about $22.7 |
| Plan A (Terraform defaults, Neptune Serverless on) | $10.4 | $42.5 |
Plan R: Neptune db.t4g.medium, NAT egress, 40 Bedrock queries. Plan A: interface endpoints, 60 queries. LOW is the remembered-rate case, not a verified floor.
Conclusion at HIGH: the whole project with tier L does not provably fit under $25. S and M fit with about $2.3 margin. So L runs last behind a gate: the human reads actual spend, extrapolates L from measured M costs (x4, x1.5) and proceeds only if the total stays at or under $23.5 (at HIGH rates actual spend before L must be at most about $11). Timeline at HIGH crosses $10 at run 15.1, $15 at run 15.3 and $20 at run 15.4; at LOW no alert fires.

## Three sizes (proposed)
| | S | M | L |
|---|---|---|---|
| Works | 5,000 | 25,000 | 100,000 |
| Distinct edges | 25,000 | 125,000 | 500,000 |
| Chunks | 1,000 | 5,000 | 20,000 |
Nested deterministic prefixes of one hash-ordered sample. L equals the old 100k-works target; its edge count is half the old 1M target at an assumed 5 edges per work (S measures real density). 4 KB per work is from the slice files; pairs n(n-1)/2 is from `TeamExpansion`. Pairs per work, edges per work, vectors per chunk and items per edge are assumptions; Vectors.scala and the storage item model were not read.

## Largest cost lines
Plan R HIGH: Neptune $5.40, EC2 plus EMR $5.39, DynamoDB writes $4.40. Plan A HIGH: Neptune Serverless $9.80, EC2 plus EMR $5.39, interface endpoints $4.40. Plan R LOW: EC2 plus EMR $1.36, Secrets Manager $1.20, DynamoDB $1.19 (Fargate $1.33 close).

## Budget threats
1. A resource left running (Neptune Serverless alone $24 to $42 a week); mitigation is teardown after each session plus a human check.
2. Neptune Serverless 1 NCU minimum, no scale to zero. Alternatives: provisioned t4g.medium, the Free Tier trial. Dropping Neptune is a proposal needing human approval (course-required service).
3. DynamoDB writes for tier L, about $0.81 to $2.92.
4. Haiku tokens, $0.009 to $0.072 a query; real queries capped at 40, load tests use the offline stub.
5. Interface endpoints cost more per hour than NAT ($0.06 to $0.105 vs $0.05 to $0.062); difference over the project only $0.33 to $1.24.
6. S3 Vectors PUT 128 KB minimum adds up to $1.33 if unbatched; batch at least 26 vectors per PUT.
7. Billing lag up to 24 hours (from memory) and unknown credit coverage for Anthropic models.

## Proposed Terraform default changes (nothing edited)
Add `neptune_mode` (serverless or provisioned) with db.t4g.medium; lower the Serverless maximum from 2.5 to 2 NCU. Egress `nat` for run sessions only if the human accepts an outbound path; else keep `endpoints`. Cloud Map default off (saves $0.50 to $1.00). Alert thresholds as variables ($25 limit; alerts $10, $15, $20). EMR scaling cap its own variable (the elasticity run needs master plus 4 core nodes; default cap is 3). Per-experiment tfvars. Ask storage-engineer to batch S3 Vectors writes.

## Unverified rates ranked by swing at HIGH
1. Neptune t4g.medium and Serverless NCU-hour, I/O volumes, Free Tier eligibility and start ($4.5 and $8.4). 2. EC2 plus EMR for m5.xlarge (about $4.0). 3. DynamoDB on-demand ($3.2; if the remembered rate is stale and doubled, HIGH rises $4.4 more). 4. Haiku 4.5 input and output (about $2.5). 5. NAT in us-east-1, public IPv4, interface endpoint (about $2.0). 6. Titan V2 ($1.8). 7. CloudWatch log ingestion ($1.7) and S3 Standard ($1.5). Also unverified: Cloud Map, ECR, EBS, S3 Vectors vector sizes. Run hours, pair counts and I/O counts are assumptions.

## Manual actions
Read Billing and Cost Explorer before and after each run (section 7). Close rates with the public price-list `curl` commands in section 8 (offer-code names and URL pattern are from memory, unconfirmed) or paste Bedrock pricing rows from a browser. Check Service Quotas for On-Demand Standard vCPU (master plus 4 m5.xlarge core is 20 vCPUs). Decide the seven items at the end of section 8.
Next gate (agent's words): independent review of COST_ESTIMATE.md, then the human decisions.
