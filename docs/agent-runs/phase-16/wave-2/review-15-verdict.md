# Phase 16 review 15 verdict (re-review of review 14: W2-RB11, W2-TR5, WO-3 part 5, W2-DOC3)

Author: code-reviewer. Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES.** Reviews 14-15 do not close.

## Findings
1. **BLOCKER (by rule), doc, experiment-engineer.** `experiments/EVALUATION.md:790`: the redaction note quotes the search patterns (bucket prefix and ARN prefix) literally, so every future scan hits. No account id, bucket name or ARN present; `[0-9]{12,}` 0 hits. Reword without the literal tokens.
2. **MAJOR, code, rag-engineer (new).** `modules/rag/src/main/scala/scholarchain/rag/BedrockAnswerModel.scala:78-82, 208`: a Converse reply with no `usage` is recorded as 0 tokens with `tokensEstimated = false` hard-coded, so `CostGuard.scala:171-174` charges it 0 as metered instead of the worst case its comment (`:76-80`) promises: a hole in the spend ceiling for live runs. No test (`BedrockAnswerModelSuite.scala:80` covers only a reply with usage). Phase 16 unaffected: H `model-calls.jsonl` 57 of 57 lines with `promptTokens` > 0 and `completionTokens` > 0. Repair: mark a reply without usage estimated (or fail it), test; or the human defers to Phase 17 by a recorded decision.
3. **MINOR, doc, experiment-engineer.** `EVALUATION.md:545`, `:783` back "metered" with `tokensEstimated` false and `haikuCallsChargedAtWorstCase` 0; per finding 2 neither detects missing usage. P16-44's condition is met by different evidence: all 57 calls have nonzero prompt and completion tokens (reviewer Grep, 57/57). Cite that.
4. **MINOR, doc, experiment-engineer.** `EVALUATION.md:577` `answersSha256` `efde559265…`; `h-audit-2/run-manifest.json:5` (and h-audit-1) `efde5592e1…`.
5. **MINOR, doc, experiment-engineer.** `EVALUATION.md:613` "the file itself does not state" contradicts `verifierSelfReported.note` ("before it removed or weakened claims"), quoted in the same paragraph. Remove the clause.
6. **MINOR, code/test, experiment-engineer.** `AnswerAuditSuite.scala:235-241` checks `RankingMetricKeys` against itself (count 9, camelCase), not against the runtime. Assert `RankingMetricKeys == CollaborationMetric.values.flatMap(m => MetricValues.field(m).map(m.wire -> _)).toMap`, or build the table that way.
7. **ADVISORY, service-engineer.** The flag exposed by `EvidenceService.scala:114` now returns false with no call or all metered; no services test pins it.

## Checks
- Redaction: one hit (finding 1).
- AnswerAudit: `RankingMetricKeys` (`AnswerAudit.scala:174-184`) equals `MetricValues.field` (`RuntimeSupport.scala:73-87`); all 9 in `CollaborationMetric` (`WireEnums.scala:49-63`), only `shortest-path-length` out (no runtime field); ranking fact writes `r.metric.wire` (`EvidenceBuilder.scala:226`); code order `:324-331` sound (key-missing only when every row present); `exportNumbers` `:376-386` uses the same table; tests not tautological (kebab defaults `AuditFixture.scala:258`, `P16W2AnswerAuditSuite.scala:250`; claim-level `AnswerAuditSuite.scala:267-281`; adversarial `:618-676` cover every code).
- h-audit-2 figures: every `audit-summary.json` field matches 6.3; 12 ranking checks `ok:true`; contradictions lines 8, 12 (Q-CA-01, Q-CA-05); `finishedAt` right; superseded note `:579` matches h-audit-1 (1 of 12, 0.8809135, 59); one mismatch (finding 4).
- `ModelUsage.zero`: identity (`Model.scala:667`), `+` ORs (`:659`); no-call paths (`Planner.scala:94, 103, 112`; `Model.scala:703, 709`) false; remaining true-expecting tests make stub calls (`Phase9GateSuite.scala:353-354`, `WorkflowSuite.scala:704`, `PlannerSuite.scala:186, 238`); `ModelPortSuite.scala:137-196` proves all-metered false (`:177-182`, fails under the old zero), any estimated true (`:145-154`, `:184-189`), no call false (`:191-196`).
- Spend wording: H-1c figures match P16-43; arithmetic 1.147985 and 0.312015 right; "not explained" and "NOT DERIVED" not over-interpreted; cited evidence wrong (findings 2, 3).
- Review-14 findings 4-8 present and accurate (`:541`; `:639`, `:785`; `:270`, `:784`; `:781`, `:790`; `:613` except finding 5).

Evidence from owners (not rerun): test-engineer `sbt quick` 1306/0 at 7:37:21; rag-engineer 1306/0 at 7:46:36. Pending: owner `sbt quick` after repairs (findings 2, 6); human `sbt clean check`; human rating (6.4); clean-checkout build. Assumptions: audit manifests' `answersSha256` is H's answers file; G cost 0.000117 rests on review 14. Risk: finding 2 for any live Phase 17 run until repaired or deferred.
