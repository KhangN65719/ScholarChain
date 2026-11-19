# Phase 16 review 16 verdict (re-review of review 15 repairs: W2-RB12, WO-3 part 6)

Author: code-reviewer. Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: REQUEST CHANGES.** Review 15 findings 1-6 repaired correctly (code side of 2 and 6 approved); one MINOR doc finding created by the order of the repairs.

1. **MINOR, doc, experiment-engineer.** `experiments/EVALUATION.md:545`, `:783` still call review 15 finding 2 "pending" (545: "pending rag-engineer or a human deferral") and say in the present tense that `tokensEstimated` and `haikuCallsChargedAtWorstCase` "cannot by themselves detect a reply without usage"; that is now true only for the build H used (W2-RB12 was written before WO-3 part 6). Repair: H ran before WO-3 part 6; in that build a reply without usage was recorded as 0 tokens with the flag false; fixed in WO-3 part 6 (P16-45); keep the 57/57 evidence as P16-44's basis. Docs only; re-review of 545 and 783 only.

Checks:
- Redaction: Grep `[0-9]{12,}|scholarchain-course-|arn:` 0 hits; line 790 names the patterns without literal tokens (review 15 finding 1 closed).
- Sha prefixes: `h-audit-2/run-manifest.json:5` `efde5592e16f…` vs `:577` `efde5592e1…` (finding 4 closed); manifest line 6 `f7cf66951967…` vs `:577`/`:158` `f7cf669519…`; `6e5342ddd6` matches `eval-v3-r3/evaluation-report.json:7`.
- 57/57: `model-calls.jsonl` 57 lines, all `promptTokens` > 0 and `completionTokens` > 0; under the old code a missing or partial usage block would show 0, so it supports "metered" (finding 3 closed except staleness). Finding 5 closed (613 "as the note states"; 609 clause removed).
- Drift guard: `AnswerAuditSuite.scala:245-250` builds the map from real `CollaborationMetric`/`MetricValues` imports (`:7`, `:10`), `assertEquals`; not tautological; JUnit 33/0 (finding 6 closed).
- Usage fix: `usageReported = in.isDefined && out.isDefined` (`BedrockAnswerModel.scala:85-93`); `tokensEstimated = !reply.usageReported` (`:218`); tests no usage (`BedrockAnswerModelSuite.scala:336`), each partial (`:343`), full metered (`:356`), client flag (`:368`), 1-3 through the real `SdkConverseClient`. `CostGuard.scala:170-178` charges any estimated reply `worstCase(request)` and counts `haikuEstimated` (matches `:76-80`). Other readers conservative OR or record-only (`Model.scala:748`, `AnswerEval.scala:453`, `AnswerRecords.scala:256/289`, `CostGuard.scala:294`, `EvidenceService.scala:114`). Default `usageReported = true` keeps existing fakes. Finding 2 closed.
- Review 15 finding 7 (service-engineer) advisory, recorded only.

Owner evidence (not rerun): experiment-engineer `sbt quick` 1306/0 at 7:57:04; rag-engineer 1306/0 at 8:01:11, `BedrockAnswerModelSuite` 26/0. Pending: the doc repair; human `sbt clean check`; human rating (6.4); clean-checkout build; a CostGuard test of the estimated-reply path not run (`CostGuardSuite.scala:107` may cover it, not confirmed). Risk: whether real Bedrock omits `usage` unobserved.
