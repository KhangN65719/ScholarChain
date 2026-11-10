# Work order 14: code-reviewer (Opus, read-only), re-review after repair round 1 (final round of at most 2)

Owner: code-reviewer. Tools: Read, Grep, Glob only. Fresh context: do not assume the first review's conclusions; verify against the files. Return APPROVE or REQUEST_CHANGES with severity, file and line, concrete failure scenario and owning agent per finding. Batch everything. Do not edit any file. You do not approve your own earlier findings by assumption: check each fix in the code.

## Scope: only what the repair changed, plus anything the repair newly touched
Read docs/agent-runs/phase-12/review-round-1.md (the findings), decisions.md (D6 rulings), and the repair reports: report-11 (infra), report-12 (tests), report-13 (hadoop). Then verify each of these in the actual files:

1. infra-engineer (report-11): H1, H2, M1 to M9, L1 to L8. Pay particular attention to what the repair NEWLY touched or added:
   - modules/identity (rule name change, ARN variables, any new statements, `deployer_require_mfa`, Bedrock variable validations): are scopes still resource-limited, is any new `*` justified, did the deployer-role claim get withdrawn everywhere?
   - modules/network and environments/course: the 8 interface endpoints, the security-group egress rules, whether anything became public.
   - the new `terraform_data` dimension guard in modules/s3vectors and `acknowledge_vector_loss`: does the guard actually block an accidental dimension change, or only look like it does?
   - modules/workflow timeouts; modules/emr cap validation and the 7-instance bound; session-down.tfvars and the experiment tfvars (do the overlays do what the README says, are they bounded?).
   - scripts/lib.sh (`verify_approval`, 24-hour cap, `consume_approval` single-use marker, `dir_digest`), deploy.sh (context digests, platform from CPU_ARCHITECTURE), cleanup.sh (vector bucket check fails closed, DIRTY field), smoke-test.sh (out-dir refusal), run-bounded-pipeline.sh (caps and burn). Can an agent-run call bypass any refusal? Is a consumed marker race-safe enough, and what happens if deploy.sh fails after consuming it?
   - COST_ESTIMATE.md (full rewrite): recheck the arithmetic of the headline table (LOW and HIGH, endpoints and NAT columns, S+M, L increment, gate figures $10.73 and $20.86), that every rate is marked VERIFIED only if it is in pricing-evidence.md, that no stale NAT-based or Serverless-based statement remains, and that the $25 conclusion is not stated more strongly than the evidence allows.
   - README.md and scripts/README.md: claims versus implementation, including the UNVERIFIED list.
2. test-engineer (report-12): M10, L9, L10. Does the new threshold (baseline plus 2 MiB) really fail a server that reads 4 MiB first and does not pass vacuously? Is the margin sound, given the measured values? Is the BodyBaseline calibration itself able to hide a bug (for example, if the baseline run reads something)? Did the repair weaken any existing test?
3. hadoop-engineer (report-13): L11. Does DblpXmlRecordReaderLookbackSuite fail if the look-back is reverted to `raw.seek(start)`? The report says the revert was not run and the suite's pass lines were not found: judge from the code only, and say what remains unproven.
4. Ownership: every file changed in this round is within its writer's allowed paths (docs/agents.md). Confirm nothing under .claude/**, CLAUDE.md or docs/agents.md changed (use the file contents; you cannot run git).

## Evidence of checks (human-run, 2026-11-09, terraform-checks.md Run 3)
`terraform validate`, `fmt -check -recursive` and `bash -n` on all seven scripts: exit 0. shellcheck not installed. These prove syntax and references only. Agent `sbt quick` runs after the repair passed (test-engineer 62 and 991; hadoop-engineer 49, 218, 62, 991, all 0 failed). The agent `sbt check` was before the repair. The human's `sbt clean check` is still to come.

## Also report
Items you could not verify and why; anything the human must run; whether a further repair round is needed (this is the last allowed round, so state clearly what remains open for the human to accept or reject, with severity).
