# WO-B5 report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator; lists kept)

STATUS: COMPLETE for the content that can be written now; 25 sections; PENDING placeholders for events still to come. FILES CREATED: `reports/final-report.md`. No commands run (no shell).

- **Corrections the agent made while drafting:**
  - Phase 14 failed loader runs: 5 (runs 6 to 10).
  - Only `deploy.sh` ties the image tag to the commit.
  - The 2.73 USD difference (6.18 to 8.91) is labelled as the orchestrator's reading.
- **Arithmetic labelled as arithmetic:** 45 of 74 first-pass pairs with no finding; 3.34 USD sum of estimates; 2.73 USD; 13.09 USD gate check.
- **Assumptions:**
  - Class 1 uses the first-pass "no finding" count as a labelled proxy.
  - The dataset and S3 Vectors reasons are not in any record; the report says so and labels the dataset reading as inference.
  - `modules/spark/DESIGN.md` was read only to line 120.
- **BLOCKED-ON EVIDENCE:** the verbatim text of the three research questions (section 2) is in no file read.

## PENDING placeholders
1. Research questions, section 2 (human or orchestrator).
2. H-13, the human's list of manual edits.
3. Terraform validate, plan and checksum (H-8).
4. Final `sbt clean check`, harness, `check.sh`, V2 and V3 (H-14, H-5).
5. Bounded-exercise replay (H-8).
6. Final B12 review and B11 run.
7. Live session outcome (H-10).
8. Post-session Billing (H-6, H-10).
9. Laptop and session outcomes (H-9, H-10).
10. Video link (H-12) and `demo-script.md` (B4).
11. Final tag and SHA, and the Phase 17 `human-gate.md` and `commit.txt`.
12. Cleanup date and inventory (H-11, D7).
13. Probes P1 to P4 (stated as NOT RECORDED).

## Class 3, manual edits, each with its source
- **M1:** the Phase 0 seed edit (`phase-01/orchestrator-update/ratification.md`).
- **M2:** the `9ee8b22..8dbd141` edits made at the human's direction.
- **M3:** Phase 4 repair 8, authored with Codex.
- **M4:** `c830b90` (P17-18).
- **M5:** `api.md:185` and `service-limits.md:120` (P17-24).
- **M6:** the human's commit `38421c6` (LIMITATIONS L14).
- **M7:** the Phase 15 human-side derives and rewrites (L21).
- **M8:** the Phase 16 rating column correction (P16-46).
- **M9:** orchestrator fixes F4 and L2 (P17-15).

## Disclosures classed outside the edit list
- Claude-in-chat commits.
- Codex orchestration in Phases 1 and 3.
- The uncommitted `.codex/`, `.agents/` and `AGENTS.md` (Phase 4 accepted-open item 4).
- The Phase 15 operational deviations (L12, L13, L27).

## Class 4, human-authored
230 gold labels; 8 free-form questions; the spot check, trace and rating; the H-9 audit; decisions P17-1 to P17-25.
