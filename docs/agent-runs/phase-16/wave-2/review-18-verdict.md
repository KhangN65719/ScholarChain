# Phase 16 review 18 verdict (W2-DOC4, EVALUATION.md 6.4)

Author: code-reviewer. Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep only.

**Verdict: REQUEST CHANGES** (one Low required finding; everything else verified against the frozen files).

Checks:
1. Figures pass: recount from the frozen CSV gives usefulness 0/2/4/6/8, sum 80, 4.00 (answered 61/14 = 4.36, refused 19/6 = 3.17); clarity 0/3/3/2/12, sum 83, 4.15 (62/14 = 4.43, 21/6 = 3.50); supported 20 yes; abstention 2/4/14. Per-stratum 3/3/3/2/2/2/2/3, 6 refused, as 6.4.4; key `abstained` flags S02, S03, S14, S16, S18, S20; 6.4.1 rows (633-652) match; all human figures match.
2. P16-46 and freeze pass: as-entered abstention "yes" on all 20; other columns equal; 18 rows differ (line 625); notes empty; hashes `65cc1b77ec…` and `809744048b…` match line 621 and the H-11 row (cited, not recomputed).
3. No copied text, no interpretation: pass (617-721; line 720 states the all-"yes" supported column as a fact, "Not interpreted here").
4. Redaction Grep `[0-9]{12,}|scholarchain-course-|arn:` on EVALUATION.md: 0 hits.
5. Consistency: no line still calls the rating pending (890, 891, 13, 573 consistent); line 370 overstated (F1).
6. D7 rubric citation: advisory (A2).

Findings:
- **F1 (Low, required; experiment-engineer).** `experiments/EVALUATION.md:370` says all figures in 6.1-6.5 come from frozen run directories under `RUNS` with `RECORD_COMPLETE=yes`; 6.4's two CSVs and SHA256SUMS are in `SCD`, frozen by the human's chmod and sha256 record (619-621), not a run record. Add the 6.4 exception pointing to its freeze record.
- **A1 (advisory).** Line 900 says the plan.md rubric text was not re-read, while 623 and 717 cite plan D7 (correct per `plan.md:88`): cite the line or reword 900.
- **A2 (advisory).** 6.4 reports 1-5 means without the scale: point to `plan.md` "Human rating rubric (D7)" (82-85) without copying; line 80 gives the sample design (3 abstentions, 2 free-form), matching the key's `group` (abstention S03, S08, S14; free-form S09, S18), which answers line 718 and explains S08.
- **A3 (nit).** Line 3: "and W2-DOC3 (...) and W2-DOC4", one "and" too many.

Assumption: as-entered and frozen files share the row layout. Next: one repair round (F1, A1-A3 at the owner's discretion); re-review of line 370 and touched lines.
