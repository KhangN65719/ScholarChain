# Phase 14 prompt

## Scope note (human instruction and orchestrator reading; not part of the course text)

Phase 13 is accepted at f065a33. Phase 14 is the first phase that creates AWS resources. The course text below assumes a prepared account and an approved plan. This phase also has to finish what Phases 12 and 13 deferred.

Human constraints:
- Account facts are in decisions.md D1. Region is LOCKED to us-east-2 (Ohio). Never suggest "Activate advanced features": it is irreversible and removes the AWS-enforced spend limit.
- FIRST, before any apply: move Terraform, tfvars, scripts, tests and docs from us-east-1 to us-east-2 (infra-engineer). Verify service, S3 Vectors, Neptune db.t4g.medium, EMR and Bedrock inference-profile availability in us-east-2 from AWS docs. The human re-runs `terraform fmt`/`validate` and the script harness. Redo the region-sensitive lines of COST_ESTIMATE.md.
- Then the Phase 13 required items: `run_bounded` with real processes (incl. the S1 path), a real `docker login --password-stdin` and `docker build`, and Neptune Free Tier eligibility (P3).
- Agents never run `terraform apply` or `destroy`, deploy, or any AWS-changing command. Every plan, apply, deploy, run and teardown is a paste-ready `!` command for the human, through the Phase 13 scripts and approval records, with `set -o pipefail`, logs tee'd to `/Users/winnguyen/scholarchain-data/logs/`, ending in `| tail -n 60; echo "exit: $?"`.
- Show the plan summary and the estimated cost before each apply.
- Smallest viable session first (smoke on fixtures), then the S real-data run. Tear down the same day with the session-down overlay. After the S run the human pastes actual spend from Billing; only then decide M, the M/L caps (D10) and endpoints vs NAT (P4). Stop if actual spend approaches USD 15.
- Instructor requirements: the integrated pipeline must run on real data. Distinguish fixture-only from real-data validation. Report actual dataset sizes, execution times, actual AWS and any external costs, and vector ingestion counts with working retrieval.
- Route work to owners per docs/agents.md. No inline fixes. No edits to CLAUDE.md, docs/agents.md or `.claude/**` (ask the human to switch to manual mode). Agents run `sbt quick` during work and one `sbt check` before review; never two sbt at once. Read-only git. No commit.
- End with a fresh read-only code-reviewer (Opus) review, at most 2 batched repair rounds, then the human's `sbt clean check`, then stop for acceptance with everything torn down except storage.

The course text says the deploy-engineer "executes approved source-controlled deployment scripts". The guard keeps live deployment closed to agents (CLAUDE.md, Hook operation; D3 of Phase 13: no ratified launcher), so the human runs each live command and the deploy-engineer records the results in docs/releases/** from pasted logs. This is recorded as the human-controlled execution path, not as agent execution.

## Course text, verbatim (Phase 14 section of /Users/winnguyen/scholarchain-data/course-README.md)

## Phase 14: apply and deploy through human approval

Purpose:

Create the approved cloud environment and deploy the validated application.

Why:

Authoring infrastructure and operating infrastructure are separate duties. The deployment agent executes approved machinery and reports reality; it does not improvise unreviewed resources.

Before starting:

- Human verifies the Terraform plan checksum.
- Human confirms the target AWS account and Region.
- Human confirms cost bounds.
- Human confirms no unexplained destroy or replacement.
- Human approves the documented deployment command and approved Terraform apply path.

Use this work order after approval:

```text
Use the deploy-engineer agent to deploy the exact approved ScholarChain commit and Terraform plan to the approved course environment.

Requirements:
- Refuse a dirty working tree.
- Record commit SHA, plan checksum, target account, Region, and environment.
- Execute only approved source-controlled deployment scripts.
- Do not edit Terraform, scripts, or application source.
- Wait for service stabilization with a bounded timeout.
- Verify deployed image digests and task revisions.
- Run smoke-test.sh.
- On failure, collect evidence and follow the approved rollback policy.
- Do not perform destructive data repair.
- Return a deployment report with outputs, health status, smoke results, revisions, alarms, and any rollback action.
```

Gate:

- Services are healthy and ready.
- Deployed image digests match the commit.
- Smoke query returns an evidence-backed response using bounded fixtures or loaded sample data.
- No critical alarm remains active.
- Deployment report is archived.
