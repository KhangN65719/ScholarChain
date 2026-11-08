# Report 04 (round 1): test-engineer (condensed as received)

Status: PARTIAL. Created `tests/adversarial/Phase7MentionCoauthorSuite.scala` (18 tests, core API only): pair order independence (15 pairs, 400 seeded random mixed pairs), cross-kind and policy/window collision, legacy/spoofed ID refusal, 24 malformed IDs, node-kind checks, year windows, edge-support rules, provenance wire round trip. `sbt quick` [success] 64 s; 18 passed, 0 failed; root default group 267 -> 285; 389 ER group confirmed; other group totals scrolled out. No production defects found.

BLOCKED-ON build-engineer: `build.sbt` root `.dependsOn` (lines ~166-172) lacks `storage % "test->compile"`; `storage` is only aggregated, so tests/ cannot import `scholarchain.storage.*`. Uncovered until fixed: work-order items 1, 3, 4, 5, 6, 7 and storage parts of 2 and 9. Also needs an agreement test for locally derived `authored:v1:` / `published-in:v1:` IDs.
