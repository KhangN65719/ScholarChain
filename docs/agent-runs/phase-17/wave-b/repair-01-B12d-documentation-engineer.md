# Repair 01 for review B12d (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: R2 to R7 and A1 to A6 are fixed. R1 is partly fixed: path (i) no longer routes A2 through `services-smoke.tfvars`, and step 1 (cost), step 4 (overlay set) and the query count are marked "BLOCKED-ON infra-engineer (WO-B8b)". `docs/demo-script.md` only; nothing was run.

## R1 to R7
- **R2:** the "no AWS-changing command" claim is scoped to option (iii), naming the path (i) and item 4 `aws s3 rm` exceptions.
- **R3 (item 18):**
  - the pipeline is labelled as composed;
  - the overlays are `session-down` + `session-down-services` + the human's `session2`, per `runbook.md:158` and `SESSION2.md:231`;
  - `phase-13/checks-1.md` scope and exit 1 are stated.
- **R4 (item 22):**
  - count-only checks added: `ecs list-clusters`, `ecs list-tasks`, `ecs describe-services`, and `sqs get-queue-attributes` for DLQ depth;
  - names are placeholders, filled off camera;
  - `--filters Name=engine,Values=neptune` added.
- **R5 (item 21):** precondition: commit the held files first, or run in the item 17 clone (`scripts/README.md:322`).
- **R6 (item 14):** an optional LIVE h-answers path is added:
  - the H-4 diagnostic: at most 4 calls, at most 0.15 USD, no approval record;
  - the full H run: through `run-experiment.sh` with an approval record;
  - otherwise ARCHIVED from `exp-H-20261119T053926Z-2e8943cf`.
- **R7:** `session2.tfvars` is off-repository (`SESSION2.md:229`); the A1 layering is added (B8 line 42).

## Advisories A1 to A6
- **A1:** a credential-less shell for item 4.
- **A2:** CLI preferred; the ARN is masked if the console shows it.
- **A3:** `--max-items 5`.
- **A4:** typed paths go through shell variables set off camera.
- **A5:** create an empty `--include` directory (acceptance UNVERIFIED), or run `package.sh` first.
- **A6:** the citation fixes.

Open: fill R1 from WO-B8b.
