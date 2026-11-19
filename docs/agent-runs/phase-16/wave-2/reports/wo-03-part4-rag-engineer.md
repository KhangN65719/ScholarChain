# WO-3 part 4: rag-engineer report (ranking claim cap, P16-36/38/40)

Author: rag-engineer. Received by the orchestrator on 2026-11-18 (after a continuation message; the first report was lost to the Stop gate) and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Bare `sbt quick`: `Passed: Total 77, Failed 0, Errors 0`; `Passed: Total 1304, Failed 0, Errors 0`; `[success] Total time: 150 s (0:02:30.0), completed Nov 18, 2026, 7:03:36 PM`. No `sbt check`, no live call. JUnit (19:01 run): `RankingClaimCapSuite` (new) 11/0; `Phase11ClaimIdSuite` 8/0; `RepairRound1Suite` 26/0; `Phase9RepairSuite` (test-engineer's repin) 25/0. Earlier (18:44): `ReplyMeasuresSuite` 18, `ModelPortSuite` 14, `SynthesisOutputLimitSuite` 5, all 0 failures.

## Root cause
- Evidence: `Workflow.scala:500-507` (`rank`) builds the ranking fact plus one metrics item per entry (6 deterministic items for 5 entries plus semantic; matches 8 and 10).
- Prompt: synthesis 1.1.0 rule 5 asks for definition claims for five ideas; nothing on rankings.
- Schema: `Model.scala:314` allows 24 claims; at 227-238 tokens per claim, 17-18 claims fill 4096.
- Split of the 17-18 (5 ranking + per-entry metric + 5 definitions + semantic) inferred; reply text not seen.

## Fix
- Prompt synthesis 1.1.0 → 1.2.0, new rule 6 (`Model.scala`), keyed on a ranking fact in the evidence: one `ranking` claim per entry in rank order citing the ranking item; no metric, coauthor, publication or path claim for ranked researchers; at most one definition claim (the ranked metric) and one semantic claim; one short sentence per claim and for the analysis. Old rule 6 → 7; rule 5 points to 6.
- Check (`Synthesis.scala`): with a ranking fact, a reply with more than `entries + 2` claims is rejected with fixed reason `"too many claims for a ranking"` (added to `RejectionReason`) and retried with the hint. Rejection over trimming (a trim could drop required ranking evidence). Uses `InputShape.rankingEntries`; named-researcher questions uncapped.
- Schema `synthesis-output/3` and verifier unchanged (every claim still cites evidence). The prompt is the real fix; the check catches only completed over-cap replies (a reply cut at 4096 never reaches it).

## Arithmetic (238 tokens per claim measured; ~150 frame estimated)

| Ranking | Cap | Tokens | With frame | Share of 4096 |
|---|---|---|---|---|
| 5 entries | 7 | 1,666 | ~1,816 | 44% |
| 10 entries | 12 | 2,856 | ~3,006 | 73% |

Old: 17-18 claims = 4,039-4,096. The 10-entry case is under but not "well under"; the short-text rule should lower per-claim size (unmeasured).

## Versions and pins
Prompt `synthesis` 1.2.0, hash `c3d58edbce95fb30fc1bc30bb52ed77070c731e040200ebda287a196dadb6b9d` (test-engineer verified independently); schema unchanged. Pins updated: `ModelPortSuite` m2; `SynthesisOutputLimitSuite` (test renamed "the limit leaves the schema version unchanged"); `RepairRound1Suite:514`. `tests/adversarial/Phase9RepairSuite` m2 repinned by test-engineer (W2-TR3).

## Continuation fixes
1. `RepairRound1Suite:514` "M2: prompt rule 3 covers every string field in facts and the gaps": asserts `"1.2.0"`; rule 3 untouched, other assertions hold.
2. `Phase11ClaimIdSuite:199` "n1: an abstention beside a kept claim does not abstain the answer": the stub ranking answer already holds the capped count; the tamper appended an abstention (`entries + 3`), rejected 3 times. The fixture was wrong, not production (rule 6 is the spec; unanswerable parts belong in `missingEvidence`; a third extra claim would take the 10-entry case to 81%). The abstention now replaces the last claim (`claims.init :+ abstention`), with a comment. Still proves an abstention beside kept ranking claims does not abstain the answer and is present.

## Tests (`RankingClaimCapSuite`)
"the cap is the entry count plus two"; "capped replies fit the 4096 limit at the measured claim size"; "the synthesis prompt is 1.2.0 and states the ranking rule"; "5 entries: 7 claims are accepted, 8 are rejected"; "10 entries: 12 claims are accepted, 13 are rejected"; "the reply of h4-diagnostic-3 (17 claims, 5 entries) is rejected"; "an over-cap reply is retried with the problem, then accepted"; "the rejection reason is a fixed code"; "a bundle without a ranking keeps the schema limit only"; "a non-ranking answer is unaffected"; "the stub's ranking answer stays within the cap and still verifies".

## Risks for G/H comparability
Prompt 1.2.0 applies to every synthesis call (non-ranking answers see the rule but are not capped). Ranking answers have fewer claims (no per-entry metric claims, at most one definition), so claim-count or claim-kind metrics differ from 1.1.0. G and H must record prompt 1.2.0 and the new hash; comparisons with the `h4-diagnostic` runs compare different prompts. A completed over-cap reply costs a retry (up to 3 attempts, ~0.027 USD each) then abstains `synthesis-failed`, inside the P16-29 call cap.

## Unverified
Whether Haiku follows rule 6 (main open item: H-4 rerun on Q-RK-01 and Q-RK-02, expect `claimsStarted` ≤ 7 and `end_turn`); per-claim size under the short-text rule and the 150-token frame; which `sbt quick` total is rag; `sbt check` not run.

Files: modified `modules/rag/src/main/scala/scholarchain/rag/{Model,Synthesis}.scala`, `modules/rag/src/test/scala/scholarchain/rag/{ModelPortSuite,SynthesisOutputLimitSuite,RepairRound1Suite,Phase11ClaimIdSuite}.scala`; created `modules/rag/src/test/scala/scholarchain/rag/RankingClaimCapSuite.scala`.
