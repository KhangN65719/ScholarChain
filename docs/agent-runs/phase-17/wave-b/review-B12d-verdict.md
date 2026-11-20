# Review B12d verdict on docs/demo-script.md (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: REQUEST CHANGES (documentation-engineer; R1 also infra-engineer).

## Required
- **R1** `:217, 220, 225`: path (i) step 4 uses `services-smoke.tfvars`, which sets `answer_model = "bedrock"` (`:13`). That contradicts "no live Haiku" (P17-26). The 1.9-2.0 USD figure includes 0.72 of Haiku. Mark the A2 overlay set as BLOCKED-ON infra-engineer. Per `services-smoke.tfvars:8-9`, leaving it out gives the stub, but also drops `slow_deadline_millis = 60000` (`:26`). Restate the cost.
- **R2** `:10, :43`: the blanket "no AWS-changing command" claim is false. Item 11 (i) and the item 4 `aws s3 rm` probe are exceptions. Scope the claim to option (iii) and name both exceptions.
- **R3** item 18 (`:338-343`): the `plan.sh | sed | grep` pipeline is composed and not labelled as such. Its overlays differ from `runbook.md:158` (`session-down-services` and `session2`). `phase-13/checks-1.md` covers fmt, init and validate only, and exited 1 (shellcheck).
- **R4** item 22 (`:389-397`): it lacks the runbook 7.3 ECS-task, ECS-service and DLQ checks; add them as count-only, or say they are omitted. Add an engine filter to `aws neptune describe-db-clusters`.
- **R5** item 21 (`:380`): the recovery dry run fails if held files are untracked. Require them committed, or run item 21 in the item 17 clone. Cite `scripts/README.md:322`.
- **R6** `:43`: points to an optional Bedrock run in item 14 that does not exist. Add an h-answers path (WO-B4 `:82`) or remove the reference.
- **R7** `:218, :221`: `session2.tfvars` is off-repository (`SESSION2.md:229`). Add B8's A1 layering of `session-down-services` and `session2` (`report-B8:42`).

## Advisories
- **A1:** item 4 session with no AWS credentials.
- **A2:** the DynamoDB console may show the ARN (prefer the CLI or mask it).
- **A3:** use `--max-items` for the scan.
- **A4:** the typed `<abs>/` path shows the home directory.
- **A5:** the item 17 `archive-evidence --include` directory may not exist.
- **A6:** `settings.json:142-163`; graph p50 "3 to 4 ms" (`RESULTS.md:253`).

## Verified
- The 22 items and labels.
- All cited archived files and figures.
- No live result claimed.
- The composed item-22 commands are describe/list only.
- No identifiers; masking instructions present; links resolve.

## Orchestrator routing
- R1 overlay question to infra-engineer, read-only (WO-B8b).
- R1 to R7 plus all advisories to documentation-engineer in one round. R1's step stays BLOCKED-ON until WO-B8b answers.
