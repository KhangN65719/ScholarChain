# W2-RB18: experiment-engineer report (review 25 findings 1-4, H-12 correction)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md`; no sbt, AWS, Python or commit; no `/Users/winnguyen/...` path changed; no re-read after editing.

- Line 3: added "and W2-RB18 (2026-11-19: the H-12 counts corrected per P16-47 and review 25 findings 1 to 4, sections 7 and 8)".
- Line 827: B-F status "partly confirmed in the compare log and partly in conflict with the relay" became "recorded by W2-DOC7 and corrected by W2-RB18 … 42 of 44 committed derived files identical, counts corrected by the human, P16-47".
- Line 932, finding 1: removed the relayed block (F2 15, C 12, 47 of 49), the conflict paragraph and "both 49 and the relay's figures need … correction"; added "Corrected result (P16-47 …)": 42 of 44 identical (D 9/9, E 5/5 original title, F2 12/12, C 10/10, B 6/8; B differences attributed to the relay, files not opened); F2 15 and C 12 were miscounts corrected by the human, earlier 47 of 49 superseded; compare log 49 lines, 46 SAME, 3 DIFF, incl. a 5-file other-title E run (4 SAME, 1 DIFF `latency.svg`) outside the 44.
- Line 932, finding 2: `e-latency-titlematch` "inferred from the directory listing, not stated in the log".
- Line 932, finding 3: regen log "8 `DERIVE_COMPLETE` lines (D 2, E 1, F2 3, C 2) and none for B nor for the title-matched E rerun (review 25)".
- Line 948, finding 4: NOT DERIVED bullet now only the Phase 15 billed total and the sample pipeline replay; new bullet "Recorded elsewhere in this file, not NOT DERIVED" with the corrected H-12 facts (7.3) and the clean-checkout facts (7.1).
- Sources: unchanged.

Risk: "Not confirmed (rest on the relay alone)" stands (B difference cause, `--title` value, sha256 method not independently read).
