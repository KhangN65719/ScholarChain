# Work order 10: code-reviewer (Opus, read-only), Phase 12 gate, round 1

Owner: code-reviewer. Tools: Read, Grep, Glob only. Do not edit any file. Do not approve work you were given to fix. Return APPROVE or REQUEST_CHANGES with severity, file and line, a concrete failure input or scenario, expected impact and the owning agent for each finding. Verify every candidate against the actual files before reporting. Batch ALL findings into this one round.

## Scope: what Phase 12 produced (all untracked or modified, so `git diff` will not show the new files; read them directly)
1. Step 1 (Phase 11 carry-over):
   - Production: modules/hadoop/** (XmlRecordStream.scala, DblpXmlInputFormat.scala, TeamExpansion.scala, TaskSink.scala, IdentifierJoin.scala, LocalPipelineRunner.scala, IdentifierJoinJob.scala) plus the new module suite PhaseTwelveDefectFixSuite.scala. hadoop-engineer's report text was never received (decision D1a), so judge the choices from the code: the zero-author counter, the pair-consolidation order rule and the ORCID conflict handling.
   - Tests: tests/adversarial/AdversarialXmlSuite.scala, AdversarialMissingFieldSuite.scala, AdversarialDuplicateIdSuite.scala, tests/ADVERSARIAL_COVERAGE.md (report 02); Phase10Http.scala, Phase10RepairSuite.scala, Phase10EarlyCloseSuite.scala (report 03).
2. Infrastructure: infra/terraform/** (modules, environments/course, README.md, COST_ESTIMATE.md, tfvars files, .terraform.lock.hcl) and scripts/** (reports 04, 05, 06, 08).
3. .gitignore (report 07).
Evidence: docs/agent-runs/phase-12/ (prompt.md for the scope note and the course gate, decisions.md D1 to D5, work orders 01 to 09, reports 02 to 09, pricing-evidence.md, terraform-checks.md). The human ran `terraform fmt -check`, `init -upgrade -backend=false` and `validate` (all exit 0, provider v6.26.0). `bash -n` on the scripts has NOT been run yet (pending the human). Agent `sbt check` passed (exit 0, 1192 s; five `Passed:` lines, all 0 failed; per-group mapping not verified).

## Gate to review against (course gate, adapted: Phase 12 is local only, so no plan exists)
- IAM policies scoped to actual resources and actions. Check modules/identity line by line, including the recent change from hand-built to provider-attribute ARNs, every `*` resource, every PassRole, the sfn and deployer roles, Bedrock scoping (no `bedrock:*` on `*`) and that no IAM user, access key or login profile exists.
- Buckets, databases, Neptune, S3 Vectors and ECS are not public: security groups chained by role (no broad CIDR), no public subnet, IGW, public IP or load balancer, bucket public-access blocks, encryption.
- Default capacity is bounded: EMR, Fargate, Neptune, NAT or endpoints, scaling caps, the variable validations.
- Secrets do not appear in source: grep every .tf, .tfvars, script and README for secret values, tokens, account ids, access keys, emails. Secrets Manager entries referenced by ARN or created valueless.
- No unexplained destroy or replacement of stateful resources is a plan item and cannot be checked locally. Instead check: `force_destroy` and deletion-protection defaults, `cleanup_mode`, lifecycle rules, and anything that would force replacement of a stateful resource on a variable change.
- Cleanup: `cleanup.sh` lists before deleting and cannot wildcard-delete.
- Cost: COST_ESTIMATE.md arithmetic and claims against pricing-evidence.md. Flag any rate presented as verified that is not in pricing-evidence.md, any total that does not follow from its lines, and any place the $25 conclusion is stated more strongly than the evidence allows.
- Approval-record logic in scripts/lib.sh (`verify_approval` and the digest checks), the smoke-test secret read, and any way the checks can be bypassed by an agent-run call.
- Step 1: do the four fixes meet the defects as stated in work order 01? Is any flipped or inverted test weaker than its original (assertions removed, loosened, vacuous)? Does each new or inverted test fail if its fix is reverted? Is the pair-consolidation result fully order-independent for 3 or more records? Does the ORCID conflict leak into match policy (hadoop must not decide matches)? Does a zero-author work still produce the right counters and rejects?
- P-10 test repair: does the new F1 proof really fail if the server reads the body? Are the thresholds sound? Are there tests that would pass if the behavior were broken?
- Ownership: every changed file is within its writer's allowed paths per docs/agents.md. Nothing under .claude/**, CLAUDE.md or docs/agents.md was changed. (You cannot run git; use the reports and file contents.)
- Constraints from the human: no AWS credentials or calls, no new dependency or tool beyond the approved Terraform fmt, init and validate, no commit, nothing public, bounded defaults, Bedrock off by default with the stub and hash embedder as fallback.
- Documentation claims not supported by the implementation (README, COST_ESTIMATE, scripts README) and any statement that overclaims what `validate` proves.

## Also report
Items you could not verify and why; anything the human must run before a re-review (for example `bash -n`); a list of findings grouped by owning agent, so the orchestrator can batch one repair round.
