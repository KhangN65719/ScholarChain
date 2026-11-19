# W2-DOC3: experiment-engineer report (EVALUATION.md: h-audit-2, P16-43/44)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md` edited; no .scala, sbt, AWS or commit; no 12-digit number, bucket name, ARN or question/answer text written. Grep, `wc` and pipelines refused; redaction scan by eye from read-back.

**1. h-audit-2 (6.3)** from `h-audit-2/audit-summary.json` (all fields), `run-manifest.json`, all 56 lines of `audit-answers.jsonl`:

| Figure | h-audit-1 | h-audit-2 |
|---|---|---|
| Ranking validity | 12 checked, 1 valid, 0.083333 | 12 checked, 12 valid, 1 |
| Export-matched | 540 | 585 |
| Evidence-only | 70 | 25 |
| `exportMatchedRate` | 0.880914 | 0.954323 |
| Claims citing invalid evidence | 59 | 0 |

Unchanged: checked 613, unmatched 3 (585 + 25 + 3 = 613). Header now h-audit-2 (`finishedAt` 2026-11-19T13:43:01Z; same `answersSha256` prefix as H); ranking-by-question 12 of 12 `ok`; ranking paragraph and known item rewritten; h-audit-1 superseded note once in 6.3 in the requested wording. No other figure moved (answers 56/34/22; 199 claims; coauthor 28/28; publications 6/6; metrics 93/93; groups 9/9; identities 22/22; contradictionRate 0.010050 (2); abstention recall 0.9; falseAbstentionRate 0.282609; reason-code match 1; repeat 1, 1, 0.8, 0.65; verdicts 140/68/59 and 33/1). 6.2.2, 6.2.4, 6.2.5 audit citations re-pointed to h-audit-2 (22 abstentions, reason codes and 12 ranking claim counts unchanged). The two contradicted claims: Q-CA-01 and Q-CA-05 (added to 6.3).

**2. Spend (P16-44, P16-43).** 6.2.3 and the known item: H's 0.891881 USD "metered from Converse usage tokens" (all 57 calls `tokensEstimated` false; `haikuCallsChargedAtWorstCase` 0; summary flag a defect, review 14 finding 3, fixed in WO-3 part 5; recorded run keeps the old flag); "upper-leaning" and "do not reconcile" removed. Billed H-1c: 10.39 USD month-to-date, +1.46 since H-1b (8.93, 2026-11-18 18:29), Phase 16 Bedrock usage (H, h4 diagnostics 1-4, G); dashboard forecast 300.96 an extrapolation; headroom about 3.61. 6.2.1 `tokensEstimated | true` row marked a defect. H-1c added to 5.1 spend sum, 7.2 rerun cost and the pending-gates line. Arithmetic: file estimates 1.147985 (0.891881 + 0.255987 + 0.000117) vs 1.46 billed; difference 0.312015 not explained in the files read; H's own billed share NOT DERIVED (billing not itemised by run).

**3.** SUMMARY.txt rewrite recorded in 4.4 and known items (P16-44).

**4.** Redaction note: file's own scan by hand; review 14's Grep covered the previous revision only. Header lists W2-DOC3.

Not found: no separate Phase 16 cost-summary section; no separate h-audit-2 log read (console line from `human-commands.md`). Next: independent review with the 12-digit, bucket-name, ARN search.
