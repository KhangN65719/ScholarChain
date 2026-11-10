# Report 11: infra-engineer (work order 11, repair round 1), as received (condensed)

STATUS: COMPLETE (edits only). Nothing was run: no sbt, no terraform, no AWS call, tests/ untouched. grep and find were refused, so stale text was sought by reading files; a missed stale string is possible.

## Files changed
infra/terraform/modules/identity/{main,variables}.tf; network/variables.tf; workflow/variables.tf; emr/{main,variables}.tf; s3vectors/{main,variables}.tf; neptune/variables.tf; environments/course/{main,variables,outputs}.tf; experiment-size-{s,m,l}.tfvars; README.md; COST_ESTIMATE.md (full rewrite); scripts/{lib,deploy,plan,rollback,cleanup,smoke-test,run-bounded-pipeline}.sh and scripts/README.md. New: environments/course/session-down.tfvars.

## What proves what
Human `terraform fmt -check -recursive` and `validate` (HCL syntax, types, references; alignment by hand). Human `bash -n` and shellcheck (script syntax only). Review only for behavior, IAM scope and every number in COST_ESTIMATE.md; plan-dependent behavior waits for the Phase 14 plan.

## Per finding
- H1 fixed: ECS rule now `StepFunctionsGetEventsForECSTaskRule`; README states three rules granted, four `.sync` integrations used.
- H2 fixed: `s3vectors` and `sqs` added to both endpoint lists: 8 interface endpoints by default (9 with Bedrock); default egress stays `endpoints`; README egress text corrected.
- M1 fixed: `max_run_seconds` default 10800, range 1800 to 10800, plus a step-timeout cap; wired as `pipeline_max_run_seconds` (S 5400, M 7200, L 10800).
- M2 fixed: full rewrite (headline below).
- M3 fixed: `session-down.tfvars` and a documented `plan.sh --extra-var-file` then `deploy.sh` sequence; no new script capability.
- M4 fixed: `dir_digest` of each build context goes into the digested arguments with image tag and platform; the platform comes from `CPU_ARCHITECTURE` in plan metadata, re-checked against a new `cpu_architecture` output; false comment corrected.
- M5 fixed: record window capped at 24 hours; `consume_approval` writes a single-use marker; README no longer claims a caller must edit the script.
- M6 fixed, check UNVERIFIED: the S3 Vectors check fails closed; `DIRTY` records the real tree state.
- M7 fixed: guard (a `terraform_data` record, an index `precondition`, `acknowledge_vector_loss` default false); hazard documented in the variable, README and estimate; behavior UNVERIFIED until a plan.
- M8 fixed: README "Who runs what"; the false deployer-role claim withdrawn in identity/main.tf, README and scripts/README.md.
- M9 fixed: smoke-test header and README list options A to D as a Phase 14 decision; example URLs now private IPs.
- L1 not fixed, flagged: the bulk-loader requirement was not in the verified facts, so no rule added (Phase 14).
- L2 fixed: EMR cap must cover 1 + core + Spot task nodes, at most 7; validated in module and environment.
- L3 partly fixed: `emr_cluster_arn` stays `cluster/*` (the ID does not exist until RunJobFlow), reason documented; `deployer_require_mfa` (default false) added with the reason it is off.
- L4 fixed: Bedrock ARN variables reject `*`, non-Bedrock ARNs and an empty list while Bedrock is on.
- L5 fixed: smoke-test.sh refuses `--out-dir` under `SC_REPO_ROOT` or with `.` or `..` segments.
- L6, L7 fixed: loader priced at 0.5 vCPU and 1 GB; burn rates now include endpoints and Neptune: 0.60 (S), 0.62 (M), 0.65 (L), about 2.02 (master plus 4 core); updated in estimate, scripts/README.md and run-bounded-pipeline.sh.
- L8 fixed: validate status, "unverified until work order 06", alarm count (3, or 4 only in Serverless), Neptune `skip_final_snapshot` description, README `force_destroy` text, smoke-test header (not side-effect free).

## Cost headline (endpoint mode, 8 endpoints (9 with Bedrock), provisioned Neptune, Cloud Map off, Fargate rebuilt)
| Case | LOW | HIGH |
|---|---|---|
| Whole project S+M+L, defaults (endpoints) | $9.62 | $36.44 |
| S+M only | $6.94 | $23.71 |
| L increment | $2.64 | $12.77 |
| NAT alternative (not chosen), whole project | $8.92 | $34.38 |
| NAT alternative, S+M only | $6.34 | $21.70 |
| Endpoints minus NAT, whole project | +$0.70 | +$2.06 |
S+M at HIGH fits under $25 with $1.29 margin, which one forgotten day of endpoints ($2.16 to $3.24 at HIGH) would remove; if the old NAT rows held a data-processing share, S+M HIGH is about $0.68 lower. Whole project at HIGH does not fit ($11.44 over $25); at LOW it leaves $15.38. Tier-L gate ($23.5 line): L passes only if spend before L is at most $10.73 at HIGH or $20.86 at LOW; at HIGH, spend after S+M is $23.71, so L would not run. Cloud Map off saves $0.50 to $1.00; the Fargate rebuild adds $0.25 and $0.08; Serverless Neptune would add $0.68 and $4.40. Limits: the per-run timeline carries old Fargate shares and old run costs; the service table is the careful total; NAT is a comparison column only.

## UNVERIFIED introduced or kept
EMR `RunJobFlow` and `AddJobFlowSteps` EventBridge rule names (carried from the earlier draft, not re-read); whether `terminateCluster.sync` uses an EMR managed rule (no name invented; docs show a polling fallback); the SQS endpoint name (not read from docs); whether `s3vectors` and `sqs` are offered in the single endpoint AZ; whether the S3 Vectors private DNS name matches what the SDK resolves; how `ignore_changes` on `terraform_data` and a precondition on a plan-time-unknown value behave; `aws s3vectors get-vector-bucket` and its not-found text (the check never reports "gone" on an error); the Neptune bulk-loader S3 prefix-list egress rule (L1); most cost rates (U1 to U13; the "consistent with V4" claim removed); whether a timed-out step also stops the EMR step; ECS retry behavior for a first apply with desired count above 0; `iso_epoch` (tries `date -d`, then `date -j -f`); that the session-down overlay produces exactly the changes the README lists.

## What the human must run
`terraform fmt -check -recursive infra/terraform`; `terraform -chdir=infra/terraform/environments/course validate`; `init -upgrade` not needed (no provider or version changed; `terraform_data` is built in); `bash -n scripts/lib.sh scripts/plan.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke-test.sh scripts/run-bounded-pipeline.sh scripts/cleanup.sh`, and shellcheck if installed. Likeliest validate failures: cross-variable validations, `startswith` and `strcontains`, the `terraform_data` record with its `lifecycle` precondition, the `dynamic "condition"` block.

## Risks
A failed `deploy.sh` after the marker is written needs a new plan and a new record (deliberately conservative). `emr_core_instance_count = 4` with the default cap of 3 is now rejected instead of silently raised (the elasticity overlay sets cap 5). The smoke test cannot run from anywhere until Phase 14 picks a network path. `deploy.sh` now requires `CPU_ARCHITECTURE` in plan metadata, so earlier plans must be remade.
Blocked-on: nothing. Next gate: human fmt -check, validate, bash -n, shellcheck; then an independent re-review of the changed items.
