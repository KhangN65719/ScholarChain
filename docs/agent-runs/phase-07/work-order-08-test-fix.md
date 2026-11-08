# Work order 08: test-engineer (update the Phase 6 test that pinned the legacy ID)

Evidence: `sbt check` (orchestrator, 2026-11-08, finished 04:15:24, 1116 s): `Failed: Total 521, Failed 1, Errors 0, Passed 520`; sole failure `scholarchain.tests.analytics.SparkCoauthorshipSuite` "an edge with an unresolved endpoint says so and keeps an edge ID", `tests/integration/SparkCoauthorshipSuite.scala:354`: `assert(row.text("edgeId").startsWith("coauthor:v1:"))`. This suite is a slow suite, so `sbt quick` did not run it.

Cause: Phase 7 core change (report-01) and Spark adaptation (report-02): edges with a mention endpoint now carry `mention-coauthor:v1:<64 hex>` from `StableIds.coauthorEdgeFor`. The assertion pins the retired Spark-local form.

Task: update that assertion (and any other assertion in tests/** that pins the legacy form for a mention endpoint; search by reading) to the new contract, equal or stronger: require the `mention-coauthor:v1:` prefix and 64-hex shape, equality with `StableIds.coauthorEdgeFor` for the row's endpoints and window/policy, and that resolved-pair edges keep `coauthor:v1:`. Do not weaken, delete or ignore anything. tests/** only. Run `sbt quick` once (never two sbt at once) and report; note this suite is slow, so quick will not exercise it: say so and state that the orchestrator will re-run `sbt check`. Read-only git. No commit.
