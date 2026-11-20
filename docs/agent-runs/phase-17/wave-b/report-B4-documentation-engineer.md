# WO-B4 report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE; NOT REPLAYED throughout. FILES CREATED: `docs/demo-script.md`. No commands run.

## Labels for the 22 items
- **LIVE (laptop):** items 1-5, 7, 8, 9 and 10 (the test-based samples, P17-20/21), 16 (`VerificationSuite` line 298) and 17.
- **ARCHIVED:** items 6 (Phase 10), 20 (B, C and D plots).
- **Both labels:**
  - 11: ARCHIVED primary, plus optional LIVE path (i) per B8, human-run with session-down.
  - 12: LIVE (AWS read-only) optional, else ARCHIVED.
  - 13: ARCHIVED, with an optional laptop G run.
  - 14 and 15: ARCHIVED, plus LIVE (laptop) local services stated as demo data.
  - 21: LIVE (laptop) dirty-tree refusal, plus ARCHIVED F2.
  - 22: LIVE (AWS read-only), plus archived reads.
- **LIVE (AWS, read-only):** 18 (plan) and 19 (dashboard).

The script also has a prerequisites and masking section, about 52.5 minutes of narrated time budget, and a checklist mapped to the deliverable index.

## Assumptions
- The `runMain` and `testOnly` commands are composed from code, not taken from a record.
- The AWS inventory commands are composed from runbook 7.3 and must be confirmed read-only.
- Item 5 uses an untracked probe file, because `policy.py` reads `git diff HEAD` plus untracked files.

## Missing evidence (stated in the file)
- No archived Neptune query transcript.
- No vector source-pointer excerpt.
- No deployed Haiku query through the HTTP API.
- No archived live call to the evidence or explain endpoints.
- Dashboard existence after session-down NOT RECORDED.
- No after-cleanup inventory.

## Open question
Path (i) of item 11 needs no live Haiku (P17-26). B8 does not say which overlay keeps the stub answer model (`services-smoke.tfvars:13` sets `bedrock`). infra-engineer must confirm before the human plans.

## Follow-ups
- `README.md:219, 402` still say `demo-script.md` is PENDING; a documentation-engineer update is needed later.
- The human must read the Phase 10 records, `docs/api.md` 4.2 and `code-reviewer.md` before narrating.
