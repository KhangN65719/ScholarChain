# W2-DOC4: experiment-engineer report (EVALUATION.md 6.4, the human rating)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE (write); redaction Grep not run** (Bash grep refused for the role, no Grep tool). No commit, no sbt, no AWS, no Python, nothing hashed.

Changed: `experiments/EVALUATION.md` only: (1) line 3 header adds W2-DOC4; (2) section 6.4 "NOT DERIVED: pending" replaced by sources, freeze record, procedure facts, P16-46 correction and 6.4.1-6.4.6; (3) section 8 NOT DERIVED bullet: "the human rating (6.4)" removed; (4) section 8 pending-gates bullet: rating filled, clean-checkout build still pending; (5) "Sources used": rating files, key and P16-46 added; `rating-sample-key.json` removed from "Not read" (`rating-sample.csv` stays: not opened). Not edited: line 13, line 370 ("All figures in 6.1 to 6.5 come from frozen run directories"; now slightly overstated since the 6.4 files are in `SCD` outside the run directories, which 6.4 itself states), line 573.

Method: guard refused Bash grep/head; frozen CSV read in full with Read; as-entered CSV: final line of each of the 20 rows only; key, SHA256SUMS and decisions read.

Per-row (frozen), usefulness/clarity/supported/abstention: S01 3/3/yes/na; S02 4/4/yes/no; S03 5/5/yes/yes; S04 4/5/yes/na; S05 5/5/yes/na; S06 5/5/yes/na; S07 5/2/yes/na; S08 5/5/yes/na; S09 4/5/yes/na; S10 5/5/yes/na; S11 4/5/yes/na; S12 5/5/yes/na; S13 4/3/yes/na; S14 2/3/yes/yes; S15 4/5/yes/na; S16 3/2/yes/no; S17 5/5/yes/na; S18 2/5/yes/no; S19 3/4/yes/na; S20 3/2/yes/no.

Distributions 1..5: usefulness 0,2,4,6,8 (sum 4+12+24+40 = 80); clarity 0,3,3,2,12 (sum 6+9+8+60 = 83). Supported yes 20, no 0. Abstention yes 2 (S03, S14), no 4 (S02, S16, S18, S20), n.a. 14.

Means: all 80/20 = 4.00 and 83/20 = 4.15; answered (80-19)/14 = 61/14 = 4.357 and (83-21)/14 = 62/14 = 4.429; refused 19/6 = 3.167 (4+5+2+3+2+3) and 21/6 = 3.50 (4+5+3+2+5+2).

Strata (key): researcher-lookup 3 (1 refused), coauthor-lookup 3 (0), ranking 3 (0), shortest-path 2 (2), community-component 2 (0), temporal-comparison 2 (0), unanswerable 2 (2), free-form 3 (1); total 20 rows, 6 refusals. Key `abstained` flags agree with the answer reading on all 20. S08: group "abstention", stratum "free-form", answered.

Against the human's figures: every figure matches (20 rows, six means, supported 20/20, abstention 2/4/14). No mismatch.

As-entered vs frozen: as-entered abstention "yes" on all 20; usefulness, clarity, supported, note equal; 18 rows differ in abstention only (14 to n.a., 4 to no). Note column empty on all 20 in both files ("0 rows have a note" in 6.4). Frozen abstention values come from the human's instruction applied by Claude in chat (P16-46), as 6.4 states.

Freeze record: SHA256SUMS lists `65cc1b77…` (frozen) and `809744048b…` (as entered), equal to the work order; cited as the human's record, not recomputed.

Unverified: as-entered question/answer/evidence cells not compared cell by cell (6.4 says so); plan D7 rubric text not re-read, scale definitions not quoted; whether the rater wrote the free-form questions: stated as unknown. 6.4.6 notes, without interpretation, that all rows including the 6 refusals are marked supported "yes".

Next gate: code-reviewer; human clean-checkout build pending.
