# Phase 12 decisions (orchestrator; human decisions marked HUMAN)

Standing rule from the human prompt: ask only for decisions that change scope, cost or dependencies. Otherwise take the conservative option and record it here.

## D1. Step 1 is split across two owners (2026-11-09)
- The four P-4 `.fail` defects are production defects in `modules/hadoop/**` (hadoop-engineer). The `.fail` twins and the pins sit in `tests/adversarial/**` (test-engineer). The ownership map gives no one agent both.
- Order: work order 01 (hadoop-engineer, production fix and any module tests it owns), then work order 02 (test-engineer, flip the twins).
- Expected transient state: once a fix lands, munit's `.fail` turns that twin red. `sbt quick` after work order 01 is therefore red on exactly those twins until work order 02. hadoop-engineer must name them and must not edit `tests/**` to hide them.
- Conservative reading of "do not weaken any test": each `.fail` twin is flipped by removing only the `.fail` tag, so its assertions are unchanged. The paired "pin" tests assert the buggy behavior and say in their own comments that they go when the fix lands (AdversarialXmlSuite.scala:73-74). They are inverted to assert the corrected behavior, not deleted, and each inversion is listed in the test-engineer report for the human.

## D1a. Open evidence gap: hadoop-engineer report text (2026-11-09)
hadoop-engineer's final message gave only a closing note, and a resend request returned no report text either. The item-to-test list, the zero-author counter decision and the pair-ordering rule are therefore not archived. The orchestrator did not reconstruct them. Work order 02 has test-engineer derive the red tests from a fresh `sbt quick`. The code reviewer reads the hadoop diff directly for these decisions. Recorded as open until the human decides whether to re-dispatch hadoop-engineer for a written report.
UPDATE (2026-11-09): work order 13 asked again and hadoop-engineer returned a report reconstructed from the files (report-13). It is not the original work order 01 report, and it says which items it could not confirm. The item-to-test list, counter decision, ordering rule and ORCID handling are now on record, with their keys. Still unconfirmed: the Hadoop job and reducer call sites, and which edits belong to work order 01. D1a is closed as far as it can be, and the reviewer's re-review reads the diff for the rest. The look-back test's pass lines and its revert variant were not observed (report-13).

## D2. Binding instructor constraints (HUMAN, relayed 2026-11-09)
Quoted from the human's message:
- "Cumulative AWS budget for the whole project: at most $25, including Bedrock, compute, storage, transfer. Not enforced by AWS. Alerts at $10/$15/$20."
- "Smaller representative dataset subsets are allowed if selection criteria, actual record counts and limitations are documented, and experiments show scalability across multiple dataset sizes. Replace the 100k works / 1M edges / 10k chunks targets with a multi-size plan (e.g. 3 sizes) that fits the budget."
- "Bedrock is allowed but may have 0 quotas on a new account; keep the offline stub and hash embedder as the fallback behind the existing ports and design so the system works without Bedrock."
- "Grading is on correctness, experiment quality and analysis, not AWS usage."
- "The Phase 12 cost estimate must show the whole remaining project (Phases 14-15 runs, with tear-down after each) fitting under $25, with the cheapest viable choice per service (e.g. Neptune Serverless minimum capacity vs alternatives, EMR on minimal/spot instances or EMR Serverless, short bounded runs). Flag any service whose minimum cost threatens the budget and propose a documented cheaper alternative. Final report must include actual AWS expenses."

