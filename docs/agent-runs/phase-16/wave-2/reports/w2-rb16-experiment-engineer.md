# W2-RB16: experiment-engineer report (review 21 repairs, EVALUATION.md)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md` edited; no sbt, AWS, Python or commit; no re-read after the edits.

- 147: "No count here is recomputed from `factory-evidence.tsv` or `factory-counts.json` (not read for this subsection)." became "No count here is recomputed. `factory-counts.json` was read only at lines 168, 262, 265, 453-455 and 462-464, for comparison (cited in 149 and 169); `factory-evidence.tsv` was not read."
- 927: "that is separate evidence from the review, not a result of the author's scan." became "that is review evidence, separate from the author's scan; review 21's Grep found 0 hits."
- 935 (Sources): appended to the W2-DOC5 line (934 untouched): "Added by W2-RB15 (2.3a, 2.2): `factorycounts-1/factory-counts.json` lines 168, 262, 265 and 453-464, read for comparison only (the "first 150 lines" entry above is the earlier reader)." The section references "(2.3a, 2.2)" are inferred from the citations at 149, 169, 924.
