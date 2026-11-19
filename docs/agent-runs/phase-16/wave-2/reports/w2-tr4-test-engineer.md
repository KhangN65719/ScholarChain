# W2-TR4: test-engineer report (repin to `ada69809…`, top-N > 12 check)

Author: test-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE for items 1 and 3; item 2 partly covered** (grep blocked; files read by hand). One file changed; bare `sbt quick` passes.

1. Repin: `tests/adversarial/Phase9RepairSuite.scala`, the one `pinnedPrompts` literal `("synthesis","1.2.0")`: `c3d58edb…6b9d` → `ada69809dac953a6b32d96fc8ef7eb385f4c72af26fe79829e907318bbc74cd0`; the three m2 tests read that map. Verified independently: "(Phase 11)" recomputes SHA-256 with JDK `MessageDigest` against `Prompts.all` text and passed. "m2: the synthesis prompt and schema moved past the round-0 versions" (not 1.1.0) untouched and passing.
3. `sbt quick`: `[success] Total time: 158 s (0:02:38.0), completed Nov 18, 2026, 10:51:27 PM`; `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1304, Failed 0, Errors 0`. `Phase9RepairSuite` JUnit `tests="25" errors="0" failures="0" skipped="0"`. Nothing outside `tests/**` failed.
2. Top-N above 12: no test changed. Read: `Phase9BenchmarkSuite` (top 3, 5, 3, 2); `P16LabelRulesSuite` (ranking N ≤ 10; 12/20/25/30 are coauthor hub sizes vs limit 25, not `maxLimit`); `Phase9GateSuite` (none); `Phase9ModelSuite` (first 260 lines, none); `Phase9RepairSuite` "m3: every default-cap workflow input fits, none abstains on budget" (~738) asks "top 20" twice, asserts only budget fit and no `input-over-budget`, passes; now clamps to 12, so the budget check is less demanding (changing to "top 12" optional). Not read: Phase8RagSuite, Phase8EndToEndSuite, Phase10*, Phase11*, `Phase10WireSuite`, `P16W2*`, Spark analytics suites, `tests/scripts` shell scripts (all pass under `sbt quick`; a vacuous dependence would not show). A grep for `top 1[3-9]|top [2-9][0-9]` would close it.

Pending: `sbt check` (slow suites), the human's `sbt clean check`.
