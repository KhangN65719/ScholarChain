# Work order 11: infra-engineer, repair round 1 (all review findings for your area, batched)

Owner: infra-engineer. Scope: `infra/terraform/**` (including COST_ESTIMATE.md, which this round updates) and `scripts/**`. No sbt, no terraform commands (the human runs fmt, init and validate), no AWS calls, no credentials. Do not weaken any existing refusal or safeguard.

Read first: review-round-1.md (the findings, with file and line), decisions.md D2c, D6, pricing-evidence.md, terraform-checks.md, report-04, report-05, report-06, report-08.

## Verified AWS facts you may use (decision D6; cite the URLs in the README)
- ECS .sync rule: `StepFunctionsGetEventsForECSTaskRule` (plural "Events"), with `events:PutTargets`, `events:PutRule`, `events:DescribeRule`; https://docs.aws.amazon.com/step-functions/latest/dg/connect-ecs.html
- EMR `terminateCluster` documented policy: `elasticmapreduce:TerminateJobFlows` and `DescribeCluster`, no EventBridge actions; missing events permissions on a `.sync` task cause a delay, not a denial; https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html and https://docs.aws.amazon.com/step-functions/latest/dg/service-integration-iam-templates.html. Whether `terminateCluster.sync` also uses an EMR managed rule is NOT verified: say so, and do not invent a rule name.
- S3 Vectors interface endpoint: service `com.amazonaws.us-east-1.s3vectors`, private DNS `s3vectors.us-east-1.api.aws`; https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-privatelink.html. SQS interface endpoint `com.amazonaws.us-east-1.sqs` is standard (not read from the docs this round).
Anything else you rely on that is not in this list must be marked UNVERIFIED in the README.

## Findings to fix
H1. Fix the ECS rule name in modules/identity (`SyncIntegrationRules`) to the verified plural form. Correct README.md:185 (the count of managed rules and names). Say in the README that the EMR terminate-rule point is unverified, and that the docs show a polling fallback.
H2. Add `s3vectors` and `sqs` to the interface endpoint list in both module variables and the environment, keep the default egress mode `endpoints` (the human's decision), and make the security-group egress rules match (endpoint group only). Correct README.md:56 so it matches the IAM grants. The endpoint count and its idle cost change; update README tables.
M1. Lower the `max_run_seconds` default to a value consistent with the 150-minute script cap plus margin, add validation (range), pass the bound per run through the experiment tfvars, and document that the Step Functions execution timeout skips CleanupOnFailure, so a client death leaves the cluster to the idle auto-termination (900 s) and to the human's cleanup. Do not claim more than that.
M2. Rewrite COST_ESTIMATE.md for the actual defaults: endpoint egress with the NOW 8 interface endpoints, provisioned Neptune t4g.medium, absolute alert thresholds. Recompute every affected total at LOW and HIGH with the arithmetic shown. State plainly what the human's D2c choices now imply: restate the S+M HIGH figure, the tier-L gate figure (the $23.5 line), and whether S+M still fits under $25 at HIGH. Show NAT as an alternative column so the human can compare, but do not choose it. Fix the stale text the review lists (Plan A label, section 6 item 4, the U1 "consistent with V4" claim: remove or mark UNVERIFIED, since no c4.2xlarge EC2 price is in pricing-evidence.md), and fix L6 (loader size) and L7 (burn rate to include endpoints and Neptune) in both the estimate and scripts/README.md and run-bounded-pipeline.sh. Every rate stays VERIFIED or UNVERIFIED.
M3. Provide a documented, scripted-safe teardown flow that keeps storage: an overlay tfvars (for example `session-down.tfvars`: `egress_mode = "none"`, Neptune off, services 0) and the plan-and-deploy sequence to use it, through the existing approved scripts and approval records. Do not add a new destructive capability. Update README.md and scripts/README.md, and make COST_ESTIMATE's teardown assumption match what exists.
M4. deploy.sh: put the build context identities into the digested arguments (and the clean-tree check or a content digest of each context), derive `--platform` from the configured `cpu_architecture`, and correct the false comment at :91-92.
M5. lib.sh: cap the record validity window (state the cap, for example 24 hours) and add a consumed marker so a record is single-use per action and args digest. Correct the false claim in scripts/README.md:73 and in the Phase 14 notes: a caller can write a record; say what the controls do and do not stop (the real control is the human signing the digest and the Phase 13 launcher).
M6. cleanup.sh: stop using `aws s3api head-bucket` for the vector bucket. Use the S3 Vectors CLI or API form instead, but you cannot verify it: if you are not sure of the command, report the gap as UNVERIFIED, make the check fail closed (report "could not verify", not BUCKET_GONE), and do not treat 403 as gone. Fix the unconditional `DIRTY=no` at :127 and :160.
M7. Document the dimension-change hazard (index replace destroys vectors) in the s3vectors module README section and the variable description, and in COST_ESTIMATE's fallback discussion. Add a guard that does not rely on a variable in `prevent_destroy` if one exists (for example a `precondition` requiring an explicit acknowledgement variable when the dimension changes). Report what you chose.
M8. Document which principal runs each script and what permissions each needs (the deployer role vs the human's broad credentials for `apply`), and remove or correct the claim that the deployer role separates deployment from apply.
M9. Document the smoke-test network requirement honestly (no client in the VPC; no execute-command; Cloud Map off, so the example URL does not resolve). Mark it as a Phase 14 decision with the options, and fix the example URL.
L1. Add the Neptune security-group egress rule to the S3 prefix list ONLY if you can cite the documented requirement for the bulk loader; otherwise leave it and flag it UNVERIFIED for Phase 14.
L2. Fix the EMR scaling bound so it accounts for task nodes, or document it with the arithmetic and validate the sum.
L3. Narrow `emr_cluster_arn` where the ARN is known at policy time, or document why `cluster/*` is required. Add an MFA or external-id condition to the deployer trust policy if appropriate, or document why not.
L4. Add validation rejecting `*` or an empty list in the Bedrock ARN variables.
L5. smoke-test.sh: refuse `--out-dir` paths under the repository root.
L8. Fix the stale documentation listed in the review (validate status now exit 0 per terraform-checks.md, "unverified until work order 06", the alarm count, the `force_destroy` description, the smoke-test header comment).

## Procedure
1. List which check proves each item (human `validate` and `fmt -check`, `bash -n`, or review only).
2. Edit. Keep HCL formatted as `terraform fmt` would. The human's fmt, init and validate were clean before this round and must stay clean.
3. Re-read every changed block against the provider schema; do not invent arguments.

## Report (plain text in your final message)
Contract fields; per finding: fixed, partly fixed or not fixed, with the file and the check that proves it; the restated cost headline (LOW and HIGH, endpoint mode and the NAT alternative); every UNVERIFIED item you introduced or kept; what the human must run (`init -upgrade` is not needed unless providers change; `validate` and `fmt -check` are).
