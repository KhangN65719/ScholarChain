# WO-B1 report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator; field values kept)

STATUS: COMPLETE (README written; nothing replayed; several values PENDING). FILES MODIFIED: `README.md` (full rewrite; "Autograder notes" kept verbatim).
- **Content:**
  - every course "README requirements" item
  - the agent catalog (18 rows, required columns; tool fences and owned paths verbatim)
  - "Reproducing the agentic workflow" with the 11 steps, the bounded exercise, the version record and the external-prerequisites table
  - the D01 to D20 index
- **Bounded exercise:** Phase 7 work order 01 (domain-engineer, `modules/core`), starting from `54fe82f` (from `phase-06/commit.txt`). Whether `sbt quick` exists at `54fe82f` is unchecked; `sbt check` is given as the fallback.
- **Catalog wording:** example instructions come from archived work orders. dataset-engineer, domain-engineer, dependency-updater and incident-responder have no archived verbatim wording, and their rows say so.
- **PENDING:**
  - repository URL and final tag (human)
  - the orchestrator model (human)
  - current python3, Docker and shellcheck versions (human)
  - plan checksum, Terraform validate and plan for the final tree (H-8)
  - Terraform security scan (NOT RECORDED)
  - `reports/final-report.md` (B5)
  - `docs/demo-script.md` (B4)
  - video link (H-12)
  - release manifest (B9)
  - inventory (H-11)
  - cleanup date (human)
  - the gold-set and prompt-text locations (D07, D10)
  - probes P1 to P4
  - the slow-deadline deployment state (now known: `services-smoke.tfvars:26`, B12a finding 2)
  - exact deploy, smoke and cleanup argument lines
- **NOT REPLAYED:** every command listed, until H-8.
- **Unverified usage:** the `/agents`, `/memory` and `/mcp` slash commands and the `claude` start command are standard usage, not verified in this repository.
NEXT GATE: review B12b, then H-8.
