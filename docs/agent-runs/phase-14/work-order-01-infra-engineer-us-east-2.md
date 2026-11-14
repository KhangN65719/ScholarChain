# Work order 01: infra-engineer, move us-east-1 to us-east-2

Owner: infra-engineer. Local only: no AWS calls, no credentials, no `terraform plan` or `apply`, no deploy. Do not run sbt. Do not edit CLAUDE.md, docs/agents.md or `.claude/**`. Do not read ~/.aws, `.tfstate` or `.env` files. Do not commit.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-14/prompt.md and decisions.md (D1), docs/agent-runs/phase-12/ratification.md, docs/agent-runs/phase-13/ratification.md, infra/terraform/**, infra/terraform/COST_ESTIMATE.md, scripts/** and scripts/README.md.

## Task

The AWS account is new and its Region is LOCKED to us-east-2 (Ohio). Replace us-east-1 with us-east-2 everywhere you own: Terraform (providers, variable defaults, AZ names and suffixes, VPC endpoint service names `com.amazonaws.<region>.*`, S3 gateway endpoint, ARNs, Bedrock inference-profile ARNs and IDs), tfvars, scripts (default region, account/Region guards, approval-record fields, help text), and the scripts and infra READMEs.

1. First produce an inventory: every file and line with `us-east-1`, `us-east`, a hard-coded AZ (`us-east-1a` style), `use1`, or a region-prefixed inference profile ID (`us.` prefix is fine for US cross-region profiles but confirm it is still correct for us-east-2; `global.` profiles are region-neutral). Put the inventory in the report.
2. Make the Region a single source of truth in Terraform and scripts where it is not already. Add a guard so a plan or script targeting any Region other than us-east-2 is refused, with a clear message that names D1 (the account is locked; other Regions need an irreversible account change).
3. Verify against AWS documentation, not memory. The orchestrator supplies a verified availability table (evidence-availability-us-east-2.md in this directory). If your Terraform uses a service, feature, instance class or inference profile that the table says is unavailable or unconfirmed in us-east-2, do not guess: report `BLOCKED-ON` human decision with the options.
4. Do NOT change any cost figure by judgment. In COST_ESTIMATE.md, list each region-sensitive line (pricing page links, per-Region rates, NAT, interface endpoint, Neptune, EMR, S3 Vectors, Bedrock, data transfer) and mark each as `us-east-2 verified`, `us-east-2 unverified` or `unchanged (Region-neutral)`, with the cited page. Recompute totals only from values you could verify; otherwise keep the old value and flag it. Do not raise `pipeline_max_run_seconds`, the `--max-minutes` caps (60, 90, 150) or any projected spend without reporting `BLOCKED-ON` human decision.
5. Tests under tests/** that assert us-east-1 are owned by test-engineer: list them in the report and do not edit them.
6. Docs outside your paths (root README, docs/**) that mention us-east-1: list them in the report for documentation-engineer.

## Checks (run what the guard allows, report what it refuses)

- `terraform fmt -check -recursive -diff infra/terraform`, `terraform -chdir=infra/terraform/environments/course init -backend=false`, then `validate`.
- `bash -n` and `shellcheck -x` on every file you edited under scripts/.
- If the guard refuses any of these for an agent, do not work around it: report `BLOCKED-ON` with the refusal text and list the files for the human's `!` run (D11 of Phase 13).
- After editing, run a final inventory and report that no `us-east-1` remains except in historical evidence you do not own, with the exact list.

Before editing, list in the report which check proves each item of your primary gate.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
