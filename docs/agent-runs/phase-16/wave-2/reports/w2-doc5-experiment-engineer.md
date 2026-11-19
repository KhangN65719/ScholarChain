# W2-DOC5: experiment-engineer report (EVALUATION.md H-8 and H-9)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md` edited. No sbt, AWS, Python or commit. Not done as instructed: `plan.md` (group 2, group 6, H-8, H-9 rows) not read; content from the `human-commands.md` rows and the two sheets. Guard refused grep; H-8/H-9 mentions found by reading the whole file.

Lines changed:
- Line 3: W2-DOC5 added.
- 1.2 provenance trace paragraph: NOT RECORDED sentence replaced by the human's H-8 result, attributed (sheet by Claude in chat, own script over the frozen trace); 45 of 50 traced edges DBLP-backed; first 10 of those in trace order checked on dblp.org, 10 of 10 OK (both coauthors on the record, year inside the window); selection rule stated (not a fresh random draw); 5 non-DBLP edges outside the check; limits: one auditor, 10 edges, check covers coauthors-on-record and year-in-window only.
- 2.3: H-9 bullet points to new 2.3a; rewrites bullet: 0 stands as the human's statement ("none, confirmed by the human"; `factory-rewrites.tsv` stays empty).
- New 2.3a (human audit of Phase 14): table BLOCKED-ON 5/6, repair files 5/5, human rulings 3/5, refusals 3/4, total 16/20 = 80% (arithmetic 6+5+5+4 = 20, 5+5+3+3 = 16); three false-positive kinds; Q1: Phase 14 reviews in `decisions-d33.md`, `-d34`, `-d35`, so the first-pass "no review" 12 is a known parser limit; per-agent rows with Phase 14 pairs affected, not recomputed, which rows NOT DERIVED; Q2 no rewrites; limits: single auditor, one phase, sample of 20, two categories drawn rather than audited in full.
- Section 8: bullet recording both audits; NOT DERIVED bullet drops H-8/H-9, keeps the Phase 15 billed total, clean-checkout build and B-F regeneration.
- Sources list: the two sheets and the H-8/H-9 rows.
- Section 7 and the clean-checkout line in 8: left pending.

Figures and sources: H-8 (45 of 50, first 10, 10/10) and all H-9 marks are the human's relay (`human-commands.md`). From the H-8 sheet: 10 rows, OK column blank; windows by reading 6 "all", 4 "2020-2029". From the H-9 sheet: printed Phase 14 counts BLOCKED-ON events 6, mentions 71, first-pass no-review 12, refusal prose 9, human rulings 26, repair files 5; draws: all 6 events, all 5 repair files, 5 of 26 rulings, 4 of 9 refusals.

Unverified: "45 of 50" not recounted (`provenance-trace.jsonl` not read); 12 attributed to the sheet and the human, not checked against `factory-counts.json`; sheets' OK columns blank, so both audits are the human's relayed results (text says so); `.html` H-8 copy not read; redaction checked by reading only.
