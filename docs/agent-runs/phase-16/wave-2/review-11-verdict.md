# Phase 16 review 11 verdict (re-review of review 10 repairs: rag repair, W2-TR4, W2-RB8)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: APPROVE.** No review-10 item needs another repair round; two LOW findings and one observation, none blocking.

| # | Status | Evidence |
|---|---|---|
| 1 | RESOLVED | `Model.scala:86` "For a ranking, keep each claim…"; `RankingClaimCapSuite.scala:161-167` checks new wording present, old gone; `ada69809…` repinned in `ModelPortSuite.scala:64`, `Phase9RepairSuite.scala:612`; `c3d58edb` remains only in `docs/agent-runs` reports. |
| 2 | RESOLVED (P16-41) | `Planner.scala:21` `maxLimit = 12`, `limitOf` `:225-231`; `RankingClaimCapSuite.scala:133-149` from `PlannerConfig().maxLimit` (14 claims, 3,482 < 4096); `PlannerSuite.scala:108-120` top 500 and 20 → 12, 12 stays. |
| 3 | RESOLVED | `StubModel.scala:279-292`; `RankingClaimCapSuite.scala:304-326`. |
| 4 | RESOLVED | `RankingClaimCapSuite.scala:240-261`; matches `stopOnMaxTokens = true` at `Synthesis.scala:139`. |
| 5 | RESOLVED | `h-answers/README.md:24` arithmetic checks (0.140956; old 0.140376); `:35` separates rule-6 cases. 1530 characters not measured by the reviewer; `CostGuardSuite` checks 0.15 against the live text. |
| 6 | RESOLVED | ragIdentity live (`RagIdentity.scala:20-25`; run's `maxLimit` passed at `AnswerEval.scala:391-393`, `RetrievalEval.scala:440-442`; `RunIdentitySuite.scala:91-112` with 7); no text; `PromptRec.ofAudit` (`AnswerRecords.scala:195-201,357`) from `payload("prompts")` (`Workflow.scala:654` always writes it, `:239` per model call). H manifest values `:74-91` equal the JSON copy; `manifest-json.py:35` skips comments; `tests/scripts/run.sh` pins no H digest (no 64-hex literal, copies manifests at run time `:464`, `:1788`); `P16W2CostGuardSuite.scala:284-352` checks values only. |
| 8 | RESOLVED (documented) | `h-answers/README.md:76-77`, H manifest `:48-53`; repeat-pass claim partly right (finding A). |

Version 1.2.0 kept with a new hash: acceptable (never committed or used live; `h4-diagnostic-3` ran on 1.1.0; runs now record the sha256).

Top-N > 12 (reviewer's Grep for `top 13`-`top 999` and "N most|top|highest|largest", excluding `docs/agent-runs`): only `PlannerSuite:109,114`, `RankingClaimCapSuite:147` (intended) and `Phase9RepairSuite.scala:738-739`. `top 1000` in `Phase11Suite`, `Phase11CarryOverSuite`, `Phase11WindowAgreementSuite` does not match `limitPattern` (`Planner.scala:247`, 1-3 digits + word boundary). No doc depended on 20. `resultLimit` tuples (`Phase9GateSuite:45`, `Phase9ModelSuite:33`) compare two runs, not 20.

## New findings
- **A. LOW, experiment-engineer.** `h-answers/README.md:77` and `experiment-h-answers.manifest:51-52` say over-cap ranking answers "abstain… add no repeat calls": true only if all 3 attempts fail; a reply rejected twice then accepted is answered, can enter the repeat pass (`AnswerEval.scala:285-296`) and cost up to 3 calls again. About 4 ranking questions in the 20-question repeat (estimate) add ≈ 0.22 USD → ≈ 2.86 total, under 3.00 but thinner headroom; the USD stop may end the run early. Fix the wording (manifest digest changes; no approval record yet).
- **B. LOW, orchestrator.** `human-commands.md:60` still uses `--run-id h4-diagnostic-3` and the `-3` log, relying on a manual note; replace with the README `:40-43` form.
- **C. Observation, test-engineer, optional.** `Phase9RepairSuite.scala:734-755` "m3: every default-cap workflow input fits, none abstains on budget" asks "top 20" (clamps to 12; still the largest allowed, not weakened; text misleading); `RankingClaimCapSuite.scala:143` comment "one more entry than the planner allows" inaccurate for a top-20 check.

Not performed: any run; no diff; source changes after 23:01 not confirmable; pending agent `sbt check`, human `sbt clean check`, commit; 1530 characters and `ada69809…` rest on passing suites; Haiku compliance; real per-claim size; live runs.
