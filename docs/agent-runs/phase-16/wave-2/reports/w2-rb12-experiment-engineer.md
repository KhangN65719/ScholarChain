# W2-RB12: experiment-engineer report (review 15 findings 1, 3-6)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `sbt quick`; no AWS, no commit.

`experiments/EVALUATION.md`:
- Finding 1 (790): redaction note says "the 12-digit, bucket-prefix and ARN-prefix patterns"; literal tokens removed; records review 15's one hit in this note and none elsewhere.
- Finding 3 (545, 6.2.3): "metered" backed by all 57 `model-calls.jsonl` lines with `promptTokens` > 0 and `completionTokens` > 0 (review 15, reviewer Grep 57/57); states that `tokensEstimated` and `haikuCallsChargedAtWorstCase` cannot by themselves detect a reply without usage (review 15 finding 2). Same, shorter, at 783 (section 8).
- Finding 4 (577): `answersSha256` prefix `efde559265…` → `efde5592e1…`.
- Finding 5: removed "the file does not state its denominator" (609); "which the file itself does not state" → "as the note states" (613).

Finding 6: `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/AnswerAuditSuite.scala` new test "RankingMetricKeys equals the runtime's metric table" asserting `AuditRules.RankingMetricKeys == CollaborationMetric.values.flatMap(m => MetricValues.field(m).map(m.wire -> _)).toMap`; imports `scholarchain.core.CollaborationMetric`, `scholarchain.rag.MetricValues`; existing tests kept.

`sbt quick` (one bare run): `Passed: Total 234, Failed 0, Errors 0`; `Passed: Total 1306, Failed 0, Errors 0`; `[success] Total time: 155 s (0:02:35.0), completed Nov 19, 2026, 7:57:04 AM`.

Unverified (agent): whether the new test ran; literal-token removal not re-scanned (grep refused); `h-audit-2/run-manifest.json` not located, so `efde5592e1` taken from review 15; `labelsSha256` `f7cf669519…` and `6e5342ddd6` not rechecked.

---

Orchestrator note: `experiments/evaluation/target/test-reports/TEST-scholarchain.evaluation.answer.AnswerAuditSuite.xml` shows `tests="33" errors="0" failures="0"`, timestamp 2026-11-19T07:54:37 (32 before), so the new test ran and passed.
