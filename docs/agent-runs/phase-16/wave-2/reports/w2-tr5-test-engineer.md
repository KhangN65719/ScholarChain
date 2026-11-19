# W2-TR5: test-engineer report (audit test metric name)

Author: test-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Bare `sbt quick` passes (one run, no background runs): `Passed: Total 77, Failed 0, Errors 0`; `Passed: Total 1306, Failed 0, Errors 0`; `[success] Total time: 148 s (0:02:28.0), completed Nov 19, 2026, 7:37:21 AM`. Marker write not verified. `P16W2AnswerAuditSuite` JUnit: `tests="69" errors="0" failures="0" skipped="0"` (was 67).

File: `tests/adversarial/P16W2AnswerAuditSuite.scala` only (reformatted by the hook). `rankingFact` default now `metric = "publication-count"`; the 4 failing tests pass with unchanged expectations ("a ranking equal to the export, in descending order, is valid"; "a ranking with a wrong value is value-differs"; "a ranking in ascending order is an order problem"; "a ranking that lists an unresolved mention is mention-ranked" — the fixture's `ghost` mention has an export row, so `mention-ranked` stands).

Added: "a ranked mention with no export row is row-missing, not mention-ranked" (`mention:v1:phantom` → `row-missing`); "a camelCase ranking metric is unknown-metric; row-missing and key-missing are distinct from value-differs" (`publicationCount` and `no-such-metric` → `unknown-metric`; `publication-count`, `weighted-degree`, `distinct-coauthor-count` accepted with export values; `bridge-score` → `key-missing` (fixture rows lack the column); `researcher:v1:nobody` → `row-missing`; wrong value → `value-differs`).

Assumptions: `RankingMetricKeys` (`AnswerAudit.scala:174-184`) is the intended specification; fixture rows hold `publicationCount`, `distinctCoauthorCount`, `weightedDegree`, `averageCoauthorsPerPaper`. Risks: the tests exercise the audit's table, not the runtime's `CollaborationMetric.wire` names; no end-to-end test on the real `answers.jsonl` shape (`h-audit-2` shows it).
