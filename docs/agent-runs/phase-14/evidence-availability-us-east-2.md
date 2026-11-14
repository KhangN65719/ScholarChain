# us-east-2 availability evidence

Collected by the orchestrator on 2026-11-13 from the AWS documentation MCP server (read-only, public docs). Quoted facts only; unconfirmed items are marked.

| Item | Result | Source |
|---|---|---|
| S3 Vectors in us-east-2 | CONFIRMED. Endpoint `s3vectors.us-east-2.api.aws`. | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html |
| Neptune endpoint in us-east-2 | CONFIRMED (`rds.us-east-2.amazonaws.com`). | https://docs.aws.amazon.com/general/latest/gr/neptune.html |
| Neptune `db.t4g.medium` in us-east-2 | NOT CONFIRMED per Region. Docs list t3/t4g as medium-only and Free Tier eligible, with no per-Region table found. Real check is the first plan/apply (`describe-orderable-db-instance-options`, human `!`). | https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html |
| EMR endpoint in us-east-2 | CONFIRMED (`elasticmapreduce.us-east-2.amazonaws.com`). Instance types: a per-Region list exists for us-east-2; infra-engineer must check the instance types in Terraform against it. | https://docs.aws.amazon.com/general/latest/gr/emr.html ; https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-supported-instance-types.html |
| Titan Text Embeddings V2 in us-east-2 | CONFIRMED, In-Region supported. | https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html |
| Claude Haiku 4.5 in us-east-2 | CONFIRMED only through cross-Region profiles. In-Region is `not-supported` on `bedrock-runtime`; Geo (US) and Global are supported. | https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html |
| Haiku 4.5 inference profile IDs | `us.anthropic.claude-haiku-4-5-20251001-v1:0` (Geo US; source us-east-2 routes to us-east-1, us-east-2, us-west-2). `global.anthropic.claude-haiku-4-5-20251001-v1:0`. | same page |
| Bulk loader S3 path | Neptune loader needs an S3 gateway VPC endpoint and an IAM role (L1 stays open until a real load). | https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-vpc.html |
| Neptune and EMR on the new-experience account | Both are in the "Paid Plan" service list (not Free Tier). OK because D1 says Paid plan. | https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html |
| Other services used | Listed as supported: DynamoDB, S3, ECS, ECR, SQS, Step Functions, Secrets Manager, KMS, CloudWatch, CloudTrail, Budgets, PrivateLink, VPC, EventBridge, Bedrock. | same page |
| Neptune Free Tier (P3) | PARTIAL. Docs say t3/t4g.medium are eligible for "the first 750 instance hours" for new customers in a standalone account. Whether a Paid-plan account with credits gets it applied is unverified from docs; the offer page is on aws.amazon.com and was not read. Real answer is the Billing line after the first Neptune hour. | instance-types.html (above) |

## Risks found (for the human)

- R1: The sign-up page says "Multi-Region or cross-Region features are not supported for any services" in the new experience. Haiku 4.5 in us-east-2 works only through a cross-Region inference profile. Your quota check suggests it is usable, but an actual invoke has not been observed. A one-call test (human `!`, a few tokens) before any session that enables Bedrock is cheap insurance. If it fails, the options are a different model that is In-Region in us-east-2 (a human decision) or no LLM synthesis in the deployed demo.
- R2: S3 Vectors is not named on the supported-services page (it is an S3 feature, and S3 is listed). Unconfirmed until the first `CreateVectorBucket`.
- R3: Interface endpoints cost per hour and per AZ regardless of use (P4 stays open for after the S run).
