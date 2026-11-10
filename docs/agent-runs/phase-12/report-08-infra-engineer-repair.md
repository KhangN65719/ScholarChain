# Report 08: infra-engineer (work order 08, validate repair and cost-driven defaults), as received (condensed)

STATUS: COMPLETE (edits only). Nothing was run. fmt, `init -upgrade` and validate still need the human. No sbt, terraform, AWS or credentials. Bash grep and ls-style checks were refused or unavailable, so file tools were used.

## What proves what
- Provider pin, S3 Vectors arguments, new dynamic and conditional blocks, ARN attribute references, new variables and validation expressions: the human's `init -upgrade` and `validate` (syntax, types, references only).
- fmt alignment (aligned by hand): the human's `fmt -check`.
- IAM scope unchanged (vector ARNs now come from provider attributes): review only.
- Neptune mode behaviour, serverless-only alarm, Cloud Map off with correct dependencies, scaling cap, size caps: review; only a Phase 14 plan shows resource counts.
- Script caps and example arithmetic: review; `bash -n` is human-run.

## Files changed
- environments/course/versions.tf: provider `= 6.26.0` (only place the version is declared).
- modules/s3vectors/outputs.tf: new `bucket_arn` (from `vector_bucket_arn`) and `index_arns` (map, from `index_arn`); old outputs kept.
- modules/identity/variables.tf and main.tf: inputs `vector_bucket_name` and `vector_index_names` replaced by `vector_bucket_arn` and `vector_index_arns`; the two hand-built S3 Vectors ARN locals removed; QueryVectors, WriteVectors and ListIndexes statements use the variables. Granted actions and resource set are meant to be identical.
- environments/course/main.tf: passes `values(module.s3vectors.index_arns)` and the bucket ARN; adds `mode`, `instance_class`, `managed_scaling_max_units`, `enable_service_discovery`, `neptune_mode`.
- modules/neptune: `serverless` local; scaling block dynamic (serverless only); instance class `db.serverless` or `var.instance_class`; `mode` default provisioned; `instance_class` default db.t4g.medium; `max_ncu` default 2 (was 2.5), range 1 to 4; `mode` output. Deletion protection and final snapshot unchanged.
- modules/observability: new `neptune_mode`; at-max-capacity alarm only when Neptune is enabled and serverless; widget shows capacity (serverless) or CPU (provisioned); `neptune_max_ncu` default 2.
- modules/runtime: `enable_service_discovery` default false; namespace uses `count`; service resource `for_each` empty when off; ECS `service_registries` dynamic; output empty map when off.
- modules/emr/variables.tf: core nodes validated 0 to 4 (was 0 to 2); scaling cap validated 1 to 6, default 3.
- environments/course/variables.tf: new `neptune_mode`, `neptune_instance_class`, `enable_service_discovery`, `emr_managed_scaling_max_units`; `neptune_max_ncu` default 2 (range 1 to 4); `emr_core_instance_count` 0 to 4; range checks on `budget_total_usd` and `budget_alert_thresholds_usd`.
- environments/course/course.tfvars: new variables at defaults; cap still 3; `egress_mode` still `endpoints`.
- New: experiment-size-s.tfvars, -m, -l (Neptune on, provisioned; 0, 1, 2 service tasks; one EMR node each), elasticity-example.tfvars (master plus 4 core, cap 5; default not raised).
- infra/terraform/README.md: pin 6.26.0 dated 2025-12-10 with the 6.24.0 and 6.26.0 changelog facts; idle table (Neptune row per mode, Cloud Map off); variable and tfvars tables; init -upgrade and verification status.
- scripts/run-bounded-pipeline.sh and scripts/README.md: `--size` caps small 60, medium 90, large 150 minutes (were 30, 60, 120), still provisional; no refusal weakened. Basis: the estimate's HIGH pipeline node-hours on one node rounded up (S 1.0 h, M 1.4 h, L 2.4 h); reference burn 0.41 USD an hour for one node plus loader, 1.81 for master plus 4 core, from unverified HIGH rates. Example projects 0.41 USD.
COST_ESTIMATE.md not touched.

## S3 Vectors schema match (checked against the work-order schema from provider docs on `main`, not the 6.26.0 tag)
vector_bucket: `vector_bucket_name`, `force_destroy`, `encryption_configuration { sse_type = "AES256" }` match; `region` and `tags` unset (default_tags). index: `index_name`, `vector_bucket_name`, `data_type = "float32"`, `dimension` (256, 384, 512, 1024), `distance_metric` (cosine or euclidean), `metadata_configuration { non_filterable_metadata_keys }` (needs 6.26.0) match; index `encryption_configuration` not set (inherits bucket). Attributes `vector_bucket_arn` and `index_arn` feed IAM. `aws_s3vectors_vector_bucket_policy` not used (IAM identity policies only).

## Needs the human
`terraform init -upgrade -backend=false` (lock file records 6.20.0; commit the regenerated `.terraform.lock.hcl`), then `validate` and `fmt -check`. Highest first-run failure risk: the `for_each` filter in modules/runtime/main.tf; dynamic blocks (Neptune scaling, ECS service_registries); cross-variable validation in environments/course/variables.tf (needs Terraform 1.9 or later; pin is `>= 1.10.0`, installed version unconfirmed); hand-aligned `=` signs.

## Assumptions and risks
- Schema from `main` docs, not the 6.26.0 tag; validate settles it.
- The agent could not search the repo, so other files (lib.sh, deploy.sh, rollback.sh, cleanup.sh, smoke-test.sh, other .tf) were not checked for the string 6.20.0 or the old S3 Vectors and Neptune names. Read: versions.tf, README.md, plan.sh.
- `db.t4g.medium` orderable for engine 1.4.5.1 is unverified.
- Budget variables and `create_budgets = false` already existed with correct defaults; only range checks added.
- Raising the core-node limit to 4 and the scaling cap to 6 widens allowed capacity; defaults, and so the default plan, unchanged.
- Per-experiment service counts (0, 1, 2) and the 60, 90, 150 minute caps are the agent's proposal on top of the proposed sizes (D2c).
Blocked on: nothing. Next gate: human fmt, init -upgrade, validate; then independent review of the identity change and the Neptune and runtime blocks.
