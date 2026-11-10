# Pricing evidence for work order 06 (fetched 2026-11-09 by two web-fetch agents; condensed)

Scope: public AWS pricing pages, us-east-1 unless marked. Many pages return table headers but not table values (JavaScript-rendered), so most numbers below come from the pages' worked examples. Every item is VERIFIED (read verbatim in page text) or NOT FOUND. Nothing was filled from memory or estimated. Work order 06 must keep this distinction.

## VERIFIED (page text, us-east-1 unless marked)
- Neptune (https://aws.amazon.com/neptune/pricing/): billed per second in NCUs, "Starting capacity" 1 NCU. Example 1: db.r5.large on demand $0.348 an hour; storage $0.10 per GB-month; I/O $0.20 per million request increments; backup storage up to 100% of cluster storage free (example implies $0.021 per GB beyond); T3/T4g CPU credits $0.15 per vCPU-hour (unlimited mode); Free Tier: 750 hours of db.t3.medium or db.t4g.medium, 10 million I/O, 1 GB storage, 1 GB backup, for 30 days. Data transfer in free, out up to 100 GB a month free (Example 1).
- EMR (https://aws.amazon.com/emr/pricing/): Example for c4.2xlarge: EMR charge $0.105 an hour (US-East-1). EMR Serverless Example 3 (region and architecture not stated): $0.052624 per vCPU-hour, $0.0057785 per GB-hour. Serverless billing: rounded up to the second, 1-minute minimum.
- EC2 Spot (https://aws.amazon.com/ec2/spot/pricing/): "up to 90% off compared to On-Demand" (no region or date).
- S3 Vectors (https://aws.amazon.com/s3/pricing/, examples labeled US East (N. Virginia)): storage $0.06 per GB-month; PUT $0.20 per GB (128 KB minimum per PUT); queries $2.50 per million; data processed $0.004 per TB for the first 100K vectors, $0.002 per TB for 100K to 10M, $0.0004 per TB above 10M; data returned $0.01 per GB (500 KB free per query).
- Fargate (https://aws.amazon.com/fargate/pricing/, US East examples): Linux/x86 $0.000011244 per vCPU-second and $0.000001235 per GB-second; Linux/ARM $0.0000089944 per vCPU-second and $0.0000009889 per GB-second; per second, 1-minute minimum. Per-hour figures are the fetcher's arithmetic (x86: $0.0404784 per vCPU-hour, $0.004446 per GB-hour; ARM: $0.03237984, $0.00356004).
- VPC (https://aws.amazon.com/vpc/pricing/): gateway endpoints have no hourly or data-processing charge. NAT example is US East (Ohio), not us-east-1: $0.045 an hour and $0.045 per GB processed.
- CloudWatch (https://aws.amazon.com/cloudwatch/pricing/): standard-resolution alarm $0.10 per month; free tier 3 dashboards (up to 50 metrics each).
- Step Functions (https://aws.amazon.com/step-functions/pricing/): Standard $0.000025 per state transition (US East N. Virginia); free tier 4,000 transitions a month.
- SQS (https://aws.amazon.com/sqs/pricing/): first 1 million requests a month free.
- Secrets Manager (https://aws.amazon.com/secrets-manager/pricing/): $0.40 per secret per month and $0.05 per 10,000 API calls (from Example 1).
- KMS (https://aws.amazon.com/kms/pricing/): $1 per customer-managed key per month, 20,000 free requests a month.
- Bedrock batch (https://aws.amazon.com/bedrock/pricing/): batch inference at 50% of on-demand for select models; Claude Haiku 4.5 and Titan not confirmed as eligible.

## NOT FOUND on the fetched pages
- Bedrock: Claude Haiku 4.5 on-demand input and output price; cross-region and global inference-profile price difference; Titan Text Embeddings V2 price.
- Neptune: Serverless price per NCU-hour; prices for db.t4g.medium, db.t3.medium, db.r6g.large (only db.r5.large in an example).
- EMR on EC2 per-instance surcharges for m5.xlarge, m5.large, m6g.xlarge, c5.xlarge; EMR Serverless rates labeled with region and architecture; EMR Serverless storage rate.
- EC2 on-demand prices for m5.xlarge, m5.large, m6g.large, m6g.xlarge, c5.xlarge; any current Spot price.
- Standard S3: storage per GB-month, PUT and GET request prices, data transfer out.
- DynamoDB on-demand table values (summary-only, unverified: $0.625 per million writes, $0.125 per million reads, $0.25 per GB-month, first 25 GB free).
- VPC: NAT gateway us-east-1 hourly and per-GB rates; interface VPC endpoint hourly and per-GB rates.
- CloudWatch: dashboard price beyond the free tier; standard log ingestion per GB (verbatim); log storage rate. Step Functions per-1,000 figure (arithmetic from the per-transition rate: $0.025). SQS price per million requests beyond the free tier.

## Consequence
The gaps include the three largest cost lines (Neptune NCU-hour, EMR and EC2 instance-hours, Bedrock tokens). Work order 06 may use an unverified figure only if the figure is marked UNVERIFIED, a stated assumption and a low and high bound are shown, and the conclusion is checked at the high bound. The human has paste-ready options to close the gaps (see the orchestrator's chat report).
