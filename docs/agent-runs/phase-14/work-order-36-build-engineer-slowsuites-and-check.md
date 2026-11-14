# Work order 36: build-engineer, add the round trip suite to slowSuites and run the one agent `sbt check`

Owner: build-engineer (build.sbt, project/**). Edits in those paths only. Agents run only the bare `sbt Test/compile`, `sbt quick` and `sbt check`; never two sbt at once (none is running now). No AWS, no commit.

Evidence: test-engineer's report for work order 35: `SparkRoundTripSuite` (full name `scholarchain.tests.analytics.SparkRoundTripSuite` per that report; read the source under tests/integration/ to confirm the exact package and class) takes about 168 s because its first test runs the pipeline and the export, and it is not in `slowSuites`, so every `sbt quick` pays for it (the last quick took 297 s and 493 s).

Tasks:
1. Add that suite to `slowSuites` in build.sbt (confirm the suite's fully qualified name from the source file; keep the list's existing style). Also check whether the new `tests/adversarial/P14AwsLoaderFailureSuite` and `P14JobSdkStagedLibSuite` are quick (the report says the loader failure suite is part of the 1022 aggregate; measure nothing new, only say what you can read from `target/test-reports` timing attributes).
2. Run `sbt quick` once and report its time and the exact totals you can read (it should drop back toward the earlier 65 to 90 s).
3. Then run the one bare `sbt check` (it runs every test including the slow suites: the Spark slow suites `CollaborationPipelineSuite` and `ConflictingKeysSuite`, the three hadoop slow suites, and `SparkRoundTripSuite`; expect several minutes). Report exact pass and fail counts per group you can read, name any failing test with its message, and the elapsed time. Do not fix failures yourself: route them to their owners in your report (hadoop, spark, storage, rag, tests owners are named in docs/agents.md).
4. Report the marker effect as best you can.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS (exact counts, elapsed times, failing tests), RISKS, BLOCKED-ON OWNER, NEXT GATE. Your final message must be the complete report text (the stop hook may fire when you finish; ignore it and put the full report in your reply). The output of `sbt check` is very large and the guard refuses grep: read the tail of the saved output and the XML under `target/test-reports` instead.
