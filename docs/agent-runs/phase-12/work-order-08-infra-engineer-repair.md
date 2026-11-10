# Work order 08: infra-engineer, validate repair and cost-driven default changes (one batched round)

Owner: infra-engineer. Scope: `infra/terraform/**` and `scripts/**`. Do not edit COST_ESTIMATE.md (it is under review as written). No sbt. No terraform commands (the guard refuses them for you; the human runs fmt, init and validate). No AWS calls, no credentials.

Read first: decisions.md (D2c, D5), report-04, report-06, the human's validate output below, and COST_ESTIMATE.md section 6.

## Evidence: the human's `terraform validate` output (2026-11-09)
`terraform init -backend=false` succeeded (hashicorp/aws v6.20.0 installed). `terraform fmt -check -recursive infra/terraform` printed nothing. `terraform validate` failed with exactly two errors:
- modules/s3vectors/main.tf line 26: "The provider hashicorp/aws does not support resource type aws_s3vectors_vector_bucket".
- modules/s3vectors/main.tf line 35: same for aws_s3vectors_index.

## Fix 1: provider pin (decision D5)
Provider v6.20.0 lacks the S3 Vectors resources. Per the provider changelog (https://raw.githubusercontent.com/hashicorp/terraform-provider-aws/main/CHANGELOG.md): the resources first appear in 6.24.0 (2025-12-02); 6.26.0 (2025-12-10) adds the `encryption_configuration` and `metadata_configuration` blocks to `aws_s3vectors_index`; no later entry changes them. Pin the AWS provider exactly to `= 6.26.0` (the smallest version with every argument you use) in every place the version is declared, and state its release date (2025-12-10) in the README. Check every argument in modules/s3vectors against this schema (from the provider docs on GitHub main, not yet verified against 6.26.0):
- `aws_s3vectors_vector_bucket`: required `vector_bucket_name`; optional `encryption_configuration { sse_type = "AES256" | "aws:kms", kms_key_arn }`, `force_destroy` (default false), `region`, `tags`. Attributes: `vector_bucket_arn`, `creation_time`, `tags_all`.
- `aws_s3vectors_index`: required `index_name`, `vector_bucket_name`, `data_type` (`float32`), `dimension`, `distance_metric` (`cosine` or `euclidean`); optional `encryption_configuration`, `metadata_configuration { non_filterable_metadata_keys }`, `region`, `tags`. Attributes: `index_arn`, `creation_time`, `tags_all`.
- `aws_s3vectors_vector_bucket_policy`: required `policy`, `vector_bucket_arn`.
Fix any argument or attribute reference that does not match (for example outputs that use ARNs, and the identity module's index ARN usage). Do not invent arguments. Keep the vector `dimension` variable. The hash embedder's 384 stays an allowed value.

## Fix 2: cost-driven defaults (decision D2c)
1. Neptune: add `neptune_mode` (`provisioned` default, `serverless`). Provisioned uses `db.t4g.medium`, one instance, no replica. Serverless max capacity 2 NCU (was 2.5), min 1. Neptune stays optional behind its existing enable variable. Deletion protection and the final snapshot still follow `persistent_environment`. Update the alarm that referenced Serverless maximum capacity so it only exists in serverless mode.
2. Egress: keep `endpoints` as the default (no change). Keep `nat` and `none` as options.
3. Cloud Map: default off, behind a variable. Where the services need discovery, keep the dependency correct when it is off.
4. Budget alerts: make the total limit and the three alert thresholds variables with defaults 25, 10, 15 and 20 (USD). Keep the budget resource optional and off.
5. EMR: give the managed scaling cap its own variable with a validation range, default unchanged (3) in `course.tfvars`; add a documented larger-cluster example for the Phase 15 elasticity run (master plus 4 core nodes) in a separate example tfvars file. Do not raise the default.
6. Add per-experiment example tfvars files (sizes S, M, L) under environments/course with sensible instance counts. Values only, no secrets. Name them in the README.

## Fix 3: scripts
Align the provisional `--size` caps and burn values in `scripts/run-bounded-pipeline.sh` and `scripts/README.md` with the S, M and L plan in COST_ESTIMATE.md (decision D2c says the sizes are proposed, so keep them documented as provisional). Do not weaken any refusal.

## Procedure
1. List which check proves each item. `terraform validate` (human-run) proves syntax and references only. Say which of your changes can only be proven by validate, and which only by review.
2. Make the edits. Because you cannot run terraform, re-read every changed block against the schema above. Keep HCL formatted the way `terraform fmt` would (two-space indent, aligned `=` in consecutive attribute lines). The human's `fmt -check` was clean before this round, and your edits must not break it.
3. Update infra/terraform/README.md: the provider pin and date, the idle-cost table (Neptune row for both modes, Cloud Map off), and the variable list.

## Report (plain text in your final message)
Contract fields; a table of every changed file and block; the exact schema match per S3 Vectors argument; which edits need the human's `init -upgrade` and `validate`; anything blocked or still unverified.
