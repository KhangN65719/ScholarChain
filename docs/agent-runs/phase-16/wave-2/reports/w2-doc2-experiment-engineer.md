# W2-DOC2: experiment-engineer report (EVALUATION.md section 6, reproducibility, P16-42)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Wrote `experiments/EVALUATION.md` only. No sbt, AWS, Python, git or commit. Next gate: independent review.

**Sections changed**
- 1-5 kept from the W2-DOC1 draft, with: every sha256 shortened to its first 10 hex (a full hash, the `previousFiles` hash for `questions-v1.jsonl`, held a 14-digit run); 3.3 spot-check in the exact P16-42 wording; 4.1 names P16-15 as the H-1 reconciliation (8.91 USD, session 2 about 2.73); 4.4 cites only the local `phase16-ci-on/` directory; 5.6 points to 6.2 for the claim cap across H.
- 6 replaces the placeholders: 6.1 G retrieval, 6.2 H answers, 6.3 audit, 6.4 rating, 6.5 ER (replay and ErExtras). Floats to 6 decimals (full doubles would trip the 12-digit scan).
- 7 (new) reproducibility: "From a clean checkout" with absolute paths plus `sbt clean check`; AWS-free reruns (GraphChecks, FactoryCounts, ER replay and ErExtras, AnswerAudit) with new run ids; G and H point to `human-commands.md`; results "NOT DERIVED: pending the human's clean-checkout build today".
- 8 (new) known items, incl. the account-id and redaction follow-up.

**Sources (new)**: G `exp-G-…/run-record.env` (`FINAL_STATUS`, `ELAPSED_SECONDS`, `ESTIMATED_SPEND_USD`, `GIT_SHA`, `MAX_TASKS`, `SPEND_CEILING_USD`), `raw/retrieval/summary.json` (`<mode>.scopes.{all,vectorAvailable}.{pooled,<stratum>}.metrics.*.{mean,low,high,n}`, `outcomes`, `perRepeat`, `repeatStability`), `raw/retrieval/run-manifest.json` (`config`, `counts`, `ragIdentity`, `wiring`, `cost`); H `exp-H-…/run-record.env`, `raw/answers/{answer-summary.json,model-attempts-summary.json,model-calls.jsonl,run-manifest.json}`; audit `h-audit-1/audit-summary.json` (every field), `audit-answers.jsonl` (`abstained`, `abstentionReason`, `claims`, `items[].checks[]`), `run-manifest.json`; benchmark `questions-v1.jsonl` (`stratum`), `labels-v1.jsonl` (`expected.expectedCodes`, lines 41-48); ER `er-replay-v3-r3-1/evaluation-report.txt`, `erextras-1/{er-extras-report.txt,run-info.json}`; `scripts/README.md` "From a clean checkout"; `human-commands.md`.

**Self-scan**: grep refused; scanned by hand. No account id, bucket name, ARN, `RAW_DIR` fragment or question/answer text copied (incl. `run-record.env` `ACCOUNT_ID`). Fixed four 12-digit-run cases (a 14-digit run in a full sha256; long floats such as `exportMatchedRate`, `falseAbstentionRate`, per-repeat recall). Read back through section 1.3, 4.4-5.4 and 6.1-6.1.3; the rest of 6 and 7 not re-read. The strict 12-digit scan should be run before any copy.

**Not found or not derivable** (marked NOT DERIVED or NOT STATED): human rating; H-1c billed cost; clean-checkout build; B-F regeneration; H-8; H-9; deployment record check; cause of the audit ranking failures; G's bootstrap confidence level (no field); what G's `cost.haikuCalls` 210 counts in a stub run; H `retryDecisions` (field absent in `model-attempts-summary.json`); per-stratum CIs (in G's `summary.json`, not reproduced; pooled CIs plus per-stratum means shown).

**For the human**
1. Ranking validity in the audit is 1 of 12; the 11 failures all carry `problem` "value-differs" (negative result kept as is; cause not in the files read; `audit-claims.jsonl` and the check rule would show it).
2. Q-FF-06 has `claims` 6 for a top-3 question; cap entries + 2 = 5; whether the audit's `claims` counts the same thing as the cap is unknown (flagged, not concluded).
3. H token flags disagree: `answer-summary.json` `tokensEstimated` true; all 57 `model-calls.jsonl` lines false and `haikuCallsChargedAtWorstCase` 0; 0.891881 USD equals metered tokens × rates plus the Titan estimate. Stated "upper-leaning estimate" per P16-42 with the observation beside it.
4. The replay prints "gold labels changed: 4 of 153 pass-3 pairs" alongside equal pooled rows and 0 of 230 decisions changed; not reconciled.
5. Rule 6 across H: the cap rejected 2 replies, both `end_turn`; 0 `max_tokens`; no primary `synthesis-failed`. "Each rejected reply was followed by an accepted one" rests on arithmetic assuming one accepted synthesis call per answered record (marked).
6. Pending: Claude in chat's `SUMMARY.txt` path rewrite; the follow-up to stop sbt echoing bucket names; the suggested clean-checkout path `/Users/winnguyen/scholarchain-data/clean-checkout-1/` is a suggestion only.
