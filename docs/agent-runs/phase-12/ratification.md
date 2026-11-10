# Phase 12 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-09.
- Basis: decisions.md (D1 to D10), review rounds (code-reviewer, Opus) ending in APPROVE at review-round-3.md, terraform-checks.md, clean-check-1.md, clean-check-2.md, COST_ESTIMATE.md, and the owner reports.

## Phase gate
- The first human `sbt clean check` FAILED in the sparkAnalytics forked JVM with exit 56. The log shows "Executor: Exit as unable to send heartbeats to driver more than 60 times" during PathsSuite. This is environmental: the machine stalled during the run, and the laptop lost power that session. No source or test changed between attempts.
- The second human `sbt clean check`, with the machine kept awake, passed: Passed: Total 81 and 1098, 0 failed, [success] in 1176 s, sbt exit 0 (pipefail). Log: phase-12-clean-check-2.log.
- Rule going forward: keep the Mac plugged in and awake (`caffeinate -dims`) for every clean check. If exit 56 recurs with the machine awake, it is a Spark test-setup defect for the modules/spark owner.
- Terraform and script checks (run 4, human): `terraform validate`, `terraform fmt -check -recursive` and `bash -n` on all 7 scripts each exited 0. shellcheck is not installed.

## Delivered
- P-4, the Hadoop defects (hadoop-engineer):
  - Comment and CDATA records are no longer read as live.
  - Zero-author works are counted separately.
  - A shared ORCID is reported as a conflict.
  - Pair consolidation is independent of arrival order.
- P-4, the tests (test-engineer): 4 `.fail` twins flipped and 6 old-behavior pins inverted. Nothing deleted or loosened.
- P-10: test-engineer strengthened the early-close tests (F1 and F2, plus small follow-ups).
- infra/terraform: an environment `course` and 11 modules (foundation, network, identity, data_lake, dynamodb, neptune, s3vectors, emr, runtime, observability, workflow).
  - AWS provider pinned to 6.26.0, with the lock file committed. Region us-east-1.
  - Interface endpoints, so there is no internet path. Nothing is public and no secrets are in source.
  - Bounded defaults: Neptune is provisioned db.t4g.medium (Serverless is optional, max 2 NCU). Bedrock is behind a default-off switch, with IAM scoped to Haiku 4.5 and Titan V2.
  - S, M and L tier tfvars plus an elasticity example, a session-down overlay, and a vector-dimension guard.
- scripts/: plan, deploy, rollback, smoke-test, run-bounded-pipeline and cleanup, plus a shared lib. Approval records are single-use and expire after 24 hours.
- COST_ESTIMATE.md: per-service bounds with cited pricing pages. Most rates are unverified.

## Human decisions
- Budget: $25 cumulative for the whole project (instructor), with alerts at $10, $15 and $20. Smaller datasets are allowed if documented and shown at multiple sizes (D2).
- Bedrock quotas are non-zero (D2a): Titan V2 at 300k tokens/min, Haiku 4.5 cross-region at 5M tokens/min.
- Terraform is approved as a local tool for fmt, init -backend=false and validate only (D2b).
- Neptune defaults to provisioned t4g.medium. Interface endpoints are kept. Tier L runs last, behind a $23.5 spend gate (D2c).
- Cost risk accepted: S+M at the high bound is $25.42, $0.42 over budget.
  - P1: run Neptune only in the sessions that need it.
  - P3: check Neptune Free Tier eligibility in Phase 14.
  - P4: decide NAT vs endpoints from actual Billing spend after the S run.
  - P2 is rejected, because it conflicts with F1.
- Price verification: the estimate is accepted as unverified. The real check is actual Billing spend after the S run, before M, and before L.
- The Paid-plan upgrade for Neptune and EMR is a Phase 14 decision. Never "activate advanced features".

## Open, carried forward
- F1: fix before the first M run. The per-tier step timeout also bounds CreateCluster and RunLoader, and the M and L loader windows are too short.
- F2: the 1000 ms vs 500 ms grace period in the N2 control.
- F3: the 14.0 Neptune window has no mechanism.
- F4: a README check block still lists init -upgrade.
- L1: the Neptune bulk-loader egress rule is unverified (Phase 14).
- Plan-dependent course gate items move to Phase 14: no unexplained stateful replacement, plan counts, and a signed plan.
- Unobserved: the look-back test's revert run, the new path checks and the timeout behavior. Actual AWS expenses are $0 so far and are recorded in Phases 14 and 15.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, the .DS_Store files, and .terraform/ (ignored).
