# Review B12b, re-reviews 2 and 3 (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

- **Re-review 2:** REQUEST CHANGES, one minor finding. The README.md:82 label "Phase 17 clean-checkout gate" overstated the record (the final gate is still H-14), and the commit wording left out the recorded discrepancy. Everything else in repair 02 was verified: URL, versions, model, figures, privacy.
- **Repair 03 (documentation-engineer):** README.md:82 only. The label now reads "Phase 17 clean-checkout run at `5157e0e` (before the final gate, H-14)". The line now states the `c830b90` versus `5157e0e` discrepancy and that the totals equal the Phase 16 gate (`phase-16/ratification.md:8`).
- **Re-review 3:** APPROVE. Lines 81, 83 and 84 are unchanged. Optional nit: a missing period after "1427".

README.md is approved for commit. Pending: H-14 final gate, H-8, probes P1 to P4, and P17-24 in the final B12 review.
