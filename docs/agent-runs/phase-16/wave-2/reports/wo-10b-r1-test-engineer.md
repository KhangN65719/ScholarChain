# Injection-string note repair (review 25 findings 15-16): test-engineer report

Author: test-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `tests/scripts/README.md`; text edit only; no sbt, AWS or commit. Line 83 untouched. Paragraph still line 151 (rewritten from "Two suites use an obedient model" onward); list added at 152-158 (file 152 to 159 lines).

Finding 16 (per `tests/ADVERSARIAL_COVERAGE.md` row 12, `Phase11FaultSuite` also has an obedient model whose false claim is removed). New text from the end of line 151:

> Two suites use an obedient model: `tests/adversarial/Phase9ModelSuite.scala` (injection in a snippet, a display name and the question, and an injected planner reply naming a tool outside the allowlist) and `tests/adversarial/Phase11FaultSuite.scala` (injection in a stored summary, the abstract-like text, reaches the model; the obedient model's false claim is removed and the snippet stays evidence, cited only as descriptive context). `tests/adversarial/Phase9RepairSuite.scala` (row 12 lists its M2 case: name, title and snippet flagged and under an untrusted key) and `tests/adversarial/Phase10SecuritySuite.scala` (over HTTP) are listed for the same topic in `tests/ADVERSARIAL_COVERAGE.md` (row 12). Further test files and a fixture in the same area, not named in row 12 (paths confirmed to exist; their bodies were not re-read for this note):

Finding 15 (lines 152-158): `modules/rag/src/test/scala/scholarchain/rag/EvidenceBuilderSuite.scala`, `ModelPortSuite.scala`, `PlannerSuite.scala`, `RepairRound1Suite.scala`, `WorkflowSuite.scala`; `tests/adversarial/Phase11CarryOverSuite.scala`; `tests/adversarial/Phase9World.scala` (a fixture, not a suite). Paths confirmed by reading line 1 of each.

Unverified: whether each of the seven added files uses injection text (named per the review's Grep finding; bodies not re-read; the note says so).
