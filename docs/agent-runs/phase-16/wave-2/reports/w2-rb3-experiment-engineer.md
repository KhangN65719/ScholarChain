# W2-RB3: experiment-engineer report (review 3 N1, docs-only)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Option (a). Changed `experiments/README.md` only; no sbt, AWS, commit or Scala edit.

Source: the eight `experiments/manifests/experiment-*.manifest` files (`MAX_MINUTES`, `HOURLY_BURN_USD`, `SPEND_CEILING_USD`); projected = burn × max minutes / 60.

| Id | Before (max min / burn / projected / ceiling) | After |
|---|---|---|
| A | 60 / 0.60 / 0.60 / 0.75 | 75 / 0.60 / 0.75 / 0.75 |
| B | 90 / 2.02 / 3.03 / 3.50 | 90 / 1.55 / 2.325 / 2.40 |
| C | 60 / 0.70 / 0.70 / 1.00 | 60 / 0.45 / 0.45 / 0.60 |
| D | 90 / 0.85 / 1.275 / 1.50 | 70 / 0.55 / 0.642 / 0.65 |
| E | 60 / 0.62 / 0.62 / 0.80 | 60 / 0.42 / 0.42 / 0.45 |
| F | 30 / 0.62 / 0.31 / 0.50 | 30 / 0.40 / 0.20 / 0.25 |
| G, H | unchanged | 60 / 0.10 / 0.10 / 0.25; 90 / 0.80 / 1.20 / 2.00 |

Sum, now labelled "Sum A to H" (before 7.835 / 10.30):
- Projected: 0.75 + 2.325 + 0.45 + 0.6417 + 0.42 + 0.20 + 0.10 + 1.20 = 6.087 (A-F 4.787; G+H 1.30).
- Ceilings: 0.75 + 2.40 + 0.60 + 0.65 + 0.45 + 0.25 + 0.25 + 2.00 = 7.35 (A-F 5.10; G+H 2.25).
- The reviewer's "7.35 across A-F" is A to H; A-F alone is 5.10.

Other edits: a source and arithmetic paragraph under the table; nearest-estimate-row cells for B, D, E say the mapping is the Phase 13 one; the C cell describes the current ceiling basis; line 81 "(90 and 70 minutes)" for B and D; line 5 says the table was refreshed in W2-RB3 from manifests A to H (A and B rows of the experiments table and the "Per-key" notes still marked as predating it).

Not changed, flagged: lines 28 and 30 ("B to F are tier M and stay drafts") are Phase 13/14 text outside N1; the "Nearest estimate row" LOW/HIGH figures.
