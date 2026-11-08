# Work order 05: build-engineer (test-only storage edge)

Owner: build-engineer. Output: build.sbt only.

Evidence: report-04-test-round1.md. The root project `.dependsOn(...)` (build.sbt ~lines 166-172) lists core, hadoop, entityResolution, sparkAnalytics as `% "test->compile"`. `storage` is only aggregated, so suites under tests/ cannot import `scholarchain.storage.*`.

Task: add `storage % "test->compile"` to the root `.dependsOn`, test-only, so the runtime graph and CLAUDE.md module boundaries are unchanged (storage must not depend on jobs, RAG or services; do not add any other edge). Add no dependencies, no version values. Optionally add `AwsIntegrationSuite` to `slowSuites` ONLY if it does not change quick/check semantics beyond skipping an all-ignored suite; otherwise leave it and say so. Run `sbt quick` once (never two sbt at once). Report exact counts and the diff summary. Note the human's `sbt clean check` covers build.sbt at the phase gate. Do not edit anything else. Read-only git. No commit.
