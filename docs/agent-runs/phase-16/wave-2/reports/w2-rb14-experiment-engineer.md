# W2-RB14: experiment-engineer report (review 18 repairs, EVALUATION.md)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Changed only `experiments/EVALUATION.md`. No sbt, AWS, Python or commit; one shell attempt refused by the guard, nothing ran.

- **F1 (line 370):** appended: "Exception: the figures in 6.4 come from the human's rating CSVs and `phase16-rating-SHA256SUMS`, which are in `SCD`, not under `RUNS`. They are frozen by the human's chmod and sha256 record (the freeze record in 6.4, lines "Sources" and "Freeze record"), not by a run record; only `rating-sample-key.json` is under `RUNS`." Cites by label, not line number.
- **A2 (6.4):** new "Rubric and sample design" paragraph after the single-rater paragraph: pointer to plan.md "Human rating rubric (D7)" lines 82-85 (no wording copied); design from plan line 80 (20 Haiku answers, 2 or 3 per answerable stratum, 3 abstentions, 2 free-form); key `group`: `abstention` S03, S08, S14; `free-form` S09, S18; the other 15 use their stratum name; S08 group `abstention`, stratum `free-form`, answered, no reason in the key.
- **A2 (limits bullet, ~722):** "how the 20 were selected is not read here" became "the design is stated above from plan.md line 80 and the key's `group` field, and the selection procedure itself is not read here".
- **A1 (Sources used, ~904):** "the plan.md rubric text was not re-read" became "`docs/agent-runs/phase-16/plan.md` (lines 78 to 88, "Human rating rubric (D7)", read for the pointer and sample design in 6.4)"; P16-46 row stays.
- **A3 (line 3):** "...rewrite note) and W2-DOC4" became "...rewrite note), W2-DOC4".

Verified: key `group` values read directly (S08 `abstention`, `abstained` false); plan.md lines 76-89 read. Unverified: redaction re-scan not run (added text has none); sha256 not recomputed; why S08 is in the `abstention` group is left open.