Consequences (orchestrator's reading):
- This supersedes the earlier instruction to plan for the 100,000 works, 1,000,000 edges and 10,000 chunks minimums. The infra-engineer plans three dataset sizes instead. The sizes, selection criteria and expected counts are proposed in the cost estimate for the human to confirm. Dataset selection itself belongs to dataset-engineer and is a later phase.
- The $25 ceiling covers the whole project, not Phase 12. The existing $25 and $50 budgets do not match the instructor's $10/$15/$20 alerts. The infra-engineer designs the alert thresholds as variables and flags the mismatch. Changing real budgets is a Phase 14 human action.
- The $25 ceiling and the Paid-plan upgrade interact. The estimate must show Neptune and EMR fit under $25 with credits before the upgrade is recommended. Service minimums that threaten the ceiling are flagged with a cheaper alternative (for example, skipping Neptune in favor of Spark-computed graph tables plus DynamoDB, or EMR Serverless). Any alternative that drops a course-required service needs human approval.
- Bedrock stays optional. Terraform may grant Bedrock permissions behind a variable that defaults off. The hash embedder and offline stub remain the default path.
- "Final report must include actual AWS expenses" is carried as an open item. Phase 12 has none, because it is local only. Phases 14 and 15 record them.

## D2a. Bedrock quotas are non-zero (HUMAN, checked in Service Quotas, us-east-1, 2026-11-09)
- Titan Text Embeddings V2, on-demand: 6,000 requests/min and 300,000 tokens/min.
- Claude Haiku 4.5, cross-region and global cross-region inference: 5,000,000 tokens/min.
- This corrects the D2 worry that a new account may have 0 quotas. D2 itself stays as quoted.
- Plan for Phase 14: Bedrock is the real model and embedder, behind the existing ports. The model is Claude Haiku 4.5 through an inference profile. The embedder is Titan Text Embeddings V2 at 1024 or 256 dimensions. The offline stub and the hash embedder stay as fallback.
- The dimension choice (1024 or 256) is open. It changes S3 Vectors index size and cost, so the cost estimate prices both.
- The cost estimate includes Bedrock: Haiku 4.5 input and output tokens per bounded query run, Titan V2 embedding tokens per dataset size, and the S3 Vectors storage and query cost for each dimension. Each line cites the AWS pricing page.
- Terraform side: Bedrock IAM permissions for the runtime role are scoped to the specific inference profile and Titan model ARNs. A variable switches Bedrock on, and a default-off setting keeps the fallback path. The infra-engineer must not grant `bedrock:*` on `*`.
- Not yet verified: the account's model access or agreement status for these models. Service Quotas shows limits and does not show access. The Phase 14 smoke test verifies it.

## D2b. Terraform is approved as a local tool (HUMAN, 2026-11-09)
- Terraform is installed locally (brew, hashicorp/tap). infra-engineer may run only `terraform fmt -check`, `terraform init -backend=false` and `terraform validate`. No `plan`, no `apply`, no AWS credentials.
- `init -backend=false` downloads the pinned providers from the public registry. That is the only network use and it needs no credentials. It writes `.terraform/` and a lock file inside the module directory. `.terraform/` must stay out of git. The lock file `.terraform.lock.hcl` is committed. `.gitignore` belongs to build-engineer, so if the pattern is missing the orchestrator routes it there.
- If the guard blocks any of the three, the orchestrator gives the human a paste-ready `!` command instead. The agent does not work around the guard.
- Other static checks (tflint, tfsec, checkov) are new tools. They need the human's approval first. Phase 12 relies on fmt, validate and a read-only review unless the human approves one.

## D2c. Cost-driven choices (HUMAN, answered 2026-11-09 after COST_ESTIMATE.md)
- Neptune: provisioned `db.t4g.medium` is the default (`neptune_mode` variable). Serverless stays selectable, with its maximum lowered from 2.5 to 2 NCU. It is torn down after every run. Neptune is not dropped.
- Egress: keep interface endpoints (no internet path at all). The estimate says NAT would save about $0.33 to $1.24 over the project. NAT stays selectable, and no run uses it unless the human changes this.
- Tier L (100,000 works, 500,000 edges, 20,000 chunks): runs last, behind a spend gate. Sizes S (5,000 works) and M (25,000) run first. The human reads actual spend from Billing and Cost Explorer and runs L only if the extrapolated total stays at or under $23.5. The gate is a human decision at Phase 15, not a script.
- The three sizes, their counts and the edge-density assumption are PROPOSED, not confirmed. They rest on assumptions listed in COST_ESTIMATE.md. Tier S measures real density, and the L estimate is re-derived from it before L runs.
- The cost estimate's rates are mostly UNVERIFIED (see pricing-evidence.md). At the HIGH bound the whole project does not provably fit under $25 with L. This is carried as an open risk, not closed.
- Pending Terraform and script edits (one batched work order after the human's fmt/init/validate output): `neptune_mode`, Neptune Serverless max 2 NCU, Cloud Map default off, alert thresholds as variables, EMR scaling cap as its own variable, per-experiment tfvars, script size caps aligned to the S, M and L plan. S3 Vectors write batching is a request to storage-engineer, deferred to a later phase.

## D5. AWS provider pin moves from 6.20.0 to 6.26.0 (orchestrator, conservative; 2026-11-09)
- Evidence: the human's `terraform validate` failed. v6.20.0 has no `aws_s3vectors_vector_bucket` or `aws_s3vectors_index`. The provider changelog shows the resources first in 6.24.0 (2025-12-02) and the index `encryption_configuration` and `metadata_configuration` blocks in 6.26.0 (2025-12-10), with no later change. The newest release at fetch time was 6.72.0 (2026-11-04).
- Choice: `= 6.26.0`, the smallest version carrying every argument the module uses. It is over 11 months old, and a smaller jump from the pin the human's `init` already accepted. The alternative, the latest 6.x, gets fixes but has far more change to review. Not a new dependency, since the same provider is already approved. It is recorded here because a pin is a dependency value.
- Needs a human `terraform init -upgrade`, because the lock file records 6.20.0. The lock file must be regenerated and then committed.
- Residual: the argument schema was read from the provider docs on GitHub `main`, not from the 6.26.0 tag. `validate` is the check.
- The Terraform `>= 1.10.0, < 2.0.0` constraint stays. The human's installed version was not shown, so it is still unconfirmed.

## D6. Repair round 1 rulings (orchestrator, conservative; 2026-11-09)
AWS facts checked by the orchestrator against docs.aws.amazon.com through the AWS documentation MCP server, because infra-engineer has no web tools:
- ECS `.sync` managed rule: `StepFunctionsGetEventsForECSTaskRule` (plural "Events"), actions `events:PutTargets`, `events:PutRule`, `events:DescribeRule`; plus `ecs:RunTask`, `ecs:StopTask`, `ecs:DescribeTasks` (https://docs.aws.amazon.com/step-functions/latest/dg/connect-ecs.html). The reviewer's reading is right and the repo's name is wrong.
- EMR `terminateCluster`: the documented policy is `elasticmapreduce:TerminateJobFlows` and `DescribeCluster` only, with no EventBridge actions (https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html). For `.sync` tasks, missing events permissions cause "a delay before Step Functions becomes aware", not an AccessDenied (https://docs.aws.amazon.com/step-functions/latest/dg/service-integration-iam-templates.html). So H1's claim that cleanup cannot terminate the cluster is NOT supported by the docs. Not verified: whether `terminateCluster.sync` uses an EMR-specific managed rule. The repair fixes the ECS name and the rule count and states the EMR point as unverified.
- S3 Vectors supports an interface VPC endpoint, service `com.amazonaws.<region>.s3vectors`, with private DNS `s3vectors.<region>.api.aws` (https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-privatelink.html). SQS has the standard interface endpoint `com.amazonaws.us-east-1.sqs`; not read from the docs this round.
- Not checked: Neptune bulk-loader egress (L1). The repair asks infra-engineer to add the S3 prefix-list egress rule only if it is the documented requirement, otherwise to flag it for Phase 14.

Rulings:
- H2: keep the human's `endpoints` choice (D2c) and add the `s3vectors` and `sqs` interface endpoints. This raises the endpoint count from 6 to 8 and raises cost. The cost estimate must be restated for endpoint mode (M2). The restated numbers go to the human, because D2c was decided on the NAT-based figures. If they now favor NAT, that is the human's decision.
- M7 (dimension change replaces indexes): no change to index names, because the storage and rag code own the names. Infra documents the hazard, and adds a guard if one exists that does not rely on a variable in `prevent_destroy`. A fallback with 384 dimensions needs its own environment or a state-aware plan. Flagged for the human.
- M8, M9 (principals and the smoke-test path): documented honestly. The smoke test is a Phase 14 step that needs a client inside the VPC or an approved access path, and Phase 14 decides which.
- L12 (conflict marker on the join record): deferred and recorded. It changes the join output contract, nothing reads it yet, and it needs a downstream owner. Open for a later phase.
- L11 (look-back test): routed to hadoop-engineer after test-engineer, because both need sbt and two sbt commands must never overlap.
- Untracked non-Phase-12 paths (.agents/, .codex/, AGENTS.md, datasets/slice/, .DS_Store, readback-paths.py): the Phase 11 ratification already excluded them from the commit. Ownership for the Codex files needs a human ruling. Not an agent action.
- Round count: this is repair round 1 of at most 2.

## D7. Repair round 2 (orchestrator; 2026-11-09)
- Round accounting: the human's cap is at most 2 batched repair rounds. Repair round 1 (work orders 11, 12, 13) answered review 1. Review 2 found two Medium (N1, N2) and five Low (L-a to L-e). Repair round 2 is the second and last allowed. Work order 14 called review 2 "the last allowed round", which was the orchestrator's wording and not the human's cap. A repair round 3 needs the human's approval.
- N1 ruling (conservative): fix the estimate to match what the overlays do (Neptune on in every size overlay, because the load stage and the course require it), and do not change the overlays or drop Neptune. Add the missing Neptune and service-task hours, and restate S+M and the L-gate at HIGH. If S+M no longer fits under $25 at HIGH, say so plainly and list cheaper options (for example a pipeline-only overlay that starts Neptune only for the load and query windows) as proposals for the human. Do not make that design change in this round without the human.
- N2 ruling: fix the calibration fake to hold the socket open without reading, add an "answer, then read 4 MiB" negative control, re-measure.
- L-a to L-e: fix by documentation, or by the smallest code change where one exists. Do not widen scope.

## D8. Phase 12 closing state (orchestrator; 2026-11-09)
- Review rounds: review 1 REQUEST_CHANGES, repair 1, review 2 REQUEST_CHANGES, repair 2, review 3 APPROVE (no BLOCKING finding). Two repair rounds used, as the human capped them. No third repair was run.
- Open items carried, not repaired (recorded for the human; none was fixed in this phase):
  - F1: the per-tier step timeout also limits CreateCluster and RunLoader, and the overlay values are shorter than the estimate's loader times. Fix (a separate loader timeout) before the first M pipeline run in Phase 14.
  - F2: the N2 control uses a 1000 ms grace period, the real-service checks 500 ms. Minor test-side item.
  - F3: the 14.0 Neptune window has no mechanism; if it uses the S overlay, S+M HIGH is about $25.82.
  - F4: README "Checks to run" block still lists `init -upgrade`.
  - L12: no conflict marker on the join record (D6).
  - Cost: S+M at the HIGH bound is $25.42 (over $25 by $0.42); the whole project with L is $38.70 at HIGH. Proposals P1 to P4 are not built. The human chooses one or accepts the risk before Phase 14 spends money. Most rates are UNVERIFIED (pricing-evidence.md, U1 to U13).
  - The look-back test's pass lines and revert run were not observed (report 13).
  - shellcheck is not installed (not run); the human's run after repair round 2 (`terraform validate`, `fmt -check`, `bash -n`) and the human's `sbt clean check` were pending when review 3 returned.
  - Course gate items that need a plan (no unexplained stateful replacement, plan counts, signed plan, human-signed plan before apply) are not met: Phase 12 is local only (D3). They move to Phase 14.
  - Ownership of .agents/, .codex/ and AGENTS.md needs a human ruling before the phase commit.
  - Actual AWS expenses (instructor requirement) are none for Phase 12; Phases 14 and 15 record them from Billing and Cost Explorer.
  - Paid-plan upgrade for Neptune and EMR is a Phase 14 human decision (D4). "Activate advanced features" was never suggested.
- Nothing was committed. No .claude/**, CLAUDE.md or docs/agents.md edits. The agent `sbt check` (exit 0, 1192 s) predates both repair rounds. Agent `sbt quick` after the last test change: 62 and 992, 0 failed.

## D9. The human's `sbt clean check` failed (2026-11-09)
- Attempt 1 failed in the `sparkAnalytics / Test / test` group: the forked test JVM exited with code 56 ("Total 0, Failed 0, Errors 0, Passed 0"; another group passed 1098, 0 failed). Evidence: clean-check-1.md. The cause was unknown from the last 60 lines (they showed passing suites of a different group and a clean SparkContext stop; that reading was corrected by the diagnosis below).
- The review verdict APPROVE (review-round-3.md) stands for what it reviewed, but it did not cover this failure, and the phase gate is not met. The phase is not ready for acceptance.
- Diagnosis from the log (clean-check-1.md): the `sparkAnalytics` forked JVM was killed by Spark's own local-mode executor ("Exit as unable to send heartbeats to driver more than 60 times", 19:50:23, during PathsSuite), not by a failing assertion. Cause behind the heartbeat failures is unproven; a process pause (for example the machine sleeping) or resource starvation is consistent with a burst of errors at one timestamp. No Phase 12 code appears in the failure path read.
- Not done: no agent rerun, no test change. Next step needs the human's choice (see the orchestrator's report): diagnose first by reading the log and the Spark harness, or rerun once to separate a flake from a defect. A diagnosis or fix beyond reading would be a repair round 3, which needs the human's approval (D7).

## D10. Clean check attempt 2 passed (2026-11-09)
- The human's second `sbt clean check` passed: `Passed: Total 81` and `1098`, 0 failed, `[success]` in 1176 s, `sbt exit: 0`. Evidence: clean-check-2.md. No source or test changed between the attempts.
- The attempt 1 failure (D9) stays on record. The same code passed on the rerun, which fits the environmental hypothesis (Spark local-mode executor heartbeat failure under a pause or starvation) but does not prove it; a flaky Spark test setup is not excluded. It is an open risk for later clean checks, with no repair done and none approved.
- Gate status: formatting, validate and `bash -n` (Run 4), agent `sbt quick` runs, review 3 APPROVE with no BLOCKING finding, and a passing `sbt clean check`. Not met or deferred: plan-dependent course gate items (Phase 14), cost fit at the HIGH bound (open), the ownership ruling for .agents/, .codex/ and AGENTS.md, and the open items in D8. The decision to accept the phase is the human's.

## D3. Phase 12 is local-only (HUMAN, in the phase prompt)
No `terraform plan` or `apply` against AWS, no AWS credentials, no AWS calls. Only `terraform fmt`, `validate` and local static checks. The course gate items that need a plan (no unexplained stateful replacement, plan counts, signed plan) cannot be met in Phase 12. They move to Phase 14 and are recorded as open.

## D4. AWS account facts to design for (HUMAN)
- One account from the new AWS sign-up: owned by an AWS Builder ID, Free plan with credits, no root user, IAM users programmatic-only. Region us-east-1. Budgets at $25 and $50 exist.
- Neptune and EMR need the Paid plan. The upgrade is a Phase 14 human decision. Never suggest "activate advanced features" (irreversible, removes the spend limit).
