# W2-TR2: test-engineer report (H ceiling pins 2.00 -> 3.00, P16-33)

Author: test-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** `sbt quick` passes; nothing else failed.

Changes, all in `tests/adversarial/P16W2CostGuardSuite.scala` ("Committed limits" test): test name and class doc say H 3.00 (P16-33); `SPEND_CEILING_USD` on the manifest pins `3.0` (exact `assertEquals`); same on the `json/` copy; `USD_CEILING` on `experiments/h-answers/run.sh` pins `3.0`; new assertion that `run.sh` contains `--usd-ceiling $USD_CEILING` (previously only `HAIKU_MAX_CALLS` was checked). Line numbers shifted with the formatter, so none given.

Other pins: none found in `P16W2Support`, `P16W2AnswerAuditSuite`, `P16W2TextOnlySuite`, `tests/scripts/w2/expcheck.py` (the `2000` hits there are window years). The guard refused grep, so `tests/**` was not searched in full; not read: other `P16*` suites, Phase 7-11 suites, `tests/scripts/p16-wo7.sh`, `tests/scripts/w2/{mutate,mkjson}.py`, `ADVERSARIAL_COVERAGE.md` (the `sbt quick` pass shows only that no Scala test there breaks).

Run, bare `sbt quick`: `[success] Total time: 147 s (0:02:27.0), completed Nov 18, 2026, 4:17:55 PM`; `Passed: Total 1304, Failed 0, Errors 0` and `Passed: Total 77, Failed 0, Errors 0`; `P16W2CostGuardSuite` JUnit `tests="34" errors="0" failures="0" skipped="0"`.

Pending: `slowSuites` until `sbt check` or the human's `sbt clean check`.
