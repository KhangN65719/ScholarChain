# WO-10-R1: documentation-engineer report (review 25 findings 5-14, docs/evaluation.md)

Author: documentation-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `docs/evaluation.md`; no commands, sbt, AWS, Python or commit. Each finding checked against the cited EVALUATION.md sections.

- 5 (truncation history, now item 3): non-ranking question already accepted at 2,000 (1,990 tokens, `end_turn`, 5.2); the ranking question hit `max_tokens` on all three attempts at 4,096 (5.3); the raise "fixed no recorded failure"; "headroom" labelled a reading.
- 6: "section 5.3 below" became "section 5 item 3 below".
- 7 (3.3): "each task had a single human"; same person not stated (6.4.6).
- 8 (item 1): 11 of the 13 false abstentions (1+4+4+2; 1 `evidence-cap`, 1 `researcher-count`), counted by reading (6.2.4); stratum rates a separate sentence.
- 9 (item 5): "the one place" removed; otherwise compared only in aggregate; 267 verifier verdicts vs 199 audited claims unexplained (6.3); "both VERIFIED" kept.
- 10 (3.1): free-form (0.4689, n 6) near coauthor-lookup (0.5000), above shortest-path and community-component (0): comparison selective; nDCG a confounded stand-in for planner accuracy; ranking and temporal-comparison "entirely template questions".
- 11: verifier window-year item deleted, no replacement; items renumbered (4-13 to 3-12), cross-references updated.
- 12: six missing-data rows (F1 INVALID 4.2; fused vector-stage skip 4.2; D abstention reasons 4.5; which two H replies the cap rejected 6.2.2; Haiku temperature 6.2.6; 5 non-DBLP traced edges 1.2); fused skip also negative result item 14; UNVERIFIED added to the label lists in sections 1 and 4. H-12 subsection untouched.
- 13 (2.5): 4.5 cited as "sampling variation under the iid assumption only"; "at least as large as the intervals" labelled "Reading (mine)".
- 14: 0.917 "a sensitivity figure under a proxy"; E end-to-end p50s (26.847, 332.538, 342.416 ms) and stage p50s given separately, vector-stage reading marked; strata n qualified (all questions; vector-available free-form n 3; unanswerable n 0); 6.3 list adds 6 publications and 9 groups; ER precision cites Wilson [0.952, 1.000] (6.5.1), degenerate bootstrap not relied on.

Unverified or open: "all 3 `max_tokens` stops at 2,000 were the ranking question" derived from 5.2 and 5.3 (labelled derived); fused skip cause UNVERIFIED; H-12 item unchanged pending the human; header and appendix not updated for R1, links not re-verified this round.
