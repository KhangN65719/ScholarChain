# Phase 14 decisions

Recorded by the orchestrator from human instructions. Dates are 2026-11-13 unless stated.

## D1: AWS account facts (replace the Phase 12 account notes)

- Project account "ScholarChain": a new AWS sign-up owned by an AWS Builder ID (identity kept out of the repository). The earlier Builder ID account became inaccessible and the duplicate root account was closed. Support case 179449495500847 is open.
- Paid plan with USD 20 credits. AWS-enforced project spend limit USD 20 with "pause idle resources" on. AWS Budget USD 25 with alerts at 10, 15 and 20.
- Region is LOCKED to us-east-2 (Ohio). Other regions need "Activate advanced features", which is irreversible and removes the spend limit. Never suggest it.
- CLI: IAM user `scholarchain-cli` (AdministratorAccess), profile `scholarchain`, region us-east-2. Agents never read ~/.aws.
- Bedrock quotas in us-east-2 (checked by the human): Titan Text Embeddings V2 on-demand 300,000 tokens/min. Claude Haiku 4.5 cross-region and global 5,000,000 tokens/min.
- Effective ceiling: USD 20 credits and the AWS spend limit are tighter than the earlier USD 25 project budget (Phase 12 D2). Stop at actual spend near USD 15.

## D2: Instructor requirements for this phase

- The integrated pipeline must run on real data.
- Distinguish fixture-only validation from real-data validation in every report.
- Report actual dataset sizes, execution times, actual AWS costs and any external costs.
- Report vector ingestion counts with working retrieval.

## D3: Deploy execution path

Agents never run apply, destroy, deploy or any AWS-changing command. The human runs each as a paste-ready `!` command through the Phase 13 scripts and approval records. Deploy-engineer records releases in docs/releases/** from the human's pasted logs.

## D5: Bedrock live check, risk R1 resolved (run by the human, us-east-2, 2026-11-13)

- Bedrock `converse` with inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0` returned "OK". `global.anthropic.claude-haiku-4-5-20251001-v1:0` also exists.
- Titan `amazon.titan-embed-text-v2:0` `invoke-model` with dimensions 256 returned a 256-length embedding.
- Decision: the `us.` profile is the default Haiku model ID for Terraform, scripts and docs. Embedding dimension 256 works; the vector-dimension guard must agree with whatever dimension the runtime config uses.
- Evidence level: human-observed live call, not agent-observed. Cross-Region inference works on this account despite the sign-up docs note.

## D6: Cost ceiling in code (human, via AskUserQuestion, 2026-11-13)

Lower the code ceiling (scripts/lib.sh, `budget_total_usd` validation, docs) from 25 to 20 USD, matching the AWS spend limit. The USD 25 Budget stays as the outer alert; the human's operating stop stays near 15.

## D7: Neptune class and EMR instance confirmation (human, 2026-11-13)

Neptune `db.t4g.medium` and EMR `m5.xlarge` in us-east-2 are confirmed by read-only CLI calls run by the human (`describe-orderable-db-instance-options`, `describe-instance-type-offerings`), not by the first apply.

## D8: Tier scope against the USD 20 ceiling (orchestrator reading of the human's staged instruction)

Infra-engineer's table (report 02) shows S+M through 15.4 at HIGH is 25.42 and S+M+L is 38.70, both over 20; LOW is under 20 everywhere. The human's standing instruction already stages the work: foundation + S first, then read actual Billing spend, and only then decide M, the M/L caps (D10) and endpoints vs NAT (P4). So Phase 14 scope is fixed to smoke on fixtures plus the S real-data run (HIGH 5.30, LOW 1.44, both under 20 and under the 15 stop). M and L are NOT approved. They need a new recorded human decision against actual spend.

## D9: Grace period is 5 s

See decision-09-grace-period.md (orchestrator proposal, awaiting human ratification).

## D11: Plan 2 approved in principle, apply deferred (human, via AskUserQuestion, 2026-11-14)

- Clean-tree plan 2: git 4a5bbf5f54c7, 132 to add, 0 change, 0 destroy, STATEFUL_CHANGE=no, DIRTY=no, X86_64, us-east-2, ceiling USD 20, PLAN_DIGEST 7733c154f0bb6c8f4c08c426650dd0f592f95400ffbefc2f68dd411c686bb53c (logs: phase14-plan-2.log). Foundation only; Neptune and EMR are off.
- Estimate shown to the human: idle about USD 1.92 to 2.88 per day for the interface endpoints; foundation plus smoke plus S LOW 1.44, HIGH 5.30 (rates mostly unverified); stop line USD 15.
- Human decision: reviewed and approved in principle. Do NOT apply tonight. At the start of the next session the human re-runs the plan on the same clean tree, approves that digest, then runs apply, smoke, the S run and session-down teardown in one sitting.
- Consequences: this digest is not an approval record and is not to be applied. A new plan digest will be shown for approval. The approval record must name script digest, arguments digest (deploy needs `--services-context` and `--loader-context`, both content-digested) and the new plan digest, and expires within 24 hours of APPROVED_AT. Nothing is applied. Spend so far: 0 USD (human confirms from Billing).

## D12: Session starts tonight (human, 2026-11-14), supersedes the deferral in D11

- The human starts the session tonight instead of tomorrow. Sequence in one sitting: identity check, fresh plan on the clean tree (phase14-plan-3), the human approves that digest, then apply, smoke on fixtures, bounded S real-data run, same-day session-down teardown (storage kept).
- Plan 2's digest stays unapproved and unused. Only the plan-3 digest can be approved. Approval records are written by the human, expire within 24 hours, and are single-use.
- Unchanged: stop at actual spend near USD 15; M, L, the caps (D10) and P4 wait for the human's pasted Billing figure; every AWS-changing command is a paste-ready `!` command run by the human.

## D4: Sequence

1. us-east-2 migration and availability verification (infra-engineer, test-engineer, documentation-engineer), human re-runs fmt/validate and harness.
2. Phase 13 required items: S1 real-process run_bounded, real docker login and build, Neptune Free Tier (P3).
3. Smallest session: smoke on fixtures. Then the S real-data run. Same-day teardown with the session-down overlay.
4. Human pastes Billing spend. Then decide M, the M/L caps (D10) and endpoints vs NAT (P4).
5. Code-reviewer (Opus), at most 2 repair rounds, human `sbt clean check`, stop for acceptance.
