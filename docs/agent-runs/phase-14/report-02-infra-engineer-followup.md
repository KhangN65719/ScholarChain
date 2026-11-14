# Report 02: infra-engineer, region migration follow-up (condensed by the orchestrator; author: infra-engineer)

STATUS: BLOCKED-ON human cost-tier decision (resolved by D8). Edits made; no check run (guard refuses terraform, bash -n, shellcheck for agents). No AWS, sbt or commit.

## Modified
- Ceiling 25 to 20 (D6): scripts/lib.sh (`SC_PROJECT_CEILING_USD`, usage text), usage examples in plan, deploy, rollback, smoke-test, run-bounded-pipeline, run-experiment, archive-evidence; environments/course variables.tf (`budget_total_usd` default and validation 20), course.tfvars, modules/foundation variables.tf and main.tf; scripts/README.md; infra/terraform/README.md; COST_ESTIMATE.md (new section "Cost ceiling lowered to 20 USD"; gate G-L threshold now 18.5).
- Stale "$25 and $50 budgets" text replaced; README gains "Account facts and cost limits", "Budgets", and "Availability checks for the human (us-east-2)" (read-only Neptune and EMR commands).
- Read in full this turn: package.sh, archive-evidence.sh, all module outputs/variables, all tfvars/overlays. Not re-read: module main.tf files (read in report 01).

## Remaining us-east-1 (intended)
Bedrock haiku routing-destination descriptions/default in environments/course/variables.tf (lines 210, 222, 224); README Bedrock bullet; COST_ESTIMATE V4/V7 source labels and the Pricing API host; one historical note in scripts/README.md check run 3.

## Cost tiers vs 20 (HIGH bounds, unverified rates)
| Scope | LOW | HIGH |
|---|---|---|
| Foundation + S (14.0, 14.1) | 1.44 | 5.30 |
| + M (14.2) | 2.19 | 8.38 |
| + 15.1, 15.2 | 3.54 | 14.20 |
| + 15.3 elasticity on M | 4.76 | 18.64 |
| S + M through 15.4 | 7.46 | 25.42 (over 20) |
| L alone | 2.82 | 13.32 |
| S + M + L | 10.32 | 38.70 (over 20) |
Alternatives at HIGH for S+M: P1 24.22, P1+P2 24.07, P3 23.00, P4 23.41. None gets under 20. Gate G-L at HIGH would not run L; passes at LOW.

## Risks
- Every SCRIPT_DIGEST changed (lib.sh included in digest); any earlier approval record is invalid.
- Unverified: whether the AWS spend limit counts gross or net, and what "pause idle resources" does to a running EMR/Neptune cluster at the limit.
- tests/scripts/run.sh likely asserts 25 and the old Region (work order 03).

## Human `!` checks pending
terraform fmt -check -recursive -diff infra/terraform; init -backend=false and validate on environments/course; bash -n and shellcheck -x on all scripts under scripts/; harness after test-engineer.
